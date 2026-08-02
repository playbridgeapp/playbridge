# PlayBridge Home Relay — Detailed Design and Implementation Plan

Status: proposed

## 1. Objective

Build a self-hosted PlayBridge Home Relay that runs on the receiver's network,
normally through Docker Compose. A phone can connect to the relay on the same
LAN or through WireGuard, inspect the receivers that the relay can see, select
one of the relay's live or saved receivers, and cast through it.

The relay is a fallback and remote-access path. Direct phone-to-receiver
casting remains the preferred path when it is healthy.

Primary outcomes:

- The phone no longer needs direct access to Google Cast port `8009`, mDNS,
  SSDP, or every receiver subnet.
- Discovery happens beside the TV, where multicast protocols work normally.
- Header-protected media can be fetched by a relay-hosted stream proxy and
  exposed to the TV through a short-lived LAN URL.
- A phone outside the home can control the relay through WireGuard without
  exposing receiver control ports to the public internet.
- Downstream receiver credentials remain on the relay. They are never copied
  to the phone.
- A failed or stale Google Cast connection is discarded and replaced with a
  fresh CastV2 session using the same lifecycle rules as the reliable CLI
  path.

## 2. Product terminology

| Term | Meaning |
| --- | --- |
| Direct route | The phone connects directly to a PlayBridge, Google Cast, DLNA, or Roku receiver. |
| Relay | The Docker-hosted PlayBridge service on the receiver's network. |
| Upstream | The authenticated phone-to-relay PlayBridge connection. |
| Downstream | The relay-to-TV/receiver connection. |
| Relay profile | A relay saved on the phone, including its endpoint, bearer token, and SPKI pin. |
| Relay target | A receiver discovered or remembered by the relay. |
| Route | A relay-owned live downstream session to one selected target. |
| Controller lease | Temporary ownership that allows one paired phone to mutate a route. |
| Saved target | A target retained by the relay after discovery ends; it may currently be offline. |

The service should be presented to users as **PlayBridge Home Relay** or
**Home Gateway**. It is not a generic TCP relay. It terminates an authenticated
PlayBridge connection, applies policy, and starts a separate downstream
protocol session.

## 3. Scope and non-goals

### In scope

- Linux Docker Compose deployment on a home LAN.
- Phone-to-relay SAS pairing, token authentication, and SPKI pinning.
- Saved relay profiles on Android and Apple phones.
- Relay-local PlayBridge, Google Cast, DLNA, and Roku discovery.
- Relay-owned saved target inventory.
- Target selection, connection progress, downstream pairing, playback,
  control, status, and explicit disconnection.
- Google Cast as the first downstream protocol.
- PlayBridge receiver support, including relay-to-TV pairing.
- Relay-hosted HLS/DASH/progressive media proxying with request headers.
- WireGuard access to a home relay without requiring multicast forwarding.
- Direct-first and configurable relay-fallback routing.
- CLI support for setup and integration testing; Desktop support after the
  phone MVP.

### Initially out of scope

- A public multi-tenant PlayBridge cloud relay.
- Automatic router configuration or public port forwarding.
- Relay chaining or loops between relays.
- Transparent forwarding of arbitrary TCP/UDP traffic.
- Uploading large phone-local files to the relay. This can be added after URL
  casting is stable.
- Guaranteeing access to origins that bind signed media URLs to the phone's
  source IP. Such origins may need a future phone-to-relay byte tunnel.
- Sending downstream PlayBridge bearer tokens or pins to the phone.
- Unattended first-time pairing with a PlayBridge TV. A human must still
  verify the code displayed by the TV.

## 4. Recommended architecture

```text
                         same LAN or WireGuard
┌──────────────┐  authenticated WSS + SPKI pin  ┌────────────────────────┐
│ Android/iOS  │ ──────────────────────────────> │ PlayBridge Home Relay  │
│ phone        │ <── inventory/status/events ── │                        │
└──────────────┘                                 │  Rust discovery        │
                                                 │  route/session manager │
                                                 │  credential store      │
                                                 │  stream proxy          │
                                                 └───────┬──────┬─────────┘
                                                         │      │
                                                CastV2   │      │ WSS/SOAP/ECP
                                                         │      │
                                                 ┌───────▼──┐ ┌─▼───────────┐
                                                 │Chromecast│ │Other targets│
                                                 └──────────┘ └─────────────┘
```

The relay has two roles:

1. It behaves like a PlayBridge receiver toward the phone. This reuses the
   existing TLS, SAS pairing, bearer-token authentication, heartbeats, command
   delivery, and status model.
2. It behaves like a PlayBridge sender toward the selected target. It uses
   Cast Core sessions, downstream PlayBridge WSS, DLNA SOAP, or Roku ECP.

After a relay target is ready, the normal `playlist`, `queue_add`,
`playlist_jump`, and `control` commands should continue to work. The relay
forwards or translates them to its active downstream route and mirrors the
downstream state using the existing `context`, `status`, `playlist_status`,
`tracks`, and `player_settings` frames.

Only relay discovery, target selection, relay-to-TV pairing, and route ownership
require new protocol messages.

## 5. Why a home gateway is preferable to a transparent relay

A raw network relay does not solve the main problems:

- Google Cast discovery is multicast and the Cast device expects the sender
  to be able to reach its local control port.
- DLNA discovery and control use SSDP and SOAP endpoints on the receiver LAN.
- A TV cannot fetch a media URL hosted on a remote phone unless routing and
  firewall rules happen to permit it.
- Forwarding the current TLS connection through a generic broker complicates
  receiver identity pinning and failure detection.

A home gateway solves these by originating each downstream connection locally.
The phone trusts the relay's stable PlayBridge identity. The relay separately
trusts any paired PlayBridge TV.

If WireGuard already routes the entire home subnet to the phone, a manually
saved direct endpoint may still work. The relay remains valuable because it
provides local discovery, a stable endpoint, header-preserving media hosting,
and protocol lifecycle management.

## 6. Identity and trust model

### 6.1 Relay identity

On first start, the relay creates and persists:

- a stable relay UUID;
- a stable display name;
- a TLS private key and certificate;
- an SPKI fingerprint derived from that certificate;
- an upstream authorized-client registry;
- a master key used to encrypt downstream credentials at rest.

The identity must live in the relay data volume. Deleting or replacing it is an
explicit factory reset because all phones will otherwise encounter a pin
mismatch.

### 6.2 Phone-to-relay pairing

