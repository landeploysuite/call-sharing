//! Symmetrical Bidirectional Call State Machine
//! Coordinates local cellular calls and remote relayed calls across both peer phones.

use std::sync::Arc;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use log::{info, warn};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum CallState {
    Idle,
    LocalIncomingRinging {
        session_id: String,
        caller_number: String,
        caller_name: String,
        sim_slot: i32,
    },
    RemoteIncomingRinging {
        session_id: String,
        caller_number: String,
        caller_name: String,
        sim_slot: i32,
    },
    ActiveBridgedLocal {
        session_id: String,
        caller_number: String,
    },
    ActiveBridgedRemote {
        session_id: String,
        caller_number: String,
    },
    Terminated {
        session_id: String,
        reason: String,
    },
}

pub struct CallSessionCoordinator {
    local_device_id: String,
    state: RwLock<CallState>,
}

impl CallSessionCoordinator {
    pub fn new(local_device_id: String) -> Arc<Self> {
        Arc::new(Self {
            local_device_id,
            state: RwLock::new(CallState::Idle),
        })
    }

    pub fn get_state(&self) -> CallState {
        self.state.read().clone()
    }

    /// Triggered when the local Android InCallService detects a cellular call on this device's SIM
    pub fn on_local_call_ringing(
        &self,
        session_id: String,
        caller_number: String,
        caller_name: String,
        sim_slot: i32,
    ) -> bool {
        let mut state = self.state.write();
        match &*state {
            CallState::Idle => {
                info!("Local SIM call ringing: {} ({})", caller_number, session_id);
                *state = CallState::LocalIncomingRinging {
                    session_id,
                    caller_number,
                    caller_name,
                    sim_slot,
                };
                true
            }
            CallState::RemoteIncomingRinging { session_id: remote_id, .. } => {
                // Conflict resolution: lower device ID wins priority
                warn!("Simultaneous call collision! Comparing device IDs");
                if self.local_device_id < *remote_id {
                    *state = CallState::LocalIncomingRinging {
                        session_id,
                        caller_number,
                        caller_name,
                        sim_slot,
                    };
                    true
                } else {
                    false
                }
            }
            _ => {
                warn!("Ignoring local ringing; call session already in progress");
                false
            }
        }
    }

    /// Triggered when peer device informs us of an incoming call on their SIM
    pub fn on_remote_call_ringing(
        &self,
        session_id: String,
        caller_number: String,
        caller_name: String,
        sim_slot: i32,
    ) -> bool {
        let mut state = self.state.write();
        match &*state {
            CallState::Idle => {
                info!("Remote peer SIM call ringing: {} ({})", caller_number, session_id);
                *state = CallState::RemoteIncomingRinging {
                    session_id,
                    caller_number,
                    caller_name,
                    sim_slot,
                };
                true
            }
            _ => {
                warn!("Device busy; cannot accept remote call relay");
                false
            }
        }
    }

    /// Local user answers the local call, bridging it to peer
    pub fn on_local_call_answered(&self, session_id: &str) {
        let mut state = self.state.write();
        if let CallState::LocalIncomingRinging { caller_number, .. } = &*state {
            info!("Local SIM call answered & bridged: {}", session_id);
            *state = CallState::ActiveBridgedLocal {
                session_id: session_id.to_string(),
                caller_number: caller_number.clone(),
            };
        }
    }

    /// Local user answers the relayed call from remote peer
    pub fn on_remote_call_answered(&self, session_id: &str) {
        let mut state = self.state.write();
        if let CallState::RemoteIncomingRinging { caller_number, .. } = &*state {
            info!("Relayed call answered: {}", session_id);
            *state = CallState::ActiveBridgedRemote {
                session_id: session_id.to_string(),
                caller_number: caller_number.clone(),
            };
        }
    }

    /// Call ends or is rejected
    pub fn on_call_terminated(&self, session_id: &str, reason: &str) {
        let mut state = self.state.write();
        info!("Call terminated ({}): {}", session_id, reason);
        *state = CallState::Idle;
    }
}
