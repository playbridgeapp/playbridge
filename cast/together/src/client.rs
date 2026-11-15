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
/// Retries slow down to this; a relay that stays down for hours costs little.
const MAX_BACKOFF: Duration = Duration::from_secs(120);
/// What a relay that says "slow down" without saying how long gets.
const SLOW_DOWN: Duration = Duration::from_secs(10);
/// After a member sent too many messages and the room closed it.
const FLOODED: Duration = Duration::from_secs(30);
/// How long without a connection before apps hear [`Event::Unreachable`].
const UNREACHABLE_AFTER: Duration = Duration::from_secs(20);

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
    /// The creator's network has too many parties open or started too many this hour.
    RoomLimit {
        retry_after_secs: u64,
    },
    /// The relay is out of its daily budget; `retry_after_secs` is until it resets.
    RelayBusy {
        retry_after_secs: u64,
    },
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
    // When the client lost (or never had) its connection, and whether apps heard about it.
    let mut offline_since = Instant::now();
    let mut told_unreachable = false;
    let mut ticker = tokio::time::interval(TICK);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    loop {
        if socket.is_none() && Instant::now() >= retry_at {
            match connect(&engine).await {
                Ok(ws) => {
                    socket = Some(ws);
                    backoff = MIN_BACKOFF;
                    told_unreachable = false;
                }
                Err(Connect::Fatal(stop)) => return Ok(stop),
                Err(Connect::Retry(at_least)) => {
                    retry_at = Instant::now() + backoff.max(at_least.unwrap_or_default());
                    backoff = (backoff * 2).min(MAX_BACKOFF);
                }
            }
        }
        if socket.is_none() && !told_unreachable && offline_since.elapsed() >= UNREACHABLE_AFTER {
            told_unreachable = true;
            let _ = events.send(Event::Unreachable);
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
                    offline_since = Instant::now();
                    if let Some(stop) = frame.as_ref().and_then(fatal_close) {
                        return Ok(stop);
                    }
                    let flooded = frame.as_ref().is_some_and(|f| u16::from(f.code) == close::RATE_LIMITED);
                    retry_at = Instant::now() + if flooded { FLOODED } else { backoff };
                    engine.disconnected()
                }
                Some(Ok(_)) => Vec::new(),
                Some(Err(_)) | None => {
                    socket = None;
                    offline_since = Instant::now();
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
                            offline_since = Instant::now();
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
    /// Retry later, waiting at least this long when the relay said so.
    Retry(Option<Duration>),
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
    match crate::tls::connect(request).await {
        Ok((ws, _)) => Ok(ws),
        Err(tungstenite::Error::Http(response)) => Err(refused(
            response.status().as_u16(),
            &Refusal::read(response.headers(), response.body().as_deref()),
        )),
        Err(_) => Err(Connect::Retry(None)),
    }
}

/// What the relay said when it turned a connect down: `{error, retry_after}` and a
/// `Retry-After` header. Older relays send neither.
#[derive(Debug, Default, PartialEq)]
struct Refusal {
    error: Option<String>,
    retry_after_secs: Option<u64>,
}

impl Refusal {
    fn read(headers: &tungstenite::http::HeaderMap, body: Option<&[u8]>) -> Self {
        #[derive(serde::Deserialize)]
        struct Body {
            error: Option<String>,
            retry_after: Option<u64>,
        }
        let body = body.and_then(|b| serde_json::from_slice::<Body>(b).ok());
        let header = headers
            .get("retry-after")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse().ok());
        Refusal {
            retry_after_secs: header.or(body.as_ref().and_then(|b| b.retry_after)),
            error: body.and_then(|b| b.error),
        }
    }
}

fn refused(status: u16, refusal: &Refusal) -> Connect {
    let retry_after_secs = refusal.retry_after_secs.unwrap_or(0);
    let wait = refusal.retry_after_secs.map(Duration::from_secs);
    match (status, refusal.error.as_deref()) {
        (401, _) => Connect::Fatal(Stop::Unauthorized),
        (403, _) => Connect::Fatal(Stop::RoomFull),
        (410, _) => Connect::Fatal(Stop::Expired),
        (429, Some("room_limit")) => Connect::Fatal(Stop::RoomLimit { retry_after_secs }),
        (503, Some("relay_busy")) => Connect::Fatal(Stop::RelayBusy { retry_after_secs }),
        (429, _) => Connect::Retry(Some(wait.unwrap_or(SLOW_DOWN))),
        _ => Connect::Retry(wait),
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

#[cfg(test)]
mod tests {
    use super::*;
    use tungstenite::http::HeaderMap;

    fn refusal(retry_after: Option<&str>, body: &str) -> Refusal {
        let mut headers = HeaderMap::new();
        if let Some(value) = retry_after {
            headers.insert("retry-after", value.parse().unwrap());
        }
        Refusal::read(&headers, Some(body.as_bytes()))
    }

    fn wait(connect: Connect) -> Option<Duration> {
        match connect {
            Connect::Retry(wait) => wait,
            Connect::Fatal(stop) => panic!("stopped: {stop:?}"),
        }
    }

    #[test]
    fn reads_the_relays_refusal() {
        assert_eq!(
            refusal(Some("90"), r#"{"error":"room_limit","retry_after":90}"#),
            Refusal {
                error: Some("room_limit".into()),
                retry_after_secs: Some(90)
            }
        );
        assert_eq!(
            refusal(None, r#"{"error":"x","retry_after":5}"#).retry_after_secs,
            Some(5)
        );
        assert_eq!(refusal(None, "<html>"), Refusal::default());
    }

    #[test]
    fn stops_on_limits_and_retries_when_told_to_slow_down() {
        let limit = refused(429, &refusal(Some("600"), r#"{"error":"room_limit"}"#));
        assert!(matches!(
            limit,
            Connect::Fatal(Stop::RoomLimit {
                retry_after_secs: 600
            })
        ));
        let busy = refused(503, &refusal(Some("3600"), r#"{"error":"relay_busy"}"#));
        assert!(matches!(
            busy,
            Connect::Fatal(Stop::RelayBusy {
                retry_after_secs: 3600
            })
        ));

        assert_eq!(
            wait(refused(
                429,
                &refusal(Some("60"), r#"{"error":"rate_limited"}"#)
            )),
            Some(Duration::from_secs(60))
        );
        // An older relay's bare 429 still slows the client down.
        assert_eq!(wait(refused(429, &Refusal::default())), Some(SLOW_DOWN));
        assert_eq!(wait(refused(503, &Refusal::default())), None);
        assert_eq!(
            wait(refused(502, &refusal(Some("30"), ""))),
            Some(Duration::from_secs(30))
        );
        assert!(matches!(
            refused(403, &Refusal::default()),
            Connect::Fatal(Stop::RoomFull)
        ));
    }
}
