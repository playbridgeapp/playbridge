import SwiftUI

/// Fast destination picker opened from cast actions. Its hierarchy intentionally mirrors
/// Android's compact picker: current destination, this phone, PlayBridge history, recent
/// third-party receivers, then a route into the full Devices screen.
struct DeviceConnectionSheet: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var pendingExternalID: String?

    private var activePairedDeviceKey: String? {
        guard vm.isConnected, !vm.isExternalReceiver, let device = vm.pairedDevice else { return nil }
        return vm.deviceKey(device)
    }

    private var playBridgeDevices: [PairedDevice] {
        vm.savedDevices
            .filter { vm.deviceKey($0) != activePairedDeviceKey }
            .sorted {
                let leftOnline = vm.onlineStatus[vm.deviceKey($0)] == true
                let rightOnline = vm.onlineStatus[vm.deviceKey($1)] == true
                if leftOnline != rightOnline { return leftOnline && !rightOnline }
                return $0.lastConnected > $1.lastConnected
            }
    }

    private var recentExternalDevices: [ExternalReceiverDevice] {
        vm.savedExternalReceiverDevices.filter {
            !(vm.isConnected && vm.externalReceiver?.identity == $0.identity)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ConnectionNowDestinationCard(compact: true) {
                        vm.disconnect()
                        dismiss()
                    }

                    ConnectionThisPhoneRow(
                        selected: !vm.isConnected && !vm.connectionIsConnecting,
                        compact: true
                    ) {
                        vm.disconnect()
                        dismiss()
                    }

                    HStack {
                        ConnectionSectionLabel("PlayBridge")
                        Spacer()
                        Button { vm.pingSavedDevices() } label: {
                            Image(systemName: "arrow.clockwise")
                                .foregroundColor(Theme.primary)
                                .frame(width: 36, height: 36)
                        }
                        .accessibilityLabel("Refresh")
                    }
                    .padding(.top, 4)

                    if playBridgeDevices.isEmpty {
                        Text("No PlayBridge TVs yet. Tap Find more devices to scan and pair.")
                            .font(Theme.font(.subheadline))
                            .foregroundColor(Theme.onSurfaceVariant)
                            .padding(.vertical, 4)
                    } else {
                        ForEach(Array(playBridgeDevices.enumerated()), id: \.offset) { _, device in
                            ConnectionPairedDeviceRow(
                                device: device,
                                online: vm.onlineStatus[vm.deviceKey(device)] == true,
                                compact: true,
                                onSelect: {
                                    vm.connectSaved(device)
                                    dismiss()
                                },
                                onRemove: { vm.forget(device) }
                            )
                        }
                    }

                    if !recentExternalDevices.isEmpty {
                        ConnectionSectionLabel("Recent other")
                            .padding(.top, 4)
                        ForEach(recentExternalDevices, id: \.identity) { device in
                            ConnectionExternalDeviceRow(
                                device: device,
                                online: isExternalDeviceOnline(device),
                                compact: true,
                                onSelect: {
                                    pendingExternalID = device.identity
                                    vm.connectExternalReceiver(device)
                                },
                                onRemove: { vm.forgetExternalReceiver(device) }
                            )
                        }
                    }

                    Button {
                        closePicker()
                        nav.navigate(to: .connection)
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "plus")
                            Text("Find more devices…")
                                .font(Theme.font(size: 16, weight: .semibold))
                            Spacer()
                        }
                        .foregroundColor(Theme.primary)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            }
            .background(Theme.surfaceContainerHigh.ignoresSafeArea())
            .navigationTitle("Cast to")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: closePicker) {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Close")
                }
            }
            .onAppear {
                vm.startDiscovery()
                vm.pingSavedDevices()
            }
            .onDisappear { vm.stopDiscovery() }
            .onChange(of: vm.state) { state in
                guard state.isConnected,
                      let pendingExternalID,
                      vm.externalReceiver?.identity == pendingExternalID else { return }
                self.pendingExternalID = nil
                dismiss()
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func isExternalDeviceOnline(_ device: ExternalReceiverDevice) -> Bool {
        (vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices)
            .contains { $0.identity == device.identity }
    }

    private func closePicker() {
        if let pendingExternalID,
           vm.externalReceiver?.identity == pendingExternalID,
           !vm.isConnected {
            vm.disconnect()
        }
        self.pendingExternalID = nil
        dismiss()
    }
}

// MARK: - Shared Android-parity destination components

struct ConnectionSectionLabel: View {
    private let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(Theme.font(size: 17, weight: .bold))
            .foregroundColor(Theme.primary)
    }
}

