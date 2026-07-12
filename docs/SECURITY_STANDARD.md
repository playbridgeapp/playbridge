# PlayBridge Sender and Receiver Security Standard

Status: normative project standard

Version: 1.0

Applies to: Android phone, iOS phone, desktop sender, Android TV, Apple TV,
desktop receiver, and any future PlayBridge sender or receiver

This document defines the minimum security behavior for PlayBridge control
connections and media requests. The words **MUST**, **MUST NOT**, **SHOULD**,
**SHOULD NOT**, and **MAY** are requirements in the sense used by RFC 2119.

Implementation code and the wire-format reference in
[`protocol/README.md`](../protocol/README.md) remain authoritative for exact
field names. If an implementation conflicts with this standard, the
implementation is non-compliant; the conflict must not be resolved by weakening
this standard or adding an insecure compatibility fallback.

## 1. Security goals and trust boundaries

PlayBridge MUST remain secure on an untrusted local network. Discovery records,
IP addresses, host names, device display names, QR codes that contain only an
address, and mDNS TXT fields are untrusted routing hints—not identities.

The design protects against:

- passive LAN monitoring;
- an active LAN attacker intercepting, changing, replaying, or relaying traffic;
- an unpaired device sending playback or remote-control commands;
- credential disclosure through logs, history, favorites, crash reports, or
  redirects;
- credentials intended for one media origin being sent to another origin;
- malformed or oversized pre-authentication input exhausting a receiver.

The design does not claim to protect secrets after the sender or receiver OS is
fully compromised. A rooted/jailbroken device and a malicious process running as
the same user are outside the primary threat model.

## 2. Required cryptographic primitives

All implementations MUST use platform cryptographic libraries or a maintained
cryptographic library. They MUST NOT implement primitives themselves.

| Purpose | Requirement |
| --- | --- |
| Control transport | TLS 1.2 or newer; TLS 1.3 preferred |
| Receiver identity | SHA-256 SPKI pin, encoded as `sha256/<base64>` |
| Pairing key agreement | X25519 with fresh ephemeral keys |
| Hash and MAC | SHA-256 and HMAC-SHA-256 |
| Key derivation | HKDF-SHA-256 (RFC 5869) |
| Protected pairing credentials | AES-256-GCM with a fresh 96-bit nonce |
| Random values and tokens | Cryptographically secure random generator |

Cryptographic comparisons of MACs, pins, and token verifiers SHOULD be
constant-time. Nonces, ephemeral private keys, shared secrets, and derived keys
MUST be discarded when the handshake ends or times out.

## 3. Receiver discovery and connection

1. A receiver advertises a `wss://` endpoint through local discovery.
2. A sender treats every discovered address and advertised fingerprint as
   untrusted until pairing succeeds.
3. Every control connection MUST use WSS. Plain `ws://` control connections MUST
   be refused, including fallback after a WSS error.
4. For a previously paired receiver, the sender MUST validate the TLS
   certificate against the stored SPKI pin before sending the bearer token or
   any command.
5. A pin mismatch MUST terminate the connection and require explicit re-pairing.
   The sender MUST NOT silently replace the pin with a discovery value or a pin
   returned by the mismatched peer.

During first pairing, the self-signed TLS connection provides confidentiality
and basic channel integrity but is not yet the trusted receiver identity. The
authenticated SAS exchange below establishes that trust.

## 4. First-time pairing

### 4.1 Sender commit

The sender generates a fresh X25519 key pair and 16-byte random `nonceS`, then
computes:

```text
commit = SHA-256(senderEphemeralPublicKey || nonceS)
```

It sends `pairing_commit` containing the base64 commitment and bounded device
metadata. It MUST retain the private key and nonce only for this handshake.

### 4.2 Receiver challenge

The receiver validates lengths and encoding, applies pairing rate limits, and
creates a fresh X25519 key pair and 16-byte random `nonceT`. It sends
`pairing_challenge` containing its public key and nonce.

Only one pending pairing ceremony SHOULD be presented to the receiver user at a
time. A handshake MUST have a short timeout; 60 seconds is the maximum user
approval window.

### 4.3 Sender reveal and shared transcript

The sender sends `pairing_reveal` containing its public key and `nonceS`. The
receiver MUST verify the original commitment before continuing.

Both peers calculate the X25519 shared secret and the byte-exact transcript:

```text
transcript = commitBytes || receiverEphemeralPublicKey || nonceT
             || senderEphemeralPublicKey || nonceS
```

Both peers derive and display the same six-digit SAS from the shared secret and
transcript. The user MUST confirm that the codes match on the physical sender
and receiver. A device MUST NOT describe pairing as complete before this user
confirmation and cryptographic key confirmation succeed.

### 4.4 Key confirmation

Both peers derive:

```text
prk = HKDF-Extract(salt = empty, IKM = sharedSecret)
confirmationKey = HKDF-Expand(prk, info = "confirmationKey", length = 32)
confirmationMac = HMAC-SHA-256(confirmationKey, transcript)
```

After the user confirms the SAS, the sender sends `pairing_confirmation` with
`confirmationMac`. The receiver MUST verify it before issuing credentials. A
denial, mismatch, malformed field, timeout, or disconnect MUST erase handshake
state and fail closed.

### 4.5 Protected credential delivery

After successful confirmation, the receiver generates a high-entropy,
receiver-scoped bearer token and derives:

```text
credentialKey = HKDF-Expand(
    prk,
    info = "playbridgeCredentialKey-v1",
    length = 32
)
aad = SHA-256(transcript)
```

The receiver encrypts a JSON object containing the token, its TLS SPKI pin, and
capabilities using AES-256-GCM, a fresh 12-byte nonce, and `aad`. It sends only
the nonce and ciphertext/tag in `pairing_approved`.

The sender MUST authenticate and decrypt this envelope before persisting or
using any field. A plaintext `token` or `certFingerprint` in
`pairing_approved` MUST be rejected. There MUST NOT be a plaintext legacy
fallback, because it creates a downgrade path.

The sender then stores the token and pin as one atomic pairing record. Pairing is
complete only after that write succeeds. The receiver MUST NOT send the token
again in any later response.

## 5. Later connections and reauthentication

The following sequence is required for every reconnect:

1. Resolve the receiver address from discovery or saved routing information.
2. Establish WSS and validate the presented SPKI against the stored pin.
3. Only after successful pin validation, send `auth` with the saved token.
4. Until authentication succeeds, the receiver accepts only bounded pairing,
   authentication, ping, and close traffic. It MUST reject commands and media
   payloads.
5. The receiver validates the token and returns `auth_response` with only
   `success`, current capabilities, and—if retained for consistency checking—the
   current SPKI pin. It MUST NOT echo the token.
6. The sender treats the session as authenticated only when `success` is true.
   A returned pin that differs from the already validated/stored pin is an error,
   not an automatic rotation instruction.

Sending a high-entropy bearer token inside an already pinned TLS connection is
the version 1 reauthentication mechanism. Application-level challenge-response
is not required because it would not replace the need for TLS and pinning. If a
token is exposed, the pairing MUST be revoked and a new token created.

Tokens MUST be independently generated for each sender/receiver pairing. They
MUST be revocable individually. Receivers SHOULD store a one-way SHA-256 or
stronger verifier rather than the plaintext token. Senders require the original
token and MUST protect it with platform-backed secret storage.

## 6. Certificate and token lifecycle

- A receiver SHOULD retain its TLS key pair across ordinary certificate renewal
  so the SPKI pin remains stable.
- Rotating or losing the receiver private key invalidates its identity and MUST
  require re-pairing. Pin rotation MUST NOT be authorized solely by a message
  carried over a connection presenting the new, untrusted pin.
- Unpairing on either device MUST delete the token, pin, device metadata that is
  no longer needed, and related cached credentials.
- Receiver reset or “forget all devices” MUST revoke every stored token.
- Authentication failures SHOULD NOT reveal whether a token, device UUID, or
  receiver record exists.
- Tokens MUST never appear in URLs, discovery records, logs, telemetry, crash
  reports, clipboard contents, or notifications.

Recommended secret storage:

- Android sender: Android Keystore-backed encryption;
- Apple sender: Keychain with an appropriate device-only accessibility class;
- desktop sender: the operating system credential vault/keychain;
- receiver: a platform-protected store; token verifiers are preferred over
  recoverable tokens.

## 7. Session and receiver hardening

Receivers MUST apply limits before authentication, including:

- maximum concurrent and per-IP connections;
- maximum WebSocket frame and decoded JSON sizes;
- bounded strings, arrays, header counts, and playlist sizes;
- handshake, idle, and authentication timeouts;
- rate limits and temporary backoff for failed pairing/authentication;
- one state machine per connection, with no authentication state shared by
  remote IP or request ordering.

Malformed or out-of-order security messages MUST fail closed. Unknown protocol
versions MUST NOT trigger a downgrade. Error responses must be generic and must
not contain secrets or raw attacker-controlled payloads.

Authenticated control does not mean every command is safe. Receivers MUST still
validate command types, numeric ranges, text lengths, URLs, headers, file paths,
and resource consumption.

## 8. Media URLs, redirects, headers, and cookies

Media URLs and request headers are sensitive untrusted input even when received
from an authenticated sender.

### 8.1 Input validation

Senders SHOULD transmit only headers required for playback. Receivers MUST:

- allow only explicitly supported URL schemes (`https` and product-approved
  `http`; local files only through an explicit local-file feature);
