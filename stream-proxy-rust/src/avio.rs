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
        let (avutil_path, avformat_path) = find_ffmpeg_libraries(custom_path)?;
        info!(
            "[pb-proxy-avio] Loading FFmpeg libraries: avutil={:?}, avformat={:?}",
            avutil_path, avformat_path
        );

        unsafe {
            let avutil_lib = Library::new(&avutil_path).ok()?;
            let avformat_lib = Library::new(&avformat_path).ok()?;

            let av_dict_set: Symbol<AvDictSetFn> = avutil_lib.get(b"av_dict_set\0").ok()?;
            let av_dict_free: Symbol<AvDictFreeFn> = avutil_lib.get(b"av_dict_free\0").ok()?;
            let av_strerror: Symbol<AvStrErrorFn> = avutil_lib.get(b"av_strerror\0").ok()?;

            let avformat_network_init: Symbol<AvFormatNetworkInitFn> =
                avformat_lib.get(b"avformat_network_init\0").ok()?;
            let avio_open2: Symbol<AvioOpen2Fn> = avformat_lib.get(b"avio_open2\0").ok()?;
            let avio_read: Symbol<AvioReadFn> = avformat_lib.get(b"avio_read\0").ok()?;
            let avio_close: Symbol<AvioCloseFn> = avformat_lib.get(b"avio_close\0").ok()?;

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
        let sys_dirs = ["/usr/lib", "/usr/lib/x86_64-linux-gnu", "/usr/local/lib"];
        for dir in sys_dirs {
            let p = Path::new(dir);
            if let Some(pair) = check_dir_for_ffmpeg(p) {
                return Some(pair);
            }
        }
    }

    // Default dynamic loader lookup
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

fn check_dir_for_ffmpeg(dir: &Path) -> Option<(PathBuf, PathBuf)> {
    let (util_ext, fmt_ext) = if cfg!(target_os = "macos") {
        ("libavutil.dylib", "libavformat.dylib")
    } else if cfg!(target_os = "windows") {
        ("avutil.dll", "avformat.dll")
    } else {
        ("libavutil.so", "libavformat.so")
    };

    let u_path = dir.join(util_ext);
    let f_path = dir.join(fmt_ext);

    if u_path.exists() && f_path.exists() {
        return Some((u_path, f_path));
    }

    for v in (58..=62).rev() {
        let u_ver = if cfg!(target_os = "macos") {
            format!("libavutil.{}.dylib", v)
        } else if cfg!(target_os = "windows") {
            format!("avutil-{}.dll", v)
        } else {
            format!("libavutil.so.{}", v)
        };

        let f_ver = if cfg!(target_os = "macos") {
            format!("libavformat.{}.dylib", v)
        } else if cfg!(target_os = "windows") {
            format!("avformat-{}.dll", v)
        } else {
            format!("libavformat.so.{}", v)
        };

        let u_p = dir.join(u_ver);
        let f_p = dir.join(f_ver);

        if u_p.exists() && f_p.exists() {
            return Some((u_p, f_p));
        }
    }

    None
}
