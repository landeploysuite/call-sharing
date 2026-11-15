//! Dynamic, Low-Latency Adaptive Jitter Buffer & Packet Loss Concealment (PLC)
//! Designed specifically for volatile Wi-Fi environments.
//! Implements RFC 1982 serial number arithmetic and anti-bloat latency pruning.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// Voice packet envelope carried over UDP
#[derive(Clone, Debug)]
pub struct VoicePacket {
    pub seq: u16,
    pub timestamp: u32,
    pub payload: Vec<u8>,
    pub arrival_time: Instant,
}

/// Statistics for call quality monitoring
#[derive(Default, Clone, Debug)]
pub struct JitterBufferStats {
    pub received_packets: u64,
    pub late_packets: u64,
    pub lost_packets: u64,
    pub concealed_frames: u64,
    pub current_depth_ms: f32,
    pub estimated_jitter_ms: f32,
}

/// Circular, adaptive jitter buffer with Packet Loss Concealment (PLC)
pub struct AdaptiveJitterBuffer {
    capacity: usize,
    buffer: VecDeque<VoicePacket>,
    last_played_seq: Option<u16>,
    last_pcm_frame: Vec<i16>, // Cached for waveform synthesis during PLC
    
    // Adaptive delay parameters
    target_delay_ms: f32,
    min_delay_ms: f32,
    max_delay_ms: f32,
    
    // Jitter estimation (RFC 3550 style)
    estimated_jitter_ms: f32,
    last_transit_time_ms: Option<f32>,
    
    pub stats: JitterBufferStats,
}

impl AdaptiveJitterBuffer {
    pub fn new(min_delay_ms: f32, max_delay_ms: f32) -> Self {
        Self {
            capacity: 64, // ~1.28 seconds of 20ms frames
            buffer: VecDeque::with_capacity(64),
            last_played_seq: None,
            last_pcm_frame: Vec::new(),
            target_delay_ms: min_delay_ms.max(20.0),
            min_delay_ms,
            max_delay_ms,
            estimated_jitter_ms: 5.0,
            last_transit_time_ms: None,
            stats: JitterBufferStats::default(),
        }
    }

    /// Insert an incoming voice packet from the UDP socket
    pub fn insert(&mut self, seq: u16, timestamp: u32, payload: Vec<u8>) {
        self.stats.received_packets += 1;
        let now = Instant::now();

        // Calculate jitter estimation using RFC 3550 formula
        let transit_ms = now.elapsed().as_secs_f32() * 1000.0;
        if let Some(prev_transit) = self.last_transit_time_ms {
            let diff = (transit_ms - prev_transit).abs();
            // J_i = J_{i-1} + (|D(i-1, i)| - J_{i-1}) / 16
            self.estimated_jitter_ms += (diff - self.estimated_jitter_ms) / 16.0;
            self.stats.estimated_jitter_ms = self.estimated_jitter_ms;
            
            // Adapt target buffer depth based on 2.5 * estimated jitter
            let dynamic_target = (self.estimated_jitter_ms * 2.5).clamp(self.min_delay_ms, self.max_delay_ms);
            self.target_delay_ms += (dynamic_target - self.target_delay_ms) * 0.05;
        }
        self.last_transit_time_ms = Some(transit_ms);

        // Check if packet is already too late using RFC 1982 wrap arithmetic
        if let Some(last_seq) = self.last_played_seq {
            let diff = seq.wrapping_sub(last_seq) as i16;
            if diff <= 0 {
                self.stats.late_packets += 1;
                return; // Drop stale out-of-order packet
            }
        }

        let packet = VoicePacket {
            seq,
            timestamp,
            payload,
            arrival_time: now,
        };

        // Binary search insertion based on sequence number (handling 16-bit wrap)
        let pos = self.buffer.binary_search_by(|p| {
            let diff = p.seq.wrapping_sub(seq) as i16;
            if diff == 0 {
                std::cmp::Ordering::Equal
            } else if diff < 0 {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Greater
            }
        });

        match pos {
            Ok(_) => {
                // Duplicate packet, ignore
            }
            Err(insert_idx) => {
                if self.buffer.len() >= self.capacity {
                    self.buffer.pop_front();
                }
                self.buffer.insert(insert_idx, packet);
            }
        }

        // Bug 3.1: Prune latency bloat immediately if network flushed a packet burst
        self.prune_excess_latency();
    }

