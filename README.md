# CallShare P2P: Offline Call-Relay & Universal AOD System

A high-performance, decentralized, peer-to-peer call bridging and management application architected with a **100% Rust Core** and **Android Telecom / NDK Bridging**. Works completely offline over local Wi-Fi (LAN only) with zero reliance on cloud servers, cellular data, or internet connectivity. Features first-class support for **Portrait and Landscape Modes** across both Call UI and Universal Always-On Display (AOD).

---

## 1. System Architecture

```
+------------------------------------------------------------------------------------+
|                                  DEVICE 1 (PHONE 1)                                |
|                                                                                    |
|  +----------------------+      +------------------------------------------------+  |
|  |  Android Telecom     | <--> |             RUST CORE (Native NDK)             |  |
|  |  (InCallService)     |      |                                                |  |
|  +----------------------+      |  - Call State Engine (Ringing / Answer / Hang) |  |
|                                |  - Audio Pipeline: Oboe/CPAL + Opus Codec      |  |
|  +----------------------+      |  - AEC / Noise Suppression / Jitter Buffer     |  |
|  | Universal AOD (Rust) | <--> |  - Local P2P Mesh (UDP Discovery + QUIC / TCP) |  |
|  | (Portrait/Landscape) |      +-----------------------+------------------------+  |
|  +----------------------+                              |                           |
+--------------------------------------------------------|---------------------------+
                                                         |
                                [ OFFLINE LOCAL WI-FI (LAN ONLY) ]
                                    Zero Internet, Direct Socket
                                - Discovery: UDP Multicast (239.255.255.250:45781)
                                - Signaling: Reliable TCP (Port 45782)
                                - Audio: Real-time Opus over UDP (Port 45783)
                                                         |
+--------------------------------------------------------|---------------------------+
|                                  DEVICE 2 (PHONE 2)    |                           |
|                                                        v                           |
|  +----------------------+      +------------------------------------------------+  |
|  |  Android Telecom     | <--> |             RUST CORE (Native NDK)             |  |
|  |  (Call UI / Bridge)  |      |                                                |  |
|  +----------------------+      |  - Dynamic Remote Audio Bridge                 |  |
|                                |  - Two-Way PCM/Opus Stream                     |  |
|  +----------------------+      |  - AOD Call Ring Overlay                       |  |
|  | Universal AOD (Rust) | <--> |  - Peer Sync & Handshake                       |  |
|  | (Portrait/Landscape) |      +------------------------------------------------+  |
|  +----------------------+                                                          |
+------------------------------------------------------------------------------------+
```

---

## 2. Key Modules & Technical Capabilities

### A. Rust Native Audio & Telecom Core (`rust-core/src/audio/`)
- **Full-Duplex Speech Engine:** 16,000 Hz wideband mono PCM stream tuned for telephony clarity with minimal bandwidth footprint.
- **Adaptive Circular Jitter Buffer (`jitter_buffer.rs`):**
  - Measures RFC 3550 inter-arrival transit jitter using exponential moving averages.
  - Dynamically stabilizes delay between 20ms and 60ms.
  - Zero-allocation circular slot storage during steady-state audio loops.
- **Packet Loss Concealment (PLC):**
  - When network jitter causes dropped frames, the engine synthesizes an extrapolated waveform with exponential decay ($0.78\times$ factor), eliminating clicks, digital distortion, and harsh pops.
- **Voice Activity Detection (VAD) & Energy Gate (`opus_codec.rs`):**
  - Real-time RMS audio energy analysis to identify speech vs silence and mute states.

### B. Offline Local P2P Protocol (`rust-core/src/net/`)
- **UDP Discovery Service (`discovery.rs`):**
  - Runs on port `45781` using subnet broadcast (`255.255.255.255`) and multicast (`239.255.255.250`).
  - Exchanges device metadata: device name, local IP address, signaling and media ports, SIM status, carrier name, battery level, and charging state.
  - Automatic peer table maintenance with 4-second expiration pruning.
- **Reliable Call Control Signaling (`signaling.rs`):**
  - Runs on TCP port `45782`.
  - Commands: `CallIncoming`, `CallAccept`, `CallReject`, `CallHangup`, `MicMute`, `Hold`, `BatteryUpdate`, `Ping`, `Pong`.
  - Re-handshake mechanism resumes active sessions within 1.5 seconds if Wi-Fi drops momentarily.
