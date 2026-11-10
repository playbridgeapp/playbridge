//! `playbridge together`: Watch Together from the terminal, with a simulated
//! player. Used to exercise the room and pairing Workers end to end before
//! any app has the feature.

use std::collections::BTreeMap;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use playbridge_together::client::{self, Player, Stop};
use playbridge_together::engine::{Command, Engine, EngineConfig, Event, PlayerCommand};
use playbridge_together::messages::{ChangePolicy, DeviceKind, SharedItem, Source};
use playbridge_together::pairing::{PairingInvite, PairingService, QrPayload};
use playbridge_together::sync::PlayerSnapshot;
use playbridge_together::{Invite, MemberId, MemberKeys, RoomId, RoomKey};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::sync::mpsc;

use crate::RunError;

pub fn usage() -> &'static str {
    r#"Watch Together (simulated player)

Usage:
  playbridge together host --relay <url> [options]
  playbridge together join <invite link> [options]
  playbridge together pair --pairing <url> [options]

host                 Create a room and print its invite link
  --relay <url>      Room relay address
  --video <url>      Load this video once connected
  --pairing <url>    Pairing service, for the `pair <code>` command
  --policy <p>       only-host (default), ask or anyone
  --screen           Also play the video here (default: remote only)

join                 Join a room as a screen
  --remote           Join as a remote instead of a screen
  --drift <percent>  Make the simulated player run fast or slow, e.g. 2 or -1.5

pair                 Wait for an invite like a TV would: show a code, then join
  --pairing <url>    Pairing service address
  --yes              Accept the invite without asking

Common options:
  --name <name>      Name shown to other members

While running, type commands:
  play | pause | seek <seconds> | load <url> | lock | unlock
  policy <only-host|ask|anyone> | request <url> | allow <id> | deny <id>
  pair <code or QR link> | own <member id> <box key> | quit"#
}

pub async fn run(arguments: &[String]) -> Result<(), RunError> {
    if arguments.is_empty() || arguments.iter().any(|a| a == "--help" || a == "-h") {
        println!("{}", usage());
        return Ok(());
    }
    let options = Options::parse(&arguments[1..])?;
    match arguments[0].as_str() {
        "host" => host(options).await,
        "join" => {
            let link = options
                .positional
                .clone()
                .ok_or("join needs an invite link")?;
            let invite = Invite::parse(&link).map_err(|e| RunError::usage(e.to_string()))?;
            let keys = MemberKeys::generate();
            let screen = !options.remote;
            room(invite, keys, options, screen, None).await
        }
        "pair" => pair(options).await,
        other => Err(RunError::usage(format!(
            "unknown together command: {other}"
        ))),
    }
}

#[derive(Default)]
struct Options {
    positional: Option<String>,
    relay: Option<String>,
    pairing: Option<String>,
    video: Option<String>,
    name: Option<String>,
    policy: Option<ChangePolicy>,
    drift: f64,
    screen: bool,
    remote: bool,
    yes: bool,
}

impl Options {
    fn parse(arguments: &[String]) -> Result<Self, RunError> {
        let mut options = Options::default();
        let mut iter = arguments.iter();
        while let Some(arg) = iter.next() {
            let mut value = |name: &str| {
                iter.next()
                    .cloned()
                    .ok_or_else(|| RunError::usage(format!("{name} needs a value")))
            };
            match arg.as_str() {
                "--relay" => options.relay = Some(value("--relay")?),
                "--pairing" => options.pairing = Some(value("--pairing")?),
                "--video" => options.video = Some(value("--video")?),
                "--name" => options.name = Some(value("--name")?),
                "--policy" => options.policy = Some(parse_policy(&value("--policy")?)?),
                "--drift" => {
                    options.drift = value("--drift")?
                        .parse::<f64>()
                        .map_err(|_| RunError::usage("--drift needs a number"))?
                        / 100.0
                }
                "--screen" => options.screen = true,
                "--remote" => options.remote = true,
                "--yes" => options.yes = true,
                other if other.starts_with("--") => {
                    return Err(RunError::usage(format!("unknown option: {other}")));
                }
                other => options.positional = Some(other.to_owned()),
            }
        }
        Ok(options)
    }
}

fn parse_policy(text: &str) -> Result<ChangePolicy, RunError> {
    match text {
        "only-host" => Ok(ChangePolicy::OnlyHost),
        "ask" => Ok(ChangePolicy::Ask),
        "anyone" => Ok(ChangePolicy::Anyone),
        _ => Err(RunError::usage("policy must be only-host, ask or anyone")),
    }
}

