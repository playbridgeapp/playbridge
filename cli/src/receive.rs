use std::{collections::HashSet, fs, io::Write, path::PathBuf, process::Stdio, time::Duration};

use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use playbridge_cast_receiver::{
    PrivateKeyKind, ReceiverCommand, ReceiverConfig, ReceiverEvent, ReceiverHost, ReceiverIdentity,
};
use rcgen::{CertificateParams, KeyPair};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
#[cfg(not(unix))]
use tokio::process::ChildStdin;
use tokio::{
    io::AsyncWriteExt,
    process::{Child, Command},
};
#[cfg(unix)]
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    net::{UnixStream, unix::OwnedWriteHalf},
    time::sleep,
};

const DEFAULT_PORT: u16 = 8765;

#[derive(Debug, Clone, Copy)]
pub(crate) enum ReceiverDashboardCommand {
    StopHost,
    PlayPause,
    SeekRelative(i64),
    Previous,
    Next,
    VolumeDelta(f32),
    ToggleMute,
    ToggleLoop,
    SetSpeed(f32),
    StopPlayback,
}

#[derive(Debug, Clone)]
pub(crate) struct ReceiverPlaybackSnapshot {
    pub state: String,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub title: Option<String>,
    pub queue_len: usize,
    pub current_index: usize,
    pub volume: f32,
    pub muted: bool,
    pub looping: bool,
    pub speed: f32,
}

#[derive(Debug, Clone)]
pub(crate) enum ReceiverUiEvent {
    HostStarted {
        name: String,
        port: u16,
    },
    ClientCount {
        total: usize,
        authenticated: usize,
    },
    PairingRequested {
        device_name: String,
        sas_code: String,
    },
    Paired {
        device_name: String,
    },
    Playback(ReceiverPlaybackSnapshot),
    Warning(String),
}

#[derive(Debug, Serialize, Deserialize)]
struct ReceiverState {
    uuid: String,
    name: String,
    certificate_der: String,
    private_key_der: String,
    #[serde(default)]
    tokens: HashSet<String>,
}

struct Mpv {
    child: Option<Child>,
    #[cfg(not(unix))]
    stdin: Option<ChildStdin>,
    #[cfg(unix)]
    ipc: Option<OwnedWriteHalf>,
    #[cfg(unix)]
    ipc_path: Option<PathBuf>,
    state: &'static str,
    title: Option<String>,
    #[cfg(debug_assertions)]
    quiet: bool,
}

struct MpvSnapshot {
    state: String,
    position_ms: u64,
    duration_ms: u64,
    title: Option<String>,
    volume: f32,
    muted: bool,
    looping: bool,
    speed: f32,
}

impl Mpv {
    fn new(_quiet: bool) -> Self {
        Self {
            child: None,
            #[cfg(not(unix))]
            stdin: None,
            #[cfg(unix)]
            ipc: None,
            #[cfg(unix)]
            ipc_path: None,
            state: "idle",
            title: None,
            #[cfg(debug_assertions)]
            quiet: _quiet,
        }
    }

