//! Hand-encoded Android TV Remote v2 protobuf.
//!
//! Field numbers follow `androidtvremote2` `polo.proto` and `remotemessage.proto`
//! (tronikos). Frames are a varint length followed by one message, the same
//! delimiter `ProtobufProtocol` uses.

use crate::atv_remote::error::AtvError;

pub const MAX_FRAME_LEN: usize = 1024 * 1024;
pub const STATUS_OK: u32 = 200;
pub const STATUS_BAD_SECRET: u32 = 402;
pub const PROTOCOL_VERSION: u32 = 2;
pub const ROLE_INPUT: u32 = 1;
pub const ENCODING_HEXADECIMAL: u32 = 3;
pub const SYMBOL_LENGTH: u32 = 6;

pub const FEATURE_PING: i32 = 1 << 0;
pub const FEATURE_KEY: i32 = 1 << 1;
pub const FEATURE_IME: i32 = 1 << 2;
pub const FEATURE_POWER: i32 = 1 << 5;
pub const FEATURE_VOLUME: i32 = 1 << 6;
pub const FEATURE_APP_LINK: i32 = 1 << 9;
pub const CLIENT_FEATURES: i32 =
    FEATURE_PING | FEATURE_KEY | FEATURE_IME | FEATURE_POWER | FEATURE_VOLUME | FEATURE_APP_LINK;

pub const DIR_START_LONG: u32 = 1;
pub const DIR_END_LONG: u32 = 2;
pub const DIR_SHORT: u32 = 3;

