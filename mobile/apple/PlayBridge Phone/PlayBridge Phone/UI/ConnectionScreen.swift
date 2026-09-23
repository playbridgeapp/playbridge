import SwiftUI

/// Discovery + pairing entry point. Port of `ui/ConnectionScreen.kt`: a list of receivers found
/// on the LAN, a manual-IP fallback, and inline status for each connection/pairing state.
struct ConnectionScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var rokuAddress = ""
    @State private var showDIAL = false
    @State private var dlnaLocation = ""
    @State private var manualIP: String = ""
    @State private var pairingCode: String = ""
    @State private var showOtherDevices = false
    @State private var showManualConnect = false
    @State private var showManualDLNA = false
    @State private var showManualRoku = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header
                statusBanner
                pairingCodeSection
                ConnectionSectionLabel("Now")
                    .padding(.top, 2)
                ConnectionNowDestinationCard(
                    compact: false,
                    onRemote: { nav.navigate(to: .remote) },
                    onDisconnect: { vm.disconnect() }
                )
                ConnectionThisPhoneRow(
                    selected: !vm.isConnected && !vm.connectionIsConnecting,
                    compact: false,
                    onSelect: { vm.disconnect() }
                )
                playBridgeSection
                recentOtherSection
                otherDevicesSection
                if showManualConnect { manualSection }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(Theme.surface.ignoresSafeArea())
        .onAppear { vm.startDiscovery(); vm.pingSavedDevices() }
        .onDisappear { vm.stopDiscovery() }
        .onChange(of: vm.state) { newState in
            // Clear the field after a rejected code so the user retypes fresh.
            if case .waitingForCodeInput(_, _, let wrong) = newState, wrong { pairingCode = "" }
        }
    }

    // MARK: - Android-parity destination groups

    private var discoveryIsScanning: Bool {
        vm.browser.isScanning || vm.googleCastBrowser.isScanning ||
            vm.dlnaBrowser.isScanning || vm.rokuBrowser.isScanning
    }

    private var activePlayBridgeKey: String? {
        guard vm.isConnected, !vm.isExternalReceiver, let device = vm.pairedDevice else { return nil }
        return vm.deviceKey(device)
    }

    private var sortedSavedPlayBridgeDevices: [PairedDevice] {
        vm.savedDevices
            .filter { vm.deviceKey($0) != activePlayBridgeKey }
            .sorted {
                let leftOnline = vm.onlineStatus[vm.deviceKey($0)] == true
                let rightOnline = vm.onlineStatus[vm.deviceKey($1)] == true
                if leftOnline != rightOnline { return leftOnline && !rightOnline }
                return $0.lastConnected > $1.lastConnected
            }
    }

    private var unpairedPlayBridgeDevices: [DiscoveredDevice] {
        vm.browser.devices.filter { discovered in
            !vm.savedDevices.contains { saved in samePlayBridgeDevice(discovered, saved) } &&
                !(vm.isConnected && !vm.isExternalReceiver && vm.pairedDevice.map {
                    samePlayBridgeDevice(discovered, $0)
                } == true)
        }
    }

    private var recentOtherDevices: [ExternalReceiverDevice] {
        vm.savedExternalReceiverDevices.filter {
            !(vm.isConnected && vm.externalReceiver?.identity == $0.identity)
        }
    }

    private var playBridgeSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                ConnectionSectionLabel("PlayBridge")
                Spacer()
                Button {
                    refreshDiscovery()
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .foregroundColor(Theme.primary)
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel("Refresh PlayBridge TVs")
            }

            if sortedSavedPlayBridgeDevices.isEmpty && unpairedPlayBridgeDevices.isEmpty {
                emptyHint(
                    discoveryIsScanning
                        ? "Looking for PlayBridge TVs on your network…"
                        : "No PlayBridge TVs yet. Open PlayBridge on your TV, then refresh."
                )
            } else {
                ForEach(Array(sortedSavedPlayBridgeDevices.enumerated()), id: \.offset) { _, device in
                    ConnectionPairedDeviceRow(
                        device: device,
                        online: vm.onlineStatus[vm.deviceKey(device)] == true,
                        onSelect: { vm.connectSaved(device) },
                        onRemove: { vm.forget(device) }
                    )
                }
                ForEach(unpairedPlayBridgeDevices) { device in
                    let display = PairedDevice(
                        ip: device.ip,
                        port: device.port,
                        name: device.name,
                        uuid: device.uuid,
                        wssPort: device.wssPort
                    )
                    ConnectionPairedDeviceRow(
                        device: display,
                        online: true,
                        onSelect: { vm.connect(to: device) }
                    )
                }
            }
        }
    }

    @ViewBuilder private var recentOtherSection: some View {
        if !recentOtherDevices.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                ConnectionSectionLabel("Recent other")
                ForEach(recentOtherDevices, id: \.identity) { device in
                    ConnectionExternalDeviceRow(
                        device: device,
                        online: isExternalDeviceOnline(device),
                        onSelect: { vm.connectExternalReceiver(device) },
                        onRemove: { vm.forgetExternalReceiver(device) }
                    )
                }
            }
        }
    }

    private var otherDevicesSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation { showOtherDevices.toggle() }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        ConnectionSectionLabel("Other devices on this network")
                        Text(discoveryIsScanning ? "Scanning quietly…" : "DLNA, Roku, Google Cast")
                            .font(Theme.font(.caption))
                            .foregroundColor(Theme.onSurfaceVariant)
                    }
                    Spacer()
                    Image(systemName: showOtherDevices ? "chevron.up" : "chevron.down")
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if showOtherDevices {
                externalProtocolSection("Google Cast", devices: externalDevices(protocolID: "google_cast"))
                externalProtocolSection("DLNA", devices: externalDevices(protocolID: "dlna"))
                manualDLNAControls
                externalProtocolSection("Roku", devices: externalDevices(protocolID: "roku"))
                manualRokuControls

                if showDIAL {
                    Text("DIAL")
                        .font(Theme.font(size: 14, weight: .semibold))
                        .foregroundColor(Theme.onSurfaceVariant)
                    Text("DIAL finds devices that launch receiver apps; generic video sending is unavailable.")
                        .font(Theme.font(.caption))
                        .foregroundColor(Theme.onSurfaceVariant)
                    ForEach(vm.dialBrowser.devices, id: \.identity) { device in
                        ConnectionExternalDeviceRow(
                            device: device,
                            online: true,
                            onSelect: {
                                vm.operationError = "DIAL devices require a supported receiver app; generic video sending is unavailable."
                            }
                        )
                    }
                }

                if allVisibleExternalDevices.isEmpty && !showDIAL {
                    emptyHint(
                        discoveryIsScanning
                            ? "Looking for cast devices…"
                            : "No other devices found. Tap refresh to scan again."
                    )
                }
            }
        }
    }

    private var manualDLNAControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("Connect DLNA by URL…") { withAnimation { showManualDLNA.toggle() } }
                .font(Theme.font(.subheadline))
                .foregroundColor(Theme.primary)
            if showManualDLNA {
                TextField("DLNA device description URL", text: $dlnaLocation)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                Button("Connect") {
                    let location = dlnaLocation.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let device = DLNABrowser.device(from: [
                        "protocol": "Dlna",
                        "id": location,
                        "location": location
                    ]) {
                        vm.connectExternalReceiver(device)
                    } else {
                        vm.operationError = "Enter a valid HTTP or HTTPS device description URL."
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.primaryDim)
                .disabled(dlnaLocation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private var manualRokuControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("Connect Roku by IP…") { withAnimation { showManualRoku.toggle() } }
                .font(Theme.font(.subheadline))
                .foregroundColor(Theme.primary)
            if showManualRoku {
                TextField("Roku IP address", text: $rokuAddress)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                Button("Connect") {
                    let input = rokuAddress.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard let device = DLNABrowser.manualRoku(input) else {
                        vm.operationError = "Enter a Roku IP address or HTTP address, optionally including its port."
                        return
                    }
                    vm.connectExternalReceiver(device)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.primaryDim)
                .disabled(rokuAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    @ViewBuilder
    private func externalProtocolSection(_ title: String, devices: [ExternalReceiverDevice]) -> some View {
        if !devices.isEmpty {
            Text(title)
                .font(Theme.font(size: 14, weight: .semibold))
                .foregroundColor(Theme.onSurfaceVariant)
                .padding(.top, 2)
            ForEach(devices, id: \.identity) { device in
                let saved = vm.savedExternalReceiverDevices.contains { $0.identity == device.identity }
                ConnectionExternalDeviceRow(
                    device: device,
                    online: isExternalDeviceOnline(device),
                    onSelect: { vm.connectExternalReceiver(device) },
                    onRemove: saved ? { vm.forgetExternalReceiver(device) } : nil
                )
            }
        }
    }

    private var allVisibleExternalDevices: [ExternalReceiverDevice] {
        externalDevices(protocolID: "google_cast") +
            externalDevices(protocolID: "dlna") +
            externalDevices(protocolID: "roku")
    }

    private func externalDevices(protocolID: String) -> [ExternalReceiverDevice] {
        let live = (vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices)
            .filter { $0.protocolID == protocolID }
        let liveIDs = Set(live.map(\.identity))
        return (live + vm.savedExternalReceiverDevices.filter {
            $0.protocolID == protocolID && !liveIDs.contains($0.identity)
        }).filter {
            !(vm.isConnected && vm.externalReceiver?.identity == $0.identity)
        }
    }

    private func isExternalDeviceOnline(_ device: ExternalReceiverDevice) -> Bool {
        (vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices)
            .contains { $0.identity == device.identity }
    }

    private func samePlayBridgeDevice(_ discovered: DiscoveredDevice, _ saved: PairedDevice) -> Bool {
        if !discovered.uuid.isEmpty && !saved.uuid.isEmpty { return discovered.uuid == saved.uuid }
        return discovered.ip == saved.ip && discovered.port == saved.port
    }

    private func refreshDiscovery() {
        vm.stopDiscovery()
        vm.startDiscovery()
        vm.pingSavedDevices()
    }

    private func emptyHint(_ message: String) -> some View {
        Text(message)
            .font(Theme.font(.subheadline))
            .foregroundColor(Theme.onSurfaceVariant)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 14))
    }

    // MARK: - SAS pairing code

    @ViewBuilder private var pairingCodeSection: some View {
        switch vm.state {
        case .waitingForCodeInput(let name, let attemptsLeft, let lastWrong):
            VStack(alignment: .leading, spacing: 12) {
                Text("Enter the 6-digit code shown on \(name)")
                    .font(Theme.font(.subheadline))
                    .foregroundColor(Theme.onSurface)
                TextField("000000", text: $pairingCode)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(Theme.font(size: 28, weight: .bold, design: .monospaced))
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
                        .font(Theme.font(.caption))
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
        HStack(spacing: 12) {
            DashboardNavigationButton()

            Text("Devices")
                .font(Theme.font(size: 20, weight: .semibold))
                .foregroundColor(Theme.onSurface)
            Spacer()

            Button(action: refreshDiscovery) {
                if discoveryIsScanning {
                    ProgressView()
                        .tint(Theme.primary)
                        .frame(width: 36, height: 36)
                } else {
                    Image(systemName: "arrow.clockwise")
                        .foregroundColor(Theme.primary)
                        .frame(width: 36, height: 36)
                }
            }
            .accessibilityLabel("Rescan network")

            Button {
                nav.navigate(to: .remote)
            } label: {
                Image(systemName: "gamecontroller.fill")
                    .foregroundColor(Theme.primary)
                    .frame(width: 36, height: 36)
            }

            Menu {
                Button("Connect by IP…", systemImage: "plus") {
                    withAnimation { showManualConnect.toggle() }
                }
                Button("Search app receivers (DIAL)", systemImage: "antenna.radiowaves.left.and.right") {
                    showDIAL = true
                    showOtherDevices = true
                    vm.dialBrowser.start()
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundColor(Theme.onSurface)
                    .frame(width: 36, height: 36)
            }
            .accessibilityLabel("More")
        }
        .padding(.top, 8)
        .padding(.bottom, 4)
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
            if let message = vm.operationError {
                banner(message, systemImage: "exclamationmark.triangle", tint: Theme.danger)
            }
        }
    }

    private func banner(_ text: String, systemImage: String, tint: Color) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage).foregroundColor(tint)
            Text(text).font(Theme.font(.subheadline)).foregroundColor(Theme.onSurface)
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceContainer)
        .cornerRadius(14)
    }

    // MARK: - Manual

    private var manualSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            ConnectionSectionLabel("Manual connect")
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

}
