//! Simulated rooms: an in-memory relay with the Room Worker's semantics,
//! simulated players and skewed device clocks, stepped in 50 ms increments.

use std::collections::BTreeMap;

use playbridge_together::engine::{Command, Engine, EngineConfig, Event, Output, PlayerCommand};
use playbridge_together::frames::{ClientFrame, ServerFrame, StateFrame};
use playbridge_together::messages::{ChangePolicy, DeviceKind, SharedItem, Source};
use playbridge_together::sync::PlayerSnapshot;
use playbridge_together::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
use url::Url;

const STEP_MS: f64 = 50.0;
const LOAD_MS: f64 = 300.0;

#[derive(Default)]
struct SimPlayer {
    item: Option<String>,
    secrets: BTreeMap<String, String>,
    position: f64,
    playing: bool,
    rate: f64,
    /// Speed error of this device's playback, for example 0.02 = 2% fast.
    skew: f64,
    loading_until: Option<f64>,
    buffering_until: f64,
    loaded: Option<(String, Option<u64>)>,
    duration: u64,
}

impl SimPlayer {
    fn apply(&mut self, cmd: PlayerCommand, now: f64) {
        match cmd {
            PlayerCommand::Load {
                item,
                secrets,
                start_ms,
            } => {
                self.item = Some(item.id);
                self.secrets = secrets;
                self.position = start_ms;
                self.playing = false;
                self.rate = 1.0;
                self.loading_until = Some(now + LOAD_MS);
            }
            PlayerCommand::Play => self.playing = true,
            PlayerCommand::Pause => self.playing = false,
            PlayerCommand::Seek(to) => self.position = to,
            PlayerCommand::SetRate(rate) => self.rate = rate,
        }
    }

    fn step(&mut self, now: f64, dt: f64) {
        if let Some(until) = self.loading_until {
            if now >= until {
                self.loading_until = None;
                self.loaded = Some((self.item.clone().unwrap(), Some(self.duration)));
            }
            return;
        }
        if self.playing && now >= self.buffering_until {
            // Like a real player, it stops at the end (still set to play).
            self.position =
                (self.position + dt * self.rate * (1.0 + self.skew)).min(self.duration as f64);
        }
    }

    fn snapshot(&self, now: f64) -> Option<PlayerSnapshot> {
        self.item.as_ref()?;
        Some(PlayerSnapshot {
            position_ms: self.position,
            playing: self.playing,
            buffering: self.loading_until.is_some() || now < self.buffering_until,
        })
    }
}

struct Device {
    engine: Engine,
    player: SimPlayer,
    /// This device's clock minus the room clock.
    clock_offset: f64,
    events: Vec<Event>,
    connected: bool,
}

struct Sim {
    now: f64,
    room: RoomId,
    key: RoomKey,
    host_keys: [u8; 64],
    devices: Vec<Device>,
    order: Vec<MemberId>,
    state: Option<StateFrame>,
    /// One-way network delay.
    latency: f64,
    queue: Vec<(f64, usize, ServerFrame)>,
}

impl Sim {
    fn new() -> Self {
        let host = MemberKeys::generate();
        Self {
            now: 1_000_000.0,
            room: RoomId::generate(),
            key: RoomKey::generate(),
            host_keys: host.to_bytes(),
            devices: Vec::new(),
            order: Vec::new(),
            state: None,
            latency: 30.0,
            queue: Vec::new(),
        }
    }

    fn invite(&self) -> Invite {
        Invite {
            relay: Url::parse("https://relay.test").unwrap(),
            room: self.room,
            key: self.key.clone(),
            host: MemberKeys::from_bytes(&self.host_keys).signing_public(),
        }
    }

    fn add(&mut self, name: &str, host: bool, screen: bool, clock_offset: f64) -> usize {
        let keys = if host {
            MemberKeys::from_bytes(&self.host_keys)
        } else {
            MemberKeys::generate()
        };
        self.add_with(name, keys, screen, clock_offset)
    }

    fn add_with(&mut self, name: &str, keys: MemberKeys, screen: bool, clock_offset: f64) -> usize {
        let me = MemberId::generate();
        let engine = Engine::new(EngineConfig {
            invite: self.invite(),
            me,
            keys,
            name: name.into(),
            kind: if screen {
                DeviceKind::Tv
            } else {
                DeviceKind::Phone
            },
            screen,
            seq_start: 1,
        });
        self.devices.push(Device {
            engine,
            player: SimPlayer {
                rate: 1.0,
                duration: 600_000,
                ..Default::default()
            },
            clock_offset,
            events: Vec::new(),
            connected: false,
        });
        let index = self.devices.len() - 1;
        self.connect(index);
        index
    }

