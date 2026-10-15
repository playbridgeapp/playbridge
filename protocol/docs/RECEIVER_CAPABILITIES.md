# Native receiver capabilities v1

This is the Stage 5 native capabilities contract. Receivers must implement and verify an
operation before advertising it. The illustrative fixtures are not
permission to add controls to current senders. Hosted-browser `BrowserCapabilitiesFrame` and
native-host bridge capabilities remain separate contracts.

## Authority, version and lifetime

`AuthResponse.capabilities` and decrypted `CredentialBundle.capabilities` contain the same
optional `ReceiverCapabilities` descriptor. Existing auth fields, required lists, token/SPKI
semantics, SAS transcript, derivation labels and GCM AAD are unchanged. Do not put capabilities
on the unencrypted `pairing_approved` envelope. Send the current descriptor on successful auth
and inside protected first-pairing credentials; refresh it on every new authenticated session,
including reconnect. Clear the old session's descriptor when changing receivers.

Read `version` from raw JSON before decoding a typed descriptor. **Absent descriptor or unknown
version uses the exact frozen legacy sender behavior**, including existing case-sensitive
profile precedence, context/media restrictions, observed state fallbacks and independent Full
control projection. Do not invent engines, infer a hosted browser from empty native arrays,
reclassify Desktop as app-only volume, or add Apple Home/volume merely from an OS/feature hint.

Version 1 is complete. `surfaces` is the single map of surface IDs (`player`, `browser`) to engine
maps. There is no second surface list or parallel top-level player/browser block. An engine's
presence means available; omit unavailable engines. An empty map/set or missing operation set
means unsupported. Never fill absent operations from OS/profile guesses, legacy flags, another
surface or another engine. A valid `{version: 1, surfaces: {}}` exposes no native surface controls.
A malformed recognized v1 descriptor must fail closed for descriptor-driven native controls,
rather than triggering an OS-based fallback; it does not weaken token/pin authentication.

Every engine declares its own complete operation sets. Use the active surface and engine reported
in `context.engine` (optional addition) / `player_settings.engine`; engine switching changes
state, not supported operations. If active engine is unknown, restrict execution to the
intersection of applicable engine operation sets until state arrives; do not union engines'
capabilities. A `browser/launch` operation may select its explicitly advertised engine while
idle. Live/seekability, media kind, current tracks/queue, setting availability and connection
readiness can further restrict a declared operation, never add one.

Identifiers are open, case-sensitive strings, not enums. Preserve unknown surface/engine/operation/
host/hint identifiers in models and safely ignore identifiers a consumer cannot execute or
present. Unknown identifiers must not cause an entire valid v1 descriptor to become legacy.
Unsupported descriptor versions are opaque and may have an entirely different shape.

Keep the descriptor static and bounded (senders should bound its serialized JSON to 64 KiB
before typed parsing). No active engine, current level/mute, loop value or media readiness belongs
in it. Supported settings and reporting operations are capabilities; their current values belong
in state frames. Legacy `players`, `browsers`, `mediaKinds`, `screenMirrorWebRtc` and `features`
remain populated during migration. Media kinds/mirroring/feature-token semantics remain canonical
there; they are not duplicated in a second descriptor feature list. For recognized v1 controls,
the operation sets authorize commands; the legacy tokens still describe command results, stable
IDs, guarded queues, diagnostic logs and other feature contracts.

`host.osFamily` (known `android`, `tvos`, `macos`, `windows`, `linux`) and `host.deviceClass`
(known `tv`, `desktop`, `cli`) are informational. `companionHints: ["android_tv_remote_v2"]`
means an OS companion **may be available**. Absence is not a negative statement. Hints do not
provide command support, trust, device matching, automatic pairing, service reachability, or a
veto of a discovered/already paired channel. The independently verified phone companion channel
may still overlay its keys/text/volume on a native receiver snapshot.

## Shape

```json
{
  "version": 1,
  "surfaces": {
    "player": {
      "engines": {
        "avplayer": {
          "transport": ["play", "pause", "toggle", "stop", "seek_absolute", "seek_relative"],
          "keys": ["dpad_left", "dpad_right", "dpad_center"],
          "settings": ["loop", "engine"],
          "tracks": ["audio_select", "subtitle_select", "subtitle_url_add", "subtitle_resource_add"],
          "queue": ["replace", "append", "jump_index", "jump_item", "remove", "move", "clear"],
          "reporting": ["status", "context", "tracks", "playlist_status"]
        }
      }
    }
  },
  "host": {"osFamily": "tvos", "deviceClass": "tv"}
}
```

