use std::{
    collections::HashSet,
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
    process::Stdio,
    sync::Arc,
    time::Duration,
};

use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use futures_util::{SinkExt, StreamExt};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use playbridge_cast_core::playbridge::{
    CredentialBundle, ReceiverFrame, ReceiverPairingSession, SenderFrame,
};
use rcgen::{CertificateParams, KeyPair};
use rustls::{
    ServerConfig,
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
#[cfg(not(unix))]
use tokio::process::ChildStdin;
use tokio::{
    io::AsyncWriteExt,
    net::{TcpListener, TcpStream},
    process::{Child, Command},
    sync::Mutex,
};
#[cfg(unix)]
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    net::{UnixStream, unix::OwnedWriteHalf},
    time::sleep,
};
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::{WebSocketStream, accept_async, tungstenite::Message};
use x509_parser::parse_x509_certificate;

const DEFAULT_PORT: u16 = 8765;

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
}

struct MpvSnapshot {
    state: String,
    position_ms: u64,
    duration_ms: u64,
    title: Option<String>,
}

impl Mpv {
    fn new() -> Self {
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
        if let Some(headers) = item.get("headers").and_then(Value::as_object) {
            let header_values = headers
                .iter()
                .filter_map(|(key, value)| value.as_str().map(|value| format!("{key}: {value}")))
                .collect::<Vec<_>>();
            if !header_values.is_empty() {
                let header_fields = header_values.join(",");
                let escaped = header_fields.replace('\\', "\\\\").replace('"', "\\\"");
                self.command(
                    vec![
                        json!("set_property"),
                        json!("http-header-fields"),
                        json!(header_values),
                    ],
                    format!("set http-header-fields \"{escaped}\""),
                )
                .await?;
            }
        }
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
    let mut lines = BufReader::new(reader).lines();
    for _ in 0..4 {
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
    })
}

fn seconds_to_millis(seconds: Option<f64>) -> u64 {
    seconds
        .filter(|value| value.is_finite() && *value > 0.0)
        .map(|value| (value * 1000.0).round() as u64)
        .unwrap_or(0)
}

pub async fn run_receiver(arguments: &[String]) -> Result<(), String> {
    let mut port = DEFAULT_PORT;
    let mut name = host_name();
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
                name = arguments
                    .get(index)
                    .ok_or("--name requires a value")?
                    .clone();
            }
            unknown => return Err(format!("unknown receiver option: {unknown}")),
        }
        index += 1;
    }

    ensure_mpv_available().await?;
    let (state_path, mut state) = load_or_create_state(name)?;
    let (tls, fingerprint) = tls_config(&state)?;
    let listener = bind_next_port(port).await?;
    let port = listener
        .local_addr()
        .map_err(|error| error.to_string())?
        .port();
    state.name = state.name.trim().to_owned();
    save_state(&state_path, &state)?;

    let mdns = advertise(&state, port)?;
    let state = Arc::new(Mutex::new(state));
    let mpv = Arc::new(Mutex::new(Mpv::new()));
    let acceptor = TlsAcceptor::from(Arc::new(tls));
    println!(
        "PlayBridge receiver \"{}\" is ready on port {port}.",
        state.lock().await.name
    );
    println!("Playback uses the installed mpv command. Press Ctrl+C to stop.");

    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let (stream, _) = accepted.map_err(|error| error.to_string())?;
                let acceptor = acceptor.clone();
                let state = state.clone();
                let mpv = mpv.clone();
                let fingerprint = fingerprint.clone();
                let state_path = state_path.clone();
                tokio::spawn(async move {
                    if let Err(error) = serve_connection(stream, acceptor, state, state_path, mpv, fingerprint).await {
                        eprintln!("receiver connection ended: {error}");
                    }
                });
            }
            _ = tokio::signal::ctrl_c() => {
                let _ = mdns.shutdown();
                mpv.lock().await.stop_process().await;
                return Ok(());
            }
        }
    }
}

