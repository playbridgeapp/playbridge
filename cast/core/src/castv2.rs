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

#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
#[cfg(target_os = "android")]
use tokio::net::TcpSocket;

const CONNECT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
const MAX_CAST_MESSAGE_SIZE: usize = 1024 * 1024;
static CONTROL_REQUEST_ID: AtomicU32 = AtomicU32::new(10_000);

fn next_control_request_id() -> u32 {
    CONTROL_REQUEST_ID.fetch_add(1, Ordering::Relaxed)
}

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
    pub fn new(
        destination_id: impl Into<String>,
        namespace: impl Into<String>,
        payload: impl Into<String>,
    ) -> Self {
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
                    let end = pos.checked_add(len as usize)?;
                    if end > bytes.len() {
                        return None;
                    }
                    let str_val = std::str::from_utf8(&bytes[pos..end]).ok()?.to_string();
                    pos = end;

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
    encode_varint(buf, field << 3);
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
    session_id: Option<&str>,
) -> String {
    build_load_payload_with_stream_type(
        content_url,
        content_type,
        "BUFFERED",
        title,
        art_url,
        start_seconds,
        request_id,
        session_id,
        None,
        None,
    )
}

#[allow(clippy::too_many_arguments)]
pub fn build_load_payload_with_stream_type(
    content_url: &str,
    content_type: Option<&str>,
    stream_type: &str,
    title: Option<&str>,
    art_url: Option<&str>,
    start_seconds: f64,
    request_id: u32,
    session_id: Option<&str>,
    hls_segment_format: Option<&str>,
    hls_video_segment_format: Option<&str>,
) -> String {
    let mut media = json!({
        "contentId": content_url,
        "contentUrl": content_url,
        "streamType": stream_type,
    });

    if let Some(ct) = content_type {
        media["contentType"] = json!(ct);
    }
    if let Some(format) = hls_segment_format {
        media["hlsSegmentFormat"] = json!(format);
    }
    if let Some(format) = hls_video_segment_format {
        media["hlsVideoSegmentFormat"] = json!(format);
    }

    let mut metadata = json!({ "metadataType": 1 });
    if let Some(t) = title {
        metadata["title"] = json!(t);
    }
    if let Some(art) = art_url {
        metadata["images"] = json!([{ "url": art }]);
    }
    media["metadata"] = metadata;

    let mut load_obj = json!({
        "type": "LOAD",
        "media": media,
        "autoplay": true,
        "currentTime": start_seconds,
        "requestId": request_id,
    });

    if let Some(sid) = session_id {
        load_obj["sessionId"] = json!(sid);
    }

    load_obj.to_string()
}

/// Build JSON payload for PLAY, PAUSE, STOP media commands.
pub fn build_media_command_payload(
    command: &str,
    media_session_id: u32,
    request_id: u32,
) -> String {
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
        Self::connect_with_network_handle(address, port, None).await
    }

    /// Connects to a CastV2 endpoint, optionally binding the TCP socket to an
    /// Android `Network#getNetworkHandle()` before connect. This keeps local
    /// Cast traffic on Wi-Fi when the app's default route is a VPN.
    pub async fn connect_with_network_handle(
        address: &str,
        port: u16,
        network_handle: Option<u64>,
    ) -> Result<Self, String> {
        let builder = native_tls::TlsConnector::builder()
            .danger_accept_invalid_certs(true)
            .danger_accept_invalid_hostnames(true)
            .build()
            .map_err(|e| format!("Failed to build native TLS connector: {e}"))?;

        let connector = tokio_native_tls::TlsConnector::from(builder);
        let addr = crate::net::socket_endpoint(address, port);
        let tcp = connect_tcp(&addr, network_handle).await?;

        let tls = tokio::time::timeout(CONNECT_TIMEOUT, connector.connect(address, tcp))
            .await
            .map_err(|_| format!("TLS handshake with Chromecast at {addr} timed out"))?
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
        if len == 0 || len > MAX_CAST_MESSAGE_SIZE {
            return Err(format!("Invalid CastV2 frame length: {len}"));
        }
        let mut buf = vec![0u8; len];
        self.stream
            .read_exact(&mut buf)
            .await
            .map_err(|e| format!("Failed to read frame body: {e}"))?;

        CastMessage::decode(&buf).ok_or_else(|| "Failed to decode CastMessage protobuf".to_string())
    }

    /// Responds to receiver heartbeat pings. Returns true when the message was handled.
    pub async fn handle_heartbeat(&mut self, message: &CastMessage) -> Result<bool, String> {
        if message.namespace != NS_HEARTBEAT {
            return Ok(false);
        }
        let payload: serde_json::Value = serde_json::from_str(&message.payload_utf8)
            .map_err(|error| format!("Invalid CastV2 heartbeat payload: {error}"))?;
        if payload["type"] == "PING" {
            let pong = CastMessage::new(
                &message.source_id,
                NS_HEARTBEAT,
                json!({ "type": "PONG" }).to_string(),
            );
            self.send_message(&pong).await?;
        }
        Ok(true)
    }
}

