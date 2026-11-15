//! P2P Call Signaling & Control Protocol
//! Manages reliable call state handshakes between peer devices over local Wi-Fi.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc};
use log::{info, warn, error};

pub const DEFAULT_SIGNALING_PORT: u16 = 45782;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "action", content = "data")]
pub enum SignalingMessage {
    CallIncoming {
        session_id: String,
        caller_number: String,
        caller_name: String,
        sim_slot: i32,
        timestamp: u64,
    },
    CallAccept {
        session_id: String,
        media_port: u16,
    },
    CallReject {
        session_id: String,
        reason: String,
    },
    CallHangup {
        session_id: String,
        reason: String,
    },
    CallAnsweredRemotely {
        session_id: String,
    },
    MicMute {
        session_id: String,
        muted: bool,
    },
    Hold {
        session_id: String,
        held: bool,
    },
    BatteryUpdate {
        battery_pct: u8,
        is_charging: bool,
    },
    Ping {
        seq: u64,
        timestamp: u64,
    },
    Pong {
        seq: u64,
        timestamp: u64,
    },
}

pub struct SignalingEngine {
    local_port: u16,
    outbound_tx: mpsc::Sender<SignalingMessage>,
    inbound_broadcast: broadcast::Sender<SignalingMessage>,
    active_peer_addr: Mutex<Option<SocketAddr>>,
    shutdown_tx: broadcast::Sender<()>,
}

impl SignalingEngine {
    pub fn new(local_port: u16) -> (Arc<Self>, broadcast::Receiver<SignalingMessage>) {
        let (outbound_tx, mut outbound_rx) = mpsc::channel::<SignalingMessage>(32);
        let (inbound_broadcast, inbound_rx) = broadcast::channel::<SignalingMessage>(64);
        let (shutdown_tx, _) = broadcast::channel::<()>(4);

        let engine = Arc::new(Self {
            local_port,
            outbound_tx,
            inbound_broadcast,
            active_peer_addr: Mutex::new(None),
            shutdown_tx,
        });

        // Background worker to broadcast outbound messages to active peer connection
        let engine_clone = engine.clone();
        tokio::spawn(async move {
            while let Some(msg) = outbound_rx.recv().await {
                let peer_target = *engine_clone.active_peer_addr.lock();
                if let Some(peer) = peer_target {
                    let _ = Self::send_message_direct(peer, &msg).await;
                }
            }
        });

        (engine, inbound_rx)
    }

    pub fn set_peer_addr(&self, addr: SocketAddr) {
        *self.active_peer_addr.lock() = Some(addr);
    }

    pub fn send_message(&self, msg: SignalingMessage) {
        let _ = self.outbound_tx.try_send(msg);
    }

    /// Direct async message transmission with length prefix
    pub async fn send_message_direct(addr: SocketAddr, msg: &SignalingMessage) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let serialized = serde_json::to_vec(msg)?;
        let mut stream = tokio::time::timeout(Duration::from_millis(1500), TcpStream::connect(addr)).await??;
        
        // Write 4-byte big endian length prefix + payload
        let len = serialized.len() as u32;
        stream.write_all(&len.to_be_bytes()).await?;
        stream.write_all(&serialized).await?;
        stream.flush().await?;
        Ok(())
    }

    /// Start TCP listener for incoming peer signaling commands
    pub fn start_listener(self: Arc<Self>) {
        tokio::spawn(async move {
            let bind_addr = format!("0.0.0.0:{}", self.local_port);
            let listener = match TcpListener::bind(&bind_addr).await {
                Ok(l) => {
                    info!("Signaling TCP server listening on {}", bind_addr);
                    l
                }
                Err(e) => {
                    error!("Failed to bind signaling port {}: {:?}", bind_addr, e);
                    return;
                }
            };

            let mut shutdown_rx = self.shutdown_tx.subscribe();

            loop {
                tokio::select! {
                    _ = shutdown_rx.recv() => {
                        info!("Signaling listener shutting down");
                        break;
                    }
                    accept_res = listener.accept() => {
                        match accept_res {
                            Ok((stream, peer_addr)) => {
                                let engine = self.clone();
                                tokio::spawn(async move {
                                    if let Err(e) = engine.handle_client_stream(stream, peer_addr).await {
                                        warn!("Signaling client error from {}: {:?}", peer_addr, e);
                                    }
                                });
                            }
                            Err(e) => {
                                warn!("Signaling accept failed: {:?}", e);
                            }
                        }
                    }
                }
            }
        });
    }

    async fn handle_client_stream(&self, mut stream: TcpStream, peer_addr: SocketAddr) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        // Automatically register as active peer
        *self.active_peer_addr.lock() = Some(peer_addr);

        let mut len_buf = [0u8; 4];
        while stream.read_exact(&mut len_buf).await.is_ok() {
            let msg_len = u32::from_be_bytes(len_buf) as usize;
            if msg_len > 65536 {
                // Safeguard against invalid memory allocations
                return Err("Signaling message length exceeded maximum threshold".into());
            }

            let mut payload_buf = vec![0u8; msg_len];
            stream.read_exact(&mut payload_buf).await?;

            if let Ok(msg) = serde_json::from_slice::<SignalingMessage>(&payload_buf) {
                let _ = self.inbound_broadcast.send(msg);
            }
        }

        Ok(())
    }

    pub fn stop(&self) {
        let _ = self.shutdown_tx.send(());
    }
}
