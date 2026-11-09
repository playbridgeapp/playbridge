# Phone player

The Android phone plays videos in `PlayerActivity` with one of two decoders:

- **ExoPlayer** (Media3, with the FFmpeg audio extension), the default.
- **mpv**, through `MpvMediaPlayer`. It wraps the shared `MpvPlayerEngine` as a Media3
  `Player`, so the player screen, the website queue (`PagePlayerSession`), the background
  notification and picture-in-picture work the same with either decoder.

## Choosing the player

**Settings → Streaming Preferences → Phone player**:

| Setting | Behaviour |
|---|---|
| Automatic (default) | Starts with ExoPlayer. Switches to mpv, once, at the current position, when ExoPlayer reports a container, decoder or format error, or when every video or every audio track is unsupported (ExoPlayer would otherwise play silently or show black). Network, HTTP and DRM errors never switch. |
| ExoPlayer only | Never uses mpv. |
| mpv | Plays everything with mpv. |

The **Switch player** button (⇄) in the player's top bar moves the current video to the other
decoder at the same position. The stats overlay names the decoder in use. The setting is
included in settings export and import.

## mpv specifics

- mpv opens `http(s)`, `file` and `content` URIs (`content` through an inherited file
  descriptor). Other schemes stay on ExoPlayer.
- Video headers go to mpv with the item. Sidecar subtitles are downloaded separately with only
  their own headers (`MpvSubtitleFiles`) and added as local files, so video credentials never
  reach a subtitle host. For single videos, subtitles on the video's origin get the video's
  headers, as ExoPlayer sends them.
- TLS certificates are verified against the CA bundle in the mpv AAR.
- mpv opens a file only once PlayerView has given it a surface; opening without one drops the
  video track. Without a surface after 1.5 s it opens audio only.
- A TextureView resize reaches mpv one frame late; after a resize while paused, mpv re-seeks to
  the current position so the frame is drawn again at the new size.
- Only one mpv core exists per process. A new player waits for a previous one to finish
  releasing.

## Subtitle style

Settings → Audio & subtitles → **Phone player subtitles** sets size, background, edge and
whether styled subtitles (ASS) are restyled. It applies live to ExoPlayer's caption view and to
mpv (`MpvMediaPlayer.setSubtitleStyle`). ExoPlayer always uses the phone style, since it has no
ASS. mpv finds fonts for any script through fontconfig over the device's fonts.

**Add a font file…** installs a .ttf, .otf or .ttc (up to 10 MB each, at most 5 fonts and 30 MB
in all; `SubtitleFontLibrary`). Each font can be selected or removed. The selected one is
hard-linked into mpv's `sub-fonts-dir` (mpv picks it by family name, and libass would otherwise
load every font into memory); ExoPlayer and the preview load it directly. Characters it lacks
always fall back to the device's fonts; Android can't turn that off.

A TV keeps its own style. Play on → **Configure TV** and Settings → TV → **Subtitles on the TV**
edit it over the connection (`subtitle_style_query` / `subtitle_style`) when the TV advertises
`subtitle_style_v1`; older TVs show an update hint instead. TVs with `subtitle_font_v1` also take
custom fonts from the phone (`subtitle_font_*`, sent in acknowledged pieces with progress) and
remove them; mpv on the TV picks up a newly selected font from the next video.

## Portrait fill

On an upright screen a landscape video gets the portrait layout (video on top, details below).
Some portrait clips are stored in a landscape frame with bars at the sides; pinching open on the
video fills the screen with it instead, cropped at the sides (`PlayerScreenState.portraitFill`,
`portraitFillGestures`). Pinching closed returns to the portrait layout. Turning the phone to
landscape shows the whole video as usual. The chosen Fit/Zoom/Stretch mode is kept; the fill only
changes what is shown while upright. Pan & zoom keeps its own pinch, so the fill pinch is off
while it is on.

## Leaving the player

**Settings → Phone player → When you leave the player** (`PhoneLeaveAction`) decides what a
playing video does on Back, Home or locking the phone. Each video's More sheet offers the same
three choices for that video only, starting from the setting.

| Choice | Back | Home | Locking the phone |
|---|---|---|---|
| Pause (default) | Closes the player | Pauses | Pauses |
| Keep playing | The player steps behind the browser and keeps playing | Keeps playing under its media notification | Keeps playing under its media notification |
| Picture-in-picture | The video shrinks into a window over the browser | The window opens by itself (`setAutoEnterEnabled` on Android 12+, `onUserLeaveHint` before) | Keeps playing |

Without a choice saved, the earlier "Keep playing in the background" switch carries over:
on becomes Keep playing, off becomes Pause. The legacy key is still written, so an older version
keeps the meaning, and backups carry both.

## Picture-in-picture

`PlayerPip.kt` builds the window; `PlayerActivity` decides when it opens. The player's PiP button
always opens it. A video in the window is one being watched: locking the phone keeps it playing
under the media notification, whatever the leave choice.

The window's buttons are play/pause, Next when the queue has another item, and **Play in
background**, which closes the window and keeps the audio going under the media notification.
Fewer buttons are shown when the system allows fewer, dropping Play in background first. Closing
the window with its X stops playback and closes the player; expanding it returns to the full
player. The window takes the video's shape (clamped to Android's 2.39:1 limits) and animates out
of the fitted video. Without PiP (no system support, or turned off for PlayBridge in system
settings), Picture-in-picture behaves like Pause.

The leave choice picks the player screen at launch (`playerActivityClass`). Keep playing uses
`PlayerActivity`/`PortraitFirstPlayerActivity` in their own task (`taskAffinity`), which lets Back
leave the player playing behind the browser; Recents then shows the player separately. Pause and
Picture-in-picture use `InAppPlayerActivity`/`InAppPortraitFirstPlayerActivity`, which share the
app's task: entering PiP moves the player into the window and leaves the browser underneath, so
Back lands in the app even after Home and expanding the window again (Android returns the player
to its original task), and Recents shows one PlayBridge entry. A player in PiP cannot bring
another task forward itself; Android blocks that as a background activity launch. Changing the
choice in the More sheet doesn't move a running player to the other task.

## Known limitations

- After a seek, the position can briefly show the old time until mpv reports the new one.
- A video first opened in the background stays audio only after returning.
- The `SurfaceView` output path doesn't wait for mpv to release the surface; the phone player
  uses a `TextureView`, so this doesn't affect it.

Logs: `adb logcat -s PB_PLAYER MpvPlayerEngine`.
