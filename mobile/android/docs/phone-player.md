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

## Known limitations

- After a seek, the position can briefly show the old time until mpv reports the new one.
- A video first opened in the background stays audio only after returning.
- The `SurfaceView` output path doesn't wait for mpv to release the surface; the phone player
  uses a `TextureView`, so this doesn't affect it.

Logs: `adb logcat -s PB_PLAYER MpvPlayerEngine`.
