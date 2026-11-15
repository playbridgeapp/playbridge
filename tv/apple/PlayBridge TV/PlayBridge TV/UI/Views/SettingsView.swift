import SwiftUI

struct SettingsView: View {
    @Binding var selectedCategory: SettingsCategory
    let onBack: () -> Void

    @EnvironmentObject var server: WebSocketServer
    @EnvironmentObject var historyStore: HistoryStore
    @Environment(\.tvColors) private var colors
    @Environment(\.appRemoteDriver) private var appRemoteDriver

    @AppStorage("preferredPlayer") var preferredPlayer: String = PlaybackEngine.defaultEngine.rawValue
    @AppStorage("skip_segments_provider") var skipProvider: String = "both"
    @AppStorage("auto_skip_intro") var autoSkipIntro: Bool = false
    @AppStorage("auto_skip_recap") var autoSkipRecap: Bool = false
    @AppStorage("auto_skip_credits") var autoSkipCredits: Bool = false
    @AppStorage("enable_history") var enableHistory: Bool = true
    @AppStorage("still_watching_enabled") var stillWatchingEnabled: Bool = false
    @AppStorage("still_watching_threshold_min") var stillWatchingThreshold: Int = StillWatchingController.defaultThreshold
    @AppStorage("still_watching_response_sec") var stillWatchingResponseSeconds: Int = StillWatchingController.defaultResponseSeconds

    @ObservedObject private var styleStore = SubtitleStyleStore.shared

    @AppStorage("app_theme") var appThemeRaw: String = AppThemeMode.dark.rawValue
    @AppStorage("bookmark_thumbnails_enabled") var bookmarkThumbnailsEnabled: Bool = true

    @State private var showLicences = false
    @State private var showConnectPhone = false
    @State private var activeSubtitlePicker: SubtitlePickerKind?
    @State private var lastSubtitlePicker: SubtitlePickerKind?
    @State private var confirmClearHistory = false
    @State private var historyClearedNotice = false
    @State private var confirmForgetAll = false
    @State private var restartedNotice = false
    @State private var connectPhoneFocusID = AppFocusID()
    @State private var firstPairedPhoneFocusID = AppFocusID()
    @State private var forgetAllFocusID = AppFocusID()
    @State private var clearHistoryFocusID = AppFocusID()
    @State private var openLicencesFocusID = AppFocusID()
    @State private var playbackFirstFocusID = AppFocusID()
    @State private var subtitlesFirstFocusID = AppFocusID()
    @State private var subtitlesFontFocusID = AppFocusID()
    @State private var subtitlesBgFocusID = AppFocusID()
    @State private var subtitlesEdgeFocusID = AppFocusID()
    @State private var skipFirstFocusID = AppFocusID()
    @State private var advancedFirstFocusID = AppFocusID()
    @State private var themeFocusIDs: [AppThemeMode: AppFocusID] = Dictionary(
        uniqueKeysWithValues: AppThemeMode.allCases.map { ($0, AppFocusID()) }
    )
    @State private var categoryFocusIDs: [SettingsCategory: AppFocusID] = Dictionary(
        uniqueKeysWithValues: SettingsCategory.allCases.map { ($0, AppFocusID()) }
    )
    @State private var currentFocusID: AppFocusID?

