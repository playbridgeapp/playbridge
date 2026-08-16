//! Origin (upstream) fetch abstraction.
//!
//! Handlers call [`ConnectionEngine`]; the concrete transport is selected at
//! build time via Cargo features:
//! - `upstream-reqwest` (+ optional `upstream-avio`): Docker / Desktop / CLI
//! - `upstream-jni`: Android host HttpURLConnection (see `upstream_jni`)

use axum::body::Body;
use axum::http::{HeaderMap, StatusCode};
use bytes::Bytes;
use std::collections::HashMap;
use std::future::Future;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::pin::Pin;
use std::sync::Arc;
use tracing::debug;

#[cfg(feature = "upstream-reqwest")]
mod reqwest_fetcher;

#[cfg(feature = "upstream-jni")]
pub mod jni_fetcher;

pub mod segment_cache;

pub use segment_cache::{hls_media_segment_urls, PrefetchTarget, SegmentCache};

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
        network_policy: Option<bool>,
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
        network_policy: Option<bool>,
    ) -> Result<UpstreamResponse, String> {
        let headers = with_default_upstream_headers(headers);
        let fetcher = Arc::clone(&self.fetcher);
        if network_policy == Some(false) {
            return fetcher
                .connect_with_policy(url, &headers, network_policy)
                .await;
        }
        let url_owned = url.to_owned();
        let headers_for_fetch = headers.clone();
        self.cache
            .get_or_fetch(url, &headers, move || {
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
        network_policy: Option<bool>,
    ) -> Result<Bytes, String> {
        let resp = self
            .connect_upstream_with_policy(url, headers, network_policy)
            .await?;
        axum::body::to_bytes(resp.body, usize::MAX)
            .await
            .map_err(|e| format!("Failed reading bytes: {}", e))
    }

    /// Best-effort background prefetch of media segment targets into the cache.
    /// Never logs URLs (may be authenticated). Caps work so playback stays first.
    pub fn prefetch_segment_urls(
        &self,
        targets: Vec<PrefetchTarget>,
        headers: &HashMap<String, String>,
    ) {
        self.prefetch_segment_urls_with_policy(targets, headers, None)
    }

    pub fn prefetch_segment_urls_with_policy(
        &self,
        targets: Vec<PrefetchTarget>,
        headers: &HashMap<String, String>,
        network_policy: Option<bool>,
    ) {
        if targets.is_empty() {
            return;
        }
        let fetcher = Arc::clone(&self.fetcher);
        let cache = Arc::clone(&self.cache);
        let base_headers = with_default_upstream_headers(headers);
        tokio::spawn(async move {
            // Prefetch a few segments sequentially to avoid stampeding the phone radio.
            for target in targets.into_iter().take(HLS_PREFETCH_SEGMENTS) {
                if !SegmentCache::is_cacheable_url(&target.url) {
                    continue;
                }
                let mut headers = base_headers.clone();
                if let Some(range) = target.range.as_ref() {
                    headers.insert("Range".to_string(), range.clone());
                }
                let fetcher = Arc::clone(&fetcher);
                let headers_for_key = headers.clone();
                let headers_for_fetch = headers.clone();
                let url_for_fetch = target.url.clone();
                // Drain the tee fully so the producer can store the segment.
                let result = if network_policy != Some(false) {
                    cache
                        .fetch_and_store(&target.url, &headers_for_key, move || {
                            let fetcher = fetcher;
                            let url_for_fetch = url_for_fetch;
                            let headers_for_fetch = headers_for_fetch;
                            async move {
                                fetcher
                                    .connect_with_policy(
                                        &url_for_fetch,
                                        &headers_for_fetch,
                                        network_policy,
                                    )
                                    .await
                            }
                        })
                        .await
                } else {
                    fetcher
                        .connect_with_policy(&url_for_fetch, &headers_for_fetch, network_policy)
                        .await
                        .map(|_| ())
                };
                if result.is_ok() {
                    debug!("[stream-proxy] prefetched segment into cache (url omitted)");
                }
            }
        });
    }
}

pub async fn validate_http_destination(
    value: &str,
    network_policy: Option<bool>,
) -> Result<(), String> {
    let url = url::Url::parse(value).map_err(|_| "invalid media URL".to_string())?;
    if !matches!(url.scheme(), "http" | "https")
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("only HTTP(S) media URLs without userinfo are allowed".into());
    }
    if network_policy.is_none() {
        return Ok(());
    }
    let allow_private_network = network_policy == Some(true);
    let host = url
        .host_str()
        .ok_or_else(|| "media URL has no host".to_string())?;
    let lower = host.to_ascii_lowercase();
    if lower == "localhost" || lower.ends_with(".localhost") {
        return Err("media destination is forbidden".into());
    }
    if !allow_private_network && lower.ends_with(".local") {
        return Err("local-network media permission is required".into());
    }
    let port = url
        .port_or_known_default()
        .ok_or_else(|| "media URL has no port".to_string())?;
    let addresses: Vec<_> = tokio::net::lookup_host((host, port))
        .await
        .map_err(|_| "media host could not be resolved".to_string())?
        .collect();
    if addresses.is_empty()
        || addresses
            .iter()
            .any(|address| !is_allowed_address(address.ip(), allow_private_network))
    {
        return Err("local-network media permission is required".into());
    }
    Ok(())
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
            {
                AddressClass::PrivateLan
            } else if ip.is_loopback()
                || ip.is_link_local()
                || ip.is_multicast()
                || ip.is_unspecified()
                || first == 0
                || first >= 224
                || (first == 100 && (64..=127).contains(&second))
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
    session_id: &str,
) -> HashMap<String, String> {
    let mut out = HashMap::new();

    if urls_share_origin(target_url, credential_url) {
        for (k, v) in session_headers {
            let lower = k.to_lowercase();
            if !should_skip_header(&lower) {
                out.insert(k.clone(), v.clone());
            }
        }
    }

    let lower_url = target_url.to_lowercase();
    let is_hls_segment = lower_url.contains(".ts") || lower_url.contains(".m4s");
    let should_forward_range = session_id == "play" || !is_hls_segment;

    if should_forward_range {
        if let Some(range_val) = incoming_headers.get("range").and_then(|v| v.to_str().ok()) {
            out.insert("range".to_string(), range_val.to_string());
        }
    }

    with_default_upstream_headers(&out)
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

    #[tokio::test]
    async fn private_destinations_require_the_explicit_grant() {
        assert!(
            validate_http_destination("http://127.0.0.1/media", Some(false))
                .await
                .is_err()
        );
        assert!(validate_http_destination("http://[::1]/media", Some(false))
            .await
            .is_err());
        assert!(
            validate_http_destination("http://192.168.1.5/media", Some(true))
                .await
                .is_ok()
        );
        assert!(
            validate_http_destination("http://127.0.0.1/media", Some(true))
                .await
                .is_err()
        );
        assert!(
            validate_http_destination("http://169.254.169.254/metadata", Some(true))
                .await
                .is_err()
        );
    }
}
