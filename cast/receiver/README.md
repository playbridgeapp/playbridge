# PlayBridge Cast Receiver

Reusable secure receiver runtime for native PlayBridge connections.

The crate owns TLS/WSS, SAS pairing, bearer-token authentication, connection and
message limits, command decoding, and receiver event delivery. Consumers provide
the TLS identity, authorized token records, capabilities, playback implementation,
and platform lifecycle.

The CLI consumes the crate directly. Flutter Desktop consumes the same runtime
through `cast/ffi` and keeps its existing `PlayerController` as the playback
adapter. Android and Apple receivers can adopt the FFI event API without moving
foreground services, NSD, activities, or platform media engines into Rust.

The optional built-in mDNS advertisement is intended for native binaries such as
the CLI. Mobile applications should normally use their platform discovery API.

## Runtime guarantees

The defaults allow 32 concurrent connections, 1 MiB WebSocket messages, 64
queued outbound messages per connection, and a 60-second pairing window. Slow
clients cannot grow an unbounded response queue.

Authorized credentials may be supplied as legacy raw tokens or
`sha256:<hex>` digests. Successful pairing emits the raw token exactly once for
the consumer to persist securely. Replacing the authorized-token set immediately
disconnects sessions authenticated by credentials that are no longer present.

The receiver identity is caller-owned and must remain stable across restarts so
existing senders retain a valid SPKI pin. The receiver crate never owns playback:
the CLI adapts events to external mpv, while Flutter Desktop adapts them to
`PlayerController`.
