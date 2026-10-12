//! Android TV Remote v2 client.
//!
//! Pairing uses port 6467 and control uses port 6466. This is a separate
//! protocol from Google Cast. The caller owns certificates and pins.

mod control;
mod discovery;
mod error;
mod framed;
mod identity;
mod pairing;
mod proto;
mod tls;

pub use control::{AtvState, ControlSession};
pub use discovery::{AtvDevice, discover};
pub use error::AtvError;
pub use identity::{
    ClientIdentity, fingerprint_hex, fingerprint_sha256, mac_from_certificate, pairing_secret,
};
pub use pairing::{PairingResult, PairingSession, start_pairing, start_pairing_timeout};
pub use proto::{
    DIR_END_LONG, DIR_SHORT, DIR_START_LONG, KEY_BACK, KEY_DPAD_CENTER, KEY_DPAD_DOWN,
    KEY_DPAD_LEFT, KEY_DPAD_RIGHT, KEY_DPAD_UP, KEY_ENTER, KEY_HOME, KEY_MUTE, KEY_POWER,
    KEY_VOLUME_DOWN, KEY_VOLUME_MUTE, KEY_VOLUME_UP, key_code_from_name,
};
pub use tls::AtvTimeouts;

pub const PAIRING_PORT: u16 = 6467;
pub const CONTROL_PORT: u16 = 6466;

#[cfg(test)]
mod tests;
