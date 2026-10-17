//! Independent Android TV Remote v2 C/JNI ABI. Credentials belong to the caller.
use std::{
    ffi::{CStr, CString, c_char},
    future::pending,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender, TrySendError},
    },
    thread,
    time::Duration,
};

use base64::{Engine, engine::general_purpose::STANDARD};
use playbridge_cast_core::atv_remote::{
    self as core, AtvError, AtvState, ClientIdentity, ControlSession, PairingResult,
    PairingSession, fingerprint_hex,
};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::{
    sync::{mpsc as async_mpsc, watch},
    task::{JoinHandle, JoinSet},
};

const COMMAND_CAPACITY: usize = 32;
const EVENT_CAPACITY: usize = 64;
const MAX_COMMAND_BYTES: usize = 1024 * 1024;

// Deliberately no Debug: commands contain caller credentials and pairing codes.
#[derive(Deserialize)]
struct Request {
    request_id: Value,
    #[serde(flatten)]
    command: Command,
}

#[derive(Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum Command {
    Discover {
        timeout_ms: u64,
    },
    GenerateIdentity {
        client_name: String,
        cert_pem: Option<String>,
        key_pem: Option<String>,
    },
    StartPairing {
        host: String,
        port: Option<u16>,
        cert_pem: String,
        key_pem: String,
        client_name: String,
    },
    FinishPairing {
        code: String,
    },
    CancelPairing,
    Connect {
        host: String,
        port: Option<u16>,
        cert_pem: String,
        key_pem: String,
        pin_sha256_hex: String,
    },
    Key {
        code: Option<u32>,
        name: Option<String>,
        #[serde(default)]
        direction: Direction,
    },
    Volume {
        action: VolumeAction,
    },
    Text {
        text: String,
    },
    LaunchApp {
        uri_or_package: String,
    },
    Power,
    Disconnect,
    #[serde(skip)]
    Invalid,
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Direction {
    #[default]
    Short,
    StartLong,
    EndLong,
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum VolumeAction {
    Up,
    Down,
    Mute,
}

impl Command {
    fn operation(&self) -> &'static str {
        match self {
            Self::Discover { .. } => "discover",
            Self::GenerateIdentity { .. } => "generate_identity",
            Self::StartPairing { .. } => "start_pairing",
            Self::FinishPairing { .. } => "finish_pairing",
            Self::CancelPairing => "cancel_pairing",
            Self::Connect { .. } => "connect",
            Self::Key { .. } => "key",
            Self::Volume { .. } => "volume",
            Self::Text { .. } => "text",
            Self::LaunchApp { .. } => "launch_app",
            Self::Power => "power",
            Self::Disconnect => "disconnect",
            Self::Invalid => "submit",
        }
    }
}

fn valid_id(id: &Value) -> bool {
    match id {
        Value::String(id) => !id.is_empty() && id.len() <= 128,
        Value::Number(_) | Value::Bool(_) => true,
        _ => false,
    }
}

pub struct AtvRemote {
    commands: async_mpsc::Sender<Request>,
    events: Mutex<Receiver<Value>>,
    cancelled: Arc<AtomicBool>,
    cancel_signal: watch::Sender<bool>,
}

impl AtvRemote {
    fn start() -> Option<Arc<Self>> {
        let (commands, inbound) = async_mpsc::channel(COMMAND_CAPACITY);
        let (events, outbound) = mpsc::sync_channel(EVENT_CAPACITY);
        let (cancel_signal, cancel_rx) = watch::channel(false);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        thread::Builder::new()
            .name("playbridge-atv-remote".into())
            .spawn(move || {
                let Ok(runtime) = tokio::runtime::Runtime::new() else {
                    let _ = events.try_send(error_event(Value::Null, &AtvError::Closed));
                    worker_cancelled.store(true, Ordering::Release);
                    return;
                };
                runtime.block_on(worker(inbound, events, worker_cancelled.clone(), cancel_rx));
                worker_cancelled.store(true, Ordering::Release);
                // RSA generation may still be running in spawn_blocking. Teardown
                // never joins it or waits for any socket/TLS operation.
                runtime.shutdown_background();
            })
            .ok()?;
        Some(Arc::new(Self {
            commands,
            events: Mutex::new(outbound),
            cancelled,
            cancel_signal,
        }))
    }

