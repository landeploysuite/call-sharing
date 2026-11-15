# MASTER SPECIFICATION & IMPLEMENTATION PROMPT: RUST P2P OFFLINE CALL-RELAY & UNIVERSAL AOD SYSTEM

> **Target Project:** Decentralized, Offline Wi-Fi Telephony Bridging & Universal AOD  
> **Primary Technology Stack:** 100% Rust Core (Audio Engine, Networking, P2P Protocol, State Machine) with Android Telecom/NDK Bridging  
> **Output Target:** `D:\ABHAY PROJECTS\call-sharing`

---

## 1. PROJECT OVERVIEW & GOAL

You are tasked with building a high-performance, REAL TIME, peer-to-peer call bridging and management application written primarily in **Rust**, WHICH ALSO HAVING THE LANDESCAPE MODE.

### The Scenario
A user possesses two physical Android smartphones:
- **Phone 1 (Host/Remote):** Holds SIM A.
- **Phone 2 (Host/Remote):** Holds SIM B.
- Both phones reside on the **same local Wi-Fi network with NO active Internet connection (LAN-only)**.
- When an incoming cellular call (GSM / VoLTE / 5G) hits Phone 1, the user can immediately answer, listen, and speak via Phone 2 seamlessly with ultra-low latency (<40ms jitter-controlled budget).
- The architecture is **symmetrical and multi-directional**: Phone 2 can relay incoming calls to Phone 1 just as easily, and either device can initiate outgoing calls through the partner device's SIM.
- The application includes a **Universal Software AOD (Always-On Display)** system optimized to run on devices without hardware AOD support (LCD and OLED), featuring burn-in prevention, sensor sleep, and instant incoming call alerts.

---

## 2. SYSTEM ARCHITECTURE & CRITICAL CHALLENGES

```
+-----------------------------------------------------------------------------------+
|                                  DEVICE 1 (PHONE 1)                               |
|                                                                                   |
|  +---------------------+      +------------------------------------------------+  |
|  |  Android Telecom    | <--> |            RUST CORE (Native NDK)              |  |
|  |  (InCallService)    |      |                                                |  |
|  +---------------------+      |  - Call State Engine (Ringing / Answer / Hang) |  |
|                               |  - Audio Pipeline: Oboe/CPAL + Opus Codec      |  |
|  +---------------------+      |  - AEC / Noise Suppression / Jitter Buffer     |  |
|  | Universal AOD (Rust)| <--> |  - Local P2P Mesh (UDP Discovery + QUIC / UDP) |  |
|  +---------------------+      +-----------------------+------------------------+  |
+-------------------------------------------------------|---------------------------+
                                                        |
                               [ OFFLINE LOCAL WI-FI (LAN ONLY) ]
                                   Zero Internet, Direct Socket
                               - Signaling: UDP Multicast / QUIC
                               - Audio: Low-latency Opus over UDP
                                                        |
+-------------------------------------------------------|---------------------------+
|                                  DEVICE 2 (PHONE 2)   |                           |
|                                                       v                           |
|  +---------------------+      +------------------------------------------------+  |
|  |  Android Telecom    | <--> |            RUST CORE (Native NDK)              |  |
|  |  (Call UI / Bridge) |      |                                                |  |
|  +---------------------+      |  - Dynamic Remote Audio Bridge                 |  |
|                               |  - Two-Way PCM/Opus Stream                     |  |
|  +---------------------+      |  - AOD Call Ring Overlay                       |  |
|  | Universal AOD (Rust)| <--> |  - Peer Sync & Handshake                       |  |
|  +---------------------+      +------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

### Known Obstacles to Overcome:
1. **Audio Routing & Voice Drops (The "No Voice" Bug):**
   - Direct audio stream isolation on modern Android (Android 10+) strictly restricts third-party apps from capturing in-call cellular downstream audio unless integrated with `InCallService` or `VOICE_COMMUNICATION` audio routing via Oboe/AAudio with `AudioDeviceInfo.TYPE_TELEPHONY`.
   - The Rust audio pipeline must handle audio capture mode fallback gracefully (Communication stream vs. Telephony stream vs. mic injection).
2. **Offline Local Discovery:**
   - No cloud signaling, STUN, TURN, or WebRTC signaling servers available.
   - Must use local UDP Broadcast / Multicast (`239.255.255.250` or subnet broadcast) with cryptographic token pairing.
3. **Ultra-Low Latency Audio:**
   - Must use modern low-delay audio compression (**Opus Speech Mode**, 10ms–20ms frames) over UDP with dynamic packet loss concealment (PLC) and an adaptive jitter buffer.
4. **Universal Software AOD:**
   - True black layout (`#000000`) for OLED power savings.
   - Software backlight dimming and clock pixel shifting (anti-burn-in) for IPS LCD / TFT panels.
   - Proximity sensor auto-blanking to avoid battery drain when face-down or inside pockets.

---

## 3. CORE MODULE SPECIFICATIONS