    fn id(&self, index: usize) -> MemberId {
        self.devices[index].engine.me()
    }

    fn local(&self, index: usize) -> f64 {
        self.now + self.devices[index].clock_offset
    }

    fn connect(&mut self, index: usize) {
        let me = self.id(index);
        let joined = !self.order.contains(&me);
        if joined {
            self.order.push(me);
        }
        self.devices[index].connected = true;
        let welcome = ServerFrame::Welcome {
            member: me,
            members: self.order.clone(),
            room_time: self.now,
            expires_at: self.now + 3_600_000.0,
            max_members: 8,
            state: self.state.clone(),
        };
        self.deliver_later(index, welcome);
        if joined {
            for other in self.others(index) {
                self.deliver_later(other, ServerFrame::Joined { member: me });
            }
        }
    }

    fn others(&self, index: usize) -> Vec<usize> {
        (0..self.devices.len())
            .filter(|i| *i != index && self.devices[*i].connected)
            .collect()
    }

    fn deliver_later(&mut self, to: usize, frame: ServerFrame) {
        self.queue.push((self.now + self.latency, to, frame));
    }

    /// The relay receiving a frame from a device.
    fn relay(&mut self, from: usize, frame: ClientFrame) {
        let me = self.id(from);
        match frame {
            ClientFrame::Ping { id, t0 } => {
                // Arrives after one latency; the reply after another.
                let room_time = self.now + self.latency;
                self.queue.push((
                    self.now + 2.0 * self.latency,
                    from,
                    ServerFrame::Pong { id, t0, room_time },
                ));
            }
            ClientFrame::Msg { to, seq, ct } => {
                let out = ServerFrame::Msg { from: me, seq, ct };
                for other in self.others(from) {
                    if to.is_none() || to == Some(self.id(other)) {
                        self.queue
                            .push((self.now + 2.0 * self.latency, other, out.clone()));
                    }
                }
            }
            ClientFrame::State { seq, ct } => {
                self.state = Some(StateFrame {
                    from: me,
                    seq,
                    ct: ct.clone(),
                });
                for other in self.others(from) {
                    let out = ServerFrame::State {
                        from: me,
                        seq,
                        ct: ct.clone(),
                    };
                    self.queue.push((self.now + 2.0 * self.latency, other, out));
                }
            }
        }
    }

    fn dispatch(&mut self, index: usize, outputs: Vec<Output>) {
        for output in outputs {
            match output {
                Output::Send(frame) => self.relay(index, frame),
                Output::Player(cmd) => {
                    let now = self.local(index);
                    self.devices[index].player.apply(cmd, now)
                }
                Output::Event(event) => self.devices[index].events.push(event),
            }
        }
    }

    fn command(&mut self, index: usize, command: Command) {
        let local = self.local(index);
        let outputs = self.devices[index].engine.command(command, local);
        self.dispatch(index, outputs);
    }

    fn step(&mut self) {
        self.now += STEP_MS;
        let due: Vec<_> = {
            let (due, later): (Vec<_>, Vec<_>) =
                self.queue.drain(..).partition(|(at, _, _)| *at <= self.now);
            self.queue = later;
            due
        };
        for (_, to, frame) in due {
            if !self.devices[to].connected {
                continue;
            }
            let local = self.local(to);
            let outputs = self.devices[to].engine.handle(frame, local);
            self.dispatch(to, outputs);
        }
        for index in 0..self.devices.len() {
            let local = self.local(index);
            self.devices[index].player.step(local, STEP_MS);
            let mut outputs = Vec::new();
            if let Some((item, duration)) = self.devices[index].player.loaded.take() {
                outputs.extend(
                    self.devices[index]
                        .engine
                        .player_loaded(&item, duration, local),
                );
            }
            let snapshot = self.devices[index].player.snapshot(local);
            outputs.extend(self.devices[index].engine.tick(local, snapshot));
            self.dispatch(index, outputs);
        }
    }

    fn run(&mut self, ms: f64) {
        let steps = (ms / STEP_MS).ceil() as usize;
        for _ in 0..steps {
            self.step();
        }
    }