- **Low-Latency Media Streamer (`udp_stream.rs`):**
  - Direct UDP socket streaming on port `45783`.
  - Transmits 20ms audio frames with 16-bit sequence numbering, 32-bit timestamps, and VAD flags.

### C. Universal Software Always-On Display (AOD) (`rust-core/src/aod/` & `AodActivity.kt`)
- **Hardware-Agnostic Display Engine:**
  - True `#000000` pitch black pixels for AMOLED energy conservation.
  - Software backlight dimming (`screenBrightness = 0.01f`) for IPS LCD and TFT screens.
- **Burn-In Pixel Shifting (`burn_in_shift.rs`):**
  - Translates display elements by $\pm2$ to $\pm10$ pixels using a closed Lissajous curve orbit every 60 seconds to prevent OLED burn-in and LCD image retention.
- **Proximity Sensor Power Gate (`sensor_gate.rs`):**
  - Listens to `Sensor.TYPE_PROXIMITY`.
  - Automatically blanks the screen completely when inside pockets or placed face-down on a desk, saving 100% display battery.
- **Dynamic Refresh Rate Throttling:**
  - Throttles UI loop to 1 FPS for ambient clock and battery monitoring.
  - Automatically ramps up to 30 FPS smooth pulse animation when an incoming call alert is triggered.
- **Full Landscape Mode Support:**
  - Automatically detects horizontal orientation (`Configuration.ORIENTATION_LANDSCAPE`) when docked on a nightstand or charging cradle.
  - Renders large ambient clock on the left pane and peer mesh status + call controls on the right pane.
  - Adapts Lissajous burn-in displacement axes for widescreen aspect ratios.

### D. Android Telephony & Permissions Bridge (`android-app/`)
- **`CallShareInCallService.kt`:**
  - Subclasses Android Telecom `InCallService`.
  - Catches cellular carrier calls (`STATE_RINGING`), broadcasts them over offline Wi-Fi to the peer device, and invokes `Call.answer()` or `Call.reject()` when triggered from either phone.
- **`AudioRouteManager.kt`:**
  - Configures `AudioManager.mode = MODE_IN_COMMUNICATION`.
  - Acquires exclusive transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`).
  - Routes audio to `TYPE_COMMUNICATION_DEVICE` or speakerphone.
  - Drives high-priority recording and playback coroutines linked directly to Rust native JNI.

---

## 3. Directory Structure

```
call-sharing/
├── prompt.md                           # Master specification & prompt file
├── README.md                           # Project overview, architecture, and guides
├── rust-core/                          # Native Rust Engine (NDK Library)
│   ├── Cargo.toml                      # Dependencies & CDYLIB configuration
│   └── src/
│       ├── lib.rs                      # JNI exports and Tokio runtime coordinator
│       ├── audio/
│       │   ├── mod.rs
│       │   ├── jitter_buffer.rs        # Dynamic adaptive jitter buffer & PLC
│       │   ├── opus_codec.rs           # Voice framing, VAD, and PCM codec
│       │   └── oboe_bridge.rs          # Audio stream coordinator & queue management
│       ├── net/
│       │   ├── mod.rs
│       │   ├── discovery.rs            # Offline LAN UDP broadcast & multicast beacon
│       │   ├── signaling.rs            # TCP call control and state handshake
│       │   └── udp_stream.rs           # Raw UDP voice frame transmitter & receiver
│       ├── aod/
│       │   ├── mod.rs
│       │   ├── burn_in_shift.rs        # Pixel-shifting anti-burn-in engine (Portrait + Landscape)
│       │   └── sensor_gate.rs          # Proximity & 1-FPS/30-FPS power manager
│       └── state/
│           ├── mod.rs
│           └── session.rs              # Symmetrical call state machine
└── android-app/                        # Android App (Kotlin / Jetpack / Telecom)
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle.properties
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/callshare/
            │   ├── CallShareApp.kt     # MulticastLock & native initialization
            │   ├── native/
            │   │   └── NativeBridge.kt # JNI binding interface
            │   ├── telecom/
            │   │   ├── CallShareInCallService.kt
            │   │   └── AudioRouteManager.kt
            │   └── ui/
            │       ├── MainActivity.kt # Dashboard & peer mesh monitor
            │       ├── AodActivity.kt  # Universal Software AOD (Portrait & Landscape)
            │       └── CallActivity.kt # Active in-call UI (Portrait & Landscape)
            └── res/
                ├── drawable/           # Vector icons (Answer, End, Mic, Speaker)
                ├── layout/             # Portrait layouts (Main, AOD, Call)
                ├── layout-land/        # Landscape layouts (Main, AOD, Call)
                └── values/             # Colors (AMOLED Black), Strings, Styles
