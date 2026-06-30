import SwiftUI

/// "Cast to" bottom sheet (opened from the browser TV button): the active TV with a
/// disconnect action, the list of saved TVs with live status, and a "Set up new TV"
/// row. Mirrors Android's DeviceConnectionSheet.
struct DeviceConnectionSheet: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @Environment(\.dismiss) private var dismiss

    private var connectedName: String? {
        if case .connected(let name, _) = vm.state { return name }
        return nil
    }

    private var isConnecting: Bool {
        switch vm.state {
        case .connecting, .waitingForApproval, .retrying, .waitingForCodeInput, .verifyingCode:
            return true
        default:
            return false
        }
    }

    /// Saved TVs excluding the currently-connected one (shown in the active card).
    private var others: [PairedDevice] {
        guard vm.isConnected, let active = vm.pairedDevice else { return vm.savedDevices }
        return vm.savedDevices.filter { vm.deviceKey($0) != vm.deviceKey(active) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let connectedName {
                        activeCard(name: connectedName)
                    } else if isConnecting {
                        connectingCard
                    }

                    HStack {
                        Text("Your TVs").font(.headline).foregroundColor(Theme.primary)
                        Spacer()
                        Button { vm.pingSavedDevices() } label: {
                            Image(systemName: "arrow.clockwise").foregroundColor(Theme.primary)
                        }
                    }
                    .padding(.top, 4)

                    if others.isEmpty {
                        Text("No saved TVs. Tap “Set up new TV” to scan your network.")
                            .font(.subheadline).foregroundColor(Theme.onSurfaceVariant)
                            .padding(.vertical, 4)
                    } else {
                        ForEach(others.indices, id: \.self) { i in deviceRow(others[i]) }
                    }

                    setupRow
                }
                .padding(20)
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Cast to")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .onAppear { vm.pingSavedDevices() }
        }
    }

    // MARK: - Cards

    private func activeCard(name: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: "tv").font(.system(size: 28)).foregroundColor(Theme.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline).foregroundColor(Theme.onSurface).lineLimit(1)
                if let d = vm.pairedDevice {
                    Text("\(d.ip):\(d.port)").font(.caption).foregroundColor(Theme.onSurfaceVariant)
                }
                Text("Connected").font(.caption.bold()).foregroundColor(Color(hex: 0x4CAF50))
            }
            Spacer()
            Button {
                vm.disconnect()
                dismiss()
            } label: {
                Text("Disconnect").font(.system(size: 13, weight: .semibold)).foregroundColor(.white)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Theme.danger).cornerRadius(10)
            }
            .buttonStyle(.plain)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainer))
    }

    private var connectingCard: some View {
        HStack(spacing: 12) {
            ProgressView().tint(Theme.primary)
            Text("Connecting…").font(.subheadline).foregroundColor(Theme.onSurface)
            Spacer()
            Button("Cancel") { vm.disconnect() }.foregroundColor(Theme.danger)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainer))
    }

    private func deviceRow(_ d: PairedDevice) -> some View {
        let online = vm.onlineStatus[vm.deviceKey(d)] == true
        return Button {
            vm.connectSaved(d)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                ZStack(alignment: .bottomTrailing) {
                    Image(systemName: "tv").font(.system(size: 20)).foregroundColor(Theme.onSurface).frame(width: 28)
                    Circle()
                        .fill(online ? Color(hex: 0x4CAF50) : Theme.onSurfaceVariant.opacity(0.4))
                        .frame(width: 8, height: 8)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.name).font(.system(size: 15, weight: .medium)).foregroundColor(Theme.onSurface).lineLimit(1)
                    Text(online ? "\(d.ip) · online" : d.ip).font(.caption).foregroundColor(Theme.onSurfaceVariant)
                }
                Spacer()
                Button { vm.forget(d) } label: {
                    Image(systemName: "trash").foregroundColor(Theme.onSurfaceVariant)
                }
                .buttonStyle(.plain)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainer))
        }
        .buttonStyle(.plain)
    }

    private var setupRow: some View {
        Button {
            dismiss()
            nav.navigate(to: .connection)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "plus.circle.fill").font(.system(size: 20)).foregroundColor(Theme.primary).frame(width: 28)
                Text("Set up new TV").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.primary)
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.onSurfaceVariant)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainer.opacity(0.5)))
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }
}
