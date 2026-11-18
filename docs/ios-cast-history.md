# iOS cast history

The iPhone keeps a history of casts so they can be cast again or resumed. It follows the
Android phone's redesign (#545).

## What a row shows

- A preview picture: the one the cast sheet already made for that video, shrunk to a small
  JPEG. Nothing new is downloaded. Without one, an icon for video, audio or picture.
- The title, the site and the device it played on ("site.example · Living room"); a playlist
  says "Playlist" and shows how many items.
- Where it got to and when: "Stopped at 2:58 · 2 h ago", "Finished · Yesterday", "Watched for
  25 min" for a live stream, a reason for one that didn't play ("The TV couldn't load it",
  "Stopped before it started", "Couldn't reach the TV"), or "Cast 2 h ago" when the device
  never reported a position. A progress bar, and the length, LIVE or "2 of 5" for playlists.
  Grouped under Today, Yesterday and dates.
- The cast playing now says "Playing on Living room · 2:58", with its position moving.
- The link's expiry when the link states it: "Link expires in 2 h" within a day, or "Link
  expired 3 days ago". This reads the link itself (`History/LinkExpiry.swift`: plain expiry
  parameters, AWS/Google signed URLs, Azure SAS, Akamai tokens, JWT `exp`) without using the
  network; the optional link check below can also ask the site.

Filters: All, Unfinished, Failed and Expired, with counts, plus search by title, site or device.

Tapping a row offers: Resume from the saved position, Play from the start (or Cast again, or
Try again for a failed one) on the selected device or the watch party, open the remote for the
cast playing now, open the page it came from, check the link, and remove. For an expired link:
open the page to get a fresh link, try the old link, or remove.

## Positions and Resume

While the phone is connected, the cast it started is followed through the receiver's reports
(`History/CastPositionTracker.swift`, a port of Android's). PlayBridge TVs, Google Cast, DLNA,
the browser receiver and AirPlay all report through the same coordinator status. The tracker
attaches to the history entry by id, ignores reports about the previous video until the receiver
shows the new one, and saves about every 10 seconds plus at once on pause, stop, error, a new
playlist item, disconnect and when the app goes to the background. "Finished" means within 5% of
the end, or under 10 seconds left on anything a minute or longer. Live streams keep time watched,
not a position. Positions under 10 seconds aren't offered for Resume.

While a resumed cast loads, receivers report 0:00 before they seek; the saved place is kept
until the receiver reaches it (within 15 seconds) or 30 seconds pass, so stopping early doesn't
lose it. Debug builds trace these steps as `[PhonePlayback] history:` lines (state, position,
whether the title matches; no addresses or titles).

Resume starts at the saved playlist item and position: on PlayBridge TVs through the playlist's
`startPositionMs`, on Google Cast and the browser receiver through their start position, on
DLNA by seeking after loading, and on AirPlay through its resume time. Roku and the watch party
can't start part-way, so Resume plays from the start there and the sheet says so. Playback on
the phone itself isn't tracked. A cast whose message couldn't be sent to the TV is kept as
failed so it can be tried again.

## Link checks

Off by default (**Check if links still work**). When on, opening a cast asks its site whether
the link still works, at most every 10 minutes; "Check the link" runs it for one cast anyway.
Only the first byte is requested. The saved headers, which can include sign-in details, go only
to the link's own host: a redirect elsewhere isn't followed. Home-network and local addresses
aren't checked ("Couldn't check"). 401, 403, 404 and 410 mark the link expired; a check that
works after a stated expiry clears it.

## What is kept, and what isn't

Settings › Cast history (also the gear on the history screen):

- **Keep cast history** (on). Off: new casts aren't remembered; what's kept stays until cleared.
- **Keep the newest** 25, 50 (default), 100, 200 or 500 casts. Lowering it asks first and says
  how many go. Someone who already had more than 50 from the old 100-cast history keeps 100.
- **Remember where you stopped** (on).
- **Keep preview pictures** (on). Off removes them.
- **Leave out bridged apps** (on). A cast from a bridged app, or from a website whose page the
  phone can't tell, stores nothing: no row, no picture, nothing to cast again. The page address
  comes from the tab itself, never from what the website sends.
- **Check if links still work** (off), described above.
- **Ask TVs not to keep a history**: the existing receiver preference, moved here from the TV page.
- Remove pictures, Clear history, and how much is kept.

Watch party casts are never saved. The history file holds the original cast message, including
sign-in headers, so it stays protected and out of backups; so do the pictures. Entries saved
before this change still load and show without a picture or page.

Checked by `mobile/apple/tests/run-cast-history-checks.sh` (store, settings, bridged apps,
limit, link expiry, wording, the position tracker, progress and link checks) and `run-page-cast-coordinator-checks.sh` (a website cast's
page and bridged-app flag reach the history before it is sent).
