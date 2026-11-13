//! Watch Together for the apps (`docs/design-watch-together.md`).
//!
//! One handle runs at most one room and one receiver pairing session. Apps
//! drive it with JSON commands and read JSON events, like the other handles in
//! this crate. The app's player is driven through `player` events and reports
//! back with `snapshot` and `loaded` commands.
//!
//! Room keys and shared sign-ins stay in this handle's memory and are dropped
//! when the room ends. Errors never quote invite links or headers.

use std::collections::BTreeMap;
use std::ffi::{CStr, CString, c_char};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
    mpsc::{self, Receiver, Sender},
};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use playbridge_together::b64;
use playbridge_together::client::{self, Player, Stop};
use playbridge_together::engine::{
    Command as RoomCommand, Engine, EngineConfig, Event, PlayerCommand,
};
use playbridge_together::messages::{ChangePolicy, DeviceKind, SharedItem, Source};
use playbridge_together::pairing::{Lobby, PairingInvite, PairingService, QrPayload};
use playbridge_together::sync::PlayerSnapshot;
use playbridge_together::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::{mpsc as async_mpsc, watch};
use tokio::task::JoinHandle;

const ABI_VERSION: u32 = 1;
const MAX_COMMAND_BYTES: usize = 256 * 1024;

#[derive(Deserialize)]
#[serde(tag = "command", rename_all = "snake_case")]
enum Call {
    /// Create a room on `relay` and join it as its host.
    Host {
        relay: String,
        #[serde(flatten)]
        device: DeviceInput,
    },
    /// Join the room in an invite link.
    Join {
        invite: String,
        #[serde(flatten)]
        device: DeviceInput,
    },
    /// Receiver: show a code and wait for an invite.
    PairRegister {
        pairing: String,
    },
    /// Receiver: join the invite that arrived, with the key it was sealed to.
    /// A `guest` joins with a fresh key, so the sender doesn't count it as its
    /// own device and it gets none of the sender's sign-ins.
    PairAccept {
        #[serde(flatten)]
        device: DeviceInput,
        #[serde(default)]
        guest: bool,
    },
    PairCancel,
    /// Sender: invite the receiver showing `target` (a code or a QR link).
    PairSend {
        pairing: String,
        target: String,
        #[serde(default)]
        id: Option<String>,
    },
    Snapshot {
        position_ms: f64,
        playing: bool,
        buffering: bool,
    },
    Loaded {
        item: String,
        #[serde(default)]
        duration_ms: Option<u64>,
    },
    Load {
        item: ItemInput,
    },
    Play,
    Pause,
    Seek {
        position_ms: f64,
    },
    Lock {
        locked: bool,
    },
    Policy {
        policy: ChangePolicy,
    },
    RequestPlay {
        item: ItemInput,
    },
    Reply {
        request: String,
        allowed: bool,
        #[serde(default)]
        always: bool,
    },
    Delegate {
        member: String,
        sign_pk: String,
    },
    AddOwn {
        member: String,
        box_pk: String,
    },
    ContinueWithout {
        member: String,
    },
    ShareCredentials {
        member: String,
    },
    /// Start or stop playing the video on this device.
    Screen {
        screen: bool,
    },
    Leave,
}

#[derive(Deserialize)]
struct DeviceInput {
    name: String,
    kind: DeviceKind,
    /// Plays the video here; false for a remote.
    screen: bool,
}

#[derive(Deserialize)]
struct ItemInput {
    #[serde(default)]
    id: Option<String>,
    title: String,
    #[serde(default)]
    site: Option<String>,
    source: Source,
    #[serde(default)]
    headers: BTreeMap<String, String>,
    #[serde(default)]
    subtitles: Vec<String>,
    #[serde(default)]
    start_ms: u64,
}

