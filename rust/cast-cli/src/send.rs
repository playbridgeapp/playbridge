use crossterm::{
    cursor,
    event::{self, Event, KeyCode},
    execute,
    terminal::{Clear, ClearType, disable_raw_mode, enable_raw_mode},
};
use std::{
    io::{self, Write},
    path::PathBuf,
    time::Duration,
};
use tokio::time::sleep;

use playbridge_cast_core::{
    castv2,
    discovery::{DiscoveryConfig, DiscoveryEvent, DiscoveryStream, Receiver},
    playbridge::{PairingSession, ReceiverFrame, SenderFrame},
    secure_ws::SecureWebSocket,
    session::{MediaRequest, PlaybackState, ReceiverSession},
    upnp::Renderer,
};

use crate::credentials::PlaybridgeCredentials;
use crate::http_server::LocalMediaServer;
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

#[allow(clippy::collapsible_if)]
pub async fn run_send(media_target: String) -> Result<(), String> {
    println!("Media Target: {media_target}");

    // Resolve local file vs remote URL
    let resolved_path = if let Some(relative) = media_target.strip_prefix("~/") {
        if let Some(home) = std::env::var_os("HOME") {
            let mut path = PathBuf::from(home);
            path.push(relative);
            path
        } else {
            PathBuf::from(&media_target)
        }
    } else {
        PathBuf::from(&media_target)
    };

    let (media_url, _server_handle) = if resolved_path.exists() && resolved_path.is_file() {
        println!(
            "Starting local HTTP media server for {:?}...",
            resolved_path.file_name().unwrap_or_default()
        );
        let (server, handle) = LocalMediaServer::start(resolved_path).await?;
        println!("Local HTTP server running at {}\n", server.url);
        (server.url, Some(handle))
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
                    wait_for_server_exit(&media_url, channel_opt, _server_handle.is_some()).await;
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
    let (target, make_preferred) = live_discovery_interactive_select().await?;

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
        wait_for_server_exit(&media_url, channel_opt, _server_handle.is_some()).await;
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
    is_local_server: bool,
) {
    if is_local_server || target_control.is_some() {
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
            None => {}
        }

        println!("\nStopped streaming.");
    }
}

#[allow(clippy::collapsible_if)]
async fn live_discovery_interactive_select() -> Result<(Receiver, bool), String> {
    let mut stream = DiscoveryStream::start(DiscoveryConfig::default());
    let mut receivers = Vec::<Receiver>::new();
    let mut selection = 0usize;
    let mut rendered_lines = 0usize;

    let raw_mode = RawModeGuard::enable()?;

    // Initial render
    let _ = redraw(&receivers, selection, &mut rendered_lines);

    let result = loop {
        // Handle input events
        if event::poll(Duration::from_millis(50)).map_err(|e| e.to_string())? {
            if let Event::Key(key_event) = event::read().map_err(|e| e.to_string())? {
                match key_event.code {
                    KeyCode::Up if selection > 0 => {
                        selection -= 1;
                        let _ = redraw(&receivers, selection, &mut rendered_lines);
                    }
                    KeyCode::Down if !receivers.is_empty() && selection + 1 < receivers.len() => {
                        selection += 1;
                        let _ = redraw(&receivers, selection, &mut rendered_lines);
                    }
                    KeyCode::Enter if !receivers.is_empty() => {
                        break Ok((receivers[selection].clone(), false));
                    }
                    KeyCode::Char('p') | KeyCode::Char('P') if !receivers.is_empty() => {
                        break Ok((receivers[selection].clone(), true));
                    }
                    KeyCode::Char('r') | KeyCode::Char('R') => {
                        receivers.clear();
                        selection = 0;
                        stream = DiscoveryStream::start(DiscoveryConfig::default());
                        let _ = redraw(&receivers, selection, &mut rendered_lines);
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
                    if let Some(existing) = receivers.iter_mut().find(|r| r.id == receiver.id) {
                        *existing = receiver;
                    } else {
                        receivers.push(receiver);
                    }
                    let _ = redraw(&receivers, selection, &mut rendered_lines);
                }
                _ => {}
            },
            _ = sleep(Duration::from_millis(50)) => {}
        }
    };

    let mut stdout = io::stdout();
    if rendered_lines > 0 {
        let _ = execute!(
            stdout,
            cursor::MoveToColumn(0),
            cursor::MoveUp(rendered_lines as u16),
            Clear(ClearType::FromCursorDown),
        );
    }
    drop(raw_mode);
    let _ = stdout.flush();
    result
}

fn redraw(
    receivers: &[Receiver],
    selection: usize,
    rendered_lines: &mut usize,
) -> Result<(), String> {
    let mut stdout = io::stdout();
    if *rendered_lines > 0 {
        let _ = execute!(
            stdout,
            cursor::MoveToColumn(0),
            cursor::MoveUp(*rendered_lines as u16),
            Clear(ClearType::FromCursorDown),
        );
    }

    let mut lines = 0usize;
    let _ = write!(stdout, "Discovered Devices (scanning...):\r\n");
    lines += 1;

    if receivers.is_empty() {
        let _ = write!(stdout, "  (Searching for devices on LAN...)\r\n");
        lines += 1;
    } else {
        for (idx, r) in receivers.iter().enumerate() {
            let prefix = if idx == selection { ">" } else { " " };
            let _ = write!(
                stdout,
                "  {} {} ({}) - {}\r\n",
                prefix,
                r.name,
                r.protocol,
                r.addresses.join(", ")
            );
            lines += 1;
        }
    }

    let _ = write!(
        stdout,
        "\r\n[↑/↓] Navigate  [Enter] Cast  [P] Preferred  [R] Rescan  [Q] Cancel\r\n"
    );
    lines += 2;

    let _ = stdout.flush();
    *rendered_lines = lines;
    Ok(())
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
                "Connecting TLS CastV2 channel to Google Cast device at {}:{}...",
                address, target_port
            );
            let details = castv2::cast_media_session_with_details(
                address,
                target_port,
                media_url,
                Some(device_name),
            )
            .await?;
            println!(
                "Successfully sent media cast request to \"{}\"!",
                device_name
            );
            Ok(Some(TargetControl::Cast {
                channel: details.channel,
                destination_id: details.transport_id,
                session_id: details.session_id,
                media_session_id: details.media_session_id,
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
    let endpoint = format!("wss://{address}:{wss_port}");

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
