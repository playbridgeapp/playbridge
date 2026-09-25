import SwiftUI

struct RemoteControlScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var showCastLink = false
    @State private var castURL = ""

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 4) {
                ScreenBackButton(destination: nav.remoteOrigin ?? .dashboard, accessibilityLabel: "Back")
                Text("Remote").font(Theme.font(.title3).bold())
                Spacer()
#if DEBUG
                Button {
                    UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: vm.castPlaybackDiagnostics]],
                        options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(900)])
                } label: {
                    Image(systemName: "doc.on.doc").frame(width: 44, height: 44)
                }.accessibilityLabel("Copy background casting diagnostics")
#endif
                Button { showCastLink = true } label: {
                    Image(systemName: "link").frame(width: 44, height: 44)
                }.accessibilityLabel("Cast a link").disabled(!vm.isConnected)
            }
            statusChip
            RemoteControlView().frame(maxWidth: .infinity, maxHeight: .infinity)
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

    private var statusChip: some View {
        let connected = vm.isConnected
        let accent = connected ? Color.green : Theme.onSurfaceVariant
        let name = vm.receiverName ?? (vm.isExternalReceiver ? "receiver" : "TV")
        let label = vm.isExternalReceiver ? "Casting to \(name)" : (connected ? "Watching on \(name)" : "Not connected")
        return HStack(spacing: 8) {
            Image(systemName: vm.isExternalReceiver ? "airplayvideo" : "tv")
                .font(Theme.font(size: 14, weight: .semibold))
            Text(label).font(Theme.font(size: 14, weight: .medium)).lineLimit(1)
            if let protocolName = vm.externalReceiver?.protocolName {
                Text(protocolName)
                    .font(Theme.font(size: 10, weight: .bold))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(accent.opacity(0.25), in: RoundedRectangle(cornerRadius: 4))
            }
        }
        .foregroundStyle(accent)
        .padding(.horizontal, 14)
        .frame(height: 36)
        .frame(maxWidth: 280)
        .background(accent.opacity(0.15), in: Capsule())
        .overlay(Capsule().stroke(accent.opacity(0.5), lineWidth: 1))
    }
}
