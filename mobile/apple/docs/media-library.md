# iPhone media library

Phone Files is now **Media Library**, with Videos, Audio, Images and Collections.
The category bar follows Android’s flat tabs, item counts and selected underline;
it scrolls horizontally when the labels need more room.
The library is usable without a connected receiver. Tap an item to preview it,
share it, add it to a collection, or connect/cast to a TV.

## Sources and access

- **Photos:** Connect Photos requests access when the user chooses it. Both full
  and limited access are supported. Limited access has a Manage access action;
  denied access links to Settings. Photo-library changes and returning to the
  foreground refresh the visible inventory. No Photos assets are deleted or edited.
- **Files:** Multi-file import coordinates provider access and copies supported
  media into app-owned Application Support storage. Metadata persists across
  launches. Removing a copy leaves the original source untouched.
- **Downloads:** Completed browser media downloads appear automatically when the
  library opens. Partial downloads are excluded. Manage downloaded files from the
  browser's Downloads menu; deleting a download makes its library reference unavailable.
- **Audio:** Shows imported audio files and completed audio downloads. This does
  not enumerate Apple Music or bypass protected music access.

Search matches media names. Sort by name or recently added, and filter by source.
The selected media category is remembered. Visible cards load small thumbnails;
Photos thumbnails do not trigger background iCloud downloads. Opening an iCloud
asset may fetch its media and shows a preparation state. Photo-library still
images are exported as JPEG for playback/casting; a Live Photo is shown as a
still image. Videos retain their existing codecs through a passthrough export.

## Collections and playback

Local media uses the existing Collections store, with an optional stable
`libraryItemID`. Existing web/IPTV entries continue to decode unchanged. Collections
store references, not expiring phone-server URLs. Duplicate additions of the same
local item to a collection are ignored. Opening a missing/revoked item reports
that it is unavailable; it never sends a stale LAN URL to a receiver.

The phone player handles local video/audio, while images have an image preview.
Playback failures explain that receiver format support may differ. Local media
is served over the existing LAN file server with its media MIME type. External
receivers receive that already-local URL directly rather than attempting to
fetch it through a configured remote proxy. Receiver support for particular
codecs/image types still applies. The library does not implement transcoding,
collection autoplay, photo slideshows, or Music-library enumeration.

## Verification

`bash mobile/apple/tests/run-media-library-checks.sh` builds an isolated simulator
app with production models, storage, collection logic and library screens. It
checks byte-preserving import, restart persistence, legacy collection decoding,
stable collection IDs, duplicate avoidance, completed-download filtering,
classification, image MIME types, missing-file behavior, and removal without
source deletion. It also renders the actual library UI to
`/tmp/playbridge-library.png` for inspection. Connection/discovery is stubbed in
this test app, and no personal photo-library authorization is granted by the test.

Physical-iPhone acceptance remains necessary for limited Photos selection,
iCloud-only assets, provider imports, audio/video playback and receiver casting.
Use a mixture of large videos, HEIC/JPEG images, audio, denied/revoked Photos
access and deleted collection members.