This player-only example intentionally has no browser, pointer, text or volume support. Its
engine list is illustrative; the real Apple builder must include all available engines. Known
engine IDs are `exo`, `mpv`, `avplayer`, `internal_mpv`, `webview`, `gecko`. Engine labels and unknown
engine presentation remain sender concerns; an engine ID must never determine an OS capability.

## Operation identifiers and command mapping

Commands remain subject to context, media, readiness and existing authorization policies.

| Engine set | Known identifiers | Native wire mapping / qualifications |
| --- | --- | --- |
| `transport` | `play`, `pause`, `toggle`, `stop` | Player `command/control` with same command; browser transport uses implemented `browser_control` actions such as `toggle_play`. Do not advertise independent browser play/pause if only toggle is implemented. |
| `transport` | `seek_absolute`, `seek_relative` | Player control `seek_to:<milliseconds>`, `seek_back` / `seek_forward`; browser `browser_control/seek_to:<milliseconds>` for bounded relative/absolute targets. No promise of seekability for live streams/images. |
| `keys` | Exact `dpad_up`, `dpad_down`, `dpad_left`, `dpad_right`, `dpad_center`, `back`, `home`, `key_enter` | `command/remote` key. Volume keys are authorized solely by the common volume block, not engine keys. Unknown keys are ignored. Home means PlayBridge receiver idle, not whole-device Home. Back is app/surface navigation. |
| `pointer` | `move`, `click`, `down`, `up`, `scroll`, `zoom` | `command/mouse` event; drag requires down + move + up. Gecko browser zoom must not be claimed merely because WebView supports it. |
| `imageTransforms` | `pan`, `scroll`, `zoom`, `rotate`, `anchor`, `reset` | Player-image mouse events `move`, `scroll`, `zoom`, `rotate`, `transform_anchor`, `reset` with existing dx/dy conventions. Image transformations do not imply browser click/drag/text support. |
| `text` | `replace`, `delete`, `enter` | `remote/text:<base64-UTF8>`, `remote/key_delete`, `remote/key_enter`. Delete only after a real standalone handler exists. Today's phone Delete-shortened-replacement is still `replace`; no append promise. |
| `settings` | `loop`, `engine`, `quality`, `speed`, `scaling`, `audio_boost`, `subtitle_offset` | Control `loop_on/off`, `switch_player:<id>`, `video_quality:auto|<height>`, `speed:<number>`, `scaling:<mode>`, `audio_boost`, `sub_offset:<milliseconds>`. Further availability is reported in player_settings. |
| `tracks` | `audio_select`, `subtitle_select`, `subtitle_url_add`, `subtitle_resource_add` | Control `audio_track:<id>`, `sub_track:<id>`, legacy `add_subtitle:<url>`, or `add_subtitle` with resource-scoped headers. Reporting tracks is a separate operation. |
| `queue` | `replace`, `append`, `jump_index`, `jump_item`, `remove`, `move`, `clear` | `playlist`, `queue_add`, index/itemId `playlist_jump`, `queue_remove`, `queue_move`, `queue_clear`. Preserve queue feature/guard/acknowledgement rules. Local CLI dashboards are not wire implementations. |
| `browser` | `launch`, `forward`, `reload`, `cycle_source`, `unmute`, `fullscreen`, `manage_user_scripts`, `manage_user_agent` | `browser` with selected engine; browser_control `forward`, `refresh`, `video_target_cycle`, `video_unmute`, `maximize_video` / `restore_video`; existing script/agent administration frames. Navigation keys remain solely in `keys`. |
| `reporting` | `status`, `context`, `tracks`, `playlist_status`, `player_settings`, `fullscreen` | Receiver frames of the same name; `fullscreen` means optional browser `status.fullscreen`. None is a transient value or a promise that a media item is active. |

Sleep/screen operations have no invented v1 wire mapping; future operation identifiers remain
extensible, but must not be advertised without a defined, implemented command. The old narrow
mouse/browser-control schema vocabulary has been widened to include the already implemented
operation families above; this changes validation, not receiver dispatch.

