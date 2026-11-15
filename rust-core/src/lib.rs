//! CallShare Core Native JNI Runtime
//! Orchestrates Audio Engine, Local LAN Networking, State Machine, and Universal AOD.

pub mod audio;
pub mod net;
pub mod aod;
pub mod state;

use std::net::SocketAddr;
use std::sync::{Arc, OnceLock};
use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray};
use jni::sys::{jboolean, jint, jstring, jshortArray, jintArray};
use parking_lot::Mutex;
use tokio::runtime::Runtime;
use log::{info, error};

use crate::audio::AudioPipeline;
use crate::net::{DiscoveryEngine, PeerBeacon, SignalingEngine, SignalingMessage, UdpAudioStreamer, DEFAULT_SIGNALING_PORT, DEFAULT_MEDIA_PORT};
use crate::aod::{BurnInShiftEngine, SensorGovernor, DisplayOrientation};
use crate::state::CallSessionCoordinator;

struct AppCore {
    tokio_rt: Runtime,
    audio_pipeline: Arc<AudioPipeline>,
    discovery: Arc<DiscoveryEngine>,
    signaling: Arc<SignalingEngine>,
    udp_streamer: Arc<UdpAudioStreamer>,
    call_coordinator: Arc<CallSessionCoordinator>,
    burn_in_engine: Mutex<BurnInShiftEngine>,
    sensor_governor: Mutex<SensorGovernor>,
}

static GLOBAL_CORE: OnceLock<AppCore> = OnceLock::new();
static CORE_INIT_LOCK: Mutex<()> = Mutex::new(());

fn get_core() -> &'static AppCore {
    GLOBAL_CORE.get().expect("CallShare Core engine is not initialized!")
}

