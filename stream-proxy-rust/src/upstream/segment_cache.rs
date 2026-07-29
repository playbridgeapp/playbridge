//! Bounded origin segment cache with per-key request coalescing.
//!
//! Caches completed media/init segment bodies (not playlists, DRM keys, or
//! auth endpoints). Concurrent fetches for the same key share one upstream
//! download. Foreground misses **stream immediately** (tee); cache fill happens
//! as bytes pass through. Cache keys are never logged (may embed tokens).

use super::UpstreamResponse;
use axum::body::Body;
use axum::http::{HeaderMap, StatusCode};
use bytes::{Bytes, BytesMut};
use dashmap::DashMap;
use http_body_util::BodyExt;
use std::collections::{HashMap, VecDeque};
use std::hash::{Hash, Hasher};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};
use tokio::sync::{mpsc, Mutex, Notify};
use tokio_stream::wrappers::ReceiverStream;

/// Default total cache budget (phone-friendly).
pub const DEFAULT_MAX_TOTAL_BYTES: usize = 48 * 1024 * 1024;
/// Max size of a single cached response body.
pub const DEFAULT_MAX_ENTRY_BYTES: usize = 12 * 1024 * 1024;
/// How long a completed segment stays reusable when origin does not specify max-age.
pub const DEFAULT_TTL: Duration = Duration::from_secs(90);
/// Tee channel depth (chunks in flight toward the client).
const TEE_QUEUE_CHUNKS: usize = 16;

#[derive(Clone)]
struct CachedBody {
    status: StatusCode,
    headers: HeaderMap,
    body: Bytes,
    inserted_at: Instant,
    /// Per-entry TTL (may be shorter than the default when origin sends max-age).
    ttl: Duration,
}

impl CachedBody {
    fn is_fresh(&self) -> bool {
        self.inserted_at.elapsed() < self.ttl
    }

    fn size(&self) -> usize {
        self.body.len()
    }

    fn to_response(&self) -> UpstreamResponse {
        UpstreamResponse {
            status: self.status,
            headers: self.headers.clone(),
            body: Body::from(self.body.clone()),
        }
    }
}

struct CacheInner {
    map: HashMap<u64, CachedBody>,
    /// Oldest keys at the front (LRU eviction order).
    order: VecDeque<u64>,
    total_bytes: usize,
}

/// Origin segment URL (+ optional byte range) for background prefetch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrefetchTarget {
    pub url: String,
    /// Full `bytes=start-end` value for the Range request header.
    pub range: Option<String>,
}

struct Inflight {
    finished: AtomicBool,
    notify: Notify,
}

impl Inflight {
    fn new() -> Self {
        Self {
            finished: AtomicBool::new(false),
            notify: Notify::new(),
        }
    }

    async fn wait(&self) {
        loop {
            if self.finished.load(Ordering::Acquire) {
                return;
            }
            // Create the waiter before rechecking completion so a concurrent
            // finish cannot fall between the state check and registration.
            let notified = self.notify.notified();
            if self.finished.load(Ordering::Acquire) {
                return;
            }
            notified.await;
        }
    }
}

/// Process-wide optional? Prefer per-engine so tests stay isolated.
pub struct SegmentCache {
    inner: Mutex<CacheInner>,
    /// Leaders register here so waiters can await completion without HOL-blocking
    /// the leader's streamed response.
    inflight: DashMap<u64, Arc<Inflight>>,
    max_total_bytes: usize,
    max_entry_bytes: usize,
    default_ttl: Duration,
    hits: AtomicU64,
    misses: AtomicU64,
    stores: AtomicU64,
}

/// Cancellation-safe ownership of an in-flight cache fill.
///
/// The guard starts immediately after leadership is registered and moves into
/// the tee task once response headers arrive. Every cancellation/error path
/// therefore removes the exact slot it owns and wakes its waiters.
struct InflightGuard {
    cache: Arc<SegmentCache>,
    key: u64,
    inflight: Arc<Inflight>,
    armed: bool,
}

impl InflightGuard {
    fn new(cache: Arc<SegmentCache>, key: u64, inflight: Arc<Inflight>) -> Self {
        Self {
            cache,
            key,
            inflight,
            armed: true,
        }
    }

    fn finish(&mut self) {
        if !self.armed {
            return;
        }
        self.armed = false;
        self.inflight.finished.store(true, Ordering::Release);
        self.cache.inflight.remove_if(&self.key, |_, existing| {
            Arc::ptr_eq(existing, &self.inflight)
        });
        self.inflight.notify.notify_waiters();
    }
}

impl Drop for InflightGuard {
    fn drop(&mut self) {
        self.finish();
    }
}

impl Default for SegmentCache {
    fn default() -> Self {
        Self::new(
            DEFAULT_MAX_TOTAL_BYTES,
            DEFAULT_MAX_ENTRY_BYTES,
            DEFAULT_TTL,
        )
    }
}

