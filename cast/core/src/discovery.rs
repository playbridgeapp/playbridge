use std::{collections::HashSet, fmt, str::FromStr, time::Duration};

use reqwest::Client;
use tokio::{sync::mpsc, task::JoinSet};
use tokio_util::sync::CancellationToken;

use crate::{
    dial::DialDevice,
    native_discovery,
    ssdp::{DiscoveryConfig as SsdpConfig, DiscoveryHit, DiscoveryProtocol, DiscoverySession},
    upnp::Renderer,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ReceiverProtocol {
    PlayBridge,
    Dlna,
    Roku,
    Dial,
    GoogleCast,
}

impl ReceiverProtocol {
    pub const DEFAULTS: [Self; 4] = [Self::PlayBridge, Self::Dlna, Self::Roku, Self::GoogleCast];
    pub const ALL: [Self; 5] = [
        Self::PlayBridge,
        Self::Dlna,
        Self::Roku,
        Self::Dial,
        Self::GoogleCast,
    ];

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::PlayBridge => "playbridge",
            Self::Dlna => "dlna",
            Self::Roku => "roku",
            Self::Dial => "dial",
            Self::GoogleCast => "google_cast",
        }
    }
}

impl fmt::Display for ReceiverProtocol {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseReceiverProtocolError(pub String);

impl fmt::Display for ParseReceiverProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "unsupported receiver protocol: {}", self.0)
    }
}

impl std::error::Error for ParseReceiverProtocolError {}

