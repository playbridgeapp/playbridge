# Addon Preference for Stream Picker — Implementation Plan

_Last updated: 2026-04-14_

---

## Overview

Allow users to set a **preferred addon** in Library settings. When the auto stream picker fires (phone) or the TV resolves next/prev episodes (series navigation), streams from the preferred addon are tried first; the existing quality/bitrate logic is applied within that addon's results, with a fallback to all addons if the preferred one yields nothing.

The preference must travel with the cast payload so the TV can honour it independently during series navigation without calling back to the phone.

---

## Pref key

Stored in `browser_prefs` (same `SharedPreferences` file as `auto_stream_quality` and `auto_stream_max_mbps`):

```
"auto_stream_addon" → String   // addon name, e.g. "Torrentio" — empty = no preference
```

---

## Affected files (in order of implementation)

| # | File | Change |
|---|------|--------|
| 1 | `LibrarySettingsScreen.kt` | Add addon dropdown under "Stream Picker" |
| 2 | `StreamSelector.kt` | Add `preferredAddon` param to `selectBest()` |
| 3 | `StreamPickerSheet.kt` | Read pref + pass to `StreamSelector`, update badge |
| 4 | `LibraryDetailScreen.kt` | Read pref + pass to auto-pick + pass to `buildSeriesContext()` |
| 5 | `Message.kt` (protocol) | Add `preferredAddonBaseUrl: String?` to `SeriesContext` |
| 6 | `LibraryDetailScreen.kt` | `buildSeriesContext()` — resolve name → base URL, populate new field |
| 7 | `StremioClient.kt` (TV) | Add `preferredAddonBaseUrl` param, try it first |
| 8 | `SeriesNavigator.kt` (TV) | Forward `context.preferredAddonBaseUrl` to each `StremioClient` call |

---

## Step 1 — `LibrarySettingsScreen.kt`: Add the addon dropdown

**Location:** `phone/app/src/main/java/com/playbridge/sender/browser/LibrarySettingsScreen.kt`

Add state reading installed addons and a dropdown below the existing "Stream Picker" section (after the Max Bitrate field, before the divider + Manage Addons button). The dropdown is populated only with addons that have `stream` in their enabled resources.

```kotlin
// Near the top of the composable, alongside other pref state:
val addonDao = remember {
    DatabaseProvider.getDatabase(context).addonDao()
}
val installedAddons by addonDao.getAllAddons().collectAsState(initial = emptyList())
val streamAddons = remember(installedAddons) {
    installedAddons.filter { it.isEnabled && it.isFeatureEnabled("stream") }
}

var autoAddon by remember {
    mutableStateOf(browserPrefs.getString("auto_stream_addon", "") ?: "")
}
var addonExpanded by remember { mutableStateOf(false) }
```

UI widget (placed inside the `if (autoQuality.isNotEmpty())` block, below the Max Bitrate field, OR always visible — design choice):

```kotlin
// Always show the addon picker when any auto-select is enabled (inside if (autoQuality.isNotEmpty()) block)
if (streamAddons.isNotEmpty()) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            readOnly = true,
            value = if (autoAddon.isEmpty()) "Any addon" else autoAddon,
            onValueChange = {},
            label = { Text("Preferred Addon") },
            supportingText = { Text("Streams from this addon are tried first before falling back to others.") },
            trailingIcon = {
                IconButton(onClick = { addonExpanded = !addonExpanded }) {
                    Icon(
                        if (addonExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.matchParentSize().clickable { addonExpanded = !addonExpanded })
        DropdownMenu(
            expanded = addonExpanded,
            onDismissRequest = { addonExpanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            DropdownMenuItem(
                text = { Text("Any addon") },
                onClick = {
                    autoAddon = ""
                    browserPrefs.edit().putString("auto_stream_addon", "").apply()
                    addonExpanded = false
                }
            )
            streamAddons.forEach { addon ->
                DropdownMenuItem(
                    text = { Text(addon.name) },
                    onClick = {
                        autoAddon = addon.name
                        browserPrefs.edit().putString("auto_stream_addon", addon.name).apply()
                        addonExpanded = false
                    }
                )
            }
        }
    }
}
```

> **Note:** The `addonDao.getAllAddons()` call returns a `Flow<List<InstalledAddonEntity>>`, which already exists as part of the Room DAO — no new DAO method needed.

---

## Step 2 — `StreamSelector.kt`: Add addon preference to `selectBest()`