pub const KEY_HOME: u32 = 3;
pub const KEY_BACK: u32 = 4;
pub const KEY_DPAD_UP: u32 = 19;
pub const KEY_DPAD_DOWN: u32 = 20;
pub const KEY_DPAD_LEFT: u32 = 21;
pub const KEY_DPAD_RIGHT: u32 = 22;
pub const KEY_DPAD_CENTER: u32 = 23;
pub const KEY_VOLUME_UP: u32 = 24;
pub const KEY_VOLUME_DOWN: u32 = 25;
pub const KEY_POWER: u32 = 26;
pub const KEY_MENU: u32 = 82;
pub const KEY_SEARCH: u32 = 84;
pub const KEY_MEDIA_PLAY_PAUSE: u32 = 85;
pub const KEY_MEDIA_STOP: u32 = 86;
pub const KEY_MEDIA_NEXT: u32 = 87;
pub const KEY_MEDIA_PREVIOUS: u32 = 88;
pub const KEY_ENTER: u32 = 66;
/// Microphone mute. Speaker mute is `KEY_VOLUME_MUTE`.
pub const KEY_MUTE: u32 = 91;
pub const KEY_VOLUME_MUTE: u32 = 164;
pub const KEY_CAPTIONS: u32 = 175;
pub const KEY_SETTINGS: u32 = 176;
pub const KEY_TV_INPUT: u32 = 178;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OuterMessage {
    pub protocol_version: u32,
    pub status: u32,
    pub pairing_request: Option<PairingRequest>,
    pub pairing_request_ack: Option<PairingRequestAck>,
    pub options: bool,
    pub configuration: bool,
    pub configuration_ack: bool,
    pub secret: Option<Vec<u8>>,
    pub secret_ack: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingRequest {
    pub service_name: String,
    pub client_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingRequestAck {
    pub server_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextField {
    pub value: Option<String>,
    pub label: Option<String>,
    pub start: Option<i32>,
    pub end: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RemoteEvent {
    Configure {
        code1: i32,
        model: Option<String>,
        vendor: Option<String>,
        app_version: Option<String>,
    },
    SetActive {
        active: i32,
    },
    Ping {
        val1: i32,
    },
    Volume {
        level: u32,
        max: u32,
        muted: bool,
    },
    Start {
        started: bool,
    },
    Ime {
        package: Option<String>,
        text: Option<TextField>,
        ime_counter: Option<i32>,
        field_counter: Option<i32>,
    },
    Error,
}

pub struct FrameDecoder {
    buf: Vec<u8>,
}

impl FrameDecoder {
    pub fn new() -> Self {
        Self { buf: Vec::new() }
    }

    pub fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }

    pub fn push(&mut self, bytes: &[u8]) -> Result<Vec<Vec<u8>>, AtvError> {
        self.buf.extend_from_slice(bytes);
        let mut frames = Vec::new();
        while let Some(frame) = self.pop()? {
            frames.push(frame);
        }
        Ok(frames)
    }

    pub fn pop(&mut self) -> Result<Option<Vec<u8>>, AtvError> {
        let Some((length, prefix)) = take_varint(&self.buf)? else {
            return Ok(None);
        };
        if length > MAX_FRAME_LEN as u64 {
            return Err(AtvError::Protocol(format!(
                "frame length {length} exceeds {MAX_FRAME_LEN}"
            )));
        }
        let total = prefix + length as usize;
        if self.buf.len() < total {
            return Ok(None);
        }
        let frame = self.buf[prefix..total].to_vec();
        self.buf.drain(..total);
        Ok(Some(frame))
    }

    pub fn finish(&self) -> Result<(), AtvError> {
        if self.buf.is_empty() {
            Ok(())
        } else {
            Err(AtvError::Protocol("truncated frame".into()))
        }
    }
}

impl Default for FrameDecoder {
    fn default() -> Self {
        Self::new()
    }
}

pub fn encode_frame(payload: &[u8]) -> Result<Vec<u8>, AtvError> {
    if payload.len() > MAX_FRAME_LEN {
        return Err(AtvError::Protocol(format!(
            "frame length {} exceeds {MAX_FRAME_LEN}",
            payload.len()
        )));
    }
    let mut out = Vec::with_capacity(payload.len() + 4);
    write_varint(&mut out, payload.len() as u64);
    out.extend_from_slice(payload);
    Ok(out)
}

pub fn encode_pairing_request(client_name: &str) -> Vec<u8> {
    let mut request = Vec::new();
    write_string(&mut request, 1, "atvremote");
    write_string(&mut request, 2, client_name);
    let mut msg = outer_header();
    write_message(&mut msg, 10, &request);
    msg
}

pub fn encode_options() -> Vec<u8> {
    let mut encoding = Vec::new();
    write_uint(&mut encoding, 1, u64::from(ENCODING_HEXADECIMAL));
    write_uint(&mut encoding, 2, u64::from(SYMBOL_LENGTH));
    let mut options = Vec::new();
    write_message(&mut options, 1, &encoding);
    write_uint(&mut options, 3, u64::from(ROLE_INPUT));
    let mut msg = outer_header();
    write_message(&mut msg, 20, &options);
    msg
}

pub fn encode_configuration() -> Vec<u8> {
    let mut encoding = Vec::new();
    write_uint(&mut encoding, 1, u64::from(ENCODING_HEXADECIMAL));
    write_uint(&mut encoding, 2, u64::from(SYMBOL_LENGTH));
    let mut configuration = Vec::new();
    write_message(&mut configuration, 1, &encoding);
    write_uint(&mut configuration, 2, u64::from(ROLE_INPUT));
    let mut msg = outer_header();
    write_message(&mut msg, 30, &configuration);
    msg
}

pub fn encode_secret(secret: &[u8]) -> Vec<u8> {
    let mut body = Vec::new();
    write_bytes(&mut body, 1, secret);
    let mut msg = outer_header();
    write_message(&mut msg, 40, &body);
    msg
}

#[cfg(test)]
pub fn encode_status(status: u32) -> Vec<u8> {
    let mut msg = Vec::new();
    write_uint(&mut msg, 1, u64::from(PROTOCOL_VERSION));
    write_uint(&mut msg, 2, u64::from(status));
    msg
}

#[cfg(test)]
pub fn encode_configuration_ack() -> Vec<u8> {
    let mut msg = outer_header();
    write_message(&mut msg, 31, &[]);
    msg
}

#[cfg(test)]
pub fn encode_secret_ack(secret: &[u8]) -> Vec<u8> {
    let mut body = Vec::new();
    write_bytes(&mut body, 1, secret);
    let mut msg = outer_header();
    write_message(&mut msg, 41, &body);
    msg
}

#[cfg(test)]
pub fn encode_pairing_request_ack(server_name: &str) -> Vec<u8> {
    let mut ack = Vec::new();
    write_string(&mut ack, 1, server_name);
    let mut msg = outer_header();
    write_message(&mut msg, 11, &ack);
    msg
}

#[cfg(test)]
pub fn encode_server_options() -> Vec<u8> {
    let mut encoding = Vec::new();
    write_uint(&mut encoding, 1, u64::from(ENCODING_HEXADECIMAL));
    write_uint(&mut encoding, 2, u64::from(SYMBOL_LENGTH));
    let mut options = Vec::new();
    write_message(&mut options, 2, &encoding);
    let mut msg = outer_header();
    write_message(&mut msg, 20, &options);
    msg
}

pub fn decode_outer(bytes: &[u8]) -> Result<OuterMessage, AtvError> {
    let mut message = OuterMessage {
        protocol_version: 0,
        status: 0,
        pairing_request: None,
        pairing_request_ack: None,
        options: false,
        configuration: false,
        configuration_ack: false,
        secret: None,
        secret_ack: false,
    };
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (1, 0) => message.protocol_version = field.varint as u32,
            (2, 0) => message.status = field.varint as u32,
            (10, 2) => message.pairing_request = Some(decode_pairing_request(field.bytes)?),
            (11, 2) => message.pairing_request_ack = Some(decode_pairing_ack(field.bytes)?),
            (20, 2) => message.options = true,
            (30, 2) => message.configuration = true,
            (31, 2) => message.configuration_ack = true,
            (40, 2) => message.secret = Some(decode_secret(field.bytes)?),
            (41, 2) => message.secret_ack = true,
            _ => {}
        }
    }
    Ok(message)
}

pub fn encode_configure_reply(active: i32) -> Vec<u8> {
    let mut info = Vec::new();
    write_uint(&mut info, 3, 1);
    write_string(&mut info, 4, "1");
    write_string(&mut info, 5, "atvremote");
    write_string(&mut info, 6, "1.0.0");
    let mut configure = Vec::new();
    write_int(&mut configure, 1, active);
    write_message(&mut configure, 2, &info);
    let mut msg = Vec::new();
    write_message(&mut msg, 1, &configure);
    msg
}

#[cfg(test)]
pub fn encode_configure_request(code1: i32, vendor: &str, model: &str, version: &str) -> Vec<u8> {
    let mut info = Vec::new();
    write_string(&mut info, 1, model);
    write_string(&mut info, 2, vendor);
    write_string(&mut info, 6, version);
    let mut configure = Vec::new();
    write_int(&mut configure, 1, code1);
    write_message(&mut configure, 2, &info);
    let mut msg = Vec::new();
    write_message(&mut msg, 1, &configure);
    msg
}

pub fn encode_set_active(active: i32) -> Vec<u8> {
    let mut body = Vec::new();
    write_int(&mut body, 1, active);
    let mut msg = Vec::new();
    write_message(&mut msg, 2, &body);
    msg
}

#[cfg(test)]
pub fn encode_ping_request(val1: i32, val2: i32) -> Vec<u8> {
    let mut body = Vec::new();
    write_int(&mut body, 1, val1);
    write_int(&mut body, 2, val2);
    let mut msg = Vec::new();
    write_message(&mut msg, 8, &body);
    msg
}

pub fn encode_ping_response(val1: i32) -> Vec<u8> {
    let mut body = Vec::new();
    write_int(&mut body, 1, val1);
    let mut msg = Vec::new();
    write_message(&mut msg, 9, &body);
    msg
}

pub fn encode_key(key_code: u32, direction: u32) -> Vec<u8> {
    let mut body = Vec::new();
    write_uint(&mut body, 1, u64::from(key_code));
    write_uint(&mut body, 2, u64::from(direction));
    let mut msg = Vec::new();
    write_message(&mut msg, 10, &body);
    msg
}

#[cfg(test)]
pub fn encode_volume(level: u32, max: u32, muted: bool) -> Vec<u8> {
    let mut body = Vec::new();
    write_uint(&mut body, 6, u64::from(max));
    write_uint(&mut body, 7, u64::from(level));
    if muted {
        write_uint(&mut body, 8, 1);
    }
    let mut msg = Vec::new();
    write_message(&mut msg, 50, &body);
    msg
}

#[cfg(test)]
pub fn encode_start(started: bool) -> Vec<u8> {
    let mut body = Vec::new();
    if started {
        write_uint(&mut body, 1, 1);
    }
    let mut msg = Vec::new();
    write_message(&mut msg, 40, &body);
    msg
}

#[cfg(test)]
pub fn encode_ime_key(package: &str, value: &str) -> Vec<u8> {
    let mut app = Vec::new();
    write_string(&mut app, 12, package);
    let mut text = Vec::new();
    write_string(&mut text, 2, value);
    let mut body = Vec::new();
    write_message(&mut body, 1, &app);
    write_message(&mut body, 2, &text);
    let mut msg = Vec::new();
    write_message(&mut msg, 20, &body);
    msg
}

#[cfg(test)]
pub fn encode_ime_batch(ime_counter: i32, field_counter: i32) -> Vec<u8> {
    let mut body = Vec::new();
    write_int(&mut body, 1, ime_counter);
    write_int(&mut body, 2, field_counter);
    let mut msg = Vec::new();
    write_message(&mut msg, 21, &body);
    msg
}

pub fn encode_text(ime_counter: i32, field_counter: i32, text: &str) -> Vec<u8> {
    let chars = i32::try_from(text.chars().count().saturating_sub(1)).unwrap_or(i32::MAX);
    let mut object = Vec::new();
    write_int(&mut object, 1, chars);
    write_int(&mut object, 2, chars);
    write_string(&mut object, 3, text);
    let mut edit = Vec::new();
    write_uint(&mut edit, 1, 1);
    write_message(&mut edit, 2, &object);
    let mut body = Vec::new();
    write_int(&mut body, 1, ime_counter);
    write_int(&mut body, 2, field_counter);
    write_message(&mut body, 3, &edit);
    let mut msg = Vec::new();
    write_message(&mut msg, 21, &body);
    msg
}

#[cfg(test)]
pub fn encode_ime_show(value: &str, label: &str) -> Vec<u8> {
    let mut text = Vec::new();
    write_string(&mut text, 2, value);
    write_string(&mut text, 6, label);
    let mut body = Vec::new();
    write_message(&mut body, 2, &text);
    let mut msg = Vec::new();
    write_message(&mut msg, 22, &body);
    msg
}

pub fn encode_app_link(uri: &str) -> Vec<u8> {
    let mut body = Vec::new();
    write_string(&mut body, 1, uri);
    let mut msg = Vec::new();
    write_message(&mut msg, 90, &body);
    msg
}

#[cfg(test)]
pub fn encode_remote_error() -> Vec<u8> {
    let mut body = Vec::new();
    write_uint(&mut body, 1, 1);
    let mut msg = Vec::new();
    write_message(&mut msg, 3, &body);
    msg
}

pub fn decode_remote(bytes: &[u8]) -> Result<Vec<RemoteEvent>, AtvError> {
    let mut events = Vec::new();
    for field in fields(bytes)? {
        if field.wire != 2 {
            continue;
        }
        match field.number {
            1 => events.push(decode_configure(field.bytes)?),
            2 => events.push(RemoteEvent::SetActive {
                active: first_int(field.bytes, 1)?.unwrap_or(0),
            }),
            3 => events.push(RemoteEvent::Error),
            8 => events.push(RemoteEvent::Ping {
                val1: first_int(field.bytes, 1)?.unwrap_or(0),
            }),
            20 => events.push(decode_ime_key(field.bytes)?),
            21 => events.push(decode_ime_batch(field.bytes)?),
            22 => events.push(decode_ime_show(field.bytes)?),
            40 => events.push(RemoteEvent::Start {
                started: first_bool(field.bytes, 1)?,
            }),
            50 => events.push(decode_volume(field.bytes)?),
            _ => {}
        }
    }
    Ok(events)
}

#[cfg(test)]
pub fn decode_key(bytes: &[u8]) -> Result<Option<(u32, u32)>, AtvError> {
    for field in fields(bytes)? {
        if field.number == 10 && field.wire == 2 {
            let mut key_code = None;
            let mut direction = None;
            for inner in fields(field.bytes)? {
                match (inner.number, inner.wire) {
                    (1, 0) => key_code = Some(inner.varint as u32),
                    (2, 0) => direction = Some(inner.varint as u32),
                    _ => {}
                }
            }
            return Ok(Some((key_code.unwrap_or(0), direction.unwrap_or(0))));
        }
    }
    Ok(None)
}

#[cfg(test)]
pub fn decode_app_link(bytes: &[u8]) -> Result<Option<String>, AtvError> {
    for field in fields(bytes)? {
        if field.number == 90 && field.wire == 2 {
            return first_string(field.bytes, 1);
        }
    }
    Ok(None)
}

#[cfg(test)]
pub fn decode_ping_response(bytes: &[u8]) -> Result<Option<i32>, AtvError> {
    for field in fields(bytes)? {
        if field.number == 9 && field.wire == 2 {
            return first_int(field.bytes, 1);
        }
    }
    Ok(None)
}

/// Maps a remote key name to an Android keycode.
///
/// A `KEYCODE_` prefix selects the Android enum value: `KEYCODE_ENTER` is 66
/// and `KEYCODE_MUTE` is microphone mute 91. Bare names then apply convenience
/// aliases: `ENTER`, `OK`, and `CENTER` are dpad center 23, and `MUTE` is
/// speaker mute 164 (`VOLUME_MUTE`). Decimal keycodes are accepted as written.
/// Supported names are the constants in this module plus those aliases.
pub fn key_code_from_name(name: &str) -> Option<u32> {
    let trimmed = name.trim();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.bytes().all(|byte| byte.is_ascii_digit()) {
        return trimmed.parse().ok();
    }
    let upper = trimmed.to_ascii_uppercase();
    if let Some(exact) = upper.strip_prefix("KEYCODE_") {
        return exact_key_code(exact);
    }
    Some(match upper.as_str() {
        "HOME" => KEY_HOME,
        "BACK" => KEY_BACK,
        "DPAD_UP" | "UP" => KEY_DPAD_UP,
        "DPAD_DOWN" | "DOWN" => KEY_DPAD_DOWN,
        "DPAD_LEFT" | "LEFT" => KEY_DPAD_LEFT,
        "DPAD_RIGHT" | "RIGHT" => KEY_DPAD_RIGHT,
        "DPAD_CENTER" | "CENTER" | "ENTER" | "OK" => KEY_DPAD_CENTER,
        "VOLUME_UP" => KEY_VOLUME_UP,
        "VOLUME_DOWN" => KEY_VOLUME_DOWN,
        "VOLUME_MUTE" | "MUTE" => KEY_VOLUME_MUTE,
        "POWER" => KEY_POWER,
        "MENU" => KEY_MENU,
        "SEARCH" => KEY_SEARCH,
        "MEDIA_PLAY_PAUSE" | "PLAY_PAUSE" => KEY_MEDIA_PLAY_PAUSE,
        "MEDIA_STOP" | "STOP" => KEY_MEDIA_STOP,
        "MEDIA_NEXT" | "NEXT" => KEY_MEDIA_NEXT,
        "MEDIA_PREVIOUS" | "PREVIOUS" => KEY_MEDIA_PREVIOUS,
        "CAPTIONS" => KEY_CAPTIONS,
        "SETTINGS" => KEY_SETTINGS,
        "TV_INPUT" => KEY_TV_INPUT,
        _ => return None,
    })
}

fn exact_key_code(name: &str) -> Option<u32> {
    Some(match name {
        "UNKNOWN" => 0,
        "HOME" => KEY_HOME,
        "BACK" => KEY_BACK,
        "DPAD_UP" => KEY_DPAD_UP,
        "DPAD_DOWN" => KEY_DPAD_DOWN,
        "DPAD_LEFT" => KEY_DPAD_LEFT,
        "DPAD_RIGHT" => KEY_DPAD_RIGHT,
        "DPAD_CENTER" => KEY_DPAD_CENTER,
        "VOLUME_UP" => KEY_VOLUME_UP,
        "VOLUME_DOWN" => KEY_VOLUME_DOWN,
        "POWER" => KEY_POWER,
        "ENTER" => KEY_ENTER,
        "DEL" => 67,
        "MENU" => KEY_MENU,
        "SEARCH" => KEY_SEARCH,
        "MEDIA_PLAY_PAUSE" => KEY_MEDIA_PLAY_PAUSE,
        "MEDIA_STOP" => KEY_MEDIA_STOP,
        "MEDIA_NEXT" => KEY_MEDIA_NEXT,
        "MEDIA_PREVIOUS" => KEY_MEDIA_PREVIOUS,
        "MEDIA_REWIND" => 89,
        "MEDIA_FAST_FORWARD" => 90,
        "MUTE" => KEY_MUTE,
        "MEDIA_PLAY" => 126,
        "MEDIA_PAUSE" => 127,
        "VOLUME_MUTE" => KEY_VOLUME_MUTE,
        "CAPTIONS" => KEY_CAPTIONS,
        "SETTINGS" => KEY_SETTINGS,
        "TV_POWER" => 177,
        "TV_INPUT" => KEY_TV_INPUT,
        "SLEEP" => 223,
        "WAKEUP" => 224,
        _ => return None,
    })
}

struct Field<'a> {
    number: u32,
    wire: u8,
    varint: u64,
    bytes: &'a [u8],
}