```

---

## 4. How to Get the APK Without Installing Android Studio

You **do not need to install Android Studio**. Android Studio is a heavy IDE (10GB+), and you can build and obtain the APK completely in the cloud or via standalone tools:

### Method 1: Free Cloud Auto-Build via GitHub Actions (Zero Local Installs)
A ready-to-run GitHub Actions workflow has been added at `.github/workflows/build-apk.yml`.
1. Initialize a git repo and push this folder to your GitHub:
   ```bash
   cd "D:\ABHAY PROJECTS\call-sharing"
   git init
   git add .
   git commit -m "Initial CallShare P2P commit"
   git branch -M main
   git remote add origin https://github.com/<your-username>/call-sharing.git
   git push -u origin main
   ```
2. Open your repository on GitHub in any browser:
   - Go to the **Actions** tab.
   - You will see the **"Build CallShare P2P APK"** workflow running on GitHub's cloud servers.
   - Once it finishes (~2 minutes), click on the build run.
   - Under **Artifacts**, download `CallShare-P2P-Debug-APK.zip`.
3. Unzip the file to get `app-debug.apk`. Transfer it to your phones (via USB, Google Drive, WhatsApp, or local Wi-Fi share) and tap **Install**.

---

## 5. Offline PC Test Harness & Simulator (Runs on Python 3.x)

To test the offline Wi-Fi P2P protocol, UDP discovery beacons, and call signaling directly from your PC without installing anything new:

```bash
cd "D:\ABHAY PROJECTS\call-sharing"

# 1. Listen for beacons from phones on your Wi-Fi:
python test_harness.py --discover

# 2. Broadcast as a simulated phone beacon on your local Wi-Fi:
python test_harness.py --simulate-phone

# 3. Trigger a simulated incoming call to an Android phone's AOD:
python test_harness.py --send-call <PHONE_LOCAL_IP> --caller-name "Work Boss"
```

---

## 6. Offline LAN Testing & Verification

1. **Connect Both Phones to Same Wi-Fi Network:**
   - The Wi-Fi router does **not** need internet access (offline local LAN works 100%).
   - You can even use a portable battery-powered travel router or one phone's offline Wi-Fi hotspot without mobile data.
2. **Grant Permissions on Both Phones:**
   - Open CallShare P2P.
   - Accept Audio Recording, Phone State, and Notification permissions.
   - Click **"Grant InCall Companion Role"** to register CallShare as an eligible call companion.
3. **Peer Auto-Discovery:**
   - Both phones will announce their beacons on port `45781`.
   - The dashboard will display the connected peer device's IP, carrier name, and battery level within 1 second.
4. **Relaying a Cellular Call:**
   - Place a cellular phone call to Phone 1's SIM.
   - Phone 1 captures the call and dispatches `CALL_INCOMING` over Wi-Fi.
   - Phone 2 immediately displays the **Universal AOD Incoming Call Alert** (in Portrait or Landscape mode) showing Phone 1's Caller ID.
   - Click **Answer** on Phone 2:
     - Phone 1 answers the cellular call via Android Telecom.
     - Ultra-low latency voice bridge begins streaming bidirectional Opus audio (<35ms latency).
5. **Landscape Dock Mode Testing:**
   - Rotate Phone 2 horizontally on a charging stand or nightstand.
   - The display automatically transitions to Landscape AOD layout with the large ambient clock and horizontal call controls.
   - Place the phone face-down to verify proximity sensor screen blanking.
