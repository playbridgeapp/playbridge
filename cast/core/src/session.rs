use std::time::Duration;

use serde_json::json;

use crate::{
    CastError, Result,
    castv2::{
        self, CastMessage, CastSessionDetails, DEFAULT_MEDIA_RECEIVER_APP_ID, NS_HEARTBEAT,
        NS_MEDIA, NS_RECEIVER, RECEIVER_ID, RequestIdGenerator, SessionLaunchStrategy,
    },
    playbridge::{ReceiverFrame, SenderFrame},
    roku::RokuClient,
    secure_ws::SecureWebSocket,
    upnp::Renderer,
};

const OPERATION_TIMEOUT: Duration = Duration::from_secs(8);

#[derive(Debug, Clone, PartialEq)]
pub struct MediaRequest {
    pub url: String,
    pub title: Option<String>,
    pub metadata: Option<String>,
    pub content_type: Option<String>,
    pub art_url: Option<String>,
    pub start_seconds: f64,
    pub stream_type: Option<String>,
    pub hls_segment_format: Option<String>,
    pub hls_video_segment_format: Option<String>,
}

impl MediaRequest {
    pub fn new(url: impl Into<String>) -> Self {
        Self {
            url: url.into(),
            title: None,
            metadata: None,
            content_type: None,
            art_url: None,
            start_seconds: 0.0,
            stream_type: None,
            hls_segment_format: None,
            hls_video_segment_format: None,
        }
    }

