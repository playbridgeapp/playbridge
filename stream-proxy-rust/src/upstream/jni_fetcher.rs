//! Host-provided origin fetch (Android JNI → HttpURLConnection).
//!
//! The host registers a small C ABI (`pb_proxy_upstream_*`) that performs
//! blocking HTTP I/O. [`JniUpstreamFetcher`] runs those callbacks on
//! `spawn_blocking` and streams the body into Axum without buffering whole
//! segments in memory.
//!
//! Callback registration uses a short-lived mutex; HTTP open/read/close never
//! holds that lock. Concurrent origin requests are limited by a semaphore so
//! the phone radio is not unbounded.
//!
//! ABI version: see [`UPSTREAM_JNI_ABI_VERSION`].

use super::{validate_http_destination, UpstreamConnectFuture, UpstreamFetcher, UpstreamResponse};
use axum::body::Body;
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode};
use bytes::Bytes;
use std::collections::HashMap;
use std::ffi::{c_char, CStr, CString};
use std::os::raw::c_int;
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};
use tokio::sync::{mpsc, OwnedSemaphorePermit, Semaphore};
use tokio_stream::wrappers::ReceiverStream;
use tracing::warn;

/// Bump when the callback struct layout or contract changes incompatibly.
pub const UPSTREAM_JNI_ABI_VERSION: u32 = 1;

/// Max concurrent origin responses (open→close lifetime) on the host path.
const MAX_CONCURRENT_UPSTREAM: usize = 8;
/// 64 KiB chunks × 16 ≈ 1 MiB queued per active response.
const READ_CHUNK_BYTES: usize = 64 * 1024;
const READ_QUEUE_CHUNKS: usize = 16;

/// Open a remote URL.
///
/// On success: returns handle `> 0`, writes HTTP status to `out_status`, and
/// sets `*out_response_headers_json` to a host-allocated JSON object string
/// (map of header name → value). Caller frees that string with `free_string`.
///
/// On failure: returns `0`, optionally sets `*out_error` to a host-allocated
/// message (freed with `free_string`).
pub type UpstreamOpenFn = unsafe extern "C" fn(
    url: *const c_char,
    request_headers_json: *const c_char,
    out_status: *mut c_int,
    out_response_headers_json: *mut *mut c_char,
    out_error: *mut *mut c_char,
) -> i64;

/// Read up to `len` bytes into `buf`.
///
/// Returns `>0` bytes read, `0` on EOF, `<0` on error (optional `*out_error`).
pub type UpstreamReadFn = unsafe extern "C" fn(
    handle: i64,
    buf: *mut u8,
    len: c_int,
    out_error: *mut *mut c_char,
) -> c_int;

/// Close a handle opened by `open`. Idempotent preferred.
pub type UpstreamCloseFn = unsafe extern "C" fn(handle: i64);

/// Free a string returned by `open` / `read` error paths.
pub type UpstreamFreeStringFn = unsafe extern "C" fn(ptr: *mut c_char);

/// Stable C layout for host registration.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct PbUpstreamCallbacks {
    pub open: UpstreamOpenFn,
    pub read: UpstreamReadFn,
    pub close: UpstreamCloseFn,
    pub free_string: UpstreamFreeStringFn,
}

// SAFETY: Function pointers are treated as immutable once registered; the host
// must keep them valid for the process lifetime (typical for JNI trampolines).
unsafe impl Send for PbUpstreamCallbacks {}
unsafe impl Sync for PbUpstreamCallbacks {}

static CALLBACKS: Mutex<Option<PbUpstreamCallbacks>> = Mutex::new(None);

fn upstream_semaphore() -> Arc<Semaphore> {
    static SEM: OnceLock<Arc<Semaphore>> = OnceLock::new();
    Arc::clone(SEM.get_or_init(|| Arc::new(Semaphore::new(MAX_CONCURRENT_UPSTREAM))))
}

/// Register host upstream I/O callbacks (replaces any previous registration).
pub fn set_upstream_callbacks(callbacks: PbUpstreamCallbacks) {
    *CALLBACKS.lock().expect("upstream callbacks mutex") = Some(callbacks);
}

