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
    env, fs,
    io::{self, IsTerminal, Write},
    path::PathBuf,
    time::Duration,
};
use stream_proxy_rust::{ProxyServer, ProxyServerConfig};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};

use crate::{
    credentials::{PlaybridgeCredentials, SenderIdentity, now_seconds},
    json_session::{self, ControlRequest, JsonCastSession, SessionInfo},
    preferred::PreferredDevice,
};

type CommandConfirmation = (
    bool,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<u64>,
);

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
    pub current_index: Option<usize>,
    pub total_count: Option<usize>,
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
    playlist_payload: Option<&'a Value>,
    pair_only: bool,
}

#[derive(Clone, Copy)]
struct JsonStatusContext<'a> {
    session_id: &'a str,
    receiver: &'a Receiver,
    media_items: &'a [String],
    capabilities: &'a CastCapabilities,
}

struct JsonSessionInput {
    media_items: Vec<String>,
    initial_index: usize,
    stdin_commands: bool,
}

pub(crate) enum MediaPayloadSource {
    File(String),
    Stdin,
}

async fn read_playlist_payload(
    source: Option<&MediaPayloadSource>,
    fallback_url: &str,
    fallback_title: &str,
) -> Result<Value, String> {
    let Some(source) = source else {
        return Ok(json!({
            "items": [{ "url": fallback_url, "title": fallback_title }],
            "startIndex": 0,
        }));
    };
    const MAX_PAYLOAD_BYTES: u64 = 256 * 1024;
    let data = match source {
        MediaPayloadSource::File(path) => {
            let metadata =
                fs::metadata(path).map_err(|_| "could not read media payload".to_owned())?;
            if metadata.len() > MAX_PAYLOAD_BYTES {
                return Err("media payload exceeds 256 KiB".into());
            }
            fs::read_to_string(path).map_err(|_| "could not read media payload".to_owned())?
        }
        MediaPayloadSource::Stdin => {
            let mut data = String::new();
            BufReader::new(tokio::io::stdin())
                .take(MAX_PAYLOAD_BYTES + 1)
                .read_line(&mut data)
                .await
                .map_err(|_| "could not read media payload from stdin".to_owned())?;
            if data.len() as u64 > MAX_PAYLOAD_BYTES {
                return Err("media payload exceeds 256 KiB".into());
            }
            data
        }
    };
    let payload: Value =
        serde_json::from_str(&data).map_err(|_| "invalid media payload".to_owned())?;
    if payload.is_null() {
        return Ok(json!({
            "items": [{ "url": fallback_url, "title": fallback_title }],
            "startIndex": 0,
        }));
    }
    let items = payload
        .get("items")
        .and_then(Value::as_array)
        .ok_or("media payload requires items")?;
    if items.is_empty() || items.len() > 256 {
        return Err("media payload requires between 1 and 256 items".into());
    }
    for item in items {
        let url = item.get("url").and_then(Value::as_str).unwrap_or_default();
        validate_media_target(url)?;
    }
    Ok(payload)
}

fn apply_history_default(payload: &mut Value, skip_history: bool) {
    if let Some(items) = payload.get_mut("items").and_then(Value::as_array_mut) {
        for item in items {
            if item.get("skipHistory").is_none_or(Value::is_null)
                && let Some(object) = item.as_object_mut()
            {
                object.insert("skipHistory".into(), json!(skip_history));
            }
        }
    }
}

fn selected_playlist_item(payload: &Value) -> Result<&Value, String> {
    let items = payload
        .get("items")
        .and_then(Value::as_array)
        .ok_or("media payload requires items")?;
    let start_index = playlist_start_index(payload)?;
    items
        .get(start_index)
        .ok_or_else(|| "media payload startIndex must select an item".to_owned())
}

fn playlist_start_index(payload: &Value) -> Result<usize, String> {
    let index = payload
        .get("startIndex")
        .map(|value| {
            value
                .as_u64()
                .and_then(|index| usize::try_from(index).ok())
                .ok_or_else(|| "media payload startIndex must be a non-negative integer".to_owned())
        })
        .transpose()?
        .unwrap_or(0);
    Ok(index)
}

async fn prepare_json_playlist(
    mut payload: Value,
    receiver: &Receiver,
) -> Result<(Value, Option<ProxyServer>), String> {
    let items = payload
        .get_mut("items")
        .and_then(Value::as_array_mut)
        .ok_or("media payload requires items")?;
    if receiver.protocol != ReceiverProtocol::PlayBridge && items.len() != 1 {
        return Err("multi-item playlists require a PlayBridge receiver".into());
    }
    let needs_proxy = items.iter().any(|item| {
        let url = item.get("url").and_then(Value::as_str).unwrap_or_default();
        resolve_media_path(url).is_file()
            || (receiver.protocol != ReceiverProtocol::PlayBridge
                && item
                    .get("headers")
                    .and_then(Value::as_object)
                    .is_some_and(|headers| !headers.is_empty()))
    });
    let server = if needs_proxy {
        Some(ProxyServer::start(ProxyServerConfig::default()).await?)
    } else {
        None
    };
    let host = match server.as_ref() {
        Some(server) => Some(primary_lan_host(server.local_addr().port())?),
        None => None,
    };
    for item in items {
        let url = item["url"]
            .as_str()
            .ok_or("media item is missing url")?
            .to_owned();
        let path = resolve_media_path(&url);
        let proxied = if path.is_file() {
            let content_type = item
                .get("contentType")
                .and_then(Value::as_str)
                .map(str::to_owned);
            Some(
                server
                    .as_ref()
                    .expect("local media requires proxy")
                    .register_file(
                        host.as_deref().expect("proxy host exists"),
                        path,
                        content_type,
                        Duration::from_secs(6 * 60 * 60),
                    )?
                    .url,
            )
        } else if receiver.protocol != ReceiverProtocol::PlayBridge {
            let headers = item
                .get("headers")
                .and_then(Value::as_object)
                .map(|values| {
                    values
                        .iter()
                        .filter_map(|(key, value)| {
                            value.as_str().map(|value| (key.clone(), value.to_owned()))
                        })
                        .collect::<HashMap<_, _>>()
                })
                .unwrap_or_default();
            if headers.is_empty() {
                None
            } else {
                let content_type = item.get("contentType").and_then(Value::as_str);
                let origins = item
                    .get("allowedPrivateOrigins")
                    .and_then(Value::as_array)
                    .map(|values| {
                        values
                            .iter()
                            .filter_map(Value::as_str)
                            .map(str::to_owned)
                            .collect()
                    })
                    .unwrap_or_default();
                Some(
                    server
                        .as_ref()
                        .expect("headered media requires proxy")
                        .register_remote_with_policy(
                            host.as_deref().expect("proxy host exists"),
                            url,
                            headers,
                            content_type,
                            origins,
                        )?
                        .url,
                )
            }
        } else {
            None
        };
        if let Some(url) = proxied {
            item["url"] = json!(url);
            if let Some(object) = item.as_object_mut() {
                object.remove("headers");
            }
        }
    }
    Ok((payload, server))
}

