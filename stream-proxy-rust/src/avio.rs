use bytes::Bytes;
use libloading::{Library, Symbol};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;
use tracing::{error, info};

pub type AvFormatNetworkInitFn = unsafe extern "C" fn() -> c_int;
pub type AvDictSetFn =
    unsafe extern "C" fn(*mut *mut c_void, *const c_char, *const c_char, c_int) -> c_int;
pub type AvDictFreeFn = unsafe extern "C" fn(*mut *mut c_void);
pub type AvioOpen2Fn = unsafe extern "C" fn(
    *mut *mut c_void,
    *const c_char,
    c_int,
    *const c_void,
    *mut *mut c_void,
) -> c_int;
pub type AvioReadFn = unsafe extern "C" fn(*mut c_void, *mut u8, c_int) -> c_int;
pub type AvioCloseFn = unsafe extern "C" fn(*mut c_void) -> c_int;
pub type AvStrErrorFn = unsafe extern "C" fn(c_int, *mut c_char, usize) -> c_int;

pub struct AvioFunctions {
    _avutil_lib: Library,
    _avformat_lib: Library,

    av_dict_set: AvDictSetFn,
    av_dict_free: AvDictFreeFn,
    av_strerror: AvStrErrorFn,

    _avformat_network_init: AvFormatNetworkInitFn,
    avio_open2: AvioOpen2Fn,
    avio_read: AvioReadFn,
    avio_close: AvioCloseFn,
}

// Safety: Dynamic library function pointers are thread-safe to call across threads.
unsafe impl Send for AvioFunctions {}
unsafe impl Sync for AvioFunctions {}

static CLIENT_INSTANCE: Mutex<Option<Arc<AvioClient>>> = Mutex::new(None);

pub fn get_avio_client(custom_ffmpeg_path: Option<&str>) -> Arc<AvioClient> {
    let mut guard = CLIENT_INSTANCE.lock().unwrap();
    if let Some(ref client) = *guard {
        return client.clone();
    }
    let client = Arc::new(AvioClient::new(custom_ffmpeg_path));
    *guard = Some(client.clone());
    client
}

pub struct AvioClient {
    funcs: Option<Arc<AvioFunctions>>,
}

impl AvioClient {
    pub fn new(custom_ffmpeg_path: Option<&str>) -> Self {
        let funcs = Self::load_libraries(custom_ffmpeg_path);
        AvioClient { funcs }
    }

    pub fn is_available(&self) -> bool {
        self.funcs.is_some()
    }

    fn load_libraries(custom_path: Option<&str>) -> Option<Arc<AvioFunctions>> {
        let (avutil_path, avformat_path) = match find_ffmpeg_libraries(custom_path) {
            Some(paths) => paths,
            None => {
                error!(
                    "[pb-proxy-avio] Could not locate libavutil/libavformat (set FFMPEG_PATH or install ffmpeg-libs)"
                );
                return None;
            }
        };
        info!(
            "[pb-proxy-avio] Loading FFmpeg libraries: avutil={:?}, avformat={:?}",
            avutil_path, avformat_path
        );

        unsafe {
            let avutil_lib = match Library::new(&avutil_path) {
                Ok(lib) => lib,
                Err(e) => {
                    error!(
                        "[pb-proxy-avio] Failed to load avutil {:?}: {}",
                        avutil_path, e
                    );
                    return None;
                }
            };
            let avformat_lib = match Library::new(&avformat_path) {
                Ok(lib) => lib,
                Err(e) => {
                    error!(
                        "[pb-proxy-avio] Failed to load avformat {:?}: {}",
                        avformat_path, e
                    );
                    return None;
                }
            };

            let av_dict_set: Symbol<AvDictSetFn> = match avutil_lib.get(b"av_dict_set\0") {
                Ok(s) => s,
                Err(e) => {
                    error!("[pb-proxy-avio] Missing symbol av_dict_set: {}", e);
                    return None;
                }
            };
            let av_dict_free: Symbol<AvDictFreeFn> = match avutil_lib.get(b"av_dict_free\0") {
                Ok(s) => s,
                Err(e) => {
                    error!("[pb-proxy-avio] Missing symbol av_dict_free: {}", e);
                    return None;
                }
            };
            let av_strerror: Symbol<AvStrErrorFn> = match avutil_lib.get(b"av_strerror\0") {
                Ok(s) => s,
                Err(e) => {
                    error!("[pb-proxy-avio] Missing symbol av_strerror: {}", e);
                    return None;
                }
            };

            let avformat_network_init: Symbol<AvFormatNetworkInitFn> =
                match avformat_lib.get(b"avformat_network_init\0") {
                    Ok(s) => s,
                    Err(e) => {
                        error!(
                            "[pb-proxy-avio] Missing symbol avformat_network_init: {}",
                            e
                        );
                        return None;
                    }
                };
            let avio_open2: Symbol<AvioOpen2Fn> = match avformat_lib.get(b"avio_open2\0") {
                Ok(s) => s,
                Err(e) => {
                    error!("[pb-proxy-avio] Missing symbol avio_open2: {}", e);
                    return None;
                }
            };
            let avio_read: Symbol<AvioReadFn> = match avformat_lib.get(b"avio_read\0") {
                Ok(s) => s,
                Err(e) => {
                    error!("[pb-proxy-avio] Missing symbol avio_read: {}", e);
                    return None;
                }
            };
            let avio_close: Symbol<AvioCloseFn> = match avformat_lib.get(b"avio_close\0") {
                Ok(s) => s,
                Err(e) => {
                    error!("[pb-proxy-avio] Missing symbol avio_close: {}", e);
                    return None;
                }
            };

            let av_dict_set_fn = *av_dict_set;
            let av_dict_free_fn = *av_dict_free;
            let av_strerror_fn = *av_strerror;
            let avformat_network_init_fn = *avformat_network_init;
            let avio_open2_fn = *avio_open2;
            let avio_read_fn = *avio_read;
            let avio_close_fn = *avio_close;

            avformat_network_init_fn();

            let funcs = AvioFunctions {
                _avutil_lib: avutil_lib,
                _avformat_lib: avformat_lib,
                av_dict_set: av_dict_set_fn,
                av_dict_free: av_dict_free_fn,
                av_strerror: av_strerror_fn,
                _avformat_network_init: avformat_network_init_fn,
                avio_open2: avio_open2_fn,
                avio_read: avio_read_fn,
                avio_close: avio_close_fn,
            };

            Some(Arc::new(funcs))
        }
    }

