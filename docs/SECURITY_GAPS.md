# PlayBridge Remaining Security Gaps

Status: living security backlog

Audit date: 2026-07-12

Standard: [`SECURITY_STANDARD.md`](SECURITY_STANDARD.md)

This document records known gaps between the current PlayBridge implementations
and the project security standard. It is an engineering backlog, not a claim that
every listed issue is presently exploitable. Each item must be verified on the
affected platform when fixed, and the evidence paths should be updated or the
item removed.

## Severity definitions

| Priority | Meaning |
| --- | --- |
| P0 — Critical | Practical compromise of pairing, receiver identity, or arbitrary unauthenticated control; release blocker |
| P1 — High | Likely disclosure or misuse of credentials, or a meaningful remote attack surface; fix before calling the affected feature secure |
| P2 — Medium | Important defense-in-depth, local secret exposure, denial of service, or security inconsistency |
| P3 — Low | Hardening, assurance, or maintainability work with limited direct impact |

## Current posture summary

No P0 issue was identified in this review. First-time pairing is consistently
protected by X25519, SAS/key confirmation, and AES-256-GCM credential delivery
across the implemented Android, Apple, and Dart sender/receiver combinations.
Previously paired senders use certificate-pinned WSS before token authentication,
and no plaintext pairing fallback is present.

The project is not yet fully compliant with the security standard. The principal
remaining risks are:

1. credential-bearing media headers can be reused across redirects or derived
   media requests without a shared origin policy;
2. receiver history and favorites persist replay credentials without
   application-level encryption;
3. some senders and receivers persist bearer tokens in ordinary preferences;
4. desktop and Apple TV receivers echo bearer tokens during reauthentication;
5. receiver input and denial-of-service limits are inconsistent;
6. protocol conformance lacks shared cross-language cryptographic test vectors.

## P1 — High priority

### SEC-001: Origin-bound media credentials are not enforced consistently

**Affected:** Android phone playback, Android TV, Apple TV, desktop playback and
proxy paths; any shared playback code that applies sender-provided headers.

**Risk:** An `Authorization`, `Cookie`, debrid/API token, or other secret header
intended for one origin may be attached to a redirect, HLS/DASH child resource,
subtitle, artwork request, or proxy rewrite at a different origin. A malicious or
compromised media endpoint could redirect a receiver to an attacker-controlled
host and collect credentials.

**Evidence:**

- `shared/.../ExoPlayerEngine.kt` applies the complete per-item header map as
  default request properties while enabling HTTP, HTTPS, and cross-protocol
  redirects.
- `shared/.../MpvPlayerEngine.kt` passes the complete map through mpv's global
  `http-header-fields` option for the loaded item.
- `tv/apple/.../VLCPlayerView.swift` and `VLCProxyServer.swift` use URLSession and
  proxy rewriting for redirect and HLS flows without a project-level
  origin/credential policy.
- Subtitle and content-sniffing paths accept and reuse the playback header map.

**Required remediation:**

- Introduce one shared conceptual header policy, implemented on every platform.
- Classify credential-bearing headers and bind them to the initial normalized
  origin: scheme, host, and effective port.
- Re-evaluate redirects, manifests, variants, segments, keys, subtitles,
  artwork, and proxy rewrites independently.
- Strip credentials on cross-origin transitions unless the sender supplies an
  explicit bounded origin allowlist.
- Never retain credentials during HTTPS-to-HTTP downgrade.
- Add integration tests using two local origins and redirect/playlist chains.

**Done when:** Every HTTP stack has tests proving credential headers reach the
allowed origin and do not reach a second origin through redirects or child URLs.

### SEC-002: Receiver history and favorites store sensitive replay data unencrypted

**Affected:** Android TV, Apple TV, desktop receiver.

**Risk:** Cast URLs can contain signed query parameters, and authenticated streams
may include cookies or authorization headers. Local backups, preference files,
device extraction, another process with access to app data, or diagnostics could
expose reusable playback credentials. Favorites retain this material longer than
normal history.

**Evidence:**

- Android TV `HistoryStore.kt` stores the raw playlist payload—including item
  headers—inside Preferences DataStore as JSON.
- Apple TV `HistoryStore.swift` encodes URLs and headers directly into
  `UserDefaults`.
- Desktop `history_store.dart` stores URLs in `SharedPreferences`; headers are not
  retained, but signed/authenticated URLs can themselves be secrets.

**Required remediation:**

- Encrypt the sensitive portion of each entry with a platform-backed key.
- Keep non-sensitive list metadata separate where practical.
- Preserve the headers needed for replay; do not create broken history entries by
  silently deleting them.