impl ItemInput {
    fn into_item(self) -> SharedItem {
        SharedItem {
            id: self
                .id
                .unwrap_or_else(|| b64::encode(MemberKeys::generate().box_public())[..12].into()),
            title: self.title,
            site: self.site,
            source: self.source,
            headers: self.headers,
            subtitles: self.subtitles,
            start_ms: self.start_ms,
            // The engine sets this to the member that shares it.
            shared_by: MemberId::from_bytes([0; 16]),
        }
    }
}

pub struct TogetherHandle {
    commands: async_mpsc::UnboundedSender<Call>,
    events: Mutex<Receiver<Value>>,
    cancelled: Arc<AtomicBool>,
    cancel_signal: watch::Sender<bool>,
}

impl TogetherHandle {
    fn start() -> Option<Arc<Self>> {
        let (commands, inbound) = async_mpsc::unbounded_channel();
        let (events, outbound) = mpsc::channel();
        let (cancel_signal, cancel_rx) = watch::channel(false);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        thread::Builder::new()
            .name("playbridge-together".into())
            .spawn(move || {
                let Ok(runtime) = tokio::runtime::Runtime::new() else {
                    let _ = events.send(json!({"event": "stopped", "reason": "error"}));
                    worker_cancelled.store(true, Ordering::Release);
                    return;
                };
                let crashed = events.clone();
                let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    runtime.block_on(Worker::new(events).run(inbound, cancel_rx));
                }));
                if run.is_err() {
                    let _ = crashed.send(json!({"event": "stopped", "reason": "error"}));
                }
                worker_cancelled.store(true, Ordering::Release);
                runtime.shutdown_background();
            })
            .ok()?;
        Some(Arc::new(Self {
            commands,
            events: Mutex::new(outbound),
            cancelled,
            cancel_signal,
        }))
    }

    fn submit(&self, json: &str) -> bool {
        if self.cancelled.load(Ordering::Acquire) || json.len() > MAX_COMMAND_BYTES {
            return false;
        }
        // Never report serde's error: it can quote an invite key or a header.
        match serde_json::from_str::<Call>(json) {
            Ok(request) => self.commands.send(request).is_ok(),
            Err(_) => false,
        }
    }

    fn next_event(&self, wait_ms: u64) -> Option<Value> {
        let events = self.events.lock().ok()?;
        if wait_ms == 0 {
            events.try_recv().ok()
        } else {
            events.recv_timeout(Duration::from_millis(wait_ms)).ok()
        }
    }

    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        let _ = self.cancel_signal.send(true);
    }
}

impl Drop for TogetherHandle {
    fn drop(&mut self) {
        self.cancel();
    }
}

struct Room {
    commands: async_mpsc::UnboundedSender<RoomCommand>,
    feed: Arc<Mutex<Feed>>,
    link: String,
    name: String,
    task: JoinHandle<()>,
}

struct Pairing {
    service: PairingService,
    lobby: Lobby,
    keys: Option<MemberKeys>,
    invite: Option<PairingInvite>,
    task: Option<JoinHandle<()>>,
}

enum Internal {
    PairingInvite(Result<PairingInvite, String>, MemberKeys),
}

struct Worker {
    events: Sender<Value>,
    room: Option<Room>,
    pairing: Option<Pairing>,
    internal_tx: async_mpsc::UnboundedSender<Internal>,
    internal_rx: async_mpsc::UnboundedReceiver<Internal>,
}

impl Worker {
    fn new(events: Sender<Value>) -> Self {
        let (internal_tx, internal_rx) = async_mpsc::unbounded_channel();
        Self {
            events,
            room: None,
            pairing: None,
            internal_tx,
            internal_rx,
        }
    }

    async fn run(
        mut self,
        mut inbound: async_mpsc::UnboundedReceiver<Call>,
        mut cancel: watch::Receiver<bool>,
    ) {
        loop {
            tokio::select! {
                _ = cancel.changed() => break,
                request = inbound.recv() => match request {
                    Some(call) => self.handle(call).await,
                    None => break,
                },
                Some(internal) = self.internal_rx.recv() => self.internal(internal),
            }
        }
        // Say goodbye so the others see this device leave right away.
        if let Some(room) = self.room.take() {
            let _ = room.commands.send(RoomCommand::Leave);
            let _ = tokio::time::timeout(Duration::from_secs(1), room.task).await;
        }
        self.pair_cancel().await;
    }

