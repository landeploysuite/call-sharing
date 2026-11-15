//! CallShare Core Native JNI Runtime
//! Orchestrates Audio Engine, Local LAN Networking, State Machine, and Universal AOD.
//! Implements direct ByteBuffer memory exchange (Bug 2.2), DPD Heartbeat Watchdog (Bug 1.3),
//! and guarded JNI callback dispatching with zero unwrap/expect panics.

pub mod audio;
pub mod net;
pub mod aod;
pub mod state;

use std::net::SocketAddr;
use std::sync::{Arc, OnceLock};
use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jfloat, jstring, jshortArray, jintArray};
use parking_lot::Mutex;
use tokio::runtime::Runtime;
use log::{info, warn, error};

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
static JAVA_VM: OnceLock<jni::JavaVM> = OnceLock::new();

/// Cached JavaVM initialization
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) -> jni::sys::jint {
    let _ = JAVA_VM.set(vm);
    jni::sys::JNI_VERSION_1_6
}

/// Defensive core accessor: returns None if uninitialized instead of panicking
fn get_core() -> Option<&'static AppCore> {
    GLOBAL_CORE.get()
}

// -----------------------------------------------------------------------------
// DEFENSIVE JNI CALLBACK DISPATCHERS TO KOTLIN NativeBridge
// -----------------------------------------------------------------------------

fn dispatch_java_remote_call_ringing(session_id: &str, caller_number: &str, caller_name: &str, sim_slot: i32) {
    let vm = match JAVA_VM.get() {
        Some(v) => v,
        None => return,
    };
    if let Ok(mut env) = vm.attach_current_thread() {
        if let Ok(class) = env.find_class("com/callshare/native/NativeBridge") {
            let Ok(s_id) = env.new_string(session_id) else { return; };
            let Ok(c_num) = env.new_string(caller_number) else { return; };
            let Ok(c_name) = env.new_string(caller_name) else { return; };

            let s_id_obj: JObject = s_id.into();
            let c_num_obj: JObject = c_num.into();
            let c_name_obj: JObject = c_name.into();

            let args = [
                JValue::Object(&s_id_obj),
                JValue::Object(&c_num_obj),
                JValue::Object(&c_name_obj),
                JValue::Int(sim_slot),
            ];

            let _ = env.call_static_method(
                &class,
                "onNativeRemoteCallRinging",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
                &args,
            );

            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }
    }
}

fn dispatch_java_call_connected(session_id: &str) {
    let vm = match JAVA_VM.get() {
        Some(v) => v,
        None => return,
    };
    if let Ok(mut env) = vm.attach_current_thread() {
        if let Ok(class) = env.find_class("com/callshare/native/NativeBridge") {
            let Ok(s_id) = env.new_string(session_id) else { return; };
            let s_id_obj: JObject = s_id.into();

            let _ = env.call_static_method(
                &class,
                "onNativeCallConnected",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&s_id_obj)],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }
    }
}

fn dispatch_java_call_terminated(session_id: &str, reason: &str) {
    let vm = match JAVA_VM.get() {
        Some(v) => v,
        None => return,
    };
    if let Ok(mut env) = vm.attach_current_thread() {
        if let Ok(class) = env.find_class("com/callshare/native/NativeBridge") {
            let Ok(s_id) = env.new_string(session_id) else { return; };
            let Ok(r_str) = env.new_string(reason) else { return; };

            let s_id_obj: JObject = s_id.into();
            let r_str_obj: JObject = r_str.into();

            let _ = env.call_static_method(
                &class,
                "onNativeCallTerminated",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&s_id_obj), JValue::Object(&r_str_obj)],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }
    }
}

fn dispatch_java_call_revoked(session_id: &str, reason: &str) {
    let vm = match JAVA_VM.get() {
        Some(v) => v,
        None => return,
    };
    if let Ok(mut env) = vm.attach_current_thread() {
        if let Ok(class) = env.find_class("com/callshare/native/NativeBridge") {
            let Ok(s_id) = env.new_string(session_id) else { return; };
            let Ok(r_str) = env.new_string(reason) else { return; };

            let s_id_obj: JObject = s_id.into();
            let r_str_obj: JObject = r_str.into();

            let _ = env.call_static_method(
                &class,
                "onNativeCallRevoked",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&s_id_obj), JValue::Object(&r_str_obj)],
            );
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        }
    }
}

