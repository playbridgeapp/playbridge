use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

use axum::{
    Router,
    extract::{
        State,
        ws::{Message, WebSocket, WebSocketUpgrade},
    },
    http::{HeaderValue, header},
    response::{Html, IntoResponse, Response},
    routing::get,
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use dashmap::{DashMap, DashSet};
use futures_util::{SinkExt, StreamExt};
use if_addrs::get_if_addrs;
use playbridge_cast_core::browser::{
    BROWSER_PROTOCOL_VERSION, BrowserCapabilities, BrowserCommand, BrowserMedia,
    BrowserPlaybackState, BrowserToHostFrame, HostToBrowserFrame,
};
use rand::Rng;
use serde::{Deserialize, Serialize};
use tokio::{
    net::TcpListener,
    sync::{Notify, RwLock, broadcast, mpsc, oneshot},
    task::JoinHandle,
    time,
};

const INDEX_HTML: &str = include_str!("../web/index.html");
const RECEIVER_JS: &str = include_str!("../web/dist/receiver.js");
const DEFAULT_PAIRING_TTL: Duration = Duration::from_secs(120);
const MAX_PAIRING_ATTEMPTS: u8 = 3;
const MAX_PORT_ATTEMPTS: u16 = 10;

#[derive(Debug, Clone)]
pub struct BrowserReceiverConfig {
    pub address: IpAddr,
    pub preferred_port: u16,
    pub pairing_ttl: Duration,
}

impl Default for BrowserReceiverConfig {
    fn default() -> Self {
        Self {
            address: IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            preferred_port: 8770,
            pairing_ttl: DEFAULT_PAIRING_TTL,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BrowserSessionSnapshot {
    pub session_id: String,
    pub receiver_id: String,
    pub name: String,
    pub approved: bool,
    pub capabilities: BrowserCapabilities,
    pub status: BrowserSessionStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BrowserSessionStatus {
    pub state: BrowserPlaybackState,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub volume: f64,
    pub muted: bool,
    pub title: Option<String>,
}

impl Default for BrowserSessionStatus {
    fn default() -> Self {
        Self {
            state: BrowserPlaybackState::Idle,
            position_ms: 0,
            duration_ms: 0,
            volume: 1.0,
            muted: false,
            title: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum BrowserReceiverEvent {
    PairingRequested {
        session: BrowserSessionSnapshot,
        expires_in_ms: u64,
    },
    Connected {
        session: BrowserSessionSnapshot,
    },
    Capabilities {
        session: BrowserSessionSnapshot,
    },
    Status {
        session: BrowserSessionSnapshot,
        request_id: Option<String>,
    },
    Ended {
        session: BrowserSessionSnapshot,
    },
    Error {
        session: BrowserSessionSnapshot,
        request_id: Option<String>,
        message: String,
    },
    Disconnected {
        session_id: String,
        receiver_id: String,
        name: String,
    },
}

struct SessionEntry {
    session_id: String,
    receiver_id: String,
    name: String,
    pairing_code: String,
    expires_at: Instant,
    attempts: AtomicU8,
    approved: AtomicBool,
    closed: AtomicBool,
    closed_notify: Notify,
    sender: mpsc::UnboundedSender<HostToBrowserFrame>,
    capabilities: RwLock<BrowserCapabilities>,
    status: RwLock<BrowserSessionStatus>,
}

impl SessionEntry {
    async fn snapshot(&self) -> BrowserSessionSnapshot {
        BrowserSessionSnapshot {
            session_id: self.session_id.clone(),
            receiver_id: self.receiver_id.clone(),
            name: self.name.clone(),
            approved: self.approved.load(Ordering::Acquire),
            capabilities: self.capabilities.read().await.clone(),
            status: self.status.read().await.clone(),
        }
    }
}

struct ServiceState {
    sessions: DashMap<String, Arc<SessionEntry>>,
    approved_receivers: DashSet<String>,
    events: broadcast::Sender<BrowserReceiverEvent>,
    pairing_ttl: Duration,
    next_request_id: AtomicU64,
}

#[derive(Clone)]
pub struct BrowserReceiverService {
    state: Arc<ServiceState>,
}

impl BrowserReceiverService {
    fn new(pairing_ttl: Duration) -> Self {
        let (events, _) = broadcast::channel(256);
        Self {
            state: Arc::new(ServiceState {
                sessions: DashMap::new(),
                approved_receivers: DashSet::new(),
                events,
                pairing_ttl,
                next_request_id: AtomicU64::new(1),
            }),
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<BrowserReceiverEvent> {
        self.state.events.subscribe()
    }

    pub async fn sessions(&self) -> Vec<BrowserSessionSnapshot> {
        let entries = self
            .state
            .sessions
            .iter()
            .map(|entry| entry.value().clone())
            .collect::<Vec<_>>();
        let mut snapshots = Vec::with_capacity(entries.len());
        for entry in entries {
            snapshots.push(entry.snapshot().await);
        }
        snapshots
    }

    pub async fn approve(&self, session_id: &str, code: &str) -> Result<(), String> {
        let entry = self
            .state
            .sessions
            .get(session_id)
            .map(|entry| entry.value().clone())
            .ok_or_else(|| "browser session is no longer available".to_owned())?;
        if entry.closed.load(Ordering::Acquire) || Instant::now() >= entry.expires_at {
            entry.closed.store(true, Ordering::Release);
            entry.closed_notify.notify_waiters();
            self.state.sessions.remove(session_id);
            return Err("browser pairing code has expired".into());
        }
        if entry.approved.load(Ordering::Acquire) {
            return Ok(());
        }
        if code.trim() != entry.pairing_code {
            let attempts = entry.attempts.fetch_add(1, Ordering::AcqRel) + 1;
            if attempts >= MAX_PAIRING_ATTEMPTS {
                entry.closed.store(true, Ordering::Release);
                entry.closed_notify.notify_waiters();
                let _ = entry.sender.send(HostToBrowserFrame::PairingDenied {
                    session_id: session_id.to_owned(),
                    reason: "Too many incorrect pairing attempts".into(),
                });
                self.state.sessions.remove(session_id);
                return Err("too many incorrect browser pairing attempts".into());
            }
            return Err(format!(
                "browser pairing code is incorrect ({} attempt(s) remaining)",
                MAX_PAIRING_ATTEMPTS - attempts
            ));
        }
        entry.approved.store(true, Ordering::Release);
        self.state.approved_receivers.insert(entry.receiver_id.clone());
        entry
            .sender
            .send(HostToBrowserFrame::PairingApproved {
                session_id: session_id.to_owned(),
            })
            .map_err(|_| "browser disconnected before pairing completed".to_owned())?;
        let _ = self.state.events.send(BrowserReceiverEvent::Connected {
            session: entry.snapshot().await,
        });
        Ok(())
    }

    pub async fn load(&self, session_id: &str, media: BrowserMedia) -> Result<String, String> {
        let request_id = self.next_request_id();
        self.send_approved(
            session_id,
            HostToBrowserFrame::Load {
                request_id: request_id.clone(),
                media,
            },
        )?;
        Ok(request_id)
    }

    pub fn command(
        &self,
        session_id: &str,
        action: BrowserCommand,
        value: Option<f64>,
    ) -> Result<String, String> {
        let request_id = self.next_request_id();
        self.send_approved(
            session_id,
            HostToBrowserFrame::Command {
                request_id: request_id.clone(),
                action,
                value,
            },
        )?;
        Ok(request_id)
    }

    pub fn ping(&self, session_id: &str) -> Result<String, String> {
        let request_id = self.next_request_id();
        self.send_approved(
            session_id,
            HostToBrowserFrame::Ping {
                request_id: request_id.clone(),
            },
        )?;
        Ok(request_id)
    }

    pub fn disconnect(&self, session_id: &str) -> bool {
        let Some((_, entry)) = self.state.sessions.remove(session_id) else {
            return false;
        };
        entry.closed.store(true, Ordering::Release);
        entry.closed_notify.notify_waiters();
        let _ = entry.sender.send(HostToBrowserFrame::Disconnect {
            reason: "Disconnected by PlayBridge".into(),
        });
        true
    }

    /// Close every live session for `receiver_id` except `keep_session_id`.
    ///
    /// Used when the same browser identity reconnects (refresh / new tab) so
    /// stale "waiting to pair" sessions do not pile up on the host or UI.
    fn supersede_receiver_sessions(&self, receiver_id: &str, keep_session_id: &str) {
        let stale = self
            .state
            .sessions
            .iter()
            .filter(|entry| {
                entry.receiver_id == receiver_id && entry.session_id != keep_session_id
            })
            .map(|entry| entry.value().clone())
            .collect::<Vec<_>>();
        for entry in stale {
            entry.closed.store(true, Ordering::Release);
            entry.closed_notify.notify_waiters();
            let _ = entry.sender.send(HostToBrowserFrame::Disconnect {
                reason: "Replaced by a newer browser session".into(),
            });
        }
    }

    fn send_approved(&self, session_id: &str, frame: HostToBrowserFrame) -> Result<(), String> {
        let entry = self
            .state
            .sessions
            .get(session_id)
            .map(|entry| entry.value().clone())
            .ok_or_else(|| "browser session is not connected".to_owned())?;
        if !entry.approved.load(Ordering::Acquire) {
            return Err("browser session has not been approved".into());
        }
        if entry.closed.load(Ordering::Acquire) {
            return Err("browser session is closed".into());
        }
        entry
            .sender
            .send(frame)
            .map_err(|_| "browser session is disconnected".into())
    }

    fn next_request_id(&self) -> String {
        self.state
            .next_request_id
            .fetch_add(1, Ordering::Relaxed)
            .to_string()
    }

    fn close_all(&self) {
        for entry in self.state.sessions.iter() {
            entry.closed.store(true, Ordering::Release);
            entry.closed_notify.notify_waiters();
        }
        self.state.sessions.clear();
    }
}

pub struct BrowserReceiverHost {
    service: BrowserReceiverService,
    local_addr: SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<Result<(), std::io::Error>>>,
}

impl BrowserReceiverHost {
    pub async fn start(config: BrowserReceiverConfig) -> Result<Self, String> {
        let listener = bind_browser_listener(config.address, config.preferred_port).await?;
        let local_addr = listener
            .local_addr()
            .map_err(|error| format!("failed to inspect browser receiver listener: {error}"))?;
        let service = BrowserReceiverService::new(config.pairing_ttl);
        let app = router(service.clone());
        let (shutdown, shutdown_rx) = oneshot::channel();
        let task = tokio::spawn(async move {
            axum::serve(listener, app)
                .with_graceful_shutdown(async {
                    let _ = shutdown_rx.await;
                })
                .await
        });
        Ok(Self {
            service,
            local_addr,
            shutdown: Some(shutdown),
            task: Some(task),
        })
    }

    pub fn service(&self) -> BrowserReceiverService {
        self.service.clone()
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    pub fn urls(&self) -> Vec<String> {
        local_urls(self.local_addr.port())
    }

    pub async fn shutdown(mut self) -> Result<(), String> {
        self.service.close_all();
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        self.task
            .take()
            .expect("browser receiver task must exist")
            .await
            .map_err(|error| format!("browser receiver task failed: {error}"))?
            .map_err(|error| format!("browser receiver host failed: {error}"))
    }
}

async fn bind_browser_listener(
    address: IpAddr,
    preferred_port: u16,
) -> Result<TcpListener, String> {
    if preferred_port == 0 {
        return TcpListener::bind(SocketAddr::new(address, 0))
            .await
            .map_err(|error| format!("failed to bind browser receiver host: {error}"));
    }

    let mut attempted_ports = Vec::with_capacity(MAX_PORT_ATTEMPTS as usize);
    for offset in 0..MAX_PORT_ATTEMPTS {
        let Some(port) = preferred_port.checked_add(offset) else {
            break;
        };
        attempted_ports.push(port);
        match TcpListener::bind(SocketAddr::new(address, port)).await {
            Ok(listener) => return Ok(listener),
            Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => {}
            Err(error) => {
                return Err(format!(
                    "failed to bind browser receiver host on port {port}: {error}"
                ));
            }
        }
    }

    let first = attempted_ports.first().copied().unwrap_or(preferred_port);
    let last = attempted_ports.last().copied().unwrap_or(preferred_port);
    Err(format!(
        "browser receiver ports {first}-{last} are already in use"
    ))
}

impl Drop for BrowserReceiverHost {
    fn drop(&mut self) {
        self.service.close_all();
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

fn router(service: BrowserReceiverService) -> Router {
    Router::new()
        .route("/", get(index_handler))
        .route("/health", get(|| async { "OK" }))
        .route("/assets/receiver.js", get(script_handler))
        .route("/v1/browser/ws", get(websocket_handler))
        .with_state(service)
}

async fn index_handler() -> Response {
    with_security_headers(Html(INDEX_HTML).into_response(), "text/html; charset=utf-8")
}

async fn script_handler() -> Response {
    with_security_headers(
        (
            [(
                header::CONTENT_TYPE,
                "application/javascript; charset=utf-8",
            )],
            RECEIVER_JS,
        )
            .into_response(),
        "application/javascript; charset=utf-8",
    )
}

fn with_security_headers(mut response: Response, content_type: &'static str) -> Response {
    let headers = response.headers_mut();
    headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("no-store, max-age=0"),
    );
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(
            "default-src 'self'; script-src 'self'; style-src 'unsafe-inline'; media-src 'self' http: https: blob:; connect-src 'self' ws: wss: http: https:; img-src 'self' data: http: https:",
        ),
    );
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    response
}

async fn websocket_handler(
    websocket: WebSocketUpgrade,
    State(service): State<BrowserReceiverService>,
) -> impl IntoResponse {
    websocket.on_upgrade(move |socket| run_browser_socket(socket, service))
}

async fn run_browser_socket(mut socket: WebSocket, service: BrowserReceiverService) {
    let hello = match time::timeout(Duration::from_secs(10), socket.recv()).await {
        Ok(Some(Ok(Message::Text(text)))) => serde_json::from_str::<BrowserToHostFrame>(&text).ok(),
        _ => None,
    };
    let Some(BrowserToHostFrame::Hello {
        protocol_version,
        receiver_id,
        name,
        session_id: _requested_session_id,
    }) = hello
    else {
        let _ = socket.close().await;
        return;
    };
    if protocol_version != BROWSER_PROTOCOL_VERSION
        || receiver_id.trim().is_empty()
        || name.trim().is_empty()
    {
        let _ = socket.close().await;
        return;
    }

    let auto_approved = service.state.approved_receivers.contains(&receiver_id);
    let session_id = random_id();

    let pairing_code = random_code();
    let expires_at = Instant::now() + service.state.pairing_ttl;
    let (sender, mut outgoing) = mpsc::unbounded_channel();
    let entry = Arc::new(SessionEntry {
        session_id: session_id.clone(),
        receiver_id: receiver_id.clone(),
        name,
        pairing_code: pairing_code.clone(),
        expires_at,
        attempts: AtomicU8::new(0),
        approved: AtomicBool::new(auto_approved),
        closed: AtomicBool::new(false),
        closed_notify: Notify::new(),
        sender,
        capabilities: RwLock::new(BrowserCapabilities::default()),
        status: RwLock::new(BrowserSessionStatus::default()),
    });
    // Drop earlier sessions for this browser identity before advertising the
    // new one, so Desktop never accumulates multiple "waiting to pair" rows
    // when the user refreshes the PIN page.
    service.supersede_receiver_sessions(&receiver_id, &session_id);
    service
        .state
        .sessions
        .insert(session_id.clone(), entry.clone());

    let expires_in_ms = service.state.pairing_ttl.as_millis() as u64;
    if auto_approved {
        let _ = entry.sender.send(HostToBrowserFrame::PairingApproved {
            session_id: session_id.clone(),
        });
        let _ = service.state.events.send(BrowserReceiverEvent::Connected {
            session: entry.snapshot().await,
        });
    } else {
        let _ = entry.sender.send(HostToBrowserFrame::PairingRequired {
            session_id: session_id.clone(),
            code: pairing_code,
            expires_in_ms,
        });
        let _ = service
            .state
            .events
            .send(BrowserReceiverEvent::PairingRequested {
                session: entry.snapshot().await,
                expires_in_ms,
            });
    }

    let (mut ws_sender, mut ws_receiver) = socket.split();
    let writer = tokio::spawn(async move {
        while let Some(frame) = outgoing.recv().await {
            let Ok(text) = serde_json::to_string(&frame) else {
                continue;
            };
            if ws_sender.send(Message::Text(text)).await.is_err() {
                break;
            }
        }
    });

    loop {
        if entry.closed.load(Ordering::Acquire) {
            break;
        }
        let message = if entry.approved.load(Ordering::Acquire) {
            tokio::select! {
                message = ws_receiver.next() => message,
                _ = entry.closed_notify.notified() => break,
            }
        } else {
            let remaining = entry.expires_at.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                let _ = entry.sender.send(HostToBrowserFrame::PairingDenied {
                    session_id: session_id.clone(),
                    reason: "Pairing code expired".into(),
                });
                break;
            }
            tokio::select! {
                message = ws_receiver.next() => message,
                _ = time::sleep(remaining) => {
                    let _ = entry.sender.send(HostToBrowserFrame::PairingDenied {
                        session_id: session_id.clone(),
                        reason: "Pairing code expired".into(),
                    });
                    break;
                }
                _ = entry.closed_notify.notified() => break,
            }
        };
        let Some(Ok(Message::Text(text))) = message else {
            break;
        };
        let Ok(frame) = serde_json::from_str::<BrowserToHostFrame>(&text) else {
            continue;
        };
        handle_browser_frame(&service, &entry, frame).await;
    }

    entry.closed.store(true, Ordering::Release);
    service.state.sessions.remove(&session_id);
    let receiver_id = entry.receiver_id.clone();
    let name = entry.name.clone();
    drop(entry);
    let _ = writer.await;
    // Always notify consumers — including unapproved PIN waits — so a browser
    // refresh/expiry removes the matching "waiting to pair" row on Desktop.
    let _ = service
        .state
        .events
        .send(BrowserReceiverEvent::Disconnected {
            session_id,
            receiver_id,
            name,
        });
}

async fn handle_browser_frame(
    service: &BrowserReceiverService,
    entry: &Arc<SessionEntry>,
    frame: BrowserToHostFrame,
) {
    match frame {
        BrowserToHostFrame::Capabilities { capabilities } => {
            *entry.capabilities.write().await = capabilities;
            if entry.approved.load(Ordering::Acquire) {
                let _ = service
                    .state
                    .events
                    .send(BrowserReceiverEvent::Capabilities {
                        session: entry.snapshot().await,
                    });
            }
        }
        BrowserToHostFrame::Status {
            request_id,
            state,
            position_ms,
            duration_ms,
            volume,
            muted,
            title,
        } if entry.approved.load(Ordering::Acquire) => {
            *entry.status.write().await = BrowserSessionStatus {
                state,
                position_ms,
                duration_ms,
                volume: volume.clamp(0.0, 1.0),
                muted,
                title,
            };
            let _ = service.state.events.send(BrowserReceiverEvent::Status {
                session: entry.snapshot().await,
                request_id,
            });
        }
        BrowserToHostFrame::Ended if entry.approved.load(Ordering::Acquire) => {
            entry.status.write().await.state = BrowserPlaybackState::Ended;
            let _ = service.state.events.send(BrowserReceiverEvent::Ended {
                session: entry.snapshot().await,
            });
        }
        BrowserToHostFrame::Error {
            request_id,
            message,
        } if entry.approved.load(Ordering::Acquire) => {
            entry.status.write().await.state = BrowserPlaybackState::Error;
            let _ = service.state.events.send(BrowserReceiverEvent::Error {
                session: entry.snapshot().await,
                request_id,
                message,
            });
        }
        BrowserToHostFrame::Ready { .. }
        | BrowserToHostFrame::Pong { .. }
        | BrowserToHostFrame::Hello { .. }
        | BrowserToHostFrame::Status { .. }
        | BrowserToHostFrame::Ended
        | BrowserToHostFrame::Error { .. } => {}
    }
}

pub fn local_urls(port: u16) -> Vec<String> {
    let mut addresses = get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|interface| !interface.is_loopback())
        .filter_map(|interface| match interface.ip() {
            IpAddr::V4(address) if address.is_private() => Some(address),
            _ => None,
        })
        .collect::<Vec<_>>();
    addresses.sort_unstable();
    addresses.dedup();
    if addresses.is_empty() {
        return vec![format!("http://127.0.0.1:{port}")];
    }
    addresses
        .into_iter()
        .map(|address| format!("http://{address}:{port}"))
        .collect()
}

fn random_id() -> String {
    let mut bytes = [0_u8; 24];
    rand::thread_rng().fill(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn random_code() -> String {
    format!("{:06}", rand::thread_rng().gen_range(0..1_000_000_u32))
}

#[cfg(test)]
mod tests {
    use std::{net::TcpListener as StdTcpListener, time::Duration};

    use futures_util::{SinkExt, StreamExt};
    use playbridge_cast_core::browser::{
        BROWSER_PROTOCOL_VERSION, BrowserMedia, BrowserPlaybackState, BrowserToHostFrame,
        HostToBrowserFrame,
    };
    use tokio_tungstenite::{connect_async, tungstenite::Message};

    use super::{
        BrowserReceiverConfig, BrowserReceiverEvent, BrowserReceiverHost, BrowserSessionSnapshot,
    };

    #[tokio::test]
    async fn host_starts_on_an_available_port_and_serves_assets() {
        let host = BrowserReceiverHost::start(BrowserReceiverConfig {
            address: "127.0.0.1".parse().unwrap(),
            preferred_port: 0,
            ..Default::default()
        })
        .await
        .unwrap();
        let base = format!("http://127.0.0.1:{}", host.local_addr().port());
        let client = reqwest_for_test();
        assert_eq!(
            client
                .get(format!("{base}/health"))
                .send()
                .await
                .unwrap()
                .text()
                .await
                .unwrap(),
            "OK"
        );
        let html = client.get(base).send().await.unwrap().text().await.unwrap();
        assert!(html.contains("PlayBridge Browser Receiver"));
        let receiver_js = client
            .get(format!(
                "http://127.0.0.1:{}/assets/receiver.js",
                host.local_addr().port()
            ))
            .send()
            .await
            .unwrap()
            .text()
            .await
            .unwrap();
        assert!(receiver_js.contains("application/dash+xml"));
        assert!(
            receiver_js.contains("DASH playback requires Media Source support in this browser")
        );
        host.shutdown().await.unwrap();
    }

    #[tokio::test]
    async fn clean_shutdown_releases_the_preferred_port_for_restart() {
        let reservation = StdTcpListener::bind("127.0.0.1:0").unwrap();
        let preferred_port = reservation.local_addr().unwrap().port();
        drop(reservation);

        for _ in 0..2 {
            let host = BrowserReceiverHost::start(BrowserReceiverConfig {
                address: "127.0.0.1".parse().unwrap(),
                preferred_port,
                ..Default::default()
            })
            .await
            .unwrap();
            assert_eq!(host.local_addr().port(), preferred_port);
            host.shutdown().await.unwrap();
        }
    }

    #[tokio::test]
    async fn occupied_preferred_port_uses_the_next_port() {
        let (reservation, preferred_port) = (30_000..60_000)
            .find_map(|port| {
                let reservation = StdTcpListener::bind(("127.0.0.1", port)).ok()?;
                let next = StdTcpListener::bind(("127.0.0.1", port + 1)).ok()?;
                drop(next);
                Some((reservation, port))
            })
            .expect("an adjacent pair of test ports should be available");

        let host = BrowserReceiverHost::start(BrowserReceiverConfig {
            address: "127.0.0.1".parse().unwrap(),
            preferred_port,
            ..Default::default()
        })
        .await
        .unwrap();
        assert_eq!(host.local_addr().port(), preferred_port + 1);

        drop(reservation);
        host.shutdown().await.unwrap();
    }

    #[tokio::test]
    async fn unapproved_session_cannot_receive_media() {
        let service = super::BrowserReceiverService::new(Duration::from_secs(60));
        assert!(
            service
                .load(
                    "missing",
                    BrowserMedia {
                        url: "http://example.test/video.mp4".into(),
                        title: None,
                        content_type: None,
                        poster_url: None,
                        subtitle_url: None,
                        start_position_ms: None,
                    }
                )
                .await
                .is_err()
        );
        let mut events = service.subscribe();
        assert!(matches!(
            events.try_recv(),
            Err(tokio::sync::broadcast::error::TryRecvError::Empty)
        ));
        let _event_type: Option<BrowserReceiverEvent> = None;
    }

    #[tokio::test]
    async fn browser_pairs_loads_and_reports_status_over_websocket() {
        let host = BrowserReceiverHost::start(BrowserReceiverConfig {
            address: "127.0.0.1".parse().unwrap(),
            preferred_port: 0,
            ..Default::default()
        })
        .await
        .unwrap();
        let service = host.service();
        let mut events = service.subscribe();
        let (mut socket, _) = connect_async(format!(
            "ws://127.0.0.1:{}/v1/browser/ws",
            host.local_addr().port()
        ))
        .await
        .unwrap();
        socket
            .send(Message::Text(
                serde_json::to_string(&BrowserToHostFrame::Hello {
                    protocol_version: BROWSER_PROTOCOL_VERSION,
                    receiver_id: "browser-test".into(),
                    name: "Test Browser".into(),
                    session_id: None,
                })
                .unwrap()
                .into(),
            ))
            .await
            .unwrap();

        let pairing = socket.next().await.unwrap().unwrap().into_text().unwrap();
        let HostToBrowserFrame::PairingRequired {
            session_id, code, ..
        } = serde_json::from_str(&pairing).unwrap()
        else {
            panic!("expected pairing frame");
        };
        assert!(matches!(
            events.recv().await.unwrap(),
            BrowserReceiverEvent::PairingRequested { .. }
        ));
        service.approve(&session_id, &code).await.unwrap();
        assert!(matches!(
            events.recv().await.unwrap(),
            BrowserReceiverEvent::Connected { .. }
        ));
        let approved = socket.next().await.unwrap().unwrap().into_text().unwrap();
        assert!(matches!(
            serde_json::from_str(&approved).unwrap(),
            HostToBrowserFrame::PairingApproved { .. }
        ));

        service
            .load(
                &session_id,
                BrowserMedia {
                    url: "http://example.test/video.mp4".into(),
                    title: Some("Example".into()),
                    content_type: Some("video/mp4".into()),
                    poster_url: None,
                    subtitle_url: None,
                    start_position_ms: None,
                },
            )
            .await
            .unwrap();
        let load = socket.next().await.unwrap().unwrap().into_text().unwrap();
        assert!(matches!(
            serde_json::from_str(&load).unwrap(),
            HostToBrowserFrame::Load { .. }
        ));

        socket
            .send(Message::Text(
                serde_json::to_string(&BrowserToHostFrame::Status {
                    request_id: None,
                    state: BrowserPlaybackState::Playing,
                    position_ms: 1_000,
                    duration_ms: 10_000,
                    volume: 0.5,
                    muted: false,
                    title: Some("Example".into()),
                })
                .unwrap()
                .into(),
            ))
            .await
            .unwrap();
        loop {
            if let BrowserReceiverEvent::Status { session, .. } = events.recv().await.unwrap() {
                assert_eq!(session.status.position_ms, 1_000);
                break;
            }
        }

        // Refresh/reconnect with the same receiver_id: old session disconnects,
        // and the new socket is auto-approved without another PIN.
        socket.close(None).await.unwrap();
        assert!(matches!(
            events.recv().await.unwrap(),
            BrowserReceiverEvent::Disconnected {
                session_id: ref closed_id,
                ..
            } if closed_id == &session_id
        ));

        let (mut socket2, _) = connect_async(format!(
            "ws://127.0.0.1:{}/v1/browser/ws",
            host.local_addr().port()
        ))
        .await
        .unwrap();
        socket2
            .send(Message::Text(
                serde_json::to_string(&BrowserToHostFrame::Hello {
                    protocol_version: BROWSER_PROTOCOL_VERSION,
                    receiver_id: "browser-test".into(),
                    name: "Test Browser".into(),
                    session_id: None,
                })
                .unwrap()
                .into(),
            ))
            .await
            .unwrap();

        let auto_approved = socket2.next().await.unwrap().unwrap().into_text().unwrap();
        assert!(matches!(
            serde_json::from_str(&auto_approved).unwrap(),
            HostToBrowserFrame::PairingApproved { .. }
        ));
        assert!(matches!(
            events.recv().await.unwrap(),
            BrowserReceiverEvent::Connected { .. }
        ));

        host.shutdown().await.unwrap();
    }

    #[tokio::test]
    async fn refresh_while_waiting_for_pin_replaces_pending_session() {
        let host = BrowserReceiverHost::start(BrowserReceiverConfig {
            address: "127.0.0.1".parse().unwrap(),
            preferred_port: 0,
            ..Default::default()
        })
        .await
        .unwrap();
        let service = host.service();
        let mut events = service.subscribe();
        let ws_url = format!(
            "ws://127.0.0.1:{}/v1/browser/ws",
            host.local_addr().port()
        );

        let hello = serde_json::to_string(&BrowserToHostFrame::Hello {
            protocol_version: BROWSER_PROTOCOL_VERSION,
            receiver_id: "browser-refresh".into(),
            name: "Refresh Browser".into(),
            session_id: None,
        })
        .unwrap();

        let (mut socket1, _) = connect_async(&ws_url).await.unwrap();
        socket1
            .send(Message::Text(hello.clone().into()))
            .await
            .unwrap();
        let pairing1 = socket1.next().await.unwrap().unwrap().into_text().unwrap();
        let HostToBrowserFrame::PairingRequired {
            session_id: first_session,
            ..
        } = serde_json::from_str(&pairing1).unwrap()
        else {
            panic!("expected first pairing frame");
        };
        assert!(matches!(
            events.recv().await.unwrap(),
            BrowserReceiverEvent::PairingRequested {
                session: BrowserSessionSnapshot {
                    session_id: ref id,
                    ..
                },
                ..
            } if id == &first_session
        ));

        // Second connect with the same receiver_id (page refresh) should
        // supersede the first pending session.
        let (mut socket2, _) = connect_async(&ws_url).await.unwrap();
        socket2
            .send(Message::Text(hello.into()))
            .await
            .unwrap();
        let pairing2 = socket2.next().await.unwrap().unwrap().into_text().unwrap();
        let HostToBrowserFrame::PairingRequired {
            session_id: second_session,
            ..
        } = serde_json::from_str(&pairing2).unwrap()
        else {
            panic!("expected second pairing frame");
        };
        assert_ne!(first_session, second_session);

        // First socket is told to go away, then host emits Disconnected for it.
        let replaced = socket1.next().await.unwrap().unwrap().into_text().unwrap();
        assert!(matches!(
            serde_json::from_str(&replaced).unwrap(),
            HostToBrowserFrame::Disconnect { .. }
        ));

        // Events: PairingRequested for second, and Disconnected for first
        // (order can interleave depending on task scheduling).
        let mut saw_second_request = false;
        let mut saw_first_disconnect = false;
        for _ in 0..4 {
            match events.recv().await.unwrap() {
                BrowserReceiverEvent::PairingRequested {
                    session: BrowserSessionSnapshot { session_id, .. },
                    ..
                } if session_id == second_session => saw_second_request = true,
                BrowserReceiverEvent::Disconnected { session_id, .. }
                    if session_id == first_session =>
                {
                    saw_first_disconnect = true
                }
                other => panic!("unexpected event while replacing pending session: {other:?}"),
            }
            if saw_second_request && saw_first_disconnect {
                break;
            }
        }
        assert!(saw_second_request && saw_first_disconnect);
        let live = service.sessions().await;
        assert_eq!(live.len(), 1);
        assert_eq!(live[0].session_id, second_session);

        host.shutdown().await.unwrap();
    }

    fn reqwest_for_test() -> reqwest::Client {
        reqwest::Client::new()
    }
}
