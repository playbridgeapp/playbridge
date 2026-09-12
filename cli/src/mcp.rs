use std::{process::Stdio, sync::Arc, time::Duration};

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
    io::{AsyncBufRead, AsyncBufReadExt, BufReader},
    process::{Child, ChildStdout, Command},
    sync::Mutex,
};

use crate::json_session::{ControlRequest, JsonCastSession, new_request_id};

const INSTRUCTIONS: &str = "\
PlayBridge casts local files and stream URLs to TVs and receivers on the LAN.

Workflow:
1. Call discover to list receivers.
2. Call send with a file path or http(s) URL. Pass the protocol-qualified receiver id from discover when needed. One physical TV may expose multiple protocol endpoints.
3. Keep the session_id returned by send for every later call.
4. If send returns error pairing_required, ask the user for the six-digit code shown on the receiver, then call submit_pair_code with that session_id. It waits for the real pairing result.
5. Use status to inspect playback and control to pause, play, seek, or stop.

send.skip_history overrides whether a PlayBridge receiver saves a cast in history. Omit it to use the persisted CLI default.

Do not invent playbridge CLI flags. Use these tools. seek seconds are relative (e.g. 60 or -10).";

pub fn usage() -> &'static str {
    "PlayBridge MCP Server\n\nUsage:\n  playbridge mcp\n\nRuns a Model Context Protocol server over stdio. Available tools:\n  discover, send, submit_pair_code, status, control\n\nThe server writes MCP messages to stdout; do not use it as an interactive command."
}

#[derive(Clone)]
pub struct PlaybridgeMcp {
    send_child: Arc<Mutex<Option<ManagedSend>>>,
}

struct ManagedSend {
    child: Child,
    stdout: BufReader<ChildStdout>,
    session_id: String,
    waiting_for_pairing: bool,
}

#[derive(Debug, Serialize, schemars::JsonSchema)]
struct ToolOutput {
    ok: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    message: Option<String>,
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
    capabilities: Option<CapabilitiesOutput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    skip_history: Option<bool>,
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
    /// Local file path or http(s) media URL to cast.
    target: String,
    /// Optional receiver id, uuid, name, or IP from discover.
    #[serde(default)]
    device: Option<String>,
    /// Override whether this cast is excluded from receiver history. Omit to use the CLI default.
    #[serde(default)]
    skip_history: Option<bool>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct PairCodeParams {
    /// Six-digit SAS code shown on the PlayBridge receiver.
    code: String,
    /// Session id returned by send. Defaults to this MCP server's managed send.
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
struct StatusParams {
    /// Session id returned by send. Defaults to this MCP server's managed send.
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct ControlParams {
    /// One of: pause, play, toggle, stop, seek, volume, mute, speed.
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

#[tool_router]
impl PlaybridgeMcp {
    pub fn new() -> Self {
        Self {
            send_child: Arc::new(Mutex::new(None)),
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
        description = "Cast a local file or stream URL to a receiver. skip_history overrides the persisted CLI history default for PlayBridge receivers. Starts playback and keeps a session for status/control. If the result has error pairing_required, ask the user for the code on the receiver and call submit_pair_code. If error is preferred_unreachable, pick a receiver from the list and call send again with device.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn send(
        &self,
        Parameters(params): Parameters<SendParams>,
    ) -> Result<CallToolResult, McpError> {
        let session_id = JsonCastSession::generate_id();
        let pair_path = JsonCastSession::pair_code_path(&session_id).map_err(internal)?;
        if let Some(parent) = pair_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::remove_file(&pair_path);

        let mut args = vec![
            "send".to_owned(),
            params.target,
            "--json".to_owned(),
            "--pair-code-file".to_owned(),
            pair_path.to_string_lossy().into_owned(),
            "--session-id".to_owned(),
            session_id.clone(),
        ];
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

        let mut slot = self.send_child.lock().await;
        if let Some(previous) = slot.take() {
            stop_managed(previous).await;
        }
        let mut child = spawn_playbridge(&args).map_err(internal)?;
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
                session_id,
                waiting_for_pairing: first.get("error").and_then(Value::as_str)
                    == Some("pairing_required"),
            });
        } else {
            let _ = child.wait().await;
        }
        json_result(Ok(first))
    }

    #[tool(
        description = "Submit the six-digit pairing code shown on the PlayBridge receiver. Waits for the receiver's actual approval and playback result. Call this only after send returned pairing_required. Ask the human for the code; do not guess it.",
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
        description = "Control a PlayBridge send session. command is pause, play, toggle, stop, seek, volume, mute, or speed. For seek pass seconds (e.g. 60). For volume pass delta. For speed pass value. stop ends the session.",
        output_schema = rmcp::handler::server::tool::schema_for_type::<ToolOutput>()
    )]
    async fn control(
        &self,
        Parameters(params): Parameters<ControlParams>,
    ) -> Result<CallToolResult, McpError> {
        let session_id = self.resolve_session_id(params.session_id).await;
        let mut args = vec!["control".to_owned(), params.command, "--json".to_owned()];
        append_session_id(&mut args, session_id.as_deref());
        if let Some(seconds) = params.seconds {
            args.push(seconds.to_string());
        } else if let Some(delta) = params.delta {
            args.push(delta.to_string());
        } else if let Some(value) = params.value {
            args.push(value.to_string());
        }
        let result = run_json_command(&args).await;
        if let Ok(value) = &result
            && value.get("command").and_then(Value::as_str) == Some("stop")
            && value.get("ok").and_then(Value::as_bool) == Some(true)
        {
            let mut slot = self.send_child.lock().await;
            if let Some(child) = slot.take() {
                stop_managed(child).await;
            }
        }
        json_result(result)
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

fn spawn_playbridge(args: &[String]) -> Result<Child, String> {
    Command::new(playbridge_exe()?)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .map_err(|error| error.to_string())
}

async fn stop_managed(mut managed: ManagedSend) {
    let stop = ControlRequest {
        id: new_request_id(),
        command: "stop".into(),
        seconds: None,
        delta: None,
        value: None,
    };
    let _ = JsonCastSession::submit(Some(&managed.session_id), stop).await;
    if tokio::time::timeout(Duration::from_secs(2), managed.child.wait())
        .await
        .is_err()
    {
        let _ = managed.child.start_kill();
        let _ = managed.child.wait().await;
    }
    JsonCastSession::cleanup(&managed.session_id);
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
        if stdout.trim().is_empty() && stderr.trim().is_empty() {
            format!("playbridge {} produced no JSON ({error})", args.join(" "))
        } else {
            format!("invalid JSON from playbridge {}: {error}", args.join(" "))
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

fn internal(error: String) -> McpError {
    McpError::internal_error(error, None)
}

#[cfg(test)]
mod tests {
    use super::{
        PairCodeParams, PlaybridgeMcp, json_result, normalized_pair_code, read_json_value,
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
        for expected in ["discover", "send", "submit_pair_code", "status", "control"] {
            assert!(
                names.iter().any(|name| name == expected),
                "missing {expected} in {names:?}"
            );
        }
        assert!(
            PlaybridgeMcp::tool_router()
                .list_all()
                .iter()
                .all(|tool| tool.output_schema.is_some())
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

    #[tokio::test]
    async fn reads_pretty_printed_json_object() {
        let pretty = "{\n  \"ok\": true,\n  \"device\": \"TV\"\n}\n";
        let mut reader = BufReader::new(pretty.as_bytes());
        let value = read_json_value(&mut reader).await.unwrap();
        assert_eq!(value["ok"], true);
        assert_eq!(value["device"], "TV");
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
