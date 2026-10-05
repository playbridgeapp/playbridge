# Security Policy

## Supported Versions

PlayBridge currently ships **0.x alpha releases**, with separate versions for
each component. There is no supported 1.0.x release line.

| Version | Security support |
| ------- | ---------------- |
| Latest published alpha release of each component | Reports accepted; fixes target the next release |
| Current `main` development branch | Reports accepted; fixes developed here |
| Older component releases | Upgrade recommended; no maintained backport branches |

See [GitHub Releases](https://github.com/playbridgeapp/playbridge/releases) for
published versions. Include the component, version, and commit (if known) in
your report; a draft release is not a published security update.

## Reporting a Vulnerability

We take the security of PlayBridge seriously. If you have discovered a security vulnerability, please do not disclose it publicly.

1.  **Report**: Please email the security team at `playbridgeapp@gmail.com`.
2.  **Response**: We will acknowledge your report within 48 hours.
3.  **Resolution**: We will work to resolve the issue as quickly as possible and will keep you updated on the progress.

## Security Considerations

The normative sender/receiver requirements are defined in
[`docs/SECURITY_STANDARD.md`](docs/SECURITY_STANDARD.md). The wire-format details
are documented in [`protocol/README.md`](protocol/README.md). Known implementation
gaps and their remediation order are tracked in
[`docs/SECURITY_GAPS.md`](docs/SECURITY_GAPS.md).

### Local Network

PlayBridge does not treat the local network, mDNS discovery, IP addresses, or
device names as trusted identities. The control channel uses `wss://`. Previously
paired senders authenticate receivers by their saved TLS SPKI pin before sending
credentials or commands.

### Pairing and Authentication

First pairing uses an ephemeral X25519 exchange, a user-compared short
authentication string (SAS), cryptographic key confirmation, and AEAD-protected
delivery of the receiver token and certificate pin. Later connections use the
saved pin and token. A pin mismatch requires explicit re-pairing; plaintext
pairing and control-channel downgrade fallbacks are prohibited.

### Media Transport

Some media sources intentionally use plain HTTP or self-signed TLS, especially
local servers and direct/torrent streams. This media compatibility exception is
separate from the PlayBridge control channel. Authenticated headers and cookies
must remain bound to their intended origin across redirects and derived playlist,
segment, subtitle, artwork, and proxy requests.

Thank you for helping keep PlayBridge safe!
