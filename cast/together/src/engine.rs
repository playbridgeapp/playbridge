//! The room state machine. Pure: no clocks, sockets or players of its own.
//! Feed it relay frames, ticks (with the local player's state) and user
//! commands; it returns frames to send, player commands and UI events.

use std::collections::{BTreeMap, HashMap, HashSet};

use crate::b64;
use crate::clock::ClockEstimator;
use crate::crypto::{self, Channel, MemberKeys};
use crate::frames::{ClientFrame, ServerFrame, StateFrame};
use crate::ids::MemberId;
use crate::invite::Invite;
use crate::messages::{Body, ChangePolicy, DeviceKind, SharedItem, Signed, Snapshot};
use crate::sync::{Correction, Corrector, PlayerSnapshot, Timeline};

/// Wait this long for every member to be ready before starting anyway.
pub const START_TIMEOUT_MS: f64 = 15_000.0;
/// Minimum lead between scheduling a start and the start itself.
pub const MIN_START_LEAD_MS: f64 = 800.0;
/// Pause everyone once a member has buffered this long.
pub const BUFFERING_PAUSE_MS: f64 = 2_000.0;
/// Offer "Continue without them" after this long.
pub const CONTINUE_WITHOUT_MS: f64 = 20_000.0;
pub const REQUEST_TIMEOUT_MS: f64 = 60_000.0;
pub const POSITION_HEARTBEAT_MS: f64 = 30_000.0;
pub const DURATION_MISMATCH_MS: u64 = 3_000;
const INITIAL_PINGS: usize = 8;
const INITIAL_PING_SPACING_MS: f64 = 150.0;
/// Steady-state clock pings. Clocks drift slowly; the burst on each connect does the work.
const PING_INTERVAL_MS: f64 = 120_000.0;
/// After a seek or play, give the player this long before correcting again.
const SETTLE_MS: f64 = 1_000.0;

pub struct EngineConfig {
    pub invite: Invite,
    pub me: MemberId,
    pub keys: MemberKeys,
    pub name: String,
    pub kind: DeviceKind,
    /// Whether this device plays the video. A remote-only phone is not a
    /// screen, so nobody waits for it to load.
    pub screen: bool,
    /// First sequence number. Must grow across restarts of the same member,
    /// for example Unix time in microseconds.
    pub seq_start: u64,
}

#[derive(Clone, Debug, PartialEq)]
#[allow(clippy::large_enum_variant)] // one load per video; not worth boxing
pub enum PlayerCommand {
    /// Load paused at `start_ms`. `secrets` holds sign-in headers when this
    /// device is allowed them.
    Load {
        item: SharedItem,
        secrets: BTreeMap<String, String>,
        start_ms: f64,
    },
    Play,
    Pause,
    Seek(f64),
    SetRate(f64),
    /// A live stream: jump to this device's live point and play.
    GoLive,
    /// Stop the shared video and return to waiting for the next one.
    Clear,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    Connected {
        members: Vec<MemberId>,
    },
    Disconnected,
    /// The relay hasn't been reachable for a while; the client keeps trying.
    Unreachable,
    MemberJoined(MemberId),
    MemberLeft(MemberId),
    MemberInfo {
        member: MemberId,
        name: String,
        kind: DeviceKind,
        host: bool,
    },
    VideoChanged(SharedItem),
    VideoCleared,
    TimelineChanged(Timeline),
    /// The video reached its end on the shared timeline (not a manual pause).
    Ended {
        item: String,
    },
    /// Members everyone is waiting for; empty when nobody.
    Waiting(Vec<MemberId>),
    /// The host may now continue without these members.
    ContinueWithoutAvailable(Vec<MemberId>),
    PlayRequested {
        request: String,
        from: MemberId,
        title: String,
        site: Option<String>,
    },
    RequestAnswered {
        request: String,
        allowed: bool,
    },
    RequestExpired {
        request: String,
    },
    Locked(bool),
    Policy(ChangePolicy),
    CredentialsReceived {
        item: String,
    },
    Warning(String),
    /// Whether the video is a live stream: every screen loaded it with no length.
    /// A live party shares playing or paused only, so apps hide seeking.
    Live(bool),
    /// The party's known video duration in milliseconds, or `None` when live or unknown.
    Duration(Option<u64>),
    /// A message was dropped; for logs, never shown as an error.
    Rejected(&'static str),
}

#[derive(Clone, Debug, PartialEq)]
pub enum Output {
    Send(ClientFrame),
    Player(PlayerCommand),
    Event(Event),
}

#[derive(Clone, Debug)]
pub enum Command {
    Load(SharedItem),
    Clear,
    Play,
    Pause,
    Seek(f64),
    Lock(bool),
    SetPolicy(ChangePolicy),
    RequestPlay(SharedItem),
    Reply {
        request: String,
        allowed: bool,
        always: bool,
    },
    Delegate {
        member: MemberId,
        sign_pk: [u8; 32],
    },
    /// A member this device's owner added (learned over the LAN or pairing).
    AddOwn {
        member: MemberId,
        box_pk: [u8; 32],
    },
    /// A device this device's owner paired by code or QR: only its key is
    /// known, so it becomes a member of ours once it says hello with that key.
    AddOwnKey([u8; 32]),
    ContinueWithout(MemberId),
    /// Start or stop playing the video on this device. The others learn it
    /// from a fresh hello; becoming a screen loads the current video.
    SetScreen(bool),
    /// One-time share of the current item's sign-in with a member.
    ShareCredentials(MemberId),
    Leave,
}

#[derive(Clone, Debug)]
struct Peer {
    name: String,
    kind: DeviceKind,
    screen: bool,
    box_pk: [u8; 32],
    sign_pk: [u8; 32],
}

struct PendingRequest {
    from: MemberId,
    item: SharedItem,
    deadline: f64,
}

pub struct Engine {
    cfg: EngineConfig,
    seq: u64,
    clock: ClockEstimator,
    corrector: Corrector,
    connected: bool,

    members: Vec<MemberId>,
    peers: HashMap<MemberId, Peer>,
    last_seq: HashMap<(MemberId, u8), u64>,

    // Host-signed state and the signed messages that established it.
    snapshot: Snapshot,
    delegates: HashMap<MemberId, [u8; 32]>,
    badged_host: Option<MemberId>,
    locked: bool,
    lock_rev: u64,
    policy: ChangePolicy,
    policy_rev: u64,
    load_rev: u64,

    item: Option<SharedItem>,
    timeline: Option<Timeline>,
    load_seen_room: f64,
    ready: HashMap<MemberId, (Option<u64>, Option<f64>)>,
    /// The last [`Event::Live`] sent.
    live: bool,
    /// The last [`Event::Duration`] sent.
    known_duration: Option<u64>,
    warned_duration: bool,
    buffering: HashMap<MemberId, f64>,
    ignored: HashSet<MemberId>,
    paused_for_buffering: bool,
    last_waiting: Vec<MemberId>,
    last_continue: Vec<MemberId>,

    // Credentials.
    own: HashMap<MemberId, [u8; 32]>,
    own_keys: HashSet<[u8; 32]>,
    my_secrets: HashMap<String, BTreeMap<String, String>>,
    received: HashMap<(String, MemberId), BTreeMap<String, String>>,

    // Requests.
    pending: HashMap<String, PendingRequest>,
    always_allow: HashSet<MemberId>,
    my_requests: HashMap<String, f64>,

    // Local player.
    loaded_item: Option<String>,
    sent_ready: Option<String>,
    reported_buffering: bool,
    settle_until: f64,
    last_position_sent: f64,

    // Clock pings.
    next_ping: u64,
    pings: HashMap<u64, f64>,
    last_ping: f64,
    pings_this_connection: usize,
}

impl Engine {
    pub fn new(cfg: EngineConfig) -> Self {
        let seq = cfg.seq_start;
        Self {
            cfg,
            seq,
            clock: ClockEstimator::default(),
            corrector: Corrector::default(),
            connected: false,
            members: Vec::new(),
            peers: HashMap::new(),
            last_seq: HashMap::new(),
            snapshot: Snapshot::default(),
            delegates: HashMap::new(),
            badged_host: None,
            locked: false,
            lock_rev: 0,
            policy: ChangePolicy::default(),
            policy_rev: 0,
            load_rev: 0,
            item: None,
            timeline: None,
            load_seen_room: 0.0,
            ready: HashMap::new(),
            live: false,
            known_duration: None,
            warned_duration: false,
            buffering: HashMap::new(),
            ignored: HashSet::new(),
            paused_for_buffering: false,
            last_waiting: Vec::new(),
            last_continue: Vec::new(),
            own: HashMap::new(),
            own_keys: HashSet::new(),
            my_secrets: HashMap::new(),
            received: HashMap::new(),
            pending: HashMap::new(),
            always_allow: HashSet::new(),
            my_requests: HashMap::new(),
            loaded_item: None,
            sent_ready: None,
            reported_buffering: false,
            settle_until: 0.0,
            last_position_sent: f64::NEG_INFINITY,
            next_ping: 1,
            pings: HashMap::new(),
            last_ping: f64::NEG_INFINITY,
            pings_this_connection: 0,
        }
    }

    pub fn me(&self) -> MemberId {
        self.cfg.me
    }

    /// This member's box key, handed to the owner's phone over the LAN so it
    /// can send this device sign-ins.
    pub fn box_public(&self) -> [u8; 32] {
        self.cfg.keys.box_public()
    }

    pub fn invite(&self) -> &Invite {
        &self.cfg.invite
    }

    pub fn members(&self) -> &[MemberId] {
        &self.members
    }

    pub fn timeline(&self) -> Option<&Timeline> {
        self.timeline.as_ref()
    }

    pub fn item(&self) -> Option<&SharedItem> {
        self.item.as_ref()
    }

    /// A member's display name and device kind, once its hello arrived.
    pub fn member_info(&self, member: &MemberId) -> Option<(&str, DeviceKind)> {
        if *member == self.cfg.me {
            return Some((&self.cfg.name, self.cfg.kind));
        }
        self.peers.get(member).map(|p| (p.name.as_str(), p.kind))
    }