    var body: some View {
        ZStack {
            colors.surface.ignoresSafeArea()

            HStack(alignment: .top, spacing: TvScale.pt(40)) {
                sidebar
                detailPane
            }
            .padding(.horizontal, TvSpacing.screenGutter)
            .padding(.top, TvSpacing.screenTop)
            .padding(.bottom, TvScale.pt(24))
            .disabled(showLicences || showConnectPhone || activeSubtitlePicker != nil)

            if showConnectPhone {
                ConnectAnotherPhoneModal {
                    showConnectPhone = false
                }
                .zIndex(20)
            }

            if let kind = activeSubtitlePicker {
                subtitlePickerSheet(for: kind)
                    .id(kind)
                    .zIndex(25)
            }
        }
        .fullScreenCover(isPresented: $showLicences) {
            OpenSourceLicencesScreen()
        }
        .onChange(of: activeSubtitlePicker) { _, next in
            if let next {
                lastSubtitlePicker = next
            } else if let prev = lastSubtitlePicker {
                let targetID: AppFocusID
                switch prev {
                case .size: targetID = subtitlesFirstFocusID
                case .font: targetID = subtitlesFontFocusID
                case .background: targetID = subtitlesBgFocusID
                case .edge: targetID = subtitlesEdgeFocusID
                }
                currentFocusID = targetID
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                    guard self.activeSubtitlePicker == nil else { return }
                    appRemoteDriver?.focus(targetID)
                }
            }
        }
        .appRemoteScope(
            back: handleBack,
            home: {
                showConnectPhone = false
                onBack()
            },
            initialFocus: categoryFocusIDs[selectedCategory],
            onFocusChange: handleFocusChange
        )
        .onExitCommand(perform: handleBack)
        .onAppear {
            if !StillWatchingController.allowedThresholds.contains(stillWatchingThreshold) {
                stillWatchingThreshold = StillWatchingController.defaultThreshold
            }
            if !StillWatchingController.allowedResponseSeconds.contains(stillWatchingResponseSeconds) {
                stillWatchingResponseSeconds = StillWatchingController.defaultResponseSeconds
            }
            if let initialID = categoryFocusIDs[selectedCategory] {
                currentFocusID = initialID
                DispatchQueue.main.async {
                    appRemoteDriver?.focus(initialID)
                }
            }
        }
        .onChange(of: showConnectPhone) { _, isShown in
            if !isShown {
                DispatchQueue.main.async { appRemoteDriver?.focus(connectPhoneFocusID) }
            }
        }
        .onChange(of: showLicences) { _, isShown in
            if !isShown {
                DispatchQueue.main.async { appRemoteDriver?.focus(openLicencesFocusID) }
            }
        }
        .onChange(of: confirmClearHistory) { _, isShown in
            if !isShown {
                DispatchQueue.main.async { appRemoteDriver?.focus(clearHistoryFocusID) }
            }
        }
        .onChange(of: confirmForgetAll) { _, isShown in
            if !isShown && selectedCategory == .phones {
                DispatchQueue.main.async { appRemoteDriver?.focus(connectPhoneFocusID) }
            }
        }
        .onChange(of: server.pairedDevicesList.map(\.id)) { _, _ in
            if selectedCategory == .phones && !confirmForgetAll {
                DispatchQueue.main.async { appRemoteDriver?.focus(connectPhoneFocusID) }
            }
        }
    }

    // MARK: - Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: TvScale.pt(22)) {
            Text("Settings")
                .font(TvText.screenTitle)
                .foregroundColor(colors.onSurface)

            VStack(alignment: .leading, spacing: TvScale.pt(6)) {
                ForEach(SettingsCategory.primaryCategories) { category in
                    sidebarButton(for: category)
                }
            }

            VStack(alignment: .leading, spacing: TvScale.pt(6)) {
                Text("For troubleshooting")
                    .font(TvText.small)
                    .foregroundColor(colors.onSurfaceMuted)
                    .padding(.horizontal, TvScale.pt(20))
                sidebarButton(for: .advanced)
            }
            .padding(.top, TvScale.pt(6))

            Spacer()
        }
        .frame(width: TvScale.pt(320), alignment: .leading)
        .focusSection()
        .appRemoteDirection { direction in
            guard direction == .right else { return false }
            focusDetailPane(for: selectedCategory)
            return true
        }
    }

    private func sidebarButton(for category: SettingsCategory) -> some View {
        SettingsSidebarRow(
            title: category.title,
            isSelected: selectedCategory == category,
            focusID: categoryFocusIDs[category],
            action: {
                selectedCategory = category
                focusDetailPane(for: category)
            }
        )
    }

    // MARK: - Detail Pane

    private var detailPane: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: TvScale.pt(10)) {
                switch selectedCategory {
                case .playback: playbackPane
                case .subtitles: subtitlesPane
                case .skip: skipPane
                case .phones: phonesPane
                case .appearance: appearancePane
                case .about: aboutPane
                case .advanced: advancedPane
                }
            }
            // Extra inset so focus rings are never clipped inside the ScrollView.
            .padding(.horizontal, TvScale.pt(12))
            .padding(.top, selectedCategory == .subtitles || selectedCategory == .advanced ? TvScale.pt(8) : TvScale.pt(66))
            .padding(.bottom, TvScale.pt(32))
        }
        .id(selectedCategory)
        .focusSection()
        .appRemoteScroll()
        .appRemoteDirection(handleDetailDirection)
    }

    private func handleFocusChange(_ newID: AppFocusID?) {
        if newID == nil, activeSubtitlePicker != nil || showConnectPhone || showLicences {
            return
        }
        let previousZone = currentFocusZone
        let wasSidebarButton = currentFocusID.map { categoryFocusIDs.values.contains($0) } ?? false
        let newCategory = newID.flatMap { id in
            categoryFocusIDs.first(where: { $0.value == id })?.key
        }
        if previousZone == .detail, let newCategory, newCategory != selectedCategory {
            if let activeID = categoryFocusIDs[selectedCategory] {
                currentFocusID = activeID
                DispatchQueue.main.async {
                    appRemoteDriver?.focus(activeID)
                }
            }
            return
        }
        if wasSidebarButton, newCategory == nil, let newID,
           let firstID = firstDetailFocusID(for: selectedCategory), newID != firstID {
            currentFocusID = firstID
            DispatchQueue.main.async {
                appRemoteDriver?.focus(firstID)
            }
            return
        }
        currentFocusID = newID
        if let newCategory {
            selectedCategory = SettingsFocusNavigation.categorySelectionOnSidebarFocus(
                focusedCategory: newCategory
            )
        }
    }

    private var currentFocusZone: SettingsFocusZone {
        let isCategory = currentFocusID.map { categoryFocusIDs.values.contains($0) } ?? false
        return SettingsFocusNavigation.zone(
            isCategoryID: isCategory,
            hasFocus: currentFocusID != nil
        )
    }

    private func handleBack() {
        switch SettingsFocusNavigation.backOutcome(
            showingConnectPhone: showConnectPhone || activeSubtitlePicker != nil,
            zone: currentFocusZone,
            selectedCategory: selectedCategory
        ) {
        case .closeOverlay:
            activeSubtitlePicker = nil
            showConnectPhone = false
        case .returnToSidebar(let category):
            if let id = categoryFocusIDs[category] {
                appRemoteDriver?.focus(id)
            }
        case .leaveSettings:
            onBack()
        }
    }

    private func firstDetailFocusID(for category: SettingsCategory) -> AppFocusID? {
        switch category {
        case .playback: return playbackFirstFocusID
        case .subtitles: return subtitlesFirstFocusID
        case .skip: return skipFirstFocusID
        case .phones: return server.pairedDevicesList.isEmpty ? connectPhoneFocusID : firstPairedPhoneFocusID
        case .appearance: return themeFocusIDs[.dark]
        case .about: return openLicencesFocusID
        case .advanced: return advancedFirstFocusID
        }
    }

    private func focusDetailPane(for category: SettingsCategory) {
        guard let targetID = firstDetailFocusID(for: category) else { return }
        DispatchQueue.main.async {
            appRemoteDriver?.focus(targetID)
        }
    }

    private func handleDetailDirection(_ direction: AppFocusDirection) -> Bool {
        guard direction == .left else { return false }
        if currentFocusID == themeFocusIDs[.light], let amoled = themeFocusIDs[.amoled] {
            appRemoteDriver?.focus(amoled)
            return true
        }
        if currentFocusID == themeFocusIDs[.amoled], let dark = themeFocusIDs[.dark] {
            appRemoteDriver?.focus(dark)
            return true
        }
        if currentFocusID == forgetAllFocusID {
            appRemoteDriver?.focus(connectPhoneFocusID)
            return true
        }
        if let sidebarID = categoryFocusIDs[selectedCategory] {
            appRemoteDriver?.focus(sidebarID)
            return true
        }
        return false
    }

    // MARK: - Playback

    @ViewBuilder
    private var playbackPane: some View {
        SettingsValueRow(
            title: "Video player",
            subtitle: preferredPlayer == "avplayer"
                ? "Native Apple player (AVPlayer)"
                : "MPV player (Recommended for MKV, ASS, and custom headers)",
            value: preferredPlayer == "avplayer" ? "AVPlayer" : "MPV",
            focusID: playbackFirstFocusID,
            action: {
                preferredPlayer = (preferredPlayer == "avplayer") ? "mpv" : "avplayer"
            }
        )

        SettingsToggleRow(
            title: "Ask \"Still watching?\"",
            subtitle: "Pauses a long run of episodes if nobody answers",
            isOn: $stillWatchingEnabled
        )

        if stillWatchingEnabled {
            SettingsValueRow(
                title: "Reminder interval",
                subtitle: "How long before asking if you're still watching",
                value: "\(stillWatchingThreshold) minutes",
                action: selectNextStillWatchingThreshold
            )

            SettingsValueRow(
                title: "Response time",
                subtitle: "How long to wait before pausing playback",
                value: stillWatchingResponseTimeLabel,
                action: selectNextStillWatchingResponseTime
            )
        }

        SettingsToggleRow(
            title: "Save watch history",
            subtitle: "Keeps track of what you've watched on this TV",
            isOn: $enableHistory
        )

        SettingsActionRow(
            title: "Clear watch history",
            subtitle: historyClearedNotice
                ? "Watch history cleared. Bookmarks were kept."
                : "Removes watch history while keeping your bookmarks",
            trailingLabel: historyClearedNotice ? "Cleared" : nil,
            focusID: clearHistoryFocusID,
            action: { confirmClearHistory = true }
        )
        .appRemoteConfirmation(
            "Clear watch history?",
            isPresented: $confirmClearHistory,
            message: "Your bookmarks will stay.",
            confirm: "Clear History"
        ) {
            historyStore.clearHistory()
            historyClearedNotice = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                historyClearedNotice = false
            }
        }
    }

    // MARK: - Subtitles

    private enum SubtitlePickerKind: String, Identifiable {
        case size, font, background, edge
        var id: String { rawValue }
    }

    private var subtitleStyle: SubtitleStylePreferences {
        styleStore.style
    }

    private var selectedFontPickerValue: String {
        if subtitleStyle.font == .custom, let custom = subtitleStyle.customFont {
            return "custom:\(custom)"
        }
        return subtitleStyle.font.rawValue
    }

    private var fontPickerOptions: [(String, String)] {
        let builtIn = SubtitleFontOption.builtInCases.map { ($0.rawValue, $0.title) }
        let custom = styleStore.installedFonts.map { ("custom:\($0)", $0) }
        return builtIn + custom
    }

    @ViewBuilder
    private var subtitlesPane: some View {
        subtitlePreviewBox

        SettingsValueRow(
            title: "Size",
            value: subtitleStyle.size.title,
            focusID: subtitlesFirstFocusID,
            action: { activeSubtitlePicker = .size }
        )

        SettingsValueRow(
            title: "Font",
            subtitle: "Characters a font lacks use the TV's own fonts.",
            value: subtitleStyle.displayFontTitle,
            focusID: subtitlesFontFocusID,
            action: { activeSubtitlePicker = .font }
        )

        SettingsValueRow(
            title: "Background",
            subtitle: "A box behind subtitles keeps them readable on bright scenes.",
            value: subtitleStyle.background.title,
            focusID: subtitlesBgFocusID,
            action: { activeSubtitlePicker = .background }
        )

        SettingsValueRow(
            title: "Edge",
            subtitle: "The dark edge around subtitle letters.",
            value: subtitleStyle.edge.title,
            focusID: subtitlesEdgeFocusID,
            action: { activeSubtitlePicker = .edge }
        )

        SettingsToggleRow(
            title: "Use this style on pre-styled subtitles too",
            subtitle: "Some subtitles (such as ASS) bring their own look. Off keeps it.",
            isOn: Binding(
                get: { styleStore.style.overrideStyled },
                set: { newValue in
                    var next = styleStore.style
                    next.overrideStyled = newValue
                    styleStore.setStyle(next)
                }
            )
        )

        Text("You can also change these, and add your own fonts, from a connected phone.")
            .font(TvText.caption)
            .foregroundColor(colors.onSurfaceMuted)
            .padding(.top, TvScale.pt(6))
    }

    @ViewBuilder
    private func subtitlePickerSheet(for kind: SubtitlePickerKind) -> some View {
        switch kind {
        case .size:
            SubtitleOptionPickerSheet(
                title: "Size",
                options: SubtitleSizeOption.allCases.map { ($0.rawValue, $0.title) },
                selected: subtitleStyle.size.rawValue,
                onSelect: { value in
                    if let size = SubtitleSizeOption(rawValue: value) {
                        var next = styleStore.style
                        next.size = size
                        styleStore.setStyle(next)
                    }
                },
                onDismiss: { activeSubtitlePicker = nil }
            )
        case .font:
            SubtitleOptionPickerSheet(
                title: "Font",
                options: fontPickerOptions,
                selected: selectedFontPickerValue,
                onSelect: { value in
                    var next = styleStore.style
                    if value.hasPrefix("custom:") {
                        next.font = .custom
                        next.customFont = String(value.dropFirst("custom:".count))
                    } else if let option = SubtitleFontOption(rawValue: value), option != .custom {
                        next.font = option
                        next.customFont = nil
                    }
                    styleStore.setStyle(next)
                },
                onDismiss: { activeSubtitlePicker = nil }
            )
        case .background:
            SubtitleOptionPickerSheet(
                title: "Background",
                options: SubtitleBackgroundOption.allCases.map { ($0.rawValue, $0.title) },
                selected: subtitleStyle.background.rawValue,
                onSelect: { value in
                    if let bg = SubtitleBackgroundOption(storageValue: value) {
                        var next = styleStore.style
                        next.background = bg
                        styleStore.setStyle(next)
                    }
                },
                onDismiss: { activeSubtitlePicker = nil }
            )
        case .edge:
            SubtitleOptionPickerSheet(
                title: "Edge",
                options: SubtitleEdgeOption.allCases.map { ($0.rawValue, $0.title) },
                selected: subtitleStyle.edge.rawValue,
                onSelect: { value in
                    if let edge = SubtitleEdgeOption(storageValue: value) {
                        var next = styleStore.style
                        next.edge = edge
                        styleStore.setStyle(next)
                    }
                },
                onDismiss: { activeSubtitlePicker = nil }
            )
        }
    }

    private func previewSubtitleFont(size: CGFloat) -> Font {
        switch subtitleStyle.font {
        case .default:
            return .system(size: size, weight: .semibold, design: .default)
        case .sans:
            return .custom("HelveticaNeue-Medium", size: size)
        case .serif:
            return .custom("TimesNewRomanPS-BoldMT", size: size)
        case .mono:
            return .custom("Menlo-Bold", size: size)
        case .custom:
            if let family = subtitleStyle.customFont,
               let uiFont = styleStore.fontLibrary.uiFont(family: family, size: size) {
                return Font(uiFont as CTFont)
            }
            return .system(size: size, weight: .semibold, design: .default)
        }
    }

    @ViewBuilder
    private func previewSubtitleText(_ sample: String, font: Font) -> some View {
        let base = Text(sample)
            .font(font)
            .multilineTextAlignment(.center)
        if subtitleStyle.edge == .outline {
            let d: CGFloat = TvScale.pt(1.6)
            let offsets: [(CGFloat, CGFloat)] = [
                (-d, 0), (d, 0), (0, -d), (0, d),
                (-d * 0.75, -d * 0.75), (d * 0.75, -d * 0.75),
                (-d * 0.75, d * 0.75), (d * 0.75, d * 0.75),
            ]
            ZStack {
                ForEach(Array(offsets.enumerated()), id: \.offset) { _, delta in
                    base
                        .foregroundColor(.black)
                        .offset(x: delta.0, y: delta.1)
                }
                base.foregroundColor(.white)
            }
        } else {
            base
                .foregroundColor(.white)
                .shadow(
                    color: subtitleStyle.edge == .dropShadow ? .black.opacity(0.88) : .clear,
                    radius: subtitleStyle.edge == .dropShadow ? 3 : 0,
                    x: subtitleStyle.edge == .dropShadow ? 2 : 0,
                    y: subtitleStyle.edge == .dropShadow ? 3 : 0
                )
        }
    }

    private var subtitlePreviewBox: some View {
        let previewFontSize = TvScale.pt(subtitleStyle.previewFontSizePoints)
        let font = previewSubtitleFont(size: previewFontSize)
        let bgOpacity = subtitleStyle.previewBackgroundOpacity
        let sceneGradient = LinearGradient(
            colors: [
                Color(red: 244 / 255, green: 227 / 255, blue: 177 / 255),
                Color(red: 122 / 255, green: 156 / 255, blue: 198 / 255),
                Color(red: 34 / 255, green: 48 / 255, blue: 63 / 255),
            ],
            startPoint: .leading,
            endPoint: .trailing
        )

        return ZStack(alignment: .bottom) {
            sceneGradient
                .frame(height: TvScale.pt(190))
                .overlay(alignment: .topLeading) {
                    Text("Preview updates as you change things")
                        .font(TvText.micro)
                        .foregroundColor(.white)
                        .padding(.horizontal, TvScale.pt(12))
                        .padding(.vertical, TvScale.pt(5))
                        .background(Color.black.opacity(0.48))
                        .clipShape(Capsule())
                        .padding(.leading, TvScale.pt(16))
                        .padding(.top, TvScale.pt(12))
                }

            previewSubtitleText("Subtitles look like this\nAcross bright and dark scenes", font: font)
                .padding(.horizontal, TvScale.pt(14))
                .padding(.vertical, TvScale.pt(6))
                .background(Color.black.opacity(bgOpacity))
                .clipShape(RoundedRectangle(cornerRadius: TvScale.pt(6)))
                .padding(.horizontal, TvScale.pt(16))
                .padding(.bottom, TvScale.pt(18))
        }
        .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))
        .overlay(
            RoundedRectangle(cornerRadius: TvRadius.option)
                .stroke(colors.outline.opacity(0.6), lineWidth: 1)
        )
    }

    // MARK: - Skip intros and recaps

    @ViewBuilder
    private var skipPane: some View {
        SettingsToggleRow(
            title: "Skip intros by themselves",
            subtitle: "When off, a Skip intro button appears and you choose",
            isOn: $autoSkipIntro,
            focusID: skipFirstFocusID
        )

        SettingsToggleRow(
            title: "Skip recaps by themselves",
            subtitle: "The \"previously on\" part at the start of an episode",
            isOn: $autoSkipRecap
        )

        SettingsToggleRow(
            title: "Skip end credits by themselves",
            subtitle: "Goes straight to the next episode in a queue",
            isOn: $autoSkipCredits
        )

        SettingsValueRow(
            title: "Where skip times come from",
            subtitle: "A community database of intro and recap times",
            value: (SkipSegmentProvider(rawValue: skipProvider) ?? .both).title,
            action: selectNextSkipProvider
        )
    }

    // MARK: - Phones

    @ViewBuilder
    private var phonesPane: some View {
        if server.pairedDevicesList.isEmpty {
            HStack {
                Text("No paired phones yet.")
                    .font(TvText.settingTitle)
                    .foregroundColor(colors.onSurfaceMuted)
                Spacer()
            }
            .padding(.horizontal, TvScale.pt(26))
            .frame(minHeight: TvSize.settingsRow)
            .background(colors.container)
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))
        } else {
            ForEach(Array(server.pairedDevicesList.enumerated()), id: \.element.id) { index, device in
                PhoneDeviceRow(
                    device: device,
                    isConnectedNow: server.connectedDeviceNames.contains(device.deviceName),
                    forgetFocusID: index == 0 ? firstPairedPhoneFocusID : nil,
                    onForget: { server.forgetDevice(device) }
                )
            }
        }

        HStack(spacing: TvScale.pt(12)) {
            TvPillButton(
                title: "Connect another phone",
                icon: "plus",
                tone: .app,
                focusID: connectPhoneFocusID,
                action: { showConnectPhone = true }
            )

            if !server.pairedDevicesList.isEmpty {
                TvPillButton(
                    title: "Forget all",
                    tone: .app,
                    focusID: forgetAllFocusID,
                    action: { confirmForgetAll = true }
                )
                .appRemoteConfirmation(
                    "Remove all paired devices?",
                    isPresented: $confirmForgetAll,
                    message: "Each phone will need a new pairing code to connect again.",
                    confirm: "Remove All"
                ) {
                    server.forgetAllDevices()
                }
            }
        }
        .padding(.top, TvScale.pt(14))

        Text("Forgetting a phone disconnects it from this TV. Bookmarks stay.")
            .font(TvText.caption)
            .foregroundColor(colors.onSurfaceMuted)
            .padding(.top, TvScale.pt(6))
    }

    // MARK: - Appearance

    private var currentThemeMode: AppThemeMode {
        AppThemeMode.fromStorage(appThemeRaw)
    }

    @ViewBuilder
    private var appearancePane: some View {
        Text("Theme")
            .font(.system(size: TvScale.pt(18), weight: .semibold))
            .foregroundColor(colors.onSurface)
            .padding(.top, TvScale.pt(10))

        HStack(spacing: TvScale.pt(16)) {
            ForEach(AppThemeMode.allCases) { mode in
                ThemeOptionCard(
                    mode: mode,
                    isSelected: currentThemeMode == mode,
                    focusID: themeFocusIDs[mode],
                    action: { appThemeRaw = mode.rawValue }
                )
            }
        }
        .padding(.bottom, TvScale.pt(8))

        SettingsValueRow(
            title: "TV name",
            subtitle: "What phones see in their list. Rename it in Apple TV Settings",
            value: server.deviceName,
            action: {}
        )

        SettingsToggleRow(
            title: "Bookmark pictures",
            subtitle: "Use a frame from where you stopped when there's no artwork",
            isOn: $bookmarkThumbnailsEnabled
        )
    }

    // MARK: - About

    private var appVersionLabel: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }

    @ViewBuilder
    private var aboutPane: some View {
        HStack(spacing: TvScale.pt(18)) {
            RoundedRectangle(cornerRadius: TvScale.pt(14))
                .fill(colors.accent)
                .frame(width: TvScale.pt(56), height: TvScale.pt(56))
                .overlay(
                    Image(systemName: "play.fill")
                        .font(.system(size: TvScale.pt(26), weight: .bold))
                        .foregroundColor(colors.onAccent)
                )

            VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                Text("PlayBridge TV \(appVersionLabel)")
                    .font(.system(size: TvScale.pt(22), weight: .semibold))
                    .foregroundColor(colors.onSurface)
                Text("Apple TV Receiver")
                    .font(TvText.caption)
                    .foregroundColor(colors.onSurfaceMuted)
            }
            Spacer()
        }
        .padding(.horizontal, TvScale.pt(26))
        .frame(minHeight: TvScale.pt(110))
        .background(colors.container)
        .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))

        SettingsActionRow(
            title: "Open-source licences",
            trailingChevron: true,
            focusID: openLicencesFocusID,
            action: { showLicences = true }
        )

        SettingsValueRow(
            title: "Privacy",
            value: "playbridge.app/privacy",
            action: {}
        )
    }

    // MARK: - Advanced

    private var addressLabel: String {
        if let port = server.wssPort {
            return "\(server.localIP):\(port)"
        }
        return server.localIP
    }

    @ViewBuilder
    private var advancedPane: some View {
        Text("Most people never need these.")
            .font(TvText.caption)
            .foregroundColor(colors.onSurfaceMuted)
            .padding(.horizontal, TvScale.pt(6))
            .padding(.bottom, TvScale.pt(4))

        SettingsValueRow(
            title: "This TV's address",
            subtitle: "Status: \(server.serverState)",
            value: addressLabel,
            focusID: advancedFirstFocusID,
            action: {}
        )

        SettingsActionRow(
            title: "Restart the connection",
            subtitle: restartedNotice
                ? "Connection restarted. Phones will reconnect shortly."
                : "Phones reconnect by themselves in a few seconds",
            trailingLabel: restartedNotice ? "Restarted" : nil,
            action: {
                server.restart()
                restartedNotice = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                    restartedNotice = false
                }
            }
        )
    }

    // MARK: - Helpers

    private func selectNextSkipProvider() {
        let values = SkipSegmentProvider.allCases
        let index = values.firstIndex { $0.rawValue == skipProvider } ?? 0
        skipProvider = values[(index + 1) % values.count].rawValue
    }

    private func selectNextStillWatchingThreshold() {
        let values = StillWatchingController.allowedThresholds
        guard let index = values.firstIndex(of: stillWatchingThreshold) else {
            stillWatchingThreshold = StillWatchingController.defaultThreshold
            return
        }
        stillWatchingThreshold = values[(index + 1) % values.count]
    }

    private var stillWatchingResponseTimeLabel: String {
        stillWatchingResponseSeconds < 60
            ? "\(stillWatchingResponseSeconds) seconds"
            : "\(stillWatchingResponseSeconds / 60) \(stillWatchingResponseSeconds == 60 ? "minute" : "minutes")"
    }

    private func selectNextStillWatchingResponseTime() {
        let values = StillWatchingController.allowedResponseSeconds
        guard let index = values.firstIndex(of: stillWatchingResponseSeconds) else {
            stillWatchingResponseSeconds = StillWatchingController.defaultResponseSeconds
            return
        }
        stillWatchingResponseSeconds = values[(index + 1) % values.count]
    }
}

