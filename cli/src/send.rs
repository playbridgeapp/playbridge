use crossterm::{
    cursor,
    event::{self, Event, KeyCode},
    execute,
    terminal::{
        Clear, ClearType, EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode,
        enable_raw_mode,
    },
};
use std::{
    collections::HashMap,
    env,
    io::{self, Write},
    path::PathBuf,
    time::Duration,
};
use tokio::time::sleep;

use playbridge_browser_receiver::{
    BrowserReceiverConfig, BrowserReceiverEvent, BrowserReceiverHost, BrowserReceiverService,
    local_urls,
};
use playbridge_cast_core::{
    browser::{BrowserCommand, BrowserMedia, BrowserPlaybackState},
    castv2,
    discovery::{DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver, ReceiverProtocol},
    playbridge::{PairingSession, ReceiverFrame, SenderFrame},
    secure_ws::SecureWebSocket,
    session::{MediaRequest, PlaybackState, ReceiverSession},
    upnp::Renderer,
};
use stream_proxy_rust::{ProxyServer, ProxyServerConfig};

use crate::credentials::PlaybridgeCredentials;
use crate::preferred::PreferredDevice;

struct RawModeGuard;

impl RawModeGuard {
    fn enable() -> Result<Self, String> {
        enable_raw_mode().map_err(|error| error.to_string())?;
        Ok(Self)
    }
}

impl Drop for RawModeGuard {
    fn drop(&mut self) {
        let _ = disable_raw_mode();
    }
}

struct PickerTerminalGuard {
    _raw_mode: RawModeGuard,
}

impl PickerTerminalGuard {
    fn enable() -> Result<Self, String> {
        let raw_mode = RawModeGuard::enable()?;
        execute!(
            io::stdout(),
            EnterAlternateScreen,
            cursor::MoveTo(0, 0),
            Clear(ClearType::All),
            cursor::Hide,
        )
        .map_err(|error| error.to_string())?;
        Ok(Self {
            _raw_mode: raw_mode,
        })
    }
}

impl Drop for PickerTerminalGuard {
    fn drop(&mut self) {
        let _ = execute!(io::stdout(), cursor::Show, LeaveAlternateScreen,);
    }
}

pub async fn run_browser_send(media_target: String) -> Result<(), String> {
    println!("Media Target: {}", display_media_target(&media_target));
    validate_media_target(&media_target)?;
    let (media_url, control, proxy) = cast_to_browser(&media_target, None, None).await?;
    wait_for_server_exit(&media_url, Some(control), Some(proxy)).await;
    Ok(())
}

async fn cast_to_browser(
    media_target: &str,
    existing_proxy: Option<ProxyServer>,
    existing_media_url: Option<String>,
) -> Result<(String, TargetControl, ProxyServer), String> {
    validate_media_target(media_target)?;
    let host = BrowserReceiverHost::start(BrowserReceiverConfig::default()).await?;
    println!("Open one of these addresses on the receiving device:");
    for url in host.urls() {
        println!("  {url}");
    }
    println!("Waiting for a browser receiver. Press Ctrl+C to cancel.");

    let proxy = match existing_proxy {
        Some(proxy) => proxy,
        None => ProxyServer::start(ProxyServerConfig::default()).await?,
    };
    let proxy_host = primary_lan_host(proxy.local_addr().port())?;
    let media_url = match existing_media_url {
        Some(url) => url,
        None => {
            let path = resolve_media_path(media_target);
            if path.is_file() {
                proxy
                    .register_file(&proxy_host, path, None, Duration::from_secs(6 * 60 * 60))?
                    .url
            } else {
                proxy
                    .register_remote(&proxy_host, media_target, HashMap::new())?
                    .url
            }
        }
    };
    let service = host.service();
    let mut events = service.subscribe();
    loop {
        let event = tokio::select! {
            event = events.recv() => event.map_err(|error| error.to_string())?,
            _ = tokio::signal::ctrl_c() => return Err("Browser receiver setup cancelled".into()),
        };
        if let BrowserReceiverEvent::PairingRequested { session, .. } = event {
            println!("\nPairing request from \"{}\".", session.name);
            print!("Enter the six-digit code shown in the browser: ");
            io::stdout().flush().map_err(|error| error.to_string())?;
            let code = tokio::task::spawn_blocking(|| {
                let mut input = String::new();
                io::stdin()
                    .read_line(&mut input)
                    .map(|_| input)
                    .map_err(|error| error.to_string())
            })
            .await
            .map_err(|error| error.to_string())??;
            match service.approve(&session.session_id, code.trim()).await {
                Ok(()) => {
                    let media = BrowserMedia {
                        url: media_url.clone(),
                        title: media_title(media_target),
                        content_type: media_content_type(media_target),
                        poster_url: None,
                        subtitle_url: None,
                        start_position_ms: None,
                    };
                    service.load(&session.session_id, media).await?;
                    println!("Casting to \"{}\" via Web Browser.", session.name);
                    return Ok((
                        media_url,
                        TargetControl::Browser {
                            host,
                            service,
                            session_id: session.session_id,
                            events,
                        },
                        proxy,
                    ));
                }
                Err(error) => eprintln!("Pairing failed: {error}"),
            }
        }
    }
}

fn resolve_media_path(media_target: &str) -> PathBuf {
    if let Some(relative) = media_target.strip_prefix("~/")
        && let Some(home) = std::env::var_os("HOME")
    {
        return PathBuf::from(home).join(relative);
    }
    PathBuf::from(media_target)
}