    pub fn is_locked(&self) -> bool {
        self.locked
    }

    pub fn policy(&self) -> ChangePolicy {
        self.policy
    }

    pub fn clock(&self) -> &ClockEstimator {
        &self.clock
    }

    pub fn room_time(&self, local_ms: f64) -> Option<f64> {
        self.clock.room_time(local_ms)
    }

    /// Whether this device holds the host's key.
    pub fn is_host(&self) -> bool {
        self.cfg.keys.signing_public() == self.cfg.invite.host
    }

    fn can_sign_as_host(&self) -> bool {
        self.is_host() || self.delegates.get(&self.cfg.me) == Some(&self.cfg.keys.signing_public())
    }

    fn is_real_host(&self, member: &MemberId) -> bool {
        if *member == self.cfg.me {
            return self.is_host();
        }
        self.peers
            .get(member)
            .is_some_and(|p| p.sign_pk == self.cfg.invite.host)
    }

    fn is_hostish(&self, member: &MemberId) -> bool {
        if *member == self.cfg.me {
            return self.can_sign_as_host();
        }
        let Some(peer) = self.peers.get(member) else {
            return false;
        };
        peer.sign_pk == self.cfg.invite.host || self.delegates.get(member) == Some(&peer.sign_pk)
    }

    /// The member that schedules starts and buffering pauses: the first
    /// online host or delegate, else the earliest member.
    pub fn leader(&self) -> Option<MemberId> {
        self.members
            .iter()
            .find(|m| self.is_hostish(m))
            .or_else(|| self.members.first())
            .copied()
    }

    /// The member wearing the party's Host badge: the real host while present,
    /// otherwise the acting host chosen by the engine (`leader()` when a host or
    /// delegated device is present), else `None`.
    pub fn acting_host(&self) -> Option<MemberId> {
        if let Some(&host_id) = self.members.iter().find(|m| self.is_real_host(m)) {
            return Some(host_id);
        }
        self.leader().filter(|m| self.is_hostish(m))
    }

    fn emit_member_info(&self, member: MemberId, host: bool, out: &mut Vec<Output>) {
        if member == self.cfg.me {
            out.push(Output::Event(Event::MemberInfo {
                member: self.cfg.me,
                name: self.cfg.name.clone(),
                kind: self.cfg.kind,
                host,
            }));
        } else if let Some(peer) = self.peers.get(&member) {
            out.push(Output::Event(Event::MemberInfo {
                member,
                name: peer.name.clone(),
                kind: peer.kind,
                host,
            }));
        }
    }

    fn sync_badged_host(&mut self, skip_emit: Option<MemberId>, out: &mut Vec<Output>) {
        let prev = self.badged_host;
        let next = self.acting_host();
        self.badged_host = next;
        if prev != next {
            if let Some(old) = prev
                && Some(old) != skip_emit
                && self.members.contains(&old)
            {
                self.emit_member_info(old, false, out);
            }
            if let Some(new) = next
                && Some(new) != skip_emit
            {
                self.emit_member_info(new, true, out);
            }
        }
    }

    /// Members are screens until their hello says otherwise.
    fn is_screen(&self, member: &MemberId) -> bool {
        if *member == self.cfg.me {
            return self.cfg.screen;
        }
        self.peers.get(member).is_none_or(|p| p.screen)
    }

    fn is_leader(&self) -> bool {
        self.leader() == Some(self.cfg.me)
    }

    // ---------------------------------------------------------------- input

    pub fn handle(&mut self, frame: ServerFrame, local_ms: f64) -> Vec<Output> {
        let mut out = Vec::new();
        match frame {
            ServerFrame::Welcome {
                member,
                members,
                room_time,
                state,
                ..
            } => {
                debug_assert_eq!(member, self.cfg.me);
                // A rough offset until the first pong arrives.
                if !self.clock.is_synced() {
                    self.clock.add(local_ms, room_time, local_ms);
                }
                self.connected = true;
                self.pings_this_connection = 0;
                self.pings.clear();
                self.members = members;
                out.push(Output::Event(Event::Connected {
                    members: self.members.clone(),
                }));
                let next_badged = self.acting_host();
                self.badged_host = next_badged;
                self.emit_member_info(
                    self.cfg.me,
                    next_badged == Some(self.cfg.me),
                    &mut out,
                );
                self.send_hello(None, &mut out);
                if let Some(state) = state {
                    self.receive_state(state, local_ms, &mut out);
                }
            }
            ServerFrame::Pong { id, room_time, .. } => {
                if let Some(t0) = self.pings.remove(&id) {
                    self.clock.add(t0, room_time, local_ms);
                }
            }
            ServerFrame::Msg { from, seq, ct } => {
                self.receive_msg(from, seq, &ct, local_ms, &mut out)
            }
            ServerFrame::State { from, seq, ct } => {
                self.receive_state(StateFrame { from, seq, ct }, local_ms, &mut out)
            }
            ServerFrame::Joined { member } => {
                if !self.members.contains(&member) {
                    self.members.push(member);
                }
                self.sync_badged_host(None, &mut out);
                out.push(Output::Event(Event::MemberJoined(member)));
                self.send_hello(Some(member), &mut out);
                // Our own devices get the sign-in for a video we are sharing.
                if let Some(item) = self.item.clone()
                    && item.shared_by == self.cfg.me
                    && self.own.contains_key(&member)
                {
                    self.send_credentials(&item.id, member, &mut out);
                }
            }
            ServerFrame::Left { member } => {
                self.members.retain(|m| *m != member);
                self.buffering.remove(&member);
                self.ready.remove(&member);
                self.sync_badged_host(None, &mut out);
                self.sync_duration(&mut out);
                out.push(Output::Event(Event::MemberLeft(member)));
            }
            ServerFrame::Error { code } => {
                out.push(Output::Event(Event::Warning(format!("relay: {code}"))))
            }
        }
        out
    }

    pub fn disconnected(&mut self) -> Vec<Output> {
        self.connected = false;
        vec![Output::Event(Event::Disconnected)]
    }

    /// The local player finished loading `item` and is paused, ready to start.
    pub fn player_loaded(
        &mut self,
        item: &str,
        duration_ms: Option<u64>,
        local_ms: f64,
    ) -> Vec<Output> {
        let mut out = Vec::new();
        if self.item.as_ref().map(|i| i.id.as_str()) != Some(item) {
            return out;
        }
        self.loaded_item = Some(item.to_owned());
        // A player that took too long to learn the length reported "live"; once it
        // knows, the video is on demand after all and positions sync again.
        let learned_length = duration_ms.is_some()
            && self.sent_ready.as_deref() == Some(item)
            && matches!(self.ready.get(&self.cfg.me), Some((None, _)));
        if self.sent_ready.as_deref() != Some(item) || learned_length {
            self.sent_ready = Some(item.to_owned());
            let rtt = self.clock.rtt_ms();
            self.ready.insert(self.cfg.me, (duration_ms, rtt));
            self.send(
                Body::Ready {
                    item: item.to_owned(),
                    duration_ms,
                    rtt_ms: rtt,
                },
                None,
                false,
                &mut out,
            );
            self.check_durations(&mut out);
            self.sync_duration(&mut out);
        }
        let _ = local_ms;
        out
    }

    pub fn tick(&mut self, local_ms: f64, player: Option<PlayerSnapshot>) -> Vec<Output> {
        let mut out = Vec::new();
        self.maybe_ping(local_ms, &mut out);
        let Some(room_now) = self.clock.room_time(local_ms) else {
            return out;
        };

        if let Some(player) = player {
            self.report_player(player, local_ms, room_now, &mut out);
            self.correct(player, local_ms, room_now, &mut out);
        }
        if self.connected {
            self.lead(room_now, &mut out);
        }
        let live = self.video_live();
        if live != self.live {
            self.live = live;
            out.push(Output::Event(Event::Live(live)));
        }
        self.sync_duration(&mut out);
        self.expire_requests(room_now, &mut out);
        out
    }

