# PlayBridge WSS flow

This document specifies when each part of the PlayBridge receiver protocol is used. The
machine-readable message contract is [`../asyncapi.yaml`](../asyncapi.yaml). If prose and the
schema disagree, the schema defines JSON shape and this document defines sequencing and security.

## Roles and transport

- A **receiver** (Android TV, Apple TV, or Desktop) publishes `_playbridge._tcp.` over mDNS.
- A **sender** (Android phone, Apple phone, or Desktop sender mode) discovers the receiver or is
  given its IP address manually.
- The receiver is the WebSocket server. The sender is the WebSocket client.
- The preferred port is `8765`. A receiver may select another available port when that port is
  occupied. The mDNS SRV port is the active receiver endpoint, and the `wss_port` TXT key mirrors
  the active secure port for compatibility. `device_name` is only a display hint and is not
  trusted identity.
- Diagnostic logs are available only to authenticated senders, through `logs_query` and
  `logs_clear` on the WSS channel (feature `diagnostic_logs_v1`). The receiver answers the
  requesting connection alone with `logs`, and redacts URLs and credentials before persisting
  a line. The legacy `logs_port` TXT key and its unauthenticated HTTP listener are retired;
  senders must not fetch logs from an advertised port.
- The WebSocket URL is `wss://<host>:<wss_port>/`. IPv6 literals must be bracketed. All protocol
  messages after the HTTP upgrade are either UTF-8 JSON text frames or the 9-byte pointer frame.
- Receivers use a locally generated TLS identity. Paired senders validate its SPKI pin on every
  connection. A changed key is a hard failure and requires explicit re-pairing.

The fallback web-browser receiver uses a separate, reversed local transport documented by the
`browserReceiverSocket` channel in `asyncapi.yaml`. An ordinary browser opens a page served by the
sender and connects back to `/v1/browser/ws` over the same LAN. It is never advertised or found by
mDNS/SSDP. Each page session displays a short-lived six-digit code that must be approved by the
sender; v1 deliberately stores no browser credential and requires approval for every new page
session. After approval the host sends `load` and `command` frames while the page returns
capabilities, status, completion, and errors. Unknown fields and message types remain
forward-compatible.

The first connection cannot authenticate the receiver's self-signed certificate from a public
CA or a previously stored pin. The TLS channel still provides encryption, while the SAS exchange
below authenticates the endpoints and protects the credential bundle containing the pin.

## Session state machine

```text
DISCONNECTED
    |
    | mDNS/manual host + WSS upgrade
    v
TLS_CONNECTED_UNAUTHENTICATED
    |                                  |
    | no saved credentials             | saved token + SPKI pin
    v                                  v
PAIRING_COMMIT_SENT                 AUTH_SENT
    | pairing_challenge                | auth_response(success=true)
    v                                  v
PAIRING_REVEALED                   AUTHENTICATED
    | matching 6-digit SAS              |
    | pairing_confirmation              | commands/status/ping
    v                                   |
PAIRING_CONFIRMED                      |
    | pairing_approved                  |
    +---------------> AUTHENTICATED <---+
```

Any denial, invalid MAC/ciphertext, failed auth, pin mismatch, timeout, or malformed required
field closes or rejects the session. An unauthenticated connection must not execute commands.

## First-time SAS pairing

All byte concatenations below are raw bytes with no separator. All fields carried in JSON are
standard padded Base64.

### 1. Sender commit

The sender generates:

- ephemeral X25519 key pair `(senderPrivate, senderPublic)`; `senderPublic` is 32 bytes;
- random 16-byte `nonceS`;
- `commitBytes = SHA-256(senderPublic || nonceS)`.

It sends `pairing_commit` with `commit = Base64(commitBytes)`, a human-readable `deviceName`, and
a stable sender `deviceUUID`. The older `pairing_request` is retained only as a deprecated
compatibility shape and must not begin a modern pairing exchange.

