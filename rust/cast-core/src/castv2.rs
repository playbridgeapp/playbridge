//! CastV2 protocol engine for Google Cast / Chromecast devices.
//!
//! CastV2 frames length-prefixed protobuf messages over a TLS connection on port 8009.
//! This module provides a lightweight, pure Rust implementation of the CastV2 wire protocol
//! and JSON payload commands (CONNECT, LAUNCH, LOAD, PLAY, PAUSED, SEEK, STOP, SET_VOLUME).

use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::atomic::{AtomicU32, Ordering};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

pub const DEFAULT_MEDIA_RECEIVER_APP_ID: &str = "CC1AD845";

pub const NS_CONNECTION: &str = "urn:x-cast:com.google.cast.tp.connection";
pub const NS_HEARTBEAT: &str = "urn:x-cast:com.google.cast.tp.heartbeat";
pub const NS_RECEIVER: &str = "urn:x-cast:com.google.cast.receiver";
pub const NS_MEDIA: &str = "urn:x-cast:com.google.cast.media";

pub const SENDER_ID: &str = "sender-0";
pub const RECEIVER_ID: &str = "receiver-0";

/// A parsed CastMessage from the CastV2 wire format.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CastMessage {
    pub source_id: String,
    pub destination_id: String,
    pub namespace: String,
    pub payload_utf8: String,
}

impl CastMessage {
    pub fn new(destination_id: impl Into<String>, namespace: impl Into<String>, payload: impl Into<String>) -> Self {
        Self {
            source_id: SENDER_ID.to_string(),
            destination_id: destination_id.into(),
            namespace: namespace.into(),
            payload_utf8: payload.into(),
        }
    }

    /// Encode the message into protobuf wire format.
    ///
    /// Wire fields:
    ///   1 (varint): protocol_version = 0
    ///   2 (string): source_id
    ///   3 (string): destination_id
    ///   4 (string): namespace
    ///   5 (varint): payload_type = 0 (STRING)
    ///   6 (string): payload_utf8
    pub fn encode(&self) -> Vec<u8> {
        let mut buf = Vec::new();

        // Field 1: protocol_version = 0 (varint)
        encode_varint_field(&mut buf, 1, 0);
        // Field 2: source_id (string)
        encode_string_field(&mut buf, 2, &self.source_id);
        // Field 3: destination_id (string)
        encode_string_field(&mut buf, 3, &self.destination_id);
        // Field 4: namespace (string)
        encode_string_field(&mut buf, 4, &self.namespace);
        // Field 5: payload_type = 0 (varint)
        encode_varint_field(&mut buf, 5, 0);
        // Field 6: payload_utf8 (string)
        encode_string_field(&mut buf, 6, &self.payload_utf8);

        buf
    }

    /// Decode a protobuf-encoded CastMessage from raw bytes.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let mut pos = 0;
        let mut source_id = String::new();
        let mut destination_id = String::new();
        let mut namespace = String::new();
        let mut payload_utf8 = String::new();

        while pos < bytes.len() {
            let (tag, new_pos) = decode_varint(bytes, pos)?;
            pos = new_pos;
            let field_number = tag >> 3;
            let wire_type = tag & 0x07;

            match wire_type {
                0 => {
                    // Varint
                    let (_, np) = decode_varint(bytes, pos)?;
                    pos = np;
                }
                2 => {
                    // Length-delimited string
                    let (len, np) = decode_varint(bytes, pos)?;
                    pos = np;
                    let end = (pos + len as usize).min(bytes.len());
                    let str_val = std::str::from_utf8(&bytes[pos..end]).ok()?.to_string();
                    pos += len as usize;

                    match field_number {
                        2 => source_id = str_val,
                        3 => destination_id = str_val,
                        4 => namespace = str_val,
                        6 => payload_utf8 = str_val,
                        _ => {}
                    }
                }
                _ => break,
            }
        }

        if !namespace.is_empty() && !payload_utf8.is_empty() {
            Some(Self {
                source_id,
                destination_id,
                namespace,
                payload_utf8,
            })
        } else {
            None
        }
    }
}

/// Helper counter for generating unique request IDs.
pub struct RequestIdGenerator(AtomicU32);

impl RequestIdGenerator {
    pub fn new() -> Self {
        Self(AtomicU32::new(1))
    }

    pub fn next(&self) -> u32 {
        self.0.fetch_add(1, Ordering::Relaxed)
    }
}

impl Default for RequestIdGenerator {
    fn default() -> Self {
        Self::new()
    }
}