struct ConnectionNowDestinationCard: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    var compact = false
    var onRemote: (() -> Void)?
    let onDisconnect: () -> Void

    private var connectingName: String {
        switch vm.state {
        case .waitingForApproval(let name), .waitingForCodeInput(let name, _, _), .verifyingCode(let name):
            return name
        default:
            return vm.externalReceiver?.name ?? vm.pairedDevice?.name ?? "TV"
        }
    }

    var body: some View {
        Group {
            if case .connected(let name, let secure) = vm.state {
                activeCard(name: name, secure: secure)
            } else if vm.connectionIsConnecting {
                activeShell(
                    name: connectingName,
                    subtitle: "Connecting…",
                    systemImage: vm.isExternalReceiver ? "airplayvideo" : "tv",
                    badge: vm.externalReceiver?.protocolName,
                    status: "Connecting",
                    statusFilled: false,
                    secure: nil
                )
            } else {
                HStack(spacing: compact ? 10 : 12) {
                    Image(systemName: "iphone")
                        .font(Theme.font(size: compact ? 22 : 27))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .frame(width: compact ? 38 : 40, height: compact ? 38 : 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Nothing selected")
                            .font(Theme.font(size: 16, weight: .semibold))
                            .foregroundColor(Theme.onSurface)
                        Text("Pick a TV below, or play on this phone.")
                            .font(Theme.font(.caption))
                            .foregroundColor(Theme.onSurfaceVariant)
                    }
                    Spacer(minLength: 0)
                }
                .padding(compact ? 12 : 16)
                .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private func activeCard(name: String, secure: Bool) -> some View {
        let receiver = vm.externalReceiver
        let detail: String?
        if let receiver {
            detail = receiver.model
        } else if let paired = vm.pairedDevice {
            detail = "\(paired.ip):\(secure ? (paired.wssPort ?? paired.port) : paired.port)"
        } else {
            detail = nil
        }
        return activeShell(
            name: name,
            subtitle: detail,
            systemImage: receiver == nil ? "tv" : "airplayvideo",
            badge: receiver?.protocolName,
            status: "Connected",
            statusFilled: true,
            secure: receiver == nil ? secure : nil
        )
    }

    private func activeShell(
        name: String,
        subtitle: String?,
        systemImage: String,
        badge: String?,
        status: String,
        statusFilled: Bool,
        secure: Bool?
    ) -> some View {
        VStack(alignment: .leading, spacing: compact ? 7 : 10) {
            HStack(alignment: .center, spacing: compact ? 10 : 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Theme.onSecondaryContainer.opacity(0.09))
                    Image(systemName: systemImage)
                        .font(Theme.font(size: compact ? 21 : 28))
                        .foregroundColor(Theme.onSecondaryContainer)
                }
                .frame(width: compact ? 38 : 44, height: compact ? 38 : 44)

                VStack(alignment: .leading, spacing: 4) {
                    Text(name)
                        .font(Theme.font(size: 16, weight: .semibold))
                        .foregroundColor(Theme.onSecondaryContainer)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    HStack(spacing: 6) {
                        if let badge { ConnectionProtocolBadge(badge) }
                        ConnectionStatusPill(text: status, filled: statusFilled)
                    }
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(Theme.font(.caption))
                            .foregroundColor(Theme.onSecondaryContainer.opacity(0.8))
                            .lineLimit(compact ? 2 : nil)
                    }
                    if let secure {
                        Label(secure ? "wss" : "ws", systemImage: secure ? "lock.fill" : "lock.open.fill")
                            .font(Theme.font(.caption))
                            .foregroundColor(secure ? ConnectionColors.online : ConnectionColors.warning)
                    }
                }
            }

            HStack(spacing: 8) {
                Spacer()
                if let onRemote {
                    Button("Remote", action: onRemote)
                        .font(Theme.font(size: 14, weight: .semibold))
                        .foregroundColor(Theme.primary)
                }
                Button("Disconnect", action: onDisconnect)
                    .font(Theme.font(size: 14, weight: .semibold))
                    .foregroundColor(Theme.danger)
            }
        }
        .padding(compact ? 12 : 16)
        .background(Theme.secondaryContainer.opacity(0.78), in: RoundedRectangle(cornerRadius: 14))
    }
}

