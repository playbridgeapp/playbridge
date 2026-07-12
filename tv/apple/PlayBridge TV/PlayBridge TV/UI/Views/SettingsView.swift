import SwiftUI

struct SettingsView: View {
    private enum FocusedSetting: Hashable { case reminderInterval, responseTime }

    @EnvironmentObject var server: WebSocketServer
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"
    @AppStorage("enable_history") var enableHistory: Bool = true
    @AppStorage("still_watching_enabled") var stillWatchingEnabled: Bool = false
    @AppStorage("still_watching_threshold_min") var stillWatchingThreshold: Int = StillWatchingController.defaultThreshold
    @AppStorage("still_watching_response_sec") var stillWatchingResponseSeconds: Int = StillWatchingController.defaultResponseSeconds
    @FocusState private var focusedSetting: FocusedSetting?

    var body: some View {
        VStack(alignment: .leading, spacing: 40) {
            Text("Settings").font(.system(size: 50, weight: .black)).padding([.leading, .top], 60)
            List {
                Section(header: Text("Playback Settings")) {
                    Button(action: { preferredPlayer = "avplayer" }) {
                        HStack {
                            Text("Native (AVPlayer)")
                            Spacer()
                            if preferredPlayer == "avplayer" {
                                Image(systemName: "checkmark").foregroundColor(Theme.accent)
                            }
                        }
                    }
                    Button(action: { preferredPlayer = "vlc" }) {
                        HStack {
                            Text("VLC Player")
                            Spacer()
                            if preferredPlayer == "vlc" {
                                Image(systemName: "checkmark").foregroundColor(Theme.accent)
                            }
                        }
                    }
                    Button(action: { preferredPlayer = "mpv" }) {
                        HStack {
                            Text("MPV Player")
                            Spacer()
                            if preferredPlayer == "mpv" {
                                Image(systemName: "checkmark").foregroundColor(Theme.accent)
                            }
                        }
                    }
                    Toggle("Save Cast History", isOn: $enableHistory)
                    Toggle("Still Watching Reminder", isOn: $stillWatchingEnabled)
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
                    .focused($focusedSetting, equals: .reminderInterval)
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
                    .focused($focusedSetting, equals: .responseTime)
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
                }
            }
            .listStyle(.grouped)
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

    private var stillWatchingThresholdLabel: String {
        "\(stillWatchingThreshold) minutes"
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
