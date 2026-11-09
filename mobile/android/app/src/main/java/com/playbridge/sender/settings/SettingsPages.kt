package com.playbridge.sender.settings

import androidx.compose.runtime.produceState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.playbridge.shared.player.SubtitleFontLibrary
import com.playbridge.sender.player.PhoneSubtitleFonts
import com.playbridge.shared.player.SubtitleStyle
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.playbridge.sender.browser.CustomUserAgent
import com.playbridge.sender.browser.MediaDetectionSheet
import com.playbridge.sender.browser.UserAgentPresets
import com.playbridge.sender.browser.UserAgentSheet
import com.playbridge.sender.cast.RemoteSettingsGroup
import com.playbridge.sender.cast.SubtitlePreferences
import com.playbridge.sender.cast.remote.RemotePresentationPreferenceStore
import com.playbridge.sender.data.settings.MediaDetectionSettings
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.player.PhoneLandscapeOpen
import com.playbridge.sender.player.PhoneLeaveAction
import com.playbridge.sender.player.PhonePlayerMode
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// ── Shared building blocks ──────────────────────────────────────────────────

/** A settings page: a back arrow, a title and a scrolling column of groups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun SettingsGroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private val transparentItem @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
internal fun SettingsSwitch(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = transparentItem,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** A row that opens another page or sheet. */
@Composable
internal fun SettingsLink(title: String, description: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = transparentItem,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A read-only field that opens a menu of [options] (label to value). */
@Composable
internal fun <T> SettingsDropdown(
    label: String,
    selected: T,
    options: List<Pair<String, T>>,
    fallback: String,
    description: String? = null,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            readOnly = true,
            value = options.find { it.second == selected }?.first ?: fallback,
            onValueChange = {},
            label = { Text(label) },
            supportingText = description?.let { { Text(it) } },
            trailingIcon = {
                Icon(if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.matchParentSize().clickable { expanded = !expanded })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.9f)) {
            options.forEach { (optionLabel, value) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

@Composable
private fun <T> SettingsRadioGroup(options: List<T>, selected: T, label: (T) -> String, description: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            ListItem(
                headlineContent = { Text(label(option)) },
                supportingContent = { Text(description(option)) },
                leadingContent = { RadioButton(selected = selected == option, onClick = null) },
                colors = transparentItem,
                modifier = Modifier.selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) }),
            )
        }
    }
}

// ── Pages ───────────────────────────────────────────────────────────────────