### MODULE A: Rust Native Audio & Telecom Core (`telecom_engine`)
- **Technology:** Rust with `oboe` / `cpal` / `aaudio` bindings.
- **Audio Capture & Playback:**
  - Full-duplex audio stream via AAudio / OpenSL ES.
  - Sample Rate: 48,000 Hz or 16,000 Hz (wideband voice).
  - Channels: Mono (optimized for voice transmission).
  - Codec: `audiopus` or `opus` crate in VOIP mode (`OPUS_APPLICATION_VOIP`), 16–24 kbps bitrate, frame size 10ms or 20ms.
- **Acoustic Echo Cancellation (AEC) & Pre-processing:**
  - Integrated acoustic echo cancellation and software gain control (using `webrtc-audio-processing` or native Android system AEC via `AudioEffect.AcousticEchoCanceler`).
  - Adaptive jitter buffer to compensate for erratic Wi-Fi signal without blowing up latency (target < 35ms end-to-end latency).

### MODULE B: Offline Local P2P Protocol (`lan_mesh`)
- **Discovery Service:**
  - UDP broadcast listener & beacon running on high port (e.g., `45781`).
  - Encrypted beacon payload containing: Device Name, Device Unique ID, SIM State, Rust Core Protocol Version, Public Ephemeral ECDH Key.
- **Control Channel (Signaling):**
  - Peer-to-peer authenticated channel using QUIC (`quinn` crate) or direct TCP with Noise Protocol / ChaCha20-Poly1305.
  - Commands:
    - `CALL_INCOMING(caller_id, sim_slot, timestamp)`
    - `CALL_ACCEPT(session_id)`
    - `CALL_REJECT(session_id)`
    - `CALL_TERMINATED(session_id, reason)`
    - `MIC_MUTE(state)` / `HOLD(state)`
    - `BATTERY_SYNC(level)`
- **Media Channel (Real-time Audio):**
  - High-priority raw UDP packet streaming with 16-bit sequence numbering, timestamp, and Opus payload.
  - Zero buffering on transmission; adaptive jitter buffer on receiver side.
  - Heartbeat & ping-pong RTT tracking every 500ms.

### MODULE C: Android Telephony & Permissions Bridge (`android_telephony`)
- **InCallService Implementation:**
  - Register app as an eligible call companion / call screening / dialer service using Android Telecom Framework (`android.telecom.InCallService`).
  - Listen for call states: `STATE_RINGING`, `STATE_DIALING`, `STATE_ACTIVE`, `STATE_DISCONNECTED`.
  - When Phone 1 receives `STATE_RINGING`, the Rust core instantly dispatches `CALL_INCOMING` over the local Wi-Fi to Phone 2.
  - When Phone 2 clicks "Answer", it notifies Phone 1 via local QUIC; Phone 1 calls `Call.answer(VideoProfile.STATE_AUDIO_ONLY)` and hooks the audio stream to the Rust UDP transmitter.
- **Audio Routing Engine:**
  - Set Android `AudioManager.mode = MODE_IN_COMMUNICATION`.
  - Route audio through `AudioDeviceInfo.TYPE_COMMUNICATION_DEVICE` or speakerphone fallback.

### MODULE D: Universal Always-On Display Engine (`universal_aod`)
- **Hardware-Agnostic AOD:**
  - Works on any phone, including budget LCD panels and older OLED screens.
  - **AMOLED Mode:** Complete `#000000` pitch black pixels. Only rendering: Minimalistic digital clock, battery indicator, Wi-Fi mesh status, and animated incoming call notification.
  - **Pixel Shifting Protection:** Displaces all text and UI elements by 2–4 pixels every 60 seconds to completely eliminate screen burn-in.
  - **IPS LCD Battery Optimizer:**
    - Lowers hardware backlight brightness via `WindowManager.LayoutParams.screenBrightness = 0.01f`.
    - Throttles UI refresh rate to 1 FPS for clock updates, transitioning to 30 FPS only when an incoming call alert is triggered.
  - **Proximity Sensor Blanker:**
    - Listens to `Sensor.TYPE_PROXIMITY`.
    - If proximity is covered (pocket or face-down), the screen turns completely off (`FLAG_KEEP_SCREEN_ON` released or blank view) to preserve 100% battery.

---

## 4. DETAILED IMPLEMENTATION PROMPT TO RUN

