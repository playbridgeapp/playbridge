use playbridge_browser_receiver::{
    BrowserReceiverConfig, BrowserReceiverEvent, BrowserReceiverHost, BrowserReceiverService,
    BrowserSessionSnapshot, local_urls,
};
use playbridge_cast_core::{
    browser::{BrowserCommand, BrowserMedia},
    castv2,
    discovery::{
        DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver, ReceiverId, ReceiverProtocol,
    },
    playbridge::{PairingSession, ReceiverFrame, SenderFrame},
    secure_ws::SecureWebSocket,
    session::{MediaRequest, ReceiverSession},
    upnp::Renderer,
};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    env,
    io::{self, IsTerminal, Write},
    path::PathBuf,
    time::Duration,
};
use stream_proxy_rust::{ProxyServer, ProxyServerConfig};
use tokio::io::{AsyncBufReadExt, BufReader};

use crate::{
    credentials::PlaybridgeCredentials,
    json_session::{self, ControlRequest, JsonCastSession, SessionInfo},
    preferred::PreferredDevice,
};

#[derive(Debug, Clone, Copy, Default, serde::Serialize)]
pub(crate) struct CastCapabilities {
    pub play_pause: bool,
    pub seek: bool,
    pub volume: bool,
    pub mute: bool,
    pub looping: bool,
    pub speed: bool,
    pub audio_boost: bool,
}

#[derive(Debug, Clone, serde::Serialize)]
pub(crate) struct CastSnapshot {
    pub state: String,
    pub title: String,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub volume: Option<f32>,
    pub muted: Option<bool>,
    pub looping: Option<bool>,
    pub speed: Option<f32>,
}

#[derive(Debug, Clone)]
pub(crate) enum CastEvent {
    BrowserHosting {
        generation: u64,
        urls: Vec<String>,
    },
    BrowserPairingRequested {
        generation: u64,
        device_name: String,
    },
    PairingCodeRequested {
        generation: u64,
        device_name: String,
    },
    PairingCompleted {
        generation: u64,
        device_name: String,
    },
    Connected {
        generation: u64,
        capabilities: CastCapabilities,
        snapshot: CastSnapshot,
    },
    Snapshot {
        generation: u64,
        snapshot: CastSnapshot,
    },
    Warning {
        generation: u64,
        message: String,
    },
}

#[derive(Debug, Clone)]
pub(crate) enum CastCommand {
    SubmitBrowserPairing(String),
    SubmitPairingCode(String),
    CancelPairing,
    PlayPause,
    SeekRelative(i64),
    VolumeDelta(f32),
    ToggleMute,
    ToggleLoop,
    SetSpeed(f32),
    ToggleAudioBoost,
    Stop,
}

#[derive(Clone, Copy)]
struct MachinePairing<'a> {
    code: Option<&'a str>,
    file: Option<&'a std::path::Path>,
    session_id: &'a str,
}

#[derive(Clone, Copy)]
struct PlaybridgeLoad<'a> {
    device_name: &'a str,
    device_uuid: &'a str,
    media_url: &'a str,
    media_title: &'a str,
    skip_history: bool,
}

#[derive(Clone, Copy)]
struct JsonStatusContext<'a> {
    session_id: &'a str,
    receiver: &'a Receiver,
    media: &'a str,
    capabilities: &'a CastCapabilities,
}

/// Casts without the dashboard and prints one JSON object. Stays running until
/// Ctrl+C so a local-file proxy is not torn down under the TV.
///
/// If the preferred receiver is unreachable, discovers LAN receivers and either
/// prompts (TTY) or returns them in JSON so an agent can ask the user. PlayBridge
/// targets without stored credentials go through SAS pairing.
pub(crate) async fn run_json_cast(
    media_target: String,
    device: Option<String>,
    pair_code: Option<String>,
    pair_code_file: Option<String>,
    requested_session_id: Option<String>,
    skip_history_override: Option<bool>,
) -> Result<(), String> {
    if let Err(message) = validate_media_target(&media_target) {
        emit_json(&json!({
            "ok": false,
            "error": "invalid_media",
            "message": &message,
        }))?;
        return Err(message);
    }

    let skip_history = skip_history_override.unwrap_or(crate::ui::skip_history_default()?);
    let session_id = requested_session_id.unwrap_or_else(JsonCastSession::generate_id);
    let session = match JsonCastSession::claim(&session_id) {
        Ok(session) => session,
        Err(message) => {
            emit_json(&json!({
                "ok": false,
                "error": if message == "invalid_session_id" { "invalid_session_id" } else { "session_in_use" },
                "message": message,
                "session_id": session_id,
            }))?;
            return Err(message);
        }
    };
    let explicit_device = device.is_some();
    let receiver = match resolve_json_receiver(device.as_deref()).await {
        Ok(receiver) => receiver,
        Err(error) => return Err(error),
    };
    let title = media_title(&media_target).unwrap_or_else(|| "Untitled media".into());

    let resolved_path = resolve_media_path(&media_target);
    let (media_url, proxy_server) = if resolved_path.is_file() {
        let server = ProxyServer::start(ProxyServerConfig::default()).await?;
        let host = primary_lan_host(server.local_addr().port())?;
        let media =
            server.register_file(&host, resolved_path, None, Duration::from_secs(6 * 60 * 60))?;
        (media.url, Some(server))
    } else {
        (media_target, None)
    };

    let pair_code_path = pair_code_file.as_deref().map(PathBuf::from);
    let pairing = MachinePairing {
        code: pair_code.as_deref(),
        file: pair_code_path.as_deref(),
        session_id: &session_id,
    };
    let (receiver, control) =
        match connect_and_load(&media_url, &title, receiver, pairing, skip_history).await {
            Ok(connected) => connected,
            Err(message) if !explicit_device && is_unreachable(&message) => {
                match select_discovered_receiver(
                    &format!("Preferred receiver is unreachable ({message})"),
                    "preferred_unreachable",
                    Some(&message),
                )
                .await
                {
                    Ok(fallback) => {
                        match connect_and_load(&media_url, &title, fallback, pairing, skip_history)
                            .await
                        {
                            Ok(connected) => connected,
                            Err(message) => {
                                emit_connect_error(&message, &session_id)?;
                                return Err(message);
                            }
                        }
                    }
                    Err(error) => return Err(error),
                }
            }
            Err(message) => {
                emit_connect_error(&message, &session_id)?;
                return Err(message);
            }
        };

    let _ = save_receiver_as_preferred(&receiver);
    let media = display_media_target(&media_url);
    session.activate(&SessionInfo {
        session_id: session_id.clone(),
        pid: std::process::id(),
        device: receiver.name.clone(),
        protocol: receiver.protocol.as_str().to_owned(),
        id: receiver.id.0.clone(),
        media: media.clone(),
    })?;
    let capabilities = dashboard_capabilities(&control);
    emit_json(&json!({
        "ok": true,
        "device": receiver.name,
        "protocol": receiver.protocol.as_str(),
        "id": receiver.id.0,
        "media": media,
        "control": true,
        "skip_history": skip_history,
        "session_id": session_id,
    }))?;
    if io::stderr().is_terminal() {
        eprintln!(
            "Playing on {}. Use `playbridge control` / `playbridge status --json`, or Ctrl+C to stop.",
            receiver.name
        );
    }

    let result = json_session_loop(control, receiver, capabilities, media, &session).await;
    if let Some(server) = proxy_server
        && let Err(error) = server.shutdown().await
    {
        eprintln!("warning: failed to stop media proxy: {error}");
    }
    result
}

pub(crate) fn run_json_status(session_id: Option<&str>) -> Result<(), String> {
    match JsonCastSession::read_status(session_id) {
        Ok(status) => emit_json(&status),
        Err(error) => {
            emit_json(&json!({ "ok": false, "error": error }))?;
            Err(error)
        }
    }
}

pub(crate) async fn run_json_control(
    session_id: Option<&str>,
    request: ControlRequest,
) -> Result<(), String> {
    match JsonCastSession::submit(session_id, request).await {
        Ok(ack) => {
            emit_json(&ack)?;
            if ack.get("ok").and_then(Value::as_bool) == Some(true) {
                Ok(())
            } else {
                Err(ack
                    .get("error")
                    .and_then(Value::as_str)
                    .unwrap_or("control_failed")
                    .to_owned())
            }
        }
        Err(error) => {
            emit_json(&json!({ "ok": false, "error": error }))?;
            Err(error)
        }
    }
}