The existing PlayBridge SAS flow is reused unchanged:

1. The phone opens an unpinned encrypted WSS connection for first-time pairing.
2. The phone sends `pairing_commit` with its stable phone UUID and display name.
3. The relay receiver runtime emits the pending phone name, UUID, and six-digit
   SAS.
4. The relay displays the SAS through a local administrative surface.
5. The user enters or compares that code on the phone.
6. The existing X25519/HKDF/HMAC/AES-GCM exchange produces a relay bearer token
   and the relay SPKI pin.
7. The phone stores the token and pin in platform-protected storage.

The relay must not print the SAS into ordinary retained logs. The initial
administrative surface should be a local `relayctl` command exposed through a
Unix socket, for example:

```text
docker compose exec relay playbridge-relay pending-pairings
```

A small authenticated setup UI can be added later, but it must not become an
unauthenticated public control panel.

### 6.3 Mapping authenticated clients

The current receiver runtime authenticates a connection by token digest. The
relay must persist a mapping created from the `Paired` event:

```text
token digest -> phone UUID, phone name, created time, last connected time, role
```

On `Authenticated`, the relay resolves the digest back to that stable phone
identity. This is required for controller leases, reconnect grace periods,
auditing, and forgetting one phone without affecting another.

The receiver runtime also needs host-visible authenticated connection lifecycle
events. Add an additive event for a specific `connectionId` disconnect and
either a heartbeat/activity event or a safe query for currently authenticated
connections. The relay cannot implement correct lease expiry from the existing
aggregate `ClientCount` event alone, and application-level `ping` is currently
answered inside the runtime without reaching the host.

Only token digests are stored for upstream clients. The raw token is emitted
once to the pairing client and must never enter logs or diagnostics.

### 6.4 Relay-to-PlayBridge-TV pairing

The relay acts as a normal sender:

1. The phone selects an unpaired PlayBridge target from the relay inventory.
2. The relay starts the existing downstream pairing exchange.
3. The TV displays its six-digit code.
4. The relay reports `relay_pairing_required` to the controlling phone.
5. The user enters the TV's code on the phone.
6. The phone sends the code to the relay over the authenticated upstream WSS.
7. The relay compares it locally and completes the downstream pairing.
8. The relay encrypts and stores the downstream token and SPKI pin.

The phone stores only a non-secret reference to the relay target. It never
receives the downstream token or pin.

## 7. Relay discovery and saved-target inventory

### 7.1 Discovery engine

The relay should use the existing Rust discovery engine for:

- `_playbridge._tcp.` mDNS;
- Google Cast mDNS;
- DLNA SSDP;
- Roku discovery/ECP metadata.

Use one process-wide discovery owner. Do not run competing scanners. Suggested
schedule:

- run a scan immediately at startup;
- run a short scan when a phone requests refresh;
- scan more frequently while a phone is viewing the relay inventory;
- scan at a slower background interval when idle;
- retain sticky results for a bounded TTL so the UI does not flash empty;
- keep explicitly saved targets after the live TTL expires.

The relay must filter itself from the results and reject any target advertised
with `role=relay`. Relay chaining is not supported in v1.

### 7.2 Stable target identity

Every target receives a relay-local immutable `targetId`. Identity is based on
protocol plus the best available stable identifier:

| Protocol | Preferred stable identifier |
| --- | --- |
| PlayBridge | receiver UUID |
| Google Cast | Cast/mDNS device UUID |
| DLNA | UDN/USN from the device description |
| Roku | serial/device ID |

An IP address is never the primary identity. DHCP and IPv6 changes update the
same target record. If no stable protocol identifier exists, the relay creates
an internal ID and records the protocol metadata used to re-associate it.

The target ID should be opaque, for example `rt_<base64url-random>`. A separate
authenticated `sourceIdentity` value may be returned to the phone so a direct
endpoint and a relay endpoint can be grouped when both expose the same
protocol-qualified stable ID.

### 7.3 Live and saved merge

The relay keeps two inputs:

- live discovery results, with `lastSeen` and current endpoints;
- persisted saved records, with `lastConnected`, pairing state, preferences,
  and last-known endpoints.

They merge into one target snapshot keyed by protocol and stable identity.

A target should expose these non-secret fields to authenticated phones:

- `targetId`;
- protocol-qualified `sourceIdentity`;
- display name;
- protocol;
- `availability`: `online`, `stale`, or `offline`;
- `saved` and `favorite` flags;
- `pairingState`: `not_required`, `required`, or `paired`;
- last seen and last connected timestamps;
- protocol capabilities relevant to the UI;
- whether another client currently owns its route;
- a short non-sensitive unavailable reason, if applicable.

Do not return target IP addresses, downstream bearer tokens, SPKI pins, origin
headers, or scoped proxy URLs as inventory metadata. The phone does not need
them to select a target.

### 7.4 Connecting to a saved offline target

When a user selects a saved but offline target, the relay should:

1. start a fresh bounded discovery pass for that protocol;
2. re-associate a live result by stable identity;
3. if discovery fails, try last-known addresses once with short per-address
   timeouts;
4. report `target_offline` rather than retry indefinitely.

The UI should keep the saved device visible and show the failure without
removing its credentials.

## 8. Relay advertisement and phone endpoint resolution

### 8.1 LAN advertisement

Advertise the relay using the existing `_playbridge._tcp.` service with
additive TXT metadata:

```text
role=relay
relay_version=1
uuid=<relay UUID>
device_name=<relay name>
wss_port=<active WSS port>
```

Using the existing service lets Rust discovery find the relay while the
`role=relay` field prevents it from being shown as a normal playback receiver.
Discovery models should gain an endpoint role rather than adding a fake media
protocol.

### 8.2 WireGuard and manual setup

mDNS generally does not cross WireGuard, and the design must not depend on an
mDNS reflector. The phone can add a relay manually using:

- a WireGuard IP;
- a DNS name resolved through the WireGuard configuration;
- `host:port` entry;
- a QR/deep link containing relay name, UUID hint, host candidates, and WSS
  port.

The setup QR must not contain a bearer token or private key. It only provides
connection hints; SAS pairing still establishes trust.

### 8.3 Saved endpoint resolution

A relay profile may contain several endpoint candidates:

- last successful LAN address;
- WireGuard address;
- configured DNS name;
- current mDNS addresses.

The phone should race or sequentially try candidates with bounded timeouts,
prefer the most recently successful candidate, and require the stored SPKI pin
on every authenticated reconnect. A discovery result with the same UUID but a
different pin is a hard failure, never an automatic re-pair.