impl SegmentCache {
    pub fn new(max_total_bytes: usize, max_entry_bytes: usize, default_ttl: Duration) -> Self {
        Self {
            inner: Mutex::new(CacheInner {
                map: HashMap::new(),
                order: VecDeque::new(),
                total_bytes: 0,
            }),
            inflight: DashMap::new(),
            max_total_bytes: max_total_bytes.max(max_entry_bytes.max(1)),
            max_entry_bytes: max_entry_bytes.max(1),
            default_ttl,
            hits: AtomicU64::new(0),
            misses: AtomicU64::new(0),
            stores: AtomicU64::new(0),
        }
    }

    pub fn max_entry_bytes(&self) -> usize {
        self.max_entry_bytes
    }

    pub fn stats(&self) -> (u64, u64, u64) {
        (
            self.hits.load(Ordering::Relaxed),
            self.misses.load(Ordering::Relaxed),
            self.stores.load(Ordering::Relaxed),
        )
    }

    /// True when the URL looks like a finite media/init segment worth caching.
    pub fn is_cacheable_url(url: &str) -> bool {
        let path = url_path_lower(url);
        if path.contains(".m3u8")
            || path.contains(".mpd")
            || path.contains(".m3u")
            || path.ends_with(".key")
            || path.contains("/key")
            || path.contains("license")
            || path.contains("widevine")
            || path.contains("playready")
            || path.contains("fairplay")
        {
            return false;
        }
        if path.ends_with(".ts")
            || path.ends_with(".m4s")
            || path.ends_with(".cmfv")
            || path.ends_with(".cmfa")
            || path.ends_with(".cmft")
            || path.ends_with(".aac")
            || path.contains("segment")
            || path.contains("frag")
            || path.contains("chunk")
            || path.contains("/init")
            || path.contains("init.mp4")
            || path.contains("init.m4s")
            || path.ends_with("init.mp4")
            || path.ends_with("init.m4s")
        {
            return true;
        }
        let lower = url.to_ascii_lowercase();
        lower.contains("format=ts")
            || lower.contains("ext=ts")
            || lower.contains("type=segment")
            || lower.contains("sq=")
    }

