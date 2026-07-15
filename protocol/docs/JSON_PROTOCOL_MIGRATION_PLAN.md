# JSON WSS protocol migration plan

This plan describes the eventual migration from protobuf-generated payload models to the
JSON-first WSS contract. It is intentionally separate from the current protocol move: protobuf
and its generated bindings remain supported until every consumer has migrated.

## Target architecture

`asyncapi.yaml` is the behavioral contract for WSS: operations, directions, sequencing, security,
and message names. Its JSON Schema components are the canonical wire shapes. The wire remains
UTF-8 JSON (plus the existing pointer frame); this migration does not change the transport.

Each consumer owns native models and validation generated or maintained from those schemas:

| Consumer | Current model | Target model |
|---|---|---|
| Android phone and TV | Shared Kotlin/Wire protobuf classes | Kotlin serialization JSON models |
| Desktop | Generated Dart protobuf classes | Dart JSON DTOs/serializers |
| Apple TV | Generated SwiftProtobuf classes | Swift `Codable` models |
| Apple phone | Hand-written JSON | Shared/validated Swift `Codable` models |
| Browser extension | Hand-written JSON/native messaging | TypeScript types plus runtime validation |

AsyncAPI is not itself a runtime serializer. Before migrating a platform, select and pin a
repeatable JSON-model generation or validation toolchain for that platform. Do not replace
working generated bindings with ad-hoc duplicate models without fixtures and compatibility tests.

## Phased execution

### Phase 0 — Contract hardening (current)

- Keep `proto/messages.proto` and all committed generated outputs unchanged for existing builds.
- Complete AsyncAPI coverage for every live message, including `tracks`, `player_settings`, browser
  administration, pairing/authentication, and pointer input.
- Document sequencing and security invariants in `docs/WSS_FLOW.md`.
- Add canonical JSON fixtures for handshake, auth, play, queue, status, and error messages.
- Add CI checks for AsyncAPI references, fixture validation, and generated protobuf drift.

Exit criterion: every supported message has one documented JSON shape and at least one fixture.

### Phase 1 — Introduce JSON models beside protobuf

- Add the target native model layer without deleting protobuf classes.
- Make encoders produce the canonical JSON fixture shape.
- Make decoders accept the current wire shape and reject malformed required fields.
- Keep protobuf adapters at the boundary so existing call sites can migrate incrementally.

Initial slices should be low-risk and widely shared: `ping`/`pong`, `auth`, `auth_response`,
`context`, and `status`.

Exit criterion: each platform can round-trip the migrated messages without using protobuf types
in its WebSocket boundary code.

### Phase 2 — Migrate command and playback families

Migrate `play`, queue/playlist, control, remote, browser, and player-setting messages one family at
a time. For each family:

1. Update AsyncAPI and fixtures first.
2. Implement native encode/decode models on every affected consumer.
3. Run cross-platform fixture tests and focused app tests.
4. Remove the protobuf dependency from that family only.

Keep field names, JSON types, units, optionality, enum values, and security-sensitive transcript
bytes backward compatible. Add fields rather than renaming or reinterpreting existing ones.

### Phase 3 — Dual-read rollout and observability

- During one compatibility window, receivers may dual-read old and new internal representations,
  but they must emit only the canonical JSON wire shape.
- Record development/test-only decode failures by message family; never log tokens, credentials,
  private keys, or authenticated URLs.
- Verify pairing, certificate pin binding, credential encryption, and reconnect auth on every
  platform before removing their protobuf adapters.

### Phase 4 — Remove legacy protobuf

After all consumers have completed migration and the compatibility window has elapsed:

- Remove protobuf adapters and generated bindings from app build graphs.
- Remove `messages.proto`, `generate.sh`, and protobuf-specific CI checks in a separate change.
- Retain historical fixtures and AsyncAPI contract checks.
- Publish a protocol version/release note documenting the removal and minimum app versions.

## Compatibility and testing requirements

- Every fixture must be decoded by all applicable receivers and encoded by all applicable
  senders.
- Test unknown fields, omitted optional fields, invalid required fields, enum additions, and
  numeric precision/units.
- Test the full SAS pairing and encrypted credential flow; cryptographic transcript changes are
  breaking changes and are not part of ordinary model migration.
- Keep sender/receiver interoperability tests for at least one old and one new app version during
  the rollout.

## Ownership and change process

Protocol maintainers change `asyncapi.yaml`, fixtures, and migration documentation. Consumer
owners change platform models and adapters. A protocol PR is not complete until the affected
consumer owners have reviewed the fixtures and focused tests.

No protobuf deletion should be merged solely because AsyncAPI has equivalent schemas; deletion is
gated on the consumer exit criteria above.