fn fields(bytes: &[u8]) -> Result<Vec<Field<'_>>, AtvError> {
    let mut out = Vec::new();
    let mut index = 0;
    while index < bytes.len() {
        let (key, next) = read_varint(bytes, index)?;
        index = next;
        let number_bits = key >> 3;
        if number_bits == 0 || number_bits > u64::from(u32::MAX >> 3) {
            return Err(AtvError::Protocol(
                "protobuf field number is invalid".into(),
            ));
        }
        let number = number_bits as u32;
        let wire = (key & 7) as u8;
        match wire {
            0 => {
                let (varint, next) = read_varint(bytes, index)?;
                index = next;
                out.push(Field {
                    number,
                    wire,
                    varint,
                    bytes: &[],
                });
            }
            1 => {
                if index + 8 > bytes.len() {
                    return Err(AtvError::Protocol("truncated protobuf field".into()));
                }
                index += 8;
            }
            2 => {
                let (length, next) = read_varint(bytes, index)?;
                index = next;
                let length = usize::try_from(length)
                    .map_err(|_| AtvError::Protocol("protobuf field is too large".into()))?;
                let end = index
                    .checked_add(length)
                    .ok_or_else(|| AtvError::Protocol("protobuf field is too large".into()))?;
                if end > bytes.len() {
                    return Err(AtvError::Protocol("truncated protobuf field".into()));
                }
                out.push(Field {
                    number,
                    wire,
                    varint: 0,
                    bytes: &bytes[index..end],
                });
                index = end;
            }
            5 => {
                if index + 4 > bytes.len() {
                    return Err(AtvError::Protocol("truncated protobuf field".into()));
                }
                index += 4;
            }
            _ => {
                return Err(AtvError::Protocol(format!(
                    "unsupported protobuf wire type {wire}"
                )));
            }
        }
    }
    Ok(out)
}

