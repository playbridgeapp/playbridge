//! Estimates the offset between this device's clock and the room clock from
//! ping round trips, trusting the fastest samples (NTP-style).

use std::collections::VecDeque;

/// Samples kept; the best (lowest round trip) of these wins.
const WINDOW: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ClockSample {
    pub rtt_ms: f64,
    pub offset_ms: f64,
}

#[derive(Debug, Default)]
pub struct ClockEstimator {
    samples: VecDeque<ClockSample>,
}

impl ClockEstimator {
    /// Adds one ping: sent at local `t0`, answered with `room_time`, received at local `t1`.
    pub fn add(&mut self, t0: f64, room_time: f64, t1: f64) {
        if t1 < t0 {
            return;
        }
        let sample = ClockSample {
            rtt_ms: t1 - t0,
            offset_ms: room_time - (t0 + t1) / 2.0,
        };
        if self.samples.len() == WINDOW {
            self.samples.pop_front();
        }
        self.samples.push_back(sample);
    }

    pub fn best(&self) -> Option<ClockSample> {
        self.samples
            .iter()
            .copied()
            .min_by(|a, b| a.rtt_ms.total_cmp(&b.rtt_ms))
    }

    pub fn is_synced(&self) -> bool {
        !self.samples.is_empty()
    }

    /// Room time for a local timestamp, or `None` before the first sample.
    pub fn room_time(&self, local_ms: f64) -> Option<f64> {
        self.best().map(|s| local_ms + s.offset_ms)
    }

    pub fn rtt_ms(&self) -> Option<f64> {
        self.best().map(|s| s.rtt_ms)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefers_the_fastest_round_trip() {
        let mut clock = ClockEstimator::default();
        // True offset +1000 ms. A slow sample with asymmetric delay skews the estimate.
        clock.add(0.0, 1000.0 + 250.0, 400.0); // rtt 400, offset 1050
        clock.add(1000.0, 2000.0 + 10.0, 1020.0); // rtt 20, offset 1000
        let best = clock.best().unwrap();
        assert_eq!(best.rtt_ms, 20.0);
        assert_eq!(best.offset_ms, 1000.0);
        assert_eq!(clock.room_time(5000.0), Some(6000.0));
    }

    #[test]
    fn keeps_a_sliding_window() {
        let mut clock = ClockEstimator::default();
        clock.add(0.0, 0.0, 1.0); // best, then pushed out
        for i in 0..WINDOW {
            let t = 100.0 * (i as f64 + 1.0);
            clock.add(t, t + 500.0 + 25.0, t + 50.0);
        }
        assert_eq!(clock.rtt_ms(), Some(50.0));
    }
}
