use super::{
    AtvRemote, pb_atv_abi_version, pb_atv_cancel, pb_atv_free, pb_atv_next_json, pb_atv_start,
    pb_atv_submit_json,
};
use crate::pb_string_free;
use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong},
};
use std::ffi::{CStr, CString, c_char};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_playbridge_sender_cast_atv_RustAtvNative_abiVersion(
    _env: EnvUnowned,
    _class: JClass,
) -> jint {
    std::panic::catch_unwind(|| pb_atv_abi_version()).unwrap_or(0) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_playbridge_sender_cast_atv_RustAtvNative_start(
    _env: EnvUnowned,
    _class: JClass,
) -> jlong {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| pb_atv_start() as jlong)).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_playbridge_sender_cast_atv_RustAtvNative_submitJson<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    command_json: JString<'local>,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return false;
        }
        let command = unowned_env
            .with_env(|env| -> Result<String, jni::errors::Error> {
                command_json.try_to_string(env)
            })
            .resolve::<ThrowRuntimeExAndDefault>();
        let Ok(command) = CString::new(command) else {
            return false;
        };
        unsafe { pb_atv_submit_json(handle as *const AtvRemote, command.as_ptr()) }
    }))
    .unwrap_or(false)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_playbridge_sender_cast_atv_RustAtvNative_nextEvent<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    wait_ms: jlong,
) -> JString<'local> {
    let json = std::panic::catch_unwind(|| {
        if handle == 0 {
            return None;
        }
        let ptr = unsafe { pb_atv_next_json(handle as *const AtvRemote, wait_ms.max(0) as u64) };
        if ptr.is_null() {
            return None;
        }
        let owned = unsafe { CStr::from_ptr(ptr as *const c_char) }
            .to_string_lossy()
            .into_owned();
        unsafe { pb_string_free(ptr) };
        Some(owned)
    })
    .unwrap_or(None);

    unowned_env
        .with_env(|env| match json {
            Some(json) => env.new_string(json),
            None => Ok(JString::default()),
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_playbridge_sender_cast_atv_RustAtvNative_cancel(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    let _ = std::panic::catch_unwind(|| {
        if handle != 0 {
            unsafe { pb_atv_cancel(handle as *const AtvRemote) };
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_playbridge_sender_cast_atv_RustAtvNative_free(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    let _ = std::panic::catch_unwind(|| {
        if handle != 0 {
            unsafe { pb_atv_free(handle as *const AtvRemote) };
        }
    });
}