async fn prepare_queue_item(
    payload: &mut Value,
    proxy_server: &mut Option<ProxyServer>,
) -> Result<(), String> {
    if let Some(item) = payload.get_mut("item") {
        return prepare_queue_item_value(item, proxy_server).await;
    }
    let items = payload
        .get_mut("items")
        .and_then(Value::as_array_mut)
        .ok_or("queue_add requires item or items")?;
    if items.is_empty() {
        return Err("queue_add requires at least one item".into());
    }
    for item in items {
        prepare_queue_item_value(item, proxy_server).await?;
    }
    Ok(())
}

async fn prepare_queue_item_value(
    value: &mut Value,
    proxy_server: &mut Option<ProxyServer>,
) -> Result<(), String> {
    let item = value
        .as_object_mut()
        .ok_or("queue item must be an object")?;
    let url = item
        .get("url")
        .and_then(Value::as_str)
        .ok_or("queue item is missing url")?
        .to_owned();
    let path = resolve_media_path(&url);
    if !path.is_file() {
        return Ok(());
    }
    if proxy_server.is_none() {
        *proxy_server = Some(ProxyServer::start(ProxyServerConfig::default()).await?);
    }
    let server = proxy_server.as_ref().expect("queue proxy exists");
    let host = primary_lan_host(server.local_addr().port())?;
    let content_type = item
        .get("contentType")
        .and_then(Value::as_str)
        .map(str::to_owned);
    let media =
        server.register_file(&host, path, content_type, Duration::from_secs(6 * 60 * 60))?;
    item.insert("url".into(), json!(media.url));
    item.remove("headers");
    Ok(())
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
    media_payload: Option<MediaPayloadSource>,
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
    let stdin_commands = matches!(media_payload, Some(MediaPayloadSource::Stdin));
    let mut playlist_payload = read_playlist_payload(
        media_payload.as_ref(),
        &media_target,
        &media_title(&media_target).unwrap_or_else(|| "Untitled media".into()),
    )
    .await?;
    apply_history_default(&mut playlist_payload, skip_history);
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
    let selected_item = selected_playlist_item(&playlist_payload)?;
    let selected_url = selected_item
        .get("url")
        .and_then(Value::as_str)
        .ok_or("media item is missing url")?;
    let title = selected_item
        .get("title")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_else(|| media_title(selected_url).unwrap_or_else(|| "Untitled media".into()));

    let raw_playlist_payload = playlist_payload.clone();
    let (mut playlist_payload, mut proxy_server) =
        prepare_json_playlist(playlist_payload, &receiver).await?;
    let mut media_url = selected_playlist_item(&playlist_payload)?["url"]
        .as_str()
        .ok_or("media item is missing url")?
        .to_owned();

    let pair_code_path = pair_code_file.as_deref().map(PathBuf::from);
    let pairing = MachinePairing {
        code: pair_code.as_deref(),
        file: pair_code_path.as_deref(),
        session_id: &session_id,
    };
    let (receiver, control) =
        match connect_and_load(&media_url, &title, &playlist_payload, receiver, pairing).await {
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
                        let (fallback_payload, fallback_proxy) =
                            prepare_json_playlist(raw_playlist_payload.clone(), &fallback).await?;
                        let fallback_url = selected_playlist_item(&fallback_payload)?["url"]
                            .as_str()
                            .ok_or("media item is missing url")?
                            .to_owned();
                        match connect_and_load(
                            &fallback_url,
                            &title,
                            &fallback_payload,
                            fallback,
                            pairing,
                        )
                        .await
                        {
                            Ok(connected) => {
                                playlist_payload = fallback_payload;
                                media_url = fallback_url;
                                proxy_server = fallback_proxy;
                                connected
                            }
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
    let media_items = playlist_payload
        .get("items")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|item| item.get("url").and_then(Value::as_str))
        .map(display_media_target)
        .collect::<Vec<_>>();
    let initial_index = playlist_start_index(&playlist_payload)?;
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

    let result = json_session_loop(
        control,
        receiver,
        capabilities,
        JsonSessionInput {
            media_items,
            initial_index,
            stdin_commands,
        },
        &session,
        &mut proxy_server,
    )
    .await;
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

pub(crate) async fn run_json_pair(
    device: Option<String>,
    pair_code: Option<String>,
    pair_code_file: Option<String>,
    requested_session_id: Option<String>,
) -> Result<(), String> {
    let session_id = requested_session_id.unwrap_or_else(JsonCastSession::generate_id);
    let receiver = resolve_json_playbridge_receiver(device.as_deref()).await?;
    let address = receiver
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .cloned()
        .or_else(|| receiver.addresses.first().cloned())
        .ok_or_else(|| format!("{} has no reachable address", receiver.name))?;
    let uuid = receiver_uuid(&receiver);
    let was_paired = PlaybridgeCredentials::load(&uuid).is_some();
    let empty_payload = json!({ "items": [] });
    let pair_path = pair_code_file.as_deref().map(PathBuf::from);
    let (socket, _) = cast_to_playbridge_maybe_pair(
        &address,
        receiver.wss_port.or(receiver.port).unwrap_or(8765),
        PlaybridgeLoad {
            device_name: &receiver.name,
            device_uuid: &uuid,
            media_url: "",
            media_title: "",
            skip_history: false,
            playlist_payload: Some(&empty_payload),
            pair_only: true,
        },
        MachinePairing {
            code: pair_code.as_deref(),
            file: pair_path.as_deref(),
            session_id: &session_id,
        },
    )
    .await
    .inspect_err(|message| {
        let _ = emit_connect_error(message, &session_id);
    })?;
    let _ = socket.close().await;
    emit_json(&json!({
        "ok": true,
        "result": if was_paired { "already_paired" } else { "paired" },
        "device": receiver.name,
        "id": receiver.id.0,
        "uuid": uuid,
    }))
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
    input: JsonSessionInput,
    session: &JsonCastSession,
    proxy_server: &mut Option<ProxyServer>,
) -> Result<(), String> {
    let JsonSessionInput {
        mut media_items,
        initial_index,
        mut stdin_commands,
    } = input;
    let mut snapshot = CastSnapshot {
        state: "buffering".into(),
        title: receiver.name.clone(),
        position_ms: 0,
        duration_ms: 0,
        current_index: Some(initial_index),
        total_count: Some(media_items.len()),
        volume: capabilities.volume.then_some(0.5),
        muted: capabilities.mute.then_some(false),
        looping: capabilities.looping.then_some(false),
        speed: capabilities.speed.then_some(1.0),
    };
    session.write_status(&status_json(
        json_status_context(session, &receiver, &media_items, &capabilities),
        &snapshot,
    ))?;
    let mut last_control_id = String::new();
    let mut tick = tokio::time::interval(Duration::from_millis(250));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut poll_count = 0_u64;
    let mut consecutive_poll_failures = 0_u8;
    let mut stop_playback_on_exit = true;
    loop {
        tokio::select! {
            _ = wait_interrupt() => break,
            request = read_stdin_control_request(), if stdin_commands => {
                match request? {
                    Some(request) => {
                        match process_json_request(
                            &mut control,
                            &mut snapshot,
                            &request,
                            proxy_server,
                            &mut media_items,
                            session,
                            &receiver,
                            &capabilities,
                            true,
                        ).await {
                            JsonRequestOutcome::Continue => {}
                            JsonRequestOutcome::Stop => break,
                            JsonRequestOutcome::Detach => {
                                stop_playback_on_exit = false;
                                break;
                            }
                        }
                    }
                    None => stdin_commands = false,
                }
            }
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
                    match process_json_request(
                        &mut control,
                        &mut snapshot,
                        &request,
                        proxy_server,
                        &mut media_items,
                        session,
                        &receiver,
                        &capabilities,
                        false,
                    ).await {
                        JsonRequestOutcome::Continue => {}
                        JsonRequestOutcome::Stop => {
                            tokio::time::sleep(Duration::from_millis(400)).await;
                            break;
                        }
                        JsonRequestOutcome::Detach => {
                            stop_playback_on_exit = false;
                            break;
                        }
                    }
                }
                let _ = session.write_status(&status_json(
                    json_status_context(session, &receiver, &media_items, &capabilities),
                    &snapshot,
                ));
            }
        }
    }
    if stop_playback_on_exit {
        stop_target(control).await;
    }
    Ok(())
}

