# Android TV Redesign — Design and Implementation Plan

Status: proposed, not started (written 2026-11-10)

## 1. Summary

Redesign the Android TV app around what it is: a receiver. Home shows whether the
TV is ready and who is connected, plus Bookmarks and each phone's recent casts.
The player gets a seek-bar-only tier, a simpler control row and side panels.
Phone actions are confirmed on the TV without taking focus.

Mockups: https://claude.ai/artifact/8Mm8jTmxhUh54CUL78Estw ("PlayBridge TV redesign",
40 boards at 1280×720). The artifact is private to its owner; share it from the
page before pointing anyone else at it. The mockups have not been checked on a
device, and nothing here has been measured on a TV.

All paths below are under `tv/android/player/app/src/main/java/com/playbridge/player/`
unless they start with another top-level folder.

## 2. Goals and non-goals

### Goals

- Home answers "is this TV ready, and who is connected?" at a glance.
- Bookmarks replace Library: one bookmark per video, holding its last played position.
- Recent casts come from the connected phone's own cast history. The TV keeps no
  history of its own; it stores bookmarks only.
- Player controls that stay smooth on weak TV hardware.
- The TV shows controls to whoever holds the TV remote; phone actions get a short
  confirmation only.
- Brand colours from `ui/theme/Color.kt` everywhere; no hard-coded off-brand colours.

### Non-goals

- Changes to the phone app's own screens.
- Bookmarks visible or editable on the phone (see open decisions).
- Replacing the 6-digit pairing code with a QR code.
- Desktop and web receivers. Apple TV follows Android; see section 8.

## 3. Decisions already made

| Decision | Detail |
|---|---|
| Bookmarks replace Library | One per video. The saved position keeps updating while the video plays. |
| Sender keeps recents, receiver keeps bookmarks | Decided 2026-11-11. The TV stores no history. "Recent from <phone>" is the phone's cast history, sent when it connects and shown only while it is connected. Applies to every receiver (Android TV, Apple TV, desktop). |
| Recents are capped | The phone sends only its most recent casts. The number is not chosen yet (see open decisions). |
| Recents use the phone's preview | Every cast from a phone carries a preview image; the recents row shows that. The TV does not generate or send thumbnails for recents. |
| A stale resume point is accepted | The phone's history holds the last position it heard from the TV. If the phone disconnects while the TV keeps playing, that position is out of date. Bookmarks hold the exact position, because the TV stores them. |
| No fixed pre-play countdown | A timer appears only as a choice window for Resume or Start over. |
| Two-tier player | Left and Right show the seek bar alone; OK, Up or Down open full controls. |
| Fixed-size control buttons | Five equal circles and one fixed label slot. Focus never changes a button's size. |
| Side panels | Subtitles, More, Queue and Chapters open as a right-hand panel over the video. |
| Settings split | Everyday categories, a Phones category, and Advanced for troubleshooting. |
| Watch Together | Entered from a Home button. No bookmarking inside a party. |
| Corner cards | Pairing, invites and updates appear as cards over the current screen. |

## 4. What the code needs

### 4.1 Needs a protocol change

| Feature | Gap |
|---|---|
| Rename the TV from a phone | No message exists. The name comes from Android's device name; `PairingStore.setDeviceName` has no caller. Add a command or drop the line from the Appearance board. |
| Recents from the phone | Two new messages: the phone sends its capped recent list (title, preview, position, duration, an item id) on connect and when it changes; the TV asks the phone to cast an item by id. The phone re-casts it, so phone-served files and expiring links keep working. Both phone apps need this; whether the iPhone app keeps a cast history like Android's (`cast/CastHistoryCommand.kt`) has not been checked. Give each item a stable id and an updated-at time so a later sync service can merge lists. |
| Seek thumb following the phone's finger | The protocol carries only finished seeks (`seek_absolute`, `seek_relative`). A live preview needs a new message. What the phone sends during a drag has not been checked. |

### 4.2 TV-side work only