```markdown
You are an expert Systems and Embedded Android Engineer specializing in Rust, NDK, low-latency audio processing, and networking.

Build a complete, production-grade Android application entirely architected in Rust (using Cargo, JNI / NDK, and modern Rust crates), entitled "CallShare P2P".

### Key Deliverables & Structure:

1. **`Cargo.toml` Workspace Configuration:**
   - Set up crates: `tokio` (full), `quinn` or `tokio-tungstenite`, `audiopus` or `opus`, `cpal` or `oboe`, `socket2`, `ring` / `chacha20poly1305`, `jni`, `log`, `android_logger`.

2. **Native Rust Core (`src/lib.rs`, `src/audio/`, `src/net/`, `src/protocol/`):**
   - `src/audio/recorder.rs`: Audio capture pipeline utilizing Oboe/AAudio with voice communication flags and Opus encoding.
   - `src/audio/player.rs`: Low-latency audio playback pipeline with adaptive jitter buffer and PLC.
   - `src/audio/jitter_buffer.rs`: Custom zero-allocation circular jitter buffer designed specifically to combat Wi-Fi packet drops.
   - `src/net/discovery.rs`: UDP broadcast discovery engine running over offline Wi-Fi without internet access.
   - `src/net/signaling.rs`: Reliable control socket for call handshake (Incoming, Accept, Decline, Hangup).
   - `src/net/streamer.rs`: Real-time UDP audio packet transmitter and receiver.
   - `src/state/call_manager.rs`: Symmetrical call coordinator (handles both incoming local SIM calls and incoming relayed calls).

3. **Android Platform Integration (JNI & Services):**
   - `CallShareInCallService.kt`: Telephony `InCallService` linking Android Telecom events to Rust via JNI calls (`onCallAdded`, `onCallRemoved`, `answerCall`, `rejectCall`).
   - `AudioRouteManager.kt`: Native audio stream switcher ensuring clean mic and speaker transmission without Android OS ducking or voice cancellation bugs.
   - `AodActivity.kt` & `src/ui/aod_renderer.rs`: High-efficiency, burn-in safe Always-On Display engine with 1-FPS sleep loop, pixel-shifter, proximity sensor screen gating, and ambient call acceptance overlay.

4. **Fault Tolerance & Edge Case Handling:**
   - Wi-Fi signal drop / reconnect mid-call: Automatic re-handshake within 1.5 seconds.
   - Simultaneous incoming calls on both phones: Priority conflict resolution.
   - Audio feedback / howling prevention: Hardware AEC detection and digital high-pass voice filter.

Generate fully implemented, robust, and clean source code for the core modules, accompanied by an exact setup guide (Android NDK toolchains, AndroidManifest permissions, and Cargo build commands).
```

---

## 5. REQUIRED ANDROID MANIFEST PERMISSIONS & SERVICES

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Telephony & Call Control Permissions -->
    <uses-permission android:name="android.permission.MANAGE_OWN_CALLS" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
    <uses-permission android:name="android.permission.CALL_PHONE" />

    <!-- Audio & Hardware Permissions -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Local Offline Network Permissions -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

    <!-- AOD & Display Permissions -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />

    <application
        android:allowBackup="true"
        android:label="CallShare"
        android:theme="@style/Theme.CallShare.AOD">

        <!-- Telephony InCallService -->
        <service
            android:name=".telecom.CallShareInCallService"
            android:permission="android.permission.BIND_INCALL_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.telecom.InCallService" />
            </intent-filter>
        </service>

        <!-- AOD Overlay Activity -->
        <activity
            android:name=".ui.AodActivity"
            android:showOnLockScreen="true"
            android:screenOrientation="portrait"
            android:turnScreenOn="true"
            android:showWhenLocked="true"
            android:exported="true" />
    </application>
</manifest>
```

---

## 6. PROJECT DIRECTORY BLUEPRINT

When generating and organizing files in `D:\ABHAY PROJECTS\call-sharing`:

```
call-sharing/
├── prompt.md                       <-- This Master Prompt & Specification File
├── README.md                       <-- Project overview, architecture, and setup instructions
├── rust-core/                      <-- Complete Rust Native Core
│   ├── Cargo.toml
│   ├── src/
│   │   ├── lib.rs                  <-- JNI entrypoints & runtime orchestrator
│   │   ├── audio/
│   │   │   ├── mod.rs
│   │   │   ├── oboe_bridge.rs      <-- Full-duplex low-latency audio capture/playback
│   │   │   ├── opus_codec.rs       <-- Voice-tuned Opus compression/decompression
│   │   │   └── jitter_buffer.rs    <-- Dynamic adaptive jitter buffer & PLC
│   │   ├── net/
│   │   │   ├── mod.rs
│   │   │   ├── discovery.rs        <-- Offline LAN UDP broadcast beacon & listener
│   │   │   ├── signaling.rs        <-- QUIC/TCP call state management
│   │   │   └── udp_stream.rs       <-- Raw high-speed voice packet streaming
│   │   ├── aod/
│   │   │   ├── mod.rs
│   │   │   ├── burn_in_shift.rs    <-- Pixel-shifting engine for display longevity
│   │   │   └── sensor_gate.rs      <-- Proximity & ambient sensor power manager
│   │   └── state/
│   │       ├── mod.rs
│   │       └── session.rs          <-- Bidirectional call state machine
└── android-app/                    <-- Android Shell (Kotlin / NDK Bridge)
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/com/callshare/
    │       │   ├── telecom/
    │       │   │   ├── CallShareInCallService.kt
    │       │   │   └── AudioRouteManager.kt
    │       │   └── ui/
    │       │       └── AodActivity.kt
    │       └── res/
```
