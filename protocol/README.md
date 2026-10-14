# PlayBridge Protocol

The PlayBridge receiver protocol is JSON over secure WebSockets, with one optional compact binary
pointer frame.

## Contract

- [`asyncapi.yaml`](asyncapi.yaml) is the machine-readable WSS contract: directions, envelopes,
  message schemas, field names, JSON types, and binary-frame layout.
- [`docs/WSS_FLOW.md`](docs/WSS_FLOW.md) defines discovery, TLS pinning, first-time SAS pairing,
  protected credential delivery, reconnection authentication, sequencing, and compatibility rules.
- [`docs/PROTOCOL_MODEL_STRATEGY.md`](docs/PROTOCOL_MODEL_STRATEGY.md) defines how AsyncAPI,
  protobuf-generated models, hand-written JSON, and compatibility fixtures work together.
- [`constants/constants.go`](constants/constants.go) records shared discovery/transport constants.

The protobuf schema and generated bindings provide shared implementation models for current
consumers. WSS frames are not protobuf-encoded, and `proto/messages.proto` does not cover every
live JSON message. New protocol behavior must be specified in AsyncAPI first and kept compatible
with the implementations listed below.

## Current consumers

| Role | Implementation | Contract handling |
|---|---|---|
| Sender | Android phone | Shared Kotlin JSON builders/parsers and generated Kotlin payload models |
| Sender | Apple phone | Hand-written Swift JSON |
| Sender | Desktop | Hand-written Dart envelopes and generated Dart payload models |
| Receiver | Android TV | Shared Kotlin JSON parsing and generated Kotlin payload models |
| Receiver | Apple TV | Swift JSON handling plus generated Swift payload models |
| Receiver | Desktop | Rust `cast/receiver` runtime via Dart FFI; Dart playback adapter and generated payload models |
| Browser extension | Firefox / Chromium | Native messaging to Desktop; it does not open receiver WSS directly |

## Changing the WSS protocol

1. Update `asyncapi.yaml` and `docs/WSS_FLOW.md`.
2. Prefer additive optional fields or new message types/actions.
3. Check all current consumers, including messages not represented in protobuf (`tracks`,
   `player_settings`, browser administration, protected pairing credentials, and binary pointer
   input).
4. Update `proto/messages.proto` only when an existing generated consumer still needs the same
   payload model, then run `./generate.sh` and commit generated changes.
5. Add or update cross-platform JSON fixtures/tests before merging a wire-format change.

Run the dependency-free structural check locally:

```bash
ruby scripts/check-spec.rb
# In an existing Python environment with PyYAML and jsonschema:
python scripts/validate-fixtures.py
```

Breaking changes include renamed fields, changed JSON types or units, removed values, and any
change to SAS transcript bytes or cryptographic derivation labels. Those require a major protocol
version and an explicit sender/receiver rollout plan.

## Generated protobuf models

The retained generation layout is:

```text
proto/messages.proto
generated/kotlin/
generated/swift/
generated/dart/
generate.sh
```

Run:

```bash
./generate.sh
./generate.sh --check
```

Prerequisites are documented in `generate.sh`. Generated Kotlin, Swift, and Dart types are local
implementation helpers; their protobuf binary encoding is not used on the WSS wire.

## Security invariants

- Pairing tokens, private keys, authenticated stream URLs, request headers, cookies, and protected
  credential plaintext must never be logged or committed.
- A stored SPKI mismatch is a hard failure. Never silently fall back to unpinned TLS.
- `pairing_approved` contains AES-256-GCM-protected credentials, not plaintext token fields.
- Ephemeral X25519 keys, nonces, shared secrets, and derived keys must be cleared after completion,
  failure, replacement, or close.

## Stage 5 receiver descriptor contract

[Receiver capabilities](docs/RECEIVER_CAPABILITIES.md) specifies optional static auth/protected-
credential descriptors, complete per-engine operation sets, state/volume rules and reserved later
push naming. This contract does not enable receiver or sender behavior. [Tests/fixtures](tests/README.md)
include real JSON Schema positive/negative validation and retained-parser compatibility checks;
`check-spec.rb` remains a structural/reference checker, not a payload validator.

The retained generator writes Kotlin, Dart and Swift models. There are no retained TypeScript or
Go message models in this repository; `constants/constants.go` is hand-maintained. `PROTOC` may
select an already installed compiler; no tool installation is part of generation verification.
