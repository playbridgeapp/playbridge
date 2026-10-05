# Website page API: sender-only field policy

This directory records requirements for the Android GeckoView and iOS WebKit
`window.playbridge` API. The full versioned types, schemas and conformance
contract are tracked in [#243](https://github.com/playbridgeapp/playbridge/issues/243).
The receiver WSS protocol in [../asyncapi.yaml](../asyncapi.yaml) is a separate
contract; a field supported by receivers is not automatically available to websites.

## Progress callbacks are not a website capability

`progressWebhook` is reserved for trusted sender tooling. Websites must never set
it through `cast()`, `linkCast()`, `play()` or linked-session operations, even if
the receiver advertises `progress_webhook_v1`.

- Reject the field at the request/envelope, playback payload or item level,
  including when its value is `null`, `false` or otherwise invalid.
- Promise-based operations reject with `invalid_request`. Legacy fire-and-forget
  `cast()` requests are discarded before native playback effects.
- Revalidate at the native boundary, independently of page-script checks.
- Build outgoing website playlists from allow-listed fields; never set or inherit
  `progressWebhook` from an earlier sender session.
- This rule applies to structural API fields, not coincidentally named strings
  or keys inside arbitrary metadata. Continue allow-listing all other fields.
- Native gesture, document identity and origin-consent checks remain authoritative.

Android extension/native and iOS parser/coordinator tests must cover rejection
and the absence of the field from outgoing website playlists. This policy does
not change the trusted-sender WSS webhook capability.