    fn position(&self, index: usize) -> f64 {
        self.devices[index].player.position
    }

    fn spread(&self, indices: &[usize]) -> f64 {
        let positions: Vec<f64> = indices.iter().map(|i| self.position(*i)).collect();
        positions.iter().cloned().fold(f64::MIN, f64::max)
            - positions.iter().cloned().fold(f64::MAX, f64::min)
    }

    fn saw(&self, index: usize, f: impl Fn(&Event) -> bool) -> bool {
        self.devices[index].events.iter().any(f)
    }
}

fn item(id: &str) -> SharedItem {
    SharedItem {
        id: id.into(),
        title: "Big Buck Bunny".into(),
        site: Some("example.com".into()),
        source: Source::Url {
            url: "https://example.com/v.m3u8".into(),
        },
        headers: BTreeMap::from([
            ("Referer".into(), "https://example.com".into()),
            ("Cookie".into(), "session=secret".into()),
        ]),
        subtitles: vec![],
        start_ms: 0,
        shared_by: MemberId::from_bytes([0; 16]),
    }
}

#[test]
fn screens_start_together_despite_skewed_clocks() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 5_000.0);
    let tv2 = sim.add("Bedroom", false, true, -3_000.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(3_000.0);

    let tv1_player = &sim.devices[tv1].player;
    assert!(tv1_player.playing, "tv1 should be playing");
    assert!(sim.devices[tv2].player.playing, "tv2 should be playing");
    assert!(sim.position(tv1) > 500.0);
    assert!(
        sim.spread(&[tv1, tv2]) < 150.0,
        "spread {}",
        sim.spread(&[tv1, tv2])
    );
}

#[test]
fn a_fast_device_is_nudged_back_into_step() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let tv2 = sim.add("Bedroom", false, true, 1_234.0);
    sim.devices[tv2].player.skew = 0.02;
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    let mut worst = 0.0_f64;
    for _ in 0..60 {
        sim.run(1_000.0);
        worst = worst.max(sim.spread(&[tv1, tv2]));
    }
    assert!(worst < 400.0, "worst spread {worst}");
    assert!(sim.spread(&[tv1, tv2]) < 200.0);
}

#[test]
fn a_stalled_device_pauses_everyone_until_it_recovers() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let tv2 = sim.add("Bedroom", false, true, 0.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(5_000.0);
    let local = sim.local(tv2);
    sim.devices[tv2].player.buffering_until = local + 4_000.0;
    sim.run(3_000.0);
    assert!(!sim.devices[tv1].player.playing, "tv1 should wait for tv2");
    assert!(sim.saw(
        phone,
        |e| matches!(e, Event::Waiting(list) if list.contains(&sim.id(tv2)))
    ));
    sim.run(4_000.0);
    assert!(sim.devices[tv1].player.playing, "tv1 should resume");
    assert!(
        sim.spread(&[tv1, tv2]) < 150.0,
        "spread {}",
        sim.spread(&[tv1, tv2])
    );
}

#[test]
fn late_joiners_catch_up_from_the_snapshot() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(20_000.0);
    let late = sim.add("Friend's TV", false, true, 777.0);
    sim.run(4_000.0);
    assert!(sim.devices[late].player.playing);
    assert!(
        sim.spread(&[tv1, late]) < 150.0,
        "spread {}",
        sim.spread(&[tv1, late])
    );
}

#[test]
fn pause_and_seek_from_any_member_apply_everywhere() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let guest = sim.add("Guest phone", false, false, 0.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(3_000.0);
    sim.command(guest, Command::Pause);
    sim.run(1_000.0);
    assert!(!sim.devices[tv1].player.playing);
    sim.command(guest, Command::Seek(120_000.0));
    sim.run(1_500.0);
    assert!((sim.position(tv1) - 120_000.0).abs() < 1.0);
    sim.command(guest, Command::Play);
    // A second tap while the start is scheduled changes nothing.
    let scheduled = sim.devices[guest].engine.timeline().unwrap().clone();
    sim.run(200.0);
    sim.command(guest, Command::Play);
    assert_eq!(sim.devices[guest].engine.timeline(), Some(&scheduled));
    sim.run(2_000.0);
    assert!(sim.devices[tv1].player.playing);
}