## 9. Protocol extension

The extension must be additive and specified first in
`protocol/asyncapi.yaml` and `protocol/docs/WSS_FLOW.md`. Generated models and
all hand-written consumers must then be updated.

### 9.1 Relay capability negotiation

Add an optional `features` array to `CredentialBundle` and `auth_response`.
The relay advertises:

```json
{"features":["relay.v1"]}
```

Ordinary receivers send an empty or absent list. Phones must not show relay UI
until `relay.v1` is present, even when mDNS reported `role=relay`.

Adding `features` to the protected credential bundle is backward compatible;
it does not change the cryptographic transcript. Unknown fields remain
tolerated by older clients.

### 9.2 Commands from phone to relay

Use the authenticated `command` envelope and add the following actions:

| Action | Purpose |
| --- | --- |
| `relay_snapshot_query` | Get the complete target inventory and active route. |
| `relay_scan` | Request a bounded discovery refresh. |
| `relay_target_select` | Resolve/connect one target and acquire a controller lease. |
| `relay_pairing_confirm` | Submit the code displayed by a downstream PlayBridge TV. |
| `relay_target_disconnect` | End the downstream route without unpairing it. |
| `relay_target_forget` | Remove a saved target and its downstream credentials. |
| `relay_lease_takeover` | Explicitly request control from another paired phone. |
| `relay_operation_cancel` | Cancel an in-flight scan, selection, or pairing operation. |

Every relay command carries a client-generated `requestId`. Mutating requests
also carry an idempotency key so a reconnect/retry cannot launch the receiver
or replace the playlist twice.

Add optional `requestId` and `idempotencyKey` fields to the common authenticated
`CommandEnvelope` as well. Existing receivers may ignore them. The relay uses
them for normal `playlist`, `queue_add`, `playlist_jump`, and non-idempotent
controls after target selection, allowing a canonical command to receive a
correlated `relay_operation` without wrapping the entire playback contract in
a second message type.

Example snapshot request:

```json
{
  "type": "command",
  "action": "relay_snapshot_query",
  "payload": {
    "requestId": "01J...",
    "includeOffline": true
  }
}
```

Example target selection:

```json
{
  "type": "command",
  "action": "relay_target_select",
  "payload": {
    "requestId": "01J...",
    "idempotencyKey": "01J...",
    "targetId": "rt_xxx",
    "takeover": false
  }
}
```

Once selection reaches `ready`, existing `playlist` and `control` commands
apply to the authenticated client's active route. This avoids duplicating the
full media protocol inside relay-specific envelopes.

### 9.3 Frames from relay to phone

Add these receiver frames:

| Frame | Purpose |
| --- | --- |
| `relay_snapshot` | Complete revisioned inventory, scan state, and active route. |
| `relay_target_update` | Incremental target changes after a snapshot. |
| `relay_operation` | Correlated accepted/progress/succeeded/failed response. |
| `relay_pairing_required` | Requests the TV-displayed code for downstream pairing. |
| `relay_route_state` | Reports downstream connection and ownership transitions. |

The first `relay_snapshot` after authentication is authoritative. Incremental
events include a monotonically increasing revision. If the phone detects a
revision gap, it requests another snapshot.

Suggested route states:

```text
none
resolving
pairing_required
connecting
ready
casting
reconnecting
ended
failed
```

Suggested operation error codes:

```text
relay_not_ready
permission_denied
lease_conflict
target_not_found
target_offline
target_pairing_required
pairing_expired
pairing_rejected
connection_timeout
receiver_ended
media_proxy_failed
media_load_failed
unsupported_protocol
invalid_request
```

User-visible messages are localized on the phone from these stable codes. The
relay may include a redacted diagnostic message for debug builds.

### 9.4 Snapshot shape

An illustrative response:

```json
{
  "type": "relay_snapshot",
  "requestId": "01J...",
  "revision": 42,
  "relay": {
    "uuid": "...",
    "name": "Home Relay",
    "version": "0.1.0"
  },
  "scan": {
    "state": "idle",
    "lastCompletedAt": 1780000000000
  },
  "targets": [
    {
      "targetId": "rt_xxx",
      "sourceIdentity": "google_cast:abcd",
      "name": "Master bedroom TV",
      "protocol": "google_cast",
      "availability": "online",
      "saved": true,
      "favorite": false,
      "pairingState": "not_required",
      "lastSeen": 1780000000000,
      "lastConnected": 1779999000000,
      "capabilities": ["play", "pause", "seek", "volume"]
    }
  ],
  "activeRoute": null
}
```

Exact schema types, length limits, timestamp units, and additional-property
rules must be defined in AsyncAPI before implementation.

### 9.5 Canonical playback state

After a route is selected, the relay mirrors target state through existing
frames:

- `context.active` represents the downstream active surface;
- `status` represents downstream playback;
- `playlist_status` represents the relay-owned queue;
- `tracks` and `player_settings` are sent only when supported;
- `pong` remains the upstream application heartbeat.

The upstream connection being healthy does not mean the selected target is
ready. The phone only changes its cast icon to connected after
`relay_route_state=ready` or `casting`.

## 10. Controller leases and multiple phones

The receiver runtime permits multiple authenticated clients, but two phones
must not silently fight over one TV.

For v1:

- one controller lease exists per relay route;
- the lease owner is the stable upstream phone identity, not a socket ID;
- selecting a free target acquires the lease;
- host-visible authenticated connection activity/heartbeats renew it;
- a dropped phone connection receives a short reconnect grace period;
- reconnecting with the same token digest reclaims the lease;
- playback continues while the phone is temporarily disconnected;
- another phone can observe state but mutating commands return
  `lease_conflict`;
- takeover requires an explicit user action and visible confirmation;
- forgetting an upstream phone immediately revokes its token and lease.

The relay should initially allow one active downstream route globally. The data
model may support multiple routes later, but the first UI and protocol should
not imply concurrency that has not been tested.

## 11. Downstream connection lifecycle

### 11.1 Common state machine

```text
NO_TARGET
    -> RESOLVING
    -> PAIRING_REQUIRED (PlayBridge only when unpaired)
    -> CONNECTING
    -> READY
    -> CASTING
    -> RECONNECTING (bounded transient recovery only)
    -> ENDED or FAILED
```

The relay must separate these facts:

- the phone is authenticated to the relay;
- a target is selected;
- a downstream protocol session is ready;
- media has been accepted;
- playback is active.