/// Clear host callbacks (tests / shutdown).
pub fn clear_upstream_callbacks() {
    *CALLBACKS.lock().expect("upstream callbacks mutex") = None;
}

/// True when the host has registered a complete callback set.
pub fn upstream_callbacks_registered() -> bool {
    CALLBACKS
        .lock()
        .expect("upstream callbacks mutex")
        .is_some()
}

/// Copy callbacks under the registration lock, then release it before any I/O.
fn callbacks() -> Result<PbUpstreamCallbacks, String> {
    let guard = CALLBACKS.lock().expect("upstream callbacks mutex");
    guard.as_ref().copied().ok_or_else(|| {
        "JNI upstream callbacks are not registered (host must call \
         pb_proxy_upstream_set_callbacks before fetching remote origins)"
            .to_string()
    })
}

// ---- C ABI -----------------------------------------------------------------

/// Returns the upstream JNI callback ABI version.
#[no_mangle]
pub extern "C" fn pb_proxy_upstream_abi_version() -> u32 {
    UPSTREAM_JNI_ABI_VERSION
}

/// Install host callbacks. See [`PbUpstreamCallbacks`].
///
/// # Safety
///
/// Every callback must follow the documented C ABI contract and remain valid
/// until [`pb_proxy_upstream_clear_callbacks`] is called.
#[no_mangle]
pub unsafe extern "C" fn pb_proxy_upstream_set_callbacks(callbacks: PbUpstreamCallbacks) {
    set_upstream_callbacks(callbacks);
}

/// Remove host callbacks.
#[no_mangle]
pub extern "C" fn pb_proxy_upstream_clear_callbacks() {
    clear_upstream_callbacks();
}

/// `1` if callbacks are installed, else `0`.
#[no_mangle]
pub extern "C" fn pb_proxy_upstream_callbacks_registered() -> c_int {
    if upstream_callbacks_registered() {
        1
    } else {
        0
    }
}

// ---- Fetcher ---------------------------------------------------------------

/// Origin client that delegates to host-registered C callbacks.
pub struct JniUpstreamFetcher;

impl JniUpstreamFetcher {
    pub fn new() -> Self {
        Self
    }
}

impl Default for JniUpstreamFetcher {
    fn default() -> Self {
        Self::new()
    }
}

/// Ensures `close` runs if open succeeds but the result is dropped (e.g. the
/// connection future is cancelled while `spawn_blocking(open)` is still running,
/// or the read task is cancelled mid-stream).
struct HandleGuard {
    handle: i64,
    callbacks: PbUpstreamCallbacks,
    closed: bool,
}

impl HandleGuard {
    fn new(handle: i64, callbacks: PbUpstreamCallbacks) -> Self {
        Self {
            handle,
            callbacks,
            closed: false,
        }
    }

    fn close_now(&mut self) {
        if !self.closed {
            self.closed = true;
            unsafe {
                (self.callbacks.close)(self.handle);
            }
        }
    }
}

impl Drop for HandleGuard {
    fn drop(&mut self) {
        self.close_now();
    }
}

struct OpenedUpstream {
    /// Created immediately after a successful host `open` so discarded join
    /// results still close the Kotlin handle.
    guard: HandleGuard,
    status: StatusCode,
    headers: HeaderMap,
    /// Held until the body is fully read / cancelled so concurrency stays bounded.
    _permit: OwnedSemaphorePermit,
}

impl UpstreamFetcher for JniUpstreamFetcher {
    fn connect_with_policy<'a>(
        &'a self,
        url: &'a str,
        headers: &'a HashMap<String, String>,
        network_policy: Option<bool>,
    ) -> UpstreamConnectFuture<'a> {
        let url = url.to_owned();
        let headers = headers.clone();
        Box::pin(async move {
            validate_http_destination(&url, network_policy).await?;
            connect_via_host(url, headers).await
        })
    }
}