async fn json_session_loop(
    mut control: TargetControl,
    receiver: Receiver,
    capabilities: CastCapabilities,
    media: String,
    session: &JsonCastSession,
) -> Result<(), String> {
    let mut snapshot = CastSnapshot {
        state: "buffering".into(),
        title: receiver.name.clone(),
        position_ms: 0,
        duration_ms: 0,
        volume: capabilities.volume.then_some(0.5),
        muted: capabilities.mute.then_some(false),
        looping: capabilities.looping.then_some(false),
        speed: capabilities.speed.then_some(1.0),
    };
    let status_context = JsonStatusContext {
        session_id: session.id(),
        receiver: &receiver,
        media: &media,
        capabilities: &capabilities,
    };
    session.write_status(&status_json(status_context, &snapshot))?;
    let mut last_control_id = String::new();
    let mut tick = tokio::time::interval(Duration::from_millis(250));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut poll_count = 0_u64;
    let mut consecutive_poll_failures = 0_u8;
    loop {
        tokio::select! {
            _ = wait_interrupt() => break,
            _ = tick.tick() => {
                poll_count = poll_count.wrapping_add(1);
                if poll_count.is_multiple_of(4) {
                    let heartbeat = poll_count.is_multiple_of(12);
                    if let Err(message) =
                        dashboard_poll(&mut control, &mut snapshot, heartbeat).await
                    {
                        consecutive_poll_failures = consecutive_poll_failures.saturating_add(1);
                        if consecutive_poll_failures >= 12 {
                            stop_target(control).await;
                            return Err(format!("cast connection lost: {message}"));
                        }
                    } else {
                        consecutive_poll_failures = 0;
                    }
                }
                if let Some(request) = session.take_request(&last_control_id) {
                    last_control_id = request.id.clone();
                    match apply_json_control(&mut control, &mut snapshot, &request).await {
                        Ok(JsonControlResult::Stop) => {
                            snapshot.state = "stopped".into();
                            let _ = session.write_ack(&control_ack(
                                status_context,
                                &request,
                                true,
                                None,
                                &snapshot,
                            ));
                            tokio::time::sleep(Duration::from_millis(400)).await;
                            break;
                        }
                        Ok(JsonControlResult::Applied) => {
                            let _ = session.write_ack(&control_ack(
                                status_context,
                                &request,
                                true,
                                None,
                                &snapshot,
                            ));
                        }
                        Err(message) => {
                            let _ = session.write_ack(&control_ack(
                                status_context,
                                &request,
                                false,
                                Some(&message),
                                &snapshot,
                            ));
                        }
                    }
                }
                let _ = session.write_status(&status_json(
                    status_context,
                    &snapshot,
                ));
            }
        }
    }
    stop_target(control).await;
    Ok(())
}

fn status_json(context: JsonStatusContext<'_>, snapshot: &CastSnapshot) -> Value {
    json!({
        "ok": true,
        "session_id": context.session_id,
        "updated_ms": json_session::now_ms(),
        "device": context.receiver.name,
        "protocol": context.receiver.protocol.as_str(),
        "id": context.receiver.id.0,
        "media": context.media,
        "state": snapshot.state,
        "title": snapshot.title,
        "position_ms": snapshot.position_ms,
        "duration_ms": snapshot.duration_ms,
        "volume": snapshot.volume,
        "muted": snapshot.muted,
        "looping": snapshot.looping,
        "speed": snapshot.speed,
        "capabilities": context.capabilities,
    })
}

fn control_ack(
    context: JsonStatusContext<'_>,
    request: &ControlRequest,
    ok: bool,
    error: Option<&str>,
    snapshot: &CastSnapshot,
) -> Value {
    let mut ack = status_json(context, snapshot);
    ack["request_id"] = json!(request.id);
    ack["command"] = json!(request.command);
    ack["ok"] = json!(ok);
    if let Some(error) = error {
        ack["error"] = json!(error);
    }
    ack
}

enum JsonControlResult {
    Applied,
    Stop,
}

async fn apply_json_control(
    control: &mut TargetControl,
    snapshot: &mut CastSnapshot,
    request: &ControlRequest,
) -> Result<JsonControlResult, String> {
    match request.command.as_str() {
        "stop" => Ok(JsonControlResult::Stop),
        "pause" => {
            if snapshot.state != "paused" {
                dashboard_control(control, CastCommand::PlayPause, snapshot).await?;
            }
            Ok(JsonControlResult::Applied)
        }
        "play" => {
            if snapshot.state == "paused" {
                dashboard_control(control, CastCommand::PlayPause, snapshot).await?;
            }
            Ok(JsonControlResult::Applied)
        }
        "toggle" => {
            dashboard_control(control, CastCommand::PlayPause, snapshot).await?;
            Ok(JsonControlResult::Applied)
        }
        "seek" => {
            let seconds = request
                .seconds
                .ok_or_else(|| "seek requires seconds".to_owned())?;
            json_seek(control, snapshot, seconds).await?;
            Ok(JsonControlResult::Applied)
        }
        "volume" => {
            let delta = request
                .delta
                .ok_or_else(|| "volume requires a delta".to_owned())?;
            dashboard_control(control, CastCommand::VolumeDelta(delta), snapshot).await?;
            Ok(JsonControlResult::Applied)
        }
        "mute" => {
            dashboard_control(control, CastCommand::ToggleMute, snapshot).await?;
            Ok(JsonControlResult::Applied)
        }
        "speed" => {
            let value = request
                .value
                .ok_or_else(|| "speed requires a value".to_owned())?;
            dashboard_control(control, CastCommand::SetSpeed(value), snapshot).await?;
            Ok(JsonControlResult::Applied)
        }
        other => Err(format!("unknown control command: {other}")),
    }
}

async fn json_seek(
    control: &mut TargetControl,
    snapshot: &mut CastSnapshot,
    seconds: i64,
) -> Result<(), String> {
    let mut position_ms = snapshot
        .position_ms
        .saturating_add_signed(seconds.saturating_mul(1000));
    if snapshot.duration_ms > 0 {
        position_ms = position_ms.min(snapshot.duration_ms);
    }
    match control {
        TargetControl::Playbridge(socket) => {
            socket
                .send(&SenderFrame::Command {
                    action: "control".into(),
                    payload: Some(json!({ "command": format!("seek_to:{position_ms}") })),
                })
                .await
                .map_err(|error| error.to_string())?;
            snapshot.position_ms = position_ms;
            Ok(())
        }
        _ => dashboard_control(control, CastCommand::SeekRelative(seconds), snapshot).await,
    }
}

async fn wait_interrupt() {
    let ctrl_c = tokio::signal::ctrl_c();
    #[cfg(unix)]
    {
        if let Ok(mut terminate) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            tokio::select! {
                _ = ctrl_c => {}
                _ = terminate.recv() => {}
            }
            return;
        }
    }
    let _ = ctrl_c.await;
}

pub(crate) fn emit_json(value: &Value) -> Result<(), String> {
    println!(
        "{}",
        serde_json::to_string_pretty(value).map_err(|error| error.to_string())?
    );
    io::stdout()
        .flush()
        .map_err(|error| format!("failed to flush JSON output: {error}"))
}

fn receiver_from_preferred(preferred: &PreferredDevice) -> Result<Receiver, String> {
    let protocol = preferred
        .protocol
        .parse::<ReceiverProtocol>()
        .map_err(|error| error.to_string())?;
    Ok(Receiver {
        id: ReceiverId(preferred.uuid.clone()),
        protocol,
        name: preferred.name.clone(),
        addresses: vec![preferred.address.clone()],
        port: preferred.port,
        wss_port: preferred.wss_port,
        location: preferred.location.clone(),
        uuid: Some(preferred.uuid.clone()),
    })
}

fn is_unreachable(message: &str) -> bool {
    let lower = message.to_ascii_lowercase();
    lower.contains("timed out")
        || lower.contains("timeout")
        || lower.contains("unreachable")
        || lower.contains("no route to host")
        || lower.contains("connection refused")
        || lower.contains("network is down")
}

fn can_prompt() -> bool {
    io::stdin().is_terminal() && io::stderr().is_terminal()
}

fn receiver_uuid(receiver: &Receiver) -> String {
    receiver
        .uuid
        .clone()
        .unwrap_or_else(|| receiver.id.0.clone())
}

fn receiver_matches_device(receiver: &Receiver, selector: &str) -> bool {
    let selector = selector.trim();
    if selector.is_empty() {
        return false;
    }
    receiver.id.0.eq_ignore_ascii_case(selector)
        || receiver
            .uuid
            .as_deref()
            .is_some_and(|uuid| uuid.eq_ignore_ascii_case(selector))
        || receiver.name.eq_ignore_ascii_case(selector)
        || receiver.addresses.iter().any(|address| address == selector)
}