No UI or API should collapse them into one Boolean `connected` value.

### 11.2 Google Cast

- Run CastV2 from the relay host, which is on the Chromecast's LAN.
- A new target selection starts from a clean Rust session unless the relay owns
  a currently healthy session to that same receiver.
- Readiness requires app ID, receiver session ID, transport ID, transport
  channel connection, and successful media `GET_STATUS`.
- If the receiver app is backgrounded or ended from the TV, mark the route
  ended and discard the native handle.
- A later cast starts a completely new connection and receiver launch.
- For a short transport interruption during active playback, use bounded
  backoff. If the old session cannot be proven healthy, close it before
  creating another one.
- Preserve explicit HLS segment-format metadata and relay-proxy behavior.
- Report `LOAD` acknowledgement and media-status errors to the controlling
  phone instead of leaving a spinner active.

### 11.3 PlayBridge receiver

- Use the relay's downstream sender identity.
- Reuse saved token + SPKI pin only for the matching protocol-qualified UUID.
- On auth rejection, retain the target but clear only its downstream token.
- Never fall back from a pin mismatch to unpinned pairing.
- Query context immediately after auth/reconnect and mirror it upstream.
- The phone-entered TV code is used only for the current downstream pairing
  exchange and is not persisted.

### 11.4 DLNA and Roku

- Re-enrich live DLNA description/control URLs before connecting.
- Store stable device identity and last-known description URL, not only a SOAP
  control URL that may expire or change.
- Normalize protocol polling into the canonical PlayBridge status model.
- Apply short, bounded timeouts and surface unsupported controls explicitly.
- Add these after Google Cast and PlayBridge routes are stable.

## 12. Media routing and the relay stream proxy

The relay should embed `stream-proxy-rust` as a library rather than run an
independent unauthenticated registration service.

### 12.1 URL casting

When the phone sends a playlist item:

1. The relay validates the item and headers.
2. The routing policy decides direct versus relay-proxied media.
3. For proxying, the relay registers the origin URL and headers internally.
4. It selects an address reachable by the downstream target.
5. It sends the resulting scoped LAN URL to the target.
6. HLS and DASH child resources remain on the same proxy route.
7. The registration is revoked when the route ends or its TTL expires.

Proxy by default when any of these are true:

- request headers are present;
- the content is HLS/DASH and needs manifest rewriting;
- the URL is otherwise not expected to be reachable by the target;
- the user configured relay proxying as always-on.

A public progressive URL without headers may be sent directly when policy
allows it.

### 12.2 Choosing the advertised media address

The URL given to the TV must contain the relay's receiver-LAN address, not the
container loopback address or a phone-only WireGuard address. Configuration
must support an explicit `PB_RELAY_MEDIA_HOST` for hosts with multiple
interfaces. Automatic selection should be logged by interface name and redacted
address class, without logging scoped URLs.

Docker host networking is recommended because it avoids an extra NAT boundary
for multicast discovery and TV-to-proxy traffic.

### 12.3 Local phone files

A phone-local `content://`, `file://`, or loopback URL cannot automatically be
fetched by a home relay. V1 should reject it with a clear
`phone_local_media_unavailable` error rather than produce a broken TV URL.

A future upload/tunnel design should use:

- authenticated resumable upload or pull;
- quotas and free-space checks;
- short-lived object IDs;
- cancellation and cleanup;
- no raw filesystem paths in protocol responses.

### 12.4 Origin and proxy security

- Limit header count, name length, value length, redirects, and total request
  metadata size.
- Strip hop-by-hop headers and never permit a caller-supplied `Host` header.
- Revalidate every redirect destination to prevent DNS rebinding/SSRF.
- Deny relay control/admin ports and loopback/link-local destinations.
- Make private-network origin access opt-in because legitimate home media
  servers and SSRF use the same address ranges.
- Keep raw URLs, cookies, authorization values, tokens, and scoped proxy URLs
  out of persisted/release logs.
- Bound proxy sessions, concurrent upstream requests, HLS depth, and cached
  bytes.

## 13. Phone data model

Relay records must be separate from ordinary saved receivers.

Suggested conceptual models:

```text
SavedRelayProfile
  relayId
  name
  uuid
  endpointCandidates[]
  wssPort
  token
  certFingerprint
  features[]
  lastConnected
  preferredEndpoint
  fallbackPolicy

SavedRelayTargetReference
  relayId
  targetId
  sourceIdentity
  protocol
  lastKnownName
  lastUsed
```

`SavedRelayTargetReference` is an optional UI cache only. The relay snapshot is
authoritative. It contains no downstream credentials or addresses.

### Android storage

- Add relay profiles to the connection store using a versioned model.
- Encrypt token/pin material with Android Keystore-backed protection, matching
  the existing saved receiver behavior.
- Expose relay profile, relay connection, inventory, route, and operation state
  through `StateFlow`.
- Keep direct `ReceiverEndpoint` identity separate from relay profile identity.

### Apple storage

- Store complete relay profiles in Keychain-backed storage.
- Keep the same protocol-qualified and relay-qualified identity rules as
  Android.
- Preserve SPKI validation on every reconnect.
- Publish relay connection and route states independently so SwiftUI does not
  infer target readiness from the upstream socket.

## 14. Phone runtime flows

The phone should model three independent state domains. Keeping them separate
prevents the existing class of bugs where an open socket is mistaken for a
ready receiver.

```text
RelayConnectionState
  disconnected -> resolving -> connecting -> pairing/authenticating -> ready

RelayInventoryState
  unavailable -> loading -> loaded -> refreshing -> error-with-stale-data

RelayRouteState
  none -> resolving -> pairing_required -> connecting -> ready/casting
       -> reconnecting -> ended/failed
```

### 14.1 First-time relay setup

1. The user chooses **Add Home Relay**.
2. The phone obtains endpoint hints from LAN discovery, QR/deep link, or manual
   host/port entry.
3. The phone creates a temporary unsaved profile and opens the existing
   first-pairing WSS path.
4. The relay UUID/name from discovery is treated only as a hint until SAS
   completes.
5. The user retrieves the relay code through `relayctl` and enters/confirms it
   on the phone.
6. The phone decrypts the protected credential bundle, verifies that its SPKI
   pin matches the certificate used by the current socket, and requires
   `features` to contain `relay.v1`.
7. Only then does the phone persist `SavedRelayProfile`.
8. The connection remains open and the phone immediately requests a relay
   snapshot.