- Revalidate URLs and headers under SEC-001 whenever an entry is replayed.
- Add retention/expiry handling and securely delete encrypted material when an
  entry or all history is removed.
- Ensure “clear history” also removes favorites or clearly offers a separate
  “clear favorites and credentials” action; Android TV currently retains
  favorites when clearing history.

**Done when:** Raw preference/database files and backups do not reveal full URLs,
headers, cookies, playlist payloads, or other replay credentials, while valid
entries still replay successfully.

### SEC-003: URL and header validation is fragmented

**Affected:** All senders and receivers that create or consume playback payloads.

**Risk:** Malformed header names/values, CR/LF injection, hop-by-hop fields,
userinfo URLs, excessive headers, unsupported schemes, or oversized values may
reach platform networking libraries and proxies. Results range from credential
misrouting and request smuggling at protocol boundaries to crashes and resource
exhaustion.

**Evidence:** Individual paths filter selected fields such as `Host`, `Range`, or
`Accept-Encoding`, but no common policy enforces header syntax, forbidden fields,
count/byte limits, URL schemes, userinfo, or redirect revalidation across every
player, sniffer, subtitle loader, and proxy.

**Required remediation:**

- Define exact maximums and an allow/deny policy in the protocol layer.
- Validate on the sender for early feedback and again on the receiver as the
  trust boundary.
- Reject CR, LF, NUL, invalid names, conflicting duplicates, hop-by-hop headers,
  proxy credentials, and excessive maps/values.
- Permit cleartext HTTP media where the TV product requires it, but do not extend
  that exception to the WSS control channel.
- Apply validation again after decoding receiver history.

**Done when:** A common malicious-input corpus passes through every platform's
validator with identical allow/reject results.

## P2 — Medium priority

### SEC-004: Bearer-token storage is inconsistent

**Affected:** Desktop sender and Android TV receiver. Desktop receiver is
partially compliant because it stores SHA-256 token verifiers. Apple TV stores
only verifier-backed receiver credentials and eagerly migrates legacy records.

**Risk:** Theft of an original token permits authentication as the paired sender
when the attacker can reach the receiver. Ordinary preferences are not a secret
store and may be exposed through local access or backups.

**Evidence:**

- Desktop `tv_connection_store.dart` serializes the sender token and SPKI pin to
  `SharedPreferences`.
- Android TV `PairingStore.kt` stores authorized plaintext token sets and paired
  device records in Preferences DataStore.
- Apple TV `WebSocketServer.swift` stores SHA-256 token verifiers, rewrites
  legacy paired-device records without their plaintext token, and removes the
  legacy authorized-token preference during startup.
- Android phone uses Keystore-backed protection and Apple phone uses Keychain;
  these are the model for their respective sender platforms.

**Required remediation:**

- Move desktop sender tokens to the OS credential vault/keychain.
- Store receiver-side token verifiers instead of recoverable tokens where the UI
  model permits it.
- If paired-device records need a token association, use a non-secret record ID
  or verifier, not a second plaintext copy.
- Migrate legacy records after successful authentication and delete old values.

**Done when:** Preference exports do not contain bearer tokens and legacy values
are migrated or revoked.

### SEC-006: Pre-authentication resource limits are uneven

**Affected:** Primarily Android TV and Apple TV receivers; re-audit desktop when
limits change.

**Risk:** A LAN peer may hold connections open, create many concurrent sockets,
send oversized frames/JSON, or repeatedly allocate handshake state, causing UI
degradation, memory pressure, or receiver unavailability.

**Evidence:** All receivers have some pairing timeout/rate-limit behavior, but
connection caps, per-IP caps, maximum frame/decoded JSON sizes, auth deadlines,
and idle deadlines are not defined and enforced uniformly. Desktop has more
explicit connection/message/handshake bounds than the TV implementations.

**Required remediation:** Define common numeric limits, enforce them before JSON
allocation where possible, bound pairing maps and lockout maps, and add socket
tests for slow, oversized, concurrent, and out-of-order clients.

**Done when:** Each receiver passes the same abuse test suite and remains
responsive under the documented limits.

### SEC-007: Authentication comparisons and token generation need one policy

**Affected:** Receiver implementations.

**Risk:** Direct string/set token comparisons may not be constant-time. UUIDv4
tokens provide substantial entropy, but the project has no centralized token
format, entropy, comparison, or verifier migration contract.

**Evidence:** Android TV and Apple TV compare supplied tokens against plaintext
sets. Desktop computes a SHA-256 verifier, but authorization uses ordinary value
comparison. Tokens are generally UUIDv4 strings.

