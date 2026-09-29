//! Optional, session-only playback webhooks. No provider accounts or persistence.
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    fmt,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    time::{Duration, Instant},
};
use tokio::sync::mpsc;
use url::Url;

#[derive(Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProgressWebhook {
    pub url: String,
    pub bearer_token: String,
}
impl fmt::Debug for ProgressWebhook {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ProgressWebhook([redacted])")
    }
}
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProgressIdentity {
    pub r#type: String,
    pub content_id: String,
    pub video_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub season: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub episode: Option<u32>,
}
impl ProgressIdentity {
    pub fn valid(&self) -> bool {
        matches!(self.r#type.as_str(), "movie" | "series")
            && [&self.content_id, &self.video_id]
                .iter()
                .all(|s| !s.is_empty() && s.len() <= 256)
            && (self.r#type == "movie" || (self.season.is_some() && self.episode.is_some()))
    }
}
impl ProgressWebhook {
    pub fn validated_url(&self) -> Option<Url> {
        if self.url.len() > 2048
            || self.bearer_token.is_empty()
            || self.bearer_token.len() > 4096
            || !self
                .bearer_token
                .bytes()
                .all(|b| (0x21..=0x7e).contains(&b))
        {
            return None;
        }
        let url = Url::parse(&self.url).ok()?;
        if url.scheme() != "https"
            || !url.username().is_empty()
            || url.password().is_some()
            || url.fragment().is_some()
            || url.query().is_some()
            || url.port_or_known_default() != Some(443)
        {
            return None;
        }
        let host = url.host_str()?.trim_matches(['[', ']']).to_lowercase();
        if !host.contains('.') && !host.contains(':')
            || host.ends_with('.')
            || host.ends_with(".local")
            || host == "localhost"
            || host.ends_with(".localhost")
        {
            return None;
        }
        if host.parse::<IpAddr>().is_ok_and(|ip| !public_address(ip)) {
            return None;
        }
        Some(url)
    }
}
/// Conservative public-unicast policy, including IPv4-mapped and transition IPv6.
pub fn public_address(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => public_v4(ip),
        IpAddr::V6(ip) => {
            if let Some(v4) = ip.to_ipv4_mapped() {
                return public_v4(v4);
            }
            let s = ip.segments();
            (s[0] & 0xe000) == 0x2000
                && s[0] != 0x2002
                && !(s[0] == 0x2001 && (s[1] <= 0x1ff || s[1] == 0xdb8))
                && !(s[0] == 0x3fff && s[1] <= 0x0fff)
        }
    }
}
fn public_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(a == 0
        || a == 10
        || a == 127
        || a >= 224
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2))))
        || (a == 192 && b == 88 && c == 99)
        || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
        || (a == 203 && b == 0 && c == 113))
}
fn random_id() -> String {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).expect("OS randomness unavailable");
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
struct Job {
    webhook: ProgressWebhook,
    body: Value,
    created: Instant,
}
/// Reporter lifecycle is owned by the player host, not the sender connection.
pub struct ProgressReporter {
    webhook: Option<ProgressWebhook>,
    playback_id: String,
    item_id: String,
    identity: Option<ProgressIdentity>,
    position: u64,
    duration: u64,
    last_state: String,
    last_sent: Option<Instant>,
    sender: mpsc::Sender<Job>,
}
impl Default for ProgressReporter {
    fn default() -> Self {
        Self::new()
    }
}
impl ProgressReporter {
    pub fn new() -> Self {
        let (sender, mut receiver) = mpsc::channel::<Job>(32);
        tokio::spawn(async move {
            while let Some(job) = receiver.recv().await {
                if job.created.elapsed() > Duration::from_secs(90) {
                    continue;
                }
                for attempt in 0..3 {
                    if attempt > 0 {
                        tokio::time::sleep(Duration::from_secs(1 << attempt)).await;
                    }
                    if job.created.elapsed() > Duration::from_secs(90) {
                        break;
                    }
                    match deliver(&job).await {
                        Ok(true) | Err(false) => break,
                        _ => {}
                    }
                }
            }
        });
        Self {
            webhook: None,
            playback_id: String::new(),
            item_id: String::new(),
            identity: None,
            position: 0,
            duration: 0,
            last_state: String::new(),
            last_sent: None,
            sender,
        }
    }
    pub fn start(
        &mut self,
        config: Option<ProgressWebhook>,
        item_id: String,
        identity: Option<ProgressIdentity>,
    ) {
        self.finish("stopped");
        self.webhook = config.filter(|c| c.validated_url().is_some());
        self.playback_id = random_id();
        self.select(item_id, identity);
    }
    pub fn select(&mut self, item_id: String, identity: Option<ProgressIdentity>) {
        if self.item_id == item_id && self.identity == identity {
            return;
        }
        self.emit("stopped");
        self.item_id = item_id;
        self.identity = identity.filter(ProgressIdentity::valid);
        self.position = 0;
        self.duration = 0;
        self.last_state.clear();
        self.last_sent = None;
    }
    pub fn observe(&mut self, state: &str, position: u64, duration: u64) {
        let terminal = matches!(state, "idle" | "stopped" | "ended" | "finished" | "error");
        if duration > 0 {
            if !(terminal && position == 0 && self.position > 0) {
                self.position = position.min(duration);
            }
            self.duration = duration;
        }
        if terminal {
            if self.duration == 0 {
                return;
            }
            if self.last_state != "terminal" {
                self.emit(if matches!(state, "ended" | "finished") {
                    "ended"
                } else {
                    "stopped"
                });
            }
            self.last_state = "terminal".into();
            return;
        }
        if duration == 0 || self.webhook.is_none() || self.identity.is_none() {
            return;
        }
        if self.last_state == "terminal" {
            self.last_state.clear();
        }
        match state {
            "playing" => {
                if self.last_state != "playing" {
                    self.emit("started");
                } else if self
                    .last_sent
                    .is_none_or(|t| t.elapsed() >= Duration::from_secs(30))
                {
                    self.emit("progress");
                }
                self.last_state = state.into();
            }
            "paused" if self.last_state != "paused" => {
                self.emit("paused");
                self.last_state = state.into();
            }
            _ => {}
        }
    }
    fn emit(&mut self, event: &str) {
        if self.duration == 0 || self.last_state == "terminal" {
            return;
        }
        let (Some(webhook), Some(identity)) = (&self.webhook, &self.identity) else {
            return;
        };
        let body = json!({"version":1,"eventId":random_id(),"playbackId":self.playback_id,
            "itemId":self.item_id,"event":event,"content":identity,"positionMs":self.position,
            "durationMs":self.duration,"occurredAt":chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis,true)});
        if self
            .sender
            .try_send(Job {
                webhook: webhook.clone(),
                body,
                created: Instant::now(),
            })
            .is_ok()
        {
            self.last_sent = Some(Instant::now());
        }
    }
    pub fn finish(&mut self, event: &str) {
        self.emit(event);
        self.webhook = None;
        self.identity = None;
        self.item_id.clear();
        self.position = 0;
        self.duration = 0;
        self.last_state.clear();
        self.last_sent = None;
    }
    pub fn playback_id(&self) -> &str {
        &self.playback_id
    }
}
impl Drop for ProgressReporter {
    fn drop(&mut self) {
        self.finish("stopped");
    }
}
// true error = retryable. Never return network error strings containing the URL/token.
async fn deliver(job: &Job) -> Result<bool, bool> {
    let url = job.webhook.validated_url().ok_or(false)?;
    let host = url.host_str().ok_or(false)?.trim_matches(['[', ']']);
    let port = url.port_or_known_default().ok_or(false)?;
    let addresses: Vec<SocketAddr> = tokio::time::timeout(
        Duration::from_secs(5),
        tokio::net::lookup_host((host, port)),
    )
    .await
    .map_err(|_| true)?
    .map_err(|_| true)?
    .collect();
    if addresses.is_empty() || addresses.iter().any(|a| !public_address(a.ip())) {
        return Err(false);
    }
    // Connect to exactly the validated addresses. Keep original host for certificate/SNI verification.
    let client = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(8))
        .resolve_to_addrs(host, &addresses)
        .build()
        .map_err(|_| false)?;
    let response = client
        .post(url)
        .bearer_auth(&job.webhook.bearer_token)
        .header("content-type", "application/json")
        .header("cache-control", "no-store")
        .body(job.body.to_string())
        .send()
        .await
        .map_err(|_| true)?;
    if response.status().is_success() {
        Ok(true)
    } else if response.status().is_server_error() || response.status().as_u16() == 429 {
        Err(true)
    } else {
        Err(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_private_and_nonpublic_addresses() {
        for address in [
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "192.168.0.1",
            "198.18.0.1",
            "224.0.0.1",
            "::1",
            "::ffff:127.0.0.1",
            "fc00::1",
            "fe80::1",
            "2002:7f00:1::",
            "2001:db8::1",
        ] {
            assert!(!public_address(address.parse().unwrap()), "{address}");
        }
        assert!(public_address("1.1.1.1".parse().unwrap()));
        assert!(public_address("2606:4700:4700::1111".parse().unwrap()));
    }
    #[test]
    fn config_rejects_unsafe_urls_headers_and_redacts_debug() {
        let mut c = ProgressWebhook {
            url: "https://sync.example.com/progress".into(),
            bearer_token: "secret".into(),
        };
        assert!(c.validated_url().is_some());
        assert!(!format!("{c:?}").contains("secret"));
        for url in [
            "http://sync.example.com",
            "https://user:pass@sync.example.com",
            "https://127.0.0.1",
            "https://[::1]",
            "https://sync.local",
            "https://sync.example.com/#token",
        ] {
            c.url = url.into();
            assert!(c.validated_url().is_none(), "{url}");
        }
        c.url = "https://sync.example.com".into();
        c.bearer_token = "abc\r\nX: y".into();
        assert!(c.validated_url().is_none());
    }
    #[tokio::test]
    async fn lifecycle_routes_episode_and_retains_final_sample_without_network() {
        let (sender, mut receiver) = mpsc::channel(32);
        let mut r = ProgressReporter::new();
        r.sender = sender;
        let identity = ProgressIdentity {
            r#type: "series".into(),
            content_id: "tt1".into(),
            video_id: "tt1:1:2".into(),
            season: Some(1),
            episode: Some(2),
        };
        r.start(
            Some(ProgressWebhook {
                url: "https://sync.example.com".into(),
                bearer_token: "secret".into(),
            }),
            "ep2".into(),
            Some(identity.clone()),
        );
        r.observe("playing", 12_000, 30_000);
        r.observe("playing", 13_000, 30_000);
        r.observe("paused", 14_000, 30_000);
        r.observe("stopped", 0, 30_000);
        r.finish("stopped");
        let started = receiver.try_recv().unwrap();
        assert_eq!(started.body["event"], "started");
        let paused = receiver.try_recv().unwrap();
        assert_eq!(paused.body["positionMs"], 14_000);
        let stopped = receiver.try_recv().unwrap();
        assert_eq!(stopped.body["positionMs"], 14_000);
        assert_eq!(stopped.body["content"]["videoId"], identity.video_id);
        assert!(receiver.try_recv().is_err());
        assert_ne!(started.body["eventId"], stopped.body["eventId"]);
        assert_eq!(started.body["playbackId"], stopped.body["playbackId"]);
    }
}
