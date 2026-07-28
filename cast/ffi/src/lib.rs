use std::{
    ffi::{CStr, CString, c_char},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver as MessageReceiver, SyncSender, TrySendError},
    },
    thread,
    time::Duration,
};

use playbridge_cast_core::discovery::{
    DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver, ReceiverProtocol,
};
use playbridge_cast_core::{
    castv2::CastChannel,
    roku::{DEFAULT_ECP_PORT, RokuClient},
    session::{MediaRequest, PlaybackState, ReceiverSession},
    upnp::Renderer,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

uniffi::setup_scaffolding!();

mod receiver_runtime;
#[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
mod sender_services;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
pub enum Protocol {
    PlayBridge,
    Dlna,
    Roku,
    Dial,
    GoogleCast,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct ReceiverInfo {
    pub id: String,
    pub protocol: Protocol,
    pub name: String,
    pub addresses: Vec<String>,
    pub port: Option<u16>,
    pub wss_port: Option<u16>,
    pub location: Option<String>,
    pub uuid: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Enum)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum ReceiverEvent {
    Started { protocol: Protocol },
    Found { receiver: ReceiverInfo },
    Updated { receiver: ReceiverInfo },
    Error { protocol: Protocol, message: String },
    Finished { protocol: Protocol },
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum BindingError {
    #[error("failed to start discovery worker: {message}")]
    StartFailed { message: String },
}

#[derive(uniffi::Object)]
pub struct DiscoveryScanner {
    events: Mutex<mpsc::Receiver<ReceiverEvent>>,
    cancelled: Arc<AtomicBool>,
}

const CAST_CORE_ABI_VERSION: u32 = 1;
const SESSION_COMMAND_CAPACITY: usize = 32;
const SESSION_EVENT_CAPACITY: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum SessionProtocol {
    Dlna,
    Roku,
    GoogleCast,
}

#[derive(Debug, Clone, Deserialize)]
struct SessionTarget {
    protocol: SessionProtocol,
    #[serde(default)]
    addresses: Vec<String>,
    port: Option<u16>,
    location: Option<String>,
}

impl SessionTarget {
    fn address(&self) -> Result<&str, String> {
        self.addresses
            .iter()
            .map(String::as_str)
            .find(|address| !address.trim().is_empty())
            .ok_or_else(|| "target must include at least one address".to_owned())
    }

    fn ordered_addresses(&self) -> Vec<&str> {
        let mut addresses: Vec<_> = self
            .addresses
            .iter()
            .map(String::as_str)
            .filter(|address| !address.trim().is_empty())
            .collect();
        addresses.sort_by_key(|address| address.contains(':'));
        addresses
    }
}

#[derive(Debug, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum SessionCommand {
    Load {
        request_id: Value,
        url: String,
        title: Option<String>,
        metadata: Option<String>,
    },
    Play {
        request_id: Value,
    },
    Pause {
        request_id: Value,
    },
    Stop {
        request_id: Value,
    },
    Seek {
        request_id: Value,
        position_seconds: f64,
    },
    RelativeSeek {
        request_id: Value,
        forward: bool,
    },
    Status {
        request_id: Value,
    },
    Disconnect {
        request_id: Value,
    },
}

impl SessionCommand {
    fn request_id(&self) -> &Value {
        match self {
            Self::Load { request_id, .. }
            | Self::Play { request_id }
            | Self::Pause { request_id }
            | Self::Stop { request_id }
            | Self::Seek { request_id, .. }
            | Self::RelativeSeek { request_id, .. }
            | Self::Status { request_id }
            | Self::Disconnect { request_id } => request_id,
        }
    }

    fn operation(&self) -> &'static str {
        match self {
            Self::Load { .. } => "load",
            Self::Play { .. } => "play",
            Self::Pause { .. } => "pause",
            Self::Stop { .. } => "stop",
            Self::Seek { .. } => "seek",
            Self::RelativeSeek { .. } => "relative_seek",
            Self::Status { .. } => "status",
            Self::Disconnect { .. } => "disconnect",
        }
    }

    fn has_valid_request_id(&self) -> bool {
        matches!(
            self.request_id(),
            Value::String(_) | Value::Number(_) | Value::Bool(_)
        )
    }
}

#[derive(Debug, Serialize)]
struct SessionCapabilities {
    load: bool,
    playback_control: bool,
    seek: bool,
    status: bool,
    receiver_app_available: Option<bool>,
}

#[derive(Debug, Serialize)]
#[serde(tag = "event", rename_all = "snake_case")]
enum SessionEvent {
    Connected {
        protocol: SessionProtocol,
        capabilities: SessionCapabilities,
        name: Option<String>,
    },
    Operation {
        request_id: Value,
        operation: &'static str,
        ok: bool,
    },
    Status {
        request_id: Value,
        status: SessionPlaybackStatus,
    },
    Error {
        #[serde(skip_serializing_if = "Option::is_none")]
        request_id: Option<Value>,
        operation: &'static str,
        message: String,
    },
    Finished {
        reason: &'static str,
    },
}

#[derive(Debug, Serialize)]
struct SessionPlaybackStatus {
    state: &'static str,
    position_seconds: f64,
    duration_seconds: f64,
}

pub struct CastSession {
    commands: SyncSender<SessionCommand>,
    events: Mutex<MessageReceiver<SessionEvent>>,
    cancelled: Arc<AtomicBool>,
}

#[uniffi::export]
impl DiscoveryScanner {
    #[uniffi::constructor]
    pub fn new(protocols: Vec<Protocol>, timeout_ms: u64) -> Result<Arc<Self>, BindingError> {
        let protocols = protocols.into_iter().map(Into::into).collect();
        let timeout = Duration::from_millis(timeout_ms.clamp(250, 30_000));
        let (sender, receiver) = mpsc::sync_channel(64);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        std::thread::Builder::new()
            .name("playbridge-discovery".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Runtime::new() {
                    Ok(runtime) => runtime,
                    Err(error) => {
                        let _ = forward_event(
                            &sender,
                            ReceiverEvent::Error {
                                protocol: Protocol::PlayBridge,
                                message: error.to_string(),
                            },
                        );
                        return;
                    }
                };
                runtime.block_on(async move {
                    let mut stream = DiscoveryStream::start(DiscoveryConfig { protocols, timeout });
                    let mut cancellation_poll = tokio::time::interval(Duration::from_millis(50));
                    loop {
                        tokio::select! {
                            _ = cancellation_poll.tick() => {
                                if worker_cancelled.load(Ordering::Acquire) {
                                    stream.cancel();
                                    break;
                                }
                            }
                            event = stream.next() => match event {
                                Some(event) => {
                                    if !forward_event(&sender, event.into()) {
                                        stream.cancel();
                                        break;
                                    }
                                }
                                None => break,
                            }
                        }
                    }
                });
            })
            .map_err(|error| BindingError::StartFailed {
                message: error.to_string(),
            })?;
        Ok(Arc::new(Self {
            events: Mutex::new(receiver),
            cancelled,
        }))
    }

    pub fn next_event(&self, wait_ms: u64) -> Option<ReceiverEvent> {
        self.events
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .recv_timeout(Duration::from_millis(wait_ms.min(30_000)))
            .ok()
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

fn forward_event(sender: &SyncSender<ReceiverEvent>, event: ReceiverEvent) -> bool {
    match sender.try_send(event) {
        Ok(()) | Err(TrySendError::Full(_)) => true,
        Err(TrySendError::Disconnected(_)) => false,
    }
}

impl Drop for DiscoveryScanner {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

impl CastSession {
    fn start(target: SessionTarget, timeout_ms: u64) -> Result<Arc<Self>, BindingError> {
        validate_target(&target).map_err(|message| BindingError::StartFailed { message })?;
        let timeout = Duration::from_millis(timeout_ms.clamp(250, 30_000));
        let (command_sender, command_receiver) = mpsc::sync_channel(SESSION_COMMAND_CAPACITY);
        let (event_sender, event_receiver) = mpsc::sync_channel(SESSION_EVENT_CAPACITY);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        thread::Builder::new()
            .name("playbridge-cast-session".into())
            .spawn(move || {
                session_worker(
                    target,
                    timeout,
                    command_receiver,
                    event_sender,
                    worker_cancelled,
                )
            })
            .map_err(|error| BindingError::StartFailed {
                message: error.to_string(),
            })?;
        Ok(Arc::new(Self {
            commands: command_sender,
            events: Mutex::new(event_receiver),
            cancelled,
        }))
    }

    fn submit(&self, command: SessionCommand) -> bool {
        if self.cancelled.load(Ordering::Acquire) || !command.has_valid_request_id() {
            return false;
        }
        self.commands.try_send(command).is_ok()
    }

    fn next_event(&self, wait_ms: u64) -> Option<SessionEvent> {
        self.events
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .recv_timeout(Duration::from_millis(wait_ms.min(30_000)))
            .ok()
    }

    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

impl Drop for CastSession {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

fn validate_target(target: &SessionTarget) -> Result<(), String> {
    match target.protocol {
        SessionProtocol::Dlna => {
            let location = target.location.as_deref().unwrap_or("").trim();
            if location.is_empty() {
                return Err("DLNA target must include a device description location".into());
            }
            url::Url::parse(location)
                .map_err(|error| format!("invalid DLNA device description location: {error}"))?;
        }
        SessionProtocol::Roku | SessionProtocol::GoogleCast => {
            target.address()?;
        }
    }
    Ok(())
}

fn session_worker(
    target: SessionTarget,
    operation_timeout: Duration,
    commands: MessageReceiver<SessionCommand>,
    events: SyncSender<SessionEvent>,
    cancelled: Arc<AtomicBool>,
) {
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => {
            let _ = send_session_event(
                &events,
                SessionEvent::Error {
                    request_id: None,
                    operation: "connect",
                    message: error.to_string(),
                },
                &cancelled,
            );
            let _ = send_session_event(
                &events,
                SessionEvent::Finished {
                    reason: "connection_failed",
                },
                &cancelled,
            );
            return;
        }
    };

    let connected = runtime.block_on(connect_target(&target, operation_timeout));
    let (mut receiver, roku_receiver_app, name, connected_address) = match connected {
        Ok(connected) => connected,
        Err(message) => {
            let _ = send_session_event(
                &events,
                SessionEvent::Error {
                    request_id: None,
                    operation: "connect",
                    message,
                },
                &cancelled,
            );
            let _ = send_session_event(
                &events,
                SessionEvent::Finished {
                    reason: "connection_failed",
                },
                &cancelled,
            );
            return;
        }
    };

    let capabilities = SessionCapabilities {
        load: target.protocol != SessionProtocol::Roku || roku_receiver_app == Some(true),
        playback_control: true,
        seek: target.protocol != SessionProtocol::Roku,
        status: true,
        receiver_app_available: (target.protocol == SessionProtocol::Roku)
            .then_some(roku_receiver_app)
            .flatten(),
    };
    if !send_session_event(
        &events,
        SessionEvent::Connected {
            protocol: target.protocol,
            capabilities,
            name,
        },
        &cancelled,
    ) {
        return;
    }

    let finish_reason = loop {
        if cancelled.load(Ordering::Acquire) {
            break "cancelled";
        }
        let command = match commands.recv_timeout(Duration::from_millis(50)) {
            Ok(command) => command,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => break "command_channel_closed",
        };
        let disconnect = matches!(command, SessionCommand::Disconnect { .. });
        let request_id = command.request_id().clone();
        let operation = command.operation();
        let result = runtime.block_on(execute_session_command(
            &target,
            connected_address.as_deref(),
            &mut receiver,
            roku_receiver_app,
            &command,
            operation_timeout,
        ));
        let event = match result {
            Ok(Some(status)) => SessionEvent::Status { request_id, status },
            Ok(None) => SessionEvent::Operation {
                request_id,
                operation,
                ok: true,
            },
            Err(message) => SessionEvent::Error {
                request_id: Some(request_id),
                operation,
                message,
            },
        };
        if !send_session_event(&events, event, &cancelled) {
            return;
        }
        if disconnect {
            break "disconnected";
        }
    };

    let _ = send_session_event(
        &events,
        SessionEvent::Finished {
            reason: finish_reason,
        },
        &cancelled,
    );
}

async fn connect_target(
    target: &SessionTarget,
    operation_timeout: Duration,
) -> Result<
    (
        Option<ReceiverSession>,
        Option<bool>,
        Option<String>,
        Option<String>,
    ),
    String,
> {
    match target.protocol {
        SessionProtocol::Dlna => {
            let location = target.location.as_deref().expect("target was validated");
            let renderer = tokio::time::timeout(operation_timeout, Renderer::load(location))
                .await
                .map_err(|_| "DLNA connection timed out".to_owned())?
                .map_err(|error| error.to_string())?;
            let name = Some(renderer.friendly_name().to_owned());
            Ok((Some(ReceiverSession::Dlna(renderer)), None, name, None))
        }
        SessionProtocol::Roku => {
            let addresses = target.ordered_addresses();
            let attempt_timeout = operation_timeout / (addresses.len() as u32 + 1);
            let mut last_error = "Roku device probe failed".to_owned();
            for address in addresses {
                let client = match RokuClient::new(
                    address,
                    target.port.unwrap_or(DEFAULT_ECP_PORT),
                    attempt_timeout,
                ) {
                    Ok(client) => client,
                    Err(error) => {
                        last_error = format!("invalid Roku address {address}: {error}");
                        continue;
                    }
                };
                let name = match tokio::time::timeout(attempt_timeout, client.device_name()).await {
                    Ok(Ok(name)) => name,
                    Ok(Err(error)) => {
                        last_error = format!("Roku probe at {address} failed: {error}");
                        continue;
                    }
                    Err(_) => {
                        last_error = format!("Roku probe at {address} timed out");
                        continue;
                    }
                };
                let receiver_app = tokio::time::timeout(attempt_timeout, client.has_play_on_roku())
                    .await
                    .ok()
                    .and_then(Result::ok);
                return Ok((
                    Some(ReceiverSession::Roku(client)),
                    receiver_app,
                    Some(name),
                    Some(address.to_owned()),
                ));
            }
            Err(last_error)
        }
        SessionProtocol::GoogleCast => {
            let port = target.port.unwrap_or(8009);
            let addresses = target.ordered_addresses();
            let attempt_timeout = operation_timeout / addresses.len() as u32;
            let mut last_error = "Google Cast connection failed".to_owned();
            for address in addresses {
                match tokio::time::timeout(attempt_timeout, CastChannel::connect(address, port))
                    .await
                {
                    Ok(Ok(_)) => {
                        return Ok((None, None, None, Some(address.to_owned())));
                    }
                    Ok(Err(error)) => {
                        last_error = format!("Google Cast probe at {address} failed: {error}");
                    }
                    Err(_) => {
                        last_error = format!("Google Cast probe at {address} timed out");
                    }
                }
            }
            Err(last_error)
        }
    }
}

async fn execute_session_command(
    target: &SessionTarget,
    connected_address: Option<&str>,
    receiver: &mut Option<ReceiverSession>,
    roku_receiver_app: Option<bool>,
    command: &SessionCommand,
    operation_timeout: Duration,
) -> Result<Option<SessionPlaybackStatus>, String> {
    let operation = async {
        match command {
            SessionCommand::Load {
                url,
                title,
                metadata,
                ..
            } => {
                if url.trim().is_empty() {
                    return Err("media URL must not be empty".to_owned());
                }
                let media = MediaRequest {
                    url: url.clone(),
                    title: title.clone(),
                    metadata: metadata.clone(),
                };
                if target.protocol == SessionProtocol::Roku && roku_receiver_app != Some(true) {
                    return Err("compatible Play on Roku receiver app 15985 is required".into());
                }
                if target.protocol == SessionProtocol::GoogleCast {
                    let session = ReceiverSession::connect_google_cast(
                        connected_address
                            .ok_or_else(|| "Google Cast receiver is not connected".to_owned())?,
                        target.port.unwrap_or(8009),
                        &media,
                    )
                    .await
                    .map_err(|error| error.to_string())?;
                    *receiver = Some(session);
                } else {
                    receiver
                        .as_mut()
                        .ok_or_else(|| "receiver is not connected".to_owned())?
                        .load(&media)
                        .await
                        .map_err(|error| error.to_string())?;
                }
                Ok(None)
            }
            SessionCommand::Play { .. } => {
                receiver_mut(receiver)?.play().await.map_err(to_message)?;
                Ok(None)
            }
            SessionCommand::Pause { .. } => {
                receiver_mut(receiver)?.pause().await.map_err(to_message)?;
                Ok(None)
            }
            SessionCommand::Stop { .. } => {
                receiver_mut(receiver)?.stop().await.map_err(to_message)?;
                Ok(None)
            }
            SessionCommand::Seek {
                position_seconds, ..
            } => {
                if !position_seconds.is_finite() || *position_seconds < 0.0 {
                    return Err("seek position must be a finite non-negative number".into());
                }
                receiver_mut(receiver)?
                    .seek(*position_seconds)
                    .await
                    .map_err(to_message)?;
                Ok(None)
            }
            SessionCommand::RelativeSeek { forward, .. } => {
                receiver_mut(receiver)?
                    .relative_seek(*forward)
                    .await
                    .map_err(to_message)?;
                Ok(None)
            }
            SessionCommand::Status { .. } => {
                let status = receiver_mut(receiver)?.status().await.map_err(to_message)?;
                Ok(Some(SessionPlaybackStatus {
                    state: playback_state_name(status.state),
                    position_seconds: status.position_seconds,
                    duration_seconds: status.duration_seconds,
                }))
            }
            SessionCommand::Disconnect { .. } => Ok(None),
        }
    };
    tokio::time::timeout(operation_timeout, operation)
        .await
        .map_err(|_| format!("{} timed out", command.operation()))?
}

fn receiver_mut(receiver: &mut Option<ReceiverSession>) -> Result<&mut ReceiverSession, String> {
    receiver
        .as_mut()
        .ok_or_else(|| "load media before sending this command".to_owned())
}

fn to_message(error: playbridge_cast_core::CastError) -> String {
    error.to_string()
}

fn playback_state_name(state: PlaybackState) -> &'static str {
    match state {
        PlaybackState::Buffering => "buffering",
        PlaybackState::Playing => "playing",
        PlaybackState::Paused => "paused",
        PlaybackState::Stopped => "stopped",
        PlaybackState::Finished => "finished",
        PlaybackState::Unknown => "unknown",
    }
}

fn send_session_event(
    sender: &SyncSender<SessionEvent>,
    mut event: SessionEvent,
    cancelled: &AtomicBool,
) -> bool {
    loop {
        match sender.try_send(event) {
            Ok(()) => return true,
            Err(TrySendError::Full(returned)) => {
                if cancelled.load(Ordering::Acquire) {
                    return false;
                }
                event = returned;
                thread::sleep(Duration::from_millis(5));
            }
            Err(TrySendError::Disconnected(_)) => return false,
        }
    }
}

impl From<Protocol> for ReceiverProtocol {
    fn from(value: Protocol) -> Self {
        match value {
            Protocol::PlayBridge => Self::PlayBridge,
            Protocol::Dlna => Self::Dlna,
            Protocol::Roku => Self::Roku,
            Protocol::Dial => Self::Dial,
            Protocol::GoogleCast => Self::GoogleCast,
        }
    }
}

impl From<ReceiverProtocol> for Protocol {
    fn from(value: ReceiverProtocol) -> Self {
        match value {
            ReceiverProtocol::PlayBridge => Self::PlayBridge,
            ReceiverProtocol::Dlna => Self::Dlna,
            ReceiverProtocol::Roku => Self::Roku,
            ReceiverProtocol::Dial => Self::Dial,
            ReceiverProtocol::GoogleCast => Self::GoogleCast,
        }
    }
}

impl From<Receiver> for ReceiverInfo {
    fn from(value: Receiver) -> Self {
        Self {
            id: value.id.0,
            protocol: value.protocol.into(),
            name: value.name,
            addresses: value.addresses,
            port: value.port,
            wss_port: value.wss_port,
            location: value.location,
            uuid: value.uuid,
        }
    }
}

impl From<DiscoveryEvent> for ReceiverEvent {
    fn from(value: DiscoveryEvent) -> Self {
        match value {
            DiscoveryEvent::Started(protocol) => Self::Started {
                protocol: protocol.into(),
            },
            DiscoveryEvent::Found(receiver) => Self::Found {
                receiver: receiver.into(),
            },
            DiscoveryEvent::Updated(receiver) => Self::Updated {
                receiver: receiver.into(),
            },
            DiscoveryEvent::Error { protocol, message } => Self::Error {
                protocol: protocol.into(),
                message,
            },
            DiscoveryEvent::Finished(protocol) => Self::Finished {
                protocol: protocol.into(),
            },
        }
    }
}

fn protocols_from_mask(mask: u32) -> Vec<Protocol> {
    [
        (1, Protocol::PlayBridge),
        (2, Protocol::Dlna),
        (4, Protocol::Roku),
        (8, Protocol::Dial),
        (16, Protocol::GoogleCast),
    ]
    .into_iter()
    .filter_map(|(bit, protocol)| (mask & bit != 0).then_some(protocol))
    .collect()
}

/// Returns the version of the stable C ABI and its JSON contracts.
#[unsafe(no_mangle)]
pub extern "C" fn pb_cast_core_abi_version() -> u32 {
    CAST_CORE_ABI_VERSION
}

/// Starts a session worker from a UTF-8 JSON target.
///
/// # Safety
///
/// `target_json` must point to a valid NUL-terminated string for the duration of
/// this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_session_start(
    target_json: *const c_char,
    timeout_ms: u64,
) -> *mut CastSession {
    let Some(target_json) =
        (!target_json.is_null()).then(|| unsafe { CStr::from_ptr(target_json) })
    else {
        return std::ptr::null_mut();
    };
    let Ok(target_json) = target_json.to_str() else {
        return std::ptr::null_mut();
    };
    let Ok(target) = serde_json::from_str::<SessionTarget>(target_json) else {
        return std::ptr::null_mut();
    };
    match CastSession::start(target, timeout_ms) {
        Ok(session) => Arc::into_raw(session) as *mut CastSession,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Queues a command without blocking. Returns false for invalid JSON, an
/// invalid request ID, a full bounded queue, or a stopped worker.
///
/// # Safety
///
/// `session` must be a live session handle and `command_json` must point to a
/// valid NUL-terminated string for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_session_submit_json(
    session: *const CastSession,
    command_json: *const c_char,
) -> bool {
    let Some(session) = (unsafe { session.as_ref() }) else {
        return false;
    };
    if command_json.is_null() {
        return false;
    }
    let Ok(command_json) = (unsafe { CStr::from_ptr(command_json) }).to_str() else {
        return false;
    };
    let Ok(command) = serde_json::from_str::<SessionCommand>(command_json) else {
        return false;
    };
    session.submit(command)
}

/// Returns one session event as UTF-8 JSON, or null on timeout/end. Free it
/// with `pb_string_free`.
///
/// # Safety
///
/// `session` must be null or a live session handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_session_next_json(
    session: *const CastSession,
    wait_ms: u64,
) -> *mut c_char {
    let Some(session) = (unsafe { session.as_ref() }) else {
        return std::ptr::null_mut();
    };
    session
        .next_event(wait_ms)
        .and_then(|event| serde_json::to_string(&event).ok())
        .and_then(|json| CString::new(json).ok())
        .map(CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// Requests cancellation of a session worker.
///
/// # Safety
///
/// `session` must be null or a live session handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_session_cancel(session: *const CastSession) {
    if let Some(session) = unsafe { session.as_ref() } {
        session.cancel();
    }
}

/// Releases a session handle. The handle must be released at most once.
///
/// # Safety
///
/// `session` must be null or a live session handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_session_free(session: *const CastSession) {
    if !session.is_null() {
        drop(unsafe { Arc::from_raw(session) });
    }
}

/// Starts a discovery scanner for Dart/other C consumers. Bitmask values are
/// PlayBridge=1, DLNA=2, Roku=4, generic DIAL=8, GoogleCast=16.
#[unsafe(no_mangle)]
pub extern "C" fn pb_discovery_start(protocol_mask: u32, timeout_ms: u64) -> *mut DiscoveryScanner {
    let protocols = protocols_from_mask(protocol_mask);
    match DiscoveryScanner::new(protocols, timeout_ms) {
        Ok(scanner) => Arc::into_raw(scanner) as *mut DiscoveryScanner,
        Err(_) => std::ptr::null_mut(),
    }
}

/// Returns one UTF-8 JSON event, or null on timeout/end. Free it with
/// `pb_string_free`.
///
/// # Safety
///
/// `scanner` must be null or a live handle returned by `pb_discovery_start` that
/// has not yet been passed to `pb_discovery_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_discovery_next_json(
    scanner: *const DiscoveryScanner,
    wait_ms: u64,
) -> *mut c_char {
    let Some(scanner) = (unsafe { scanner.as_ref() }) else {
        return std::ptr::null_mut();
    };
    scanner
        .next_event(wait_ms)
        .and_then(|event| serde_json::to_string(&event).ok())
        .and_then(|json| CString::new(json).ok())
        .map(CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// Requests cancellation of a discovery scanner.
///
/// # Safety
///
/// `scanner` must be null or a live handle returned by `pb_discovery_start` that
/// has not yet been passed to `pb_discovery_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_discovery_cancel(scanner: *const DiscoveryScanner) {
    if let Some(scanner) = unsafe { scanner.as_ref() } {
        scanner.cancel();
    }
}

/// Releases a discovery scanner handle.
///
/// # Safety
///
/// `scanner` must be null or a live handle returned by `pb_discovery_start` and
/// this function must be called at most once for that handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_discovery_free(scanner: *const DiscoveryScanner) {
    if !scanner.is_null() {
        drop(unsafe { Arc::from_raw(scanner) });
    }
}

/// Releases a string returned by `pb_discovery_next_json`.
///
/// # Safety
///
/// `value` must be null or a pointer returned by `pb_discovery_next_json` and this
/// function must be called at most once for that pointer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_string_free(value: *mut c_char) {
    if !value.is_null() {
        drop(unsafe { CString::from_raw(value) });
    }
}