fn outer_header() -> Vec<u8> {
    let mut msg = Vec::new();
    write_uint(&mut msg, 1, u64::from(PROTOCOL_VERSION));
    write_uint(&mut msg, 2, u64::from(STATUS_OK));
    msg
}

fn decode_pairing_request(bytes: &[u8]) -> Result<PairingRequest, AtvError> {
    let mut request = PairingRequest {
        service_name: String::new(),
        client_name: String::new(),
    };
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (1, 2) => request.service_name = utf8(field.bytes)?,
            (2, 2) => request.client_name = utf8(field.bytes)?,
            _ => {}
        }
    }
    Ok(request)
}

fn decode_pairing_ack(bytes: &[u8]) -> Result<PairingRequestAck, AtvError> {
    Ok(PairingRequestAck {
        server_name: first_string(bytes, 1)?,
    })
}

fn decode_secret(bytes: &[u8]) -> Result<Vec<u8>, AtvError> {
    for field in fields(bytes)? {
        if field.number == 1 && field.wire == 2 {
            return Ok(field.bytes.to_vec());
        }
    }
    Ok(Vec::new())
}

fn decode_configure(bytes: &[u8]) -> Result<RemoteEvent, AtvError> {
    let mut code1 = 0;
    let mut model = None;
    let mut vendor = None;
    let mut app_version = None;
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (1, 0) => code1 = field.varint as i32,
            (2, 2) => {
                for info in fields(field.bytes)? {
                    match (info.number, info.wire) {
                        (1, 2) => model = Some(utf8(info.bytes)?),
                        (2, 2) => vendor = Some(utf8(info.bytes)?),
                        (6, 2) => app_version = Some(utf8(info.bytes)?),
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }
    Ok(RemoteEvent::Configure {
        code1,
        model,
        vendor,
        app_version,
    })
}

fn decode_volume(bytes: &[u8]) -> Result<RemoteEvent, AtvError> {
    let mut level = 0;
    let mut max = 0;
    let mut muted = false;
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (6, 0) => max = field.varint as u32,
            (7, 0) => level = field.varint as u32,
            (8, 0) => muted = field.varint != 0,
            _ => {}
        }
    }
    Ok(RemoteEvent::Volume { level, max, muted })
}