async fn connect_tcp(addr: &str, network_handle: Option<u64>) -> Result<TcpStream, String> {
    #[cfg(target_os = "android")]
    if let Some(network_handle) = network_handle.filter(|handle| *handle != 0) {
        match connect_tcp_on_android_network(addr, network_handle).await {
            Ok(stream) => return Ok(stream),
            Err(AndroidNetworkConnectError::Connect(error)) => return Err(error),
            Err(AndroidNetworkConnectError::Bind(bind_error)) => {
                return connect_tcp_on_android_default_network(addr).await.map_err(
                    |default_error| android_network_fallback_error(&bind_error, &default_error),
                );
            }
        }
    }

    #[cfg(not(target_os = "android"))]
    let _ = network_handle;

    tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| format!("Timed out connecting to Chromecast at {addr}"))?
        .map_err(|error| format!("Failed to connect TCP to {addr}: {error}"))
}

#[cfg(any(target_os = "android", test))]
#[derive(Debug, Clone, PartialEq, Eq)]
enum AndroidNetworkConnectError {
    Bind(String),
    Connect(String),
}

#[cfg(any(target_os = "android", test))]
fn android_network_fallback_error(bind_error: &str, default_error: &str) -> String {
    format!("{bind_error}; retrying with Android's default network also failed: {default_error}")
}

#[cfg(target_os = "android")]
async fn connect_tcp_on_android_default_network(addr: &str) -> Result<TcpStream, String> {
    tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| format!("Timed out connecting to Chromecast at {addr}"))?
        .map_err(|error| format!("Failed to connect TCP to {addr}: {error}"))
}

#[cfg(target_os = "android")]
async fn connect_tcp_on_android_network(
    addr: &str,
    network_handle: u64,
) -> Result<TcpStream, AndroidNetworkConnectError> {
    let endpoints = tokio::net::lookup_host(addr)
        .await
        .map_err(|error| {
            AndroidNetworkConnectError::Connect(format!(
                "Failed to resolve Chromecast endpoint {addr}: {error}"
            ))
        })?
        .collect::<Vec<_>>();
    if endpoints.is_empty() {
        return Err(AndroidNetworkConnectError::Connect(format!(
            "Chromecast endpoint {addr} resolved to no addresses"
        )));
    }

    let mut last_error = format!("Failed to connect TCP to {addr}");
    for endpoint in endpoints {
        let socket = if endpoint.is_ipv4() {
            TcpSocket::new_v4()
        } else {
            TcpSocket::new_v6()
        }
        .map_err(|error| {
            AndroidNetworkConnectError::Connect(format!(
                "Failed to create Chromecast socket for {endpoint}: {error}"
            ))
        })?;
        bind_socket_to_android_network(&socket, network_handle).map_err(|error| {
            AndroidNetworkConnectError::Bind(format!(
                "Failed to bind Chromecast socket to the Android local network: {error}. A VPN may be preventing local-network access"
            ))
        })?;
        match tokio::time::timeout(CONNECT_TIMEOUT, socket.connect(endpoint)).await {
            Ok(Ok(stream)) => return Ok(stream),
            Ok(Err(error)) => {
                last_error = format!("Failed to connect TCP to {endpoint}: {error}");
            }
            Err(_) => {
                last_error = format!("Timed out connecting to Chromecast at {endpoint}");
            }
        }
    }
    Err(AndroidNetworkConnectError::Connect(last_error))
}