async fn host(options: Options) -> Result<(), RunError> {
    let relay = options.relay.clone().ok_or("host needs --relay")?;
    let relay = url::Url::parse(&relay).map_err(|_| RunError::usage("--relay must be a URL"))?;
    playbridge_together::invite::check_relay_scheme(&relay)
        .map_err(|e| RunError::usage(e.to_string()))?;
    let keys = MemberKeys::generate();
    let invite = Invite {
        relay,
        room: RoomId::generate(),
        key: RoomKey::generate(),
        host: keys.signing_public(),
    };
    println!("Invite link:\n  {}\n", invite.to_link());
    let screen = options.screen;
    let first = options.video.clone().map(|url| video(&url));
    room(invite, keys, options, screen, first).await
}

async fn pair(options: Options) -> Result<(), RunError> {
    let base = options.pairing.clone().ok_or("pair needs --pairing")?;
    let service = PairingService::new(&base).map_err(|e| RunError::usage(e.to_string()))?;
    let keys = MemberKeys::generate();
    let lobby = service
        .register(&keys)
        .await
        .map_err(|e| RunError::operation(e.to_string()))?;
    let qr = service.qr(&lobby, &keys);
    println!("Join a watch party");
    println!("  Code: {}-{}", &lobby.code[..3], &lobby.code[3..]);
    println!("  QR:   {}", qr.to_link());
    println!(
        "  Box key (for `own`): {}\n",
        playbridge_together::b64::encode(keys.box_public())
    );
    let invite = service
        .wait(&lobby, &keys)
        .await
        .map_err(|e| RunError::operation(e.to_string()))?;
    let _ = service.release(&lobby).await;
    let parsed = Invite::parse(&invite.link).map_err(|e| RunError::operation(e.to_string()))?;
    let host = parsed.relay.host_str().unwrap_or("?").to_owned();
    if !options.yes
        && !confirm(&format!(
            "Join {}'s watch party on {host}? [y/N] ",
            invite.from
        ))
        .await
    {
        println!("Declined.");
        return Ok(());
    }
    room(parsed, keys, options, true, None).await
}

async fn confirm(prompt: &str) -> bool {
    use std::io::Write;
    print!("{prompt}");
    let _ = std::io::stdout().flush();
    let mut line = String::new();
    let mut stdin = BufReader::new(tokio::io::stdin());
    stdin.read_line(&mut line).await.is_ok() && matches!(line.trim(), "y" | "Y" | "yes")
}

fn video(url: &str) -> SharedItem {
    let site = url::Url::parse(url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_owned));
    SharedItem {
        id: playbridge_together::b64::encode(MemberKeys::generate().box_public())[..12].to_owned(),
        title: url.rsplit('/').next().unwrap_or(url).to_owned(),
        site,
        source: Source::Url {
            url: url.to_owned(),
        },
        headers: BTreeMap::new(),
        subtitles: Vec::new(),
        start_ms: 0,
        shared_by: MemberId::from_bytes([0; 16]),
    }
}

async fn room(
    invite: Invite,
    keys: MemberKeys,
    options: Options,
    screen: bool,
    first: Option<SharedItem>,
) -> Result<(), RunError> {
    let me = MemberId::generate();
    let name = options.name.clone().unwrap_or_else(|| {
        if screen {
            "CLI screen".into()
        } else {
            "CLI remote".into()
        }
    });
    let pairing = options.pairing.clone();
    let link = invite.to_link();
    println!(
        "Member {me} ({name}), box key {}",
        playbridge_together::b64::encode(keys.box_public())
    );
    let engine = Engine::new(EngineConfig {
        invite,
        me,
        keys,
        name: name.clone(),
        kind: DeviceKind::Cli,
        screen,
        seq_start: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_micros() as u64)
            .unwrap_or(1),
    });
    let (commands_tx, commands_rx) = mpsc::unbounded_channel();
    let (events_tx, mut events_rx) = mpsc::unbounded_channel();
    let player = SimPlayer::new(screen, options.drift);
    let status = player.status.clone();
    let task = tokio::spawn(client::run(engine, player, commands_rx, events_tx));

    if let Some(policy) = options.policy {
        let _ = commands_tx.send(Command::SetPolicy(policy));
    }
    let mut pending_first = first;
    let mut stdin = BufReader::new(tokio::io::stdin()).lines();
    let mut report = tokio::time::interval(std::time::Duration::from_secs(1));
    loop {
        tokio::select! {
            event = events_rx.recv() => {
                let Some(event) = event else { break };
                if matches!(event, Event::Connected { .. })
                    && let Some(item) = pending_first.take() {
                        let _ = commands_tx.send(Command::Load(item));
                    }
                print_event(&event);
            }
            line = stdin.next_line() => {
                let Ok(Some(line)) = line else {
                    let _ = commands_tx.send(Command::Leave);
                    break;
                };
                match parse_command(&line) {
                    Ok(Some(Typed::Room(command))) => { let _ = commands_tx.send(command); }
                    Ok(Some(Typed::Pair(target))) => {
                        let Some(base) = pairing.clone() else { println!("! start with --pairing to send invites"); continue };
                        let link = link.clone();
                        let from = name.clone();
                        let commands = commands_tx.clone();
                        tokio::spawn(async move {
                            match send_pairing(&base, &target, link, from).await {
                                Ok(box_pk) => {
                                    // The TV this terminal paired counts as its own.
                                    let _ = commands.send(Command::AddOwnKey(box_pk));
                                    println!("✓ invite sent");
                                }
                                Err(error) => println!("! pairing failed: {error}"),
                            }
                        });
                    }
                    Ok(Some(Typed::Quit)) => { let _ = commands_tx.send(Command::Leave); break; }
                    Ok(None) => {}
                    Err(message) => println!("! {message}"),
                }
            }
            _ = report.tick(), if screen => {
                if let Ok(status) = status.lock()
                    && let Some(line) = status.as_ref() {
                        println!("  {line}");
                    }
            }
        }
    }
    match task.await {
        Ok(Ok(Stop::Left)) => Ok(()),
        Ok(Ok(stop)) => Err(RunError::operation(format!("left the room: {stop:?}"))),
        Ok(Err(error)) => Err(RunError::operation(error.to_string())),
        Err(error) => Err(RunError::operation(error.to_string())),
    }
}