    fn dlna_metadata(&self) -> String {
        if let Some(metadata) = &self.metadata {
            return metadata.clone();
        }
        let title = self.title.as_deref().unwrap_or("PlayBridge media");
        let content_type = castv2::media_format(&self.url).0;
        format!(
            r#"<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="0" restricted="1"><dc:title>{}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:{}:*">{}</res></item></DIDL-Lite>"#,
            escape_xml(title),
            content_type,
            escape_xml(&self.url),
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PlaybackState {
    Buffering,
    Playing,
    Paused,
    Stopped,
    Finished,
    #[default]
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct PlaybackStatus {
    pub state: PlaybackState,
    pub position_seconds: f64,
    pub duration_seconds: f64,
}

pub enum ReceiverSession {
    GoogleCast(GoogleCastSession),
    Dlna(Renderer),
    Roku(RokuClient),
    PlayBridge(Box<SecureWebSocket>),
}

pub struct GoogleCastSession {
    details: CastSessionDetails,
    request_ids: RequestIdGenerator,
}

impl ReceiverSession {
    pub async fn connect_google_cast(
        address: &str,
        port: u16,
        application_id: Option<&str>,
    ) -> Result<Self> {
        Self::connect_google_cast_with_strategy(
            address,
            port,
            application_id,
            SessionLaunchStrategy::ReuseOrLaunch,
        )
        .await
    }

    pub async fn connect_google_cast_with_strategy(
        address: &str,
        port: u16,
        application_id: Option<&str>,
        strategy: SessionLaunchStrategy,
    ) -> Result<Self> {
        Self::connect_google_cast_with_strategy_on_network(
            address,
            port,
            application_id,
            strategy,
            None,
        )
        .await
    }

    pub async fn connect_google_cast_with_strategy_on_network(
        address: &str,
        port: u16,
        application_id: Option<&str>,
        strategy: SessionLaunchStrategy,
        network_handle: Option<u64>,
    ) -> Result<Self> {
        let details = castv2::launch_app_session_with_strategy_on_network(
            address,
            port,
            application_id.unwrap_or(DEFAULT_MEDIA_RECEIVER_APP_ID),
            strategy,
            network_handle,
        )
        .await
        .map_err(CastError::Protocol)?;
        Ok(Self::GoogleCast(GoogleCastSession {
            details,
            request_ids: RequestIdGenerator::new(),
        }))
    }

    pub async fn connect_dlna(location: &str, media: &MediaRequest) -> Result<Self> {
        let renderer = timeout(Renderer::load(location), "DLNA renderer connection").await??;
        timeout(
            renderer.set_media_uri(&media.url, &media.dlna_metadata()),
            "DLNA media load",
        )
        .await??;
        timeout(renderer.play(), "DLNA play").await??;
        Ok(Self::Dlna(renderer))
    }

    pub fn connect_roku(address: &str, port: u16) -> Result<Self> {
        Ok(Self::Roku(RokuClient::new(
            address,
            port,
            OPERATION_TIMEOUT,
        )?))
    }

    pub fn authenticated_playbridge(socket: SecureWebSocket) -> Self {
        Self::PlayBridge(Box::new(socket))
    }

    pub async fn load(&mut self, media: &MediaRequest) -> Result<()> {
        match self {
            Self::Roku(client) => {
                client
                    .launch_media(&media.url, media.title.as_deref())
                    .await
            }
            Self::PlayBridge(socket) => {
                socket
                    .send(&SenderFrame::Command {
                        action: "playlist".into(),
                        payload: Some(
                            json!({ "items": [{ "url": media.url, "title": media.title }] }),
                        ),
                    })
                    .await
            }
            Self::Dlna(renderer) => {
                renderer
                    .set_media_uri(&media.url, &media.dlna_metadata())
                    .await?;
                renderer.play().await
            }
            Self::GoogleCast(session) => session.load(media).await,
        }
    }

    pub async fn play(&mut self) -> Result<()> {
        self.set_playing(true).await
    }
    pub async fn pause(&mut self) -> Result<()> {
        self.set_playing(false).await
    }

    async fn set_playing(&mut self, playing: bool) -> Result<()> {
        match self {
            Self::GoogleCast(session) => {
                session
                    .media_command(if playing { "PLAY" } else { "PAUSE" })
                    .await
            }
            Self::Dlna(renderer) if playing => renderer.play().await,
            Self::Dlna(renderer) => renderer.pause().await,
            Self::Roku(client) => {
                let status = client.status().await.ok();
                let already = status.as_ref().is_some_and(|status| {
                    let state = status.state.to_ascii_lowercase();
                    if playing {
                        state == "play" || state == "playing"
                    } else {
                        state == "pause" || state == "paused"
                    }
                });
                if already {
                    Ok(())
                } else {
                    client.keypress("Play").await
                }
            }
            Self::PlayBridge(socket) => {
                socket
                    .send(&control(if playing { "play" } else { "pause" }))
                    .await
            }
        }
    }

    pub async fn stop(&mut self) -> Result<()> {
        match self {
            Self::GoogleCast(session) => session.stop().await,
            Self::Dlna(renderer) => renderer.stop().await,
            Self::Roku(client) => client.keypress("Stop").await,
            Self::PlayBridge(socket) => socket.send(&control("stop")).await,
        }
    }

    /// Explicitly ends a Google Cast receiver application. Normal media stop
    /// intentionally leaves the receiver ready on its idle splash.
    pub async fn end_receiver_application(&mut self) -> Result<()> {
        match self {
            Self::GoogleCast(session) => session.end_receiver_application().await,
            _ => Err(CastError::Protocol(
                "ending the receiver application is only supported by Google Cast".into(),
            )),
        }
    }

    pub async fn seek(&mut self, position_seconds: f64) -> Result<()> {
        match self {
            Self::GoogleCast(session) => session.seek(position_seconds).await,
            Self::Dlna(renderer) => renderer.seek(&format_dlna_time(position_seconds)).await,
            Self::Roku(_) => Err(CastError::Protocol(
                "Roku ECP does not support absolute seeking".into(),
            )),
            Self::PlayBridge(socket) => {
                socket
                    .send(&SenderFrame::Command {
                        action: "control".into(),
                        payload: Some(json!({ "command": format!("seek:{position_seconds}") })),
                    })
                    .await
            }
        }
    }

    pub async fn relative_seek(&mut self, forward: bool) -> Result<()> {
        match self {
            Self::Roku(client) => client.keypress(if forward { "Fwd" } else { "Rev" }).await,
            Self::PlayBridge(socket) => {
                socket
                    .send(&control(if forward { "seek_forward" } else { "seek_back" }))
                    .await
            }
            _ => {
                let status = self.status().await?;
                let delta = if forward { 10.0 } else { -10.0 };
                self.seek((status.position_seconds + delta).max(0.0)).await
            }
        }
    }

    pub async fn set_volume(&mut self, level: f32) -> Result<()> {
        match self {
            Self::GoogleCast(session) => session
                .receiver_command(
                    json!({ "type": "SET_VOLUME", "volume": { "level": level.clamp(0.0, 1.0) } }),
                )
                .await,
            Self::PlayBridge(socket) => {
                socket
                    .send(&remote(if level >= 0.5 {
                        "volume_up"
                    } else {
                        "volume_down"
                    }))
                    .await
            }
            Self::Roku(client) => {
                client
                    .keypress(if level >= 0.5 {
                        "VolumeUp"
                    } else {
                        "VolumeDown"
                    })
                    .await
            }
            Self::Dlna(_) => Err(CastError::Protocol(
                "DLNA volume control is not available for this session".into(),
            )),
        }
    }

    pub async fn send_playbridge_control(&mut self, command: &str) -> Result<()> {
        match self {
            Self::PlayBridge(socket) => socket.send(&control(command)).await,
            _ => Err(CastError::Protocol(
                "command is only supported by PlayBridge receivers".into(),
            )),
        }
    }

    pub async fn send_remote_key(&mut self, key: &str) -> Result<()> {
        match self {
            Self::PlayBridge(socket) => socket.send(&remote(key)).await,
            Self::Roku(client) => client.keypress(key).await,
            _ => Err(CastError::Protocol(
                "remote keys are not supported by this receiver".into(),
            )),
        }
    }

    pub async fn status(&mut self) -> Result<PlaybackStatus> {
        match self {
            Self::GoogleCast(session) => session.status().await,
            Self::Dlna(renderer) => {
                let transport = renderer.transport_info().await?;
                let position = renderer.position_info().await?;
                Ok(PlaybackStatus {
                    state: state_from_text(
                        transport
                            .get("CurrentTransportState")
                            .map(String::as_str)
                            .unwrap_or(""),
                    ),
                    position_seconds: position
                        .get("RelTime")
                        .and_then(|value| parse_dlna_time(value))
                        .unwrap_or(0.0),
                    duration_seconds: position
                        .get("TrackDuration")
                        .and_then(|value| parse_dlna_time(value))
                        .unwrap_or(0.0),
                })
            }
            Self::Roku(client) => {
                let status = client.status().await?;
                Ok(PlaybackStatus {
                    state: state_from_text(&status.state),
                    position_seconds: status.position_ms as f64 / 1000.0,
                    duration_seconds: status.duration_ms as f64 / 1000.0,
                })
            }
            Self::PlayBridge(socket) => {
                let deadline = tokio::time::Instant::now() + OPERATION_TIMEOUT;
                loop {
                    let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
                    if remaining.is_zero() {
                        return Err(CastError::Protocol("PlayBridge status timed out".into()));
                    }
                    match tokio::time::timeout(remaining, socket.receive())
                        .await
                        .map_err(|_| CastError::Protocol("PlayBridge status timed out".into()))??
                    {
                        Some(ReceiverFrame::Status {
                            state,
                            position,
                            duration,
                            ..
                        }) => {
                            return Ok(PlaybackStatus {
                                state: state_from_text(&state),
                                position_seconds: position as f64 / 1000.0,
                                duration_seconds: duration as f64 / 1000.0,
                            });
                        }
                        Some(_) => {}
                        None => {
                            return Err(CastError::Protocol(
                                "PlayBridge receiver closed the connection".into(),
                            ));
                        }
                    }
                }
            }
        }
    }

    pub fn is_playbridge(&self) -> bool {
        matches!(self, Self::PlayBridge(_))
    }
    pub fn is_google_cast(&self) -> bool {
        matches!(self, Self::GoogleCast(_))
    }
    pub fn is_dlna(&self) -> bool {
        matches!(self, Self::Dlna(_))
    }
    pub fn is_roku(&self) -> bool {
        matches!(self, Self::Roku(_))
    }
}

impl GoogleCastSession {
    async fn load(&mut self, media: &MediaRequest) -> Result<()> {
        self.ensure_receiver_application_active().await?;
        let inferred = castv2::media_format(&media.url);
        castv2::load_media(
            &mut self.details,
            &media.url,
            media.content_type.as_deref().or(Some(inferred.0)),
            media.stream_type.as_deref().unwrap_or(inferred.1),
            media.title.as_deref(),
            media.art_url.as_deref(),
            media.start_seconds,
            media.hls_segment_format.as_deref(),
            media.hls_video_segment_format.as_deref(),
        )
        .await
        .map_err(map_google_cast_load_error)?;
        Ok(())
    }

    fn media_session_id(&self) -> Result<i64> {
        self.details.media_session_id.ok_or_else(|| {
            CastError::Protocol("Google Cast receiver is ready but no media is loaded".into())
        })
    }

    async fn send_media(
        &mut self,
        mut payload: serde_json::Value,
        include_media_session: bool,
    ) -> Result<u32> {
        let request_id = self.request_ids.next();
        payload["requestId"] = json!(request_id);
        if include_media_session {
            payload["mediaSessionId"] = json!(self.media_session_id()?);
        }
        self.details
            .channel
            .send_message(&CastMessage::new(
                &self.details.transport_id,
                NS_MEDIA,
                payload.to_string(),
            ))
            .await
            .map_err(CastError::Transport)?;
        Ok(request_id)
    }
    async fn media_command(&mut self, command: &str) -> Result<()> {
        let request_id = self.send_media(json!({ "type": command }), true).await?;
        self.wait_for_media_status(request_id).await.map(|_| ())
    }
    async fn seek(&mut self, seconds: f64) -> Result<()> {
        let request_id = self
            .send_media(
                json!({ "type": "SEEK", "currentTime": seconds.max(0.0) }),
                true,
            )
            .await?;
        self.wait_for_media_status(request_id).await.map(|_| ())
    }
    async fn receiver_command(&mut self, mut payload: serde_json::Value) -> Result<()> {
        payload["requestId"] = json!(self.request_ids.next());
        self.details
            .channel
            .send_message(&CastMessage::new(
                RECEIVER_ID,
                NS_RECEIVER,
                payload.to_string(),
            ))
            .await
            .map_err(CastError::Transport)
    }
    async fn stop(&mut self) -> Result<()> {
        if self.details.media_session_id.is_some() {
            self.media_command("STOP").await?;
        }
        Ok(())
    }
    async fn end_receiver_application(&mut self) -> Result<()> {
        if self.details.session_id.is_empty() {
            return Ok(());
        }
        let session_id = self.details.session_id.clone();
        self.receiver_command(json!({ "type": "STOP", "sessionId": session_id }))
            .await
    }
    async fn status(&mut self) -> Result<PlaybackStatus> {
        self.ensure_receiver_application_active().await?;
        let request_id = self
            .send_media(json!({ "type": "GET_STATUS" }), false)
            .await?;
        self.wait_for_media_status(request_id).await
    }

    async fn ensure_receiver_application_active(&mut self) -> Result<()> {
        let request_id = self.request_ids.next();
        self.details
            .channel
            .send_message(&CastMessage::new(
                RECEIVER_ID,
                NS_RECEIVER,
                json!({ "type": "GET_STATUS", "requestId": request_id }).to_string(),
            ))
            .await
            .map_err(CastError::Transport)?;

        let deadline = tokio::time::Instant::now() + OPERATION_TIMEOUT;
        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                return Err(CastError::Protocol(
                    "Google Cast receiver application status timed out".into(),
                ));
            }
            let message = tokio::time::timeout(remaining, self.details.channel.read_message())
                .await
                .map_err(|_| {
                    CastError::Protocol("Google Cast receiver application status timed out".into())
                })?
                .map_err(CastError::Transport)?;
            if castv2::is_connection_close(&message) {
                return Err(CastError::ReceiverSessionEnded);
            }
            if message.namespace == NS_HEARTBEAT {
                self.details
                    .channel
                    .handle_heartbeat(&message)
                    .await
                    .map_err(CastError::Transport)?;
                continue;
            }
            if message.namespace != NS_RECEIVER {
                continue;
            }
            let payload: serde_json::Value = serde_json::from_str(&message.payload_utf8)
                .map_err(|error| CastError::Protocol(error.to_string()))?;
            if payload["type"] != "RECEIVER_STATUS" {
                continue;
            }
            let Some(application) =
                castv2::matching_receiver_application(&payload, &self.details.app_id)
            else {
                return Err(CastError::ReceiverSessionEnded);
            };
            let session_matches = self.details.session_id.is_empty()
                || application.session_id.is_empty()
                || application.session_id == self.details.session_id;
            if application.transport_id != self.details.transport_id || !session_matches {
                return Err(CastError::ReceiverSessionEnded);
            }
            return Ok(());
        }
    }

    async fn wait_for_media_status(&mut self, request_id: u32) -> Result<PlaybackStatus> {
        let deadline = tokio::time::Instant::now() + OPERATION_TIMEOUT;
        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                return Err(CastError::Protocol("Google Cast status timed out".into()));
            }
            let message = tokio::time::timeout(remaining, self.details.channel.read_message())
                .await
                .map_err(|_| CastError::Protocol("Google Cast status timed out".into()))?
                .map_err(CastError::Transport)?;
            if castv2::is_connection_close(&message) {
                return Err(CastError::ReceiverSessionEnded);
            }
            if message.namespace == NS_HEARTBEAT {
                self.details
                    .channel
                    .handle_heartbeat(&message)
                    .await
                    .map_err(CastError::Transport)?;
                continue;
            }
            if message.namespace == NS_RECEIVER {
                let payload: serde_json::Value = serde_json::from_str(&message.payload_utf8)
                    .map_err(|error| CastError::Protocol(error.to_string()))?;
                if payload["type"] == "RECEIVER_STATUS" {
                    let current =
                        castv2::matching_receiver_application(&payload, &self.details.app_id);
                    if current.as_ref().is_none_or(|application| {
                        application.transport_id != self.details.transport_id
                            || (!self.details.session_id.is_empty()
                                && !application.session_id.is_empty()
                                && application.session_id != self.details.session_id)
                    }) {
                        return Err(CastError::ReceiverSessionEnded);
                    }
                }
                continue;
            }
            if message.namespace == NS_MEDIA {
                let payload: serde_json::Value = serde_json::from_str(&message.payload_utf8)
                    .map_err(|error| CastError::Protocol(error.to_string()))?;
                if payload["requestId"]
                    .as_u64()
                    .is_some_and(|id| id != u64::from(request_id))
                {
                    continue;
                }
                if matches!(
                    payload["type"].as_str(),
                    Some("INVALID_REQUEST" | "LOAD_FAILED")
                ) {
                    return Err(CastError::Protocol(format!(
                        "Google Cast rejected request: {}",
                        payload["reason"].as_str().unwrap_or("unknown reason")
                    )));
                }
                if payload["type"] == "MEDIA_STATUS"
                    && payload["status"].as_array().is_some_and(Vec::is_empty)
                {
                    self.details.media_session_id = None;
                    return Ok(PlaybackStatus {
                        state: PlaybackState::Stopped,
                        ..PlaybackStatus::default()
                    });
                }
                if let Some(status) = payload["status"].as_array().and_then(|items| items.first()) {
                    if let Some(id) = status["mediaSessionId"].as_i64() {
                        self.details.media_session_id = Some(id);
                    }
                    return Ok(PlaybackStatus {
                        state: state_from_text(status["playerState"].as_str().unwrap_or("")),
                        position_seconds: status["currentTime"].as_f64().unwrap_or(0.0),
                        duration_seconds: status["media"]["duration"].as_f64().unwrap_or(0.0),
                    });
                }
            }
        }
    }
}

