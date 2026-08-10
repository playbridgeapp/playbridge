use playbridge_browser_receiver::{
    BrowserReceiverConfig, BrowserReceiverEvent, BrowserReceiverHost, BrowserReceiverService,
    BrowserSessionSnapshot, local_urls,
};
use playbridge_cast_core::{
    browser::{BrowserCommand, BrowserMedia},
    castv2,
    playbridge::{PairingSession, ReceiverFrame, SenderFrame},
    secure_ws::SecureWebSocket,
    session::{MediaRequest, ReceiverSession},
    upnp::Renderer,
};
use serde_json::{Value, json};
use std::{collections::HashMap, env, path::PathBuf, time::Duration};
use stream_proxy_rust::{ProxyServer, ProxyServerConfig};

use crate::credentials::PlaybridgeCredentials;

#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct CastCapabilities {
    pub play_pause: bool,
    pub seek: bool,
    pub volume: bool,
    pub mute: bool,
    pub looping: bool,
    pub speed: bool,
    pub audio_boost: bool,
}

#[derive(Debug, Clone)]
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

/// Runs a cast selected by the dashboard. Unlike the legacy command path this
/// never reads the terminal: the dashboard remains responsible for input and
/// sends the stop signal when the user ends the session.
pub(crate) async fn run_dashboard_cast(
    media_target: String,
    target: playbridge_cast_core::discovery::Receiver,
    generation: u64,
    mut commands: tokio::sync::mpsc::Receiver<CastCommand>,
    events: tokio::sync::mpsc::Sender<CastEvent>,
) -> Result<(), String> {
    validate_media_target(&media_target)?;
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
                    &target.name,
                    &uuid,
                    &media_url,
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
            &target.name,
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

#[cfg(test)]
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
    device_name: &str,
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
                Some(device_name),
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
            media.title = Some(device_name.to_owned());
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
    device_name: &str,
    device_uuid: &str,
    media_url: &str,
) -> Result<SecureWebSocket, String> {
    let credentials = PlaybridgeCredentials::load(device_uuid)
        .ok_or_else(|| format!("{device_name} has no stored pairing credentials"))?;
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

    send_playlist(&mut socket, media_url, device_name).await?;
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
    device_name: &str,
    device_uuid: &str,
    media_url: &str,
    pairing_ui: DashboardPairing<'_>,
) -> Result<SecureWebSocket, String> {
    let DashboardPairing {
        generation,
        commands,
        events,
    } = pairing_ui;
    if PlaybridgeCredentials::load(device_uuid).is_some() {
        return cast_to_playbridge(address, wss_port, device_name, device_uuid, media_url).await;
    }

    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);
    let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
        .await
        .map_err(|error| error.to_string())?;
    let served_pin = socket.served_spki_pin().to_owned();
    let (mut pairing, commit) =
        PairingSession::start(device_name.to_owned(), device_uuid.to_owned())
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
                        device_name: device_name.to_owned(),
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
                credentials.save(device_uuid)?;
                events
                    .send(CastEvent::PairingCompleted {
                        generation,
                        device_name: device_name.to_owned(),
                    })
                    .await
                    .map_err(|_| "dashboard closed after pairing".to_owned())?;
                send_playlist(&mut socket, media_url, device_name).await?;
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
    device_name: &str,
) -> Result<(), String> {
    let cmd = SenderFrame::Command {
        action: "playlist".into(),
        payload: Some(serde_json::json!({
            "items": [{
                "url": media_url,
                "title": device_name,
            }]
        })),
    };
    socket.send(&cmd).await.map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_target_validation_accepts_http_urls() {
        assert!(validate_media_target("https://example.test/video.mpd").is_ok());
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
}
