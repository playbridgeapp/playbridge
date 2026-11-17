//! Room and member IDs: 16 random bytes, written as 32 lowercase hex characters.

use std::fmt;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::crypto::random_bytes;
use crate::{Error, Result};

macro_rules! hex_id {
    ($name:ident) => {
        #[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
        pub struct $name([u8; 16]);

        impl $name {
            pub fn generate() -> Self {
                Self(random_bytes())
            }

            pub fn from_bytes(bytes: [u8; 16]) -> Self {
                Self(bytes)
            }

            pub fn as_bytes(&self) -> &[u8; 16] {
                &self.0
            }

            pub fn parse(text: &str) -> Result<Self> {
                if text.len() != 32 || !text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) {
                    return Err(Error::Invalid(stringify!($name)));
                }
                let mut bytes = [0_u8; 16];
                for (i, byte) in bytes.iter_mut().enumerate() {
                    *byte = u8::from_str_radix(&text[i * 2..i * 2 + 2], 16).expect("checked hex");
                }
                Ok(Self(bytes))
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                for byte in self.0 {
                    write!(f, "{byte:02x}")?;
                }
                Ok(())
            }
        }

        impl fmt::Debug for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(f, "{}({})", stringify!($name), &self.to_string()[..8])
            }
        }

        impl Serialize for $name {
            fn serialize<S: Serializer>(&self, s: S) -> std::result::Result<S::Ok, S::Error> {
                s.collect_str(self)
            }
        }

        impl<'de> Deserialize<'de> for $name {
            fn deserialize<D: Deserializer<'de>>(d: D) -> std::result::Result<Self, D::Error> {
                let text = String::deserialize(d)?;
                Self::parse(&text).map_err(serde::de::Error::custom)
            }
        }
    };
}

hex_id!(RoomId);

/// The first bytes of a room ID that carries its own expiry. The relay refuses such a
/// room once the time has passed, so an old invite link cannot start a new room.
const DATED_MARKER: [u8; 3] = [0xe7, 0x1d, 0xa7];
const MINUTE_MS: f64 = 60_000.0;

impl RoomId {
    /// A random ID with no expiry in it, for relays that do not check one.
    pub fn undated() -> Self {
        loop {
            let id = Self::generate();
            if id.0[..3] != DATED_MARKER {
                return id;
            }
        }
    }

    /// An ID for a room that ends at `expires_at_ms` (Unix time, on the relay's clock),
    /// rounded down to the minute.
    pub fn dated(expires_at_ms: f64) -> Self {
        let minutes = (expires_at_ms / MINUTE_MS)
            .floor()
            .clamp(0.0, f64::from(u32::MAX)) as u32;
        let mut bytes: [u8; 16] = random_bytes();
        bytes[..3].copy_from_slice(&DATED_MARKER);
        bytes[3..7].copy_from_slice(&minutes.to_be_bytes());
        Self(bytes)
    }

    /// When this room ends, if its ID says so.
    pub fn expires_at_ms(&self) -> Option<f64> {
        if self.0[..3] != DATED_MARKER {
            return None;
        }
        let minutes = u32::from_be_bytes(self.0[3..7].try_into().expect("four bytes"));
        Some(f64::from(minutes) * MINUTE_MS)
    }
}
hex_id!(MemberId);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_round_trip_through_hex() {
        let id = RoomId::generate();
        assert_eq!(RoomId::parse(&id.to_string()).unwrap(), id);
        assert!(RoomId::parse("ABCDEF00000000000000000000000000").is_err());
        assert!(MemberId::parse("00").is_err());
    }

    #[test]
    fn dated_room_ids_carry_their_expiry_to_the_minute() {
        let expires = 1_800_000_000_000.0 + 59_999.0;
        let id = RoomId::dated(expires);
        assert_eq!(id.expires_at_ms(), Some(1_800_000_000_000.0));
        assert_eq!(
            RoomId::parse(&id.to_string()).unwrap().expires_at_ms(),
            id.expires_at_ms()
        );
        assert_ne!(RoomId::dated(expires), id, "the rest of the ID is random");
        assert!(id.to_string().starts_with("e71da7"));
        for _ in 0..64 {
            assert_eq!(RoomId::undated().expires_at_ms(), None);
        }
    }
}