    fn emit(&self, event: Value) {
        let _ = self.events.send(event);
    }

    fn error(&self, context: &str, message: impl Into<String>, id: Option<&str>) {
        self.emit(
            json!({"event": "error", "context": context, "message": message.into(), "id": id}),
        );
    }

    async fn handle(&mut self, request: Call) {
        match request {
            Call::Host { relay, device } => {
                let Ok(relay) = url::Url::parse(relay.trim()) else {
                    return self.error("host", "invalid relay address", None);
                };
                if playbridge_together::invite::check_relay_scheme(&relay).is_err() {
                    return self.error("host", "the relay must use https", None);
                }
                let keys = MemberKeys::generate();
                let invite = Invite {
                    relay,
                    room: RoomId::generate(),
                    key: RoomKey::generate(),
                    host: keys.signing_public(),
                };
                self.enter(invite, keys, device);
            }
            Call::Join { invite, device } => match Invite::parse(&invite) {
                Ok(invite) => self.enter(invite, MemberKeys::generate(), device),
                Err(_) => self.error("join", "invalid invite link", None),
            },
            Call::PairRegister { pairing } => self.pair_register(&pairing).await,
            Call::PairAccept { device, guest } => {
                let Some(pairing) = self.pairing.as_mut() else {
                    return self.error("pair", "no invite to accept", None);
                };
                let (Some(invite), Some(keys)) = (pairing.invite.take(), pairing.keys.take())
                else {
                    return self.error("pair", "no invite to accept", None);
                };
                self.pair_cancel().await;
                match Invite::parse(&invite.link) {
                    // Join with the key the invite was sealed to, so the phone
                    // that sent it can recognise this device as its own.
                    Ok(parsed) => {
                        let keys = if guest { MemberKeys::generate() } else { keys };
                        self.enter(parsed, keys, device)
                    }
                    Err(_) => self.error("pair", "invalid invite", None),
                }
            }
            Call::PairCancel => self.pair_cancel().await,
            Call::PairSend {
                pairing,
                target,
                id,
            } => self.pair_send(pairing, target, id),
            Call::Snapshot {
                position_ms,
                playing,
                buffering,
            } => {
                if let Some(room) = &self.room
                    && let Ok(mut feed) = room.feed.lock()
                {
                    feed.report(PlayerSnapshot {
                        position_ms,
                        playing,
                        buffering,
                    });
                }
            }
            Call::Loaded { item, duration_ms } => {
                if let Some(room) = &self.room
                    && let Ok(mut feed) = room.feed.lock()
                {
                    feed.loaded = Some((item, duration_ms));
                }
            }
            Call::Screen { screen } => {
                let Some(room) = &self.room else {
                    return self.error("room", "not in a room", None);
                };
                if let Ok(mut feed) = room.feed.lock() {
                    feed.screen = screen;
                    feed.snapshot = None;
                    feed.loaded = None;
                }
                let _ = room.commands.send(RoomCommand::SetScreen(screen));
            }
            Call::Leave => self.leave(),
            other => match room_command(other) {
                Ok(command) => match &self.room {
                    Some(room) => {
                        let _ = room.commands.send(command);
                    }
                    None => self.error("room", "not in a room", None),
                },
                Err(message) => self.error("room", message, None),
            },
        }
    }

