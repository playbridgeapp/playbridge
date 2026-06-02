import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var server: WebSocketServer
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"
    // Same key as WebSocketServer.allowInsecure.
    @AppStorage("pb_allow_insecure") var allowInsecure: Bool = false

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

                Section(header: Text("Security")) {
                    Toggle("Allow insecure connections (ws)", isOn: $allowInsecure)
                        .onChange(of: allowInsecure) { _, _ in server.restart() }
                    Text("Off = encrypted wss only. Enable for older senders that can't use TLS (e.g. the browser extension).")
                        .font(.footnote)
                        .foregroundColor(.gray)
                }
            }
            .listStyle(.grouped)
        }
    }
}