// MARK: - Settings Row Components

private struct SettingsSidebarRow: View {
    let title: String
    let isSelected: Bool
    var focusID: AppFocusID? = nil
    var onFocus: () -> Void = {}
    let action: () -> Void

    @Environment(\.tvColors) private var colors
    @State private var isFocused = false

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title)
                    .font(isSelected ? TvText.rowSelected : TvText.row)
                    .foregroundColor(
                        isFocused ? colors.onFocusFill :
                            (isSelected ? colors.onAccentContainer : colors.onSurfaceMuted)
                    )
                Spacer()
            }
            .padding(.horizontal, TvScale.pt(20))
            .frame(height: TvSize.navRow)
            .background(
                isFocused ? colors.focusFill :
                    (isSelected ? colors.accentContainer : colors.surface)
            )
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.row))
            .overlay(
                RoundedRectangle(cornerRadius: TvRadius.row + TvScale.pt(3))
                    .stroke(isFocused ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                    .padding(-TvSize.focusRingGap)
            )
        }
        .buttonStyle(TvNoChromeButtonStyle())
        .focusEffectDisabled()
        .appRemoteAction(action, id: focusID, focusChanged: { focused in
            isFocused = focused
            if focused { onFocus() }
        })
    }
}

private struct SettingsValueRow: View {
    let title: String
    var subtitle: String? = nil
    let value: String
    var focusID: AppFocusID? = nil
    let action: () -> Void

