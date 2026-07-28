//! Android host origin-fetch: JNI trampolines → Kotlin HttpURLConnection.
//!
//! Registers [stream_proxy_rust::PbUpstreamCallbacks] that call
//! `com.playbridge.sender.cast.proxy.JniUpstreamHttpClient` open/read/close.

use jni::objects::{Global, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::jboolean;
use jni::{jni_sig, jni_str, Env, EnvUnowned, JavaVM};
use std::ffi::{CStr, CString, c_char};
use std::os::raw::c_int;
use std::ptr;
use std::sync::Mutex;
use stream_proxy_rust::{PbUpstreamCallbacks, set_upstream_callbacks};

struct HostBridge {
    jvm: JavaVM,
    class: Global<JClass<'static>>,
}

// SAFETY: JavaVM is process-global; Global class ref is valid for process life.
unsafe impl Send for HostBridge {}
unsafe impl Sync for HostBridge {}

static HOST: Mutex<Option<HostBridge>> = Mutex::new(None);

/// Install C upstream callbacks that dispatch to [JniUpstreamHttpClient].
pub fn install_from_env(env: &mut Env) -> Result<(), String> {
    let jvm = env
        .get_java_vm()
        .map_err(|e| format!("get_java_vm: {e}"))?;
    let class = env
        .find_class(jni_str!("com/playbridge/sender/cast/proxy/JniUpstreamHttpClient"))
        .map_err(|e| format!("find_class JniUpstreamHttpClient: {e}"))?;
    let global = env
        .new_global_ref(&class)
        .map_err(|e| format!("global ref: {e}"))?;

    {
        let mut guard = HOST.lock().map_err(|_| "host mutex poisoned".to_string())?;
        *guard = Some(HostBridge {
            jvm,
            class: global,
        });
    }

    set_upstream_callbacks(PbUpstreamCallbacks {
        open: trampoline_open,
        read: trampoline_read,
        close: trampoline_close,
        free_string: trampoline_free_string,
    });
    Ok(())
}

fn with_host<T>(f: impl FnOnce(&HostBridge) -> Result<T, String>) -> Result<T, String> {
    let guard = HOST.lock().map_err(|_| "host mutex poisoned".to_string())?;
    let host = guard
        .as_ref()
        .ok_or_else(|| "Android upstream host is not installed".to_string())?;
    f(host)
}

unsafe extern "C" fn trampoline_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(unsafe { CString::from_raw(ptr) });
    }
}

fn c_string_raw(s: impl Into<Vec<u8>>) -> *mut c_char {
    CString::new(s)
        .map(|c| c.into_raw())
        .unwrap_or(ptr::null_mut())
}

unsafe extern "C" fn trampoline_open(
    url: *const c_char,
    request_headers_json: *const c_char,
    out_status: *mut c_int,
    out_response_headers_json: *mut *mut c_char,
    out_error: *mut *mut c_char,
) -> i64 {
    let result = (|| -> Result<(i64, i32, String), String> {
        let url = unsafe { CStr::from_ptr(url) }
            .to_str()
            .map_err(|_| "url not utf-8".to_string())?
            .to_owned();
        let headers = if request_headers_json.is_null() {
            "{}".to_owned()
        } else {
            unsafe { CStr::from_ptr(request_headers_json) }
                .to_str()
                .map_err(|_| "headers not utf-8".to_string())?
                .to_owned()
        };

        let json = with_host(|host| {
            host.jvm
                .attach_current_thread(|env| -> Result<String, jni::errors::Error> {
                    let j_url = env.new_string(&url)?;
                    let j_headers = env.new_string(&headers)?;
                    // Pass Global class directly (Desc impl); avoid as_ref ambiguity.
                    let ret = env.call_static_method(
                        &host.class,
                        jni_str!("open"),
                        jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                        &[JValue::Object(&j_url), JValue::Object(&j_headers)],
                    )?;
                    let jobj: JObject = ret.l()?;
                    if jobj.is_null() {
                        return Err(jni::errors::Error::NullPtr("open returned null"));
                    }
                    let jstr = unsafe { JString::from_raw(env, jobj.as_raw()) };
                    Ok(jstr.mutf8_chars(env)?.to_string())
                })
                .map_err(|e| format!("jni open: {e}"))
        })?;
        parse_open_json(&json)
    })();

    match result {
        Ok((handle, status, headers_json)) => {
            unsafe {
                if !out_status.is_null() {
                    *out_status = status;
                }
                if !out_response_headers_json.is_null() {
                    *out_response_headers_json = c_string_raw(headers_json);
                }
                if !out_error.is_null() {
                    *out_error = ptr::null_mut();
                }
            }
            handle
        }
        Err(msg) => {
            unsafe {
                if !out_status.is_null() {
                    *out_status = 0;
                }
                if !out_response_headers_json.is_null() {
                    *out_response_headers_json = ptr::null_mut();
                }
                if !out_error.is_null() {
                    *out_error = c_string_raw(msg);
                }
            }
            0
        }
    }
}

