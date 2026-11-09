//! Invite links: `https://<relay>/j/<room id>#k=<room key>&h=<host signing key>`.
//!
//! The fragment never leaves the device that opens the link, so the relay
//! only ever sees the room ID.

use url::Url;

use crate::b64;
use crate::crypto::RoomKey;
use crate::ids::RoomId;
use crate::{Error, Result};

#[derive(Clone, Debug)]
pub struct Invite {
    /// Relay base address, for example `https://together.example.workers.dev`.
    pub relay: Url,
    pub room: RoomId,
    pub key: RoomKey,
    /// The host's Ed25519 public key; host-only messages must verify against it.
    pub host: [u8; 32],
}

impl Invite {
    pub fn to_link(&self) -> String {
        let mut url = self.relay.clone();
        url.set_path(&format!(
            "{}/j/{}",
            self.relay.path().trim_end_matches('/'),
            self.room
        ));
        url.set_query(None);
        url.set_fragment(Some(&format!(
            "k={}&h={}",
            b64::encode(self.key.as_bytes()),
            b64::encode(self.host)
        )));
        url.to_string()
    }

    pub fn parse(link: &str) -> Result<Self> {
        let url = Url::parse(link.trim()).map_err(|_| Error::Invalid("invite link"))?;
        check_relay_scheme(&url)?;
        let path = url.path();
        let (base, room) = path
            .rsplit_once("/j/")
            .ok_or(Error::Invalid("invite link"))?;
        let room = RoomId::parse(room)?;
        let fragment = url.fragment().ok_or(Error::Invalid("invite key"))?;
        let mut key = None;
        let mut host = None;
        for part in fragment.split('&') {
            match part.split_once('=') {
                Some(("k", value)) => key = Some(RoomKey::from_bytes(b64::decode_32(value)?)),
                Some(("h", value)) => host = Some(b64::decode_32(value)?),
                _ => {}
            }
        }
        let mut relay = url.clone();
        relay.set_path(if base.is_empty() { "/" } else { base });
        relay.set_query(None);
        relay.set_fragment(None);
        Ok(Self {
            relay,
            room,
            key: key.ok_or(Error::Invalid("invite key"))?,
            host: host.ok_or(Error::Invalid("invite host"))?,
        })
    }

    /// WebSocket address for a member of this room.
    pub fn socket_url(&self, member: &crate::MemberId) -> Url {
        let mut url = self.relay.clone();
        let scheme = if url.scheme() == "http" { "ws" } else { "wss" };
        url.set_scheme(scheme)
            .expect("ws and wss are valid schemes");
        url.set_path(&format!(
            "{}/ws/{}",
            self.relay.path().trim_end_matches('/'),
            self.room
        ));
        url.set_query(Some(&format!("member={member}")));
        url.set_fragment(None);
        url
    }
}

/// Relays must use HTTPS, except plain HTTP on loopback for local development.
pub fn check_relay_scheme(url: &Url) -> Result<()> {
    match url.scheme() {
        "https" => Ok(()),
        "http" if matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "[::1]")) => Ok(()),
        _ => Err(Error::Invalid("relay must use https")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(relay: &str) -> Invite {
        Invite {
            relay: Url::parse(relay).unwrap(),
            room: RoomId::from_bytes([0xab; 16]),
            key: RoomKey::from_bytes([7; 32]),
            host: [9; 32],
        }
    }

    #[test]
    fn links_round_trip() {
        let invite = sample("https://together.example.workers.dev");
        let link = invite.to_link();
        assert!(link.starts_with("https://together.example.workers.dev/j/abababab"));
        let parsed = Invite::parse(&link).unwrap();
        assert_eq!(parsed.room, invite.room);
        assert_eq!(parsed.key.as_bytes(), invite.key.as_bytes());
        assert_eq!(parsed.host, invite.host);
        assert_eq!(
            parsed.relay.as_str(),
            "https://together.example.workers.dev/"
        );
    }

    #[test]
    fn relays_under_a_path_keep_it() {
        let invite = sample("https://example.com/relay/");
        let parsed = Invite::parse(&invite.to_link()).unwrap();
        assert_eq!(parsed.relay.path(), "/relay");
        let socket = parsed.socket_url(&crate::MemberId::from_bytes([1; 16]));
        assert!(
            socket
                .as_str()
                .starts_with("wss://example.com/relay/ws/abab")
        );
    }

    #[test]
    fn rejects_plain_http_off_loopback_and_missing_keys() {
        assert!(Invite::parse(&sample("http://example.com").to_link()).is_err());
        assert!(Invite::parse(&sample("http://127.0.0.1:8787").to_link()).is_ok());
        assert!(Invite::parse("https://example.com/j/abababababababababababababababab").is_err());
    }
}
