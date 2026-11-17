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
/// Short one-shot connect/close budgets on Leave so a stale relay socket is
/// replaced when online while offline devices still finish Leave quickly.
const LEAVE_CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const LEAVE_CLOSE_TIMEOUT: Duration = Duration::from_secs(1);
/// Keep-alive text a relay that advertises `keepalive_secs` answers by itself, without
/// waking the room. It tells the relay this device is still there, and the answer tells
/// this device its connection still works.
const KEEPALIVE_PING: &str = "ping";
const KEEPALIVE_PONG: &str = "pong";
/// The connection counts as dead after this many keep-alive periods with nothing received.
const DEAD_AFTER_PERIODS: u32 = 3;

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
    // Set by the relay's welcome; relays that do not advertise it get no keep-alives.
    let mut keepalive: Option<Duration> = None;
    let mut next_keepalive = Instant::now();
    let mut last_received = Instant::now();

    loop {
        if socket.is_none() && Instant::now() >= retry_at {
            tokio::select! {
                result = connect(&engine) => match result {
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
                },
                command = commands.recv() => match command {
                    Some(Command::Leave) | None => {
                        let outputs = engine.command(Command::Leave, now());
                        send_leave_and_close(None, &engine, &outputs).await;
                        return Ok(Stop::Left);
                    }
                    Some(command) => {
                        for output in engine.command(command, now()) {
                            match output {
                                Output::Player(cmd) => player.apply(cmd),
                                Output::Event(event) => {
                                    let _ = events.send(event);
                                }
                                Output::Send(_) => {}
                            }
                        }
                    }
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
                Some(Ok(Message::Text(text))) => {
                    last_received = Instant::now();
                    if text.as_str() == KEEPALIVE_PONG {
                        Vec::new()
                    } else {
                        match serde_json::from_str::<ServerFrame>(&text) {
                            Ok(frame) => {
                                if matches!(frame, ServerFrame::Welcome { .. }) {
                                    keepalive = keepalive_period(&text);
                                    next_keepalive = Instant::now() + keepalive.unwrap_or_default();
                                }
                                engine.handle(frame, now())
                            }
                            Err(_) => Vec::new(),
                        }
                    }
                }
                Some(Ok(Message::Close(frame))) => {
                    socket = None;
                    keepalive = None;
                    offline_since = Instant::now();
                    if let Some(stop) = frame.as_ref().and_then(fatal_close) {
                        return Ok(stop);
                    }
                    let flooded = frame.as_ref().is_some_and(|f| u16::from(f.code) == close::RATE_LIMITED);
                    retry_at = Instant::now() + if flooded { FLOODED } else { backoff };
                    engine.disconnected()
                }
                Some(Ok(_)) => {
                    last_received = Instant::now();
                    Vec::new()
                }
                Some(Err(_)) | None => {
                    socket = None;
                    keepalive = None;
                    offline_since = Instant::now();
                    retry_at = Instant::now() + backoff;
                    engine.disconnected()
                }
            },
            command = commands.recv() => match command {
                Some(Command::Leave) | None => {
                    let outputs = engine.command(Command::Leave, now());
                    send_leave_and_close(socket.take(), &engine, &outputs).await;
                    return Ok(Stop::Left);
                }
                Some(command) => engine.command(command, now()),
            },
            _ = ticker.tick() => {
                let mut outputs = Vec::new();
                if let (Some(every), Some(ws)) = (keepalive, socket.as_mut()) {
                    let dead = if last_received.elapsed() >= every * DEAD_AFTER_PERIODS {
                        true
                    } else if Instant::now() >= next_keepalive {
                        next_keepalive = Instant::now() + every;
                        ws.send(Message::text(KEEPALIVE_PING)).await.is_err()
                    } else {
                        false
                    };
                    if dead {
                        // Nothing came back: the connection is gone without a close.
                        socket = None;
                        keepalive = None;
                        offline_since = Instant::now();
                        retry_at = Instant::now();
                        outputs.extend(engine.disconnected());
                    }
                }
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
                            keepalive = None;
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

/// How often the relay wants a keep-alive, from its welcome; `None` for relays that do
/// not answer them (an older relay would treat the text as a bad frame).
fn keepalive_period(welcome: &str) -> Option<Duration> {
    #[derive(serde::Deserialize)]
    struct Hint {
        keepalive_secs: Option<u64>,
    }
    let secs = serde_json::from_str::<Hint>(welcome).ok()?.keepalive_secs?;
    (secs > 0).then(|| Duration::from_secs(secs.min(300)))
}

async fn send_leave_and_close(socket: Option<Socket>, engine: &Engine, outputs: &[Output]) {
    let ws = match socket {
        Some(ws) => Some(ws),
        None => match tokio::time::timeout(LEAVE_CONNECT_TIMEOUT, connect(engine)).await {
            Ok(Ok(ws)) => Some(ws),
            _ => None,
        },
    };
    if let Some(mut ws) = ws {
        let _ = tokio::time::timeout(LEAVE_CLOSE_TIMEOUT, async {
            flush(&mut ws, outputs).await;
            let _ = ws.close(None).await;
        })
        .await;
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

    struct NullPlayer;
    impl Player for NullPlayer {
        fn apply(&mut self, _: PlayerCommand) {}
        fn snapshot(&mut self) -> Option<PlayerSnapshot> {
            None
        }
        fn take_loaded(&mut self) -> Option<(String, Option<u64>)> {
            None
        }
    }

    #[tokio::test]
    async fn leave_while_disconnected_reconnects_once_and_closes_when_reachable() {
        use crate::engine::EngineConfig;
        use crate::messages::DeviceKind;
        use crate::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
        use tokio::net::TcpListener;
        use tokio_tungstenite::accept_async;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (closed_tx, closed_rx) = tokio::sync::oneshot::channel::<()>();

        let server = tokio::spawn(async move {
            // 1. First connection: send Welcome, then drop without fatal close so client enters backoff.
            let (stream1, _) = listener.accept().await.unwrap();
            let mut ws1 = accept_async(stream1).await.unwrap();
            let welcome = serde_json::to_string(&ServerFrame::Welcome {
                member: MemberId::from_bytes([1; 16]),
                members: vec![MemberId::from_bytes([1; 16])],
                room_time: 1_000.0,
                expires_at: 60_000.0,
                max_members: 4,
                state: None,
            })
            .unwrap();
            ws1.send(Message::text(welcome)).await.unwrap();
            // Brief wait so client reads Welcome, then close abruptly with non-fatal 1012 Service Restart.
            tokio::time::sleep(Duration::from_millis(50)).await;
            let _ = ws1
                .close(Some(CloseFrame {
                    code:
                        tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode::Restart,
                    reason: "restart".into(),
                }))
                .await;

            // 2. While client is in backoff (socket == None) and Leave arrives, expect one reconnect + clean close.
            let (stream2, _) = tokio::time::timeout(Duration::from_secs(3), listener.accept())
                .await
                .expect("expected client to reconnect once on Leave")
                .unwrap();
            let mut ws2 = accept_async(stream2).await.unwrap();
            while let Some(msg) = ws2.next().await {
                if let Ok(Message::Close(_)) = msg {
                    let _ = closed_tx.send(());
                    return;
                }
            }
            let _ = closed_tx.send(());
        });

        let keys = MemberKeys::generate();
        let me = MemberId::from_bytes([1; 16]);
        let invite = Invite {
            relay: url::Url::parse(&format!("http://{addr}")).unwrap(),
            room: RoomId::generate(),
            key: RoomKey::generate(),
            host: keys.signing_public(),
        };
        let engine = Engine::new(EngineConfig {
            invite,
            me,
            keys,
            name: "Phone".into(),
            kind: DeviceKind::Phone,
            screen: false,
            seq_start: 1,
        });
        let (tx, rx) = mpsc::unbounded_channel();
        let (ev_tx, mut ev_rx) = mpsc::unbounded_channel();
        let client_task = tokio::spawn(run(engine, NullPlayer, rx, ev_tx));

        // Wait for Disconnected (socket becomes None, client in MIN_BACKOFF=500ms wait).
        while let Some(ev) = ev_rx.recv().await {
            if matches!(ev, Event::Disconnected) {
                break;
            }
        }
        tx.send(Command::Leave).unwrap();

        let stop = tokio::time::timeout(Duration::from_secs(3), client_task)
            .await
            .expect("client must return quickly on Leave")
            .unwrap()
            .unwrap();
        assert_eq!(stop, Stop::Left);
        tokio::time::timeout(Duration::from_secs(2), closed_rx)
            .await
            .expect("server must receive clean close on Leave reconnect")
            .unwrap();
        server.await.unwrap();
    }

    #[tokio::test]
    async fn leave_while_offline_always_returns_quickly() {
        use crate::engine::EngineConfig;
        use crate::messages::DeviceKind;
        use crate::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
        use tokio::net::TcpListener;

        // Bind a port then drop or accept-without-reply to simulate unreachable offline relay.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);

        let keys = MemberKeys::generate();
        let me = MemberId::generate();
        let invite = Invite {
            relay: url::Url::parse(&format!("http://{addr}")).unwrap(),
            room: RoomId::generate(),
            key: RoomKey::generate(),
            host: keys.signing_public(),
        };
        let engine = Engine::new(EngineConfig {
            invite,
            me,
            keys,
            name: "Phone".into(),
            kind: DeviceKind::Phone,
            screen: false,
            seq_start: 1,
        });
        let (tx, rx) = mpsc::unbounded_channel();
        let (ev_tx, _ev_rx) = mpsc::unbounded_channel();
        let client_task = tokio::spawn(run(engine, NullPlayer, rx, ev_tx));

        tokio::time::sleep(Duration::from_millis(50)).await;
        let started = Instant::now();
        tx.send(Command::Leave).unwrap();
        let stop = tokio::time::timeout(Duration::from_secs(3), client_task)
            .await
            .expect("Leave when offline must always return quickly")
            .unwrap()
            .unwrap();
        assert_eq!(stop, Stop::Left);
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[tokio::test]
    async fn leave_while_stalled_handshake_returns_within_leave_timeout() {
        use crate::engine::EngineConfig;
        use crate::messages::DeviceKind;
        use crate::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
        use tokio::net::TcpListener;

        // Listener accepts TCP sockets but never completes the HTTP/WebSocket upgrade.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let hold = tokio::spawn(async move {
            let mut held = Vec::new();
            while let Ok((stream, _)) = listener.accept().await {
                held.push(stream);
            }
        });

        let keys = MemberKeys::generate();
        let me = MemberId::generate();
        let invite = Invite {
            relay: url::Url::parse(&format!("http://{addr}")).unwrap(),
            room: RoomId::generate(),
            key: RoomKey::generate(),
            host: keys.signing_public(),
        };
        let engine = Engine::new(EngineConfig {
            invite,
            me,
            keys,
            name: "Phone".into(),
            kind: DeviceKind::Phone,
            screen: false,
            seq_start: 1,
        });
        let (tx, rx) = mpsc::unbounded_channel();
        let (ev_tx, _ev_rx) = mpsc::unbounded_channel();
        let client_task = tokio::spawn(run(engine, NullPlayer, rx, ev_tx));

        tokio::time::sleep(Duration::from_millis(50)).await;
        let started = Instant::now();
        tx.send(Command::Leave).unwrap();
        let stop = tokio::time::timeout(Duration::from_secs(4), client_task)
            .await
            .expect("stalled Leave must return within bounded timeout")
            .unwrap()
            .unwrap();
        assert_eq!(stop, Stop::Left);
        assert!(started.elapsed() < Duration::from_secs(3));
        hold.abort();
    }

    #[tokio::test]
    async fn send_failure_preserves_clear_and_republishes_on_reconnect() {
        use crate::engine::EngineConfig;
        use crate::frames::{ClientFrame, StateFrame};
        use crate::messages::{DeviceKind, SharedItem, Source};
        use crate::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
        use std::collections::BTreeMap;
        use tokio::net::TcpListener;
        use tokio_tungstenite::accept_async;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (republished_tx, republished_rx) = tokio::sync::oneshot::channel::<bool>();

        let server = tokio::spawn(async move {
            // 1. Accept first connection and deliver Welcome; keep socket only briefly until Load State arrives.
            let (stream1, _) = listener.accept().await.unwrap();
            let mut ws1 = accept_async(stream1).await.unwrap();
            let host_id = MemberId::from_bytes([1; 16]);
            let welcome = serde_json::to_string(&ServerFrame::Welcome {
                member: host_id,
                members: vec![host_id],
                room_time: 1_000.0,
                expires_at: 60_000.0,
                max_members: 4,
                state: None,
            })
            .unwrap();
            ws1.send(Message::text(welcome)).await.unwrap();

            // Capture the initial Load State frame, then close abruptly so Clear arrives on/after disconnect.
            let mut initial_state = None;
            while let Some(Ok(Message::Text(txt))) = ws1.next().await {
                if let Ok(ClientFrame::State { seq, ct }) =
                    serde_json::from_str::<ClientFrame>(&txt)
                {
                    initial_state = Some(StateFrame {
                        from: host_id,
                        seq,
                        ct,
                    });
                    break;
                }
            }
            drop(ws1);

            // 2. On reconnect, hand back the stale pre-Clear State in Welcome and verify client re-sends State.
            let (stream2, _) = tokio::time::timeout(Duration::from_secs(3), listener.accept())
                .await
                .expect("client must reconnect after drop")
                .unwrap();
            let mut ws2 = accept_async(stream2).await.unwrap();
            let welcome2 = serde_json::to_string(&ServerFrame::Welcome {
                member: host_id,
                members: vec![host_id],
                room_time: 2_000.0,
                expires_at: 60_000.0,
                max_members: 4,
                state: initial_state,
            })
            .unwrap();
            ws2.send(Message::text(welcome2)).await.unwrap();

            let mut saw_state = false;
            let deadline = Instant::now() + Duration::from_secs(2);
            while Instant::now() < deadline {
                if let Ok(Some(Ok(Message::Text(txt)))) =
                    tokio::time::timeout(Duration::from_millis(200), ws2.next()).await
                    && matches!(
                        serde_json::from_str::<ClientFrame>(&txt),
                        Ok(ClientFrame::State { .. })
                    )
                {
                    saw_state = true;
                    break;
                }
            }
            let _ = republished_tx.send(saw_state);
        });

        let keys = MemberKeys::generate();
        let me = MemberId::from_bytes([1; 16]);
        let invite = Invite {
            relay: url::Url::parse(&format!("http://{addr}")).unwrap(),
            room: RoomId::generate(),
            key: RoomKey::generate(),
            host: keys.signing_public(),
        };
        let engine = Engine::new(EngineConfig {
            invite,
            me,
            keys,
            name: "Host".into(),
            kind: DeviceKind::Phone,
            screen: false,
            seq_start: 1,
        });
        let (tx, rx) = mpsc::unbounded_channel();
        let (ev_tx, mut ev_rx) = mpsc::unbounded_channel();
        let client_task = tokio::spawn(run(engine, NullPlayer, rx, ev_tx));

        while let Some(ev) = ev_rx.recv().await {
            if matches!(ev, Event::Connected { .. }) {
                break;
            }
        }
        tx.send(Command::Load(SharedItem {
            id: "v1".into(),
            title: "Video".into(),
            site: None,
            source: Source::Url {
                url: "https://example.com/v.mp4".into(),
            },
            headers: BTreeMap::new(),
            subtitles: Vec::new(),
            start_ms: 0,
            shared_by: me,
        }))
        .unwrap();

        // Wait until first socket drops (Disconnect) then immediately send Clear while offline/failing.
        while let Some(ev) = ev_rx.recv().await {
            if matches!(ev, Event::Disconnected) {
                break;
            }
        }
        tx.send(Command::Clear).unwrap();

        let saw_state = tokio::time::timeout(Duration::from_secs(5), republished_rx)
            .await
            .expect("timeout waiting for republished state")
            .unwrap();
        assert!(
            saw_state,
            "reconnected client must republish State for offline/failed Clear"
        );

        tx.send(Command::Leave).unwrap();
        let _ = client_task.await;
        server.await.unwrap();
    }

    #[tokio::test]
    async fn half_open_send_followed_by_read_error_republishes_on_reconnect() {
        use crate::engine::EngineConfig;
        use crate::frames::{ClientFrame, StateFrame};
        use crate::messages::{DeviceKind, SharedItem, Source};
        use crate::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
        use std::collections::BTreeMap;
        use tokio::net::TcpListener;
        use tokio_tungstenite::accept_async;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (loaded_tx, loaded_rx) = tokio::sync::oneshot::channel::<()>();
        let (republished_tx, republished_rx) = tokio::sync::oneshot::channel::<bool>();

        let server = tokio::spawn(async move {
            let (stream1, _) = listener.accept().await.unwrap();
            let mut ws1 = accept_async(stream1).await.unwrap();
            let host_id = MemberId::from_bytes([1; 16]);
            let welcome = serde_json::to_string(&ServerFrame::Welcome {
                member: host_id,
                members: vec![host_id],
                room_time: 1_000.0,
                expires_at: 60_000.0,
                max_members: 4,
                state: None,
            })
            .unwrap();
            ws1.send(Message::text(welcome)).await.unwrap();

            let mut initial_state = None;
            while let Some(Ok(Message::Text(txt))) = ws1.next().await {
                if let Ok(ClientFrame::State { seq, ct }) =
                    serde_json::from_str::<ClientFrame>(&txt)
                {
                    initial_state = Some(StateFrame {
                        from: host_id,
                        seq,
                        ct,
                    });
                    break;
                }
            }
            let _ = loaded_tx.send(());
            // Give the connected client time to issue Clear into the OS TCP buffer, then drop
            // the server socket without reading Clear (half-open write followed by read drop).
            tokio::time::sleep(Duration::from_millis(60)).await;
            drop(ws1);

            let (stream2, _) = tokio::time::timeout(Duration::from_secs(3), listener.accept())
                .await
                .expect("client must reconnect after read drop")
                .unwrap();
            let mut ws2 = accept_async(stream2).await.unwrap();
            let welcome2 = serde_json::to_string(&ServerFrame::Welcome {
                member: host_id,
                members: vec![host_id],
                room_time: 2_000.0,
                expires_at: 60_000.0,
                max_members: 4,
                state: initial_state,
            })
            .unwrap();
            ws2.send(Message::text(welcome2)).await.unwrap();

            let mut saw_state = false;
            let deadline = Instant::now() + Duration::from_secs(2);
            while Instant::now() < deadline {
                if let Ok(Some(Ok(Message::Text(txt)))) =
                    tokio::time::timeout(Duration::from_millis(200), ws2.next()).await
                    && matches!(
                        serde_json::from_str::<ClientFrame>(&txt),
                        Ok(ClientFrame::State { .. })
                    )
                {
                    saw_state = true;
                    break;
                }
            }
            let _ = republished_tx.send(saw_state);
        });

        let keys = MemberKeys::generate();
        let me = MemberId::from_bytes([1; 16]);
        let invite = Invite {
            relay: url::Url::parse(&format!("http://{addr}")).unwrap(),
            room: RoomId::generate(),
            key: RoomKey::generate(),
            host: keys.signing_public(),
        };
        let engine = Engine::new(EngineConfig {
            invite,
            me,
            keys,
            name: "Host".into(),
            kind: DeviceKind::Phone,
            screen: false,
            seq_start: 1,
        });
        let (tx, rx) = mpsc::unbounded_channel();
        let (ev_tx, mut ev_rx) = mpsc::unbounded_channel();
        let client_task = tokio::spawn(run(engine, NullPlayer, rx, ev_tx));

        while let Some(ev) = ev_rx.recv().await {
            if matches!(ev, Event::Connected { .. }) {
                break;
            }
        }
        tx.send(Command::Load(SharedItem {
            id: "v1".into(),
            title: "Video".into(),
            site: None,
            source: Source::Url {
                url: "https://example.com/v.mp4".into(),
            },
            headers: BTreeMap::new(),
            subtitles: Vec::new(),
            start_ms: 0,
            shared_by: me,
        }))
        .unwrap();

        loaded_rx.await.unwrap();
        // Send Clear WHILE STILL CONNECTED before the read side sees the server drop.
        tx.send(Command::Clear).unwrap();

        let saw_state = tokio::time::timeout(Duration::from_secs(5), republished_rx)
            .await
            .expect("timeout waiting for republished state")
            .unwrap();
        assert!(
            saw_state,
            "reconnected client must republish State when half-open Clear was followed by read drop"
        );

        tx.send(Command::Leave).unwrap();
        let _ = client_task.await;
        server.await.unwrap();
    }

    fn welcome_json(keepalive_secs: Option<u64>) -> String {
        let mut welcome = serde_json::to_value(ServerFrame::Welcome {
            member: crate::MemberId::from_bytes([1; 16]),
            members: vec![crate::MemberId::from_bytes([1; 16])],
            room_time: 1_000.0,
            expires_at: 60_000.0,
            max_members: 4,
            state: None,
        })
        .unwrap();
        if let Some(secs) = keepalive_secs {
            welcome["keepalive_secs"] = secs.into();
        }
        welcome.to_string()
    }

    fn keepalive_test_engine(addr: std::net::SocketAddr) -> Engine {
        use crate::engine::EngineConfig;
        use crate::messages::DeviceKind;
        use crate::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
        let keys = MemberKeys::generate();
        Engine::new(EngineConfig {
            invite: Invite {
                relay: url::Url::parse(&format!("http://{addr}")).unwrap(),
                room: RoomId::generate(),
                key: RoomKey::generate(),
                host: keys.signing_public(),
            },
            me: MemberId::from_bytes([1; 16]),
            keys,
            name: "Phone".into(),
            kind: DeviceKind::Phone,
            screen: false,
            seq_start: 1,
        })
    }

    #[test]
    fn keepalive_period_comes_only_from_a_relay_that_advertises_it() {
        assert_eq!(keepalive_period(&welcome_json(None)), None);
        assert_eq!(keepalive_period(&welcome_json(Some(0))), None);
        assert_eq!(
            keepalive_period(&welcome_json(Some(25))),
            Some(Duration::from_secs(25))
        );
        assert_eq!(
            keepalive_period(&welcome_json(Some(100_000))),
            Some(Duration::from_secs(300))
        );
    }

    #[tokio::test]
    async fn no_keepalive_is_sent_to_a_relay_that_does_not_advertise_it() {
        use tokio::net::TcpListener;
        use tokio_tungstenite::accept_async;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();
            ws.send(Message::text(welcome_json(None))).await.unwrap();
            let mut texts = Vec::new();
            let until = tokio::time::Instant::now() + Duration::from_millis(1_500);
            while let Ok(Some(Ok(message))) = tokio::time::timeout_at(until, ws.next()).await {
                if let Message::Text(text) = message {
                    texts.push(text.to_string());
                }
            }
            texts
        });

        let (tx, rx) = mpsc::unbounded_channel();
        let (ev_tx, _ev_rx) = mpsc::unbounded_channel();
        let client = tokio::spawn(run(keepalive_test_engine(addr), NullPlayer, rx, ev_tx));
        let texts = server.await.unwrap();
        assert!(
            !texts.is_empty(),
            "the client says hello and pings its clock"
        );
        assert!(
            texts.iter().all(|t| t.starts_with('{')),
            "only JSON frames reach an old relay: {texts:?}"
        );
        tx.send(Command::Leave).unwrap();
        let _ = tokio::time::timeout(Duration::from_secs(5), client).await;
    }

    #[tokio::test]
    async fn keepalives_flow_and_a_silent_relay_is_dropped_and_reconnected() {
        use tokio::net::TcpListener;
        use tokio_tungstenite::accept_async;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            // First connection: answer two keep-alives, then go silent without closing.
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();
            ws.send(Message::text(welcome_json(Some(1)))).await.unwrap();
            let mut pings = 0;
            while pings < 2 {
                match ws.next().await {
                    Some(Ok(Message::Text(text))) if text.as_str() == KEEPALIVE_PING => {
                        pings += 1;
                        ws.send(Message::text(KEEPALIVE_PONG)).await.unwrap();
                    }
                    Some(Ok(_)) => {}
                    other => panic!("first connection ended early: {other:?}"),
                }
            }
            let went_silent = tokio::time::Instant::now();
            // Keep the socket open and unread: the client must give up on its own.
            let (stream2, _) = tokio::time::timeout(Duration::from_secs(8), listener.accept())
                .await
                .expect("the client reconnects after missing its keep-alive answers")
                .unwrap();
            let waited = went_silent.elapsed();
            let mut ws2 = accept_async(stream2).await.unwrap();
            ws2.send(Message::text(welcome_json(Some(1))))
                .await
                .unwrap();
            drop(ws);
            (waited, ws2)
        });

        let (tx, rx) = mpsc::unbounded_channel();
        let (ev_tx, mut ev_rx) = mpsc::unbounded_channel();
        let client = tokio::spawn(run(keepalive_test_engine(addr), NullPlayer, rx, ev_tx));
        let (waited, _ws2) = server.await.unwrap();
        // Three one-second periods with nothing received, not sooner.
        assert!(
            waited >= Duration::from_millis(2_500),
            "gave up too early: {waited:?}"
        );

        let mut saw_disconnect = false;
        let mut connects = 0;
        while let Ok(Some(event)) = tokio::time::timeout(Duration::from_secs(3), ev_rx.recv()).await
        {
            match event {
                Event::Disconnected => saw_disconnect = true,
                Event::Connected { .. } => {
                    connects += 1;
                    if connects == 2 {
                        break;
                    }
                }
                _ => {}
            }
        }
        assert!(saw_disconnect, "apps hear about the dead connection");
        assert_eq!(connects, 2, "and about the reconnect");
        tx.send(Command::Leave).unwrap();
        let _ = tokio::time::timeout(Duration::from_secs(5), client).await;
    }
}