    @Environment(\.tvColors) private var colors
    @State private var isFocused = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: TvScale.pt(24)) {
                VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                    Text(title)
                        .font(TvText.settingTitle)
                        .foregroundColor(isFocused ? colors.onFocusFill : colors.onSurface)
                    if let subtitle {
                        Text(subtitle)
                            .font(TvText.caption)
                            .foregroundColor(isFocused ? colors.onFocusFill.opacity(0.75) : colors.onSurfaceMuted)
                    }
                }
                Spacer()
                Text(value)
                    .font(.system(size: TvScale.pt(19), weight: .semibold))
                    .foregroundColor(isFocused ? colors.onFocusFill : colors.accent)
            }
            .padding(.horizontal, TvScale.pt(26))
            .padding(.vertical, TvScale.pt(16))
            .frame(minHeight: TvSize.settingsRow)
            .background(isFocused ? colors.focusFill : colors.container)
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))
            .overlay(
                RoundedRectangle(cornerRadius: TvRadius.option + TvScale.pt(3))
                    .stroke(isFocused ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                    .padding(-TvSize.focusRingGap)
            )
        }
        .buttonStyle(TvNoChromeButtonStyle())
        .focusEffectDisabled()
        .appRemoteAction(action, id: focusID, focusChanged: { isFocused = $0 })
    }
}