#[cfg(target_os = "android")]
fn bind_socket_to_android_network(socket: &TcpSocket, network_handle: u64) -> std::io::Result<()> {
    #[link(name = "android")]
    unsafe extern "C" {
        fn android_setsocknetwork(network: u64, fd: std::os::raw::c_int) -> std::os::raw::c_int;
    }

    let result = unsafe { android_setsocknetwork(network_handle, socket.as_raw_fd()) };
    if result == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

pub async fn send_stop_session(channel: &mut CastChannel, session_id: &str) -> Result<(), String> {
    let stop_msg = CastMessage::new(
        RECEIVER_ID,
        NS_RECEIVER,
        json!({
            "type": "STOP",
            "sessionId": session_id,
            "requestId": next_control_request_id(),
        })
        .to_string(),
    );
    channel.send_message(&stop_msg).await
}

pub async fn send_stop_media(
    channel: &mut CastChannel,
    destination_id: &str,
    media_session_id: i64,
) -> Result<(), String> {
    let msg = CastMessage::new(
        destination_id,
        NS_MEDIA,
        json!({
            "type": "STOP",
            "mediaSessionId": media_session_id,
            "requestId": next_control_request_id(),
        })
        .to_string(),
    );
    channel.send_message(&msg).await
}

pub async fn send_pause(
    channel: &mut CastChannel,
    destination_id: &str,
    media_session_id: i64,
) -> Result<(), String> {
    let msg = CastMessage::new(
        destination_id,
        NS_MEDIA,
        json!({
            "type": "PAUSE",
            "mediaSessionId": media_session_id,
            "requestId": next_control_request_id(),
        })
        .to_string(),
    );
    channel.send_message(&msg).await
}

pub async fn send_play(
    channel: &mut CastChannel,
    destination_id: &str,
    media_session_id: i64,
) -> Result<(), String> {
    let msg = CastMessage::new(
        destination_id,
        NS_MEDIA,
        json!({
            "type": "PLAY",
            "mediaSessionId": media_session_id,
            "requestId": next_control_request_id(),
        })
        .to_string(),
    );
    channel.send_message(&msg).await
}

pub async fn send_seek(
    channel: &mut CastChannel,
    destination_id: &str,
    media_session_id: i64,
    current_time_secs: f64,
) -> Result<(), String> {
    let msg = CastMessage::new(
        destination_id,
        NS_MEDIA,
        json!({
            "type": "SEEK",
            "mediaSessionId": media_session_id,
            "currentTime": current_time_secs,
            "requestId": next_control_request_id(),
        })
        .to_string(),
    );
    channel.send_message(&msg).await
}

pub async fn send_volume(channel: &mut CastChannel, level: f32) -> Result<(), String> {
    let msg = CastMessage::new(
        RECEIVER_ID,
        NS_RECEIVER,
        json!({
            "type": "SET_VOLUME",
            "volume": { "level": level.clamp(0.0, 1.0) },
            "requestId": next_control_request_id(),
        })
        .to_string(),
    );
    channel.send_message(&msg).await
}

pub async fn send_get_media_status(
    channel: &mut CastChannel,
    destination_id: &str,
) -> Result<(), String> {
    let msg = CastMessage::new(
        destination_id,
        NS_MEDIA,
        json!({
            "type": "GET_STATUS",
            "requestId": next_control_request_id(),
        })
        .to_string(),
    );
    channel.send_message(&msg).await
}

pub async fn send_heartbeat_ping(channel: &mut CastChannel) -> Result<(), String> {
    let ping = CastMessage::new(
        RECEIVER_ID,
        NS_HEARTBEAT,
        json!({ "type": "PING" }).to_string(),
    );
    channel.send_message(&ping).await
}

pub struct CastSessionDetails {
    pub channel: CastChannel,
    pub app_id: String,
    pub transport_id: String,
    pub session_id: String,
    pub media_session_id: Option<i64>,
}

/// Failure returned while sending or confirming a media LOAD request.
///
/// Keep transport failures distinct so persistent session consumers can tear
/// down a broken CastV2 socket and establish a genuinely fresh connection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LoadMediaError {
    Transport(String),
    Rejected(String),
    ReceiverUnresponsive,
}

impl std::fmt::Display for LoadMediaError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Transport(message) | Self::Rejected(message) => formatter.write_str(message),
            Self::ReceiverUnresponsive => {
                formatter.write_str("Chromecast did not confirm the LOAD request")
            }
        }
    }
}