**Location:** `phone/app/src/main/java/com/playbridge/sender/browser/StreamSelector.kt`

Add `preferredAddon: String? = null` to `selectBest()`. When set and streams from that addon exist, apply quality/bitrate filtering only within those results. Fallback to the current full-pool logic if the preferred addon yields no qualifying streams.

```kotlin
fun selectBest(
    streams: List<ResolvedStream>,
    preferredQuality: QualityFilter?,
    maxMbps: Double? = null,
    runtimeMinutes: Int? = null,
    preferredAddon: String? = null          // NEW
): ResolvedStream? {
    if (preferredQuality == null || streams.isEmpty()) return null

    // If a preferred addon is set, try it first
    if (!preferredAddon.isNullOrBlank()) {
        val addonStreams = streams.filter { it.addonName == preferredAddon }
        if (addonStreams.isNotEmpty()) {
            val best = selectBestFromPool(addonStreams, preferredQuality, maxMbps, runtimeMinutes)
            if (best != null) return best
            // Preferred addon had streams but none matched quality/bitrate — fall through to full pool
        }
        // Preferred addon had no streams at all — fall through to full pool
    }

    return selectBestFromPool(streams, preferredQuality, maxMbps, runtimeMinutes)
}

/** Internal: applies quality + bitrate filtering to a pool without addon filtering. */
private fun selectBestFromPool(
    streams: List<ResolvedStream>,
    preferredQuality: QualityFilter,
    maxMbps: Double?,
    runtimeMinutes: Int?
): ResolvedStream? {
    var candidates = streams.filter { matchesFilter(it, preferredQuality) }
    if (candidates.isEmpty()) return null

    if (maxMbps != null) {
        val capped = candidates.filter { s ->
            val mbps = estimatedMbps(s, runtimeMinutes)
            mbps == null || mbps <= maxMbps
        }
        if (capped.isNotEmpty()) candidates = capped
    }

    return candidates.firstOrNull()
}
```

> The original `selectBest()` body becomes `selectBestFromPool()`. Existing callers of `selectBest()` don't need to change — `preferredAddon` defaults to `null`.

---

## Step 3 — `StreamPickerSheet.kt`: Read pref + update auto-pick and badge

**Location:** `phone/app/src/main/java/com/playbridge/sender/browser/StreamPickerSheet.kt`

Two places to update inside the composable:

**3a. Read the new pref** (alongside existing pref reads at the top of the composable):

```kotlin
val autoAddonKey = remember { prefs.getString("auto_stream_addon", "") ?: "" }
```

**3b. Update the `LaunchedEffect` auto-pick call** (line ~85):

```kotlin
val best = StreamSelector.selectBest(
    streams = streams,
    preferredQuality = autoFilter,
    maxMbps = autoMaxMbps,
    runtimeMinutes = episodeRuntimeMinutes,
    preferredAddon = autoAddonKey.takeIf { it.isNotEmpty() }   // NEW
)
```

**3c. Update the `autoMatchStream` computation** (line ~61, the `remember` block used for the "Would auto-pick" badge in force-manual mode):

```kotlin
val autoMatchStream = remember(streams, autoFilter, autoMaxMbps, episodeRuntimeMinutes, autoAddonKey) {
    if (forceManual && autoFilter != null && streams.isNotEmpty()) {
        val best = StreamSelector.selectBest(
            streams = streams,
            preferredQuality = autoFilter,
            maxMbps = autoMaxMbps,
            runtimeMinutes = episodeRuntimeMinutes,
            preferredAddon = autoAddonKey.takeIf { it.isNotEmpty() }   // NEW
        )
        best ?: streams.firstOrNull()
    } else null
}
```

---

## Step 4 — `LibraryDetailScreen.kt`: Read pref + inject into auto-pick

**Location:** `phone/app/src/main/java/com/playbridge/sender/browser/LibraryDetailScreen.kt`

**4a. Read the new pref** (around line 342, alongside `autoQualityKey` and `autoMaxMbps`):

```kotlin
val autoAddonKey = remember {
    context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        .getString("auto_stream_addon", "") ?: ""
}
```

**4b. Update the inline auto-pick call inside `startResolution`** (around line 374):

```kotlin
val best = StreamSelector.selectBest(
    streams = resolvedStreams,
    preferredQuality = QualityFilter.fromKey(autoQualityKey),
    maxMbps = autoMaxMbps,
    runtimeMinutes = if (!isSeries) movieDetails?.runtime else null,
    preferredAddon = autoAddonKey.takeIf { it.isNotEmpty() }   // NEW
)
```