    pub fn command(&mut self, command: Command, local_ms: f64) -> Vec<Output> {
        let mut out = Vec::new();
        let room_now = self.clock.room_time(local_ms).unwrap_or(local_ms);
        match command {
            Command::Load(mut item) => {
                let allowed = self.can_sign_as_host()
                    || (self.policy == ChangePolicy::Anyone && !self.locked);
                if !allowed {
                    out.push(Output::Event(Event::Rejected(
                        "not allowed to change the video",
                    )));
                    return out;
                }
                item.shared_by = self.cfg.me;
                let secrets = item.take_secrets();
                self.my_secrets.insert(item.id.clone(), secrets);
                for member in self.own_online() {
                    self.send_credentials(&item.id, member, &mut out);
                }
                self.issue_load(item, room_now, &mut out);
            }
            Command::Clear => {
                let allowed = self.can_sign_as_host()
                    || (self.policy == ChangePolicy::Anyone && !self.locked);
                if !allowed {
                    out.push(Output::Event(Event::Rejected(
                        "not allowed to change the video",
                    )));
                    return out;
                }
                if self.item.is_none() && self.snapshot.load.is_none() {
                    return out;
                }
                self.issue_clear(&mut out);
            }
            Command::Play | Command::Pause | Command::Seek(_) => {
                if self.locked && !self.can_sign_as_host() {
                    out.push(Output::Event(Event::Rejected("controls are locked")));
                    return out;
                }
                let Some(current) = self.timeline.clone() else {
                    return out;
                };
                // A live stream has no shared position to move.
                if matches!(command, Command::Seek(_)) && self.video_live() {
                    out.push(Output::Event(Event::Rejected("live streams can't seek")));
                    return out;
                }
                // Already so: a second tap must not push a scheduled start back.
                if matches!(
                    (&command, current.playing),
                    (Command::Play, true) | (Command::Pause, false)
                ) {
                    return out;
                }
                let expected = current.expected_ms(room_now);
                let (playing, position) = match command {
                    Command::Play => (true, expected),
                    Command::Pause => (false, expected),
                    Command::Seek(to) => (current.playing, to.max(0.0)),
                    _ => unreachable!(),
                };
                let at = if playing {
                    room_now + self.start_lead()
                } else {
                    room_now
                };
                self.paused_for_buffering = false;
                self.write_timeline(playing, position, at, false, &mut out);
            }
            Command::Lock(locked) => {
                if !self.can_sign_as_host() {
                    return out;
                }
                let rev = self.next_seq();
                let signed = self.signed(&Body::Lock { locked }, true, rev);
                self.apply_lock(locked, rev, signed.clone(), &mut out);
                self.send_signed(signed, None, &mut out);
                self.write_state(&mut out);
            }
            Command::SetPolicy(change) => {
                if !self.can_sign_as_host() {
                    return out;
                }
                let rev = self.next_seq();
                let signed = self.signed(&Body::Policy { change }, true, rev);
                self.apply_policy(change, rev, signed.clone(), &mut out);
                self.send_signed(signed, None, &mut out);
                self.write_state(&mut out);
            }
            Command::RequestPlay(mut item) => {
                item.shared_by = self.cfg.me;
                let secrets = item.take_secrets();
                self.my_secrets.insert(item.id.clone(), secrets);
                // Our own devices get the sign-in up front so it is there when
                // the host's load arrives.
                for member in self.own_online() {
                    self.send_credentials(&item.id, member, &mut out);
                }
                let request = b64::encode(crypto::random_bytes::<9>());
                self.my_requests
                    .insert(request.clone(), room_now + REQUEST_TIMEOUT_MS);
                self.send(Body::RequestPlay { request, item }, None, false, &mut out);
            }
            Command::Reply {
                request,
                allowed,
                always,
            } => {
                if !self.can_sign_as_host() {
                    return out;
                }
                let Some(pending) = self.pending.remove(&request) else {
                    return out;
                };
                if always && allowed {
                    self.always_allow.insert(pending.from);
                }
                self.answer(request, pending, allowed, room_now, &mut out);
            }
            Command::Delegate { member, sign_pk } => {
                if !self.is_host() {
                    return out;
                }
                let body = Body::Delegate {
                    member,
                    sign_pk: b64::encode(sign_pk),
                };
                let signed = self.signed(&body, true, 0);
                self.delegates.insert(member, sign_pk);
                self.snapshot.delegates.push(signed.clone());
                self.send_signed(signed, None, &mut out);
                self.write_state(&mut out);
            }
            Command::AddOwn { member, box_pk } => self.add_own(member, box_pk, &mut out),
            Command::AddOwnKey(box_pk) => {
                self.own_keys.insert(box_pk);
                let matching: Vec<MemberId> = self
                    .peers
                    .iter()
                    .filter(|(_, peer)| peer.box_pk == box_pk)
                    .map(|(member, _)| *member)
                    .collect();
                for member in matching {
                    self.add_own(member, box_pk, &mut out);
                }
            }
            Command::ContinueWithout(member) => {
                self.ignored.insert(member);
            }
            Command::SetScreen(screen) => self.set_screen(screen, room_now, &mut out),
            Command::ShareCredentials(member) => {
                let Some(item) = self.item.clone() else {
                    return out;
                };
                if item.shared_by != self.cfg.me {
                    return out;
                }
                if let Some(peer) = self.peers.get(&member).cloned() {
                    self.send_credentials_to(&item.id, member, &peer.box_pk, &mut out);
                }
            }
            Command::Leave => self.send(Body::Leave, None, false, &mut out),
        }
        out
    }

    // ------------------------------------------------------------ receiving

    fn accept_seq(&mut self, from: MemberId, channel: Channel, seq: u64) -> bool {
        let key = (from, channel as u8);
        match self.last_seq.get(&key) {
            Some(last) if seq <= *last => false,
            _ => {
                self.last_seq.insert(key, seq);
                true
            }
        }
    }

    fn open(&self, channel: Channel, from: &MemberId, seq: u64, ct: &str) -> Option<Signed> {
        let plain = self
            .cfg
            .invite
            .key
            .open(&self.cfg.invite.room, channel, from, seq, ct)
            .ok()?;
        serde_json::from_slice(&plain).ok()
    }

    fn receive_msg(
        &mut self,
        from: MemberId,
        seq: u64,
        ct: &str,
        local_ms: f64,
        out: &mut Vec<Output>,
    ) {
        if from == self.cfg.me {
            return;
        }
        let Some(signed) = self.open(Channel::Message, &from, seq, ct) else {
            return out.push(Output::Event(Event::Rejected("undecryptable message")));
        };
        if !self.accept_seq(from, Channel::Message, seq) {
            return out.push(Output::Event(Event::Rejected("replayed message")));
        }
        let Ok(body) = serde_json::from_str::<Body>(&signed.b) else {
            return out.push(Output::Event(Event::Rejected("unknown message")));
        };
        let room_now = self.clock.room_time(local_ms).unwrap_or(local_ms);
        self.apply(from, body, &signed, room_now, out);
    }

    fn receive_state(&mut self, frame: StateFrame, local_ms: f64, out: &mut Vec<Output>) {
        if frame.from == self.cfg.me
            && self
                .last_seq
                .contains_key(&(frame.from, Channel::State as u8))
        {
            return;
        }
        let Some(signed) = self.open(Channel::State, &frame.from, frame.seq, &frame.ct) else {
            return out.push(Output::Event(Event::Rejected("undecryptable state")));
        };
        if !self.accept_seq(frame.from, Channel::State, frame.seq) {
            return out.push(Output::Event(Event::Rejected("replayed state")));
        }
        let Ok(snapshot) = serde_json::from_str::<Snapshot>(&signed.b) else {
            return out.push(Output::Event(Event::Rejected("unknown state")));
        };
        let room_now = self.clock.room_time(local_ms).unwrap_or(local_ms);
        // Host-signed pieces first: they decide what the timeline may do.
        for delegate in &snapshot.delegates {
            self.apply_signed_piece(frame.from, delegate, room_now, out);
        }
        for piece in [
            &snapshot.lock,
            &snapshot.policy,
            &snapshot.load,
            &snapshot.clear,
        ]
        .into_iter()
        .flatten()
        {
            self.apply_signed_piece(frame.from, piece, room_now, out);
        }
        if let Some(timeline) = snapshot.timeline {
            let signed_ok = signed
                .s
                .as_deref()
                .is_some_and(|s| self.verify_hostish(&signed.b, s));
            if self.locked && !signed_ok {
                return out.push(Output::Event(Event::Rejected(
                    "timeline change while locked",
                )));
            }
            self.accept_timeline(timeline, out);
        }
    }

    fn apply_signed_piece(
        &mut self,
        from: MemberId,
        piece: &Signed,
        room_now: f64,
        out: &mut Vec<Output>,
    ) {
        if let Ok(body) = serde_json::from_str::<Body>(&piece.b) {
            self.apply(from, body, piece, room_now, out);
        }
    }

    fn verify_host(&self, bytes: &str, sig: &str) -> bool {
        crypto::verify(&self.cfg.invite.host, bytes.as_bytes(), sig)
    }

    fn verify_hostish(&self, bytes: &str, sig: &str) -> bool {
        self.verify_host(bytes, sig)
            || self
                .delegates
                .values()
                .any(|pk| crypto::verify(pk, bytes.as_bytes(), sig))
    }

    fn host_signed(&self, signed: &Signed) -> bool {
        signed
            .s
            .as_deref()
            .is_some_and(|s| self.verify_hostish(&signed.b, s))
    }