async fn serve_connection(
    stream: TcpStream,
    acceptor: TlsAcceptor,
    state: Arc<Mutex<ReceiverState>>,
    state_path: PathBuf,
    mpv: Arc<Mutex<Mpv>>,
    fingerprint: String,
) -> Result<(), String> {
    let tls = acceptor
        .accept(stream)
        .await
        .map_err(|error| error.to_string())?;
    let mut socket = accept_async(tls).await.map_err(|error| error.to_string())?;
    let mut authenticated = false;
    let mut pairing: Option<ReceiverPairingSession> = None;
    let mut status_tick = tokio::time::interval(Duration::from_millis(500));
    status_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        let event = tokio::select! {
            _ = status_tick.tick(), if authenticated => {
                let player = mpv.lock().await;
                send_status(&mut socket, &player).await?;
                None
            }
            message = socket.next() => Some(message),
        };
        let Some(message) = event else {
            continue;
        };
        let Some(message) = message else {
            break;
        };
        let message = message.map_err(|error| error.to_string())?;
        let Message::Text(text) = message else {
            continue;
        };
        let frame: SenderFrame = serde_json::from_str(&text).map_err(|error| error.to_string())?;
        match frame {
            SenderFrame::Ping => send_json(&mut socket, &ReceiverFrame::Pong).await?,
            SenderFrame::Auth { token } => {
                authenticated = state.lock().await.tokens.contains(&token);
                send_value(
                    &mut socket,
                    json!({
                        "type": "auth_response",
                        "success": authenticated,
                        "certFingerprint": fingerprint,
                        "players": ["internal_mpv"]
                    }),
                )
                .await?;
            }
            SenderFrame::PairingCommit {
                commit,
                device_name,
                ..
            } if !authenticated => {
                let (session, challenge) =
                    ReceiverPairingSession::start(&commit).map_err(|error| error.to_string())?;
                println!("Pairing request from {device_name}.");
                pairing = Some(session);
                send_json(&mut socket, &challenge).await?;
            }
            SenderFrame::PairingReveal {
                sender_eph_pub,
                nonce_s,
            } if !authenticated => {
                let session = pairing
                    .as_mut()
                    .ok_or("pairing reveal arrived without a commit")?;
                let sas = session
                    .accept_reveal(&sender_eph_pub, &nonce_s)
                    .map_err(|error| error.to_string())?;
                println!("Pairing code: {sas}");
            }
            SenderFrame::PairingConfirmation { mac } if !authenticated => {
                let session = pairing
                    .as_ref()
                    .ok_or("pairing confirmation arrived without a reveal")?;
                let token = random_token()?;
                let credentials = CredentialBundle {
                    token: token.clone(),
                    cert_fingerprint: Some(fingerprint.clone()),
                    players: vec!["internal_mpv".into()],
                    browsers: vec![],
                };
                let approval = session
                    .approve(&mac, &credentials)
                    .map_err(|error| error.to_string())?;
                {
                    let mut stored = state.lock().await;
                    stored.tokens.insert(token);
                    save_state(&state_path, &stored)?;
                }
                authenticated = true;
                send_json(&mut socket, &approval).await?;
                println!("Sender paired.");
            }
            SenderFrame::Command { action, payload } if authenticated => {
                println!("Received PlayBridge command: {action}");
                if let Err(error) =
                    handle_command(&mut socket, &mpv, &action, payload.unwrap_or(Value::Null)).await
                {
                    eprintln!("Playback command failed: {error}");
                }
            }
            _ => {}
        }
    }
    Ok(())
}

async fn handle_command<S>(
    socket: &mut WebSocketStream<S>,
    mpv: &Arc<Mutex<Mpv>>,
    action: &str,
    payload: Value,
) -> Result<(), String>
where
    WebSocketStream<S>: SinkExt<Message> + Unpin,
{
    match action {
        "context_query" => {
            let player = mpv.lock().await;
            send_value(socket, json!({"type":"context","active": if player.state == "idle" {"idle"} else {"player"}})).await?;
            send_status(socket, &player).await?;
        }
        "playlist" => {
            let items = payload
                .get("items")
                .and_then(Value::as_array)
                .ok_or("playlist has no items")?;
            let index = payload
                .get("startIndex")
                .and_then(Value::as_u64)
                .unwrap_or(0) as usize;
            let item = items
                .get(index)
                .or_else(|| items.first())
                .ok_or("playlist is empty")?;
            let title = item
                .get("title")
                .and_then(Value::as_str)
                .unwrap_or("untitled media");
            println!(
                "Received playlist ({} item(s)); playing \"{title}\".",
                items.len()
            );
            let mut player = mpv.lock().await;
            player.load(item).await?;
            println!("Sent playback request to mpv.");
            send_status(socket, &player).await?;
        }
        "control" => {
            if let Some(command) = payload.get("command").and_then(Value::as_str) {
                let mut player = mpv.lock().await;
                player.control(command).await?;
                send_status(socket, &player).await?;
                if command == "stop" {
                    send_value(socket, json!({"type":"context","active":"idle"})).await?;
                }
            }
        }
        _ => {}
    }
    Ok(())
}

