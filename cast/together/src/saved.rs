//! A device's place in a party, saved so the app can come back as the same
//! member after it was killed: a host as the host, a guest as the same guest
//! (so its own TVs still recognise it).
//!
//! It holds the room key and the member's private keys. Apps keep it in the
//! platform keystore, delete it when the room ends, and never log it.

use serde::{Deserialize, Serialize};
use zeroize::{Zeroize, Zeroizing};

use crate::{Error, Invite, MemberId, MemberKeys, Result, b64};

const VERSION: u8 = 1;

pub struct SavedParty {
    pub invite: Invite,
    pub keys: MemberKeys,
    pub me: MemberId,
}

#[derive(Serialize, Deserialize)]
struct Wire {
    v: u8,
    invite: String,
    keys: String,
    member: String,
}

impl Drop for Wire {
    fn drop(&mut self) {
        self.invite.zeroize();
        self.keys.zeroize();
    }
}

impl SavedParty {
    /// An opaque text the app stores and hands back to [`SavedParty::decode`].
    pub fn encode(&self) -> String {
        let keys = Zeroizing::new(self.keys.to_bytes());
        let wire = Wire {
            v: VERSION,
            invite: self.invite.to_link(),
            keys: b64::encode(keys.as_slice()),
            member: self.me.to_string(),
        };
        let json = Zeroizing::new(serde_json::to_vec(&wire).expect("plain fields serialize"));
        b64::encode(json.as_slice())
    }

    pub fn decode(text: &str) -> Result<Self> {
        let json =
            Zeroizing::new(b64::decode(text.trim()).map_err(|_| Error::Invalid("saved party"))?);
        let wire: Wire =
            serde_json::from_slice(&json).map_err(|_| Error::Invalid("saved party"))?;
        if wire.v != VERSION {
            return Err(Error::Invalid("saved party version"));
        }
        let raw =
            Zeroizing::new(b64::decode(&wire.keys).map_err(|_| Error::Invalid("saved keys"))?);
        let bytes: Zeroizing<[u8; 64]> = Zeroizing::new(
            raw.as_slice()
                .try_into()
                .map_err(|_| Error::Invalid("saved keys"))?,
        );
        Ok(Self {
            invite: Invite::parse(&wire.invite)?,
            keys: MemberKeys::from_bytes(&bytes),
            me: MemberId::parse(&wire.member)?,
        })
    }

    /// When the room ends (Unix ms), if its ID says. Rooms made before dated IDs
    /// don't say; apps then keep the party for the relay's room lifetime.
    pub fn ends_at_ms(&self) -> Option<f64> {
        self.invite.room.expires_at_ms()
    }

    /// Whether this device saved the party as its host.
    pub fn is_host(&self) -> bool {
        self.keys.signing_public() == self.invite.host
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{RoomId, RoomKey};

    fn party(host: bool) -> SavedParty {
        let keys = MemberKeys::generate();
        let host_key = if host {
            keys.signing_public()
        } else {
            MemberKeys::generate().signing_public()
        };
        SavedParty {
            invite: Invite {
                relay: url::Url::parse("https://relay.example.com").unwrap(),
                room: RoomId::dated(1_800_000_000_000.0),
                key: RoomKey::generate(),
                host: host_key,
            },
            keys,
            me: MemberId::generate(),
        }
    }

    #[test]
    fn round_trips_the_same_member_keys_and_room() {
        let saved = party(true);
        let back = SavedParty::decode(&saved.encode()).unwrap();
        assert_eq!(back.me, saved.me);
        assert_eq!(back.keys.to_bytes(), saved.keys.to_bytes());
        assert_eq!(back.invite.to_link(), saved.invite.to_link());
        assert_eq!(back.ends_at_ms(), Some(1_800_000_000_000.0));
        assert!(back.is_host());
        assert!(
            !SavedParty::decode(&party(false).encode())
                .unwrap()
                .is_host()
        );
    }

    #[test]
    fn refuses_damaged_or_unknown_text() {
        assert!(SavedParty::decode("").is_err());
        assert!(SavedParty::decode("not base64!").is_err());
        assert!(SavedParty::decode(&b64::encode(br#"{"v":9}"#)).is_err());
        let wire =
            br#"{"v":1,"invite":"https://relay.example.com/j/00#k=x","keys":"AA","member":"00"}"#;
        assert!(SavedParty::decode(&b64::encode(wire)).is_err());
    }
}