    /// Bug 3.1: Anti-bloat latency recovery after Wi-Fi stall flushes packet bursts
    pub fn prune_excess_latency(&mut self) {
        let max_acceptable_frames = ((self.target_delay_ms / 20.0) * 2.5).ceil() as usize;
        while self.buffer.len() > max_acceptable_frames.max(3) {
            let dropped = self.buffer.pop_front();
            if let Some(pkt) = dropped {
                self.last_played_seq = Some(pkt.seq);
                self.stats.late_packets += 1;
            }
        }
    }

    /// Retrieve the next playable payload or perform PLC if frame is lost
    pub fn get_frame(&mut self, expected_samples: usize) -> PlaybackFrame {
        let current_buffered_frames = self.buffer.len() as f32;
        self.stats.current_depth_ms = current_buffered_frames * 20.0;

        if self.buffer.is_empty() {
            // Buffer starvation -> perform packet loss concealment
            self.stats.lost_packets += 1;
            self.stats.concealed_frames += 1;
            let plc_pcm = self.synthesize_plc_pcm(expected_samples);
            return PlaybackFrame::ConcealedPcm(plc_pcm);
        }

        // Check next packet in sequence using RFC 1982 serial number arithmetic
        if let Some(last_seq) = self.last_played_seq {
            let expected_next_seq = last_seq.wrapping_add(1);
            if let Some(front) = self.buffer.front() {
                let front_seq = front.seq;
                let seq_gap = front_seq.wrapping_sub(expected_next_seq) as i16;

                if seq_gap > 0 {
                    let wait_duration = front.arrival_time.elapsed();

                    if wait_duration < Duration::from_millis(self.target_delay_ms as u64) {
                        // Conceal and give missing packet chance to arrive
                        self.stats.concealed_frames += 1;
                        let plc_pcm = self.synthesize_plc_pcm(expected_samples);
                        return PlaybackFrame::ConcealedPcm(plc_pcm);
                    } else {
                        // Bug 1.4: Saturating arithmetic prevents u64 underflow crash
                        self.stats.lost_packets = self.stats.lost_packets.saturating_add(seq_gap as u64);
                    }
                }
            }
        }

        let packet = match self.buffer.pop_front() {
            Some(p) => p,
            None => {
                let plc_pcm = self.synthesize_plc_pcm(expected_samples);
                return PlaybackFrame::ConcealedPcm(plc_pcm);
            }
        };
        self.last_played_seq = Some(packet.seq);
        PlaybackFrame::ValidPacket(packet)
    }

    /// Cache decoded PCM frame for waveform extrapolation
    pub fn update_last_pcm(&mut self, pcm: &[i16]) {
        self.last_pcm_frame.clear();
        self.last_pcm_frame.extend_from_slice(pcm);
    }

    /// Packet Loss Concealment (PLC):
    /// Extrapolates previous speech frame with an exponential decay curve (0.78x)
    fn synthesize_plc_pcm(&mut self, samples_count: usize) -> Vec<i16> {
        if self.last_pcm_frame.is_empty() {
            return vec![0i16; samples_count];
        }

        let decay_factor = 0.78f32;
        let mut plc_buffer = Vec::with_capacity(samples_count);
        let src_len = self.last_pcm_frame.len();

        for i in 0..samples_count {
            let src_idx = i % src_len;
            let original = self.last_pcm_frame[src_idx] as f32;
            let attenuated = (original * decay_factor).clamp(i16::MIN as f32, i16::MAX as f32) as i16;
            plc_buffer.push(attenuated);
            self.last_pcm_frame[src_idx] = attenuated;
        }

        plc_buffer
    }

    pub fn reset(&mut self) {
        self.buffer.clear();
        self.last_played_seq = None;
        self.last_pcm_frame.clear();
        self.last_transit_time_ms = None;
    }
}

pub enum PlaybackFrame {
    ValidPacket(VoicePacket),
    ConcealedPcm(Vec<i16>),
}