    fn submit(&self, json: &str) -> bool {
        if self.cancelled.load(Ordering::Acquire) {
            return false;
        }
        let parsed = (json.len() <= MAX_COMMAND_BYTES)
            .then(|| serde_json::from_str::<Request>(json).ok())
            .flatten()
            .filter(|request| valid_id(&request.request_id));
        match parsed {
            Some(request) => self.commands.try_send(request).is_ok(),
            None => {
                // Never expose serde's error: it can quote a supplied code or key.
                let request_id = if json.len() <= MAX_COMMAND_BYTES {
                    serde_json::from_str::<Value>(json)
                        .ok()
                        .and_then(|value| value.get("request_id").cloned())
                        .filter(valid_id)
                        .unwrap_or(Value::Null)
                } else {
                    Value::Null
                };
                let _ = self.commands.try_send(Request {
                    request_id,
                    command: Command::Invalid,
                });
                false
            }
        }
    }

    fn next_event(&self, wait_ms: u64) -> Option<Value> {
        let events = self.events.lock().ok()?;
        if wait_ms == 0 {
            events.try_recv().ok()
        } else {
            events.recv_timeout(Duration::from_millis(wait_ms)).ok()
        }
    }

    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        let _ = self.cancel_signal.send(true);
    }
}

impl Drop for AtvRemote {
    fn drop(&mut self) {
        self.cancel();
    }
}

struct Pending<T>(JoinHandle<(Value, Result<T, AtvError>)>);
impl<T> Drop for Pending<T> {
    fn drop(&mut self) {
        self.0.abort();
    }
}

enum PairingOutcome {
    Started(Box<PairingSession>),
    Finished(PairingResult),
}

#[derive(Default)]
struct Sessions {
    pairing: Option<PairingSession>,
    pairing_pending: Option<Pending<PairingOutcome>>,
    connecting: Option<Pending<ControlSession>>,
    control: Option<ControlSession>,
    state: Option<watch::Receiver<AtvState>>,
    control_id: Value,
    last_state: Option<Value>,
}

async fn completed<T>(task: &mut Option<Pending<T>>) -> (Value, Result<T, AtvError>) {
    match task {
        Some(task) => (&mut task.0)
            .await
            .unwrap_or((Value::Null, Err(AtvError::Closed))),
        None => pending().await,
    }
}

async fn state_changed(state: &mut Option<watch::Receiver<AtvState>>) {
    match state {
        Some(state) => {
            if state.changed().await.is_err() {
                pending::<()>().await;
            }
        }
        None => pending().await,
    }
}

async fn control_closed(control: &mut Option<ControlSession>) -> AtvError {
    match control {
        Some(control) => control.until_closed().await,
        None => pending().await,
    }
}

async fn worker(
    mut commands: async_mpsc::Receiver<Request>,
    events: SyncSender<Value>,
    cancelled: Arc<AtomicBool>,
    mut cancel_rx: watch::Receiver<bool>,
) {
    let mut sessions = Sessions::default();
    let mut jobs = JoinSet::new();
    loop {
        if cancelled.load(Ordering::Acquire) {
            break;
        }
        tokio::select! {
            biased;
            _ = cancel_rx.changed() => break,
            command = commands.recv() => {
                let Some(request) = command else { break; };
                // Key writes and event backpressure are also interruptible.
                tokio::select! {
                    biased;
                    _ = cancel_rx.changed() => break,
                    _ = handle_request(request, &mut sessions, &mut jobs, &events, &cancelled) => {}
                }
            }
            (id, result) = completed(&mut sessions.pairing_pending) => {
                sessions.pairing_pending = None;
                let event = match result {
                    Ok(PairingOutcome::Started(pairing)) => {
                        let event = json!({"event":"pairing_started", "request_id":id,
                            "server_name":pairing.server_name(), "fingerprint":fingerprint_hex(&pairing.fingerprint_sha256()), "mac":pairing.mac()});
                        sessions.pairing = Some(*pairing);
                        event
                    }
                    Ok(PairingOutcome::Finished(result)) => json!({"event":"paired", "request_id":id,
                        "server_cert_der_b64":STANDARD.encode(&result.server_certificate_der),
                        "fingerprint":fingerprint_hex(&result.fingerprint_sha256), "mac":result.mac, "server_name":result.server_name}),
                    Err(error) => error_event(id, &error),
                };
                send_event(&events, event, &cancelled).await;
            }
            (id, result) = completed(&mut sessions.connecting) => {
                sessions.connecting = None;
                match result {
                    Ok(control) => {
                        sessions.control_id = id.clone();
                        sessions.state = Some(control.watch());
                        sessions.control = Some(control);
                        send_event(&events, json!({"event":"connected", "request_id":id}), &cancelled).await;
                        publish_state(&mut sessions, &events, &cancelled).await;
                    }
                    Err(error) => { send_event(&events, error_event(id, &error), &cancelled).await; }
                }
            }
            _ = state_changed(&mut sessions.state) => { publish_state(&mut sessions, &events, &cancelled).await; }
            error = control_closed(&mut sessions.control) => {
                // Flush the last visible snapshot before the terminal event.
                publish_state(&mut sessions, &events, &cancelled).await;
                close_control(&mut sessions, &events, &cancelled, &error).await;
            }
            Some(result) = jobs.join_next(), if !jobs.is_empty() => {
                let event = result.unwrap_or_else(|_| error_event(Value::Null, &AtvError::Closed));
                send_event(&events, event, &cancelled).await;
            }
        }
    }
    if sessions.control.is_some() || sessions.connecting.is_some() {
        // Terminal cancellation is best effort if the bounded queue is full.
        let _ = events
            .try_send(json!({"event":"closed", "request_id":sessions.control_id, "kind":"closed"}));
    }
    // Drop aborts pending handshakes, the core control reader, and jobs.
}

