import SwiftUI

struct RemoteControlScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var showCastLink = false
    @State private var castURL = ""

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Button { nav.navigate(to: nav.remoteOrigin ?? nav.lastMainScreen) } label: {
                    Image(systemName: "arrow.left").frame(width: 44, height: 44)
                }.accessibilityLabel("Back")
                Text("Remote").font(Theme.font(.title2).bold())
                Spacer()
                Button { showCastLink = true } label: {
                    Image(systemName: "link").frame(width: 44, height: 44)
                }.accessibilityLabel("Cast a link").disabled(!vm.isConnected)
            }
            HStack(spacing: 10) {
                Image(systemName: "tv").foregroundStyle(Theme.primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(vm.receiverName ?? "Your TV").font(Theme.font(.subheadline).bold()).lineLimit(1)
                    Text(vm.isConnected ? "Connected" : "Disconnected")
                        .font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
                }
                Spacer()
                Text(vm.externalReceiver?.protocolName ?? "PlayBridge")
                    .font(Theme.font(.caption2).bold()).foregroundStyle(Theme.primary)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Theme.primary.opacity(0.12), in: Capsule())
                Circle().fill(vm.isConnected ? Color.green : Theme.onSurfaceVariant).frame(width: 7, height: 7)
            }
            .padding(14).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 20))
            RemoteControlView()
        }
        .padding(.horizontal, 16).padding(.bottom, 12)
        .foregroundStyle(Theme.onSurface)
        .background(Theme.surface.ignoresSafeArea())
        .onAppear { vm.queryContext() }
        .sheet(isPresented: $showCastLink) {
            NavigationStack {
                Form {
                    TextField("Video URL", text: $castURL)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Cast") {
                        vm.cast(urlString: castURL.trimmingCharacters(in: .whitespacesAndNewlines))
                        castURL = ""
                        showCastLink = false
                    }.disabled(castURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !vm.isConnected)
                }
                .navigationTitle("Cast a link").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showCastLink = false } } }
            }.presentationDetents([.medium])
        }
    }
}