impl std::error::Error for LoadMediaError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SessionLaunchStrategy {
    /// Always tear down lingering sessions and launch a fresh receiver app (ideal for one-shot CLI commands).
    #[default]
    ForceRelaunch,
    /// Reuse active session if running; otherwise launch new session (ideal for GUI apps with persistent media servers).
    ReuseOrLaunch,
}

pub async fn cast_media(
    address: &str,
    port: u16,
    media_url: &str,
    title: Option<&str>,
) -> Result<(), String> {
    let _ = cast_media_session(address, port, media_url, title).await?;
    Ok(())
}

pub async fn cast_media_session(
    address: &str,
    port: u16,
    media_url: &str,
    title: Option<&str>,
) -> Result<CastChannel, String> {
    let details = cast_media_session_with_strategy(
        address,
        port,
        media_url,
        title,
        SessionLaunchStrategy::ForceRelaunch,
    )
    .await?;
    Ok(details.channel)
}

pub async fn cast_media_session_with_details(
    address: &str,
    port: u16,
    media_url: &str,
    title: Option<&str>,
) -> Result<CastSessionDetails, String> {
    cast_media_session_with_strategy(
        address,
        port,
        media_url,
        title,
        SessionLaunchStrategy::ForceRelaunch,
    )
    .await
}

