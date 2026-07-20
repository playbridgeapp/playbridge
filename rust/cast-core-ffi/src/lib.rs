use std::{
    ffi::{CString, c_char},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};

use playbridge_cast_core::discovery::{
    DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver, ReceiverProtocol,
};
use serde::{Deserialize, Serialize};

uniffi::setup_scaffolding!();

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
                        let _ = sender.send(ReceiverEvent::Error {
                            protocol: Protocol::PlayBridge,
                            message: error.to_string(),
                        });
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
                                    if sender.send(event.into()).is_err() {
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

impl Drop for DiscoveryScanner {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Release);
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
        sys::{jint, jlong},
    };

    use super::{DiscoveryScanner, pb_discovery_cancel, pb_discovery_free, pb_discovery_start};

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_connection_RustDiscoveryNative_start<
        'local,
    >(
        _env: EnvUnowned<'local>,
        _class: JClass<'local>,
        protocol_mask: jint,
        timeout_ms: jlong,
    ) -> jlong {
        pb_discovery_start(protocol_mask as u32, timeout_ms.max(0) as u64) as jlong
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
        let Some(scanner) = (unsafe { (handle as *const DiscoveryScanner).as_ref() }) else {
            return JString::default();
        };
        let json = scanner
            .next_event(wait_ms.max(0) as u64)
            .and_then(|event| serde_json::to_string(&event).ok());
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
        unsafe { pb_discovery_cancel(handle as *const DiscoveryScanner) };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_playbridge_sender_connection_RustDiscoveryNative_free(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        unsafe { pb_discovery_free(handle as *const DiscoveryScanner) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