async fn read_stdin_control_request() -> Result<Option<ControlRequest>, String> {
    const MAX_COMMAND_BYTES: u64 = 256 * 1024;
    let mut line = String::new();
    let read = BufReader::new(tokio::io::stdin())
        .take(MAX_COMMAND_BYTES + 1)
        .read_line(&mut line)
        .await
        .map_err(|error| format!("could not read MCP control command: {error}"))?;
    if read == 0 {
        return Ok(None);
    }
    if line.len() as u64 > MAX_COMMAND_BYTES {
        return Err("MCP control command exceeds 256 KiB".into());
    }
    serde_json::from_str(&line)
        .map(Some)
        .map_err(|error| format!("invalid MCP control command: {error}"))
}

#[allow(clippy::too_many_arguments)]
async fn process_json_request(
    control: &mut TargetControl,
    snapshot: &mut CastSnapshot,
    request: &ControlRequest,
    proxy_server: &mut Option<ProxyServer>,
    media_items: &mut Vec<String>,
    session: &JsonCastSession,
    receiver: &Receiver,
    capabilities: &CastCapabilities,
    emit_response: bool,
) -> JsonRequestOutcome {
    let result = apply_json_control(control, snapshot, request, proxy_server, media_items).await;
    if matches!(result, Ok(JsonControlResult::Stop)) {
        snapshot.state = "stopped".into();
    }
    let (ok, error) = match &result {
        Ok(_) => (true, None),
        Err(message) => (false, Some(message.as_str())),
    };
    let ack = control_ack(
        json_status_context(session, receiver, media_items, capabilities),
        request,
        ok,
        error,
        snapshot,
    );
    let _ = session.write_ack(&ack);
    if emit_response {
        let _ = emit_json(&ack);
    }
    match result {
        Ok(JsonControlResult::Stop) => JsonRequestOutcome::Stop,
        Ok(JsonControlResult::Detach) => JsonRequestOutcome::Detach,
        Ok(JsonControlResult::Applied) | Err(_) => JsonRequestOutcome::Continue,
    }
}

enum JsonRequestOutcome {
    Continue,
    Stop,
    Detach,
}

fn json_status_context<'a>(
    session: &'a JsonCastSession,
    receiver: &'a Receiver,
    media_items: &'a [String],
    capabilities: &'a CastCapabilities,
) -> JsonStatusContext<'a> {
    JsonStatusContext {
        session_id: session.id(),
        receiver,
        media_items,
        capabilities,
    }
}

