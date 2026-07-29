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
        let headers = with_default_upstream_headers(headers);
        let fetcher = Arc::clone(&self.fetcher);
        let url_owned = url.to_owned();
        let headers_for_fetch = headers.clone();
        self.cache
            .get_or_fetch(url, &headers, move || {
                let fetcher = fetcher;
                let url_owned = url_owned;
                let headers_for_fetch = headers_for_fetch;
                async move { fetcher.connect(&url_owned, &headers_for_fetch).await }
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

    /// Best-effort background prefetch of media segment targets into the cache.
    /// Never logs URLs (may be authenticated). Caps work so playback stays first.
    pub fn prefetch_segment_urls(
        &self,
        targets: Vec<PrefetchTarget>,
        headers: &HashMap<String, String>,
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
                let result = cache
                    .fetch_and_store(&target.url, &headers_for_key, move || {
                        let fetcher = fetcher;
                        let url_for_fetch = url_for_fetch;
                        let headers_for_fetch = headers_for_fetch;
                        async move { fetcher.connect(&url_for_fetch, &headers_for_fetch).await }
                    })
                    .await;
                if result.is_ok() {
                    debug!("[stream-proxy] prefetched segment into cache (url omitted)");
                }
            }
        });
    }
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
    session_id: &str,
) -> HashMap<String, String> {
    let mut out = HashMap::new();

    for (k, v) in session_headers {
        let lower = k.to_lowercase();
        if !should_skip_header(&lower) {
            out.insert(k.clone(), v.clone());
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
