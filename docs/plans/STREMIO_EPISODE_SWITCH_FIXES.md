# Stremio Episode Switch — Bug Analysis & Fix Plan

_Drafted: 2026-04-17_
_Scope: TV player (`tv/player/app`) — Stremio series navigation only_
_Relevant files: `ExoPlayerActivity.kt`, `SeriesNavigator.kt`, `PlaylistPickerDialog.kt`, `StremioClient.kt`_

---

## 1. Summary of Reported Symptoms

1. **Wrong episode plays after picker selection / next-button press.** When switching episodes via the Stremio playlist picker or Prev/Next controls, playback sometimes lands on an unexpected episode (skip, repeat, or stale result).
2. **User preferences are not respected when switching episodes.** Preferred audio / subtitle language, playback speed, video scaling mode, and external subtitle URL silently revert to defaults every time a new Stremio episode starts.

Both behaviours only manifest in the Stremio *series* path (the one driven by `SeriesNavigator`). The Debrid/Magnet/IPTV `playlistItems` path is unaffected because it uses direct URLs and a different code branch.

---

## 2. Root-Cause Analysis

### 2.1 Wrong episode on switch

There are three independent mechanisms that can land on the wrong episode.

#### (a) Race on concurrent `resolveNext()` / `resolvePrev()` calls

`ExoPlayerActivity.playNextInPlaylist()` (lines 1252–1337) launches `nav.resolveNext()` inside `lifecycleScope.launch` **without cancelling a previous in-flight call** and **without a mutex**. A user who taps the Next button twice in quick succession triggers two coroutines that both run:

```kotlin
val stream = nav.resolveNext()  // network-bound — can take several seconds
```

Both calls read the *same* `currentIndex` (because neither has advanced yet), so they both compute `peekNext()` as `currentIndex + 1`, resolve the same episode, and eventually both call the private helper:

```kotlin
// SeriesNavigator.advance()
currentIndex = currentIndex?.plus(1)
```

Each call increments `currentIndex` by 1, so after two duplicate resolutions of the *same* episode, `currentIndex` has moved forward by **2**. The user sees episode N playing but `SeriesNavigator` believes it is on N+1. The very next click therefore jumps to N+2 and skips N+1 entirely.

The same race exists between `playSeriesEpisodeAtIndex` invocations (picker rapid-tap): both launch independent coroutines and whichever network resolution finishes *last* is the stream that ends up on screen — not necessarily what the user tapped last.

There is also a cross-interaction race between auto-advance (player ended → `playNextInPlaylist`) and an overlapping user tap.

#### (b) `advance()` increments an index that may be stale

`SeriesNavigator.advance(to)` does two unrelated things:

```kotlin
currentSeason  = to.season
currentEpisode = to.episode
currentIndex   = currentIndex?.plus(1)   // ⚠ blindly +1, ignores `to`
```

If `to` comes from the caller (e.g. `peekNext()`) but the list was mutated or the caller skipped ahead, `currentIndex` and `to` can disagree. The function should index `to` in `episodeList` and set that, not blindly `+1`.

#### (c) No cancellation of prior `playJob` / `resolutionJob` before a picker jump

`playVideo()` already cancels `playJob` (line 534), but `playSeriesEpisodeAtIndex` does not cancel a prior coroutine started by `playNextInPlaylist`. Two network requests can run concurrently and the slower one writes last.

### 2.2 User preferences lost on episode switch

The path `playSeriesEpisodeAtIndex` / `playNextInPlaylist` / `playPreviousInPlaylist` all call `playVideo(url, title)` with the default `subtitles = null` and no explicit language hints. Inside `playVideo → startPlayback → initializePlayer` the following things drop the user's preferences:

1. **`initializePlayer` gates language-preference application on `playlistItems.isNotEmpty()`** (ExoPlayerActivity.kt, lines 676–686):

   ```kotlin
   if (playlistItems.isNotEmpty()) {
       preferredAudioLanguage?.let { params.setPreferredAudioLanguage(it) }
       preferredSubtitleLanguage?.let { params.setPreferredTextLanguage(it); ... }
   }
   ```

   In Stremio series mode `playlistItems` is *always empty* (the playlist is implicit in `SeriesNavigator.episodeList`), so the audio/subtitle language preferences from the phone are never reapplied on the new `TrackSelector`. Stremio users end up on whatever language the stream declares as default (usually English), regardless of their phone preference.

