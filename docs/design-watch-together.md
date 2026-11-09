# Watch Together — Design and Implementation Plan

Status: proposed

## 1. Summary

A host starts a watch party. Every device in it (your TVs, a friend's TV, a
friend's phone) plays its own copy of the same video, and a small Cloudflare
service keeps play, pause, seek and position in step. Only control messages go
through Cloudflare, never video, and they are end-to-end encrypted.

The same path works on one home network and across homes. There is no separate
local-only mode.

## 2. Goals and non-goals

### Goals

- Several screens, in one home or several, playing the same video in sync.
- Joining that never needs typing on a TV.
- One sync engine in Rust, shared by every PlayBridge app.
- No hosting cost that grows with users: users deploy their own room Worker; the
  PlayBridge one is a limited shared tier.
- Nothing from a party is recorded in history or progress.

### Non-goals (for now)

- Paid hosted rooms or any billing.
- Chat, reactions, cursors or drawing.
- Google Cast, DLNA and Roku members (later, through a PlayBridge device).
- Relaying video bytes between homes.
- Self-hosting the pairing service (later; see §12).

## 3. Architecture

```text
              ┌──────────────────────────┐
 TV + phone ─►│ Pairing Worker           │  run by PlayBridge, free
              │ TV codes, QR, sealed     │
              │ invites                  │
              └──────────────────────────┘
                         │ sealed invite names the room relay
                         ▼
              ┌──────────────────────────┐
 every member►│ Room Worker              │  the user's own (recommended)
              │ clock, state, sync       │  or PlayBridge's shared, throttled one
              └──────────────────────────┘
```

| Piece | Path | Role |
| --- | --- | --- |
| Room engine | `cast/together/` (new crate) | Room connection and reconnects, clock sync, room state machine, drift decisions, encryption, member ownership. Nearly all logic lives here. |
| Bindings | `cast/ffi/` | Exposes the engine to Kotlin (phone and TV), Swift (iOS and tvOS) and Dart (desktop). |
| Player adapter | each app | Play, pause, seek, set rate, report position and buffering. |
| Party UI | each app | Start a party, invite, add screens, join prompt, status. |
| Pairing Worker | `together/pairing-worker/` | `Lobby` Durable Object: TV codes, QR codes, sealed invites. |
| Room Worker | `together/room-worker/` | `Room` Durable Object: room clock, state, message routing, limits. |
| Shared types | `together/shared/` | Envelope types and protocol version used by both Workers. |
| Contract | `protocol/` | The unencrypted envelope and the LAN `join_room` command (phone to TV). |
| CLI | `cli/` | `playbridge together join` with a fake player, for tests without app UI. |

Every PlayBridge receiver already links the Rust core: the Android TV loads
`libplaybridge_cast_core_ffi.so` for its proxy, Apple TV TestFlight builds run
`cast/build-apple-tvos.sh`, and desktop uses the Rust receiver runtime. Because
each TV runs the engine in process and drives its own player, speed control
needs no new phone-to-TV protocol command.

## 4. Members and roles

- Every device is a **member** of the room and connects to the Room Worker
  itself. A TV keeps playing in sync when the host's phone locks or leaves.
- Phones are remotes and can also be screens (the phone player).
- The **host** creates the room. The invite carries the host's public signing
  key, and the host signs every message that only a host may send (`load`,
  `lock`, `change_policy`, `request_reply`, `host`). Members drop unsigned or
  wrongly signed host messages, so a guest holding the room key still cannot act
  as host.
- When the host phone hands the room to one of its own TVs, it signs a
  delegation for that TV. If the host leaves, host duties pass to the
  earliest-joined delegated device. If none is left, the current video keeps
  playing under the existing policy, but nobody can change the video.
- Anyone can play, pause and seek. The host can turn on **Lock controls**, which
  overrides everything below.
- Every member has an **owner**: the person whose phone added it. The host's
  own devices are **mine**; anything a guest adds belongs to that guest. This
  decides what playback data each member receives (§7).
- Ownership is never self-asserted. A phone only treats a member as its own
  when it learned that member's public key itself, over the paired LAN handoff
  or the pairing flow it completed. A member's in-room claim of an owner is
  ignored for credentials.

### Who can change the video

A host setting, **Settings → Watch Together → Who can change the video**:

| Option | Behavior |
| --- | --- |
| Only me (default) | Only the host can change the video. |
| Ask me | A guest can pick **Play in party** in the cast sheet. The host sees "Alex wants to play *Title* (youtube.com)" with **Allow**, **Deny** and **Always allow Alex**. |
| Anyone | Any member can change the video. |

- Requests show the title and site only, never the full URL, which can carry
  tokens.
- A request expires after 60 seconds; the guest sees "Atul didn't respond".
- An allowed video starts like any other: everyone loads, buffers, then starts
  together (§6).

## 5. Joining

Nobody types on a TV.

| Situation | Flow |
| --- | --- |
| Friends | Host shares the invite link; it opens PlayBridge on their phone. |
| Your paired TV | Tick it in the party screen. The phone sends the invite over the existing paired LAN connection (`join_room`). One tap. |
| Friend's TV, friend has PlayBridge on their phone | The friend joins by link, then ticks their TV the same way. |
| A TV with no phone running PlayBridge nearby | See the pairing flow below. Works from anywhere. |

### Pairing flow (receivers only)

1. The TV opens **Join a watch party**, generates an X25519 key pair, and
   registers its public key with the Pairing Worker. It receives a 6-character
   code (expires in 10 minutes) and shows it with a QR code.
2. Any room member enters the code or scans the QR on their phone. The phone
   seals the invite (room relay address and room key) to the TV's public key and
   sends it to the Pairing Worker.
3. The TV opens the invite and asks: "Join Atul's watch party on
   `together.example.workers.dev`?" The user confirms with the remote.
4. The TV connects straight to the Room Worker. Pairing is done.

An honest Pairing Worker never learns which room or relay the TV joined. A
dishonest one could swap in its own key when a code is typed, so typed codes
trust the pairing service. The QR carries a fingerprint of the TV's key and the
phone checks it, so scanning does not. A code alone
gets nothing: someone in the room has to send an invite, and the TV owner has to
accept it.

Codes are only for receivers (TVs, and desktop running as a receiver). Phones
and desktop senders use the link or scan the QR.

### Invite link

```text
https://<room relay>/j/<room id>#k=<room key>&h=<host signing key>
```

The fragment never reaches the server. `h` is the host's Ed25519 public key,
which members use to check host-signed messages (§4). Rooms are created on the host's device
(random 128-bit ID, 256-bit key); the `Room` object starts when the first member
connects.

## 6. Playback sync

### Starting

1. The host picks the video and sends it to the room.
2. Every member loads it paused, buffers, and reports `ready` with the duration.
3. When everyone is ready, or after 15 seconds, the room broadcasts
   `play {position, at_room_time}`, scheduled ahead by the slowest member's
   round trip plus 500 ms (at least 800 ms). Late members seek to
   catch up.

### Clock

Each member sends 8 timed pings on joining, keeps the lowest round trip to
estimate its offset from the room clock, and repeats every 60 seconds.

### Staying in step

Each member corrects itself against the shared state
(`position + (room_now - at_room_time) × rate`):

| Drift | Correction |
| --- | --- |
| Under 150 ms | Leave it |
| 150 ms to 2 s | Play at 0.95× or 1.05× until caught up |
| Over 2 s | Seek |

Correction is local, so members do not stream positions. They send a message
only when something changes (ready, buffering, seek, a correction over 2 s) and
a `position` heartbeat every 30 seconds for the party screen. A Durable Object
is evicted after about 10 seconds without events, so this keeps rooms
hibernating between messages; reporting every 5 seconds would keep them awake.
WebSocket protocol pings are answered by Cloudflare without waking the room.

### Buffering and failures

- A member buffering for more than 2 seconds pauses everyone ("Waiting for Sam's
  TV…"). After 20 seconds the host can choose **Continue without them**.
- A member that loses its connection keeps playing on its own, reconnects with
  backoff, and resyncs.
- Room state is stored in the Durable Object, so it survives eviction.
- If members report durations more than a few seconds apart (for example ads
  stitched in server-side), everyone sees a warning that the room may drift.

## 7. What is shared

Whoever shares a video (the host, or a guest allowed by §4) shares the full
playback object. Credentials are routed by ownership: a member is **same owner**
when it belongs to the person who shared the video, and **other** otherwise.

| Field | same owner | other |
| --- | --- | --- |
| URL, including signed links | ✅ | ✅ |
| Referer, Origin, User-Agent, subtitles, title, start position | ✅ | ✅ |
| `Cookie`, `Authorization` | ✅ | ❌ by default |
| Phone-served files, LAN addresses | ✅ on the same network | ❌ blocked with a message |

- Credentials are sent per member, encrypted to that member's key, never with
  the room key, so other members cannot read them.
- If a member cannot play the video, the sharer sees "Alex's TV can't play this
  without your sign-in" and a one-time **Share my sign-in for this video** button
  with a plain warning.
- A guest's credentials never go to the host or to other guests unless that
  guest presses the same button.

### Nothing is recorded

- Party playback never goes into TV history, phone cast history, resume progress
  or Continue Watching, whoever shared the video.
- Logs redact URLs and headers.
- Room keys and shared credentials live in memory only and are dropped when the
  member leaves.

## 8. Security

- Messages are encrypted with AES-GCM using the room key. The relay sees room
  IDs, member IDs, sizes and timing only.
- The Room Worker checks a token derived from the room key (HKDF), so a room ID
  alone cannot join or spam a room.
- Each member has an X25519 key pair for per-member credential messages and an
  Ed25519 key pair for signing.
- Every sender numbers its messages (`seq`); members drop repeats and older
  numbers, so the relay cannot replay messages.
- Pairing codes expire after 10 minutes; code lookups are rate-limited per IP.
- Joining a room on any relay shows its address in the join prompt.
- The relay reports a protocol version; the app refuses incompatible relays with
  "This relay needs updating".

## 9. Hosting

### Pairing Worker

Run by PlayBridge and free for everyone. Each TV join costs a few requests and
well under a second of Durable Object time. Rate limits only stop abuse.

### Room Worker

One codebase, deployed two ways:

| | PlayBridge shared | Self-hosted (recommended) |
| --- | --- | --- |
| Devices per room | 4 | 8 (configurable) |
| Room lifetime | 3 hours | 8 hours (configurable) |
| Rooms per IP per day | a few | configurable |
| When the quota runs out | "The shared relay is busy. Deploy your own to keep watching." | The owner's own free quota |

Both use SQLite-backed Durable Objects (the kind the free plan allows),
WebSocket Hibernation and Cloudflare's free plan. Per-IP room limits are kept in
a small `Limits` Durable Object keyed by IP. Rooms close 30 minutes
after the last member leaves. On the free plan, hitting the daily limit stops the
service until reset; it never bills.

### Self-hosting a Room Worker

1. Settings → Watch Together → Room relay shows **Shared (limited)** with **Set
   up your own (free, about 2 minutes)**.
2. That opens the **Deploy to Cloudflare** page for `together/room-worker`.
3. The deployed Worker serves `/setup` with its address as a QR code and a link.
4. The user scans or pastes it in PlayBridge; the app checks the version before
   saving.
5. Creating a room on the shared relay shows "Shared relay: up to 4 devices,
   3 hours" with a link to the same setup.

Only the host's relay setting matters. Guests follow the invite link.

## 10. Protocol additions

- **Envelope** (`protocol/`, unencrypted): `v`, `room`, `from`, `to` (optional,
  for per-member messages), `seq`, `ct` (ciphertext).
- **Room messages** (inside `ct`, defined in `cast/together`): `hello`,
  `member`, `load`, `ready`, `play`, `pause`, `seek`, `position`, `buffering`,
  `leave`, `host`, `lock`, `change_policy`, `request_play`, `request_reply`,
  `credentials`, `warning`.
- **Clock**: `ping {t0}` and `pong {t0, room_time}` handled by the Room Worker
  without decryption.
- **LAN command** (phone to paired PlayBridge receiver): `join_room {invite}`,
  advertised with the `watch_together_v1` capability.

## 11. Phases

| Phase | Scope | Verification |
| --- | --- | --- |
| 0. Engine | Both Workers, `cast/together`, CLI fake player | Rust tests with simulated clocks and drift; Worker tests in local `workerd`; two CLI members in one room against a deployed Worker |
| 1. Android | Android TV and Android phone (remote and screen): invite links, LAN handoff, pairing codes, mine and guest, no-history, relay setup | Hisense, Samsung phone, a second screen |
| 2. Apple | Apple TV and iPhone | Main Bedroom Apple TV, iPhone 12 Pro Max |
| 3. Desktop | Desktop receiver and sender | Mac |
| Later | Google Cast, DLNA and Roku through a nearby PlayBridge device (seek-only); self-hosted pairing | — |

## 12. Open items

- The PlayBridge relay addresses: `workers.dev` or a subdomain of a PlayBridge
  domain. A custom domain lets invite links open the app directly (App Links
  and universal links).
- Cloudflare account access for deploying (an API token as a GitHub secret, or
  manual `wrangler deploy`).
- Self-hosted pairing: the QR already carries the pairing service address, so a
  custom pairing service only breaks typed codes between devices on different
  services.
