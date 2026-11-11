//! Receiver pairing: a TV shows a short code (and QR); a phone in the room
//! seals the invite to the TV's key and drops it at the pairing service.

use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use url::Url;

use crate::b64;
use crate::crypto::{self, MemberKeys};
use crate::invite::check_relay_scheme;
use crate::{Error, Result};

/// A code this receiver holds.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Lobby {
    pub code: String,
    pub token: String,
    pub expires_at: f64,
}

/// What the receiver gets once a phone sends an invite.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PairingInvite {
    /// The full invite link, including its key fragment.
    pub link: String,
    /// Who sent it, shown in the "Join … watch party?" prompt.
    pub from: String,
}

/// What a QR code shown by a receiver carries.
#[derive(Clone, Debug, PartialEq)]
pub struct QrPayload {
    pub pairing: Url,
    pub code: String,
    pub fingerprint: String,
}

impl QrPayload {
    /// `https://<pairing>/p/<code>#fp=<fingerprint>`
    pub fn to_link(&self) -> String {
        let mut url = self.pairing.clone();
        url.set_path(&format!(
            "{}/p/{}",
            self.pairing.path().trim_end_matches('/'),
            self.code
        ));
        url.set_fragment(Some(&format!("fp={}", self.fingerprint)));
        url.to_string()
    }

    pub fn parse(link: &str) -> Result<Self> {
        let url = Url::parse(link.trim()).map_err(|_| Error::Invalid("pairing link"))?;
        check_relay_scheme(&url)?;
        let (base, code) = url
            .path()
            .rsplit_once("/p/")
            .ok_or(Error::Invalid("pairing link"))?;
        let fingerprint = url
            .fragment()
            .and_then(|f| f.strip_prefix("fp="))
            .ok_or(Error::Invalid("pairing fingerprint"))?
            .to_owned();
        let mut pairing = url.clone();
        pairing.set_path(if base.is_empty() { "/" } else { base });
        pairing.set_fragment(None);
        Ok(Self {
            pairing,
            code: normalize_code(code).ok_or(Error::Invalid("pairing code"))?,
            fingerprint,
        })
    }
}

pub const CODE_ALPHABET: &str = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

/// Upper-cases and strips spaces and dashes; `None` if it cannot be a code.
pub fn normalize_code(raw: &str) -> Option<String> {
    let code: String = raw
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .collect::<String>()
        .to_uppercase();
    (code.len() == 6 && code.chars().all(|c| CODE_ALPHABET.contains(c))).then_some(code)
}

fn context(code: &str) -> Vec<u8> {
    let mut context = b"pb-together v1 pairing".to_vec();
    context.extend_from_slice(code.as_bytes());
    context
}

pub struct PairingService {
    base: Url,
    http: reqwest::Client,
}

#[derive(Deserialize)]
struct LookupBody {
    pk: String,
}

impl PairingService {
    pub fn new(base: &str) -> Result<Self> {
        let base = Url::parse(base).map_err(|_| Error::Invalid("pairing url"))?;
        check_relay_scheme(&base)?;
        Ok(Self {
            base,
            http: crate::tls::http_client(),
        })
    }

    pub fn base(&self) -> &Url {
        &self.base
    }

    fn url(&self, path: &str) -> Url {
        let mut url = self.base.clone();
        url.set_path(&format!(
            "{}{}",
            self.base.path().trim_end_matches('/'),
            path
        ));
        url
    }

    // -- receiver side

    pub async fn register(&self, keys: &MemberKeys) -> Result<Lobby> {
        let response = self
            .http
            .post(self.url("/lobby"))
            .json(&serde_json::json!({ "pk": b64::encode(keys.box_public()) }))
            .send()
            .await
            .map_err(network)?;
        decode(response).await
    }

    pub fn qr(&self, lobby: &Lobby, keys: &MemberKeys) -> QrPayload {
        QrPayload {
            pairing: self.base.clone(),
            code: lobby.code.clone(),
            fingerprint: crypto::fingerprint(&keys.box_public()),
        }
    }