async fn send_status<S>(socket: &mut WebSocketStream<S>, player: &Mpv) -> Result<(), String>
where
    WebSocketStream<S>: SinkExt<Message> + Unpin,
{
    let snapshot = player.snapshot().await;
    send_value(
        socket,
        json!({
            "type": "status",
            "state": snapshot.state,
            "position": snapshot.position_ms,
            "duration": snapshot.duration_ms,
            "title": snapshot.title
        }),
    )
    .await
}

async fn send_json<S, T>(socket: &mut WebSocketStream<S>, value: &T) -> Result<(), String>
where
    WebSocketStream<S>: SinkExt<Message> + Unpin,
    T: Serialize,
{
    let text = serde_json::to_string(value).map_err(|error| error.to_string())?;
    socket
        .send(Message::Text(text.into()))
        .await
        .map_err(|_| "could not send receiver WebSocket frame".to_owned())
}

async fn send_value<S>(socket: &mut WebSocketStream<S>, value: Value) -> Result<(), String>
where
    WebSocketStream<S>: SinkExt<Message> + Unpin,
{
    send_json(socket, &value).await
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

async fn bind_next_port(start: u16) -> Result<TcpListener, String> {
    for offset in 0..10 {
        let port = start
            .checked_add(offset)
            .ok_or("receiver port range overflow")?;
        match TcpListener::bind(("0.0.0.0", port)).await {
            Ok(listener) => return Ok(listener),
            Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => continue,
            Err(error) => return Err(error.to_string()),
        }
    }
    Err(format!(
        "ports {start} through {} are unavailable",
        start.saturating_add(9)
    ))
}

fn advertise(state: &ReceiverState, port: u16) -> Result<ServiceDaemon, String> {
    let daemon = ServiceDaemon::new().map_err(|error| error.to_string())?;
    let properties = [
        ("uuid", state.uuid.as_str()),
        ("wss_port", &port.to_string()),
    ];
    let service = ServiceInfo::new(
        "_playbridge._tcp.local.",
        &state.name,
        &format!("{}.local.", state.uuid),
        "",
        port,
        &properties[..],
    )
    .map_err(|error| error.to_string())?
    .enable_addr_auto();
    daemon
        .register(service)
        .map_err(|error| error.to_string())?;
    Ok(daemon)
}

fn state_path() -> Result<PathBuf, String> {
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .ok_or("could not determine home directory")?;
    Ok(PathBuf::from(home).join(".config/playbridge/receiver.json"))
}

fn load_or_create_state(name: String) -> Result<(PathBuf, ReceiverState), String> {
    let path = state_path()?;
    if let Ok(contents) = fs::read_to_string(&path) {
        let mut state: ReceiverState =
            serde_json::from_str(&contents).map_err(|error| error.to_string())?;
        if !name.trim().is_empty() {
            state.name = name;
        }
        return Ok((path, state));
    }
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
    let mut options = OpenOptions::new();
    options.create(true).truncate(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path).map_err(|error| error.to_string())?;
    file.write_all(&serde_json::to_vec_pretty(state).map_err(|error| error.to_string())?)
        .map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))
            .map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn tls_config(state: &ReceiverState) -> Result<(ServerConfig, String), String> {
    let certificate = BASE64
        .decode(&state.certificate_der)
        .map_err(|error| error.to_string())?;
    let private_key = BASE64
        .decode(&state.private_key_der)
        .map_err(|error| error.to_string())?;
    let (_, parsed) = parse_x509_certificate(&certificate).map_err(|error| error.to_string())?;
    let fingerprint = format!(
        "sha256/{}",
        BASE64.encode(Sha256::digest(parsed.public_key().raw))
    );
    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let config = ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|error| error.to_string())?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(certificate)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(private_key)),
        )
        .map_err(|error| error.to_string())?;
    Ok((config, fingerprint))
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

#[cfg(test)]
mod tests {
    #[test]
    fn mpv_seconds_are_reported_as_protocol_milliseconds() {
        assert_eq!(super::seconds_to_millis(Some(12.345)), 12_345);
        assert_eq!(super::seconds_to_millis(Some(f64::NAN)), 0);
        assert_eq!(super::seconds_to_millis(Some(-1.0)), 0);
        assert_eq!(super::seconds_to_millis(None), 0);
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
    }
}