#[test]
fn locked_rooms_ignore_guest_controls() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let guest = sim.add("Guest phone", false, false, 0.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(3_000.0);
    sim.command(phone, Command::Lock(true));
    sim.run(500.0);
    assert!(sim.saw(guest, |e| *e == Event::Locked(true)));
    sim.command(guest, Command::Pause);
    sim.run(1_000.0);
    assert!(
        sim.devices[tv1].player.playing,
        "a locked room keeps playing"
    );
    sim.command(phone, Command::Pause);
    sim.run(1_000.0);
    assert!(!sim.devices[tv1].player.playing, "the host can still pause");
}

#[test]
fn guests_cannot_change_the_video_by_default() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let guest = sim.add("Guest phone", false, false, 0.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(2_000.0);
    sim.command(guest, Command::Load(item("evil")));
    sim.run(1_000.0);
    assert_eq!(sim.devices[tv1].player.item.as_deref(), Some("v1"));
    let _ = phone;
}

#[test]
fn ask_policy_lets_the_host_approve_a_guest_video() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let guest = sim.add("Guest phone", false, false, 0.0);
    sim.run(2_000.0);
    sim.command(phone, Command::SetPolicy(ChangePolicy::Ask));
    sim.run(500.0);
    sim.command(guest, Command::RequestPlay(item("guest-video")));
    sim.run(500.0);
    let request = sim.devices[phone]
        .events
        .iter()
        .find_map(|e| match e {
            Event::PlayRequested {
                request,
                title,
                site,
                ..
            } => {
                assert_eq!(title, "Big Buck Bunny");
                assert_eq!(site.as_deref(), Some("example.com"));
                Some(request.clone())
            }
            _ => None,
        })
        .expect("host saw the request");
    sim.command(
        phone,
        Command::Reply {
            request,
            allowed: true,
            always: false,
        },
    );
    sim.run(2_000.0);
    assert_eq!(sim.devices[tv1].player.item.as_deref(), Some("guest-video"));
    assert!(sim.saw(guest, |e| matches!(
        e,
        Event::RequestAnswered { allowed: true, .. }
    )));
    // The guest's cookie never reaches the host's TV.
    assert!(sim.devices[tv1].player.secrets.is_empty());
}

#[test]
fn sign_ins_reach_only_the_sharers_own_devices() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let own_tv = sim.add("Living room", false, true, 0.0);
    let guest_tv = sim.add("Friend's TV", false, true, 0.0);
    sim.run(2_000.0);
    // The phone learned this TV's member ID and key over the LAN handoff.
    let own_id = sim.id(own_tv);
    let tv_box = sim.devices[own_tv].engine.box_public();
    sim.command(
        phone,
        Command::AddOwn {
            member: own_id,
            box_pk: tv_box,
        },
    );
    sim.command(phone, Command::Load(item("v1")));
    sim.run(2_000.0);
    assert_eq!(
        sim.devices[own_tv]
            .player
            .secrets
            .get("Cookie")
            .map(String::as_str),
        Some("session=secret")
    );
    assert!(sim.devices[guest_tv].player.secrets.is_empty());
    assert_eq!(
        sim.devices[guest_tv].player.item.as_deref(),
        Some("v1"),
        "the guest still gets the video, without the sign-in"
    );

    // One-time share with the guest's TV.
    let guest_id = sim.id(guest_tv);
    sim.command(phone, Command::ShareCredentials(guest_id));
    sim.run(2_000.0);
    assert_eq!(
        sim.devices[guest_tv]
            .player
            .secrets
            .get("Cookie")
            .map(String::as_str),
        Some("session=secret")
    );
}

#[test]
fn a_tv_paired_by_code_gets_the_inviting_phones_sign_in() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(2_000.0);
    // The phone learned only the TV's key, from the pairing lookup.
    let tv_keys = MemberKeys::generate();
    sim.command(phone, Command::AddOwnKey(tv_keys.box_public()));
    let tv = sim.add_with("Paired TV", tv_keys, true, 0.0);
    let guest_tv = sim.add("Friend's TV", false, true, 0.0);
    sim.run(3_000.0);
    assert_eq!(
        sim.devices[tv]
            .player
            .secrets
            .get("Cookie")
            .map(String::as_str),
        Some("session=secret")
    );
    assert!(sim.devices[guest_tv].player.secrets.is_empty());
}