async fn send_event(events: &SyncSender<Value>, mut event: Value, cancelled: &AtomicBool) {
    // Same capacity/backpressure policy as pb_session_*, with async sleep.
    loop {
        if cancelled.load(Ordering::Acquire) {
            return;
        }
        match events.try_send(event) {
            Ok(()) | Err(TrySendError::Disconnected(_)) => return,
            Err(TrySendError::Full(returned)) => {
                event = returned;
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        }
    }
}

fn public_state(state: &AtvState) -> Value {
    // Keep IME contents, labels and counters entirely behind the core boundary.
    json!({"is_on":state.is_on, "volume_level":state.volume_level, "volume_max":state.volume_max,
        "volume_muted":state.volume_muted, "volume_revision":state.volume_revision,
        "current_app":state.current_app, "model":state.model, "vendor":state.vendor})
}

async fn publish_state(
    sessions: &mut Sessions,
    events: &SyncSender<Value>,
    cancelled: &AtomicBool,
) {
    let Some(state) = sessions.state.as_mut() else {
        return;
    };
    let state = public_state(&state.borrow_and_update());
    if sessions.last_state.as_ref() != Some(&state) {
        send_event(
            events,
            json!({"event":"state", "request_id":sessions.control_id, "state":state}),
            cancelled,
        )
        .await;
        sessions.last_state = Some(state);
    }
}

async fn close_control(
    sessions: &mut Sessions,
    events: &SyncSender<Value>,
    cancelled: &AtomicBool,
    error: &AtvError,
) {
    let was_connected = sessions.control.take().is_some();
    sessions.connecting = None;
    sessions.state = None;
    sessions.last_state = None;
    if was_connected {
        send_event(
            events,
            json!({"event":"closed", "request_id":sessions.control_id, "kind":error_kind(error)}),
            cancelled,
        )
        .await;
    }
}

async fn handle_request(
    request: Request,
    sessions: &mut Sessions,
    jobs: &mut JoinSet<Value>,
    events: &SyncSender<Value>,
    cancelled: &AtomicBool,
) {
    let Request {
        request_id: id,
        command,
    } = request;
    let operation = command.operation();
    let result = match command {
        Command::Discover { timeout_ms } => {
            if jobs.len() >= COMMAND_CAPACITY {
                Err(AtvError::Protocol("too many operations".into()))
            } else {
                jobs.spawn(async move {
                    match core::discover(Duration::from_millis(timeout_ms.min(60_000))).await {
                        Ok(devices) => {
                            json!({"event":"devices", "request_id":id, "devices":devices})
                        }
                        Err(error) => error_event(id, &error),
                    }
                });
                return;
            }
        }
        Command::GenerateIdentity {
            client_name,
            cert_pem,
            key_pem,
        } => {
            if jobs.len() >= COMMAND_CAPACITY {
                Err(AtvError::Protocol("too many operations".into()))
            } else {
                jobs.spawn(async move {
                    match tokio::task::spawn_blocking(move || {
                        let existing = match (cert_pem, key_pem) {
                            (Some(cert), Some(key)) => Some(ClientIdentity::from_pem(&cert, &key)?),
                            (None, None) => None,
                            _ => return Err(AtvError::Protocol("identity requires both certificate and key".to_owned())),
                        };
                        let (identity, replaces_legacy) = ClientIdentity::for_pairing(&client_name, existing)?;
                        let name = identity.client_name()?;
                        Ok((identity, name, replaces_legacy))
                    }).await {
                        Ok(Ok((identity, name, replaces_legacy))) => json!({"event":"identity", "request_id":id,
                            "cert_pem":identity.certificate_pem(), "key_pem":identity.private_key_pem(),
                            "client_name":name, "replaces_legacy":replaces_legacy,
                            "fingerprint":fingerprint_hex(&identity.fingerprint_sha256())}),
                        Ok(Err(error)) => error_event(id, &error),
                        Err(_) => error_event(id, &AtvError::Closed),
                    }
                });
                return;
            }
        }
        Command::StartPairing {
            host,
            port,
            cert_pem,
            key_pem,
            client_name,
        } => {
            sessions.pairing = None;
            sessions.pairing_pending = None;
            sessions.pairing_pending = Some(Pending(tokio::spawn(async move {
                let result = async {
                    let identity = ClientIdentity::from_pem(&cert_pem, &key_pem)?;
                    core::start_pairing(
                        &host,
                        port.unwrap_or(core::PAIRING_PORT),
                        &identity,
                        &client_name,
                    )
                    .await
                    .map(|pairing| PairingOutcome::Started(Box::new(pairing)))
                }
                .await;
                (id, result)
            })));
            return;
        }
        Command::FinishPairing { code } => {
            if let Some(pairing) = sessions.pairing.take() {
                sessions.pairing_pending = Some(Pending(tokio::spawn(async move {
                    (
                        id,
                        pairing.finish(&code).await.map(PairingOutcome::Finished),
                    )
                })));
                return;
            }
            Err(AtvError::Protocol("pairing has not started".into()))
        }
        Command::CancelPairing => {
            sessions.pairing = None;
            sessions.pairing_pending = None;
            Ok(())
        }
        Command::Connect {
            host,
            port,
            cert_pem,
            key_pem,
            pin_sha256_hex,
        } => {
            close_control(sessions, events, cancelled, &AtvError::Closed).await;
            sessions.control_id = id.clone();
            sessions.connecting = Some(Pending(tokio::spawn(async move {
                let result = async {
                    let pin = parse_pin(&pin_sha256_hex)
                        .ok_or_else(|| AtvError::Protocol("invalid pin".into()))?;
                    let identity = ClientIdentity::from_pem(&cert_pem, &key_pem)?;
                    ControlSession::connect(
                        &host,
                        port.unwrap_or(core::CONTROL_PORT),
                        &identity,
                        pin,
                    )
                    .await
                }
                .await;
                (id, result)
            })));
            return;
        }
        Command::Disconnect => {
            close_control(sessions, events, cancelled, &AtvError::Closed).await;
            Ok(())
        }
        Command::Invalid => Err(AtvError::Protocol("invalid command".into())),
        command => {
            let Some(control) = sessions.control.as_ref() else {
                send_event(events, error_event(id, &AtvError::Closed), cancelled).await;
                return;
            };
            match command {
                Command::Key {
                    code,
                    name,
                    direction,
                } => {
                    let key = match (code, name) {
                        (Some(code), None) if code <= i32::MAX as u32 => Some(code),
                        (None, Some(name)) => {
                            core::key_code_from_name(&name).filter(|code| *code <= i32::MAX as u32)
                        }
                        _ => None,
                    };
                    match key {
                        Some(key) => {
                            control
                                .key(
                                    key,
                                    match direction {
                                        Direction::Short => core::DIR_SHORT,
                                        Direction::StartLong => core::DIR_START_LONG,
                                        Direction::EndLong => core::DIR_END_LONG,
                                    },
                                )
                                .await
                        }
                        None => Err(AtvError::Protocol("invalid key".into())),
                    }
                }
                Command::Volume { action } => match action {
                    VolumeAction::Up => control.volume_up().await.map(|_| ()),
                    VolumeAction::Down => control.volume_down().await.map(|_| ()),
                    VolumeAction::Mute => control.volume_mute().await.map(|_| ()),
                },
                Command::Text { text } => control.insert_text(&text).await,
                Command::LaunchApp { uri_or_package } => control.launch_app(&uri_or_package).await,
                Command::Power => control.power().await,
                _ => unreachable!(),
            }
        }
    };
    let event = match result {
        Ok(()) => json!({"event":"operation", "request_id":id, "operation":operation, "ok":true}),
        Err(error) => error_event(id, &error),
    };
    send_event(events, event, cancelled).await;
}

fn parse_pin(pin: &str) -> Option<[u8; 32]> {
    if pin.len() != 64 || !pin.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    let mut bytes = [0; 32];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&pin[index * 2..index * 2 + 2], 16).ok()?;
    }
    Some(bytes)
}