// -----------------------------------------------------------------------
// Protobuf wire helper functions
// -----------------------------------------------------------------------

fn encode_varint_field(buf: &mut Vec<u8>, field: u32, val: u32) {
    encode_varint(buf, (field << 3) | 0);
    encode_varint(buf, val);
}

fn encode_string_field(buf: &mut Vec<u8>, field: u32, val: &str) {
    let bytes = val.as_bytes();
    encode_varint(buf, (field << 3) | 2);
    encode_varint(buf, bytes.len() as u32);
    buf.extend_from_slice(bytes);
}

fn encode_varint(buf: &mut Vec<u8>, mut val: u32) {
    while val > 0x7F {
        buf.push(((val & 0x7F) | 0x80) as u8);
        val >>= 7;
    }
    buf.push((val & 0x7F) as u8);
}

fn decode_varint(bytes: &[u8], mut offset: usize) -> Option<(u32, usize)> {
    let mut result = 0u32;
    let mut shift = 0;
    while offset < bytes.len() {
        let b = bytes[offset] as u32;
        result |= (b & 0x7F) << shift;
        offset += 1;
        if b & 0x80 == 0 {
            return Some((result, offset));
        }
        shift += 7;
        if shift >= 32 {
            return None;
        }
    }
    None
}

/// Build JSON payload for CONNECT message.
pub fn build_connect_payload() -> String {
    json!({ "type": "CONNECT" }).to_string()
}

/// Build JSON payload for LAUNCH message.
pub fn build_launch_payload(app_id: &str, request_id: u32) -> String {
    json!({
        "type": "LAUNCH",
        "appId": app_id,
        "requestId": request_id,
    })
    .to_string()
}

/// Build JSON payload for LOAD media message.
pub fn build_load_payload(
    content_url: &str,
    content_type: Option<&str>,
    title: Option<&str>,
    art_url: Option<&str>,
    start_seconds: f64,
    request_id: u32,
) -> String {
    let mut media = json!({
        "contentId": content_url,
        "streamType": "BUFFERED",
    });

    if let Some(ct) = content_type {
        media["contentType"] = json!(ct);
    }

    let mut metadata = json!({ "type": 0 });
    if let Some(t) = title {
        metadata["title"] = json!(t);
    }
    if let Some(art) = art_url {
        metadata["images"] = json!([{ "url": art }]);
    }
    media["metadata"] = metadata;

    json!({
        "type": "LOAD",
        "media": media,
        "autoplay": true,
        "currentTime": start_seconds,
        "requestId": request_id,
    })
    .to_string()
}

/// Build JSON payload for PLAY, PAUSE, STOP media commands.
pub fn build_media_command_payload(command: &str, media_session_id: u32, request_id: u32) -> String {
    json!({
        "type": command,
        "mediaSessionId": media_session_id,
        "requestId": request_id,
    })
    .to_string()
}

/// Build JSON payload for SEEK command.
pub fn build_seek_payload(position_seconds: f64, media_session_id: u32, request_id: u32) -> String {
    json!({
        "type": "SEEK",
        "mediaSessionId": media_session_id,
        "currentTime": position_seconds,
        "requestId": request_id,
    })
    .to_string()
}

/// Build JSON payload for SET_VOLUME command.
pub fn build_volume_payload(level: f32, request_id: u32) -> String {
    json!({
        "type": "SET_VOLUME",
        "volume": { "level": level.clamp(0.0, 1.0) },
        "requestId": request_id,
    })
    .to_string()
}

// -----------------------------------------------------------------------
// TLS CastV2 Channel & Cast Media Flow
// -----------------------------------------------------------------------

pub struct CastChannel {
    stream: tokio_native_tls::TlsStream<TcpStream>,
}

impl CastChannel {
    pub async fn connect(address: &str, port: u16) -> Result<Self, String> {
        let builder = native_tls::TlsConnector::builder()
            .danger_accept_invalid_certs(true)
            .danger_accept_invalid_hostnames(true)
            .build()
            .map_err(|e| format!("Failed to build native TLS connector: {e}"))?;

        let connector = tokio_native_tls::TlsConnector::from(builder);
        let addr = format!("{address}:{port}");
        let tcp = TcpStream::connect(&addr)
            .await
            .map_err(|e| format!("Failed to connect TCP to {addr}: {e}"))?;

        let tls = connector
            .connect(address, tcp)
            .await
            .map_err(|e| format!("TLS handshake with Chromecast failed: {e}"))?;

        Ok(Self { stream: tls })
    }