2. **`startPlayback` resets playback speed and scaling mode when there is no playlist JSON** (ExoPlayerActivity.kt, lines 597–600):

   ```kotlin
   if (plistJson == null) {
       applyPlaybackSpeed(1.0f)
       applyVideoScalingMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
   }
   ```

   Again, Stremio series mode has empty `playlistItems` → `plistJson == null` → both values are forcibly reset to defaults. The `restoreProgress(url)` call that follows only re-applies them *if* the new episode has a prior history row — the first play of a fresh episode always starts at 1.0× speed and default aspect, even if the user set 1.5× on the previous episode.

3. **External subtitle URL (`currentSubtitleUrl`) is dropped.** `playSeriesEpisodeAtIndex` calls `playVideo(url = stream.url, title = mainTitle)` without forwarding `currentSubtitleUrl`. Inside `initializePlayer`, `subtitleUrls = subtitles ?: emptyList()` triggers `subtitleManager.disable()`. Even if the next episode would work with the same sidecar URL (rare, but possible for generic per-season subs), the manager is disabled.

4. **Video filter reapplies correctly** — `videoFilterManager.reapplyFilter()` is called by the caller after `playVideo`, so filters *do* survive. Good news: no fix needed there.

5. **`defaultVideoQuality` / `maxBitrateCapMbps` reapply correctly** — they are member fields on `PlayerActivity` and `initializePlayer` applies them unconditionally. No fix needed.

---

## 3. Fix Plan

The work is split into two independent patches so each can ship/rollback separately.

### Patch A — Serialise Stremio navigation to eliminate the race

**Goal:** guarantee at most one in-flight `SeriesNavigator` resolution at any time, and guarantee that `currentIndex` always reflects the episode actually playing.

**Changes:**

1. **`SeriesNavigator.kt`**
   - Add a private `kotlinx.coroutines.sync.Mutex` and wrap `resolveNext`, `resolvePrev`, `resolveAndAdvanceToIndex`, and `resolveCurrentStreams` in `mutex.withLock { … }`. This converts rapid taps into sequential network requests; the second one observes the already-advanced `currentIndex` and either becomes a no-op (no-change) or resolves the *next* episode correctly.
   - Replace `advance(to)` / `rewind(to)` bodies with an explicit lookup against `episodeList` so the index can never drift from the `(season, episode)` pair:

     ```kotlin
     private fun jumpTo(ref: SeriesEpisodeRef) {
         currentSeason  = ref.season
         currentEpisode = ref.episode
         currentIndex   = episodeList?.indexOfFirst {
             it.season == ref.season && it.episode == ref.episode
         }?.takeIf { it >= 0 }
     }
     ```

     Both `resolveNext`, `resolvePrev`, and `resolveAndAdvanceToIndex` call `jumpTo(targetRef)` on success.

2. **`ExoPlayerActivity.kt`**
   - Introduce a single `navigationJob: Job?` field. `playSeriesEpisodeAtIndex`, `playNextInPlaylist` (Stremio branch), and `playPreviousInPlaylist` (Stremio branch) must `navigationJob?.cancelAndJoin()` before launching their own coroutine. This guarantees that only the latest user request is in flight, and older ones are cancelled before their `playVideo` runs.
   - In `playNextInPlaylist` / `playPreviousInPlaylist`, read `nav.hasNext()` / `nav.hasPrev()` *after* grabbing the mutex inside the navigator (the existing `hasNext()` check before launch stays as a fast path for UI, but the authoritative check is inside the locked section).
   - Disable the Next / Prev buttons (`controlsManager.setNavEnabled(false/true)`) while a navigation is in flight, to give visual feedback and prevent accidental multi-taps. Re-enable in a `finally { }` block on the coroutine.
   - Add an early-return guard: if `stream.url == currentPlayingUrl` after a resolve completes, skip `playVideo` (prevents flicker when a cancelled coroutine slips through).

**Tests (manual — there is no test harness yet):**
- Rapid-tap Next 5× within 1 s on a 10-episode Stremio show. Expected: TV advances exactly as many episodes as the last visible index in the UI, never more. Internal index (checked via `adb logcat -s SeriesNavigator`) matches displayed (s,e).
- Open picker, tap ep 8 while ep 3 is loading. Expected: ep 3's resolution is cancelled, ep 8 plays.
- Let one episode end naturally, and tap Next the moment the countdown starts. Expected: no double-advance.

### Patch B — Preserve user preferences across Stremio episode switches

**Goal:** all preferences that the user set on the *current* playback session continue to apply to the next episode without relying on per-URL history.

**Changes in `ExoPlayerActivity.kt`:**