    fn enter(&mut self, invite: Invite, keys: MemberKeys, device: DeviceInput) {
        self.leave();
        let me = MemberId::generate();
        let link = invite.to_link();
        let relay = invite.relay.host_str().unwrap_or_default().to_owned();
        let host = invite.host == keys.signing_public();
        let room_id = invite.room.to_string();
        self.emit(json!({
            "event": "room",
            "room": room_id,
            "invite": link,
            "relay": relay,
            "member": me.to_string(),
            "box_pk": b64::encode(keys.box_public()),
            "sign_pk": b64::encode(keys.signing_public()),
            "host": host,
        }));
        let engine = Engine::new(EngineConfig {
            invite,
            me,
            keys,
            name: device.name.clone(),
            kind: device.kind,
            screen: device.screen,
            seq_start: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_micros() as u64)
                .unwrap_or(1),
        });
        let (commands, commands_rx) = async_mpsc::unbounded_channel();
        let (room_events, mut room_events_rx) = async_mpsc::unbounded_channel();
        let feed = Arc::new(Mutex::new(Feed {
            screen: device.screen,
            ..Feed::default()
        }));
        let player = BridgePlayer {
            feed: feed.clone(),
            events: self.events.clone(),
        };
        let events = self.events.clone();
        let task = tokio::spawn(async move {
            // Its own task, so a panic in the room still ends with `stopped`.
            let mut run = tokio::spawn(client::run(engine, player, commands_rx, room_events));
            let stop = loop {
                tokio::select! {
                    stop = &mut run => break stop,
                    Some(event) = room_events_rx.recv() => {
                        if let Some(value) = event_json(&event) {
                            let _ = events.send(value);
                        }
                    }
                }
            };
            while let Ok(event) = room_events_rx.try_recv() {
                if let Some(value) = event_json(&event) {
                    let _ = events.send(value);
                }
            }
            let reason = match stop {
                Ok(Ok(Stop::Left)) => "left",
                Ok(Ok(Stop::Unauthorized)) => "unauthorized",
                Ok(Ok(Stop::RoomFull)) => "room_full",
                Ok(Ok(Stop::Expired)) => "expired",
                Ok(Ok(Stop::Replaced)) => "replaced",
                Ok(Err(_)) | Err(_) => "error",
            };
            let _ = events.send(json!({"event": "stopped", "room": room_id, "reason": reason}));
        });
        self.room = Some(Room {
            commands,
            feed,
            link,
            name: device.name,
            task,
        });
    }

    fn leave(&mut self) {
        if let Some(room) = self.room.take() {
            // The client sends `leave`, closes the socket and reports `stopped`.
            if room.commands.send(RoomCommand::Leave).is_err() {
                room.task.abort();
            }
        }
    }

    async fn pair_register(&mut self, pairing: &str) {
        self.pair_cancel().await;
        let service = match PairingService::new(pairing) {
            Ok(service) => service,
            Err(_) => return self.error("pair", "invalid pairing address", None),
        };
        let keys = MemberKeys::generate();
        let lobby = match service.register(&keys).await {
            Ok(lobby) => lobby,
            Err(error) => return self.error("pair", error.to_string(), None),
        };
        let qr = service.qr(&lobby, &keys).to_link();
        let (qr_size, qr_modules) = qr_modules(&qr);
        self.emit(json!({
            "event": "pairing_code",
            "code": lobby.code,
            "qr": qr,
            "qr_size": qr_size,
            "qr_modules": qr_modules,
            "expires_at": lobby.expires_at,
        }));
        let waiter = PairingService::new(service.base().as_str()).expect("base already parsed");
        let waiting_lobby = lobby.clone();
        let internal = self.internal_tx.clone();
        let task = tokio::spawn(async move {
            let wait_keys = MemberKeys::from_bytes(&keys.to_bytes());
            let result = tokio::spawn(async move { waiter.wait(&waiting_lobby, &wait_keys).await })
                .await
                .map_err(|_| "pairing failed".to_owned())
                .and_then(|result| result.map_err(|error| error.to_string()));
            let _ = internal.send(Internal::PairingInvite(result, keys));
        });
        self.pairing = Some(Pairing {
            service,
            lobby,
            keys: None,
            invite: None,
            task: Some(task),
        });
    }

    fn internal(&mut self, internal: Internal) {
        match internal {
            Internal::PairingInvite(result, keys) => {
                let Some(pairing) = self.pairing.as_mut() else {
                    return;
                };
                pairing.task = None;
                match result {
                    Ok(invite) => {
                        let relay = Invite::parse(&invite.link)
                            .ok()
                            .and_then(|parsed| parsed.relay.host_str().map(str::to_owned))
                            .unwrap_or_default();
                        let event = json!({
                            "event": "pairing_invite",
                            "from": invite.from,
                            "relay": relay,
                        });
                        pairing.invite = Some(invite);
                        pairing.keys = Some(keys);
                        self.emit(event);
                    }
                    Err(message) => {
                        self.pairing = None;
                        self.error("pair", message, None);
                    }
                }
            }
        }
    }

    async fn pair_cancel(&mut self) {
        if let Some(pairing) = self.pairing.take() {
            if let Some(task) = pairing.task {
                task.abort();
            }
            let release = pairing.service.release(&pairing.lobby);
            let _ = tokio::time::timeout(Duration::from_secs(3), release).await;
        }
    }

    fn pair_send(&self, pairing: String, target: String, id: Option<String>) {
        let Some(room) = &self.room else {
            return self.error("pair_send", "not in a room", id.as_deref());
        };
        let invite = PairingInvite {
            link: room.link.clone(),
            from: room.name.clone(),
        };
        let commands = room.commands.clone();
        let events = self.events.clone();
        tokio::spawn(async move {
            let send = tokio::spawn(async move {
                let (service, code, fingerprint) = if target.contains("/p/") {
                    let qr = QrPayload::parse(&target)?;
                    (
                        PairingService::new(qr.pairing.as_str())?,
                        qr.code,
                        Some(qr.fingerprint),
                    )
                } else {
                    (PairingService::new(&pairing)?, target, None)
                };
                service
                    .send_invite(&code, &invite, fingerprint.as_deref())
                    .await
            });
            let event = match send.await.map_err(|_| "pairing failed".to_owned()) {
                Ok(Ok(box_pk)) => {
                    // The receiver this phone invited counts as its own.
                    let _ = commands.send(RoomCommand::AddOwnKey(box_pk));
                    json!({"event": "pair_sent", "id": id})
                }
                Ok(Err(error)) => {
                    json!({"event": "error", "context": "pair_send", "message": error.to_string(), "id": id})
                }
                Err(message) => {
                    json!({"event": "error", "context": "pair_send", "message": message, "id": id})
                }
            };
            let _ = events.send(event);
        });
    }
}