### 2. Receiver challenge

The receiver applies its per-IP pairing limits, rejects concurrent/invalid handshakes, generates
an ephemeral X25519 key pair and random 16-byte `nonceT`, and sends:

```json
{"type":"pairing_challenge","tvEphPub":"<base64-32-bytes>","nonceT":"<base64-16-bytes>"}
```

### 3. Sender reveal and SAS

The sender derives the X25519 shared secret and constructs:

```text
transcript = commitBytes || tvEphPub || nonceT || senderPublic || nonceS
```

It sends `pairing_reveal` containing `senderEphPub` and `nonceS`. Both sides calculate:

```text
sasHash = SHA-256(sharedSecret || transcript)
value   = unsigned-big-endian(sasHash[0..3]) & 0x7fffffff
SAS     = zeroPad6(value mod 1_000_000)
```

The receiver verifies `SHA-256(senderEphPub || nonceS) == commitBytes` before displaying the SAS.
The sender asks the user to enter/compare the receiver's six digits. Current senders allow three
local entry attempts without restarting the cryptographic exchange. Receivers rate-limit failed
pairings by source IP (currently three failures followed by a 60-second lockout).

### 4. Sender confirmation

After the user confirms the SAS, both sides derive:

```text
prk             = HKDF-Extract-SHA256(salt = 32 zero bytes, ikm = sharedSecret)
confirmationKey = HKDF-Expand-SHA256(prk, info = UTF8("confirmationKey"), length = 32)
confirmationMac = HMAC-SHA256(confirmationKey, transcript)
```

The sender transmits `pairing_confirmation.mac = Base64(confirmationMac)`. The receiver must use
a constant-time comparison where the platform provides one and must not approve the UI request
until this MAC is valid.

### 5. Protected credentials and pin binding

The receiver creates a random bearer token and a JSON `CredentialBundle` containing the token,
its current `sha256/<base64>` SPKI pin, and receiver capability arrays (`players`, `browsers`). It
then derives:

```text
credentialKey = HKDF-Expand-SHA256(
    prk,
    info = UTF8("playbridgeCredentialKey-v1"),
    length = 32
)
aad        = SHA-256(transcript)
nonce      = random 12 bytes
ciphertext = AES-256-GCM(credentialKey, nonce, UTF8(credentialBundleJson), aad)
```

The GCM authentication tag is the final 16 bytes of `ciphertext`. The receiver sends only the
Base64 `nonce` and `ciphertext` in `pairing_approved`; credentials are never plaintext fields of
that outer frame.

The sender decrypts and validates the bundle, compares `certFingerprint` with the certificate
actually served by the current WSS connection, then stores token + pin together in platform
secure storage. A mismatch or authentication failure discards the bundle and closes the socket.
When pairing succeeds for an existing stable sender `deviceUUID`, the receiver replaces that
sender's stored device entry and revokes the previously issued token before authorizing the new
token. Re-pairing must not leave older credentials usable.

## Reconnection authentication

For a known receiver, the sender establishes WSS while requiring the stored SPKI pin, then sends:

```json
{"type":"auth","token":"<stored bearer token>"}
```

The receiver replies with `auth_response`. On success it will assert the pin and current
capability arrays. It must never contain the `token`. On failure the sender forgets or marks the credentials stale, closes the
connection, and requires explicit re-pairing. A sender must not fall back from a pin mismatch to
un-pinned trust.

After pairing or authentication the sender sends `context_query`. The receiver answers `context`
and replays current `status`, `tracks`, `player_settings`, `timeline`, `volume_state`, and
`playlist_status` where available, so a sender reconnecting during playback can reconstruct its
UI and episode queue.

## Authenticated message families

### Sender to receiver

Most actions use `{"type":"command","action":"...","payload":{...}}`:

| Action | When used | Result |
|---|---|---|
| `playlist` | Every new cast, including a single item | Replaces the live queue and starts at `startIndex` |
| `queue_add` | Lazy episode resolution or manual queueing | Appends one item without replacing playback |
| `playlist_jump` | User selects a queued item | Moves playback to the zero-based index |
| `queue_query` | Reconnect or explicit queue refresh | Returns the current queue snapshot to the requester |
| `queue_remove` | Remove one or more stable queue items | Removes by `itemId` without replacing playback |
| `queue_move` | Reorder a stable queue item | Moves by `itemId`, optionally before another item |
| `queue_clear` | Explicitly clear receiver-owned playback | Clears the active queue |
| `control` | Player transport, seek, tracks, engine, or presentation setting | Routed to the active player |
| `remote` | D-pad, back/home, or volume keys | Routed to the active player/browser or system |
| `mouse` | Low-rate/fallback pointer input | Routed like the compact binary pointer frame |
| `browser` | Open a URL on a receiver with browser capability | Activates browser context |
| `browser_control` | Refresh or toggle receiver ad blocking | Controls active browser |
| `context_query` | Immediately after auth/reconnect or UI refresh | Receiver sends current context and state |
| `screen_mirror_start` | Phone starts a WebRTC screen mirror after `auth_response.screenMirrorWebRtc=true` | Receiver creates a receive-only peer and replies `screen_mirror_ready` before the offer. Payload is `sessionId` (UUID) plus `protocolVersion: 1`. |
| `screen_mirror_offer` | Phone sends the H.264 (optional Opus) SDP offer | Receiver sets remote SDP, flushes queued ICE, and replies `screen_mirror_answer` |
| `screen_mirror_candidate` | Phone trickle-ICE (host UDP only) | Receiver adds the candidate or queues it until remote SDP is set |
| `screen_mirror_stop` | Phone ends the session | Receiver tears down the peer and does not echo stop |

Android receiver browser administration also uses standalone `user_script`, `user_script_query`,
`user_agent`, and `user_agent_query` frames. Other receivers may ignore unsupported, well-formed
messages. Capability arrays are the feature-negotiation mechanism; senders should not offer an
engine/browser absent from those arrays.

Late network subtitle attachment uses `control` with `payload.command = "add_subtitle"`
and `payload.subtitleResource` (URL, resource-scoped headers, optional label/language).
Receivers supporting it advertise `subtitle_resource_add_v1` in `auth_response.features`.
The older `add_subtitle:<url>` command remains supported for URL-only sidecars and
phone-hosted local files. Senders must not put request headers into URLs or send
credentialed sidecars through the legacy command. Receivers must apply the headers
only to the subtitle's origin, strip them on cross-origin redirects, enforce their
platform's media network policy, and clear late resource state when playback changes.

`PlayPayload.headers`, subtitle URLs, media URLs, and bearer tokens may contain credentials. They
must not be logged. `startPositionMs`, `status.position`, and `status.duration` are milliseconds.
A one-item cast still uses `playlist`; the legacy `play` action is accepted by some receivers but
is not part of the canonical sender contract. Each playlist item may independently declare
`mediaKind` as `video`, `audio`, or `image`; mixed-kind playlists retain one queue and receiver
`player` context while changing presentation. Omitted kinds are inferred. For images, a positive
`displayDurationMs` runs a pausable auto-advance timer, while omitted or zero duration keeps the
image visible until explicit navigation. Receivers advertise supported kinds through
`auth_response.mediaKinds` and echo the resolved per-item kind in `status` and
`playlist_status`. Apple TV supports all three kinds with either engine selection:
images use a shared aspect-fit ImageIO presentation; audio keeps its selected engine
with music artwork/title/artist/album beneath the transport controls. Pre-play is video-only.
Apple TV clamps image duration to 0–24 hours and starts timing after the image is
shown, loading fails, or a 15-second load fallback expires. Images are not seekable;
tracks and timeline are empty, and settings report unavailable image-inapplicable tuning.
GIFs use their first frame. JSON `command/mouse` and compact binary pointer frames
apply pan/scroll/zoom/rotate/anchor/reset only to images, never to a browser surface.
Apple image/artwork downloads retain origin-scoped headers and standard TLS trust;
page-controlled image/artwork downloads are rejected until its transport can enforce DNS/peer grants.