fn status_json(context: JsonStatusContext<'_>, snapshot: &CastSnapshot) -> Value {
    let media = match snapshot.current_index {
        Some(index) => context.media_items.get(index),
        None => context.media_items.first(),
    };
    json!({
        "ok": true,
        "session_id": context.session_id,
        "updated_ms": json_session::now_ms(),
        "device": context.receiver.name,
        "protocol": context.receiver.protocol.as_str(),
        "id": context.receiver.id.0,
        "media": media,
        "state": snapshot.state,
        "title": snapshot.title,
        "position_ms": snapshot.position_ms,
        "duration_ms": snapshot.duration_ms,
        "current_index": snapshot.current_index,
        "total_count": snapshot.total_count,
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
    Detach,
}

#[derive(Debug, PartialEq)]
enum ManagedCommandResult {
    Pending,
    Accepted {
        playback_id: Option<String>,
        queue_revision: Option<u64>,
    },
    Rejected(String),
}

fn managed_command_requires_confirmation(action: &str) -> bool {
    matches!(
        action,
        "queue_add"
            | "playlist_jump"
            | "queue_query"
            | "queue_remove"
            | "queue_move"
            | "queue_clear"
    )
}

fn managed_command_invalidates_media_mapping(action: &str) -> bool {
    matches!(
        action,
        "queue_add" | "queue_remove" | "queue_move" | "queue_clear"
    )
}

fn managed_command_result(frame: &ReceiverFrame, request_id: &str) -> ManagedCommandResult {
    let ReceiverFrame::CommandResult {
        request_id: result_request_id,
        ok,
        error,
        message,
        playback_id,
        queue_revision,
    } = frame
    else {
        return ManagedCommandResult::Pending;
    };
    if result_request_id != request_id {
        return ManagedCommandResult::Pending;
    }
    if *ok {
        ManagedCommandResult::Accepted {
            playback_id: playback_id.clone(),
            queue_revision: *queue_revision,
        }
    } else {
        ManagedCommandResult::Rejected(
            message
                .clone()
                .or_else(|| error.clone())
                .unwrap_or_else(|| "receiver rejected command".into()),
        )
    }
}

fn update_snapshot_from_receiver_frame(snapshot: &mut CastSnapshot, frame: &ReceiverFrame) {
    match frame {
        ReceiverFrame::Status {
            state,
            position,
            duration,
            title,
            ..
        } => {
            snapshot.state = state.clone();
            snapshot.position_ms = *position;
            snapshot.duration_ms = *duration;
            if let Some(title) = title {
                snapshot.title = title.clone();
            }
        }
        ReceiverFrame::PlaylistStatus {
            items,
            current_index,
            total_count,
            ..
        } => {
            snapshot.current_index = Some(*current_index);
            snapshot.total_count = Some(*total_count);
            if let Some(item) = items.iter().find(|item| item.index == *current_index) {
                snapshot.title = item.title.clone();
            }
        }
        _ => {}
    }
}

async fn send_confirmed_managed_command(
    socket: &mut SecureWebSocket,
    snapshot: &mut CastSnapshot,
    request_id: &str,
    action: &str,
    payload: Option<Value>,
) -> Result<(), String> {
    socket
        .send_command(action, payload, Some(request_id))
        .await
        .map_err(|error| error.to_string())?;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(8);
    let mut accepted = None;
    let mut playlist_snapshot = None;
    loop {
        let now = tokio::time::Instant::now();
        if now >= deadline {
            return Err("receiver does not support confirmed receiver commands".into());
        }
        let frame = match tokio::time::timeout(deadline - now, socket.receive()).await {
            Ok(Ok(Some(frame))) => frame,
            Ok(Ok(None)) => return Err("receiver closed connection during command".into()),
            Ok(Err(error)) => return Err(error.to_string()),
            Err(_) => return Err("receiver does not support confirmed receiver commands".into()),
        };
        if let ReceiverFrame::PlaylistStatus {
            playback_id,
            queue_revision,
            ..
        } = &frame
        {
            playlist_snapshot = Some((playback_id.clone(), *queue_revision));
        }
        update_snapshot_from_receiver_frame(snapshot, &frame);
        match managed_command_result(&frame, request_id) {
            ManagedCommandResult::Pending => {}
            ManagedCommandResult::Accepted {
                playback_id,
                queue_revision,
            } => accepted = Some((playback_id, queue_revision)),
            ManagedCommandResult::Rejected(error) => return Err(error),
        }
        if let (Some((result_playback_id, result_revision)), Some((snapshot_id, snapshot_revision))) =
            (&accepted, &playlist_snapshot)
            && confirmation_matches_snapshot(
                true,
                result_playback_id.as_deref(),
                *result_revision,
                snapshot_id.as_deref(),
                *snapshot_revision,
            )
        {
            return Ok(());
        }
    }
}

async fn apply_json_control(
    control: &mut TargetControl,
    snapshot: &mut CastSnapshot,
    request: &ControlRequest,
    proxy_server: &mut Option<ProxyServer>,
    media_items: &mut Vec<String>,
) -> Result<JsonControlResult, String> {
    match request.command.as_str() {
        "stop" => Ok(JsonControlResult::Stop),
        "detach" => Ok(JsonControlResult::Detach),
        "receiver_command" => {
            let action = request
                .action
                .as_deref()
                .ok_or("receiver command requires action")?;
            let mut payload = request.payload.clone();
            let queued_media = if action == "queue_add" {
                payload
                    .as_ref()
                    .map(queue_media_targets)
                    .unwrap_or_default()
            } else {
                Vec::new()
            };
            if action == "queue_add"
                && let Some(payload) = payload.as_mut()
            {
                prepare_queue_item(payload, proxy_server).await?;
            }
            let TargetControl::Playbridge { socket, features } = control else {
                return Err("receiver commands require a PlayBridge session".into());
            };
            if managed_command_requires_confirmation(action) {
                if !supports_confirmed_queue_crud(features) {
                    return Err("receiver does not support confirmed queue CRUD v1".into());
                }
                send_confirmed_managed_command(socket, snapshot, &request.id, action, payload)
                    .await?;
            } else {
                socket
                    .send(&SenderFrame::Command {
                        action: action.to_owned(),
                        payload,
                    })
                    .await
                    .map_err(|error| error.to_string())?;
            }
            if managed_command_invalidates_media_mapping(action) {
                // Receiver-owned CRUD can deduplicate, remove, and reorder entries. The
                // compact authoritative snapshot intentionally carries no URLs, so the
                // original sender URL vector can no longer be indexed safely.
                media_items.clear();
            } else if action == "queue_add" {
                media_items.extend(queued_media);
                snapshot.total_count = Some(media_items.len());
            }
            Ok(JsonControlResult::Applied)
        }
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
        "loop" => {
            dashboard_control(control, CastCommand::ToggleLoop, snapshot).await?;
            Ok(JsonControlResult::Applied)
        }
        "audio_boost" => {
            dashboard_control(control, CastCommand::ToggleAudioBoost, snapshot).await?;
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

fn queue_media_targets(payload: &Value) -> Vec<String> {
    let values: Vec<&Value> = payload
        .get("items")
        .and_then(Value::as_array)
        .map(|items| items.iter().collect())
        .or_else(|| payload.get("item").map(|item| vec![item]))
        .unwrap_or_default();
    values
        .into_iter()
        .filter_map(|item| item.get("url").and_then(Value::as_str))
        .map(display_media_target)
        .collect()
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
        TargetControl::Playbridge { socket, .. } => {
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

async fn resolve_json_playbridge_receiver(device: Option<&str>) -> Result<Receiver, String> {
    if device.is_none()
        && let Some(preferred) = PreferredDevice::load()
        && preferred.protocol.eq_ignore_ascii_case("playbridge")
    {
        return receiver_from_preferred(&preferred);
    }
    let discovered = discover_receivers()
        .await
        .into_iter()
        .filter(|receiver| receiver.protocol == ReceiverProtocol::PlayBridge)
        .collect::<Vec<_>>();
    if let Some(selector) = device {
        match select_receiver_by_selector(&discovered, selector) {
            Ok(Some(receiver)) => return Ok(receiver),
            Err(matches) => {
                emit_json(&json!({
                    "ok": false,
                    "error": "ambiguous_device",
                    "device": selector,
                    "receivers": matches.iter().map(json_receiver).collect::<Vec<_>>(),
                }))?;
                return Err("ambiguous_device".into());
            }
            Ok(None) => {}
        }
    } else if let [receiver] = discovered.as_slice() {
        return Ok(receiver.clone());
    }
    let error = if discovered.is_empty() {
        "device_not_found"
    } else {
        "device_required"
    };
    emit_json(&json!({
        "ok": false,
        "error": error,
        "receivers": discovered.iter().map(json_receiver).collect::<Vec<_>>(),
    }))?;
    Err(error.into())
}

async fn resolve_playbridge_receiver_quiet(device: &str) -> Result<Receiver, String> {
    let discovered = discover_receivers()
        .await
        .into_iter()
        .filter(|receiver| receiver.protocol == ReceiverProtocol::PlayBridge)
        .collect::<Vec<_>>();
    match select_receiver_by_selector(&discovered, device) {
        Ok(Some(receiver)) => Ok(receiver),
        Err(_) => Err("ambiguous_device".into()),
        Ok(None) => Err("device_not_found".into()),
    }
}

/// Apply a queue mutation directly to receiver-owned PlayBridge playback.
/// The connection is deliberately short lived: closing it must not stop the
/// receiver's current media or establish a CLI-owned cast session.
pub(crate) async fn run_device_receiver_command(
    device: &str,
    action: &str,
    payload: Value,
) -> Result<Value, String> {
    let requires_v1 = matches!(action, "queue_remove" | "queue_move" | "queue_clear")
        || (action == "queue_add"
            && (payload.get("items").is_some() || payload.get("ifPlaybackId").is_some()))
        || (action == "playlist_jump"
            && (payload.get("itemId").is_some() || payload.get("ifPlaybackId").is_some()));
    let receiver = resolve_playbridge_receiver_quiet(device).await?;
    let address = receiver
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .cloned()
        .or_else(|| receiver.addresses.first().cloned())
        .ok_or_else(|| format!("{} has no reachable address", receiver.name))?;
    let uuid = receiver_uuid(&receiver);
    if PlaybridgeCredentials::load(&uuid).is_none() {
        return Err(format!(
            "{} has no stored pairing credentials; call pair first",
            receiver.name
        ));
    }
    let empty_payload = json!({ "items": [] });
    let (mut socket, features) = cast_to_playbridge_with_features(
        &address,
        receiver.wss_port.or(receiver.port).unwrap_or(8765),
        PlaybridgeLoad {
            device_name: &receiver.name,
            device_uuid: &uuid,
            media_url: "",
            media_title: "",
            skip_history: false,
            playlist_payload: Some(&empty_payload),
            pair_only: true,
        },
    )
    .await?;
    if requires_v1 && !supports_confirmed_queue_crud(&features) {
        let _ = socket.close().await;
        return Err("receiver does not support confirmed queue CRUD v1".into());
    }
    let request_id = crate::json_session::new_request_id();
    socket
        .send_command(action, Some(payload), Some(&request_id))
        .await
        .map_err(|error| error.to_string())?;
    socket
        .send(&SenderFrame::Command {
            action: "context_query".into(),
            payload: None,
        })
        .await
        .map_err(|error| error.to_string())?;

    let deadline = tokio::time::Instant::now() + Duration::from_secs(8);
    let use_legacy_snapshot_deadline = uses_legacy_snapshot_deadline(&features);
    let mut snapshot = None;
    let mut command_result: Option<CommandConfirmation> = None;
    let mut legacy_deadline: Option<tokio::time::Instant> = None;
    let observed = loop {
        let now = tokio::time::Instant::now();
        let receive_deadline = legacy_deadline.map_or(deadline, |legacy| legacy.min(deadline));
        if now >= receive_deadline {
            break command_result;
        }
        match tokio::time::timeout(receive_deadline - now, socket.receive()).await {
            Ok(Ok(Some(frame))) => match frame {
                ReceiverFrame::PlaylistStatus {
                    items,
                    current_index,
                    total_count,
                    playback_id,
                    queue_revision,
                    current_item_id,
                } => {
                    snapshot = Some((
                        items,
                        current_index,
                        total_count,
                        playback_id,
                        queue_revision,
                        current_item_id,
                    ));
                    if use_legacy_snapshot_deadline {
                        legacy_deadline
                            .get_or_insert(tokio::time::Instant::now() + Duration::from_secs(1));
                    }
                    if let Some((ok, error, message, result_playback_id, result_revision)) =
                        command_result.as_ref()
                        && confirmation_matches_snapshot(
                            *ok,
                            result_playback_id.as_deref(),
                            *result_revision,
                            snapshot.as_ref().and_then(|value| value.3.as_deref()),
                            queue_revision,
                        )
                    {
                        break Some((
                            *ok,
                            error.clone(),
                            message.clone(),
                            result_playback_id.clone(),
                            *result_revision,
                        ));
                    }
                }
                ReceiverFrame::CommandResult {
                    request_id: result_request_id,
                    ok,
                    error,
                    message,
                    playback_id,
                    queue_revision,
                } if result_request_id == request_id => {
                    if snapshot.as_ref().is_some_and(|value| {
                        confirmation_matches_snapshot(
                            ok,
                            playback_id.as_deref(),
                            queue_revision,
                            value.3.as_deref(),
                            value.4,
                        )
                    }) || !ok
                    {
                        break Some((ok, error, message, playback_id, queue_revision));
                    }
                    command_result = Some((ok, error, message, playback_id, queue_revision));
                }
                _ => {}
            },
            Ok(Ok(None)) => break command_result,
            Ok(Err(error)) => return Err(error.to_string()),
            Err(_) => break command_result,
        }
    };
    let _ = socket.close().await;

    let confirmed = observed.is_some();
    if !confirmed && requires_v1 {
        return Err("receiver does not support confirmed queue CRUD v1".into());
    }
    let (ok, error, message, playback_id, queue_revision) =
        observed.unwrap_or((true, None, None, None, None));
    if !ok {
        return Err(message
            .or(error)
            .unwrap_or_else(|| "receiver rejected command".into()));
    }
    snapshot = snapshot.filter(|value| {
        confirmation_matches_snapshot(
            true,
            playback_id.as_deref(),
            queue_revision,
            value.3.as_deref(),
            value.4,
        )
    });

    let mut result = json!({
        "ok": true,
        "action": action,
        "device": receiver.name,
        "id": receiver.id.0,
        "uuid": uuid,
        "confirmed": confirmed,
        "requestId": request_id,
        "playbackId": playback_id,
        "queueRevision": queue_revision,
    });
    if let Some((
        items,
        current_index,
        total_count,
        snapshot_playback_id,
        snapshot_revision,
        current_item_id,
    )) = snapshot
    {
        result["playlist"] = json!({
            "items": items,
            "currentIndex": current_index,
            "totalCount": total_count,
            "playbackId": snapshot_playback_id,
            "queueRevision": snapshot_revision,
            "currentItemId": current_item_id,
        });
    }
    Ok(result)
}

fn supports_confirmed_queue_crud(features: &[String]) -> bool {
    features.iter().any(|feature| feature == "queue_crud_v1")
        && features.iter().any(|feature| feature == "stable_item_ids")
        && supports_command_results(features)
}

fn supports_command_results(features: &[String]) -> bool {
    features.iter().any(|feature| feature == "command_results")
}

fn uses_legacy_snapshot_deadline(features: &[String]) -> bool {
    !supports_command_results(features)
}

fn confirmation_matches_snapshot(
    ok: bool,
    result_playback_id: Option<&str>,
    result_revision: Option<u64>,
    snapshot_playback_id: Option<&str>,
    snapshot_revision: u64,
) -> bool {
    !ok || (result_revision.is_none_or(|revision| snapshot_revision >= revision)
        && result_playback_id.is_none_or(|id| snapshot_playback_id == Some(id)))
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
    } else if message == "credentials_rejected" {
        emit_json(&json!({
            "ok": false,
            "error": "credentials_rejected",
            "message": "The receiver rejected the stored credential. Forget this receiver and pair again.",
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
    playlist_payload: &Value,
    receiver: Receiver,
    pairing: MachinePairing<'_>,
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
            let (socket, features) = cast_to_playbridge_maybe_pair(
                &address,
                port,
                PlaybridgeLoad {
                    device_name: &receiver.name,
                    device_uuid: &uuid,
                    media_url,
                    media_title,
                    skip_history: false,
                    playlist_payload: Some(playlist_payload),
                    pair_only: false,
                },
                pairing,
            )
            .await?;
            TargetControl::Playbridge {
                socket: Box::new(socket),
                features,
            }
        }
        _ => cast_to_target(
            protocol.as_str(),
            &address,
            receiver.port,
            receiver.location.as_deref(),
            media_url,
            media_title,
            playlist_payload
                .get("items")
                .and_then(Value::as_array)
                .and_then(|items| items.first()),
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
) -> Result<(SecureWebSocket, Vec<String>), String> {
    if PlaybridgeCredentials::load(load.device_uuid).is_some() {
        return cast_to_playbridge_with_features(address, wss_port, load).await;
    }

    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);
    let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
        .await
        .map_err(|error| error.to_string())?;
    let served_pin = socket.served_spki_pin().to_owned();
    let identity = SenderIdentity::load_or_create()?;
    let (mut pairing, commit) =
        PairingSession::start(identity.name, identity.uuid).map_err(|error| error.to_string())?;
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
                let features = bundle.features.clone();
                let credentials = PlaybridgeCredentials {
                    token: bundle.token,
                    cert_fingerprint: bundle
                        .cert_fingerprint
                        .unwrap_or_else(|| served_pin.clone()),
                    players: bundle.players,
                    browsers: bundle.browsers,
                    receiver_name: Some(load.device_name.to_owned()),
                    last_used_at: Some(now_seconds()),
                };
                credentials.save(load.device_uuid)?;
                if !load.pair_only {
                    send_load(&mut socket, load).await?;
                }
                return Ok((socket, features));
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
            let (socket, features) = cast_to_playbridge_dashboard(
                &address,
                port,
                PlaybridgeLoad {
                    device_name: &target.name,
                    device_uuid: &uuid,
                    media_url: &media_url,
                    media_title: &dashboard_title,
                    skip_history,
                    playlist_payload: None,
                    pair_only: false,
                },
                DashboardPairing {
                    generation,
                    commands: &mut commands,
                    events: &events,
                },
            )
            .await?;
            TargetControl::Playbridge {
                socket: Box::new(socket),
                features,
            }
        }
        _ => cast_to_target(
            &protocol,
            &address,
            target.port,
            target.location.as_deref(),
            &media_url,
            &dashboard_title,
            None,
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
        current_index: None,
        total_count: None,
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
        current_index: None,
        total_count: None,
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
    Playbridge {
        socket: Box<SecureWebSocket>,
        features: Vec<String>,
    },
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
        TargetControl::Playbridge { .. } => CastCapabilities {
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
        TargetControl::Playbridge { socket, .. } => {
            if heartbeat {
                socket
                    .send(&SenderFrame::Ping)
                    .await
                    .map_err(|error| error.to_string())?;
            }
            while let Ok(Ok(Some(frame))) =
                tokio::time::timeout(Duration::from_millis(10), socket.receive()).await
            {
                match frame {
                    ReceiverFrame::Status {
                        state,
                        position,
                        duration,
                        title,
                        ..
                    } => {
                        snapshot.state = state;
                        snapshot.position_ms = position;
                        snapshot.duration_ms = duration;
                        if let Some(title) = title {
                            snapshot.title = title;
                        }
                    }
                    ReceiverFrame::PlaylistStatus {
                        items,
                        current_index,
                        total_count,
                        ..
                    } => {
                        snapshot.current_index = Some(current_index);
                        snapshot.total_count = Some(total_count);
                        if let Some(item) = items.iter().find(|item| item.index == current_index) {
                            snapshot.title = item.title.clone();
                        }
                    }
                    _ => {}
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
                TargetControl::Playbridge { socket, .. } => {
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
                TargetControl::Playbridge { socket, .. } => socket
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
                TargetControl::Playbridge { socket, .. } => socket
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
            let TargetControl::Playbridge { socket, .. } = control else {
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
        TargetControl::Playbridge { mut socket, .. } => {
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
    media_item: Option<&Value>,
) -> Result<Option<TargetControl>, String> {
    let content_type = media_item
        .and_then(|item| item.get("contentType"))
        .and_then(Value::as_str);
    let start_seconds = media_item
        .and_then(|item| item.get("startPositionMs"))
        .and_then(Value::as_u64)
        .map_or(0.0, |milliseconds| milliseconds as f64 / 1000.0);
    let art_url = media_item
        .and_then(|item| item.get("visualMetadata"))
        .and_then(|metadata| {
            metadata
                .get("artworkUrl")
                .or_else(|| metadata.get("posterUrl"))
                .or_else(|| metadata.get("backdropUrl"))
        })
        .and_then(Value::as_str);
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
            let (inferred_content_type, stream_type) = castv2::media_format(media_url);
            let media_session_id = castv2::load_media(
                &mut details,
                media_url,
                content_type.or(Some(inferred_content_type)),
                stream_type,
                Some(media_title),
                art_url,
                start_seconds,
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
            if start_seconds > 0.0 {
                renderer
                    .seek(&format_time(start_seconds))
                    .await
                    .map_err(|error| error.to_string())?;
            }
            Ok(Some(TargetControl::Dlna(renderer)))
        }
        "roku" => {
            let mut session = ReceiverSession::connect_roku(address, port.unwrap_or(8060))
                .map_err(|error| error.to_string())?;
            let mut media = MediaRequest::new(media_url);
            media.title = Some(media_title.to_owned());
            media.content_type = content_type.map(str::to_owned);
            media.art_url = art_url.map(str::to_owned);
            media.start_seconds = start_seconds;
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

async fn cast_to_playbridge_with_features(
    address: &str,
    wss_port: u16,
    load: PlaybridgeLoad<'_>,
) -> Result<(SecureWebSocket, Vec<String>), String> {
    let mut credentials = PlaybridgeCredentials::load(load.device_uuid)
        .ok_or_else(|| format!("{} has no stored pairing credentials", load.device_name))?;
    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);
    let mut socket = SecureWebSocket::connect_pinned(&endpoint, &credentials.cert_fingerprint)
        .await
        .map_err(|error| error.to_string())?;
    socket
        .send(&SenderFrame::Auth {
            token: credentials.token.clone(),
        })
        .await
        .map_err(|error| error.to_string())?;

    let features = loop {
        match socket.receive().await.map_err(|error| error.to_string())? {
            Some(ReceiverFrame::AuthResponse {
                success: true,
                features,
                ..
            }) => break features,
            Some(ReceiverFrame::AuthResponse { success: false, .. }) => {
                return Err("credentials_rejected".into());
            }
            Some(_) => {}
            None => return Err("Receiver closed connection during auth".into()),
        }
    };

    credentials.receiver_name = Some(load.device_name.to_owned());
    credentials.last_used_at = Some(now_seconds());
    credentials.save(load.device_uuid)?;

    if !load.pair_only {
        send_load(&mut socket, load).await?;
    }
    Ok((socket, features))
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
) -> Result<(SecureWebSocket, Vec<String>), String> {
    let DashboardPairing {
        generation,
        commands,
        events,
    } = pairing_ui;
    if PlaybridgeCredentials::load(load.device_uuid).is_some() {
        return cast_to_playbridge_with_features(address, wss_port, load).await;
    }

    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);
    let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
        .await
        .map_err(|error| error.to_string())?;
    let served_pin = socket.served_spki_pin().to_owned();
    let identity = SenderIdentity::load_or_create()?;
    let (mut pairing, commit) =
        PairingSession::start(identity.name, identity.uuid).map_err(|error| error.to_string())?;
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
                let features = bundle.features.clone();
                let credentials = PlaybridgeCredentials {
                    token: bundle.token,
                    cert_fingerprint: bundle
                        .cert_fingerprint
                        .unwrap_or_else(|| served_pin.clone()),
                    players: bundle.players,
                    browsers: bundle.browsers,
                    receiver_name: Some(load.device_name.to_owned()),
                    last_used_at: Some(now_seconds()),
                };
                credentials.save(load.device_uuid)?;
                events
                    .send(CastEvent::PairingCompleted {
                        generation,
                        device_name: load.device_name.to_owned(),
                    })
                    .await
                    .map_err(|_| "dashboard closed after pairing".to_owned())?;
                if !load.pair_only {
                    send_load(&mut socket, load).await?;
                }
                return Ok((socket, features));
            }
            ReceiverFrame::PairingDenied => {
                return Err("Pairing was denied by the receiver".into());
            }
            _ => {}
        }
    }

    Err("Receiver closed connection before pairing completed".into())
}

async fn send_load(socket: &mut SecureWebSocket, load: PlaybridgeLoad<'_>) -> Result<(), String> {
    let command = if let Some(payload) = load.playlist_payload {
        SenderFrame::Command {
            action: "playlist".into(),
            payload: Some(payload.clone()),
        }
    } else {
        playlist_command(load.media_url, load.media_title, load.skip_history)
    };
    socket
        .send(&command)
        .await
        .map_err(|error| error.to_string())
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

    #[tokio::test]
    async fn regular_payload_file_is_read_without_being_removed() {
        let temp = tempfile::tempdir().unwrap();
        let preserved = temp.path().join("preserved.json");
        let payload = r#"{"items":[{"url":"https://example.test/video.mp4"}]}"#;
        fs::write(&preserved, payload).unwrap();

        read_playlist_payload(
            Some(&MediaPayloadSource::File(
                preserved.to_string_lossy().into_owned(),
            )),
            "unused",
            "unused",
        )
        .await
        .unwrap();

        assert!(preserved.exists());
    }

    #[tokio::test]
    async fn managed_queue_preparation_accepts_batch_payloads() {
        let mut payload = json!({
            "items": [
                {"url": "https://example.test/one.mp4"},
                {"url": "https://example.test/two.mp4"}
            ],
            "ifPlaybackId": "playback-1"
        });
        let mut proxy = None;

        prepare_queue_item(&mut payload, &mut proxy).await.unwrap();

        assert!(proxy.is_none());
        assert_eq!(queue_media_targets(&payload).len(), 2);
    }

    #[test]
    fn guarded_queue_commands_require_all_receiver_features() {
        assert!(!supports_confirmed_queue_crud(&["queue_crud_v1".into()]));
        assert!(!supports_confirmed_queue_crud(&["command_results".into()]));
        assert!(!supports_confirmed_queue_crud(&[
            "queue_crud_v1".into(),
            "command_results".into(),
        ]));
        assert!(supports_confirmed_queue_crud(&[
            "queue_crud_v1".into(),
            "stable_item_ids".into(),
            "command_results".into(),
        ]));
    }

    #[test]
    fn advertised_command_results_disable_the_legacy_snapshot_deadline() {
        assert!(!uses_legacy_snapshot_deadline(&["command_results".into()]));
        assert!(uses_legacy_snapshot_deadline(&["queue_crud_v1".into()]));
    }

    #[test]
    fn managed_receiver_commands_only_accept_the_matching_result() {
        let accepted = ReceiverFrame::CommandResult {
            request_id: "current".into(),
            ok: true,
            error: None,
            message: None,
            playback_id: Some("playback-1".into()),
            queue_revision: Some(4),
        };
        assert_eq!(
            managed_command_result(&accepted, "other"),
            ManagedCommandResult::Pending
        );
        assert_eq!(
            managed_command_result(&accepted, "current"),
            ManagedCommandResult::Accepted {
                playback_id: Some("playback-1".into()),
                queue_revision: Some(4),
            }
        );

        let rejected = ReceiverFrame::CommandResult {
            request_id: "current".into(),
            ok: false,
            error: Some("queue_full".into()),
            message: None,
            playback_id: Some("playback-1".into()),
            queue_revision: Some(4),
        };
        assert_eq!(
            managed_command_result(&rejected, "current"),
            ManagedCommandResult::Rejected("queue_full".into())
        );
    }

    #[test]
    fn only_queue_v1_commands_require_receiver_confirmation() {
        for action in [
            "queue_add",
            "playlist_jump",
            "queue_query",
            "queue_remove",
            "queue_move",
            "queue_clear",
        ] {
            assert!(managed_command_requires_confirmation(action), "{action}");
        }

        for action in ["browser", "browser_control", "remote", "control"] {
            assert!(!managed_command_requires_confirmation(action), "{action}");
        }
    }

    #[test]
    fn read_only_and_navigation_commands_preserve_media_mapping() {
        for action in ["queue_query", "playlist_jump"] {
            assert!(
                !managed_command_invalidates_media_mapping(action),
                "{action}"
            );
        }
        for action in ["queue_add", "queue_remove", "queue_move", "queue_clear"] {
            assert!(
                managed_command_invalidates_media_mapping(action),
                "{action}"
            );
        }
    }

    #[test]
    fn managed_receiver_command_updates_status_while_waiting_for_confirmation() {
        let mut snapshot = CastSnapshot {
            state: "buffering".into(),
            title: "old".into(),
            position_ms: 0,
            duration_ms: 0,
            current_index: Some(0),
            total_count: Some(1),
            volume: None,
            muted: None,
            looping: None,
            speed: None,
        };
        update_snapshot_from_receiver_frame(
            &mut snapshot,
            &ReceiverFrame::Status {
                state: "playing".into(),
                position: 1_000,
                duration: 10_000,
                title: Some("new".into()),
                media_kind: Some("video".into()),
                playback_id: Some("playback-1".into()),
                current_item_id: Some("item-1".into()),
            },
        );
        assert_eq!(snapshot.state, "playing");
        assert_eq!(snapshot.title, "new");
        assert_eq!(snapshot.position_ms, 1_000);
        assert_eq!(snapshot.duration_ms, 10_000);
    }

    #[test]
    fn command_confirmation_accepts_newer_same_playback_snapshot() {
        assert!(confirmation_matches_snapshot(
            true,
            Some("playback-1"),
            Some(4),
            Some("playback-1"),
            5,
        ));
        assert!(!confirmation_matches_snapshot(
            true,
            Some("playback-1"),
            Some(4),
            Some("playback-2"),
            5,
        ));
    }

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
    fn selected_playlist_item_honors_start_index() {
        let payload = json!({
            "items": [
                { "url": "https://example.test/one.mp4", "title": "One" },
                { "url": "https://example.test/two.mp4", "title": "Two" }
            ],
            "startIndex": 1
        });
        let selected = selected_playlist_item(&payload).unwrap();
        assert_eq!(selected["url"], "https://example.test/two.mp4");
        assert_eq!(selected["title"], "Two");
    }

    #[test]
    fn selected_playlist_item_rejects_an_out_of_range_start_index() {
        let payload = json!({
            "items": [{ "url": "https://example.test/one.mp4" }],
            "startIndex": 1
        });
        assert_eq!(
            selected_playlist_item(&payload).unwrap_err(),
            "media payload startIndex must select an item"
        );
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
            current_index: Some(0),
            total_count: Some(1),
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
            action: None,
            payload: None,
        };
        let ack = control_ack(
            JsonStatusContext {
                session_id: "session-1",
                receiver: &receiver,
                media_items: &["video.mp4".into()],
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

    #[test]
    fn status_media_follows_the_current_playlist_index() {
        let receiver = receiver_from_preferred(&preferred_device("playbridge")).unwrap();
        let capabilities = CastCapabilities::default();
        let snapshot = CastSnapshot {
            state: "playing".into(),
            title: "Episode 2".into(),
            position_ms: 1000,
            duration_ms: 2000,
            current_index: Some(1),
            total_count: Some(2),
            volume: None,
            muted: None,
            looping: None,
            speed: None,
        };
        let media_items = vec!["one.mp4".into(), "two.mp4".into()];
        let status = status_json(
            JsonStatusContext {
                session_id: "session-1",
                receiver: &receiver,
                media_items: &media_items,
                capabilities: &capabilities,
            },
            &snapshot,
        );

        assert_eq!(status["media"], "two.mp4");
        assert_eq!(status["current_index"], 1);
        assert_eq!(status["total_count"], 2);
    }

    #[test]
    fn status_does_not_report_the_first_item_for_an_unknown_index() {
        let receiver = receiver_from_preferred(&preferred_device("playbridge")).unwrap();
        let capabilities = CastCapabilities::default();
        let snapshot = CastSnapshot {
            state: "playing".into(),
            title: "Receiver-added item".into(),
            position_ms: 0,
            duration_ms: 0,
            current_index: Some(2),
            total_count: Some(3),
            volume: None,
            muted: None,
            looping: None,
            speed: None,
        };
        let media_items = vec!["one.mp4".into()];
        let status = status_json(
            JsonStatusContext {
                session_id: "session-1",
                receiver: &receiver,
                media_items: &media_items,
                capabilities: &capabilities,
            },
            &snapshot,
        );

        assert!(status["media"].is_null());
        assert_eq!(status["current_index"], 2);
    }
}
