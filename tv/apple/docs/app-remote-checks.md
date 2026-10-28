# In-app phone D-pad checks

Apple TV advertises `app.engines.native.keys` for its own foreground idle UI.
The player keeps its existing remote handler, including pre-play/still-watching rules.
Home returns to Pair Device (the idle root); it never opens tvOS Home or another app.
Pairing approval and cancellation remain physical-remote-only: all phone app keys
are ignored while a pairing request is pending, even from an already paired phone.

Each root/presentation hierarchy owns a SwiftUI `FocusState<AppFocusID?>`.
Controls register an opaque ID, frame, primary action and optional scroll handler.
The driver selects only registered frames in the top eligible scope using beam
and distance, reveals the target with `ScrollViewReader`, and assigns that scope's
FocusState binding. OK uses the focused ID directly. UIKit is used only for geometry,
text scrolling and foreground/key-window/system-alert guards, never to redirect focus.
Owned confirmations have independent scopes and prefer Cancel. Licence text has its
own scope; Back restores the licence list and closing a cover restores the root.
Settings/licence rows are eager so offscreen targets remain registered, like bounded
History. Scope registrations reattach when a presenting view returns to its window.

Both phone idle-app navigation bars now show an explicit OK button outside the swipe
recognizer. Quick taps still work; a stationary press above 300 ms is deliberately
not a swipe-pad tap. On iOS, idle-app OK uses a discrete receiver write so a subsequent
swipe/new gesture cannot replace it in the latest-pending navigation slot. Other
player/browser/companion gesture behavior is unchanged.

Debug builds emit a single line after the focus update, containing only a known key
and opaque control IDs (or `none`), for example:

```text
[AppDpad] key=dpad_down target=00000000-0000-0000-0000-000000000002 focused=00000000-0000-0000-0000-000000000002
[AppDpad] key=dpad_center target=none focused=none
```

These are illustrative IDs. `focused` is the scope's FocusState value read on the
next main-queue turn, not a claim about rendered pixels. Edge/blocked/no-focus input
can produce `target=none`. Logging is compiled only under `#if DEBUG`; it contains no
media, titles, device names, URLs, credentials or pairing data.

## Manual Apple TV and phone checklist

No device or simulator interaction was performed for this change. Test with both
Android and iOS phones after reconnecting to refresh the descriptor:

- Idle Pair Device, History, Favorites, and Settings show a phone pad plus OK/Back/Home.
  Check the visible OK button in both swipe and button modes, a short tap, and a
  longer stationary press followed by explicit OK. On iOS, press OK while a previous
  direction write is slow, then immediately swipe: OK should arrive once.
  A v1 receiver without `app` keeps its previous idle UI. Player/browser contexts
  use only their own keys, even if `app` has additional keys. Repeat with several
  app engines: only their common keys may appear or execute.
- Move in all four directions between sidebar and content; check initial focus,
  beam preference, edges, repeated keys, and focus styling. OK activates exactly
  once and does nothing for a read-only item. Physical Siri Remote behavior remains
  usable before/after phone input.
- History: scroll through more than one page and to both ends, including Continue
  Watching/Recent. Focused rows stay visible. Play/resume and Favorite are separately
  focusable and activate their own actions. Check Favorites removal while focused.
  Clear All opens confirmation; Cancel/Back/Home do not erase data; Erase All does.
- Settings: both engines, skip-provider cycling, history/reminder toggles, reminder
  interval/response cycling, read-only information, server restart/reconnect, and
  licences. Verify list scrolling follows focus and control values update once.
- Licences: open/close the full-screen cover, open each bundled notice, scroll long
  text up/down, use Back to pop text then close the cover, and Home to close all
  presentations and return to Pair Device. Do not leave a stale modal scope.
- Pair Device: Manage Paired Devices, Done/Back, scrolling long lists, individual
  Forget, Remove All confirmation and Cancel. Confirm forgetting a connected phone
  still revokes it immediately and does not grant another phone authority.
- While another device requests pairing, an already paired phone's D-pad/OK/Back/
  Home cannot approve or deny/cancel it or activate underlying controls. Only the
  physical remote can act on pairing controls. Repeat while a sheet is open.
- Back at the idle root is a no-op; Back on another idle screen returns to the root.
  Back closes only the top owned confirmation/sheet or navigation destination.
  Home closes nested presentations and returns to the idle root.
- Switch to video/music/image playback while a phone key is queued: only the existing
  player handler may act. Check pre-play and still-watching overlays are unchanged;
  returning to idle restores the app route with no stale focus action.
- Background PlayBridge, show a system permission/OS alert, and return. Phone app keys
  must be ignored while the app is inactive or system focus is outside its own UI.
- Android TV's independently paired companion/Full control, legacy Apple descriptors,
  Desktop and CLI continue to use their existing behavior. No system-volume or
  whole-device Apple control is implied by the app surface.
