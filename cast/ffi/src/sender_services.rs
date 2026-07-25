use std::{
    collections::HashMap,
    ffi::{CStr, CString, c_char},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver as MessageReceiver, SyncSender, TrySendError},
    },
    thread,
    time::Duration,
};

use playbridge_browser_receiver::{
    BrowserReceiverConfig, BrowserReceiverEvent, BrowserReceiverHost, BrowserReceiverService,
};
use playbridge_cast_core::browser::{BrowserCommand, BrowserMedia};
use serde::Deserialize;
use serde_json::{Value, json};
use stream_proxy_rust::{ProxyServer, ProxyServerConfig};
use tokio::sync::mpsc as tokio_mpsc;

const SENDER_SERVICES_ABI_VERSION: u32 = 1;
const COMMAND_CAPACITY: usize = 32;
const EVENT_CAPACITY: usize = 128;

#[derive(Debug, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum ServicesCommand {
    ProxyRegisterUrl {
        request_id: Value,
        host: String,
        url: String,
        #[serde(default)]
        headers: HashMap<String, String>,
    },
    ProxyRegisterFile {
        request_id: Value,
        host: String,
        path: String,
        content_type: Option<String>,
        ttl_ms: Option<u64>,
    },
    ProxyRevoke {
        request_id: Value,
        id: String,
    },
    BrowserStart {
        request_id: Value,
        preferred_port: Option<u16>,
    },
    BrowserStop {
        request_id: Value,
    },
    BrowserApprove {
        request_id: Value,
        session_id: String,
        code: String,
    },
    BrowserLoad {
        request_id: Value,
        session_id: String,
        media: BrowserMedia,
    },
    BrowserControl {
        request_id: Value,
        session_id: String,
        action: BrowserCommand,
        value: Option<f64>,
    },
    BrowserDisconnect {
        request_id: Value,
        session_id: String,
    },
    Shutdown {
        request_id: Value,
    },
}

impl ServicesCommand {
    fn request_id(&self) -> &Value {
        match self {
            Self::ProxyRegisterUrl { request_id, .. }
            | Self::ProxyRegisterFile { request_id, .. }
            | Self::ProxyRevoke { request_id, .. }
            | Self::BrowserStart { request_id, .. }
            | Self::BrowserStop { request_id }
            | Self::BrowserApprove { request_id, .. }
            | Self::BrowserLoad { request_id, .. }
            | Self::BrowserControl { request_id, .. }
            | Self::BrowserDisconnect { request_id, .. }
            | Self::Shutdown { request_id } => request_id,
        }
    }

    fn operation(&self) -> &'static str {
        match self {
            Self::ProxyRegisterUrl { .. } => "proxy_register_url",
            Self::ProxyRegisterFile { .. } => "proxy_register_file",
            Self::ProxyRevoke { .. } => "proxy_revoke",
            Self::BrowserStart { .. } => "browser_start",
            Self::BrowserStop { .. } => "browser_stop",
            Self::BrowserApprove { .. } => "browser_approve",
            Self::BrowserLoad { .. } => "browser_load",
            Self::BrowserControl { .. } => "browser_control",
            Self::BrowserDisconnect { .. } => "browser_disconnect",
            Self::Shutdown { .. } => "shutdown",
        }
    }
}

pub struct SenderServices {
    commands: tokio_mpsc::Sender<ServicesCommand>,
    events: Mutex<MessageReceiver<Value>>,
    cancelled: Arc<AtomicBool>,
}

impl SenderServices {
    fn start() -> Result<Arc<Self>, String> {
        let (commands_tx, commands_rx) = tokio_mpsc::channel(COMMAND_CAPACITY);
        let (events_tx, events_rx) = mpsc::sync_channel(EVENT_CAPACITY);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        thread::Builder::new()
            .name("playbridge-sender-services".into())
            .spawn(move || {
                let runtime = tokio::runtime::Runtime::new();
                match runtime {
                    Ok(runtime) => {
                        runtime.block_on(worker(commands_rx, events_tx, worker_cancelled))
                    }
                    Err(error) => {
                        let _ = events_tx.try_send(json!({
                            "event": "error",
                            "operation": "start",
                            "message": format!("failed to start sender services runtime: {error}")
                        }));
                    }
                }
            })
            .map_err(|error| format!("failed to start sender services thread: {error}"))?;
        Ok(Arc::new(Self {
            commands: commands_tx,
            events: Mutex::new(events_rx),
            cancelled,
        }))
    }