- reject userinfo in URLs unless a specific feature securely handles it;
- reject control characters, CR/LF, invalid header names, duplicate conflicting
  security headers, and excessive header counts or sizes;
- strip hop-by-hop and transport-controlled fields such as `Host`, `Connection`,
  `Content-Length`, `Transfer-Encoding`, and proxy authentication fields;
- keep stream credentials scoped to the individual playback item, never in a
  global client or host-wide mutable header store.

Android TV's product-approved cleartext media support MAY remain enabled for
direct and torrent streams. This exception applies to media fetching only, not
the PlayBridge control channel.

### 8.2 Origin-bound credential forwarding

Credential-bearing headers include at least `Authorization`, `Cookie`,
`Proxy-Authorization`, API/debrid tokens, and any application-designated secret
header.

The receiver MUST bind such headers to the original origin: scheme, normalized
host, and effective port. On every redirect and every derived request—HLS/DASH
manifests, variants, segments, encryption keys, subtitles, artwork, and proxy
rewrites—it MUST re-evaluate the destination before attaching credentials.

Credentials MUST NOT cross origins by default. A cross-origin redirect or child
resource may receive credentials only when the sender supplied an explicit,
bounded allowlist for that credential set. Redirects MUST have a finite hop
limit and MUST NOT downgrade HTTPS to HTTP while retaining credentials.

Non-secret compatibility headers such as `User-Agent` or, where required,
`Referer` MAY follow a separately documented policy; they must not be assumed
safe merely because they are common.

## 9. Receiver history and favorites

Receiver history is permitted because it enables replay without a sender.
Authenticated streams may require their original URL, headers, and cookies, so
silently dropping those fields is not a valid general solution.

If a receiver persists a replayable entry, it MUST:

- encrypt the complete sensitive entry at rest with a non-exportable or
  platform-protected key;
- keep display metadata separate where practical so listing history does not
  decrypt credentials;
- apply the same URL/header validation and origin policy again on replay;
- never place decrypted values in logs, UI messages, telemetry, or crash data;
- delete encrypted credential material when the entry is deleted;
- provide clear-history and history-disable controls;
- enforce bounded entry count and retention, and track credential expiry where
  known;
- show expired or failed entries as unavailable rather than leaking response
  details or repeatedly retrying authentication.

Where a provider supports refresh, history and favorites SHOULD store a stable,
non-secret content identifier and request fresh playback credentials from an
authorized sender/provider integration. Otherwise, storing encrypted replay
credentials is acceptable with the limitations above.

Favorites usually live longer than history. Receiver favorites MAY be supported
only under the same encrypted-storage and credential-lifecycle requirements.
Until a receiver meets them, favorites containing credentials SHOULD remain
sender-authoritative.

## 10. Logging, diagnostics, and privacy

Production logs MUST NOT contain:

- pairing or authentication tokens;
- cookies, authorization values, or complete header maps;
- complete authenticated/signed media URLs or query strings;
- shared secrets, keys, nonces combined with secrets, plaintext protected
  payloads, or raw security frames.

Logs SHOULD record message type, outcome, counts, coarse timing, and a random
correlation identifier. Redaction is a safety net, not permission to log a
secret first. Diagnostic export MUST be opt-in, clearly warn the user, and apply
redaction again at export time.

## 11. Compatibility and protocol evolution

Security-relevant protocol changes MUST have an explicit version or capability
and must fail closed. A peer that cannot perform protected SAS pairing, WSS pin
validation, or the required authentication flow is incompatible and must be
upgraded or re-paired; it must not be accommodated with plaintext fallback.

All cryptographic changes MUST include shared byte-level test vectors for
transcript construction, HKDF outputs, SAS, confirmation MAC, SPKI formatting,
and AES-GCM credential envelopes. Android, Apple, and Dart implementations MUST
pass the same vectors. Cross-version tests SHOULD cover every supported
sender/receiver combination.

## 12. Compliance checklist

A sender is compliant only if it:

- uses WSS, pins every known receiver before disclosing the token, and fails on
  pin mismatch;
- performs the complete SAS and confirmation flow for first pairing;
- accepts credentials only from the authenticated AEAD envelope;
- stores the token and pin in platform-protected storage;
- never sends commands before successful authentication;
- validates outgoing URL/header data and does not log secrets.

A receiver is compliant only if it:

- exposes the control protocol only over WSS;
- performs the complete bounded SAS pairing state machine;
- protects first-pair credentials with the specified AEAD envelope;
- authenticates every later connection before accepting commands and never
  echoes tokens;
- protects token verifiers, TLS keys, history credentials, and favorites at
  rest;
- applies pre-authentication resource limits and origin-bound header forwarding;
- provides revocation and deletion controls and does not log secrets.

Release review MUST verify these requirements separately for every sender and
receiver project. Passing on one platform does not establish compliance on
another.
