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

use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use playbridge_cast_receiver::{
    PrivateKeyKind, ReceiverConfig, ReceiverEvent, ReceiverHost, ReceiverIdentity,
};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::mpsc as tokio_mpsc;

const RECEIVER_RUNTIME_ABI_VERSION: u32 = 1;
const COMMAND_CAPACITY: usize = 64;
const EVENT_CAPACITY: usize = 256;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeConfig {
    preferred_port: Option<u16>,
    fallback_attempts: Option<u16>,
    name: String,
    uuid: String,
    certificate_der: String,
    private_key_der: String,
    private_key_kind: Option<String>,
    #[serde(default)]
    authorized_tokens: Vec<String>,
    #[serde(default)]
    players: Vec<String>,
    #[serde(default)]
    browsers: Vec<String>,
    #[serde(default)]
    media_kinds: Vec<String>,
    #[serde(default)]
    screen_mirror_web_rtc: bool,
    #[serde(default)]
    advertise: bool,
}

impl RuntimeConfig {
    fn into_receiver_config(self) -> Result<ReceiverConfig, String> {
        let identity = ReceiverIdentity {
            certificate_der: BASE64
                .decode(self.certificate_der)
                .map_err(|error| format!("invalid receiver certificate: {error}"))?,
            private_key_der: BASE64
                .decode(self.private_key_der)
                .map_err(|error| format!("invalid receiver private key: {error}"))?,
            private_key_kind: match self.private_key_kind.as_deref() {
                Some("pkcs1") => PrivateKeyKind::Pkcs1,
                Some("pkcs8") | None => PrivateKeyKind::Pkcs8,
                Some(value) => return Err(format!("unsupported private key kind: {value}")),
            },
        };
        let mut config = ReceiverConfig::new(self.name, self.uuid, identity);
        config.preferred_port = self.preferred_port.unwrap_or(8765);
        config.fallback_attempts = self.fallback_attempts.unwrap_or(32);
        config.authorized_tokens = self.authorized_tokens;
        config.players = self.players;
        config.browsers = self.browsers;
        config.media_kinds = self.media_kinds;
        config.screen_mirror_web_rtc = self.screen_mirror_web_rtc;
        config.advertise = self.advertise;
        Ok(config)
    }
}

#[derive(Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum RuntimeCommand {
    Broadcast { message: Value },
    SendTo { connection_id: u64, message: Value },
    DenyPairing { connection_id: u64 },
    ReplaceAuthorizedTokens { tokens: Vec<String> },
    DisconnectAll,
    Shutdown,
}

pub struct ReceiverRuntime {
    commands: tokio_mpsc::Sender<RuntimeCommand>,
    events: Mutex<MessageReceiver<Value>>,
    cancelled: Arc<AtomicBool>,
}

impl ReceiverRuntime {
    fn start(config: ReceiverConfig) -> Result<Arc<Self>, String> {
        let (commands_tx, commands_rx) = tokio_mpsc::channel(COMMAND_CAPACITY);
        let (events_tx, events_rx) = mpsc::sync_channel(EVENT_CAPACITY);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        thread::Builder::new()
            .name("playbridge-receiver-runtime".into())
            .spawn(move || match tokio::runtime::Runtime::new() {
                Ok(runtime) => {
                    runtime.block_on(worker(config, commands_rx, events_tx, worker_cancelled))
                }
                Err(error) => send_event(
                    &events_tx,
                    json!({"event":"error","message":format!("failed to start receiver runtime: {error}")}),
                ),
            })
            .map_err(|error| format!("failed to start receiver thread: {error}"))?;
        Ok(Arc::new(Self {
            commands: commands_tx,
            events: Mutex::new(events_rx),
            cancelled,
        }))
    }

    fn submit(&self, command: RuntimeCommand) -> bool {
        !self.cancelled.load(Ordering::Acquire) && self.commands.try_send(command).is_ok()
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
        if !self.cancelled.swap(true, Ordering::AcqRel) {
            let _ = self.commands.try_send(RuntimeCommand::Shutdown);
        }
    }
}

impl Drop for ReceiverRuntime {
    fn drop(&mut self) {
        self.cancel();
    }
}

