//! Origin (upstream) fetch abstraction.
//!
//! Handlers call [`ConnectionEngine`]; the concrete transport is selected at
//! build time via Cargo features:
//! - `upstream-reqwest` (+ optional `upstream-avio`): Docker / Desktop / CLI
//! - `upstream-jni`: Android host HttpURLConnection (see `upstream_jni`)

use axum::body::Body;
use axum::http::{HeaderMap, StatusCode};
use bytes::Bytes;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::pin::Pin;
use std::sync::Arc;
use tracing::debug;

#[cfg(feature = "upstream-reqwest")]
mod reqwest_fetcher;

#[cfg(feature = "upstream-jni")]
pub mod jni_fetcher;

#[cfg(feature = "upstream-jni")]
mod pinned_proxy;
pub mod segment_cache;

pub use segment_cache::{hls_media_segment_urls, PrefetchTarget, SegmentCache};

/// Request-scoped network authority. Page grants are exact origins; native/admin
/// local registrations may trust one LAN host, but loopback is exact-origin.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct NetworkPolicy {
    allowed_private_origins: HashSet<String>,
    /// Native/admin registration of a local server. LAN grants are host-level
    /// (same-host port redirects). Loopback grants are the exact origin only.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    trusted_origin: Option<String>,
    #[serde(default)]
    registered_media: bool,
    /// Set when the trusted origin is loopback, or native DNS resolved only loopback.
    #[serde(default)]
    trusted_allows_loopback: bool,
}

impl NetworkPolicy {
    pub const MAX_PRIVATE_ORIGINS: usize = 16;

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
        })
    }

    /// HTTP/admin and legacy synchronous registrations classify only URL text.
    pub(crate) fn for_registered_media(value: &str) -> Result<Self, String> {
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
        })
    }

    /// Native async registration only: never HTTP `/register`, EPG or page grants.
    /// Classify the first DNS answer set once; connection-time checks still apply.
    pub(crate) async fn for_native_registered_media(value: &str) -> Result<Self, String> {
        let mut policy = Self::for_registered_media(value)?;
        let url = url::Url::parse(value).map_err(|_| "invalid media URL")?;
        let host = url.host_str().ok_or("media URL has no host")?;
        if host.trim_matches(['[', ']']).parse::<IpAddr>().is_ok() {
            return Ok(policy);
        }
        let port = url.port_or_known_default().ok_or("media URL has no port")?;
        // No blocking resolver on Tokio executor threads. Slow/missing DNS leaves
        // the restrictive text policy intact rather than granting LAN access.
        if let Ok(Ok(addresses)) = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            tokio::net::lookup_host((host, port)),
        )
        .await
        {
            policy.approve_native_addresses(&url, &addresses.collect::<Vec<_>>())?;
        }
        Ok(policy)
    }

    fn approve_native_addresses(
        &mut self,
        url: &url::Url,
        addresses: &[SocketAddr],
    ) -> Result<(), String> {
        if !addresses.is_empty()
            && addresses.iter().all(|address| {
                address.ip().is_loopback()
                    || classify_address(address.ip()) == AddressClass::PrivateLan
            })
        {
            let origin = normalized_origin(url)?;
            self.allowed_private_origins.insert(origin.clone());
            self.trusted_origin = Some(origin);
            self.trusted_allows_loopback =
                addresses.iter().any(|address| address.ip().is_loopback());
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
        if self.trusted_allows_loopback || origin_host_is_loopback(&original) {
            normalized_origin(url).is_ok_and(|value| value == origin)
        } else {
            original.host_str().is_some_and(|host| {
                url.host_str()
                    .is_some_and(|other| host.eq_ignore_ascii_case(other))
            })
        }
    }

    fn allows_loopback(&self, url: &url::Url) -> bool {
        self.trusted_allows_loopback && self.allows_trusted_origin(url)
    }

    fn cache_scope(&self) -> String {
        let mut origins: Vec<_> = self.allowed_private_origins.iter().collect();
        origins.sort();
        serde_json::to_string(&(origins, &self.trusted_origin, self.trusted_allows_loopback))
            .expect("string serialization")
    }

    fn allows_private_url(&self, url: &url::Url) -> bool {
        normalized_origin(url)
            .map(|origin| {
                self.allowed_private_origins.contains(&origin) || self.allows_trusted_origin(url)
            })
            .unwrap_or(false)
    }
}

