import SwiftUI

struct SettingsView: View {
    private enum FocusedSetting: Hashable { case skipProvider, reminderInterval, responseTime }

    @EnvironmentObject var server: WebSocketServer
    @AppStorage("preferredPlayer") var preferredPlayer: String = PlaybackEngine.defaultEngine.rawValue
    @AppStorage("skip_segments_provider") var skipProvider: String = "both"
    @AppStorage("enable_history") var enableHistory: Bool = true
    @AppStorage("still_watching_enabled") var stillWatchingEnabled: Bool = false
    @AppStorage("still_watching_threshold_min") var stillWatchingThreshold: Int = StillWatchingController.defaultThreshold
    @AppStorage("still_watching_response_sec") var stillWatchingResponseSeconds: Int = StillWatchingController.defaultResponseSeconds
    @State private var focusedSetting: FocusedSetting?
    @State private var showLicences = false

    var body: some View {
        VStack(alignment: .leading, spacing: 40) {
            Text("Settings").font(.system(size: 50, weight: .black)).padding([.leading, .top], 60)
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Section(header: Text("Playback Settings")) {
                        Button(action: { preferredPlayer = "mpv" }) {
                            HStack {
                                Text("MPV Player (Recommended)")
                                Spacer()
                                if preferredPlayer == "mpv" {
                                    Image(systemName: "checkmark").foregroundColor(Theme.accent)
                                }
                            }
                        }
                        .appRemoteAction { preferredPlayer = "mpv" }
                        Button(action: { preferredPlayer = "avplayer" }) {
                            HStack {
                                Text("Native (AVPlayer)")
                                Spacer()
                                if preferredPlayer == "avplayer" {
                                    Image(systemName: "checkmark").foregroundColor(Theme.accent)
                                }
                            }
                        }
                        .appRemoteAction { preferredPlayer = "avplayer" }
                        // A Picker here is not focusable on tvOS without a navigation stack;
                        // cycle on each press like the reminder rows below.
                        Button(action: selectNextSkipProvider) {
                            HStack {
                                Text("Skip Segments Provider")
                                    .foregroundColor(focusedSetting == .skipProvider ? .black : .primary)
                                Spacer()
                                Text(skipProviderLabel)
                                    .foregroundColor(focusedSetting == .skipProvider ? .black : Theme.secondaryText)
                                Image(systemName: "chevron.right")
                                    .foregroundColor(focusedSetting == .skipProvider ? .black : Theme.accent)
                            }
                        }
                        .appRemoteAction(selectNextSkipProvider, focusChanged: { noteFocus(.skipProvider, active: $0) })
                        Toggle("Save Cast History", isOn: $enableHistory)
                            .appRemoteAction { enableHistory.toggle() }
                        Toggle("Still Watching Reminder", isOn: $stillWatchingEnabled)
                            .appRemoteAction { stillWatchingEnabled.toggle() }
                        Button(action: selectNextStillWatchingThreshold) {
                            HStack {
                                Text("Reminder interval")
                                    .foregroundColor(focusedSetting == .reminderInterval ? .black : .primary)
                                Spacer()
                                Text(stillWatchingThresholdLabel)
                                    .foregroundColor(focusedSetting == .reminderInterval ? .black : Theme.secondaryText)
                                Image(systemName: "chevron.right")
                                    .foregroundColor(focusedSetting == .reminderInterval ? .black : Theme.accent)
                            }
                        }
                        .appRemoteAction(selectNextStillWatchingThreshold, focusChanged: { noteFocus(.reminderInterval, active: $0) })
                        Button(action: selectNextStillWatchingResponseTime) {
                            HStack {
                                Text("Response time")
                                    .foregroundColor(focusedSetting == .responseTime ? .black : .primary)
                                Spacer()
                                Text(stillWatchingResponseTimeLabel)
                                    .foregroundColor(focusedSetting == .responseTime ? .black : Theme.secondaryText)
                                Image(systemName: "chevron.right")
                                    .foregroundColor(focusedSetting == .responseTime ? .black : Theme.accent)
                            }
                        }
                        .appRemoteAction(selectNextStillWatchingResponseTime, focusChanged: { noteFocus(.responseTime, active: $0) })
                    }

                    Section("Server Information") {
                        HStack {
                            Text("Device Name")
                            Spacer()
                            Text(server.deviceName).foregroundColor(.gray)
                        }
                        HStack {
                            Text("IP Address")
                            Spacer()
                            Text(server.localIP).foregroundColor(.gray)
                        }
                        HStack {
                            Text("Status")
                            Spacer()
                            Text(server.serverState).foregroundColor(Theme.accent)
                        }
                        Button(action: { server.restart() }) {
                            HStack {
                                Image(systemName: "arrow.clockwise")
                                Text("Restart Server")
                            }
                            .foregroundColor(Theme.accent)
                        }
                        .appRemoteAction { server.restart() }
                    }

                    // tvOS has no browser to open links, so the policy URL is shown for reading.
                    Section("About") {
                        HStack {
                            Text("Privacy Policy")
                            Spacer()
                            Text("playbridge.app/privacy").foregroundColor(.gray)
                        }
                        .focusable()
                        .appRemoteAction({})
                        HStack {
                            Text("Version")
                            Spacer()
                            Text(appVersionLabel).foregroundColor(.gray)
                        }
                        .focusable()
                        .appRemoteAction({})
                        Button("Open source licences") { showLicences = true }
                            .appRemoteAction { showLicences = true }
                    }
                }
                .padding(.horizontal, 60)
                .padding(.bottom, 40)
            }
            .appRemoteScroll()
        }
        .fullScreenCover(isPresented: $showLicences) {
            OpenSourceLicencesScreen()
        }
        .onAppear {
            if !StillWatchingController.allowedThresholds.contains(stillWatchingThreshold) {
                stillWatchingThreshold = StillWatchingController.defaultThreshold
            }
            if !StillWatchingController.allowedResponseSeconds.contains(stillWatchingResponseSeconds) {
                stillWatchingResponseSeconds = StillWatchingController.defaultResponseSeconds
            }
        }
    }

    private func noteFocus(_ setting: FocusedSetting, active: Bool) {
        if active { focusedSetting = setting }
        else if focusedSetting == setting { focusedSetting = nil }
    }

    private var appVersionLabel: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }

    private var stillWatchingThresholdLabel: String {
        "\(stillWatchingThreshold) minutes"
    }

    private var skipProviderLabel: String {
        (SkipSegmentProvider(rawValue: skipProvider) ?? .both).title
    }

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