#[allow(clippy::collapsible_if)]
pub async fn cast_media_session_with_strategy(
    address: &str,
    port: u16,
    media_url: &str,
    title: Option<&str>,
    strategy: SessionLaunchStrategy,
) -> Result<CastSessionDetails, String> {
    let mut details =
        launch_app_session_with_strategy(address, port, DEFAULT_MEDIA_RECEIVER_APP_ID, strategy)
            .await?;
    let (content_type, stream_type) = media_format(media_url);
    load_media(
        &mut details,
        media_url,
        Some(content_type),
        stream_type,
        title,
        None,
        0.0,
        None,
        None,
    )
    .await
    .map_err(|error| error.to_string())?;
    Ok(details)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ReceiverApplication {
    pub(crate) transport_id: String,
    pub(crate) session_id: String,
}

pub(crate) fn matching_receiver_application(
    payload: &serde_json::Value,
    app_id: &str,
) -> Option<ReceiverApplication> {
    if payload["type"] != "RECEIVER_STATUS" {
        return None;
    }
    payload["status"]["applications"]
        .as_array()?
        .iter()
        .find_map(|application| {
            if application["appId"].as_str()? != app_id {
                return None;
            }
            let transport_id = application["transportId"].as_str()?.to_owned();
            if transport_id.is_empty() {
                return None;
            }
            Some(ReceiverApplication {
                transport_id,
                session_id: application["sessionId"]
                    .as_str()
                    .unwrap_or_default()
                    .to_owned(),
            })
        })
}

pub(crate) fn is_connection_close(message: &CastMessage) -> bool {
    if message.namespace != NS_CONNECTION {
        return false;
    }
    serde_json::from_str::<serde_json::Value>(&message.payload_utf8)
        .is_ok_and(|payload| payload["type"] == "CLOSE")
}

/// Launches or joins a receiver application and confirms that its media
/// namespace is answering. No media is loaded by this operation.
pub async fn launch_app_session(
    address: &str,
    port: u16,
    app_id: &str,
) -> Result<CastSessionDetails, String> {
    launch_app_session_with_strategy(address, port, app_id, SessionLaunchStrategy::ReuseOrLaunch)
        .await
}

#[allow(clippy::collapsible_if)]
pub async fn launch_app_session_with_strategy(
    address: &str,
    port: u16,
    app_id: &str,
    strategy: SessionLaunchStrategy,
) -> Result<CastSessionDetails, String> {
    launch_app_session_with_strategy_on_network(address, port, app_id, strategy, None).await
}

/// Launches or joins a receiver application over an optional Android network.
/// Non-Android callers should pass `None` and retain the platform default route.
#[allow(clippy::collapsible_if)]
pub async fn launch_app_session_with_strategy_on_network(
    address: &str,
    port: u16,
    app_id: &str,
    strategy: SessionLaunchStrategy,
    network_handle: Option<u64>,
) -> Result<CastSessionDetails, String> {
    if app_id.trim().is_empty() {
        return Err("Google Cast application ID must not be empty".into());
    }
    let mut channel =
        CastChannel::connect_with_network_handle(address, port, network_handle).await?;
    let req_gen = RequestIdGenerator::new();

    let conn_msg = CastMessage::new(RECEIVER_ID, NS_CONNECTION, build_connect_payload());
    channel.send_message(&conn_msg).await?;

    let status_req_id = req_gen.next();
    let get_status_msg = CastMessage::new(
        RECEIVER_ID,
        NS_RECEIVER,
        json!({
            "type": "GET_STATUS",
            "requestId": status_req_id,
        })
        .to_string(),
    );
    let _ = channel.send_message(&get_status_msg).await;

    let mut application = None;
    let status_deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(2);
    while tokio::time::Instant::now() < status_deadline {
        if let Ok(Ok(msg)) = tokio::time::timeout(
            std::time::Duration::from_millis(400),
            channel.read_message(),
        )
        .await
        {
            if msg.namespace == NS_HEARTBEAT {
                channel.handle_heartbeat(&msg).await?;
                continue;
            }
            if msg.namespace == NS_RECEIVER {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(&msg.payload_utf8) {
                    application = matching_receiver_application(&v, app_id);
                    if application.is_some() {
                        break;
                    }
                }
            }
        }
    }

    if strategy == SessionLaunchStrategy::ForceRelaunch || application.is_none() {
        if let Some(existing) = application.take() {
            if strategy == SessionLaunchStrategy::ForceRelaunch && !existing.session_id.is_empty() {
                let stop_msg = CastMessage::new(
                    RECEIVER_ID,
                    NS_RECEIVER,
                    json!({
                        "type": "STOP",
                            "sessionId": existing.session_id,
                        "requestId": req_gen.next(),
                    })
                    .to_string(),
                );
                let _ = channel.send_message(&stop_msg).await;
                tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            }
        }

        let launch_req_id = req_gen.next();
        let launch_msg = CastMessage::new(
            RECEIVER_ID,
            NS_RECEIVER,
            build_launch_payload(app_id, launch_req_id),
        );
        channel.send_message(&launch_msg).await?;

        let launch_deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);
        while tokio::time::Instant::now() < launch_deadline {
            if let Ok(Ok(msg)) =
                tokio::time::timeout(std::time::Duration::from_secs(1), channel.read_message())
                    .await
            {
                if msg.namespace == NS_HEARTBEAT {
                    channel.handle_heartbeat(&msg).await?;
                    continue;
                }
                if msg.namespace == NS_RECEIVER {
                    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&msg.payload_utf8) {
                        if v["requestId"].as_u64() == Some(u64::from(launch_req_id))
                            && matches!(
                                v["type"].as_str(),
                                Some("LAUNCH_ERROR" | "INVALID_REQUEST")
                            )
                        {
                            return Err(format!(
                                "Chromecast rejected receiver application launch: {}",
                                v["reason"].as_str().unwrap_or("unknown reason")
                            ));
                        }
                        application = matching_receiver_application(&v, app_id);
                        if application.is_some() {
                            break;
                        }
                    }
                }
            }
        }
    }

    let application = application.ok_or_else(|| {
        format!(
            "Failed to launch Google Cast receiver application {app_id} (no transportId returned)"
        )
    })?;

    let app_conn_msg = CastMessage::new(
        &application.transport_id,
        NS_CONNECTION,
        build_connect_payload(),
    );
    channel.send_message(&app_conn_msg).await?;

    confirm_media_channel_ready(&mut channel, &application.transport_id, &req_gen).await?;

    Ok(CastSessionDetails {
        channel,
        app_id: app_id.to_owned(),
        transport_id: application.transport_id,
        session_id: application.session_id,
        media_session_id: None,
    })
}

async fn confirm_media_channel_ready(
    channel: &mut CastChannel,
    transport_id: &str,
    request_ids: &RequestIdGenerator,
) -> Result<(), String> {
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
    while tokio::time::Instant::now() < deadline {
        let request_id = request_ids.next();
        channel
            .send_message(&CastMessage::new(
                transport_id,
                NS_MEDIA,
                json!({ "type": "GET_STATUS", "requestId": request_id }).to_string(),
            ))
            .await?;

        let attempt_deadline =
            (tokio::time::Instant::now() + std::time::Duration::from_millis(750)).min(deadline);
        while tokio::time::Instant::now() < attempt_deadline {
            let remaining = attempt_deadline.saturating_duration_since(tokio::time::Instant::now());
            let Ok(result) = tokio::time::timeout(remaining, channel.read_message()).await else {
                break;
            };
            let message = result?;
            if message.namespace == NS_HEARTBEAT {
                channel.handle_heartbeat(&message).await?;
                continue;
            }
            if message.namespace != NS_MEDIA {
                continue;
            }
            let payload: serde_json::Value = serde_json::from_str(&message.payload_utf8)
                .map_err(|error| format!("Invalid Google Cast media status: {error}"))?;
            if payload["requestId"]
                .as_u64()
                .is_some_and(|id| id != u64::from(request_id))
            {
                continue;
            }
            if payload["type"] == "MEDIA_STATUS" {
                return Ok(());
            }
        }
    }
    Err(
        "Google Cast receiver application launched but its media channel did not become ready"
            .into(),
    )
}