/// The QR code for `text` as rows of `1` (dark) and `0`, without a quiet zone,
/// so apps can draw it without a QR library.
fn qr_modules(text: &str) -> (usize, String) {
    match qrcode::QrCode::with_error_correction_level(text, qrcode::EcLevel::M) {
        Ok(code) => {
            let modules = code
                .to_colors()
                .iter()
                .map(|color| {
                    if *color == qrcode::Color::Dark {
                        '1'
                    } else {
                        '0'
                    }
                })
                .collect();
            (code.width(), modules)
        }
        Err(_) => (0, String::new()),
    }
}

fn member(text: &str) -> Result<MemberId, &'static str> {
    MemberId::parse(text).map_err(|_| "invalid member")
}

fn key(text: &str) -> Result<[u8; 32], &'static str> {
    b64::decode_32(text).map_err(|_| "invalid key")
}

fn room_command(request: Call) -> Result<RoomCommand, &'static str> {
    Ok(match request {
        Call::Load { item } => RoomCommand::Load(item.into_item()),
        Call::Play => RoomCommand::Play,
        Call::Pause => RoomCommand::Pause,
        Call::Seek { position_ms } => RoomCommand::Seek(position_ms.max(0.0)),
        Call::Lock { locked } => RoomCommand::Lock(locked),
        Call::Policy { policy } => RoomCommand::SetPolicy(policy),
        Call::RequestPlay { item } => RoomCommand::RequestPlay(item.into_item()),
        Call::Reply {
            request,
            allowed,
            always,
        } => RoomCommand::Reply {
            request,
            allowed,
            always,
        },
        Call::Delegate { member: m, sign_pk } => RoomCommand::Delegate {
            member: member(&m)?,
            sign_pk: key(&sign_pk)?,
        },
        Call::AddOwn { member: m, box_pk } => RoomCommand::AddOwn {
            member: member(&m)?,
            box_pk: key(&box_pk)?,
        },
        Call::ContinueWithout { member: m } => RoomCommand::ContinueWithout(member(&m)?),
        Call::ShareCredentials { member: m } => RoomCommand::ShareCredentials(member(&m)?),
        _ => return Err("unsupported command"),
    })
}

