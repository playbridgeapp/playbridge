use std::fmt;
use std::time::Duration;

use tokio::sync::{mpsc, oneshot, watch};
use tokio::task::JoinHandle;
use tokio_rustls::client::TlsStream;

use super::error::AtvError;
use super::framed::AtvStream;
use super::identity::ClientIdentity;
use super::proto::{
    CLIENT_FEATURES, DIR_SHORT, KEY_POWER, KEY_VOLUME_DOWN, KEY_VOLUME_MUTE, KEY_VOLUME_UP,
    RemoteEvent, decode_remote, encode_app_link, encode_configure_reply, encode_key,
    encode_ping_response, encode_set_active, encode_text,
};
use super::tls::{AtvTimeouts, connect_control};

/// Volume, power, app, and IME state pushed by the TV.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AtvState {
    pub volume_level: Option<u32>,
    pub volume_max: Option<u32>,
    pub volume_muted: bool,
    pub current_app: Option<String>,
    pub is_on: bool,
    pub ime_counter: i32,
    pub field_counter: i32,
    pub ime_text: Option<String>,
    pub ime_label: Option<String>,
    pub model: Option<String>,
    pub vendor: Option<String>,
    /// Increments once for every volume message from the TV.
    pub volume_revision: u64,
}

enum Command {
    Key { key_code: u32, direction: u32 },
    Text(String),
    App(String),
    Shutdown,
}

struct Outbound {
    command: Command,
    done: oneshot::Sender<Result<u64, AtvError>>,
}

/// Open control connection. Drop aborts the reader task.
pub struct ControlSession {
    commands: mpsc::Sender<Outbound>,
    state: watch::Receiver<AtvState>,
    terminal: watch::Receiver<Option<AtvError>>,
    task: JoinHandle<()>,
}

impl fmt::Debug for ControlSession {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ControlSession")
            .finish_non_exhaustive()
    }
}

impl Drop for ControlSession {
    fn drop(&mut self) {
        self.task.abort();
    }
}

impl ControlSession {
    pub async fn connect(
        host: &str,
        port: u16,
        identity: &ClientIdentity,
        pin: [u8; 32],
    ) -> Result<Self, AtvError> {
        Self::connect_timeout(host, port, identity, pin, AtvTimeouts::default()).await
    }

    pub async fn connect_timeout(
        host: &str,
        port: u16,
        identity: &ClientIdentity,
        pin: [u8; 32],
        timeouts: AtvTimeouts,
    ) -> Result<Self, AtvError> {
        let stream = connect_control(host, port, identity, pin, timeouts).await?;
        let (commands_tx, commands_rx) = mpsc::channel(8);
        let (state_tx, state_rx) = watch::channel(AtvState::default());
        let (terminal_tx, terminal_rx) = watch::channel(None);
        let (ready_tx, ready_rx) = oneshot::channel();
        let task = tokio::spawn(async move {
            let error = session_loop(
                AtvStream::new(stream),
                commands_rx,
                state_tx,
                Some(ready_tx),
                timeouts,
            )
            .await;
            let _ = terminal_tx.send(Some(error));
        });
        match tokio::time::timeout(timeouts.handshake, ready_rx).await {
            Ok(Ok(Ok(()))) => Ok(Self {
                commands: commands_tx,
                state: state_rx,
                terminal: terminal_rx,
                task,
            }),
            Ok(Ok(Err(error))) => {
                task.abort();
                Err(error)
            }
            Ok(Err(_)) => {
                task.abort();
                Err(AtvError::Closed)
            }
            Err(_) => {
                task.abort();
                Err(AtvError::Timeout)
            }
        }
    }

    pub fn state(&self) -> AtvState {
        self.state.borrow().clone()
    }

    pub fn watch(&self) -> watch::Receiver<AtvState> {
        self.state.clone()
    }

    pub async fn until_closed(&mut self) -> AtvError {
        loop {
            if let Some(error) = self.terminal.borrow().clone() {
                return error;
            }
            if self.terminal.changed().await.is_err() {
                return AtvError::Closed;
            }
        }
    }

    pub async fn shutdown(&mut self) -> Result<(), AtvError> {
        let _ = self.enqueue(Command::Shutdown).await;
        match self.until_closed().await {
            AtvError::Closed => Ok(()),
            error => Err(error),
        }
    }

    pub async fn key(&self, key_code: u32, direction: u32) -> Result<(), AtvError> {
        self.send(Command::Key {
            key_code,
            direction,
        })
        .await
        .map(|_| ())
    }