fn map_google_cast_load_error(error: castv2::LoadMediaError) -> CastError {
    match error {
        castv2::LoadMediaError::Transport(message) => CastError::Transport(message),
        castv2::LoadMediaError::Rejected(message) => CastError::Protocol(message),
        castv2::LoadMediaError::ReceiverUnresponsive => CastError::ReceiverSessionUnresponsive,
    }
}

fn control(command: &str) -> SenderFrame {
    SenderFrame::Command {
        action: "control".into(),
        payload: Some(json!({ "command": command })),
    }
}
fn remote(key: &str) -> SenderFrame {
    SenderFrame::Command {
        action: "remote".into(),
        payload: Some(json!({ "key": key })),
    }
}

fn state_from_text(value: &str) -> PlaybackState {
    match value.to_ascii_lowercase().as_str() {
        "play" | "playing" => PlaybackState::Playing,
        "pause" | "paused" | "paused_playback" => PlaybackState::Paused,
        "buffering" | "buffering_playback" | "transitioning" => PlaybackState::Buffering,
        "stopped" | "stopped_playback" | "none" => PlaybackState::Stopped,
        "finished" | "finished_playback" | "idle" => PlaybackState::Finished,
        _ => PlaybackState::Unknown,
    }
}

fn parse_dlna_time(value: &str) -> Option<f64> {
    let mut parts = value.trim().split(':');
    let hours = parts.next()?.parse::<f64>().ok()?;
    let minutes = parts.next()?.parse::<f64>().ok()?;
    let seconds = parts.next()?.parse::<f64>().ok()?;
    Some(hours * 3600.0 + minutes * 60.0 + seconds)
}