**Required remediation:** Define a versioned token format generated from at least
128 bits of CSPRNG output, store receiver verifiers, compare fixed-length decoded
verifiers in constant time, and reject malformed lengths before lookup.

**Done when:** All receivers use the same token/verifier rules and unit tests
cover malformed tokens, revocation, migration, and comparison.

### SEC-008: Security protocol versioning is implicit

**Affected:** All senders and receivers.

**Risk:** Future protocol changes may create ambiguous compatibility behavior or
pressure developers to add permissive fallbacks. Security properties are harder
to negotiate and test when support is inferred from missing fields.

**Evidence:** The protected envelope uses a versioned HKDF info string, but the
pairing/auth message exchange does not provide a single explicit security
protocol version with minimum-supported-version behavior.

**Required remediation:** Add an authenticated security version/capability,
define failure behavior for unsupported versions, and prohibit fallback within
the same connection attempt.

**Done when:** New/new succeeds, unsupported versions fail clearly, and downgrade
tests prove an attacker cannot select an older security mode.

## P3 — Assurance and hardening

### SEC-009: Shared cryptographic conformance vectors are missing

**Affected:** Android/Kotlin, Apple/Swift, and desktop/Dart implementations.

**Risk:** Each platform can pass isolated tests while disagreeing on transcript
bytes, base64, HKDF salt semantics, AES-GCM tag layout, SPKI encoding, or JSON
field handling. Cross-platform drift can cause outages or unsafe compatibility
patches.

**Required remediation:** Commit language-neutral vectors for X25519 inputs,
transcript construction, SAS, confirmation MAC, credential key, nonce/AAD,
ciphertext/tag, decryption, and SPKI pin formatting. Run the same vectors in all
three language test suites.

**Done when:** Android phone/TV, Apple phone/TV, and desktop sender/receiver tests
consume the same fixtures in CI.

### SEC-010: Security regression matrix is incomplete

**Affected:** Project-wide CI and release process.

**Risk:** A change can remain internally correct on one platform but break or
weaken another sender/receiver combination.

**Required remediation:** Add automated or repeatable integration coverage for:

- every supported sender/receiver pairing combination;
- first pair, reconnect, denial, timeout, malformed envelope, bad MAC, bad AEAD
  tag, wrong pin, revoked token, and missing token;
- proof that plaintext `pairing_approved` is rejected;
- proof that no control-channel `ws://` fallback occurs;
- SEC-001 cross-origin credential leakage tests;
- SEC-006 resource-limit tests.

**Done when:** The matrix is a required release check and failures block release.

### SEC-011: Secret logging needs continuous automated checks

**Affected:** All projects.

**Risk:** Existing logging was substantially reduced and redacted, but future
debugging can reintroduce raw URLs, payloads, tokens, or headers.

**Required remediation:** Add static checks for known unsafe logging patterns and
runtime tests that inject canary token/cookie/URL values, export diagnostics, and
assert that the canaries do not appear. Keep logging message type/count rather
than payload contents.

**Done when:** Canary-secret tests cover receiver logs, sender logs, crash/diagnostic
exports, and proxy/player logs on every platform.

## Recommended implementation order

1. **SEC-001 and SEC-003 together:** create the shared origin/header policy before
   adding more replay persistence.
2. **SEC-002:** encrypt receiver history and favorites while preserving working
   replay.
4. **SEC-004 and SEC-007:** migrate token storage and verification consistently.
5. **SEC-006:** standardize receiver connection, frame, and timeout limits.
6. **SEC-009 and SEC-010:** establish conformance fixtures and the cross-platform
   regression matrix.
7. **SEC-008 and SEC-011:** add explicit versioning and durable logging guardrails.

## Items intentionally not classified as gaps

- Receiver-local history itself is not a vulnerability. The gap is plaintext
  credential persistence and unsafe replay behavior.
- TV support for cleartext HTTP media is an explicit product requirement. It
  must remain isolated from the WSS control channel and must never retain
  credentials across an HTTPS-to-HTTP downgrade.
- Sending the bearer token over an already pinned WSS connection is the approved
  version 1 reauthentication model. An additional application challenge-response
  exchange is not currently required.
- Self-signed receiver certificates are acceptable because sender trust is based
  on the SAS-established SPKI pin, not a public certificate authority.

## Maintenance rule

When a gap is fixed, the implementing change must include tests matching its
“Done when” condition. Update this file in the same change: remove resolved
evidence from the active list and record the completion in version control. Do
not mark an item resolved solely because one platform was fixed.
