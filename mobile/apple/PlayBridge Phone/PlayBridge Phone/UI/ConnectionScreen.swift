import SwiftUI

/// Discovery + pairing entry point. Port of `ui/ConnectionScreen.kt`: a list of receivers found
/// on the LAN, a manual-IP fallback, and inline status for each connection/pairing state.
struct ConnectionScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @State private var manualIP: String = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                statusBanner
                savedSection
                discoveredSection
                manualSection
            }
            .padding(20)
        }
        .background(Theme.surface.ignoresSafeArea())
        .onAppear { vm.startDiscovery() }
        .onDisappear { vm.stopDiscovery() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("PlayBridge")
                .font(.largeTitle.bold())
                .foregroundColor(Theme.onSurface)
            Text("Connect to your TV")
                .font(.subheadline)
                .foregroundColor(Theme.onSurfaceVariant)
        }
        .padding(.top, 12)
    }

    // MARK: - Status

    @ViewBuilder private var statusBanner: some View {
        switch vm.state {
        case .connecting:
            banner("Connecting…", systemImage: "antenna.radiowaves.left.and.right", tint: Theme.primary)
        case .waitingForApproval(let name):
            banner("Waiting for approval on \(name)…\nTap Allow on the TV.",
                   systemImage: "hourglass", tint: Theme.primary)
        case .retrying(let attempt, let max, _):
            banner("Reconnecting (\(attempt)/\(max))…", systemImage: "arrow.clockwise", tint: Theme.onSurfaceVariant)
        case .pairingDenied(let name):
            banner("\(name) denied the pairing request.", systemImage: "xmark.octagon", tint: Theme.danger)
        case .authFailed:
            banner("The saved pairing was rejected. Pair again.", systemImage: "key.slash", tint: Theme.danger)
        case .pinMismatch(let name):
            banner("\(name)'s security fingerprint changed — possible impersonation. Forget and re-pair.",
                   systemImage: "exclamationmark.shield", tint: Theme.danger)
        case .error(let message):
            banner(message, systemImage: "wifi.exclamationmark", tint: Theme.danger)
        default:
            EmptyView()
        }
    }

    private func banner(_ text: String, systemImage: String, tint: Color) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage).foregroundColor(tint)
            Text(text).font(.subheadline).foregroundColor(Theme.onSurface)
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceContainer)
        .cornerRadius(14)
    }

    // MARK: - Saved

    @ViewBuilder private var savedSection: some View {
        if let saved = vm.pairedDevice {
            VStack(alignment: .leading, spacing: 10) {
                sectionTitle("Saved")
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(saved.name).foregroundColor(Theme.onSurface).font(.headline)
                        Text(saved.ip).foregroundColor(Theme.onSurfaceVariant).font(.caption)
                    }
                    Spacer()
                    Button("Reconnect") { vm.reconnectSaved() }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.primaryDim)
                    Button(role: .destructive) { vm.forgetDevice() } label: {
                        Image(systemName: "trash")
                    }
                }
                .padding(14)
                .background(Theme.surfaceContainer)
                .cornerRadius(14)
            }
        }
    }

    // MARK: - Discovered

    private var discoveredSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                sectionTitle("Discovered")
                Spacer()
                if vm.browser.isScanning { ProgressView().tint(Theme.primary) }
            }
            if vm.browser.devices.isEmpty {
                Text("Searching for receivers on your Wi-Fi…")
                    .font(.subheadline)
                    .foregroundColor(Theme.onSurfaceVariant)
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.surfaceContainerLow)
                    .cornerRadius(14)
            } else {
                ForEach(vm.browser.devices) { device in
                    Button { vm.connect(to: device) } label: {
                        HStack {
                            Image(systemName: "tv").foregroundColor(Theme.primary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.name).foregroundColor(Theme.onSurface).font(.headline)
                                Text("\(device.ip)\(device.wssPort != nil ? "  · secure" : "")")
                                    .foregroundColor(Theme.onSurfaceVariant).font(.caption)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundColor(Theme.onSurfaceVariant)
                        }
                        .padding(14)
                        .background(Theme.surfaceContainer)
                        .cornerRadius(14)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Manual

    private var manualSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("Manual connect")
            HStack {
                TextField("TV IP address", text: $manualIP)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.numbersAndPunctuation)
                    .foregroundColor(Theme.onSurface)
                    .padding(12)
                    .background(Theme.surfaceContainerLow)
                    .cornerRadius(12)
                Button("Connect") {
                    let ip = manualIP.trimmingCharacters(in: .whitespaces)
                    if !ip.isEmpty { vm.connectManual(ip: ip) }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.primaryDim)
                .disabled(manualIP.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption.bold())
            .foregroundColor(Theme.onSurfaceVariant)
    }
}