#[test]
fn a_remote_that_starts_watching_joins_the_video_in_step() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv = sim.add("Living room", false, true, 0.0);
    let guest = sim.add("Guest phone", false, false, 321.0);
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(15_000.0);
    // Forget what the remote's simulated player did so far: only the switch may start it.
    sim.devices[guest].player = SimPlayer {
        rate: 1.0,
        duration: 600_000,
        ..Default::default()
    };
    sim.command(guest, Command::SetScreen(true));
    assert!(
        sim.devices[guest].player.item.is_some(),
        "it loads the current video"
    );
    assert!(
        sim.devices[guest].player.position > 10_000.0,
        "where the party is"
    );
    // Up to half a second off after loading is closed by a speed nudge.
    sim.run(12_000.0);
    assert!(sim.devices[guest].player.playing);
    assert!(
        sim.spread(&[tv, guest]) < 150.0,
        "spread {}",
        sim.spread(&[tv, guest])
    );
}

#[test]
fn the_party_stops_at_the_end_of_the_video() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let tv2 = sim.add("Bedroom", false, true, 250.0);
    for device in &mut sim.devices {
        device.player.duration = 20_000;
    }
    sim.run(2_000.0);
    sim.command(phone, Command::Load(item("v1")));
    sim.run(30_000.0);
    let timeline = sim.devices[phone].engine.timeline().unwrap().clone();
    assert!(!timeline.playing, "the room pauses at the end");
    assert_eq!(timeline.position_ms, 20_000.0);
    for tv in [tv1, tv2] {
        assert!(sim.position(tv) <= 20_000.0);
        assert!(sim.position(tv) > 19_000.0);
    }
    // Nothing keeps bouncing: no pauses for buffering after the end.
    assert!(!sim.saw(
        phone,
        |e| matches!(e, Event::Waiting(list) if !list.is_empty())
    ));
}

#[test]
fn a_guest_cannot_pose_as_the_host() {
    let mut sim = Sim::new();
    let _phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    let guest = sim.add("Guest phone", false, false, 0.0);
    sim.run(1_000.0);
    // The guest claims the host's signing key in a hello it cannot sign.
    let invite = sim.invite();
    let from = sim.id(guest);
    let body = serde_json::json!({
        "k": "hello", "member": from.to_string(), "name": "Host phone", "kind": "phone",
        "box_pk": playbridge_together::b64::encode([1_u8; 32]),
        "sign_pk": playbridge_together::b64::encode(invite.host),
    })
    .to_string();
    let plain = serde_json::to_vec(&serde_json::json!({ "b": body, "s": "AAAA" })).unwrap();
    let seq = 99;
    let ct = invite.key.seal(
        &invite.room,
        playbridge_together::crypto::Channel::Message,
        &from,
        seq,
        &plain,
    );
    let local = sim.local(tv1);
    let outputs = sim.devices[tv1]
        .engine
        .handle(ServerFrame::Msg { from, seq, ct }, local);
    assert!(outputs.contains(&Output::Event(Event::Rejected("unproven hello"))));
    assert!(
        !outputs
            .iter()
            .any(|o| matches!(o, Output::Event(Event::MemberInfo { host: true, .. })))
    );
}

#[test]
fn replayed_messages_are_dropped() {
    let mut sim = Sim::new();
    let phone = sim.add("Host phone", true, false, 0.0);
    let tv1 = sim.add("Living room", false, true, 0.0);
    sim.run(1_000.0);
    // Capture a frame the phone sends and deliver it to the TV twice.
    let local = sim.local(phone);
    let outputs = sim.devices[phone]
        .engine
        .command(Command::Lock(true), local);
    let Some(Output::Send(ClientFrame::Msg { seq, ct, .. })) = outputs
        .into_iter()
        .find(|o| matches!(o, Output::Send(ClientFrame::Msg { .. })))
    else {
        panic!("lock should send a message");
    };
    let from = sim.id(phone);
    let local_tv = sim.local(tv1);
    let first = sim.devices[tv1].engine.handle(
        ServerFrame::Msg {
            from,
            seq,
            ct: ct.clone(),
        },
        local_tv,
    );
    assert!(first.contains(&Output::Event(Event::Locked(true))));
    let again = sim.devices[tv1]
        .engine
        .handle(ServerFrame::Msg { from, seq, ct }, local_tv);
    assert!(again.contains(&Output::Event(Event::Rejected("replayed message"))));
}