## Complete player settings, including smaller receivers

Keep the existing required fields `[type, speed, scaling, audioBoost, subtitleOffsetMs, engine]`.
A receiver with only loop/engine sends a **complete snapshot** on initial sync, relevant changes
and resync. Unsupported settings use neutral values, plus explicit false availability flags:

```json
{
  "type": "player_settings", "speed": 1, "scaling": "Fit", "audioBoost": false,
  "subtitleOffsetMs": 0, "engine": "avplayer", "loop": false,
  "speedAvailable": false, "scalingAvailable": false, "audioBoostAvailable": false,
  "qualityAvailable": false, "subtitleOffsetAvailable": false,
  "engineAvailable": true, "loopAvailable": true
}
```

Neutral values are compatibility values, not support claims. Unsupported scaling/boost/speed/
subtitle offset are never fabricated as supported. The new optional flags fill gaps in the
existing availability vocabulary; old receivers may omit them. A recognized descriptor still
needs its `settings` operations; a true flag cannot authorize a missing operation. For a listed
operation an absent flag preserves existing engine/media eligibility, while false restricts it.
Omit optional loop when the receiver cannot report it. **No `status.loop`**. The full snapshot
avoids changing old required fields or making every existing consumer guess whether a partial
snapshot replaced or merged previous settings. New player-settings protobuf helper fields are
optional implementation fields; AsyncAPI still enforces the complete WSS snapshot.

## Common volume route: app versus system

`volume` is independent of a player/browser block. Omission/empty operations is unsupported.
Known scopes are `system` (system media output) and `app` (PlayBridge audio attenuation).
`fallbackScope: "app"` on a system route describes only explicit relative-step fallback, as used
by current Desktop; it does not imply app-only controls, absolute volume, mute or reporting.
Unknown scopes/operations are ignored; no route is inferred from host OS or player engine.

| Declared operation | Command/report | Semantics |
| --- | --- | --- |
| `relative_step` | Existing remote `volume_up` / `volume_down` | One key means one step on the advertised common route. App defaults to ±0.05 normalized units unless a stable positive `step` is advertised. System uses one native platform increment; optional step may describe a stable normalized increment. Repeat keys for multiple steps. Clamp at 0 and 1. |
| `absolute` | New native `command`, action `volume`, payload `{scope, level}` | Scope must match the advertised route and level is a finite JSON number in [0,1], never 0–100. Unsupported scope/operation is rejected/ignored without rerouting. Absolute commands are not defined for fallbackScope. App level changes preserve the independent mute flag; mute is changed explicitly by mute_toggle. System steps retain native platform behavior and report its actual resulting state. |
| `mute_toggle` | Existing remote `mute` | Toggle mute on the advertised route, retaining the pre-mute level for unmute. Mute is not setting level to zero. |
| `level_report` | New native `volume_state.level` | Normalized current value for the actual scope, on initial sync/change/resync. System maps its actual media range to 0–1; app is player attenuation. |
| `mute_report` | New native `volume_state.muted` | Explicit true/false mute state, independent of level. Never infer it from zero volume. |

`volume_state` requires type/scope and at least one of level/muted. Fields that cannot be reported
are omitted, not fabricated; unsolicited reports do not grant command support. If relative system
stepping falls back to app, a report names the actual `app` scope. State is session-fenced and
cleared on receiver changes; it is never copied into the descriptor. Existing `context_query` is
the resync request: receivers declaring volume reporting reply with current volume_state as well
as applicable context/settings snapshots. No separate volume query is introduced.

Absolute app command example (contract only; advertise after implementation):

```json
{"type":"command","action":"volume","payload":{"scope":"app","level":0.35}}
```

The new native volume command/state do not alter the hosted-browser `set_volume` command/status.
The receiver maps app normalization to `AVPlayer.volume` and deliberately to mpv's units; the
stored level survives engine changes. Senders label app controls visibly/accessibly **App volume**.
App attenuation is not tvOS system volume or HDMI-CEC. Until handlers and real audio output are
verified, current Apple TV descriptors omit volume. Current Desktop declares relative system
steps with app fallback, and does not claim mute, absolute or reporting; CLI claims none.

## Live native replacement (step 9)

The independent native `ReceiverCapabilitiesFrame` is now defined:

```json
{"type":"receiver_capabilities","revision":1,"capabilities":{"version":1,"surfaces":{}}}
```

It replaces the entire descriptor, including removed surfaces/engines/operations/volume/hints.
Never merge or fill removed fields from a previous descriptor or legacy metadata. It does not
replace context, settings availability, volume values, media identity or canonical legacy arrays.
Engine switching alone is state and does not require a descriptor update.

**Opt-in:** supporting receivers advertise `receiver_capabilities_v1` in authenticated
`auth_response.features` and protected `CredentialBundle.features`. That feature is an offer,
not consent to receive pushes. After verifying successful auth or protected pairing, a supporting
sender sends exactly:

```json
{"type":"command","action":"receiver_capabilities_subscribe","payload":{}}
```

Only that authenticated socket is subscribed. Pre-auth subscriptions are ignored; senders without
this explicit opt-in never receive the new frame. Do not add an auth or pairing-commit field or
alter their cryptographic transcript. New senders do not subscribe when the receiver lacks the
feature. The command is idempotent and does not reset revisions; it immediately replies with the
current descriptor as revision 1, closing the race between initial auth and subscription. It
remains valid after first pairing without an extra auth exchange.

`revision` is a JSON **number**, an integer from 1 through 9007199254740991 (2^53-1), strictly
increasing for each subscribed authenticated socket. It is not a global receiver revision, a
persisted credential field, a string, or an auth/bundle field. The auth baseline has implicit
revision 0. Receivers increment only for changed advertisements, reset on a new socket, discard
subscriptions on close, and close a session rather than wrap an exhausted counter. Manual JSON
adapters must preserve numeric encoding: default protobuf JSON encodes uint64 as a string and
must not be sent verbatim. Current Android uses a bounded manual JSON writer.

Senders fence updates with the local authenticated socket/session generation and check revision
before applying. Drop pre-auth, unsolicited, stale-session, duplicate, nonincreasing and invalid
revision/envelope frames. A structurally valid newer frame with an unknown descriptor version
advances the session's ordering watermark but **keeps the current descriptor**; do not decode a
future shape as v1 or revert to legacy. Malformed recognized v1 advances ordering and **fails
closed**, replacing native operations with an unsupported snapshot until a newer valid update or
fresh auth. A valid empty v1 removes every native surface control. Continue enforcing context,
media, readiness and independently verified companion routes. Close or disable open removed
controls, and recheck current metadata at execution; unrelated updates preserve controller,
media and keyboard draft identity.

Android TV rebuilds availability for Gecko plugin package add/remove/change/replacement while
running. One serialized publication owner supplies auth, protected pairing and opted-in pushes;
a later auth/pairing sees the same current descriptor. During package replacement, ignore the
intermediate removal and recheck on add/replaced. Apple TV, Desktop and CLI receivers do not have
runtime capability changes and do **not** send this frame. iPhone/Desktop/CLI sender subscription
and consumption are step 9b; until implemented they must not opt in. Rust's existing unknown-frame
decoder safely ignores this frame, independently of subscription policy.

Never repurpose the hosted-browser `BrowserCapabilitiesFrame`, `/v1/browser/ws` protocol or generic
browser `capabilities` type. Never log the descriptor, credentials, pins or typed text.

## Compatibility boundary

The maintained manual Android/Apple/Desktop auth readers and Rust serde are tolerant of extra
fields. The tests retain their old parsing surfaces at revision `39934a05`, before this contract;
see [the fixture/test guide](../tests/README.md). They do not prove all released/generated or
third-party strict JSON readers accept optional fields. The old strict AsyncAPI AuthResponse
rejects `capabilities`, and already rejects deployed `subtitle_resource_add_v1`; CredentialBundle
had an even shorter feature enum. Those pre-existing feature mismatches are reconciled with one
extensible string vocabulary, not hidden by skipping schema validation.

If strict legacy readers must be supported universally, delivery needs opt-in negotiation before
receivers begin sending descriptors. Live pushes use the explicit socket opt-in above; the existing static auth delivery boundary
is unchanged. Protobuf binary unknown-
field tolerance is not proof of protobuf JSON tolerance. Version selection must precede typed
JSON decoding; generated model defaults/omission, 64-bit JSON encodings and unknown-field flags
need wire adapters/consumer tests wherever generated serialization is used directly.