@Composable
fun PhonePlayerSettingsScreen(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val mode by settings.phonePlayerMode.collectAsState(initial = PhonePlayerMode.AUTOMATIC)
    val landscapeOpen by settings.phoneLandscapeOpen.collectAsState(initial = PhoneLandscapeOpen.FULLSCREEN)
    val leaveAction by settings.phoneLeaveAction.collectAsState(initial = PhoneLeaveAction.PAUSE)

    SettingsPage("Phone player", onBack) {
        SettingsGroupTitle("Player")
        SettingsRadioGroup(PhonePlayerMode.entries, mode, { it.label }, { it.description }) {
            scope.launch { settings.setPhonePlayerMode(it) }
        }
        SettingsGroupTitle("Landscape videos open in")
        SettingsRadioGroup(PhoneLandscapeOpen.entries, landscapeOpen, { it.label }, { it.description }) {
            scope.launch { settings.setPhoneLandscapeOpen(it) }
        }
        Text(
            "Vertical videos always open in portrait. Websites can still ask for an orientation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SettingsGroupTitle("When you leave the player")
        SettingsRadioGroup(PhoneLeaveAction.entries, leaveAction, { it.label }, { it.description }) {
            scope.launch { settings.setPhoneLeaveAction(it) }
        }
        Text(
            "Leaving means Back, Home or locking the phone. Each video's More menu can change it for that video; Picture-in-picture applies to videos opened after you choose it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
fun AudioSubtitleSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    // The TV reads these from browser_prefs, so both stores are written.
    val browserPrefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val audio by settings.preferredAudioLang.collectAsState(initial = "")
    val subtitles by settings.preferredSubtitleLang.collectAsState(initial = "")
    val phoneStyle by settings.phoneSubtitleStyle.collectAsState(initial = com.playbridge.shared.player.SubtitleStyle.DEFAULT)
    var phoneFontStatus by remember { mutableStateOf<String?>(null) }
    var phoneFontBusy by remember { mutableStateOf(false) }
    val phoneFonts by PhoneSubtitleFonts.families.collectAsState()
    val fontRevision by PhoneSubtitleFonts.revision.collectAsState()
    LaunchedEffect(Unit) { PhoneSubtitleFonts.load(context) }
    // The selected font's typeface for the preview; selecting it also links it in for mpv.
    val phoneTypeface by produceState<android.graphics.Typeface?>(null, phoneStyle.customFont, fontRevision) {
        value = PhoneSubtitleFonts.select(context, phoneStyle.customFont)
    }

    SettingsPage("Audio & subtitles", onBack) {
        Text(
            "Used by the phone player and when casting to a TV.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsDropdown("Preferred audio language", audio, SubtitlePreferences.audioLanguages, "Auto") { code ->
            scope.launch { settings.setPreferredAudioLang(code) }
            browserPrefs.edit { putString("preferred_audio_lang", code) }
        }
        SettingsDropdown("Preferred subtitle language", subtitles, SubtitlePreferences.subtitleLanguages, "None") { code ->
            scope.launch { settings.setPreferredSubtitleLang(code) }
            browserPrefs.edit { putString("preferred_subtitle_lang", code) }
        }

        SettingsGroupTitle("Phone player subtitles")
        SubtitleStyleEditor(
            phoneStyle,
            onChange = { scope.launch { settings.setPhoneSubtitleStyle(it) } },
            font = SubtitleFontControls(
                installed = phoneFonts,
                status = phoneFontStatus,
                busy = phoneFontBusy,
                typeface = phoneTypeface,
                onPick = { uri ->
                    phoneFontStatus = null
                    phoneFontBusy = true
                    scope.launch {
                        when (val picked = withContext(Dispatchers.IO) { readPickedFont(context, uri) }) {
                            is PickedFont.Rejected -> phoneFontStatus = picked.message
                            is PickedFont.Font -> when (val added = PhoneSubtitleFonts.add(context, picked.bytes)) {
                                is SubtitleFontLibrary.AddResult.Added -> settings.setPhoneSubtitleStyle(
                                    phoneStyle.copy(font = SubtitleStyle.Font.CUSTOM, customFont = added.family),
                                )
                                is SubtitleFontLibrary.AddResult.Failed -> phoneFontStatus = fontErrorMessage(added.code)
                            }
                        }
                        phoneFontBusy = false
                    }
                },
                onRemove = { family ->
                    phoneFontStatus = null
                    scope.launch {
                        PhoneSubtitleFonts.remove(context, family)
                        if (phoneStyle.customFont == family) {
                            settings.setPhoneSubtitleStyle(phoneStyle.copy(font = SubtitleStyle.Font.DEFAULT, customFont = null))
                        }
                    }
                },
            ),
        )
        Text(
            "Your TV keeps its own style: change it in Settings → TV or Play on → Configure TV.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CastingSettingsScreen(onBack: () -> Unit, onStreamProxy: () -> Unit, onWebsitePermissions: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val hideEmptyTabs by settings.hideEmptyCastSheetTabs.collectAsState(initial = true)
    val queueWindow by settings.tvPrefetchWindow.collectAsState(initial = 1)

    SettingsPage("Casting", onBack) {
        SettingsGroupTitle("Cast sheet")
        SettingsSwitch(
            "Hide empty Audio and Images tabs",
            "Videos and Subtitles stay available; newly detected media updates the tab order.",
            hideEmptyTabs,
        ) { scope.launch { settings.setHideEmptyCastSheetTabs(it) } }

        SettingsGroupTitle("Website queue")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Items requested ahead", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "How many upcoming episodes a website prepares while one plays.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { scope.launch { settings.setTvPrefetchWindow(queueWindow - 1) } }, enabled = queueWindow > 1) {
                Icon(Icons.Default.Remove, contentDescription = "Fewer items")
            }
            Text("$queueWindow")
            IconButton(onClick = { scope.launch { settings.setTvPrefetchWindow(queueWindow + 1) } }, enabled = queueWindow < 10) {
                Icon(Icons.Default.Add, contentDescription = "More items")
            }
        }

        SettingsGroupTitle("Routing and permissions")
        SettingsLink("Cast route & stream proxy", "Default cast route and remote stream-proxy URL", onStreamProxy)
        SettingsLink("Website casting permissions", "Review or reset websites allowed to start a cast", onWebsitePermissions)
    }
}

@Composable
fun BrowserSettingsScreen(onBack: () -> Unit, onPopupBlocker: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val maxAliveTabs by settings.maxAliveTabs.collectAsState(initial = 5)
    val userAgentPreset by settings.userAgentPreset.collectAsState(initial = UserAgentPresets.DEFAULT_ID)
    val customUserAgents by settings.customUserAgents.collectAsState(initial = emptyList())
    val mediaDetection by settings.mediaDetectionSettings.collectAsState(initial = MediaDetectionSettings())
    var showUserAgents by remember { mutableStateOf(false) }
    var showMediaDetection by remember { mutableStateOf(false) }

    SettingsPage("Browser", onBack) {
        SettingsGroupTitle("Websites")
        SettingsLink("Popup blocker", "Block popups with per-site exceptions", onPopupBlocker)
        SettingsLink(
            "User agent",
            if (userAgentPreset == UserAgentPresets.DEFAULT_ID) "Default" else "Custom identity in use",
        ) { showUserAgents = true }
        SettingsLink(
            "Media detection",
            if (mediaDetection.enabled) "Finds videos, audio and subtitles on pages" else "Off",
        ) { showMediaDetection = true }

        SettingsGroupTitle("Tabs")
        var maxTabs by remember { mutableFloatStateOf(5f) }
        LaunchedEffect(maxAliveTabs) { maxTabs = maxAliveTabs.toFloat() }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Open tab limit", style = MaterialTheme.typography.bodyLarge)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "${maxTabs.toInt()} tabs",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Slider(
                value = maxTabs,
                onValueChange = { maxTabs = it },
                onValueChangeFinished = { scope.launch { settings.setMaxAliveTabs(maxTabs.toInt()) } },
                valueRange = 1f..15f,
                steps = 13,
            )
            Text(
                "How many tabs stay loaded in memory. More switches faster but uses more memory, and can crash low-end phones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showUserAgents) {
        val sheetState = rememberModalBottomSheetState()
        UserAgentSheet(
            sheetState = sheetState,
            currentPresetId = userAgentPreset,
            customUserAgents = customUserAgents,
            onDismissRequest = { showUserAgents = false },
            onSelectPreset = { id ->
                scope.launch { settings.setUserAgentPreset(id) }
                showUserAgents = false
            },
            onAddCustom = { name, value ->
                val agent = CustomUserAgent(id = java.util.UUID.randomUUID().toString(), name = name, value = value)
                scope.launch {
                    settings.addCustomUserAgent(agent)
                    settings.setUserAgentPreset(UserAgentPresets.customSelectionId(agent.id))
                }
                showUserAgents = false
            },
            onDeleteCustom = { id ->
                scope.launch {
                    settings.removeCustomUserAgent(id)
                    if (userAgentPreset == UserAgentPresets.customSelectionId(id)) {
                        settings.setUserAgentPreset(UserAgentPresets.DEFAULT_ID)
                    }
                }
            },
        )
    }
    if (showMediaDetection) {
        MediaDetectionSheet(
            settings = mediaDetection,
            onSettingsChange = { scope.launch { settings.setMediaDetectionSettings(it) } },
            onDismissRequest = { showMediaDetection = false },
        )
    }
}

@Composable
fun RemoteSettingsScreen(onBack: () -> Unit) {
    val preferences: RemotePresentationPreferenceStore = koinInject()
    SettingsPage("Remote", onBack) {
        RemoteSettingsGroup(preferences, showTitle = false)
    }
}
