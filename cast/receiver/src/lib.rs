//! Secure, reusable PlayBridge receiver runtime.
//!
//! This crate owns the network-facing receiver boundary: TLS/WSS, pairing,
//! authentication, connection limits, and authenticated command decoding.
//! Playback and platform lifecycle remain with the consuming application.

use std::{
    collections::{HashMap, HashSet},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use futures_util::{SinkExt, StreamExt};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use playbridge_cast_core::playbridge::{CredentialBundle, ReceiverPairingSession, SenderFrame};
use rustls::{
    ServerConfig,
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs1KeyDer, PrivatePkcs8KeyDer},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::{
    net::{TcpListener, TcpStream},
    sync::{Semaphore, broadcast, mpsc, watch},
};
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::{
    WebSocketStream, accept_async_with_config,
    tungstenite::{Message, protocol::WebSocketConfig},
};
use x509_parser::parse_x509_certificate;

const DEFAULT_MAX_CONNECTIONS: usize = 32;
const DEFAULT_MAX_MESSAGE_BYTES: usize = 1024 * 1024;
const OUTBOUND_QUEUE_CAPACITY: usize = 64;
const DEFAULT_PAIRING_TIMEOUT: Duration = Duration::from_secs(60);
const TLS_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PrivateKeyKind {
    Pkcs1,
    Pkcs8,
}

#[derive(Clone)]
pub struct ReceiverIdentity {
    pub certificate_der: Vec<u8>,
    pub private_key_der: Vec<u8>,
    pub private_key_kind: PrivateKeyKind,
}

#[derive(Clone)]
pub struct ReceiverConfig {
    pub preferred_port: u16,
    pub fallback_attempts: u16,
    pub name: String,
    pub uuid: String,
    pub identity: ReceiverIdentity,
    /// Raw legacy tokens and `sha256:<hex>` token digests are both accepted.
    pub authorized_tokens: Vec<String>,
    pub players: Vec<String>,
    pub browsers: Vec<String>,
    pub screen_mirror_web_rtc: bool,
    pub advertise: bool,
    pub max_connections: usize,
    pub max_message_bytes: usize,
    pub pairing_timeout: Duration,
}

impl ReceiverConfig {
    pub fn new(name: String, uuid: String, identity: ReceiverIdentity) -> Self {
        Self {
            preferred_port: 8765,
            fallback_attempts: 10,
            name,
            uuid,
            identity,
            authorized_tokens: Vec::new(),
            players: Vec::new(),
            browsers: Vec::new(),
            screen_mirror_web_rtc: false,
            advertise: false,
            max_connections: DEFAULT_MAX_CONNECTIONS,
            max_message_bytes: DEFAULT_MAX_MESSAGE_BYTES,
            pairing_timeout: DEFAULT_PAIRING_TIMEOUT,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "action", content = "payload", rename_all = "snake_case")]
pub enum ReceiverCommand {
    ContextQuery,
    Playlist(Value),
    QueueAdd(Value),
    PlaylistJump(Value),
    Control(Value),
    Remote(Value),
    Mouse(Value),
    Browser(Value),
    BrowserControl(Value),
    ScreenMirrorStart(Value),
    ScreenMirrorOffer(Value),
    ScreenMirrorCandidate(Value),
    ScreenMirrorStop(Value),
    Unknown { action: String, payload: Value },
}

impl ReceiverCommand {
    fn decode(action: String, payload: Option<Value>) -> Self {
        let payload = payload.unwrap_or(Value::Null);
        match action.as_str() {
            "context_query" => Self::ContextQuery,
            "playlist" => Self::Playlist(payload),
            "queue_add" => Self::QueueAdd(payload),
            "playlist_jump" => Self::PlaylistJump(payload),
            "control" => Self::Control(payload),
            "remote" => Self::Remote(payload),
            "mouse" => Self::Mouse(payload),
            "browser" => Self::Browser(payload),
            "browser_control" => Self::BrowserControl(payload),
            "screen_mirror_start" => Self::ScreenMirrorStart(payload),
            "screen_mirror_offer" => Self::ScreenMirrorOffer(payload),
            "screen_mirror_candidate" => Self::ScreenMirrorCandidate(payload),
            "screen_mirror_stop" => Self::ScreenMirrorStop(payload),
            _ => Self::Unknown { action, payload },
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum ReceiverEvent {
    Started {
        port: u16,
        fingerprint: String,
    },
    ClientCount {
        total: usize,
        authenticated: usize,
    },
    ClientDisconnected {
        connection_id: u64,
    },
    PairingStarted {
        connection_id: u64,
        device_name: String,
        device_uuid: String,
    },
    PairingRequested {
        connection_id: u64,
        device_name: String,
        device_uuid: String,
        sas_code: String,
    },
    Paired {
        connection_id: u64,
        device_name: String,
        device_uuid: String,
        /// Consumers must persist this securely and must not log it.
        token: String,
    },
    Authenticated {
        connection_id: u64,
        token_digest: String,
    },
    Command {
        connection_id: u64,
        command: ReceiverCommand,
        raw: String,
    },
    Error {
        connection_id: Option<u64>,
        message: String,
    },
    Finished,
}

enum Outbound {
    Json(Value),
}

struct ConnectionHandle {
    outbound: mpsc::Sender<Outbound>,
    close: watch::Sender<bool>,
}

struct ConnectionIo {
    outbound: mpsc::Receiver<Outbound>,
    close: watch::Receiver<bool>,
}

struct Shared {
    fingerprint: String,
    players: Vec<String>,
    browsers: Vec<String>,
    screen_mirror_web_rtc: bool,
    authorized_tokens: Mutex<HashSet<String>>,
    connections: Mutex<HashMap<u64, ConnectionHandle>>,
    authenticated: Mutex<HashMap<u64, String>>,
    pairing_owner: Mutex<Option<u64>>,
    failed_attempts: Mutex<HashMap<String, (u8, Instant)>>,
    events: broadcast::Sender<ReceiverEvent>,
}

impl Shared {
    fn emit(&self, event: ReceiverEvent) {
        let _ = self.events.send(event);
    }

    fn emit_counts(&self) {
        let total = self.connections.lock().map_or(0, |items| items.len());
        let authenticated = self.authenticated.lock().map_or(0, |items| items.len());
        self.emit(ReceiverEvent::ClientCount {
            total,
            authenticated,
        });
    }

    fn is_authorized(&self, token: &str) -> bool {
        let digest = token_digest(token);
        self.authorized_tokens.lock().is_ok_and(|tokens| {
            tokens.iter().any(|stored| {
                stored.as_bytes().ct_eq(token.as_bytes()).unwrap_u8() == 1
                    || stored.as_bytes().ct_eq(digest.as_bytes()).unwrap_u8() == 1
            })
        })
    }

    fn record_pairing_failure(&self, ip: &str) {
        if let Ok(mut attempts) = self.failed_attempts.lock() {
            let entry = attempts.entry(ip.to_owned()).or_insert((0, Instant::now()));
            entry.0 = entry.0.saturating_add(1);
            if entry.0 >= 3 {
                *entry = (0, Instant::now() + Duration::from_secs(60));
            }
        }
    }

    fn is_locked_out(&self, ip: &str) -> bool {
        self.failed_attempts.lock().is_ok_and(|attempts| {
            attempts
                .get(ip)
                .is_some_and(|(count, until)| *count == 0 && Instant::now() < *until)
        })
    }
}

pub struct ReceiverHost {
    port: u16,
    fingerprint: String,
    shared: Arc<Shared>,
    events: broadcast::Sender<ReceiverEvent>,
    shutdown: Option<tokio::sync::oneshot::Sender<()>>,
    task: Option<tokio::task::JoinHandle<()>>,
}

impl ReceiverHost {
    pub async fn start(config: ReceiverConfig) -> Result<Self, String> {
        validate_config(&config)?;
        let (tls, fingerprint) = tls_config(&config.identity)?;
        let listener =
            bind_next_port(config.preferred_port, config.fallback_attempts.max(1)).await?;
        let port = listener
            .local_addr()
            .map_err(|error| error.to_string())?
            .port();
        let (events, _) = broadcast::channel(256);
        let shared = Arc::new(Shared {
            fingerprint: fingerprint.clone(),
            players: config.players.clone(),
            browsers: config.browsers.clone(),
            screen_mirror_web_rtc: config.screen_mirror_web_rtc,
            authorized_tokens: Mutex::new(config.authorized_tokens.iter().cloned().collect()),
            connections: Mutex::new(HashMap::new()),
            authenticated: Mutex::new(HashMap::new()),
            pairing_owner: Mutex::new(None),
            failed_attempts: Mutex::new(HashMap::new()),
            events: events.clone(),
        });
        let mdns = if config.advertise {
            Some(advertise(&config.name, &config.uuid, port)?)
        } else {
            None
        };
        let acceptor = TlsAcceptor::from(Arc::new(tls));
        let semaphore = Arc::new(Semaphore::new(config.max_connections));
        let next_id = Arc::new(AtomicU64::new(1));
        let (shutdown_tx, mut shutdown_rx) = tokio::sync::oneshot::channel();
        let task_shared = shared.clone();
        let task_events = events.clone();
        let task = tokio::spawn(async move {
            task_shared.emit(ReceiverEvent::Started {
                port,
                fingerprint: task_shared.fingerprint.clone(),
            });
            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => break,
                    accepted = listener.accept() => {
                        let Ok((stream, address)) = accepted else {
                            task_shared.emit(ReceiverEvent::Error {
                                connection_id: None,
                                message: "receiver listener failed".into(),
                            });
                            continue;
                        };
                        let Ok(permit) = semaphore.clone().try_acquire_owned() else {
                            drop(stream);
                            continue;
                        };
                        let id = next_id.fetch_add(1, Ordering::Relaxed);
                        let shared = task_shared.clone();
                        let acceptor = acceptor.clone();
                        let ip = address.ip().to_string();
                        let pairing_timeout = config.pairing_timeout;
                        let max_message_bytes = config.max_message_bytes;
                        tokio::spawn(async move {
                            let _permit = permit;
                            if let Err(message) = serve_connection(
                                id,
                                ip,
                                stream,
                                acceptor,
                                shared.clone(),
                                pairing_timeout,
                                max_message_bytes,
                            )
                            .await
                            {
                                shared.emit(ReceiverEvent::Error {
                                    connection_id: Some(id),
                                    message,
                                });
                            }
                            cleanup_connection(id, &shared);
                        });
                    }
                }
            }
            if let Some(mdns) = mdns {
                let _ = mdns.shutdown();
            }
            for sender in task_shared
                .connections
                .lock()
                .map(|items| {
                    items
                        .values()
                        .map(|connection| connection.close.clone())
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default()
            {
                let _ = sender.send(true);
            }
            task_shared.emit(ReceiverEvent::Finished);
            drop(task_events);
        });
        Ok(Self {
            port,
            fingerprint,
            shared,
            events,
            shutdown: Some(shutdown_tx),
            task: Some(task),
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }

    pub fn subscribe(&self) -> broadcast::Receiver<ReceiverEvent> {
        self.events.subscribe()
    }

    pub fn broadcast(&self, value: Value) {
        let authenticated = self
            .shared
            .authenticated
            .lock()
            .map(|items| items.clone())
            .unwrap_or_default();
        for sender in self
            .shared
            .connections
            .lock()
            .map(|items| {
                items
                    .iter()
                    .filter(|(id, _)| authenticated.contains_key(id))
                    .map(|(_, connection)| connection.outbound.clone())
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default()
        {
            let _ = sender.try_send(Outbound::Json(value.clone()));
        }
    }

    pub fn send_to(&self, connection_id: u64, value: Value) -> bool {
        self.shared
            .connections
            .lock()
            .ok()
            .and_then(|items| {
                items
                    .get(&connection_id)
                    .map(|connection| connection.outbound.clone())
            })
            .is_some_and(|sender| sender.try_send(Outbound::Json(value)).is_ok())
    }

    pub fn deny_pairing(&self, connection_id: u64) {
        let _ = self.send_to(connection_id, json!({"type":"pairing_denied"}));
        if let Ok(connections) = self.shared.connections.lock()
            && let Some(connection) = connections.get(&connection_id)
        {
            let _ = connection.close.send(true);
        }
    }

    pub fn disconnect_all(&self) {
        for close in self
            .shared
            .connections
            .lock()
            .map(|items| {
                items
                    .values()
                    .map(|connection| connection.close.clone())
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default()
        {
            let _ = close.send(true);
        }
    }

    pub fn replace_authorized_tokens(&self, tokens: impl IntoIterator<Item = String>) {
        let tokens = tokens.into_iter().collect::<HashSet<_>>();
        let accepted_digests = tokens
            .iter()
            .map(|token| {
                if token.starts_with("sha256:") {
                    token.clone()
                } else {
                    token_digest(token)
                }
            })
            .collect::<HashSet<_>>();
        if let Ok(mut authorized) = self.shared.authorized_tokens.lock() {
            *authorized = tokens;
        }
        let revoked = self
            .shared
            .authenticated
            .lock()
            .map(|authenticated| {
                authenticated
                    .iter()
                    .filter(|(_, digest)| !accepted_digests.contains(*digest))
                    .map(|(id, _)| *id)
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        if let Ok(connections) = self.shared.connections.lock() {
            for id in revoked {
                if let Some(connection) = connections.get(&id) {
                    let _ = connection.close.send(true);
                }
            }
        }
    }

    pub async fn shutdown(mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        if let Some(task) = self.task.take() {
            let _ = task.await;
        }
    }
}

impl Drop for ReceiverHost {
    fn drop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

async fn serve_connection(
    id: u64,
    ip: String,
    stream: TcpStream,
    acceptor: TlsAcceptor,
    shared: Arc<Shared>,
    pairing_timeout: Duration,
    max_message_bytes: usize,
) -> Result<(), String> {
    let tls = tokio::time::timeout(TLS_TIMEOUT, acceptor.accept(stream))
        .await
        .map_err(|_| "TLS handshake timed out".to_owned())?
        .map_err(|_| "TLS handshake failed".to_owned())?;
    let mut websocket_config = WebSocketConfig::default();
    websocket_config.max_message_size = Some(max_message_bytes);
    websocket_config.max_frame_size = Some(max_message_bytes);
    let socket = accept_async_with_config(tls, Some(websocket_config))
        .await
        .map_err(|_| "WebSocket handshake failed".to_owned())?;
    let (outbound_tx, outbound_rx) = mpsc::channel(OUTBOUND_QUEUE_CAPACITY);
    let (close_tx, close_rx) = watch::channel(false);
    shared
        .connections
        .lock()
        .map_err(|_| "receiver connection registry failed")?
        .insert(
            id,
            ConnectionHandle {
                outbound: outbound_tx,
                close: close_tx,
            },
        );
    shared.emit_counts();
    run_socket(
        id,
        ip,
        socket,
        ConnectionIo {
            outbound: outbound_rx,
            close: close_rx,
        },
        shared,
        pairing_timeout,
        max_message_bytes,
    )
    .await
}

async fn run_socket<S>(
    id: u64,
    ip: String,
    mut socket: WebSocketStream<S>,
    mut io: ConnectionIo,
    shared: Arc<Shared>,
    pairing_timeout: Duration,
    max_message_bytes: usize,
) -> Result<(), String>
where
    WebSocketStream<S>: SinkExt<Message>
        + StreamExt<Item = Result<Message, tokio_tungstenite::tungstenite::Error>>
        + Unpin,
{
    let mut authenticated = false;
    let mut pairing: Option<(ReceiverPairingSession, String, String, Instant)> = None;
    let mut timeout_tick = tokio::time::interval(Duration::from_secs(1));
    timeout_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        tokio::select! {
            changed = io.close.changed() => {
                if changed.is_err() || *io.close.borrow() {
                    break;
                }
            }
            _ = timeout_tick.tick(), if !authenticated && pairing.is_some() => {
                if pairing.as_ref().is_some_and(|(_, _, _, started)| started.elapsed() >= pairing_timeout) {
                    shared.record_pairing_failure(&ip);
                    let _ = socket.send(Message::Text(r#"{"type":"pairing_denied"}"#.into())).await;
                    break;
                }
            }
            outbound = io.outbound.recv() => {
                match outbound {
                    Some(Outbound::Json(value)) => {
                        send_value(&mut socket, value).await?;
                    }
                    None => break,
                }
            }
            incoming = socket.next() => {
                let Some(incoming) = incoming else { break };
                let incoming = incoming.map_err(|_| "WebSocket connection failed".to_owned())?;
                let Message::Text(text) = incoming else { continue };
                if text.len() > max_message_bytes {
                    return Err("receiver message exceeded size limit".into());
                }
                let frame = match serde_json::from_str::<SenderFrame>(&text) {
                    Ok(frame) => frame,
                    Err(_) => continue,
                };
                match frame {
                    SenderFrame::Ping => send_value(&mut socket, json!({"type":"pong"})).await?,
                    SenderFrame::Auth { token } if !authenticated => {
                        authenticated = shared.is_authorized(&token);
                        send_value(
                            &mut socket,
                            json!({
                                "type":"auth_response",
                                "success":authenticated,
                                "certFingerprint":shared.fingerprint,
                                "players":shared.players,
                                "browsers":shared.browsers,
                                "screenMirrorWebRtc":shared.screen_mirror_web_rtc,
                            }),
                        ).await?;
                        if authenticated {
                            let digest = token_digest(&token);
                            shared.authenticated.lock().map_err(|_| "authentication registry failed")?.insert(id, digest.clone());
                            shared.emit(ReceiverEvent::Authenticated {
                                connection_id: id,
                                token_digest: digest,
                            });
                            shared.emit_counts();
                        } else {
                            break;
                        }
                    }
                    SenderFrame::PairingCommit { commit, device_name, device_uuid }
                        if !authenticated =>
                    {
                        if shared.is_locked_out(&ip)
                            || device_name.is_empty()
                            || device_name.len() > 128
                            || device_uuid.is_empty()
                            || device_uuid.len() > 128
                        {
                            send_value(&mut socket, json!({"type":"pairing_denied"})).await?;
                            break;
                        }
                        let pairing_busy = {
                            let mut owner = shared.pairing_owner.lock().map_err(|_| "pairing registry failed")?;
                            if owner.is_some_and(|owner| owner != id) {
                                true
                            } else {
                                *owner = Some(id);
                                false
                            }
                        };
                        if pairing_busy {
                            send_value(&mut socket, json!({"type":"pairing_denied"})).await?;
                            break;
                        }
                        let (session, challenge) = ReceiverPairingSession::start(&commit)
                            .map_err(|_| "invalid pairing commitment".to_owned())?;
                        pairing = Some((
                            session,
                            device_name.clone(),
                            device_uuid.clone(),
                            Instant::now(),
                        ));
                        shared.emit(ReceiverEvent::PairingStarted {
                            connection_id: id,
                            device_name,
                            device_uuid,
                        });
                        send_frame(&mut socket, &challenge).await?;
                    }
                    SenderFrame::PairingReveal { sender_eph_pub, nonce_s }
                        if !authenticated =>
                    {
                        let (_, device_name, device_uuid, _) = pairing
                            .as_ref()
                            .ok_or_else(|| "pairing reveal arrived without a commit".to_owned())?;
                        let device_name = device_name.clone();
                        let device_uuid = device_uuid.clone();
                        let sas = pairing
                            .as_mut()
                            .expect("checked pairing session")
                            .0
                            .accept_reveal(&sender_eph_pub, &nonce_s)
                            .map_err(|_| "pairing reveal was invalid".to_owned())?;
                        shared.emit(ReceiverEvent::PairingRequested {
                            connection_id: id,
                            device_name,
                            device_uuid,
                            sas_code: sas,
                        });
                    }
                    SenderFrame::PairingConfirmation { mac } if !authenticated => {
                        let (session, device_name, device_uuid, _) = pairing
                            .as_ref()
                            .ok_or_else(|| "pairing confirmation arrived without a reveal".to_owned())?;
                        let token = random_token()?;
                        let approval = session
                            .approve(
                                &mac,
                                &CredentialBundle {
                                    token: token.clone(),
                                    cert_fingerprint: Some(shared.fingerprint.clone()),
                                    players: shared.players.clone(),
                                    browsers: shared.browsers.clone(),
                                    screen_mirror_web_rtc: shared.screen_mirror_web_rtc,
                                },
                            )
                            .map_err(|_| "pairing confirmation was invalid".to_owned())?;
                        shared
                            .authorized_tokens
                            .lock()
                            .map_err(|_| "credential registry failed")?
                            .insert(token.clone());
                        authenticated = true;
                        shared.authenticated.lock().map_err(|_| "authentication registry failed")?.insert(id, token_digest(&token));
                        send_frame(&mut socket, &approval).await?;
                        shared.emit(ReceiverEvent::Paired {
                            connection_id: id,
                            device_name: device_name.clone(),
                            device_uuid: device_uuid.clone(),
                            token,
                        });
                        shared.emit_counts();
                        if let Ok(mut owner) = shared.pairing_owner.lock() {
                            *owner = None;
                        }
                        pairing = None;
                    }
                    SenderFrame::Command { action, payload } if authenticated => {
                        let command = ReceiverCommand::decode(action, payload);
                        shared.emit(ReceiverEvent::Command {
                            connection_id: id,
                            command,
                            raw: text.to_string(),
                        });
                    }
                    _ => {}
                }
            }
        }
    }
    Ok(())
}

fn cleanup_connection(id: u64, shared: &Shared) {
    if let Ok(mut connections) = shared.connections.lock() {
        connections.remove(&id);
    }
    if let Ok(mut authenticated) = shared.authenticated.lock() {
        authenticated.remove(&id);
    }
    if let Ok(mut owner) = shared.pairing_owner.lock()
        && owner.is_some_and(|owner| owner == id)
    {
        *owner = None;
    }
    shared.emit(ReceiverEvent::ClientDisconnected { connection_id: id });
    shared.emit_counts();
}

async fn send_frame<S, T>(socket: &mut WebSocketStream<S>, value: &T) -> Result<(), String>
where
    WebSocketStream<S>: SinkExt<Message> + Unpin,
    T: Serialize,
{
    let value = serde_json::to_value(value).map_err(|error| error.to_string())?;
    send_value(socket, value).await
}

async fn send_value<S>(socket: &mut WebSocketStream<S>, value: Value) -> Result<(), String>
where
    WebSocketStream<S>: SinkExt<Message> + Unpin,
{
    let text = serde_json::to_string(&value).map_err(|error| error.to_string())?;
    socket
        .send(Message::Text(text.into()))
        .await
        .map_err(|_| "could not send receiver WebSocket frame".to_owned())
}

async fn bind_next_port(start: u16, attempts: u16) -> Result<TcpListener, String> {
    for offset in 0..attempts {
        let port = start
            .checked_add(offset)
            .ok_or("receiver port range overflow")?;
        match TcpListener::bind(("0.0.0.0", port)).await {
            Ok(listener) => return Ok(listener),
            Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => continue,
            Err(error) => return Err(error.to_string()),
        }
    }
    Err(format!(
        "ports {start} through {} are unavailable",
        start.saturating_add(attempts.saturating_sub(1))
    ))
}

fn advertise(name: &str, uuid: &str, port: u16) -> Result<ServiceDaemon, String> {
    let daemon = ServiceDaemon::new().map_err(|error| error.to_string())?;
    let port_text = port.to_string();
    let properties = [("uuid", uuid), ("wss_port", port_text.as_str())];
    let service = ServiceInfo::new(
        "_playbridge._tcp.local.",
        name,
        &format!("{uuid}.local."),
        "",
        port,
        &properties[..],
    )
    .map_err(|error| error.to_string())?
    .enable_addr_auto();
    daemon
        .register(service)
        .map_err(|error| error.to_string())?;
    Ok(daemon)
}

fn validate_config(config: &ReceiverConfig) -> Result<(), String> {
    if config.name.trim().is_empty() || config.name.len() > 128 {
        return Err("receiver name must contain 1-128 characters".into());
    }
    if config.uuid.trim().is_empty() || config.uuid.len() > 128 {
        return Err("receiver UUID must contain 1-128 characters".into());
    }
    if config.max_connections == 0 || config.max_message_bytes < 1024 {
        return Err("receiver resource limits are invalid".into());
    }
    Ok(())
}

fn tls_config(identity: &ReceiverIdentity) -> Result<(ServerConfig, String), String> {
    let (_, parsed) =
        parse_x509_certificate(&identity.certificate_der).map_err(|error| error.to_string())?;
    let fingerprint = format!(
        "sha256/{}",
        BASE64.encode(Sha256::digest(parsed.public_key().raw))
    );
    let key = match identity.private_key_kind {
        PrivateKeyKind::Pkcs1 => {
            PrivateKeyDer::Pkcs1(PrivatePkcs1KeyDer::from(identity.private_key_der.clone()))
        }
        PrivateKeyKind::Pkcs8 => {
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.private_key_der.clone()))
        }
    };
    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let config = ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|error| error.to_string())?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(identity.certificate_der.clone())],
            key,
        )
        .map_err(|error| error.to_string())?;
    Ok((config, fingerprint))
}

fn random_token() -> Result<String, String> {
    let mut bytes = [0_u8; 32];
    getrandom::fill(&mut bytes).map_err(|error| error.to_string())?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

pub fn token_digest(token: &str) -> String {
    format!("sha256:{:x}", Sha256::digest(token.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use playbridge_cast_core::{
        playbridge::{PairingSession, ReceiverFrame, SenderFrame},
        secure_ws::SecureWebSocket,
    };
    use rcgen::{CertificateParams, KeyPair};

    fn identity() -> ReceiverIdentity {
        let key = KeyPair::generate().unwrap();
        let certificate = CertificateParams::new(vec!["localhost".into()])
            .unwrap()
            .self_signed(&key)
            .unwrap();
        ReceiverIdentity {
            certificate_der: certificate.der().to_vec(),
            private_key_der: key.serialize_der(),
            private_key_kind: PrivateKeyKind::Pkcs8,
        }
    }

    #[test]
    fn receiver_commands_are_typed_without_rejecting_future_actions() {
        assert_eq!(
            ReceiverCommand::decode("control".into(), Some(json!({"command":"pause"}))),
            ReceiverCommand::Control(json!({"command":"pause"}))
        );
        assert_eq!(
            ReceiverCommand::decode(
                "screen_mirror_start".into(),
                Some(json!({"sessionId":"session","protocolVersion":1})),
            ),
            ReceiverCommand::ScreenMirrorStart(json!({"sessionId":"session","protocolVersion":1}))
        );
        assert!(matches!(
            ReceiverCommand::decode("future".into(), Some(json!({"value":1}))),
            ReceiverCommand::Unknown { action, .. } if action == "future"
        ));
    }

    #[test]
    fn token_digest_is_stable_and_distinct_from_plaintext() {
        let digest = token_digest("secret");
        assert_eq!(
            digest,
            "sha256:2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b"
        );
        assert_ne!(digest, "secret");
    }

    #[tokio::test]
    async fn receiver_uses_next_available_port() {
        let mut selected = None;
        for port in 40_000..50_000 {
            let Ok(occupied) = TcpListener::bind(("0.0.0.0", port)).await else {
                continue;
            };
            let Ok(next) = TcpListener::bind(("0.0.0.0", port + 1)).await else {
                continue;
            };
            drop(next);
            selected = Some((occupied, port));
            break;
        }
        let (occupied, port) = selected.expect("could not find two consecutive test ports");
        let mut config = ReceiverConfig::new("Test".into(), "test-id".into(), identity());
        config.preferred_port = port;
        config.fallback_attempts = 2;
        let host = ReceiverHost::start(config).await.unwrap();
        assert_eq!(host.port(), port + 1);
        host.shutdown().await;
        drop(occupied);
    }

    #[tokio::test]
    async fn receiver_pairs_authenticates_and_emits_typed_commands() {
        let mut config = ReceiverConfig::new("Test".into(), "test-id".into(), identity());
        config.preferred_port = 0;
        config.fallback_attempts = 1;
        config.players = vec!["internal_mpv".into()];
        config.screen_mirror_web_rtc = true;
        let host = ReceiverHost::start(config).await.unwrap();
        let mut events = host.subscribe();
        let endpoint = format!("wss://127.0.0.1:{}/", host.port());

        let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
            .await
            .unwrap();
        let pin = socket.served_spki_pin().to_owned();
        let (mut pairing, commit) =
            PairingSession::start("Phone".into(), "phone-id".into()).unwrap();
        socket.send(&commit).await.unwrap();
        let challenge = socket.receive().await.unwrap().unwrap();
        let ReceiverFrame::PairingChallenge {
            tv_eph_pub,
            nonce_t,
        } = challenge
        else {
            panic!("expected pairing challenge");
        };
        let (sas, reveal) = pairing.accept_challenge(&tv_eph_pub, &nonce_t).unwrap();
        socket.send(&reveal).await.unwrap();

        let requested = tokio::time::timeout(Duration::from_secs(2), async {
            loop {
                if let ReceiverEvent::PairingRequested {
                    sas_code,
                    device_uuid,
                    ..
                } = events.recv().await.unwrap()
                {
                    break (sas_code, device_uuid);
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(requested, (sas.clone(), "phone-id".into()));

        socket
            .send(&pairing.confirmation(&sas, &sas).unwrap())
            .await
            .unwrap();
        let approval = socket.receive().await.unwrap().unwrap();
        let ReceiverFrame::PairingApproved { nonce, ciphertext } = approval else {
            panic!("expected pairing approval");
        };
        let credentials = pairing
            .decrypt_credentials(&nonce, &ciphertext, Some(&pin))
            .unwrap();
        assert!(credentials.screen_mirror_web_rtc);
        socket
            .send(&SenderFrame::Command {
                action: "control".into(),
                payload: Some(json!({"command":"pause"})),
            })
            .await
            .unwrap();
        let (command_connection_id, command) = tokio::time::timeout(Duration::from_secs(2), async {
            loop {
                if let ReceiverEvent::Command {
                    connection_id,
                    command,
                    ..
                } = events.recv().await.unwrap()
                {
                    break (connection_id, command);
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(
            command,
            ReceiverCommand::Control(json!({"command":"pause"}))
        );
        socket.close().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), async {
            loop {
                if let ReceiverEvent::ClientDisconnected { connection_id } =
                    events.recv().await.unwrap()
                {
                    assert_eq!(connection_id, command_connection_id);
                    break;
                }
            }
        })
        .await
        .expect("receiver did not report the disconnected connection");

        let mut authenticated = SecureWebSocket::connect_pinned(&endpoint, &pin)
            .await
            .unwrap();
        authenticated
            .send(&SenderFrame::Auth {
                token: credentials.token.clone(),
            })
            .await
            .unwrap();
        assert!(matches!(
            authenticated.receive().await.unwrap(),
            Some(ReceiverFrame::AuthResponse {
                success: true,
                screen_mirror_web_rtc: true,
                ..
            })
        ));
        host.replace_authorized_tokens(Vec::new());
        let revoked = tokio::time::timeout(Duration::from_secs(2), authenticated.receive())
            .await
            .expect("revoked connection did not close");
        assert!(
            !matches!(revoked, Ok(Some(_))),
            "revoked connection received another frame"
        );
        host.shutdown().await;
    }
}
