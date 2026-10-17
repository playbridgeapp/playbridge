import SwiftUI

struct RemoteControlScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var pageCasting: PageCastCoordinator
    @EnvironmentObject private var atv: AtvRemoteCoordinator
    @State private var showAtvPairing = false
    @State private var showCastLink = false
    @State private var showDevices = false
    @State private var showTVApps = false
    @State private var appTicket: AtvControlTicket?
    private var combined: CombinedRemotePolicy { vm.combinedRemote }
    @State private var castURL = ""

    var body: some View {
        let controlTicket = combined.fullControl.ticket
        return VStack(spacing: 8) {
            HStack(spacing: 4) {
                Button { nav.returnFromRemote() } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .frame(width: 44, height: 44)
                }.accessibilityLabel("Back").accessibilityIdentifier("remote-return")
                statusChip
                Menu {
                    if let action = AtvPairingPresentation.pairingAction(atv.channel), combined.badge != nil {
                        Button(action) { showAtvPairing = true }.disabled(!atv.canPair)
                            .accessibilityIdentifier("atv-pairing-entry")
                    }
                    if combined.fullControlReady {
                        Button("Open app…") { appTicket = controlTicket; showTVApps = true }
                        Button("TV menu") { vm.sendTVControl(.key("menu"), ticket: controlTicket) }
                        Button(atv.channel.television?.isOn == true ? "Power off" : "Power") {
                            vm.sendTVControl(.power, ticket: controlTicket)
                        }
                    }
                    if combined.native && atv.storageError != nil {
                        Button("Retry TV pairing storage") { atv.refresh() }
                    }
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
        .sheet(isPresented: $showTVApps) {
            AtvAppLaunchSheet { value in vm.sendTVControl(.launch(value), ticket: appTicket) }
        }
        .sheet(isPresented: $showAtvPairing) { AtvPairingSheet(coordinator: atv) }
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
        let connected = vm.isConnected || combined.fullControlReady
        let accent = connected ? Color.green : Theme.onSurfaceVariant
        let name = vm.receiverName ?? (vm.isExternalReceiver ? "receiver" : "TV")
        let label = combined.fullControlReady && !vm.isConnected ? "TV control on \(name)" : RemoteControlPresentation.connectionTitle(connected: vm.isConnected,
            external: vm.isExternalReceiver || vm.isAirPlay, context: vm.coordinator.activeContext,
            mediaKind: vm.coordinator.mediaKind, name: name)
        let protocolName = vm.isAirPlay ? "AirPlay" : vm.externalReceiver?.protocolName ?? "PlayBridge"
        let level = combined.badge ?? RemoteControlPresentation.controlLevel(policy: vm.nativeRemotePolicy,
            connected: connected, externalProtocol: vm.isAirPlay ? "airplay" : vm.externalReceiver?.protocolID)
        let accessibilityTitle = ([label] + (connected || combined.badge != nil ? [protocolName, level].compactMap { $0 } : [])).joined(separator: ", ") + (combined.televisionOff ? ", TV is off" : "")
        return Button { showDevices = true } label: {
            HStack(spacing: 8) {
                Image(systemName: vm.isExternalReceiver || vm.isAirPlay ? "airplayvideo" : "tv")
                    .font(Theme.font(size: 14, weight: .semibold))
                VStack(alignment: .leading, spacing: 3) {
                    Text(label).font(Theme.font(size: 14, weight: .medium)).lineLimit(1)
                    if connected || combined.badge != nil {
                        HStack(spacing: 6) {
                            Text(protocolName)
                                .font(Theme.font(size: 10, weight: .medium))
                            if combined.televisionOff { Text("Off").font(Theme.font(size: 10, weight: .bold)).accessibilityLabel("TV is off") }
                            if let level {
                                Text(level).font(Theme.font(size: 10, weight: .bold))
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(accent.opacity(0.25), in: RoundedRectangle(cornerRadius: 4))
                            }
                        }
                    }
                    if combined.native && combined.fullControl.endpoint == combined.endpoint && combined.fullControl.storageFailure, let error = atv.storageError {
                        Text(error.errorDescription ?? "TV pairing storage unavailable")
                            .font(Theme.font(.caption2)).foregroundStyle(Theme.danger).lineLimit(2)
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
        .contextMenu {
            if let action = AtvPairingPresentation.pairingAction(atv.channel), combined.badge != nil {
                Button(action) { showAtvPairing = true }.disabled(!atv.canPair)
            }
        }
        .accessibilityLabel(accessibilityTitle + ". Change device")
        .accessibilityIdentifier("remote-device-picker")
    }
}