fn normalized_origin(url: &url::Url) -> Result<String, String> {
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

#[cfg(test)]
mod capability_policy_tests {
    use super::*;
    #[tokio::test]
    async fn public_registration_cannot_rebind_or_redirect_to_internal_destinations() {
        let policy = NetworkPolicy::for_registered_media("https://cdn.example/video.mp4").unwrap();
        for target in [
            "http://127.0.0.1/private",
            "http://192.168.1.10/private",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/private",
        ] {
            assert!(validate_http_destination(target, Some(&policy))
                .await
                .is_err());
        }
    }
    #[tokio::test]
    async fn native_selected_local_device_is_not_a_grant_to_every_device() {
        let policy =
            NetworkPolicy::for_registered_media("http://192.168.1.10:8080/video.mp4").unwrap();
        assert!(
            validate_http_destination("http://192.168.1.10:9000/segment", Some(&policy))
                .await
                .is_ok()
        );
        assert!(
            validate_http_destination("http://192.168.1.11/segment", Some(&policy))
                .await
                .is_err()
        );
        assert!(
            validate_http_destination("http://127.0.0.1/segment", Some(&policy))
                .await
                .is_err()
        );
    }
    #[tokio::test]
    async fn loopback_native_grant_is_exact_origin_not_every_local_port() {
        let policy = NetworkPolicy::for_native_registered_media("http://127.0.0.1:8080/video.mp4")
            .await
            .unwrap();
        assert!(
            validate_http_destination("http://127.0.0.1:8080/segment", Some(&policy))
                .await
                .is_ok()
        );
        assert!(
            validate_http_destination("http://127.0.0.1:6379/segment", Some(&policy))
                .await
                .is_err()
        );
    }
    #[tokio::test]
    async fn cgnat_requires_an_origin_grant_and_is_not_forbidden() {
        let granted = NetworkPolicy::new(vec!["http://100.64.0.1".into()]).unwrap();
        assert!(
            validate_http_destination("http://100.64.0.1/media", Some(&granted))
                .await
                .is_ok()
        );
        let public = NetworkPolicy::for_registered_media("https://cdn.example/video.mp4").unwrap();
        assert!(
            validate_http_destination("http://100.64.0.1/media", Some(&public))
                .await
                .is_err()
        );
    }
    #[test]
    fn http_registration_does_not_treat_public_hostnames_as_lan() {
        let policy =
            NetworkPolicy::for_registered_media("https://nas.example.com/video.mp4").unwrap();
        assert!(policy.trusted_origin.is_none());
        assert!(policy.allowed_private_origins.is_empty());
    }
    #[test]
    fn native_dns_classification_is_explicit_bounded_and_origin_scoped() {
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
            assert!(native.allows_private_url(
                &url::Url::parse(&format!("http://{name}:9000/segment")).unwrap()
            ));
            assert!(!native
                .allows_private_url(&url::Url::parse("http://other.example.com/media").unwrap()));
            assert!(!native.allows_loopback(&url));
            for addresses in [
                vec![],
                vec!["8.8.8.8:443".parse().unwrap()],
                vec![
                    "192.168.1.20:443".parse().unwrap(),
                    "8.8.8.8:443".parse().unwrap(),
                ],
                vec!["169.254.169.254:443".parse().unwrap()],
            ] {
                let mut denied = restricted.clone();
                denied.approve_native_addresses(&url, &addresses).unwrap();
                assert!(denied.trusted_origin.is_none());
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

    #[tokio::test]
    async fn native_async_lookup_path_preserves_loopback_scope() {
        let policy = NetworkPolicy::for_native_registered_media("http://localhost:8080/video.mp4")
            .await
            .unwrap();
        assert!(policy.allows_loopback(&url::Url::parse("http://localhost:8080/segment").unwrap()));
        assert!(!policy.allows_loopback(&url::Url::parse("http://localhost:6379/segment").unwrap()));
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

pub(crate) const EFFECTIVE_URL_HEADER: &str = "x-playbridge-internal-effective-url";

pub struct UpstreamResponse {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Body,
}

/// Boxed async result so feature-gated implementors need no `async_trait` dep.
pub type UpstreamConnectFuture<'a> =
    Pin<Box<dyn Future<Output = Result<UpstreamResponse, String>> + Send + 'a>>;

/// Platform-specific origin HTTP client.
pub trait UpstreamFetcher: Send + Sync {
    fn connect<'a>(
        &'a self,
        url: &'a str,
        headers: &'a HashMap<String, String>,
    ) -> UpstreamConnectFuture<'a> {
        self.connect_with_policy(url, headers, None)
    }

    fn connect_with_policy<'a>(
        &'a self,
        url: &'a str,
        headers: &'a HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
    ) -> UpstreamConnectFuture<'a>;
}

/// Browser-like UA used when the cast session did not capture one. Many live CDNs
/// reject clients with no UA; embedded phone proxies historically had no AVIO
/// fallback, so a missing UA became a hard failure for MSE players.
pub const DEFAULT_UPSTREAM_UA: &str = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 \
(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

/// How many upcoming media-playlist segments to prefetch after a rewrite.
const HLS_PREFETCH_SEGMENTS: usize = 3;

/// Façade used by Axum handlers; owns the selected [`UpstreamFetcher`].
pub struct ConnectionEngine {
    fetcher: Arc<dyn UpstreamFetcher>,
    cache: Arc<SegmentCache>,
}

impl ConnectionEngine {
    /// Build the default fetcher for this crate's enabled features.
    pub fn new(ffmpeg_path: Option<String>) -> Self {
        Self {
            fetcher: default_upstream_fetcher(ffmpeg_path),
            cache: Arc::new(SegmentCache::default()),
        }
    }

    /// Inject a custom fetcher (tests, future embedders).
    pub fn with_fetcher(fetcher: Arc<dyn UpstreamFetcher>) -> Self {
        Self {
            fetcher,
            cache: Arc::new(SegmentCache::default()),
        }
    }

    /// Custom fetcher + cache (tests).
    pub fn with_fetcher_and_cache(
        fetcher: Arc<dyn UpstreamFetcher>,
        cache: Arc<SegmentCache>,
    ) -> Self {
        Self { fetcher, cache }
    }

    pub fn fetcher(&self) -> &Arc<dyn UpstreamFetcher> {
        &self.fetcher
    }

    pub fn cache(&self) -> &Arc<SegmentCache> {
        &self.cache
    }

    pub async fn connect_upstream(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<UpstreamResponse, String> {
        self.connect_upstream_with_policy(url, headers, None).await
    }

    pub async fn connect_upstream_with_policy(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
    ) -> Result<UpstreamResponse, String> {
        let headers = with_default_upstream_headers(headers);
        let fetcher = Arc::clone(&self.fetcher);
        if network_policy
            .as_ref()
            .is_some_and(|policy| !policy.registered_media)
        {
            return fetcher
                .connect_with_policy(url, &headers, network_policy)
                .await;
        }
        validate_http_destination(url, network_policy.as_ref()).await?;
        let url_owned = url.to_owned();
        let headers_for_fetch = headers.clone();
        let mut cache_headers = headers.clone();
        if let Some(policy) = network_policy.as_ref() {
            cache_headers.insert(
                "x-playbridge-internal-cache-policy".into(),
                policy.cache_scope(),
            );
        }
        self.cache
            .get_or_fetch(url, &cache_headers, move || {
                let fetcher = fetcher;
                let url_owned = url_owned;
                let headers_for_fetch = headers_for_fetch;
                async move {
                    fetcher
                        .connect_with_policy(&url_owned, &headers_for_fetch, network_policy)
                        .await
                }
            })
            .await
    }

    pub async fn fetch_url_bytes(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> Result<Bytes, String> {
        let resp = self.connect_upstream(url, headers).await?;
        let bytes = axum::body::to_bytes(resp.body, usize::MAX)
            .await
            .map_err(|e| format!("Failed reading bytes: {}", e))?;
        Ok(bytes)
    }

    pub async fn fetch_url_bytes_with_policy(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
    ) -> Result<Bytes, String> {
        let resp = self
            .connect_upstream_with_policy(url, headers, network_policy)
            .await?;
        axum::body::to_bytes(resp.body, usize::MAX)
            .await
            .map_err(|e| format!("Failed reading bytes: {}", e))
    }

    pub(crate) async fn fetch_manifest_with_policy(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
        policy: Option<NetworkPolicy>,
    ) -> Result<(Bytes, String), String> {
        let mut response = self
            .connect_upstream_with_policy(url, headers, policy)
            .await?;
        let effective = response
            .headers
            .remove(EFFECTIVE_URL_HEADER)
            .and_then(|value| value.to_str().ok().map(str::to_owned))
            .unwrap_or_else(|| url.to_owned());
        let bytes = axum::body::to_bytes(response.body, 4 * 1024 * 1024)
            .await
            .map_err(|_| "upstream manifest exceeds limit or could not be read")?;
        Ok((bytes, effective))
    }

    /// Best-effort background prefetch of media segment targets into the cache.
    /// Never logs URLs (may be authenticated). Caps work so playback stays first.
    pub fn prefetch_segment_urls(
        &self,
        targets: Vec<PrefetchTarget>,
        headers: &HashMap<String, String>,
    ) {
        self.prefetch_segment_urls_with_policy(targets, headers, None, "")
    }

    pub fn prefetch_segment_urls_with_policy(
        &self,
        targets: Vec<PrefetchTarget>,
        headers: &HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
        origin_url: &str,
    ) {
        if targets.is_empty() {
            return;
        }
        // Page-controlled traffic must not populate the trusted segment cache.
        if network_policy
            .as_ref()
            .is_some_and(|policy| !policy.registered_media)
        {
            return;
        }
        let engine =
            Self::with_fetcher_and_cache(Arc::clone(&self.fetcher), Arc::clone(&self.cache));
        let base_headers = headers.clone();
        let origin_url = origin_url.to_owned();
        tokio::spawn(async move {
            // Prefetch a few segments sequentially to avoid stampeding the phone radio.
            for target in targets.into_iter().take(HLS_PREFETCH_SEGMENTS) {
                if !SegmentCache::is_cacheable_url(&target.url) {
                    continue;
                }
                if validate_http_destination(&target.url, network_policy.as_ref())
                    .await
                    .is_err()
                {
                    continue;
                }
                let mut headers = if origin_url.is_empty() {
                    base_headers.clone()
                } else {
                    redirect_headers(&base_headers, &origin_url, &target.url)
                };
                if let Some(range) = target.range.as_ref() {
                    headers.insert("Range".to_string(), range.clone());
                }
                if let Ok(response) = engine
                    .connect_upstream_with_policy(&target.url, &headers, network_policy.clone())
                    .await
                {
                    // Drain the tee: only fully consumed responses enter the cache.
                    if axum::body::to_bytes(
                        response.body,
                        engine.cache.max_entry_bytes().saturating_add(1),
                    )
                    .await
                    .is_ok()
                    {
                        debug!("[stream-proxy] completed segment prefetch (url omitted)");
                    }
                }
            }
        });
    }
}

pub async fn validate_http_destination(
    value: &str,
    network_policy: Option<&NetworkPolicy>,
) -> Result<(), String> {
    resolve_destination(value, network_policy).await.map(|_| ())
}

#[cfg(feature = "upstream-jni")]
async fn resolve_checked_destination(
    value: &str,
    policy: &NetworkPolicy,
) -> Result<Vec<std::net::SocketAddr>, String> {
    resolve_destination(value, Some(policy)).await
}

async fn resolve_destination(
    value: &str,
    network_policy: Option<&NetworkPolicy>,
) -> Result<Vec<std::net::SocketAddr>, String> {
    let url = url::Url::parse(value).map_err(|_| "invalid media URL".to_string())?;
    if !matches!(url.scheme(), "http" | "https")
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("only HTTP(S) media URLs without userinfo are allowed".into());
    }
    if network_policy.is_none() {
        return Ok(Vec::new());
    }
    let allow_private_network =
        network_policy.is_some_and(|policy| policy.allows_private_url(&url));
    let host = url
        .host_str()
        .ok_or_else(|| "media URL has no host".to_string())?;
    let lower = host.to_ascii_lowercase();
    let allow_loopback = network_policy.is_some_and(|policy| policy.allows_loopback(&url));
    if !allow_loopback && (lower == "localhost" || lower.ends_with(".localhost")) {
        return Err("media destination is forbidden".into());
    }
    if !allow_private_network && lower.ends_with(".local") {
        return Err("local-network media permission is required".into());
    }
    let port = url
        .port_or_known_default()
        .ok_or_else(|| "media URL has no port".to_string())?;
    let addresses: Vec<_> = tokio::net::lookup_host((host.trim_matches(['[', ']']), port))
        .await
        .map_err(|_| "media host could not be resolved".to_string())?
        .collect();
    if addresses.is_empty()
        || addresses.iter().any(|address| {
            !(is_allowed_address(address.ip(), allow_private_network)
                || allow_loopback && address.ip().is_loopback())
        })
    {
        return Err("local-network media permission is required".into());
    }
    Ok(addresses)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum AddressClass {
    Public,
    PrivateLan,
    Forbidden,
}

fn is_allowed_address(address: IpAddr, allow_private_network: bool) -> bool {
    match classify_address(address) {
        AddressClass::Public => true,
        AddressClass::PrivateLan => allow_private_network,
        AddressClass::Forbidden => false,
    }
}

fn classify_address(address: IpAddr) -> AddressClass {
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

fn origin_host_is_loopback(url: &url::Url) -> bool {
    url.host_str().is_some_and(|host| {
        let host = host.trim_matches(['[', ']']).to_ascii_lowercase();
        host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback())
            || host == "localhost"
            || host.ends_with(".localhost")
    })
}

/// Pick the build-default origin fetcher.
///
/// Preference when multiple features are enabled: **reqwest** (Desktop/Docker
/// default). Android embeds should enable **only** `upstream-jni`.
pub fn default_upstream_fetcher(ffmpeg_path: Option<String>) -> Arc<dyn UpstreamFetcher> {
    #[cfg(all(feature = "upstream-jni", not(feature = "upstream-reqwest")))]
    {
        let _ = ffmpeg_path;
        Arc::new(jni_fetcher::JniUpstreamFetcher::new())
    }

    #[cfg(feature = "upstream-reqwest")]
    {
        Arc::new(reqwest_fetcher::ReqwestUpstreamFetcher::new(ffmpeg_path))
    }

    #[cfg(not(any(feature = "upstream-reqwest", feature = "upstream-jni")))]
    {
        let _ = ffmpeg_path;
        compile_error!(
            "stream-proxy-rust: enable at least one of `upstream-reqwest` or `upstream-jni`"
        );
    }
}

pub fn filter_upstream_headers(
    session_headers: &HashMap<String, String>,
    incoming_headers: &HeaderMap,
    target_url: &str,
    credential_url: &str,
    _session_id: &str,
) -> HashMap<String, String> {
    let mut out = HashMap::new();

    let same_origin = urls_share_origin(target_url, credential_url);
    for (k, v) in session_headers {
        let lower = k.to_ascii_lowercase();
        if should_skip_header(&lower) {
            continue;
        }
        if same_origin || matches!(lower.as_str(), "user-agent" | "accept" | "accept-language") {
            out.insert(k.clone(), v.clone());
        } else if matches!(lower.as_str(), "referer" | "origin") {
            // HLS commonly puts segments on another CDN. Preserve browser
            // context there, but never send URL credentials, paths, or signed
            // queries to another origin (strict-origin cross-origin behavior).
            if let Ok(url) = url::Url::parse(v) {
                if matches!(url.scheme(), "http" | "https") {
                    let origin = url.origin().ascii_serialization();
                    out.insert(
                        k.clone(),
                        if lower == "referer" {
                            format!("{origin}/")
                        } else {
                            origin
                        },
                    );
                }
            }
        }
        // Cookie, Authorization, and unknown custom credentials remain
        // restricted to the original media origin.
    }

    // Byte-range HLS segments and ordinary seeks are both legitimate scoped requests.
    if let Some(range_val) = incoming_headers.get("range").and_then(|v| v.to_str().ok()) {
        out.insert("range".to_string(), range_val.to_string());
    }

    with_default_upstream_headers(&out)
}

/// Reuse the browser-context policy for redirect hops. Preserve byte ranges,
/// but scope credentials to the initial media origin and Referer to its origin.
pub(crate) fn redirect_headers(
    headers: &HashMap<String, String>,
    initial: &str,
    target: &str,
) -> HashMap<String, String> {
    let mut incoming = HeaderMap::new();
    if let Some((_, range)) = headers
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case("range"))
    {
        if let Ok(value) = range.parse() {
            incoming.insert("range", value);
        }
    }
    filter_upstream_headers(headers, &incoming, target, initial, "play")
}

fn urls_share_origin(first: &str, second: &str) -> bool {
    let Ok(first) = url::Url::parse(first) else {
        return false;
    };
    let Ok(second) = url::Url::parse(second) else {
        return false;
    };
    first.scheme() == second.scheme()
        && first.host_str().map(str::to_ascii_lowercase)
            == second.host_str().map(str::to_ascii_lowercase)
        && first.port_or_known_default() == second.port_or_known_default()
}

pub fn with_default_upstream_headers(headers: &HashMap<String, String>) -> HashMap<String, String> {
    let mut out = headers.clone();
    if !out.keys().any(|k| k.eq_ignore_ascii_case("user-agent")) {
        out.insert("User-Agent".to_string(), DEFAULT_UPSTREAM_UA.to_string());
    }
    if !out.keys().any(|k| k.eq_ignore_ascii_case("accept")) {
        out.insert(
            "Accept".to_string(),
            "application/vnd.apple.mpegurl, application/x-mpegURL, application/dash+xml, */*;q=0.8"
                .to_string(),
        );
    }
    out
}

fn should_skip_header(lower_key: &str) -> bool {
    const SKIP: &[&str] = &[
        "host",
        "connection",
        "content-length",
        "accept-encoding",
        "range",
    ];
    SKIP.contains(&lower_key) || lower_key.starts_with(':')
}

#[cfg(test)]
mod policy_tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct CountingFetcher(AtomicUsize);

    impl UpstreamFetcher for CountingFetcher {
        fn connect_with_policy<'a>(
            &'a self,
            _url: &'a str,
            _headers: &'a HashMap<String, String>,
            _network_policy: Option<NetworkPolicy>,
        ) -> UpstreamConnectFuture<'a> {
            self.0.fetch_add(1, Ordering::Relaxed);
            Box::pin(async {
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: HeaderMap::new(),
                    body: Body::from("segment"),
                })
            })
        }
    }

    #[test]
    fn page_headers_are_scoped_to_their_original_media_origin() {
        let headers = HashMap::from([
            ("Authorization".to_string(), "Bearer secret".to_string()),
            ("Cookie".to_string(), "session=secret".to_string()),
        ]);
        let same = filter_upstream_headers(
            &headers,
            &HeaderMap::new(),
            "https://cdn.example/segment.ts",
            "https://cdn.example/master.m3u8",
            "session",
        );
        assert!(same.contains_key("Authorization"));

        let cross = filter_upstream_headers(
            &headers,
            &HeaderMap::new(),
            "https://other.example/segment.ts",
            "https://cdn.example/master.m3u8",
            "session",
        );
        assert!(!cross.contains_key("Authorization"));
        assert!(!cross.contains_key("Cookie"));
    }

    #[test]
    fn cross_cdn_hls_keeps_browser_context_without_credentials() {
        let headers = HashMap::from([
            ("User-Agent".into(), "BrowserFixture".into()),
            (
                "Referer".into(),
                "https://user:secret@page.example/player?token=secret".into(),
            ),
            ("Origin".into(), "https://page.example".into()),
            ("Authorization".into(), "Bearer secret".into()),
            ("Cookie".into(), "session=secret".into()),
            ("X-Api-Key".into(), "secret".into()),
        ]);
        let cross = filter_upstream_headers(
            &headers,
            &HeaderMap::new(),
            "https://segments.example/000.jpg",
            "https://manifest.example/master.m3u8",
            "session",
        );
        assert_eq!(cross.get("User-Agent").unwrap(), "BrowserFixture");
        assert_eq!(cross.get("Referer").unwrap(), "https://page.example/");
        assert_eq!(cross.get("Origin").unwrap(), "https://page.example");
        assert!(!cross.contains_key("Authorization"));
        assert!(!cross.contains_key("Cookie"));
        assert!(!cross.contains_key("X-Api-Key"));
        assert!(cross.values().all(|value| !value.contains("secret")));
    }

    #[tokio::test]
    async fn page_requests_do_not_reuse_the_trusted_segment_cache() {
        let fetcher = Arc::new(CountingFetcher(AtomicUsize::new(0)));
        let engine = ConnectionEngine::with_fetcher(fetcher.clone());
        let headers = HashMap::new();
        let first = engine
            .connect_upstream("https://media.example/segment.ts", &headers)
            .await
            .unwrap();
        axum::body::to_bytes(first.body, usize::MAX).await.unwrap();
        let page = engine
            .connect_upstream_with_policy(
                "https://media.example/segment.ts",
                &headers,
                Some(NetworkPolicy::new(vec![]).unwrap()),
            )
            .await
            .unwrap();
        axum::body::to_bytes(page.body, usize::MAX).await.unwrap();
        assert_eq!(fetcher.0.load(Ordering::Relaxed), 2);
    }

    #[tokio::test]
    async fn registered_media_prefetch_validates_and_fills_the_partitioned_cache() {
        let fetcher = Arc::new(CountingFetcher(AtomicUsize::new(0)));
        let cache = Arc::new(SegmentCache::default());
        let engine = ConnectionEngine::with_fetcher_and_cache(fetcher.clone(), cache.clone());
        let policy = NetworkPolicy::for_registered_media("http://127.0.0.1/master.m3u8").unwrap();
        engine.prefetch_segment_urls_with_policy(
            vec![
                PrefetchTarget {
                    url: "http://127.0.0.1/segment.ts".into(),
                    range: None,
                },
                PrefetchTarget {
                    url: "http://127.0.0.1:6379/segment.ts".into(),
                    range: None,
                },
            ],
            &HashMap::new(),
            Some(policy.clone()),
            "http://127.0.0.1/master.m3u8",
        );
        tokio::time::timeout(std::time::Duration::from_secs(2), async {
            while cache.stats().2 == 0 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(fetcher.0.load(Ordering::Relaxed), 1);
        let cached = engine
            .connect_upstream_with_policy(
                "http://127.0.0.1/segment.ts",
                &HashMap::new(),
                Some(policy),
            )
            .await
            .unwrap();
        axum::body::to_bytes(cached.body, usize::MAX).await.unwrap();
        assert_eq!(fetcher.0.load(Ordering::Relaxed), 1);
    }

    #[tokio::test]
    async fn private_destinations_require_the_exact_origin_grant() {
        let public_only = NetworkPolicy::new(vec![]).unwrap();
        let approved = NetworkPolicy::new(vec!["http://192.168.1.5".into()]).unwrap();
        assert!(NetworkPolicy::new(vec!["http://*.local".into()]).is_err());
        assert!(
            validate_http_destination("http://127.0.0.1/media", Some(&public_only))
                .await
                .is_err()
        );
        assert!(
            validate_http_destination("http://[::1]/media", Some(&approved))
                .await
                .is_err()
        );
        assert!(
            validate_http_destination("http://192.168.1.5/media", Some(&approved))
                .await
                .is_ok()
        );
        assert!(
            validate_http_destination("http://192.168.1.5:8080/media", Some(&approved))
                .await
                .is_err()
        );
        assert!(
            validate_http_destination("http://169.254.169.254/metadata", Some(&approved))
                .await
                .is_err()
        );
    }
}