    pub fn get_error_string(&self, err_num: c_int) -> String {
        if let Some(ref funcs) = self.funcs {
            let mut err_buf = vec![0u8; 1024];
            unsafe {
                let res = (funcs.av_strerror)(err_num, err_buf.as_mut_ptr() as *mut c_char, 1024);
                if res == 0 {
                    if let Ok(c_str) = CStr::from_ptr(err_buf.as_ptr() as *const c_char).to_str() {
                        return c_str.to_string();
                    }
                }
            }
        }
        format!("Unknown FFmpeg error {}", err_num)
    }

    pub fn spawn_stream(
        &self,
        url: String,
        headers: HashMap<String, String>,
        timeout_secs: u32,
    ) -> Option<mpsc::Receiver<Result<Bytes, String>>> {
        let funcs = self.funcs.clone()?;
        let (tx, rx) = mpsc::channel(16);

        tokio::task::spawn_blocking(move || {
            unsafe {
                let mut options: *mut c_void = ptr::null_mut();

                let mut custom_headers = String::new();
                for (k, v) in &headers {
                    custom_headers.push_str(&format!("{}: {}\r\n", k, v));
                }

                let c_headers_key = CString::new("headers").unwrap();
                let c_headers_val = CString::new(custom_headers).unwrap();
                (funcs.av_dict_set)(
                    &mut options,
                    c_headers_key.as_ptr(),
                    c_headers_val.as_ptr(),
                    0,
                );

                let c_timeout_key = CString::new("timeout").unwrap();
                let c_timeout_val = CString::new(format!("{}", timeout_secs * 1_000_000)).unwrap();
                (funcs.av_dict_set)(
                    &mut options,
                    c_timeout_key.as_ptr(),
                    c_timeout_val.as_ptr(),
                    0,
                );

                let mut avio_ctx: *mut c_void = ptr::null_mut();
                let c_url = CString::new(url.clone()).unwrap();

                let open_res =
                    (funcs.avio_open2)(&mut avio_ctx, c_url.as_ptr(), 1, ptr::null(), &mut options);

                if open_res < 0 {
                    let mut err_buf = vec![0u8; 1024];
                    let _ =
                        (funcs.av_strerror)(open_res, err_buf.as_mut_ptr() as *mut c_char, 1024);
                    let err_str = CStr::from_ptr(err_buf.as_ptr() as *const c_char)
                        .to_string_lossy()
                        .into_owned();

                    error!(
                        "[pb-proxy-avio] avio_open2 failed for {}: code={} msg={}",
                        url, open_res, err_str
                    );
                    let _ = tx.blocking_send(Err(format!(
                        "avio_open2 failed: code={} msg={}",
                        open_res, err_str
                    )));
                    (funcs.av_dict_free)(&mut options);
                    return;
                }

                let buffer_size = 32768;
                let mut read_buffer = vec![0u8; buffer_size];

                loop {
                    let bytes_read =
                        (funcs.avio_read)(avio_ctx, read_buffer.as_mut_ptr(), buffer_size as c_int);

                    if bytes_read > 0 {
                        let chunk = Bytes::copy_from_slice(&read_buffer[..bytes_read as usize]);
                        if tx.blocking_send(Ok(chunk)).is_err() {
                            break; // Stream cancelled downstream
                        }
                    } else {
                        if bytes_read != -541478725 && bytes_read < 0 {
                            let mut err_buf = vec![0u8; 1024];
                            let _ = (funcs.av_strerror)(
                                bytes_read,
                                err_buf.as_mut_ptr() as *mut c_char,
                                1024,
                            );
                            let err_str = CStr::from_ptr(err_buf.as_ptr() as *const c_char)
                                .to_string_lossy()
                                .into_owned();
                            let _ = tx.blocking_send(Err(format!(
                                "avio_read failed: code={} msg={}",
                                bytes_read, err_str
                            )));
                        }
                        break; // EOF
                    }
                }

                (funcs.avio_close)(avio_ctx);
                (funcs.av_dict_free)(&mut options);
            }
        });

        Some(rx)
    }
}