### Receiver to sender

| Type | When used |
|---|---|
| `context` | Active surface changes or a `context_query` arrives |
| `status` | Playback state/position changes; duration is `0` when unknown/live. Browser status may include optional `fullscreen`; player status may include optional absolute `buffered` milliseconds |
| `playlist_status` | Queue replacement, append, index change, reconnect, or clear |
| `command_result` | Targeted acknowledgement or structured rejection of a command carrying `requestId` |
| `tracks` | Audio/subtitle track list or selection changes |
| `player_settings` | Complete settings snapshot, honest availability, active engine and optional `loop` |
| `volume_state` | Scoped normalized volume/mute after native reporting support is implemented |
| `timeline` | Chapters and skip segments (`intro`/`recap`/`outro`/`preview`, `endMs: null` = to the end) for the current item: on item change (empty lists until known), when either list changes, and on `context_query`. Tagged with the same `playbackId`/`currentItemId` as `status`; senders must drop a frame whose item is not current |
| `user_scripts` / `user_agents` | Response to Android browser administration queries |
| `pong` | Response to application-level `ping` |
| `screen_mirror_ready` | Peer + renderer/pipe created; sent before the phone offers |
| `screen_mirror_answer` | Local SDP set after `screen_mirror_offer` |
| `screen_mirror_candidate` | Local host UDP ICE for the answerer |
| `screen_mirror_event` | `connected`, or `failed`/`stopped` plus a stable `reason` |

A terminal native player failure sends `status` with `state: "error"` and an optional
short, user-safe `error` reason before the player exits and sends `context: idle`.
The reason never includes media URLs, headers, tokens or credentials. Recoverable
engine failover does not emit an error status. Senders that display the reason must
retain it independently of the subsequent idle-context cleanup.

Receivers must tolerate unknown JSON object properties for forward compatibility. Unknown
message types/actions should be ignored or surfaced diagnostically, not treated as authenticated
commands. Senders should likewise ignore receiver events they do not understand.

## Shared queue control v1

Receivers advertising `queue_crud_v1`, `stable_item_ids`, and `command_results` treat playback
as receiver-owned shared state. A WebSocket connection is only a temporary authenticated
transport: disconnecting does not stop playback, and only an explicit `control/stop` or
`queue_clear` clears it.

`playlist` creates a new receiver-generated `playbackId`. Queue mutations and navigation preserve
that ID, assign or retain stable `itemId` values, and advance a monotonic `queueRevision` whenever
membership, ordering, or the current item changes. New controllers address entries by `itemId`;
legacy index-based navigation remains accepted.

Long-running automation should include `ifPlaybackId` with queue mutations. The receiver rejects
the command with `stale_playback` if another controller has replaced playback. Interactive
controllers may omit the guard when they intentionally operate on whatever is currently playing.

A controller may add a `requestId` to a command. Supporting receivers return exactly one targeted
`command_result` after applying or rejecting it. Successful mutations are then followed by a
`playlist_status` broadcast to all authenticated controllers. Receivers should make repeated
request IDs idempotent for the lifetime of their bounded result cache.

V1 queue snapshots remain complete and compact: at most 200 active items, at most 50 items in one
batch append, and no media URLs or request headers in status frames. Larger catalogs should lazily
append a bounded window. Paging and delta snapshots require a future advertised capability.

## Platform usage matrix

This is the current implementation surface, not permission to send a command without checking
advertised capabilities.