fn parse_open_json(json: &str) -> Result<(i64, i32, String), String> {
    let v: serde_json::Value =
        serde_json::from_str(json).map_err(|e| format!("open json: {e}"))?;
    if v.get("ok").and_then(|x| x.as_bool()) != Some(true) {
        let err = v
            .get("error")
            .and_then(|x| x.as_str())
            .unwrap_or("open failed")
            .to_owned();
        return Err(err);
    }
    let handle = v
        .get("handle")
        .and_then(|x| x.as_i64())
        .ok_or_else(|| "missing handle".to_string())?;
    let status = v.get("status").and_then(|x| x.as_i64()).unwrap_or(200) as i32;
    let headers = v
        .get("headers")
        .cloned()
        .unwrap_or_else(|| serde_json::json!({}));
    let headers_json =
        serde_json::to_string(&headers).map_err(|e| format!("headers serialize: {e}"))?;
    if handle <= 0 {
        return Err("invalid handle".into());
    }
    Ok((handle, status, headers_json))
}

unsafe extern "C" fn trampoline_read(
    handle: i64,
    buf: *mut u8,
    len: c_int,
    out_error: *mut *mut c_char,
) -> c_int {
    if buf.is_null() || len <= 0 {
        return 0;
    }

    let result = with_host(|host| {
        host.jvm
            .attach_current_thread(|env| -> Result<Option<Vec<u8>>, jni::errors::Error> {
                let ret = env.call_static_method(
                    &host.class,
                    jni_str!("read"),
                    jni_sig!("(JI)[B"),
                    &[JValue::Long(handle), JValue::Int(len)],
                )?;
                let obj: JObject = ret.l()?;
                if obj.is_null() {
                    return Ok(None);
                }
                // SAFETY: call returned a byte[] local ref.
                let arr = unsafe { JByteArray::from_raw(env, obj.as_raw()) };
                let bytes = env.convert_byte_array(&arr)?;
                Ok(Some(bytes))
            })
            .map_err(|e| format!("jni read: {e}"))
    });

    match result {
        Ok(Some(bytes)) if bytes.is_empty() => 0,
        Ok(Some(bytes)) => {
            let n = bytes.len().min(len as usize);
            unsafe {
                ptr::copy_nonoverlapping(bytes.as_ptr(), buf, n);
            }
            n as c_int
        }
        Ok(None) => {
            unsafe {
                if !out_error.is_null() {
                    *out_error = c_string_raw("read failed");
                }
            }
            -1
        }
        Err(msg) => {
            unsafe {
                if !out_error.is_null() {
                    *out_error = c_string_raw(msg);
                }
            }
            -1
        }
    }
}

unsafe extern "C" fn trampoline_close(handle: i64) {
    let _ = with_host(|host| {
        host.jvm
            .attach_current_thread(|env| -> Result<(), jni::errors::Error> {
                env.call_static_method(
                    &host.class,
                    jni_str!("close"),
                    jni_sig!("(J)V"),
                    &[JValue::Long(handle)],
                )?;
                Ok(())
            })
            .map_err(|e| format!("jni close: {e}"))
    });
}

/// JNI entry: `SenderServicesNative.installUpstreamHttpClient()`.
pub extern "system" fn java_install_upstream<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jboolean {
    use jni::errors::ThrowRuntimeExAndDefault;

    let ok: bool = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        unowned_env
            .with_env(|env| -> Result<bool, jni::errors::Error> {
                match install_from_env(env) {
                    Ok(()) => Ok(true),
                    Err(msg) => {
                        eprintln!("[android_upstream] install failed: {msg}");
                        Ok(false)
                    }
                }
            })
            .resolve::<ThrowRuntimeExAndDefault>()
    }))
    .unwrap_or(false);
    ok as jboolean
}