    async fn ensure_started(&mut self) -> Result<(), String> {
        if self
            .child
            .as_mut()
            .is_some_and(|child| child.try_wait().ok().flatten().is_none())
        {
            return Ok(());
        }
        #[cfg(unix)]
        {
            self.ipc = None;
            if let Some(path) = self.ipc_path.take() {
                let _ = fs::remove_file(path);
            }
        }
        let mut command = Command::new("mpv");
        command.args(["--idle=yes", "--fullscreen=yes", "--msg-level=all=warn"]);
        #[cfg(unix)]
        {
            let path = mpv_ipc_path()?;
            command.arg(format!("--input-ipc-server={}", path.display()));
            self.ipc_path = Some(path);
        }
        #[cfg(not(unix))]
        command.arg("--input-terminal=yes");

        #[allow(unused_mut)]
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|error| format!("could not launch mpv: {error}"))?;
        #[cfg(not(unix))]
        {
            self.stdin = child.stdin.take();
        }
        self.child = Some(child);
        #[cfg(unix)]
        self.connect_ipc().await?;
        Ok(())
    }

    async fn stop_process(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill().await;
        }
        #[cfg(unix)]
        {
            self.ipc = None;
            if let Some(path) = self.ipc_path.take() {
                let _ = fs::remove_file(path);
            }
        }
    }

    #[cfg(unix)]
    async fn connect_ipc(&mut self) -> Result<(), String> {
        let path = self
            .ipc_path
            .as_ref()
            .ok_or("mpv IPC path is unavailable")?;
        let mut last_error = None;
        for _ in 0..40 {
            match UnixStream::connect(path).await {
                Ok(stream) => {
                    let (reader, writer) = stream.into_split();
                    tokio::spawn(async move {
                        let mut lines = BufReader::new(reader).lines();
                        while let Ok(Some(_)) = lines.next_line().await {}
                    });
                    self.ipc = Some(writer);
                    return Ok(());
                }
                Err(error) => {
                    last_error = Some(error);
                    sleep(Duration::from_millis(50)).await;
                }
            }
        }
        Err(format!(
            "could not connect to mpv IPC: {}",
            last_error.map_or_else(|| "timed out".into(), |error| error.to_string())
        ))
    }

    async fn command(&mut self, arguments: Vec<Value>, _legacy: String) -> Result<(), String> {
        self.ensure_started().await?;
        #[cfg(unix)]
        {
            let ipc = self.ipc.as_mut().ok_or("mpv IPC is unavailable")?;
            let mut frame =
                serde_json::to_vec(&json!({"command": arguments})).map_err(|e| e.to_string())?;
            frame.push(b'\n');
            ipc.write_all(&frame)
                .await
                .map_err(|error| format!("could not control mpv: {error}"))
        }
        #[cfg(not(unix))]
        {
            let stdin = self.stdin.as_mut().ok_or("mpv stdin is unavailable")?;
            stdin
                .write_all(format!("{_legacy}\n").as_bytes())
                .await
                .map_err(|error| format!("could not control mpv: {error}"))
        }
    }

    async fn load(&mut self, item: &Value) -> Result<(), String> {
        let url = item
            .get("url")
            .and_then(Value::as_str)
            .ok_or("playlist item has no URL")?;
        let header_values = item
            .get("headers")
            .and_then(Value::as_object)
            .into_iter()
            .flatten()
            .filter_map(|(key, value)| value.as_str().map(|value| format!("{key}: {value}")))
            .collect::<Vec<_>>();
        #[cfg(debug_assertions)]
        if !self.quiet {
            eprintln!("[debug][receiver] playback URL: {url}");
            eprintln!(
                "[debug][receiver] playback headers ({}):",
                header_values.len()
            );
            for header in &header_values {
                eprintln!("[debug][receiver]   {header}");
            }
        }
        let header_fields = header_values.join(",");
        let escaped = header_fields.replace('\\', "\\\\").replace('"', "\\\"");
        // This property is global in mpv. Always write it, including an empty
        // value, so credentials from one stream cannot leak to the next host.
        self.command(
            vec![
                json!("set_property"),
                json!("http-header-fields"),
                json!(header_values),
            ],
            format!("set http-header-fields \"{escaped}\""),
        )
        .await?;
        let escaped = url.replace('\\', "\\\\").replace('"', "\\\"");
        self.command(
            vec![json!("loadfile"), json!(url), json!("replace")],
            format!("loadfile \"{escaped}\" replace"),
        )
        .await?;
        if let Some(position) = item.get("startPositionMs").and_then(Value::as_u64) {
            let seconds = position as f64 / 1000.0;
            self.command(
                vec![json!("seek"), json!(seconds), json!("absolute+exact")],
                format!("seek {seconds} absolute exact"),
            )
            .await?;
        }
        self.state = "playing";
        self.title = item.get("title").and_then(Value::as_str).map(str::to_owned);
        Ok(())
    }

    async fn control(&mut self, command: &str) -> Result<(), String> {
        match command {
            "play" => {
                self.command(
                    vec![json!("set_property"), json!("pause"), json!(false)],
                    "set pause no".into(),
                )
                .await?;
                self.state = "playing";
            }
            "pause" => {
                self.command(
                    vec![json!("set_property"), json!("pause"), json!(true)],
                    "set pause yes".into(),
                )
                .await?;
                self.state = "paused";
            }
            "toggle" => {
                self.command(vec![json!("cycle"), json!("pause")], "cycle pause".into())
                    .await?;
                self.state = if self.state == "playing" {
                    "paused"
                } else {
                    "playing"
                };
            }
            "stop" => {
                self.command(vec![json!("stop")], "stop".into()).await?;
                self.state = "idle";
                self.title = None;
            }
            "seek_back" => {
                self.command(
                    vec![json!("seek"), json!(-10), json!("relative+exact")],
                    "seek -10 relative exact".into(),
                )
                .await?
            }
            "seek_forward" => {
                self.command(
                    vec![json!("seek"), json!(10), json!("relative+exact")],
                    "seek 10 relative exact".into(),
                )
                .await?
            }
            value if value.starts_with("seek_to:") => {
                let milliseconds = value["seek_to:".len()..]
                    .parse::<u64>()
                    .map_err(|_| "invalid seek position")?;
                let seconds = milliseconds as f64 / 1000.0;
                self.command(
                    vec![json!("seek"), json!(seconds), json!("absolute+exact")],
                    format!("seek {seconds} absolute exact"),
                )
                .await?;
            }
            _ => {}
        }
        Ok(())
    }

    async fn snapshot(&self) -> MpvSnapshot {
        #[cfg(unix)]
        if let Some(path) = self.ipc_path.as_deref()
            && let Ok(snapshot) = query_mpv_status(path, self.title.clone()).await
        {
            return snapshot;
        }
        MpvSnapshot {
            state: self.state.into(),
            position_ms: 0,
            duration_ms: 0,
            title: self.title.clone(),
            volume: 100.0,
            muted: false,
            looping: false,
            speed: 1.0,
        }
    }
}