fn validate_media_target(media_target: &str) -> Result<(), String> {
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

#[allow(clippy::collapsible_if)]
pub async fn run_send(media_target: String) -> Result<(), String> {
    println!("Media Target: {}", display_media_target(&media_target));
    validate_media_target(&media_target)?;

    // Resolve local file vs remote URL
    let resolved_path = resolve_media_path(&media_target);

    let (media_url, mut proxy_server) = if resolved_path.exists() && resolved_path.is_file() {
        println!(
            "Starting Rust media proxy for {:?}...",
            resolved_path.file_name().unwrap_or_default()
        );
        let server = ProxyServer::start(ProxyServerConfig::default()).await?;
        let host = primary_lan_host(server.local_addr().port())?;
        let media =
            server.register_file(&host, resolved_path, None, Duration::from_secs(6 * 60 * 60))?;
        println!(
            "Local media proxy running at {}\n",
            display_media_target(&media.url)
        );
        (media.url, Some(server))
    } else {
        (media_target.clone(), None)
    };

    // 1. Check if preferred device is configured
    if let Some(pref) = PreferredDevice::load() {
        println!(
            "Preferred device found: \"{}\" ({}) - {}",
            pref.name, pref.protocol, pref.address
        );
        println!("Auto-sending in 3 seconds... Press any key to choose another device.");

        let mut cancelled = false;
        let raw_mode = RawModeGuard::enable().ok();
        let start_time = tokio::time::Instant::now();
        let timeout_duration = Duration::from_secs(3);

        while start_time.elapsed() < timeout_duration {
            let remaining =
                (timeout_duration.as_secs_f64() - start_time.elapsed().as_secs_f64()).ceil() as u32;
            print!("\rCountdown: {remaining}s... ");
            let _ = io::stdout().flush();
            if event::poll(Duration::from_millis(100)).unwrap_or(false) {
                if let Ok(Event::Key(_)) = event::read() {
                    cancelled = true;
                    break;
                }
            }
        }
        drop(raw_mode);
        println!();

        if !cancelled {
            let target_url = media_url.clone();
            let result = match pref.protocol.to_lowercase().as_str() {
                "playbridge" | "native" => {
                    let wss_port = pref.wss_port.or(pref.port).unwrap_or(8765);
                    match cast_to_playbridge(
                        &pref.address,
                        wss_port,
                        &pref.name,
                        &pref.uuid,
                        &target_url,
                    )
                    .await
                    {
                        Ok(ws) => Ok(Some(TargetControl::Playbridge(Box::new(ws)))),
                        Err(e) => Err(e),
                    }
                }
                _ => {
                    println!(
                        "Connecting to preferred device \"{}\" ({}:{})...",
                        pref.name,
                        pref.address,
                        pref.port.unwrap_or(8009)
                    );
                    cast_to_target(
                        &pref.protocol,
                        &pref.address,
                        pref.port,
                        pref.location.as_deref(),
                        &target_url,
                        &pref.name,
                    )
                    .await
                }
            };
            match result {
                Ok(channel_opt) => {
                    wait_for_server_exit(&media_url, channel_opt, proxy_server.take()).await;
                    return Ok(());
                }
                Err(err) => {
                    println!("Failed to cast to preferred device: {err}");
                    println!("Falling back to network discovery...\n");
                }
            }
        } else {
            println!("Auto-send cancelled. Scanning for devices...\n");
        }
    }

    // 2. Continuous live discovery and interactive menu
    let selection = live_discovery_interactive_select().await?;
    let (target, make_preferred) = match selection {
        PickerSelection::Browser => {
            let final_existing_media_url = proxy_server.as_ref().map(|_| media_url.clone());
            let (browser_url, control, browser_proxy) =
                cast_to_browser(&media_target, proxy_server.take(), final_existing_media_url)
                    .await?;
            wait_for_server_exit(&browser_url, Some(control), Some(browser_proxy)).await;
            return Ok(());
        }
        PickerSelection::Receiver(target, make_preferred) => (target, make_preferred),
    };

    let address = target
        .addresses
        .iter()
        .find(|a| a.contains('.'))
        .cloned()
        .unwrap_or_else(|| target.addresses.first().cloned().unwrap_or_default());
    let protocol_str = target.protocol.as_str().to_string();

    if make_preferred {
        let pref = PreferredDevice {
            uuid: target.uuid.clone().unwrap_or_else(|| target.id.0.clone()),
            name: target.name.clone(),
            protocol: protocol_str.clone(),
            address: address.clone(),
            port: target.port,
            wss_port: target.wss_port,
            location: target.location.clone(),
        };
        if let Err(e) = pref.save() {
            println!("Warning: failed to save preferred device: {e}");
        } else {
            println!("Saved \"{}\" as preferred device!", target.name);
        }
    }

    let (result, channel_opt) = match protocol_str.to_lowercase().as_str() {
        "playbridge" | "native" => {
            let wss_port = target.wss_port.or(target.port).unwrap_or(8765);
            let uuid = target.uuid.clone().unwrap_or_else(|| target.id.0.clone());
            match cast_to_playbridge(&address, wss_port, &target.name, &uuid, &media_url).await {
                Ok(ws) => (Ok(()), Some(TargetControl::Playbridge(Box::new(ws)))),
                Err(e) => (Err(e), None),
            }
        }
        _ => {
            println!(
                "Connecting to \"{}\" ({}:{})...",
                target.name,
                address,
                target.port.unwrap_or(8009)
            );
            match cast_to_target(
                &protocol_str,
                &address,
                target.port,
                target.location.as_deref(),
                &media_url,
                &target.name,
            )
            .await
            {
                Ok(ch) => (Ok(()), ch),
                Err(e) => (Err(e), None),
            }
        }
    };

    if result.is_ok() {
        wait_for_server_exit(&media_url, channel_opt, proxy_server.take()).await;
    }

    result
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

fn build_seekbar(pos_secs: f64, dur_secs: f64, bar_len: usize) -> String {
    if dur_secs <= 0.0 {
        return format!("[{}] {}", "=".repeat(bar_len), format_time(pos_secs));
    }
    let pct = (pos_secs / dur_secs).clamp(0.0, 1.0);
    let filled_len = (pct * bar_len as f64).round() as usize;
    let empty_len = bar_len.saturating_sub(filled_len);
    let bar = format!("{}{}", "=".repeat(filled_len), "-".repeat(empty_len));
    format!(
        "[{}] {} / {} ({:.0}%)",
        bar,
        format_time(pos_secs),
        format_time(dur_secs),
        pct * 100.0
    )
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

#[allow(clippy::collapsible_if)]
async fn wait_for_server_exit(
    media_url: &str,
    mut target_control: Option<TargetControl>,
    proxy_server: Option<ProxyServer>,
) {
    if proxy_server.is_some() || target_control.is_some() {
        println!("\nStreaming media at {}", media_url);
        println!("Interactive Controls:");
        match target_control {
            Some(TargetControl::Playbridge(_)) => {
                println!("  [Space] / [P] : Pause / Resume");
                println!("  [Left]  / [A] : Seek Backward (-10s)");
                println!("  [Right] / [D] : Seek Forward (+10s)");
                println!("  [Up]    / [W] : Volume Up (+5%)");
                println!("  [Down]  / [S] : Volume Down (-5%)");
                println!("  [M]           : Mute / Unmute Toggle");
                println!("  [L]           : Loop Toggle");
                println!("  [B]           : Audio Boost Toggle");
                println!("  [1] - [4]     : Speed (1.0x, 1.25x, 1.5x, 2.0x)");
                println!("  [Q] / [Ctrl+C]: Stop Playback & Exit\n");
            }
            Some(TargetControl::Cast { .. }) => {
                println!("  [Space] / [P] : Pause / Resume");
                println!("  [Left]  / [A] : Seek Backward (-10s)");
                println!("  [Right] / [D] : Seek Forward (+10s)");
                println!("  [Up]    / [W] : Volume Up (+5%)");
                println!("  [Down]  / [S] : Volume Down (-5%)");
                println!("  [Q] / [Ctrl+C]: Stop Playback & Exit\n");
            }
            Some(TargetControl::Dlna(_)) => {
                println!("  [Space] / [P] : Pause / Resume");
                println!("  [Left]  / [A] : Seek Backward (-10s)");
                println!("  [Right] / [D] : Seek Forward (+10s)");
                println!("  [Q] / [Ctrl+C]: Stop Playback & Exit\n");
            }
            Some(TargetControl::Roku(_)) => {
                println!("  [Space] / [P] : Pause / Resume");
                println!("  [Left]  / [A] : Rewind");
                println!("  [Right] / [D] : Fast Forward");
                println!("  [Q] / [Ctrl+C]: Stop Playback & Exit\n");
            }
            Some(TargetControl::Browser { .. }) => {
                println!("  [Space] / [P] : Pause / Resume");
                println!("  [Left]  / [A] : Seek Backward (-10s)");
                println!("  [Right] / [D] : Seek Forward (+10s)");
                println!("  [Up]    / [W] : Volume Up (+5%)");
                println!("  [Down]  / [S] : Volume Down (-5%)");
                println!("  [Q] / [Ctrl+C]: Stop Playback & Exit\n");
            }
            None => {
                println!("  [Q] / [Ctrl+C]: Stop Local Server & Exit\n");
            }
        }

        let _raw_mode = RawModeGuard::enable().ok();
        let mut is_paused = false;
        let mut is_looping = false;
        let mut volume_level: f32 = 0.5;
        let mut current_speed: &str = "1.0x";
        let mut last_ping = tokio::time::Instant::now();
        let mut last_tick = tokio::time::Instant::now();
        let mut current_pos_secs: f64 = 0.0;
        let mut duration_secs: f64 = 0.0;
        let mut browser_disconnected = false;

        loop {
            // Update local time estimate if playing
            let delta = last_tick.elapsed().as_secs_f64();
            last_tick = tokio::time::Instant::now();
            if !is_paused && current_pos_secs > 0.0 {
                current_pos_secs += delta;
                if duration_secs > 0.0 && current_pos_secs > duration_secs {
                    current_pos_secs = duration_secs;
                }
            }

            // Heartbeat ping & status read for Google Cast
            if let Some(TargetControl::Cast {
                ref mut channel,
                ref destination_id,
                ref mut media_session_id,
                ..
            }) = target_control
            {
                if last_ping.elapsed() >= Duration::from_secs(3) {
                    let _ = castv2::send_heartbeat_ping(channel).await;
                    let _ = castv2::send_get_media_status(channel, destination_id).await;
                    last_ping = tokio::time::Instant::now();
                }

                if let Ok(Ok(msg)) =
                    tokio::time::timeout(Duration::from_millis(40), channel.read_message()).await
                {
                    if msg.namespace == castv2::NS_MEDIA {
                        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&msg.payload_utf8)
                        {
                            if let Some(status) = v["status"].as_array().and_then(|a| a.first()) {
                                if let Some(new_msid) = status["mediaSessionId"].as_i64() {
                                    *media_session_id = new_msid;
                                }
                                if let Some(ct) = status["currentTime"].as_f64() {
                                    current_pos_secs = ct;
                                }
                                if let Some(dur) = status["media"]["duration"].as_f64() {
                                    duration_secs = dur;
                                }
                                if let Some(ps) = status["playerState"].as_str() {
                                    is_paused = ps == "PAUSED";
                                }
                            }
                        }
                    } else if msg.namespace == castv2::NS_HEARTBEAT {
                        if let Err(error) = channel.handle_heartbeat(&msg).await {
                            print!("\r\x1b[K[Google Cast heartbeat failed: {error}]");
                        }
                    }
                }
            }

            // Keep-alive ping & status reader for PlayBridge
            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control {
                if last_ping.elapsed() >= Duration::from_secs(5) {
                    let _ = socket.send(&SenderFrame::Ping).await;
                    last_ping = tokio::time::Instant::now();
                }

                // Continuously drain incoming WebSocket frames and extract position/duration
                while let Ok(Ok(Some(frame))) =
                    tokio::time::timeout(Duration::from_millis(5), socket.receive()).await
                {
                    if let ReceiverFrame::Status {
                        position, duration, ..
                    } = frame
                    {
                        if position > 0 {
                            current_pos_secs = position as f64 / 1000.0;
                        }
                        if duration > 0 {
                            duration_secs = duration as f64 / 1000.0;
                        }
                    }
                }
            }

            // Position & duration reader for DLNA
            if let Some(TargetControl::Dlna(ref renderer)) = target_control {
                if last_ping.elapsed() >= Duration::from_secs(2) {
                    if let Ok(info) = renderer.transport_info().await
                        && let Some(state) = info.get("CurrentTransportState")
                    {
                        is_paused = matches!(state.as_str(), "PAUSED_PLAYBACK" | "STOPPED");
                    }
                    if let Ok(info) = renderer.position_info().await {
                        if let Some(rel) = info.get("RelTime").and_then(|s| parse_dlna_time(s)) {
                            if rel > 0.0 {
                                current_pos_secs = rel;
                            }
                        }
                        if let Some(dur) =
                            info.get("TrackDuration").and_then(|s| parse_dlna_time(s))
                        {
                            if dur > 0.0 {
                                duration_secs = dur;
                            }
                        }
                    }
                    last_ping = tokio::time::Instant::now();
                }
            }

            if let Some(TargetControl::Roku(ref mut session)) = target_control {
                if last_ping.elapsed() >= Duration::from_secs(2) {
                    match session.status().await {
                        Ok(status) => {
                            current_pos_secs = status.position_seconds;
                            duration_secs = status.duration_seconds;
                            if status.state != PlaybackState::Unknown {
                                is_paused = status.state == PlaybackState::Paused;
                            }
                        }
                        Err(error) => print!("\r\x1b[K[Roku status failed: {error}]"),
                    }
                    last_ping = tokio::time::Instant::now();
                }
            }

            if let Some(TargetControl::Browser { ref mut events, .. }) = target_control {
                while let Ok(event) = events.try_recv() {
                    match event {
                        BrowserReceiverEvent::Status { session, .. } => {
                            current_pos_secs = session.status.position_ms as f64 / 1000.0;
                            duration_secs = session.status.duration_ms as f64 / 1000.0;
                            volume_level = session.status.volume as f32;
                            is_paused = matches!(
                                session.status.state,
                                BrowserPlaybackState::Paused
                                    | BrowserPlaybackState::AutoplayBlocked
                            );
                        }
                        BrowserReceiverEvent::Ended { session } => {
                            current_pos_secs = session.status.duration_ms as f64 / 1000.0;
                            duration_secs = current_pos_secs;
                            is_paused = true;
                        }
                        BrowserReceiverEvent::Error { message, .. } => {
                            print!("\r\x1b[K[Browser receiver error: {message}]");
                        }
                        BrowserReceiverEvent::Disconnected { .. } => {
                            browser_disconnected = true;
                        }
                        _ => {}
                    }
                }
            }
            if browser_disconnected {
                print!("\r\x1b[K[Browser receiver disconnected]");
                break;
            }

            // Keyboard input polling
            if event::poll(Duration::from_millis(80)).unwrap_or(false) {
                if let Ok(Event::Key(key)) = event::read() {
                    match key.code {
                        KeyCode::Char(' ') | KeyCode::Char('p') | KeyCode::Char('P') => {
                            is_paused = !is_paused;
                            match target_control {
                                Some(TargetControl::Cast {
                                    ref mut channel,
                                    ref destination_id,
                                    media_session_id,
                                    ..
                                }) => {
                                    let result = if is_paused {
                                        castv2::send_pause(
                                            channel,
                                            destination_id,
                                            media_session_id,
                                        )
                                        .await
                                    } else {
                                        castv2::send_play(channel, destination_id, media_session_id)
                                            .await
                                    };
                                    if let Err(error) = result {
                                        is_paused = !is_paused;
                                        print!("\r\x1b[K[Google Cast control failed: {error}]");
                                    } else {
                                        print!(
                                            "\r\x1b[K[State: {}]",
                                            if is_paused { "PAUSED" } else { "PLAYING" }
                                        );
                                    }
                                }
                                Some(TargetControl::Dlna(ref renderer)) => {
                                    let result = if is_paused {
                                        renderer.pause().await
                                    } else {
                                        renderer.play().await
                                    };
                                    if let Err(error) = result {
                                        is_paused = !is_paused;
                                        print!("\r\x1b[K[DLNA control failed: {error}]");
                                    } else {
                                        print!(
                                            "\r\x1b[K[State: {}]",
                                            if is_paused { "PAUSED" } else { "PLAYING" }
                                        );
                                    }
                                }
                                Some(TargetControl::Roku(ref mut session)) => {
                                    let result = if is_paused {
                                        session.pause().await
                                    } else {
                                        session.play().await
                                    };
                                    if let Err(error) = result {
                                        is_paused = !is_paused;
                                        print!("\r\x1b[K[Roku control failed: {error}]");
                                    }
                                }
                                Some(TargetControl::Playbridge(ref mut socket)) => {
                                    let command_str = if is_paused { "pause" } else { "play" };
                                    let cmd = SenderFrame::Command {
                                        action: "control".into(),
                                        payload: Some(
                                            serde_json::json!({ "command": command_str }),
                                        ),
                                    };
                                    let _ = socket.send(&cmd).await;
                                    print!(
                                        "\r\x1b[K[State: {}]",
                                        if is_paused { "PAUSED" } else { "PLAYING" }
                                    );
                                }
                                Some(TargetControl::Browser {
                                    ref service,
                                    ref session_id,
                                    ..
                                }) => {
                                    let action = if is_paused {
                                        BrowserCommand::Pause
                                    } else {
                                        BrowserCommand::Play
                                    };
                                    if let Err(error) = service.command(session_id, action, None) {
                                        is_paused = !is_paused;
                                        print!("\r\x1b[K[Browser control failed: {error}]");
                                    }
                                }
                                None => {}
                            }
                            let _ = io::stdout().flush();
                        }
                        KeyCode::Left | KeyCode::Char('a') | KeyCode::Char('A') => {
                            current_pos_secs = (current_pos_secs - 10.0).max(0.0);
                            match target_control {
                                Some(TargetControl::Cast {
                                    ref mut channel,
                                    ref destination_id,
                                    media_session_id,
                                    ..
                                }) => {
                                    match castv2::send_seek(
                                        channel,
                                        destination_id,
                                        media_session_id,
                                        current_pos_secs,
                                    )
                                    .await
                                    {
                                        Ok(()) => print!(
                                            "\r\x1b[K[Seek -10s -> {:.0}s]",
                                            current_pos_secs
                                        ),
                                        Err(error) => {
                                            print!("\r\x1b[K[Google Cast seek failed: {error}]")
                                        }
                                    }
                                }
                                Some(TargetControl::Dlna(ref renderer)) => {
                                    let hrs = (current_pos_secs / 3600.0) as u32;
                                    let mins = ((current_pos_secs % 3600.0) / 60.0) as u32;
                                    let secs = (current_pos_secs % 60.0) as u32;
                                    let rel_time = format!("{:02}:{:02}:{:02}", hrs, mins, secs);
                                    match renderer.seek(&rel_time).await {
                                        Ok(()) => print!("\r\x1b[K[Seek -10s -> {}]", rel_time),
                                        Err(error) => print!("\r\x1b[K[DLNA seek failed: {error}]"),
                                    }
                                }
                                Some(TargetControl::Roku(ref mut session)) => {
                                    if let Err(error) = session.relative_seek(false).await {
                                        print!("\r\x1b[K[Roku rewind failed: {error}]");
                                    }
                                }
                                Some(TargetControl::Playbridge(ref mut socket)) => {
                                    let cmd = SenderFrame::Command {
                                        action: "control".into(),
                                        payload: Some(
                                            serde_json::json!({ "command": "seek_back" }),
                                        ),
                                    };
                                    let _ = socket.send(&cmd).await;
                                    print!("\r\x1b[K[Seek -10s]");
                                }
                                Some(TargetControl::Browser {
                                    ref service,
                                    ref session_id,
                                    ..
                                }) => {
                                    let _ = service.command(
                                        session_id,
                                        BrowserCommand::Seek,
                                        Some(current_pos_secs * 1000.0),
                                    );
                                }
                                None => {}
                            }
                            let _ = io::stdout().flush();
                        }
                        KeyCode::Right | KeyCode::Char('d') | KeyCode::Char('D') => {
                            current_pos_secs += 10.0;
                            match target_control {
                                Some(TargetControl::Cast {
                                    ref mut channel,
                                    ref destination_id,
                                    media_session_id,
                                    ..
                                }) => {
                                    match castv2::send_seek(
                                        channel,
                                        destination_id,
                                        media_session_id,
                                        current_pos_secs,
                                    )
                                    .await
                                    {
                                        Ok(()) => print!(
                                            "\r\x1b[K[Seek +10s -> {:.0}s]",
                                            current_pos_secs
                                        ),
                                        Err(error) => {
                                            print!("\r\x1b[K[Google Cast seek failed: {error}]")
                                        }
                                    }
                                }
                                Some(TargetControl::Dlna(ref renderer)) => {
                                    let hrs = (current_pos_secs / 3600.0) as u32;
                                    let mins = ((current_pos_secs % 3600.0) / 60.0) as u32;
                                    let secs = (current_pos_secs % 60.0) as u32;
                                    let rel_time = format!("{:02}:{:02}:{:02}", hrs, mins, secs);
                                    match renderer.seek(&rel_time).await {
                                        Ok(()) => print!("\r\x1b[K[Seek +10s -> {}]", rel_time),
                                        Err(error) => print!("\r\x1b[K[DLNA seek failed: {error}]"),
                                    }
                                }
                                Some(TargetControl::Roku(ref mut session)) => {
                                    if let Err(error) = session.relative_seek(true).await {
                                        print!("\r\x1b[K[Roku fast-forward failed: {error}]");
                                    }
                                }
                                Some(TargetControl::Playbridge(ref mut socket)) => {
                                    let cmd = SenderFrame::Command {
                                        action: "control".into(),
                                        payload: Some(
                                            serde_json::json!({ "command": "seek_forward" }),
                                        ),
                                    };
                                    let _ = socket.send(&cmd).await;
                                    print!("\r\x1b[K[Seek +10s]");
                                }
                                Some(TargetControl::Browser {
                                    ref service,
                                    ref session_id,
                                    ..
                                }) => {
                                    let _ = service.command(
                                        session_id,
                                        BrowserCommand::Seek,
                                        Some(current_pos_secs * 1000.0),
                                    );
                                }
                                None => {}
                            }
                            let _ = io::stdout().flush();
                        }
                        KeyCode::Up
                        | KeyCode::Char('w')
                        | KeyCode::Char('W')
                        | KeyCode::Char('+') => {
                            volume_level = (volume_level + 0.05).min(1.0);
                            match target_control {
                                Some(TargetControl::Cast {
                                    ref mut channel, ..
                                }) => {
                                    let _ = castv2::send_volume(channel, volume_level).await;
                                    print!("\r\x1b[K[Volume: {:.0}%]", volume_level * 100.0);
                                }
                                Some(TargetControl::Playbridge(ref mut socket)) => {
                                    let cmd = SenderFrame::Command {
                                        action: "remote".into(),
                                        payload: Some(serde_json::json!({ "key": "volume_up" })),
                                    };
                                    let _ = socket.send(&cmd).await;
                                    print!("\r\x1b[K[Volume Up]");
                                }
                                Some(TargetControl::Browser {
                                    ref service,
                                    ref session_id,
                                    ..
                                }) => {
                                    let _ = service.command(
                                        session_id,
                                        BrowserCommand::SetVolume,
                                        Some(volume_level as f64),
                                    );
                                    print!("\r\x1b[K[Volume: {:.0}%]", volume_level * 100.0);
                                }
                                _ => {}
                            }
                            let _ = io::stdout().flush();
                        }
                        KeyCode::Down
                        | KeyCode::Char('s')
                        | KeyCode::Char('S')
                        | KeyCode::Char('-') => {
                            volume_level = (volume_level - 0.05).max(0.0);
                            match target_control {
                                Some(TargetControl::Cast {
                                    ref mut channel, ..
                                }) => {
                                    let _ = castv2::send_volume(channel, volume_level).await;
                                    print!("\r\x1b[K[Volume: {:.0}%]", volume_level * 100.0);
                                }
                                Some(TargetControl::Playbridge(ref mut socket)) => {
                                    let cmd = SenderFrame::Command {
                                        action: "remote".into(),
                                        payload: Some(serde_json::json!({ "key": "volume_down" })),
                                    };
                                    let _ = socket.send(&cmd).await;
                                    print!("\r\x1b[K[Volume Down]");
                                }
                                Some(TargetControl::Browser {
                                    ref service,
                                    ref session_id,
                                    ..
                                }) => {
                                    let _ = service.command(
                                        session_id,
                                        BrowserCommand::SetVolume,
                                        Some(volume_level as f64),
                                    );
                                    print!("\r\x1b[K[Volume: {:.0}%]", volume_level * 100.0);
                                }
                                _ => {}
                            }
                            let _ = io::stdout().flush();
                        }
                        KeyCode::Char('m') | KeyCode::Char('M') => {
                            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control
                            {
                                let cmd = SenderFrame::Command {
                                    action: "remote".into(),
                                    payload: Some(serde_json::json!({ "key": "mute" })),
                                };
                                let _ = socket.send(&cmd).await;
                                print!("\r\x1b[K[Mute Toggle]");
                                let _ = io::stdout().flush();
                            }
                        }
                        KeyCode::Char('l') | KeyCode::Char('L') => {
                            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control
                            {
                                is_looping = !is_looping;
                                let cmd = SenderFrame::Command {
                                    action: "control".into(),
                                    payload: Some(
                                        serde_json::json!({ "command": if is_looping { "loop_on" } else { "loop_off" } }),
                                    ),
                                };
                                let _ = socket.send(&cmd).await;
                                print!("\r\x1b[K[Loop: {}]", if is_looping { "ON" } else { "OFF" });
                                let _ = io::stdout().flush();
                            }
                        }
                        KeyCode::Char('b') | KeyCode::Char('B') => {
                            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control
                            {
                                let cmd = SenderFrame::Command {
                                    action: "control".into(),
                                    payload: Some(serde_json::json!({ "command": "audio_boost" })),
                                };
                                let _ = socket.send(&cmd).await;
                                print!("\r\x1b[K[Audio Boost Toggle]");
                                let _ = io::stdout().flush();
                            }
                        }
                        KeyCode::Char('1') => {
                            current_speed = "1.0x";
                            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control
                            {
                                let cmd = SenderFrame::Command {
                                    action: "control".into(),
                                    payload: Some(serde_json::json!({ "command": "speed:1.0" })),
                                };
                                let _ = socket.send(&cmd).await;
                            }
                        }
                        KeyCode::Char('2') => {
                            current_speed = "1.25x";
                            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control
                            {
                                let cmd = SenderFrame::Command {
                                    action: "control".into(),
                                    payload: Some(serde_json::json!({ "command": "speed:1.25" })),
                                };
                                let _ = socket.send(&cmd).await;
                            }
                        }
                        KeyCode::Char('3') => {
                            current_speed = "1.5x";
                            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control
                            {
                                let cmd = SenderFrame::Command {
                                    action: "control".into(),
                                    payload: Some(serde_json::json!({ "command": "speed:1.5" })),
                                };
                                let _ = socket.send(&cmd).await;
                            }
                        }
                        KeyCode::Char('4') => {
                            current_speed = "2.0x";
                            if let Some(TargetControl::Playbridge(ref mut socket)) = target_control
                            {
                                let cmd = SenderFrame::Command {
                                    action: "control".into(),
                                    payload: Some(serde_json::json!({ "command": "speed:2.0" })),
                                };
                                let _ = socket.send(&cmd).await;
                            }
                        }
                        KeyCode::Char('q') | KeyCode::Char('Q') | KeyCode::Esc => {
                            break;
                        }
                        KeyCode::Char('c')
                            if key
                                .modifiers
                                .contains(crossterm::event::KeyModifiers::CONTROL) =>
                        {
                            break;
                        }
                        _ => {}
                    }
                }
            }

            // Render live progress seekbar
            print!(
                "\r\x1b[KState: {} | Speed: {} | Vol: {:.0}%\r\n\x1b[KSeekbar: {}\x1b[1A",
                if is_paused { "PAUSED" } else { "PLAYING" },
                current_speed,
                volume_level * 100.0,
                build_seekbar(current_pos_secs, duration_secs, 30)
            );
            let _ = io::stdout().flush();

            tokio::select! {
                _ = tokio::signal::ctrl_c() => { break; }
                _ = sleep(Duration::from_millis(50)) => {}
            }
        }

        // Cleanup on exit
        match target_control {
            Some(TargetControl::Cast {
                ref mut channel,
                ref destination_id,
                ref session_id,
                media_session_id,
            }) => {
                let _ = castv2::send_stop_media(channel, destination_id, media_session_id).await;
                if !session_id.is_empty() {
                    let _ = castv2::send_stop_session(channel, session_id).await;
                }
            }
            Some(TargetControl::Dlna(ref renderer)) => {
                let _ = renderer.stop().await;
            }
            Some(TargetControl::Playbridge(mut socket)) => {
                let cmd = SenderFrame::Command {
                    action: "control".into(),
                    payload: Some(serde_json::json!({ "command": "stop" })),
                };
                let _ = socket.send(&cmd).await;
                let _ = (*socket).close().await;
            }
            Some(TargetControl::Roku(mut session)) => {
                if let Err(error) = session.stop().await {
                    eprintln!("\nwarning: failed to stop Roku playback: {error}");
                }
            }
            Some(TargetControl::Browser {
                host,
                service,
                session_id,
                ..
            }) => {
                let _ = service.command(&session_id, BrowserCommand::Stop, None);
                service.disconnect(&session_id);
                if let Err(error) = host.shutdown().await {
                    eprintln!("\nwarning: failed to stop browser receiver host: {error}");
                }
            }
            None => {}
        }

        println!("\nStopped streaming.");
    }
    if let Some(server) = proxy_server
        && let Err(error) = server.shutdown().await
    {
        eprintln!("warning: failed to stop media proxy: {error}");
    }
}

#[allow(clippy::collapsible_if)]
async fn live_discovery_interactive_select() -> Result<PickerSelection, String> {
    let mut stream = DiscoveryStream::start(DiscoveryConfig::default());
    let mut receivers = Vec::<Receiver>::new();
    let mut selection = 0usize;

    let _terminal = PickerTerminalGuard::enable()?;

    // Initial render
    redraw(&receivers, selection)?;

    loop {
        // Handle input events
        if event::poll(Duration::from_millis(50)).map_err(|e| e.to_string())? {
            if let Event::Key(key_event) = event::read().map_err(|e| e.to_string())? {
                match key_event.code {
                    KeyCode::Up if selection > 0 => {
                        selection -= 1;
                        redraw(&receivers, selection)?;
                    }
                    KeyCode::Down if selection + 1 < receivers.len() + 1 => {
                        selection += 1;
                        redraw(&receivers, selection)?;
                    }
                    KeyCode::Enter if selection == receivers.len() => {
                        break Ok(PickerSelection::Browser);
                    }
                    KeyCode::Enter if selection < receivers.len() => {
                        break Ok(PickerSelection::Receiver(
                            receivers[selection].clone(),
                            false,
                        ));
                    }
                    KeyCode::Char('p') | KeyCode::Char('P') if selection < receivers.len() => {
                        break Ok(PickerSelection::Receiver(
                            receivers[selection].clone(),
                            true,
                        ));
                    }
                    KeyCode::Char('r') | KeyCode::Char('R') => {
                        receivers.clear();
                        selection = 0;
                        stream = DiscoveryStream::start(DiscoveryConfig::default());
                        redraw(&receivers, selection)?;
                    }
                    KeyCode::Char('q') | KeyCode::Char('Q') | KeyCode::Esc => {
                        break Err("Selection cancelled".into());
                    }
                    _ => {}
                }
            }
        }

        // Poll discovery stream for new or updated receivers
        tokio::select! {
            event = stream.next() => match event {
                Some(DiscoveryEvent::Found(receiver)) | Some(DiscoveryEvent::Updated(receiver)) => {
                    upsert_receiver(&mut receivers, receiver, &mut selection);
                    redraw(&receivers, selection)?;
                }
                _ => {}
            },
            _ = sleep(Duration::from_millis(50)) => {}
        }
    }
}

fn redraw(receivers: &[Receiver], selection: usize) -> Result<(), String> {
    let mut stdout = io::stdout();
    execute!(stdout, cursor::MoveTo(0, 0), Clear(ClearType::All),)
        .map_err(|error| error.to_string())?;

    write!(stdout, "Discovered Devices (scanning...):\r\n").map_err(|error| error.to_string())?;

    if receivers.is_empty() {
        write!(stdout, "  (Searching for devices on LAN...)\r\n")
            .map_err(|error| error.to_string())?;
    } else {
        let mut current_protocol = None;
        for (idx, r) in receivers.iter().enumerate() {
            if current_protocol != Some(r.protocol) {
                current_protocol = Some(r.protocol);
                write!(stdout, "\r\n{}\r\n", protocol_label(r.protocol))
                    .map_err(|error| error.to_string())?;
            }
            let prefix = if idx == selection { ">" } else { " " };
            write!(
                stdout,
                "  {} {} - {}\r\n",
                prefix,
                r.name,
                address_summary(r),
            )
            .map_err(|error| error.to_string())?;
        }
    }
    let browser_prefix = if selection == receivers.len() {
        ">"
    } else {
        " "
    };
    write!(
        stdout,
        "\r\nWeb Browser\r\n  {} Open receiver setup page\r\n",
        browser_prefix
    )
    .map_err(|error| error.to_string())?;

    write!(
        stdout,
        "\r\n[↑/↓] Navigate  [Enter] Cast/Setup  [P] Preferred  [R] Rescan  [Q] Cancel\r\n"
    )
    .map_err(|error| error.to_string())?;

    stdout.flush().map_err(|error| error.to_string())
}

enum PickerSelection {
    Receiver(Receiver, bool),
    Browser,
}

fn upsert_receiver(receivers: &mut Vec<Receiver>, receiver: Receiver, selection: &mut usize) {
    let browser_selected = *selection == receivers.len();
    let selected_id = receivers.get(*selection).map(|item| item.id.clone());
    if let Some(existing) = receivers.iter_mut().find(|item| item.id == receiver.id) {
        *existing = receiver;
    } else {
        receivers.push(receiver);
    }
    receivers.sort_by(|left, right| {
        protocol_rank(left.protocol)
            .cmp(&protocol_rank(right.protocol))
            .then_with(|| left.name.to_lowercase().cmp(&right.name.to_lowercase()))
            .then_with(|| left.id.0.cmp(&right.id.0))
    });
    *selection = if browser_selected {
        receivers.len()
    } else {
        selected_id
            .and_then(|id| receivers.iter().position(|item| item.id == id))
            .unwrap_or_else(|| (*selection).min(receivers.len().saturating_sub(1)))
    };
}

const fn protocol_rank(protocol: ReceiverProtocol) -> u8 {
    match protocol {
        ReceiverProtocol::PlayBridge => 0,
        ReceiverProtocol::Dlna => 1,
        ReceiverProtocol::Roku => 2,
        ReceiverProtocol::GoogleCast => 3,
        ReceiverProtocol::Dial => 4,
    }
}

const fn protocol_label(protocol: ReceiverProtocol) -> &'static str {
    match protocol {
        ReceiverProtocol::PlayBridge => "PlayBridge",
        ReceiverProtocol::Dlna => "DLNA",
        ReceiverProtocol::Roku => "Roku",
        ReceiverProtocol::GoogleCast => "Google Cast",
        ReceiverProtocol::Dial => "DIAL",
    }
}

fn address_summary(receiver: &Receiver) -> String {
    let address = receiver
        .addresses
        .iter()
        .find(|address| address.contains('.'))
        .or_else(|| receiver.addresses.first())
        .map(String::as_str)
        .unwrap_or("address unavailable");
    let additional = receiver.addresses.len().saturating_sub(1);
    if additional == 0 {
        address.to_owned()
    } else {
        format!("{address} (+{additional} more)")
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
            let target_port = port.unwrap_or(8009);
            println!(
                "Launching Google Cast receiver at {}:{}...",
                address, target_port
            );
            let application_id = env::var("PLAYBRIDGE_GOOGLE_CAST_APP_ID")
                .unwrap_or_else(|_| castv2::DEFAULT_MEDIA_RECEIVER_APP_ID.to_owned());
            let mut details = castv2::launch_app_session_with_strategy(
                address,
                target_port,
                &application_id,
                castv2::SessionLaunchStrategy::ForceRelaunch,
            )
            .await?;
            println!(
                "Receiver application {} is ready; loading media...",
                details.app_id
            );
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
            println!(
                "Successfully sent media cast request to \"{}\"!",
                device_name
            );
            Ok(Some(TargetControl::Cast {
                channel: details.channel,
                destination_id: details.transport_id,
                session_id: details.session_id,
                media_session_id,
            }))
        }
        "dlna" => {
            let loc = location.ok_or_else(|| "DLNA location description missing".to_string())?;
            println!("Loading UPnP DLNA Renderer at {}...", loc);
            let renderer = Renderer::load(loc).await.map_err(|e| e.to_string())?;
            println!("Setting AVTransport URI to {}...", media_url);
            renderer
                .set_media_uri(media_url, "")
                .await
                .map_err(|e| e.to_string())?;
            renderer.play().await.map_err(|e| e.to_string())?;
            println!("Successfully started DLNA playback on \"{}\"!", device_name);
            Ok(Some(TargetControl::Dlna(renderer)))
        }
        "roku" => {
            let target_port = port.unwrap_or(8060);
            println!("Sending media to Roku at {}:{}...", address, target_port);
            let mut session = ReceiverSession::connect_roku(address, target_port)
                .map_err(|error| error.to_string())?;
            let mut media = MediaRequest::new(media_url);
            media.title = Some(device_name.to_owned());
            session
                .load(&media)
                .await
                .map_err(|error| error.to_string())?;
            println!("Successfully launched media on Roku \"{}\"!", device_name);
            Ok(Some(TargetControl::Roku(session)))
        }
        "playbridge" | "native" => Err(
            "PlayBridge devices must be cast via cast_to_playbridge (internal routing error)"
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
    let endpoint = playbridge_cast_core::net::wss_endpoint(address, wss_port);

    // Try stored credentials first
    if let Some(creds) = PlaybridgeCredentials::load(device_uuid) {
        println!("Using stored credentials for \"{}\"...", device_name);
        let mut socket = SecureWebSocket::connect_pinned(&endpoint, &creds.cert_fingerprint)
            .await
            .map_err(|e| e.to_string())?;

        socket
            .send(&SenderFrame::Auth {
                token: creds.token.clone(),
            })
            .await
            .map_err(|e| e.to_string())?;

        loop {
            match socket.receive().await.map_err(|e| e.to_string())? {
                Some(ReceiverFrame::AuthResponse { success: true, .. }) => {
                    println!("Authenticated with \"{}\"!", device_name);
                    break;
                }
                Some(ReceiverFrame::AuthResponse { success: false, .. }) => {
                    return Err("Authentication failed".into());
                }
                Some(_) => {}
                None => return Err("Receiver closed connection during auth".into()),
            }
        }

        send_playlist(&mut socket, media_url, device_name).await?;
        return Ok(socket);
    }

    // No stored credentials — full pairing flow
    println!(
        "No stored credentials for \"{}\". Starting pairing...",
        device_name
    );
    let mut socket = SecureWebSocket::connect_for_pairing(&endpoint)
        .await
        .map_err(|e| e.to_string())?;
    let served_pin = socket.served_spki_pin().to_owned();

    let (mut pairing, commit) =
        PairingSession::start(device_name.to_owned(), device_uuid.to_owned())
            .map_err(|e| e.to_string())?;

    socket.send(&commit).await.map_err(|e| e.to_string())?;
    println!("Pairing commit sent. Waiting for receiver challenge...");

    while let Some(frame) = socket.receive().await.map_err(|e| e.to_string())? {
        match frame {
            ReceiverFrame::PairingChallenge {
                tv_eph_pub,
                nonce_t,
            } => {
                let (sas, reveal) = pairing
                    .accept_challenge(&tv_eph_pub, &nonce_t)
                    .map_err(|e| e.to_string())?;
                socket.send(&reveal).await.map_err(|e| e.to_string())?;
                println!("Challenge accepted.");

                print!("Enter the 6-digit code shown on \"{}\": ", device_name);
                io::stdout().flush().map_err(|e| e.to_string())?;
                let mut entered = String::new();
                io::stdin()
                    .read_line(&mut entered)
                    .map_err(|e| e.to_string())?;
                let confirmation = pairing
                    .confirmation(entered.trim(), &sas)
                    .map_err(|e| e.to_string())?;
                socket
                    .send(&confirmation)
                    .await
                    .map_err(|e| e.to_string())?;
                println!("Confirmation sent. Waiting for receiver approval...");
            }
            ReceiverFrame::PairingApproved { nonce, ciphertext } => {
                let bundle = pairing
                    .decrypt_credentials(&nonce, &ciphertext, Some(&served_pin))
                    .map_err(|e| e.to_string())?;
                println!(
                    "Pairing succeeded! Players: {:?}, Browsers: {:?}",
                    bundle.players, bundle.browsers
                );

                let creds = PlaybridgeCredentials {
                    token: bundle.token.clone(),
                    cert_fingerprint: bundle
                        .cert_fingerprint
                        .unwrap_or_else(|| served_pin.clone()),
                    players: bundle.players.clone(),
                    browsers: bundle.browsers.clone(),
                };
                if let Err(e) = creds.save(device_uuid) {
                    println!("Warning: failed to save credentials: {e}");
                } else {
                    println!("Credentials saved for future casts.");
                }

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
    #[cfg(debug_assertions)]
    {
        eprintln!("[debug][sender] playlist URL: {media_url}");
        eprintln!("[debug][sender] playlist headers (0)");
    }
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
    println!("Sent playlist command to \"{}\"!", device_name);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use playbridge_cast_core::discovery::ReceiverId;

    fn receiver(id: &str, protocol: ReceiverProtocol, name: &str, addresses: &[&str]) -> Receiver {
        Receiver {
            id: ReceiverId(id.to_owned()),
            protocol,
            name: name.to_owned(),
            addresses: addresses
                .iter()
                .map(|address| (*address).to_owned())
                .collect(),
            port: None,
            wss_port: None,
            location: None,
            uuid: None,
        }
    }

    #[test]
    fn groups_receivers_without_moving_the_selected_device() {
        let mut receivers = vec![receiver(
            "dlna:bedroom",
            ReceiverProtocol::Dlna,
            "Bedroom TV",
            &["192.168.1.34"],
        )];
        let mut selection = 0;

        upsert_receiver(
            &mut receivers,
            receiver(
                "playbridge:desktop",
                ReceiverProtocol::PlayBridge,
                "Desktop",
                &["192.168.1.32"],
            ),
            &mut selection,
        );
        upsert_receiver(
            &mut receivers,
            receiver(
                "google_cast:speaker",
                ReceiverProtocol::GoogleCast,
                "Bedroom Speaker",
                &["192.168.1.17"],
            ),
            &mut selection,
        );

        assert_eq!(receivers[0].protocol, ReceiverProtocol::PlayBridge);
        assert_eq!(receivers[1].protocol, ReceiverProtocol::Dlna);
        assert_eq!(receivers[2].protocol, ReceiverProtocol::GoogleCast);
        assert_eq!(receivers[selection].id.0, "dlna:bedroom");
    }

    #[test]
    fn address_summary_prefers_ipv4_and_compacts_alternatives() {
        let receiver = receiver(
            "playbridge:desktop",
            ReceiverProtocol::PlayBridge,
            "Desktop",
            &["fe80::1%en0", "192.168.1.32", "fdeb::2"],
        );
        assert_eq!(address_summary(&receiver), "192.168.1.32 (+2 more)");
    }

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
