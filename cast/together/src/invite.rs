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

/// How long [`new_room_id`] waits for the relay before settling for an undated ID.
const RELAY_INFO_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);

/// The ID for a party about to start on `relay`. A relay that reports its clock gets an
/// ID that ends with the party, so the invite link stops working then for good. Older
/// relays, and ones that do not answer in time, get a plain random ID as before.
pub async fn new_room_id(relay: &Url) -> RoomId {
    #[derive(serde::Deserialize)]
    struct Info {
        now: Option<f64>,
        room_lifetime_ms: Option<f64>,
    }
    let mut url = relay.clone();
    url.set_path(&format!("{}/version", relay.path().trim_end_matches('/')));
    url.set_query(None);
    url.set_fragment(None);
    let info = async {
        let response = crate::tls::http_client().get(url).send().await.ok()?;
        response.json::<Info>().await.ok()
    };
    match tokio::time::timeout(RELAY_INFO_TIMEOUT, info).await {
        Ok(Some(Info {
            now: Some(now),
            room_lifetime_ms: Some(lifetime),
        })) if now > 0.0 && lifetime > 0.0 => RoomId::dated(now + lifetime),
        _ => RoomId::undated(),
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

    /// Serves one HTTP response on loopback and returns the relay address for it.
    async fn relay_answering(body: &'static str) -> Url {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = [0_u8; 1024];
            let _ = stream.read(&mut request).await;
            let response = format!(
                "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = stream.write_all(response.as_bytes()).await;
        });
        Url::parse(&format!("http://{addr}")).unwrap()
    }

    #[tokio::test]
    async fn a_new_room_is_dated_from_the_relays_clock_and_lifetime() {
        let relay = relay_answering(r#"{"now":1800000000000,"room_lifetime_ms":10800000}"#).await;
        let room = new_room_id(&relay).await;
        assert_eq!(room.expires_at_ms(), Some(1_800_010_800_000.0));
    }

    #[tokio::test]
    async fn a_relay_that_reports_no_clock_gets_an_undated_room() {
        let old = relay_answering(r#"{"room_lifetime_ms":10800000}"#).await;
        assert_eq!(new_room_id(&old).await.expires_at_ms(), None);
        // Nothing listening at all.
        let gone = Url::parse("http://127.0.0.1:9").unwrap();
        assert_eq!(new_room_id(&gone).await.expires_at_ms(), None);
    }
}