**4c. Pass `autoAddonKey` down to `buildSeriesContext()`** (the two call sites of `buildSeriesContext` in the auto-pick block and the `StreamPickerSheet.onStreamSelected` block):

```kotlin
val seriesCtx = buildSeriesContext(
    isSeries, resolvedImdbId, selectedSeason,
    currentEpisodeSelection, displayTitle, addonMeta?.videos,
    addonRepository,
    preferredAddonName = autoAddonKey.takeIf { it.isNotEmpty() }   // NEW
)
```

---

## Step 5 — `Message.kt` (protocol): Add field to `SeriesContext`

**Location:** `protocol/src/main/java/com/playbridge/protocol/Message.kt`

Add one nullable field to `SeriesContext`. Because `protocolJson` is configured with `ignoreUnknownKeys = true` and `encodeDefaults = true`, old TV builds that don't know this field will simply ignore it — backward compatible.

```kotlin
@Serializable
data class SeriesContext(
    val imdbId: String,
    val season: Int,
    val episode: Int,
    val seriesTitle: String? = null,
    val episodeTitle: String? = null,
    val addonBaseUrls: List<String>,
    val allEpisodes: List<SeriesEpisodeRef>? = null,
    val preferredAddonBaseUrl: String? = null   // NEW — base URL of the preferred addon
)
```

> **No changes required to `Command.Play`, `PlayPayload`, `parseCommand()`, or any command helper functions** — `SeriesContext` is nested inside `PlayPayload.seriesContext` which is already fully serialized. The new field is automatically included when `PlayPayload` is encoded/decoded.

---

## Step 6 — `LibraryDetailScreen.kt`: `buildSeriesContext()` — resolve name → base URL

**Location:** `phone/app/src/main/java/com/playbridge/sender/browser/LibraryDetailScreen.kt`, function `buildSeriesContext()` (~line 1758)

**6a. Extend the function signature:**

```kotlin
private suspend fun buildSeriesContext(
    isSeries: Boolean,
    resolvedImdbId: String?,
    selectedSeason: Int,
    currentEpisodeSelection: com.playbridge.sender.data.library.StremioVideo?,
    displayTitle: String,
    addonVideos: List<com.playbridge.sender.data.library.StremioVideo>?,
    addonRepository: AddonRepository,
    preferredAddonName: String? = null   // NEW
): com.playbridge.protocol.SeriesContext? {
```

**6b. Resolve the preferred addon name to its base URL while building the addons list:**

```kotlin
val installedStreamAddons = addonRepository.getInstalledAddons()
    .filter { it.isEnabled && (it.resources.isBlank() || it.supportsResource("stream")) }

val addonBaseUrls = installedStreamAddons.map { it.baseUrl }

if (addonBaseUrls.isEmpty()) return null

// Resolve name → base URL for the preferred addon (null if not set or not found)
val preferredAddonBaseUrl = preferredAddonName?.takeIf { it.isNotEmpty() }?.let { name ->
    installedStreamAddons.firstOrNull { it.name == name }?.baseUrl
}
```

**6c. Add the new field to the returned `SeriesContext`:**

```kotlin
return com.playbridge.protocol.SeriesContext(
    imdbId            = resolvedImdbId,
    season            = selectedSeason,
    episode           = currentEpisodeSelection.episode ?: 1,
    seriesTitle       = displayTitle.ifBlank { null },
    episodeTitle      = currentEpisodeSelection.title.ifBlank { null },
    addonBaseUrls     = addonBaseUrls,
    allEpisodes       = allEpisodes,
    preferredAddonBaseUrl = preferredAddonBaseUrl   // NEW
)
```

> This also requires updating the two `buildSeriesContext()` call sites in `LibraryDetailScreen` (step 4c above) to pass `preferredAddonName`.

---

## Step 7 — `StremioClient.kt` (TV): Try preferred addon first

**Location:** `tv/player/app/src/main/java/com/playbridge/player/stremio/StremioClient.kt`

**7a. Add `preferredAddonBaseUrl` parameter to `resolveEpisode()`:**

```kotlin
suspend fun resolveEpisode(
    addonBaseUrls: List<String>,
    imdbId: String,
    season: Int,
    episode: Int,
    qualityPreference: String? = null,
    sourceHint: String? = null,
    preferredAddonBaseUrl: String? = null   // NEW
): ResolvedStremioStream? = withContext(Dispatchers.IO) {
```

