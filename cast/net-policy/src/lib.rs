//! Pure destination rules shared by page-media transports.
//!
//! This crate performs no DNS lookup or I/O. Transports must check all DNS
//! answers immediately before connecting, check the connected peer when available,
//! and repeat the checks for redirects, playlists, segments and subtitles.

use serde::{Deserialize, Serialize};
use std::collections::{BTreeSet, HashSet};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

/// Request-scoped network authority. Page grants are exact origins. Native/admin
/// local registrations get one `trusted_origin`:
/// - LAN IP-literal host the user chose: host-level (any port on that IP);
/// - DNS-approved name (native local registration only): exact origin
///   (scheme, host, port) with every connection pinned to the IPs checked at
///   registration (`pinned_ips`);
/// - loopback (literal, `localhost`, or DNS resolving only to loopback): exact origin.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct NetworkPolicy {
    allowed_private_origins: HashSet<String>,
    /// Native/admin registration of a local server. Only a LAN IP-literal host is
    /// trusted host-level (same-host port redirects); every other trusted origin,
    /// DNS-approved or loopback, matches scheme, host and port exactly.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    trusted_origin: Option<String>,
    #[serde(default)]
    registered_media: bool,
    /// Set when the trusted origin is loopback, or native DNS resolved only loopback.
    #[serde(default)]
    trusted_allows_loopback: bool,
    /// IPs a DNS-approved `trusted_origin` resolved to at registration. When
    /// non-empty, every later connection to that origin must resolve only to
    /// these addresses, so DNS rebinding cannot retarget the trust.
    #[serde(default, skip_serializing_if = "BTreeSet::is_empty")]
    pinned_ips: BTreeSet<IpAddr>,
}

impl NetworkPolicy {
    pub const MAX_PRIVATE_ORIGINS: usize = 16;

    /// Whether this policy belongs to a native/admin media registration.
    pub fn is_registered_media(&self) -> bool {
        self.registered_media
    }

    /// Validate URL syntax and hostname rules before a transport resolves it.
    pub fn validate_url(&self, value: &str) -> Result<url::Url, String> {
        let url = parse_http_url(value)?;
        self.address_policy(&url)
            .validate_host(url.host_str().ok_or("media URL has no host")?)?;
        Ok(url)
    }

    /// Validate every DNS answer, or a connected peer passed as a one-element slice.
    /// An empty answer set fails closed. Native DNS grants also enforce their pin.
    pub fn validate_destination(&self, value: &str, addresses: &[IpAddr]) -> Result<(), String> {
        let url = self.validate_url(value)?;
        self.address_policy(&url).validate_addresses(addresses)?;
        if let Some(pinned) = self.pinned_ips_for(&url) {
            if addresses.iter().any(|address| !pinned.contains(address)) {
                return Err("media host resolved outside the approved addresses".into());
            }
        }
        Ok(())
    }

    /// Authority selected for this exact request, also used by connector DNS hooks.
    pub fn address_policy(&self, url: &url::Url) -> AddressPolicy {
        AddressPolicy {
            allow_private_network: self.allows_private_url(url),
            allow_loopback: self.allows_loopback(url),
        }
    }

    pub fn new(origins: Vec<String>) -> Result<Self, String> {
        if origins.len() > Self::MAX_PRIVATE_ORIGINS {
            return Err("too many private media origins".into());
        }
        let mut normalized = HashSet::new();
        for value in origins {
            let url = url::Url::parse(&value).map_err(|_| "invalid private media origin")?;
            if !matches!(url.scheme(), "http" | "https")
                || !url.username().is_empty()
                || url.password().is_some()
                || !matches!(url.path(), "" | "/")
                || url.query().is_some()
                || url.fragment().is_some()
            {
                return Err("invalid private media origin".into());
            }
            let host = url.host_str().ok_or("private media origin has no host")?;
            let lower = host.to_ascii_lowercase();
            if lower.contains('*') || lower == "localhost" || lower.ends_with(".localhost") {
                return Err("private media origin is forbidden".into());
            }
            normalized.insert(normalized_origin(&url)?);
        }
        Ok(Self {
            allowed_private_origins: normalized,
            trusted_origin: None,
            registered_media: false,
            trusted_allows_loopback: false,
            pinned_ips: BTreeSet::new(),
        })
    }

    /// HTTP/admin and legacy synchronous registrations classify only URL text.
    pub fn for_registered_media(value: &str) -> Result<Self, String> {
        let url = url::Url::parse(value).map_err(|_| "invalid media URL")?;
        let host = url.host_str().ok_or("media URL has no host")?;
        let local = host_text_looks_local(host);
        let origin = normalized_origin(&url)?;
        Ok(Self {
            allowed_private_origins: if local {
                HashSet::from([origin.clone()])
            } else {
                HashSet::new()
            },
            trusted_origin: local.then_some(origin),
            registered_media: true,
            trusted_allows_loopback: local && origin_host_is_loopback(&url),
            pinned_ips: BTreeSet::new(),
        })
    }

