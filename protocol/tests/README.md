# Receiver-capabilities contract tests

All values are synthetic. `TEST_ONLY_NOT_A_CREDENTIAL` is a dummy literal required to exercise
CredentialBundle's token field, never a real token. No media URLs, header secrets, private keys,
or live credentials are included. These tests never connect to devices.

## Schema validation

From `protocol/`, use an **existing** Python environment with PyYAML 6 and jsonschema 4 (draft
2020-12 support). Scripts never install packages:

```sh
ruby scripts/check-spec.rb
python scripts/validate-fixtures.py
python scripts/check-retained-parsers.py
```

The Ruby checker validates structure/references only. The Python validator checks all component
schemas against the JSON Schema metaschema and validates actual payloads through local `$ref`,
`oneOf`/`allOf`/`anyOf`, required/type/const/uniqueness/additional-property/range constraints.
Its errors report fixture/schema paths without echoing payloads. The manifest includes 37 valid
and 25 invalid cases; every JSON fixture must be listed. Fixtures cover auth and decrypted bundles,
with/without descriptors for Android-TV-like, Apple player-only, Desktop and CLI hosts, unknown
identifiers and opaque future versions, empty complete sets, app/system volume and full settings.

`future-apple-app-volume` and `future-apple-complete-settings` are future contract examples, not
claims of deployed Apple support. Android-TV-like fixtures illustrate engine differences; real
receiver builders must derive their advertisements from implemented dispatch/availability.

Negative fixtures reject wrong/missing version, contradictory surface arrays, duplicate/nonstring
operations, transient state, plaintext outer-pairing descriptors, premature reserved pushes,
percent-as-normalized-volume, missing volume/state fields, loop-only settings and `status.loop`.
Unknown versions deliberately change the type of `surfaces`, proving why raw version selection
must precede typed descriptor decoding. Unknown identifiers are accepted, including engines,
surfaces, operation sets, host labels and companion hints.

Retained strict schemas demonstrate that old `additionalProperties: false` rejects new auth
properties. They also reproduce the **pre-existing** `subtitle_resource_add_v1` feature mismatch
in both AuthResponse and CredentialBundle. The validator asserts existing auth fields/required
lists, protected outer credentials, browser capabilities and Status remain unchanged.

## Maintained old parser boundary

`legacy/manifest.json` pins pre-contract revision `39934a056d04a79a00f31231e84d454a986f3508`,
source paths and SHA-256 hashes. `.inc` fragments are retained verbatim and embedded in isolated
harnesses. The provenance script checks fragment/wrapper integrity and the original Git objects;
it requires that pinned revision to be available locally.

- Android: original `emitCapabilities` and `parseStringArray`, with state/flow/coroutine callbacks
  replaced by local captures. The old auth success expression and post-decryption token/pin field
  reads are exercised too. The harness compares parsed legacy fields with/without capabilities.
- Apple: original `handleAuthResponse` / `emitCapabilities`, with state/socket/ping callbacks
  replaced by local stubs; token/pin field projections after decryption are compared.
- Desktop: original `_handleAuthResponse`, with local state/close stubs, plus the original
  post-decryption token/pin field-read expressions.
- Rust: exact old ReceiverFrame/PlaylistStatusItem/CredentialBundle serde definitions in an
  isolated crate; compare full decoded models with/without capabilities and check unknown events.

Every harness exercises the same 29 valid auth/decrypted-bundle cases. These are parsing/projection
checks, not complete historical binaries, lifecycle integration, AES-GCM/SAS round trips or TLS/SPKI
security tests. Full authorization/pin validation happens outside these extracted methods and is
not weakened. This is tested compatibility for identified maintained parsing surfaces, **not a
universal zero-breakage claim** for strict, generated or third-party JSON readers. Supporting those
universally may require opt-in descriptor delivery before receiver emission.

Run with existing tools/caches from `protocol/`:

```sh
swift tests/legacy/LegacyAppleParser.swift tests/fixtures/receiver-capabilities
dart tests/legacy/legacy_desktop_parser.dart tests/fixtures/receiver-capabilities
cargo test --offline --locked --manifest-path tests/legacy/rust/Cargo.toml \
  --target-dir /private/tmp/stage5-retained-rust
```

Android harness (from `mobile/android/`, macOS zsh after sourcing `~/.zshrc`):

```sh
./gradlew --offline --no-daemon --max-workers=2 \
  --init-script ../../protocol/tests/legacy-android.init.gradle \
  :app:compileFossDebugKotlin :app:testFossDebugUnitTest \
  --tests stage5.contract.LegacyAndroidParserTest
```

The init script injects only protocol-owned test sources and the already-used cached Wire/Moshi
adapter dependency into that invocation. It edits no consumer source or build file. Three tests
cover old Android fields, new Kotlin model/open-ID decoding and token diagnostics, and raw future
version selection. Generated Dart models are separately checked by `tests/generated-models.dart`,
including required zero-volume presence. Use `dart --packages=<existing-package-config>` with
already available protobuf 4.1.0/fixnum/collection/meta packages; no `pub get` is required. The new
Dart/Kotlin checks are not retained-old generated-parser tests.

## Generation and consumer follow-ups

```sh
./generate.sh
./generate.sh --check
```

The retained generator writes Kotlin/Dart/Swift only. There are no retained TS/Go message models;
Go constants are not generated messages. An existing alternate compiler may be selected via
`PROTOC`; protoc 3.13 needs an external wrapper adding `--experimental_allow_proto3_optional`.
Do not install or repair prerequisites as part of this task. Wire's cached compiler must already
exist to avoid its generator bootstrap download.

Compiled Android models and decoded generated Dart helpers do not prove every Swift/runtime or
protobuf JSON serializer is wire-compatible. Verify generated Swift against its consumer's
SwiftProtobuf package, and validate encoded JSON defaults/required empty maps/integer units and
unknown-field behavior wherever generated JSON serialization is adopted. Protobuf binary unknown-
field tolerance is not JSON tolerance.

Consumer steps must add full first-pairing encrypted-bundle/reconnect/receiver-switch/reset tests,
raw version gates, exact legacy policy goldens, recognized-empty/partial operation behavior,
engine/context/media readiness, state availability, volume units/mute/scope, queue acknowledgements,
keyboard/gesture/channel fences, and independent paired/discovered companion overlays. Runtime
publication must be consistent across auth and protected credentials and preserve existing pin,
token replacement/revocation and SAS security smoke tests. No dynamic native push is shipped here.