fn select_receiver_by_selector(
    discovered: &[Receiver],
    selector: &str,
) -> Result<Option<Receiver>, Vec<Receiver>> {
    let selector = selector.trim();
    let exact = discovered
        .iter()
        .filter(|receiver| {
            receiver.id.0.eq_ignore_ascii_case(selector)
                || receiver
                    .uuid
                    .as_deref()
                    .is_some_and(|uuid| uuid.eq_ignore_ascii_case(selector))
        })
        .cloned()
        .collect::<Vec<_>>();
    match exact.as_slice() {
        [receiver] => return Ok(Some(receiver.clone())),
        [] => {}
        _ => return Err(exact),
    }

    let broad = discovered
        .iter()
        .filter(|receiver| {
            receiver.name.eq_ignore_ascii_case(selector)
                || receiver.addresses.iter().any(|address| address == selector)
        })
        .cloned()
        .collect::<Vec<_>>();
    match broad.as_slice() {
        [receiver] => Ok(Some(receiver.clone())),
        [] => Ok(None),
        _ => Err(broad),
    }
}

fn receiver_is_paired(receiver: &Receiver) -> bool {
    if receiver.protocol != ReceiverProtocol::PlayBridge {
        return true;
    }
    PlaybridgeCredentials::load(&receiver_uuid(receiver)).is_some()
}

fn json_receiver(receiver: &Receiver) -> Value {
    json!({
        "id": receiver.id.0,
        "protocol": receiver.protocol.as_str(),
        "name": receiver.name,
        "addresses": receiver.addresses,
        "port": receiver.port,
        "wss_port": receiver.wss_port,
        "location": receiver.location,
        "uuid": receiver.uuid,
        "paired": receiver_is_paired(receiver),
    })
}

fn save_receiver_as_preferred(receiver: &Receiver) -> Result<(), String> {
    let address = receiver
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .cloned()
        .or_else(|| receiver.addresses.first().cloned())
        .unwrap_or_default();
    PreferredDevice {
        uuid: receiver_uuid(receiver),
        name: receiver.name.clone(),
        protocol: receiver.protocol.as_str().into(),
        address,
        port: receiver.port,
        wss_port: receiver.wss_port,
        location: receiver.location.clone(),
    }
    .save()
}

async fn discover_receivers() -> Vec<Receiver> {
    let mut stream = DiscoveryStream::start(DiscoveryConfig::selected(
        HashSet::from(ReceiverProtocol::DEFAULTS),
        Duration::from_secs(5),
    ));
    let mut receivers = BTreeMap::<String, Receiver>::new();
    while let Some(event) = stream.next().await {
        match event {
            DiscoveryEvent::Found(receiver) | DiscoveryEvent::Updated(receiver) => {
                receivers.insert(receiver.id.0.clone(), receiver);
            }
            _ => {}
        }
    }
    receivers.into_values().collect()
}

async fn resolve_json_receiver(device: Option<&str>) -> Result<Receiver, String> {
    if let Some(selector) = device {
        let discovered = discover_receivers().await;
        match select_receiver_by_selector(&discovered, selector) {
            Ok(Some(receiver)) => return Ok(receiver),
            Err(matches) => {
                emit_json(&json!({
                    "ok": false,
                    "error": "ambiguous_device",
                    "message": format!(
                        "Multiple receivers match {selector:?}; select a protocol-qualified id"
                    ),
                    "device": selector,
                    "receivers": matches.iter().map(json_receiver).collect::<Vec<_>>(),
                }))?;
                return Err("ambiguous_device".into());
            }
            Ok(None) => {}
        }
        if let Some(preferred) = PreferredDevice::load()
            && let Ok(receiver) = receiver_from_preferred(&preferred)
            && receiver_matches_device(&receiver, selector)
        {
            return Ok(receiver);
        }
        emit_json(&json!({
            "ok": false,
            "error": "device_not_found",
            "device": selector,
            "receivers": discovered.iter().map(json_receiver).collect::<Vec<_>>(),
        }))?;
        return Err("device_not_found".into());
    }

    if let Some(preferred) = PreferredDevice::load() {
        return receiver_from_preferred(&preferred).inspect_err(|message| {
            let _ = emit_json(&json!({
                "ok": false,
                "error": "unsupported_protocol",
                "protocol": preferred.protocol,
                "message": message,
            }));
        });
    }

    select_discovered_receiver(
        "No preferred receiver is saved.",
        "no_preferred_device",
        None,
    )
    .await
}

async fn select_discovered_receiver(
    reason: &str,
    error: &str,
    message: Option<&str>,
) -> Result<Receiver, String> {
    let discovered = discover_receivers().await;
    if discovered.is_empty() {
        let mut report = json!({
            "ok": false,
            "error": "no_receivers_found",
            "message": reason,
        });
        if let Some(message) = message {
            report["cause"] = json!(message);
        }
        emit_json(&report)?;
        return Err("no_receivers_found".into());
    }

    if can_prompt() {
        eprintln!("{reason}");
        eprintln!("Discovered receivers:");
        for (index, receiver) in discovered.iter().enumerate() {
            let address = receiver
                .addresses
                .iter()
                .find(|address| address.contains('.'))
                .or_else(|| receiver.addresses.first())
                .map(String::as_str)
                .unwrap_or("-");
            let pairing = if receiver_is_paired(receiver) {
                "ready"
            } else {
                "needs pairing"
            };
            eprintln!(
                "  {}. {}  {}  {}  [{pairing}]",
                index + 1,
                receiver.name,
                receiver.protocol.as_str(),
                address
            );
        }
        eprint!("Connect to which receiver? [1-{}] ", discovered.len());
        let _ = io::stderr().flush();
        let choice = read_stdin_line().await?;
        if choice.is_empty() || choice.eq_ignore_ascii_case("q") {
            emit_json(&json!({
                "ok": false,
                "error": "cancelled",
                "message": "No receiver selected",
            }))?;
            return Err("cancelled".into());
        }
        if let Ok(index) = choice.parse::<usize>()
            && index >= 1
            && index <= discovered.len()
        {
            return Ok(discovered[index - 1].clone());
        }
        if let Some(receiver) = discovered
            .iter()
            .find(|receiver| receiver_matches_device(receiver, &choice))
        {
            return Ok(receiver.clone());
        }
        emit_json(&json!({
            "ok": false,
            "error": "device_not_found",
            "device": choice,
            "receivers": discovered.iter().map(json_receiver).collect::<Vec<_>>(),
        }))?;
        return Err("device_not_found".into());
    }

    let mut report = json!({
        "ok": false,
        "error": error,
        "message": reason,
        "receivers": discovered.iter().map(json_receiver).collect::<Vec<_>>(),
    });
    if let Some(message) = message {
        report["cause"] = json!(message);
    }
    emit_json(&report)?;
    Err(error.into())
}

async fn read_stdin_line() -> Result<String, String> {
    let mut line = String::new();
    BufReader::new(tokio::io::stdin())
        .read_line(&mut line)
        .await
        .map_err(|error| format!("failed to read stdin: {error}"))?;
    Ok(line.trim().to_owned())
}

fn emit_connect_error(message: &str, session_id: &str) -> Result<(), String> {
    if message == "pairing_required" {
        emit_json(&json!({
            "ok": false,
            "error": "pairing_required",
            "message": "PlayBridge receiver is unpaired. Re-run with a TTY to enter the code, or pass --pair-code.",
            "session_id": session_id,
        }))
    } else {
        emit_json(&json!({
            "ok": false,
            "error": "cast_failed",
            "message": message,
            "session_id": session_id,
        }))
    }
}

async fn connect_and_load(
    media_url: &str,
    media_title: &str,
    receiver: Receiver,
    pairing: MachinePairing<'_>,
    skip_history: bool,
) -> Result<(Receiver, TargetControl), String> {
    let address = receiver
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .cloned()
        .or_else(|| receiver.addresses.first().cloned())
        .ok_or_else(|| format!("{} has no reachable address", receiver.name))?;
    let protocol = receiver.protocol;
    let control = match protocol {
        ReceiverProtocol::PlayBridge => {
            let port = receiver.wss_port.or(receiver.port).unwrap_or(8765);
            let uuid = receiver_uuid(&receiver);
            TargetControl::Playbridge(Box::new(
                cast_to_playbridge_maybe_pair(
                    &address,
                    port,
                    PlaybridgeLoad {
                        device_name: &receiver.name,
                        device_uuid: &uuid,
                        media_url,
                        media_title,
                        skip_history,
                    },
                    pairing,
                )
                .await?,
            ))
        }
        _ => cast_to_target(
            protocol.as_str(),
            &address,
            receiver.port,
            receiver.location.as_deref(),
            media_url,
            media_title,
        )
        .await?
        .ok_or_else(|| format!("{} did not provide playback controls", receiver.name))?,
    };
    Ok((receiver, control))
}

