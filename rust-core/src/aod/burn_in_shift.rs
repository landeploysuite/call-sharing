//! Anti-Burn-In Pixel Shifting Engine
//! Displaces Always-On Display elements periodically to prevent OLED burn-in and LCD image retention.
//! Full support for dynamic clamping between Portrait and Landscape orientations, orbit speed scaling,
//! and 3-way AOD orientation locking.

use std::time::Instant;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DisplayOrientation {
    Portrait,
    LandscapeLeft,
    LandscapeRight,
}

#[derive(Clone, Copy, Debug)]
pub struct PixelOffset {
    pub dx: i32,
    pub dy: i32,
}

pub struct BurnInShiftEngine {
    last_shift: Instant,
    step_counter: u32,
    current_orientation: DisplayOrientation,
    max_shift_x: i32,
    max_shift_y: i32,
    orbit_speed_multiplier: f32,
}

impl BurnInShiftEngine {
    pub fn new() -> Self {
        Self {
            last_shift: Instant::now(),
            step_counter: 0,
            current_orientation: DisplayOrientation::Portrait,
            max_shift_x: 6,
            max_shift_y: 8,
            orbit_speed_multiplier: 1.0,
        }
    }

    /// Sets the AOD orientation mode:
    /// 0 = Portrait Lock (max_x = 6, max_y = 8)
    /// 1 = Landscape Dock (max_x = 10, max_y = 5)
    /// 2 = Auto Sensor (defaults to current sensor orientation)
    pub fn set_aod_orientation(&mut self, mode: i32) {
        match mode {
            0 => {
                self.current_orientation = DisplayOrientation::Portrait;
                self.max_shift_x = 6;
                self.max_shift_y = 8;
            }
            1 => {
                self.current_orientation = DisplayOrientation::LandscapeLeft;
                self.max_shift_x = 10;
                self.max_shift_y = 5;
            }
            _ => {
                // Auto Sensor: Keep current orientation clamping
                self.apply_orientation_clamping();
            }
        }
    }

    /// Recalculates and clamps displacement axes immediately upon orientation change
    pub fn set_orientation(&mut self, orientation: DisplayOrientation) {
        self.current_orientation = orientation;
        self.apply_orientation_clamping();
    }

    fn apply_orientation_clamping(&mut self) {
        match self.current_orientation {
            DisplayOrientation::Portrait => {
                self.max_shift_x = 6;
                self.max_shift_y = 8;
            }
            DisplayOrientation::LandscapeLeft | DisplayOrientation::LandscapeRight => {
                // Broader horizontal travel in landscape, restricted vertical headroom
                self.max_shift_x = 10;
                self.max_shift_y = 5;
            }
        }
    }

    /// Configures the orbit speed multiplier (1.0 = normal 60s, up to 20.0x preview)
    pub fn set_orbit_speed_multiplier(&mut self, multiplier: f32) {
        self.orbit_speed_multiplier = multiplier.clamp(1.0, 50.0);
    }

    /// Calculate the next anti-burn-in displacement offset
    /// Uses a closed Lissajous curve orbit to evenly distribute subpixel wear.
    pub fn get_offset(&mut self) -> PixelOffset {
        let now = Instant::now();
        let interval_secs = (60.0 / self.orbit_speed_multiplier).max(0.1);

        if now.duration_since(self.last_shift).as_secs_f32() >= interval_secs {
            self.step_counter = self.step_counter.wrapping_add(1);
            self.last_shift = now;
        }

        let t = (self.step_counter as f32) * 0.45;
        // Closed Lissajous curve: x = A * sin(2*t), y = B * cos(3*t)
        let normalized_x = (t * 2.0).sin();
        let normalized_y = (t * 3.0).cos();

        let dx = (normalized_x * self.max_shift_x as f32).round() as i32;
        let dy = (normalized_y * self.max_shift_y as f32).round() as i32;

        PixelOffset {
            dx: dx.clamp(-self.max_shift_x, self.max_shift_x),
            dy: dy.clamp(-self.max_shift_y, self.max_shift_y),
        }
    }
}