| Feature | Work |
|---|---|
| "Paused from <phone>" and other phone cards | Commands already carry a connection id and `server/ConnectionSenderNames.kt` maps it to a name. Pass that name into `player/PlayerHostActivity.kt`. |
| Stop storing history | `data/HistoryStore.kt` keeps only bookmarked items (`isFavorite`). Non-bookmarked items already stored are deleted on upgrade. The history thumbnail modes (`data/HistoryThumbnailMode.kt`) then apply to bookmarks only. |
| Recents row | Held in memory per connection, filled from the phone's list, dropped when the phone disconnects. No owner field is needed. |
| Bookmarks | `isFavorite`, position and duration exist. Add the in-player button and make Bookmarks the only stored list. |
| Resume or Start over | `preplay/PrePlayScreen.kt` has no such choice; the position is already stored. |
| Remote keys | In `PlayerHostActivity.onKeyDown` and `handleDirectionalInput`: Up and Down open controls, Back needs a second press on a bare video, and hidden-controls seeking reuses the 400 ms debounce in `PlayerControlsViewModel.handleScrubbing`. |
| Pairing over the player | Pairing requests are ignored while the player or browser is active. This changes behaviour, not only layout. |
| Forgetting a phone | `PairingStore.forgetDevice` exists. Nothing else to clear, since the TV holds none of that phone's recents. |

### 4.3 Already supported

Queue add, remove, move, jump and clear (`queue_crud_v1`); speed, subtitle offset
and track changes from the phone; Dark, AMOLED and Light themes; thumbnail modes
(`data/HistoryThumbnailMode.kt`); sending logs to a phone (`diagnostic_logs_v1`);
chapters, skip intro, Still Watching, the update prompt and script approval.

## 5. Phases

Each phase should ship as its own PR and leave the app working.

1. **Theme and focus groundwork.** Replace hard-coded colours with theme tokens.
   Settle one focus treatment (colour swap and ring, scale through `graphicsLayer`).
   No animated width, height or padding anywhere.
2. **Player controls.** Two tiers, the fixed five-button row, the label slot, and
   the new key map. Smallest change with the largest effect on daily use.
3. **Side panels.** Subtitles, More, Queue and Chapters as panels that slide in as
   one layer. Replaces the current overlays in `ui/player/PlayerControlsOverlay.kt`.
4. **Phone confirmations.** Carry the sender name into the player; add the paused
   pill, the phone-seek bar and the change card.
5. **Bookmarks.** In-player bookmark button, Bookmarks row on Home, the long-press
   actions panel. Library goes away here.
6. **Recents from the phone.** The two protocol messages in 4.1, the phone side on
   Android and iPhone, the "Recent from <phone>" row on Home, and removal of the
   TV's own history. Until a phone app supports it, that phone shows no recents.
7. **Home and pre-play.** New Home layout, corner cards for pairing and updates,
   the Resume or Start over choice, and removal of the fixed countdown.
   `preplay/PrePlayActivity.kt` appears unused; confirm and delete.
8. **Settings.** New categories, Phones, and Advanced.
9. **Watch Together surfaces.** After phase 1 of Watch Together has merged
   (see `docs/design-watch-together.md`), restyle its panel and party player.
10. **Remaining screens.** Browser, screen mirroring, prompts, music and photos.

The rename and seek-preview items from 4.1 are optional and can be done at any
point or dropped. The recents messages are required for phase 6.

## 6. Open decisions

- **Up and Down for volume.** They change volume today. The redesign uses them to
  open controls. Check whether the phone's on-screen remote has its own volume
  buttons before removing this.
- **Back guard.** "Press Back again to stop" on a bare video, or keep the immediate exit.
- **Bookmarks on the phone.** Staying TV-only needs no protocol work. Showing or
  toggling them on the phone needs a status field and a command.
- **Casts sent with `skipHistory`.** The flag no longer affects recents, since the
  TV stores none. Decide whether it should still disable the bookmark button, as
  in a party.
- **How many recents.** The cap on the list a phone sends is not chosen.
- **Cast history sync service.** A Cloudflare Worker that syncs a person's cast
  history across their devices is a possible later addition, not part of this
  plan. It would need a way to tell which devices belong to one person, and the
  history should be encrypted so the service cannot read it.
- **Things played on the TV itself.** Browser plays and replayed bookmarks appear
  in no phone's recents. Assumed acceptable.
- **Audio button.** Keep it in the row, or fold audio into the Subtitles panel to
  leave four buttons.
- **Font.** One Poppins weight is bundled; `DESIGN.md` names Manrope and Inter.
  The mockups use four Poppins weights.

## 7. Not drawn yet