// -----------------------------------------------------------------------------
// JNI INTERACTION IMPLEMENTATIONS
// -----------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_initNativeCore(
    env: JNIEnv,
    _class: JClass,
    device_id: JString,
    device_name: JString,
    local_ip: JString,
    carrier_name: JString,
    sim_slot: jint,
) -> jboolean {
    let _guard = CORE_INIT_LOCK.lock();

    // Cache JavaVM if not already set by JNI_OnLoad
    if JAVA_VM.get().is_none() {
        if let Ok(vm) = env.get_java_vm() {
            let _ = JAVA_VM.set(vm);
        }
    }

    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("CallShareRust"),
    );

    let dev_id: String = env.get_string(&device_id).map(|s| s.into()).unwrap_or_else(|_| "unknown-device".into());
    let dev_name: String = env.get_string(&device_name).map(|s| s.into()).unwrap_or_else(|_| "Android Phone".into());
    let ip: String = env.get_string(&local_ip).map(|s| s.into()).unwrap_or_else(|_| "0.0.0.0".into());
    let carrier: String = env.get_string(&carrier_name).map(|s| s.into()).unwrap_or_else(|_| "No SIM".into());

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
    let (discovery, mut addr_update_rx) = DiscoveryEngine::new(initial_beacon);
    let (signaling, mut inbound_rx) = SignalingEngine::new(DEFAULT_SIGNALING_PORT);
    let udp_streamer = UdpAudioStreamer::new(DEFAULT_MEDIA_PORT, audio_pipeline.clone());
    let call_coordinator = CallSessionCoordinator::new(dev_id);
    let burn_in_engine = Mutex::new(BurnInShiftEngine::new());
    let sensor_governor = Mutex::new(SensorGovernor::new());

    // Start Bug 1.3: Dead-Peer Detection (DPD) 500ms heartbeat loop
    signaling.clone().start_dpd_monitor(call_coordinator.clone(), udp_streamer.clone());

    // Start Bug 1.2: Dynamic DHCP re-allocation listener
    let sig_dhcp = signaling.clone();
    let streamer_dhcp = udp_streamer.clone();
    rt.spawn(async move {
        while let Ok(update) = addr_update_rx.recv().await {
            info!("Dynamic DHCP update propagated to signaling & media: {}", update.new_ip);
            sig_dhcp.set_peer_addr(update.signaling_addr);
            streamer_dhcp.set_remote_target(Some(update.media_addr));
        }
    });

    // Spawn background task to process incoming signaling messages & bridge to Java
    let coord = call_coordinator.clone();
    let streamer = udp_streamer.clone();
    rt.spawn(async move {
        while let Ok(msg) = inbound_rx.recv().await {
            match msg {
                SignalingMessage::CallIncoming { session_id, caller_number, caller_name, sim_slot, .. } => {
                    coord.on_remote_call_ringing(session_id.clone(), caller_number.clone(), caller_name.clone(), sim_slot);
                    dispatch_java_remote_call_ringing(&session_id, &caller_number, &caller_name, sim_slot);
                }
                SignalingMessage::CallAccept { session_id, media_port } => {
                    info!("Remote peer accepted call: {} (media port {})", session_id, media_port);
                    coord.on_local_call_answered(&session_id);
                    dispatch_java_call_connected(&session_id);
                }
                SignalingMessage::CallReject { session_id, reason } => {
                    coord.on_call_terminated(&session_id, &reason);
                    streamer.set_remote_target(None);
                    dispatch_java_call_terminated(&session_id, &reason);
                }
                SignalingMessage::CallHangup { session_id, reason } => {
                    coord.on_call_terminated(&session_id, &reason);
                    streamer.set_remote_target(None);
                    dispatch_java_call_terminated(&session_id, &reason);
                }
                SignalingMessage::CallRevoked { session_id, reason } => {
                    warn!("Host phone revoked call {}: {}", session_id, reason);
                    coord.on_call_revoked_locally(&session_id);
                    streamer.set_remote_target(None);
                    dispatch_java_call_revoked(&session_id, &reason);
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
    if let Some(core) = get_core() {
        core.discovery.clone().start();
        core.signaling.clone().start_listener();
        core.udp_streamer.clone().start_receiver();
        info!("P2P Discovery, Signaling, and UDP Audio engines started");
    } else {
        warn!("startDiscovery ignored: Core engine uninitialized");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_stopDiscovery(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(core) = get_core() {
        core.discovery.stop();
        core.signaling.stop();
        core.udp_streamer.stop();
        info!("P2P Discovery and Network services stopped");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_isApIsolated(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match get_core() {
        Some(core) => if core.discovery.is_ap_isolated() { 1 } else { 0 },
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_getDiscoveredPeersJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let beacons: Vec<PeerBeacon> = match get_core() {
        Some(core) => {
            let peers = core.discovery.get_active_peers();
            peers.into_iter().map(|p| p.beacon).collect()
        }
        None => Vec::new(),
    };
    let json_str = serde_json::to_string(&beacons).unwrap_or_else(|_| "[]".to_string());
    match env.new_string(json_str) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_connectToPeer(
    env: JNIEnv,
    _class: JClass,
    peer_ip: JString,
    signaling_port: jint,
    media_port: jint,
) -> jboolean {
    let core = match get_core() {
        Some(c) => c,
        None => return 0,
    };
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
    env: JNIEnv,
    _class: JClass,
    session_id: JString,
    caller_number: JString,
    caller_name: JString,
    sim_slot: jint,
) -> jboolean {
    let core = match get_core() {
        Some(c) => c,
        None => return 0,
    };
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

/// Bug 2.1: Host phone answered locally via Bluetooth; revoke remote relay
#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_notifyCallAnsweredLocally(
    env: JNIEnv,
    _class: JClass,
    session_id: JString,
) {
    let core = match get_core() {
        Some(c) => c,
        None => return,
    };
    let s_id: String = env.get_string(&session_id).map(|s| s.into()).unwrap_or_default();
    core.call_coordinator.on_call_revoked_locally(&s_id);
    core.signaling.send_message(SignalingMessage::CallRevoked {
        session_id: s_id,
        reason: "answered_locally_on_host".into(),
    });
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_answerRelayedCall(
    env: JNIEnv,
    _class: JClass,
    session_id: JString,
) {
    let core = match get_core() {
        Some(c) => c,
        None => return,
    };
    let s_id: String = env.get_string(&session_id).map(|s| s.into()).unwrap_or_default();
    core.call_coordinator.on_remote_call_answered(&s_id);
    core.signaling.send_message(SignalingMessage::CallAccept {
        session_id: s_id,
        media_port: DEFAULT_MEDIA_PORT,
    });
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_rejectRelayedCall(
    env: JNIEnv,
    _class: JClass,
    session_id: JString,
    reason: JString,
) {
    let core = match get_core() {
        Some(c) => c,
        None => return,
    };
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
    env: JNIEnv,
    _class: JClass,
    session_id: JString,
    reason: JString,
) {
    let core = match get_core() {
        Some(c) => c,
        None => return,
    };
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
    if let Some(core) = get_core() {
        core.audio_pipeline.set_mic_mute(muted != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_onSensorStateChanged(
    _env: JNIEnv,
    _class: JClass,
    is_near: jboolean,
) {
    if let Some(core) = get_core() {
        core.sensor_governor.lock().on_proximity_changed(is_near != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_setDeviceOrientation(
    _env: JNIEnv,
    _class: JClass,
    orientation: jint,
) {
    if let Some(core) = get_core() {
        let orient = match orientation {
            1 => DisplayOrientation::LandscapeLeft,
            2 => DisplayOrientation::LandscapeRight,
            _ => DisplayOrientation::Portrait,
        };
        core.burn_in_engine.lock().set_orientation(orient);
        core.sensor_governor.lock().set_orientation(orient);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_setAodOrientation(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
) {
    if let Some(core) = get_core() {
        core.burn_in_engine.lock().set_aod_orientation(mode as i32);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_setOrbitSpeedMultiplier(
    _env: JNIEnv,
    _class: JClass,
    multiplier: jfloat,
) {
    if let Some(core) = get_core() {
        core.burn_in_engine.lock().set_orbit_speed_multiplier(multiplier as f32);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_getBurnInOffset(
    env: JNIEnv,
    class: JClass,
) -> jintArray {
    Java_com_callshare_native_NativeBridge_getAodShiftOffset(env, class)
}

#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_getAodShiftOffset(
    mut env: JNIEnv,
    _class: JClass,
) -> jintArray {
    let (dx, dy) = match get_core() {
        Some(core) => {
            let offset = core.burn_in_engine.lock().get_offset();
            (offset.dx, offset.dy)
        }
        None => (0, 0),
    };
    let result = [dx, dy];
    match env.new_int_array(2) {
        Ok(j_arr) => {
            let _ = env.set_int_array_region(&j_arr, 0, &result);
            j_arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Bug 2.2: Zero-allocation direct ByteBuffer audio extraction preventing ART reference overflow
#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_pullPlaybackPcmDirect(
    env: JNIEnv,
    _class: JClass,
    direct_buffer: JByteBuffer,
    samples_count: jint,
) -> jint {
    let core = match get_core() {
        Some(c) => c,
        None => return 0,
    };
    let samples = core.audio_pipeline.pull_playback_pcm(samples_count as usize);
    let needed_bytes = samples.len() * 2;

    if let Ok(addr) = env.get_direct_buffer_address(&direct_buffer) {
        if let Ok(cap) = env.get_direct_buffer_capacity(&direct_buffer) {
            if cap >= needed_bytes {
                let dest_slice = unsafe { std::slice::from_raw_parts_mut(addr as *mut u8, needed_bytes) };
                for (i, sample) in samples.iter().enumerate() {
                    let bytes = sample.to_le_bytes();
                    dest_slice[i * 2] = bytes[0];
                    dest_slice[i * 2 + 1] = bytes[1];
                }
                return samples.len() as jint;
            }
        }
    }
    0
}

/// Bug 2.2: Zero-allocation direct ByteBuffer mic ingest
#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_processMicCaptureDirect(
    env: JNIEnv,
    _class: JClass,
    direct_buffer: JByteBuffer,
    bytes_count: jint,
) {
    let core = match get_core() {
        Some(c) => c,
        None => return,
    };
    if let Ok(addr) = env.get_direct_buffer_address(&direct_buffer) {
        let len = bytes_count as usize;
        let u8_slice: &[u8] = unsafe { std::slice::from_raw_parts(addr as *const u8, len) };
        if let Some(packet) = core.audio_pipeline.process_mic_capture(u8_slice) {
            let streamer = core.udp_streamer.clone();
            core.tokio_rt.spawn(async move {
                streamer.send_voice_packet(&packet).await;
            });
        }
    }
}

// Fallback legacy array APIs
#[no_mangle]
pub extern "system" fn Java_com_callshare_native_NativeBridge_processMicCapture(
    env: JNIEnv,
    _class: JClass,
    pcm_bytes: JByteArray,
) {
    let core = match get_core() {
        Some(c) => c,
        None => return,
    };
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
    mut env: JNIEnv,
    _class: JClass,
    samples_count: jint,
) -> jshortArray {
    let samples = match get_core() {
        Some(core) => core.audio_pipeline.pull_playback_pcm(samples_count as usize),
        None => Vec::new(),
    };
    match env.new_short_array(samples.len() as i32) {
        Ok(j_arr) => {
            let _ = env.set_short_array_region(&j_arr, 0, &samples);
            j_arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}