    fn apply(
        &mut self,
        from: MemberId,
        body: Body,
        signed: &Signed,
        room_now: f64,
        out: &mut Vec<Output>,
    ) {
        match body {
            Body::Hello {
                member,
                name,
                kind,
                screen,
                box_pk,
                sign_pk,
            } => {
                let (Ok(box_pk), Ok(sign_pk)) = (b64::decode_32(&box_pk), b64::decode_32(&sign_pk))
                else {
                    return out.push(Output::Event(Event::Rejected("bad hello keys")));
                };
                let proven = member == from
                    && signed
                        .s
                        .as_deref()
                        .is_some_and(|s| crypto::verify(&sign_pk, signed.b.as_bytes(), s));
                if !proven {
                    return out.push(Output::Event(Event::Rejected("unproven hello")));
                }
                let known = self.peers.contains_key(&from);
                self.peers.insert(
                    from,
                    Peer {
                        name: name.clone(),
                        kind,
                        screen,
                        box_pk,
                        sign_pk,
                    },
                );
                if !known && !self.members.contains(&from) {
                    self.members.push(from);
                }
                self.sync_badged_host(Some(from), out);
                let host = self.badged_host == Some(from);
                out.push(Output::Event(Event::MemberInfo {
                    member: from,
                    name,
                    kind,
                    host,
                }));
                if self.own_keys.contains(&box_pk) && !self.own.contains_key(&from) {
                    self.add_own(from, box_pk, out);
                }
                if !known
                    && self.connected
                    && let Some(item) = self.item.as_ref().map(|i| i.id.clone())
                    && self.loaded_item.as_deref() == Some(item.as_str())
                    && let Some((duration_ms, rtt_ms)) = self.ready.get(&self.cfg.me).copied()
                {
                    self.send(
                        Body::Ready {
                            item,
                            duration_ms,
                            rtt_ms,
                        },
                        Some(from),
                        false,
                        out,
                    );
                }
            }
            Body::Delegate { member, sign_pk } => {
                let Some(sig) = signed.s.as_deref() else {
                    return;
                };
                if !self.verify_host(&signed.b, sig) {
                    return out.push(Output::Event(Event::Rejected("unsigned delegation")));
                }
                let Ok(pk) = b64::decode_32(&sign_pk) else {
                    return;
                };
                if self.delegates.insert(member, pk) != Some(pk) {
                    self.snapshot.delegates.push(signed.clone());
                }
                self.sync_badged_host(None, out);
            }
            Body::Lock { locked } => {
                if !self.host_signed(signed) {
                    return out.push(Output::Event(Event::Rejected("unsigned lock")));
                }
                let rev = signed_rev(signed);
                if rev > self.lock_rev {
                    self.apply_lock(locked, rev, signed.clone(), out);
                }
            }
            Body::Policy { change } => {
                if !self.host_signed(signed) {
                    return out.push(Output::Event(Event::Rejected("unsigned policy")));
                }
                let rev = signed_rev(signed);
                if rev > self.policy_rev {
                    self.apply_policy(change, rev, signed.clone(), out);
                }
            }
            Body::Load { item } => {
                let rev = signed_rev(signed);
                let host = self.host_signed(signed);
                let open =
                    self.policy == ChangePolicy::Anyone && !self.locked && item.shared_by == from;
                if !host && !open {
                    return out.push(Output::Event(Event::Rejected("load not allowed")));
                }
                if rev <= self.load_rev || self.item.as_ref().is_some_and(|i| i.id == item.id) {
                    return;
                }
                self.load_rev = rev;
                self.snapshot.load = Some(signed.clone());
                self.snapshot.clear = None;
                self.start_item(item, room_now, out);
            }
            Body::Clear => {
                let rev = signed_rev(signed);
                let host = self.host_signed(signed);
                let open = self.policy == ChangePolicy::Anyone && !self.locked;
                if !host && !open {
                    return out.push(Output::Event(Event::Rejected("clear not allowed")));
                }
                if rev <= self.load_rev {
                    return;
                }
                self.load_rev = rev;
                self.snapshot.load = None;
                self.snapshot.timeline = None;
                self.snapshot.clear = Some(signed.clone());
                self.clear_item(out);
            }
            Body::Ready {
                item,
                duration_ms,
                rtt_ms,
            } => {
                if self.item.as_ref().map(|i| i.id.as_str()) == Some(item.as_str()) {
                    self.ready.insert(from, (duration_ms, rtt_ms));
                    self.check_durations(out);
                    self.sync_duration(out);
                }
            }
            Body::Buffering { item, buffering } => {
                if self.item.as_ref().map(|i| i.id.as_str()) != Some(item.as_str()) {
                    return;
                }
                if buffering {
                    self.buffering.entry(from).or_insert(room_now);
                } else {
                    self.buffering.remove(&from);
                }
            }
            Body::Position { .. } => {}
            Body::RequestPlay { request, item } => {
                if !self.can_sign_as_host() || item.shared_by != from {
                    return;
                }
                if self.policy != ChangePolicy::Ask {
                    let pending = PendingRequest {
                        from,
                        item,
                        deadline: 0.0,
                    };
                    let allowed = self.policy == ChangePolicy::Anyone && !self.locked;
                    return self.answer(request, pending, allowed, room_now, out);
                }
                let pending = PendingRequest {
                    from,
                    item: item.clone(),
                    deadline: room_now + REQUEST_TIMEOUT_MS,
                };
                if self.always_allow.contains(&from) && !self.locked {
                    return self.answer(request, pending, true, room_now, out);
                }
                self.pending.insert(request.clone(), pending);
                out.push(Output::Event(Event::PlayRequested {
                    request,
                    from,
                    title: item.title,
                    site: item.site,
                }));
            }
            Body::RequestReply {
                request,
                to,
                allowed,
            } => {
                if to != self.cfg.me || !self.host_signed(signed) {
                    return;
                }
                if self.my_requests.remove(&request).is_some() {
                    out.push(Output::Event(Event::RequestAnswered { request, allowed }));
                }
            }
            Body::Credentials { item, sealed } => {
                let context = credentials_context(&self.cfg.invite, &item);
                let Ok(plain) = self.cfg.keys.open_sealed(&sealed, &context) else {
                    return out.push(Output::Event(Event::Rejected("unreadable credentials")));
                };
                let Ok(secrets) = serde_json::from_slice::<BTreeMap<String, String>>(&plain) else {
                    return;
                };
                self.received.insert((item.clone(), from), secrets);
                out.push(Output::Event(Event::CredentialsReceived {
                    item: item.clone(),
                }));
                // Already playing this item without them: reload in place.
                let current = self.item.clone();
                if let Some(current) = current.filter(|i| i.id == item && i.shared_by == from) {
                    let start = self
                        .timeline
                        .as_ref()
                        .map(|t| t.expected_ms(room_now))
                        .unwrap_or(current.start_ms as f64);
                    let secrets = self.secrets_for(&current);
                    self.loaded_item = None;
                    self.corrector.reset();
                    out.push(Output::Player(PlayerCommand::Load {
                        item: current,
                        secrets,
                        start_ms: start,
                    }));
                }
            }
            Body::Leave => {}
        }
    }

    fn apply_lock(&mut self, locked: bool, rev: u64, signed: Signed, out: &mut Vec<Output>) {
        self.lock_rev = rev;
        self.snapshot.lock = Some(signed);
        if self.locked != locked {
            self.locked = locked;
            out.push(Output::Event(Event::Locked(locked)));
        }
    }

    fn apply_policy(
        &mut self,
        change: ChangePolicy,
        rev: u64,
        signed: Signed,
        out: &mut Vec<Output>,
    ) {
        self.policy_rev = rev;
        self.snapshot.policy = Some(signed);
        if self.policy != change {
            self.policy = change;
            out.push(Output::Event(Event::Policy(change)));
        }
    }

    fn accept_timeline(&mut self, timeline: Timeline, out: &mut Vec<Output>) {
        if self.item.as_ref().map(|i| &i.id) != Some(&timeline.item) {
            return;
        }
        if self
            .timeline
            .as_ref()
            .is_some_and(|t| !timeline.supersedes(t))
        {
            return;
        }
        let was_ended = self.timeline.as_ref().is_some_and(|t| t.ended);
        let ended = timeline.ended;
        let item = timeline.item.clone();
        self.settle_until = 0.0;
        self.timeline = Some(timeline.clone());
        self.snapshot.timeline = Some(timeline.clone());
        out.push(Output::Event(Event::TimelineChanged(timeline)));
        if ended && !was_ended {
            out.push(Output::Event(Event::Ended { item }));
        }
    }

    fn start_item(&mut self, item: SharedItem, room_now: f64, out: &mut Vec<Output>) {
        self.item = Some(item.clone());
        self.load_seen_room = room_now;
        self.reset_item_state(out);
        let secrets = self.secrets_for(&item);
        out.push(Output::Event(Event::VideoChanged(item.clone())));
        out.push(Output::Player(PlayerCommand::Load {
            start_ms: item.start_ms as f64,
            item,
            secrets,
        }));
    }

    fn clear_item(&mut self, out: &mut Vec<Output>) {
        let had_item = self.item.take().is_some();
        self.reset_item_state(out);
        if had_item {
            out.push(Output::Event(Event::VideoCleared));
            out.push(Output::Player(PlayerCommand::Clear));
        }
    }

    fn reset_item_state(&mut self, out: &mut Vec<Output>) {
        self.timeline = None;
        self.snapshot.timeline = None;
        self.ready.clear();
        self.buffering.clear();
        self.ignored.clear();
        self.paused_for_buffering = false;
        self.warned_duration = false;
        self.loaded_item = None;
        self.sent_ready = None;
        self.reported_buffering = false;
        self.corrector.reset();
        if !self.last_waiting.is_empty() {
            self.last_waiting.clear();
            out.push(Output::Event(Event::Waiting(Vec::new())));
        }
        if !self.last_continue.is_empty() {
            self.last_continue.clear();
            out.push(Output::Event(Event::ContinueWithoutAvailable(Vec::new())));
        }
        if self.live {
            self.live = false;
            out.push(Output::Event(Event::Live(false)));
        }
        self.set_known_duration(None, out);
    }

    fn secrets_for(&self, item: &SharedItem) -> BTreeMap<String, String> {
        if item.shared_by == self.cfg.me {
            return self.my_secrets.get(&item.id).cloned().unwrap_or_default();
        }
        self.received
            .get(&(item.id.clone(), item.shared_by))
            .cloned()
            .unwrap_or_default()
    }

    fn check_durations(&mut self, out: &mut Vec<Output>) {
        if self.warned_duration {
            return;
        }
        let durations: Vec<u64> = self.ready.values().filter_map(|(d, _)| *d).collect();
        if let (Some(min), Some(max)) = (durations.iter().min(), durations.iter().max())
            && max - min > DURATION_MISMATCH_MS
        {
            self.warned_duration = true;
            out.push(Output::Event(Event::Warning(
                    "Devices got different versions of this video (for example with ads), so they may drift apart."
                        .into(),
                )));
        }
    }

    // ----------------------------------------------------------- local work

    fn maybe_ping(&mut self, local_ms: f64, out: &mut Vec<Output>) {
        if !self.connected {
            return;
        }
        let due = if self.pings_this_connection < INITIAL_PINGS {
            local_ms - self.last_ping >= INITIAL_PING_SPACING_MS
        } else {
            local_ms - self.last_ping >= PING_INTERVAL_MS
        };
        if !due {
            return;
        }
        let id = self.next_ping;
        self.next_ping += 1;
        self.last_ping = local_ms;
        self.pings_this_connection += 1;
        self.pings.retain(|_, t0| local_ms - *t0 < 10_000.0);
        self.pings.insert(id, local_ms);
        out.push(Output::Send(ClientFrame::Ping { id, t0: local_ms }));
    }

