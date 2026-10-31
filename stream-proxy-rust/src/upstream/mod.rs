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
use std::net::IpAddr;
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

pub use playbridge_net_policy::{lan_literal_origin, NetworkPolicy};

/// Upper bound on the registration-time DNS lookup for native LAN approval.
const NATIVE_DNS_APPROVAL_TIMEOUT: std::time::Duration = std::time::Duration::from_millis(1500);

/// Native async registration for registrations the local user started on this
/// device only: never HTTP `/register`, EPG, page grants or remote payloads.
/// Classifies the DNS answer set once and pins the trust to those IPs.
pub(crate) async fn for_native_registered_media(value: &str) -> Result<NetworkPolicy, String> {
    let mut policy = NetworkPolicy::for_registered_media(value)?;
    let url = url::Url::parse(value).map_err(|_| "invalid media URL")?;
    let host = url.host_str().ok_or("media URL has no host")?;
    if host.trim_matches(['[', ']']).parse::<IpAddr>().is_ok() {
        return Ok(policy);
    }
    let port = url.port_or_known_default().ok_or("media URL has no port")?;
    // No blocking resolver on Tokio executor threads, and the caller is the
    // serial FFI command loop, so wait briefly. Slow/missing DNS leaves the
    // restrictive text policy intact rather than granting LAN access.
    if let Ok(Ok(addresses)) = tokio::time::timeout(
        NATIVE_DNS_APPROVAL_TIMEOUT,
        tokio::net::lookup_host((host, port)),
    )
    .await
    {
        policy.approve_native_addresses(&url, &addresses.collect::<Vec<_>>())?;
    }
    Ok(policy)
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
        let policy = for_native_registered_media("http://127.0.0.1:8080/video.mp4")
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
    #[tokio::test]
    async fn dns_rebinding_to_an_unapproved_ip_is_rejected() {
        let url = url::Url::parse("http://localhost:8080/video.mp4").unwrap();
        let mut policy = NetworkPolicy::for_registered_media("http://example.test/").unwrap();
        // Registration approved only 127.0.0.2; `localhost` now answers 127.0.0.1.
        policy
            .approve_native_addresses(&url, &["127.0.0.2:8080".parse().unwrap()])
            .unwrap();
        assert!(policy.allows_loopback(&url));
        assert!(validate_http_destination(url.as_str(), Some(&policy))
            .await
            .is_err());
        // Same hostname on another port is outside the exact-origin grant.
        assert!(
            validate_http_destination("http://localhost:6379/x", Some(&policy))
                .await
                .is_err()
        );
        // The pin accepts the addresses that were actually approved.
        let mut approved = NetworkPolicy::for_registered_media("http://example.test/").unwrap();
        approved
            .approve_native_addresses(
                &url,
                &[
                    "127.0.0.1:8080".parse().unwrap(),
                    "[::1]:8080".parse().unwrap(),
                ],
            )
            .unwrap();
        assert!(validate_http_destination(url.as_str(), Some(&approved))
            .await
            .is_ok());
    }

    #[tokio::test]
    async fn native_async_lookup_path_preserves_loopback_scope() {
        let policy = for_native_registered_media("http://localhost:8080/video.mp4")
            .await
            .unwrap();
        assert!(policy.allows_loopback(&url::Url::parse("http://localhost:8080/segment").unwrap()));
        assert!(!policy.allows_loopback(&url::Url::parse("http://localhost:6379/segment").unwrap()));
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
            .is_some_and(|policy| !policy.is_registered_media())
        {
            return fetcher
                .connect_with_policy(url, &headers, network_policy)
                .await;
        }
        validate_http_destination(url, network_policy.as_ref()).await?;
        let url_owned = url.to_owned();
        let headers_for_fetch = headers.clone();
        let cache_headers = cache_key_headers(&headers, network_policy.as_ref());
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
    ///
    /// `session_headers` are the session's original (unfiltered) headers and
    /// `credential_url` is the original media URL: each target's headers come
    /// from the same per-destination filter the foreground segment request
    /// uses, so prefetch and playback share upstream headers and cache keys.
    pub fn prefetch_segment_urls_with_policy(
        &self,
        targets: Vec<PrefetchTarget>,
        session_headers: &HashMap<String, String>,
        network_policy: Option<NetworkPolicy>,
        credential_url: &str,
    ) {
        if targets.is_empty() {
            return;
        }
        // Page-controlled traffic must not populate the trusted segment cache.
        if network_policy
            .as_ref()
            .is_some_and(|policy| !policy.is_registered_media())
        {
            return;
        }
        let engine =
            Self::with_fetcher_and_cache(Arc::clone(&self.fetcher), Arc::clone(&self.cache));
        let session_headers = session_headers.clone();
        let credential_url = credential_url.to_owned();
        tokio::spawn(async move {
            // Prefetch a few segments sequentially to avoid stampeding the phone radio.
            for target in targets.into_iter().take(HLS_PREFETCH_SEGMENTS) {
                if !SegmentCache::is_cacheable_url(&target.url) {
                    continue;
                }
                let mut incoming = HeaderMap::new();
                if let Some(value) = target.range.as_deref().and_then(|r| r.parse().ok()) {
                    incoming.insert("range", value);
                }
                let headers = filter_upstream_headers(
                    &session_headers,
                    &incoming,
                    &target.url,
                    &credential_url,
                    "prefetch",
                );
                let cache_headers = cache_key_headers(&headers, network_policy.as_ref());
                if engine.cache.contains(&target.url, &cache_headers).await {
                    continue;
                }
                // Validation (policy + DNS) happens once, inside the connect path.
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

/// Headers that identify a cache entry: forwarded headers plus the policy scope.
fn cache_key_headers(
    headers: &HashMap<String, String>,
    network_policy: Option<&NetworkPolicy>,
) -> HashMap<String, String> {
    let mut cache_headers = with_default_upstream_headers(headers);
    if let Some(policy) = network_policy {
        cache_headers.insert(
            "x-playbridge-internal-cache-policy".into(),
            policy.cache_scope(),
        );
    }
    cache_headers
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
    let url = match network_policy {
        Some(policy) => policy.validate_url(value)?,
        None => {
            playbridge_net_policy::parse_http_url(value)?;
            return Ok(Vec::new());
        }
    };
    let host = url.host_str().ok_or("media URL has no host")?;
    let port = url
        .port_or_known_default()
        .ok_or_else(|| "media URL has no port".to_string())?;
    let addresses: Vec<_> = tokio::net::lookup_host((host.trim_matches(['[', ']']), port))
        .await
        .map_err(|_| "media host could not be resolved".to_string())?
        .collect();
    if let Some(policy) = network_policy {
        policy.validate_destination(
            value,
            &addresses
                .iter()
                .map(|address| address.ip())
                .collect::<Vec<_>>(),
        )?;
    }
    Ok(addresses)
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
