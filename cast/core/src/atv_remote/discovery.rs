use std::collections::HashMap;
use std::time::{Duration, Instant};

use mdns_sd::{ServiceDaemon, ServiceEvent};

use super::error::AtvError;

pub const SERVICE_TYPE: &str = "_androidtvremote2._tcp.local.";

/// One Android TV Remote v2 device from mDNS.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct AtvDevice {
    pub name: String,
    /// Preferred address. Pairing and control use the fixed protocol ports.
    pub host: String,
    pub port: u16,
    pub addresses: Vec<String>,
    pub txt: Vec<(String, String)>,
}

impl AtvDevice {
    /// Bluetooth MAC from the `bt` TXT record, when the TV sent one.
    pub fn bluetooth_mac(&self) -> Option<&str> {
        self.txt
            .iter()
            .find(|(key, _)| key == "bt")
            .map(|(_, value)| value.as_str())
    }
}

/// Browses `_androidtvremote2._tcp` until `timeout` elapses.
///
/// A zero timeout returns an empty list and does not open a browser.
pub async fn discover(timeout: Duration) -> Result<Vec<AtvDevice>, AtvError> {
    if timeout.is_zero() {
        return Ok(Vec::new());
    }
    let daemon = ServiceDaemon::new()
        .map_err(|error| AtvError::Unreachable(format!("mDNS browse failed: {error}")))?;
    // FFI cancellation drops this future. Explicit shutdown is needed even
    // when it never reaches the normal timeout/error cleanup below.
    let _shutdown = DiscoveryShutdown(&daemon);
    let receiver = daemon
        .browse(SERVICE_TYPE)
        .map_err(|error| AtvError::Unreachable(format!("mDNS browse failed: {error}")))?;
    let deadline = Instant::now() + timeout;
    let mut found = HashMap::<String, AtvDevice>::new();
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match tokio::time::timeout(remaining, receiver.recv_async()).await {
            Ok(Ok(ServiceEvent::ServiceResolved(info))) => {
                let addresses = prefer_non_loopback(
                    info.get_addresses()
                        .iter()
                        .map(ToString::to_string)
                        .collect(),
                );
                let mut txt = Vec::new();
                for property in info.get_properties().iter() {
                    txt.push((property.key().to_owned(), property.val_str().to_owned()));
                }
                if let Some(device) = assemble_device(
                    service_name(info.get_fullname()),
                    info.get_port(),
                    addresses,
                    txt,
                ) {
                    found.insert(format!("{}:{}", device.name, device.port), device);
                }
            }
            Ok(Ok(ServiceEvent::ServiceRemoved(_, fullname))) => {
                found.retain(|_, device| !fullname.starts_with(&device.name));
            }
            Ok(Ok(_)) => {}
            Ok(Err(error)) => {
                return Err(AtvError::Unreachable(format!(
                    "mDNS browse failed: {error}"
                )));
            }
            Err(_) => break,
        }
    }
    let mut devices: Vec<_> = found.into_values().collect();
    devices.sort_by(|left, right| left.name.cmp(&right.name).then(left.host.cmp(&right.host)));
    Ok(devices)
}

struct DiscoveryShutdown<'a>(&'a ServiceDaemon);

impl Drop for DiscoveryShutdown<'_> {
    fn drop(&mut self) {
        let _ = self.0.stop_browse(SERVICE_TYPE);
        let _ = self.0.shutdown();
    }
}

pub(crate) fn assemble_device(
    name: String,
    port: u16,
    addresses: Vec<String>,
    txt: Vec<(String, String)>,
) -> Option<AtvDevice> {
    let addresses = prefer_non_loopback(addresses);
    let host = addresses.first()?.clone();
    Some(AtvDevice {
        name,
        host,
        port,
        addresses,
        txt,
    })
}

pub(crate) fn prefer_non_loopback(mut addresses: Vec<String>) -> Vec<String> {
    if addresses.iter().any(|address| !is_loopback(address)) {
        addresses.retain(|address| !is_loopback(address));
    }
    addresses.sort_by_key(|address| (address.contains(':'), address.clone()));
    addresses
}

fn service_name(fullname: &str) -> String {
    fullname
        .strip_suffix(SERVICE_TYPE)
        .unwrap_or(fullname)
        .trim_end_matches('.')
        .replace("\\032", " ")
}

fn is_loopback(address: &str) -> bool {
    let address = address.split('%').next().unwrap_or(address);
    address.starts_with("127.") || address == "::1" || address.starts_with("::ffff:127.")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn zero_timeout_does_not_browse() {
        let found = discover(Duration::ZERO).await.unwrap();
        assert!(found.is_empty());
    }

    #[test]
    fn assembly_drops_loopback_and_reads_bt() {
        let device = assemble_device(
            "Living Room".to_owned(),
            6466,
            vec!["127.0.0.1".to_owned(), "192.168.1.19".to_owned()],
            vec![("bt".to_owned(), "AA:BB:CC:DD:EE:FF".to_owned())],
        )
        .unwrap();
        assert_eq!(device.host, "192.168.1.19");
        assert_eq!(device.addresses, vec!["192.168.1.19".to_owned()]);
        assert_eq!(device.bluetooth_mac(), Some("AA:BB:CC:DD:EE:FF"));
    }

    #[test]
    fn loopback_is_kept_when_it_is_the_only_address() {
        let device = assemble_device(
            "Local".to_owned(),
            6466,
            vec!["127.0.0.1".to_owned()],
            Vec::new(),
        )
        .unwrap();
        assert_eq!(device.host, "127.0.0.1");
        assert!(device.bluetooth_mac().is_none());
    }
}