private struct SettingsToggleRow: View {
    let title: String
    var subtitle: String? = nil
    @Binding var isOn: Bool
    var focusID: AppFocusID? = nil

    @Environment(\.tvColors) private var colors
    @State private var isFocused = false

    var body: some View {
        Button(action: { isOn.toggle() }) {
            HStack(spacing: TvScale.pt(24)) {
                VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                    Text(title)
                        .font(TvText.settingTitle)
                        .foregroundColor(isFocused ? colors.onFocusFill : colors.onSurface)
                    if let subtitle {
                        Text(subtitle)
                            .font(TvText.caption)
                            .foregroundColor(isFocused ? colors.onFocusFill.opacity(0.75) : colors.onSurfaceMuted)
                    }
                }
                Spacer()
                ZStack(alignment: isOn ? .trailing : .leading) {
                    Capsule()
                        .fill(isOn ? colors.accent : colors.outline)
                        .frame(width: TvScale.pt(56), height: TvScale.pt(32))
                    Circle()
                        .fill(isOn ? colors.onAccent : colors.onSurface)
                        .frame(width: TvScale.pt(24), height: TvScale.pt(24))
                        .padding(TvScale.pt(4))
                }
            }
            .padding(.horizontal, TvScale.pt(26))
            .padding(.vertical, TvScale.pt(16))
            .frame(minHeight: TvSize.settingsRow)
            .background(isFocused ? colors.focusFill : colors.container)
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))
            .overlay(
                RoundedRectangle(cornerRadius: TvRadius.option + TvScale.pt(3))
                    .stroke(isFocused ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                    .padding(-TvSize.focusRingGap)
            )
        }
        .buttonStyle(TvNoChromeButtonStyle())
        .focusEffectDisabled()
        .appRemoteAction({ isOn.toggle() }, id: focusID, focusChanged: { isFocused = $0 })
    }
}