    fn report_player(
        &mut self,
        player: PlayerSnapshot,
        local_ms: f64,
        room_now: f64,
        out: &mut Vec<Output>,
    ) {
        let Some(item) = self.loaded_item.clone() else {
            return;
        };
        if player.buffering != self.reported_buffering {
            self.reported_buffering = player.buffering;
            if player.buffering {
                self.buffering.entry(self.cfg.me).or_insert(room_now);
            } else {
                self.buffering.remove(&self.cfg.me);
            }
            if self.connected {
                self.send(
                    Body::Buffering {
                        item: item.clone(),
                        buffering: player.buffering,
                    },
                    None,
                    false,
                    out,
                );
            }
        }
        if self.connected && local_ms - self.last_position_sent >= POSITION_HEARTBEAT_MS {
            self.last_position_sent = local_ms;
            self.send(
                Body::Position {
                    item,
                    position_ms: player.position_ms,
                },
                None,
                false,
                out,
            );
        }
    }

    fn correct(
        &mut self,
        player: PlayerSnapshot,
        local_ms: f64,
        room_now: f64,
        out: &mut Vec<Output>,
    ) {
        if self.loaded_item.is_none() || local_ms < self.settle_until {
            return;
        }
        let Some(timeline) = self.timeline.as_ref() else {
            return;
        };
        let Some((own_duration, _)) = self.ready.get(&self.cfg.me) else {
            return;
        };
        // Loaded with no length: a live stream, synced by playing or paused only.
        let live = own_duration.is_none();
        let correction = match own_duration {
            None => self.corrector.correct_live(timeline, room_now, player),
            Some(end) => {
                self.corrector
                    .correct_until(timeline, room_now, player, Some(*end as f64))
            }
        };
        let commands = match correction {
            Correction::None => return,
            // Playing a live stream again starts at the live point, not where it paused.
            Correction::Play if live => vec![PlayerCommand::GoLive],
            Correction::Play => vec![PlayerCommand::Play],
            Correction::Pause => vec![PlayerCommand::Pause],
            Correction::SetRate(rate) => vec![PlayerCommand::SetRate(rate)],
            Correction::Seek(to) => vec![PlayerCommand::Seek(to)],
            Correction::SeekAndPlay(to) => vec![PlayerCommand::Seek(to), PlayerCommand::Play],
        };
        if commands.iter().any(|c| {
            matches!(
                c,
                PlayerCommand::Seek(_) | PlayerCommand::Play | PlayerCommand::GoLive
            )
        }) {
            self.settle_until = local_ms + SETTLE_MS;
        }
        out.extend(commands.into_iter().map(Output::Player));
    }

    fn start_lead(&self) -> f64 {
        let slowest = self
            .ready
            .values()
            .filter_map(|(_, rtt)| *rtt)
            .chain(self.clock.rtt_ms())
            .fold(0.0_f64, f64::max);
        (slowest + 500.0).max(MIN_START_LEAD_MS)
    }

    /// Leader duties: schedule starts and pause for buffering.
    fn lead(&mut self, room_now: f64, out: &mut Vec<Output>) {
        // Waiting list is computed by everyone, for the UI.
        let waiting: Vec<MemberId> = {
            let mut list: Vec<_> = self
                .buffering
                .iter()
                .filter(|(m, since)| {
                    !self.ignored.contains(m)
                        && (**m == self.cfg.me || self.members.contains(m))
                        && room_now - **since >= BUFFERING_PAUSE_MS
                })
                .map(|(m, _)| *m)
                .collect();
            list.sort();
            list
        };
        if waiting != self.last_waiting {
            self.last_waiting = waiting.clone();
            out.push(Output::Event(Event::Waiting(waiting.clone())));
        }
        if self.can_sign_as_host() {
            let stuck: Vec<MemberId> = {
                let mut list: Vec<_> = self
                    .buffering
                    .iter()
                    .filter(|(m, since)| {
                        !self.ignored.contains(m) && room_now - **since >= CONTINUE_WITHOUT_MS
                    })
                    .map(|(m, _)| *m)
                    .collect();
                list.sort();
                list
            };
            if stuck != self.last_continue {
                self.last_continue = stuck.clone();
                out.push(Output::Event(Event::ContinueWithoutAvailable(stuck)));
            }
        }

        if !self.is_leader() || (self.locked && !self.can_sign_as_host()) {
            return;
        }
        let Some(item) = self.item.clone() else {
            return;
        };

        // Nobody shows the video: wait for a screen, and don't play to no one.
        let any_screen = self.members.iter().any(|m| self.is_screen(m));

        // Schedule the start once everyone online is ready, or on timeout.
        if self.timeline.is_none() {
            if !any_screen {
                return;
            }
            let everyone = self
                .members
                .iter()
                .filter(|m| self.is_screen(m))
                .all(|m| self.ready.contains_key(m) || self.ignored.contains(m));
            if everyone || room_now - self.load_seen_room >= START_TIMEOUT_MS {
                let at = room_now + self.start_lead();
                self.write_timeline(true, item.start_ms as f64, at, false, out);
            }
            return;
        }

        let Some(timeline) = self.timeline.clone() else {
            return;
        };
        // The last screen stopped watching: pause where the party is, so whoever
        // watches next picks up from there.
        if timeline.playing && !any_screen {
            self.paused_for_buffering = false;
            let position = timeline.expected_ms(room_now);
            self.write_timeline(false, position, room_now, false, out);
            return;
        }
        // The video is over: everyone stops at the end, together.
        if timeline.playing
            && let Some(end) = self.video_end()
            && timeline.expected_ms(room_now) >= end
        {
            self.paused_for_buffering = false;
            self.write_timeline(false, end, room_now, true, out);
            if self.can_sign_as_host() {
                self.issue_clear(out);
            }
            return;
        }
        if timeline.ended && self.can_sign_as_host() {
            self.issue_clear(out);
            return;
        }
        // A live stream doesn't wait for a buffering device: pausing would put
        // everyone behind live, and the slow device catches up on its own.
        if timeline.playing && !waiting.is_empty() && !self.video_live() {
            self.paused_for_buffering = true;
            let position = timeline.expected_ms(room_now);
            self.write_timeline(false, position, room_now, false, out);
        } else if self.paused_for_buffering
            && !timeline.playing
            && self.buffering.keys().all(|m| self.ignored.contains(m))
        {
            self.paused_for_buffering = false;
            let at = room_now + self.start_lead();
            self.write_timeline(true, timeline.position_ms, at, false, out);
        }
    }

    /// The video is a live stream: every screen that loaded it reported no length.
    fn video_live(&self) -> bool {
        let mut loaded = self
            .ready
            .iter()
            .filter(|(member, _)| !self.ignored.contains(member) && self.is_screen(member))
            .peekable();
        loaded.peek().is_some() && loaded.all(|(_, (duration, _))| duration.is_none())
    }

    /// Shortest known length across active screens, in milliseconds.
    fn known_duration_ms(&self) -> Option<u64> {
        self.ready
            .iter()
            .filter(|(member, _)| !self.ignored.contains(member) && self.is_screen(member))
            .filter_map(|(_, (duration, _))| *duration)
            .min()
    }

    fn sync_duration(&mut self, out: &mut Vec<Output>) {
        let next = if self.video_live() {
            None
        } else {
            self.known_duration_ms()
        };
        self.set_known_duration(next, out);
    }

    fn set_known_duration(&mut self, next: Option<u64>, out: &mut Vec<Output>) {
        if self.known_duration != next {
            self.known_duration = next;
            out.push(Output::Event(Event::Duration(next)));
        }
    }

    /// Where the video ends: the shortest length a screen reported. Live
    /// streams report none, so they never end.
    fn video_end(&self) -> Option<f64> {
        self.known_duration_ms().map(|d| d as f64)
    }

    fn expire_requests(&mut self, room_now: f64, out: &mut Vec<Output>) {
        self.pending
            .retain(|_, p| p.deadline == 0.0 || p.deadline > room_now);
        let expired: Vec<String> = self
            .my_requests
            .iter()
            .filter(|(_, deadline)| **deadline <= room_now)
            .map(|(r, _)| r.clone())
            .collect();
        for request in expired {
            self.my_requests.remove(&request);
            out.push(Output::Event(Event::RequestExpired { request }));
        }
    }

    // -------------------------------------------------------------- sending

    fn next_seq(&mut self) -> u64 {
        self.seq += 1;
        self.seq
    }

    /// Wraps a body; `rev` is folded into signed host messages so an old
    /// signed message cannot be replayed over a newer one.
    fn signed(&self, body: &Body, sign: bool, rev: u64) -> Signed {
        let mut value = serde_json::to_value(body).expect("bodies serialize");
        if rev > 0 {
            value["rev"] = rev.into();
        }
        let b = value.to_string();
        let s = (sign && self.can_sign_as_host()).then(|| self.cfg.keys.sign(b.as_bytes()));
        Signed { b, s }
    }

    fn send(&mut self, body: Body, to: Option<MemberId>, sign: bool, out: &mut Vec<Output>) {
        let signed = self.signed(&body, sign, 0);
        self.send_signed(signed, to, out);
    }

    fn send_signed(&mut self, signed: Signed, to: Option<MemberId>, out: &mut Vec<Output>) {
        if !self.connected {
            return;
        }
        let seq = self.next_seq();
        let plain = serde_json::to_vec(&signed).expect("signed serializes");
        let ct = self.cfg.invite.key.seal(
            &self.cfg.invite.room,
            Channel::Message,
            &self.cfg.me,
            seq,
            &plain,
        );
        out.push(Output::Send(ClientFrame::Msg { to, seq, ct }));
    }

    fn send_hello(&mut self, to: Option<MemberId>, out: &mut Vec<Output>) {
        let body = Body::Hello {
            member: self.cfg.me,
            name: self.cfg.name.clone(),
            kind: self.cfg.kind,
            screen: self.cfg.screen,
            box_pk: b64::encode(self.cfg.keys.box_public()),
            sign_pk: b64::encode(self.cfg.keys.signing_public()),
        };
        let b = serde_json::to_string(&body).expect("bodies serialize");
        let s = Some(self.cfg.keys.sign(b.as_bytes()));
        self.send_signed(Signed { b, s }, to, out);
    }