fn error_kind(error: &AtvError) -> &'static str {
    match error {
        AtvError::Unreachable(_) => "unreachable",
        AtvError::TlsHandshake(_) => "tls_handshake",
        AtvError::NotTrusted => "not_trusted",
        AtvError::PinMismatch => "pin_mismatch",
        AtvError::BadCode => "bad_code",
        AtvError::Protocol(_) => "protocol",
        AtvError::Timeout => "timeout",
        AtvError::Closed => "closed",
    }
}

fn error_event(id: Value, error: &AtvError) -> Value {
    // Do not forward nested errors, hosts, command values or server-provided text.
    let message = match error {
        AtvError::Unreachable(_) => "Android TV remote is unreachable",
        AtvError::TlsHandshake(_) => "Android TV TLS handshake failed",
        AtvError::NotTrusted => "Android TV does not trust this client; pair again",
        AtvError::PinMismatch => "Android TV certificate does not match the stored pin",
        AtvError::BadCode => "pairing code was rejected",
        AtvError::Protocol(_) => "invalid Android TV remote command or protocol message",
        AtvError::Timeout => "Android TV remote timed out",
        AtvError::Closed => "Android TV remote connection closed",
    };
    json!({"event":"error", "request_id":id, "kind":error_kind(error), "message":message})
}

