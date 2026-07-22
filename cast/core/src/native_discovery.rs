use std::{
    collections::HashMap,
    time::{Duration, Instant},
};

use mdns_sd::{ResolvedService, ServiceDaemon, ServiceEvent};
use tokio::{sync::mpsc, time};

use crate::{CastError, Result};

pub const SERVICE_TYPE: &str = "_playbridge._tcp.local.";
pub const GOOGLE_CAST_SERVICE_TYPE: &str = "_googlecast._tcp.local.";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GoogleCastReceiver {
    pub name: String,
    pub uuid: Option<String>,
    pub addresses: Vec<String>,
    pub port: u16,
    pub model: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlayBridgeReceiver {
    pub name: String,
    pub uuid: Option<String>,
    /// Address strings retain an IPv6 scope suffix such as `%en0` when mDNS
    /// resolved a link-local address.
    pub addresses: Vec<String>,
    pub custom_address: Option<String>,
    pub port: u16,
    pub wss_port: Option<u16>,
    pub logs_port: Option<u16>,
}

impl PlayBridgeReceiver {
    pub fn preferred_port(&self) -> u16 {
        self.wss_port.unwrap_or(self.port)
    }

    pub fn preferred_addresses(&self) -> Vec<&str> {
        self.custom_address
            .as_deref()
            .map(|address| vec![address])
            .unwrap_or_else(|| self.addresses.iter().map(String::as_str).collect())
    }

    pub fn websocket_url(&self, address: &str) -> String {
        crate::net::wss_endpoint(address, self.preferred_port())
    }
}

/// Browses the native PlayBridge service for a bounded foreground scan window.
pub async fn discover(timeout: Duration) -> Result<Vec<PlayBridgeReceiver>> {
    let (sender, mut receiver) = mpsc::channel(64);
    let worker = discover_incremental(timeout, sender);
    tokio::pin!(worker);
    let mut found = HashMap::<String, PlayBridgeReceiver>::new();
    loop {
        tokio::select! {
            item = receiver.recv() => {
                match item {
                    Some(item) => {
                        found.insert(receiver_key(&item), item);
                    }
                    None => {
                        (&mut worker).await?;
                        return Ok(found.into_values().collect());
                    }
                }
            }
            result = &mut worker => {
                result?;
                while let Ok(item) = receiver.try_recv() {
                    found.insert(receiver_key(&item), item);
                }
                return Ok(found.into_values().collect());
            }
        }
    }
}

/// Browses PlayBridge services and sends each new or changed resolution immediately.
pub async fn discover_incremental(
    timeout: Duration,
    events: mpsc::Sender<PlayBridgeReceiver>,
) -> Result<()> {
    let daemon = ServiceDaemon::new().map_err(mdns_error)?;
    let receiver = daemon.browse(SERVICE_TYPE).map_err(mdns_error)?;
    let deadline = Instant::now() + timeout;
    let mut found = HashMap::<String, PlayBridgeReceiver>::new();

    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match time::timeout(remaining, receiver.recv_async()).await {
            Ok(Ok(ServiceEvent::ServiceResolved(info))) => {
                if let Some(device) = parse_service(&info) {
                    let key = receiver_key(&device);
                    if found.get(&key) != Some(&device) {
                        found.insert(key, device.clone());
                        let _ = events.send(device).await;
                    }
                }
            }
            Ok(Ok(ServiceEvent::ServiceRemoved(_, fullname))) => {
                found.retain(|_, device| !fullname.starts_with(&device.name));
            }
            Ok(Ok(_)) => {}
            Ok(Err(error)) => {
                let _ = daemon.stop_browse(SERVICE_TYPE);
                let _ = daemon.shutdown();
                return Err(mdns_error(error));
            }
            Err(_) => break,
        }
    }
    let _ = daemon.stop_browse(SERVICE_TYPE);
    let _ = daemon.shutdown();
    Ok(())
}

/// Browses Google Cast services and sends each new or changed resolution immediately.
pub async fn discover_google_cast_incremental(
    timeout: Duration,
    events: mpsc::Sender<GoogleCastReceiver>,
) -> Result<()> {
    let daemon = ServiceDaemon::new().map_err(mdns_error)?;
    let receiver = daemon
        .browse(GOOGLE_CAST_SERVICE_TYPE)
        .map_err(mdns_error)?;
    let deadline = Instant::now() + timeout;
    let mut found = HashMap::<String, GoogleCastReceiver>::new();

    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match time::timeout(remaining, receiver.recv_async()).await {
            Ok(Ok(ServiceEvent::ServiceResolved(info))) => {
                if let Some(device) = parse_google_cast_service(&info) {
                    let key = device
                        .uuid
                        .clone()
                        .unwrap_or_else(|| format!("{}:{}", device.name, device.port));
                    if found.get(&key) != Some(&device) {
                        found.insert(key, device.clone());
                        let _ = events.send(device).await;
                    }
                }
            }
            Ok(Ok(ServiceEvent::ServiceRemoved(_, fullname))) => {
                found.retain(|_, device| !fullname.starts_with(&device.name));
            }
            Ok(Ok(_)) => {}
            Ok(Err(error)) => {
                let _ = daemon.stop_browse(GOOGLE_CAST_SERVICE_TYPE);
                let _ = daemon.shutdown();
                return Err(mdns_error(error));
            }
            Err(_) => break,
        }
    }
    let _ = daemon.stop_browse(GOOGLE_CAST_SERVICE_TYPE);
    let _ = daemon.shutdown();
    Ok(())
}