If any validation fails, discard the temporary profile and credential bundle.
Do not save a half-paired relay.

### 14.2 Reconnecting to a saved relay

1. Load the profile from protected storage.
2. Build endpoint candidates from the preferred endpoint, WireGuard/DNS hints,
   last successful LAN address, and matching live discovery.
3. Attempt candidates with a bounded Happy-Eyeballs-style strategy.
4. Require the saved SPKI pin during TLS establishment.
5. Authenticate with the saved relay token.
6. Confirm `relay.v1` remains advertised.
7. Update only volatile endpoint and `lastConnected` fields after success.
8. Send `context_query` and `relay_snapshot_query`.

Authentication rejection may clear that relay's token while retaining the
non-secret profile for explicit re-pairing. A pin mismatch retains the profile
and displays a security error; it must not clear the old pin or begin pairing.

### 14.3 Loading what the relay can see

The phone starts with any cached target references marked stale, then replaces
them with the first authoritative `relay_snapshot`.

For each snapshot:

1. verify relay UUID and schema/capability version;
2. reject a revision older than the currently applied snapshot;
3. replace the authoritative target map atomically;
4. reconcile the active route and controller lease;
5. group direct and relayed routes only when `sourceIdentity` matches;
6. persist only optional non-secret display references;
7. apply later `relay_target_update` frames in revision order;
8. request a new snapshot after any revision gap.

Refresh sends `relay_scan` and keeps the last snapshot visible with a progress
indicator. An empty in-progress scan must not clear saved or sticky targets.

### 14.4 Selecting a live or saved target

1. The user taps a relay target.
2. The phone sends `relay_target_select` with a request ID and idempotency key.
3. It keeps the dialog open and follows correlated `relay_operation` and
   `relay_route_state` events.
4. For an online target, the relay connects immediately.
5. For a stale/offline saved target, the relay performs its bounded resolution
   flow before using last-known endpoints.
6. For an unpaired PlayBridge TV, the phone presents the downstream code-entry
   step and sends `relay_pairing_confirm`.
7. For a lease conflict, the phone offers an explicit takeover action rather
   than automatically stealing control.
8. The phone considers the target selected only at route state `ready`.
9. It stores the non-secret relay/target reference as recently used.

Cancel sends a correlated cancellation or target disconnect when the operation
already created a downstream session. Local UI cancellation alone is
insufficient because it could leave the relay holding a route and lease.

### 14.5 Casting and controlling through the selected route

Once ready, the phone uses its existing sender pipeline:

1. media detection/resolution produces the normal `PlaylistPayload`;
2. the phone sends `playlist` over the authenticated relay WSS;
3. the relay validates lease ownership and route readiness;
4. the relay applies media routing/proxy policy;
5. the relay loads the downstream target;
6. the phone waits for an acknowledged operation and canonical status;
7. existing queue, control, track, and player-setting UI consumes mirrored
   frames without a second relay-specific playback model.

The phone should retain the original playlist locally for retry UX, but it must
not retry a mutating request without the same idempotency key.

### 14.6 Reconnect and process restoration

- If phone-to-relay WSS drops, preserve the displayed route as reconnecting,
  reconnect with the saved pin/token, and request context plus snapshot.
- If the same phone reconnects inside the lease grace period, reclaim its route
  without relaunching the receiver.
- If the route ended while disconnected, replace stale now-playing state with
  the snapshot's `ended`/`none` state.
- If the relay restarted, it authenticates normally but reports no live route;
  the next cast creates a new downstream session.
- If only the downstream connection drops, keep the upstream relay connection
  ready and display the route's bounded reconnect/failure progress.
- After phone process death, restore relay profiles but initialize both relay
  and route state as disconnected until authenticated evidence arrives.

### 14.7 Suggested phone component boundaries

Keep relay networking out of the direct Cast target implementations:

```text
RelayProfileStore
  protected relay identities and endpoint hints

RelayConnectionClient
  WSS pairing/auth, pinning, heartbeat, frame correlation

RelayInventoryRepository
  snapshots, revisions, target updates, refresh lifecycle

RelayRouteCoordinator
  selection, downstream pairing, leases, route operations

ConnectionViewModel
  combines direct discovery/routes and relay sources for the UI
```

Android implementations should expose these through `StateFlow`. Apple
implementations should publish equivalent independent observable state. The UI
may compose the states, but the networking layer should not collapse them.

## 15. Phone connection and selection UX

### 15.1 Device dialog structure

The device dialog should have two top-level sources:

```text
Cast directly
  Saved TVs
  Nearby devices

Home relays
  Home Relay             Online via WireGuard
  Cabin Relay            Offline
```

Tapping a relay opens an in-dialog relay target view rather than dismissing the
dialog:

```text
< Home Relay                         Refresh

Available now
  Master bedroom TV       Google Cast
  Living room TV          PlayBridge

Saved on this relay
  Projector               DLNA · Offline
```

The dialog remains visible through:

- relay connection/authentication;
- inventory loading;
- target resolution;
- downstream pairing;
- target connection;
- receiver readiness.

It closes only after the selected route reaches `ready`, or when the user
cancels.

### 15.2 Status language

Use precise stages:

- Connecting to Home Relay…
- Loading devices visible to Home Relay…
- Looking for Master bedroom TV…
- Enter the code shown on Living room TV
- Starting Google Cast receiver…
- Ready to cast via Home Relay
- Home Relay is online, but the TV is unavailable

Do not display “Connected” merely because the relay WSS authenticated.

### 15.3 Grouping direct and relay routes

When a direct endpoint and relay target have the same protocol-qualified stable
identity, show one logical device with route choices:

```text
Master bedroom TV
  Direct
  Via Home Relay
```

If identity cannot be proven, keep them separate. Do not merge solely by name
or IP address.

### 15.4 Routing preferences

Per-phone settings:

- **Direct only** — never use a relay automatically.
- **Ask after direct failure** — recommended default.
- **Automatically use saved relay fallback** — only for a previously mapped
  direct endpoint and relay target.
- **Prefer relay** — useful when WireGuard or header proxying is consistently
  required.

The active route is always visible in the now-playing UI, for example
“Master bedroom TV via Home Relay.”

## 16. Direct-to-relay fallback policy

Fallback is allowed only for transport failures:

- no route to host;
- connection refused/unreachable;
- bounded connection timeout;
- direct discovery unavailable;
- local media proxy address not reachable by the target.

Fallback must not occur for:

- SPKI pin mismatch;
- rejected or expired credentials;
- pairing denial;
- malformed authenticated protocol data;
- explicit user cancellation;
- a receiver reporting a definitive unsupported-media error.

Automatic fallback also requires a saved mapping between the direct endpoint's
protocol-qualified stable ID and the relay target. If no mapping exists, show a
choice instead of guessing by display name.

Suggested attempt flow:

```text
direct connect
  -> success: use direct route
  -> transport failure: locate saved relay mapping
       -> relay unavailable: show direct error
       -> relay available: authenticate relay
            -> target online: select and wait for ready
            -> target unavailable: show both failure stages
```

## 17. Relay persistence

Use a small transactional store such as SQLite plus a stable data directory.
Suggested logical tables:

| Table | Contents |
| --- | --- |
| `relay_identity` | UUID, name, certificate, encrypted private key metadata. |
| `upstream_clients` | token digest, phone UUID/name, role, timestamps, revoked flag. |
| `targets` | target ID, protocol identity, name, saved/favorite, last endpoints, timestamps. |
| `downstream_credentials` | target ID, encrypted token, SPKI pin, capabilities. |
| `preferences` | default target, scan policy, proxy policy, interface choices. |

Requirements:

- atomic schema migrations;
- restrictive file permissions;
- raw upstream tokens are never persisted;
- downstream secrets are encrypted with a master key supplied through a Docker
  secret or protected key file;
- the service fails clearly if an existing encrypted database cannot be opened
  with the configured key;
- active socket/session handles are never serialized;
- after restart, targets remain saved but all routes begin disconnected.

## 18. Docker Compose design

Create a new relay image and Compose file. Do not repurpose the current
standalone stream-proxy Compose service, which is a diagnostic media proxy and
does not implement relay authentication or target ownership.

Recommended Linux deployment shape:

```yaml
services:
  relay:
    image: ghcr.io/playbridgeapp/playbridge-relay:latest
    container_name: playbridge-relay
    network_mode: host
    restart: unless-stopped
    read_only: true
    tmpfs:
      - /tmp
      - /run/playbridge-relay
    volumes:
      - ./data:/var/lib/playbridge-relay
    secrets:
      - relay_master_key
    environment:
      PB_RELAY_NAME: Home Relay
      PB_RELAY_WSS_PORT: "8765"
      PB_RELAY_ADMIN_SOCKET: /run/playbridge-relay/admin.sock
      PB_RELAY_MASTER_KEY_FILE: /run/secrets/relay_master_key
      PB_RELAY_MEDIA_HOST: 192.168.1.20
      RUST_LOG: playbridge_relay=info
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    healthcheck:
      test: ["CMD", "playbridge-relay", "healthcheck"]
      interval: 30s
      timeout: 5s
      retries: 3

secrets:
  relay_master_key:
    file: ./secrets/relay_master_key
```

The final image should run as a non-root UID when host networking and
multicast sockets permit it. Avoid broad Linux capabilities; add only a proven
minimum if a target platform requires one.

There must be no default API password. Phone access uses SAS-paired PlayBridge
credentials. Administrative commands use the local Unix socket and container
access controls.

### macOS and Windows Docker hosts

Docker Desktop does not provide Linux host networking and multicast behavior in
the same way as native Linux. Treat Linux as the supported v1 deployment. A
future non-container native relay binary can support macOS/Windows directly.

## 19. WireGuard deployment model

Recommended topology:

- relay runs on the home LAN;
- its host is also a WireGuard peer or is routed from the WireGuard gateway;
- phone routes only the relay/WireGuard address, or the home subnet when desired;
- host firewall permits relay WSS from LAN and WireGuard interfaces;
- media proxy access is permitted from the receiver LAN;
- no relay, CastV2, DLNA, or proxy control port is exposed publicly.

If the relay runs on a VPS, it must have a WireGuard route back to the home LAN.
Multicast discovery will still normally fail, so targets must be statically
saved or discovery must run on a home-side agent. A home-LAN relay is therefore
the supported and simpler design.

## 20. Rust implementation structure

Add a workspace member such as:

```text
relay/
  Cargo.toml
  Dockerfile
  docker-compose.yml
  src/
    main.rs
    config.rs
    identity.rs
    store.rs
    upstream.rs
    inventory.rs
    route_manager.rs
    media_router.rs
    admin.rs
```

Dependency ownership:

- `cast/receiver`: upstream TLS/WSS, SAS, authentication, limits, typed command
  delivery;
- `cast/core`: downstream discovery, pairing, protocol sessions, status;
- `stream-proxy-rust`: embedded media proxy;
- `relay`: orchestration, persistence, policy, controller leases, normalized
  inventory, and Docker-facing lifecycle.

Do not put relay product state into `cast/core`. Core should gain only reusable
models/APIs needed for all consumers, such as endpoint roles, richer discovery
metadata, or session events.

The relay should use structured cancellation so shutdown closes discovery,
WSS clients, downstream sessions, and proxy grants in a deterministic order.

## 21. Repository change map

Expected areas:

| Area | Planned change |
| --- | --- |
| Root `Cargo.toml` | Add the relay workspace member. |
| `relay/` | New daemon, persistence, Docker image, Compose example, admin CLI, tests. |
| `cast/core/` | Endpoint role metadata, relay-useful session/status hooks, no product persistence. |
| `cast/receiver/` | Optional `features`, authenticated-client identity support, relay command decoding. |
| `cast/ffi/` | Expose additive relay protocol models only if mobile uses Rust for that layer. |
| `stream-proxy-rust/` | Relay-safe embedded configuration and resource/security bounds. |
| `protocol/asyncapi.yaml` | Source-of-truth relay schemas and optional capability fields. |
| `protocol/docs/WSS_FLOW.md` | Relay sequencing, trust boundaries, leases, and reconnect behavior. |
| `protocol/proto/messages.proto` | Add retained relay models, then regenerate bindings. |
| `shared/` | Kotlin protocol models and tolerant relay frame parsing. |
| Android phone | Relay profiles, relay connection state, inventory, dialog, route selection/fallback. |
| Apple phone | Keychain relay profiles, relay coordinator, SwiftUI inventory and route UX. |
| Desktop | Relay client support after phone MVP; optional native relay packaging later. |
| CLI | Relay discovery/pairing/list/select/cast commands for testing and administration. |
| Documentation | Deployment, WireGuard routing, security, backup/restore, troubleshooting. |

## 22. Implementation phases

### Phase 0 — Contract and threat model