#[cfg(unix)]
async fn query_mpv_status(
    path: &std::path::Path,
    title: Option<String>,
) -> Result<MpvSnapshot, String> {
    let stream = UnixStream::connect(path)
        .await
        .map_err(|error| format!("could not query mpv IPC: {error}"))?;
    let (reader, mut writer) = stream.into_split();
    for (request_id, property) in [
        (1, "time-pos"),
        (2, "duration"),
        (3, "pause"),
        (4, "idle-active"),
        (5, "volume"),
        (6, "mute"),
        (7, "loop-file"),
        (8, "speed"),
    ] {
        let mut frame = serde_json::to_vec(&json!({
            "command": ["get_property", property],
            "request_id": request_id
        }))
        .map_err(|error| error.to_string())?;
        frame.push(b'\n');
        writer
            .write_all(&frame)
            .await
            .map_err(|error| format!("could not query mpv: {error}"))?;
    }

    let mut position = None;
    let mut duration = None;
    let mut paused = None;
    let mut idle = None;
    let mut volume = None;
    let mut muted = None;
    let mut looping = None;
    let mut speed = None;
    let mut lines = BufReader::new(reader).lines();
    for _ in 0..8 {
        let line = tokio::time::timeout(Duration::from_millis(500), lines.next_line())
            .await
            .map_err(|_| "mpv status query timed out".to_owned())?
            .map_err(|error| error.to_string())?
            .ok_or("mpv closed the status connection")?;
        let response: Value = serde_json::from_str(&line).map_err(|error| error.to_string())?;
        match response.get("request_id").and_then(Value::as_u64) {
            Some(1) => position = response.get("data").and_then(Value::as_f64),
            Some(2) => duration = response.get("data").and_then(Value::as_f64),
            Some(3) => paused = response.get("data").and_then(Value::as_bool),
            Some(4) => idle = response.get("data").and_then(Value::as_bool),
            Some(5) => volume = response.get("data").and_then(Value::as_f64),
            Some(6) => muted = response.get("data").and_then(Value::as_bool),
            Some(7) => {
                looping = response
                    .get("data")
                    .and_then(Value::as_str)
                    .map(|value| value != "no")
            }
            Some(8) => speed = response.get("data").and_then(Value::as_f64),
            _ => {}
        }
    }

    let state = if idle.unwrap_or(false) {
        "idle"
    } else if paused.unwrap_or(false) {
        "paused"
    } else {
        "playing"
    };
    Ok(MpvSnapshot {
        state: state.into(),
        position_ms: seconds_to_millis(position),
        duration_ms: seconds_to_millis(duration),
        title,
        volume: volume.unwrap_or(100.0) as f32,
        muted: muted.unwrap_or(false),
        looping: looping.unwrap_or(false),
        speed: speed.unwrap_or(1.0) as f32,
    })
}