impl FromStr for ReceiverProtocol {
    type Err = ParseReceiverProtocolError;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value.trim().to_ascii_lowercase().as_str() {
            "playbridge" | "native" => Ok(Self::PlayBridge),
            "dlna" => Ok(Self::Dlna),
            "roku" => Ok(Self::Roku),
            "dial" => Ok(Self::Dial),
            "google_cast" | "googlecast" | "chromecast" => Ok(Self::GoogleCast),
            _ => Err(ParseReceiverProtocolError(value.to_owned())),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ReceiverId(pub String);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Receiver {
    pub id: ReceiverId,
    pub protocol: ReceiverProtocol,
    pub name: String,
    pub addresses: Vec<String>,
    pub port: Option<u16>,
    pub wss_port: Option<u16>,
    pub location: Option<String>,
    pub uuid: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DiscoveryEvent {
    Started(ReceiverProtocol),
    Found(Receiver),
    Updated(Receiver),
    Error {
        protocol: ReceiverProtocol,
        message: String,
    },
    Finished(ReceiverProtocol),
}

#[derive(Debug, Clone)]
pub struct DiscoveryConfig {
    pub protocols: HashSet<ReceiverProtocol>,
    pub timeout: Duration,
}

impl Default for DiscoveryConfig {
    fn default() -> Self {
        Self {
            protocols: HashSet::from(ReceiverProtocol::DEFAULTS),
            timeout: Duration::from_secs(3),
        }
    }
}

impl DiscoveryConfig {
    /// Creates a bounded scan for exactly the selected protocols. An empty
    /// selection is valid and completes immediately.
    pub fn selected(
        protocols: impl IntoIterator<Item = ReceiverProtocol>,
        timeout: Duration,
    ) -> Self {
        Self {
            protocols: protocols.into_iter().collect(),
            timeout,
        }
    }

    /// Creates the normal automatic scan: PlayBridge, DLNA, Roku, and Google Cast.
    pub fn automatic(timeout: Duration) -> Self {
        Self::selected(ReceiverProtocol::DEFAULTS, timeout)
    }
}

pub struct DiscoveryStream {
    events: mpsc::Receiver<DiscoveryEvent>,
    cancellation: CancellationToken,
}

impl DiscoveryStream {
    pub fn start(config: DiscoveryConfig) -> Self {
        let (events, receiver) = mpsc::channel(64);
        let cancellation = CancellationToken::new();
        let task_cancellation = cancellation.clone();
        tokio::spawn(async move {
            run_discovery(config, events, task_cancellation).await;
        });
        Self {
            events: receiver,
            cancellation,
        }
    }

    pub async fn next(&mut self) -> Option<DiscoveryEvent> {
        self.events.recv().await
    }

    pub fn cancel(&self) {
        self.cancellation.cancel();
    }
}

impl Drop for DiscoveryStream {
    fn drop(&mut self) {
        self.cancellation.cancel();
    }
}

async fn run_discovery(
    config: DiscoveryConfig,
    events: mpsc::Sender<DiscoveryEvent>,
    cancellation: CancellationToken,
) {
    let mut tasks = JoinSet::new();
    if config.protocols.contains(&ReceiverProtocol::PlayBridge) {
        let events = events.clone();
        let cancellation = cancellation.clone();
        let timeout = config.timeout;
        tasks.spawn(async move { discover_playbridge(timeout, events, cancellation).await });
    }

    if config.protocols.contains(&ReceiverProtocol::GoogleCast) {
        let events = events.clone();
        let cancellation = cancellation.clone();
        let timeout = config.timeout;
        tasks.spawn(async move { discover_google_cast(timeout, events, cancellation).await });
    }

    let wants_dlna = config.protocols.contains(&ReceiverProtocol::Dlna);
    let wants_roku = config.protocols.contains(&ReceiverProtocol::Roku);
    let wants_generic_dial = config.protocols.contains(&ReceiverProtocol::Dial);
    let wants_dial = wants_generic_dial;
    if wants_dlna || wants_roku || wants_dial {
        let events = events.clone();
        let cancellation = cancellation.clone();
        let timeout = config.timeout;
        tasks.spawn(async move {
            discover_ssdp(
                timeout,
                wants_dlna,
                wants_roku,
                wants_generic_dial,
                events,
                cancellation,
            )
            .await
        });
    }

    while tasks.join_next().await.is_some() {}
}

async fn discover_playbridge(
    timeout: Duration,
    events: mpsc::Sender<DiscoveryEvent>,
    cancellation: CancellationToken,
) {
    send(
        &events,
        DiscoveryEvent::Started(ReceiverProtocol::PlayBridge),
    )
    .await;
    let (found_sender, mut found_receiver) = mpsc::channel(64);
    let search = native_discovery::discover_incremental(timeout, found_sender);
    tokio::pin!(search);
    loop {
        tokio::select! {
            _ = cancellation.cancelled() => break,
            receiver = found_receiver.recv() => {
                if let Some(receiver) = receiver {
                    send(&events, DiscoveryEvent::Found(playbridge_receiver(receiver))).await;
                }
            }
            result = &mut search => {
                if let Err(failure) = result {
                    report_error(&events, ReceiverProtocol::PlayBridge, failure).await;
                }
                while let Ok(receiver) = found_receiver.try_recv() {
                    send(&events, DiscoveryEvent::Found(playbridge_receiver(receiver))).await;
                }
                break;
            }
        }
    }
    send(
        &events,
        DiscoveryEvent::Finished(ReceiverProtocol::PlayBridge),
    )
    .await;
}

async fn discover_google_cast(
    timeout: Duration,
    events: mpsc::Sender<DiscoveryEvent>,
    cancellation: CancellationToken,
) {
    send(
        &events,
        DiscoveryEvent::Started(ReceiverProtocol::GoogleCast),
    )
    .await;
    let (found_sender, mut found_receiver) = mpsc::channel(64);
    let search = native_discovery::discover_google_cast_incremental(timeout, found_sender);
    tokio::pin!(search);
    loop {
        tokio::select! {
            _ = cancellation.cancelled() => break,
            receiver = found_receiver.recv() => {
                if let Some(receiver) = receiver {
                    send(&events, DiscoveryEvent::Found(google_cast_receiver(receiver))).await;
                }
            }
            result = &mut search => {
                if let Err(failure) = result {
                    report_error(&events, ReceiverProtocol::GoogleCast, failure).await;
                }
                while let Ok(receiver) = found_receiver.try_recv() {
                    send(&events, DiscoveryEvent::Found(google_cast_receiver(receiver))).await;
                }
                break;
            }
        }
    }
    send(
        &events,
        DiscoveryEvent::Finished(ReceiverProtocol::GoogleCast),
    )
    .await;
}

fn google_cast_receiver(receiver: native_discovery::GoogleCastReceiver) -> Receiver {
    let id = receiver
        .uuid
        .clone()
        .unwrap_or_else(|| format!("{}:{}", receiver.name, receiver.port));
    Receiver {
        id: ReceiverId(format!("google_cast:{id}")),
        protocol: ReceiverProtocol::GoogleCast,
        name: receiver.name,
        addresses: receiver.addresses,
        port: Some(receiver.port),
        wss_port: None,
        location: None,
        uuid: receiver.uuid,
    }
}

fn playbridge_receiver(receiver: native_discovery::PlayBridgeReceiver) -> Receiver {
    let id = receiver
        .uuid
        .clone()
        .unwrap_or_else(|| format!("{}:{}", receiver.name, receiver.port));
    let addresses = receiver
        .preferred_addresses()
        .into_iter()
        .map(ToOwned::to_owned)
        .collect();
    Receiver {
        id: ReceiverId(format!("playbridge:{id}")),
        protocol: ReceiverProtocol::PlayBridge,
        name: receiver.name,
        addresses,
        port: Some(receiver.port),
        wss_port: receiver.wss_port,
        location: None,
        uuid: receiver.uuid,
    }
}

async fn discover_ssdp(
    timeout: Duration,
    wants_dlna: bool,
    wants_roku: bool,
    wants_generic_dial: bool,
    events: mpsc::Sender<DiscoveryEvent>,
    cancellation: CancellationToken,
) {
    if wants_dlna {
        send(&events, DiscoveryEvent::Started(ReceiverProtocol::Dlna)).await;
    }
    if wants_roku {
        send(&events, DiscoveryEvent::Started(ReceiverProtocol::Roku)).await;
    }
    if wants_generic_dial {
        send(&events, DiscoveryEvent::Started(ReceiverProtocol::Dial)).await;
    }
    let protocols = [
        wants_dlna.then_some(DiscoveryProtocol::Dlna),
        wants_roku.then_some(DiscoveryProtocol::Roku),
        wants_generic_dial.then_some(DiscoveryProtocol::Dial),
    ]
    .into_iter()
    .flatten()
    .collect();
    let ssdp_config = SsdpConfig {
        protocols,
        timeout,
        ..SsdpConfig::default()
    };
    let enrich_timeout = timeout.min(Duration::from_secs(3));
    let http = match Client::builder()
        .timeout(enrich_timeout)
        .redirect(reqwest::redirect::Policy::none())
        .build()
    {
        Ok(client) => client,
        Err(failure) => {
            if wants_dlna {
                report_error(&events, ReceiverProtocol::Dlna, &failure).await;
            }
            if wants_roku {
                report_error(&events, ReceiverProtocol::Roku, &failure).await;
            }
            if wants_generic_dial {
                report_error(&events, ReceiverProtocol::Dial, failure).await;
            }
            return;
        }
    };
    let (hit_sender, mut hit_receiver) = mpsc::channel(64);
    let search = DiscoverySession::search_incremental(&ssdp_config, hit_sender);
    tokio::pin!(search);
    let emitter = SsdpEmitter {
        wants_dlna,
        wants_roku,
        wants_generic_dial,
        enrich_timeout,
        http: &http,
        events: &events,
        cancellation: &cancellation,
        deadline: tokio::time::Instant::now() + timeout,
    };
    loop {
        tokio::select! {
            _ = cancellation.cancelled() => break,
            hit = hit_receiver.recv() => {
                if let Some(hit) = hit {
                    emitter.emit(hit).await;
                }
            }
            result = &mut search => {
                if let Err(failure) = result {
                    if wants_dlna {
                        report_error(&events, ReceiverProtocol::Dlna, &failure).await;
                    }
                    if wants_roku {
                        report_error(&events, ReceiverProtocol::Roku, &failure).await;
                    }
                    if wants_generic_dial {
                        report_error(&events, ReceiverProtocol::Dial, failure).await;
                    }
                }
                while let Ok(hit) = hit_receiver.try_recv() {
                    emitter.emit(hit).await;
                }
                break;
            }
        }
    }
    if wants_dlna {
        send(&events, DiscoveryEvent::Finished(ReceiverProtocol::Dlna)).await;
    }
    if wants_roku {
        send(&events, DiscoveryEvent::Finished(ReceiverProtocol::Roku)).await;
    }
    if wants_generic_dial {
        send(&events, DiscoveryEvent::Finished(ReceiverProtocol::Dial)).await;
    }
}

struct SsdpEmitter<'a> {
    wants_dlna: bool,
    wants_roku: bool,
    wants_generic_dial: bool,
    enrich_timeout: Duration,
    http: &'a Client,
    events: &'a mpsc::Sender<DiscoveryEvent>,
    cancellation: &'a CancellationToken,
    deadline: tokio::time::Instant,
}

impl SsdpEmitter<'_> {
    async fn emit(&self, hit: DiscoveryHit) {
        if self.cancellation.is_cancelled() {
            return;
        }
        let remaining = self
            .deadline
            .saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return;
        }
        let enrich_timeout = self.enrich_timeout.min(remaining);
        let stable = hit
            .unique_service_name
            .as_deref()
            .unwrap_or(&hit.location)
            .to_owned();
        match hit.protocol {
            DiscoveryProtocol::Dlna if self.wants_dlna => {
                let receiver = Receiver {
                    id: ReceiverId(format!("dlna:{stable}")),
                    protocol: ReceiverProtocol::Dlna,
                    name: "DLNA receiver".into(),
                    addresses: vec![hit.source.ip().to_string()],
                    port: None,
                    wss_port: None,
                    location: Some(hit.location.clone()),
                    uuid: hit.unique_service_name.clone(),
                };
                send(self.events, DiscoveryEvent::Found(receiver.clone())).await;
                let loaded = tokio::select! {
                    _ = self.cancellation.cancelled() => return,
                    result = tokio::time::timeout(enrich_timeout, Renderer::load(&hit.location)) => result.ok().and_then(Result::ok),
                };
                if let Some(renderer) = loaded {
                    let name = renderer.friendly_name();
                    if !name.is_empty() && name != receiver.name {
                        send(
                            self.events,
                            DiscoveryEvent::Updated(Receiver {
                                name: name.to_owned(),
                                ..receiver
                            }),
                        )
                        .await;
                    }
                }
            }
            DiscoveryProtocol::Roku if self.wants_roku => {
                let port = url::Url::parse(&hit.location)
                    .ok()
                    .and_then(|url| url.port_or_known_default())
                    .or(Some(crate::roku::DEFAULT_ECP_PORT));
                let receiver = Receiver {
                    id: ReceiverId(format!("roku:{stable}")),
                    protocol: ReceiverProtocol::Roku,
                    name: "Roku".into(),
                    addresses: vec![hit.source.ip().to_string()],
                    port,
                    wss_port: None,
                    location: Some(hit.location),
                    uuid: hit.unique_service_name,
                };
                send(self.events, DiscoveryEvent::Found(receiver.clone())).await;
                let client = crate::roku::RokuClient::new(
                    &hit.source.ip().to_string(),
                    port.unwrap_or(crate::roku::DEFAULT_ECP_PORT),
                    enrich_timeout,
                );
                let name = match client {
                    Ok(client) => tokio::select! {
                        _ = self.cancellation.cancelled() => return,
                        result = tokio::time::timeout(enrich_timeout, client.device_name()) => result.ok().and_then(Result::ok),
                    },
                    Err(_) => None,
                };
                if let Some(name) = name
                    && name != receiver.name
                {
                    send(
                        self.events,
                        DiscoveryEvent::Updated(Receiver { name, ..receiver }),
                    )
                    .await;
                }
            }
            DiscoveryProtocol::Dial if self.wants_generic_dial => {
                let described = tokio::select! {
                    _ = self.cancellation.cancelled() => return,
                    result = tokio::time::timeout(enrich_timeout, DialDevice::fetch(&hit.location, self.http)) => result,
                };
                match described {
                    Err(_) => {
                        report_error(
                            self.events,
                            ReceiverProtocol::Dial,
                            "DIAL description timed out",
                        )
                        .await;
                    }
                    Ok(Ok(device)) => {
                        let protocol = ReceiverProtocol::Dial;
                        if self.wants_generic_dial {
                            send(
                                self.events,
                                DiscoveryEvent::Found(Receiver {
                                    id: ReceiverId(format!("dial:{stable}")),
                                    protocol,
                                    name: device.friendly_name,
                                    addresses: vec![hit.source.ip().to_string()],
                                    port: device
                                        .application_url
                                        .as_ref()
                                        .and_then(|url| url.port_or_known_default()),
                                    wss_port: None,
                                    location: Some(hit.location),
                                    uuid: hit.unique_service_name,
                                }),
                            )
                            .await;
                        }
                    }
                    Ok(Err(failure)) => {
                        report_error(self.events, ReceiverProtocol::Dial, failure).await;
                    }
                }
            }
            _ => {}
        }
    }
}