private struct SettingsActionRow: View {
    let title: String
    var subtitle: String? = nil
    var trailingLabel: String? = nil
    var trailingChevron: Bool = false
    var focusID: AppFocusID? = nil
    let action: () -> Void

    @Environment(\.tvColors) private var colors
    @State private var isFocused = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: TvScale.pt(24)) {
                VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                    Text(title)
                        .font(TvText.settingTitle)
                        .foregroundColor(isFocused ? colors.onFocusFill : colors.onSurface)
                    if let subtitle {
                        Text(subtitle)
                            .font(TvText.caption)
                            .foregroundColor(isFocused ? colors.onFocusFill.opacity(0.75) : colors.onSurfaceMuted)
                    }
                }
                Spacer()
                if let trailingLabel {
                    Text(trailingLabel)
                        .font(.system(size: TvScale.pt(18), weight: .semibold))
                        .foregroundColor(isFocused ? colors.onFocusFill : colors.accent)
                } else if trailingChevron {
                    Image(systemName: "chevron.right")
                        .font(.system(size: TvScale.pt(18), weight: .semibold))
                        .foregroundColor(isFocused ? colors.onFocusFill : colors.onSurfaceMuted)
                }
            }
            .padding(.horizontal, TvScale.pt(26))
            .padding(.vertical, TvScale.pt(16))
            .frame(minHeight: TvScale.pt(72))
            .background(isFocused ? colors.focusFill : colors.container)
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))
            .overlay(
                RoundedRectangle(cornerRadius: TvRadius.option + TvScale.pt(3))
                    .stroke(isFocused ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                    .padding(-TvSize.focusRingGap)
            )
        }
        .buttonStyle(TvNoChromeButtonStyle())
        .focusEffectDisabled()
        .appRemoteAction(action, id: focusID, focusChanged: { isFocused = $0 })
    }
}