fn ids(members: &[MemberId]) -> Vec<String> {
    members.iter().map(MemberId::to_string).collect()
}

fn event_json(event: &Event) -> Option<Value> {
    Some(match event {
        Event::Connected { members } => json!({"event": "connected", "members": ids(members)}),
        Event::Disconnected => json!({"event": "disconnected"}),
        Event::MemberJoined(m) => json!({"event": "member_joined", "member": m.to_string()}),
        Event::MemberLeft(m) => json!({"event": "member_left", "member": m.to_string()}),
        Event::MemberInfo {
            member,
            name,
            kind,
            host,
        } => {
            json!({"event": "member", "member": member.to_string(), "name": name, "kind": kind, "host": host})
        }
        Event::VideoChanged(item) => json!({"event": "video", "item": item}),
        Event::TimelineChanged(t) => json!({
            "event": "timeline",
            "item": t.item,
            "playing": t.playing,
            "position_ms": t.position_ms,
            "rev": t.rev,
        }),
        Event::Waiting(members) => json!({"event": "waiting", "members": ids(members)}),
        Event::ContinueWithoutAvailable(members) => {
            json!({"event": "continue_without_available", "members": ids(members)})
        }
        Event::PlayRequested {
            request,
            from,
            title,
            site,
        } => json!({
            "event": "play_requested",
            "request": request,
            "from": from.to_string(),
            "title": title,
            "site": site,
        }),
        Event::RequestAnswered { request, allowed } => {
            json!({"event": "request_answered", "request": request, "allowed": allowed})
        }
        Event::RequestExpired { request } => {
            json!({"event": "request_expired", "request": request})
        }
        Event::Locked(locked) => json!({"event": "locked", "locked": locked}),
        Event::Policy(policy) => json!({"event": "policy", "policy": policy}),
        Event::CredentialsReceived { item } => json!({"event": "credentials", "item": item}),
        Event::Warning(message) => json!({"event": "warning", "message": message}),
        Event::Live(live) => json!({"event": "live", "live": live}),
        Event::Rejected(reason) => json!({"event": "rejected", "reason": reason}),
    })
}

/// What the app last reported about its player, moved forward in time so the
/// app does not have to report on every engine tick.
#[derive(Default)]
struct Feed {
    /// Whether this device plays the video; a remote reports no player.
    screen: bool,
    snapshot: Option<(PlayerSnapshot, Instant)>,
    rate: f64,
    loaded: Option<(String, Option<u64>)>,
}

impl Feed {
    fn report(&mut self, snapshot: PlayerSnapshot) {
        self.snapshot = Some((snapshot, Instant::now()));
    }

    fn now(&self) -> Option<PlayerSnapshot> {
        let (mut snapshot, at) = self.snapshot?;
        if snapshot.playing && !snapshot.buffering {
            let rate = if self.rate > 0.0 { self.rate } else { 1.0 };
            snapshot.position_ms += at.elapsed().as_secs_f64() * 1000.0 * rate;
        }
        Some(snapshot)
    }

