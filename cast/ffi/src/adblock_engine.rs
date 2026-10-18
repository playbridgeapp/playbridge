// SPDX-License-Identifier: MPL-2.0
//! Adblock ABI 1. Opt-in, panic-safe synchronous boundary; no diagnostics or resources.
use playbridge_cast_core::adblock_engine::{
    AdblockEngine, AdblockError, CacheManifest, Config, content_blocking,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    ffi::{CString, c_char},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};

type Shared = Arc<Mutex<AdblockEngine>>;
static ENGINES: OnceLock<Mutex<HashMap<u64, Shared>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
const MAX_INPUT: usize = 64 * 1024 * 1024;
fn engines() -> &'static Mutex<HashMap<u64, Shared>> {
    ENGINES.get_or_init(Mutex::default)
}
fn insert(engine: AdblockEngine) -> Option<u64> {
    let mut map = engines().lock().ok()?;
    if map.len() >= 16 {
        return None;
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    if id == 0 {
        return None;
    }
    map.insert(id, Arc::new(Mutex::new(engine)));
    Some(id)
}
unsafe fn input<'a>(data: *const u8, len: usize) -> Option<&'a [u8]> {
    if data.is_null() || len == 0 || len > MAX_INPUT {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(data, len) })
}
fn output(value: Value) -> *mut c_char {
    CString::new(value.to_string())
        .expect("JSON escapes NUL")
        .into_raw()
}
fn guarded(work: impl FnOnce() -> Result<Value, AdblockError>) -> *mut c_char {
    let value = match catch_unwind(AssertUnwindSafe(work)) {
        Ok(Ok(v)) => v,
        Ok(Err(code)) => json!({"error":code}),
        Err(_) => json!({"error":AdblockError::EnginePanic}),
    };
    output(value)
}
#[unsafe(no_mangle)]
pub extern "C" fn pb_adblock_abi_version() -> u32 {
    1
}
/// # Safety
/// `data` must point to `len` initialized readable bytes for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_adblock_create_json(data: *const u8, len: usize) -> *mut c_char {
    guarded(|| {
        let config: Config =
            serde_json::from_slice(unsafe { input(data, len) }.ok_or(AdblockError::InvalidInput)?)
                .map_err(|_| AdblockError::InvalidConfig)?;
        validate_config(&config)?;
        let id = insert(AdblockEngine::new(config)).ok_or(AdblockError::EngineLimit)?;
        Ok(json!({"handle": id}))
    })
}
fn validate_config(config: &Config) -> Result<(), AdblockError> {
    if config.lists.len() > 128
        || config.lists.iter().map(String::len).sum::<usize>() > 32 * 1024 * 1024
    {
        return Err(AdblockError::ListLimit);
    }
    Ok(())
}
/// # Safety
/// Buffers must be readable for their lengths. Config is the expected source lists;
/// the embedded manifest and checksum must match before engine deserialization.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_adblock_deserialize(
    data: *const u8,
    len: usize,
    config: *const u8,
    config_len: usize,
) -> *mut c_char {
    guarded(|| {
        let bytes = unsafe { input(data, len) }.ok_or(AdblockError::InvalidInput)?;
        let config: Config = serde_json::from_slice(
            unsafe { input(config, config_len) }.ok_or(AdblockError::InvalidInput)?,
        )
        .map_err(|_| AdblockError::InvalidConfig)?;
        validate_config(&config)?;
        let engine = AdblockEngine::deserialize(bytes, CacheManifest::for_lists(&config.lists))?;
        let id = insert(engine).ok_or(AdblockError::EngineLimit)?;
        Ok(json!({"handle": id}))
    })
}
#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
enum Command {
    Check {
        url: String,
        source_url: String,
        request_type: String,
        method: String,
    },
    Cosmetic {
        url: String,
    },
    HiddenClassIdSelectors {
        classes: Vec<String>,
        ids: Vec<String>,
        exceptions: std::collections::HashSet<String>,
    },
}
/// # Safety
/// `data` must point to `len` readable bytes. Free the returned string once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_adblock_call_json(id: u64, data: *const u8, len: usize) -> *mut c_char {
    guarded(|| {
        if len > 128 * 1024 {
            return Err(AdblockError::RequestTooLarge);
        }
        let bytes = unsafe { input(data, len) }.ok_or(AdblockError::InvalidInput)?;
        let command: Command =
            serde_json::from_slice(bytes).map_err(|_| AdblockError::InvalidCommand)?;
        let shared = engines()
            .lock()
            .map_err(|_| AdblockError::RegistryUnavailable)?
            .get(&id)
            .cloned()
            .ok_or(AdblockError::UnknownEngine)?;
        let engine = shared.lock().map_err(|_| AdblockError::EngineUnavailable)?;
        match command {
            Command::Check {
                url,
                source_url,
                request_type,
                method,
            } => engine.check(&url, &source_url, &request_type, &method),
            Command::Cosmetic { url } => {
                if url.len() > 16384 {
                    return Err(AdblockError::RequestTooLarge);
                }
                Ok(engine.cosmetic(&url))
            }
            Command::HiddenClassIdSelectors {
                classes,
                ids,
                exceptions,
            } => Ok(json!(engine.hidden(classes, ids, exceptions))),
        }
    })
}
/// # Safety
/// `out_len` must be writable. Free with exactly the returned length once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_adblock_serialize(id: u64, out_len: *mut usize) -> *mut u8 {
    if out_len.is_null() {
        return std::ptr::null_mut();
    }
    unsafe {
        *out_len = 0;
    }
    catch_unwind(AssertUnwindSafe(|| {
        let shared = engines().lock().ok()?.get(&id).cloned()?;
        let bytes = shared.lock().ok()?.serialize().into_boxed_slice();
        unsafe {
            *out_len = bytes.len();
        }
        Some(Box::into_raw(bytes) as *mut u8)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}
#[derive(Deserialize)]
struct Convert {
    lists: Vec<String>,
    #[serde(default = "chunk_limit")]
    chunk_limit: usize,
    #[serde(default)]
    json_strings: bool,
}
fn chunk_limit() -> usize {
    140_000
}
/// # Safety
/// `data` must point to `len` readable bytes. Free the returned string once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_adblock_content_blocking_json(
    data: *const u8,
    len: usize,
) -> *mut c_char {
    guarded(|| {
        let request: Convert =
            serde_json::from_slice(unsafe { input(data, len) }.ok_or(AdblockError::InvalidInput)?)
                .map_err(|_| AdblockError::InvalidInput)?;
        validate_config(&Config {
            lists: request.lists.clone(),
        })?;
        let converted = content_blocking(&request.lists, request.chunk_limit)?;
        if !request.json_strings {
            return serde_json::to_value(converted).map_err(|_| AdblockError::EncodingFailed);
        }
        let chunks: Result<Vec<String>, AdblockError> = converted
            .chunks
            .iter()
            .map(|chunk| {
                let mut value =
                    serde_json::to_value(chunk).map_err(|_| AdblockError::EncodingFailed)?;
                canonicalize(&mut value, "");
                serde_json::to_string(&value).map_err(|_| AdblockError::EncodingFailed)
            })
            .collect();
        Ok(
            json!({"chunks":chunks?, "source_lines":converted.source_lines,
            "converted_filters":converted.converted_filters, "unique_rules":converted.unique_rules,
            "emitted_rules":converted.emitted_rules, "disabled_by_badfilter":converted.disabled_by_badfilter,
            "failures":converted.failures}),
        )
    })
}
fn canonicalize(value: &mut Value, key: &str) {
    match value {
        Value::Object(map) => {
            for (key, value) in map {
                canonicalize(value, key);
            }
        }
        Value::Array(array) => {
            for value in array.iter_mut() {
                canonicalize(value, "");
            }
            if [
                "resource-type",
                "load-type",
                "if-domain",
                "unless-domain",
                "if-top-url",
                "unless-top-url",
            ]
            .contains(&key)
            {
                array.sort_by(|a, b| a.as_str().cmp(&b.as_str()));
            }
        }
        _ => {}
    }
}
#[unsafe(no_mangle)]
pub extern "C" fn pb_adblock_destroy(id: u64) {
    if let Ok(mut map) = engines().lock() {
        map.remove(&id);
    }
}
/// # Safety
/// Pointer must be null or one returned string not freed previously.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_adblock_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(unsafe { CString::from_raw(ptr) });
    }
}
/// # Safety
/// Pointer/length must be exactly a live buffer returned by serialize, freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_adblock_bytes_free(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr, len)) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;
    #[test]
    fn handles_roundtrip_ownership_and_invalid_inputs() {
        let cfg = br#"{"lists":["||ads.test^"]}"#;
        let ptr = unsafe { pb_adblock_create_json(cfg.as_ptr(), cfg.len()) };
        let created: Value =
            serde_json::from_slice(unsafe { CStr::from_ptr(ptr) }.to_bytes()).unwrap();
        unsafe { pb_adblock_string_free(ptr) };
        let id = created["handle"].as_u64().unwrap();
        assert_ne!(id, 0);
        let req = br#"{"op":"check","url":"https://ads.test/a","source_url":"https://a.test","request_type":"script","method":"GET"}"#;
        let ptr = unsafe { pb_adblock_call_json(id, req.as_ptr(), req.len()) };
        let value: Value =
            serde_json::from_slice(unsafe { CStr::from_ptr(ptr) }.to_bytes()).unwrap();
        assert_eq!(value["matched"], true);
        unsafe { pb_adblock_string_free(ptr) };
        let mut len = 0;
        let bytes = unsafe { pb_adblock_serialize(id, &mut len) };
        let ptr = unsafe { pb_adblock_deserialize(bytes, len, cfg.as_ptr(), cfg.len()) };
        let value: Value =
            serde_json::from_slice(unsafe { CStr::from_ptr(ptr) }.to_bytes()).unwrap();
        unsafe { pb_adblock_string_free(ptr) };
        let restored = value["handle"].as_u64().unwrap();
        assert_ne!(restored, 0);
        unsafe { pb_adblock_bytes_free(bytes, len) };
        pb_adblock_destroy(id);
        pb_adblock_destroy(id);
        pb_adblock_destroy(restored);
        let ptr = unsafe { pb_adblock_create_json(std::ptr::null(), 1) };
        let value: Value =
            serde_json::from_slice(unsafe { CStr::from_ptr(ptr) }.to_bytes()).unwrap();
        assert_eq!(value["error"], "invalid_input");
        unsafe { pb_adblock_string_free(ptr) };
        assert!(unsafe { pb_adblock_serialize(id, &mut len) }.is_null());
        let ptr = unsafe { pb_adblock_call_json(id, req.as_ptr(), req.len()) };
        assert!(
            unsafe { CStr::from_ptr(ptr) }
                .to_bytes()
                .windows(14)
                .any(|s| s == b"unknown_engine")
        );
        unsafe { pb_adblock_string_free(ptr) };
    }
    #[test]
    fn typed_errors_caps_and_no_resource_inputs() {
        unsafe fn error(pointer: *mut c_char) -> Value {
            let result: Value =
                serde_json::from_slice(unsafe { CStr::from_ptr(pointer) }.to_bytes()).unwrap();
            unsafe { pb_adblock_string_free(pointer) };
            result["error"].clone()
        }
        let config = br#"{"lists":[],"resources":[]}"#;
        assert_eq!(
            unsafe { error(pb_adblock_create_json(config.as_ptr(), config.len())) },
            "invalid_config"
        );
        assert_eq!(
            unsafe { error(pb_adblock_create_json(b"{".as_ptr(), MAX_INPUT + 1)) },
            "invalid_input"
        );
        assert_eq!(
            unsafe { error(pb_adblock_call_json(0, b"{".as_ptr(), 128 * 1024 + 1)) },
            "request_too_large"
        );
        let convert = br#"{"lists":["||a.test^"],"chunk_limit":140001}"#;
        assert_eq!(
            unsafe {
                error(pb_adblock_content_blocking_json(
                    convert.as_ptr(),
                    convert.len(),
                ))
            },
            "invalid_chunk_limit"
        );
    }
}