fn parse_google_cast_service(info: &ResolvedService) -> Option<GoogleCastReceiver> {
    let mut addresses: Vec<_> = info
        .get_addresses()
        .iter()
        .map(ToString::to_string)
        .collect();
    if addresses.iter().any(|address| !is_loopback(address)) {
        addresses.retain(|address| !is_loopback(address));
    }
    addresses.sort_by_key(|ip| (ip.contains(':'), ip.clone()));
    if addresses.is_empty() {
        return None;
    }
    let friendly_name = info
        .get_property_val_str("fn")
        .map(str::trim)
        .filter(|val| !val.is_empty())
        .map(ToOwned::to_owned)
        .unwrap_or_else(|| {
            info.get_fullname()
                .strip_suffix(GOOGLE_CAST_SERVICE_TYPE)
                .unwrap_or(info.get_fullname())
                .trim_end_matches('.')
                .replace("\\032", " ")
        });
    Some(GoogleCastReceiver {
        name: friendly_name,
        uuid: non_empty_property(info, "id"),
        addresses,
        port: info.get_port(),
        model: non_empty_property(info, "md"),
    })
}

fn receiver_key(device: &PlayBridgeReceiver) -> String {
    device
        .uuid
        .clone()
        .unwrap_or_else(|| format!("{}:{}", device.name, device.port))
}

fn parse_service(info: &ResolvedService) -> Option<PlayBridgeReceiver> {
    let mut addresses: Vec<_> = info
        .get_addresses()
        .iter()
        .map(ToString::to_string)
        .collect();
    if addresses.iter().any(|address| !is_loopback(address)) {
        addresses.retain(|address| !is_loopback(address));
    }
    addresses.sort_by_key(|ip| (ip.contains(':'), ip.clone()));
    if addresses.is_empty() {
        return None;
    }
    let display_name = info
        .get_fullname()
        .strip_suffix(SERVICE_TYPE)
        .unwrap_or(info.get_fullname())
        .trim_end_matches('.')
        .replace("\\032", " ");
    Some(PlayBridgeReceiver {
        name: display_name,
        uuid: non_empty_property(info, "uuid"),
        addresses,
        custom_address: non_empty_property(info, "custom_ip").filter(|value| value != "auto"),
        port: info.get_port(),
        wss_port: port_property(info, "wss_port"),
        logs_port: port_property(info, "logs_port"),
    })
}

fn non_empty_property(info: &ResolvedService, key: &str) -> Option<String> {
    info.get_property_val_str(key)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToOwned::to_owned)
}

fn port_property(info: &ResolvedService, key: &str) -> Option<u16> {
    non_empty_property(info, key)?
        .parse()
        .ok()
        .filter(|port| *port > 0)
}

fn mdns_error(error: impl std::fmt::Display) -> CastError {
    CastError::Mdns(error.to_string())
}

fn is_loopback(address: &str) -> bool {
    let (ip, scope) = address.split_once('%').unwrap_or((address, ""));
    scope == "lo"
        || scope.starts_with("lo0")
        || ip
            .parse::<std::net::IpAddr>()
            .ok()
            .is_some_and(|ip| ip.is_loopback())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_ipv6_wss_url_using_advertised_port() {
        let receiver = PlayBridgeReceiver {
            name: "Living Room".into(),
            uuid: Some("receiver-1".into()),
            addresses: vec!["fe80::1234%en0".into()],
            custom_address: None,
            port: 8765,
            wss_port: Some(9000),
            logs_port: None,
        };
        assert_eq!(
            receiver.websocket_url("fe80::1234%en0"),
            "wss://[fe80::1234%25en0]:9000/"
        );
    }

    #[test]
    fn filters_loopback_and_loopback_scoped_addresses() {
        assert!(is_loopback("127.0.0.1"));
        assert!(is_loopback("::1"));
        assert!(is_loopback("fe80::1%lo0"));
        assert!(!is_loopback("fe80::1234%en0"));
    }
}