async fn connect_via_host(
    url: String,
    headers: HashMap<String, String>,
) -> Result<UpstreamResponse, String> {
    let headers_json = serde_json::to_string(&headers).map_err(|e| format!("headers json: {e}"))?;

    // Acquire before open so abandoned opens still count toward the limit.
    let permit = upstream_semaphore()
        .acquire_owned()
        .await
        .map_err(|_| "upstream concurrency semaphore closed".to_string())?;

    // If this future is cancelled while open is in-flight, the JoinHandle is
    // dropped; when the blocking task finishes, `OpenedUpstream` is dropped and
    // `HandleGuard` closes the host handle.
    let opened = tokio::task::spawn_blocking(move || open_blocking(&url, &headers_json, permit))
        .await
        .map_err(|e| format!("upstream open join: {e}"))??;

    let (tx, rx) = mpsc::channel::<Result<Bytes, std::io::Error>>(READ_QUEUE_CHUNKS);
    let guard = opened.guard;
    let permit = opened._permit;
    let status = opened.status;
    let headers = opened.headers;

    tokio::task::spawn_blocking(move || {
        read_loop(guard, tx);
        // Release concurrency slot after close (HandleGuard Drop / close_now).
        drop(permit);
    });

    let stream = ReceiverStream::new(rx);
    let body = Body::from_stream(stream);

    Ok(UpstreamResponse {
        status,
        headers,
        body,
    })
}

fn open_blocking(
    url: &str,
    headers_json: &str,
    permit: OwnedSemaphorePermit,
) -> Result<OpenedUpstream, String> {
    // Copy callbacks, then release the registration mutex before any network I/O.
    let cb = callbacks()?;

    let c_url = CString::new(url).map_err(|_| "url contains NUL".to_string())?;
    let c_headers =
        CString::new(headers_json).map_err(|_| "headers json contains NUL".to_string())?;

    let mut status: c_int = 0;
    let mut headers_ptr: *mut c_char = ptr::null_mut();
    let mut error_ptr: *mut c_char = ptr::null_mut();

    let handle = unsafe {
        (cb.open)(
            c_url.as_ptr(),
            c_headers.as_ptr(),
            &mut status,
            &mut headers_ptr,
            &mut error_ptr,
        )
    };

    if handle <= 0 {
        let msg = take_c_string(cb.free_string, error_ptr)
            .unwrap_or_else(|| "upstream open failed".to_string());
        return Err(msg);
    }

    // Guard immediately so any subsequent failure / cancelled join closes the handle.
    let guard = HandleGuard::new(handle, cb);
    let free_string = guard.callbacks.free_string;

    let headers_json = take_c_string(free_string, headers_ptr).unwrap_or_else(|| "{}".into());
    let headers = parse_response_headers(&headers_json);
    let status = StatusCode::from_u16(status as u16).unwrap_or(StatusCode::OK);

    Ok(OpenedUpstream {
        guard,
        status,
        headers,
        _permit: permit,
    })
}

fn read_loop(mut guard: HandleGuard, tx: mpsc::Sender<Result<Bytes, std::io::Error>>) {
    let handle = guard.handle;
    let mut buf = vec![0u8; READ_CHUNK_BYTES];
    loop {
        let mut error_ptr: *mut c_char = ptr::null_mut();
        let n = unsafe {
            (guard.callbacks.read)(handle, buf.as_mut_ptr(), buf.len() as c_int, &mut error_ptr)
        };
        if n == 0 {
            break;
        }
        if n < 0 {
            let msg = take_c_string(guard.callbacks.free_string, error_ptr)
                .unwrap_or_else(|| "upstream read failed".to_string());
            let _ = tx.blocking_send(Err(std::io::Error::other(msg)));
            break;
        }
        let chunk = Bytes::copy_from_slice(&buf[..n as usize]);
        if tx.blocking_send(Ok(chunk)).is_err() {
            // Consumer dropped (client disconnect) — stop reading; Drop closes.
            break;
        }
    }
    guard.close_now();
}

fn take_c_string(free_string: UpstreamFreeStringFn, ptr: *mut c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    let s = unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned();
    unsafe {
        free_string(ptr);
    }
    Some(s)
}