    /// Waits for the first invite this receiver can open. Invites that fail
    /// to open (not for us, or tampered) are skipped.
    pub async fn wait(&self, lobby: &Lobby, keys: &MemberKeys) -> Result<PairingInvite> {
        let mut url = self.url(&format!("/lobby/{}/ws", lobby.code));
        url.set_scheme(if url.scheme() == "http" { "ws" } else { "wss" })
            .expect("ws schemes are valid");
        let mut request = url
            .as_str()
            .into_client_request()
            .map_err(|_| Error::Invalid("pairing url"))?;
        request.headers_mut().insert(
            "authorization",
            HeaderValue::from_str(&format!("Bearer {}", lobby.token))
                .map_err(|_| Error::Invalid("token"))?,
        );
        let (mut ws, _) = crate::tls::connect(request).await?;
        while let Some(message) = ws.next().await {
            let Message::Text(text) = message? else {
                continue;
            };
            let frame: serde_json::Value =
                serde_json::from_str(&text).map_err(|_| Error::Relay("bad frame".into()))?;
            match frame.get("t").and_then(|t| t.as_str()) {
                Some("invite") => {
                    let Some(sealed) = frame.get("sealed").and_then(|s| s.as_str()) else {
                        continue;
                    };
                    let Ok(plain) = keys.open_sealed(sealed, &context(&lobby.code)) else {
                        continue;
                    };
                    if let Ok(invite) = serde_json::from_slice::<PairingInvite>(&plain)
                        && crate::Invite::parse(&invite.link).is_ok()
                    {
                        return Ok(invite);
                    }
                }
                Some("expired") => return Err(Error::Relay("code expired".into())),
                _ => {}
            }
        }
        Err(Error::Network("pairing connection closed".into()))
    }

    pub async fn release(&self, lobby: &Lobby) -> Result<()> {
        self.http
            .delete(self.url(&format!("/lobby/{}", lobby.code)))
            .bearer_auth(&lobby.token)
            .send()
            .await
            .map_err(network)?;
        Ok(())
    }

    // -- phone side

    /// Seals `invite` to the receiver holding `code` and delivers it. With a
    /// QR fingerprint, refuses a key that does not match it. Returns the
    /// receiver's box key, so the sender can treat it as its own device.
    pub async fn send_invite(
        &self,
        code: &str,
        invite: &PairingInvite,
        fingerprint: Option<&str>,
    ) -> Result<[u8; 32]> {
        let code = normalize_code(code).ok_or(Error::Invalid("pairing code"))?;
        let response = self
            .http
            .get(self.url(&format!("/lobby/{code}")))
            .send()
            .await
            .map_err(network)?;
        let body: LookupBody = decode(response).await?;
        let pk = b64::decode_32(&body.pk)?;
        if let Some(expected) = fingerprint
            && crypto::fingerprint(&pk) != expected
        {
            return Err(Error::Crypto);
        }
        let plain = serde_json::to_vec(invite).expect("invite serializes");
        let sealed = crypto::seal_to(&pk, &plain, &context(&code));
        let response = self
            .http
            .post(self.url(&format!("/lobby/{code}/invite")))
            .json(&serde_json::json!({ "sealed": sealed }))
            .send()
            .await
            .map_err(network)?;
        decode::<serde_json::Value>(response).await.map(|_| pk)
    }
}

fn network(error: reqwest::Error) -> Error {
    Error::Network(error.to_string())
}

async fn decode<T: serde::de::DeserializeOwned>(response: reqwest::Response) -> Result<T> {
    let status = response.status();
    if !status.is_success() {
        let body: serde_json::Value = response.json().await.unwrap_or_default();
        let code = body
            .get("error")
            .and_then(|e| e.as_str())
            .unwrap_or("error");
        return Err(Error::Relay(format!("{} ({code})", status.as_u16())));
    }
    response.json().await.map_err(network)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_normalize() {
        assert_eq!(normalize_code("abc-234").as_deref(), Some("ABC234"));
        assert_eq!(normalize_code("ab c2 34").as_deref(), Some("ABC234"));
        assert_eq!(normalize_code("ABC23"), None);
        assert_eq!(normalize_code("ABC10O"), None);
    }

    #[test]
    fn qr_links_round_trip() {
        let qr = QrPayload {
            pairing: Url::parse("https://pair.example.com").unwrap(),
            code: "ABC234".into(),
            fingerprint: "Zm9vYmFyYmF6cXV4".into(),
        };
        let parsed = QrPayload::parse(&qr.to_link()).unwrap();
        assert_eq!(parsed.code, "ABC234");
        assert_eq!(parsed.fingerprint, qr.fingerprint);
        assert_eq!(parsed.pairing.as_str(), "https://pair.example.com/");
    }
}