fn format_dlna_time(seconds: f64) -> String {
    let seconds = seconds.max(0.0) as u64;
    format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600,
        (seconds % 3600) / 60,
        seconds % 60
    )
}

fn escape_xml(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

async fn timeout<T, E>(
    future: impl std::future::Future<Output = std::result::Result<T, E>>,
    operation: &'static str,
) -> std::result::Result<std::result::Result<T, E>, CastError> {
    tokio::time::timeout(OPERATION_TIMEOUT, future)
        .await
        .map_err(|_| CastError::Protocol(format!("{operation} timed out")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_google_cast_load_acknowledgement_invalidates_the_session() {
        assert!(matches!(
            map_google_cast_load_error(castv2::LoadMediaError::ReceiverUnresponsive),
            CastError::ReceiverSessionUnresponsive,
        ));
        assert!(matches!(
            map_google_cast_load_error(castv2::LoadMediaError::Rejected(
                "Chromecast rejected LOAD request: cancelled".into(),
            )),
            CastError::Protocol(_),
        ));
        assert!(matches!(
            map_google_cast_load_error(castv2::LoadMediaError::Transport(
                "Failed to write CastMessage: socket closed".into(),
            )),
            CastError::Transport(_),
        ));
    }
    #[test]
    fn maps_common_protocol_states() {
        assert_eq!(state_from_text("PLAYING"), PlaybackState::Playing);
        assert_eq!(state_from_text("paused_playback"), PlaybackState::Paused);
    }
    #[test]
    fn formats_dlna_seek_time() {
        assert_eq!(format_dlna_time(3661.9), "01:01:01");
    }

    #[test]
    fn generates_dlna_metadata_when_the_caller_does_not_supply_it() {
        let mut media = MediaRequest::new("https://example.test/video.mp4?x=1&y=2");
        media.title = Some("One & <Two>".into());
        let metadata = media.dlna_metadata();
        assert!(metadata.contains("One &amp; &lt;Two&gt;"));
        assert!(metadata.contains("video/mp4"));
        assert!(metadata.contains("x=1&amp;y=2"));
    }
}
