//! Audio Engine Pipeline & Oboe/AAudio Stream Coordinator
//! Connects low-latency audio capture and playback with the jitter buffer and network streamer.

use std::sync::Arc;
use parking_lot::Mutex;
use crate::audio::jitter_buffer::{AdaptiveJitterBuffer, PlaybackFrame};
use crate::audio::opus_codec::{AudioCodecEngine, SAMPLES_PER_FRAME};

pub struct AudioPipeline {
    codec: Mutex<AudioCodecEngine>,
    jitter_buffer: Mutex<AdaptiveJitterBuffer>,
    playback_queue: Mutex<Vec<i16>>,
}

impl AudioPipeline {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            codec: Mutex::new(AudioCodecEngine::new()),
            jitter_buffer: Mutex::new(AdaptiveJitterBuffer::new(20.0, 80.0)),
            playback_queue: Mutex::new(Vec::with_capacity(SAMPLES_PER_FRAME * 4)),
        })
    }

    /// Process microphone audio captured from Android AAudio / Oboe / AudioRecord
    /// Returns an encoded packet ready for transmission over UDP.
    pub fn process_mic_capture(&self, pcm_data: &[u8]) -> Option<Vec<u8>> {
        let codec = self.codec.lock();
        codec.encode_pcm_to_packet(pcm_data)
    }

    /// Ingest an incoming network packet from the remote peer into the jitter buffer
    pub fn ingest_network_packet(&self, raw_packet: &[u8]) {
        let codec = self.codec.lock();
        if let Some((seq, timestamp, _is_voice, samples)) = codec.decode_packet_to_pcm(raw_packet) {
            // Convert samples back to byte payload for jitter buffer storage
            let mut payload = Vec::with_capacity(samples.len() * 2);
            for s in &samples {
                payload.extend_from_slice(&s.to_le_bytes());
            }

            let mut jb = self.jitter_buffer.lock();
            jb.insert(seq, timestamp, payload);
        }
    }

    /// Pull the next audio frame for speaker playback (called at 20ms intervals)
    /// Returns 16-bit PCM samples formatted for Oboe / AudioTrack playback.
    pub fn pull_playback_pcm(&self, out_samples_count: usize) -> Vec<i16> {
        let mut jb = self.jitter_buffer.lock();
        let frame = jb.get_frame(out_samples_count);

        match frame {
            PlaybackFrame::ValidPacket(packet) => {
                let mut samples = Vec::with_capacity(packet.payload.len() / 2);
                for chunk in packet.payload.chunks_exact(2) {
                    let sample = i16::from_le_bytes([chunk[0], chunk[1]]);
                    samples.push(sample);
                }
                jb.update_last_pcm(&samples);
                samples
            }
            PlaybackFrame::ConcealedPcm(plc_samples) => {
                // Return smoothly interpolated lost-frame waveform
                plc_samples
            }
        }
    }

    pub fn set_mic_mute(&self, muted: bool) {
        let mut codec = self.codec.lock();
        codec.set_mic_muted(muted);
    }

    pub fn is_mic_muted(&self) -> bool {
        self.codec.lock().is_mic_muted()
    }

    pub fn reset_buffers(&self) {
        self.jitter_buffer.lock().reset();
        self.playback_queue.lock().clear();
    }
}
