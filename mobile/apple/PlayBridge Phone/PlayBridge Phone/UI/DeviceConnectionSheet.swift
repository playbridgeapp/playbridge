import SwiftUI
import Network
import UIKit

/// The Play on sheet. Pages slide inside this same sheet; the system back gesture
/// pops a page, and dragging the sheet closed leaves the app on the screen underneath.
struct DeviceConnectionSheet: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var atv: AtvRemoteCoordinator
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @StateObject private var pathMonitor = PlayOnPathMonitor()

    private let openedForPicking: Bool
    @State private var path: [PlayOnPage]
    @State private var pairingCode = ""
    @State private var showUnavailable = false
    @State private var vpnDismissed = false
    @State private var userPicked = false
    @State private var pendingExternalID: String?
    @State private var manualIP = ""
    @State private var rokuAddress = ""
    @State private var dlnaLocation = ""
    @State private var showAtvPairing = false
    @State private var showPairedTVs = false

    init(initialPage: PlayOnPage? = nil, openedForPicking: Bool = true) {
        self.openedForPicking = openedForPicking
        _path = State(initialValue: initialPage.map { [$0] } ?? [])
    }

    var body: some View {
        NavigationStack(path: $path) {
            mainPage
                .navigationTitle("Play on")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        VStack(spacing: 0) {
                            Text("Play on")
                                .font(Theme.font(size: 17, weight: .bold))
                                .foregroundColor(Theme.onSurface)
                            Text(PlayOnCatalog.networkSubtitle(onWiFi: pathMonitor.onWiFi, wifiName: nil))
                                .font(Theme.font(size: 12))
                                .foregroundColor(pathMonitor.onWiFi ? Theme.onSurfaceVariant : Theme.tertiary)
                        }
                        .accessibilityElement(children: .combine)
                    }
                    ToolbarItem(placement: .cancellationAction) {
                        Button(action: close) {
                            Image(systemName: "xmark")
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("Close")
                    }
                }
                .navigationDestination(for: PlayOnPage.self) { page in
                    switch page {
                    case .pairing: pairingPage
                    case .add: addPage
                    case .actions(let id): actionsPage(id)
                    case .configure: configurePage
                    case .manualPlayBridge: manualPlayBridgePage
                    case .manualRoku: manualRokuPage
                    case .manualDLNA: manualDLNAPage
                    }
                }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("play-on-sheet")
        .sheet(isPresented: $showAtvPairing) { AtvPairingSheet(coordinator: atv) }
        .sheet(isPresented: $showPairedTVs) { AtvPairedTVsSheet(coordinator: atv) }
        .onAppear {
            nav.playOnSuppressCount += 1
            vm.startDiscovery()
            vm.pingSavedDevices()
            pathMonitor.start()
        }
        .onDisappear {
            nav.playOnSuppressCount = max(0, nav.playOnSuppressCount - 1)
            vm.stopDiscovery()
            pathMonitor.stop()
        }
        .onChange(of: pathMonitor.vpnActive) { active in
            if !active { vpnDismissed = false }
        }
        .onChange(of: vm.state) { state in
            switch state {
            case .waitingForCodeInput(_, _, let wrong):
                if wrong { pairingCode = "" }
                showPairingPage()
            case .verifyingCode, .waitingForApproval:
                showPairingPage()
            case .connected:
                guard pendingExternalID == nil || vm.externalReceiver?.identity == pendingExternalID else { return }
                pendingExternalID = nil
                if userPicked || (openedForPicking && path.contains(.pairing)) {
                    userPicked = false
                    dismiss()
                } else if path.contains(.pairing) {
                    path = []
                }
            default:
                break
            }
        }
        .onChange(of: path) { newPath in
            guard !newPath.contains(.pairing) else { return }
            switch vm.state {
            case .waitingForCodeInput, .verifyingCode, .waitingForApproval:
                vm.disconnect()
            default:
                break
            }
        }
    }

    private var mainPage: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    networkBanners
                    if showNowCard { nowCard }
                    devicesSection
                    nearbySection
                    unavailableSection
                    if !showNowCard { firstRunTip }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 12)
            }
            .refreshable { vm.pingSavedDevices() }
            addDeviceButton
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
        }
        .background(Theme.surface.ignoresSafeArea())
    }

    @ViewBuilder private var networkBanners: some View {
        if !pathMonitor.onWiFi {
            banner(title: "Not on Wi‑Fi",
                   message: "Connect this phone to the same Wi‑Fi as your TV to cast and discover devices.",
                   systemImage: "wifi.slash",
                   tint: Theme.tertiary) {
                Button("Wi‑Fi settings") { openSettings() }
                    .font(Theme.font(size: 14, weight: .semibold))
                    .frame(minHeight: 44)
            }
        } else if pathMonitor.vpnActive && !vpnDismissed {
            banner(title: "VPN may block casting",
                   message: "Some VPNs hide your local network. If devices don’t appear, pause the VPN and try again.",
                   systemImage: "shield",
                   tint: Theme.primary) {
                Button { vpnDismissed = true } label: {
                    Image(systemName: "xmark").frame(width: 44, height: 44)
                }
                .accessibilityLabel("Dismiss")
            }
        }
    }

    private var nowCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: nowSymbol)
                    .font(Theme.font(size: 22))
                    .foregroundColor(Theme.onSecondaryContainer)
                    .frame(width: 40, height: 40)
                    .background(Theme.onSecondaryContainer.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(nowName)
                        .font(Theme.font(size: 16, weight: .semibold))
                        .foregroundColor(Theme.onSecondaryContainer)
                        .lineLimit(1)
                    Text(nowStatusText)
                        .font(Theme.font(size: 13))
                        .foregroundColor(showsFailure && !vm.isConnected ? Theme.danger : Theme.onSecondaryContainer)
                    if let failureDetail {
                        Text(failureDetail)
                            .font(Theme.font(size: 12))
                            .foregroundColor(Theme.onSecondaryContainer.opacity(0.85))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    nav.navigate(to: .remote)
                    dismiss()
                } label: {
                    Label("Remote", systemImage: "av.remote")
                        .font(Theme.font(size: 14, weight: .semibold))
                        .frame(minHeight: 44)
                }
                .accessibilityLabel("Remote")
            }
            HStack(spacing: 8) {
                if showConfigure {
                    Button { path.append(.configure) } label: {
                        Label("Configure TV", systemImage: "slider.horizontal.3")
                            .font(Theme.font(size: 14, weight: .semibold))
                            .frame(minHeight: 44)
                    }
                }
                if let row = vm.selectedRemoteTV, row.paired, !row.ambiguous, !vm.isConnected {
                    Button {
                        vm.requestOpenPlayBridge()
                        nav.navigate(to: .remote)
                        dismiss()
                    } label: {
                        Text("Open PlayBridge on \(row.device.name)")
                            .font(Theme.font(size: 14, weight: .semibold))
                            .frame(minHeight: 44)
                    }
                }
                Spacer(minLength: 0)
                Button(vm.isAirPlay ? "Stop using AirPlay" : "Disconnect") {
                    vm.disconnect()
                }
                .font(Theme.font(size: 14, weight: .semibold))
                .foregroundColor(Theme.danger)
                .frame(minHeight: 44)
            }
            if showsFailure && !vm.isConnected {
                ShareLink(item: diagnosticsText) {
                    Label("Share diagnostics", systemImage: "square.and.arrow.up")
                        .font(Theme.font(size: 14, weight: .semibold))
                        .frame(minHeight: 44)
                }
            }
        }
        .padding(14)
        .background(Theme.secondaryContainer.opacity(0.78), in: RoundedRectangle(cornerRadius: 16))
    }

    private var devicesSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            sectionLabel("Devices")
            phoneRow
            AirPlayDestinationRow()
            ForEach(sections.ready) { row in
                deviceRow(row)
            }
        }
    }

    private var phoneRow: some View {
        Button {
            vm.disconnect()
            dismiss()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "iphone")
                    .font(Theme.font(size: 20))
                    .foregroundColor(Theme.primary)
                    .frame(width: 28, height: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("This phone")
                        .font(Theme.font(size: 15, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                    Text("Play here")
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                Spacer(minLength: 0)
                if thisPhoneSelected {
                    Image(systemName: "checkmark")
                        .font(Theme.font(size: 16, weight: .bold))
                        .foregroundColor(Theme.primary)
                        .accessibilityLabel("Selected")
                }
            }
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(thisPhoneSelected ? .isSelected : [])
    }

    @ViewBuilder private var nearbySection: some View {
        if scanning || !sections.nearby.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    sectionLabel("Nearby")
                    Spacer()
                    if scanning {
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small)
                            Text("Searching")
                                .font(Theme.font(size: 12))
                                .foregroundColor(Theme.onSurfaceVariant)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("Searching")
                    }
                }
                ForEach(sections.nearby) { row in
                    deviceRow(row)
                }
            }
        }
    }

    @ViewBuilder private var unavailableSection: some View {
        if !sections.unavailable.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Button {
                    withAnimation { showUnavailable.toggle() }
                } label: {
                    HStack {
                        Text("Not available now · \(sections.unavailable.count)")
                            .font(Theme.font(size: 14))
                            .foregroundColor(Theme.onSurfaceVariant)
                        Spacer()
                        Image(systemName: showUnavailable ? "chevron.up" : "chevron.down")
                            .foregroundColor(Theme.onSurfaceVariant)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Not available now, \(sections.unavailable.count)")
                .accessibilityValue(showUnavailable ? "Expanded" : "Collapsed")
                if showUnavailable {
                    ForEach(sections.unavailable) { row in
                        deviceRow(row, dimmed: true)
                    }
                }
            }
        }
    }

    private var firstRunTip: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Don’t see your TV?")
                .font(Theme.font(size: 14, weight: .semibold))
                .foregroundColor(Theme.onSurface)
            Text("Open the PlayBridge app on your TV and make sure it’s on the same Wi‑Fi as this phone.")
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
    }

    private var addDeviceButton: some View {
        Button { path.append(.add) } label: {
            Label("Add a device", systemImage: "plus")
                .font(Theme.font(size: 15, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.plain)
        .foregroundColor(Theme.primary)
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.outlineVariant))
    }

    private func deviceRow(_ row: PlayOnSheetRow, badge: String? = nil, dimmed: Bool = false) -> some View {
        let newBadge = badge ?? (row.listed.kind == .playBridge && row.listed.needsPairing ? "New" : nil)
        return HStack(spacing: 0) {
            Button { select(row) } label: {
                HStack(spacing: 12) {
                    Image(systemName: symbol(for: row.listed.kind))
                        .font(Theme.font(size: 20))
                        .foregroundColor(dimmed ? Theme.onSurfaceVariant : Theme.primary)
                        .frame(width: 28, height: 28)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.listed.name)
                            .font(Theme.font(size: 15, weight: .semibold))
                            .foregroundColor(dimmed ? Theme.onSurfaceVariant : Theme.onSurface)
                            .lineLimit(1)
                        Text(rowSubtitle(row))
                            .font(Theme.font(size: 13))
                            .foregroundColor(Theme.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if let newBadge {
                        Text(newBadge)
                            .font(Theme.font(size: 12, weight: .semibold))
                            .foregroundColor(Theme.onSecondaryContainer)
                            .padding(.horizontal, 9)
                            .frame(minHeight: 24)
                            .background(Theme.secondaryContainer, in: Capsule())
                    }
                }
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if row.listed.saved {
                Button { path.append(.actions(row.id)) } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("More options for \(row.listed.name)")
            }
        }
    }

    private var pairingPage: some View {
        let name = pairingName
        return VStack(spacing: 18) {
            Text("Enter the 6-digit code shown on \(name).")
                .font(Theme.font(size: 15))
                .foregroundColor(Theme.onSurface)
                .multilineTextAlignment(.center)
                .padding(.top, 12)
            pairingEntry
            if case .waitingForCodeInput(_, let attempts, true) = vm.state {
                Text("Incorrect code — \(attempts) \(attempts == 1 ? "try" : "tries") left")
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.danger)
            }
            Text("No code on the TV? Make sure the PlayBridge app is open on it, then tap the TV again.")
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle("Pair \(name)")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private var pairingEntry: some View {
        switch vm.state {
        case .waitingForApproval:
            ProgressView()
            Text("Approve on the TV")
                .font(Theme.font(size: 16, weight: .semibold))
                .foregroundColor(Theme.onSurface)
        case .verifyingCode:
            ProgressView()
            Text("Checking the code…")
                .font(Theme.font(size: 16, weight: .semibold))
                .foregroundColor(Theme.onSurface)
        default:
            ZStack {
                HStack(spacing: 8) {
                    ForEach(0..<6, id: \.self) { index in
                        Text(digit(index))
                            .font(Theme.font(size: 24, weight: .bold, design: .monospaced))
                            .foregroundColor(Theme.onSurface)
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(index == pairingCode.count ? Theme.primary : Theme.outlineVariant, lineWidth: index == pairingCode.count ? 2 : 1))
                    }
                }
                .accessibilityHidden(true)
                TextField("000000", text: $pairingCode)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .foregroundColor(.clear)
                    .tint(.clear)
                    .accessibilityLabel("Pairing code")
                    .onChange(of: pairingCode) { newValue in
                        let filtered = String(newValue.filter(\.isNumber).prefix(6))
                        if filtered != newValue { pairingCode = filtered }
                        if filtered.count == 6 { vm.submitPairingCode(filtered) }
                    }
            }
            Text("Pairs automatically when all six digits are in")
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
        }
    }

    private var addPage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("TVs on your Wi‑Fi show up automatically under Nearby. Use these when one doesn’t.")
                    .font(Theme.font(size: 14))
                    .foregroundColor(Theme.onSurfaceVariant)
                VStack(spacing: 0) {
                    addLink(.manualPlayBridge, title: "PlayBridge TV by IP address",
                            subtitle: "When it’s on another network segment or discovery is blocked",
                            systemImage: "tv")
                    addLink(.manualRoku, title: "Roku by IP address",
                            subtitle: "Find it under Settings › Network on the Roku",
                            systemImage: "tv")
                    addLink(.manualDLNA, title: "DLNA device by URL",
                            subtitle: "Paste its description URL",
                            systemImage: "link")
                }
                .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
                VStack(alignment: .leading, spacing: 10) {
                    Text("If a TV won’t show up")
                        .font(Theme.font(size: 14, weight: .semibold))
                    tip("1", "Open PlayBridge on the TV and leave it on the home screen.")
                    tip("2", "Put the phone and TV on the same Wi‑Fi. Guest networks often block devices.")
                    tip("3", "Turn off VPN on this phone.")
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
                ShareLink(item: diagnosticsText) {
                    Text("Share connection diagnostics")
                        .font(Theme.font(size: 14, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
            }
            .padding(16)
        }
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle("Add a device")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func actionsPage(_ id: String) -> some View {
        let row = rowIndex[id]
        return VStack(alignment: .leading, spacing: 8) {
            if let row {
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.listed.name)
                        .font(Theme.font(size: 20, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                    Text(PlayOnCatalog.actionsSubtitle(protocolName: row.listed.protocolName, address: row.listed.address, used: usedText(row.listed.lastConnected)))
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                .padding(.bottom, 8)
                actionButton("Play on this TV", systemImage: "play.tv") { select(row) }
                if row.playBridge != nil {
                    Button { openFullTVRemote(row) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "av.remote").frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Full TV remote")
                                    .font(Theme.font(size: 15, weight: .semibold))
                                Text("Power, volume and apps — pair once with a code")
                                    .font(Theme.font(size: 13))
                                    .foregroundColor(Theme.onSurfaceVariant)
                            }
                            Spacer()
                            Text(row.paired && !row.ambiguous ? "Paired" : "Set up")
                                .font(Theme.font(size: 12, weight: .semibold))
                                .foregroundColor(Theme.onSecondaryContainer)
                                .padding(.horizontal, 9)
                                .frame(minHeight: 24)
                                .background(Theme.secondaryContainer, in: Capsule())
                        }
                        .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Full TV remote, \(row.paired && !row.ambiguous ? "Paired" : "Set up")")
                }
                Button(role: .destructive) { forget(row) } label: {
                    Label(row.playBridge == nil ? "Forget this device" : "Forget this TV", systemImage: "trash")
                        .font(Theme.font(size: 15, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                }
                .foregroundColor(Theme.danger)
            } else {
                Text("This device is no longer available.")
                    .foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle("Device")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var configurePage: some View {
        let options = TvCapabilityOptions.playerOptions(for: vm.pairedDevice).filter { $0.id != "tv" }
        let selected = vm.coordinator.playerEngine
        return ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                sectionLabel("Playback")
                if options.isEmpty {
                    Text("This TV hasn’t reported a video player yet.")
                        .font(Theme.font(size: 14))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(options.enumerated()), id: \.element.id) { index, option in
                            Button { vm.control("switch_player:\(option.id)") } label: {
                                HStack(spacing: 12) {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(option.label)
                                            .font(Theme.font(size: 15, weight: .semibold))
                                            .foregroundColor(Theme.onSurface)
                                        Text(PlayOnPlayerCopy.detail(for: option.id))
                                            .font(Theme.font(size: 13))
                                            .foregroundColor(Theme.onSurfaceVariant)
                                    }
                                    Spacer(minLength: 0)
                                    Image(systemName: selected == option.id ? "largecircle.fill.circle" : "circle")
                                        .foregroundColor(selected == option.id ? Theme.primary : Theme.onSurfaceVariant)
                                }
                                .padding(14)
                                .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(option.label), \(PlayOnPlayerCopy.detail(for: option.id))")
                            .accessibilityAddTraits(selected == option.id ? .isSelected : [])
                            if index < options.count - 1 {
                                Divider().overlay(Theme.outlineVariant)
                            }
                        }
                    }
                    .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
                }
                Text("Changes apply on the TV right away.")
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .padding(.horizontal, 4)
            }
            .padding(16)
        }
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle("Configure TV")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text("Configure TV")
                        .font(Theme.font(size: 17, weight: .bold))
                    Text("\(nowName) · Connected")
                        .font(Theme.font(size: 12))
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private var manualPlayBridgePage: some View {
        manualForm(title: "PlayBridge TV by IP address", prompt: "TV IP address", text: $manualIP, keyboard: .numbersAndPunctuation) {
            let ip = manualIP.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !ip.isEmpty else { return }
            userPicked = true
            vm.connectManual(ip: ip)
        }
    }

    private var manualRokuPage: some View {
        manualForm(title: "Roku by IP address", prompt: "Roku IP address", text: $rokuAddress, keyboard: .URL) {
            let input = rokuAddress.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let device = DLNABrowser.manualRoku(input) else {
                vm.operationError = "Enter a Roku IP address or HTTP address, optionally including its port."
                return
            }
            pickExternal(device)
        }
    }

    private var manualDLNAPage: some View {
        manualForm(title: "DLNA device by URL", prompt: "Description URL", text: $dlnaLocation, keyboard: .URL) {
            let location = dlnaLocation.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let device = DLNABrowser.device(from: ["protocol": "Dlna", "id": location, "location": location]) else {
                vm.operationError = "Enter a valid HTTP or HTTPS device description URL."
                return
            }
            pickExternal(device)
        }
    }

    private func manualForm(title: String, prompt: String, text: Binding<String>, keyboard: UIKeyboardType, connect: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField(prompt, text: text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(keyboard)
                .padding(12)
                .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
            Button("Connect", action: connect)
                .font(Theme.font(size: 16, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundColor(Theme.onPrimary)
                .background(Theme.primaryDim, in: RoundedRectangle(cornerRadius: 14))
                .disabled(text.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Spacer(minLength: 0)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func addLink(_ page: PlayOnPage, title: String, subtitle: String, systemImage: String) -> some View {
        Button { path.append(page) } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .foregroundColor(Theme.primary)
                    .frame(width: 40, height: 40)
                    .background(Theme.secondaryContainer, in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(Theme.font(size: 15, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                    Text(subtitle)
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundColor(Theme.onSurfaceVariant)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func actionButton(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(Theme.font(size: 15, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func tip(_ number: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(number).font(Theme.font(size: 13, weight: .bold)).foregroundColor(Theme.primary)
            Text(text).font(Theme.font(size: 13)).foregroundColor(Theme.onSurfaceVariant)
        }
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(Theme.font(size: 12, weight: .bold))
            .foregroundColor(Theme.onSurfaceVariant)
            .tracking(0.8)
            .padding(.top, 4)
    }

    private func banner<Accessory: View>(title: String, message: String, systemImage: String, tint: Color, @ViewBuilder accessory: () -> Accessory) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .foregroundColor(tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(Theme.font(size: 15, weight: .semibold)).foregroundColor(Theme.onSurface)
                Text(message).font(Theme.font(size: 13)).foregroundColor(Theme.onSurfaceVariant)
                accessory()
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .contain)
    }

    private var sections: (ready: [PlayOnSheetRow], nearby: [PlayOnSheetRow], unavailable: [PlayOnSheetRow]) {
        let groups = PlayOnCatalog.group(saved: savedRows.map(\.listed), discovered: nearbyRows.map(\.listed))
        func resolve(_ devices: [PlayOnListedDevice]) -> [PlayOnSheetRow] {
            devices.compactMap { rowIndex[$0.id] }
        }
        return (resolve(groups.reachableSaved), resolve(groups.nearby), resolve(groups.unavailable))
    }

    private var rowIndex: [String: PlayOnSheetRow] {
        var index: [String: PlayOnSheetRow] = [:]
        for row in savedRows { index[row.id] = row }
        for row in nearbyRows where index[row.id] == nil { index[row.id] = row }
        return index
    }

    private var savedRows: [PlayOnSheetRow] {
        var rows: [PlayOnSheetRow] = []
        for device in vm.savedDevices {
            let key = vm.deviceKey(device)
            let id = "pb:\(key)"
            if id == activePlayBridgeID { continue }
            let remote = vm.remoteTVRows.first { vm.deviceKey($0.device) == key }
            let reachable = vm.onlineStatus[key] == true || remote?.receiverReachable == true || remote?.tvReachable == true
            var keys: Set<String> = [id, key, "\(device.ip):\(device.port)"]
            if !device.uuid.isEmpty { keys.insert(device.uuid) }
            rows.append(PlayOnSheetRow(
                listed: PlayOnListedDevice(id: id, name: device.name, kind: .playBridge, address: "\(device.ip):\(device.port)",
                                            reachable: reachable, saved: true, lastConnected: device.lastConnected, needsPairing: false, matchKeys: keys),
                playBridge: device, external: nil, paired: remote?.paired == true, ambiguous: remote?.ambiguous == true))
        }
        let liveIDs = Set((vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices).map(\.identity))
        for (index, device) in vm.savedExternalReceiverDevices.enumerated() where device.protocolID != "dial" {
            let id = "ex:\(device.identity)"
            if id == activeExternalID { continue }
            let address = device.addresses.first.map { "\($0):\(device.port)" } ?? device.model
            rows.append(PlayOnSheetRow(
                listed: PlayOnListedDevice(id: id, name: device.name, kind: kind(device.protocolID), address: address,
                                            reachable: liveIDs.contains(device.identity), saved: true,
                                            lastConnected: Date(timeIntervalSince1970: TimeInterval(1_000_000 - index)),
                                            needsPairing: false, matchKeys: [id, device.identity]),
                playBridge: nil, external: device, paired: false, ambiguous: false))
        }
        return rows
    }

    private var nearbyRows: [PlayOnSheetRow] {
        var rows: [PlayOnSheetRow] = []
        for device in vm.browser.devices {
            let key = device.uuid.isEmpty ? "\(device.ip):\(device.port)" : device.uuid
            let id = "pb:\(key)"
            if id == activePlayBridgeID { continue }
            var keys: Set<String> = [id, key, "\(device.ip):\(device.port)"]
            if !device.uuid.isEmpty { keys.insert(device.uuid) }
            rows.append(PlayOnSheetRow(
                listed: PlayOnListedDevice(id: id, name: device.name, kind: .playBridge, address: "\(device.ip):\(device.port)",
                                            reachable: true, saved: false, lastConnected: nil, needsPairing: true, matchKeys: keys),
                playBridge: nil, external: nil, discovered: device, paired: false, ambiguous: false))
        }
        for device in vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices where device.protocolID != "dial" {
            let id = "ex:\(device.identity)"
            if id == activeExternalID { continue }
            let address = device.addresses.first.map { "\($0):\(device.port)" } ?? device.model
            rows.append(PlayOnSheetRow(
                listed: PlayOnListedDevice(id: id, name: device.name, kind: kind(device.protocolID), address: address,
                                            reachable: true, saved: false, lastConnected: nil, needsPairing: false, matchKeys: [id, device.identity]),
                playBridge: nil, external: device, paired: false, ambiguous: false))
        }
        return rows
    }

    private var activePlayBridgeID: String? {
        guard vm.nativeReceiverSelected, !vm.isExternalReceiver, !vm.isAirPlay, let device = vm.pairedDevice else { return nil }
        guard vm.isConnected || vm.connectionIsConnecting || vm.selectedRemoteTV != nil || showsFailure else { return nil }
        return "pb:\(vm.deviceKey(device))"
    }

    private var activeExternalID: String? {
        guard let device = vm.externalReceiver, vm.isConnected || vm.connectionIsConnecting || showsFailure else { return nil }
        return "ex:\(device.identity)"
    }

    private var scanning: Bool {
        vm.browser.isScanning || vm.googleCastBrowser.isScanning || vm.dlnaBrowser.isScanning || vm.rokuBrowser.isScanning
    }

    private var thisPhoneSelected: Bool {
        !vm.nativeReceiverSelected && !vm.isAirPlay && vm.externalReceiver == nil && !vm.isConnected && !vm.connectionIsConnecting
    }

    private var showNowCard: Bool {
        vm.isAirPlay || vm.isConnected || vm.connectionIsConnecting || showsFailure
            || (vm.nativeReceiverSelected && vm.selectedRemoteTV != nil)
            || vm.externalReceiver != nil
    }

    private var showConfigure: Bool {
        vm.isConnected && vm.nativeReceiverSelected && !vm.isExternalReceiver && !vm.isAirPlay
    }

    private var showsFailure: Bool {
        switch vm.state {
        case .error, .authFailed, .pinMismatch, .pairingDenied: return true
        default: return false
        }
    }

    private var nowName: String {
        if vm.isAirPlay { return vm.airPlay.routeName }
        switch vm.state {
        case .connected(let name, _): return name
        case .waitingForApproval(let name), .waitingForCodeInput(let name, _, _), .verifyingCode(let name),
                .pairingDenied(let name), .pinMismatch(let name):
            return name.isEmpty ? fallbackName : name
        default:
            return fallbackName
        }
    }

    private var fallbackName: String {
        vm.externalReceiver?.name ?? vm.selectedRemoteTV?.device.name ?? vm.pairedDevice?.name ?? "TV"
    }

    private var pairingName: String {
        switch vm.state {
        case .waitingForCodeInput(let name, _, _), .verifyingCode(let name), .waitingForApproval(let name):
            return name.isEmpty ? fallbackName : name
        default:
            return fallbackName
        }
    }

    private var protocolName: String {
        if vm.isAirPlay { return "AirPlay" }
        return vm.externalReceiver?.protocolName ?? "PlayBridge"
    }

    private var nowSymbol: String {
        vm.isAirPlay || vm.isExternalReceiver ? "airplayvideo" : "tv"
    }

    private var nowStatusText: String {
        if vm.isConnected || vm.connectionIsConnecting || (showsFailure && !vm.isAirPlay) {
            return PlayOnCatalog.nowStatus(connecting: vm.connectionIsConnecting && !vm.isConnected,
                                            failed: showsFailure && !vm.isConnected,
                                            castingTitle: castingTitle,
                                            protocolName: protocolName)
        }
        if vm.isAirPlay { return vm.airPlay.routeAvailable ? "Connected · AirPlay" : "Couldn’t connect" }
        if let row = vm.selectedRemoteTV { return row.status }
        return "Couldn’t connect"
    }

    private var castingTitle: String? {
        guard vm.isConnected, let playback = vm.coordinator.playback else { return nil }
        guard ["playing", "paused", "buffering"].contains(playback.state.lowercased()) else { return nil }
        let title = playback.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return title.isEmpty ? nil : title
    }

    private var failureDetail: String? {
        guard showsFailure, !vm.isConnected else { return nil }
        switch vm.state {
        case .error(let message): return message
        case .authFailed: return "The saved pairing was rejected. Pair again."
        case .pinMismatch: return "This TV’s security fingerprint changed. Forget it and pair again."
        case .pairingDenied(let name): return "\(name) denied the pairing request."
        default: return nil
        }
    }

    private var diagnosticsText: String {
        PlayOnCatalog.connectionDiagnostics(state: stateLabel, destination: showNowCard ? nowName : nil,
                                             protocolName: showNowCard ? protocolName : nil, scanning: scanning)
    }

    private var stateLabel: String {
        switch vm.state {
        case .disconnected: return "disconnected"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .waitingForApproval: return "waiting for approval"
        case .waitingForCodeInput: return "waiting for code"
        case .verifyingCode: return "verifying code"
        case .pairingDenied: return "pairing denied"
        case .retrying: return "retrying"
        case .error: return "error"
        case .authFailed: return "auth failed"
        case .pinMismatch: return "pin mismatch"
        }
    }

    private func rowSubtitle(_ row: PlayOnSheetRow) -> String {
        if row.listed.saved {
            return PlayOnCatalog.savedSubtitle(protocolName: row.listed.protocolName, reachable: row.listed.reachable, used: usedText(row.listed.lastConnected))
        }
        return PlayOnCatalog.nearbySubtitle(kind: row.listed.kind, needsPairing: row.listed.needsPairing, protocolName: row.listed.protocolName)
    }

    private func usedText(_ date: Date?) -> String? {
        date.map { PlayOnRelativeTime.used($0) }
    }

    private func symbol(for kind: PlayOnListedDevice.Kind) -> String {
        switch kind {
        case .playBridge: return "tv"
        case .googleCast: return "airplayvideo"
        case .dlna: return "hifispeaker"
        case .roku: return "tv"
        case .browser: return "globe"
        }
    }

    private func kind(_ protocolID: String) -> PlayOnListedDevice.Kind {
        switch protocolID {
        case "dlna": return .dlna
        case "roku": return .roku
        default: return .googleCast
        }
    }

    private func digit(_ index: Int) -> String {
        guard index < pairingCode.count else { return "" }
        let character = pairingCode[pairingCode.index(pairingCode.startIndex, offsetBy: index)]
        return String(character)
    }

    private func select(_ row: PlayOnSheetRow) {
        if let device = row.playBridge {
            userPicked = true
            vm.connectSaved(device)
        } else if let device = row.external {
            pickExternal(device)
        } else if let device = row.discovered {
            userPicked = true
            vm.connect(to: device)
        }
    }

    private func pickExternal(_ device: ExternalReceiverDevice) {
        pendingExternalID = device.identity
        userPicked = true
        vm.connectExternalReceiver(device)
    }

    private func forget(_ row: PlayOnSheetRow) {
        if let device = row.playBridge { vm.forget(device) }
        if let device = row.external { vm.forgetExternalReceiver(device) }
        if !path.isEmpty { path.removeLast() }
    }

    private func openFullTVRemote(_ row: PlayOnSheetRow) {
        guard let device = row.playBridge else { return }
        if row.paired && !row.ambiguous {
            showPairedTVs = true
            return
        }
        if vm.remoteEndpoint != AtvReceiverTarget(playBridge: device).endpoint {
            userPicked = false
            vm.connectSaved(device)
        }
        nav.pendingAtvPairing = true
        nav.navigate(to: .remote)
        dismiss()
    }

    private func showPairingPage() {
        if path.last != .pairing { path = [.pairing] }
    }

    private func close() {
        if let pendingExternalID, vm.externalReceiver?.identity == pendingExternalID, !vm.isConnected {
            vm.disconnect()
        }
        self.pendingExternalID = nil
        dismiss()
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url)
    }
}

private struct PlayOnSheetRow: Identifiable {
    let listed: PlayOnListedDevice
    var playBridge: PairedDevice?
    var external: ExternalReceiverDevice?
    var discovered: DiscoveredDevice?
    var paired = false
    var ambiguous = false
    var id: String { listed.id }
}

final class PlayOnPathMonitor: ObservableObject {
    @Published private(set) var onWiFi = true
    @Published private(set) var vpnActive = false
    private var monitor: NWPathMonitor?

    func start() {
        guard monitor == nil else { return }
        let monitor = NWPathMonitor()
        self.monitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let onWiFi = path.status == .satisfied && (path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet))
            let vpn = path.availableInterfaces.contains { $0.type == .other }
            Task { @MainActor in
                self?.onWiFi = onWiFi
                self?.vpnActive = onWiFi && vpn
            }
        }
        monitor.start(queue: DispatchQueue(label: "PlayBridge.playon.path"))
    }

    func stop() {
        monitor?.cancel()
        monitor = nil
    }
}

struct PlayOnLocalSheetModifier: ViewModifier {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var vm: ConnectionViewModel
    @Binding var isPresented: Bool
    @Binding var page: PlayOnPage?
    @Binding var picking: Bool
    @State private var holding = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                guard !holding else { return }
                holding = true
                nav.playOnSuppressCount += 1
            }
            .onDisappear {
                guard holding else { return }
                holding = false
                nav.playOnSuppressCount = max(0, nav.playOnSuppressCount - 1)
            }
            .onChange(of: vm.state) { state in
                guard case .waitingForCodeInput = state, !isPresented else { return }
                page = .pairing
                picking = false
                isPresented = true
            }
            .sheet(isPresented: $isPresented, onDismiss: { page = nil }) {
                DeviceConnectionSheet(initialPage: page, openedForPicking: picking)
            }
    }
}

extension View {
    func playOnLocalSheet(isPresented: Binding<Bool>, page: Binding<PlayOnPage?>, picking: Binding<Bool>) -> some View {
        modifier(PlayOnLocalSheetModifier(isPresented: isPresented, page: page, picking: picking))
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