fn parse_response_headers(json: &str) -> HeaderMap {
    let mut out = HeaderMap::new();
    let Ok(map) = serde_json::from_str::<HashMap<String, String>>(json) else {
        warn!("[stream-proxy] JNI upstream response headers were not a JSON object");
        return out;
    };
    for (k, v) in map {
        let lower = k.to_ascii_lowercase();
        // Only forward headers the proxy layer uses for clients.
        if matches!(
            lower.as_str(),
            "content-type"
                | "content-length"
                | "content-range"
                | "accept-ranges"
                | "cache-control"
                | "vary"
                | "expires"
                | "age"
                | "date"
        ) {
            if let (Ok(name), Ok(value)) = (
                HeaderName::from_bytes(k.as_bytes()),
                HeaderValue::from_str(&v),
            ) {
                out.insert(name, value);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};
    use std::sync::{Arc, Mutex as StdMutex};
    use std::time::{Duration, Instant};

    struct MockBody {
        data: Vec<u8>,
        pos: usize,
        /// Artificial delay per read to prove locks are not held across I/O.
        read_delay: Duration,
    }

    /// Global callbacks are process-wide; serialize tests that mutate them.
    static TEST_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());
    static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
    static MOCKS: StdMutex<Option<HashMap<i64, Arc<StdMutex<MockBody>>>>> = StdMutex::new(None);
    static OPEN_INFLIGHT: AtomicUsize = AtomicUsize::new(0);
    static OPEN_PEAK: AtomicUsize = AtomicUsize::new(0);
    static OPEN_DELAY_MS: AtomicUsize = AtomicUsize::new(0);

    unsafe extern "C" fn test_free_string(ptr: *mut c_char) {
        if !ptr.is_null() {
            drop(unsafe { CString::from_raw(ptr) });
        }
    }

    unsafe extern "C" fn test_open(
        url: *const c_char,
        _headers: *const c_char,
        out_status: *mut c_int,
        out_headers: *mut *mut c_char,
        out_error: *mut *mut c_char,
    ) -> i64 {
        let url = unsafe { CStr::from_ptr(url) }.to_string_lossy();
        if !url.contains("ok") {
            let err = CString::new("mock open fail").unwrap().into_raw();
            unsafe {
                *out_error = err;
            }
            return 0;
        }

        let inflight = OPEN_INFLIGHT.fetch_add(1, Ordering::SeqCst) + 1;
        OPEN_PEAK.fetch_max(inflight, Ordering::SeqCst);
        let delay = OPEN_DELAY_MS.load(Ordering::SeqCst);
        if delay > 0 {
            std::thread::sleep(Duration::from_millis(delay as u64));
        }
        OPEN_INFLIGHT.fetch_sub(1, Ordering::SeqCst);

        let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
        let body = Arc::new(StdMutex::new(MockBody {
            data: b"hello-jni".to_vec(),
            pos: 0,
            read_delay: Duration::from_millis(0),
        }));
        {
            let mut guard = MOCKS.lock().unwrap();
            if guard.is_none() {
                *guard = Some(HashMap::new());
            }
            guard.as_mut().unwrap().insert(handle, body);
        }
        unsafe {
            *out_status = 200;
            *out_headers = CString::new(r#"{"content-type":"text/plain","content-length":"9"}"#)
                .unwrap()
                .into_raw();
            *out_error = ptr::null_mut();
        }
        handle
    }

    unsafe extern "C" fn test_read(
        handle: i64,
        buf: *mut u8,
        len: c_int,
        out_error: *mut *mut c_char,
    ) -> c_int {
        let body = {
            let guard = MOCKS.lock().unwrap();
            guard.as_ref().and_then(|m| m.get(&handle).cloned())
        };
        let Some(body) = body else {
            unsafe {
                *out_error = CString::new("bad handle").unwrap().into_raw();
            }
            return -1;
        };
        let mut g = body.lock().unwrap();
        if !g.read_delay.is_zero() {
            let d = g.read_delay;
            drop(g);
            std::thread::sleep(d);
            g = body.lock().unwrap();
        }
        let remaining = g.data.len().saturating_sub(g.pos);
        if remaining == 0 {
            return 0;
        }
        let n = remaining.min(len as usize);
        unsafe {
            ptr::copy_nonoverlapping(g.data.as_ptr().add(g.pos), buf, n);
        }
        g.pos += n;
        n as c_int
    }

    unsafe extern "C" fn test_close(handle: i64) {
        if let Ok(mut guard) = MOCKS.lock() {
            if let Some(map) = guard.as_mut() {
                map.remove(&handle);
            }
        }
    }

    fn install_test_callbacks() {
        set_upstream_callbacks(PbUpstreamCallbacks {
            open: test_open,
            read: test_read,
            close: test_close,
            free_string: test_free_string,
        });
    }

    #[tokio::test]
    async fn mock_callbacks_stream_body() {
        let _guard = TEST_LOCK.lock().await;
        clear_upstream_callbacks();
        OPEN_DELAY_MS.store(0, Ordering::SeqCst);
        install_test_callbacks();
        assert!(upstream_callbacks_registered());

        let fetcher = JniUpstreamFetcher::new();
        let headers = HashMap::new();
        let resp = fetcher
            .connect("https://example.test/ok", &headers)
            .await
            .expect("open");
        assert_eq!(resp.status, StatusCode::OK);
        assert_eq!(
            resp.headers
                .get("content-type")
                .and_then(|v| v.to_str().ok()),
            Some("text/plain")
        );
        let bytes = axum::body::to_bytes(resp.body, 1024).await.unwrap();
        assert_eq!(&bytes[..], b"hello-jni");

        clear_upstream_callbacks();
    }

    #[tokio::test]
    async fn unregistered_callbacks_error() {
        let _guard = TEST_LOCK.lock().await;
        clear_upstream_callbacks();
        let fetcher = JniUpstreamFetcher::new();
        let result = fetcher
            .connect("https://example.test/ok", &HashMap::new())
            .await;
        match result {
            Ok(_) => panic!("expected error when callbacks unregistered"),
            Err(err) => assert!(err.contains("not registered"), "{err}"),
        }
    }

    /// Two opens with artificial delay must overlap (mutex must not serialize them).
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn concurrent_opens_overlap() {
        let _guard = TEST_LOCK.lock().await;
        clear_upstream_callbacks();
        OPEN_INFLIGHT.store(0, Ordering::SeqCst);
        OPEN_PEAK.store(0, Ordering::SeqCst);
        OPEN_DELAY_MS.store(80, Ordering::SeqCst);
        install_test_callbacks();

        let fetcher = Arc::new(JniUpstreamFetcher::new());
        let headers = HashMap::new();
        let start = Instant::now();
        let f1 = {
            let fetcher = Arc::clone(&fetcher);
            let headers = headers.clone();
            async move { fetcher.connect("https://example.test/ok-a", &headers).await }
        };
        let f2 = {
            let fetcher = Arc::clone(&fetcher);
            let headers = headers.clone();
            async move { fetcher.connect("https://example.test/ok-b", &headers).await }
        };
        let (r1, r2) = tokio::join!(f1, f2);
        let elapsed = start.elapsed();
        r1.expect("open a");
        r2.expect("open b");
        // Two 80ms opens serialized would be ≥160ms; overlapping should finish
        // closer to one open (~80–140ms on CI).
        assert!(
            elapsed < Duration::from_millis(150),
            "opens appear serialized: {elapsed:?}"
        );
        assert!(
            OPEN_PEAK.load(Ordering::SeqCst) >= 2,
            "expected overlapping opens, peak={}",
            OPEN_PEAK.load(Ordering::SeqCst)
        );

        OPEN_DELAY_MS.store(0, Ordering::SeqCst);
        clear_upstream_callbacks();
    }

    #[test]
    fn cache_policy_response_headers_are_preserved() {
        let headers = parse_response_headers(
            r#"{
                "Cache-Control":"public, max-age=30",
                "Vary":"Accept",
                "Expires":"Wed, 21 Oct 2015 07:29:00 GMT",
                "Age":"20",
                "Date":"Wed, 21 Oct 2015 07:28:00 GMT",
                "Set-Cookie":"secret=must-not-forward"
            }"#,
        );

        assert_eq!(
            headers
                .get(axum::http::header::CACHE_CONTROL)
                .and_then(|value| value.to_str().ok()),
            Some("public, max-age=30")
        );
        assert_eq!(
            headers
                .get(axum::http::header::DATE)
                .and_then(|value| value.to_str().ok()),
            Some("Wed, 21 Oct 2015 07:28:00 GMT")
        );
        assert!(!headers.contains_key(axum::http::header::SET_COOKIE));
    }
}
