import SwiftUI

/// A Settings topic. Each page covers one topic; the hub shows a live summary of each.
enum SettingsPage: Hashable, CaseIterable {
    case tv, phonePlayer, subtitles, casting, history, browser, remote, about

    var title: String {
        switch self {
        case .tv: return "TV"
        case .phonePlayer: return "Phone player"
        case .subtitles: return "Audio & subtitles"
        case .casting: return "Casting"
        case .history: return "Cast history"
        case .browser: return "Browser"
        case .remote: return "Remote"
        case .about: return "About"
        }
    }

    var icon: String {
        switch self {
        case .tv: return "tv"
        case .phonePlayer: return "play.rectangle"
        case .subtitles: return "captions.bubble"
        case .casting: return "airplayvideo"
        case .history: return "clock.arrow.circlepath"
        case .browser: return "globe"
        case .remote: return "av.remote"
        case .about: return "info.circle"
        }
    }
}

/// Where the subtitle style is being edited from Audio & subtitles.
enum SubtitleSettingsTarget: Hashable { case phone, tv }

/// Settings, grouped by topic. Opens from the Dashboard and the browser menu; Back returns there.
struct SettingsScreen: View {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var store: BrowserStore
    /// Observed so the Audio & subtitles summary follows language changes.
    @AppStorage(PhonePlayerPreferences.key) private var playerPreferences: Data?
    @AppStorage(PlayOnPreferences.autoConnectKey) private var autoConnect = true
    @AppStorage(PhoneLandscapeOpening.key) private var landscapeOpening = PhoneLandscapeOpening.fullscreen.rawValue
    @AppStorage(PhoneLeaveChoice.key) private var leaveChoice = PhoneLeaveChoice.pause.rawValue
    @AppStorage(CastHistorySettings.enabledKey) private var saveHistory = true
    /// Observed so the Cast history summary follows the limit.
    @AppStorage(CastHistorySettings.limitKey) private var historyLimit = CastHistorySettings.defaultLimit
    @AppStorage("stream_route_default") private var route = StreamRoute.direct.rawValue
    @AppStorage(RemoteHapticPreferences.enabledKey) private var hapticsEnabled = true
    @AppStorage(RemoteHapticPreferences.levelKey) private var hapticsLevel: RemoteHapticLevel = .light
    @State private var engine = SearchEngine.current

    var body: some View {
        NavigationStack {
            List {
                ForEach(SettingsPage.allCases, id: \.self) { page in
                    NavigationLink(value: page) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(page.title).foregroundColor(Theme.onSurface)
                                Text(summary(page))
                                    .font(Theme.font(.caption))
                                    .foregroundColor(Theme.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                        } icon: {
                            Image(systemName: page.icon).foregroundColor(Theme.primary)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    // Settings opens from the Dashboard's top bar too; Back returns there.
                    if nav.settingsOrigin == .dashboard {
                        ScreenBackButton(destination: .dashboard, accessibilityLabel: "Back to Dashboard")
                    } else {
                        ScreenBackButton(destination: .browser, accessibilityLabel: "Back to Browser")
                    }
                }
            }
            .navigationDestination(for: SettingsPage.self) { page in
                Group {
                    switch page {
                    case .tv: TvSettingsPage()
                    case .phonePlayer: PhonePlayerSettingsPage()
                    case .history: CastHistorySettingsForm(store: vm.castHistory)
                    case .subtitles: AudioSubtitleSettingsPage(tv: vm.tvSubtitles)
                    case .casting: CastingSettingsPage()
                    case .browser: BrowserSettingsPage(engine: $engine)
                    case .remote: RemoteSettingsPage()
                    case .about: AboutSettingsPage()
                    }
                }
                .scrollContentBackground(.hidden)
                .background(Theme.surface.ignoresSafeArea())
                .navigationTitle(page.title)
                .navigationBarTitleDisplayMode(.inline)
            }
            .navigationDestination(for: SubtitleSettingsTarget.self) { target in
                Form {
                    switch target {
                    case .phone: PhoneSubtitleStyleSections()
                    case .tv: TvSubtitleStyleSections(controller: vm.tvSubtitles)
                    }
                }
                .scrollContentBackground(.hidden)
                .background(Theme.surface.ignoresSafeArea())
                .navigationTitle(target == .phone ? "Phone player subtitles" : "TV subtitles")
                .navigationBarTitleDisplayMode(.inline)
            }
        }
    }

    private func summary(_ page: SettingsPage) -> String {
        switch page {
        case .tv:
            return autoConnect ? "Auto-connect to the last TV" : "Auto-connect off"
        case .phonePlayer:
            let opening = (PhoneLandscapeOpening(rawValue: landscapeOpening) ?? .fullscreen).summary
            return PhoneLeaveChoice(rawValue: leaveChoice) == .keepPlaying ? opening + " · keeps playing" : opening
        case .subtitles:
            _ = playerPreferences
            return PhoneLanguageChoices.summary(PhonePlayerPreferences.load(from: .standard))
        case .history:
            _ = (historyLimit, saveHistory)
            let settings = CastHistorySettings.load()
            return settings.enabled ? "Keeps the newest \(settings.limit) casts" : "Off"
        case .casting:
            return "Videos play " + (StreamRoute(rawValue: route) ?? .direct).label.lowercased()
        case .browser:
            return [engine.label, store.adBlockEnabled ? "Ad blocking on" : "Ad blocking off"].joined(separator: " · ")
        case .remote:
            return hapticsEnabled ? "Haptics: \(hapticsLevel.title.lowercased())" : "Haptics off"
        case .about:
            return "Privacy policy, open source licences"
        }
    }
}

// MARK: - Pages

/// Connecting to TVs, their pairings and what is remembered about casts.
private struct TvSettingsPage: View {
    @EnvironmentObject private var atv: AtvRemoteCoordinator
    @AppStorage(PlayOnPreferences.autoConnectKey) private var autoConnect = true
    @State private var showPairedTVs = false