    /// Stable key from URL + all forwarded request headers (canonicalized).
    pub fn cache_key(url: &str, headers: &HashMap<String, String>) -> u64 {
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        url.hash(&mut hasher);
        let mut pairs: Vec<(String, &str)> = headers
            .iter()
            .map(|(k, v)| (k.to_ascii_lowercase(), v.as_str()))
            .collect();
        pairs.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(b.1)));
        for (k, v) in pairs {
            k.hash(&mut hasher);
            0u8.hash(&mut hasher);
            v.hash(&mut hasher);
            1u8.hash(&mut hasher);
        }
        hasher.finish()
    }

    /// Get a cached body or fetch from origin.
    ///
    /// **Foreground misses stream immediately** via a bounded tee while bytes
    /// are accumulated for a possible cache store. Concurrent callers for the
    /// same key wait for the leader's fill (or stream their own if the leader
    /// could not store).
    pub async fn get_or_fetch<F, Fut>(
        self: &Arc<Self>,
        url: &str,
        headers: &HashMap<String, String>,
        fetch: F,
    ) -> Result<UpstreamResponse, String>
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Result<UpstreamResponse, String>>,
    {
        if !Self::is_cacheable_url(url) {
            return fetch().await;
        }

        let key = Self::cache_key(url, headers);

        // Fast path + waiter loop for in-flight leaders.
        loop {
            if let Some(hit) = self.lookup(key).await {
                self.hits.fetch_add(1, Ordering::Relaxed);
                return Ok(hit.to_response());
            }

            if let Some(inf) = self
                .inflight
                .get(&key)
                .map(|entry| Arc::clone(entry.value()))
            {
                inf.wait().await;
                // Leader finished (stored or failed) — retry lookup / leadership.
                continue;
            }

            // Try to become the leader for this key.
            let inf = Arc::new(Inflight::new());
            let existing = match self.inflight.entry(key) {
                dashmap::mapref::entry::Entry::Occupied(o) => {
                    // Clone out of the occupied entry so its shard write lock is
                    // dropped before awaiting leader completion.
                    Some(Arc::clone(o.get()))
                }
                dashmap::mapref::entry::Entry::Vacant(v) => {
                    v.insert(Arc::clone(&inf));
                    None
                }
            };
            if let Some(existing) = existing {
                existing.wait().await;
                continue;
            }

            // Leader path.
            self.misses.fetch_add(1, Ordering::Relaxed);
            return self.leader_fetch(key, Arc::clone(&inf), fetch).await;
        }
    }

    async fn leader_fetch<F, Fut>(
        self: &Arc<Self>,
        key: u64,
        inf: Arc<Inflight>,
        fetch: F,
    ) -> Result<UpstreamResponse, String>
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Result<UpstreamResponse, String>>,
    {
        let mut inflight_guard = InflightGuard::new(Arc::clone(self), key, Arc::clone(&inf));
        let resp = fetch().await?;

        if !resp.status.is_success() {
            inflight_guard.finish();
            return Ok(resp);
        }

        if response_disallows_store(&resp.headers) {
            inflight_guard.finish();
            return Ok(resp);
        }

        let entry_ttl = effective_ttl(&resp.headers, self.default_ttl);
        if entry_ttl.is_zero() {
            inflight_guard.finish();
            return Ok(resp);
        }

        // Known oversized: stream origin body without tee/cache work.
        if let Some(cl) = content_length(&resp.headers) {
            if cl > self.max_entry_bytes {
                inflight_guard.finish();
                return Ok(resp);
            }
        }

        // Stream immediately; clear inflight only after tee completes (so waiters
        // see a cache hit when possible).
        Ok(self.stream_and_maybe_store(key, resp, inflight_guard, entry_ttl))
    }

    /// Tee origin → client while optionally filling the cache when the body
    /// completes within the entry budget.
    fn stream_and_maybe_store(
        self: &Arc<Self>,
        key: u64,
        resp: UpstreamResponse,
        inflight_guard: InflightGuard,
        entry_ttl: Duration,
    ) -> UpstreamResponse {
        let status = resp.status;
        let headers = resp.headers;
        let body = resp.body;
        let max_entry = self.max_entry_bytes;
        let expected_length = content_length(&headers);
        let store_headers = filter_stored_headers(&headers);
        let cache = Arc::clone(self);
        let freshness_started = Instant::now();

        let (tx, rx) = mpsc::channel::<Result<Bytes, std::io::Error>>(TEE_QUEUE_CHUNKS);

        tokio::spawn(async move {
            let mut acc = BytesMut::new();
            let mut storeable = true;
            let mut client_connected = true;
            let mut body = body;

            loop {
                match body.frame().await {
                    Some(Ok(frame)) => {
                        let Ok(data) = frame.into_data() else {
                            continue;
                        };
                        if storeable {
                            if acc.len().saturating_add(data.len()) > max_entry {
                                storeable = false;
                                acc.clear();
                            } else {
                                acc.extend_from_slice(&data);
                            }
                        }
                        if client_connected && tx.send(Ok(data)).await.is_err() {
                            client_connected = false;
                            // With no client and no possible cache fill, abort the
                            // origin body rather than draining unused bytes.
                            if !storeable {
                                break;
                            }
                        }
                        if !client_connected && !storeable {
                            break;
                        }
                    }
                    Some(Err(err)) => {
                        storeable = false;
                        acc.clear();
                        if client_connected {
                            let _ = tx.send(Err(std::io::Error::other(err.to_string()))).await;
                        }
                        break;
                    }
                    None => break,
                }
            }
            drop(tx);

            if storeable && expected_length.is_some_and(|expected| expected != acc.len()) {
                storeable = false;
                acc.clear();
            }

            if storeable && !acc.is_empty() {
                // Freshness starts when response headers arrive, not after a
                // potentially slow segment transfer finishes.
                let remaining_ttl = entry_ttl.saturating_sub(freshness_started.elapsed());
                if remaining_ttl.is_zero() {
                    drop(inflight_guard);
                    return;
                }
                let cached = CachedBody {
                    status,
                    headers: store_headers,
                    body: acc.freeze(),
                    inserted_at: Instant::now(),
                    ttl: remaining_ttl,
                };
                cache.insert(key, cached).await;
                cache.stores.fetch_add(1, Ordering::Relaxed);
            }

            // Drop after the store attempt so waiters observe the completed entry.
            drop(inflight_guard);
        });

        UpstreamResponse {
            status,
            headers,
            body: Body::from_stream(ReceiverStream::new(rx)),
        }
    }

    /// Fully buffer a response into the cache (background prefetch path).
    /// Prefer this when the caller will not stream to a player.
    pub async fn fetch_and_store<F, Fut>(
        self: &Arc<Self>,
        url: &str,
        headers: &HashMap<String, String>,
        fetch: F,
    ) -> Result<(), String>
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Result<UpstreamResponse, String>>,
    {
        let resp = self.get_or_fetch(url, headers, fetch).await?;
        // Drain the tee so the producer finishes and may store.
        let _ = axum::body::to_bytes(resp.body, self.max_entry_bytes.saturating_add(1)).await;
        Ok(())
    }

    async fn lookup(&self, key: u64) -> Option<CachedBody> {
        let mut inner = self.inner.lock().await;
        let fresh = match inner.map.get(&key) {
            Some(e) if e.is_fresh() => Some(e.clone()),
            Some(_) => {
                if let Some(old) = inner.map.remove(&key) {
                    inner.total_bytes = inner.total_bytes.saturating_sub(old.size());
                    inner.order.retain(|k| *k != key);
                }
                None
            }
            None => None,
        };
        if fresh.is_some() {
            inner.order.retain(|k| *k != key);
            inner.order.push_back(key);
        }
        fresh
    }

    async fn insert(&self, key: u64, entry: CachedBody) {
        let size = entry.size();
        if size == 0 || size > self.max_entry_bytes {
            return;
        }
        let mut inner = self.inner.lock().await;
        if let Some(old) = inner.map.remove(&key) {
            inner.total_bytes = inner.total_bytes.saturating_sub(old.size());
            inner.order.retain(|k| *k != key);
        }
        while inner.total_bytes + size > self.max_total_bytes {
            let Some(victim) = inner.order.pop_front() else {
                break;
            };
            if let Some(old) = inner.map.remove(&victim) {
                inner.total_bytes = inner.total_bytes.saturating_sub(old.size());
            }
        }
        if inner.total_bytes + size > self.max_total_bytes {
            return;
        }
        inner.total_bytes += size;
        inner.map.insert(key, entry);
        inner.order.push_back(key);
    }
}

