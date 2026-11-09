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
- The **host** creates the room and changes the video. Host duties pass to the
  earliest remaining member if the host leaves.
- Anyone can play, pause and seek. The host can turn on **Lock controls**.
- Each member is **mine** (added through the host's own paired phone) or
  **guest** (anything a guest device adds is also a guest). This decides what
  playback data it receives (§7).

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

The Pairing Worker never learns which room or relay the TV joined. A code alone
gets nothing: someone in the room has to send an invite, and the TV owner has to
accept it.

Codes are only for receivers (TVs, and desktop running as a receiver). Phones
and desktop senders use the link or scan the QR.

### Invite link

```text
https://<room relay>/j/<room id>#<room key>
```

The fragment never reaches the server. Rooms are created on the host's device
(random 128-bit ID, 256-bit key); the `Room` object starts when the first member
connects.

## 6. Playback sync

### Starting

1. The host picks the video and sends it to the room.
2. Every member loads it paused, buffers, and reports `ready` with the duration.
3. When everyone is ready, or after 15 seconds, the room broadcasts
   `play {position, at_room_time}` about 800 ms ahead. Late members seek to
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

Members report position every 5 seconds or on change, so the room can hibernate
between messages.

### Buffering and failures

- A member buffering for more than 2 seconds pauses everyone ("Waiting for Sam's
  TV…"). After 20 seconds the host can choose **Continue without them**.
- A member that loses its connection keeps playing on its own, reconnects with
  backoff, and resyncs.
- Room state is stored in the Durable Object, so it survives eviction.
- If members report durations more than a few seconds apart (for example ads
  stitched in server-side), everyone sees a warning that the room may drift.

## 7. What is shared

The host shares the full playback object. Credentials are routed by ownership.

| Field | mine | guest |
| --- | --- | --- |
| URL, including signed links | ✅ | ✅ |
| Referer, Origin, User-Agent, subtitles, title, start position | ✅ | ✅ |
| `Cookie`, `Authorization` | ✅ | ❌ by default |
| Phone-served files, LAN addresses | ✅ on the same network | ❌ blocked with a message |

- Credentials are sent per member, encrypted to that member's key, never with
  the room key, so guests cannot read them.
- If a guest cannot play the video, the host sees "Alex's TV can't play this
  without your sign-in" and a one-time **Share my sign-in for this video** button
  with a plain warning.

### Nothing is recorded

- Party playback never goes into TV history, phone cast history, resume progress
  or Continue Watching.
- Logs redact URLs and headers.
- Room keys and shared credentials live in memory only and are dropped when the
  member leaves.

## 8. Security

- Messages are encrypted with AES-GCM using the room key. The relay sees room
  IDs, member IDs, sizes and timing only.
- The Room Worker checks a token derived from the room key (HKDF), so a room ID
  alone cannot join or spam a room.
- Each member has its own key pair for per-member credential messages.
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

Both use WebSocket Hibernation and Cloudflare's free plan. Rooms close 30 minutes
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
  `leave`, `host`, `lock`, `credentials`, `warning`.
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