fn decode_ime_key(bytes: &[u8]) -> Result<RemoteEvent, AtvError> {
    let mut package = None;
    let mut text = None;
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (1, 2) => package = first_string(field.bytes, 12)?,
            (2, 2) => text = Some(decode_text_field(field.bytes)?),
            _ => {}
        }
    }
    Ok(RemoteEvent::Ime {
        package,
        text,
        ime_counter: None,
        field_counter: None,
    })
}

fn decode_ime_batch(bytes: &[u8]) -> Result<RemoteEvent, AtvError> {
    // Proto3 omits zero scalars. A present batch edit still carries both counters.
    let mut ime_counter = 0;
    let mut field_counter = 0;
    let mut text = None;
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (1, 0) => ime_counter = field.varint as i32,
            (2, 0) => field_counter = field.varint as i32,
            (3, 2) => {
                if let Some(object) = nested_bytes(field.bytes, 2)? {
                    text = Some(decode_ime_object(object)?);
                }
            }
            _ => {}
        }
    }
    Ok(RemoteEvent::Ime {
        package: None,
        text,
        ime_counter: Some(ime_counter),
        field_counter: Some(field_counter),
    })
}

fn decode_ime_show(bytes: &[u8]) -> Result<RemoteEvent, AtvError> {
    let text = nested_bytes(bytes, 2)?.map(decode_text_field).transpose()?;
    Ok(RemoteEvent::Ime {
        package: None,
        text,
        ime_counter: None,
        field_counter: None,
    })
}