/// Loads media into an already-ready receiver application.
#[allow(clippy::too_many_arguments)]
pub async fn load_media(
    details: &mut CastSessionDetails,
    media_url: &str,
    content_type: Option<&str>,
    stream_type: &str,
    title: Option<&str>,
    art_url: Option<&str>,
    start_seconds: f64,
    hls_segment_format: Option<&str>,
    hls_video_segment_format: Option<&str>,
) -> Result<i64, LoadMediaError> {
    let load_req_id = next_control_request_id();
    let load_payload = build_load_payload_with_stream_type(
        media_url,
        content_type,
        stream_type,
        title,
        art_url,
        start_seconds.max(0.0),
        load_req_id,
        if details.session_id.is_empty() {
            None
        } else {
            Some(&details.session_id)
        },
        hls_segment_format,
        hls_video_segment_format,
    );
    let load_msg = CastMessage::new(&details.transport_id, NS_MEDIA, load_payload);
    details
        .channel
        .send_message(&load_msg)
        .await
        .map_err(LoadMediaError::Transport)?;

    let mut media_session_id = None;
    let read_deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(8);
    while tokio::time::Instant::now() < read_deadline {
        let msg = match tokio::time::timeout(
            std::time::Duration::from_millis(500),
            details.channel.read_message(),
        )
        .await
        {
            Err(_) => continue,
            Ok(Err(error)) => return Err(LoadMediaError::Transport(error)),
            Ok(Ok(message)) => message,
        };
        if msg.namespace == NS_MEDIA {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&msg.payload_utf8) {
                // A persistent receiver can emit unsolicited status updates for the
                // previously loaded item. Only the response correlated to this LOAD
                // may confirm or reject the new media request.
                if !load_response_matches_request(&v, load_req_id) {
                    continue;
                }
                if matches!(v["type"].as_str(), Some("LOAD_FAILED" | "INVALID_REQUEST")) {
                    return Err(LoadMediaError::Rejected(format!(
                        "Chromecast rejected LOAD request: {}",
                        v["reason"].as_str().unwrap_or("unknown reason")
                    )));
                }
                if let Some(status) = v["status"].as_array().and_then(|a| a.first())
                    && let Some(msid) = status["mediaSessionId"].as_i64()
                {
                    media_session_id = Some(msid);
                    break;
                }
            }
        } else if msg.namespace == NS_HEARTBEAT {
            details
                .channel
                .handle_heartbeat(&msg)
                .await
                .map_err(LoadMediaError::Transport)?;
        }
    }

    let media_session_id = media_session_id.ok_or(LoadMediaError::ReceiverUnresponsive)?;
    details.media_session_id = Some(media_session_id);
    Ok(media_session_id)
}

fn load_response_matches_request(payload: &serde_json::Value, request_id: u32) -> bool {
    payload["requestId"].as_u64() == Some(u64::from(request_id))
}

