use std::time::{Duration, Instant};

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
    upnp::{MediaFacts, Renderer, action_code},
};

const OPERATION_TIMEOUT: Duration = Duration::from_secs(8);
const DLNA_RESUME_WAIT: Duration = Duration::from_secs(10);
const DLNA_RESUME_POLL: Duration = Duration::from_millis(500);
// Mirror: 3s preflight + two sequences of (three 8s SetURI + 8s Stop)
// + 8s Play = 75s. Buffered: 3s + four 8s SetURI + 8s Stop + 8s Play
// + 10s resume wait + 8s Seek = 69s. Reserve 5s scheduling margin.
pub const DLNA_LOAD_TIMEOUT: Duration = Duration::from_secs(80);

#[derive(Debug, Clone, PartialEq)]
pub struct MediaRequest {
    pub url: String,
    pub title: Option<String>,
    pub metadata: Option<String>,
    pub content_type: Option<String>,
    pub art_url: Option<String>,
    pub start_seconds: f64,
    pub duration_seconds: Option<f64>,
    pub stream_type: Option<String>,
    pub hls_segment_format: Option<String>,
    pub hls_video_segment_format: Option<String>,
    pub is_screen_mirror: bool,
    pub fallback_url: Option<String>,
    pub fallback_content_type: Option<String>,
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
            duration_seconds: None,
            stream_type: None,
            hls_segment_format: None,
            hls_video_segment_format: None,
            is_screen_mirror: false,
            fallback_url: None,
            fallback_content_type: None,
        }
    }

    fn dlna_is_live(&self) -> bool {
        self.is_screen_mirror
            || self
                .stream_type
                .as_deref()
                .unwrap_or_else(|| castv2::media_format(&self.url).1)
                .eq_ignore_ascii_case("LIVE")
    }

    fn dlna_is_hls(&self) -> bool {
        let path = self
            .url
            .split(['?', '#'])
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase();
        self.dlna_content_type()
            .to_ascii_lowercase()
            .contains("mpegurl")
            || path.ends_with(".m3u8")
            || path.ends_with(".m3u")
    }

    fn dlna_content_type(&self) -> &str {
        if self.is_screen_mirror {
            return "video/mpeg";
        }
        self.content_type
            .as_deref()
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| castv2::media_format(&self.url).0)
            .split(';')
            .next()
            .unwrap_or_default()
            .trim()
    }

    fn dlna_allows_empty_metadata_retry(&self) -> bool {
        let generated = self
            .metadata
            .as_deref()
            .is_none_or(|metadata| metadata.trim().is_empty());
        generated && !self.dlna_is_live() && !self.dlna_is_hls() && !self.is_screen_mirror
    }

    fn dlna_metadata(&self) -> String {
        if let Some(metadata) = &self.metadata
            && !metadata.trim().is_empty()
            && !self.is_screen_mirror
        {
            return metadata.clone();
        }
        let title = self.title.as_deref().unwrap_or("PlayBridge media");
        let content_type = self.dlna_content_type();
        let category = content_type.split('/').next().unwrap_or_default();
        let class = match category.to_ascii_lowercase().as_str() {
            "audio" => "object.item.audioItem.musicTrack",
            "image" => "object.item.imageItem.photo",
            _ => "object.item.videoItem",
        };
        let features = if self.dlna_is_live() {
            "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        } else {
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        };
        let duration = self
            .duration_seconds
            .filter(|_| !self.dlna_is_live())
            .filter(|seconds| seconds.is_finite() && *seconds > 0.0)
            .map(|seconds| {
                let seconds = seconds as u64;
                format!(
                    r#" duration="{}:{:02}:{:02}""#,
                    seconds / 3600,
                    (seconds / 60) % 60,
                    seconds % 60
                )
            })
            .unwrap_or_default();
        format!(
            r#"<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="0" restricted="1"><dc:title>{}</dc:title><upnp:class>{class}</upnp:class><res protocolInfo="http-get:*:{}:{features}"{duration}>{}</res></item></DIDL-Lite>"#,
            escape_xml(title),
            escape_xml(content_type),
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
    pub is_live: bool,
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
    volume_level: Option<(f32, Instant)>,
    playback_error_reported: bool,
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
            volume_level: None,
            playback_error_reported: false,
        }))
    }

    pub async fn connect_dlna(location: &str, media: &MediaRequest) -> Result<Self> {
        let renderer = timeout(Renderer::load(location), "DLNA renderer connection").await??;
        let mut session = Self::Dlna(renderer);
        session.load(media).await?;
        Ok(session)
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
            Self::Dlna(renderer) => load_dlna(renderer, media).await,
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

    pub fn supports_volume(&self) -> bool {
        match self {
            Self::GoogleCast(session) => !session.details.receiver_volume.fixed,
            Self::Dlna(renderer) => renderer.supports_volume(),
            _ => false,
        }
    }

    pub async fn set_volume(&mut self, level: f32) -> Result<()> {
        match self {
            Self::GoogleCast(session) => session.set_volume(level).await,
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
            Self::Dlna(renderer) => renderer.set_volume(level).await,
        }
    }

    /// Adjust from the receiver's current level so a sender with no volume
    /// snapshot cannot unexpectedly jump the speaker to a guessed value.
    pub async fn adjust_volume(&mut self, delta: f32) -> Result<()> {
        match self {
            Self::GoogleCast(session) => session.adjust_volume(delta).await,
            Self::Dlna(renderer) => {
                if !delta.is_finite() {
                    return Err(CastError::Protocol("volume delta must be finite".into()));
                }
                let current = renderer.volume().await?;
                renderer.set_volume((current + delta).clamp(0.0, 1.0)).await
            }
            _ => Err(CastError::Protocol(
                "relative volume is only supported by Google Cast".into(),
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
                let track_duration = position
                    .get("TrackDuration")
                    .and_then(|value| parse_dlna_time(value))
                    .filter(|duration| duration.is_finite() && *duration > 0.0)
                    .unwrap_or(0.0);
                if !renderer.facts.is_live
                    && track_duration <= 0.0
                    && renderer.facts.media_duration <= 0.0
                    && renderer.facts.media_info_attempts < 20
                {
                    renderer.facts.media_info_attempts += 1;
                    // GetMediaInfo is an optional duration probe, never a reason to fail STATUS.
                    if let Ok(info) = renderer.media_info().await {
                        renderer.facts.media_duration = info
                            .get("MediaDuration")
                            .and_then(|value| parse_dlna_time(value))
                            .filter(|duration| duration.is_finite() && *duration > 0.0)
                            .unwrap_or(0.0);
                    }
                }
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
                    duration_seconds: if renderer.facts.is_live {
                        0.0
                    } else if track_duration > 0.0 {
                        track_duration
                    } else if renderer.facts.media_duration > 0.0 {
                        renderer.facts.media_duration
                    } else {
                        renderer.facts.supplied_duration
                    },
                    is_live: renderer.facts.is_live,
                })
            }
            Self::Roku(client) => {
                let status = client.status().await?;
                Ok(PlaybackStatus {
                    state: state_from_text(&status.state),
                    position_seconds: status.position_ms as f64 / 1000.0,
                    duration_seconds: status.duration_ms as f64 / 1000.0,
                    is_live: false,
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
                                is_live: false,
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

    pub fn media_facts(
        &mut self,
        is_live: Option<bool>,
        duration_seconds: Option<f64>,
    ) -> Result<()> {
        let Self::Dlna(renderer) = self else {
            return Err(CastError::Protocol(
                "media_facts requires a DLNA session".into(),
            ));
        };
        if !renderer.facts.loaded {
            return Err(CastError::Protocol(
                "media_facts requires a current load".into(),
            ));
        }
        if duration_seconds.is_some_and(|duration| !duration.is_finite() || duration < 0.0) {
            return Err(CastError::Protocol(
                "duration must be finite and non-negative".into(),
            ));
        }
        if let Some(is_live) = is_live {
            renderer.facts.is_live = renderer.facts.is_screen_mirror || is_live;
        }
        if let Some(duration) = duration_seconds.filter(|duration| *duration > 0.0) {
            renderer.facts.supplied_duration = duration;
        }
        Ok(())
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
        let _ = self.ensure_receiver_application_active().await?;
        // A new LOAD gets one playback error even if the receiver reuses its mediaSessionId.
        self.playback_error_reported = false;
        castv2::load_media_with_options(
            &mut self.details,
            &media.url,
            castv2::LoadMediaOptions {
                content_type: media.content_type.as_deref(),
                stream_type: media.stream_type.as_deref(),
                title: media.title.as_deref(),
                art_url: media.art_url.as_deref(),
                start_seconds: media.start_seconds,
                duration_seconds: media.duration_seconds,
                hls_segment_format: media.hls_segment_format.as_deref(),
                hls_video_segment_format: media.hls_video_segment_format.as_deref(),
            },
        )
        .await
        .map_err(map_google_cast_load_error)?;
        // LOAD can receive a fresh receiver volume snapshot while awaiting its reply.
        self.volume_level = None;
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
    fn require_volume_control(&self) -> Result<()> {
        if self.details.receiver_volume.fixed {
            return Err(CastError::Protocol(
                "Google Cast volume control is unsupported".into(),
            ));
        }
        Ok(())
    }
    async fn set_volume(&mut self, level: f32) -> Result<()> {
        self.require_volume_control()?;
        let level = level.clamp(0.0, 1.0);
        self.receiver_command(json!({ "type": "SET_VOLUME", "volume": { "level": level } }))
            .await?;
        self.details.receiver_volume.level = Some(level);
        self.volume_level = Some((level, Instant::now()));
        Ok(())
    }
    async fn adjust_volume(&mut self, delta: f32) -> Result<()> {
        self.require_volume_control()?;
        // Reuse our last successfully sent level during a continuous gesture.
        // Query the receiver again after the gesture goes quiet so hardware
        // remote changes are respected on the next swipe.
        let current = match self.volume_level {
            Some((level, updated)) if updated.elapsed() < Duration::from_secs(1) => level,
            _ => {
                let level = self.ensure_receiver_application_active().await?;
                self.require_volume_control()?;
                level.ok_or_else(|| {
                    CastError::Protocol("Google Cast receiver did not report its volume".into())
                })?
            }
        };
        self.set_volume((current + delta).clamp(0.0, 1.0)).await
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
        let _ = self.ensure_receiver_application_active().await?;
        let request_id = self
            .send_media(json!({ "type": "GET_STATUS" }), false)
            .await?;
        self.wait_for_media_status(request_id).await
    }

    async fn ensure_receiver_application_active(&mut self) -> Result<Option<f32>> {
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
            self.update_receiver_volume(&payload);
            return Ok(self.details.receiver_volume.level);
        }
    }

    fn update_receiver_volume(&mut self, payload: &serde_json::Value) {
        self.details.receiver_volume.update(payload);
        if let Some(level) = self.details.receiver_volume.level {
            self.volume_level = Some((level, Instant::now()));
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
                    self.update_receiver_volume(&payload);
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
                    let state = match google_cast_playback_state(status) {
                        Err(CastError::ReceiverPlaybackError) if self.playback_error_reported => {
                            PlaybackState::Stopped
                        }
                        Err(CastError::ReceiverPlaybackError) => {
                            self.playback_error_reported = true;
                            return Err(CastError::ReceiverPlaybackError);
                        }
                        state => state?,
                    };
                    return Ok(PlaybackStatus {
                        state,
                        position_seconds: status["currentTime"].as_f64().unwrap_or(0.0),
                        duration_seconds: status["media"]["duration"].as_f64().unwrap_or(0.0),
                        is_live: false,
                    });
                }
            }
        }
    }
}

async fn load_dlna(renderer: &mut Renderer, media: &MediaRequest) -> Result<()> {
    renderer.facts = MediaFacts {
        is_live: media.dlna_is_live(),
        is_screen_mirror: media.is_screen_mirror,
        supplied_duration: media
            .duration_seconds
            .filter(|duration| duration.is_finite() && *duration > 0.0)
            .unwrap_or(0.0),
        ..MediaFacts::default()
    };
    renderer.preflight(media.dlna_content_type()).await?;
    let result = renderer
        .set_media_uri_with_retry(
            &media.url,
            &media.dlna_metadata(),
            media.dlna_allows_empty_metadata_retry(),
            !media.is_screen_mirror && media.dlna_is_hls(),
        )
        .await;
    match result {
        Err(error)
            if media.is_screen_mirror
                && action_code(&error) == Some(501)
                && matches!(&error, CastError::UpnpAction(failure) if failure.action == "SetAVTransportURI")
                && media
                    .fallback_url
                    .as_ref()
                    .is_some_and(|url| !url.trim().is_empty()) =>
        {
            let mut fallback = media.clone();
            fallback.url = media
                .fallback_url
                .as_ref()
                .expect("checked fallback")
                .clone();
            fallback.content_type = Some(
                media
                    .fallback_content_type
                    .as_deref()
                    .filter(|mime| mime.to_ascii_lowercase().contains("mpegurl"))
                    .unwrap_or("application/x-mpegURL")
                    .into(),
            );
            fallback.is_screen_mirror = false;
            fallback.stream_type = Some("LIVE".into());
            fallback.metadata = None;
            // A mirror's HLS fallback retains generated live DIDL; do not apply
            // the non-mirror HLS empty-metadata retry to it.
            renderer
                .set_media_uri_with_retry(&fallback.url, &fallback.dlna_metadata(), false, false)
                .await?;
        }
        result => result?,
    }
    renderer.facts.loaded = true;
    renderer.play().await?;
    if media.start_seconds.is_finite() && media.start_seconds > 0.0 && !renderer.facts.is_live {
        resume_dlna(renderer, media.start_seconds, DLNA_RESUME_WAIT).await;
    }
    Ok(())
}

#[cfg(test)]
#[path = "session/dlna_tests.rs"]
mod dlna_tests;

async fn resume_dlna(renderer: &Renderer, seconds: f64, wait: Duration) {
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        if let Ok(Ok(info)) = tokio::time::timeout(remaining, renderer.transport_info()).await
            && info
                .get("CurrentTransportState")
                .is_some_and(|state| state.eq_ignore_ascii_case("PLAYING"))
        {
            break;
        }
        tokio::time::sleep(
            DLNA_RESUME_POLL.min(deadline.saturating_duration_since(tokio::time::Instant::now())),
        )
        .await;
    }
    // TVs often reject an early seek; resume failure must not reject an accepted LOAD.
    let _ = renderer.seek(&format_dlna_time(seconds)).await;
}

fn google_cast_playback_state(status: &serde_json::Value) -> Result<PlaybackState> {
    let state = status["playerState"].as_str().unwrap_or("");
    if state.eq_ignore_ascii_case("idle") {
        match status["idleReason"].as_str() {
            Some("ERROR") => return Err(CastError::ReceiverPlaybackError),
            Some("CANCELLED" | "INTERRUPTED") => return Ok(PlaybackState::Stopped),
            _ => return Ok(PlaybackState::Finished),
        }
    }
    Ok(state_from_text(state))
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
    fn google_cast_idle_reasons_distinguish_completion_stop_and_error() {
        for reason in [None, Some("FINISHED")] {
            let state =
                google_cast_playback_state(&json!({"playerState": "IDLE", "idleReason": reason}))
                    .unwrap();
            assert_eq!(state, PlaybackState::Finished);
        }
        for reason in ["CANCELLED", "INTERRUPTED"] {
            assert_eq!(
                google_cast_playback_state(&json!({"playerState": "IDLE", "idleReason": reason}))
                    .unwrap(),
                PlaybackState::Stopped
            );
        }
        let error = google_cast_playback_state(&json!({"playerState": "IDLE", "idleReason": "ERROR", "url": "https://example.test/secret"})).unwrap_err();
        assert!(matches!(error, CastError::ReceiverPlaybackError));
        assert_eq!(
            error.to_string(),
            "Google Cast receiver reported a playback error"
        );
        assert_eq!(
            google_cast_playback_state(&json!({"playerState": "PLAYING", "idleReason": "ERROR"}))
                .unwrap(),
            PlaybackState::Playing
        );
    }

    async fn test_google_cast_session(
        port: u16,
        fixed: bool,
        level: Option<f32>,
    ) -> ReceiverSession {
        ReceiverSession::GoogleCast(GoogleCastSession {
            details: CastSessionDetails {
                channel: castv2::CastChannel::connect("127.0.0.1", port)
                    .await
                    .unwrap(),
                app_id: DEFAULT_MEDIA_RECEIVER_APP_ID.into(),
                transport_id: "transport".into(),
                session_id: "session".into(),
                media_session_id: None,
                receiver_volume: castv2::ReceiverVolume { level, fixed },
            },
            request_ids: RequestIdGenerator::new(),
            volume_level: None,
            playback_error_reported: false,
        })
    }

    #[tokio::test]
    async fn idle_status_uses_playback_error_path_and_refreshes_volume_capability() {
        let (port, task) = castv2::test_support::receiver(|mut receiver| async move {
            let status = receiver.request(NS_RECEIVER, "GET_STATUS").await;
            receiver.send(NS_RECEIVER, json!({"type": "RECEIVER_STATUS", "requestId": status["requestId"], "status": {"applications": [{"appId": DEFAULT_MEDIA_RECEIVER_APP_ID, "transportId": "transport", "sessionId": "session"}]}})).await;
            let media = receiver.request(NS_MEDIA, "GET_STATUS").await;
            // Receiver updates can also arrive while waiting for a MEDIA_STATUS.
            receiver.send(NS_RECEIVER, json!({"type": "RECEIVER_STATUS", "status": {"applications": [{"appId": DEFAULT_MEDIA_RECEIVER_APP_ID, "transportId": "transport", "sessionId": "session"}], "volume": {"controlType": "fixed"}}})).await;
            receiver.send(NS_MEDIA, json!({"type": "MEDIA_STATUS", "requestId": media["requestId"], "status": [{"playerState": "IDLE", "idleReason": "ERROR", "mediaSessionId": 42, "media": {"contentId": "https://example.test/secret"}}]})).await;
        }).await;
        let mut session = test_google_cast_session(port, false, Some(0.4)).await;
        assert!(matches!(
            session.status().await.unwrap_err(),
            CastError::ReceiverPlaybackError
        ));
        assert!(!session.supports_volume());
        task.await.unwrap();
    }

    #[tokio::test]
    async fn playback_error_is_reported_once_per_load_with_or_without_media_session_id() {
        for media_session_id in [Some(42), None] {
            let (port, task) = castv2::test_support::receiver(move |mut receiver| async move {
                let application = json!({"applications": [{"appId": DEFAULT_MEDIA_RECEIVER_APP_ID, "transportId": "transport", "sessionId": "session"}]});
                for _ in 0..2 {
                    let status = receiver.request(NS_RECEIVER, "GET_STATUS").await;
                    receiver.send(NS_RECEIVER, json!({"type": "RECEIVER_STATUS", "requestId": status["requestId"], "status": application})).await;
                    let load = receiver.request(NS_MEDIA, "LOAD").await;
                    // Deliberately reuse the same ID on the second LOAD.
                    receiver.send(NS_MEDIA, json!({"type": "MEDIA_STATUS", "requestId": load["requestId"], "status": [{"mediaSessionId": 42}]})).await;
                    for _ in 0..3 {
                        let status = receiver.request(NS_RECEIVER, "GET_STATUS").await;
                        receiver.send(NS_RECEIVER, json!({"type": "RECEIVER_STATUS", "requestId": status["requestId"], "status": application})).await;
                        let media = receiver.request(NS_MEDIA, "GET_STATUS").await;
                        receiver.send(NS_MEDIA, json!({"type": "MEDIA_STATUS", "requestId": media["requestId"], "status": [{"playerState": "IDLE", "idleReason": "ERROR", "mediaSessionId": media_session_id, "currentTime": 12.5, "media": {"duration": 100}}]})).await;
                    }
                }
            }).await;
            let mut session = test_google_cast_session(port, false, None).await;
            let media = MediaRequest::new("https://example.test/movie.mp4");
            for _ in 0..2 {
                session.load(&media).await.unwrap();
                assert!(matches!(
                    session.status().await.unwrap_err(),
                    CastError::ReceiverPlaybackError
                ));
                for _ in 0..2 {
                    let status = session.status().await.unwrap();
                    assert_eq!(status.state, PlaybackState::Stopped);
                    assert_eq!(status.position_seconds, 12.5);
                    assert_eq!(status.duration_seconds, 100.0);
                }
            }
            task.await.unwrap();
        }
    }

    #[tokio::test]
    async fn fixed_receiver_volume_is_unsupported_and_sends_no_commands() {
        let (port, task) = castv2::test_support::receiver(|mut receiver| async move {
            receiver.expect_no_request().await;
        })
        .await;
        let mut session = test_google_cast_session(port, true, Some(0.4)).await;
        assert!(!session.supports_volume());
        for result in [
            session.set_volume(0.5).await,
            session.adjust_volume(0.05).await,
        ] {
            assert!(result.unwrap_err().to_string().contains("unsupported"));
        }
        task.await.unwrap();
    }

    #[tokio::test]
    async fn muted_receiver_without_level_adjusts_from_last_known_level() {
        let (port, task) = castv2::test_support::receiver(|mut receiver| async move {
            let status = receiver.request(NS_RECEIVER, "GET_STATUS").await;
            receiver.send(NS_RECEIVER, json!({"type": "RECEIVER_STATUS", "requestId": status["requestId"], "status": {"applications": [{"appId": DEFAULT_MEDIA_RECEIVER_APP_ID, "transportId": "transport", "sessionId": "session"}], "volume": {"muted": true}}})).await;
            let volume = receiver.request(NS_RECEIVER, "SET_VOLUME").await;
            assert!((volume["volume"]["level"].as_f64().unwrap() - 0.45).abs() < 0.0001);
        }).await;
        let mut session = test_google_cast_session(port, false, Some(0.4)).await;
        session.adjust_volume(0.05).await.unwrap();
        task.await.unwrap();
    }

    #[tokio::test]
    async fn receiver_status_can_disable_volume_before_adjustment() {
        let (port, task) = castv2::test_support::receiver(|mut receiver| async move {
            let status = receiver.request(NS_RECEIVER, "GET_STATUS").await;
            receiver.send(NS_RECEIVER, json!({"type": "RECEIVER_STATUS", "requestId": status["requestId"], "status": {"applications": [{"appId": DEFAULT_MEDIA_RECEIVER_APP_ID, "transportId": "transport", "sessionId": "session"}], "volume": {"controlType": "fixed"}}})).await;
            receiver.expect_no_request().await;
        }).await;
        let mut session = test_google_cast_session(port, false, None).await;
        assert!(session.supports_volume());
        assert!(
            session
                .adjust_volume(0.05)
                .await
                .unwrap_err()
                .to_string()
                .contains("unsupported")
        );
        assert!(!session.supports_volume());
        task.await.unwrap();
    }

    #[test]
    fn formats_dlna_seek_time() {
        assert_eq!(format_dlna_time(3661.9), "01:01:01");
    }

    #[test]
    fn dlna_metadata_uses_mime_class_features_and_duration() {
        for (mime, class) in [
            ("video/quicktime", "object.item.videoItem"),
            ("audio/flac", "object.item.audioItem.musicTrack"),
            ("image/png", "object.item.imageItem.photo"),
        ] {
            let mut media = MediaRequest::new("http://example.test/unknown");
            media.content_type = Some(format!("{mime}; charset=utf-8"));
            media.duration_seconds = Some(3661.9);
            let metadata = media.dlna_metadata();
            assert!(metadata.contains(&format!("<upnp:class>{class}</upnp:class>")));
            assert!(metadata.contains(&format!("http-get:*:{mime}:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")));
            assert!(metadata.contains("duration=\"1:01:01\""));
            assert!(metadata.contains("DLNA.ORG_FLAGS=01700000000000000000000000000000"));
            assert!(!metadata.contains("DLNA.ORG_PN"));
            roxmltree::Document::parse(&metadata).unwrap();
        }
    }

    #[test]
    fn dlna_omits_nonpositive_duration() {
        let mut media = MediaRequest::new("http://example.test/movie.mp4");
        for duration in [0.0, -1.0, f64::NAN, f64::INFINITY] {
            media.duration_seconds = Some(duration);
            assert!(!media.dlna_metadata().contains("duration="));
        }
    }

    #[test]
    fn empty_metadata_retry_only_applies_to_generated_non_live_non_hls_media() {
        let mut media = MediaRequest::new("http://example.test/movie.mp4");
        assert!(media.dlna_allows_empty_metadata_retry());
        media.metadata = Some("<DIDL-Lite/>".into());
        assert!(!media.dlna_allows_empty_metadata_retry());
        media.metadata = Some(String::new());
        assert!(media.dlna_allows_empty_metadata_retry());
        media.stream_type = Some("LIVE".into());
        assert!(!media.dlna_allows_empty_metadata_retry());
        for url in [
            "http://example.test/live.ts",
            "http://example.test/master.m3u8",
            "http://example.test/master.m3u",
        ] {
            let mut media = MediaRequest::new(url);
            assert!(!media.dlna_allows_empty_metadata_retry());
            media.stream_type = Some("BUFFERED".into());
            assert_eq!(
                media.dlna_allows_empty_metadata_retry(),
                url.ends_with(".ts")
            );
        }
        let mut media = MediaRequest::new("http://example.test/extensionless");
        media.stream_type = Some("BUFFERED".into());
        media.content_type = Some("application/vnd.apple.mpegurl".into());
        assert!(!media.dlna_allows_empty_metadata_retry());
    }

    #[test]
    fn dlna_live_and_unknown_duration_metadata() {
        for url in [
            "http://example.test/live.m3u8",
            "http://example.test/live.ts",
        ] {
            let mut media = MediaRequest::new(url);
            media.metadata = Some(String::new());
            let metadata = media.dlna_metadata();
            assert!(metadata.contains(
                "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            ));
            assert!(!metadata.contains("duration="));
            media.stream_type = Some("BUFFERED".into());
            assert!(media.dlna_metadata().contains("DLNA.ORG_OP=01"));
        }
        let mut media = MediaRequest::new("http://example.test/mirror");
        media.content_type = Some("video/mpeg".into());
        media.stream_type = Some("LIVE".into());
        media.duration_seconds = Some(f64::NAN);
        assert!(
            media
                .dlna_metadata()
                .contains("http-get:*:video/mpeg:DLNA.ORG_OP=00")
        );
        assert!(!media.dlna_metadata().contains("duration="));
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
