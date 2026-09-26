import SwiftUI

struct PageCastRequestSheet: View {
    @ObservedObject var casting: PageCastCoordinator
    @EnvironmentObject private var vm: ConnectionViewModel
    @State private var code = ""

    private var title: String {
        switch casting.presentation?.stage {
        case .website: return "Allow website to cast?"
        case .privateServers: return "Allow local-network media?"
        case .device: return "Choose a PlayBridge device"
        default: return "Connecting to your device"
        }
    }
    var body: some View {
        NavigationStack {
            Form {
                if let presentation = casting.presentation {
                    let site = PageCastCoordinator.displayName(presentation.origin)
                    switch presentation.stage {
                    case .website:
                        Section {
                            Label(site, systemImage: "globe").font(Theme.font(.headline))
                            Text("This website can start casts and stay linked to manage its playlist on your selected device. You can still use PlayBridge’s playback controls.")
                            Text("Your choice is remembered. Change it in Browser settings → Website casting permissions. Local-network media requires separate permission.")
                                .font(Theme.font(.footnote)).foregroundStyle(.secondary)
                        }
                        consentButtons
                    case .privateServers(let origins):
                        Section {
                            Text("\(site) wants your device to load media from these local servers:")
                            ForEach(origins.sorted(), id: \.self) { origin in
                                Label(PageCastCoordinator.displayName(origin), systemImage: "network")
                            }
                        } footer: {
                            Text("Only allow servers you recognize. Permission is remembered for this website and these servers only.")
                        }
                        consentButtons
                    case .device:
                        Section { Text("This website request needs a PlayBridge receiver. Open PlayBridge on your TV or computer to find it here.") }
                        PageCastDeviceChoices(casting: casting)
                    case .connecting:
                        Section {
                            switch vm.state {
                            case .waitingForCodeInput(let name, let attempts, let wrong):
                                Text("Enter the 6-digit code shown on \(name).")
                                TextField("000000", text: $code)
                                    .keyboardType(.numberPad).textContentType(.oneTimeCode)
                                    .font(Theme.font(size: 28, weight: .bold, design: .monospaced))
                                    .onChange(of: code) { code = String($0.filter(\.isNumber).prefix(6)) }
                                if wrong { Text("Incorrect code. \(attempts) attempts left.").foregroundColor(Theme.danger) }
                                Button("Verify code") { vm.submitPairingCode(code); code = "" }.disabled(code.count != 6)
                            case .waitingForApproval(let name):
                                ProgressView("Approve the connection on \(name)…")
                            case .pinMismatch:
                                Text("This device’s identity changed. Reconnect from Devices before casting.").foregroundColor(Theme.danger)
                            case .pairingDenied, .authFailed:
                                Text("The connection wasn’t approved. Cancel and try again.").foregroundColor(Theme.danger)
                            case .error:
                                Text("Couldn’t connect. Check that PlayBridge is open on the device.")
                            default:
                                ProgressView("Connecting…")
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { casting.dismissPresentation() }
            } }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
    private var consentButtons: some View {
        Section {
            Button("Allow") { casting.resolvePrompt(true) }.font(Theme.font(.headline))
            Button("Don’t allow", role: .cancel) { casting.resolvePrompt(false) }
        }
    }
}

private struct PageCastDeviceChoices: View {
    @ObservedObject var casting: PageCastCoordinator
    @EnvironmentObject private var vm: ConnectionViewModel
    @State private var address = ""
    private var discovered: [DiscoveredDevice] {
        vm.browser.devices.filter { device in
            !vm.savedDevices.contains { saved in
                (!device.uuid.isEmpty && device.uuid == saved.uuid) || (device.ip == saved.ip && device.port == saved.port)
            }
        }
    }
    var body: some View {
        Group {
            if !vm.savedDevices.isEmpty {
                Section("Saved devices") {
                    ForEach(Array(vm.savedDevices.enumerated()), id: \.offset) { _, device in
                        Button { casting.chooseReceiver(id: vm.deviceKey(device)) { vm.connectSaved(device) } } label: {
                            Label(device.name, systemImage: "tv")
                        }
                    }
                }
            }
            Section("Nearby devices") {
                ForEach(discovered) { device in
                    Button { casting.chooseReceiver(id: device.uuid.isEmpty ? device.id : device.uuid) { vm.connect(to: device) } } label: {
                        Label(device.name, systemImage: "tv")
                    }
                }
                if discovered.isEmpty { ProgressView("Looking for PlayBridge devices…") }
            }
            Section("Connect by address") {
                TextField("Device IP address", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Connect") {
                    let ip = address.trimmingCharacters(in: .whitespacesAndNewlines)
                    casting.chooseReceiver(id: "\(ip):\(ProtocolConstants.defaultPort)") { vm.connectManual(ip: ip) }
                }.disabled(address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .onAppear { vm.startDiscovery() }
        .onDisappear { vm.stopDiscovery() }
    }
}

struct PageCastPermissionsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var origins: [String] = []
    @State private var revision = 0
    @State private var resetAll = false
    @State private var resetLocal = false
    @AppStorage("website_cast_prefetch") private var prefetch = 3
    private let permissions = PageCastPermissions.shared

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if origins.isEmpty { Text("No websites have been allowed to cast.").foregroundStyle(.secondary) }
                    ForEach(origins, id: \.self) { origin in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(PageCastCoordinator.displayName(origin))
                            let count = permissions.privateOrigins(for: origin).count
                            Text((origin.hasPrefix("https:") ? "Secure site" : "Not secure") +
                                 (count > 0 ? " · \(count) local media servers allowed" : ""))
                                .font(Theme.font(.caption)).foregroundStyle(.secondary)
                        }
                        .swipeActions { Button("Revoke", role: .destructive) { permissions.revoke(origin) } }
                    }
                } header: { Text("Allowed websites") } footer: {
                    Text("These websites can cast without asking again. Swipe a website to revoke its permission. Revoking permission unlinks its active session; media already sent can keep playing.")
                }
                Section {
                    Stepper("Queue ahead: \(prefetch) items", value: $prefetch, in: 1...10)
                } header: { Text("Linked playlists") } footer: {
                    Text("How many upcoming items PlayBridge asks a linked website to supply.")
                }
                if !origins.isEmpty {
                    Section {
                        Button("Reset local media server access", role: .destructive) { resetLocal = true }
                        Button("Reset all website casting permissions", role: .destructive) { resetAll = true }
                    }
                }
            }
            .id(revision)
            .navigationTitle("Website casting permissions").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { reload() }
            .onReceive(NotificationCenter.default.publisher(for: .pageCastPermissionsChanged)) { _ in reload() }
            .alert("Reset website permissions?", isPresented: $resetAll) {
                Button("Reset", role: .destructive) { permissions.clear() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Websites will need permission before casting again. Active website links will end.") }
            .alert("Reset local-network access?", isPresented: $resetLocal) {
                Button("Reset", role: .destructive) { permissions.clearPrivateOrigins() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Websites will ask again before loading local media. Their basic casting permission stays allowed.") }
        }
    }
    private func reload() { origins = permissions.approvedOrigins; revision += 1 }
}
