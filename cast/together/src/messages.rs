//! Encrypted room messages (inside `ct`). Every plaintext is a [`Signed`]
//! wrapper so host-only messages can carry an Ed25519 signature over their
//! exact JSON bytes.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::ids::MemberId;
use crate::sync::Timeline;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeviceKind {
    Tv,
    Phone,
    Desktop,
    Cli,
}

/// Where the video comes from. Each member resolves it itself.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Source {
    /// A direct media link (signed links included).
    Url { url: String },
    /// A web page each receiver extracts the video from.
    Page { url: String },
    /// A Bridged App item, for example Invidious or Jellyfin.
    App { app: String, id: String },
}

/// The shared playback object. Never contains `Cookie` or `Authorization`;
/// those travel separately as [`Body::Credentials`].
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SharedItem {
    /// Unique per load.
    pub id: String,
    pub title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub site: Option<String>,
    pub source: Source,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub headers: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub subtitles: Vec<String>,
    #[serde(default)]
    pub start_ms: u64,
    /// The member whose video this is; its owner's credentials apply.
    pub shared_by: MemberId,
}

/// Headers that must never be broadcast with the room key.
pub const SECRET_HEADERS: [&str; 3] = ["cookie", "authorization", "proxy-authorization"];

impl SharedItem {
    /// Moves secret headers out, returning them.
    pub fn take_secrets(&mut self) -> BTreeMap<String, String> {
        let mut secrets = BTreeMap::new();
        self.headers.retain(|name, value| {
            if SECRET_HEADERS.contains(&name.to_ascii_lowercase().as_str()) {
                secrets.insert(name.clone(), std::mem::take(value));
                false
            } else {
                true
            }
        });
        secrets
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ChangePolicy {
    #[default]
    OnlyHost,
    Ask,
    Anyone,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "k", rename_all = "snake_case")]
pub enum Body {
    /// Signed with the member's own `sign_pk`, and names the sender, so a
    /// member cannot claim someone else's key (for example the host's).
    Hello {
        member: MemberId,
        name: String,
        kind: DeviceKind,
        /// Whether this member plays the video (false for a remote-only phone).
        #[serde(default = "yes")]
        screen: bool,
        box_pk: String,
        sign_pk: String,
    },
    /// Host-signed, or unsigned when the policy is `Anyone`.
    Load {
        item: SharedItem,
    },
    Ready {
        item: String,
        #[serde(default)]
        duration_ms: Option<u64>,
        #[serde(default)]
        rtt_ms: Option<f64>,
    },
    Buffering {
        item: String,
        buffering: bool,
    },
    Position {
        item: String,
        position_ms: f64,
    },
    /// Host-signed.
    Lock {
        locked: bool,
    },
    /// Host-signed.
    Policy {
        change: ChangePolicy,
    },
    /// A guest asks the host to play `item`.
    RequestPlay {
        request: String,
        item: SharedItem,
    },
    /// Host-signed.
    RequestReply {
        request: String,
        to: MemberId,
        allowed: bool,
    },
    /// Host-signed grant of host duties to one of the host's own devices.
    Delegate {
        member: MemberId,
        sign_pk: String,
    },
    /// Sent to one member: secret headers sealed to its box key.
    Credentials {
        item: String,
        sealed: String,
    },
    Leave,
}

fn yes() -> bool {
    true
}

/// Wire plaintext: `b` is the JSON of a [`Body`] or [`Snapshot`], `s` an
/// optional signature over exactly those bytes.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Signed {
    pub b: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub s: Option<String>,
}

/// The latest room state, kept by the relay for late joiners. Host-only
/// pieces are kept as their original signed messages so joiners can verify
/// them.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Snapshot {
    #[serde(default)]
    pub timeline: Option<Timeline>,
    #[serde(default)]
    pub load: Option<Signed>,
    #[serde(default)]
    pub lock: Option<Signed>,
    #[serde(default)]
    pub policy: Option<Signed>,
    #[serde(default)]
    pub delegates: Vec<Signed>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secrets_are_split_from_shared_headers() {
        let mut item = SharedItem {
            id: "1".into(),
            title: "t".into(),
            site: None,
            source: Source::Url {
                url: "https://v".into(),
            },
            headers: BTreeMap::from([
                ("Cookie".into(), "a=b".into()),
                ("Referer".into(), "https://r".into()),
                ("authorization".into(), "Bearer x".into()),
            ]),
            subtitles: vec![],
            start_ms: 0,
            shared_by: MemberId::from_bytes([1; 16]),
        };
        let secrets = item.take_secrets();
        assert_eq!(item.headers.keys().collect::<Vec<_>>(), ["Referer"]);
        assert_eq!(secrets.len(), 2);
    }

    #[test]
    fn bodies_use_the_k_tag() {
        let json = serde_json::to_string(&Body::Lock { locked: true }).unwrap();
        assert_eq!(json, r#"{"k":"lock","locked":true}"#);
    }
}