Deliverables:

- finalize relay terminology and trust boundaries;
- document phone-to-relay and relay-to-target state machines;
- add AsyncAPI schemas and JSON fixtures;
- define size/rate limits and stable error codes;
- define endpoint role and capability negotiation;
- extend the security threat model for SSRF, stolen relay volumes, malicious
  paired clients, takeover, and replayed commands.

Exit criteria:

- protocol validation passes;
- old frames remain valid;
- new clients tolerate receivers without `relay.v1`;
- no downstream secret appears in any upstream schema.

### Phase 1 — Headless relay foundation

Deliverables:

- new Rust relay binary and workspace member;
- persistent identity and encrypted state store;
- upstream receiver runtime integration;
- token-digest-to-phone identity mapping;
- per-connection authenticated/disconnected/activity events needed by leases;
- local `relayctl` administrative commands;
- healthcheck and graceful shutdown;
- Linux Dockerfile and hardened Compose example.

Exit criteria:

- a CLI sender can pair/authenticate through SAS;
- restart preserves the relay SPKI identity and paired phone;
- forgetting a phone revokes its active connection;
- no media or downstream support is required yet.

### Phase 2 — Inventory and target selection contract

Deliverables:

- continuous/on-demand Rust discovery scheduler;
- stable target persistence and live/saved merge;
- revisioned snapshots and incremental updates;
- target selection state machine and controller lease;
- CLI commands to list targets, refresh, select, disconnect, and forget;
- self/relay-loop filtering.

Exit criteria:

- CLI over LAN and WireGuard can see exactly what the relay sees;
- saved targets remain visible offline;
- DHCP/address changes retain target identity;
- no selection reports ready without a downstream-ready session.

### Phase 3 — Google Cast and media proxy MVP

Deliverables:

- fresh Google Cast downstream session lifecycle;
- receiver-ready and media-LOAD acknowledgement handling;
- normalized playback status and control;
- embedded stream proxy registration and cleanup;
- headers, HLS/DASH rewriting, range responses, and HLS segment hints;
- proxy address/interface selection;
- clear route failure and retry events.

Exit criteria:

- URL with headers casts from phone through relay to Chromecast;
- phone may be reachable only through WireGuard;
- TV fetches media from the relay LAN address;
- receiver Back/app termination is detected;
- the next cast creates a fresh session;
- relay/container restart never attempts to reuse a dead native handle.

### Phase 4 — Android phone experience

Deliverables:

- saved relay profile model and encrypted persistence;
- LAN/manual/QR relay setup;
- relay authentication and snapshot client;
- source-aware device dialog and target inventory;
- downstream pairing-code prompt;
- operation progress, cancellation, and errors;
- now-playing route label;
- ask-after-failure routing policy.

Exit criteria:

- the dialog remains open until target readiness;
- relay connection and target connection are visibly distinct;
- no downstream credentials exist in Android storage;
- reconnect through WireGuard restores snapshot and lease state;
- direct casting behavior remains unchanged when relay support is unused.

### Phase 5 — Apple phone parity

Deliverables:

- Keychain-backed relay profiles;
- relay discovery/manual setup and SPKI-pinned reconnect;
- Swift relay snapshot/operation parsing;
- relay target UI and downstream code entry;
- routing preference and route status parity with Android.

Exit criteria:

- the same protocol fixtures pass in Swift;
- relay targets and errors match Android semantics;
- Bonjour remains optional for saved WireGuard relays.

### Phase 6 — PlayBridge, DLNA, and Roku downstream parity

Deliverables:

- relay-to-PlayBridge SAS pairing and encrypted credentials;
- context/status mirroring and reconnect;
- DLNA endpoint enrichment, proxy load, polling, and controls;
- Roku launch/status/control;
- per-protocol capability reporting.

Exit criteria:

- each protocol has bounded connection behavior and deterministic cleanup;
- forgetting one target removes only its matching credentials;
- pin mismatch cannot trigger unpinned fallback;
- unsupported controls are surfaced rather than silently accepted.

### Phase 7 — Automatic fallback and hardening

Deliverables:

- direct/relay target mapping by stable identity;
- direct failure classification;
- ask and automatic fallback modes;
- controller takeover UX;
- metrics, backup/restore, resource quotas, and upgrade migrations;
- long-running soak and network-transition tests.

Exit criteria:

- security/authentication failures never trigger relay fallback;
- duplicate cast requests are idempotent;
- a phone network transition does not stop active playback;
- relay upgrades preserve identity, phones, target credentials, and settings.

### Phase 8 — Desktop and broader tooling

Deliverables:

- Desktop relay profile and target picker;
- full CLI remote-cast workflow for diagnostics and automation;
- optional native relay installer outside Docker;
- optional authenticated setup UI;
- evaluate phone-local upload/tunnel support.

## 23. Testing strategy

### 23.1 Protocol tests

- validate AsyncAPI structure and references;
- add valid/invalid fixtures for every relay frame;
- verify request ID, idempotency, revision, timestamp, and size bounds;
- verify old clients ignore relay frames and new clients handle absent features;
- verify credentials and headers are marked sensitive/write-only where
  applicable.

### 23.2 Rust unit tests

- stable target identity across address changes;
- live/saved merge and TTL behavior;
- self/relay-loop filtering;
- encrypted credential storage and wrong-key failure;
- token digest to phone identity mapping;
- controller lease acquisition, reconnect, expiry, and takeover;
- idempotent target selection and playlist routing;
- per-protocol retry classification;
- proxy grant creation/revocation and SSRF policy.

### 23.3 Relay integration tests

- pair/authenticate a sender against the real receiver runtime;
- restart relay and authenticate with the previous pin/token;
- discover simulated targets and publish revisioned snapshots;
- select a fake Cast/PlayBridge target and verify state transitions;
- disconnect upstream during playback and reclaim within grace;
- reject commands from a non-lease owner;
- revoke a phone and verify immediate socket closure;
- terminate a downstream receiver and verify `ended` then fresh reconnect;
- proxy HLS with headers, misleading extensions, redirects, and ranges.

### 23.4 Docker tests

- build from a clean checkout;
- first-start identity generation;
- volume persistence across recreation;
- non-root/read-only execution;
- healthcheck and graceful SIGTERM;
- host-network mDNS/SSDP discovery on Linux;
- explicit `PB_RELAY_MEDIA_HOST` behavior;
- no startup with a missing/wrong key for an existing encrypted store.

### 23.5 Phone tests