pub fn media_format(media_url: &str) -> (&'static str, &'static str) {
    let path = media_url
        .split(['?', '#'])
        .next()
        .unwrap_or(media_url)
        .to_ascii_lowercase();
    if path.ends_with(".m3u8") {
        ("application/x-mpegURL", "LIVE")
    } else if path.ends_with(".webm") {
        ("video/webm", "BUFFERED")
    } else if path.ends_with(".mkv") {
        ("video/x-matroska", "BUFFERED")
    } else if path.ends_with(".ts") {
        ("video/mp2t", "LIVE")
    } else if path.ends_with(".mp3") {
        ("audio/mpeg", "BUFFERED")
    } else if path.ends_with(".m4a") {
        ("audio/mp4", "BUFFERED")
    } else {
        ("video/mp4", "BUFFERED")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn android_network_fallback_error_preserves_both_failures() {
        let error = android_network_fallback_error(
            "explicit Android network bind was rejected",
            "default route timed out",
        );

        assert!(error.contains("explicit Android network bind was rejected"));
        assert!(error.contains("default route timed out"));
    }

    #[test]
    fn android_network_connect_errors_distinguish_bind_from_connect_failures() {
        let bind = AndroidNetworkConnectError::Bind("bind rejected".into());
        let connect = AndroidNetworkConnectError::Connect("connect timed out".into());

        assert!(matches!(bind, AndroidNetworkConnectError::Bind(_)));
        assert!(matches!(connect, AndroidNetworkConnectError::Connect(_)));
    }

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
    fn rejects_truncated_length_delimited_fields() {
        let mut encoded =
            CastMessage::new("receiver-0", NS_CONNECTION, r#"{"type":"CONNECT"}"#).encode();
        encoded.pop();
        assert!(CastMessage::decode(&encoded).is_none());
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

    #[test]
    fn matches_only_the_configured_receiver_application() {
        let payload = json!({
            "type": "RECEIVER_STATUS",
            "status": {
                "applications": [
                    {
                        "appId": "OTHER",
                        "sessionId": "other-session",
                        "transportId": "other-transport"
                    },
                    {
                        "appId": "PLAY1234",
                        "sessionId": "play-session",
                        "transportId": "play-transport",
                        "isIdleScreen": true
                    }
                ]
            }
        });

        assert_eq!(
            matching_receiver_application(&payload, "PLAY1234"),
            Some(ReceiverApplication {
                session_id: "play-session".into(),
                transport_id: "play-transport".into(),
            })
        );
        assert_eq!(matching_receiver_application(&payload, "MISSING"), None);
    }

    #[test]
    fn recognizes_receiver_application_connection_close() {
        assert!(is_connection_close(&CastMessage::new(
            "sender-0",
            NS_CONNECTION,
            json!({ "type": "CLOSE" }).to_string(),
        )));
        assert!(!is_connection_close(&CastMessage::new(
            "sender-0",
            NS_HEARTBEAT,
            json!({ "type": "CLOSE" }).to_string(),
        )));
    }

    #[test]
    fn load_payload_carries_receiver_branding_metadata() {
        let payload = build_load_payload_with_stream_type(
            "https://example.test/live.m3u8",
            Some("application/x-mpegURL"),
            "LIVE",
            Some("Live channel"),
            Some("https://example.test/art.jpg"),
            12.5,
            7,
            Some("receiver-session"),
            Some("ts_aac"),
            Some("mpeg2_ts"),
        );
        let payload: serde_json::Value = serde_json::from_str(&payload).unwrap();
        assert_eq!(payload["media"]["metadata"]["title"], "Live channel");
        assert_eq!(
            payload["media"]["metadata"]["images"][0]["url"],
            "https://example.test/art.jpg"
        );
        assert_eq!(payload["media"]["streamType"], "LIVE");
        assert_eq!(payload["media"]["hlsSegmentFormat"], "ts_aac");
        assert_eq!(payload["media"]["hlsVideoSegmentFormat"], "mpeg2_ts");
        assert_eq!(payload["currentTime"], 12.5);
        assert_eq!(payload["sessionId"], "receiver-session");
    }

    #[test]
    fn load_confirmation_requires_the_exact_request_id() {
        assert!(load_response_matches_request(
            &json!({ "type": "MEDIA_STATUS", "requestId": 42, "status": [] }),
            42,
        ));
        assert!(!load_response_matches_request(
            &json!({ "type": "MEDIA_STATUS", "requestId": 41, "status": [] }),
            42,
        ));
        assert!(!load_response_matches_request(
            &json!({ "type": "MEDIA_STATUS", "status": [] }),
            42,
        ));
        assert!(!load_response_matches_request(
            &json!({ "type": "LOAD_FAILED", "requestId": 41 }),
            42,
        ));
    }

    #[test]
    fn infers_common_media_formats() {
        assert_eq!(
            media_format("https://example.test/live.m3u8?token=x"),
            ("application/x-mpegURL", "LIVE")
        );
        assert_eq!(
            media_format("https://example.test/movie.mkv"),
            ("video/x-matroska", "BUFFERED")
        );
    }
}