    var body: some View {
        Form {
            Section {
                Toggle("Auto-connect to this TV", isOn: $autoConnect).tint(Theme.primary)
            } footer: {
                Text("Reconnect to the last TV when PlayBridge opens.")
            }
            Section {
                Button("Paired TVs") { showPairedTVs = true }
            } footer: {
                Text("Android TVs this phone controls with full remote access.")
            }
        }
        .sheet(isPresented: $showPairedTVs) { AtvPairedTVsSheet(coordinator: atv) }
    }
}

/// The phone's own player: how it opens.
private struct PhonePlayerSettingsPage: View {
    @AppStorage(PhoneLandscapeOpening.key) private var landscapeOpening = PhoneLandscapeOpening.fullscreen.rawValue
    @AppStorage(PhoneAmbientMode.key) private var ambient = true
    @AppStorage(PhoneLeaveChoice.key) private var leave = PhoneLeaveChoice.pause.rawValue

    var body: some View {
        Form {
            Section {
                Picker("Landscape videos open in", selection: Binding(
                    get: { PhoneLandscapeOpening(rawValue: landscapeOpening) ?? .fullscreen },
                    set: { landscapeOpening = $0.rawValue })) {
                    ForEach(PhoneLandscapeOpening.allCases) { Text($0.label).tag($0) }
                }
                .accessibilityIdentifier("settings-landscape-opening")
            } footer: {
                Text("Fullscreen turns the player to landscape at once. Portrait first follows how you hold the phone: upright, the video sits on top with the controls below; turn the phone or tap Fullscreen for full screen. Vertical videos always open in portrait, and a website can ask for its own orientation.")
            }
            Section {
                Picker("When you leave the player", selection: Binding(
                    get: { PhoneLeaveChoice(rawValue: leave) ?? .pause },
                    set: { leave = $0.rawValue })) {
                    ForEach(PhoneLeaveChoice.allCases) { Text($0.label).tag($0) }
                }
                .accessibilityIdentifier("settings-leave-choice")
            } footer: {
                Text("Pause stops the video when you go back, go home or lock the phone. Keep playing leaves it playing: going back shows a bar to return to it, and going home or locking the phone keeps the sound going with controls on the lock screen. More in the player changes it for one video.")
            }
            Section {
                Toggle("Ambient mode", isOn: $ambient).tint(Theme.primary)
                    .accessibilityIdentifier("settings-ambient-mode")
            } footer: {
                Text("When the video sits on top with the controls below, a soft glow of its colours fills the space around it.")
            }
        }
    }
}

/// How subtitles look on this phone and on the connected TV.
private struct AudioSubtitleSettingsPage: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @ObservedObject var tv: TvSubtitleStyleController
    @ObservedObject private var phone = SubtitleStyleStore.shared
    @State private var languages = PhonePlayerPreferences.load(from: .standard)