| Flow or message | Android phone sender | Apple phone sender | Desktop sender | Android TV receiver | Apple TV receiver | Desktop receiver |
|---|---:|---:|---:|---:|---:|---:|
| mDNS/manual discovery + WSS | Yes | Yes | Yes | Yes | Yes | Yes |
| SAS pairing + protected credentials | Yes | Yes | Yes | Yes | Yes | Yes |
| Token auth + SPKI pinning | Yes | Yes | Yes | Yes | Yes | Yes |
| `playlist`, `queue_add`, `playlist_jump` | Sends | Sends | Sends | Handles | Handles | Handles |
| Player `control` | Sends | Sends | Sends | Handles | Handles | Handles |
| D-pad/system `remote` | Sends | Sends | Limited | Handles | Player-focused | Volume-focused |
| JSON or binary mouse/touchpad | Sends | Sends | No | Handles | Image transforms only | Image transforms only |
| `browser`, `browser_control` | Sends | Sends | No | Handles when browser capability exists | No | No |
| User scripts / user agents | Sends | No | No | Handles | No | No |
| `context`, `status`, `playlist_status` | Receives | Receives | Receives | Sends | Sends | Sends |
| `tracks` | Receives | Receives | Receives | Sends | Sends | Sends |
| `player_settings` | Receives | Receives | Receives | Sends | Sends | Sends |
| `timeline`, `status.buffered` | Receives | No | No | No | No | Sends |
| WebRTC screen mirror (`screenMirrorWebRtc` + v1 `protocolVersion`) | Sends | No | No | Handles | No | Handles |

The Firefox/Chromium extension is not a WSS endpoint. It sends a restricted native-messaging
request to PlayBridge Desktop; Desktop owns discovery, pairing, pinning, receiver selection, and
the WSS session. DLNA/UPnP casting is also outside this contract and uses SSDP, AVTransport SOAP,
and a phone-hosted HTTP media proxy instead.

## Page-controlled media network policy

Items marked `page_cast` or `linked_page` are untrusted webpage input. The sender owns consent and
may attach at most 16 exact `allowedPrivateOrigins` entries (HTTP(S) scheme, host, and effective
port; no paths, wildcards, or subnet grants). An empty list means public destinations only.
Receivers that support page-controlled media must enforce the grant on the initial
URL, DNS answers, connected peer, every redirect and derived manifest/subtitle resource, while
keeping credentials on their declared origin. A sender must not route page-controlled media to a
receiver or transport that cannot enforce these rules.

## Heartbeats and closing

`ping` / `pong` are application-level JSON heartbeats and are separate from RFC 6455 control
frames. A normal user disconnect uses WebSocket close code `1000`. Invalid protected credentials
may use `1008`. Implementations must clear ephemeral SAS keys/nonces on success, failure, close,
or replacement by a new connection.

## Known implementation and legacy-model gaps

- `proto/messages.proto` still describes plaintext `token` / `certFingerprint` fields on
  `PairingApprovedMessage`. The live protocol uses only protected `nonce` + `ciphertext`; new code
  must follow AsyncAPI and must not reintroduce plaintext credentials.
- Protobuf does not model `tracks`, `timeline`, `status.buffered`, or all browser administration. It now includes native capability
  descriptors/protected credential bundles and complete player-settings helper fields; AsyncAPI
  remains authoritative for required JSON snapshot fields. Generated protobuf JSON adapters may
  need to normalize integer/default/presence representation before emitting canonical WSS frames.
  `StatusMessage.fullscreen` is optional and is sent by browser surfaces.
- Android TV and Apple TV advertise `player_home_v1` when remote Home finishes the player and returns to the receiver idle screen. Receivers that omit the feature keep Home unavailable in the player.
- Legacy receivers may have included the `token` in `auth_response`. Current receivers must never
  return the token, and senders must ignore it if present, retaining the token they already hold.
- Apple TV still accepts the legacy `command/play` action for compatibility. Canonical senders
  emit a one-item `playlist` instead.