private struct PhoneDeviceRow: View {
    let device: PairedDevice
    let isConnectedNow: Bool
    var forgetFocusID: AppFocusID? = nil
    let onForget: () -> Void

    @Environment(\.tvColors) private var colors

    private var statusText: String {
        if isConnectedNow { return "Connected now" }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return "Last connected \(formatter.string(from: device.lastConnected))"
    }

    var body: some View {
        HStack(spacing: TvScale.pt(18)) {
            Circle()
                .fill(isConnectedNow ? colors.accentContainer : colors.containerHigh)
                .frame(width: TvScale.pt(48), height: TvScale.pt(48))
                .overlay(
                    Image(systemName: "iphone")
                        .font(.system(size: TvScale.pt(22), weight: .semibold))
                        .foregroundColor(isConnectedNow ? colors.onAccentContainer : colors.onSurfaceMuted)
                )

            VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                Text(device.deviceName)
                    .font(TvText.settingTitle)
                    .foregroundColor(colors.onSurface)
                Text(statusText)
                    .font(TvText.caption)
                    .foregroundColor(isConnectedNow ? colors.accent : colors.onSurfaceMuted)
            }

            Spacer()

            TvPillButton(
                title: "Forget",
                tone: .app,
                height: TvSize.pillHeightCompact,
                focusID: forgetFocusID,
                action: onForget
            )
        }
        .padding(.horizontal, TvScale.pt(26))
        .frame(height: TvScale.pt(88))
        .background(colors.container)
        .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))
    }
}