    var body: some View {
        Form {
            Section {
                Picker("Preferred audio language", selection: Binding(get: { languages.audioLanguage ?? "" }, set: { code in
                    updateLanguages { $0.audioLanguage = code.isEmpty ? nil : code }
                })) {
                    Text("Source default").tag("")
                    languageChoices(including: languages.audioLanguage)
                }
                Picker("Preferred subtitle language", selection: Binding(get: {
                    languages.subtitlesEnabled ? (languages.subtitleLanguage ?? "first") : ""
                }, set: { value in
                    updateLanguages { $0.subtitlesEnabled = !value.isEmpty; $0.subtitleLanguage = value.isEmpty || value == "first" ? nil : value }
                })) {
                    Text("Off").tag("")
                    Text("First available").tag("first")
                    languageChoices(including: languages.subtitleLanguage)
                }
            } header: {
                Text("Languages")
            } footer: {
                Text("Used by the phone player, to tick subtitles in the cast sheet and when casting to a TV. Choosing a track in the player changes them too.")
            }
            Section {
                row("Phone player", SubtitleStyleLabels.summary(phone.style), .phone)
                row(vm.pairedDevice.map { "TV · \($0.name)" } ?? "TV", tvSummary, .tv)
            } header: {
                Text("Subtitle style")
            } footer: {
                Text("The phone player and your TV each keep their own style. The TV applies changes right away.")
            }
        }
    }

    private func updateLanguages(_ change: (inout PhonePlayerPreferences) -> Void) {
        var next = PhonePlayerPreferences.load(from: .standard)
        change(&next)
        next.save(to: .standard)
        languages = PhonePlayerPreferences.load(from: .standard)
    }

    private func languageChoices(including current: String?) -> some View {
        let codes = Set(PhoneLanguageChoices.common + [current].compactMap { $0 })
        return ForEach(codes.sorted { PhoneLanguageChoices.name($0) < PhoneLanguageChoices.name($1) }, id: \.self) { code in
            Text(PhoneLanguageChoices.name(code)).tag(code)
        }
    }

    private var tvSummary: String {
        guard vm.isConnected, vm.playBridgeLinkConnected else { return "Not connected" }
        guard vm.tvSupportsSubtitleStyle else { return "Needs a newer PlayBridge on the TV" }
        return tv.style(for: vm.subtitleStyleSession).map(SubtitleStyleLabels.summary) ?? "Change how the TV shows subtitles"
    }

    private func row(_ title: String, _ detail: String, _ target: SubtitleSettingsTarget) -> some View {
        NavigationLink(value: target) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(1)
                Text(detail).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
            }
        }
    }
}

/// How videos reach the TV, and what websites may do when they cast.
private struct CastingSettingsPage: View {
    @AppStorage("stream_route_default") private var route = StreamRoute.direct.rawValue
    @State private var proxy = StreamProxySettingsStore.load()
    @State private var showProxy = false
    @State private var selectProxyAfterSave = false
    @State private var showPermissions = false

    var body: some View {
        Form {
            Section {
                Picker("Send videos", selection: Binding(get: { StreamRoute(rawValue: route) ?? .direct }, set: choose)) {
                    ForEach(StreamRoute.allCases) { Text($0.label).tag($0) }
                }
                Button { selectProxyAfterSave = false; showProxy = true } label: {
                    HStack {
                        Text("Stream proxy").foregroundColor(Theme.onSurface)
                        Spacer()
                        Text(proxyHost ?? "Not set").foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                    }
                }
            } header: {
                Text("Route")
            } footer: {
                Text("Direct sends the video link to the TV. Via phone streams it through this phone, and Via proxy through your stream proxy, for links the TV can't open itself. The cast sheet can change it for a cast.")
            }
            Section {
                Button("Website casting permissions") { showPermissions = true }
            } footer: {
                Text("Websites allowed to cast, local media server access and how far ahead linked playlists are queued.")
            }
        }
        .sheet(isPresented: $showProxy) {
            StreamProxySettingsView(configuration: proxy) { configuration in
                proxy = configuration
                if selectProxyAfterSave { route = StreamRoute.proxy.rawValue }
            }
        }
        .sheet(isPresented: $showPermissions) { PageCastPermissionsView() }
    }