fn content_length(headers: &HeaderMap) -> Option<usize> {
    headers
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse().ok())
}

/// Shared-cache store policy (no revalidation support).
fn response_disallows_store(headers: &HeaderMap) -> bool {
    if let Some(cc) = headers
        .get(axum::http::header::CACHE_CONTROL)
        .and_then(|v| v.to_str().ok())
    {
        let lower = cc.to_ascii_lowercase();
        if lower.split(',').any(|d| {
            let d = d.trim();
            d == "no-store"
                || d == "no-cache"
                || d.starts_with("no-cache=")
                || d == "private"
                || d.starts_with("private=")
        }) {
            return true;
        }
    }
    if let Some(vary) = headers
        .get(axum::http::header::VARY)
        .and_then(|v| v.to_str().ok())
    {
        if vary.split(',').any(|p| p.trim() == "*") {
            return true;
        }
    }
    false
}

/// Prefer origin max-age when shorter than the configured default TTL.
fn effective_ttl(headers: &HeaderMap, default: Duration) -> Duration {
    let age = headers
        .get(axum::http::header::AGE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.trim().parse::<u64>().ok())
        .unwrap_or(0);

    if let Some(cc) = headers
        .get(axum::http::header::CACHE_CONTROL)
        .and_then(|v| v.to_str().ok())
    {
        if let Some(secs) = parse_shared_max_age(cc) {
            return default.min(Duration::from_secs(secs.saturating_sub(age)));
        }
    }

    if let Some(expires) = headers
        .get(axum::http::header::EXPIRES)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| httpdate::parse_http_date(v).ok())
    {
        let remaining = headers
            .get(axum::http::header::DATE)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| httpdate::parse_http_date(v).ok())
            .map(|date| {
                expires
                    .duration_since(date)
                    .unwrap_or_default()
                    .saturating_sub(Duration::from_secs(age))
            })
            .unwrap_or_else(|| {
                expires
                    .duration_since(SystemTime::now())
                    .unwrap_or_default()
            });
        return default.min(remaining);
    }

    default
}

fn parse_shared_max_age(cache_control: &str) -> Option<u64> {
    let mut max_age = None;
    let mut shared_max_age = None;
    for directive in cache_control.split(',') {
        let Some((name, value)) = directive.trim().split_once('=') else {
            continue;
        };
        let seconds = value.trim().trim_matches('"').parse::<u64>().ok();
        match name.trim().to_ascii_lowercase().as_str() {
            "max-age" => max_age = seconds,
            "s-maxage" => shared_max_age = seconds,
            _ => {}
        }
    }
    shared_max_age.or(max_age)
}

fn filter_stored_headers(headers: &HeaderMap) -> HeaderMap {
    let mut out = HeaderMap::new();
    for (name, value) in headers.iter() {
        let lower = name.as_str();
        if matches!(
            lower,
            "content-type"
                | "content-length"
                | "content-range"
                | "accept-ranges"
                | "content-encoding"
        ) {
            out.insert(name.clone(), value.clone());
        }
    }
    out
}

fn url_path_lower(url: &str) -> String {
    let without_query = url.split('?').next().unwrap_or(url);
    let path = without_query
        .split("://")
        .nth(1)
        .map(|rest| rest.split_once('/').map(|(_, p)| p).unwrap_or(rest))
        .unwrap_or(without_query);
    path.to_ascii_lowercase()
}

