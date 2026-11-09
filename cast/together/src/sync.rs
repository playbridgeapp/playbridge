//! The shared playback timeline and per-device drift correction.

use serde::{Deserialize, Serialize};

use crate::ids::MemberId;

/// Drift under this is left alone.
pub const DEADBAND_MS: f64 = 150.0;
/// Drift up to this is fixed by nudging speed; beyond it, by seeking.
pub const SEEK_THRESHOLD_MS: f64 = 2000.0;
/// A nudge stops once drift is back under this.
pub const NUDGE_DONE_MS: f64 = 40.0;
pub const NUDGE_RATE: f64 = 0.05;

/// The room's playback timeline. Every member derives where it should be from
/// this, so positions are never streamed.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Timeline {
    pub item: String,
    pub playing: bool,
    /// Position at `at_room_ms`.
    pub position_ms: f64,
    pub at_room_ms: f64,
    /// Revision; higher wins. Ties break on the member that wrote it.
    pub rev: u64,
    pub by: MemberId,
}

impl Timeline {
    pub fn expected_ms(&self, room_now: f64) -> f64 {
        if self.playing {
            self.position_ms + (room_now - self.at_room_ms).max(0.0)
        } else {
            self.position_ms
        }
    }

    /// Whether `self` should replace `current`.
    pub fn supersedes(&self, current: &Timeline) -> bool {
        (self.rev, self.by) > (current.rev, current.by)
    }

    /// Whether a scheduled start is still in the future.
    pub fn starts_after(&self, room_now: f64) -> bool {
        self.playing && self.at_room_ms > room_now
    }
}

/// What the local player should do.
#[derive(Clone, Debug, PartialEq)]
pub enum Correction {
    None,
    Play,
    Pause,
    SetRate(f64),
    Seek(f64),
    /// Seek, then play straight away (joining a timeline that is running).
    SeekAndPlay(f64),
}

/// Per-device corrector with hysteresis around speed nudges.
#[derive(Debug)]
pub struct Corrector {
    rate: f64,
}

impl Default for Corrector {
    fn default() -> Self {
        Self { rate: 1.0 }
    }
}

/// What the local player is doing right now.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PlayerSnapshot {
    pub position_ms: f64,
    pub playing: bool,
    pub buffering: bool,
}

impl Corrector {
    pub fn rate(&self) -> f64 {
        self.rate
    }

    /// Call after any seek or reload; the player's speed is back to normal.
    pub fn reset(&mut self) {
        self.rate = 1.0;
    }

    /// Returns at most one correction per call; call again on the next tick.
    pub fn correct(
        &mut self,
        timeline: &Timeline,
        room_now: f64,
        player: PlayerSnapshot,
    ) -> Correction {
        if player.buffering {
            return Correction::None;
        }
        // Waiting for a scheduled start: hold paused at the start position.
        if timeline.starts_after(room_now) || !timeline.playing {
            if player.playing {
                return self.unnudged(Correction::Pause);
            }
            let target = timeline.position_ms;
            if (player.position_ms - target).abs() > DEADBAND_MS {
                self.rate = 1.0;
                return Correction::Seek(target);
            }
            return self.unnudged(Correction::None);
        }

        let expected = timeline.expected_ms(room_now);
        let drift = player.position_ms - expected; // positive: ahead
        if !player.playing {
            self.rate = 1.0;
            return if drift.abs() > DEADBAND_MS {
                Correction::SeekAndPlay(expected)
            } else {
                Correction::Play
            };
        }
        if drift.abs() > SEEK_THRESHOLD_MS {
            self.rate = 1.0;
            return Correction::Seek(expected);
        }
        if self.rate != 1.0 {
            if drift.abs() <= NUDGE_DONE_MS || (self.rate > 1.0) == (drift > 0.0) {
                self.rate = 1.0;
                return Correction::SetRate(1.0);
            }
            return Correction::None;
        }
        if drift.abs() > DEADBAND_MS {
            self.rate = if drift > 0.0 {
                1.0 - NUDGE_RATE
            } else {
                1.0 + NUDGE_RATE
            };
            return Correction::SetRate(self.rate);
        }
        Correction::None
    }

