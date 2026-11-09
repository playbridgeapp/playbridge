//! Runs an [`Engine`] against a room relay and a local [`Player`], with
//! reconnects. The player keeps being corrected while offline, from the last
//! known timeline and clock offset.

use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::{self, Message};
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};

use crate::engine::{Command, Engine, Event, Output, PlayerCommand};
use crate::frames::{ServerFrame, close};
use crate::sync::PlayerSnapshot;
use crate::{Error, Result};

const TICK: Duration = Duration::from_millis(200);
const MIN_BACKOFF: Duration = Duration::from_millis(500);
const MAX_BACKOFF: Duration = Duration::from_secs(15);

/// The platform's player. Calls come from the client task; keep them quick.
pub trait Player: Send {
    fn apply(&mut self, command: PlayerCommand);
    /// The player's current state, or `None` for a remote-only device.
    fn snapshot(&mut self) -> Option<PlayerSnapshot>;
    /// Returns `(item, duration)` once, after a load has finished.
    fn take_loaded(&mut self) -> Option<(String, Option<u64>)>;
}

/// Why the client stopped.
#[derive(Debug, PartialEq, Eq)]
pub enum Stop {
    Left,
    Unauthorized,
    RoomFull,
    Expired,
    /// The same member connected from somewhere else.
    Replaced,
}

type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;

pub async fn run<P: Player>(
    mut engine: Engine,
    mut player: P,
    mut commands: mpsc::UnboundedReceiver<Command>,
    events: mpsc::UnboundedSender<Event>,
) -> Result<Stop> {
    let epoch = Instant::now();
    let now = || epoch.elapsed().as_secs_f64() * 1000.0;
    let mut socket: Option<Socket> = None;
    let mut backoff = MIN_BACKOFF;
    let mut retry_at = Instant::now();
    let mut ticker = tokio::time::interval(TICK);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        if socket.is_none() && Instant::now() >= retry_at {
            match connect(&engine).await {
                Ok(ws) => {
                    socket = Some(ws);
                    backoff = MIN_BACKOFF;
                }
                Err(Connect::Fatal(stop)) => return Ok(stop),
                Err(Connect::Retry(slow)) => {
                    let wait = if slow {
                        backoff.max(Duration::from_secs(10))
                    } else {
                        backoff
                    };
                    retry_at = Instant::now() + wait;
                    backoff = (backoff * 2).min(MAX_BACKOFF);
                }
            }
        }

        let incoming = async {
            match socket.as_mut() {
                Some(ws) => ws.next().await,
                None => std::future::pending().await,
            }
        };

        let outputs = tokio::select! {
            message = incoming => match message {
                Some(Ok(Message::Text(text))) => match serde_json::from_str::<ServerFrame>(&text) {
                    Ok(frame) => engine.handle(frame, now()),
                    Err(_) => Vec::new(),
                },
                Some(Ok(Message::Close(frame))) => {
                    socket = None;
                    if let Some(stop) = frame.as_ref().and_then(fatal_close) {
                        return Ok(stop);
                    }
                    retry_at = Instant::now() + backoff;
                    engine.disconnected()
                }
                Some(Ok(_)) => Vec::new(),
                Some(Err(_)) | None => {
                    socket = None;
                    retry_at = Instant::now() + backoff;
                    engine.disconnected()
                }
            },
            command = commands.recv() => match command {
                Some(Command::Leave) | None => {
                    let outputs = engine.command(Command::Leave, now());
                    if let Some(ws) = socket.as_mut() {
                        flush(ws, &outputs).await;
                        let _ = ws.close(None).await;
                    }
                    return Ok(Stop::Left);
                }
                Some(command) => engine.command(command, now()),
            },
            _ = ticker.tick() => {
                let mut outputs = Vec::new();
                if let Some((item, duration)) = player.take_loaded() {
                    outputs.extend(engine.player_loaded(&item, duration, now()));
                }
                let snapshot = player.snapshot();
                outputs.extend(engine.tick(now(), snapshot));
                outputs
            }
        };

        for output in outputs {
            match output {
                Output::Send(frame) => {
                    if let Some(ws) = socket.as_mut() {
                        let text = serde_json::to_string(&frame).expect("frames serialize");
                        if ws.send(Message::text(text)).await.is_err() {
                            socket = None;
                            retry_at = Instant::now() + backoff;
                            for output in engine.disconnected() {
                                if let Output::Event(event) = output {
                                    let _ = events.send(event);
                                }
                            }
                        }
                    }
                }
                Output::Player(command) => player.apply(command),
                Output::Event(event) => {
                    let _ = events.send(event);
                }
            }
        }
    }
}

async fn flush(ws: &mut Socket, outputs: &[Output]) {
    for output in outputs {
        if let Output::Send(frame) = output {
            let text = serde_json::to_string(frame).expect("frames serialize");
            let _ = ws.send(Message::text(text)).await;
        }
    }
}

enum Connect {
    Fatal(Stop),
    /// Retry later; `true` when the relay asked us to slow down.
    Retry(bool),
}

async fn connect(engine: &Engine) -> std::result::Result<Socket, Connect> {
    let invite = engine.invite();
    let url = invite.socket_url(&engine.me());
    let mut request = url
        .as_str()
        .into_client_request()
        .map_err(|_| Connect::Fatal(Stop::Unauthorized))?;
    let token = invite.key.auth_token(&invite.room);
    request.headers_mut().insert(
        "authorization",
        HeaderValue::from_str(&format!("Bearer {token}"))
            .expect("base64url is a valid header value"),
    );
    match tokio_tungstenite::connect_async(request).await {
        Ok((ws, _)) => Ok(ws),
        Err(tungstenite::Error::Http(response)) => match response.status().as_u16() {
            401 => Err(Connect::Fatal(Stop::Unauthorized)),
            403 => Err(Connect::Fatal(Stop::RoomFull)),
            410 => Err(Connect::Fatal(Stop::Expired)),
            429 => Err(Connect::Retry(true)),
            _ => Err(Connect::Retry(false)),
        },
        Err(_) => Err(Connect::Retry(false)),
    }
}

fn fatal_close(frame: &CloseFrame) -> Option<Stop> {
    match u16::from(frame.code) {
        close::UNAUTHORIZED => Some(Stop::Unauthorized),
        close::ROOM_FULL => Some(Stop::RoomFull),
        close::EXPIRED => Some(Stop::Expired),
        close::REPLACED => Some(Stop::Replaced),
        _ => None,
    }
}

impl From<tungstenite::Error> for Error {
    fn from(error: tungstenite::Error) -> Self {
        Error::Network(error.to_string())
    }
}
