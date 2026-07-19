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
- Receivers with a separate HTTP diagnostics listener may advertise its active port in the
  optional `logs_port` TXT key. It is not part of the authenticated WSS transport.
- The WebSocket URL is `wss://<host>:<wss_port>/`. IPv6 literals must be bracketed. All protocol
  messages after the HTTP upgrade are either UTF-8 JSON text frames or the 9-byte pointer frame.
- Receivers use a locally generated TLS identity. Paired senders validate its SPKI pin on every
  connection. A changed key is a hard failure and requires explicit re-pairing.

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

## Reconnection authentication

For a known receiver, the sender establishes WSS while requiring the stored SPKI pin, then sends:

```json
{"type":"auth","token":"<stored bearer token>"}
```

The receiver replies with `auth_response`. On success it may re-assert the token, pin, and current
capability arrays. On failure the sender forgets or marks the credentials stale, closes the
connection, and requires explicit re-pairing. A sender must not fall back from a pin mismatch to
un-pinned trust.

After pairing or authentication the sender sends `context_query`. The receiver answers `context`
and replays current `status`, `tracks`, `player_settings`, and `playlist_status` where available,
so a sender reconnecting during playback can reconstruct its UI and episode queue.

## Authenticated message families

### Sender to receiver

Most actions use `{"type":"command","action":"...","payload":{...}}`:

| Action | When used | Result |
|---|---|---|
| `playlist` | Every new cast, including a single item | Replaces the live queue and starts at `startIndex` |
| `queue_add` | Lazy episode resolution or manual queueing | Appends one item without replacing playback |
| `playlist_jump` | User selects a queued item | Moves playback to the zero-based index |
| `control` | Player transport, seek, tracks, engine, or presentation setting | Routed to the active player |
| `remote` | D-pad, back/home, or volume keys | Routed to the active player/browser or system |
| `mouse` | Low-rate/fallback pointer input | Routed like the compact binary pointer frame |
| `browser` | Open a URL on a receiver with browser capability | Activates browser context |
| `browser_control` | Refresh or toggle receiver ad blocking | Controls active browser |
| `context_query` | Immediately after auth/reconnect or UI refresh | Receiver sends current context and state |

Android receiver browser administration also uses standalone `user_script`, `user_script_query`,
`user_agent`, and `user_agent_query` frames. Other receivers may ignore unsupported, well-formed
messages. Capability arrays are the feature-negotiation mechanism; senders should not offer an
engine/browser absent from those arrays.

`PlayPayload.headers`, subtitle URLs, media URLs, and bearer tokens may contain credentials. They
must not be logged. `startPositionMs`, `status.position`, and `status.duration` are milliseconds.
A one-item cast still uses `playlist`; the legacy `play` action is accepted by some receivers but
is not part of the canonical sender contract.

### Receiver to sender

| Type | When used |
|---|---|
| `context` | Active surface changes or a `context_query` arrives |
| `status` | Playback state/position changes; duration is `0` when unknown/live |
| `playlist_status` | Queue replacement, append, index change, reconnect, or clear |
| `tracks` | Audio/subtitle track list or selection changes |
| `player_settings` | Speed, adaptive quality ceiling, scaling, boost, subtitle offset, engine, and live capability changes |
| `user_scripts` / `user_agents` | Response to Android browser administration queries |
| `pong` | Response to application-level `ping` |

Receivers must tolerate unknown JSON object properties for forward compatibility. Unknown
message types/actions should be ignored or surfaced diagnostically, not treated as authenticated
commands. Senders should likewise ignore receiver events they do not understand.

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
| JSON or binary mouse/touchpad | Sends | Sends | No | Handles | No | No |
| `browser`, `browser_control` | Sends | Sends | No | Handles when browser capability exists | No | No |
| User scripts / user agents | Sends | No | No | Handles | No | No |
| `context`, `status`, `playlist_status` | Receives | Receives | Receives | Sends | Sends | Sends |
| `tracks` | Receives | Receives | Receives | Sends | Sends | Sends |
| `player_settings` | Receives | Receives | Receives | Sends | Not currently sent | Not currently sent |

The Firefox/Chromium extension is not a WSS endpoint. It sends a restricted native-messaging
request to PlayBridge Desktop; Desktop owns discovery, pairing, pinning, receiver selection, and
the WSS session. DLNA/UPnP casting is also outside this contract and uses SSDP, AVTransport SOAP,
and a phone-hosted HTTP media proxy instead.

## Heartbeats and closing

`ping` / `pong` are application-level JSON heartbeats and are separate from RFC 6455 control
frames. A normal user disconnect uses WebSocket close code `1000`. Invalid protected credentials
may use `1008`. Implementations must clear ephemeral SAS keys/nonces on success, failure, close,
or replacement by a new connection.

## Known implementation and legacy-model gaps

- `proto/messages.proto` still describes plaintext `token` / `certFingerprint` fields on
  `PairingApprovedMessage`. The live protocol uses only protected `nonce` + `ciphertext`; new code
  must follow AsyncAPI and must not reintroduce plaintext credentials.
- Protobuf does not model `tracks`, `player_settings`, browser administration, capability arrays,
  protected credential bundles, or the enriched `season` / `episode` / `imdbId` / `bingeGroup`
  fields in `playlist_status`.
- Android receiver `auth_response` commonly omits `token` because the sender already has it;
  Apple TV and Desktop may re-assert it. Senders must retain the existing token when a successful
  response omits that field.
- Apple TV still accepts the legacy `command/play` action for compatibility. Canonical senders
  emit a one-item `playlist` instead.
- Only Android TV currently emits `player_settings` and implements receiver-browser/user-script
  actions. Capability negotiation and tolerant unknown-message handling are therefore required.
- There is no protocol-version field negotiated on the wire today. `asyncapi.yaml` versioning,
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