    fn apply(&mut self, command: &PlayerCommand) {
        let current = self.now();
        let at = Instant::now();
        match command {
            PlayerCommand::Load { start_ms, .. } => {
                self.rate = 1.0;
                self.snapshot = Some((
                    PlayerSnapshot {
                        position_ms: *start_ms,
                        playing: false,
                        buffering: true,
                    },
                    at,
                ));
            }
            PlayerCommand::Play
            | PlayerCommand::GoLive
            | PlayerCommand::Pause
            | PlayerCommand::Seek(_) => {
                if let Some(mut snapshot) = current {
                    match command {
                        PlayerCommand::Play | PlayerCommand::GoLive => snapshot.playing = true,
                        PlayerCommand::Pause => snapshot.playing = false,
                        PlayerCommand::Seek(to) => snapshot.position_ms = *to,
                        _ => {}
                    }
                    self.snapshot = Some((snapshot, at));
                }
            }
            PlayerCommand::SetRate(rate) => {
                if let Some(snapshot) = current {
                    self.snapshot = Some((snapshot, at));
                }
                self.rate = *rate;
            }
        }
    }
}

struct BridgePlayer {
    feed: Arc<Mutex<Feed>>,
    events: Sender<Value>,
}

impl Player for BridgePlayer {
    fn apply(&mut self, command: PlayerCommand) {
        if let Ok(mut feed) = self.feed.lock() {
            // A remote has no player to drive.
            if !feed.screen {
                return;
            }
            feed.apply(&command);
        }
        let action = match command {
            PlayerCommand::Load {
                item,
                secrets,
                start_ms,
            } => {
                // The app gets the sign-in headers merged in; they are never
                // part of the `video` event the rest of the UI sees.
                let mut headers = item.headers.clone();
                headers.extend(secrets);
                json!({"action": "load", "item": item, "headers": headers, "start_ms": start_ms})
            }
            PlayerCommand::Play => json!({"action": "play"}),
            PlayerCommand::Pause => json!({"action": "pause"}),
            PlayerCommand::Seek(to) => json!({"action": "seek", "position_ms": to}),
            PlayerCommand::SetRate(rate) => json!({"action": "rate", "rate": rate}),
            PlayerCommand::GoLive => json!({"action": "go_live"}),
        };
        let _ = self
            .events
            .send(json!({"event": "player", "command": action}));
    }

    fn snapshot(&mut self) -> Option<PlayerSnapshot> {
        let feed = self.feed.lock().ok()?;
        if !feed.screen {
            return None;
        }
        feed.now()
    }

    fn take_loaded(&mut self) -> Option<(String, Option<u64>)> {
        self.feed.lock().ok()?.loaded.take()
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn pb_together_abi_version() -> u32 {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn pb_together_start() -> *mut TogetherHandle {
    std::panic::catch_unwind(|| {
        TogetherHandle::start()
            .map(|handle| Arc::into_raw(handle) as *mut TogetherHandle)
            .unwrap_or(std::ptr::null_mut())
    })
    .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `handle` must be null or live; `command_json` must be a valid NUL-terminated string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_together_submit_json(
    handle: *const TogetherHandle,
    command_json: *const c_char,
) -> bool {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Some(handle) = (unsafe { handle.as_ref() }) else {
            return false;
        };
        if command_json.is_null() {
            return false;
        }
        match unsafe { CStr::from_ptr(command_json) }.to_str() {
            Ok(json) => handle.submit(json),
            Err(_) => false,
        }
    }))
    .unwrap_or(false)
}

/// # Safety
/// `handle` must be null or live. Release returned strings with `pb_string_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_together_next_json(
    handle: *const TogetherHandle,
    wait_ms: u64,
) -> *mut c_char {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Some(handle) = (unsafe { handle.as_ref() }) else {
            return std::ptr::null_mut();
        };
        handle
            .next_event(wait_ms)
            .and_then(|event| serde_json::to_string(&event).ok())
            .and_then(|json| CString::new(json).ok())
            .map(CString::into_raw)
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `handle` must be null or live. Cancelling leaves the room and never joins
/// the worker.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_together_cancel(handle: *const TogetherHandle) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(handle) = unsafe { handle.as_ref() } {
            handle.cancel();
        }
    }));
}