    fn unnudged(&mut self, otherwise: Correction) -> Correction {
        if self.rate != 1.0 && otherwise == Correction::None {
            self.rate = 1.0;
            return Correction::SetRate(1.0);
        }
        otherwise
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn timeline(playing: bool, position_ms: f64, at_room_ms: f64) -> Timeline {
        Timeline {
            item: "v".into(),
            playing,
            position_ms,
            at_room_ms,
            rev: 1,
            by: MemberId::from_bytes([1; 16]),
        }
    }

    fn snap(position_ms: f64, playing: bool) -> PlayerSnapshot {
        PlayerSnapshot {
            position_ms,
            playing,
            buffering: false,
        }
    }

    #[test]
    fn expected_position_advances_only_while_playing() {
        assert_eq!(timeline(true, 1000.0, 0.0).expected_ms(500.0), 1500.0);
        assert_eq!(timeline(false, 1000.0, 0.0).expected_ms(500.0), 1000.0);
    }

    #[test]
    fn leaves_small_drift_alone() {
        let mut c = Corrector::default();
        assert_eq!(
            c.correct(&timeline(true, 0.0, 0.0), 10_000.0, snap(10_100.0, true)),
            Correction::None
        );
    }

    #[test]
    fn nudges_medium_drift_then_restores_speed() {
        let mut c = Corrector::default();
        let t = timeline(true, 0.0, 0.0);
        assert_eq!(
            c.correct(&t, 10_000.0, snap(9_500.0, true)),
            Correction::SetRate(1.05)
        );
        assert_eq!(
            c.correct(&t, 10_000.0, snap(9_800.0, true)),
            Correction::None
        );
        assert_eq!(
            c.correct(&t, 10_000.0, snap(9_990.0, true)),
            Correction::SetRate(1.0)
        );
        assert_eq!(c.rate(), 1.0);
    }

    #[test]
    fn slows_down_when_ahead_and_stops_on_overshoot() {
        let mut c = Corrector::default();
        let t = timeline(true, 0.0, 0.0);
        assert_eq!(
            c.correct(&t, 10_000.0, snap(10_600.0, true)),
            Correction::SetRate(0.95)
        );
        // Overshot to behind: stop nudging rather than oscillate.
        assert_eq!(
            c.correct(&t, 10_000.0, snap(9_900.0, true)),
            Correction::SetRate(1.0)
        );
    }

    #[test]
    fn seeks_large_drift() {
        let mut c = Corrector::default();
        assert_eq!(
            c.correct(&timeline(true, 0.0, 0.0), 10_000.0, snap(4_000.0, true)),
            Correction::Seek(10_000.0)
        );
    }

    #[test]
    fn holds_paused_until_a_scheduled_start() {
        let mut c = Corrector::default();
        let t = timeline(true, 0.0, 5_000.0);
        assert_eq!(c.correct(&t, 4_000.0, snap(0.0, true)), Correction::Pause);
        assert_eq!(c.correct(&t, 4_000.0, snap(0.0, false)), Correction::None);
        assert_eq!(c.correct(&t, 5_010.0, snap(0.0, false)), Correction::Play);
        // Joining long after the start: seek and play together.
        assert_eq!(
            c.correct(&t, 65_000.0, snap(0.0, false)),
            Correction::SeekAndPlay(60_000.0)
        );
    }

    #[test]
    fn pauses_and_aligns_when_the_room_is_paused() {
        let mut c = Corrector::default();
        let t = timeline(false, 30_000.0, 0.0);
        assert_eq!(c.correct(&t, 1.0, snap(29_000.0, true)), Correction::Pause);
        assert_eq!(
            c.correct(&t, 1.0, snap(29_000.0, false)),
            Correction::Seek(30_000.0)
        );
    }

    #[test]
    fn newer_revisions_win_and_ties_break_by_member() {
        let mut a = timeline(true, 0.0, 0.0);
        let mut b = a.clone();
        b.rev = 2;
        assert!(b.supersedes(&a));
        a.rev = 2;
        a.by = MemberId::from_bytes([2; 16]);
        assert!(a.supersedes(&b));
        assert!(!b.supersedes(&a));
    }
}
