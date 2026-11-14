package com.playbridge.player.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.playbridge.player.BuildConfig
import com.playbridge.player.FlavorConfig
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.model.PairedDevice
import com.playbridge.player.pairing.PairingStore
import com.playbridge.player.player.StillWatchingPrefs
import com.playbridge.player.player.SubtitleRenderingMode
import com.playbridge.player.player.SubtitleStyleStore
import com.playbridge.player.data.HistoryThumbnailMode
import com.playbridge.player.server.ServerService
import com.playbridge.player.together.TvTogether
import com.playbridge.player.ui.components.ListRow
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.SectionHeading
import com.playbridge.player.ui.components.SwitchRow
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.components.ValueRow
import com.playbridge.player.ui.settings.CategoryRow
import com.playbridge.player.ui.settings.ConfirmDialog
import com.playbridge.player.ui.settings.ContentDialog
import com.playbridge.player.ui.settings.InfoRow
import com.playbridge.player.ui.settings.NoteText
import com.playbridge.player.ui.settings.OptionPickerDialog
import com.playbridge.player.ui.settings.PhoneRow
import com.playbridge.player.ui.settings.SettingOption
import com.playbridge.player.ui.settings.SettingsCategory
import com.playbridge.player.ui.settings.SettingsText
import com.playbridge.player.ui.settings.TextEntryDialog
import com.playbridge.player.ui.settings.labelFor
import com.playbridge.player.ui.theme.AppTheme
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import com.playbridge.shared.player.SubtitleStyle
import com.playbridge.shared.update.UpdateState
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_INTRODB_URL = "https://api.introdb.app"
private const val DEFAULT_THEINTRODB_URL = "https://api.theintrodb.org"

/** The focus targets the category list and the rows use to hand focus to each other. */
private class SettingsFocus(
    val content: FocusRequester,
    val categories: Map<SettingsCategory, FocusRequester>,
)

/**
 * Settings: a category list on the left and the chosen category's rows on the right.
 * Focus: the list is focused on entry; Right or OK moves into the rows; Left or Back from the rows
 * returns to the category that owns them; Back from the list leaves Settings (handled by the caller).
 *
 * @param pairingStore the store paired phones are read from and forgotten in. Callers that already
 *   hold one should pass it; the default opens the same underlying data.
 * @param initialCategory the category shown (and focused) on entry.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onThemeChanged: (AppTheme) -> Unit,
    pairingStore: PairingStore? = null,
    initialCategory: SettingsCategory = SettingsCategory.Playback,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val store = pairingStore ?: remember { PairingStore(context.applicationContext) }

    var selected by rememberSaveable { mutableStateOf(initialCategory) }
    val focus = remember {
        SettingsFocus(FocusRequester(), SettingsCategory.entries.associateWith { FocusRequester() })
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.categories.getValue(selected).requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = TvSpacing.screenGutter - 8.dp, end = TvSpacing.screenGutter - 8.dp, top = TvSpacing.screenTop),
    ) {
        // Category list. Moving focus onto a category shows its rows.
        Column(
            Modifier
                .width(336.dp)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) {
                        runCatching { focus.content.requestFocus() }.getOrDefault(false)
                    } else {
                        false
                    }
                }
                // Scrolls when the list is taller than the screen, so the focused category is always
                // brought fully into view; the padding inside keeps its focus ring from being clipped.
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                "Settings",
                style = TvText.screenTitle,
                color = TvTheme.colors.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsCategory.everyday.forEach { category ->
                    CategoryEntry(category, selected, focus) { selected = category }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "For troubleshooting",
                    style = TvText.small,
                    color = TvTheme.colors.onSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                CategoryEntry(SettingsCategory.Advanced, selected, focus) { selected = SettingsCategory.Advanced }
            }
        }

        Spacer(Modifier.width(32.dp))

        // Rows of the chosen category. Recreated per category so each starts scrolled to the top.
        key(selected) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 58.dp)
                    .focusRequester(focus.content)
                    .focusProperties {
                        exit = { direction ->
                            if (direction == FocusDirection.Left) focus.categories.getValue(selected) else FocusRequester.Default
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyDown) {
                                runCatching { focus.categories.getValue(selected).requestFocus() }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (selected) {
                    SettingsCategory.Playback -> PlaybackSettings(prefs)
                    SettingsCategory.Subtitles -> SubtitleSettings()
                    SettingsCategory.Skip -> SkipSettings(prefs)
                    SettingsCategory.Phones -> PhoneSettings(store, focus)
                    SettingsCategory.Appearance -> AppearanceSettings(prefs, store, onThemeChanged)
                    SettingsCategory.About -> AboutSettings()
                    SettingsCategory.Advanced -> AdvancedSettings(prefs)
                }
            }
        }
    }
}

@Composable
private fun CategoryEntry(
    category: SettingsCategory,
    selected: SettingsCategory,
    focus: SettingsFocus,
    onSelect: () -> Unit,
) {
    CategoryRow(
        label = category.label,
        selected = category == selected,
        onClick = { runCatching { focus.content.requestFocus() } },
        modifier = Modifier
            .focusRequester(focus.categories.getValue(category))
            .onFocusChanged { if (it.isFocused) onSelect() },
    )
}

// ─── Row helpers ──────────────────────────────────────────────────────────────

@Composable
private fun PrefSwitch(
    prefs: SharedPreferences,
    key: String,
    default: Boolean,
    title: String,
    description: String,
    onChanged: (Boolean) -> Unit = {},
) {
    var value by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    SwitchRow(
        title = title,
        description = description,
        checked = value,
        onCheckedChange = {
            value = it
            prefs.edit().putBoolean(key, it).apply()
            onChanged(it)
        },
    )
}

/** A value row that opens a picker of [options]. */
@Composable
private fun PickerRow(
    title: String,
    options: List<SettingOption>,
    selected: String,
    onSelected: (String) -> Unit,
    description: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    ValueRow(title = title, value = options.labelFor(selected), onClick = { open = true }, description = description)
    if (open) {
        OptionPickerDialog(title, options, selected, onSelected, onDismiss = { open = false })
    }
}