    fn submit(&self, command: ServicesCommand) -> bool {
        if self.cancelled.load(Ordering::Acquire) {
            return false;
        }
        self.commands.try_send(command).is_ok()
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
            let _ = self.commands.try_send(ServicesCommand::Shutdown {
                request_id: Value::Null,
            });
        }
    }
}

impl Drop for SenderServices {
    fn drop(&mut self) {
        self.cancel();
    }
}

async fn worker(
    mut commands: tokio_mpsc::Receiver<ServicesCommand>,
    events: SyncSender<Value>,
    cancelled: Arc<AtomicBool>,
) {
    let proxy = match ProxyServer::start(ProxyServerConfig::default()).await {
        Ok(proxy) => proxy,
        Err(error) => {
            send_event(
                &events,
                json!({"event":"error","operation":"start","message":error}),
            );
            return;
        }
    };
    send_event(
        &events,
        json!({
            "event": "started",
            "proxyPort": proxy.local_addr().port(),
        }),
    );
    let mut browser_host: Option<BrowserReceiverHost> = None;
    let mut browser_service: Option<BrowserReceiverService> = None;
    let mut browser_events: Option<tokio::sync::broadcast::Receiver<BrowserReceiverEvent>> = None;

    while !cancelled.load(Ordering::Acquire) {
        if let Some(receiver) = browser_events.as_mut() {
            tokio::select! {
                command = commands.recv() => {
                    let Some(command) = command else { break };
                    if process_command(
                        command,
                        &proxy,
                        &mut browser_host,
                        &mut browser_service,
                        &mut browser_events,
                        &events,
                    ).await {
                        break;
                    }
                }
                event = receiver.recv() => {
                    if let Ok(event) = event {
                        send_browser_event(&events, event);
                    }
                }
            }
        } else {
            let Some(command) = commands.recv().await else {
                break;
            };
            if process_command(
                command,
                &proxy,
                &mut browser_host,
                &mut browser_service,
                &mut browser_events,
                &events,
            )
            .await
            {
                break;
            }
        }
    }

    if let Some(host) = browser_host {
        let _ = host.shutdown().await;
    }
    let _ = proxy.shutdown().await;
    send_event(&events, json!({"event":"finished"}));
}

async fn process_command(
    command: ServicesCommand,
    proxy: &ProxyServer,
    browser_host: &mut Option<BrowserReceiverHost>,
    browser_service: &mut Option<BrowserReceiverService>,
    browser_events: &mut Option<tokio::sync::broadcast::Receiver<BrowserReceiverEvent>>,
    events: &SyncSender<Value>,
) -> bool {
    let operation = command.operation();
    let request_id = command.request_id().clone();
    let result: Result<Value, String> = match command {
        ServicesCommand::ProxyRegisterUrl {
            host, url, headers, ..
        } => proxy
            .register_remote(&host, url, headers)
            .and_then(|media| serde_json::to_value(media).map_err(|error| error.to_string())),
        ServicesCommand::ProxyRegisterFile {
            host,
            path,
            content_type,
            ttl_ms,
            ..
        } => proxy
            .register_file(
                &host,
                path,
                content_type,
                Duration::from_millis(ttl_ms.unwrap_or(6 * 60 * 60 * 1_000)),
            )
            .and_then(|media| serde_json::to_value(media).map_err(|error| error.to_string())),
        ServicesCommand::ProxyRevoke { id, .. } => {
            Ok(json!({"revoked": proxy.service().revoke(&id)}))
        }
        ServicesCommand::BrowserStart { preferred_port, .. } => {
            if browser_host.is_none() {
                match BrowserReceiverHost::start(BrowserReceiverConfig {
                    preferred_port: preferred_port.unwrap_or(8770),
                    ..Default::default()
                })
                .await
                {
                    Ok(host) => {
                        let service = host.service();
                        *browser_events = Some(service.subscribe());
                        let urls = host.urls();
                        let port = host.local_addr().port();
                        *browser_service = Some(service);
                        *browser_host = Some(host);
                        Ok(json!({"urls": urls, "port": port}))
                    }
                    Err(error) => Err(error),
                }
            } else {
                let host = browser_host.as_ref().expect("checked browser host");
                Ok(json!({"urls": host.urls(), "port": host.local_addr().port()}))
            }
        }
        ServicesCommand::BrowserStop { .. } => {
            *browser_events = None;
            *browser_service = None;
            if let Some(host) = browser_host.take() {
                match host.shutdown().await {
                    Ok(()) => Ok(Value::Null),
                    Err(error) => Err(error),
                }
            } else {
                Ok(Value::Null)
            }
        }
        ServicesCommand::BrowserApprove {
            session_id, code, ..
        } => match browser_service {
            Some(service) => service
                .approve(&session_id, &code)
                .await
                .map(|_| Value::Null),
            None => Err("browser receiver host is not running".into()),
        },
        ServicesCommand::BrowserLoad {
            session_id, media, ..
        } => match browser_service {
            Some(service) => service
                .load(&session_id, media)
                .await
                .map(|browser_request_id| json!({"browserRequestId": browser_request_id})),
            None => Err("browser receiver host is not running".into()),
        },
        ServicesCommand::BrowserControl {
            session_id,
            action,
            value,
            ..
        } => match browser_service {
            Some(service) => service
                .command(&session_id, action, value)
                .map(|browser_request_id| json!({"browserRequestId": browser_request_id})),
            None => Err("browser receiver host is not running".into()),
        },
        ServicesCommand::BrowserDisconnect { session_id, .. } => match browser_service {
            Some(service) => Ok(json!({"disconnected": service.disconnect(&session_id)})),
            None => Err("browser receiver host is not running".into()),
        },
        ServicesCommand::Shutdown { .. } => return true,
    };
    match result {
        Ok(data) => send_event(
            events,
            json!({
                "event": "operation",
                "requestId": request_id,
                "operation": operation,
                "data": data,
            }),
        ),
        Err(message) => send_event(
            events,
            json!({
                "event": "error",
                "requestId": request_id,
                "operation": operation,
                "message": message,
            }),
        ),
    }
    false
}