#[unsafe(no_mangle)]
pub extern "C" fn pb_atv_abi_version() -> u32 {
    1
}

#[unsafe(no_mangle)]
pub extern "C" fn pb_atv_start() -> *mut AtvRemote {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        AtvRemote::start()
            .map(|handle| Arc::into_raw(handle) as *mut AtvRemote)
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `handle` must be null or live; `command_json` must be a valid NUL-terminated string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_atv_submit_json(
    handle: *const AtvRemote,
    command_json: *const c_char,
) -> bool {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Some(handle) = (unsafe { handle.as_ref() }) else {
            return false;
        };
        if command_json.is_null() {
            return false;
        }
        let Ok(json) = (unsafe { CStr::from_ptr(command_json) }).to_str() else {
            let _ = handle.submit("");
            return false;
        };
        handle.submit(json)
    }))
    .unwrap_or(false)
}

/// # Safety
/// `handle` must be null or live. Release returned strings with `pb_string_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_atv_next_json(handle: *const AtvRemote, wait_ms: u64) -> *mut c_char {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Some(handle) = (unsafe { handle.as_ref() }) else {
            return std::ptr::null_mut();
        };
        handle
            .next_event(wait_ms)
            .and_then(|event| serde_json::to_string(&event).ok())
            .and_then(|json| CString::new(json).ok())
            .map(CString::into_raw)
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `handle` must be null or live. Cancellation never joins the worker.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_atv_cancel(handle: *const AtvRemote) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(handle) = unsafe { handle.as_ref() } {
            handle.cancel();
        }
    }));
}

/// # Safety
/// `handle` must be null or live and freed at most once. No call may overlap free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_atv_free(handle: *const AtvRemote) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if !handle.is_null() {
            drop(unsafe { Arc::from_raw(handle) });
        }
    }));
}