struct ConnectionThisPhoneRow: View {
    let selected: Bool
    var compact = false
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: compact ? 10 : 16) {
                Image(systemName: "iphone")
                    .font(Theme.font(size: compact ? 22 : 27))
                    .foregroundColor(Theme.primary)
                    .frame(width: compact ? 38 : 40, height: compact ? 38 : 40)
                Text("This phone")
                    .font(Theme.font(size: 16, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .font(Theme.font(size: 15, weight: .bold))
                        .foregroundColor(ConnectionColors.online)
                }
            }
            .padding(.horizontal, compact ? 12 : 16)
            .padding(.vertical, compact ? 8 : 12)
            .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct ConnectionPairedDeviceRow: View {
    let device: PairedDevice
    let online: Bool
    var compact = false
    let onSelect: () -> Void
    var onRemove: (() -> Void)?

    var body: some View {
        ConnectionDeviceRowShell(
            name: device.name,
            endpoint: "\(device.ip):\(device.port)",
            status: online ? "Online" : connectionLastSeen(device.lastConnected),
            online: online,
            systemImage: "tv",
            badge: nil,
            compact: compact,
            onSelect: onSelect,
            onRemove: onRemove
        )
    }
}

struct ConnectionExternalDeviceRow: View {
    let device: ExternalReceiverDevice
    let online: Bool
    var compact = false
    let onSelect: () -> Void
    var onRemove: (() -> Void)?

    private var endpoint: String {
        if let address = device.addresses.first { return "\(address):\(device.port)" }
        return device.model
    }

    var body: some View {
        ConnectionDeviceRowShell(
            name: device.name,
            endpoint: endpoint,
            status: online ? "Online" : "Saved",
            online: online,
            systemImage: "airplayvideo",
            badge: device.protocolName,
            compact: compact,
            onSelect: onSelect,
            onRemove: onRemove
        )
    }
}

private struct ConnectionDeviceRowShell: View {
    let name: String
    let endpoint: String
    let status: String
    let online: Bool
    let systemImage: String
    let badge: String?
    let compact: Bool
    let onSelect: () -> Void
    let onRemove: (() -> Void)?

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onSelect) {
                HStack(spacing: compact ? 10 : 16) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Theme.primary.opacity(0.10))
                        Image(systemName: systemImage)
                            .font(Theme.font(size: compact ? 20 : 25))
                            .foregroundColor(Theme.primary)
                    }
                    .frame(width: compact ? 38 : 44, height: compact ? 38 : 44)

                    VStack(alignment: .leading, spacing: 3) {
                        Text(name)
                            .font(Theme.font(size: compact ? 15 : 16, weight: .semibold))
                            .foregroundColor(Theme.onSurface)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        HStack(spacing: 5) {
                            Circle()
                                .fill(online ? ConnectionColors.online : Theme.onSurfaceVariant.opacity(0.5))
                                .frame(width: 7, height: 7)
                            Text("\(status) · \(endpoint)")
                                .font(Theme.font(.caption))
                                .foregroundColor(online ? ConnectionColors.online : Theme.onSurfaceVariant)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            if let badge { ConnectionProtocolBadge(badge) }
                        }
                    }
                }
                .padding(.leading, compact ? 10 : 14)
                .padding(.vertical, compact ? 9 : 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "trash")
                        .font(Theme.font(size: 17))
                        .foregroundColor(Theme.danger)
                        .frame(width: 42, height: 44)
                }
                .accessibilityLabel("Remove \(name)")
            } else {
                Spacer().frame(width: compact ? 10 : 14)
            }
        }
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct ConnectionProtocolBadge: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(Theme.font(size: 10, weight: .medium))
            .foregroundColor(Theme.onSecondaryContainer)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Theme.secondaryContainer, in: RoundedRectangle(cornerRadius: 4))
            .lineLimit(1)
    }
}

private struct ConnectionStatusPill: View {
    let text: String
    let filled: Bool

    var body: some View {
        Text(text)
            .font(Theme.font(size: 10, weight: .medium))
            .foregroundColor(filled ? ConnectionColors.online : Theme.onSurfaceVariant)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(
                (filled ? ConnectionColors.online.opacity(0.18) : Theme.surfaceContainerHighest),
                in: Capsule()
            )
    }
}

private enum ConnectionColors {
    static let online = Color(hex: 0x4CAF50)
    static let warning = Color(hex: 0xFFA000)
}

func connectionLastSeen(_ date: Date) -> String {
    let seconds = max(0, Date().timeIntervalSince(date))
    switch seconds {
    case ..<60: return "Last seen just now"
    case ..<3_600: return "Last seen \(Int(seconds / 60))m ago"
    case ..<86_400: return "Last seen \(Int(seconds / 3_600))h ago"
    case ..<(7 * 86_400): return "Last seen \(Int(seconds / 86_400))d ago"
    default: return "Saved"
    }
}

extension ConnectionViewModel {
    var connectionIsConnecting: Bool {
        switch state {
        case .connecting, .waitingForApproval, .retrying, .waitingForCodeInput, .verifyingCode:
            return true
        default:
            return false
        }
    }
}
