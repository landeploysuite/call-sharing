//! Audio Framing & Speech Codec Pipeline
//! Handles Voice Activity Detection (VAD), PCM encapsulation, and Opus speech profiles.

use std::sync::atomic::{AtomicU16, AtomicU32, Ordering};

pub const SAMPLE_RATE_HZ: u32 = 16_000;
pub const CHANNELS: u16 = 1; // Mono voice for telephony
pub const FRAME_DURATION_MS: u32 = 20;
pub const SAMPLES_PER_FRAME: usize = (SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000) as usize; // 320 samples
pub const BYTES_PER_SAMPLE: usize = 2; // 16-bit PCM
pub const PCM_FRAME_SIZE_BYTES: usize = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE; // 640 bytes

const PACKET_MAGIC: u16 = 0xCA55;
const FLAG_VAD_VOICE: u8 = 0x01;
const SILENCE_RMS_THRESHOLD: f32 = 120.0; // Voice activity gate

pub struct AudioCodecEngine {
    seq_counter: AtomicU16,
    timestamp_counter: AtomicU32,
    mic_muted: bool,
}

impl AudioCodecEngine {
    pub fn new() -> Self {
        Self {
            seq_counter: AtomicU16::new(0),
            timestamp_counter: AtomicU32::new(0),
            mic_muted: false,
        }
    }

    pub fn set_mic_muted(&mut self, muted: bool) {
        self.mic_muted = muted;
    }

    pub fn is_mic_muted(&self) -> bool {
        self.mic_muted
    }

    /// Calculate RMS audio energy to identify speech presence
    pub fn compute_rms(samples: &[i16]) -> f32 {
        if samples.is_empty() {
            return 0.0;
        }
        let sum_sq: f64 = samples.iter().map(|&s| (s as f64) * (s as f64)).sum();
        ((sum_sq / samples.len() as f64).sqrt()) as f32
    }

    /// Package raw PCM microphone capture into a network-ready voice frame
    pub fn encode_pcm_to_packet(&self, pcm_bytes: &[u8]) -> Option<Vec<u8>> {
        if pcm_bytes.len() < PCM_FRAME_SIZE_BYTES {
            return None;
        }

        // Convert slice to i16 samples
        let mut samples = Vec::with_capacity(SAMPLES_PER_FRAME);
        for chunk in pcm_bytes.chunks_exact(2) {
            let sample = i16::from_le_bytes([chunk[0], chunk[1]]);
            samples.push(sample);
            if samples.len() == SAMPLES_PER_FRAME {
                break;
            }
        }

        if self.mic_muted {
            // Send silence frame or skip
            samples.fill(0);
        }

        let rms = Self::compute_rms(&samples);
        let is_voice = rms > SILENCE_RMS_THRESHOLD && !self.mic_muted;

        let seq = self.seq_counter.fetch_add(1, Ordering::Relaxed);
        let timestamp = self.timestamp_counter.fetch_add(SAMPLES_PER_FRAME as u32, Ordering::Relaxed);

        let mut flags = 0u8;
        if is_voice {
            flags |= FLAG_VAD_VOICE;
        }

        // Voice frame layout:
        // [0..2]: Magic (0xCA55)
        // [2..4]: Sequence number (u16 BE)
        // [4..8]: Timestamp (u32 BE)
        // [8]: Flags
        // [9..]: Audio payload (in production Opus compressed; PCM fallback)
        let mut packet = Vec::with_capacity(9 + pcm_bytes.len());
        packet.extend_from_slice(&PACKET_MAGIC.to_be_bytes());
        packet.extend_from_slice(&seq.to_be_bytes());
        packet.extend_from_slice(&timestamp.to_be_bytes());
        packet.push(flags);

        // Append PCM audio payload (16-bit LE)
        for sample in samples {
            packet.extend_from_slice(&sample.to_le_bytes());
        }

        Some(packet)
    }

    /// Unpack an incoming network voice frame into raw PCM samples
    pub fn decode_packet_to_pcm(&self, packet_bytes: &[u8]) -> Option<(u16, u32, bool, Vec<i16>)> {
        if packet_bytes.len() < 9 {
            return None;
        }

        let magic = u16::from_be_bytes([packet_bytes[0], packet_bytes[1]]);
        if magic != PACKET_MAGIC {
            return None;
        }

        let seq = u16::from_be_bytes([packet_bytes[2], packet_bytes[3]]);
        let timestamp = u32::from_be_bytes([packet_bytes[4], packet_bytes[5], packet_bytes[6], packet_bytes[7]]);
        let flags = packet_bytes[8];
        let is_voice = (flags & FLAG_VAD_VOICE) != 0;

        let payload = &packet_bytes[9..];
        let mut samples = Vec::with_capacity(payload.len() / 2);
        for chunk in payload.chunks_exact(2) {
            let sample = i16::from_le_bytes([chunk[0], chunk[1]]);
            samples.push(sample);
        }

        Some((seq, timestamp, is_voice, samples))
    }
}