enum Typed {
    Room(Command),
    Pair(String),
    Quit,
}

fn parse_command(line: &str) -> Result<Option<Typed>, String> {
    let mut words = line.split_whitespace();
    let Some(word) = words.next() else {
        return Ok(None);
    };
    let arg = words.next();
    let command = match (word, arg) {
        ("play", _) => Command::Play,
        ("pause", _) => Command::Pause,
        ("seek", Some(s)) => {
            Command::Seek(s.parse::<f64>().map_err(|_| "seek needs seconds")? * 1000.0)
        }
        ("load", Some(url)) => Command::Load(video(url)),
        ("request", Some(url)) => Command::RequestPlay(video(url)),
        ("lock", _) => Command::Lock(true),
        ("unlock", _) => Command::Lock(false),
        ("policy", Some(p)) => Command::SetPolicy(parse_policy(p).map_err(|e| e.message)?),
        ("allow", Some(id)) => Command::Reply {
            request: id.into(),
            allowed: true,
            always: false,
        },
        ("deny", Some(id)) => Command::Reply {
            request: id.into(),
            allowed: false,
            always: false,
        },
        ("own", Some(member)) => {
            let member = MemberId::parse(member).map_err(|e| e.to_string())?;
            let key = words.next().ok_or("own needs <member id> <box key>")?;
            let box_pk = playbridge_together::b64::decode_32(key).map_err(|e| e.to_string())?;
            Command::AddOwn { member, box_pk }
        }
        ("share", Some(member)) => {
            Command::ShareCredentials(MemberId::parse(member).map_err(|e| e.to_string())?)
        }
        ("pair", Some(target)) => return Ok(Some(Typed::Pair(target.to_owned()))),
        ("quit" | "exit", _) => return Ok(Some(Typed::Quit)),
        _ => return Err(format!("unknown command: {line}")),
    };
    Ok(Some(Typed::Room(command)))
}

async fn send_pairing(
    base: &str,
    target: &str,
    link: String,
    from: String,
) -> Result<[u8; 32], String> {
    let (service, code, fingerprint) = if target.contains("/p/") {
        let qr = QrPayload::parse(target).map_err(|e| e.to_string())?;
        (
            PairingService::new(qr.pairing.as_str()).map_err(|e| e.to_string())?,
            qr.code,
            Some(qr.fingerprint),
        )
    } else {
        (
            PairingService::new(base).map_err(|e| e.to_string())?,
            target.to_owned(),
            None,
        )
    };
    service
        .send_invite(&code, &PairingInvite { link, from }, fingerprint.as_deref())
        .await
        .map_err(|e| e.to_string())
}