/// # Safety
/// `handle` must be null or live and freed at most once. No call may overlap free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pb_together_free(handle: *const TogetherHandle) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if !handle.is_null() {
            drop(unsafe { Arc::from_raw(handle) });
        }
    }));
}

#[cfg(target_os = "android")]
#[path = "together_jni.rs"]
mod android_jni;

#[cfg(test)]
mod tests {
    use super::*;

    fn next(handle: &TogetherHandle, event: &str) -> Value {
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if let Some(value) = handle.next_event(200)
                && value["event"] == event
            {
                return value;
            }
        }
        panic!("no {event} event");
    }

    #[test]
    fn rejects_malformed_and_out_of_room_commands_without_quoting_them() {
        let handle = TogetherHandle::start().unwrap();
        assert!(!handle.submit(r#"{"command":"join","invite":"#));
        assert!(handle.submit(r#"{"command":"play"}"#));
        let error = next(&handle, "error");
        assert_eq!(error["message"], "not in a room");
        assert!(handle.submit(
            r#"{"command":"join","invite":"https://relay.test/j/zz#k=secret","name":"TV","kind":"tv","screen":true}"#
        ));
        let error = next(&handle, "error");
        assert_eq!(error["message"], "invalid invite link");
        assert!(!error.to_string().contains("secret"));
    }

    #[test]
    fn hosting_reports_an_invite_signed_by_this_device() {
        let handle = TogetherHandle::start().unwrap();
        assert!(handle.submit(
            r#"{"command":"host","relay":"https://relay.invalid","name":"Phone","kind":"phone","screen":false}"#
        ));
        let room = next(&handle, "room");
        assert_eq!(room["host"], true);
        assert_eq!(room["relay"], "relay.invalid");
        let invite = Invite::parse(room["invite"].as_str().unwrap()).unwrap();
        assert_eq!(b64::encode(invite.host), room["sign_pk"].as_str().unwrap());
        assert!(handle.submit(r#"{"command":"leave"}"#));
        assert_eq!(next(&handle, "stopped")["reason"], "left");
    }

    #[test]
    fn plain_http_relays_are_refused_for_hosting() {
        let handle = TogetherHandle::start().unwrap();
        assert!(handle.submit(
            r#"{"command":"host","relay":"http://relay.example.com","name":"Phone","kind":"phone","screen":false}"#
        ));
        assert_eq!(next(&handle, "error")["context"], "host");
    }

    #[test]
    fn qr_modules_form_a_square() {
        let (size, modules) = qr_modules("https://pair.example.com/p/ABC234#fp=AAAAAAAAAAAAAAAA");
        assert!(size >= 21);
        assert_eq!(modules.len(), size * size);
        assert!(modules.chars().all(|c| c == '0' || c == '1'));
        // Finder pattern: the top-left corner is dark.
        assert!(modules.starts_with("1111111"));
    }

    #[test]
    fn the_feed_moves_a_playing_snapshot_forward() {
        let mut feed = Feed::default();
        feed.report(PlayerSnapshot {
            position_ms: 1_000.0,
            playing: true,
            buffering: false,
        });
        feed.snapshot.as_mut().unwrap().1 -= Duration::from_millis(500);
        let now = feed.now().unwrap();
        assert!((1_490.0..1_600.0).contains(&now.position_ms));
        feed.apply(&PlayerCommand::Pause);
        feed.apply(&PlayerCommand::Seek(10_000.0));
        assert_eq!(feed.now().unwrap().position_ms, 10_000.0);
    }
}