fn seconds_to_millis(seconds: Option<f64>) -> u64 {
    seconds
        .filter(|value| value.is_finite() && *value > 0.0)
        .map(|value| (value * 1000.0).round() as u64)
        .unwrap_or(0)
}

/// Dashboard receiver lifecycle. The terminal UI owns the stop action while
/// this task owns the receiver runtime and mpv process.
pub(crate) async fn run_receiver_dashboard(
    arguments: Vec<String>,
    commands: tokio::sync::mpsc::Receiver<ReceiverDashboardCommand>,
    events: tokio::sync::mpsc::Sender<ReceiverUiEvent>,
) -> Result<(), String> {
    run_receiver_mode(&arguments, Some((commands, events))).await
}

async fn run_receiver_mode(
    arguments: &[String],
    mut dashboard: Option<(
        tokio::sync::mpsc::Receiver<ReceiverDashboardCommand>,
        tokio::sync::mpsc::Sender<ReceiverUiEvent>,
    )>,
) -> Result<(), String> {
    let mut port = DEFAULT_PORT;
    let mut requested_name = None;
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--port" => {
                index += 1;
                port = arguments
                    .get(index)
                    .ok_or("--port requires a value")?
                    .parse()
                    .map_err(|_| "invalid receiver port")?;
            }
            "--name" => {
                index += 1;
                requested_name = Some(
                    arguments
                        .get(index)
                        .ok_or("--name requires a value")?
                        .clone(),
                );
            }
            unknown => return Err(format!("unknown receiver option: {unknown}")),
        }
        index += 1;
    }

    ensure_mpv_available().await?;
    let (state_path, mut state) = load_or_create_state(requested_name)?;
    state.name = state.name.trim().to_owned();
    save_state(&state_path, &state)?;
    let mut config = ReceiverConfig::new(
        state.name.clone(),
        state.uuid.clone(),
        ReceiverIdentity {
            certificate_der: BASE64
                .decode(&state.certificate_der)
                .map_err(|error| error.to_string())?,
            private_key_der: BASE64
                .decode(&state.private_key_der)
                .map_err(|error| error.to_string())?,
            private_key_kind: PrivateKeyKind::Pkcs8,
        },
    );
    config.preferred_port = port;
    config.fallback_attempts = 10;
    config.authorized_tokens = state.tokens.iter().cloned().collect();
    config.players = vec!["internal_mpv".into()];
    config.advertise = true;
    let host = ReceiverHost::start(config).await?;
    let mut events = host.subscribe();
    let mut playback = ReceiverPlayback::new(dashboard.is_some());
    let mut status_tick = tokio::time::interval(Duration::from_millis(500));
    status_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    if let Some((_, events)) = dashboard.as_ref() {
        let _ = events
            .send(ReceiverUiEvent::HostStarted {
                name: state.name.clone(),
                port: host.port(),
            })
            .await;
    } else {
        println!(
            "PlayBridge receiver \"{}\" is ready on port {}.",
            state.name,
            host.port()
        );
        println!("Playback uses the installed mpv command. Press Ctrl+C to stop.");
    }

    loop {
        tokio::select! {
            event = events.recv() => {
                match event {
                    Ok(ReceiverEvent::PairingStarted { device_name, .. }) => {
                        if dashboard.is_none() {
                            println!("Pairing request from {device_name}.");
                        }
                    }
                    Ok(ReceiverEvent::PairingRequested { device_name, sas_code, .. }) => {
                        if let Some((_, events)) = dashboard.as_ref() {
                            let _ = events.send(ReceiverUiEvent::PairingRequested { device_name, sas_code }).await;
                        } else {
                            println!("Pairing code: {sas_code}");
                        }
                    }
                    Ok(ReceiverEvent::Paired {
                        device_name,
                        token,
                        ..
                    }) => {
                        state.tokens.insert(token);
                        save_state(&state_path, &state)?;
                        if let Some((_, events)) = dashboard.as_ref() {
                            let _ = events.send(ReceiverUiEvent::Paired { device_name }).await;
                        } else {
                            println!("Sender \"{device_name}\" paired.");
                        }
                    }
                    Ok(ReceiverEvent::ClientCount { total, authenticated }) => {
                        if let Some((_, events)) = dashboard.as_ref() {
                            let _ = events.send(ReceiverUiEvent::ClientCount { total, authenticated }).await;
                        }
                    }
                    Ok(ReceiverEvent::Command { command, .. }) => {
                        if let Err(error) = handle_command(&host, &mut playback, command, dashboard.is_some()).await {
                            if let Some((_, events)) = dashboard.as_ref() {
                                let _ = events.send(ReceiverUiEvent::Warning(error)).await;
                            } else {
                                eprintln!("Playback command failed: {error}");
                            }
                        }
                    }
                    Ok(ReceiverEvent::Error { connection_id, message }) => {
                        if let Some((_, events)) = dashboard.as_ref() {
                            if !is_routine_handshake_rejection(connection_id, &message) {
                                let _ = events.send(ReceiverUiEvent::Warning(message)).await;
                            }
                        } else {
                            eprintln!("receiver connection ended: {message}");
                        }
                    }
                    Ok(_) => {}
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
            _ = status_tick.tick() => {
                broadcast_status(&host, &playback).await;
                emit_receiver_playback(&playback, dashboard.as_ref().map(|(_, events)| events)).await;
            }
            command = async {
                match dashboard.as_mut() {
                    Some((commands, _)) => commands.recv().await,
                    None => std::future::pending().await,
                }
            } => match command {
                Some(ReceiverDashboardCommand::StopHost) | None => break,
                Some(command) => {
                    let result = handle_dashboard_receiver_command(&host, &mut playback, command).await;
                    if let Err(error) = result
                        && let Some((_, events)) = dashboard.as_ref()
                    {
                        let _ = events.send(ReceiverUiEvent::Warning(error)).await;
                    }
                    emit_receiver_playback(&playback, dashboard.as_ref().map(|(_, events)| events)).await;
                }
            },
            _ = tokio::signal::ctrl_c() => break,
        }
    }
    playback.mpv.stop_process().await;
    host.shutdown().await;
    Ok(())
}

struct ReceiverPlayback {
    mpv: Mpv,
    queue: Vec<Value>,
    current_index: usize,
}

impl ReceiverPlayback {
    fn new(quiet: bool) -> Self {
        Self {
            mpv: Mpv::new(quiet),
            queue: Vec::new(),
            current_index: 0,
        }
    }
}

async fn emit_receiver_playback(
    playback: &ReceiverPlayback,
    events: Option<&tokio::sync::mpsc::Sender<ReceiverUiEvent>>,
) {
    let Some(events) = events else { return };
    let snapshot = playback.mpv.snapshot().await;
    let _ = events.try_send(ReceiverUiEvent::Playback(ReceiverPlaybackSnapshot {
        state: snapshot.state,
        position_ms: snapshot.position_ms,
        duration_ms: snapshot.duration_ms,
        title: snapshot.title,
        queue_len: playback.queue.len(),
        current_index: playback.current_index,
        volume: snapshot.volume,
        muted: snapshot.muted,
        looping: snapshot.looping,
        speed: snapshot.speed,
    }));
}

async fn handle_dashboard_receiver_command(
    host: &ReceiverHost,
    playback: &mut ReceiverPlayback,
    command: ReceiverDashboardCommand,
) -> Result<(), String> {
    match command {
        ReceiverDashboardCommand::StopHost => return Ok(()),
        ReceiverDashboardCommand::PlayPause => playback.mpv.control("toggle").await?,
        ReceiverDashboardCommand::SeekRelative(seconds) => {
            playback
                .mpv
                .control(if seconds < 0 {
                    "seek_back"
                } else {
                    "seek_forward"
                })
                .await?;
        }
        ReceiverDashboardCommand::Previous | ReceiverDashboardCommand::Next => {
            if playback.queue.is_empty() {
                return Err("The receiver queue is empty".into());
            }
            playback.current_index = match command {
                ReceiverDashboardCommand::Previous => playback.current_index.saturating_sub(1),
                ReceiverDashboardCommand::Next => {
                    (playback.current_index + 1).min(playback.queue.len() - 1)
                }
                _ => unreachable!(),
            };
            playback
                .mpv
                .load(&playback.queue[playback.current_index])
                .await?;
            broadcast_playlist(host, playback);
        }
        ReceiverDashboardCommand::VolumeDelta(delta) => {
            let amount = delta * 100.0;
            playback
                .mpv
                .command(
                    vec![json!("add"), json!("volume"), json!(amount)],
                    format!("add volume {amount}"),
                )
                .await?;
        }
        ReceiverDashboardCommand::ToggleMute => {
            playback
                .mpv
                .command(vec![json!("cycle"), json!("mute")], "cycle mute".into())
                .await?;
        }
        ReceiverDashboardCommand::ToggleLoop => {
            playback
                .mpv
                .command(
                    vec![
                        json!("cycle-values"),
                        json!("loop-file"),
                        json!("inf"),
                        json!("no"),
                    ],
                    "cycle-values loop-file inf no".into(),
                )
                .await?;
        }
        ReceiverDashboardCommand::SetSpeed(speed) => {
            playback
                .mpv
                .command(
                    vec![json!("set_property"), json!("speed"), json!(speed)],
                    format!("set speed {speed}"),
                )
                .await?;
        }
        ReceiverDashboardCommand::StopPlayback => {
            playback.mpv.control("stop").await?;
            playback.queue.clear();
            playback.current_index = 0;
            host.broadcast(json!({"type":"context","active":"idle"}));
            broadcast_playlist(host, playback);
        }
    }
    broadcast_status(host, playback).await;
    Ok(())
}

async fn handle_command(
    host: &ReceiverHost,
    playback: &mut ReceiverPlayback,
    command: ReceiverCommand,
    quiet: bool,
) -> Result<(), String> {
    match command {
        ReceiverCommand::ContextQuery => {
            host.broadcast(json!({
                "type":"context",
                "active": if playback.mpv.state == "idle" {"idle"} else {"player"}
            }));
            broadcast_status(host, playback).await;
            broadcast_playlist(host, playback);
        }
        ReceiverCommand::Playlist(payload) => {
            let items = payload
                .get("items")
                .and_then(Value::as_array)
                .ok_or("playlist has no items")?;
            let index = payload
                .get("startIndex")
                .and_then(Value::as_u64)
                .unwrap_or(0) as usize;
            playback.queue = items.clone();
            playback.current_index = index.min(playback.queue.len().saturating_sub(1));
            let item = playback
                .queue
                .get(index)
                .or_else(|| playback.queue.first())
                .ok_or("playlist is empty")?;
            let title = item
                .get("title")
                .and_then(Value::as_str)
                .unwrap_or("untitled media");
            if !quiet {
                println!(
                    "Received playlist ({} item(s)); playing \"{title}\".",
                    items.len()
                );
            }
            playback.mpv.load(item).await?;
            if !quiet {
                println!("Sent playback request to mpv.");
            }
            broadcast_status(host, playback).await;
            broadcast_playlist(host, playback);
        }
        ReceiverCommand::QueueAdd(payload) => {
            let item = payload
                .get("item")
                .cloned()
                .ok_or("queue_add has no item")?;
            let was_empty = playback.queue.is_empty();
            playback.queue.push(item);
            if was_empty {
                playback.current_index = 0;
                playback.mpv.load(&playback.queue[0]).await?;
            }
            broadcast_playlist(host, playback);
        }
        ReceiverCommand::PlaylistJump(payload) => {
            let index = payload
                .get("index")
                .and_then(Value::as_u64)
                .ok_or("playlist_jump has no index")? as usize;
            let item = playback
                .queue
                .get(index)
                .ok_or("playlist index is out of range")?;
            playback.current_index = index;
            playback.mpv.load(item).await?;
            broadcast_playlist(host, playback);
        }
        ReceiverCommand::Control(payload) => {
            if let Some(command) = payload.get("command").and_then(Value::as_str) {
                playback.mpv.control(command).await?;
                broadcast_status(host, playback).await;
                if command == "stop" {
                    playback.queue.clear();
                    playback.current_index = 0;
                    host.broadcast(json!({"type":"context","active":"idle"}));
                    broadcast_playlist(host, playback);
                }
            }
        }
        _ => {}
    }
    Ok(())
}

async fn broadcast_status(host: &ReceiverHost, playback: &ReceiverPlayback) {
    let snapshot = playback.mpv.snapshot().await;
    host.broadcast(json!({
        "type": "status",
        "state": snapshot.state,
        "position": snapshot.position_ms,
        "duration": snapshot.duration_ms,
        "title": snapshot.title
    }));
}

fn broadcast_playlist(host: &ReceiverHost, playback: &ReceiverPlayback) {
    let items = playback
        .queue
        .iter()
        .enumerate()
        .map(|(index, item)| {
            json!({
                "index": index,
                "title": item.get("title").and_then(Value::as_str).unwrap_or("untitled media")
            })
        })
        .collect::<Vec<_>>();
    host.broadcast(json!({
        "type":"playlist_status",
        "items":items,
        "currentIndex":playback.current_index,
        "totalCount":playback.queue.len()
    }));
}

async fn ensure_mpv_available() -> Result<(), String> {
    Command::new("mpv")
        .arg("--version")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await
        .map_err(|_| "mpv was not found in PATH".to_owned())
        .and_then(|status| {
            status
                .success()
                .then_some(())
                .ok_or_else(|| "mpv --version failed".to_owned())
        })
}

fn state_path() -> Result<PathBuf, String> {
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .ok_or("could not determine home directory")?;
    Ok(PathBuf::from(home).join(".config/playbridge/receiver.json"))
}

fn load_or_create_state(
    requested_name: Option<String>,
) -> Result<(PathBuf, ReceiverState), String> {
    let path = state_path()?;
    if let Ok(contents) = fs::read_to_string(&path) {
        let mut state: ReceiverState =
            serde_json::from_str(&contents).map_err(|error| error.to_string())?;
        if let Some(name) = requested_name.filter(|name| !name.trim().is_empty()) {
            state.name = name;
        }
        return Ok((path, state));
    }
    let name = requested_name.unwrap_or_else(host_name);
    let key = KeyPair::generate().map_err(|error| error.to_string())?;
    let params = CertificateParams::new(vec![name.clone(), "localhost".into()])
        .map_err(|error| error.to_string())?;
    let certificate = params
        .self_signed(&key)
        .map_err(|error| error.to_string())?;
    let state = ReceiverState {
        uuid: random_token()?,
        name,
        certificate_der: BASE64.encode(certificate.der()),
        private_key_der: BASE64.encode(key.serialize_der()),
        tokens: HashSet::new(),
    };
    save_state(&path, &state)?;
    Ok((path, state))
}

fn save_state(path: &PathBuf, state: &ReceiverState) -> Result<(), String> {
    let parent = path.parent().ok_or("receiver state path has no parent")?;
    fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(parent, fs::Permissions::from_mode(0o700))
            .map_err(|error| error.to_string())?;
    }
    let mut file = tempfile::NamedTempFile::new_in(parent).map_err(|error| error.to_string())?;
    file.write_all(&serde_json::to_vec_pretty(state).map_err(|error| error.to_string())?)
        .map_err(|error| error.to_string())?;
    file.as_file()
        .sync_all()
        .map_err(|error| error.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(file.path(), fs::Permissions::from_mode(0o600))
            .map_err(|error| error.to_string())?;
    }
    file.persist(path)
        .map_err(|error| error.error.to_string())?;
    Ok(())
}