/// Extract absolute media segment targets from an HLS media playlist for prefetch.
///
/// - **VOD** (`#EXT-X-ENDLIST`): first `limit` complete segments.
/// - **Live** (no endlist): last `limit` complete segments (near the live edge).
/// - Attaches `Range` for `#EXT-X-BYTERANGE` segments.
pub fn hls_media_segment_urls(
    content: &str,
    base_uri: &url::Url,
    limit: usize,
) -> Vec<PrefetchTarget> {
    if limit == 0 {
        return Vec::new();
    }

    let mut is_vod = false;
    let mut pending_byterange: Option<(u64, Option<u64>)> = None;
    let mut next_byte_offset: u64 = 0;
    let mut segments: Vec<PrefetchTarget> = Vec::new();

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if trimmed.starts_with('#') {
            let upper = trimmed.to_ascii_uppercase();
            if upper.starts_with("#EXT-X-ENDLIST") {
                is_vod = true;
            } else if upper.starts_with("#EXT-X-BYTERANGE:") {
                let value = trimmed.split_once(':').map(|(_, v)| v.trim()).unwrap_or("");
                pending_byterange = parse_ext_x_byterange(value);
            } else if upper.starts_with("#EXT-X-MAP:") {
                pending_byterange = None;
            }
            continue;
        }

        let Ok(resolved) = base_uri.join(trimmed) else {
            pending_byterange = None;
            continue;
        };
        let url = resolved.as_str().to_string();
        if !SegmentCache::is_cacheable_url(&url) {
            pending_byterange = None;
            continue;
        }

        let range = if let Some((length, offset_opt)) = pending_byterange.take() {
            let start = offset_opt.unwrap_or(next_byte_offset);
            let end = start.saturating_add(length).saturating_sub(1);
            next_byte_offset = end.saturating_add(1);
            Some(format!("bytes={start}-{end}"))
        } else {
            next_byte_offset = 0;
            None
        };

        segments.push(PrefetchTarget { url, range });
    }

    if segments.is_empty() {
        return segments;
    }

    if is_vod {
        segments.into_iter().take(limit).collect()
    } else {
        let skip = segments.len().saturating_sub(limit);
        segments.into_iter().skip(skip).collect()
    }
}