    fn set_screen(&mut self, screen: bool, room_now: f64, out: &mut Vec<Output>) {
        if self.cfg.screen == screen {
            return;
        }
        self.cfg.screen = screen;
        let me = self.cfg.me;
        self.loaded_item = None;
        self.sent_ready = None;
        self.ready.remove(&me);
        self.sync_duration(out);
        self.corrector.reset();
        if self.reported_buffering {
            self.reported_buffering = false;
            self.buffering.remove(&me);
            if let Some(item) = self.item.as_ref().map(|i| i.id.clone())
                && self.connected
            {
                self.send(
                    Body::Buffering {
                        item,
                        buffering: false,
                    },
                    None,
                    false,
                    out,
                );
            }
        }
        if self.connected {
            self.send_hello(None, out);
        }
        // Join the video where the party is now.
        if screen && let Some(item) = self.item.clone() {
            let start_ms = self
                .timeline
                .as_ref()
                .map(|t| t.expected_ms(room_now))
                .unwrap_or(item.start_ms as f64);
            let secrets = self.secrets_for(&item);
            out.push(Output::Player(PlayerCommand::Load {
                item,
                secrets,
                start_ms,
            }));
        }
    }

    fn issue_load(&mut self, item: SharedItem, room_now: f64, out: &mut Vec<Output>) {
        let rev = self.next_seq().max(self.load_rev + 1);
        let signed = self.signed(&Body::Load { item: item.clone() }, true, rev);
        self.load_rev = rev;
        self.snapshot.load = Some(signed.clone());
        self.snapshot.clear = None;
        self.send_signed(signed, None, out);
        self.start_item(item, room_now, out);
        self.write_state(out);
    }

    fn issue_clear(&mut self, out: &mut Vec<Output>) {
        let rev = self.next_seq().max(self.load_rev + 1);
        let signed = self.signed(&Body::Clear, true, rev);
        self.load_rev = rev;
        self.snapshot.load = None;
        self.snapshot.timeline = None;
        self.snapshot.clear = Some(signed.clone());
        self.send_signed(signed, None, out);
        self.clear_item(out);
        self.write_state(out);
    }

    fn answer(
        &mut self,
        request: String,
        pending: PendingRequest,
        allowed: bool,
        room_now: f64,
        out: &mut Vec<Output>,
    ) {
        let reply = Body::RequestReply {
            request,
            to: pending.from,
            allowed,
        };
        let signed = self.signed(&reply, true, 0);
        self.send_signed(signed, Some(pending.from), out);
        if allowed {
            self.issue_load(pending.item, room_now, out);
        }
    }

    fn add_own(&mut self, member: MemberId, box_pk: [u8; 32], out: &mut Vec<Output>) {
        self.own.insert(member, box_pk);
        if let Some(item) = self.item.clone()
            && item.shared_by == self.cfg.me
            && self.members.contains(&member)
        {
            self.send_credentials(&item.id, member, out);
        }
    }

    fn own_online(&self) -> Vec<MemberId> {
        self.own
            .keys()
            .filter(|m| self.members.contains(m))
            .copied()
            .collect()
    }

    fn send_credentials(&mut self, item: &str, member: MemberId, out: &mut Vec<Output>) {
        if let Some(box_pk) = self.own.get(&member).copied() {
            self.send_credentials_to(item, member, &box_pk, out);
        }
    }

    fn send_credentials_to(
        &mut self,
        item: &str,
        member: MemberId,
        box_pk: &[u8; 32],
        out: &mut Vec<Output>,
    ) {
        let Some(secrets) = self.my_secrets.get(item) else {
            return;
        };
        if secrets.is_empty() {
            return;
        }
        let plain = serde_json::to_vec(secrets).expect("headers serialize");
        let sealed = crypto::seal_to(box_pk, &plain, &credentials_context(&self.cfg.invite, item));
        self.send(
            Body::Credentials {
                item: item.to_owned(),
                sealed,
            },
            Some(member),
            false,
            out,
        );
    }

    fn write_timeline(
        &mut self,
        playing: bool,
        position_ms: f64,
        at_room_ms: f64,
        ended: bool,
        out: &mut Vec<Output>,
    ) {
        let Some(item) = self.item.as_ref() else {
            return;
        };
        let rev = self.timeline.as_ref().map_or(1, |t| t.rev + 1);
        let timeline = Timeline {
            item: item.id.clone(),
            playing,
            position_ms,
            at_room_ms,
            rev,
            by: self.cfg.me,
            ended,
        };
        self.accept_timeline(timeline, out);
        self.write_state(out);
    }