fn random_token() -> Result<String, String> {
    let mut bytes = [0_u8; 16];
    getrandom::fill(&mut bytes).map_err(|error| error.to_string())?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

#[cfg(unix)]
fn mpv_ipc_path() -> Result<PathBuf, String> {
    let random = random_token()?;
    Ok(PathBuf::from(format!(
        "/tmp/pb-mpv-{}-{}.sock",
        std::process::id(),
        &random[..16]
    )))
}

fn host_name() -> String {
    std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .unwrap_or_else(|_| "PlayBridge CLI".into())
}

fn is_routine_handshake_rejection(connection_id: Option<u64>, message: &str) -> bool {
    connection_id.is_some()
        && matches!(
            message,
            "TLS handshake failed" | "TLS handshake timed out" | "WebSocket handshake failed"
        )
}

#[cfg(test)]
mod tests {
    #[test]
    fn mpv_seconds_are_reported_as_protocol_milliseconds() {
        assert_eq!(super::seconds_to_millis(Some(12.345)), 12_345);
        assert_eq!(super::seconds_to_millis(Some(f64::NAN)), 0);
        assert_eq!(super::seconds_to_millis(Some(-1.0)), 0);
        assert_eq!(super::seconds_to_millis(None), 0);
    }

    #[test]
    fn dashboard_ignores_only_per_connection_handshake_noise() {
        assert!(super::is_routine_handshake_rejection(
            Some(7),
            "TLS handshake failed"
        ));
        assert!(super::is_routine_handshake_rejection(
            Some(8),
            "TLS handshake timed out"
        ));
        assert!(super::is_routine_handshake_rejection(
            Some(9),
            "WebSocket handshake failed"
        ));
        assert!(!super::is_routine_handshake_rejection(
            None,
            "TLS handshake failed"
        ));
        assert!(!super::is_routine_handshake_rejection(
            Some(10),
            "receiver listener failed"
        ));
    }

    #[cfg(unix)]
    #[test]
    fn mpv_ipc_path_fits_unix_socket_limits() {
        let path = super::mpv_ipc_path().unwrap();
        let text = path.to_string_lossy();
        assert!(text.starts_with("/tmp/pb-mpv-"));
        // macOS sockaddr_un.sun_path is 104 bytes including the terminator.
        assert!(text.len() < 104, "IPC path is too long: {text}");
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn mpv_status_query_maps_ipc_properties_to_protocol_units() {
        use tokio::{
            io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
            net::UnixListener,
        };

        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("mpv.sock");
        let listener = UnixListener::bind(&path).unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let (reader, mut writer) = stream.into_split();
            let mut lines = BufReader::new(reader).lines();
            for (request_id, data) in [
                (1, serde_json::json!(12.5)),
                (2, serde_json::json!(100.0)),
                (3, serde_json::json!(false)),
                (4, serde_json::json!(false)),
                (5, serde_json::json!(75.0)),
                (6, serde_json::json!(true)),
                (7, serde_json::json!("inf")),
                (8, serde_json::json!(1.25)),
            ] {
                lines.next_line().await.unwrap().unwrap();
                writer
                    .write_all(
                        format!(
                            "{}\n",
                            serde_json::json!({
                                "request_id": request_id,
                                "error": "success",
                                "data": data
                            })
                        )
                        .as_bytes(),
                    )
                    .await
                    .unwrap();
            }
        });

        let snapshot = super::query_mpv_status(&path, Some("Video".into()))
            .await
            .unwrap();
        server.await.unwrap();
        assert_eq!(snapshot.state, "playing");
        assert_eq!(snapshot.position_ms, 12_500);
        assert_eq!(snapshot.duration_ms, 100_000);
        assert_eq!(snapshot.title.as_deref(), Some("Video"));
        assert_eq!(snapshot.volume, 75.0);
        assert!(snapshot.muted);
        assert!(snapshot.looping);
        assert_eq!(snapshot.speed, 1.25);
    }
}