fn decode_text_field(bytes: &[u8]) -> Result<TextField, AtvError> {
    let mut text = TextField {
        value: None,
        label: None,
        start: None,
        end: None,
    };
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (2, 2) => text.value = Some(utf8(field.bytes)?),
            (3, 0) => text.start = Some(field.varint as i32),
            (4, 0) => text.end = Some(field.varint as i32),
            (6, 2) => text.label = Some(utf8(field.bytes)?),
            _ => {}
        }
    }
    Ok(text)
}

fn decode_ime_object(bytes: &[u8]) -> Result<TextField, AtvError> {
    let mut text = TextField {
        value: None,
        label: None,
        start: None,
        end: None,
    };
    for field in fields(bytes)? {
        match (field.number, field.wire) {
            (1, 0) => text.start = Some(field.varint as i32),
            (2, 0) => text.end = Some(field.varint as i32),
            (3, 2) => text.value = Some(utf8(field.bytes)?),
            _ => {}
        }
    }
    Ok(text)
}

fn first_int(bytes: &[u8], number: u32) -> Result<Option<i32>, AtvError> {
    for field in fields(bytes)? {
        if field.number == number && field.wire == 0 {
            return Ok(Some(field.varint as i32));
        }
    }
    Ok(None)
}

fn first_bool(bytes: &[u8], number: u32) -> Result<bool, AtvError> {
    Ok(first_int(bytes, number)?.unwrap_or(0) != 0)
}

fn first_string(bytes: &[u8], number: u32) -> Result<Option<String>, AtvError> {
    for field in fields(bytes)? {
        if field.number == number && field.wire == 2 {
            return Ok(Some(utf8(field.bytes)?));
        }
    }
    Ok(None)
}

fn nested_bytes(bytes: &[u8], number: u32) -> Result<Option<&[u8]>, AtvError> {
    for field in fields(bytes)? {
        if field.number == number && field.wire == 2 {
            return Ok(Some(field.bytes));
        }
    }
    Ok(None)
}

fn utf8(bytes: &[u8]) -> Result<String, AtvError> {
    String::from_utf8(bytes.to_vec())
        .map_err(|_| AtvError::Protocol("protobuf string is not UTF-8".into()))
}

fn take_varint(buf: &[u8]) -> Result<Option<(u64, usize)>, AtvError> {
    let mut value = 0u64;
    let mut shift = 0;
    for (index, byte) in buf.iter().copied().enumerate() {
        if index >= 10 {
            return Err(AtvError::Protocol(
                "frame length is not a valid varint".into(),
            ));
        }
        // The tenth byte holds only bit 63 and must end the varint.
        if index == 9 && (byte & 0x80 != 0 || byte & 0x7f > 1) {
            return Err(AtvError::Protocol("varint is out of range".into()));
        }
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(Some((value, index + 1)));
        }
        shift += 7;
    }
    Ok(None)
}

fn read_varint(buf: &[u8], index: usize) -> Result<(u64, usize), AtvError> {
    let Some((value, consumed)) = take_varint(&buf[index..])? else {
        return Err(AtvError::Protocol("truncated protobuf field".into()));
    };
    Ok((value, index + consumed))
}

fn write_varint(buf: &mut Vec<u8>, mut value: u64) {
    loop {
        let mut byte = (value & 0x7f) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        buf.push(byte);
        if value == 0 {
            break;
        }
    }
}

fn write_key(buf: &mut Vec<u8>, field: u32, wire: u8) {
    write_varint(buf, (u64::from(field) << 3) | u64::from(wire));
}

fn write_uint(buf: &mut Vec<u8>, field: u32, value: u64) {
    write_key(buf, field, 0);
    write_varint(buf, value);
}