    /// Sends a volume key and returns the volume revision observed when the write finished.
    pub async fn volume_up(&self) -> Result<u64, AtvError> {
        self.send(Command::Key {
            key_code: KEY_VOLUME_UP,
            direction: DIR_SHORT,
        })
        .await
    }

    /// Sends a volume key and returns the volume revision observed when the write finished.
    pub async fn volume_down(&self) -> Result<u64, AtvError> {
        self.send(Command::Key {
            key_code: KEY_VOLUME_DOWN,
            direction: DIR_SHORT,
        })
        .await
    }

    /// Sends a volume key and returns the volume revision observed when the write finished.
    pub async fn volume_mute(&self) -> Result<u64, AtvError> {
        self.send(Command::Key {
            key_code: KEY_VOLUME_MUTE,
            direction: DIR_SHORT,
        })
        .await
    }

    pub async fn power(&self) -> Result<(), AtvError> {
        self.key(KEY_POWER, DIR_SHORT).await
    }

    pub async fn insert_text(&self, text: &str) -> Result<(), AtvError> {
        if text.is_empty() {
            return Err(AtvError::Protocol("text must not be empty".to_owned()));
        }
        self.send(Command::Text(text.to_owned())).await.map(|_| ())
    }

    pub async fn launch_app(&self, uri: &str) -> Result<(), AtvError> {
        if uri.is_empty() {
            return Err(AtvError::Protocol("app link must not be empty".to_owned()));
        }
        self.send(Command::App(normalize_app_link(uri)))
            .await
            .map(|_| ())
    }

    /// Waits until a volume message newer than `seen` arrives, or until `limit` elapses.
    ///
    /// The boolean is true when a newer volume message was observed.
    pub async fn wait_for_volume_revision(
        &mut self,
        seen: u64,
        limit: Duration,
    ) -> (AtvState, bool) {
        let deadline = tokio::time::Instant::now() + limit;
        let mut watch = self.state.clone();
        loop {
            let state = watch.borrow().clone();
            if state.volume_revision != seen {
                return (state, true);
            }
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                return (state, false);
            }
            tokio::select! {
                changed = watch.changed() => {
                    if changed.is_err() {
                        let state = self.state();
                        return (state.clone(), state.volume_revision != seen);
                    }
                }
                _ = tokio::time::sleep(remaining) => {
                    let state = self.state();
                    let fresh = state.volume_revision != seen;
                    return (state, fresh);
                }
            }
        }
    }

    async fn send(&self, command: Command) -> Result<u64, AtvError> {
        let outbound = self.enqueue(command).await?;
        match outbound.await {
            Ok(result) => result,
            Err(_) => Err(self.terminal.borrow().clone().unwrap_or(AtvError::Closed)),
        }
    }

    async fn enqueue(
        &self,
        command: Command,
    ) -> Result<oneshot::Receiver<Result<u64, AtvError>>, AtvError> {
        if let Some(error) = self.terminal.borrow().clone() {
            return Err(error);
        }
        let (done, inbound) = oneshot::channel();
        self.commands
            .send(Outbound { command, done })
            .await
            .map_err(|_| self.terminal.borrow().clone().unwrap_or(AtvError::Closed))?;
        Ok(inbound)
    }
}

/// Package ids have no URI scheme. The reference launches those through the market URI.
pub(crate) fn normalize_app_link(value: &str) -> String {
    if value.contains("://") {
        value.to_owned()
    } else {
        format!("market://launch?id={value}")
    }
}