async fn send(events: &mpsc::Sender<DiscoveryEvent>, event: DiscoveryEvent) {
    let _ = events.send(event).await;
}

async fn report_error(
    events: &mpsc::Sender<DiscoveryEvent>,
    protocol: ReceiverProtocol,
    failure: impl std::fmt::Display,
) {
    send(
        events,
        DiscoveryEvent::Error {
            protocol,
            message: failure.to_string(),
        },
    )
    .await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn cancellation_closes_discovery_stream() {
        let mut stream = DiscoveryStream::start(DiscoveryConfig {
            protocols: HashSet::new(),
            timeout: Duration::from_secs(30),
        });
        stream.cancel();
        assert!(stream.next().await.is_none());
    }

    #[test]
    fn automatic_mode_is_native_dlna_and_roku() {
        let protocols = DiscoveryConfig::default().protocols;
        assert!(protocols.contains(&ReceiverProtocol::PlayBridge));
        assert!(protocols.contains(&ReceiverProtocol::Dlna));
        assert!(protocols.contains(&ReceiverProtocol::Roku));
        assert!(!protocols.contains(&ReceiverProtocol::Dial));
    }

    #[test]
    fn protocol_names_are_stable_and_parse_native_alias() {
        assert_eq!(ReceiverProtocol::PlayBridge.as_str(), "playbridge");
        assert_eq!("native".parse(), Ok(ReceiverProtocol::PlayBridge));
        assert_eq!("DLNA".parse(), Ok(ReceiverProtocol::Dlna));
        assert_eq!("googlecast".parse(), Ok(ReceiverProtocol::GoogleCast));
        assert!("unknown".parse::<ReceiverProtocol>().is_err());
    }

    #[test]
    fn selected_mode_deduplicates_protocols() {
        let config = DiscoveryConfig::selected(
            [ReceiverProtocol::Roku, ReceiverProtocol::Roku],
            Duration::from_secs(9),
        );
        assert_eq!(config.protocols, HashSet::from([ReceiverProtocol::Roku]));
        assert_eq!(config.timeout, Duration::from_secs(9));
    }
}
