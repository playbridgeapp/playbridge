# Protocol model strategy

PlayBridge uses JSON over secure WebSockets. Protobuf is an implementation schema and code
generation tool; protobuf binary encoding is not used on the wire. The long-term strategy is to
retain generated protobuf models where they provide shared, importable types and use AsyncAPI to
specify the complete JSON/WSS contract.

This is a deliberate hybrid architecture, not a staged plan to remove protobuf.

## Decision

- `asyncapi.yaml` and `docs/WSS_FLOW.md` are authoritative for on-wire JSON shapes, message
  direction, sequencing, transport, authentication, and security behavior.
- `proto/messages.proto` defines shared implementation models used to generate Kotlin, Dart, and
  Swift types.
- Generated models serialize and parse the same JSON described by AsyncAPI through Wire/Moshi,
  protobuf Dart JSON mapping, and SwiftProtobuf JSON mapping.
- Hand-written JSON remains appropriate for heterogeneous envelopes, platform-local messages, and
  messages that do not yet justify a shared generated model.
- If AsyncAPI and protobuf disagree about the wire representation, AsyncAPI wins and protobuf or
  its adapter must be corrected.

## Why retain generated models

Android phone/TV, Desktop, and Apple TV already compile against generated models. These models
provide stable imports, typed constructors, presence APIs, enum handling, and compile-time
feedback when a shared payload changes. Replacing them would require a second cross-language
generation system or duplicated hand-written models without changing the JSON WSS transport.

Generated line count is not equivalent to maintained line count. The generated files are build
artifacts reviewed through schema changes and generation-drift checks rather than line by line.

## Responsibilities of each contract

| Contract | Owns | Does not own |
|---|---|---|
| AsyncAPI | Exact JSON field names/types, envelopes, operations, directions, examples, binary pointer frame | Platform class APIs or serializer implementation |
| WSS flow documentation | Discovery, TLS pinning, SAS pairing, auth, ordering, state transitions, compatibility rules | Generated model definitions |
| Protobuf | Shared typed payload/message models and language binding generation | WSS routing, complete protocol sequencing, or protobuf binary transport |
| Compatibility fixtures | Proof that implementations encode/decode the canonical JSON | Product behavior outside the protocol boundary |

## Generated artifact policy

- Commit generated Kotlin, Dart, and Swift models because current application builds import them.
- Do not generate or commit bindings for languages without an in-repository consumer.
- Pin generator versions and verify `generate.sh --check` in CI.
- Never hand-edit generated files; change `messages.proto`, regenerate, and review the schema plus
  resulting compatibility impact.
- Keep protobuf runtime dependencies scoped to projects that use the generated models.

## Model coverage policy

Add a message or payload to protobuf when it has a stable structure and at least one of these is
true:

- multiple platforms consume it;
- application/domain code benefits from passing the typed value beyond the WebSocket boundary;
- optional-field presence, enums, maps, or nested payloads would otherwise be duplicated;
- the message is security-sensitive and shared field definitions reduce compatibility risk.

Hand-written JSON is reasonable when a message is platform-local, intentionally dynamic, a
simple routing envelope with heterogeneous payloads, or not used outside a narrow protocol
boundary. Hand-written messages must still be represented in AsyncAPI.

The current protobuf schema does not cover every live WSS message. Coverage should be expanded
when shared typing has a concrete benefit; completeness by itself is not a reason to generate a
class for every envelope.

## Change process

For a wire-compatible protocol change:

1. Update `asyncapi.yaml` and `docs/WSS_FLOW.md` when behavior or sequencing changes.
2. Add or update canonical JSON fixtures.
3. Update `proto/messages.proto` when an affected consumer uses a generated model.
4. Regenerate committed bindings with `generate.sh`.
5. Verify each affected sender and receiver against the same fixtures.

Prefer additive optional fields and new message/action values. Renamed fields, changed JSON types
or units, removed values, and cryptographic transcript changes require an explicit versioned
rollout.

## Compatibility test roadmap

1. Add fixtures for pairing, auth, play/playlist, queue, status, tracks, settings, and errors.
2. Validate every fixture against its AsyncAPI schema.
3. Decode applicable fixtures with Kotlin, Dart, and Swift generated models.
4. Encode representative models and compare their normalized JSON with the fixtures.
5. Retain interoperability coverage for at least one older sender/receiver version during a
   breaking rollout.

Security fixtures must contain synthetic values only. Tests and logs must never expose real
tokens, private keys, credentials, request headers, cookies, or authenticated stream URLs.

## When to reconsider protobuf

Removing protobuf should require a separate architecture decision supported by evidence, such as:

- protobuf JSON mapping cannot represent required wire semantics;
- maintaining the protobuf/AsyncAPI alignment repeatedly causes production defects;
- a proven AsyncAPI/JSON Schema generator provides equivalent Kotlin, Dart, and Swift ergonomics;
- protobuf runtime or generator maintenance becomes a measurable product cost; and
- all generated-model consumers have an independently justified migration path.

AsyncAPI schema coverage or generated line count alone is not sufficient justification for
removing protobuf.
