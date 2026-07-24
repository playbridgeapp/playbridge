# PlayBridge Browser Receiver

A sender-hosted receiver page for TVs, consoles, and computers that have a
modern web browser but no supported native casting protocol.

The Rust crate is both:

- an embeddable `BrowserReceiverHost` used by Desktop and the `playbridge` CLI;
- a standalone `playbridge-browser-receiver` binary for testing and future
  consumers.

The host serves a local page and WebSocket control channel. Every browser
connection displays a short-lived six-digit code. The sender must approve that
code before it can load or control media. Approved sessions report playback
status and capabilities and support native media, HLS.js, DASH.js, WebVTT,
seeking, and volume.

## Run

```sh
cargo run -p playbridge-browser-receiver
```

Open one of the printed LAN addresses on the receiving device, then enter its
code in the sender. Port `8770` is preferred. If it is occupied, the host checks
the next port in order, up to ten total attempts (`8770` through `8779`), and
reports an error instead of selecting an unpredictable ephemeral port when the
range is unavailable.

The generated browser bundle is checked in so Rust/Flutter release builds do
not need Node.js:

```sh
cd browser-receiver-rust/web
pnpm install
pnpm build
```
