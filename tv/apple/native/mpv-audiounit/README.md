# Pinned AudioUnit channel-layout compatibility patch

## Failure and behavior

On the tested tvOS HDMI route, MPVKit 0.41.0-av's AudioUnit driver fails with
`kAudioUnitErr_InvalidProperty` (-10879) when reading
`kAudioUnitProperty_AudioChannelLayout`, output scope, element 0. It then
aborts audio initialization; the app reports `selectedAudio=no` and no AO.
AVPlayer working does not mean this separate RemoteIO query is supported.
The HDMI route's advertised 32 channels are not a usable speaker map.

`channel-layout-fallback.patch` changes only the mpv v0.41.0 AudioUnit driver:

- If that layout query is unsupported and the format is PCM, request two
  output channels and negotiate a stereo input map. mpv's normal conversion
  path can downmix the decoded audio to stereo. RemoteIO still validates the
  input format; failure there remains fatal.
- Never manufacture a surround map from a channel count. Valid layout queries
  retain the original multichannel behavior. This is a compatibility fallback,
  not a claim of surround/Atmos preservation on the affected route.
- Never relabel encoded passthrough as PCM. Other errors remain fatal.
- Clear the freed layout pointer if `AudioUnitGetProperty` fails after allocation,
  avoiding a double free during cleanup/fallback.

The fallback emits:

```text
PlayBridge AudioUnit layout fallback v1: unsupported output channel layout (-10879); requesting stereo PCM
```

## Reproducible integration

The TV continues to use CocoaPods and the exact **0.41.0-av** framework. No
phone/SPM or video backend migration is involved. Because upstream publishes
only a combined static framework, the post-install script recompiles just
`audio_out_ao_audiounit.m.o` from checksum-pinned mpv v0.41.0 source, then
replaces that one archive member in all four tvOS device/simulator architectures.
This uses normal source compilation/static linking, not runtime API interception
or machine-code patching.

`patch_framework.py`:

1. Requires exact SHA-256 hashes for the original tvOS framework slices. Unknown
   input fails closed. The original slices are backed up outside the framework.
2. Downloads the upstream source archive over HTTPS, checks SHA-256, and applies
   the patch without fuzz. Source is retained in `Pods/MPVKitAudioPatch/`.
3. Compiles with the selected Xcode SDK and matching tvOS 14 deployment target.
   `config.h` is scoped to this one translation unit. The shared private structs
   have no platform feature switches; `abi-check.h` checks the offsets against
   this pinned version. Do not reuse this patcher for another mpv release.
4. Verifies **every other archive member's payload hash**, in order and including
   duplicate names, is unchanged. Thus video/codec libraries remain intact.
5. Builds every slice before replacing the installed binaries. Records output
   hashes and a source/toolchain fingerprint for idempotent `pod install`.

From `tv/apple/PlayBridge TV/`:

```sh
pod install --no-repo-update
```

Requires Xcode command-line tools, Python 3, curl, tar and patch. First install
needs network access for the ~source-sized upstream download; subsequent runs
use the checksum-verified local cache. Clean/rebuild Xcode after installation.
Do not commit generated Pods, cached archives or modified binary frameworks.
If installation is interrupted or detects an unknown binary, reinstall the
pinned MPVKit pod and rerun `pod install`; never accept an unknown hash just to
make the build pass. Removing this hook alone does not restore a patched local
pod: reinstall MPVKit to roll back.

## Verification

```sh
python3 tv/apple/tests/run-mpv-audio-unit-checks.py
bash tv/apple/tests/run-receiver-review-checks.sh
bash tv/apple/tests/run-progress-webhook-checks.sh
```

The first runner compiles the **actual patched source** with Apple API doubles
on macOS under AddressSanitizer/UndefinedBehaviorSanitizer. It tests unsupported
property failures both before and after allocation, stereo selection on a
32-channel route, valid multichannel preservation, normal stereo, other errors,
encoded passthrough rejection, input-format failure, cleanup and output start.
It does not prove that RemoteIO accepts/plays the format on a physical Apple TV.

Build Debug/Release for tvOS and Debug for tvOS Simulator. On the device, use
the same known-audio source and HDMI route that failed. Look for the fallback
message and **audible sound**. The user confirmed restored sound on the
affected HDMI route. Test seek, pause/resume, queue advance, engine switching
and other audio routes; host builds alone do not establish audible playback.

## Source and license

- Combined framework: https://github.com/mpv-ios/MPVKit/releases/tag/0.41.0-av
  (GPL-3.0 according to its podspec; unchanged supporting components keep their
  original licenses).
- Patched source: https://github.com/mpv-player/mpv/blob/v0.41.0/audio/out/ao_audiounit.m
  (LGPL-2.1-or-later header retained in the source).
- Full upstream source and license files are in the verified archive; this
  directory contains the modifications and build instructions. Preserve these
  and provide the corresponding dependency sources/notices when distributing
  the modified GPL bundle. This patch does not change the bundle's license.