fn write_int(buf: &mut Vec<u8>, field: u32, value: i32) {
    write_uint(buf, field, value as i64 as u64);
}

fn write_bytes(buf: &mut Vec<u8>, field: u32, value: &[u8]) {
    write_key(buf, field, 2);
    write_varint(buf, value.len() as u64);
    buf.extend_from_slice(value);
}

fn write_string(buf: &mut Vec<u8>, field: u32, value: &str) {
    write_bytes(buf, field, value.as_bytes());
}

fn write_message(buf: &mut Vec<u8>, field: u32, value: &[u8]) {
    write_bytes(buf, field, value);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn round_trip_outer(payload: Vec<u8>, check: impl FnOnce(&OuterMessage)) {
        let frame = encode_frame(&payload).unwrap();
        let mut decoder = FrameDecoder::new();
        let frames = decoder.push(&frame).unwrap();
        assert_eq!(frames.len(), 1);
        decoder.finish().unwrap();
        check(&decode_outer(&frames[0]).unwrap());
    }

    #[test]
    fn polo_messages_round_trip() {
        round_trip_outer(encode_pairing_request("PlayBridge"), |message| {
            assert_eq!(message.protocol_version, 2);
            assert_eq!(message.status, STATUS_OK);
            let request = message.pairing_request.as_ref().unwrap();
            assert_eq!(request.service_name, "atvremote");
            assert_eq!(request.client_name, "PlayBridge");
        });
        round_trip_outer(encode_pairing_request_ack("Living Room"), |message| {
            assert_eq!(
                message
                    .pairing_request_ack
                    .as_ref()
                    .unwrap()
                    .server_name
                    .as_deref(),
                Some("Living Room")
            );
        });
        round_trip_outer(encode_options(), |message| assert!(message.options));
        round_trip_outer(encode_server_options(), |message| assert!(message.options));
        round_trip_outer(encode_configuration(), |message| {
            assert!(message.configuration)
        });
        round_trip_outer(encode_configuration_ack(), |message| {
            assert!(message.configuration_ack)
        });
        round_trip_outer(encode_secret(&[0x11, 0x22]), |message| {
            assert_eq!(message.secret.as_deref(), Some(&[0x11, 0x22][..]));
        });
        round_trip_outer(encode_secret_ack(&[0x11, 0x22]), |message| {
            assert!(message.secret_ack);
        });
        let bad = decode_outer(&encode_status(STATUS_BAD_SECRET)).unwrap();
        assert_eq!(bad.status, STATUS_BAD_SECRET);
    }

    #[test]
    fn remote_messages_round_trip() {
        let configure =
            decode_remote(&encode_configure_request(615, "Sony", "Bravia", "2")).unwrap();
        assert_eq!(
            configure,
            vec![RemoteEvent::Configure {
                code1: 615,
                model: Some("Bravia".into()),
                vendor: Some("Sony".into()),
                app_version: Some("2".into()),
            }]
        );
        let reply = decode_remote(&encode_configure_reply(CLIENT_FEATURES)).unwrap();
        match &reply[0] {
            RemoteEvent::Configure { code1, .. } => assert_eq!(*code1, CLIENT_FEATURES),
            other => panic!("unexpected {other:?}"),
        }
        assert_eq!(
            decode_remote(&encode_set_active(CLIENT_FEATURES)).unwrap(),
            vec![RemoteEvent::SetActive {
                active: CLIENT_FEATURES
            }]
        );
        assert_eq!(
            decode_remote(&encode_ping_request(7, 9)).unwrap(),
            vec![RemoteEvent::Ping { val1: 7 }]
        );
        assert_eq!(
            decode_ping_response(&encode_ping_response(7)).unwrap(),
            Some(7)
        );
        assert_eq!(
            decode_key(&encode_key(KEY_DPAD_UP, DIR_SHORT)).unwrap(),
            Some((KEY_DPAD_UP, DIR_SHORT))
        );
        assert_eq!(
            decode_key(&encode_key(KEY_HOME, DIR_START_LONG)).unwrap(),
            Some((KEY_HOME, DIR_START_LONG))
        );
        assert_eq!(
            decode_key(&encode_key(KEY_BACK, DIR_END_LONG)).unwrap(),
            Some((KEY_BACK, DIR_END_LONG))
        );
        assert_eq!(
            decode_remote(&encode_volume(18, 100, false)).unwrap(),
            vec![RemoteEvent::Volume {
                level: 18,
                max: 100,
                muted: false,
            }]
        );
        assert_eq!(
            decode_remote(&encode_volume(0, 100, true)).unwrap(),
            vec![RemoteEvent::Volume {
                level: 0,
                max: 100,
                muted: true,
            }]
        );
        assert_eq!(
            decode_remote(&encode_start(true)).unwrap(),
            vec![RemoteEvent::Start { started: true }]
        );
        assert_eq!(
            decode_remote(&encode_start(false)).unwrap(),
            vec![RemoteEvent::Start { started: false }]
        );
        match &decode_remote(&encode_ime_key("com.example.app", "hi")).unwrap()[0] {
            RemoteEvent::Ime { package, text, .. } => {
                assert_eq!(package.as_deref(), Some("com.example.app"));
                assert_eq!(text.as_ref().unwrap().value.as_deref(), Some("hi"));
            }
            other => panic!("unexpected {other:?}"),
        }
        match &decode_remote(&encode_ime_batch(4, 5)).unwrap()[0] {
            RemoteEvent::Ime {
                ime_counter,
                field_counter,
                ..
            } => {
                assert_eq!(*ime_counter, Some(4));
                assert_eq!(*field_counter, Some(5));
            }
            other => panic!("unexpected {other:?}"),
        }
        match &decode_remote(&encode_text(4, 5, "hi")).unwrap()[0] {
            RemoteEvent::Ime {
                ime_counter,
                field_counter,
                text,
                ..
            } => {
                assert_eq!(*ime_counter, Some(4));
                assert_eq!(*field_counter, Some(5));
                let text = text.as_ref().unwrap();
                assert_eq!(text.value.as_deref(), Some("hi"));
                assert_eq!(text.start, Some(1));
                assert_eq!(text.end, Some(1));
            }
            other => panic!("unexpected {other:?}"),
        }
        match &decode_remote(&encode_ime_show("query", "Search")).unwrap()[0] {
            RemoteEvent::Ime { text, .. } => {
                let text = text.as_ref().unwrap();
                assert_eq!(text.value.as_deref(), Some("query"));
                assert_eq!(text.label.as_deref(), Some("Search"));
            }
            other => panic!("unexpected {other:?}"),
        }
        assert_eq!(
            decode_app_link(&encode_app_link("https://www.youtube.com")).unwrap(),
            Some("https://www.youtube.com".into())
        );
        assert_eq!(
            decode_remote(&encode_remote_error()).unwrap(),
            vec![RemoteEvent::Error]
        );
    }

    #[test]
    fn frames_reject_oversized_and_truncated_bytes() {
        let mut decoder = FrameDecoder::new();
        let err = decoder.push(&[0xff, 0xff, 0xff, 0xff, 0x0f]).unwrap_err();
        assert!(matches!(err, AtvError::Protocol(_)));

        let mut decoder = FrameDecoder::new();
        assert!(decoder.push(&[0x05, 0x01, 0x02]).unwrap().is_empty());
        assert!(decoder.finish().is_err());

        let payload = encode_pairing_request("A");
        let frame = encode_frame(&payload).unwrap();
        let mut decoder = FrameDecoder::new();
        assert!(decoder.push(&frame[..1]).unwrap().is_empty());
        let frames = decoder.push(&frame[1..]).unwrap();
        assert_eq!(frames.len(), 1);
        decoder.finish().unwrap();

        let mut joined = encode_frame(&encode_ping_response(1)).unwrap();
        joined.extend(encode_frame(&encode_ping_response(2)).unwrap());
        let mut decoder = FrameDecoder::new();
        assert_eq!(decoder.push(&joined).unwrap().len(), 2);
    }

    #[test]
    fn key_names_cover_the_remote_pad() {
        assert_eq!(key_code_from_name("DPAD_UP"), Some(KEY_DPAD_UP));
        assert_eq!(key_code_from_name("keycode_home"), Some(KEY_HOME));
        assert_eq!(key_code_from_name("back"), Some(KEY_BACK));
        assert_eq!(key_code_from_name("26"), Some(KEY_POWER));
        assert_eq!(key_code_from_name("not-a-key"), None);
        assert_eq!(key_code_from_name("KEYCODE_ENTER"), Some(KEY_ENTER));
        assert_eq!(key_code_from_name("KEYCODE_MUTE"), Some(KEY_MUTE));
        assert_eq!(
            key_code_from_name("KEYCODE_VOLUME_MUTE"),
            Some(KEY_VOLUME_MUTE)
        );
        assert_eq!(key_code_from_name("ENTER"), Some(KEY_DPAD_CENTER));
        assert_eq!(key_code_from_name("MUTE"), Some(KEY_VOLUME_MUTE));
    }

    #[test]
    fn omitted_zero_ime_counters_are_present() {
        let empty = decode_remote(&[0xaa, 0x01, 0x00]).unwrap();
        assert!(matches!(
            &empty[0],
            RemoteEvent::Ime {
                ime_counter: Some(0),
                field_counter: Some(0),
                ..
            }
        ));
        let partial = decode_remote(&[0xaa, 0x01, 0x02, 0x08, 0x09]).unwrap();
        assert!(matches!(
            &partial[0],
            RemoteEvent::Ime {
                ime_counter: Some(9),
                field_counter: Some(0),
                ..
            }
        ));
    }

    #[test]
    fn malformed_lengths_are_protocol_errors() {
        let huge = decode_remote(&[
            0x0a, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01,
        ]);
        assert!(matches!(huge, Err(AtvError::Protocol(_))));

        let mut decoder = FrameDecoder::new();
        let overflow = decoder.push(&[0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x02]);
        assert!(matches!(overflow, Err(AtvError::Protocol(_))));
    }

    #[test]
    fn negative_int32_is_sign_extended() {
        let frame = encode_frame(&encode_ping_response(-1)).unwrap();
        let expected = [
            0x0d, 0x4a, 0x0b, 0x08, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01,
        ];
        assert_eq!(frame, expected);
    }
}
