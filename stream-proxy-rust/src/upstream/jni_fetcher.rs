//! Host-provided origin fetch (Android JNI → HttpURLConnection).
//!
//! The host registers a small C ABI (`pb_proxy_upstream_*`) that performs
//! blocking HTTP I/O. [`JniUpstreamFetcher`] runs those callbacks on
//! `spawn_blocking` and streams the body into Axum without buffering whole
//! segments in memory.
//!
//! ABI version: see [`UPSTREAM_JNI_ABI_VERSION`].

use super::{UpstreamConnectFuture, UpstreamFetcher, UpstreamResponse};
use axum::body::Body;
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode};
use bytes::Bytes;
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char};
use std::os::raw::c_int;
use std::ptr;
use std::sync::Mutex;
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use tracing::warn;

/// Bump when the callback struct layout or contract changes incompatibly.
pub const UPSTREAM_JNI_ABI_VERSION: u32 = 1;

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
    CALLBACKS.lock().expect("upstream callbacks mutex").is_some()
}

fn with_callbacks<T>(f: impl FnOnce(&PbUpstreamCallbacks) -> T) -> Result<T, String> {
    let guard = CALLBACKS.lock().expect("upstream callbacks mutex");
    let cb = guard
        .as_ref()
        .ok_or_else(|| {
            "JNI upstream callbacks are not registered (host must call \
             pb_proxy_upstream_set_callbacks before fetching remote origins)"
                .to_string()
        })?;
    Ok(f(cb))
}

// ---- C ABI -----------------------------------------------------------------

/// Returns the upstream JNI callback ABI version.
#[no_mangle]
pub extern "C" fn pb_proxy_upstream_abi_version() -> u32 {
    UPSTREAM_JNI_ABI_VERSION
}

/// Install host callbacks. See [`PbUpstreamCallbacks`].
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

struct OpenedUpstream {
    handle: i64,
    status: StatusCode,
    headers: HeaderMap,
    callbacks: PbUpstreamCallbacks,
}

impl UpstreamFetcher for JniUpstreamFetcher {
    fn connect<'a>(
        &'a self,
        url: &'a str,
        headers: &'a HashMap<String, String>,
    ) -> UpstreamConnectFuture<'a> {
        let url = url.to_owned();
        let headers = headers.clone();
        Box::pin(async move { connect_via_host(url, headers).await })
    }
}

async fn connect_via_host(
    url: String,
    headers: HashMap<String, String>,
) -> Result<UpstreamResponse, String> {
    let headers_json =
        serde_json::to_string(&headers).map_err(|e| format!("headers json: {e}"))?;

    let opened = tokio::task::spawn_blocking(move || open_blocking(&url, &headers_json))
        .await
        .map_err(|e| format!("upstream open join: {e}"))??;

    let (tx, rx) = mpsc::channel::<Result<Bytes, std::io::Error>>(4);
    let handle = opened.handle;
    let callbacks = opened.callbacks;

    tokio::task::spawn_blocking(move || {
        read_loop(handle, callbacks, tx);
    });

    let stream = ReceiverStream::new(rx);
    let body = Body::from_stream(stream);

    Ok(UpstreamResponse {
        status: opened.status,
        headers: opened.headers,
        body,
    })
}

fn open_blocking(url: &str, headers_json: &str) -> Result<OpenedUpstream, String> {
    with_callbacks(|cb| {
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

        let headers_json = take_c_string(cb.free_string, headers_ptr).unwrap_or_else(|| "{}".into());
        let headers = parse_response_headers(&headers_json);
        let status = StatusCode::from_u16(status as u16).unwrap_or(StatusCode::OK);

        Ok(OpenedUpstream {
            handle,
            status,
            headers,
            callbacks: *cb,
        })
    })?
}

fn read_loop(
    handle: i64,
    callbacks: PbUpstreamCallbacks,
    tx: mpsc::Sender<Result<Bytes, std::io::Error>>,
) {
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let mut error_ptr: *mut c_char = ptr::null_mut();
        let n = unsafe {
            (callbacks.read)(
                handle,
                buf.as_mut_ptr(),
                buf.len() as c_int,
                &mut error_ptr,
            )
        };
        if n == 0 {
            break;
        }
        if n < 0 {
            let msg = take_c_string(callbacks.free_string, error_ptr)
                .unwrap_or_else(|| "upstream read failed".to_string());
            let _ = tx.blocking_send(Err(std::io::Error::other(msg)));
            break;
        }
        let chunk = Bytes::copy_from_slice(&buf[..n as usize]);
        if tx.blocking_send(Ok(chunk)).is_err() {
            // Consumer dropped (client disconnect) — stop reading.
            break;
        }
    }
    unsafe {
        (callbacks.close)(handle);
    }
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
            "content-type" | "content-length" | "content-range" | "accept-ranges"
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
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::sync::{Arc, Mutex as StdMutex};

    struct MockBody {
        data: Vec<u8>,
        pos: usize,
    }

    /// Global callbacks are process-wide; serialize tests that mutate them.
    static TEST_LOCK: StdMutex<()> = StdMutex::new(());
    static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
    static MOCKS: StdMutex<Option<HashMap<i64, Arc<StdMutex<MockBody>>>>> = StdMutex::new(None);

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
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
        let body = Arc::new(StdMutex::new(MockBody {
            data: b"hello-jni".to_vec(),
            pos: 0,
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
            guard
                .as_ref()
                .and_then(|m| m.get(&handle).cloned())
        };
        let Some(body) = body else {
            unsafe {
                *out_error = CString::new("bad handle").unwrap().into_raw();
            }
            return -1;
        };
        let mut g = body.lock().unwrap();
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

    #[tokio::test]
    async fn mock_callbacks_stream_body() {
        let _guard = TEST_LOCK.lock().unwrap();
        clear_upstream_callbacks();
        set_upstream_callbacks(PbUpstreamCallbacks {
            open: test_open,
            read: test_read,
            close: test_close,
            free_string: test_free_string,
        });
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
        let _guard = TEST_LOCK.lock().unwrap();
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
}