fn send_browser_event(events: &SyncSender<Value>, event: BrowserReceiverEvent) {
    if let Ok(value) = serde_json::to_value(event) {
        send_event(events, value);
    }
}

fn send_event(events: &SyncSender<Value>, event: Value) {
    match events.try_send(event) {
        Ok(()) | Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {}
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn pb_sender_services_abi_version() -> u32 {
    SENDER_SERVICES_ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn pb_sender_services_start() -> *mut SenderServices {
    SenderServices::start()
        .map(|services| Arc::into_raw(services) as *mut SenderServices)
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
///
/// `services` must be a live handle and `command_json` a valid NUL-terminated
/// UTF-8 string for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_sender_services_submit_json(
    services: *const SenderServices,
    command_json: *const c_char,
) -> bool {
    let Some(services) = (unsafe { services.as_ref() }) else {
        return false;
    };
    if command_json.is_null() {
        return false;
    }
    let Ok(command_json) = (unsafe { CStr::from_ptr(command_json) }).to_str() else {
        return false;
    };
    let Ok(command) = serde_json::from_str::<ServicesCommand>(command_json) else {
        return false;
    };
    services.submit(command)
}

/// # Safety
///
/// `services` must be null or a live sender-services handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_sender_services_next_json(
    services: *const SenderServices,
    wait_ms: u64,
) -> *mut c_char {
    let Some(services) = (unsafe { services.as_ref() }) else {
        return std::ptr::null_mut();
    };
    services
        .next_event(wait_ms)
        .and_then(|event| serde_json::to_string(&event).ok())
        .and_then(|json| CString::new(json).ok())
        .map(CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
///
/// `services` must be null or a live sender-services handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_sender_services_cancel(services: *const SenderServices) {
    if let Some(services) = unsafe { services.as_ref() } {
        services.cancel();
    }
}

/// # Safety
///
/// `services` must be null or a live handle released at most once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_sender_services_free(services: *const SenderServices) {
    if !services.is_null() {
        drop(unsafe { Arc::from_raw(services) });
    }
}

#[cfg(test)]
mod tests {
    use super::{SENDER_SERVICES_ABI_VERSION, ServicesCommand, pb_sender_services_abi_version};

    #[test]
    fn sender_services_abi_is_stable() {
        assert_eq!(
            pb_sender_services_abi_version(),
            SENDER_SERVICES_ABI_VERSION
        );
        assert_eq!(SENDER_SERVICES_ABI_VERSION, 1);
    }

    #[test]
    fn browser_and_proxy_commands_use_the_documented_wire_names() {
        let proxy: ServicesCommand = serde_json::from_str(
            r#"{"command":"proxy_register_file","request_id":"1","host":"192.0.2.1","path":"/tmp/video.mp4"}"#,
        )
        .unwrap();
        assert_eq!(proxy.operation(), "proxy_register_file");

        let browser: ServicesCommand = serde_json::from_str(
            r#"{"command":"browser_control","request_id":"2","session_id":"session","action":"set_volume","value":0.5}"#,
        )
        .unwrap();
        assert_eq!(browser.operation(), "browser_control");
    }
}