fn find_ffmpeg_libraries(custom_path: Option<&str>) -> Option<(PathBuf, PathBuf)> {
    if let Some(path_str) = custom_path {
        if !path_str.trim().is_empty() {
            let p = Path::new(path_str);
            if p.is_dir() {
                if let Some(pair) = check_dir_for_ffmpeg(p) {
                    return Some(pair);
                }
            } else if p.is_file() {
                if let Some(parent) = p.parent() {
                    if let Some(pair) = check_dir_for_ffmpeg(parent) {
                        return Some(pair);
                    }
                }
            }
        }
    }

    #[cfg(target_os = "macos")]
    {
        let brew_dirs = ["/opt/homebrew/lib", "/usr/local/lib"];
        for dir in brew_dirs {
            let p = Path::new(dir);
            if let Some(pair) = check_dir_for_ffmpeg(p) {
                return Some(pair);
            }
        }
    }

    #[cfg(target_os = "linux")]
    {
        // Alpine (/usr/lib), Debian multiarch, and local installs.
        // FFmpeg 8+ uses independent SONAME majors (e.g. libavutil.so.60 +
        // libavformat.so.62), so discovery must not require matching versions.
        let sys_dirs = [
            "/usr/lib",
            "/usr/lib64",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
            "/usr/local/lib",
        ];
        for dir in sys_dirs {
            let p = Path::new(dir);
            if let Some(pair) = check_dir_for_ffmpeg(p) {
                return Some(pair);
            }
        }
    }

    // Last resort: bare sonames for the dynamic loader (works when unversioned
    // symlinks exist, e.g. Homebrew or -dev packages). Prefer real paths above.
    let util_name = if cfg!(target_os = "macos") {
        "libavutil.dylib"
    } else if cfg!(target_os = "windows") {
        "avutil.dll"
    } else {
        "libavutil.so"
    };

    let format_name = if cfg!(target_os = "macos") {
        "libavformat.dylib"
    } else if cfg!(target_os = "windows") {
        "avformat.dll"
    } else {
        "libavformat.so"
    };

    Some((PathBuf::from(util_name), PathBuf::from(format_name)))
}