// -----------------------------------------------------------------------------
// JNI INTERACTION IMPLEMENTATIONS
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_initNativeCore(
    mut env: JNIEnv,
    _class: JClass,
    device_id: JString,
    device_name: JString,
    local_ip: JString,
    carrier_name: JString,
    sim_slot: jint,
) -> jboolean {
    let _guard = CORE_INIT_LOCK.lock();

    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("CallShareRust"),
    );

    let dev_id: String = match env.get_string(&device_id) {
        Ok(s) => s.into(),
        Err(_) => "unknown-device".into(),
    };
    let dev_name: String = match env.get_string(&device_name) {
        Ok(s) => s.into(),
        Err(_) => "Android Phone".into(),
    };
    let ip: String = match env.get_string(&local_ip) {
        Ok(s) => s.into(),
        Err(_) => "0.0.0.0".into(),
    };
    let carrier: String = match env.get_string(&carrier_name) {
        Ok(s) => s.into(),
        Err(_) => "No SIM".into(),
    };

    let rt = match Runtime::new() {
        Ok(r) => r,
        Err(e) => {
            error!("Failed to create Tokio Runtime: {:?}", e);
            return 0;
        }
    };

    let initial_beacon = PeerBeacon {
        device_id: dev_id.clone(),
        device_name: dev_name.clone(),
        ip,
        signaling_port: DEFAULT_SIGNALING_PORT,
        media_port: DEFAULT_MEDIA_PORT,
        sim_active: sim_slot >= 0,
        sim_slot,
        carrier_name: carrier,
        battery_pct: 100,
        is_charging: false,
        protocol_version: 1,
    };

    let audio_pipeline = AudioPipeline::new();
    let discovery = DiscoveryEngine::new(initial_beacon);
    let (signaling, mut inbound_rx) = SignalingEngine::new(DEFAULT_SIGNALING_PORT);
    let udp_streamer = UdpAudioStreamer::new(DEFAULT_MEDIA_PORT, audio_pipeline.clone());
    let call_coordinator = CallSessionCoordinator::new(dev_id);
    let burn_in_engine = Mutex::new(BurnInShiftEngine::new());
    let sensor_governor = Mutex::new(SensorGovernor::new());

    // Spawn background task to process incoming signaling messages
    let coord = call_coordinator.clone();
    let streamer = udp_streamer.clone();
    rt.spawn(async move {
        while let Ok(msg) = inbound_rx.recv().await {
            match msg {
                SignalingMessage::CallIncoming { session_id, caller_number, caller_name, sim_slot, .. } => {
                    coord.on_remote_call_ringing(session_id, caller_number, caller_name, sim_slot);
                }
                SignalingMessage::CallAccept { session_id, media_port } => {
                    info!("Remote peer accepted call: {} (media port {})", session_id, media_port);
                    coord.on_local_call_answered(&session_id);
                }
                SignalingMessage::CallReject { session_id, reason } => {
                    coord.on_call_terminated(&session_id, &reason);
                    streamer.set_remote_target(None);
                }
                SignalingMessage::CallHangup { session_id, reason } => {
                    coord.on_call_terminated(&session_id, &reason);
                    streamer.set_remote_target(None);
                }
                _ => {}
            }
        }
    });

    let _ = GLOBAL_CORE.set(AppCore {
        tokio_rt: rt,
        audio_pipeline,
        discovery,
        signaling,
        udp_streamer,
        call_coordinator,
        burn_in_engine,
        sensor_governor,
    });

    info!("CallShare Core native engine initialized successfully for: {}", dev_name);
    1
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_startDiscovery(
    _env: JNIEnv,
    _class: JClass,
) {
    let core = get_core();
    core.discovery.clone().start();
    core.signaling.clone().start_listener();
    core.udp_streamer.clone().start_receiver();
    info!("P2P Discovery, Signaling, and UDP Audio engines started");
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_stopDiscovery(
    _env: JNIEnv,
    _class: JClass,
) {
    let core = get_core();
    core.discovery.stop();
    core.signaling.stop();
    core.udp_streamer.stop();
    info!("P2P Discovery and Network services stopped");
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_getDiscoveredPeersJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let core = get_core();
    let peers = core.discovery.get_active_peers();
    let beacons: Vec<PeerBeacon> = peers.into_iter().map(|p| p.beacon).collect();
    let json_str = serde_json::to_string(&beacons).unwrap_or_else(|_| "[]".into());
    env.new_string(json_str).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_connectToPeer(
    mut env: JNIEnv,
    _class: JClass,
    peer_ip: JString,
    signaling_port: jint,
    media_port: jint,
) -> jboolean {
    let core = get_core();
    let ip_str: String = match env.get_string(&peer_ip) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    if let Ok(sig_addr) = format!("{}:{}", ip_str, signaling_port).parse::<SocketAddr>() {
        core.signaling.set_peer_addr(sig_addr);
    }
    if let Ok(media_addr) = format!("{}:{}", ip_str, media_port).parse::<SocketAddr>() {
        core.udp_streamer.set_remote_target(Some(media_addr));
    }

    info!("Connected P2P peer target: {}:{} / media {}", ip_str, signaling_port, media_port);
    1
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_notifyLocalCallRinging(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    caller_number: JString,
    caller_name: JString,
    sim_slot: jint,
) -> jboolean {
    let core = get_core();
    let s_id: String = env.get_string(&session_id).map(|s| s.into()).unwrap_or_default();
    let c_num: String = env.get_string(&caller_number).map(|s| s.into()).unwrap_or_default();
    let c_name: String = env.get_string(&caller_name).map(|s| s.into()).unwrap_or_default();

    let accepted = core.call_coordinator.on_local_call_ringing(
        s_id.clone(),
        c_num.clone(),
        c_name.clone(),
        sim_slot,
    );

    if accepted {
        // Dispatch over local LAN signaling to peer device
        core.signaling.send_message(SignalingMessage::CallIncoming {
            session_id: s_id,
            caller_number: c_num,
            caller_name: c_name,
            sim_slot,
            timestamp: 0,
        });
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_answerRelayedCall(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) {
    let core = get_core();
    let s_id: String = env.get_string(&session_id).map(|s| s.into()).unwrap_or_default();
    core.call_coordinator.on_remote_call_answered(&s_id);
    core.signaling.send_message(SignalingMessage::CallAccept {
        session_id: s_id,
        media_port: DEFAULT_MEDIA_PORT,
    });
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_rejectRelayedCall(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    reason: JString,
) {
    let core = get_core();
    let s_id: String = env.get_string(&session_id).map(|s| s.into()).unwrap_or_default();
    let r_str: String = env.get_string(&reason).map(|s| s.into()).unwrap_or_else(|_| "rejected".into());
    core.call_coordinator.on_call_terminated(&s_id, &r_str);
    core.signaling.send_message(SignalingMessage::CallReject {
        session_id: s_id,
        reason: r_str,
    });
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_hangupCall(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    reason: JString,
) {
    let core = get_core();
    let s_id: String = env.get_string(&session_id).map(|s| s.into()).unwrap_or_default();
    let r_str: String = env.get_string(&reason).map(|s| s.into()).unwrap_or_else(|_| "hangup".into());
    core.call_coordinator.on_call_terminated(&s_id, &r_str);
    core.audio_pipeline.reset_buffers();
    core.signaling.send_message(SignalingMessage::CallHangup {
        session_id: s_id,
        reason: r_str,
    });
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_setMicMute(
    _env: JNIEnv,
    _class: JClass,
    muted: jboolean,
) {
    let core = get_core();
    core.audio_pipeline.set_mic_mute(muted != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_onSensorStateChanged(
    _env: JNIEnv,
    _class: JClass,
    is_near: jboolean,
) {
    let core = get_core();
    core.sensor_governor.lock().on_proximity_changed(is_near != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_setDeviceOrientation(
    _env: JNIEnv,
    _class: JClass,
    orientation: jint,
) {
    let core = get_core();
    let orient = match orientation {
        1 => DisplayOrientation::LandscapeLeft,
        2 => DisplayOrientation::LandscapeRight,
        _ => DisplayOrientation::Portrait,
    };
    core.burn_in_engine.lock().set_orientation(orient);
    core.sensor_governor.lock().set_orientation(orient);
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_getAodShiftOffset(
    env: JNIEnv,
    _class: JClass,
) -> jintArray {
    let core = get_core();
    let offset = core.burn_in_engine.lock().get_offset();
    let result = [offset.dx, offset.dy];
    let j_arr = env.new_int_array(2).expect("Failed to allocate shift offset int array");
    env.set_int_array_region(&j_arr, 0, &result).expect("Failed to fill shift offset array");
    j_arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_processMicCapture(
    env: JNIEnv,
    _class: JClass,
    pcm_bytes: JByteArray,
) {
    let core = get_core();
    let len = match env.get_array_length(&pcm_bytes) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut buffer = vec![0i8; len];
    if env.get_byte_array_region(&pcm_bytes, 0, &mut buffer).is_ok() {
        let u8_slice: &[u8] = unsafe { std::slice::from_raw_parts(buffer.as_ptr() as *const u8, len) };
        if let Some(packet) = core.audio_pipeline.process_mic_capture(u8_slice) {
            let streamer = core.udp_streamer.clone();
            core.tokio_rt.spawn(async move {
                streamer.send_voice_packet(&packet).await;
            });
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_pullPlaybackPcm(
    env: JNIEnv,
    _class: JClass,
    samples_count: jint,
) -> jshortArray {
    let core = get_core();
    let samples = core.audio_pipeline.pull_playback_pcm(samples_count as usize);
    let j_arr = env.new_short_array(samples.len() as i32).expect("Failed to allocate playback short array");
    env.set_short_array_region(&j_arr, 0, &samples).expect("Failed to fill playback short array");
    j_arr.into_raw()
}