async fn wait_for_pair_code(
    device_name: &str,
    pair_code: Option<&str>,
    pair_code_file: Option<&std::path::Path>,
    session_id: &str,
) -> Result<String, String> {
    emit_json(&json!({
        "ok": false,
        "error": "pairing_required",
        "event": "pairing_required",
        "device": device_name,
        "message": format!("Enter the six-digit code shown by {device_name}"),
        "pair_code_file": pair_code_file.map(|path| path.display().to_string()),
        "session_id": session_id,
    }))?;
    if io::stderr().is_terminal() {
        eprintln!("Enter the six-digit code shown by {device_name}:");
        let _ = io::stderr().flush();
    }

    if let Some(code) = pair_code.filter(|code| !code.trim().is_empty()) {
        return normalize_pair_code(code);
    }
    if let Some(path) = pair_code_file {
        return wait_for_pair_code_file(path).await;
    }
    let entered = read_stdin_line().await?;
    if entered.is_empty() {
        return Err("pairing_required".into());
    }
    normalize_pair_code(&entered)
}

async fn wait_for_pair_code_file(path: &std::path::Path) -> Result<String, String> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(180);
    let mut last = String::new();
    loop {
        if let Ok(contents) = std::fs::read_to_string(path) {
            let code: String = contents.chars().filter(|ch| ch.is_ascii_digit()).collect();
            if code.len() == 6 && code != last {
                let _ = std::fs::remove_file(path);
                return Ok(code);
            }
            last = code;
        }
        if tokio::time::Instant::now() >= deadline {
            return Err("pairing_required: timed out waiting for --pair-code-file".into());
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

fn normalize_pair_code(value: &str) -> Result<String, String> {
    let digits: String = value.chars().filter(|ch| ch.is_ascii_digit()).collect();
    if digits.len() != 6 {
        return Err("Pairing code must contain exactly six digits".into());
    }
    Ok(digits)
}

async fn cast_to_playbridge_maybe_pair(
    address: &str,
    wss_port: u16,
    load: PlaybridgeLoad<'_>,
    machine_pairing: MachinePairing<'_>,
) -> Result<SecureWebSocket, String> {
    if PlaybridgeCredentials::load(load.device_uuid).is_some() {
        return cast_to_playbridge(address, wss_port, load).await;
    }

    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);
    let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
        .await
        .map_err(|error| error.to_string())?;
    let served_pin = socket.served_spki_pin().to_owned();
    let (mut pairing, commit) =
        PairingSession::start(load.device_name.to_owned(), load.device_uuid.to_owned())
            .map_err(|error| error.to_string())?;
    socket
        .send(&commit)
        .await
        .map_err(|error| error.to_string())?;

    while let Some(frame) = socket.receive().await.map_err(|error| error.to_string())? {
        match frame {
            ReceiverFrame::PairingChallenge {
                tv_eph_pub,
                nonce_t,
            } => {
                let (sas, reveal) = pairing
                    .accept_challenge(&tv_eph_pub, &nonce_t)
                    .map_err(|error| error.to_string())?;
                socket
                    .send(&reveal)
                    .await
                    .map_err(|error| error.to_string())?;
                let entered = wait_for_pair_code(
                    load.device_name,
                    machine_pairing.code,
                    machine_pairing.file,
                    machine_pairing.session_id,
                )
                .await?;
                let confirmation = pairing
                    .confirmation(&entered, &sas)
                    .map_err(|_| "The code does not match the receiver".to_owned())?;
                socket
                    .send(&confirmation)
                    .await
                    .map_err(|error| error.to_string())?;
            }
            ReceiverFrame::PairingApproved { nonce, ciphertext } => {
                let bundle = pairing
                    .decrypt_credentials(&nonce, &ciphertext, Some(&served_pin))
                    .map_err(|error| error.to_string())?;
                let credentials = PlaybridgeCredentials {
                    token: bundle.token,
                    cert_fingerprint: bundle
                        .cert_fingerprint
                        .unwrap_or_else(|| served_pin.clone()),
                    players: bundle.players,
                    browsers: bundle.browsers,
                };
                credentials.save(load.device_uuid)?;
                send_playlist(
                    &mut socket,
                    load.media_url,
                    load.media_title,
                    load.skip_history,
                )
                .await?;
                return Ok(socket);
            }
            ReceiverFrame::PairingDenied => {
                return Err("Pairing was denied or timed out on the receiver".into());
            }
            _ => {}
        }
    }

    Err("Receiver closed connection before pairing completed".into())
}

/// Runs a cast selected by the dashboard. Unlike the legacy command path this
/// never reads the terminal: the dashboard remains responsible for input and
/// sends the stop signal when the user ends the session.
pub(crate) async fn run_dashboard_cast(
    media_target: String,
    target: playbridge_cast_core::discovery::Receiver,
    generation: u64,
    mut commands: tokio::sync::mpsc::Receiver<CastCommand>,
    events: tokio::sync::mpsc::Sender<CastEvent>,
    skip_history_override: Option<bool>,
) -> Result<(), String> {
    validate_media_target(&media_target)?;
    let skip_history = skip_history_override.unwrap_or(crate::ui::skip_history_default()?);
    let dashboard_title = media_title(&media_target).unwrap_or_else(|| "Untitled media".into());

    let resolved_path = resolve_media_path(&media_target);
    let (media_url, proxy_server) = if resolved_path.is_file() {
        let server = ProxyServer::start(ProxyServerConfig::default()).await?;
        let host = primary_lan_host(server.local_addr().port())?;
        let media =
            server.register_file(&host, resolved_path, None, Duration::from_secs(6 * 60 * 60))?;
        (media.url, Some(server))
    } else {
        (media_target, None)
    };

    let address = target
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .cloned()
        .or_else(|| target.addresses.first().cloned())
        .ok_or_else(|| format!("{} has no reachable address", target.name))?;
    let protocol = target.protocol.as_str().to_owned();
    let mut control = match protocol.to_lowercase().as_str() {
        "playbridge" | "native" => {
            let port = target.wss_port.or(target.port).unwrap_or(8765);
            let uuid = target.uuid.clone().unwrap_or_else(|| target.id.0.clone());
            TargetControl::Playbridge(Box::new(
                cast_to_playbridge_dashboard(
                    &address,
                    port,
                    PlaybridgeLoad {
                        device_name: &target.name,
                        device_uuid: &uuid,
                        media_url: &media_url,
                        media_title: &dashboard_title,
                        skip_history,
                    },
                    DashboardPairing {
                        generation,
                        commands: &mut commands,
                        events: &events,
                    },
                )
                .await?,
            ))
        }
        _ => cast_to_target(
            &protocol,
            &address,
            target.port,
            target.location.as_deref(),
            &media_url,
            &dashboard_title,
        )
        .await?
        .ok_or_else(|| format!("{} did not provide playback controls", target.name))?,
    };

    let capabilities = dashboard_capabilities(&control);
    let mut snapshot = CastSnapshot {
        state: "buffering".into(),
        title: dashboard_title,
        position_ms: 0,
        duration_ms: 0,
        volume: capabilities.volume.then_some(0.5),
        muted: capabilities.mute.then_some(false),
        looping: capabilities.looping.then_some(false),
        speed: capabilities.speed.then_some(1.0),
    };
    let _ = events
        .send(CastEvent::Connected {
            generation,
            capabilities,
            snapshot: snapshot.clone(),
        })
        .await;

    let mut tick = tokio::time::interval(Duration::from_secs(1));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut poll_count = 0_u64;
    let mut consecutive_poll_failures = 0_u8;
    let mut session_error = None;
    loop {
        tokio::select! {
            command = commands.recv() => {
                match command {
                    Some(CastCommand::Stop) | None => break,
                    Some(CastCommand::SubmitPairingCode(_) | CastCommand::CancelPairing | CastCommand::SubmitBrowserPairing(_)) => {}
                    Some(command) => {
                        if let Err(message) = dashboard_control(&mut control, command, &mut snapshot).await {
                            let _ = events.send(CastEvent::Warning { generation, message }).await;
                        } else {
                            let _ = events.try_send(CastEvent::Snapshot { generation, snapshot: snapshot.clone() });
                        }
                    }
                }
            }
            _ = tick.tick() => {
                poll_count = poll_count.wrapping_add(1);
                if let Err(message) = dashboard_poll(
                    &mut control,
                    &mut snapshot,
                    poll_count.checked_rem(3) == Some(0),
                ).await {
                    consecutive_poll_failures = consecutive_poll_failures.saturating_add(1);
                    if consecutive_poll_failures >= 3 {
                        session_error = Some(format!("cast connection lost: {message}"));
                        break;
                    }
                    let _ = events.try_send(CastEvent::Warning { generation, message });
                } else {
                    consecutive_poll_failures = 0;
                }
                let _ = events.try_send(CastEvent::Snapshot { generation, snapshot: snapshot.clone() });
            }
        }
    }

    stop_target(control).await;
    if let Some(server) = proxy_server
        && let Err(error) = server.shutdown().await
    {
        return Err(format!("failed to stop media proxy: {error}"));
    }
    session_error.map_or(Ok(()), Err)
}

pub(crate) async fn run_dashboard_browser_cast(
    media_target: String,
    generation: u64,
    mut commands: tokio::sync::mpsc::Receiver<CastCommand>,
    events: tokio::sync::mpsc::Sender<CastEvent>,
) -> Result<(), String> {
    validate_media_target(&media_target)?;
    let title = media_title(&media_target).unwrap_or_else(|| "Untitled media".into());
    let mut host = Some(BrowserReceiverHost::start(BrowserReceiverConfig::default()).await?);
    let service = host.as_ref().expect("browser host exists").service();
    let _ = events
        .send(CastEvent::BrowserHosting {
            generation,
            urls: host.as_ref().expect("browser host exists").urls(),
        })
        .await;

    let proxy = ProxyServer::start(ProxyServerConfig::default()).await?;
    let proxy_host = primary_lan_host(proxy.local_addr().port())?;
    let path = resolve_media_path(&media_target);
    let media_url = if path.is_file() {
        proxy
            .register_file(&proxy_host, path, None, Duration::from_secs(6 * 60 * 60))?
            .url
    } else {
        proxy
            .register_remote(&proxy_host, &media_target, HashMap::new())?
            .url
    };
    let mut browser_events = service.subscribe();
    let mut pending_session: Option<BrowserSessionSnapshot> = None;

    let session = loop {
        tokio::select! {
            command = commands.recv() => match command {
                Some(CastCommand::Stop | CastCommand::CancelPairing) | None => {
                    if let Some(host) = host.take() {
                        let _ = host.shutdown().await;
                    }
                    let _ = proxy.shutdown().await;
                    return Ok(());
                }
                Some(CastCommand::SubmitBrowserPairing(code)) => {
                    let Some(session) = pending_session.as_ref() else {
                        let _ = events.send(CastEvent::Warning {
                            generation,
                            message: "No browser is waiting for pairing".into(),
                        }).await;
                        continue;
                    };
                    match service.approve(&session.session_id, code.trim()).await {
                        Ok(()) => break session.clone(),
                        Err(message) => {
                            let _ = events.send(CastEvent::Warning { generation, message }).await;
                        }
                    }
                }
                Some(_) => {}
            },
            event = browser_events.recv() => match event.map_err(|error| error.to_string())? {
                BrowserReceiverEvent::PairingRequested { session, .. } => {
                    pending_session = Some(session.clone());
                    let _ = events.send(CastEvent::BrowserPairingRequested {
                        generation,
                        device_name: session.name,
                    }).await;
                }
                BrowserReceiverEvent::Connected { session } if session.approved => break session,
                BrowserReceiverEvent::Error { message, .. } => {
                    let _ = events.send(CastEvent::Warning { generation, message }).await;
                }
                BrowserReceiverEvent::Disconnected { name, .. } => {
                    let _ = events.send(CastEvent::Warning {
                        generation,
                        message: format!("Browser receiver {name} disconnected"),
                    }).await;
                }
                _ => {}
            },
        }
    };

    service
        .load(
            &session.session_id,
            BrowserMedia {
                url: media_url,
                title: Some(title.clone()),
                content_type: media_content_type(&media_target),
                poster_url: None,
                subtitle_url: None,
                start_position_ms: None,
            },
        )
        .await?;
    let mut control = TargetControl::Browser {
        host: host.take().expect("browser host exists"),
        service,
        session_id: session.session_id,
        events: browser_events,
    };
    let capabilities = dashboard_capabilities(&control);
    let mut snapshot = CastSnapshot {
        state: "buffering".into(),
        title,
        position_ms: 0,
        duration_ms: 0,
        volume: Some(1.0),
        muted: Some(false),
        looping: None,
        speed: None,
    };
    let _ = events
        .send(CastEvent::Connected {
            generation,
            capabilities,
            snapshot: snapshot.clone(),
        })
        .await;

    let mut tick = tokio::time::interval(Duration::from_secs(1));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut session_error = None;
    loop {
        tokio::select! {
            command = commands.recv() => match command {
                Some(CastCommand::Stop) | None => break,
                Some(CastCommand::SubmitPairingCode(_) | CastCommand::CancelPairing | CastCommand::SubmitBrowserPairing(_)) => {}
                Some(command) => {
                    if let Err(message) = dashboard_control(&mut control, command, &mut snapshot).await {
                        let _ = events.send(CastEvent::Warning { generation, message }).await;
                    } else {
                        let _ = events.try_send(CastEvent::Snapshot { generation, snapshot: snapshot.clone() });
                    }
                }
            },
            _ = tick.tick() => {
                match dashboard_poll(&mut control, &mut snapshot, false).await {
                    Ok(()) => {
                        let _ = events.try_send(CastEvent::Snapshot { generation, snapshot: snapshot.clone() });
                    }
                    Err(message) => {
                        session_error = Some(format!("browser receiver connection lost: {message}"));
                        break;
                    }
                }
            }
        }
    }
    stop_target(control).await;
    let proxy_result = proxy
        .shutdown()
        .await
        .map_err(|error| format!("failed to stop media proxy: {error}"));
    proxy_result?;
    session_error.map_or(Ok(()), Err)
}

fn resolve_media_path(media_target: &str) -> PathBuf {
    if let Some(relative) = media_target.strip_prefix("~/")
        && let Some(home) = std::env::var_os("HOME")
    {
        return PathBuf::from(home).join(relative);
    }
    PathBuf::from(media_target)
}

pub(crate) fn validate_media_target(media_target: &str) -> Result<(), String> {
    let target = media_target.trim();
    if target.is_empty() {
        return Err("media target cannot be empty".into());
    }

    let path = resolve_media_path(target);
    if path.exists() {
        if path.is_file() {
            return Ok(());
        }
        return Err(format!("media path is not a file: {}", path.display()));
    }

    if target.starts_with("http://") || target.starts_with("https://") {
        return Ok(());
    }

    Err(format!("media file does not exist: {}", path.display()))
}

fn display_media_target(target: &str) -> String {
    let Ok(url) = reqwest::Url::parse(target) else {
        return target.to_owned();
    };
    let Some(host) = url.host_str() else {
        return "<redacted URL>".into();
    };
    let port = url
        .port()
        .map_or_else(String::new, |port| format!(":{port}"));
    let query = url.query().map_or("", |_| "?<redacted>");
    format!("{}://{host}{port}{}{query}", url.scheme(), url.path())
}

fn primary_lan_host(port: u16) -> Result<String, String> {
    let url = local_urls(port)
        .into_iter()
        .find(|url| !url.contains("127.0.0.1"))
        .or_else(|| local_urls(port).into_iter().next())
        .ok_or_else(|| "could not determine a LAN address for the media proxy".to_owned())?;
    url.strip_prefix("http://")
        .and_then(|value| value.rsplit_once(':').map(|(host, _)| host.to_owned()))
        .ok_or_else(|| "invalid local proxy address".to_owned())
}

fn media_title(target: &str) -> Option<String> {
    if let Ok(url) = reqwest::Url::parse(target)
        && matches!(url.scheme(), "http" | "https")
    {
        return url
            .path_segments()
            .and_then(|mut segments| segments.rfind(|segment| !segment.is_empty()))
            .map(str::to_owned);
    }
    let path = resolve_media_path(target);
    path.file_name()
        .and_then(|value| value.to_str())
        .map(str::to_owned)
}

fn media_content_type(target: &str) -> Option<String> {
    let lower = target
        .split('?')
        .next()
        .unwrap_or(target)
        .to_ascii_lowercase();
    let content_type = if lower.ends_with(".m3u8") {
        "application/vnd.apple.mpegurl"
    } else if lower.ends_with(".mpd") {
        "application/dash+xml"
    } else if lower.ends_with(".webm") {
        "video/webm"
    } else if lower.ends_with(".mp3") {
        "audio/mpeg"
    } else if lower.ends_with(".m4a") {
        "audio/mp4"
    } else if lower.ends_with(".mp4") || lower.ends_with(".m4v") {
        "video/mp4"
    } else {
        return None;
    };
    Some(content_type.into())
}

enum TargetControl {
    Cast {
        channel: castv2::CastChannel,
        destination_id: String,
        session_id: String,
        media_session_id: i64,
    },
    Dlna(Renderer),
    Playbridge(Box<SecureWebSocket>),
    Roku(ReceiverSession),
    Browser {
        host: BrowserReceiverHost,
        service: BrowserReceiverService,
        session_id: String,
        events: tokio::sync::broadcast::Receiver<BrowserReceiverEvent>,
    },
}

fn dashboard_capabilities(control: &TargetControl) -> CastCapabilities {
    match control {
        TargetControl::Playbridge(_) => CastCapabilities {
            play_pause: true,
            seek: true,
            volume: true,
            mute: true,
            looping: true,
            speed: true,
            audio_boost: true,
        },
        TargetControl::Cast { .. } | TargetControl::Browser { .. } => CastCapabilities {
            play_pause: true,
            seek: true,
            volume: true,
            ..CastCapabilities::default()
        },
        TargetControl::Dlna(_) | TargetControl::Roku(_) => CastCapabilities {
            play_pause: true,
            seek: true,
            ..CastCapabilities::default()
        },
    }
}

async fn dashboard_poll(
    control: &mut TargetControl,
    snapshot: &mut CastSnapshot,
    heartbeat: bool,
) -> Result<(), String> {
    match control {
        TargetControl::Cast {
            channel,
            destination_id,
            media_session_id,
            ..
        } => {
            if heartbeat {
                castv2::send_heartbeat_ping(channel).await?;
            }
            castv2::send_get_media_status(channel, destination_id).await?;
            if let Ok(Ok(message)) =
                tokio::time::timeout(Duration::from_millis(80), channel.read_message()).await
            {
                if message.namespace == castv2::NS_HEARTBEAT {
                    channel.handle_heartbeat(&message).await?;
                } else if message.namespace == castv2::NS_MEDIA
                    && let Ok(value) = serde_json::from_str::<Value>(&message.payload_utf8)
                    && let Some(status) = value["status"].as_array().and_then(|items| items.first())
                {
                    if let Some(id) = status["mediaSessionId"].as_i64() {
                        *media_session_id = id;
                    }
                    snapshot.position_ms = status["currentTime"]
                        .as_f64()
                        .map(|seconds| (seconds * 1000.0) as u64)
                        .unwrap_or(snapshot.position_ms);
                    snapshot.duration_ms = status["media"]["duration"]
                        .as_f64()
                        .map(|seconds| (seconds * 1000.0) as u64)
                        .unwrap_or(snapshot.duration_ms);
                    if let Some(state) = status["playerState"].as_str() {
                        snapshot.state = state.to_ascii_lowercase();
                    }
                }
            }
        }
        TargetControl::Playbridge(socket) => {
            if heartbeat {
                socket
                    .send(&SenderFrame::Ping)
                    .await
                    .map_err(|error| error.to_string())?;
            }
            while let Ok(Ok(Some(frame))) =
                tokio::time::timeout(Duration::from_millis(10), socket.receive()).await
            {
                if let ReceiverFrame::Status {
                    state,
                    position,
                    duration,
                    title,
                    ..
                } = frame
                {
                    snapshot.state = state;
                    snapshot.position_ms = position;
                    snapshot.duration_ms = duration;
                    if let Some(title) = title {
                        snapshot.title = title;
                    }
                }
            }
        }
        TargetControl::Dlna(renderer) => {
            if let Ok(info) = renderer.transport_info().await
                && let Some(state) = info.get("CurrentTransportState")
            {
                snapshot.state = state.to_ascii_lowercase();
            }
            if let Ok(info) = renderer.position_info().await {
                if let Some(value) = info.get("RelTime").and_then(|value| parse_dlna_time(value)) {
                    snapshot.position_ms = (value * 1000.0) as u64;
                }
                if let Some(value) = info
                    .get("TrackDuration")
                    .and_then(|value| parse_dlna_time(value))
                {
                    snapshot.duration_ms = (value * 1000.0) as u64;
                }
            }
        }
        TargetControl::Roku(session) => {
            let status = session.status().await.map_err(|error| error.to_string())?;
            snapshot.state = format!("{:?}", status.state).to_ascii_lowercase();
            snapshot.position_ms = (status.position_seconds * 1000.0) as u64;
            snapshot.duration_ms = (status.duration_seconds * 1000.0) as u64;
        }
        TargetControl::Browser { events, .. } => {
            while let Ok(event) = events.try_recv() {
                match event {
                    BrowserReceiverEvent::Status { session, .. } => {
                        snapshot.position_ms = session.status.position_ms;
                        snapshot.duration_ms = session.status.duration_ms;
                        snapshot.volume = Some(session.status.volume as f32);
                        snapshot.muted = Some(session.status.muted);
                        snapshot.state = format!("{:?}", session.status.state).to_ascii_lowercase();
                    }
                    BrowserReceiverEvent::Ended { .. } => snapshot.state = "ended".into(),
                    BrowserReceiverEvent::Error { message, .. } => return Err(message),
                    BrowserReceiverEvent::Disconnected { name, .. } => {
                        return Err(format!("browser receiver {name} disconnected"));
                    }
                    BrowserReceiverEvent::PairingRequested { .. }
                    | BrowserReceiverEvent::Connected { .. }
                    | BrowserReceiverEvent::Capabilities { .. } => {}
                }
            }
        }
    }
    Ok(())
}

async fn dashboard_control(
    control: &mut TargetControl,
    command: CastCommand,
    snapshot: &mut CastSnapshot,
) -> Result<(), String> {
    let unsupported = || Err("This receiver does not support that control".into());
    match command {
        CastCommand::Stop
        | CastCommand::SubmitPairingCode(_)
        | CastCommand::CancelPairing
        | CastCommand::SubmitBrowserPairing(_) => Ok(()),
        CastCommand::PlayPause => {
            let pause = snapshot.state != "paused";
            match control {
                TargetControl::Cast {
                    channel,
                    destination_id,
                    media_session_id,
                    ..
                } => {
                    if pause {
                        castv2::send_pause(channel, destination_id, *media_session_id).await?;
                    } else {
                        castv2::send_play(channel, destination_id, *media_session_id).await?;
                    }
                }
                TargetControl::Dlna(renderer) => {
                    if pause {
                        renderer.pause().await.map_err(|error| error.to_string())?
                    } else {
                        renderer.play().await.map_err(|error| error.to_string())?
                    }
                }
                TargetControl::Roku(session) => {
                    if pause {
                        session.pause().await.map_err(|error| error.to_string())?
                    } else {
                        session.play().await.map_err(|error| error.to_string())?
                    }
                }
                TargetControl::Playbridge(socket) => {
                    socket
                        .send(&SenderFrame::Command {
                            action: "control".into(),
                            payload: Some(json!({"command": if pause {"pause"} else {"play"}})),
                        })
                        .await
                        .map_err(|error| error.to_string())?;
                }
                TargetControl::Browser {
                    service,
                    session_id,
                    ..
                } => {
                    service.command(
                        session_id,
                        if pause {
                            BrowserCommand::Pause
                        } else {
                            BrowserCommand::Play
                        },
                        None,
                    )?;
                }
            }
            snapshot.state = if pause { "paused" } else { "playing" }.into();
            Ok(())
        }
        CastCommand::SeekRelative(delta) => {
            let position_ms = snapshot.position_ms.saturating_add_signed(delta * 1000);
            match control {
                TargetControl::Cast {
                    channel,
                    destination_id,
                    media_session_id,
                    ..
                } => {
                    castv2::send_seek(
                        channel,
                        destination_id,
                        *media_session_id,
                        position_ms as f64 / 1000.0,
                    )
                    .await?
                }
                TargetControl::Dlna(renderer) => renderer
                    .seek(&format_time(position_ms as f64 / 1000.0))
                    .await
                    .map_err(|error| error.to_string())?,
                TargetControl::Roku(session) => session
                    .relative_seek(delta > 0)
                    .await
                    .map_err(|error| error.to_string())?,
                TargetControl::Playbridge(socket) => socket
                    .send(&SenderFrame::Command {
                        action: "control".into(),
                        payload: Some(
                            json!({"command": if delta < 0 {"seek_back"} else {"seek_forward"}}),
                        ),
                    })
                    .await
                    .map_err(|error| error.to_string())?,
                TargetControl::Browser {
                    service,
                    session_id,
                    ..
                } => {
                    let _ = service.command(
                        session_id,
                        BrowserCommand::Seek,
                        Some(position_ms as f64),
                    )?;
                }
            }
            snapshot.position_ms = position_ms;
            Ok(())
        }
        CastCommand::VolumeDelta(delta) => {
            let volume = (snapshot.volume.unwrap_or(0.5) + delta).clamp(0.0, 1.0);
            match control {
                TargetControl::Cast { channel, .. } => castv2::send_volume(channel, volume).await?,
                TargetControl::Playbridge(socket) => socket
                    .send(&SenderFrame::Command {
                        action: "remote".into(),
                        payload: Some(
                            json!({"key": if delta < 0.0 {"volume_down"} else {"volume_up"}}),
                        ),
                    })
                    .await
                    .map_err(|error| error.to_string())?,
                TargetControl::Browser {
                    service,
                    session_id,
                    ..
                } => {
                    let _ = service.command(
                        session_id,
                        BrowserCommand::SetVolume,
                        Some(volume as f64),
                    )?;
                }
                _ => return unsupported(),
            }
            snapshot.volume = Some(volume);
            Ok(())
        }
        CastCommand::ToggleMute
        | CastCommand::ToggleLoop
        | CastCommand::ToggleAudioBoost
        | CastCommand::SetSpeed(_) => {
            let TargetControl::Playbridge(socket) = control else {
                return unsupported();
            };
            let (action, payload) = match command {
                CastCommand::ToggleMute => ("remote", json!({"key":"mute"})),
                CastCommand::ToggleLoop => (
                    "control",
                    json!({"command": if snapshot.looping.unwrap_or(false) {"loop_off"} else {"loop_on"}}),
                ),
                CastCommand::ToggleAudioBoost => ("control", json!({"command":"audio_boost"})),
                CastCommand::SetSpeed(speed) => {
                    ("control", json!({"command":format!("speed:{speed}")}))
                }
                _ => unreachable!(),
            };
            socket
                .send(&SenderFrame::Command {
                    action: action.into(),
                    payload: Some(payload),
                })
                .await
                .map_err(|error| error.to_string())?;
            match command {
                CastCommand::ToggleMute => snapshot.muted = Some(!snapshot.muted.unwrap_or(false)),
                CastCommand::ToggleLoop => {
                    snapshot.looping = Some(!snapshot.looping.unwrap_or(false))
                }
                CastCommand::SetSpeed(speed) => snapshot.speed = Some(speed),
                _ => {}
            }
            Ok(())
        }
    }
}

fn format_time(secs: f64) -> String {
    let s = secs.max(0.0) as u64;
    let hrs = s / 3600;
    let mins = (s % 3600) / 60;
    let secs = s % 60;
    if hrs > 0 {
        format!("{:02}:{:02}:{:02}", hrs, mins, secs)
    } else {
        format!("{:02}:{:02}", mins, secs)
    }
}

fn parse_dlna_time(time_str: &str) -> Option<f64> {
    let parts: Vec<&str> = time_str.trim().split(':').collect();
    match parts.len() {
        3 => {
            let h: f64 = parts[0].parse().ok()?;
            let m: f64 = parts[1].parse().ok()?;
            let s: f64 = parts[2].parse().ok()?;
            Some(h * 3600.0 + m * 60.0 + s)
        }
        2 => {
            let m: f64 = parts[0].parse().ok()?;
            let s: f64 = parts[1].parse().ok()?;
            Some(m * 60.0 + s)
        }
        _ => None,
    }
}

async fn stop_target(target_control: TargetControl) {
    match target_control {
        TargetControl::Cast {
            mut channel,
            destination_id,
            session_id,
            media_session_id,
        } => {
            let _ = castv2::send_stop_media(&mut channel, &destination_id, media_session_id).await;
            if !session_id.is_empty() {
                let _ = castv2::send_stop_session(&mut channel, &session_id).await;
            }
        }
        TargetControl::Dlna(renderer) => {
            let _ = renderer.stop().await;
        }
        TargetControl::Playbridge(mut socket) => {
            let cmd = SenderFrame::Command {
                action: "control".into(),
                payload: Some(serde_json::json!({ "command": "stop" })),
            };
            let _ = socket.send(&cmd).await;
            let _ = (*socket).close().await;
        }
        TargetControl::Roku(mut session) => {
            let _ = session.stop().await;
        }
        TargetControl::Browser {
            host,
            service,
            session_id,
            ..
        } => {
            let _ = service.command(&session_id, BrowserCommand::Stop, None);
            service.disconnect(&session_id);
            let _ = host.shutdown().await;
        }
    }
}

async fn cast_to_target(
    protocol: &str,
    address: &str,
    port: Option<u16>,
    location: Option<&str>,
    media_url: &str,
    media_title: &str,
) -> Result<Option<TargetControl>, String> {
    match protocol.to_lowercase().as_str() {
        "google_cast" | "googlecast" | "chromecast" => {
            let application_id = env::var("PLAYBRIDGE_GOOGLE_CAST_APP_ID")
                .unwrap_or_else(|_| castv2::DEFAULT_MEDIA_RECEIVER_APP_ID.to_owned());
            let mut details = castv2::launch_app_session_with_strategy(
                address,
                port.unwrap_or(8009),
                &application_id,
                castv2::SessionLaunchStrategy::ForceRelaunch,
            )
            .await?;
            let (content_type, stream_type) = castv2::media_format(media_url);
            let media_session_id = castv2::load_media(
                &mut details,
                media_url,
                Some(content_type),
                stream_type,
                Some(media_title),
                None,
                0.0,
                None,
                None,
            )
            .await
            .map_err(|error| error.to_string())?;
            Ok(Some(TargetControl::Cast {
                channel: details.channel,
                destination_id: details.transport_id,
                session_id: details.session_id,
                media_session_id,
            }))
        }
        "dlna" => {
            let location =
                location.ok_or_else(|| "DLNA location description missing".to_string())?;
            let renderer = Renderer::load(location)
                .await
                .map_err(|error| error.to_string())?;
            renderer
                .set_media_uri(media_url, "")
                .await
                .map_err(|error| error.to_string())?;
            renderer.play().await.map_err(|error| error.to_string())?;
            Ok(Some(TargetControl::Dlna(renderer)))
        }
        "roku" => {
            let mut session = ReceiverSession::connect_roku(address, port.unwrap_or(8060))
                .map_err(|error| error.to_string())?;
            let mut media = MediaRequest::new(media_url);
            media.title = Some(media_title.to_owned());
            session
                .load(&media)
                .await
                .map_err(|error| error.to_string())?;
            Ok(Some(TargetControl::Roku(session)))
        }
        "playbridge" | "native" => Err(
            "PlayBridge devices must use the dashboard pairing route (internal routing error)"
                .into(),
        ),
        _ => Err(format!("Unsupported target protocol: {protocol}")),
    }
}

async fn cast_to_playbridge(
    address: &str,
    wss_port: u16,
    load: PlaybridgeLoad<'_>,
) -> Result<SecureWebSocket, String> {
    let credentials = PlaybridgeCredentials::load(load.device_uuid)
        .ok_or_else(|| format!("{} has no stored pairing credentials", load.device_name))?;
    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);
    let mut socket = SecureWebSocket::connect_pinned(&endpoint, &credentials.cert_fingerprint)
        .await
        .map_err(|error| error.to_string())?;
    socket
        .send(&SenderFrame::Auth {
            token: credentials.token,
        })
        .await
        .map_err(|error| error.to_string())?;

    loop {
        match socket.receive().await.map_err(|error| error.to_string())? {
            Some(ReceiverFrame::AuthResponse { success: true, .. }) => break,
            Some(ReceiverFrame::AuthResponse { success: false, .. }) => {
                return Err("Authentication failed".into());
            }
            Some(_) => {}
            None => return Err("Receiver closed connection during auth".into()),
        }
    }

    send_playlist(
        &mut socket,
        load.media_url,
        load.media_title,
        load.skip_history,
    )
    .await?;
    Ok(socket)
}