    /// Trust the exact origin only if every answer is loopback or every answer is
    /// private LAN/CGNAT. Mixed answers are refused, and the checked IPs are pinned.
    pub fn approve_native_addresses(
        &mut self,
        url: &url::Url,
        addresses: &[SocketAddr],
    ) -> Result<(), String> {
        if addresses.is_empty() {
            return Ok(());
        }
        let all_loopback = addresses.iter().all(|address| address.ip().is_loopback());
        let all_lan = addresses
            .iter()
            .all(|address| classify_address(address.ip()) == AddressClass::PrivateLan);
        if all_loopback || all_lan {
            self.trusted_origin = Some(normalized_origin(url)?);
            self.trusted_allows_loopback = all_loopback;
            self.pinned_ips = addresses.iter().map(|address| address.ip()).collect();
        }
        Ok(())
    }

    fn allows_trusted_origin(&self, url: &url::Url) -> bool {
        let Some(origin) = self.trusted_origin.as_deref() else {
            return false;
        };
        let Ok(original) = url::Url::parse(origin) else {
            return false;
        };
        if original_host_is_lan_literal(&original) {
            // LAN IP literal the user chose: any port on the same host.
            original.host_str().is_some_and(|host| {
                url.host_str()
                    .is_some_and(|other| host.eq_ignore_ascii_case(other))
            })
        } else {
            normalized_origin(url).is_ok_and(|value| value == origin)
        }
    }

    /// Registration-time IPs that connections to `url` must stay within, if it
    /// is the DNS-approved trusted origin.
    pub fn pinned_ips_for(&self, url: &url::Url) -> Option<&BTreeSet<IpAddr>> {
        let origin = self.trusted_origin.as_deref()?;
        (!self.pinned_ips.is_empty() && normalized_origin(url).is_ok_and(|value| value == origin))
            .then_some(&self.pinned_ips)
    }

    pub fn allows_loopback(&self, url: &url::Url) -> bool {
        self.trusted_allows_loopback && self.allows_trusted_origin(url)
    }

    pub fn cache_scope(&self) -> String {
        let mut origins: Vec<_> = self.allowed_private_origins.iter().collect();
        origins.sort();
        serde_json::to_string(&(
            origins,
            &self.trusted_origin,
            self.trusted_allows_loopback,
            &self.pinned_ips,
        ))
        .expect("string serialization")
    }

    pub fn allows_private_url(&self, url: &url::Url) -> bool {
        normalized_origin(url)
            .map(|origin| {
                self.allowed_private_origins.contains(&origin) || self.allows_trusted_origin(url)
            })
            .unwrap_or(false)
    }
}

/// Parse the HTTP(S) URL syntax accepted by the proxy, without resolving its host.
pub fn parse_http_url(value: &str) -> Result<url::Url, String> {
    let url = url::Url::parse(value).map_err(|_| "invalid media URL".to_string())?;
    if !matches!(url.scheme(), "http" | "https")
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("only HTTP(S) media URLs without userinfo are allowed".into());
    }
    Ok(url)
}

/// Scheme, IDNA-normalized host and effective port of a parsed URL.
pub fn normalized_origin(url: &url::Url) -> Result<String, String> {
    let host = url
        .host_str()
        .ok_or_else(|| "media URL has no host".to_string())?;
    let port = url
        .port_or_known_default()
        .ok_or_else(|| "media URL has no port".to_string())?;
    Ok(format!(
        "{}://{}:{}",
        url.scheme().to_ascii_lowercase(),
        host.to_ascii_lowercase(),
        port
    ))
}

/// Address authority for a single origin, suitable for a transport's DNS hook.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AddressPolicy {
    pub allow_private_network: bool,
    pub allow_loopback: bool,
}

impl AddressPolicy {
    pub fn validate_host(self, host: &str) -> Result<(), String> {
        let lower = host.to_ascii_lowercase();
        if !self.allow_loopback && (lower == "localhost" || lower.ends_with(".localhost")) {
            return Err("media destination is forbidden".into());
        }
        if !self.allow_private_network && lower.ends_with(".local") {
            return Err("local-network media permission is required".into());
        }
        Ok(())
    }