**7b. Try the preferred addon in isolation first, before the full parallel call:**

Insert this block at the top of the function body, before the existing `coroutineScope { addonBaseUrls.map { ... } }` call:

```kotlin
// If a preferred addon is specified, try it in isolation first.
// Only fall back to all addons when the preferred one returns nothing.
if (!preferredAddonBaseUrl.isNullOrBlank() && preferredAddonBaseUrl in addonBaseUrls) {
    Log.d(TAG, "Trying preferred addon first: $preferredAddonBaseUrl")
    val preferredStreams = fetchFromAddon(preferredAddonBaseUrl, stremioId)
    if (preferredStreams.isNotEmpty()) {
        Log.d(TAG, "Preferred addon returned ${preferredStreams.size} stream(s); skipping other addons")
        val result = pickBest(preferredStreams, qualityPreference, sourceHint)
        if (result != null) return@withContext result
        Log.d(TAG, "No qualifying stream from preferred addon; falling back to all addons")
    }
}
```

The rest of the function (fetching all addons in parallel) remains unchanged — it is the fallback when the preferred addon returns nothing or no qualifying stream.

---

## Step 8 — `SeriesNavigator.kt` (TV): Forward preferred addon to each resolution call

**Location:** `tv/player/app/src/main/java/com/playbridge/player/stremio/SeriesNavigator.kt`

`SeriesNavigator` already holds the full `SeriesContext` as `context`. No constructor change is needed — just pass `context.preferredAddonBaseUrl` to every `StremioClient.resolveEpisode()` call.

There are three call sites: `resolveNext()`, `resolvePrev()`, and `resolveAndAdvanceToIndex()`. Each becomes:

```kotlin
val stream = StremioClient.resolveEpisode(
    addonBaseUrls          = context.addonBaseUrls,
    imdbId                 = context.imdbId,
    season                 = nextRef.season,    // or prevRef / targetRef
    episode                = nextRef.episode,
    qualityPreference      = qualityPreference,
    sourceHint             = currentSourceHint,
    preferredAddonBaseUrl  = context.preferredAddonBaseUrl   // NEW
)
```

---

## Fallback behaviour summary

| Scenario | Result |
|----------|--------|
| Preferred addon set, has matching quality stream | Use that stream |
| Preferred addon set, has streams but none match quality/bitrate | Fall back to best stream from **all** addons (existing logic) |
| Preferred addon set, returns no streams at all | Fall back to best stream from **all** addons |
| Preferred addon not set (empty string) | Existing behaviour unchanged |
| TV receives old `PlayPayload` without `preferredAddonBaseUrl` | Field is `null`, TV falls back to all-addon resolution (backward compatible) |

---

## No changes required in

- `ServerService.kt` (TV) — already passes `seriesContext` verbatim in the intent to `ExoPlayerActivity`; the new field travels automatically.
- `ExoPlayerActivity.kt` (TV) — constructs `SeriesNavigator(ctx, defaultVideoQuality)` where `ctx` is the full `SeriesContext`; no change needed.
- `ConnectionViewModel.kt` (phone) — does not touch stream-picker prefs or `SeriesContext` construction.
- `AddonRepository.kt` — no new methods needed; `getInstalledAddons()` already exists.
- `AddonModels.kt` — `InstalledAddonEntity.isFeatureEnabled()` and `supportsResource()` are already the right helpers to use.
- `ExportedSettings.kt` — the new pref lives in `browser_prefs` under a plain `String` key; it will be captured automatically by any future settings backup if that pref file is included.

---

## Testing checklist

- [ ] With a preferred addon set and auto-quality on: verify the auto-pick on the phone uses only streams from that addon (check Toast message showing stream name)
- [ ] With preferred addon set but addon has no streams for the content: verify fallback to any-addon auto-pick works (no crash, correct stream plays)
- [ ] Force-manual picker (long-press): verify "Would auto-pick" badge appears on the stream from the preferred addon, not another addon's stream
- [ ] Cast to TV from LibraryDetailScreen: inspect the serialized `PlayPayload` (via FileLogger on TV) and confirm `seriesContext.preferredAddonBaseUrl` is populated
- [ ] TV next-episode navigation: confirm the TV contacts the preferred addon's URL first, and falls back when it returns nothing
- [ ] Old phone paired with new TV (or vice versa): confirm `preferredAddonBaseUrl: null` is handled gracefully on both sides