/// Pick the best available shared library for a logical FFmpeg component.
///
/// Prefer unversioned names (`libavutil.so`), then the highest major SONAME
/// found via directory scan. Avutil and avformat **must** be discovered
/// independently — FFmpeg 8 ships e.g. `libavutil.so.60` with `libavformat.so.62`.
fn find_best_lib(dir: &Path, basenames: &[&str]) -> Option<PathBuf> {
    for name in basenames {
        let p = dir.join(name);
        if p.exists() {
            return Some(p);
        }
    }

    // Scan versioned SONAMEs: libavutil.so.60, libavutil.60.dylib, avutil-60.dll
    let mut best: Option<(u32, PathBuf)> = None;
    let entries = std::fs::read_dir(dir).ok()?;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        for base in basenames {
            // libavutil.so.60[.x.y] or libavutil.60.dylib
            let prefix_so = format!("{}.", base); // libavutil.so.
            let prefix_dylib = base
                .strip_suffix(".dylib")
                .map(|stem| format!("{}.", stem)); // libavutil.
            let prefix_dll = base
                .strip_suffix(".dll")
                .map(|stem| format!("{}-", stem)); // avutil-

            let major = if name.starts_with(&prefix_so) {
                name[prefix_so.len()..]
                    .split('.')
                    .next()
                    .and_then(|s| s.parse::<u32>().ok())
            } else if let Some(ref pfx) = prefix_dylib {
                if name.starts_with(pfx) && name.ends_with(".dylib") {
                    name[pfx.len()..]
                        .strip_suffix(".dylib")
                        .and_then(|s| s.parse::<u32>().ok())
                } else {
                    None
                }
            } else if let Some(ref pfx) = prefix_dll {
                if name.starts_with(pfx) && name.ends_with(".dll") {
                    name[pfx.len()..]
                        .strip_suffix(".dll")
                        .and_then(|s| s.parse::<u32>().ok())
                } else {
                    None
                }
            } else {
                None
            };

            if let Some(maj) = major {
                let path = entry.path();
                // Prefer higher major; on ties prefer the short SONAME symlink
                // (libavutil.so.60) over the fully versioned file (.60.26.102).
                let better = match &best {
                    None => true,
                    Some((best_maj, best_path)) => {
                        maj > *best_maj
                            || (maj == *best_maj
                                && path.as_os_str().len() < best_path.as_os_str().len())
                    }
                };
                if better {
                    best = Some((maj, path));
                }
            }
        }
    }

    best.map(|(_, p)| p)
}

fn check_dir_for_ffmpeg(dir: &Path) -> Option<(PathBuf, PathBuf)> {
    if !dir.is_dir() {
        return None;
    }

    let (util_names, fmt_names): (&[&str], &[&str]) = if cfg!(target_os = "macos") {
        (&["libavutil.dylib"], &["libavformat.dylib"])
    } else if cfg!(target_os = "windows") {
        (&["avutil.dll"], &["avformat.dll"])
    } else {
        (&["libavutil.so"], &["libavformat.so"])
    };

    let util = find_best_lib(dir, util_names)?;
    let format = find_best_lib(dir, fmt_names)?;
    Some((util, format))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn discovers_independent_soname_majors_like_ffmpeg8() {
        let dir = std::env::temp_dir().join(format!(
            "pb-avio-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();

        // Alpine FFmpeg 8 layout: different majors for util vs format.
        #[cfg(target_os = "macos")]
        {
            fs::write(dir.join("libavutil.60.dylib"), b"").unwrap();
            fs::write(dir.join("libavformat.62.dylib"), b"").unwrap();
        }
        #[cfg(not(target_os = "macos"))]
        {
            fs::write(dir.join("libavutil.so.60"), b"").unwrap();
            fs::write(dir.join("libavformat.so.62"), b"").unwrap();
        }

        let (util, format) = check_dir_for_ffmpeg(&dir).expect("should find mismatched majors");
        assert!(util.to_string_lossy().contains("libavutil"));
        assert!(format.to_string_lossy().contains("libavformat"));
        assert!(util.to_string_lossy().contains("60"));
        assert!(format.to_string_lossy().contains("62"));

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn prefers_unversioned_soname_when_present() {
        let dir = std::env::temp_dir().join(format!(
            "pb-avio-unversioned-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();

        #[cfg(target_os = "macos")]
        {
            fs::write(dir.join("libavutil.dylib"), b"").unwrap();
            fs::write(dir.join("libavformat.dylib"), b"").unwrap();
            fs::write(dir.join("libavutil.59.dylib"), b"").unwrap();
            fs::write(dir.join("libavformat.61.dylib"), b"").unwrap();
        }
        #[cfg(not(target_os = "macos"))]
        {
            fs::write(dir.join("libavutil.so"), b"").unwrap();
            fs::write(dir.join("libavformat.so"), b"").unwrap();
            fs::write(dir.join("libavutil.so.59"), b"").unwrap();
            fs::write(dir.join("libavformat.so.61"), b"").unwrap();
        }

        let (util, format) = check_dir_for_ffmpeg(&dir).expect("should prefer unversioned");
        assert!(
            util.file_name().unwrap().to_string_lossy() == "libavutil.so"
                || util.file_name().unwrap().to_string_lossy() == "libavutil.dylib"
        );
        assert!(
            format.file_name().unwrap().to_string_lossy() == "libavformat.so"
                || format.file_name().unwrap().to_string_lossy() == "libavformat.dylib"
        );

        let _ = fs::remove_dir_all(&dir);
    }
}
