use std::{
    collections::HashMap,
    net::{Ipv4Addr, SocketAddr, SocketAddrV4},
    time::{Duration, Instant},
};

use tokio::{net::UdpSocket, sync::mpsc, time};

use crate::Result;

pub const MEDIA_RENDERER: &str = "urn:schemas-upnp-org:device:MediaRenderer:1";
pub const AV_TRANSPORT: &str = "urn:schemas-upnp-org:service:AVTransport:1";
pub const DIAL_SERVICE: &str = "urn:dial-multiscreen-org:service:dial:1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DiscoveryProtocol {
    Dlna,
    Dial,
}

impl DiscoveryProtocol {
    fn search_targets(self) -> &'static [&'static str] {
        match self {
            Self::Dlna => &[MEDIA_RENDERER, AV_TRANSPORT],
            Self::Dial => &[DIAL_SERVICE],
        }
    }
}

#[derive(Debug, Clone)]
pub struct DiscoveryConfig {
    pub protocols: Vec<DiscoveryProtocol>,
    pub timeout: Duration,
    pub repeats: usize,
    pub mx_seconds: u8,
    pub ttl: u32,
}

impl Default for DiscoveryConfig {
    fn default() -> Self {
        Self {
            protocols: vec![DiscoveryProtocol::Dlna, DiscoveryProtocol::Dial],
            timeout: Duration::from_secs(3),
            repeats: 2,
            mx_seconds: 2,
            ttl: 2,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveryHit {
    pub protocol: DiscoveryProtocol,
    pub location: String,
    pub search_target: Option<String>,
    pub unique_service_name: Option<String>,
    pub server: Option<String>,
    pub source: SocketAddr,
}

/// A lightweight, one-socket SSDP search covering every selected provider.
pub struct DiscoverySession;

impl DiscoverySession {
    pub async fn search(config: &DiscoveryConfig) -> Result<Vec<DiscoveryHit>> {
        let (sender, mut receiver) = mpsc::unbounded_channel();
        let worker = Self::search_incremental(config, sender);
        tokio::pin!(worker);
        let mut hits = HashMap::<String, DiscoveryHit>::new();
        loop {
            tokio::select! {
                item = receiver.recv() => {
                    match item {
                        Some(item) => {
                            hits.insert(item.location.clone(), item);
                        }
                        None => {
                            (&mut worker).await?;
                            return Ok(hits.into_values().collect());
                        }
                    }
                }
                result = &mut worker => {
                    result?;
                    while let Ok(item) = receiver.try_recv() {
                        hits.insert(item.location.clone(), item);
                    }
                    return Ok(hits.into_values().collect());
                }
            }
        }
    }

    /// Sends each newly discovered location while the SSDP receive window is open.
    pub async fn search_incremental(
        config: &DiscoveryConfig,
        events: mpsc::UnboundedSender<DiscoveryHit>,
    ) -> Result<()> {
        let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)).await?;
        socket.set_multicast_ttl_v4(config.ttl)?;
        let destination = SocketAddrV4::new(Ipv4Addr::new(239, 255, 255, 250), 1900);

        for _ in 0..config.repeats.max(1) {
            for target in targets(&config.protocols) {
                socket
                    .send_to(&m_search(target, config.mx_seconds), destination)
                    .await?;
            }
        }

        let deadline = Instant::now() + config.timeout;
        let mut buffer = [0_u8; 8192];
        let mut hits = HashMap::<String, DiscoveryHit>::new();
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            let received = time::timeout(remaining, socket.recv_from(&mut buffer)).await;
            let Ok(Ok((length, source))) = received else {
                break;
            };
            if let Some(hit) = parse_response(&buffer[..length], source) {
                let location = hit.location.clone();
                let should_emit = match hits.get(&location) {
                    None => true,
                    Some(saved) => {
                        saved.protocol != DiscoveryProtocol::Dial
                            && hit.protocol == DiscoveryProtocol::Dial
                    }
                };
                if should_emit {
                    hits.insert(location, hit.clone());
                    let _ = events.send(hit);
                }
            }
        }
        Ok(())
    }
}

fn targets(protocols: &[DiscoveryProtocol]) -> Vec<&'static str> {
    let mut result = Vec::new();
    for protocol in protocols {
        for target in protocol.search_targets() {
            if !result.contains(target) {
                result.push(*target);
            }
        }
    }
    result
}

fn m_search(target: &str, mx_seconds: u8) -> Vec<u8> {
    format!(
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: {}\r\nST: {}\r\n\r\n",
        mx_seconds.clamp(1, 5),
        target
    )
    .into_bytes()
}

fn parse_response(bytes: &[u8], source: SocketAddr) -> Option<DiscoveryHit> {
    let response = std::str::from_utf8(bytes).ok()?;
    let headers = response.lines().skip(1).filter_map(|line| {
        let (name, value) = line.split_once(':')?;
        Some((name.trim().to_ascii_lowercase(), value.trim().to_owned()))
    });
    let headers: HashMap<_, _> = headers.collect();
    let location = headers.get("location")?.to_owned();
    url::Url::parse(&location).ok()?;
    let search_target = headers.get("st").cloned();
    let protocol = if search_target
        .as_deref()
        .is_some_and(|target| target.eq_ignore_ascii_case(DIAL_SERVICE))
        || headers
            .get("application-url")
            .is_some_and(|value| !value.is_empty())
    {
        DiscoveryProtocol::Dial
    } else {
        DiscoveryProtocol::Dlna
    };
    Some(DiscoveryHit {
        protocol,
        location,
        search_target,
        unique_service_name: headers.get("usn").cloned(),
        server: headers.get("server").cloned(),
        source,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_headers_case_insensitively_and_classifies_dial() {
        let packet = b"HTTP/1.1 200 OK\r\nLocation: http://192.0.2.1/dd.xml\r\nST: urn:dial-multiscreen-org:service:dial:1\r\nUSN: uuid:roku::dial\r\n\r\n";
        let hit = parse_response(packet, "192.0.2.1:1900".parse().unwrap()).unwrap();
        assert_eq!(hit.protocol, DiscoveryProtocol::Dial);
        assert_eq!(hit.location, "http://192.0.2.1/dd.xml");
    }

    #[test]
    fn automatic_mode_builds_three_targets_without_duplicates() {
        let found = targets(&[
            DiscoveryProtocol::Dlna,
            DiscoveryProtocol::Dial,
            DiscoveryProtocol::Dlna,
        ]);
        assert_eq!(found, vec![MEDIA_RENDERER, AV_TRANSPORT, DIAL_SERVICE]);
    }

    #[test]
    fn ignores_missing_or_invalid_locations() {
        let source = "192.0.2.1:1900".parse().unwrap();
        assert!(parse_response(b"HTTP/1.1 200 OK\r\nST: foo\r\n\r\n", source).is_none());
        assert!(parse_response(b"HTTP/1.1 200 OK\r\nLOCATION: nope\r\n\r\n", source).is_none());
    }
}