- Android TV, Apple TV and Desktop emit complete `player_settings` snapshots. Apple TV
  advertises tuning per engine and restricts AVPlayer subtitle offset to its external subtitle
  overlay at runtime. Only Android TV implements receiver-browser/user-script actions.
  Capability negotiation and tolerant unknown-message handling are therefore required.
- There is no overall protocol-version field negotiated on the wire today. The optional native
  descriptor's `version` governs only that descriptor and is not a WSS/SAS version. `asyncapi.yaml` versioning,
  additive evolution, capability arrays, and tolerant readers are the current compatibility
  mechanisms until an explicit version-negotiation message is introduced.
- Runtime implementations do not yet validate every frame against AsyncAPI. Existing focused
  protocol tests and cross-platform behavior remain necessary while schema-derived validation or
  models are introduced incrementally.

## Compatibility and change policy

- The current protocol version is the `info.version` in `asyncapi.yaml`.
- Add optional fields and new message types/actions in a backward-compatible release.
- Removing/renaming a field, changing its JSON type/units, or changing cryptographic transcript
  bytes requires a major protocol version and an explicit multi-platform rollout plan.
- JSON field names are camelCase exactly as specified. Kotlin/Dart/Swift property naming is local
  implementation detail.
- Examples and tests should validate against the AsyncAPI schemas. No protobuf binary encoding is
  used on the WSS transport.

## Receiver cast history preference

A sender may set `skipHistory: true` on each `PlayPayload` in a `playlist` command
and on the item in `queue_add`. Supporting receivers keep those items out of
playback history, saved resume progress, and captured history artwork. Because
history can store the whole queue for replay, a queue containing a marked item
must not be persisted in full. The flag stays with the item across playback,
queue navigation, and renderer changes; it does not change pairing or titles.

Omitted or false retains the receiver's normal history policy. Enabling this
preference affects subsequently sent items, leaves existing history untouched,
and does not disable sender-side watch tracking. Android TV, Apple TV, and Desktop implement this
preference; older or other receivers may ignore it, so senders must not promise
history suppression on those receivers.

## Optional playback progress callback

Receivers advertising `progress_webhook_v1` accept `progressWebhook` on an authenticated
`command/playlist` payload and `progressIdentity` on each playlist item or later
`queue_add` item. The callback configuration belongs to that playback session. A new
playlist clears any previous callback when the field is absent. Queue items may retain
their nonsecret identity for episode navigation and status; the callback URL and token
must remain in memory only and must never appear in status, history, logs, media request
headers, or the browser receiver's local WebSocket frames.

```json
{
  "type": "command", "action": "playlist", "payload": {
    "items": [{
      "url": "https://media.example/video.mkv", "itemId": "episode-2",
      "progressIdentity": {
        "type": "series", "contentId": "tt123", "videoId": "tt123:1:2",
        "season": 1, "episode": 2
      }
    }],
    "progressWebhook": {
      "url": "https://sync.example.com/v1/progress",
      "bearerToken": "<session-token>"
    }
  }
}
```

The receiver posts `ProgressWebhookEvent` JSON with `Authorization: Bearer
<session-token>`. For example, the event body can be:

```json
{
  "version": 1, "eventId": "e51d", "playbackId": "a82f", "itemId": "episode-2",
  "event": "paused", "content": {
    "type": "series", "contentId": "tt123", "videoId": "tt123:1:2",
    "season": 1, "episode": 2
  },
  "positionMs": 420000, "durationMs": 3600000,
  "occurredAt": "2026-09-28T12:00:00.000Z"
}
```

`started` is sent when playback begins, `progress` about every 30 seconds while
playing, and `paused`, `stopped`, or `ended` at those transitions. A retry uses the
same `eventId`; a callback service should deduplicate by that ID. Playback with no
known positive duration is omitted. Network delivery is best effort: receivers use
short timeouts and bounded queues, and a receiver crash or network loss can drop the
last event. The callback service owns provider credentials and translates these
events into the Stremio, Nuvio, or other account APIs.

