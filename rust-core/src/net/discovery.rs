//! Offline LAN P2P UDP Discovery Service
//! Broadcasts and listens for local peer beacons on offline Wi-Fi networks without internet access.

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;
use std::time::{Duration, Instant};
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::UdpSocket;
use tokio::sync::broadcast;
use log::{info, warn, error};

pub const DEFAULT_DISCOVERY_PORT: u16 = 45781;
pub const MULTICAST_IPV4: &str = "239.255.255.250";
const BEACON_INTERVAL: Duration = Duration::from_millis(1000);
const PEER_TIMEOUT: Duration = Duration::from_secs(4);

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PeerBeacon {
    pub device_id: String,
    pub device_name: String,
    pub ip: String,
    pub signaling_port: u16,
    pub media_port: u16,
    pub sim_active: bool,
    pub sim_slot: i32,
    pub carrier_name: String,
    pub battery_pct: u8,
    pub is_charging: bool,
    pub protocol_version: u32,
}

#[derive(Clone, Debug)]
pub struct DiscoveredPeer {
    pub beacon: PeerBeacon,
    pub socket_addr: SocketAddr,
    pub last_seen: Instant,
}

pub struct DiscoveryEngine {
    local_beacon: RwLock<PeerBeacon>,
    peers: Arc<RwLock<HashMap<String, DiscoveredPeer>>>,
    shutdown_tx: broadcast::Sender<()>,
}

impl DiscoveryEngine {
    pub fn new(initial_beacon: PeerBeacon) -> Arc<Self> {
        let (shutdown_tx, _) = broadcast::channel(4);
        Arc::new(Self {
            local_beacon: RwLock::new(initial_beacon),
            peers: Arc::new(RwLock::new(HashMap::new())),
            shutdown_tx,
        })
    }

    pub fn update_local_beacon<F>(&self, update_fn: F)
    where
        F: FnOnce(&mut PeerBeacon),
    {
        let mut b = self.local_beacon.write();
        update_fn(&mut b);
    }

    pub fn get_active_peers(&self) -> Vec<DiscoveredPeer> {
        let mut peers = self.peers.write();
        let now = Instant::now();
        // Prune stale peers
        peers.retain(|_, peer| now.duration_since(peer.last_seen) < PEER_TIMEOUT);
        peers.values().cloned().collect()
    }

    /// Start the offline broadcast advertiser and listener tasks
    pub fn start(self: Arc<Self>) {
        let this_tx = self.clone();
        tokio::spawn(async move {
            if let Err(e) = this_tx.run_broadcaster().await {
                error!("Discovery broadcaster encountered error: {:?}", e);
            }
        });

        let this_rx = self.clone();
        tokio::spawn(async move {
            if let Err(e) = this_rx.run_listener().await {
                error!("Discovery listener encountered error: {:?}", e);
            }
        });
    }

    pub fn stop(&self) {
        let _ = self.shutdown_tx.send(());
    }

    async fn run_broadcaster(self: Arc<Self>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let mut shutdown_rx = self.shutdown_tx.subscribe();
        
        // Setup broadcast UDP socket
        let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
        socket.set_broadcast(true)?;
        socket.set_nonblocking(true)?;
        socket.set_reuse_address(true)?;

        let bind_addr: SocketAddr = "0.0.0.0:0".parse()?;
        socket.bind(&bind_addr.into())?;
        let udp_socket = UdpSocket::from_std(socket.into())?;

        let broadcast_target: SocketAddr = format!("255.255.255.255:{}", DEFAULT_DISCOVERY_PORT).parse()?;
        let multicast_target: SocketAddr = format!("{}:{}", MULTICAST_IPV4, DEFAULT_DISCOVERY_PORT).parse()?;

        let mut interval = tokio::time::interval(BEACON_INTERVAL);

        loop {
            tokio::select! {
                _ = shutdown_rx.recv() => {
                    info!("Discovery broadcaster received shutdown signal");
                    break;
                }
                _ = interval.tick() => {
                    let payload = {
                        let b = self.local_beacon.read();
                        serde_json::to_vec(&*b)?
                    };

                    // Broadcast on both subnet broadcast and multicast for maximum LAN compatibility
                    let _ = udp_socket.send_to(&payload, broadcast_target).await;
                    let _ = udp_socket.send_to(&payload, multicast_target).await;
                }
            }
        }

        Ok(())
    }

    async fn run_listener(self: Arc<Self>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let mut shutdown_rx = self.shutdown_tx.subscribe();

        let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
        socket.set_reuse_address(true)?;
        #[cfg(not(windows))]
        socket.set_reuse_port(true)?;
        socket.set_broadcast(true)?;
        socket.set_nonblocking(true)?;

        let bind_addr = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, DEFAULT_DISCOVERY_PORT);
        socket.bind(&bind_addr.into())?;

        // Join multicast group
        let multi_addr: Ipv4Addr = MULTICAST_IPV4.parse()?;
        socket.join_multicast_v4(&multi_addr, &Ipv4Addr::UNSPECIFIED)?;

        let udp_socket = UdpSocket::from_std(socket.into())?;
        info!("Discovery listener listening on 0.0.0.0:{}", DEFAULT_DISCOVERY_PORT);

        let mut buf = [0u8; 2048];

        loop {
            tokio::select! {
                _ = shutdown_rx.recv() => {
                    info!("Discovery listener received shutdown signal");
                    break;
                }
                res = udp_socket.recv_from(&mut buf) => {
                    match res {
                        Ok((len, peer_addr)) => {
                            if let Ok(beacon) = serde_json::from_slice::<PeerBeacon>(&buf[..len]) {
                                let local_id = self.local_beacon.read().device_id.clone();
                                // Ignore our own beacon loopback
                                if beacon.device_id != local_id {
                                    let mut peers = self.peers.write();
                                    peers.insert(
                                        beacon.device_id.clone(),
                                        DiscoveredPeer {
                                            beacon,
                                            socket_addr: peer_addr,
                                            last_seen: Instant::now(),
                                        },
                                    );
                                }
                            }
                        }
                        Err(e) => {
                            warn!("Error receiving discovery packet: {:?}", e);
                        }
                    }
                }
            }
        }

        Ok(())
    }
}