async fn session_loop(
    mut stream: AtvStream<TlsStream<tokio::net::TcpStream>>,
    mut commands: mpsc::Receiver<Outbound>,
    state_tx: watch::Sender<AtvState>,
    mut ready_tx: Option<oneshot::Sender<Result<(), AtvError>>>,
    timeouts: AtvTimeouts,
) -> AtvError {
    let mut state = AtvState::default();
    let mut active = 0_i32;
    let mut last = tokio::time::Instant::now();
    loop {
        while let Some(frame) = stream.next_frame() {
            if let Err(error) = handle_frame(
                &mut stream,
                &frame,
                &mut state,
                &mut active,
                &state_tx,
                &mut ready_tx,
                timeouts,
            )
            .await
            {
                fail_ready(&mut ready_tx, &error);
                return error;
            }
            last = tokio::time::Instant::now();
        }
        let idle_left = timeouts.idle.saturating_sub(last.elapsed());
        if idle_left.is_zero() {
            let error = AtvError::Closed;
            fail_ready(&mut ready_tx, &error);
            return error;
        }
        tokio::select! {
            biased;
            read = stream.read_some() => {
                if let Err(error) = read {
                    fail_ready(&mut ready_tx, &error);
                    return error;
                }
                last = tokio::time::Instant::now();
            }
            command = commands.recv() => {
                let Some(outbound) = command else {
                    let error = AtvError::Closed;
                    fail_ready(&mut ready_tx, &error);
                    return error;
                };
                if matches!(outbound.command, Command::Shutdown) {
                    let _ = outbound.done.send(Ok(state.volume_revision));
                    let error = AtvError::Closed;
                    fail_ready(&mut ready_tx, &error);
                    return error;
                }
                match write_command(&mut stream, &state, outbound.command, timeouts).await {
                    Ok(()) => {
                        let _ = outbound.done.send(Ok(state.volume_revision));
                        last = tokio::time::Instant::now();
                    }
                    Err(error) => {
                        let _ = outbound.done.send(Err(error.clone()));
                        fail_ready(&mut ready_tx, &error);
                        return error;
                    }
                }
            }
            _ = tokio::time::sleep(idle_left) => {
                let error = AtvError::Closed;
                fail_ready(&mut ready_tx, &error);
                return error;
            }
        }
    }
}

async fn handle_frame(
    stream: &mut AtvStream<TlsStream<tokio::net::TcpStream>>,
    frame: &[u8],
    state: &mut AtvState,
    active: &mut i32,
    state_tx: &watch::Sender<AtvState>,
    ready_tx: &mut Option<oneshot::Sender<Result<(), AtvError>>>,
    timeouts: AtvTimeouts,
) -> Result<(), AtvError> {
    for event in decode_remote(frame)? {
        match event {
            RemoteEvent::Configure {
                code1,
                model,
                vendor,
                ..
            } => {
                *active = code1 & CLIENT_FEATURES;
                state.model = model;
                state.vendor = vendor;
                stream
                    .write_frame(&encode_configure_reply(*active), timeouts.handshake)
                    .await?;
            }
            RemoteEvent::SetActive { .. } => {
                stream
                    .write_frame(&encode_set_active(*active), timeouts.handshake)
                    .await?;
            }
            RemoteEvent::Ping { val1 } => {
                stream
                    .write_frame(&encode_ping_response(val1), timeouts.handshake)
                    .await?;
            }
            RemoteEvent::Volume { level, max, muted } => {
                state.volume_level = Some(level);
                state.volume_max = Some(max);
                state.volume_muted = muted;
                state.volume_revision = state.volume_revision.saturating_add(1);
            }
            RemoteEvent::Start { started } => {
                state.is_on = started;
                let _ = state_tx.send(state.clone());
                if let Some(sender) = ready_tx.take() {
                    let _ = sender.send(Ok(()));
                }
            }
            RemoteEvent::Ime {
                package,
                text,
                ime_counter,
                field_counter,
            } => {
                if let Some(package) = package {
                    state.current_app = Some(package);
                }
                if let Some(text) = text {
                    if let Some(value) = text.value {
                        state.ime_text = Some(value);
                    }
                    if let Some(label) = text.label {
                        state.ime_label = Some(label);
                    }
                }
                if let Some(counter) = ime_counter {
                    state.ime_counter = counter;
                }
                if let Some(counter) = field_counter {
                    state.field_counter = counter;
                }
            }
            RemoteEvent::Error => {}
        }
        let _ = state_tx.send(state.clone());
    }
    Ok(())
}

async fn write_command(
    stream: &mut AtvStream<TlsStream<tokio::net::TcpStream>>,
    state: &AtvState,
    command: Command,
    timeouts: AtvTimeouts,
) -> Result<(), AtvError> {
    let payload = match command {
        Command::Key {
            key_code,
            direction,
        } => encode_key(key_code, direction),
        Command::Text(text) => encode_text(state.ime_counter, state.field_counter, &text),
        Command::App(uri) => encode_app_link(&uri),
        Command::Shutdown => return Ok(()),
    };
    stream.write_frame(&payload, timeouts.handshake).await
}

fn fail_ready(ready_tx: &mut Option<oneshot::Sender<Result<(), AtvError>>>, error: &AtvError) {
    if let Some(sender) = ready_tx.take() {
        let _ = sender.send(Err(error.clone()));
    }
}
