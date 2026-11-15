//! Real-Time UDP Voice Streamer
//! Transmits and receives low-latency Opus audio frames over local Wi-Fi.

use std::net::SocketAddr;
use std::sync::Arc;
use parking_lot::RwLock;
use tokio::net::UdpSocket;
use tokio::sync::broadcast;
use log::{info, warn, error};

use crate::audio::AudioPipeline;

pub const DEFAULT_MEDIA_PORT: u16 = 45783;

pub struct UdpAudioStreamer {
    local_port: u16,
    remote_target: RwLock<Option<SocketAddr>>,
    audio_pipeline: Arc<AudioPipeline>,
    shutdown_tx: broadcast::Sender<()>,
}

impl UdpAudioStreamer {
    pub fn new(local_port: u16, audio_pipeline: Arc<AudioPipeline>) -> Arc<Self> {
        let (shutdown_tx, _) = broadcast::channel(4);
        Arc::new(Self {
            local_port,
            remote_target: RwLock::new(None),
            audio_pipeline,
            shutdown_tx,
        })
    }

    pub fn set_remote_target(&self, target: Option<SocketAddr>) {
        *self.remote_target.write() = target;
    }

    /// Start the background UDP receiver task
    pub fn start_receiver(self: Arc<Self>) {
        tokio::spawn(async move {
            let bind_addr = format!("0.0.0.0:{}", self.local_port);
            let socket = match UdpSocket::bind(&bind_addr).await {
                Ok(s) => {
                    info!("UDP Voice Media receiver bound to {}", bind_addr);
                    s
                }
                Err(e) => {
                    error!("Failed to bind UDP audio socket {}: {:?}", bind_addr, e);
                    return;
                }
            };

            let mut shutdown_rx = self.shutdown_tx.subscribe();
            let mut buf = [0u8; 1500]; // MTU safe

            loop {
                tokio::select! {
                    _ = shutdown_rx.recv() => {
                        info!("UDP Audio receiver received shutdown");
                        break;
                    }
                    recv_res = socket.recv_from(&mut buf) => {
                        match recv_res {
                            Ok((len, peer_addr)) => {
                                // Ingest received voice frame into jitter buffer
                                self.audio_pipeline.ingest_network_packet(&buf[..len]);

                                // Auto-learn remote media target if not yet set
                                if self.remote_target.read().is_none() {
                                    *self.remote_target.write() = Some(peer_addr);
                                }
                            }
                            Err(e) => {
                                warn!("UDP voice recv error: {:?}", e);
                            }
                        }
                    }
                }
            }
        });
    }

    /// Send a single captured voice packet out over UDP
    pub async fn send_voice_packet(&self, packet_bytes: &[u8]) {
        let target = *self.remote_target.read();
        if let Some(remote_addr) = target {
            // Ephemeral or dedicated sender socket
            if let Ok(socket) = UdpSocket::bind("0.0.0.0:0").await {
                let _ = socket.send_to(packet_bytes, remote_addr).await;
            }
        }
    }

    pub fn stop(&self) {
        let _ = self.shutdown_tx.send(());
    }
}
