# PlayBridge Cast Core

This Rust crate owns the portable parts of PlayBridge receiver discovery,
protocol negotiation, and casting. Android and Desktop already consume its
discovery bindings; applications can use the Rust `ReceiverSession` API for
playback and control while platform code owns lifecycle and UI.

It currently proves:

- one UDP socket can issue bounded DLNA, Roku ECP, and DIAL searches;
- `rupnp` can load a renderer description and execute AVTransport actions;
- Roku ECP discovery, media launch, status, and remote-key controls;
- a unified `ReceiverSession` API for PlayBridge, DLNA, Roku, and Google Cast;
- `m3u8-rs` can classify HLS playlists while PlayBridge retains its own proxy
  URL-rewriting and duration policy.
- `mdns-sd` can resolve `_playbridge._tcp.local.` receivers and preserve UUID,
  dynamic WSS port, diagnostics port, IPv4, and IPv6 endpoint information;
- PlayBridge JSON frames, the compact 9-byte pointer frame, and the complete
  sender side of SAS pairing use portable Rust types and cryptography.

Run the deterministic test suite with `cargo test`. On a LAN with receivers,
run `cargo run --example discover`. Discovery requires the host application to
provide platform prerequisites: Android must hold a `WifiManager.MulticastLock`,
and Apple platforms require Local Network permission and the multicast
entitlement. Those lifecycle concerns intentionally remain outside this crate.

An interactive first-pairing probe extracts the receiver certificate's SPKI
pin from its active TLS socket:

```text
cargo run --example pair -- wss://receiver.local:8765/
```

The example sends the SAS commit/reveal/confirmation sequence, asks for the
six digits displayed by the receiver, decrypts the protected credential bundle,
checks its protected pin against the active connection, requests context, and disconnects.
It deliberately does not print or persist the bearer token.

A known receiver can be checked without sending credentials:

```text
cargo run --example check_pin -- wss://receiver.local:8765/ sha256/<base64-pin>
```

Roku discovery uses the official `roku:ecp` SSDP target. DIAL remains a separate
generic provider for receiver applications that explicitly support it.

The core WSS transport extracts the active certificate's SPKI pin. First-time
pairing binds protected credentials to it; reconnects verify a saved pin before
the caller can send `auth`. Host applications still own secure token/pin
storage and lifecycle policy.