- relay profile migration and secure persistence;
- address candidate resolution and SPKI pin failure;
- snapshot/revision reconciliation;
- UI remains present through every connection stage;
- saved offline targets remain visible;
- downstream pairing prompt and expiry;
- cancellation does not leave a route or lease active;
- direct/relay grouping only by stable identity;
- fallback eligibility matrix;
- process death/relaunch restores relay profile but not false ready state.

### 23.6 Network matrix

Test at least:

- phone and relay on the same LAN;
- phone on cellular connected through WireGuard;
- WireGuard connected but home subnet not routed, relay IP routed only;
- mDNS unavailable to the phone but available to the relay;
- relay with Ethernet and WireGuard interfaces;
- receiver DHCP address change;
- IPv4-only, IPv6-only where supported, and dual stack;
- Wi-Fi isolation/firewall blocking TV-to-relay proxy traffic;
- relay restart during playback;
- WireGuard roaming between Wi-Fi and cellular;
- two paired phones observing one active route.

### 23.7 Required project checks

At implementation time, run the owning-project checks described in the agent
guide, including:

- Rust tests and Clippy for core, receiver, FFI, CLI, proxy, and relay;
- `stream-proxy-rust` tests and Docker build;
- protocol spec validation and generated binding checks;
- Android phone tests, assemble, and lint;
- Apple phone simulator build and focused tests;
- Desktop Flutter tests/analyze when Desktop or Dart bindings change.

## 24. Observability and diagnostics

Use structured events containing:

- relay instance ID suffix;
- upstream connection ID and hashed client identity;
- request ID and operation;
- target ID and protocol;
- route generation/attempt number;
- state transition and elapsed time;
- redacted error code.

Never log:

- raw upstream/downstream bearer tokens;
- SPKI private material;
- SAS values in retained service logs;
- full authenticated media URLs;
- header values, cookies, authorization, or proxy grant URLs.

Useful counters:

- authenticated phones;
- discovery scans and targets by protocol;
- route attempts/success/failure by protocol and error code;
- active proxy grants and bytes proxied;
- downstream reconnects and receiver-ended events;
- lease conflicts and takeovers.

The health endpoint should report process/store/discovery readiness without
listing clients, targets, credentials, or URLs.

## 25. Upgrade, backup, and reset behavior

- Schema migrations run before listeners start.
- Back up the data volume and its master key together; one without the other is
  intentionally unusable.
- Restoring the same relay identity preserves phone pins.
- Restoring only the database under a new identity requires re-pairing phones.
- Factory reset removes identity, upstream clients, targets, and downstream
  credentials only after explicit confirmation.
- Forgetting a phone does not forget downstream targets.
- Forgetting a target does not forget phones or unrelated target credentials.
- Container upgrades never generate a new certificate when a valid identity
  already exists.

## 26. Acceptance criteria for the first public relay release

The first releasable version is complete when:

1. A fresh Docker Compose deployment creates one stable relay identity.
2. Android and Apple phones can pair with and save that relay.
3. The same saved relay reconnects over LAN and WireGuard with SPKI pinning.
4. The phone can request and display live plus saved relay targets.
5. Selecting Google Cast waits for confirmed receiver readiness.
6. The phone can cast a header-protected HLS URL through the relay proxy.
7. Playback status and controls return through the existing phone UI.
8. Ending the Cast receiver app is detected and the next cast starts fresh.
9. A saved offline target remains visible and fails with a bounded error.
10. No downstream credentials or addresses are stored on the phone.
11. No control or proxy-registration port must be exposed to the public
    internet.
12. Relay identity and pairings survive container recreation and upgrade.
13. Security/pin failures never silently fall back or re-pair.
14. All protocol, Rust, proxy, mobile, Docker, and migration checks pass.

## 27. Current reusable foundations and identified gaps

The plan intentionally builds on these existing foundations:

- `cast/core/src/discovery.rs` already provides protocol-qualified PlayBridge,
  Google Cast, DLNA, and Roku discovery.
- `cast/core/src/session.rs` already normalizes downstream protocol sessions
  and basic playback status.
- `cast/receiver/src/lib.rs` already owns TLS/WSS, SAS pairing, bearer-token
  authentication, limits, command delivery, and targeted responses.
- `stream-proxy-rust` already supports embedded URL registration, scoped media
  URLs, header forwarding, range requests, and HLS/DASH rewriting.
- Android already has protocol-qualified `ReceiverEndpoint` and
  `SavedReceiverEndpoint` models plus Keystore-backed token protection.
- Apple already has Keychain-backed saved receiver history and SPKI-pinned
  reconnect behavior.
- `protocol/asyncapi.yaml` and `protocol/docs/WSS_FLOW.md` already define the
  canonical authenticated playback contract the relay can mirror.

Known gaps to close deliberately:

- discovery results do not yet preserve a receiver role such as `relay`;
- credential/auth capability models expose players and browsers but no general
  additive `features` list;
- the receiver runtime does not currently expose a connection-specific
  disconnect or heartbeat/activity event to its host;
- the protocol has no relay inventory, selection, operation, or lease frames;
- normal command envelopes have no request/idempotency correlation;
- phone stores currently model direct receivers, not a two-level
  relay-profile/relay-target relationship;
- downstream target credentials need a new encrypted headless persistence
  owner;
- the standalone stream-proxy Compose deployment is not an authenticated
  casting gateway and must not be exposed as one;
- multicast discovery is not expected to cross WireGuard, so saved/manual
  relay resolution is a product requirement rather than an edge case.

## 28. Recommended decisions

These choices should be treated as the v1 baseline unless implementation
evidence requires changing them:

- Deploy the relay on the home LAN, not primarily on a VPS.
- Use WireGuard only for private reachability; do not expose public ports.
- Model the relay as a PlayBridge receiver upstream and sender downstream.
- Reuse normal playback/control/status frames after target selection.
- Add a small additive protocol surface for inventory, selection, pairing, and
  controller leases.
- Advertise through `_playbridge._tcp.` with `role=relay`.
- Keep relay profiles separate from saved direct receivers on phones.
- Keep all downstream credentials on the relay.
- Co-locate and embed the Rust stream proxy.
- Support Linux host-network Docker Compose first.
- Allow one active downstream route globally in v1.
- Default to “ask after direct failure,” not invisible automatic fallback.
- Start with Google Cast, then PlayBridge, DLNA, and Roku.
- Use a local Unix-socket admin CLI before building a web administration UI.