Native receivers require HTTPS on port 443, reject local/private resolved addresses,
pin each request to validated DNS answers, and do not follow redirects. The Google
Cast web receiver can only call origins approved in its deployment's
`browser-receiver-rust/web/src/progress-webhook-origins.json`; the service must allow
cross-origin requests from that receiver. The default allowlist is empty. A browser
receiver hosted on the sender posts through its native host so credentials do not
enter the local browser connection.

## Optional native receiver capabilities (Stage 5 contract)

The versioned `capabilities` descriptor is optional in the existing `auth_response` and in the
AES-GCM-protected `CredentialBundle`. Auth fields/required lists, token/SPKI meaning, SAS transcript,
HKDF labels and AAD are unchanged. Never expose the descriptor outside the protected
`pairing_approved` envelope. Senders inspect the raw descriptor version first: absent or unknown
version retains the frozen legacy sender behavior; recognized version 1 uses complete declared
operation sets without per-field platform fallback.

`surfaces` is the sole authority, mapping surface IDs to available engine maps and per-engine
transport, exact keys, pointer/image, text, settings, tracks, queue, browser and reporting sets.
Missing/empty sets are unsupported. Active surface/engine remain state (`context.engine` and
`player_settings.engine`). Informational host fields and advisory companion hints never confer
operations/trust, veto a paired/discovered OS channel, or replace independent Full control evidence.

Player-only receivers send complete `player_settings` snapshots with the existing required fields
and honest availability flags. Unsupported settings use neutral compatibility values with false
flags. Loop lives in `player_settings.loop`, never `status.loop`. This retains old snapshot parsers;
a loop-only frame is invalid.

Common volume is separate from surfaces: `scope: system` is system media volume; `scope: app` is
app attenuation. Native values use normalized 0–1 units. Relative steps and mute reuse remote
`volume_up/down` and `mute` on the advertised route. App steps default to 0.05 unless a stable step
is declared; system steps retain one native increment. New scoped absolute `command/volume`
uses payload `{scope, level}` only after `absolute` is advertised. New `volume_state` reports actual
scope with level and/or muted, only as reporting is implemented. No current values belong in auth.
App controls must be labelled App volume; they do not control tvOS system/HDMI-CEC volume.

Live native updates use `receiver_capabilities` / `ReceiverCapabilitiesFrame`, separate from the
unchanged hosted-browser `BrowserCapabilitiesFrame`. A receiver advertises `receiver_capabilities_v1`
in successful auth / protected credentials. A supporting authenticated sender then opts in with
`command/receiver_capabilities_subscribe`, payload `{}`. Without that explicit socket opt-in the
receiver never sends the new frame; pre-auth requests are ignored. Subscription is idempotent and
immediately sends the current complete descriptor at revision 1, closing the auth/subscription race.

Each changed snapshot replaces the descriptor with a strictly increasing session-local numeric
revision (1..2^53-1). Senders drop invalid/nonincreasing/stale-session updates. Unknown descriptor
versions retain the current descriptor while advancing ordering; malformed recognized v1 fails
closed. New connections clear descriptor and revision state. Auth, protected pairing and pushes
share one current receiver advertisement. Android TV rechecks Gecko package changes while running;
Desktop publishes volume-adapter availability changes to subscribers. Apple TV sends its
static descriptor at numeric revision 1 on subscription and does not push unchanged snapshots;
subscriptions are per authenticated socket and cleared on disconnect. CLI does not push. Sender support outside Android is deferred to step 9b.
See the linked capability contract for bounds, numeric JSON encoding and UI/execution fencing.

See [Receiver capabilities v1](RECEIVER_CAPABILITIES.md) for the complete shape, known identifiers,
wire mappings, settings/volume decisions, and rollout/strict-parser qualification, and
[contract fixtures](../tests/README.md) for real schema validation and retained parser checks.