    fn write_state(&mut self, out: &mut Vec<Output>) {
        if !self.connected {
            return;
        }
        let b = serde_json::to_string(&self.snapshot).expect("snapshot serializes");
        let s = self
            .can_sign_as_host()
            .then(|| self.cfg.keys.sign(b.as_bytes()));
        let plain = serde_json::to_vec(&Signed { b, s }).expect("signed serializes");
        let seq = self.next_seq();
        let ct = self.cfg.invite.key.seal(
            &self.cfg.invite.room,
            Channel::State,
            &self.cfg.me,
            seq,
            &plain,
        );
        out.push(Output::Send(ClientFrame::State { seq, ct }));
    }
}

fn signed_rev(signed: &Signed) -> u64 {
    serde_json::from_str::<serde_json::Value>(&signed.b)
        .ok()
        .and_then(|v| v.get("rev").and_then(|r| r.as_u64()))
        .unwrap_or(0)
}

fn credentials_context(invite: &Invite, item: &str) -> Vec<u8> {
    let mut context = b"pb-together v1 credentials".to_vec();
    context.extend_from_slice(invite.room.as_bytes());
    context.extend_from_slice(item.as_bytes());
    context
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::RoomKey;
    use crate::messages::Source;

    fn test_invite(host: &MemberKeys) -> Invite {
        Invite {
            relay: url::Url::parse("https://relay.example").unwrap(),
            room: crate::RoomId::generate(),
            key: RoomKey::generate(),
            host: host.signing_public(),
        }
    }

    fn connected_engine(
        invite: &Invite,
        keys: MemberKeys,
        name: &str,
        screen: bool,
        members: Vec<MemberId>,
    ) -> Engine {
        connected_engine_with_kind(invite, keys, name, DeviceKind::Phone, screen, members)
    }

    fn connected_engine_with_kind(
        invite: &Invite,
        keys: MemberKeys,
        name: &str,
        kind: DeviceKind,
        screen: bool,
        members: Vec<MemberId>,
    ) -> Engine {
        let me = MemberId::from_bytes(keys.box_public()[..16].try_into().unwrap());
        let mut engine = Engine::new(EngineConfig {
            invite: invite.clone(),
            me,
            keys,
            name: name.into(),
            kind,
            screen,
            seq_start: 1,
        });
        let _ = engine.handle(
            ServerFrame::Welcome {
                member: me,
                members,
                room_time: 1_000.0,
                expires_at: 0.0,
                max_members: 16,
                state: None,
            },
            1_000.0,
        );
        engine
    }

    fn item(id: &str, shared_by: MemberId) -> SharedItem {
        SharedItem {
            id: id.into(),
            title: "Video".into(),
            site: None,
            source: Source::Url {
                url: "https://example.com/v.mp4".into(),
            },
            headers: BTreeMap::new(),
            subtitles: Vec::new(),
            start_ms: 0,
            shared_by,
        }
    }

    fn deliver(
        sender: &Engine,
        outputs: Vec<Output>,
        receiver: &mut Engine,
        now: f64,
    ) -> Vec<Output> {
        let mut out = Vec::new();
        for o in outputs {
            match o {
                Output::Send(ClientFrame::Msg { seq, ct, .. }) => {
                    out.extend(receiver.handle(
                        ServerFrame::Msg {
                            from: sender.me(),
                            seq,
                            ct,
                        },
                        now,
                    ));
                }
                Output::Send(ClientFrame::State { seq, ct }) => {
                    out.extend(receiver.handle(
                        ServerFrame::State {
                            from: sender.me(),
                            seq,
                            ct,
                        },
                        now,
                    ));
                }
                _ => {}
            }
        }
        out
    }

    #[test]
    fn clear_permission_and_snapshot_semantics() {
        let host_keys = MemberKeys::generate();
        let guest_keys = MemberKeys::generate();
        let invite = test_invite(&host_keys);
        let host_id = MemberId::from_bytes(host_keys.box_public()[..16].try_into().unwrap());
        let guest_id = MemberId::from_bytes(guest_keys.box_public()[..16].try_into().unwrap());
        let members = vec![host_id, guest_id];
        let mut host = connected_engine(&invite, host_keys, "Host", false, members.clone());
        let mut guest = connected_engine(&invite, guest_keys, "Guest", true, members);

        let load_out = host.command(Command::Load(item("v1", host_id)), 1_000.0);
        let guest_load = deliver(&host, load_out, &mut guest, 1_000.0);
        assert!(
            guest_load
                .iter()
                .any(|o| matches!(o, Output::Event(Event::VideoChanged(_))))
        );

        // By default (`OnlyHost`), a guest cannot clear.
        let guest_try = guest.command(Command::Clear, 1_100.0);
        assert!(guest_try.contains(&Output::Event(Event::Rejected(
            "not allowed to change the video"
        ))));

        // Even under `Ask`, a guest cannot clear.
        let policy_out = host.command(Command::SetPolicy(ChangePolicy::Ask), 1_200.0);
        deliver(&host, policy_out, &mut guest, 1_200.0);
        let guest_try_ask = guest.command(Command::Clear, 1_250.0);
        assert!(guest_try_ask.contains(&Output::Event(Event::Rejected(
            "not allowed to change the video"
        ))));

        // Host clears -> guest emits VideoCleared + PlayerCommand::Clear, and Snapshot has clear (no load/timeline).
        let clear_out = host.command(Command::Clear, 1_300.0);
        assert!(clear_out.contains(&Output::Event(Event::VideoCleared)));
        assert!(clear_out.contains(&Output::Player(PlayerCommand::Clear)));
        let guest_clear = deliver(&host, clear_out, &mut guest, 1_300.0);
        assert!(guest_clear.contains(&Output::Event(Event::VideoCleared)));
        assert!(guest_clear.contains(&Output::Player(PlayerCommand::Clear)));

        assert!(host.snapshot.load.is_none());
        assert!(host.snapshot.timeline.is_none());
        assert!(host.snapshot.clear.is_some());

        // Under `Anyone` (unlocked), a guest can clear after a load.
        let policy_open = host.command(Command::SetPolicy(ChangePolicy::Anyone), 1_400.0);
        deliver(&host, policy_open, &mut guest, 1_400.0);
        let load_v2 = guest.command(Command::Load(item("v2", guest_id)), 1_500.0);
        deliver(&guest, load_v2, &mut host, 1_500.0);
        let guest_clear_v2 = guest.command(Command::Clear, 1_600.0);
        let host_sees_clear = deliver(&guest, guest_clear_v2, &mut host, 1_600.0);
        assert!(host_sees_clear.contains(&Output::Event(Event::VideoCleared)));
    }

    #[test]
    fn old_snapshot_clear_field_and_manual_pause_do_not_emit_ended() {
        let host_keys = MemberKeys::generate();
        let tv_keys = MemberKeys::generate();
        let invite = test_invite(&host_keys);
        let host_id = MemberId::from_bytes(host_keys.box_public()[..16].try_into().unwrap());
        let tv_id = MemberId::from_bytes(tv_keys.box_public()[..16].try_into().unwrap());
        let members = vec![host_id, tv_id];
        let mut host = connected_engine(&invite, host_keys, "Host", false, members.clone());
        let mut tv = connected_engine(&invite, tv_keys, "TV", true, members);

        let load_out = host.command(Command::Load(item("v1", host_id)), 1_000.0);
        deliver(&host, load_out, &mut tv, 1_000.0);
        let ready_out = tv.player_loaded("v1", Some(10_000), 1_100.0);
        let host_on_ready = deliver(&tv, ready_out, &mut host, 1_100.0);
        assert!(host_on_ready.contains(&Output::Event(Event::Duration(Some(10_000)))));

        // Start playback via lead().
        let start_out = host.tick(1_200.0, None);
        deliver(&host, start_out, &mut tv, 1_200.0);
        let timeline_start = host.timeline.as_ref().unwrap().at_room_ms;

        // Manual pause near the end does NOT emit Ended.
        let pause_out = host.command(Command::Pause, timeline_start + 9_900.0);
        assert!(
            !pause_out
                .iter()
                .any(|o| matches!(o, Output::Event(Event::Ended { .. })))
        );
        let tv_pause = deliver(&host, pause_out, &mut tv, timeline_start + 9_900.0);
        assert!(
            !tv_pause
                .iter()
                .any(|o| matches!(o, Output::Event(Event::Ended { .. })))
        );

        // Resume and tick past end -> emits Ended before VideoCleared, and host auto-clears.
        let play_out = host.command(Command::Play, timeline_start + 9_950.0);
        deliver(&host, play_out, &mut tv, timeline_start + 9_950.0);
        let end_out = host.tick(timeline_start + 15_000.0, None);
        let host_ended_idx = end_out
            .iter()
            .position(|o| matches!(o, Output::Event(Event::Ended { item }) if item == "v1"))
            .expect("host emits Ended");
        let host_cleared_idx = end_out
            .iter()
            .position(|o| matches!(o, Output::Event(Event::VideoCleared)))
            .expect("host emits VideoCleared");
        assert!(
            host_ended_idx < host_cleared_idx,
            "Ended must precede VideoCleared"
        );
        assert!(end_out.contains(&Output::Player(PlayerCommand::Clear)));
        assert!(host.snapshot.load.is_none());
        assert!(host.snapshot.clear.is_some());

        let tv_end = deliver(&host, end_out, &mut tv, timeline_start + 15_000.0);
        let tv_ended_idx = tv_end
            .iter()
            .position(|o| matches!(o, Output::Event(Event::Ended { item }) if item == "v1"))
            .expect("tv emits Ended");
        let tv_cleared_idx = tv_end
            .iter()
            .position(|o| matches!(o, Output::Event(Event::VideoCleared)))
            .expect("tv emits VideoCleared");
        assert!(
            tv_ended_idx < tv_cleared_idx,
            "Ended must precede VideoCleared on peer"
        );
        assert!(tv_end.contains(&Output::Player(PlayerCommand::Clear)));
    }

    #[test]
    fn two_host_capable_members_clearing_at_end_is_harmless() {
        let host_keys = MemberKeys::generate();
        let delegate_keys = MemberKeys::generate();
        let guest_keys = MemberKeys::generate();
        let invite = test_invite(&host_keys);
        let host_id = MemberId::from_bytes(host_keys.box_public()[..16].try_into().unwrap());
        let delegate_id =
            MemberId::from_bytes(delegate_keys.box_public()[..16].try_into().unwrap());
        let guest_id = MemberId::from_bytes(guest_keys.box_public()[..16].try_into().unwrap());
        let delegate_pk = delegate_keys.signing_public();
        let members = vec![host_id, delegate_id, guest_id];
        let mut host = connected_engine(&invite, host_keys, "Host", false, members.clone());
        let mut delegate =
            connected_engine(&invite, delegate_keys, "Delegate", true, members.clone());
        let mut guest = connected_engine(&invite, guest_keys, "Guest", true, members);

        // Hello + delegation + load.
        let hello_out = delegate.tick(500.0, None);
        deliver(&delegate, hello_out, &mut host, 500.0);
        let del_out = host.command(
            Command::Delegate {
                member: delegate_id,
                sign_pk: delegate_pk,
            },
            600.0,
        );
        deliver(&host, del_out.clone(), &mut delegate, 600.0);
        deliver(&host, del_out, &mut guest, 600.0);

        let load_out = host.command(Command::Load(item("v1", host_id)), 1_000.0);
        deliver(&host, load_out.clone(), &mut delegate, 1_000.0);
        deliver(&host, load_out, &mut guest, 1_000.0);

        let d_ready = delegate.player_loaded("v1", Some(5_000), 1_100.0);
        deliver(&delegate, d_ready.clone(), &mut host, 1_100.0);
        deliver(&delegate, d_ready, &mut guest, 1_100.0);
        let g_ready = guest.player_loaded("v1", Some(5_000), 1_100.0);
        deliver(&guest, g_ready.clone(), &mut host, 1_100.0);
        deliver(&guest, g_ready, &mut delegate, 1_100.0);

        let start_out = host.tick(1_200.0, None);
        deliver(&host, start_out.clone(), &mut delegate, 1_200.0);
        deliver(&host, start_out, &mut guest, 1_200.0);

        // Simulate both host and delegate clearing simultaneously at EOF (same or different seq).
        let host_end = host.tick(10_000.0, None);
        let delegate_clear = delegate.command(Command::Clear, 10_000.0);

        let guest_from_host = deliver(&host, host_end.clone(), &mut guest, 10_000.0);
        let guest_from_delegate = deliver(&delegate, delegate_clear.clone(), &mut guest, 10_000.0);
        let host_from_delegate = deliver(&delegate, delegate_clear, &mut host, 10_000.0);
        let _ = deliver(&host, host_end, &mut delegate, 10_000.0);

        let guest_clears = guest_from_host
            .iter()
            .chain(guest_from_delegate.iter())
            .filter(|o| matches!(o, Output::Event(Event::VideoCleared)))
            .count();
        assert_eq!(
            guest_clears, 1,
            "VideoCleared should only fire once on guest"
        );
        assert!(
            !host_from_delegate
                .iter()
                .any(|o| matches!(o, Output::Event(Event::VideoCleared))),
            "host already cleared"
        );
        assert!(guest.item.is_none());
        assert!(host.item.is_none());
        assert!(delegate.item.is_none());
    }

    #[test]
    fn guest_only_room_emits_ended_without_clearing() {
        let host_keys = MemberKeys::generate();
        let g1_keys = MemberKeys::generate();
        let g2_keys = MemberKeys::generate();
        let invite = test_invite(&host_keys);
        let host_id = MemberId::from_bytes(host_keys.box_public()[..16].try_into().unwrap());
        let g1_id = MemberId::from_bytes(g1_keys.box_public()[..16].try_into().unwrap());
        let g2_id = MemberId::from_bytes(g2_keys.box_public()[..16].try_into().unwrap());
        let mut host = connected_engine(
            &invite,
            host_keys,
            "Host",
            false,
            vec![host_id, g1_id, g2_id],
        );
        let mut g1 = connected_engine(&invite, g1_keys, "G1", true, vec![host_id, g1_id, g2_id]);
        let mut g2 = connected_engine(&invite, g2_keys, "G2", true, vec![host_id, g1_id, g2_id]);

        let load_out = host.command(Command::Load(item("v1", host_id)), 1_000.0);
        deliver(&host, load_out.clone(), &mut g1, 1_000.0);
        deliver(&host, load_out, &mut g2, 1_000.0);
        let r1 = g1.player_loaded("v1", Some(5_000), 1_100.0);
        deliver(&g1, r1.clone(), &mut host, 1_100.0);
        deliver(&g1, r1, &mut g2, 1_100.0);
        let r2 = g2.player_loaded("v1", Some(5_000), 1_100.0);
        deliver(&g2, r2.clone(), &mut host, 1_100.0);
        deliver(&g2, r2, &mut g1, 1_100.0);
        let start_out = host.tick(1_200.0, None);
        deliver(&host, start_out.clone(), &mut g1, 1_200.0);
        deliver(&host, start_out, &mut g2, 1_200.0);

        // Host leaves; G1 becomes fallback leader while G1 & G2 keep watching.
        let _ = host;
        let _ = g1.handle(ServerFrame::Left { member: host_id }, 1_300.0);
        let _ = g2.handle(ServerFrame::Left { member: host_id }, 1_300.0);
        assert_eq!(g1.leader(), Some(g1_id));

        let g1_end = g1.tick(10_000.0, None);
        assert!(g1_end.contains(&Output::Event(Event::Ended { item: "v1".into() })));
        assert!(!g1_end.contains(&Output::Event(Event::VideoCleared)));
        assert!(g1.item.is_some());

        let g2_end = deliver(&g1, g1_end, &mut g2, 10_000.0);
        assert!(g2_end.contains(&Output::Event(Event::Ended { item: "v1".into() })));
        assert!(!g2_end.contains(&Output::Event(Event::VideoCleared)));
        assert!(g2.item.is_some());
    }

    #[test]
    fn live_video_never_ends_or_clears() {
        let host_keys = MemberKeys::generate();
        let tv_keys = MemberKeys::generate();
        let invite = test_invite(&host_keys);
        let host_id = MemberId::from_bytes(host_keys.box_public()[..16].try_into().unwrap());
        let tv_id = MemberId::from_bytes(tv_keys.box_public()[..16].try_into().unwrap());
        let members = vec![host_id, tv_id];
        let mut host = connected_engine(&invite, host_keys, "Host", false, members.clone());
        let mut tv = connected_engine(&invite, tv_keys, "TV", true, members);

        let load_out = host.command(Command::Load(item("live", host_id)), 1_000.0);
        deliver(&host, load_out, &mut tv, 1_000.0);
        let ready_out = tv.player_loaded("live", None, 1_100.0);
        deliver(&tv, ready_out, &mut host, 1_100.0);
        let start_out = host.tick(1_200.0, None);
        deliver(&host, start_out, &mut tv, 1_200.0);

        let later = host.tick(999_999.0, None);
        assert!(
            !later
                .iter()
                .any(|o| matches!(o, Output::Event(Event::Ended { .. })))
        );
        assert!(
            !later
                .iter()
                .any(|o| matches!(o, Output::Event(Event::VideoCleared)))
        );
        assert!(host.item.is_some());
    }

    #[test]
    fn delegated_member_is_hostish_without_member_info_host_flag() {
        let host_keys = MemberKeys::generate();
        let tv_keys = MemberKeys::generate();
        let guest_keys = MemberKeys::generate();
        let invite = test_invite(&host_keys);
        let host_id = MemberId::from_bytes(host_keys.box_public()[..16].try_into().unwrap());
        let tv_id = MemberId::from_bytes(tv_keys.box_public()[..16].try_into().unwrap());
        let guest_id = MemberId::from_bytes(guest_keys.box_public()[..16].try_into().unwrap());

        let tv_sign_pk = tv_keys.signing_public();
        let members = vec![host_id, tv_id];
        let mut host = connected_engine(&invite, host_keys, "Host phone", false, members.clone());
        let mut desktop = connected_engine_with_kind(
            &invite,
            tv_keys,
            "Desktop",
            DeviceKind::Desktop,
            true,
            members,
        );
        let mut guest = connected_engine(&invite, guest_keys, "Guest", true, vec![]);

        let del_out = host.command(
            Command::Delegate {
                member: tv_id,
                sign_pk: tv_sign_pk,
            },
            100.0,
        );
        deliver(&host, del_out, &mut guest, 100.0);

        // Host receives Desktop's Hello AFTER Command::Delegate: must see host=false.
        let desktop_hello_to_host = desktop.handle(ServerFrame::Joined { member: host_id }, 150.0);
        let host_sees = deliver(&desktop, desktop_hello_to_host, &mut host, 150.0);
        assert_eq!(
            host_sees.iter().find_map(|o| match o {
                Output::Event(Event::MemberInfo { member, kind, host, .. }) if *member == tv_id => {
                    Some((*kind, *host))
                }
                _ => None,
            }),
            Some((DeviceKind::Desktop, false))
        );

        // Guest receives Host's Hello so Host is a present known member.
        let host_hello = host.handle(ServerFrame::Joined { member: guest_id }, 120.0);
        deliver(&host, host_hello, &mut guest, 120.0);

        let desktop_hello = desktop.handle(ServerFrame::Joined { member: guest_id }, 200.0);
        let from_desktop = deliver(&desktop, desktop_hello, &mut guest, 200.0);
        let info = from_desktop
            .iter()
            .find_map(|o| match o {
                Output::Event(Event::MemberInfo {
                    member,
                    kind,
                    host,
                    ..
                }) if *member == tv_id => Some((*kind, *host)),
                _ => None,
            })
            .expect("guest should observe delegate's MemberInfo");
        assert_eq!(info, (DeviceKind::Desktop, false));
        assert!(guest.is_hostish(&tv_id));
    }

    #[test]
    fn acting_host_transitions_on_host_leave_return_and_late_delegate() {
        let host_keys = MemberKeys::generate();
        let del1_keys = MemberKeys::generate();
        let del2_keys = MemberKeys::generate();
        let guest_keys = MemberKeys::generate();
        let invite = test_invite(&host_keys);

        let host_id = MemberId::from_bytes(host_keys.box_public()[..16].try_into().unwrap());
        let del1_id = MemberId::from_bytes(del1_keys.box_public()[..16].try_into().unwrap());
        let del2_id = MemberId::from_bytes(del2_keys.box_public()[..16].try_into().unwrap());
        let guest_id = MemberId::from_bytes(guest_keys.box_public()[..16].try_into().unwrap());

        let del1_pk = del1_keys.signing_public();
        let del2_pk = del2_keys.signing_public();
        let order = vec![host_id, del1_id, del2_id, guest_id];

        let mut host =
            connected_engine(&invite, host_keys, "Host", false, order.clone());
        let mut del1 = connected_engine_with_kind(
            &invite,
            del1_keys,
            "Desktop",
            DeviceKind::Desktop,
            true,
            order.clone(),
        );
        let mut del2 = connected_engine_with_kind(
            &invite,
            del2_keys,
            "TV",
            DeviceKind::Tv,
            true,
            order.clone(),
        );
        let mut guest = connected_engine(&invite, guest_keys, "Guest", true, order);

        // Exchange Hellos so guest and del1 know everyone.
        for peer in [&mut host, &mut del1, &mut del2] {
            let hello = peer.handle(ServerFrame::Joined { member: guest_id }, 50.0);
            deliver(peer, hello, &mut guest, 50.0);
        }
        let host_hello = host.handle(ServerFrame::Joined { member: del1_id }, 50.0);
        deliver(&host, host_hello, &mut del1, 50.0);
        let del2_hello = del2.handle(ServerFrame::Joined { member: del1_id }, 50.0);
        deliver(&del2, del2_hello, &mut del1, 50.0);

        // Delegate del2 first (later member), then del1 AFTER host leaves (late delegation test on guest).
        let out2 = host.command(
            Command::Delegate {
                member: del2_id,
                sign_pk: del2_pk,
            },
            100.0,
        );
        deliver(&host, out2.clone(), &mut del1, 100.0);
        deliver(&host, out2, &mut guest, 100.0);

        // Real host is still present: badged host is still host_id.
        assert_eq!(guest.acting_host(), Some(host_id));
        assert_eq!(del1.acting_host(), Some(host_id));

        // Real host leaves: del2 becomes badged acting host.
        let g_left = guest.handle(ServerFrame::Left { member: host_id }, 200.0);
        assert!(g_left.contains(&Output::Event(Event::MemberInfo {
            member: del2_id,
            name: "TV".into(),
            kind: DeviceKind::Tv,
            host: true,
        })));
        let _ = del1.handle(ServerFrame::Left { member: host_id }, 200.0);

        // Now delegation for earliest-joined member `del1` arrives AFTER del1's Hello while host is absent:
        // del2 loses the badge and del1 takes it cleanly.
        let out1 = host.command(
            Command::Delegate {
                member: del1_id,
                sign_pk: del1_pk,
            },
            250.0,
        );
        let g_del1 = deliver(&host, out1.clone(), &mut guest, 250.0);
        assert!(g_del1.contains(&Output::Event(Event::MemberInfo {
            member: del2_id,
            name: "TV".into(),
            kind: DeviceKind::Tv,
            host: false,
        })));
        assert!(g_del1.contains(&Output::Event(Event::MemberInfo {
            member: del1_id,
            name: "Desktop".into(),
            kind: DeviceKind::Desktop,
            host: true,
        })));
        let d1_self = deliver(&host, out1, &mut del1, 250.0);
        assert!(d1_self.contains(&Output::Event(Event::MemberInfo {
            member: del1_id,
            name: "Desktop".into(),
            kind: DeviceKind::Desktop,
            host: true,
        })));

        // Real host returns (`Joined` + `Hello`): del1 loses badge, host_id regains it even at end of `members`.
        let g_ret = guest.handle(ServerFrame::Joined { member: host_id }, 300.0);
        assert!(g_ret.contains(&Output::Event(Event::MemberInfo {
            member: del1_id,
            name: "Desktop".into(),
            kind: DeviceKind::Desktop,
            host: false,
        })));
        assert!(g_ret.contains(&Output::Event(Event::MemberInfo {
            member: host_id,
            name: "Host".into(),
            kind: DeviceKind::Phone,
            host: true,
        })));
        let d1_ret = del1.handle(ServerFrame::Joined { member: host_id }, 300.0);
        assert!(d1_ret.contains(&Output::Event(Event::MemberInfo {
            member: del1_id,
            name: "Desktop".into(),
            kind: DeviceKind::Desktop,
            host: false,
        })));
        assert_eq!(guest.acting_host(), Some(host_id));
        assert_eq!(del1.acting_host(), Some(host_id));

        // Guests never get the host badge when neither host nor delegates remain.
        let _ = guest.handle(ServerFrame::Left { member: host_id }, 400.0);
        let _ = guest.handle(ServerFrame::Left { member: del1_id }, 401.0);
        let g_no_host = guest.handle(ServerFrame::Left { member: del2_id }, 402.0);
        assert_eq!(guest.acting_host(), None);
        assert!(!g_no_host
            .iter()
            .any(|o| matches!(o, Output::Event(Event::MemberInfo { host: true, .. }))));
    }
}