struct DashboardPairing<'a> {
    generation: u64,
    commands: &'a mut tokio::sync::mpsc::Receiver<CastCommand>,
    events: &'a tokio::sync::mpsc::Sender<CastEvent>,
}

async fn cast_to_playbridge_dashboard(
    address: &str,
    wss_port: u16,
    load: PlaybridgeLoad<'_>,
    pairing_ui: DashboardPairing<'_>,
) -> Result<SecureWebSocket, String> {
    let DashboardPairing {
        generation,
        commands,
        events,
    } = pairing_ui;
    if PlaybridgeCredentials::load(load.device_uuid).is_some() {
        return cast_to_playbridge(address, wss_port, load).await;
    }

    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);
    let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
        .await
        .map_err(|error| error.to_string())?;
    let served_pin = socket.served_spki_pin().to_owned();
    let (mut pairing, commit) =
        PairingSession::start(load.device_name.to_owned(), load.device_uuid.to_owned())
            .map_err(|error| error.to_string())?;
    socket
        .send(&commit)
        .await
        .map_err(|error| error.to_string())?;

    while let Some(frame) = socket.receive().await.map_err(|error| error.to_string())? {
        match frame {
            ReceiverFrame::PairingChallenge {
                tv_eph_pub,
                nonce_t,
            } => {
                let (sas, reveal) = pairing
                    .accept_challenge(&tv_eph_pub, &nonce_t)
                    .map_err(|error| error.to_string())?;
                socket
                    .send(&reveal)
                    .await
                    .map_err(|error| error.to_string())?;
                events
                    .send(CastEvent::PairingCodeRequested {
                        generation,
                        device_name: load.device_name.to_owned(),
                    })
                    .await
                    .map_err(|_| "dashboard closed during pairing".to_owned())?;

                loop {
                    tokio::select! {
                        command = commands.recv() => match command {
                            Some(CastCommand::SubmitPairingCode(code)) => {
                                match pairing.confirmation(&code, &sas) {
                                    Ok(confirmation) => {
                                        socket
                                            .send(&confirmation)
                                            .await
                                            .map_err(|error| error.to_string())?;
                                        break;
                                    }
                                    Err(_) => {
                                        let _ = events.send(CastEvent::Warning {
                                            generation,
                                            message: "The code does not match the receiver. Try again.".into(),
                                        }).await;
                                    }
                                }
                            }
                            Some(CastCommand::CancelPairing | CastCommand::Stop) | None => {
                                let _ = socket.close().await;
                                return Err("Pairing cancelled".into());
                            }
                            Some(_) => {}
                        },
                        frame = socket.receive() => match frame.map_err(|error| error.to_string())? {
                            Some(ReceiverFrame::PairingDenied) => {
                                return Err("Pairing was denied or timed out on the receiver".into());
                            }
                            Some(_) => {}
                            None => return Err("Receiver closed connection during pairing".into()),
                        }
                    }
                }
            }
            ReceiverFrame::PairingApproved { nonce, ciphertext } => {
                let bundle = pairing
                    .decrypt_credentials(&nonce, &ciphertext, Some(&served_pin))
                    .map_err(|error| error.to_string())?;
                let credentials = PlaybridgeCredentials {
                    token: bundle.token,
                    cert_fingerprint: bundle
                        .cert_fingerprint
                        .unwrap_or_else(|| served_pin.clone()),
                    players: bundle.players,
                    browsers: bundle.browsers,
                };
                credentials.save(load.device_uuid)?;
                events
                    .send(CastEvent::PairingCompleted {
                        generation,
                        device_name: load.device_name.to_owned(),
                    })
                    .await
                    .map_err(|_| "dashboard closed after pairing".to_owned())?;
                send_playlist(
                    &mut socket,
                    load.media_url,
                    load.media_title,
                    load.skip_history,
                )
                .await?;
                return Ok(socket);
            }
            ReceiverFrame::PairingDenied => {
                return Err("Pairing was denied by the receiver".into());
            }
            _ => {}
        }
    }

    Err("Receiver closed connection before pairing completed".into())
}

