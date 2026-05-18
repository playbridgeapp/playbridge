# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

We take the security of PlayBridge seriously. If you have discovered a security vulnerability, please do not disclose it publicly.

1.  **Report**: Please email the security team at `playbridgeapp@gmail.com`.
2.  **Response**: We will acknowledge your report within 48 hours.
3.  **Resolution**: We will work to resolve the issue as quickly as possible and will keep you updated on the progress.

## Security Considerations

As PlayBridge is a casting solution for the local network, there are several architectural choices users should be aware of:

### Local Network Assumption
PlayBridge assumes a trusted local network. Communication between the phone and TV apps via WebSocket is unencrypted over HTTP (`ws://`), which is standard for local device-to-device casting (similar to early Chromecast/DIAL protocols).

### SSL Bypass Option
The TV app's `PlayerActivity` includes an option to bypass SSL verification for media streams. This is intended for local media servers or self-signed HLS streams. Users should use this with caution as it facilitates Man-In-The-Middle (MITM) attacks if used on hostile networks.

### Authentication
Pairing between phone and TV is secured via a PIN and token flow. Only authenticated devices can send commands to the TV receiver.

Thank you for helping keep PlayBridge safe!