1. **Remove the `playlistItems.isNotEmpty()` gate around audio/subtitle language** (lines 675–686). Reapply unconditionally whenever the member fields are non-null:

   ```kotlin
   preferredAudioLanguage?.let { params.setPreferredAudioLanguage(it) }
   preferredSubtitleLanguage?.let {
       params.setPreferredTextLanguage(it)
       params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
   }
   ```

   Rationale: these are session-level fields set from the phone's PlayPayload at launch; gating on `playlistItems` was an unintended artefact of when the code was originally written for Debrid playlists only.

2. **Remove the speed / scaling-mode reset** at lines 597–600. The `restoreProgress` block already handles per-URL restoration. For cross-episode continuity we instead want the *current* session values to survive:

   ```kotlin
   // DELETE:
   if (plistJson == null) {
       applyPlaybackSpeed(1.0f)
       applyVideoScalingMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
   }
   ```

   Replace with: after `initializePlayer` creates the new `ExoPlayer`, explicitly push the session values back into the freshly-built player:

   ```kotlin
   applyPlaybackSpeed(currentPlaybackSpeed)
   applyVideoScalingMode(currentVideoScalingMode)
   ```

   (Both are already stored as activity-level fields at lines 1603–1604.)

   The per-URL `restoreProgress(url)` then overrides *only* if the new episode has a saved value — which is the correct precedence (user's explicit override per-file wins; otherwise session value wins; otherwise defaults).

3. **Carry `currentSubtitleUrl` across Stremio switches.** In `playSeriesEpisodeAtIndex`, `playNextInPlaylist` (Stremio branch), and `playPreviousInPlaylist` (Stremio branch), forward the in-memory subtitle URL if the user picked one manually:

   ```kotlin
   val forwardedSubs = currentSubtitleUrl?.let { arrayListOf(it) }
   playVideo(url = stream.url, title = mainTitle, subtitles = forwardedSubs)
   ```

   Caveat: this forwards a sidecar that may not match the new episode (e.g. S01E03.srt won't fit S01E04). Reasonable compromise: only forward if the URL host is a *generic* subtitle service (OpenSubtitles session URL) or if the filename does not embed an episode number. A simpler first cut is to forward unconditionally and let the user disable it if wrong — that matches how `preferredSubtitleLanguage` is treated.

4. **Short-circuit `setupSeriesNavigator` inside `playVideo`**. Line 520 currently rebuilds the navigator from the intent every time `playVideo` is called. During a Stremio episode switch the *navigator already exists* and its `currentIndex` was just advanced — rebuilding it from the intent throws away the advance. Fix:

   ```kotlin
   if (seriesNavigator == null) {
       setupSeriesNavigator(intent)
   }
   ```

   (Intent-driven launches — cold start, onNewIntent, player-switch — all run `handleIntent` which calls `setupSeriesNavigator` explicitly, so nothing is lost.)

**Tests (manual):**
- Set preferred audio language to Spanish on the phone. Play Stremio series. Verify Spanish on ep 1. Press Next. Verify Spanish on ep 2 (check `PlayerControlsManager` track dialog or logcat).
- Set playback speed to 1.5× during ep 1. Press Next. Verify ep 2 starts at 1.5× (read `player.playbackParameters.speed`).
- Change aspect ratio to `RESIZE_MODE_FILL` on ep 1. Press Next. Verify ep 2 uses the same resize mode.
- Pick a subtitle track from the internal dialog on ep 1 (not external). Press Next. Verify ep 2 auto-selects same-language subs.

---

## 4. Rollout Order

1. Ship **Patch A** first — it is a correctness fix with observable user impact and no behavioural subtleties. Easy to validate by watching the logcat tag `SeriesNavigator`.
2. Ship **Patch B** after A is confirmed stable. Patch B touches hotter initialization code and regressions would be user-visible across all playback paths, so it benefits from a clean baseline.

Both patches are localised to `tv/player/app/src/main/java/com/playbridge/player/player/` (+ `stremio/SeriesNavigator.kt`). No protocol changes, no phone changes, no extension changes.

---

## 5. Out of Scope / Follow-ups

- **Test harness.** `tv/player/app/src/test/` is empty per AI_CONTEXT. A JVM unit test for `SeriesNavigator` (feed a fake `StremioClient`, assert index math under concurrent calls) would be high-value but is a separate initiative.
- **Disable picker items while navigation is in flight** — UX polish, nice but not required to fix the reported bugs.
- **Coalesce progress-save writes.** Both `playNext` and `playPrev` capture a bitmap and save history before advancing; rapid-tap still triggers N captures. Not a correctness issue once Patch A serialises navigation, but worth auditing for battery/IO.
- **Unify `playNext/Prev/JumpTo`** into a single `navigateTo(direction|index)` helper in a follow-up refactor. Right now three copies of very similar code guarantee future drift.