async fn send_playlist(
    socket: &mut SecureWebSocket,
    media_url: &str,
    media_title: &str,
    skip_history: bool,
) -> Result<(), String> {
    socket
        .send(&playlist_command(media_url, media_title, skip_history))
        .await
        .map_err(|e| e.to_string())?;
    Ok(())
}

fn playlist_command(media_url: &str, media_title: &str, skip_history: bool) -> SenderFrame {
    SenderFrame::Command {
        action: "playlist".into(),
        payload: Some(serde_json::json!({
            "items": [{
                "url": media_url,
                "title": media_title,
                "skipHistory": skip_history,
            }]
        })),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playlist_command_sets_skip_history() {
        let SenderFrame::Command { payload, .. } =
            playlist_command("https://example.test/video.mp4", "video.mp4", true)
        else {
            panic!("expected command");
        };
        let item = &payload.unwrap()["items"][0];
        assert_eq!(item["title"], "video.mp4");
        assert_eq!(item["skipHistory"], true);
    }

    #[test]
    fn media_target_validation_accepts_http_urls() {
        assert!(validate_media_target("https://example.test/video.mpd").is_ok());
    }

    #[test]
    fn media_title_uses_the_file_name_without_url_credentials_or_query() {
        assert_eq!(
            media_title("/tmp/My Movie.mp4").as_deref(),
            Some("My Movie.mp4")
        );
        assert_eq!(
            media_title("https://user:secret@example.test/path/video.mp4?token=secret").as_deref(),
            Some("video.mp4")
        );
    }

    #[test]
    fn media_target_validation_explains_missing_local_files() {
        let error = validate_media_target("/definitely/missing/video.mpd").unwrap_err();
        assert!(error.contains("media file does not exist"));
        assert!(error.contains("/definitely/missing/video.mpd"));
    }

    #[test]
    fn display_media_target_redacts_url_credentials() {
        assert_eq!(
            display_media_target(
                "https://user:password@example.test/video.m3u8?token=secret#fragment"
            ),
            "https://example.test/video.m3u8?<redacted>"
        );
        assert_eq!(display_media_target("/tmp/video.mp4"), "/tmp/video.mp4");
    }

    fn preferred_device(protocol: &str) -> PreferredDevice {
        PreferredDevice {
            uuid: "tv-1".into(),
            name: "Living Room".into(),
            protocol: protocol.into(),
            address: "192.168.1.20".into(),
            port: Some(8765),
            wss_port: Some(8765),
            location: None,
        }
    }

    #[test]
    fn receiver_from_preferred_copies_identity_fields() {
        let receiver = receiver_from_preferred(&preferred_device("dlna")).unwrap();
        assert_eq!(receiver.id.0, "tv-1");
        assert_eq!(receiver.protocol, ReceiverProtocol::Dlna);
        assert_eq!(receiver.name, "Living Room");
        assert_eq!(receiver.addresses, vec!["192.168.1.20"]);
        assert_eq!(receiver.port, Some(8765));
        assert_eq!(receiver.wss_port, Some(8765));
        assert_eq!(receiver.uuid.as_deref(), Some("tv-1"));
    }

    #[test]
    fn receiver_from_preferred_rejects_unknown_protocols() {
        let error = receiver_from_preferred(&preferred_device("miracast")).unwrap_err();
        assert!(error.contains("unsupported receiver protocol"));
    }

    #[test]
    fn unreachable_detects_timeouts_and_ignores_auth_failures() {
        assert!(is_unreachable(
            "protocol operation failed: PlayBridge WebSocket connection timed out"
        ));
        assert!(is_unreachable("No route to host"));
        assert!(!is_unreachable("Authentication failed"));
        assert!(!is_unreachable("pairing_required"));
    }

    #[test]
    fn receiver_matches_device_by_id_name_uuid_or_address() {
        let receiver = receiver_from_preferred(&preferred_device("playbridge")).unwrap();
        assert!(receiver_matches_device(&receiver, "tv-1"));
        assert!(receiver_matches_device(&receiver, "Living Room"));
        assert!(receiver_matches_device(&receiver, "192.168.1.20"));
        assert!(!receiver_matches_device(&receiver, "Kitchen"));
    }

    #[test]
    fn receiver_selection_rejects_ambiguous_names_and_prefers_exact_ids() {
        let playbridge = receiver_from_preferred(&preferred_device("playbridge")).unwrap();
        let mut google_cast = playbridge.clone();
        google_cast.id = ReceiverId("google_cast:living-room".into());
        google_cast.uuid = Some("google-cast-1".into());
        google_cast.protocol = ReceiverProtocol::GoogleCast;

        let discovered = vec![google_cast, playbridge.clone()];
        let ambiguous = select_receiver_by_selector(&discovered, "Living Room").unwrap_err();
        assert_eq!(ambiguous.len(), 2);
        let selected = select_receiver_by_selector(&discovered, "tv-1")
            .unwrap()
            .unwrap();
        assert_eq!(selected.protocol, ReceiverProtocol::PlayBridge);
    }

    #[test]
    fn json_success_and_error_shapes_are_stable() {
        let success = json!({
            "ok": true,
            "device": "Living Room",
            "protocol": "dlna",
            "media": "https://example.test/video.m3u8",
        });
        assert_eq!(success["ok"], true);
        assert_eq!(success["protocol"], "dlna");

        let missing = json!({ "ok": false, "error": "no_preferred_device" });
        assert_eq!(missing["ok"], false);
        assert_eq!(missing["error"], "no_preferred_device");

        let pairing = json!({
            "ok": false,
            "error": "pairing_required",
            "device": "Living Room",
        });
        assert_eq!(pairing["error"], "pairing_required");
        assert_eq!(pairing["device"], "Living Room");
    }

    #[test]
    fn control_ack_preserves_receiver_id_and_names_request_id() {
        let receiver = receiver_from_preferred(&preferred_device("playbridge")).unwrap();
        let capabilities = CastCapabilities::default();
        let snapshot = CastSnapshot {
            state: "stopped".into(),
            title: "Video".into(),
            position_ms: 1000,
            duration_ms: 2000,
            volume: None,
            muted: None,
            looping: None,
            speed: None,
        };
        let request = ControlRequest {
            id: "request-1".into(),
            command: "stop".into(),
            seconds: None,
            delta: None,
            value: None,
        };
        let ack = control_ack(
            JsonStatusContext {
                session_id: "session-1",
                receiver: &receiver,
                media: "video.mp4",
                capabilities: &capabilities,
            },
            &request,
            true,
            None,
            &snapshot,
        );

        assert_eq!(ack["id"], "tv-1");
        assert_eq!(ack["request_id"], "request-1");
        assert_eq!(ack["state"], "stopped");
    }
}
