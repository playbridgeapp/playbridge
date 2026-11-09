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
}