    /// Only the host is shown; the proxy URL and password stay out of the page.
    private var proxyHost: String? { (try? proxy.validatedURL())?.host }

    private func choose(_ next: StreamRoute) {
        if next == .proxy, proxyHost == nil {
            selectProxyAfterSave = true
            showProxy = true
        } else {
            route = next.rawValue
        }
    }
}

/// Search, ad blocking and what the browser detects, sends and keeps.
private struct BrowserSettingsPage: View {
    @EnvironmentObject private var store: BrowserStore
    @Binding var engine: SearchEngine
    @State private var showAdblock = false
    @State private var showMediaDetection = false
    @State private var showUserAgent = false
    @State private var showNetworkLogs = false
    @State private var showClearData = false
#if PB_DEVELOPER_TOOLS
    @AppStorage(BrowserDeveloperTools.key) private var developerTools = false
#endif

    var body: some View {
        Form {
            Section {
                Picker("Search engine", selection: $engine) {
                    ForEach(SearchEngine.allCases) { Text($0.label).tag($0) }
                }
                .onChange(of: engine) { SearchEngine.current = $0 }
                detailButton("Ad blocking & pop-ups", store.adBlockEnabled ? "On" : "Off") { showAdblock = true }
                Button("Media detection") { showMediaDetection = true }
            }
            Section {
                detailButton("User agent", store.activeTab?.userAgentPreset.label ?? "Automatic") { showUserAgent = true }
                    .disabled(store.activeTab == nil)
                Button("Network logs") { showNetworkLogs = true }
                    .disabled(store.activeTab == nil)
#if PB_DEVELOPER_TOOLS
                Toggle("Developer tools", isOn: $developerTools)
                    .tint(Theme.primary)
                    .accessibilityIdentifier("browserDeveloperTools")
#endif
            } header: {
                Text("Current tab")
            } footer: {
#if PB_DEVELOPER_TOOLS
                Text("Developer tools add JSON and Copy as cURL for detected media, and a full, unredacted copy of network logs. These can include cookies and tokens.")
#else
                Text("These apply to the tab that is open in the browser.")
#endif
            }
            Section {
                Button("Clear browsing data") { showClearData = true }
                    .foregroundColor(Theme.danger)
            }
        }
        .sheet(isPresented: $showAdblock) { AdblockSettingsSheet(store: store) }
        .sheet(isPresented: $showMediaDetection) { BrowserMediaDetectionSheet(store: store) }
        .sheet(isPresented: $showClearData) { BrowserClearDataSheet(store: store) }
        .sheet(isPresented: $showUserAgent) {
            if let tab = store.activeTab { BrowserUserAgentSheet(tab: tab) }
        }
        .sheet(isPresented: $showNetworkLogs) {
            if let tab = store.activeTab { BrowserNetworkLogView(tab: tab, store: store) }
        }
    }

    private func detailButton(_ title: String, _ detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundColor(Theme.onSurface)
                Spacer()
                Text(detail).foregroundColor(Theme.onSurfaceVariant)
            }
        }
    }
}

/// How the remote feels.
private struct RemoteSettingsPage: View {
    @AppStorage(RemoteHapticPreferences.enabledKey) private var hapticsEnabled = true
    @AppStorage(RemoteHapticPreferences.levelKey) private var hapticsLevel: RemoteHapticLevel = .light

    var body: some View {
        Form {
            Section {
                Toggle("Haptic feedback", isOn: $hapticsEnabled).tint(Theme.primary)
                Picker("Haptic level", selection: $hapticsLevel) {
                    ForEach(RemoteHapticLevel.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .disabled(!hapticsEnabled)
            } footer: {
                Text("A tap you can feel when you press the remote's buttons.")
            }
        }
    }
}

private struct AboutSettingsPage: View {
    @State private var showLicences = false

    var body: some View {
        Form {
            Section {
                Link("Privacy policy", destination: URL(string: "https://playbridge.app/privacy")!)
                Button("Open source licences") { showLicences = true }
            }
        }
        .sheet(isPresented: $showLicences) { OpenSourceLicencesScreen() }
    }
}