Text-entry dialogs (custom address, skip-service keys and URLs, Watch Together
service URL), the quit confirmation, the GeckoView add-on install prompt, the
"party ended" message, option pickers opened from settings rows, and an
end-of-video "Up next" card.

## 8. Apple TV

The Apple TV app (`tv/apple/PlayBridge TV/PlayBridge TV/`) has the same set of
screens in SwiftUI, so the design carries over. It should follow Android, not run
in parallel, so decisions are made once. Paths below are under that folder. The
Apple app was skimmed for this section, not read in full.

### 8.1 What carries over, adapts or drops

| | Items |
|---|---|
| Same | Home with Bookmarks and per-phone recents, pairing card, Resume or Start over (`UI/Views/PrePlayView.swift` has a fixed 7-second countdown today), player layout, side panels, phone confirmations, most of Settings. |
| Adapt | Remote keys for the Siri Remote, Back on the top screen leaving the app, native focus look, 1920×1080 points (boards scale by 1.5). |
| Drop | Browser, script approval, screen mirroring, the open-over-apps and wrong-device prompts, the update prompt, and "ready in the background" (tvOS suspends apps that are not in front). |
| Later | Watch Together. Phase 1 is Android only. |

### 8.2 One controls overlay for both engines

Today the two engines have different controls:

- **AVPlayer** uses the system `AVPlayerViewController` transport bar, with extra
  items added through `transportBarCustomMenuItems`, skip through
  `contextualActions`, and chapters through `navigationMarkerGroups`
  (`Player/NativePlayerView.swift`).
- **mpv**, the default engine, uses a custom SwiftUI overlay styled to resemble
  the system bar (`Player/PlayerControlsOverlay.swift`, `Player/MPVPlayerView.swift`).

Proposed: one PlayBridge overlay over both engines, following the redesign.

- Bookmark, Queue, the side panels and the phone confirmations are built once.
  The system bar cannot host a side panel or a "Paused from <phone>" pill.
- A cast that falls back from AVPlayer to mpv keeps the same controls.
- Keep `AVPlayerViewController` as the video host with `showsPlaybackControls`
  off, so it still handles display matching for HDR and frame rate. The code
  already switches that flag.
- The key table goes into `Player/PlayerRemoteNavigation.swift`, which is already
  a pure model both engines report into. Swipe scrubbing (`Player/TouchScrub.swift`)
  feeds the seek-bar tier.

Cost, to confirm on a device before committing: with the system controls off,
AVPlayer sessions lose what the system bar provides for free. This likely
includes Siri playback commands, the system info panel and system scrubbing
previews. None of this has been tested.

This is an Apple-flavoured version of the design, not a literal port: same
structure and behaviour, with the native focus effect and the existing
Apple-style seek bar. No code is shared with Android; the shared thing is the
key table and the screen structure.

### 8.3 Extra work on Apple

- **History model.** `PlaybackHistoryItem` (`Data/HistoryStore.swift`) is keyed by
  URL and stores title, headers and position only. It has no original cast
  payload or thumbnail. Bookmarks that replay with the same subtitles and queue
  need the payload stored, as Android does. Recents need no storage here: they
  come from the phone (section 3).
- **Sender name in the player.** Not checked whether the Apple server tracks a
  name per connection the way Android's `ConnectionSenderNames` does.

### 8.4 Phases

1. mpv overlay to the new layout and key table (default engine, already custom).
2. AVPlayer under the same overlay, after the device check in 8.2.
3. Phone confirmations.
4. Bookmark storage, then Bookmarks and the recents row from the phone.
5. Home, pre-play and Settings.
6. Watch Together, once it is built for Apple TV. The Android TV party boards apply.

## 9. Desktop

Mockups: https://claude.ai/artifact/GrD622QN9dfP9K17E9piC2 ("PlayBridge Desktop
redesign", 32 boards, private to its owner). No implementation plan has been
written for desktop yet. The rule in section 3 applies: as a receiver the desktop
stores bookmarks only, and its Recent tab lists what it played or sent itself.

## 10. Verification

- Test on the Hisense first; it is the weaker device. Watch for dropped frames
  when moving focus along the control row and when a side panel opens.
- Compare each built screen against its board in the artifact.
- Recents need two paired phones to test properly: each should see only its own
  list, and the row should clear when its phone disconnects.
- On Apple TV, check HDR and frame-rate matching still work for AVPlayer once the
  system controls are off.
