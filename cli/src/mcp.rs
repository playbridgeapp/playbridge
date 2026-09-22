use std::{collections::HashMap, process::Stdio, sync::Arc, time::Duration};

use rmcp::{
    ErrorData as McpError, ServerHandler, ServiceExt,
    handler::server::wrapper::Parameters,
    model::{CallToolResult, Implementation, ProtocolVersion, ServerCapabilities, ServerInfo},
    schemars, tool, tool_handler, tool_router,
    transport::stdio,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::{
    io::{AsyncBufRead, AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::{Child, ChildStdin, ChildStdout, Command},
    sync::Mutex,
};

use crate::{
    credentials::PlaybridgeCredentials,
    json_session::{ControlRequest, JsonCastSession, new_request_id},
};

const INSTRUCTIONS: &str = "\
PlayBridge casts local files and stream URLs to TVs and receivers on the LAN.

Workflow:
1. Call discover to list receivers.
2. Call send with target for a simple cast, or items for rich media/playlist metadata and request headers. Pass the protocol-qualified receiver id from discover when needed. One physical TV may expose multiple protocol endpoints.
3. Keep the session_id returned by send for sender-owned resources. For receiver-owned PlayBridge state use device with get_state, queue_add, queue_remove, queue_move, queue_clear, or playlist_jump.
4. If send returns error pairing_required, ask the user for the six-digit code shown on the receiver, then call submit_pair_code with that session_id. It waits for the real pairing result.
5. Use status and control for playback. PlayBridge sessions also support queue_add, playlist_jump, browser, browser_control, and remote.

Use list_paired, forget, and pair to manage this CLI sender's local receiver credentials. These tools never revoke another sender from the receiver.

send.skip_history overrides whether a PlayBridge receiver saves a cast in history. Omit it to use the persisted CLI default.

Do not invent playbridge CLI flags. Use these tools. seek seconds are relative (e.g. 60 or -10).";

pub fn usage() -> &'static str {
    "PlayBridge MCP Server\n\nUsage:\n  playbridge mcp\n\nRuns a Model Context Protocol server over stdio. Available tools:\n  discover, send, list_paired, forget, pair, submit_pair_code, status, control, get_state, queue_add, queue_remove, queue_move, queue_clear, playlist_jump, browser, browser_control, remote\n\nThe server writes MCP messages to stdout; do not use it as an interactive command."
}

#[derive(Clone)]
pub struct PlaybridgeMcp {
    send_child: Arc<Mutex<Option<ManagedSend>>>,
    command_lock: Arc<Mutex<()>>,
}

struct ManagedSend {
    child: Child,
    stdout: BufReader<ChildStdout>,
    stdin: Option<ChildStdin>,
    session_id: String,
    waiting_for_pairing: bool,
    receiver_owned_playback: bool,
}

#[derive(Debug, Serialize, schemars::JsonSchema)]
struct ToolOutput {
    ok: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    event: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pair_code_file: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    session_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    receivers: Option<Vec<ReceiverOutput>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    errors: Option<Vec<DiscoveryErrorOutput>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    device: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    protocol: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    media: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    state: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    position_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_index: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    total_count: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    volume: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    muted: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    looping: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    speed: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    request_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    command: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    updated_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    control: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    cause: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    capabilities: Option<CapabilitiesOutput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    skip_history: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    uuid: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    paired: Option<Vec<PairedOutput>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    forgotten: Option<Vec<PairedOutput>>,
}

#[derive(Debug, Serialize, schemars::JsonSchema)]
struct PairedOutput {
    uuid: String,
    name: Option<String>,
    last_used_at: Option<u64>,
    error: Option<String>,
}

#[derive(Debug, Serialize, schemars::JsonSchema)]
struct ReceiverOutput {
    id: String,
    protocol: String,
    name: String,
    addresses: Vec<String>,
    port: Option<u16>,
    wss_port: Option<u16>,
    location: Option<String>,
    uuid: Option<String>,
    paired: Option<bool>,
}

#[derive(Debug, Serialize, schemars::JsonSchema)]
struct DiscoveryErrorOutput {
    protocol: String,
    message: String,
}

#[derive(Debug, Serialize, schemars::JsonSchema)]
struct CapabilitiesOutput {
    play_pause: bool,
    seek: bool,
    volume: bool,
    mute: bool,
    looping: bool,
    speed: bool,
    audio_boost: bool,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct DiscoverParams {
    /// Scan timeout in seconds (1-300). Defaults to 5.
    #[serde(default)]
    timeout: Option<u64>,
    /// Comma-separated protocols: playbridge, dlna, roku, googlecast, or all.
    #[serde(default)]
    protocol: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct SendParams {
    /// Local file path or http(s) media URL. Shorthand for a one-item playlist.
    #[serde(default)]
    target: Option<String>,
    /// Rich PlayBridge media items. Cannot be combined with target.
    #[serde(default)]
    items: Vec<MediaItemParams>,
    /// Zero-based item to start with. Only valid with items.
    #[serde(default, alias = "startIndex")]
    start_index: Option<usize>,
    /// Playlist-level metadata used by receiver pre-play UI.
    #[serde(default, alias = "visualMetadata")]
    visual_metadata: Option<VisualMetadataParams>,
    /// Skip the receiver's metadata-rich pre-play screen.
    #[serde(default, alias = "skipPreplay")]
    skip_preplay: Option<bool>,
    /// Optional receiver id, uuid, name, or IP from discover.
    #[serde(default)]
    device: Option<String>,
    /// Override whether this cast is excluded from receiver history. Omit to use the CLI default.
    #[serde(default, alias = "skipHistory")]
    skip_history: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
struct MediaItemParams {
    /// Local file path or http(s) media URL.
    url: String,
    #[serde(default)]
    title: Option<String>,
    /// HTTP request headers, including Referer, Cookie, Authorization, or User-Agent.
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default)]
    content_type: Option<String>,
    #[serde(default)]
    subtitles: Vec<String>,
    #[serde(default)]
    subtitle_resources: Vec<SubtitleResourceParams>,
    /// video, audio, or image.
    #[serde(default)]
    media_kind: Option<String>,
    #[serde(default)]
    display_duration_ms: Option<u64>,
    #[serde(default)]
    skip_history: Option<bool>,
    #[serde(default)]
    detected_by: Option<String>,
    #[serde(default)]
    player_mode: Option<String>,
    #[serde(default)]
    preferred_audio_language: Option<String>,
    #[serde(default)]
    preferred_subtitle_language: Option<String>,
    #[serde(default)]
    default_video_quality: Option<String>,
    #[serde(default)]
    max_bitrate_cap_mbps: Option<f64>,
    #[serde(default)]
    visual_metadata: Option<VisualMetadataParams>,
    #[serde(default)]
    binge_group: Option<String>,
    #[serde(default)]
    start_position_ms: Option<u64>,
    #[serde(default)]
    allowed_private_origins: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
struct SubtitleResourceParams {
    url: String,
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default)]
    label: Option<String>,
    #[serde(default)]
    language: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
struct VisualMetadataParams {
    title: String,
    #[serde(default)]
    year: Option<String>,
    #[serde(default)]
    rating: Option<String>,
    #[serde(default)]
    runtime: Option<String>,
    #[serde(default)]
    overview: Option<String>,
    #[serde(default)]
    genres: Vec<String>,
    #[serde(default)]
    cast: Vec<String>,
    #[serde(default)]
    director: Vec<String>,
    #[serde(default)]
    backdrop_url: Option<String>,
    #[serde(default)]
    poster_url: Option<String>,
    #[serde(default)]
    logo_url: Option<String>,
    #[serde(default)]
    season: Option<i32>,
    #[serde(default)]
    episode: Option<i32>,
    #[serde(default)]
    episode_title: Option<String>,
    #[serde(default)]
    imdb_id: Option<String>,
    #[serde(default)]
    tmdb_id: Option<String>,
    #[serde(default)]
    artist: Option<String>,
    #[serde(default)]
    album: Option<String>,
    #[serde(default)]
    album_artist: Option<String>,
    #[serde(default)]
    artwork_url: Option<String>,
    #[serde(default)]
    track_number: Option<i32>,
}

impl SendParams {
    fn rich_payload(&self) -> Result<Option<Value>, String> {
        match (&self.target, self.items.is_empty()) {
            (Some(_), false) => return Err("target cannot be combined with items".into()),
            (None, true) => return Err("send requires target or at least one item".into()),
            _ => {}
        }
        if self.items.len() > 256 {
            return Err("items cannot contain more than 256 entries".into());
        }
        if let Some(index) = self.start_index
            && (self.items.is_empty() || index >= self.items.len())
        {
            return Err("start_index must select an item".into());
        }
        for item in &self.items {
            validate_media_item(item)?;
        }
        if self.items.is_empty() {
            if self.start_index.is_some()
                || self.visual_metadata.is_some()
                || self.skip_preplay.is_some()
            {
                return Err("playlist options require items".into());
            }
            return Ok(None);
        }
        let mut payload = serde_json::json!({
            "items": self.items,
            "startIndex": self.start_index.unwrap_or(0),
        });
        if let Some(metadata) = &self.visual_metadata {
            payload["visualMetadata"] = serde_json::to_value(metadata)
                .map_err(|error| format!("could not encode visualMetadata: {error}"))?;
        }
        if let Some(skip_preplay) = self.skip_preplay {
            payload["skipPreplay"] = serde_json::json!(skip_preplay);
        }
        Ok(Some(payload))
    }

    fn primary_target(&self) -> Option<&str> {
        self.target
            .as_deref()
            .or_else(|| self.items.first().map(|item| item.url.as_str()))
    }
}

fn validate_media_item(item: &MediaItemParams) -> Result<(), String> {
    if item.url.trim().is_empty() {
        return Err("media item url cannot be empty".into());
    }
    if item.headers.len() > 16 {
        return Err("media item headers cannot contain more than 16 entries".into());
    }
    if item.subtitle_resources.len() > 16 || item.allowed_private_origins.len() > 16 {
        return Err("subtitleResources and allowedPrivateOrigins are limited to 16 entries".into());
    }
    if item
        .media_kind
        .as_deref()
        .is_some_and(|kind| !matches!(kind, "video" | "audio" | "image"))
    {
        return Err("mediaKind must be video, audio, or image".into());
    }
    if item
        .max_bitrate_cap_mbps
        .is_some_and(|value| !value.is_finite() || value <= 0.0)
    {
        return Err("maxBitrateCapMbps must be greater than zero".into());
    }
    if item.visual_metadata.as_ref().is_some_and(|metadata| {
        metadata.season.is_some_and(|value| value < 0)
            || metadata.episode.is_some_and(|value| value < 0)
            || metadata.track_number.is_some_and(|value| value < 0)
    }) {
        return Err("season, episode, and trackNumber cannot be negative".into());
    }
    Ok(())
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct PairCodeParams {
    /// Six-digit SAS code shown on the PlayBridge receiver.
    code: String,
    /// Session id returned by send. Defaults to this MCP server's managed send.
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct PairParams {
    /// PlayBridge receiver id, UUID, name, or IP. Omit to use the preferred receiver.
    #[serde(default)]
    device: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct ForgetParams {
    /// Exact receiver UUID/id or an unambiguous saved receiver name.
    #[serde(default)]
    device: Option<String>,
    /// Forget every credential stored by this CLI sender. Must be explicitly true.
    #[serde(default)]
    all: bool,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
struct ListPairedParams {}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
struct StatusParams {
    /// Session id returned by send. Defaults to this MCP server's managed send.
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct ControlParams {
    /// One of: pause, play, toggle, stop, seek, volume, mute, loop, speed, audio_boost.
    command: String,
    /// Session id returned by send. Defaults to this MCP server's managed send.
    #[serde(default)]
    session_id: Option<String>,
    /// For seek: relative seconds (negative seeks backward).
    #[serde(default)]
    seconds: Option<i64>,
    /// For volume: relative delta in -1..=1.
    #[serde(default)]
    delta: Option<f32>,
    /// For speed: playback rate (e.g. 1.5).
    #[serde(default)]
    value: Option<f32>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct QueueAddParams {
    #[serde(default)]
    item: Option<MediaItemParams>,
    #[serde(default)]
    items: Vec<MediaItemParams>,
    #[serde(default)]
    if_playback_id: Option<String>,
    /// PlayBridge receiver id, UUID, or unambiguous name. Connects briefly and
    /// appends to the receiver-owned queue without taking playback ownership.
    #[serde(default)]
    device: Option<String>,
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct PlaylistJumpParams {
    #[serde(default)]
    index: Option<usize>,
    #[serde(default)]
    item_id: Option<String>,
    #[serde(default)]
    if_playback_id: Option<String>,
    /// PlayBridge receiver id, UUID, or unambiguous name. Connects briefly and
    /// changes the receiver-owned queue without taking playback ownership.
    #[serde(default)]
    device: Option<String>,
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct DeviceParams {
    device: String,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct QueueRemoveParams {
    device: String,
    item_ids: Vec<String>,
    #[serde(default)]
    if_playback_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct QueueMoveParams {
    device: String,
    item_id: String,
    #[serde(default)]
    before_item_id: Option<String>,
    #[serde(default)]
    if_playback_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct QueueClearParams {
    device: String,
    #[serde(default)]
    if_playback_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct BrowserParams {
    url: String,
    #[serde(default)]
    browser_mode: Option<String>,
    #[serde(default)]
    desktop_mode: Option<bool>,
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct BrowserControlParams {
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct RemoteParams {
    /// dpad_up, dpad_down, dpad_left, dpad_right, dpad_center, back, home, volume_up, volume_down, or mute.
    key: String,
    #[serde(default)]
    session_id: Option<String>,
}

#[tool_router]
impl PlaybridgeMcp {
    pub fn new() -> Self {
        Self {
            send_child: Arc::new(Mutex::new(None)),
            command_lock: Arc::new(Mutex::new(())),
        }
    }

    #[tool(
        description = "List PlayBridge, DLNA, Roku, and Google Cast receivers on the local network. Call this before send if you do not already know the device.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn discover(
        &self,
        Parameters(params): Parameters<DiscoverParams>,
    ) -> Result<CallToolResult, McpError> {
        let mut args = vec!["discover".to_owned(), "--json".to_owned()];
        if let Some(timeout) = params.timeout {
            args.push("-t".into());
            args.push(timeout.to_string());
        }
        if let Some(protocol) = params.protocol.filter(|value| !value.trim().is_empty()) {
            args.push("-p".into());
            args.push(protocol);
        }
        json_result(run_json_command(&args).await)
    }

    #[tool(
        description = "List receivers paired with this CLI sender. Returns metadata only; tokens and certificate pins are never exposed.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn list_paired(
        &self,
        Parameters(_params): Parameters<ListPairedParams>,
    ) -> Result<CallToolResult, McpError> {
        let mut paired = match PlaybridgeCredentials::list() {
            Ok(paired) => paired,
            Err(error) => return json_result(Err(error)),
        };
        if paired.iter().any(|item| item.name.is_none())
            && let Ok(discovery) = run_json_command(&[
                "discover".into(),
                "--json".into(),
                "--protocol".into(),
                "playbridge".into(),
                "--timeout".into(),
                "3".into(),
            ])
            .await
            && let Some(receivers) = discovery.get("receivers").and_then(Value::as_array)
        {
            for item in &mut paired {
                let Some(receiver) = receivers.iter().find(|receiver| {
                    receiver.get("uuid").and_then(Value::as_str) == Some(item.uuid.as_str())
                        || receiver
                            .get("id")
                            .and_then(Value::as_str)
                            .is_some_and(|id| {
                                id.strip_prefix("playbridge:") == Some(item.uuid.as_str())
                            })
                }) else {
                    continue;
                };
                if let Some(name) = receiver.get("name").and_then(Value::as_str) {
                    item.name = Some(name.to_owned());
                }
            }
        }
        json_result(Ok(serde_json::json!({ "ok": true, "paired": paired })))
    }

    #[tool(
        description = "Forget credentials held by this CLI sender for one receiver, or all when all=true. This does not remove devices from the receiver's trust list.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn forget(
        &self,
        Parameters(params): Parameters<ForgetParams>,
    ) -> Result<CallToolResult, McpError> {
        let mut args = vec!["forget".into(), "--json".into()];
        if params.all && params.device.is_some() {
            return json_result(Ok(serde_json::json!({
                "ok": false,
                "error": "invalid_arguments",
                "message": "all=true cannot be combined with device",
            })));
        } else if params.all {
            args.push("--all".into());
        } else if let Some(device) = params.device.filter(|value| !value.trim().is_empty()) {
            args.push(device);
        } else {
            return json_result(Ok(serde_json::json!({
                "ok": false,
                "error": "invalid_arguments",
                "message": "forget requires device or all=true",
            })));
        }
        json_result(run_json_command(&args).await)
    }

    #[tool(
        description = "Pair this CLI sender with a PlayBridge receiver without casting media. If pairing_required is returned, ask the user for the receiver's six-digit code and call submit_pair_code.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn pair(
        &self,
        Parameters(params): Parameters<PairParams>,
    ) -> Result<CallToolResult, McpError> {
        let session_id = JsonCastSession::generate_id();
        let pair_path = JsonCastSession::pair_code_path(&session_id).map_err(internal)?;
        if let Some(parent) = pair_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::remove_file(&pair_path);
        let mut args = vec![
            "pair".into(),
            "--json".into(),
            "--pair-code-file".into(),
            pair_path.to_string_lossy().into_owned(),
            "--session-id".into(),
            session_id.clone(),
        ];
        if let Some(device) = params.device.filter(|value| !value.trim().is_empty()) {
            args.push("--device".into());
            args.push(device);
        }
        let _command_guard = self.command_lock.lock().await;
        self.start_managed(args, session_id).await
    }

    #[tool(
        description = "Cast target as a simple local file/URL, or items as a rich PlayBridge playlist with headers, subtitles, playback preferences, and metadata. target and items are mutually exclusive. skip_history supplies the default for items that omit skipHistory. Starts playback and keeps a session for later tools.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn send(
        &self,
        Parameters(params): Parameters<SendParams>,
    ) -> Result<CallToolResult, McpError> {
        let rich_payload = match params.rich_payload() {
            Ok(payload) => payload,
            Err(message) => {
                return json_result(Ok(serde_json::json!({
                    "ok": false,
                    "error": "invalid_arguments",
                    "message": message,
                })));
            }
        };
        let target = params
            .primary_target()
            .expect("validated send has a target")
            .to_owned();
        let session_id = JsonCastSession::generate_id();
        let pair_path = JsonCastSession::pair_code_path(&session_id).map_err(internal)?;
        if let Some(parent) = pair_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::remove_file(&pair_path);

        let mut args = vec![
            "send".to_owned(),
            target,
            "--json".to_owned(),
            "--pair-code-file".to_owned(),
            pair_path.to_string_lossy().into_owned(),
            "--session-id".to_owned(),
            session_id.clone(),
        ];
        let mut payload_json = serde_json::to_vec(&rich_payload.unwrap_or(Value::Null))
            .map_err(|error| internal(error.to_string()))?;
        payload_json.push(b'\n');
        args.push("--media-payload-stdin".into());
        if let Some(device) = params.device.filter(|value| !value.trim().is_empty()) {
            args.push("--device".into());
            args.push(device);
        }
        if let Some(skip_history) = params.skip_history {
            args.push(if skip_history {
                "--skip-history".into()
            } else {
                "--save-history".into()
            });
        }

        let _command_guard = self.command_lock.lock().await;
        let mut slot = self.send_child.lock().await;
        if let Some(previous) = slot.take() {
            stop_managed(previous).await;
        }
        let mut child = match spawn_playbridge(&args, true) {
            Ok(child) => child,
            Err(error) => {
                JsonCastSession::cleanup(&session_id);
                return Err(internal(error));
            }
        };
        let mut stdin = child
            .stdin
            .take()
            .ok_or_else(|| McpError::internal_error("send child has no stdin", None))?;
        if let Err(error) = stdin.write_all(&payload_json).await {
            let _ = child.start_kill();
            let _ = child.wait().await;
            JsonCastSession::cleanup(&session_id);
            return json_result(Err(error.to_string()));
        }
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| McpError::internal_error("send child has no stdout", None))?;
        if let Some(stderr) = child.stderr.take() {
            tokio::spawn(drain_reader(BufReader::new(stderr)));
        }
        let mut reader = BufReader::new(stdout);
        let first =
            match tokio::time::timeout(Duration::from_secs(30), read_json_value(&mut reader)).await
            {
                Ok(Ok(value)) => value,
                Ok(Err(error)) => {
                    let _ = child.start_kill();
                    let _ = child.wait().await;
                    JsonCastSession::cleanup(&session_id);
                    return json_result(Err(error));
                }
                Err(_) => {
                    let _ = child.start_kill();
                    let _ = child.wait().await;
                    JsonCastSession::cleanup(&session_id);
                    return json_result(Err("timed out waiting for send result".into()));
                }
            };
        let keep_alive = first.get("ok").and_then(Value::as_bool) == Some(true)
            || first.get("error").and_then(Value::as_str) == Some("pairing_required");
        if keep_alive {
            *slot = Some(ManagedSend {
                child,
                stdout: reader,
                stdin: Some(stdin),
                session_id,
                waiting_for_pairing: first.get("error").and_then(Value::as_str)
                    == Some("pairing_required"),
                receiver_owned_playback: first.get("protocol").and_then(Value::as_str)
                    == Some("playbridge"),
            });
        } else {
            let _ = child.wait().await;
            JsonCastSession::cleanup(&session_id);
        }
        json_result(Ok(first))
    }

    #[tool(
        description = "Submit the six-digit pairing code shown on the PlayBridge receiver. Waits for the receiver's actual approval and result. Call this after send or pair returned pairing_required. Ask the human for the code; do not guess it.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn submit_pair_code(
        &self,
        Parameters(params): Parameters<PairCodeParams>,
    ) -> Result<CallToolResult, McpError> {
        let Some(digits) = normalized_pair_code(&params.code) else {
            return json_result(Ok(serde_json::json!({
                "ok": false,
                "error": "invalid_pair_code",
                "message": "Expected a six-digit code from the receiver",
            })));
        };
        let mut slot = self.send_child.lock().await;
        let Some(managed) = slot.as_mut() else {
            return json_result(Ok(serde_json::json!({
                "ok": false,
                "error": "no_pairing_in_progress",
                "message": "No managed send is waiting for a pairing code",
            })));
        };
        if !managed.waiting_for_pairing {
            return json_result(Ok(serde_json::json!({
                "ok": false,
                "error": "no_pairing_in_progress",
                "session_id": managed.session_id,
            })));
        }
        if params
            .session_id
            .as_deref()
            .is_some_and(|id| id != managed.session_id)
        {
            return json_result(Ok(serde_json::json!({
                "ok": false,
                "error": "session_mismatch",
                "session_id": managed.session_id,
            })));
        }
        let path =
            JsonCastSession::write_pair_code(&managed.session_id, &digits).map_err(internal)?;
        let result = match tokio::time::timeout(
            Duration::from_secs(75),
            read_json_value(&mut managed.stdout),
        )
        .await
        {
            Ok(result) => result,
            Err(_) => {
                if let Some(failed) = slot.take() {
                    stop_managed(failed).await;
                }
                return json_result(Err("pairing_timeout".into()));
            }
        };
        let _ = std::fs::remove_file(path);
        let value = match result {
            Ok(value) => value,
            Err(error) => {
                if let Some(failed) = slot.take() {
                    stop_managed(failed).await;
                }
                return json_result(Err(error));
            }
        };
        managed.waiting_for_pairing = false;
        managed.receiver_owned_playback =
            value.get("protocol").and_then(Value::as_str) == Some("playbridge");
        if value.get("ok").and_then(Value::as_bool) != Some(true)
            && let Some(failed) = slot.take()
        {
            stop_managed(failed).await;
        }
        json_result(Ok(value))
    }

    #[tool(
        description = "Get playback status of a PlayBridge send session (state, position, duration, device).",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn status(
        &self,
        Parameters(params): Parameters<StatusParams>,
    ) -> Result<CallToolResult, McpError> {
        let session_id = self.resolve_session_id(params.session_id).await;
        let mut args = vec!["status".into(), "--json".into()];
        append_session_id(&mut args, session_id.as_deref());
        json_result(run_json_command(&args).await)
    }

    #[tool(
        description = "Control a send session. command is pause, play, toggle, stop, seek, volume, mute, loop, speed, or audio_boost. For seek pass seconds, for volume pass delta, and for speed pass value. stop ends the session.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn control(
        &self,
        Parameters(params): Parameters<ControlParams>,
    ) -> Result<CallToolResult, McpError> {
        let _command_guard = self.command_lock.lock().await;
        let session_id = self.resolve_session_id(params.session_id).await;
        let request = ControlRequest {
            id: new_request_id(),
            command: params.command,
            seconds: params.seconds,
            delta: params.delta,
            value: params.value,
            action: None,
            payload: None,
        };
        json_result(self.submit_request(session_id, request).await)
    }

    #[tool(
        description = "Append a rich media item to a PlayBridge receiver queue. Pass device for a brief receiver-owned operation that does not require or stop an MCP send session; otherwise pass session_id or use this server's managed send.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn queue_add(
        &self,
        Parameters(mut params): Parameters<QueueAddParams>,
    ) -> Result<CallToolResult, McpError> {
        if let Some(item) = params.item.take() {
            params.items.insert(0, item);
        }
        if params.items.is_empty() || params.items.len() > 50 {
            return invalid_arguments("queue_add requires between 1 and 50 items".into());
        }
        for item in &params.items {
            if let Err(message) = validate_queue_item(item) {
                return invalid_arguments(message);
            }
        }
        let skip_history = crate::ui::skip_history_default().map_err(internal)?;
        for item in &mut params.items {
            if item.skip_history.is_none() {
                item.skip_history = Some(skip_history);
            }
        }
        if params.device.is_some() && params.session_id.is_some() {
            return invalid_arguments("device and session_id are mutually exclusive".into());
        }
        if let Some(device) = params.device.filter(|value| !value.trim().is_empty()) {
            if params
                .items
                .iter()
                .any(|item| std::path::Path::new(&item.url).is_file())
            {
                return invalid_arguments(
                    "device queue_add requires a receiver-accessible URL; use a send session for local files"
                        .into(),
                );
            }
            let _command_guard = self.command_lock.lock().await;
            let payload = queue_add_payload(params.items, params.if_playback_id);
            return json_result(
                crate::send::run_device_receiver_command(&device, "queue_add", payload).await,
            );
        }
        let payload = queue_add_payload(params.items, params.if_playback_id);
        self.submit_receiver_command(params.session_id, "queue_add", payload)
            .await
    }

    #[tool(
        description = "Jump to a zero-based item in a PlayBridge receiver playlist. Pass device for a brief receiver-owned operation that does not require or stop an MCP send session; otherwise pass session_id or use this server's managed send.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn playlist_jump(
        &self,
        Parameters(params): Parameters<PlaylistJumpParams>,
    ) -> Result<CallToolResult, McpError> {
        if params.index.is_some() == params.item_id.is_some() {
            return invalid_arguments(
                "playlist_jump requires exactly one of index or item_id".into(),
            );
        }
        if params.device.is_some() && params.session_id.is_some() {
            return invalid_arguments("device and session_id are mutually exclusive".into());
        }
        let payload = playlist_jump_payload(params.index, params.item_id, params.if_playback_id);
        if let Some(device) = params.device.filter(|value| !value.trim().is_empty()) {
            let _command_guard = self.command_lock.lock().await;
            return json_result(
                crate::send::run_device_receiver_command(&device, "playlist_jump", payload).await,
            );
        }
        self.submit_receiver_command(params.session_id, "playlist_jump", payload)
            .await
    }

    #[tool(description = "Read the authoritative queue and playback identity from a PlayBridge receiver.", output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>())]
    async fn get_state(
        &self,
        Parameters(params): Parameters<DeviceParams>,
    ) -> Result<CallToolResult, McpError> {
        let _command_guard = self.command_lock.lock().await;
        json_result(
            crate::send::run_device_receiver_command(
                &params.device,
                "queue_query",
                serde_json::json!({}),
            )
            .await,
        )
    }

    #[tool(description = "Remove stable item IDs from a receiver-owned PlayBridge queue.", output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>())]
    async fn queue_remove(
        &self,
        Parameters(params): Parameters<QueueRemoveParams>,
    ) -> Result<CallToolResult, McpError> {
        if params.item_ids.is_empty() {
            return invalid_arguments("queue_remove requires item_ids".into());
        }
        let _command_guard = self.command_lock.lock().await;
        let mut payload = serde_json::json!({"itemIds": params.item_ids});
        if let Some(playback_id) = params.if_playback_id {
            payload["ifPlaybackId"] = serde_json::json!(playback_id);
        }
        json_result(
            crate::send::run_device_receiver_command(&params.device, "queue_remove", payload).await,
        )
    }

    #[tool(description = "Move a stable queue item before another item, or to the end when before_item_id is omitted.", output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>())]
    async fn queue_move(
        &self,
        Parameters(params): Parameters<QueueMoveParams>,
    ) -> Result<CallToolResult, McpError> {
        let _command_guard = self.command_lock.lock().await;
        let mut payload = serde_json::json!({"itemId": params.item_id});
        if let Some(before) = params.before_item_id {
            payload["beforeItemId"] = serde_json::json!(before);
        }
        if let Some(playback_id) = params.if_playback_id {
            payload["ifPlaybackId"] = serde_json::json!(playback_id);
        }
        json_result(
            crate::send::run_device_receiver_command(&params.device, "queue_move", payload).await,
        )
    }

    #[tool(description = "Explicitly clear receiver-owned PlayBridge playback. This is destructive and should only be called when requested.", output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>())]
    async fn queue_clear(
        &self,
        Parameters(params): Parameters<QueueClearParams>,
    ) -> Result<CallToolResult, McpError> {
        let _command_guard = self.command_lock.lock().await;
        let mut payload = serde_json::json!({});
        if let Some(playback_id) = params.if_playback_id {
            payload["ifPlaybackId"] = serde_json::json!(playback_id);
        }
        json_result(
            crate::send::run_device_receiver_command(&params.device, "queue_clear", payload).await,
        )
    }

    #[tool(
        description = "Open a URL in the active PlayBridge receiver browser.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn browser(
        &self,
        Parameters(params): Parameters<BrowserParams>,
    ) -> Result<CallToolResult, McpError> {
        if !params.url.starts_with("http://") && !params.url.starts_with("https://") {
            return invalid_arguments("browser url must use http or https".into());
        }
        self.submit_receiver_command(
            params.session_id,
            "browser",
            serde_json::json!({
                "url": params.url,
                "browserMode": params.browser_mode,
                "desktopMode": params.desktop_mode,
            }),
        )
        .await
    }

    #[tool(
        description = "Refresh the active PlayBridge receiver browser.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn browser_control(
        &self,
        Parameters(params): Parameters<BrowserControlParams>,
    ) -> Result<CallToolResult, McpError> {
        self.submit_receiver_command(
            params.session_id,
            "browser_control",
            serde_json::json!({ "action": "refresh" }),
        )
        .await
    }

    #[tool(
        description = "Send a navigation or TV volume key to the active PlayBridge receiver.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn remote(
        &self,
        Parameters(params): Parameters<RemoteParams>,
    ) -> Result<CallToolResult, McpError> {
        if !is_supported_remote_key(&params.key) {
            return invalid_arguments("unsupported remote key".into());
        }
        self.submit_receiver_command(
            params.session_id,
            "remote",
            serde_json::json!({ "key": params.key }),
        )
        .await
    }

    async fn submit_receiver_command(
        &self,
        requested_session_id: Option<String>,
        action: &str,
        payload: Value,
    ) -> Result<CallToolResult, McpError> {
        let _command_guard = self.command_lock.lock().await;
        let session_id = self.resolve_session_id(requested_session_id).await;
        let request = ControlRequest {
            id: new_request_id(),
            command: "receiver_command".into(),
            seconds: None,
            delta: None,
            value: None,
            action: Some(action.to_owned()),
            payload: Some(payload),
        };
        json_result(self.submit_request(session_id, request).await)
    }

    async fn submit_request(
        &self,
        session_id: Option<String>,
        request: ControlRequest,
    ) -> Result<Value, String> {
        let mut slot = self.send_child.lock().await;
        if let Some(managed) = slot.as_mut()
            && session_id
                .as_deref()
                .is_none_or(|id| id == managed.session_id)
            && let Some(stdin) = managed.stdin.as_mut()
        {
            let mut message = serde_json::to_vec(&request).map_err(|error| error.to_string())?;
            if message.len() > 256 * 1024 {
                return Err("MCP control command exceeds 256 KiB".into());
            }
            message.push(b'\n');
            let request_id = request.id.clone();
            let result = async {
                stdin
                    .write_all(&message)
                    .await
                    .map_err(|error| error.to_string())?;
                read_matching_ack(&mut managed.stdout, &request_id).await
            };
            let error = match tokio::time::timeout(Duration::from_secs(12), result).await {
                Ok(Ok(value)) => return Ok(value),
                Ok(Err(error)) => error,
                Err(_) => "control_timeout".to_owned(),
            };
            let failed = slot.take().expect("managed send still exists");
            drop(slot);
            stop_managed(failed).await;
            return Err(error);
        }
        drop(slot);
        JsonCastSession::submit(session_id.as_deref(), request).await
    }

    async fn resolve_session_id(&self, requested: Option<String>) -> Option<String> {
        if requested.is_some() {
            return requested;
        }
        let mut slot = self.send_child.lock().await;
        if let Some(managed) = slot.as_mut()
            && managed.child.try_wait().ok().flatten().is_some()
        {
            let session_id = managed.session_id.clone();
            JsonCastSession::cleanup(&session_id);
            *slot = None;
            return None;
        }
        slot.as_ref().map(|managed| managed.session_id.clone())
    }

    async fn start_managed(
        &self,
        args: Vec<String>,
        session_id: String,
    ) -> Result<CallToolResult, McpError> {
        let mut slot = self.send_child.lock().await;
        if let Some(previous) = slot.take() {
            stop_managed(previous).await;
        }
        let mut child = spawn_playbridge(&args, false).map_err(internal)?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| McpError::internal_error("managed command child has no stdout", None))?;
        if let Some(stderr) = child.stderr.take() {
            tokio::spawn(drain_reader(BufReader::new(stderr)));
        }
        let mut reader = BufReader::new(stdout);
        let first =
            match tokio::time::timeout(Duration::from_secs(30), read_json_value(&mut reader)).await
            {
                Ok(Ok(value)) => value,
                Ok(Err(error)) => {
                    let _ = child.start_kill();
                    let _ = child.wait().await;
                    return json_result(Err(error));
                }
                Err(_) => {
                    let _ = child.start_kill();
                    let _ = child.wait().await;
                    return json_result(Err("timed out waiting for pair result".into()));
                }
            };
        if first.get("error").and_then(Value::as_str) == Some("pairing_required") {
            *slot = Some(ManagedSend {
                child,
                stdout: reader,
                stdin: None,
                session_id,
                waiting_for_pairing: true,
                receiver_owned_playback: false,
            });
        } else {
            let _ = child.wait().await;
        }
        json_result(Ok(first))
    }
}

fn is_supported_remote_key(key: &str) -> bool {
    matches!(
        key,
        "dpad_up"
            | "dpad_down"
            | "dpad_left"
            | "dpad_right"
            | "dpad_center"
            | "back"
            | "home"
            | "volume_up"
            | "volume_down"
            | "mute"
    )
}

fn playlist_jump_payload(
    index: Option<usize>,
    item_id: Option<String>,
    if_playback_id: Option<String>,
) -> Value {
    let mut payload = serde_json::json!({});
    if let Some(index) = index {
        payload["index"] = serde_json::json!(index);
    }
    if let Some(item_id) = item_id {
        payload["itemId"] = serde_json::json!(item_id);
    }
    if let Some(playback_id) = if_playback_id {
        payload["ifPlaybackId"] = serde_json::json!(playback_id);
    }
    payload
}

fn queue_add_payload(items: Vec<MediaItemParams>, if_playback_id: Option<String>) -> Value {
    let mut payload = if items.len() == 1 && if_playback_id.is_none() {
        serde_json::json!({ "item": items.into_iter().next().expect("one item") })
    } else {
        serde_json::json!({ "items": items })
    };
    if let Some(playback_id) = if_playback_id {
        payload["ifPlaybackId"] = serde_json::json!(playback_id);
    }
    payload
}

fn validate_queue_item(item: &MediaItemParams) -> Result<(), String> {
    validate_media_item(item)?;
    crate::send::validate_media_target(&item.url)
}

#[tool_handler]
impl ServerHandler for PlaybridgeMcp {
    fn get_info(&self) -> ServerInfo {
        ServerInfo::new(ServerCapabilities::builder().enable_tools().build())
            .with_server_info(Implementation::new("playbridge", env!("CARGO_PKG_VERSION")))
            .with_protocol_version(ProtocolVersion::V_2025_11_25)
            .with_instructions(INSTRUCTIONS.to_owned())
    }
}

pub async fn run() -> Result<(), String> {
    let server = PlaybridgeMcp::new();
    let cleanup = server.clone();
    let running = server
        .serve(stdio())
        .await
        .map_err(|error| error.to_string())?;
    let result = running.waiting().await.map_err(|error| error.to_string());
    if let Some(managed) = cleanup.send_child.lock().await.take() {
        stop_managed(managed).await;
    }
    result?;
    Ok(())
}

fn playbridge_exe() -> Result<std::path::PathBuf, String> {
    std::env::current_exe().map_err(|error| error.to_string())
}

fn spawn_playbridge(args: &[String], pipe_stdin: bool) -> Result<Child, String> {
    Command::new(playbridge_exe()?)
        .args(args)
        .stdin(if pipe_stdin {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .map_err(|error| error.to_string())
}

async fn stop_managed(mut managed: ManagedSend) {
    let request = ControlRequest {
        id: new_request_id(),
        command: managed_teardown_command(managed.receiver_owned_playback).into(),
        seconds: None,
        delta: None,
        value: None,
        action: None,
        payload: None,
    };
    let _ = JsonCastSession::submit(Some(&managed.session_id), request).await;
    if tokio::time::timeout(Duration::from_secs(2), managed.child.wait())
        .await
        .is_err()
    {
        let _ = managed.child.start_kill();
        let _ = managed.child.wait().await;
    }
    JsonCastSession::cleanup(&managed.session_id);
}

fn managed_teardown_command(receiver_owned_playback: bool) -> &'static str {
    if receiver_owned_playback {
        "detach"
    } else {
        "stop"
    }
}

fn append_session_id(args: &mut Vec<String>, session_id: Option<&str>) {
    if let Some(session_id) = session_id {
        args.push("--session-id".into());
        args.push(session_id.to_owned());
    }
}

fn normalized_pair_code(value: &str) -> Option<String> {
    let digits: String = value.chars().filter(|ch| ch.is_ascii_digit()).collect();
    (digits.len() == 6).then_some(digits)
}

async fn run_json_command(args: &[String]) -> Result<Value, String> {
    let output = Command::new(playbridge_exe()?)
        .args(args)
        .stdin(Stdio::null())
        .output()
        .await
        .map_err(|error| error.to_string())?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    serde_json::from_str(stdout.trim()).map_err(|error| {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let command = args.first().map_or("command", String::as_str);
        if stdout.trim().is_empty() && stderr.trim().is_empty() {
            format!("playbridge {command} produced no JSON ({error})")
        } else {
            format!("invalid JSON from playbridge {command}: {error}")
        }
    })
}

async fn read_json_value<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<Value, String> {
    let mut buf = String::new();
    loop {
        let mut line = String::new();
        let n = reader
            .read_line(&mut line)
            .await
            .map_err(|error| error.to_string())?;
        if n == 0 {
            if buf.trim().is_empty() {
                return Err("send produced no JSON".into());
            }
            return serde_json::from_str(buf.trim()).map_err(|error| error.to_string());
        }
        buf.push_str(&line);
        if let Ok(value) = serde_json::from_str::<Value>(buf.trim()) {
            return Ok(value);
        }
    }
}

async fn read_matching_ack<R: AsyncBufRead + Unpin>(
    reader: &mut R,
    request_id: &str,
) -> Result<Value, String> {
    loop {
        let value = read_json_value(reader).await?;
        if value.get("request_id").and_then(Value::as_str) == Some(request_id) {
            return Ok(value);
        }
    }
}

async fn drain_reader<R: AsyncBufRead + Unpin>(mut reader: R) {
    let mut line = String::new();
    while let Ok(n) = reader.read_line(&mut line).await {
        if n == 0 {
            break;
        }
        line.clear();
    }
}

fn json_result(result: Result<Value, String>) -> Result<CallToolResult, McpError> {
    match result {
        Ok(value) if value.get("ok").and_then(Value::as_bool) == Some(false) => {
            Ok(CallToolResult::structured_error(value))
        }
        Ok(value) => Ok(CallToolResult::structured(value)),
        Err(error) => Ok(CallToolResult::structured_error(serde_json::json!({
            "ok": false,
            "error": "command_failed",
            "message": error,
        }))),
    }
}

fn invalid_arguments(message: String) -> Result<CallToolResult, McpError> {
    json_result(Ok(serde_json::json!({
        "ok": false,
        "error": "invalid_arguments",
        "message": message,
    })))
}

fn internal(error: String) -> McpError {
    McpError::internal_error(error, None)
}

#[cfg(test)]
mod tests {
    use super::{
        MediaItemParams, PairCodeParams, PlaybridgeMcp, QueueAddParams, SendParams,
        is_supported_remote_key, json_result, managed_teardown_command, normalized_pair_code,
        read_json_value, read_matching_ack, validate_queue_item,
    };
    use rmcp::handler::server::wrapper::Parameters;
    use tokio::io::BufReader;

    #[test]
    fn registers_core_tools() {
        let names: Vec<String> = PlaybridgeMcp::tool_router()
            .list_all()
            .into_iter()
            .map(|tool| tool.name.to_string())
            .collect();
        for expected in [
            "discover",
            "send",
            "list_paired",
            "forget",
            "pair",
            "submit_pair_code",
            "status",
            "control",
            "get_state",
            "queue_add",
            "queue_remove",
            "queue_move",
            "queue_clear",
            "playlist_jump",
            "browser",
            "browser_control",
            "remote",
        ] {
            assert!(
                names.iter().any(|name| name == expected),
                "missing {expected} in {names:?}"
            );
        }
        assert!(
            !names.iter().any(|name| name == "mouse"),
            "mouse must not be exposed to MCP agents"
        );
        assert!(
            PlaybridgeMcp::tool_router()
                .list_all()
                .iter()
                .all(|tool| tool.output_schema.is_some())
        );
    }

    #[test]
    fn queue_add_accepts_device_without_a_session() {
        let params: QueueAddParams = serde_json::from_value(serde_json::json!({
            "device": "playbridge:receiver-uuid",
            "item": { "url": "https://example.test/video.mp4" }
        }))
        .unwrap();
        assert_eq!(params.device.as_deref(), Some("playbridge:receiver-uuid"));
        assert!(params.session_id.is_none());
    }

    #[test]
    fn receiver_owned_playback_detaches_during_mcp_teardown() {
        assert_eq!(managed_teardown_command(true), "detach");
        assert_eq!(managed_teardown_command(false), "stop");
    }

    #[test]
    fn rich_send_preserves_android_media_fields_and_sensitive_headers() {
        let params: SendParams = serde_json::from_value(serde_json::json!({
            "items": [{
                "url": "https://cdn.example.test/video.m3u8",
                "title": "Episode 1",
                "headers": {
                    "Referer": "https://example.test/",
                    "Authorization": "Bearer secret"
                },
                "contentType": "application/vnd.apple.mpegurl",
                "subtitleResources": [{
                    "url": "https://cdn.example.test/sub.vtt",
                    "headers": { "Cookie": "subtitle-secret" },
                    "language": "en"
                }],
                "mediaKind": "video",
                "startPositionMs": 120000,
                "preferredAudioLanguage": "en",
                "visualMetadata": {
                    "title": "Show",
                    "season": 1,
                    "episode": 1
                }
            }],
            "start_index": 0,
            "skip_preplay": true
        }))
        .unwrap();
        let payload = params.rich_payload().unwrap().unwrap();
        assert_eq!(
            payload["items"][0]["headers"]["Referer"],
            "https://example.test/"
        );
        assert_eq!(
            payload["items"][0]["subtitleResources"][0]["headers"]["Cookie"],
            "subtitle-secret"
        );
        assert_eq!(payload["items"][0]["startPositionMs"], 120000);
        assert_eq!(payload["skipPreplay"], true);
    }

    #[test]
    fn send_rejects_target_combined_with_items() {
        let params: SendParams = serde_json::from_value(serde_json::json!({
            "target": "https://example.test/one.mp4",
            "items": [{ "url": "https://example.test/two.mp4" }]
        }))
        .unwrap();
        assert_eq!(
            params.rich_payload().unwrap_err(),
            "target cannot be combined with items"
        );
    }

    #[test]
    fn output_schemas_use_objects_for_every_property() {
        fn assert_no_boolean_schemas(value: &serde_json::Value, path: &str) {
            match value {
                serde_json::Value::Bool(_) => panic!("boolean JSON Schema at {path}"),
                serde_json::Value::Array(values) => {
                    for (index, value) in values.iter().enumerate() {
                        assert_no_boolean_schemas(value, &format!("{path}[{index}]"));
                    }
                }
                serde_json::Value::Object(values) => {
                    for (key, value) in values {
                        assert_no_boolean_schemas(value, &format!("{path}.{key}"));
                    }
                }
                _ => {}
            }
        }

        for tool in PlaybridgeMcp::tool_router().list_all() {
            let schema = serde_json::to_value(tool.output_schema.as_ref().unwrap()).unwrap();
            assert_no_boolean_schemas(&schema, tool.name.as_ref());
        }
    }

    #[test]
    fn output_schema_describes_pairing_event_fields() {
        for tool in PlaybridgeMcp::tool_router().list_all() {
            let schema = serde_json::to_value(tool.output_schema.as_ref().unwrap()).unwrap();
            let properties = schema["properties"].as_object().unwrap();
            assert!(
                properties.contains_key("event"),
                "{} lacks event",
                tool.name
            );
            assert!(
                properties.contains_key("pair_code_file"),
                "{} lacks pair_code_file",
                tool.name
            );
        }
    }

    #[test]
    fn remote_keys_match_the_supported_protocol_set() {
        for key in [
            "dpad_up",
            "dpad_down",
            "dpad_left",
            "dpad_right",
            "dpad_center",
            "back",
            "home",
            "volume_up",
            "volume_down",
            "mute",
        ] {
            assert!(is_supported_remote_key(key), "remote rejected {key}");
        }
        assert!(!is_supported_remote_key("power"));
    }

    #[test]
    fn queue_items_use_the_same_media_target_validation_as_send() {
        let item: MediaItemParams = serde_json::from_value(serde_json::json!({
            "url": "/definitely/missing/video.mp4"
        }))
        .unwrap();
        assert!(validate_queue_item(&item).is_err());

        let item: MediaItemParams = serde_json::from_value(serde_json::json!({
            "url": "https://example.test/video.mp4"
        }))
        .unwrap();
        assert!(validate_queue_item(&item).is_ok());
    }

    #[tokio::test]
    async fn reads_pretty_printed_json_object() {
        let pretty = "{\n  \"ok\": true,\n  \"device\": \"TV\"\n}\n";
        let mut reader = BufReader::new(pretty.as_bytes());
        let value = read_json_value(&mut reader).await.unwrap();
        assert_eq!(value["ok"], true);
        assert_eq!(value["device"], "TV");
    }

    #[tokio::test]
    async fn matching_ack_skips_a_late_response_from_an_earlier_command() {
        let input = br#"{"ok":true,"request_id":"old"}
{"ok":true,"request_id":"current","command":"pause"}
"#;
        let mut reader = BufReader::new(&input[..]);

        let value = read_matching_ack(&mut reader, "current").await.unwrap();

        assert_eq!(value["request_id"], "current");
        assert_eq!(value["command"], "pause");
    }

    #[tokio::test]
    async fn preserves_pairing_and_completion_as_separate_events() {
        let events =
            "{\"ok\":false,\"error\":\"pairing_required\"}\n{\"ok\":true,\"session_id\":\"abc\"}\n";
        let mut reader = BufReader::new(events.as_bytes());
        let pairing = read_json_value(&mut reader).await.unwrap();
        let completed = read_json_value(&mut reader).await.unwrap();
        assert_eq!(pairing["error"], "pairing_required");
        assert_eq!(completed["ok"], true);
    }

    #[test]
    fn pairing_codes_require_exactly_six_digits() {
        assert_eq!(normalized_pair_code("575 722").as_deref(), Some("575722"));
        assert!(normalized_pair_code("12345").is_none());
        assert!(normalized_pair_code("1234567").is_none());
    }

    #[test]
    fn tool_failures_are_structured_mcp_errors() {
        let value = serde_json::json!({"ok": false, "error": "device_not_found"});
        let result = json_result(Ok(value.clone())).unwrap();
        assert_eq!(result.is_error, Some(true));
        assert_eq!(result.structured_content, Some(value));
    }

    #[tokio::test]
    async fn pairing_code_requires_a_managed_pairing_operation() {
        let result = PlaybridgeMcp::new()
            .submit_pair_code(Parameters(PairCodeParams {
                code: "123456".into(),
                session_id: None,
            }))
            .await
            .unwrap();
        assert_eq!(result.is_error, Some(true));
        assert_eq!(
            result.structured_content.unwrap()["error"],
            "no_pairing_in_progress"
        );
    }
}