#[cfg(target_os = "android")]
#[path = "atv_jni.rs"]
mod android_jni;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn explicit_identity_preparation_migrates_legacy_and_reuses_modern_without_network() {
        let legacy = ClientIdentity::generate("PlayBridge").unwrap();
        let handle = AtvRemote::start().unwrap();
        assert!(handle.submit(&json!({"command":"generate_identity", "request_id":"first", "client_name":"PlayBridge (iPhone) aaaaaaaa",
            "cert_pem":legacy.certificate_pem(), "key_pem":legacy.private_key_pem()}).to_string()));
        let event = handle.next_event(30_000).unwrap();
        assert!(event["event"] == "identity" && event["replaces_legacy"] == true);
        let prepared = ClientIdentity::from_pem(
            event["cert_pem"].as_str().unwrap(),
            event["key_pem"].as_str().unwrap(),
        )
        .unwrap();
        assert!(!prepared.is_legacy().unwrap());
        assert!(
            event["client_name"]
                .as_str()
                .unwrap()
                .starts_with("PlayBridge (iPhone) ")
        );
        assert!(handle.submit(&json!({"command":"generate_identity", "request_id":"second", "client_name":"PlayBridge (Android) bbbbbbbb",
            "cert_pem":prepared.certificate_pem(), "key_pem":prepared.private_key_pem()}).to_string()));
        let reused = handle.next_event(30_000).unwrap();
        assert!(reused["replaces_legacy"] == false);
        assert!(
            reused["cert_pem"] == event["cert_pem"]
                && reused["client_name"] == event["client_name"]
        );
        handle.cancel();
    }

    #[test]
    fn atv_errors_keep_all_kinds_and_redact_nested_messages() {
        for (error, kind) in [
            (
                AtvError::Unreachable("PRIVATE KEY SECRET-CODE".into()),
                "unreachable",
            ),
            (
                AtvError::TlsHandshake("BEGIN CERTIFICATE SECRET-CODE".into()),
                "tls_handshake",
            ),
            (AtvError::NotTrusted, "not_trusted"),
            (AtvError::PinMismatch, "pin_mismatch"),
            (AtvError::BadCode, "bad_code"),
            (AtvError::Protocol("SECRET-CODE".into()), "protocol"),
            (AtvError::Timeout, "timeout"),
            (AtvError::Closed, "closed"),
        ] {
            let event = error_event(json!(42), &error);
            assert_eq!(event["kind"], kind);
            assert_eq!(event["request_id"], 42);
            assert!(!event.to_string().contains("SECRET-CODE"));
            assert!(!event.to_string().contains("PRIVATE KEY"));
            assert!(!event.to_string().contains("BEGIN CERTIFICATE"));
        }
    }

    #[test]
    fn atv_command_queue_is_bounded_and_cancel_bypasses_it() {
        let (commands, _inbound) = async_mpsc::channel(COMMAND_CAPACITY);
        let (_events, outbound) = mpsc::sync_channel(EVENT_CAPACITY);
        let (cancel_signal, signal) = watch::channel(false);
        let handle = AtvRemote {
            commands,
            events: Mutex::new(outbound),
            cancelled: Arc::new(AtomicBool::new(false)),
            cancel_signal,
        };
        for id in 0..COMMAND_CAPACITY {
            assert!(handle.submit(&json!({"command":"disconnect","request_id":id}).to_string()));
        }
        assert!(!handle.submit(r#"{"command":"disconnect","request_id":99}"#));
        handle.cancel();
        assert!(*signal.borrow());
        assert!(!handle.submit(r#"{"command":"disconnect","request_id":100}"#));
    }

    #[tokio::test]
    async fn atv_event_backpressure_ends_on_cancel() {
        let (events, receiver) = mpsc::sync_channel(EVENT_CAPACITY);
        for id in 0..EVENT_CAPACITY {
            events.try_send(json!({"id":id})).unwrap();
        }
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        let task = tokio::spawn(async move {
            send_event(&events, json!("pending"), &worker_cancelled).await;
        });
        tokio::time::sleep(Duration::from_millis(10)).await;
        assert!(!task.is_finished());
        cancelled.store(true, Ordering::Release);
        tokio::time::timeout(Duration::from_millis(100), task)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(receiver.try_iter().count(), EVENT_CAPACITY);
    }

    #[tokio::test]
    async fn atv_ime_only_updates_do_not_emit_public_state() {
        let (events, receiver) = mpsc::sync_channel(EVENT_CAPACITY);
        let (state_tx, state_rx) = watch::channel(AtvState::default());
        let mut sessions = Sessions {
            state: Some(state_rx),
            control_id: json!("connect"),
            ..Default::default()
        };
        let cancelled = AtomicBool::new(false);
        publish_state(&mut sessions, &events, &cancelled).await;
        assert_eq!(receiver.try_iter().count(), 1);
        state_tx
            .send(AtvState {
                ime_text: Some("SECRET-TEXT".into()),
                ime_label: Some("SECRET-LABEL".into()),
                ime_counter: 9,
                field_counter: 4,
                ..Default::default()
            })
            .unwrap();
        publish_state(&mut sessions, &events, &cancelled).await;
        assert!(receiver.try_recv().is_err());
    }
}
