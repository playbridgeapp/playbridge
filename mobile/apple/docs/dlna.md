# iOS DLNA sender

The linked Rust Cast Core handles SSDP discovery, UPnP device descriptions, SOAP
media loading, status, play/pause, stop and relative seek. iOS owns the setup UI,
saved receivers, session lifetime and route preparation. No ABI change is needed.

Open **Set up new TV** for DLNA discovery. Searches last five seconds, can be
repeated, and cancel when setup closes. The normal picker only shows previously
connected external receivers under **Recent other**. Saved Google Cast data is
backward compatible; the existing storage key now holds both external protocols.
Manual connection accepts an HTTP(S) UPnP device-description URL, not a video URL.
The friendly name returned by the receiver is saved after connection succeeds.

Direct, Via phone and Via proxy apply to sending. Direct requires a URL the TV
can fetch without custom browser headers. The proxy routes preserve upstream
headers. Format support depends on the TV; proxying does not transcode media.
External subtitles, queues, volume and receiver-app shutdown are not supported
by the current DLNA adapter and are hidden in its UI.

## Physical iPhone discovery prerequisite

Apple requires the restricted `com.apple.developer.networking.multicast`
entitlement for SSDP multicast on physical iOS devices:
https://developer.apple.com/news/?id=0oi77447

The existing target does not have this entitlement. Request approval for the app
identifier, enable it in the provisioning profile, then set the phone target's
Code Signing Entitlements to `../config/DLNAMulticast.entitlements` (relative to
`mobile/apple/PlayBridge Phone`). The template is supplied but not enabled by
default, so existing provisioning profiles remain usable. The app also needs
Local Network permission. Until provisioned, automatic SSDP discovery is not
verified/expected on physical iPhones; manual URL connection uses unicast HTTP.

## Verification

`bash mobile/apple/tests/run-dlna-checks.sh` links the actual host Rust archive
and drives a local SOAP renderer fixture through the production Swift session.
It verifies connection, load, play/pause, relative seek, status, stop, XML escaping,
receiver parsing and saved Google Cast compatibility. The existing Google Cast
controller tests cover ready-state handling, cancellation and reconnect.

The fixture does not validate SSDP delivery on iPhone or actual TV decoding.
Hardware validation still requires an entitled iPhone build and a DLNA renderer.

## Roku and optional DIAL discovery

The same bounded Rust SSDP browser now supports Roku (mask 4) and generic DIAL
(mask 8), with separate scan instances and generation-scoped cancellation.
Setup automatically searches DLNA and Roku. DIAL is opt-in through **Search app
receivers (DIAL)** and presents informational results without a Cast action:
Cast Core has no generic DIAL media session. Leaving setup cancels all scans.
Physical iPhone Roku/DIAL discovery needs the same multicast entitlement.

Roku supports manual HTTP/IP entry (default ECP port 8060), saved history,
Direct/Via phone/Via proxy sends, status, play/pause/stop and forward/reverse.
Forward/reverse are ECP commands, not fixed ten-second seeks. Sending requires
Rust to confirm the Play on Roku receiver app is available. Absolute volume and
Google Cast's receiver-app shutdown action are not shown for Roku.

The SOAP fixture also exposes Roku ECP endpoints. `run-dlna-checks.sh` checks
real Rust Roku connection, media launch URL encoding, playback controls, discovery
record parsing and DIAL identity separation. Hardware behavior is still unverified.