    pub fn validate_addresses(self, addresses: &[IpAddr]) -> Result<(), String> {
        if addresses.is_empty()
            || addresses.iter().any(|address| {
                !(is_allowed_address(*address, self.allow_private_network)
                    || self.allow_loopback && address.is_loopback())
            })
        {
            return Err("local-network media permission is required".into());
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AddressClass {
    Public,
    PrivateLan,
    Forbidden,
}

pub fn is_allowed_address(address: IpAddr, allow_private_network: bool) -> bool {
    match classify_address(address) {
        AddressClass::Public => true,
        AddressClass::PrivateLan => allow_private_network,
        AddressClass::Forbidden => false,
    }
}

pub fn classify_address(address: IpAddr) -> AddressClass {
    match address {
        IpAddr::V4(ip) => {
            let [first, second, third, _] = ip.octets();
            if first == 10
                || (first == 172 && (16..=31).contains(&second))
                || (first == 192 && second == 168)
                || (first == 100 && (64..=127).contains(&second))
            {
                // RFC1918 plus CGNAT/Tailscale 100.64/10. Still requires an origin grant.
                AddressClass::PrivateLan
            } else if ip.is_loopback()
                || ip.is_link_local()
                || ip.is_multicast()
                || ip.is_unspecified()
                || first == 0
                || first >= 224
                || (first == 192 && second == 0)
                || (first == 198 && (18..=19).contains(&second))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || ip == Ipv4Addr::BROADCAST
            {
                AddressClass::Forbidden
            } else {
                AddressClass::Public
            }
        }
        IpAddr::V6(ip) => {
            if let Some(mapped) = ip.to_ipv4_mapped() {
                return classify_address(IpAddr::V4(mapped));
            }
            let first = ip.octets()[0];
            if (first & 0xfe) == 0xfc {
                AddressClass::PrivateLan
            } else if ip.is_loopback()
                || ip.is_multicast()
                || ip.is_unspecified()
                || is_ipv6_link_local(ip)
                || is_ipv6_site_local(ip)
                || is_ipv6_documentation(ip)
            {
                AddressClass::Forbidden
            } else {
                AddressClass::Public
            }
        }
    }
}

fn is_ipv6_link_local(ip: Ipv6Addr) -> bool {
    let segments = ip.segments();
    (segments[0] & 0xffc0) == 0xfe80
}

fn is_ipv6_site_local(ip: Ipv6Addr) -> bool {
    (ip.segments()[0] & 0xffc0) == 0xfec0
}

fn is_ipv6_documentation(ip: Ipv6Addr) -> bool {
    let segments = ip.segments();
    segments[0] == 0x2001 && segments[1] == 0x0db8
}

fn host_text_looks_local(host: &str) -> bool {
    let host = host.trim_matches(['[', ']']).to_ascii_lowercase();
    host.parse::<IpAddr>()
        .map(|ip| classify_address(ip) == AddressClass::PrivateLan || ip.is_loopback())
        .unwrap_or_else(|_| {
            host == "localhost"
                || host.ends_with(".localhost")
                || host.ends_with(".local")
                || host.ends_with(".lan")
                || !host.contains('.')
        })
}

/// Exact origin of a media URL whose host is a private-LAN/CGNAT IP literal.
/// Remote-sender registrations may be granted this origin without any DNS-derived
/// trust; hostnames, loopback and public addresses return `None`.
pub fn lan_literal_origin(value: &str) -> Option<String> {
    let url = url::Url::parse(value).ok()?;
    if !matches!(url.scheme(), "http" | "https") || !original_host_is_lan_literal(&url) {
        return None;
    }
    normalized_origin(&url).ok()
}

fn original_host_is_lan_literal(url: &url::Url) -> bool {
    url.host_str().is_some_and(|host| {
        host.trim_matches(['[', ']'])
            .parse::<IpAddr>()
            .is_ok_and(|ip| classify_address(ip) == AddressClass::PrivateLan)
    })
}

fn origin_host_is_loopback(url: &url::Url) -> bool {
    url.host_str().is_some_and(|host| {
        let host = host.trim_matches(['[', ']']).to_ascii_lowercase();
        host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback())
            || host == "localhost"
            || host.ends_with(".localhost")
    })
}

#[cfg(test)]
mod capability_policy_tests {
    use super::*;
    #[test]
    fn http_registration_does_not_treat_public_hostnames_as_lan() {
        let policy =
            NetworkPolicy::for_registered_media("https://nas.example.com/video.mp4").unwrap();
        assert!(policy.trusted_origin.is_none());
        assert!(policy.allowed_private_origins.is_empty());
    }
    #[test]
    fn native_dns_classification_is_explicit_bounded_and_exact_origin() {
        // Exercise the production classification without relying on external DNS.
        for name in ["nas.example.com", "media.plex.direct", "tail.example.com"] {
            let url = url::Url::parse(&format!("https://{name}/video.mp4")).unwrap();
            let restricted = NetworkPolicy::for_registered_media(url.as_str()).unwrap();
            assert!(restricted.trusted_origin.is_none());
            let mut native = restricted.clone();
            native
                .approve_native_addresses(
                    &url,
                    &[
                        "192.168.1.20:443".parse().unwrap(),
                        "100.64.0.5:443".parse().unwrap(),
                    ],
                )
                .unwrap();
            assert!(native.allows_private_url(&url));
            // DNS-approved trust is the exact origin, not the whole host.
            assert!(!native.allows_private_url(
                &url::Url::parse(&format!("http://{name}:9000/segment")).unwrap()
            ));
            assert!(!native
                .allows_private_url(&url::Url::parse(&format!("http://{name}/segment")).unwrap()));
            assert!(!native
                .allows_private_url(&url::Url::parse("http://other.example.com/media").unwrap()));
            assert!(!native.allows_loopback(&url));
            assert_eq!(native.pinned_ips_for(&url).map(|ips| ips.len()), Some(2));
            for addresses in [
                vec![],
                vec!["8.8.8.8:443".parse().unwrap()],
                vec![
                    "192.168.1.20:443".parse().unwrap(),
                    "8.8.8.8:443".parse().unwrap(),
                ],
                vec!["169.254.169.254:443".parse().unwrap()],
                // Mixed loopback + LAN answers are refused outright.
                vec![
                    "127.0.0.1:443".parse().unwrap(),
                    "192.168.1.20:443".parse().unwrap(),
                ],
            ] {
                let mut denied = restricted.clone();
                denied.approve_native_addresses(&url, &addresses).unwrap();
                assert!(denied.trusted_origin.is_none());
                assert!(denied.pinned_ips.is_empty());
            }
            let mut loopback = restricted;
            loopback
                .approve_native_addresses(&url, &["127.0.0.1:443".parse().unwrap()])
                .unwrap();
            assert!(loopback.allows_loopback(&url));
            assert!(!loopback
                .allows_loopback(&url::Url::parse(&format!("https://{name}:6379/media")).unwrap()));
        }
    }

    #[test]
    fn lan_ip_literal_is_host_level_while_dns_and_loopback_are_exact() {
        let literal =
            NetworkPolicy::for_registered_media("http://192.168.1.10:8080/video.mp4").unwrap();
        assert!(literal.pinned_ips.is_empty());
        assert!(literal.allows_private_url(&url::Url::parse("http://192.168.1.10:9000/s").unwrap()));
        assert!(
            !literal.allows_private_url(&url::Url::parse("http://192.168.1.11:8080/s").unwrap())
        );
        let loopback =
            NetworkPolicy::for_registered_media("http://127.0.0.1:8080/video.mp4").unwrap();
        assert!(loopback.allows_loopback(&url::Url::parse("http://127.0.0.1:8080/s").unwrap()));
        assert!(!loopback.allows_loopback(&url::Url::parse("http://127.0.0.1:6379/s").unwrap()));
        // Local-looking names have no registration-time pin, so they stay exact-origin.
        let named = NetworkPolicy::for_registered_media("http://nas.lan:8080/video.mp4").unwrap();
        assert!(!named.allows_private_url(&url::Url::parse("http://nas.lan:9000/s").unwrap()));
    }

    #[test]
    fn pinned_ips_survive_token_serialization_and_partition_the_cache() {
        let url = url::Url::parse("https://nas.example.com/v").unwrap();
        let base = NetworkPolicy::for_registered_media(url.as_str()).unwrap();
        let mut first = base.clone();
        first
            .approve_native_addresses(&url, &["192.168.1.20:443".parse().unwrap()])
            .unwrap();
        let mut second = base.clone();
        second
            .approve_native_addresses(&url, &["192.168.1.21:443".parse().unwrap()])
            .unwrap();
        assert_ne!(first.cache_scope(), second.cache_scope());
        let restored: NetworkPolicy =
            serde_json::from_str(&serde_json::to_string(&first).unwrap()).unwrap();
        assert_eq!(first, restored);
        // Tokens minted before `pinned_ips` existed still deserialize.
        let legacy: NetworkPolicy = serde_json::from_str(
            r#"{"allowed_private_origins":[],"trusted_origin":"http://127.0.0.1:80","registered_media":true,"trusted_allows_loopback":true}"#,
        )
        .unwrap();
        assert!(legacy.pinned_ips.is_empty());
    }

    #[test]
    fn policies_are_preserved_by_token_serialization_and_partition_cache() {
        let policy = NetworkPolicy::for_registered_media("http://127.0.0.1/video.mp4").unwrap();
        let restored: NetworkPolicy =
            serde_json::from_str(&serde_json::to_string(&policy).unwrap()).unwrap();
        assert_eq!(policy, restored);
        assert_eq!(policy.cache_scope(), restored.cache_scope());
        assert_ne!(
            policy.cache_scope(),
            NetworkPolicy::new(vec![]).unwrap().cache_scope()
        );
    }
}
