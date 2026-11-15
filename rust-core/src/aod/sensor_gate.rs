//! Sensor Power Governor & Refresh Rate Throttle
//! Powers down display when face-down or in pocket, controls 1-FPS vs 30-FPS rendering cycles.

use crate::aod::burn_in_shift::DisplayOrientation;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProximityState {
    Near, // Face down or pocket -> blank screen completely
    Far,  // Screen visible -> render AOD
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum AodVisualState {
    IdleClock,           // 1 FPS refresh rate
    IncomingCallAlert,   // 30 FPS pulse animation
    ScreenBlanked,       // 0 FPS, display off
}

pub struct SensorGovernor {
    proximity: ProximityState,
    visual_state: AodVisualState,
    orientation: DisplayOrientation,
    target_brightness: f32, // 0.01 for LCD battery preservation
}

impl SensorGovernor {
    pub fn new() -> Self {
        Self {
            proximity: ProximityState::Far,
            visual_state: AodVisualState::IdleClock,
            orientation: DisplayOrientation::Portrait,
            target_brightness: 0.01,
        }
    }

    pub fn on_proximity_changed(&mut self, is_near: bool) {
        self.proximity = if is_near {
            ProximityState::Near
        } else {
            ProximityState::Far
        };
    }

    pub fn on_call_alert_changed(&mut self, is_ringing: bool) {
        if is_ringing {
            self.visual_state = AodVisualState::IncomingCallAlert;
        } else {
            self.visual_state = AodVisualState::IdleClock;
        }
    }

    pub fn set_orientation(&mut self, orientation: DisplayOrientation) {
        self.orientation = orientation;
    }

    pub fn get_orientation(&self) -> DisplayOrientation {
        self.orientation
    }

    /// Evaluates current hardware display strategy
    pub fn get_render_strategy(&self) -> RenderStrategy {
        if self.proximity == ProximityState::Near {
            return RenderStrategy {
                blank_screen: true,
                refresh_rate_fps: 0,
                target_brightness: 0.0,
                is_landscape: matches!(
                    self.orientation,
                    DisplayOrientation::LandscapeLeft | DisplayOrientation::LandscapeRight
                ),
            };
        }

        match self.visual_state {
            AodVisualState::IdleClock => RenderStrategy {
                blank_screen: false,
                refresh_rate_fps: 1, // Ultra-low power 1-FPS clock tick
                target_brightness: self.target_brightness,
                is_landscape: matches!(
                    self.orientation,
                    DisplayOrientation::LandscapeLeft | DisplayOrientation::LandscapeRight
                ),
            },
            AodVisualState::IncomingCallAlert => RenderStrategy {
                blank_screen: false,
                refresh_rate_fps: 30, // Smooth call pulse & accept button animation
                target_brightness: 0.8, // Boost visibility for incoming call
                is_landscape: matches!(
                    self.orientation,
                    DisplayOrientation::LandscapeLeft | DisplayOrientation::LandscapeRight
                ),
            },
            AodVisualState::ScreenBlanked => RenderStrategy {
                blank_screen: true,
                refresh_rate_fps: 0,
                target_brightness: 0.0,
                is_landscape: false,
            },
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct RenderStrategy {
    pub blank_screen: bool,
    pub refresh_rate_fps: u32,
    pub target_brightness: f32,
    pub is_landscape: bool,
}
