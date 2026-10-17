import SwiftUI

struct RemoteControlScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var pageCasting: PageCastCoordinator
    @State private var showCastLink = false
    @State private var showDevices = false
    @State private var castURL = ""

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 4) {
                Button { nav.returnFromRemote() } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .frame(width: 44, height: 44)
                }.accessibilityLabel("Back").accessibilityIdentifier("remote-return")
                statusChip
                Menu {
#if DEBUG
                    Button {
                        UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: vm.castPlaybackDiagnostics]],
                            options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(900)])
                    } label: {
                        Label("Copy background casting diagnostics", systemImage: "doc.on.doc")
                    }
#endif
                    Button { showCastLink = true } label: {
                        Label("Cast a link", systemImage: "link")
                    }.disabled(!vm.isConnected)
                } label: {
                    Image(systemName: "ellipsis").frame(width: 44, height: 44)
                }.accessibilityLabel("Remote options")
            }
            if let site = pageCasting.controllerName {
                HStack {
                    Text("Controlled by \(site)").font(Theme.font(.footnote)).lineLimit(1)
                    Spacer()
                    Button("Unlink") { pageCasting.unlink() }
                }
                .padding(12)
                .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
            }
            if vm.isAirPlay && !vm.airPlay.routeAvailable {
                AirPlayDestinationRow()
            }
            RemoteControlView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(.horizontal, 16).padding(.bottom, 12)
        .foregroundStyle(Theme.onSurface)
        .background(Theme.surface.ignoresSafeArea())
        .onAppear { vm.queryContext() }
        .sheet(isPresented: $showDevices) { DeviceConnectionSheet() }
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
        let label = RemoteControlPresentation.connectionTitle(connected: connected,
            external: vm.isExternalReceiver || vm.isAirPlay, context: vm.coordinator.activeContext,
            mediaKind: vm.coordinator.mediaKind, name: name)
        let protocolName = vm.isAirPlay ? "AirPlay" : vm.externalReceiver?.protocolName ?? "PlayBridge"
        let level = RemoteControlPresentation.controlLevel(policy: vm.nativeRemotePolicy,
            connected: connected, externalProtocol: vm.isAirPlay ? "airplay" : vm.externalReceiver?.protocolID)
        let accessibilityTitle = ([label] + (connected ? [protocolName, level].compactMap { $0 } : [])).joined(separator: ", ")
        return Button { showDevices = true } label: {
            HStack(spacing: 8) {
                Image(systemName: vm.isExternalReceiver || vm.isAirPlay ? "airplayvideo" : "tv")
                    .font(Theme.font(size: 14, weight: .semibold))
                VStack(alignment: .leading, spacing: 3) {
                    Text(label).font(Theme.font(size: 14, weight: .medium)).lineLimit(1)
                    if connected {
                        HStack(spacing: 6) {
                            Text(protocolName)
                                .font(Theme.font(size: 10, weight: .medium))
                            if let level {
                                Text(level).font(Theme.font(size: 10, weight: .bold))
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(accent.opacity(0.25), in: RoundedRectangle(cornerRadius: 4))
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .foregroundStyle(accent)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(accent.opacity(0.15), in: Capsule())
            .overlay(Capsule().stroke(accent.opacity(0.5), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityTitle + ". Change device")
        .accessibilityIdentifier("remote-device-picker")
    }
}
