import SwiftUI

/// Discovery + pairing entry point. Port of `ui/ConnectionScreen.kt`: a list of receivers found
/// on the LAN, a manual-IP fallback, and inline status for each connection/pairing state.
struct ConnectionScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var manualIP: String = ""
    @State private var pairingCode: String = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                statusBanner
                pairingCodeSection
                connectedSection
                savedSection
                discoveredSection
                manualSection
            }
            .padding(20)
        }
        .background(Theme.surface.ignoresSafeArea())
        .onAppear { vm.startDiscovery(); vm.pingSavedDevices() }
        .onDisappear { vm.stopDiscovery() }
        .onChange(of: vm.state) { newState in
            // Clear the field after a rejected code so the user retypes fresh.
            if case .waitingForCodeInput(_, _, let wrong) = newState, wrong { pairingCode = "" }
        }
    }

    // MARK: - SAS pairing code

    @ViewBuilder private var pairingCodeSection: some View {
        switch vm.state {
        case .waitingForCodeInput(let name, let attemptsLeft, let lastWrong):
            VStack(alignment: .leading, spacing: 12) {
                Text("Enter the 6-digit code shown on \(name)")
                    .font(.subheadline)
                    .foregroundColor(Theme.onSurface)
                TextField("000000", text: $pairingCode)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(.system(size: 28, weight: .bold, design: .monospaced))
                    .multilineTextAlignment(.center)
                    .foregroundColor(Theme.onSurface)
                    .padding(12)
                    .background(Theme.surfaceContainerLow)
                    .cornerRadius(12)
                    .onChange(of: pairingCode) { newValue in
                        let filtered = String(newValue.filter { $0.isNumber }.prefix(6))
                        if filtered != newValue { pairingCode = filtered }
                        if filtered.count == 6 { vm.submitPairingCode(filtered) }
                    }
                if lastWrong {
                    Text("Incorrect code — \(attemptsLeft) \(attemptsLeft == 1 ? "try" : "tries") left")
                        .font(.caption)
                        .foregroundColor(Theme.danger)
                }
                HStack {
                    Button("Verify") { vm.submitPairingCode(pairingCode) }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.primaryDim)
                        .disabled(pairingCode.count != 6)
                    Spacer()
                    Button("Cancel") { vm.disconnect() }
                        .foregroundColor(Theme.danger)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surfaceContainer)
            .cornerRadius(14)
        case .verifyingCode(let name):
            banner("Verifying code with \(name)…", systemImage: "hourglass", tint: Theme.primary)
        default:
            EmptyView()
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("PlayBridge")
                    .font(.largeTitle.bold())
                    .foregroundColor(Theme.onSurface)
                Text("Connect to your TV")
                    .font(.subheadline)
                    .foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer()
            Button {
                nav.navigate(to: .dashboard)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2)
                    .foregroundColor(Theme.onSurfaceVariant)
            }
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
        if !vm.savedDevices.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    sectionTitle("Your TVs")
                    Spacer()
                    Button { vm.pingSavedDevices() } label: {
                        Image(systemName: "arrow.clockwise").foregroundColor(Theme.primary)
                    }
                }
                ForEach(vm.savedDevices.indices, id: \.self) { i in
                    let saved = vm.savedDevices[i]
                    let online = vm.onlineStatus[vm.deviceKey(saved)] == true
                    HStack {
                        ZStack(alignment: .bottomTrailing) {
                            Image(systemName: "tv").foregroundColor(Theme.primary)
                            Circle()
                                .fill(online ? Color(hex: 0x4CAF50) : Theme.onSurfaceVariant.opacity(0.4))
                                .frame(width: 7, height: 7)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(saved.name).foregroundColor(Theme.onSurface).font(.headline)
                            Text(online ? "\(saved.ip) · online" : saved.ip)
                                .foregroundColor(Theme.onSurfaceVariant).font(.caption)
                        }
                        Spacer()
                        Button("Connect") { vm.connectSaved(saved) }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.primaryDim)
                        Button(role: .destructive) { vm.forget(saved) } label: {
                            Image(systemName: "trash")
                        }
                    }
                    .padding(14)
                    .background(Theme.surfaceContainer)
                    .cornerRadius(14)
                }
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

    @ViewBuilder private var connectedSection: some View {
        if case .connected(let serverName, let secure) = vm.state {
            VStack(alignment: .leading, spacing: 10) {
                sectionTitle("Connected TV")
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 16) {
                        Image(systemName: "tv")
                            .font(.system(size: 32))
                            .foregroundColor(Theme.primary)
                        
                        VStack(alignment: .leading, spacing: 2) {
                            Text(serverName)
                                .foregroundColor(Theme.onSurface)
                                .font(.headline)
                            
                            if let saved = vm.pairedDevice {
                                Text("\(saved.ip):\(secure ? (saved.wssPort != nil ? String(saved.wssPort!) : String(saved.port)) : String(saved.port))")
                                    .foregroundColor(Theme.onSurfaceVariant)
                                    .font(.caption)
                            }
                            
                            HStack(spacing: 4) {
                                Image(systemName: secure ? "lock.fill" : "lock.open.fill")
                                    .font(.system(size: 14))
                                    .foregroundColor(secure ? Color(hex: 0x4CAF50) : Color(hex: 0xFFA000))
                                Text(secure ? "Secure (wss)" : "Not secure (ws)")
                                    .font(.caption)
                                    .foregroundColor(secure ? Color(hex: 0x4CAF50) : Color(hex: 0xFFA000))
                            }
                        }
                        Spacer()
                    }
                    
                    HStack {
                        Button {
                            nav.navigate(to: .remote)
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "gamecontroller.fill")
                                Text("Remote Control")
                            }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(Theme.onPrimary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Theme.primaryDim)
                            .cornerRadius(10)
                        }
                        .buttonStyle(.plain)

                        Spacer()
                        
                        Button {
                            vm.disconnect()
                        } label: {
                            Text("Disconnect")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(Theme.danger)
                                .cornerRadius(10)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(14)
                .background(Theme.surfaceContainer)
                .cornerRadius(14)
            }
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption.bold())
            .foregroundColor(Theme.onSurfaceVariant)
    }
}