/** A value row that opens a text entry. [display] is what the row shows; [initial] is what the field starts with. */
@Composable
private fun TextRow(
    title: String,
    display: String,
    initial: String,
    dialogMessage: String,
    onSave: (String) -> Unit,
    description: String? = null,
    saveLabel: String = "Save",
) {
    var open by remember { mutableStateOf(false) }
    ValueRow(title = title, value = display, onClick = { open = true }, description = description)
    if (open) {
        TextEntryDialog(
            title = title,
            message = dialogMessage,
            initial = initial,
            saveLabel = saveLabel,
            onSave = {
                onSave(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun GroupHeading(text: String) {
    SectionHeading(text, Modifier.padding(start = 4.dp, top = TvSpacing.l, bottom = TvSpacing.xs))
}

@Composable
private fun rememberGeckoInstalled(): Boolean {
    val context = LocalContext.current
    var installed by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = try {
                    context.packageManager.getPackageInfo("com.playbridge.geckoview.plugin", 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return installed
}

// ─── Playback ─────────────────────────────────────────────────────────────────

@Composable
private fun PlaybackSettings(prefs: SharedPreferences) {
    var playerMode by remember { mutableStateOf(prefs.getString("player_mode", "phone") ?: "phone") }
    PickerRow(
        title = "Video player",
        description = "Which player plays casts. The phone's choice is used unless you pick one here.",
        options = listOf(
            SettingOption("phone", "Phone's choice"),
            SettingOption("exo", "ExoPlayer"),
            SettingOption("mpv", "MPV"),
        ),
        selected = playerMode,
        onSelected = {
            playerMode = it
            prefs.edit().putString("player_mode", it).apply()
        },
    )
    PrefSwitch(
        prefs, "frame_rate_matching", false,
        title = "Match the video's frame rate",
        description = "Smoother motion on TVs that support it (Android 11 or newer). The screen may go black briefly when a video starts.",
    )
    PrefSwitch(
        prefs, "loudness_enhancer", false,
        title = "Boost quiet audio",
        description = "Raises dialogue on videos that play too quietly and evens out loud peaks.",
    )

    var stillWatching by remember { mutableStateOf(prefs.getBoolean(StillWatchingPrefs.PREF_STILL_WATCHING_ENABLED, false)) }
    SwitchRow(
        title = "Ask \"Still watching?\"",
        description = "Pauses after a long stretch of playback and returns to idle if nobody answers.",
        checked = stillWatching,
        onCheckedChange = {
            stillWatching = it
            prefs.edit().putBoolean(StillWatchingPrefs.PREF_STILL_WATCHING_ENABLED, it).apply()
        },
    )
    if (stillWatching) {
        var minutes by remember {
            mutableStateOf(
                StillWatchingPrefs.normalizeStillWatchingThreshold(
                    prefs.getInt(StillWatchingPrefs.PREF_STILL_WATCHING_THRESHOLD_MIN, 90),
                ),
            )
        }
        PickerRow(
            title = "Ask after",
            description = "How long it plays before asking whether to keep going.",
            options = StillWatchingPrefs.STILL_WATCHING_PRESETS.sorted().map {
                SettingOption(it.toString(), SettingsText.stillWatchingThresholdLabel(it))
            },
            selected = minutes.toString(),
            onSelected = { value ->
                value.toIntOrNull()?.takeIf { it in StillWatchingPrefs.STILL_WATCHING_PRESETS }?.let {
                    minutes = it
                    prefs.edit().putInt(StillWatchingPrefs.PREF_STILL_WATCHING_THRESHOLD_MIN, it).apply()
                }
            },
        )
        var seconds by remember {
            mutableStateOf(
                StillWatchingPrefs.normalizeStillWatchingResponseSeconds(
                    prefs.getInt(StillWatchingPrefs.PREF_STILL_WATCHING_RESPONSE_SEC, 300),
                ),
            )
        }
        PickerRow(
            title = "Time to answer",
            description = "How long you have to answer before playback stops.",
            options = StillWatchingPrefs.STILL_WATCHING_RESPONSE_PRESETS.sorted().map {
                SettingOption(it.toString(), SettingsText.stillWatchingResponseLabel(it))
            },
            selected = seconds.toString(),
            onSelected = { value ->
                value.toIntOrNull()?.takeIf { it in StillWatchingPrefs.STILL_WATCHING_RESPONSE_PRESETS }?.let {
                    seconds = it
                    prefs.edit().putInt(StillWatchingPrefs.PREF_STILL_WATCHING_RESPONSE_SEC, it).apply()
                }
            },
        )
    }

    PrefSwitch(
        prefs, "enable_history", true,
        title = "Remember what I cast",
        description = "Keeps future casts and where you stopped, so you can pick up again.",
    )
    ClearHistoryRow()
}

/** Clears stored watch history, keeping bookmarks, after a confirmation. */
@Composable
private fun ClearHistoryRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val historyStore = remember { com.playbridge.player.data.HistoryStore(context.applicationContext) }
    var confirm by remember { mutableStateOf(false) }
    ValueRow(
        title = "Clear watch history",
        value = "Clear",
        onClick = { confirm = true },
        description = "Forgets where you stopped in videos that aren't bookmarked. Bookmarks are kept.",
    )
    if (confirm) {
        ConfirmDialog(
            title = "Clear watch history?",
            message = "Saved places in videos that aren't bookmarked are removed. Your bookmarks and their places are kept.",
            confirmLabel = "Clear history",
            onConfirm = {
                confirm = false
                scope.launch { historyStore.clearHistory() }
                Toast.makeText(context, "Watch history cleared", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirm = false },
        )
    }
}

// ─── Subtitles ────────────────────────────────────────────────────────────────

@Composable
private fun SubtitleSettings() {
    val context = LocalContext.current
    // Subtitle style is TV-wide and is also changed by a paired phone.
    val styles = remember { SubtitleStyleStore.get(context) }
    val style by styles.style.collectAsState()
    val installedFonts by styles.installedFonts.collectAsState()

    PickerRow(
        title = "Size",
        options = listOf(
            SettingOption(SubtitleStyle.Size.SMALL.wire, "Small"),
            SettingOption(SubtitleStyle.Size.MEDIUM.wire, "Medium"),
            SettingOption(SubtitleStyle.Size.LARGE.wire, "Large"),
            SettingOption(SubtitleStyle.Size.EXTRA_LARGE.wire, "Extra large"),
        ),
        selected = style.size.wire,
        onSelected = { wire ->
            SubtitleStyle.Size.entries.firstOrNull { it.wire == wire }?.let { styles.set(style.copy(size = it)) }
        },
    )
    if (installedFonts.isNotEmpty()) {
        PickerRow(
            title = "Font",
            description = "Characters a font lacks use the TV's own fonts.",
            options = listOf(SettingOption(SubtitleStyle.Font.DEFAULT.wire, "Default")) +
                installedFonts.map { SettingOption(it, it) },
            selected = style.customFont ?: SubtitleStyle.Font.DEFAULT.wire,
            onSelected = { choice ->
                styles.set(
                    if (choice in installedFonts) {
                        style.copy(font = SubtitleStyle.Font.CUSTOM, customFont = choice)
                    } else {
                        style.copy(font = SubtitleStyle.Font.DEFAULT, customFont = null)
                    },
                )
            },
        )
    }
    PickerRow(
        title = "Background",
        description = "A box behind subtitles keeps them readable on bright scenes.",
        options = listOf(
            SettingOption(SubtitleStyle.Background.NONE.wire, "None"),
            SettingOption(SubtitleStyle.Background.TRANSLUCENT.wire, "Semi-transparent black"),
            SettingOption(SubtitleStyle.Background.SOLID.wire, "Solid black"),
        ),
        selected = style.background.wire,
        onSelected = { wire ->
            SubtitleStyle.Background.entries.firstOrNull { it.wire == wire }?.let { styles.set(style.copy(background = it)) }
        },
    )
    PickerRow(
        title = "Edge",
        description = "The dark edge around subtitle letters.",
        options = listOf(
            SettingOption(SubtitleStyle.Edge.OUTLINE.wire, "Outline"),
            SettingOption(SubtitleStyle.Edge.SHADOW.wire, "Drop shadow"),
            SettingOption(SubtitleStyle.Edge.NONE.wire, "None"),
        ),
        selected = style.edge.wire,
        onSelected = { wire ->
            SubtitleStyle.Edge.entries.firstOrNull { it.wire == wire }?.let { styles.set(style.copy(edge = it)) }
        },
    )
    SwitchRow(
        title = "Use this style on pre-styled subtitles too",
        description = "Some subtitles (such as ASS) bring their own look. Off keeps it.",
        checked = style.overrideStyled,
        onCheckedChange = { styles.set(style.copy(overrideStyled = it)) },
    )
    NoteText("You can also change these, and add your own fonts, from a connected phone.")
}

// ─── Skip ─────────────────────────────────────────────────────────────────────

@Composable
private fun SkipSettings(prefs: SharedPreferences) {
    PrefSwitch(
        prefs, "auto_skip_intro", false,
        title = "Skip intros by themselves",
        description = "When off, you choose whether to skip.",
    )
    PrefSwitch(
        prefs, "auto_skip_recap", false,
        title = "Skip recaps by themselves",
        description = "The \"previously on\" part at the start of an episode.",
    )
    PrefSwitch(
        prefs, "auto_skip_outro", false,
        title = "Skip end credits by themselves",
        description = "Goes straight to the next episode in a queue.",
    )
    var provider by remember { mutableStateOf(prefs.getString("skip_segments_provider", "both") ?: "both") }
    PickerRow(
        title = "Where skip times come from",
        description = "Community databases of intro and recap times.",
        options = listOf(
            SettingOption("both", "Both", "IntroDB first, TheIntroDB fills the gaps (movies too)"),
            SettingOption("introdb", "IntroDB", "IntroDB only"),
            SettingOption("theintrodb", "TheIntroDB", "TheIntroDB only"),
        ),
        selected = provider,
        onSelected = {
            provider = it
            prefs.edit().putString("skip_segments_provider", it).apply()
        },
    )
    NoteText("Service keys and addresses are under Advanced.")
}

// ─── Phones ───────────────────────────────────────────────────────────────────

@Composable
private fun PhoneSettings(store: PairingStore, focus: SettingsFocus) {
    val devices by store.pairedDevices.collectAsState(initial = null)
    val connected by ServerService.connectedClientCount.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmForgetAll by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val zone = remember { TimeZone.getDefault() }
    val locale = remember { Locale.getDefault() }

    // A forgotten phone takes the focused Forget button with it: put focus back in the rows, or on
    // the category when none are left.
    var lastCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(devices?.size) {
        val count = devices?.size
        val previous = lastCount
        lastCount = count
        if (previous != null && count != null && count < previous) {
            withFrameNanos { }
            val moved = runCatching { focus.content.requestFocus() }.getOrDefault(false)
            if (!moved) runCatching { focus.categories.getValue(SettingsCategory.Phones).requestFocus() }
        }
    }

    val list = devices ?: return
    SectionHeading("Paired phones", hint = SettingsText.connectedPhonesLabel(connected))
    if (list.isEmpty()) {
        NoteText("No phones are paired with this TV. Open PlayBridge on a phone and choose this TV to connect.")
    } else {
        list.forEach { device: PairedDevice ->
            key(device.id) {
                PhoneRow(
                    name = device.name,
                    subtitle = SettingsText.phoneSubtitle(device, list, now, zone, locale),
                    onForget = { scope.launch { store.forgetDevice(device) } },
                )
            }
        }
        Row(Modifier.padding(top = TvSpacing.s)) {
            PillButton("Forget all", { confirmForgetAll = true }, style = PillStyle.Secondary)
        }
        NoteText("A forgotten phone can't reconnect until it is paired with this TV again.")
    }
    if (confirmForgetAll) {
        ConfirmDialog(
            title = "Forget all phones?",
            message = "Every paired phone is removed and can't reconnect until it is paired again.",
            confirmLabel = "Forget all",
            onConfirm = {
                confirmForgetAll = false
                scope.launch { store.forgetAllDevices() }
            },
            onDismiss = { confirmForgetAll = false },
        )
    }
}

// ─── Appearance ───────────────────────────────────────────────────────────────

@Composable
private fun AppearanceSettings(
    prefs: SharedPreferences,
    store: PairingStore,
    onThemeChanged: (AppTheme) -> Unit,
) {
    var themeName by remember { mutableStateOf(prefs.getString("app_theme", "DARK") ?: "DARK") }
    SectionHeading("Theme", Modifier.padding(start = 4.dp, bottom = TvSpacing.xs))
    AppTheme.entries.forEach { theme ->
        ListRow(
            title = SettingsText.themeLabel(theme),
            selected = theme.name == themeName,
            tone = TvTone.App,
            onClick = {
                themeName = theme.name
                prefs.edit().putString("app_theme", theme.name).apply()
                onThemeChanged(theme)
            },
        )
    }

    GroupHeading("This TV")
    // The name phones see comes from Android's device name, so it is shown, not edited.
    val appContext = LocalContext.current.applicationContext
    val tvName = remember { com.playbridge.player.server.advertisedDeviceName(appContext) }
    InfoRow(
        title = "TV name",
        description = "What phones see in their list. It comes from this TV's Android device name.",
        value = tvName,
    )

    if (prefs.getBoolean("enable_history", true)) {
        var mode by remember {
            mutableStateOf(
                HistoryThumbnailMode.fromPreference(
                    prefs.getString(HistoryThumbnailMode.PREFERENCE_KEY, HistoryThumbnailMode.SMART.preferenceValue),
                ),
            )
        }
        GroupHeading("Pictures")
        PickerRow(
            title = "Bookmark pictures",
            description = when (mode) {
                HistoryThumbnailMode.SMART -> "Uses a frame from the video when there's no artwork."
                HistoryThumbnailMode.LIVE -> "Takes a frame after 15 seconds, then refreshes it every 2 minutes."
                HistoryThumbnailMode.ARTWORK_ONLY -> "Only uses the poster or backdrop that came with the cast."
            },
            options = listOf(
                SettingOption(HistoryThumbnailMode.SMART.preferenceValue, "Smart", "Grabs a missing picture after 15 seconds, with a fallback on exit"),
                SettingOption(HistoryThumbnailMode.LIVE.preferenceValue, "Live", "Grabs after 15 seconds, then every 2 minutes"),
                SettingOption(HistoryThumbnailMode.ARTWORK_ONLY.preferenceValue, "Artwork only", "Never grabs a frame from the video"),
            ),
            selected = mode.preferenceValue,
            onSelected = {
                mode = HistoryThumbnailMode.fromPreference(it)
                prefs.edit().putString(HistoryThumbnailMode.PREFERENCE_KEY, mode.preferenceValue).apply()
            },
        )
    }
}

// ─── About ────────────────────────────────────────────────────────────────────

@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val geckoInstalled = rememberGeckoInstalled()
    var showGeckoDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    InfoRow(title = "PlayBridge TV", value = BuildConfig.VERSION_NAME, description = "Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.FLAVOR}")

    val updateChecker = remember { com.playbridge.player.update.UpdateChecker.getInstance(context) }
    val updateState by updateChecker.state.collectAsState()
    val busy = SettingsText.isUpdateBusy(updateState)
    // The update dialog and progress are drawn once at the activity root; here is only the trigger.
    ValueRow(
        title = "Check for updates",
        description = SettingsText.updateStatus(updateState),
        value = if (updateState is UpdateState.Checking) "Checking…" else "Check now",
        onClick = { if (!busy) updateChecker.check(manual = true) },
    )

    // Play flavor: never advertise the sideloaded plugin APK; only show the row when it is already installed.
    if (geckoInstalled || FlavorConfig.SIDELOAD_LINKS_SUPPORTED) {
        ValueRow(
            title = "GeckoView add-on",
            description = "An optional browser engine for pages that don't work in the system browser.",
            value = if (geckoInstalled) "Installed" else "Not installed",
            onClick = {
                if (geckoInstalled) {
                    Toast.makeText(context, "GeckoView plugin is ready to use", Toast.LENGTH_SHORT).show()
                } else {
                    showGeckoDialog = true
                }
            },
        )
    }
    ValueRow(
        title = "Quit PlayBridge",
        description = "Phones can't cast to this TV until you open it again.",
        value = "Quit",
        onClick = { showExitDialog = true },
    )

    if (showExitDialog) {
        ConfirmDialog(
            title = "Quit PlayBridge?",
            message = "This stops the server. Phones can't cast to this TV until you open the app again.",
            confirmLabel = "Quit",
            onConfirm = {
                showExitDialog = false
                exitApp(context, scope)
            },
            onDismiss = { showExitDialog = false },
        )
    }
    if (showGeckoDialog) {
        ConfirmDialog(
            title = "Install the GeckoView add-on",
            message = "GeckoView is an optional browser engine that works better with some streaming sites.\n\n" +
                "You can download it from:\n" + BuildConfig.GECKO_PLUGIN_DOWNLOAD_URL,
            confirmLabel = "Open download page",
            onConfirm = {
                showGeckoDialog = false
                try {
                    val intent = Intent(context, com.playbridge.player.browser.BrowserActivity::class.java).apply {
                        action = "com.playbridge.player.ACTION_BROWSER_INTERNAL"
                        putExtra("extra_url", BuildConfig.GECKO_PLUGIN_DOWNLOAD_URL)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to launch browser: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showGeckoDialog = false },
        )
    }
}

private fun exitApp(context: Context, scope: CoroutineScope) {
    scope.launch {
        // Stop the foreground service first so the WS server closes cleanly and NSD unregisters.
        ServerService.stop(context)
        delay(300)
        (context as? Activity)?.finishAffinity()
        // Kill the process so nothing (started-sticky service, retained singletons) lingers.
        // BootReceiver brings the server back on next boot or app launch.
        kotlin.system.exitProcess(0)
    }
}

// ─── Advanced ─────────────────────────────────────────────────────────────────

@Composable
private fun AdvancedSettings(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NoteText("Most people never need these.")

    // Connection
    GroupHeading("Connection")
    var customIp by remember { mutableStateOf(prefs.getString("preferred_ip", "") ?: "") }
    var restarting by remember { mutableStateOf(false) }
    fun restartServer() {
        restarting = true
        scope.launch {
            ServerService.stop(context)
            delay(1500)
            ServerService.start(context)
            delay(1200)
            restarting = false
        }
    }
    TextRow(
        title = "This TV's address",
        description = "Chosen automatically. Change it if phones can't find the TV (for example with an emulator).",
        display = SettingsText.addressLabel(customIp),
        initial = if (customIp == "auto") "" else customIp,
        dialogMessage = "Useful for emulator port forwarding. Leave empty for automatic.",
        saveLabel = "Save and restart",
        onSave = { typed ->
            val finalIp = typed.trim().ifEmpty { "auto" }
            customIp = finalIp
            prefs.edit().putString("preferred_ip", finalIp).apply()
            restartServer()
        },
    )
    ValueRow(
        title = if (restarting) "Restarting…" else "Restart the connection",
        description = "Restarts the server phones connect to. They reconnect by themselves in a few seconds.",
        value = "Restart",
        onClick = { if (!restarting) restartServer() },
    )

    // Browser
    GroupHeading("Browser")
    val geckoInstalled = rememberGeckoInstalled()
    var browserMode by remember { mutableStateOf(prefs.getString("browser_mode_override", "phone") ?: "phone") }
    PickerRow(
        title = "Browser engine",
        description = "Which browser opens pages cast from a phone. GeckoView needs a separate add-on app.",
        options = buildList {
            add(SettingOption("phone", "Phone's choice"))
            add(SettingOption("webview", "System WebView"))
            if (geckoInstalled) add(SettingOption("gecko", "GeckoView"))
        },
        selected = browserMode,
        onSelected = {
            browserMode = it
            prefs.edit().putString("browser_mode_override", it).apply()
        },
    )
    PrefSwitch(
        prefs, "hide_soft_keyboard", false,
        title = "Hide the on-screen keyboard",
        description = "Don't pop up the TV keyboard in web fields. Type on the phone's keyboard instead (built-in WebView browser).",
    )
    BrowserScriptsRow()

    // Subtitles and skip services
    GroupHeading("Subtitles and skip services")
    var renderingMode by remember {
        mutableStateOf(
            SubtitleRenderingMode.fromPreference(
                prefs.getString(SubtitleRenderingMode.PREFERENCE_KEY, SubtitleRenderingMode.AUTO.preferenceValue),
            ),
        )
    }
    PickerRow(
        title = "Subtitle rendering",
        description = when (renderingMode) {
            SubtitleRenderingMode.AUTO -> "Uses the player's renderer, with the PlayBridge overlay as a fallback."
            SubtitleRenderingMode.BUILT_IN -> "Uses MPV/libass or ExoPlayer's built-in subtitle renderer."
            SubtitleRenderingMode.PLAYBRIDGE_OVERLAY -> "Uses PlayBridge's overlay, which handles headers and sizes text to fit."
        },
        options = listOf(
            SettingOption(SubtitleRenderingMode.AUTO.preferenceValue, "Automatic (recommended)"),
            SettingOption(SubtitleRenderingMode.BUILT_IN.preferenceValue, "Built-in player"),
            SettingOption(SubtitleRenderingMode.PLAYBRIDGE_OVERLAY.preferenceValue, "PlayBridge overlay"),
        ),
        selected = renderingMode.preferenceValue,
        onSelected = {
            renderingMode = SubtitleRenderingMode.fromPreference(it)
            prefs.edit().putString(SubtitleRenderingMode.PREFERENCE_KEY, renderingMode.preferenceValue).apply()
        },
    )
    var apiKey by remember { mutableStateOf(prefs.getString("introdb_api_key", "") ?: "") }
    TextRow(
        title = "IntroDB key",
        description = "Only needed for a private IntroDB account.",
        display = if (apiKey.isEmpty()) "Not set" else "Set",
        initial = apiKey,
        dialogMessage = "Enter your IntroDB API key or Clerk token. Leave empty if using the public API.",
        onSave = {
            apiKey = it.trim()
            prefs.edit().putString("introdb_api_key", apiKey).apply()
        },
    )
    var introDbUrl by remember { mutableStateOf(prefs.getString("introdb_api_url", DEFAULT_INTRODB_URL) ?: DEFAULT_INTRODB_URL) }
    TextRow(
        title = "IntroDB address",
        display = SettingsText.hostOf(introDbUrl),
        initial = introDbUrl,
        dialogMessage = "A custom address for skip times. The default is $DEFAULT_INTRODB_URL",
        onSave = {
            introDbUrl = it.trim().ifEmpty { DEFAULT_INTRODB_URL }
            prefs.edit().putString("introdb_api_url", introDbUrl).apply()
        },
    )
    var theIntroDbUrl by remember { mutableStateOf(prefs.getString("theintrodb_api_url", DEFAULT_THEINTRODB_URL) ?: DEFAULT_THEINTRODB_URL) }
    TextRow(
        title = "TheIntroDB address",
        display = SettingsText.hostOf(theIntroDbUrl),
        initial = theIntroDbUrl,
        dialogMessage = "A custom address for TheIntroDB skip times. The default is $DEFAULT_THEINTRODB_URL",
        onSave = {
            theIntroDbUrl = it.trim().ifEmpty { DEFAULT_THEINTRODB_URL }
            prefs.edit().putString("theintrodb_api_url", theIntroDbUrl).apply()
        },
    )

    // Watch Together service address (the party screens live on Home)
    GroupHeading("Watch Together")
    var pairingUrl by remember { mutableStateOf(TvTogether.pairingUrl(context)) }
    TextRow(
        title = "Pairing service",
        description = "The address of the Watch Together pairing service.",
        display = pairingUrl.ifEmpty { "Not set" },
        initial = pairingUrl,
        dialogMessage = "The address of the Watch Together pairing service, for example https://pair.example.workers.dev",
        onSave = {
            TvTogether.setPairingUrl(context, it)
            pairingUrl = TvTogether.pairingUrl(context)
        },
    )

    // Logs
    GroupHeading("Logs")
    LogSettings()
}

/** "Browser scripts from phones": a summary row that opens the existing script manager. */
@Composable
private fun BrowserScriptsRow() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val summary = remember(refresh) {
        val controller = com.playbridge.player.userscript.UserScriptRuntime.get(context)
        controller.ensureMigrated()
        SettingsText.scriptsSummary(controller.isInstallEnabled(), controller.listInstalled().size)
    }
    ValueRow(
        title = "Browser scripts from phones",
        description = "Each one still asks for approval on this TV.",
        value = summary,
        onClick = { open = true },
    )
    if (open) {
        ContentDialog(
            title = "Browser scripts from phones",
            onDismiss = {
                open = false
                refresh++
            },
        ) { UserScriptSettingsSection() }
    }
}

@Composable
private fun LogSettings() {
    var enabled by remember { mutableStateOf(FileLogger.isEnabled()) }
    SwitchRow(
        title = "Keep a log for bug reports",
        description = "Saves a log on this TV. It can contain stream addresses and request headers " +
            "(including Debrid tokens), so only turn it on when needed.",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            FileLogger.setEnabled(it)
        },
    )
    if (!enabled) {
        NoteText("Logging is off. No log is being saved on this TV.")
        return
    }
    ValueRow(
        title = "Clear the log",
        description = "Deletes the saved log files on this TV.",
        value = "Clear",
        onClick = { FileLogger.clearLogs() },
    )
    NoteText("The log stays on this TV. A connected phone can pull it from Settings → TV → Logs.")
    RecentLogLines()
}

/** Collected here so the fast-changing log only recomposes these lines. */
@Composable
private fun RecentLogLines() {
    val recentLogs by FileLogger.recent.collectAsState()
    if (recentLogs.isEmpty()) {
        NoteText("No log entries yet.")
    } else {
        // A short recent summary is readable at ten-foot distance; the phone can pull the full file.
        recentLogs.takeLast(12).forEach { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                style = TvText.micro,
                color = TvTheme.colors.onSurface,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 1.dp),
            )
        }
    }
}

/** Switch row kept for [UserScriptSettingsSection], which still builds its toggle through this. */
@Composable
internal fun SettingToggleItem(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchRow(title = label, description = description, checked = checked, onCheckedChange = onCheckedChange)
}