private struct ThemeOptionCard: View {
    let mode: AppThemeMode
    let isSelected: Bool
    var focusID: AppFocusID? = nil
    let action: () -> Void

    @Environment(\.tvColors) private var colors
    @State private var isFocused = false

    private var previewColors: TvColors {
        switch mode {
        case .dark: return .dark
        case .amoled: return .amoled
        case .light: return .light
        }
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: TvScale.pt(12)) {
                ZStack(alignment: .bottomLeading) {
                    previewColors.surface
                    HStack(spacing: TvScale.pt(8)) {
                        RoundedRectangle(cornerRadius: TvScale.pt(6))
                            .fill(previewColors.container)
                            .frame(width: TvScale.pt(70), height: TvScale.pt(40))
                        RoundedRectangle(cornerRadius: TvScale.pt(6))
                            .fill(previewColors.accent)
                            .frame(width: TvScale.pt(70), height: TvScale.pt(40))
                    }
                    .padding(TvScale.pt(12))
                }
                .frame(height: TvScale.pt(110))
                .clipShape(RoundedRectangle(cornerRadius: TvScale.pt(10)))

                HStack {
                    Text(mode.title)
                        .font(.system(size: TvScale.pt(19), weight: isSelected ? .semibold : .medium))
                        .foregroundColor(isFocused ? colors.onFocusFill : colors.onSurface)
                    Spacer()
                    if isSelected {
                        Text("In use")
                            .font(TvText.small)
                            .foregroundColor(isFocused ? colors.onFocusFill : colors.accent)
                    }
                }
            }
            .padding(TvScale.pt(14))
            .background(isFocused ? colors.focusFill : (isSelected ? colors.containerHighest : colors.container))
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.option))
            .overlay(
                RoundedRectangle(cornerRadius: TvRadius.option + TvScale.pt(3))
                    .stroke(isFocused ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                    .padding(-TvSize.focusRingGap)
            )
        }
        .buttonStyle(TvNoChromeButtonStyle())
        .focusEffectDisabled()
        .appRemoteAction(action, id: focusID, focusChanged: { isFocused = $0 })
    }
}

private struct SubtitleOptionPickerSheet: View {
    let title: String
    let options: [(value: String, title: String)]
    let selected: String
    let onSelect: (String) -> Void
    let onDismiss: () -> Void

    @Environment(\.tvColors) private var colors
    @Environment(\.appRemoteDriver) private var appRemoteDriver
    @Namespace private var pickerFocusScope
    @State private var focusIDs: [String: AppFocusID]

    init(
        title: String,
        options: [(value: String, title: String)],
        selected: String,
        onSelect: @escaping (String) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.title = title
        self.options = options
        self.selected = selected
        self.onSelect = onSelect
        self.onDismiss = onDismiss
        _focusIDs = State(initialValue: Dictionary(
            uniqueKeysWithValues: options.map { ($0.value, AppFocusID()) }
        ))
    }

    private var initialFocusID: AppFocusID? {
        focusIDs[selected] ?? options.first.flatMap { focusIDs[$0.value] }
    }

    var body: some View {
        ZStack {
            colors.surface.opacity(0.92).ignoresSafeArea()

            VStack(alignment: .leading, spacing: TvScale.pt(14)) {
                HStack {
                    Text(title)
                        .font(TvText.panelTitle)
                        .foregroundColor(colors.onSurface)
                    Spacer()
                }
                .padding(.bottom, TvScale.pt(4))

                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: TvScale.pt(8)) {
                        ForEach(options, id: \.value) { option in
                            TvListRow(
                                title: option.title,
                                selected: option.value == selected,
                                showCheckmark: true,
                                tone: .app,
                                focusID: focusIDs[option.value],
                                action: {
                                    onSelect(option.value)
                                    onDismiss()
                                }
                            )
                            .prefersDefaultFocus(option.value == selected, in: pickerFocusScope)
                        }
                    }
                    .padding(TvScale.pt(6))
                }
                .frame(maxHeight: TvScale.pt(480))
            }
            .padding(TvScale.pt(28))
            .frame(width: TvScale.pt(560))
            .background(colors.container)
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.card))
            .overlay(
                RoundedRectangle(cornerRadius: TvRadius.card)
                    .stroke(colors.outline, lineWidth: 1)
            )
            .focusSection()
            .focusScope(pickerFocusScope)
        }
        .appRemoteScope(
            back: onDismiss,
            home: onDismiss,
            initialFocus: initialFocusID
        )
        .onAppear {
            if let initialFocusID {
                DispatchQueue.main.async {
                    appRemoteDriver?.focus(initialFocusID)
                }
            }
        }
    }
}