fn parse_ext_x_byterange(value: &str) -> Option<(u64, Option<u64>)> {
    let value = value.trim();
    if value.is_empty() {
        return None;
    }
    if let Some((len_s, off_s)) = value.split_once('@') {
        let len = len_s.trim().parse().ok()?;
        let off = off_s.trim().parse().ok()?;
        Some((len, Some(off)))
    } else {
        let len = value.parse().ok()?;
        Some((len, None))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};
    use std::time::Duration;

    fn cl_headers(n: usize) -> HeaderMap {
        let mut h = HeaderMap::new();
        h.insert(
            axum::http::header::CONTENT_LENGTH,
            axum::http::HeaderValue::from_str(&n.to_string()).unwrap(),
        );
        h
    }

    #[test]
    fn cacheable_detection() {
        assert!(SegmentCache::is_cacheable_url(
            "https://cdn.example/live/seg001.ts"
        ));
        assert!(!SegmentCache::is_cacheable_url(
            "https://cdn.example/movies/big-film.mp4"
        ));
        assert!(!SegmentCache::is_cacheable_url(
            "https://cdn.example/live/index.m3u8"
        ));
    }

    #[test]
    fn cache_key_includes_all_headers() {
        let mut h1 = HashMap::new();
        h1.insert("Range".into(), "bytes=0-99".into());
        h1.insert("Referer".into(), "https://a.example/".into());
        let mut h2 = HashMap::new();
        h2.insert("Range".into(), "bytes=0-99".into());
        h2.insert("Referer".into(), "https://b.example/".into());
        let url = "https://cdn.example/seg.ts";
        assert_ne!(
            SegmentCache::cache_key(url, &h1),
            SegmentCache::cache_key(url, &h2)
        );
    }

    #[tokio::test]
    async fn miss_streams_before_origin_completes() {
        let cache = Arc::new(SegmentCache::new(
            1024 * 1024,
            64 * 1024,
            Duration::from_secs(60),
        ));
        let (block_tx, block_rx) = tokio::sync::oneshot::channel::<()>();
        let url = "https://cdn.example/slow.ts";
        let headers = HashMap::new();

        let fetch = {
            let block_rx = block_rx;
            async move {
                // Body yields first chunk immediately, second after signal.
                let (tx, rx) = mpsc::channel::<Result<Bytes, std::io::Error>>(4);
                tokio::spawn(async move {
                    let _ = tx.send(Ok(Bytes::from_static(b"first"))).await;
                    let _ = block_rx.await;
                    let _ = tx.send(Ok(Bytes::from_static(b"second"))).await;
                });
                let mut h = cl_headers(11);
                // Lie about length slightly — tee still streams.
                h.insert(
                    axum::http::header::CONTENT_LENGTH,
                    axum::http::HeaderValue::from_static("11"),
                );
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: h,
                    body: Body::from_stream(ReceiverStream::new(rx)),
                })
            }
        };

        let resp = cache.get_or_fetch(url, &headers, || fetch).await.unwrap();
        // Must return before origin finishes (block_rx not yet sent).
        let mut body = resp.body;
        let first_frame = body.frame().await.unwrap().unwrap();
        let first = first_frame.into_data().unwrap();
        assert_eq!(&first[..], b"first");

        // Unblock origin and drain.
        let _ = block_tx.send(());
        let second_frame = body.frame().await.unwrap().unwrap();
        let second = second_frame.into_data().unwrap();
        assert_eq!(&second[..], b"second");
        while body.frame().await.is_some() {}
        tokio::time::sleep(Duration::from_millis(50)).await;
    }

    #[tokio::test]
    async fn completion_before_wait_registration_is_not_lost() {
        let inflight = Arc::new(Inflight::new());
        inflight.finished.store(true, Ordering::Release);
        inflight.notify.notify_waiters();

        tokio::time::timeout(Duration::from_millis(100), inflight.wait())
            .await
            .expect("completed in-flight wait must return immediately");
    }

    #[tokio::test]
    async fn cancelled_leader_releases_inflight_slot() {
        let cache = Arc::new(SegmentCache::new(
            1024 * 1024,
            64 * 1024,
            Duration::from_secs(60),
        ));
        let url = "https://cdn.example/cancelled.ts";
        let (started_tx, started_rx) = tokio::sync::oneshot::channel::<()>();

        let leader_cache = Arc::clone(&cache);
        let leader = tokio::spawn(async move {
            let headers = HashMap::new();
            leader_cache
                .get_or_fetch(url, &headers, || async move {
                    let _ = started_tx.send(());
                    std::future::pending::<Result<UpstreamResponse, String>>().await
                })
                .await
        });

        started_rx.await.expect("leader fetch started");
        leader.abort();
        let _ = leader.await;
        assert!(
            cache.inflight.is_empty(),
            "cancelled leader must release its in-flight slot"
        );

        let response = tokio::time::timeout(
            Duration::from_secs(1),
            cache.get_or_fetch(url, &HashMap::new(), || async {
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: cl_headers(2),
                    body: Body::from(Bytes::from_static(b"ok")),
                })
            }),
        )
        .await
        .expect("replacement fetch must not hang")
        .expect("replacement fetch");
        let body = axum::body::to_bytes(response.body, 16).await.unwrap();
        assert_eq!(&body[..], b"ok");
    }

    #[tokio::test]
    async fn coalesces_and_hits_cache() {
        let cache = Arc::new(SegmentCache::new(
            1024 * 1024,
            64 * 1024,
            Duration::from_secs(60),
        ));
        let fetches = Arc::new(AtomicUsize::new(0));
        let url = "https://cdn.example/seg.ts";
        let headers = HashMap::new();
        let payload = b"segment-bytes";

        let mk = |fetches: Arc<AtomicUsize>, label: &'static [u8]| {
            let fetches = Arc::clone(&fetches);
            async move {
                fetches.fetch_add(1, AtomicOrdering::SeqCst);
                tokio::time::sleep(Duration::from_millis(40)).await;
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: cl_headers(label.len()),
                    body: Body::from(Bytes::from_static(label)),
                })
            }
        };

        let a = {
            let cache = Arc::clone(&cache);
            let fetches = Arc::clone(&fetches);
            let headers = headers.clone();
            async move {
                cache
                    .get_or_fetch(url, &headers, || mk(fetches, payload))
                    .await
            }
        };
        let b = {
            let cache = Arc::clone(&cache);
            let fetches = Arc::clone(&fetches);
            let headers = headers.clone();
            async move {
                // Small delay so a becomes leader.
                tokio::time::sleep(Duration::from_millis(5)).await;
                cache
                    .get_or_fetch(url, &headers, || mk(fetches, b"other--------"))
                    .await
            }
        };

        let (ra, rb) = tokio::join!(a, b);
        let ba = axum::body::to_bytes(ra.unwrap().body, 1024).await.unwrap();
        let bb = axum::body::to_bytes(rb.unwrap().body, 1024).await.unwrap();
        // Drain may race store; wait for leader store.
        tokio::time::sleep(Duration::from_millis(80)).await;

        // One of them is the leader stream; the waiter may have waited and hit
        // cache or also streamed if store lagged — at most one origin fetch when
        // waiter arrives after store, or 1 while leader in-flight.
        assert_eq!(fetches.load(AtomicOrdering::SeqCst), 1);
        assert_eq!(&ba[..], payload);
        // Waiter after notify should get cached copy of leader body.
        assert_eq!(&bb[..], payload);

        let rc = cache
            .get_or_fetch(url, &headers, || mk(Arc::clone(&fetches), b"nope---------"))
            .await
            .unwrap();
        let bc = axum::body::to_bytes(rc.body, 1024).await.unwrap();
        assert_eq!(&bc[..], payload);
        assert_eq!(fetches.load(AtomicOrdering::SeqCst), 1);
    }

    #[tokio::test]
    async fn unknown_length_streams_and_may_store_if_small() {
        let cache = Arc::new(SegmentCache::new(
            1024 * 1024,
            64 * 1024,
            Duration::from_secs(60),
        ));
        let url = "https://cdn.example/seg.ts";
        let resp = cache
            .get_or_fetch(url, &HashMap::new(), || async {
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: HeaderMap::new(),
                    body: Body::from(Bytes::from_static(b"tiny")),
                })
            })
            .await
            .unwrap();
        let bytes = axum::body::to_bytes(resp.body, 1024).await.unwrap();
        assert_eq!(&bytes[..], b"tiny");
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(cache.stats().2, 1);
    }

    #[tokio::test]
    async fn known_oversized_streams_without_store() {
        let cache = Arc::new(SegmentCache::new(1024 * 1024, 64, Duration::from_secs(60)));
        let url = "https://cdn.example/bigseg.ts";
        let resp = cache
            .get_or_fetch(url, &HashMap::new(), || async {
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: cl_headers(200),
                    body: Body::from(vec![b'y'; 200]),
                })
            })
            .await
            .unwrap();
        let bytes = axum::body::to_bytes(resp.body, 1024).await.unwrap();
        assert_eq!(bytes.len(), 200);
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(cache.stats().2, 0);
    }

    #[tokio::test]
    async fn disconnected_client_does_not_cache_body_that_later_errors() {
        let cache = Arc::new(SegmentCache::new(
            1024 * 1024,
            64 * 1024,
            Duration::from_secs(60),
        ));
        let url = "https://cdn.example/flaky.ts";
        let (release_tx, release_rx) = tokio::sync::oneshot::channel::<()>();

        let response = cache
            .get_or_fetch(url, &HashMap::new(), || async move {
                let (tx, rx) = mpsc::channel::<Result<Bytes, std::io::Error>>(2);
                tokio::spawn(async move {
                    let _ = tx.send(Ok(Bytes::from_static(b"part"))).await;
                    let _ = release_rx.await;
                    let _ = tx.send(Err(std::io::Error::other("origin failed"))).await;
                });
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: cl_headers(8),
                    body: Body::from_stream(ReceiverStream::new(rx)),
                })
            })
            .await
            .unwrap();

        let mut body = response.body;
        let first = body
            .frame()
            .await
            .expect("first frame")
            .expect("first frame data")
            .into_data()
            .expect("data frame");
        assert_eq!(&first[..], b"part");
        drop(body);
        let _ = release_tx.send(());

        tokio::time::timeout(Duration::from_secs(1), async {
            while !cache.inflight.is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("tee cleanup");
        assert_eq!(cache.stats().2, 0);

        let fresh = cache
            .get_or_fetch(url, &HashMap::new(), || async {
                Ok(UpstreamResponse {
                    status: StatusCode::OK,
                    headers: cl_headers(5),
                    body: Body::from(Bytes::from_static(b"fresh")),
                })
            })
            .await
            .unwrap();
        let fresh_body = axum::body::to_bytes(fresh.body, 16).await.unwrap();
        assert_eq!(&fresh_body[..], b"fresh");
    }

    #[tokio::test]
    async fn content_length_mismatch_is_not_cached() {
        let cache = Arc::new(SegmentCache::new(
            1024 * 1024,
            64 * 1024,
            Duration::from_secs(60),
        ));
        let response = cache
            .get_or_fetch(
                "https://cdn.example/truncated.ts",
                &HashMap::new(),
                || async {
                    Ok(UpstreamResponse {
                        status: StatusCode::OK,
                        headers: cl_headers(10),
                        body: Body::from(Bytes::from_static(b"short")),
                    })
                },
            )
            .await
            .unwrap();
        let body = axum::body::to_bytes(response.body, 16).await.unwrap();
        assert_eq!(&body[..], b"short");

        tokio::time::timeout(Duration::from_secs(1), async {
            while !cache.inflight.is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("tee cleanup");
        assert_eq!(cache.stats().2, 0);
    }

    #[tokio::test]
    async fn honors_cache_control_no_store() {
        let cache = Arc::new(SegmentCache::new(
            1024 * 1024,
            64 * 1024,
            Duration::from_secs(60),
        ));
        let url = "https://cdn.example/seg.ts";
        let mut h = cl_headers(4);
        h.insert(
            axum::http::header::CACHE_CONTROL,
            axum::http::HeaderValue::from_static("no-store"),
        );
        let resp = cache
            .get_or_fetch(url, &HashMap::new(), || {
                let h = h.clone();
                async move {
                    Ok(UpstreamResponse {
                        status: StatusCode::OK,
                        headers: h,
                        body: Body::from(Bytes::from_static(b"data")),
                    })
                }
            })
            .await
            .unwrap();
        let _ = axum::body::to_bytes(resp.body, 64).await.unwrap();
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(cache.stats().2, 0);
    }

    #[test]
    fn honors_no_cache_and_origin_freshness() {
        let mut no_cache = HeaderMap::new();
        no_cache.insert(
            axum::http::header::CACHE_CONTROL,
            axum::http::HeaderValue::from_static("no-cache"),
        );
        assert!(response_disallows_store(&no_cache));

        let mut max_age = HeaderMap::new();
        max_age.insert(
            axum::http::header::CACHE_CONTROL,
            axum::http::HeaderValue::from_static("max-age=10"),
        );
        let ttl = effective_ttl(&max_age, Duration::from_secs(90));
        assert_eq!(ttl, Duration::from_secs(10));

        let mut shared_age = HeaderMap::new();
        shared_age.insert(
            axum::http::header::CACHE_CONTROL,
            axum::http::HeaderValue::from_static("max-age=120, s-maxage=30"),
        );
        shared_age.insert(
            axum::http::header::AGE,
            axum::http::HeaderValue::from_static("20"),
        );
        assert_eq!(
            effective_ttl(&shared_age, Duration::from_secs(90)),
            Duration::from_secs(10)
        );

        let mut expires = HeaderMap::new();
        expires.insert(
            axum::http::header::DATE,
            axum::http::HeaderValue::from_static("Wed, 21 Oct 2015 07:28:00 GMT"),
        );
        expires.insert(
            axum::http::header::EXPIRES,
            axum::http::HeaderValue::from_static("Wed, 21 Oct 2015 07:29:00 GMT"),
        );
        expires.insert(
            axum::http::header::AGE,
            axum::http::HeaderValue::from_static("50"),
        );
        assert_eq!(
            effective_ttl(&expires, Duration::from_secs(90)),
            Duration::from_secs(10)
        );
    }

    #[tokio::test]
    async fn does_not_cache_playlists_path() {
        let cache = Arc::new(SegmentCache::default());
        let fetches = Arc::new(AtomicUsize::new(0));
        let url = "https://cdn.example/index.m3u8";
        for _ in 0..2 {
            let f = Arc::clone(&fetches);
            let _ = cache
                .get_or_fetch(url, &HashMap::new(), || {
                    let f = Arc::clone(&f);
                    async move {
                        f.fetch_add(1, AtomicOrdering::SeqCst);
                        Ok(UpstreamResponse {
                            status: StatusCode::OK,
                            headers: HeaderMap::new(),
                            body: Body::from(Bytes::from_static(b"#EXTM3U")),
                        })
                    }
                })
                .await
                .unwrap();
        }
        assert_eq!(fetches.load(AtomicOrdering::SeqCst), 2);
    }

    #[test]
    fn hls_vod_takes_first_segments() {
        let content = r#"#EXTM3U
#EXTINF:6.0,
seg0.ts
#EXTINF:6.0,
seg1.ts
#EXTINF:6.0,
seg2.ts
#EXT-X-ENDLIST
"#;
        let base = url::Url::parse("https://cdn.example/live/index.m3u8").unwrap();
        let segs = hls_media_segment_urls(content, &base, 2);
        assert_eq!(segs.len(), 2);
        assert!(segs[0].url.ends_with("/live/seg0.ts"));
    }

    #[test]
    fn hls_live_takes_tail_segments() {
        let content = r#"#EXTM3U
#EXTINF:6.0,
seg0.ts
#EXTINF:6.0,
seg1.ts
#EXTINF:6.0,
seg2.ts
#EXTINF:6.0,
seg3.ts
"#;
        let base = url::Url::parse("https://cdn.example/live/index.m3u8").unwrap();
        let segs = hls_media_segment_urls(content, &base, 2);
        assert!(segs[0].url.ends_with("seg2.ts"));
        assert!(segs[1].url.ends_with("seg3.ts"));
    }

    #[test]
    fn hls_byterange_attaches_range_header() {
        let content = r#"#EXTM3U
#EXTINF:6.0,
#EXT-X-BYTERANGE:1000@0
media.m4s
#EXTINF:6.0,
#EXT-X-BYTERANGE:500
media.m4s
#EXT-X-ENDLIST
"#;
        let base = url::Url::parse("https://cdn.example/vod/index.m3u8").unwrap();
        let segs = hls_media_segment_urls(content, &base, 10);
        assert_eq!(segs[0].range.as_deref(), Some("bytes=0-999"));
        assert_eq!(segs[1].range.as_deref(), Some("bytes=1000-1499"));
    }
}