#[cfg(target_os = "android")]
mod android_jni {
    use jni::{
        EnvUnowned,
        errors::ThrowRuntimeExAndDefault,
        objects::{JClass, JString},
        sys::{jboolean, jint, jlong},
    };
    use std::ffi::{CStr, CString, c_char};

    use super::{DiscoveryScanner, pb_discovery_cancel, pb_discovery_free, pb_discovery_start};

    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    use super::pb_string_free;
    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    use super::sender_services::{
        SenderServices, pb_sender_services_abi_version, pb_sender_services_cancel,
        pb_sender_services_free, pb_sender_services_next_json, pb_sender_services_start,
        pb_sender_services_submit_json,
    };

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_connection_RustDiscoveryNative_start<
        'local,
    >(
        _env: EnvUnowned<'local>,
        _class: JClass<'local>,
        protocol_mask: jint,
        timeout_ms: jlong,
    ) -> jlong {
        std::panic::catch_unwind(|| {
            pb_discovery_start(protocol_mask as u32, timeout_ms.max(0) as u64) as jlong
        })
        .unwrap_or(0)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_connection_RustDiscoveryNative_nextEvent<
        'local,
    >(
        mut unowned_env: EnvUnowned<'local>,
        _class: JClass<'local>,
        handle: jlong,
        wait_ms: jlong,
    ) -> JString<'local> {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if handle == 0 {
                return None;
            }
            let Some(scanner) = (unsafe { (handle as *const DiscoveryScanner).as_ref() }) else {
                return None;
            };
            scanner
                .next_event(wait_ms.max(0) as u64)
                .and_then(|event| serde_json::to_string(&event).ok())
        }));

        let json = match result {
            Ok(json_opt) => json_opt,
            Err(_) => None,
        };

        unowned_env
            .with_env(|env| match json {
                Some(json) => env.new_string(json),
                None => Ok(JString::default()),
            })
            .resolve::<ThrowRuntimeExAndDefault>()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_connection_RustDiscoveryNative_cancel(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        let _ = std::panic::catch_unwind(|| {
            if handle != 0 {
                unsafe { pb_discovery_cancel(handle as *const DiscoveryScanner) };
            }
        });
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_connection_RustDiscoveryNative_free(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        let _ = std::panic::catch_unwind(|| {
            if handle != 0 {
                unsafe { pb_discovery_free(handle as *const DiscoveryScanner) };
            }
        });
    }

    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_abiVersion(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jint {
        std::panic::catch_unwind(|| pb_sender_services_abi_version()).unwrap_or(0) as jint
    }

    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_start(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jlong {
        std::panic::catch_unwind(|| pb_sender_services_start() as jlong).unwrap_or(0)
    }

    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_submitJson<
        'local,
    >(
        mut unowned_env: EnvUnowned<'local>,
        _class: JClass<'local>,
        handle: jlong,
        command_json: JString<'local>,
    ) -> jboolean {
        let accepted = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if handle == 0 {
                return false;
            }
            // Mirror discovery JNI: extract JSON via nextEvent-style env helpers.
            let command = unowned_env
                .with_env(|env| -> Result<String, jni::errors::Error> {
                    let java = env.get_string(&command_json)?;
                    Ok(java.to_str().into_owned())
                })
                .resolve::<ThrowRuntimeExAndDefault>();
            if command.is_empty() {
                return false;
            }
            let Ok(c_command) = CString::new(command) else {
                return false;
            };
            unsafe {
                pb_sender_services_submit_json(handle as *const SenderServices, c_command.as_ptr())
            }
        }))
        .unwrap_or(false);
        accepted
    }

    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_nextEvent<
        'local,
    >(
        mut unowned_env: EnvUnowned<'local>,
        _class: JClass<'local>,
        handle: jlong,
        wait_ms: jlong,
    ) -> JString<'local> {
        let json = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if handle == 0 {
                return None;
            }
            let ptr = unsafe {
                pb_sender_services_next_json(handle as *const SenderServices, wait_ms.max(0) as u64)
            };
            if ptr.is_null() {
                return None;
            }
            let owned = unsafe { CStr::from_ptr(ptr as *const c_char) }
                .to_string_lossy()
                .into_owned();
            unsafe { pb_string_free(ptr) };
            Some(owned)
        }))
        .unwrap_or(None);

        unowned_env
            .with_env(|env| match json {
                Some(json) => env.new_string(json),
                None => Ok(JString::default()),
            })
            .resolve::<ThrowRuntimeExAndDefault>()
    }

    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_cancel(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        let _ = std::panic::catch_unwind(|| {
            if handle != 0 {
                unsafe { pb_sender_services_cancel(handle as *const SenderServices) };
            }
        });
    }

    #[cfg(any(feature = "sender-services", feature = "sender-services-android"))]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_free(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        let _ = std::panic::catch_unwind(|| {
            if handle != 0 {
                unsafe { pb_sender_services_free(handle as *const SenderServices) };
            }
        });
    }

    /// Upstream JNI callback ABI (only meaningful with `sender-services-android`).
    #[cfg(feature = "sender-services-android")]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_upstreamAbiVersion(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jint {
        std::panic::catch_unwind(|| stream_proxy_rust::pb_proxy_upstream_abi_version() as jint)
            .unwrap_or(0)
    }

    #[cfg(feature = "sender-services-android")]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_cast_proxy_SenderServicesNative_upstreamCallbacksRegistered(
        _env: EnvUnowned,
        _class: JClass,
    ) -> jboolean {
        let ready = std::panic::catch_unwind(|| {
            stream_proxy_rust::pb_proxy_upstream_callbacks_registered() != 0
        })
        .unwrap_or(false);
        ready as jboolean
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stable_c_abi_version_is_one() {
        assert_eq!(pb_cast_core_abi_version(), 1);
    }

    #[test]
    fn session_target_requires_protocol_specific_endpoint() {
        let dlna: SessionTarget = serde_json::from_str(
            r#"{"protocol":"dlna","addresses":[],"location":"http://192.0.2.1/device.xml"}"#,
        )
        .unwrap();
        assert!(validate_target(&dlna).is_ok());

        let roku: SessionTarget =
            serde_json::from_str(r#"{"protocol":"roku","addresses":[]}"#).unwrap();
        assert_eq!(
            validate_target(&roku).unwrap_err(),
            "target must include at least one address"
        );
    }

    #[test]
    fn session_target_prefers_ipv4_but_retains_ipv6_fallbacks() {
        let target: SessionTarget = serde_json::from_str(
            r#"{"protocol":"google_cast","addresses":["fe80::1%en0","192.0.2.10","receiver.local"]}"#,
        )
        .unwrap();
        assert_eq!(
            target.ordered_addresses(),
            vec!["192.0.2.10", "receiver.local", "fe80::1%en0"]
        );
    }

    #[test]
    fn session_commands_require_scalar_request_ids() {
        let valid: SessionCommand = serde_json::from_str(
            r#"{"command":"seek","request_id":"seek-1","position_seconds":12.5}"#,
        )
        .unwrap();
        assert!(valid.has_valid_request_id());
        assert_eq!(valid.operation(), "seek");

        let invalid: SessionCommand =
            serde_json::from_str(r#"{"command":"play","request_id":{"nested":"not-supported"}}"#)
                .unwrap();
        assert!(!invalid.has_valid_request_id());
    }

    #[test]
    fn session_event_json_has_stable_tag_and_status_shape() {
        let event = SessionEvent::Status {
            request_id: Value::from(42),
            status: SessionPlaybackStatus {
                state: "playing",
                position_seconds: 3.5,
                duration_seconds: 10.0,
            },
        };
        let json = serde_json::to_value(event).unwrap();
        assert_eq!(json["event"], "status");
        assert_eq!(json["request_id"], 42);
        assert_eq!(json["status"]["state"], "playing");
        assert_eq!(json["status"]["position_seconds"], 3.5);
    }

    #[test]
    fn protocol_mask_is_stable() {
        assert_eq!(
            protocols_from_mask(1 | 4),
            vec![Protocol::PlayBridge, Protocol::Roku]
        );
    }

    #[test]
    fn event_json_is_tagged_and_contains_protocol() {
        let json = serde_json::to_string(&ReceiverEvent::Started {
            protocol: Protocol::Dlna,
        })
        .unwrap();
        assert_eq!(json, r#"{"event":"started","protocol":"Dlna"}"#);
    }

    #[test]
    fn receiver_json_keeps_plain_and_secure_ports_distinct() {
        let event = ReceiverEvent::Found {
            receiver: ReceiverInfo {
                id: "playbridge:test".into(),
                protocol: Protocol::PlayBridge,
                name: "Test receiver".into(),
                addresses: vec!["192.0.2.1".into()],
                port: Some(8765),
                wss_port: Some(8766),
                location: None,
                uuid: Some("test".into()),
            },
        };
        let json = serde_json::to_value(event).unwrap();
        assert_eq!(json["receiver"]["port"], 8765);
        assert_eq!(json["receiver"]["wss_port"], 8766);
    }

    #[test]
    fn empty_scanner_can_be_cancelled() {
        let scanner = DiscoveryScanner::new(Vec::new(), 30_000).unwrap();
        scanner.cancel();
        assert!(scanner.next_event(200).is_none());
    }

    #[test]
    fn full_event_queue_drops_events_without_blocking_the_worker() {
        let (sender, _receiver) = mpsc::sync_channel(1);
        let event = ReceiverEvent::Started {
            protocol: Protocol::Dlna,
        };
        assert!(forward_event(&sender, event.clone()));
        assert!(forward_event(&sender, event));
    }

    #[test]
    fn disconnected_event_queue_stops_the_worker() {
        let (sender, receiver) = mpsc::sync_channel(1);
        drop(receiver);
        assert!(!forward_event(
            &sender,
            ReceiverEvent::Started {
                protocol: Protocol::Dlna,
            }
        ));
    }
}