fn print_event(event: &Event) {
    match event {
        Event::Connected { members } => println!("● connected ({} in room)", members.len()),
        Event::Disconnected => println!("○ disconnected, retrying"),
        Event::MemberJoined(m) => println!("+ {m} joined"),
        Event::MemberLeft(m) => println!("- {m} left"),
        Event::MemberInfo {
            member,
            name,
            kind,
            host,
        } => {
            println!(
                "  {member} is {name} ({kind:?}{})",
                if *host { ", host" } else { "" }
            )
        }
        Event::VideoChanged(item) => println!(
            "▶ video: {} ({})",
            item.title,
            item.site.as_deref().unwrap_or("?")
        ),
        Event::TimelineChanged(t) => println!(
            "  timeline: {} at {:.1}s (rev {})",
            if t.playing { "playing" } else { "paused" },
            t.position_ms / 1000.0,
            t.rev
        ),
        Event::Waiting(list) if list.is_empty() => println!("  nobody is buffering"),
        Event::Waiting(list) => println!("… waiting for {}", ids(list)),
        Event::ContinueWithoutAvailable(list) if !list.is_empty() => {
            println!(
                "… still waiting for {}; the host can continue without them",
                ids(list)
            )
        }
        Event::ContinueWithoutAvailable(_) => {}
        Event::PlayRequested {
            request,
            from,
            title,
            site,
        } => println!(
            "? {from} wants to play {title} ({}). Type `allow {request}` or `deny {request}`",
            site.as_deref().unwrap_or("?")
        ),
        Event::RequestAnswered { allowed, .. } => {
            println!("  request {}", if *allowed { "allowed" } else { "denied" })
        }
        Event::RequestExpired { .. } => println!("  the host didn't respond"),
        Event::Locked(locked) => {
            println!("  controls {}", if *locked { "locked" } else { "unlocked" })
        }
        Event::Policy(policy) => println!("  who can change the video: {policy:?}"),
        Event::CredentialsReceived { item } => println!("  received sign-in for {item}"),
        Event::Warning(text) => println!("! {text}"),
        Event::Live(true) => println!("  live stream: play and pause only"),
        Event::Live(false) => {}
        Event::Rejected(reason) => println!("  (dropped: {reason})"),
    }
}

fn ids(list: &[MemberId]) -> String {
    list.iter()
        .map(|m| m.to_string()[..8].to_owned())
        .collect::<Vec<_>>()
        .join(", ")
}

/// A player that only keeps time, so sync can be watched in the terminal.
struct SimPlayer {
    screen: bool,
    drift: f64,
    epoch: Instant,
    item: Option<String>,
    secrets: usize,
    position: f64,
    playing: bool,
    rate: f64,
    last: f64,
    loading_until: Option<f64>,
    loaded: Option<(String, Option<u64>)>,
    status: std::sync::Arc<std::sync::Mutex<Option<String>>>,
}

impl SimPlayer {
    fn new(screen: bool, drift: f64) -> Self {
        Self {
            screen,
            drift,
            epoch: Instant::now(),
            item: None,
            secrets: 0,
            position: 0.0,
            playing: false,
            rate: 1.0,
            last: 0.0,
            loading_until: None,
            loaded: None,
            status: Default::default(),
        }
    }

    fn now(&self) -> f64 {
        self.epoch.elapsed().as_secs_f64() * 1000.0
    }

    fn advance(&mut self) {
        let now = self.now();
        let dt = now - self.last;
        self.last = now;
        if let Some(until) = self.loading_until {
            if now >= until {
                self.loading_until = None;
                self.loaded = self.item.clone().map(|id| (id, Some(3_600_000)));
            }
            return;
        }
        if self.playing {
            self.position += dt * self.rate * (1.0 + self.drift);
        }
        if let Ok(mut status) = self.status.lock() {
            *status = self.item.as_ref().map(|_| {
                format!(
                    "{:>9.3}s  {}  rate {:.2}{}",
                    self.position / 1000.0,
                    if self.playing { "playing" } else { "paused " },
                    self.rate,
                    if self.secrets > 0 {
                        "  (with sign-in)"
                    } else {
                        ""
                    }
                )
            });
        }
    }
}

impl Player for SimPlayer {
    fn apply(&mut self, command: PlayerCommand) {
        if !self.screen {
            return;
        }
        self.advance();
        match command {
            PlayerCommand::Load {
                item,
                secrets,
                start_ms,
            } => {
                self.item = Some(item.id);
                self.secrets = secrets.len();
                self.position = start_ms;
                self.playing = false;
                self.rate = 1.0;
                self.loading_until = Some(self.now() + 500.0);
            }
            PlayerCommand::Play | PlayerCommand::GoLive => self.playing = true,
            PlayerCommand::Pause => self.playing = false,
            PlayerCommand::Seek(to) => self.position = to,
            PlayerCommand::SetRate(rate) => self.rate = rate,
        }
    }

    fn snapshot(&mut self) -> Option<PlayerSnapshot> {
        if !self.screen {
            return None;
        }
        self.advance();
        self.item.as_ref()?;
        Some(PlayerSnapshot {
            position_ms: self.position,
            playing: self.playing,
            buffering: self.loading_until.is_some(),
        })
    }

    fn take_loaded(&mut self) -> Option<(String, Option<u64>)> {
        self.loaded.take()
    }
}
