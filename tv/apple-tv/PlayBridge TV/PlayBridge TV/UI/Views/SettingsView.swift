import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var server: WebSocketServer
    @AppStorage("preferredPlayer") var preferredPlayer: String = "avplayer"

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
                }
            }
            .listStyle(.grouped)
        }
    }
}