async fn worker(
    config: ReceiverConfig,
    mut commands: tokio_mpsc::Receiver<RuntimeCommand>,
    events: SyncSender<Value>,
    cancelled: Arc<AtomicBool>,
) {
    let host = match ReceiverHost::start(config).await {
        Ok(host) => host,
        Err(message) => {
            send_event(&events, json!({"event":"error","message":message}));
            return;
        }
    };
    let mut receiver_events = host.subscribe();
    send_event(
        &events,
        json!({
            "event":"started",
            "port":host.port(),
            "fingerprint":host.fingerprint(),
        }),
    );

    while !cancelled.load(Ordering::Acquire) {
        tokio::select! {
            command = commands.recv() => {
                match command {
                    Some(RuntimeCommand::Broadcast { message }) => host.broadcast(message),
                    Some(RuntimeCommand::SendTo { connection_id, message }) => {
                        let _ = host.send_to(connection_id, message);
                    }
                    Some(RuntimeCommand::DenyPairing { connection_id }) => {
                        host.deny_pairing(connection_id);
                    }
                    Some(RuntimeCommand::ReplaceAuthorizedTokens { tokens }) => {
                        host.replace_authorized_tokens(tokens);
                    }
                    Some(RuntimeCommand::DisconnectAll) => host.disconnect_all(),
                    Some(RuntimeCommand::Shutdown) | None => break,
                }
            }
            event = receiver_events.recv() => {
                match event {
                    Ok(ReceiverEvent::Started { .. }) => {}
                    Ok(event) => {
                        let critical = matches!(event, ReceiverEvent::Paired { .. });
                        if let Ok(value) = serde_json::to_value(event) {
                            if critical {
                                let _ = events.send(value);
                            } else {
                                send_event(&events, value);
                            }
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                        send_event(
                            &events,
                            json!({"event":"error","message":format!("receiver event consumer lagged by {skipped} events")}),
                        );
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        }
    }
    host.shutdown().await;
}

fn send_event(events: &SyncSender<Value>, event: Value) {
    match events.try_send(event) {
        Ok(()) | Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {}
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn pb_receiver_runtime_abi_version() -> u32 {
    RECEIVER_RUNTIME_ABI_VERSION
}

/// # Safety
///
/// `config_json` must point to a valid NUL-terminated UTF-8 JSON string for the
/// duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_receiver_runtime_start(
    config_json: *const c_char,
) -> *mut ReceiverRuntime {
    if config_json.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(text) = (unsafe { CStr::from_ptr(config_json) }).to_str() else {
        return std::ptr::null_mut();
    };
    let Ok(config) = serde_json::from_str::<RuntimeConfig>(text)
        .map_err(|error| error.to_string())
        .and_then(RuntimeConfig::into_receiver_config)
    else {
        return std::ptr::null_mut();
    };
    ReceiverRuntime::start(config)
        .map(|runtime| Arc::into_raw(runtime) as *mut ReceiverRuntime)
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
///
/// `runtime` must be a live runtime handle and `command_json` a valid
/// NUL-terminated UTF-8 JSON string for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_receiver_runtime_submit_json(
    runtime: *const ReceiverRuntime,
    command_json: *const c_char,
) -> bool {
    let Some(runtime) = (unsafe { runtime.as_ref() }) else {
        return false;
    };
    if command_json.is_null() {
        return false;
    }
    let Ok(text) = (unsafe { CStr::from_ptr(command_json) }).to_str() else {
        return false;
    };
    serde_json::from_str::<RuntimeCommand>(text).is_ok_and(|command| runtime.submit(command))
}

/// # Safety
///
/// `runtime` must be a live runtime handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_receiver_runtime_next_json(
    runtime: *const ReceiverRuntime,
    wait_ms: u64,
) -> *mut c_char {
    let Some(runtime) = (unsafe { runtime.as_ref() }) else {
        return std::ptr::null_mut();
    };
    runtime
        .next_event(wait_ms)
        .and_then(|event| serde_json::to_string(&event).ok())
        .and_then(|event| CString::new(event).ok())
        .map(CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
///
/// `runtime` must be a live runtime handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_receiver_runtime_cancel(runtime: *const ReceiverRuntime) {
    if let Some(runtime) = unsafe { runtime.as_ref() } {
        runtime.cancel();
    }
}

/// # Safety
///
/// `runtime` must come from `pb_receiver_runtime_start` and be freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_receiver_runtime_free(runtime: *const ReceiverRuntime) {
    if !runtime.is_null() {
        drop(unsafe { Arc::from_raw(runtime) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn receiver_runtime_abi_is_stable() {
        assert_eq!(pb_receiver_runtime_abi_version(), 1);
    }

    #[test]
    fn receiver_runtime_forwards_host_capabilities() {
        let runtime = serde_json::from_value::<RuntimeConfig>(json!({
            "name":"Desktop",
            "uuid":"desktop-id",
            "certificateDer":"",
            "privateKeyDer":"",
            "mediaKinds":["video","audio","image"],
            "screenMirrorWebRtc":true
        }))
        .unwrap();
        let config = runtime.into_receiver_config().unwrap();
        assert_eq!(config.media_kinds, ["video", "audio", "image"]);
        assert!(config.screen_mirror_web_rtc);
    }

    #[test]
    fn receiver_runtime_commands_use_documented_names() {
        assert!(matches!(
            serde_json::from_value::<RuntimeCommand>(json!({
                "command":"replace_authorized_tokens",
                "tokens":["sha256:test"]
            }))
            .unwrap(),
            RuntimeCommand::ReplaceAuthorizedTokens { tokens } if tokens == ["sha256:test"]
        ));
        assert!(matches!(
            serde_json::from_value::<RuntimeCommand>(json!({
                "command":"send_to",
                "connection_id":7,
                "message":{"type":"context","active":"idle"}
            }))
            .unwrap(),
            RuntimeCommand::SendTo {
                connection_id: 7,
                ..
            }
        ));
    }
}
