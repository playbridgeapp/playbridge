---
name: playbridge-protocol
description: Change or review PlayBridge's cross-platform wire protocol and compatibility surface. Use for protocol/asyncapi.yaml, protocol/proto/messages.proto, generated bindings, shared Kotlin message parsing/building, Desktop Dart protocol code, Apple JSON consumers, extension message handling, pairing/authentication envelopes, or any change whose payload must remain compatible across senders and receivers.
---

# PlayBridge Protocol

## Treat the protocol as a contract

- Treat `protocol/` as a directly owned part of the PlayBridge monorepo. Keep contract, documentation, retained protobuf models, generated artifacts, and consumer updates together when they form one compatibility change.
- Treat `protocol/asyncapi.yaml` plus `protocol/docs/WSS_FLOW.md` as the source of truth for the JSON/WSS wire contract and sequencing.
- Keep `protocol/proto/messages.proto` and committed Kotlin, Dart, and Swift bindings as shared implementation models for current consumers. When a generated payload changes, update the AsyncAPI contract first, mirror the compatible change in protobuf, then regenerate with `protocol/generate.sh`.
- Do not assume generated bindings cover every consumer. PlayBridge still has hand-written JSON and compatibility code.
- Give contract/schema and generated artifacts one writer. Let consumer owners implement or review their non-overlapping projects.

## Check consumers

Inspect all affected consumers, including:

- `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt`
- `shared/src/androidMain/kotlin/com/playbridge/shared/protocol/IncomingMessage.kt`
- Android sender and TV server call sites
- `cast/core/src/playbridge.rs` and the network-facing behavior in `cast/receiver/`
- `desktop/lib/protocol.dart` and the generated Dart dependency
- Apple phone and TV message handling
- `extension/src/background.ts` and related bridge code

Load the corresponding PlayBridge specialist skills for consumer implementation.

The `pb_receiver_runtime_*` C API and its Dart event JSON are an internal host
ABI, not the PlayBridge WSS contract. A change limited to that ABI does not
require an AsyncAPI change, but it must update the C header, Rust FFI worker,
Dart wrapper, receiver ABI version when incompatible, and affected consumers
together. Any change to frames sent over WSS still requires the normal protocol
contract review.

## Preserve security invariants

- Keep pairing transcripts, protected credentials, certificate fingerprints, field names, and optional rollout behavior compatible.
- Never log or commit pairing tokens, private keys, signing credentials, or authenticated stream URLs.
- Prefer additive, backward-compatible changes unless the user explicitly authorizes a breaking protocol revision.

## Verify

From `protocol/`, always validate the AsyncAPI structure and references:

```bash
ruby scripts/check-spec.rb
```

When protobuf changed, regenerate and verify the retained committed bindings:

```bash
./generate.sh
./generate.sh --check
```

Then run focused checks in every affected consumer project. Validate shared Kotlin changes through both Android Gradle roots.