    pub async fn send_message(&mut self, msg: &CastMessage) -> Result<(), String> {
        let payload = msg.encode();
        let len = payload.len() as u32;
        let mut packet = Vec::with_capacity(4 + payload.len());
        packet.extend_from_slice(&len.to_be_bytes());
        packet.extend_from_slice(&payload);

        self.stream
            .write_all(&packet)
            .await
            .map_err(|e| format!("Failed to write CastMessage: {e}"))?;
        self.stream
            .flush()
            .await
            .map_err(|e| format!("Failed to flush CastMessage stream: {e}"))?;
        Ok(())
    }

    pub async fn read_message(&mut self) -> Result<CastMessage, String> {
        let mut len_bytes = [0u8; 4];
        self.stream
            .read_exact(&mut len_bytes)
            .await
            .map_err(|e| format!("Failed to read frame length header: {e}"))?;

        let len = u32::from_be_bytes(len_bytes) as usize;
        let mut buf = vec![0u8; len];
        self.stream
            .read_exact(&mut buf)
            .await
            .map_err(|e| format!("Failed to read frame body: {e}"))?;

        CastMessage::decode(&buf).ok_or_else(|| "Failed to decode CastMessage protobuf".to_string())
    }
}

pub async fn cast_media(
    address: &str,
    port: u16,
    media_url: &str,
    title: Option<&str>,
) -> Result<(), String> {
    let mut channel = CastChannel::connect(address, port).await?;
    let req_gen = RequestIdGenerator::new();

    // 1. Send CONNECT to receiver-0
    let conn_msg = CastMessage::new(RECEIVER_ID, NS_CONNECTION, build_connect_payload());
    channel.send_message(&conn_msg).await?;

    // 2. Launch Default Media Receiver app (CC1AD845)
    let launch_req_id = req_gen.next();
    let launch_msg = CastMessage::new(
        RECEIVER_ID,
        NS_RECEIVER,
        build_launch_payload(DEFAULT_MEDIA_RECEIVER_APP_ID, launch_req_id),
    );
    channel.send_message(&launch_msg).await?;

    // 3. Read RECEIVER_STATUS to get transportId
    let mut destination_id = RECEIVER_ID.to_string();
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);

    while tokio::time::Instant::now() < deadline {
        let msg = match tokio::time::timeout(std::time::Duration::from_secs(2), channel.read_message()).await {
            Ok(Ok(m)) => m,
            _ => continue,
        };

        if msg.namespace == NS_RECEIVER {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&msg.payload_utf8) {
                if v["type"] == "RECEIVER_STATUS" {
                    if let Some(apps) = v["status"]["applications"].as_array() {
                        for app in apps {
                            if app["appId"] == DEFAULT_MEDIA_RECEIVER_APP_ID {
                                if let Some(tid) = app["transportId"].as_str() {
                                    destination_id = tid.to_string();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            if destination_id != RECEIVER_ID {
                break;
            }
        }
    }

    // 4. Connect to target transportId
    let app_conn_msg = CastMessage::new(&destination_id, NS_CONNECTION, build_connect_payload());
    channel.send_message(&app_conn_msg).await?;

    // 5. Send LOAD message to target transportId
    let load_req_id = req_gen.next();
    let load_payload = build_load_payload(
        media_url,
        Some("video/mp4"),
        title,
        None,
        0.0,
        load_req_id,
    );
    let load_msg = CastMessage::new(&destination_id, NS_MEDIA, load_payload);
    channel.send_message(&load_msg).await?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cast_message_encode_decode() {
        let msg = CastMessage::new("receiver-0", NS_CONNECTION, r#"{"type":"CONNECT"}"#);
        let encoded = msg.encode();
        assert!(!encoded.is_empty());

        let decoded = CastMessage::decode(&encoded).expect("should decode successfully");
        assert_eq!(decoded.source_id, SENDER_ID);
        assert_eq!(decoded.destination_id, "receiver-0");
        assert_eq!(decoded.namespace, NS_CONNECTION);
        assert_eq!(decoded.payload_utf8, r#"{"type":"CONNECT"}"#);
    }

    #[test]
    fn test_request_id_generator() {
        let id_gen = RequestIdGenerator::new();
        assert_eq!(id_gen.next(), 1);
        assert_eq!(id_gen.next(), 2);
    }

    #[test]
    fn test_build_launch_payload() {
        let payload = build_launch_payload(DEFAULT_MEDIA_RECEIVER_APP_ID, 42);
        assert!(payload.contains("CC1AD845"));
        assert!(payload.contains("42"));
    }
}
