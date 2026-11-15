//! Anti-Burn-In Pixel Shifting Engine
//! Displaces Always-On Display elements periodically to prevent OLED burn-in and LCD image retention.
//! Full support for dynamic clamping between Portrait and Landscape orientations.

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
}

impl BurnInShiftEngine {
    pub fn new() -> Self {
        Self {
            last_shift: Instant::now(),
            step_counter: 0,
            current_orientation: DisplayOrientation::Portrait,
            max_shift_x: 6,
            max_shift_y: 8,
        }
    }

    /// Bug 4.2: Recalculates and clamps displacement axes immediately upon orientation change
    pub fn set_orientation(&mut self, orientation: DisplayOrientation) {
        self.current_orientation = orientation;
        match orientation {
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
        // Force immediate recalculation to avoid out-of-bounds artifacting
        let _ = self.get_offset();
    }

    /// Calculate the next anti-burn-in displacement offset
    /// Uses a closed Lissajous curve orbit to evenly distribute subpixel wear.
    pub fn get_offset(&mut self) -> PixelOffset {
        let now = Instant::now();
        // Shift every 60 seconds
        if now.duration_since(self.last_shift).as_secs() >= 60 {
            self.step_counter = self.step_counter.wrapping_add(1);
            self.last_shift = now;
        }

        let t = (self.step_counter as f32) * 0.45;
        // Lissajous curve: x = A * sin(2*t), y = B * cos(3*t)
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
