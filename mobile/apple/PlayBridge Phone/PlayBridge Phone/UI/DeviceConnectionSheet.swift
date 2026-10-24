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
    @FocusState private var pairingFieldFocused: Bool
    @State private var showUnavailable = false
    @State private var vpnDismissed = false
    @State private var wifiDismissed = false
    @State private var listOffset: CGFloat = 0
    @State private var userPicked = false
    @State private var pendingExternalID: String?
    @State private var manualIP = ""
    @State private var rokuAddress = ""
    @State private var dlnaLocation = ""
    @State private var showAtvPairing = false
    @State private var showPairedTVs = false
    @State private var actionsRowID: String?

    init(initialPage: PlayOnPage? = nil, openedForPicking: Bool = true) {
        self.openedForPicking = openedForPicking
        _path = State(initialValue: initialPage.map { [$0] } ?? [])
    }

    var body: some View {
        NavigationStack(path: $path) {
            mainPage
                .navigationTitle("Play on")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: PlayOnPage.self) { page in
                    playOnDestination(page)
                        .toolbar(.visible, for: .navigationBar)
                }
        }
        // Pushed pages use the system bar. Drop it the same 20pt the root header uses under the grabber.
        .padding(.top, path.isEmpty ? 0 : 20)
        .background(Theme.surface.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("play-on-sheet")
        .sheet(isPresented: $showAtvPairing) { AtvPairingSheet(coordinator: atv) }
        .sheet(isPresented: $showPairedTVs) { AtvPairedTVsSheet(coordinator: atv) }
        .sheet(isPresented: actionsSheetPresented) {
            actionsPage(actionsRowID ?? "")
                .presentationDetents([.height(380)])
                .presentationDragIndicator(.visible)
        }
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
        .onAppear { restoreDismissedBanners() }
        .onChange(of: pathMonitor.vpnActive) { active in
            if !active { clearDismissedBanner("vpn") }
        }
        .onChange(of: pathMonitor.onWiFi) { onWiFi in
            if onWiFi { clearDismissedBanner("wifi") }
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
            sheetHeader
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    networkBanners
                    if showNowCard { nowCard }
                    devicesSection
                    nearbySection
                    unavailableSection
                    if !showNowCard { firstRunTip }
                    addDeviceButton
                        .padding(.top, 4)
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 20)
                .background(PlayOnListOffset(offset: $listOffset, restore: path.isEmpty))
            }
            .refreshable { vm.pingSavedDevices() }
        }
        .background(Theme.surface.ignoresSafeArea())
    }

    private var sheetHeader: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text("Play on")
                    .font(Theme.font(size: 22, weight: .bold))
                    .foregroundColor(Theme.onSurface)
                HStack(spacing: 4) {
                    Image(systemName: pathMonitor.onWiFi ? "wifi" : "antenna.radiowaves.left.and.right")
                        .font(Theme.font(size: 11, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(PlayOnCatalog.networkSubtitle(onWiFi: pathMonitor.onWiFi, wifiName: nil))
                }
                .font(Theme.font(size: 12))
                .foregroundColor(pathMonitor.onWiFi ? Theme.onSurfaceVariant : Theme.tertiary)
            }
            .accessibilityElement(children: .combine)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(Theme.font(size: 13, weight: .bold))
                    .foregroundColor(Theme.onSecondaryContainer)
                    .frame(width: 36, height: 36)
                    .background(Theme.secondaryContainer, in: Circle())
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close")
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 4)
    }

    @ViewBuilder private var networkBanners: some View {
        if !pathMonitor.onWiFi && !wifiBannerHidden {
            banner(title: "Not on Wi‑Fi",
                   message: "Connect this phone to the same Wi‑Fi as your TV to cast and discover devices.",
                   systemImage: "wifi.slash",
                   tint: Theme.tertiary) {
                HStack(spacing: 8) {
                    Button("Wi‑Fi settings") { openSettings() }
                        .font(Theme.font(size: 14, weight: .semibold))
                        .frame(minHeight: 44)
                    Button { dismissBanner("wifi") } label: {
                        Image(systemName: "xmark").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Dismiss")
                }
            }
        } else if pathMonitor.vpnActive && !vpnBannerHidden {
            banner(title: "VPN may block casting",
                   message: "Some VPNs hide your local network. If devices don’t appear, pause the VPN and try again.",
                   systemImage: "shield",
                   tint: Theme.primary) {
                Button { dismissBanner("vpn") } label: {
                    Image(systemName: "xmark").frame(width: 44, height: 44)
                }
                .accessibilityLabel("Dismiss")
            }
        }
    }

    private var nowCard: some View {
        let iconTint = nowCardAccented ? PlayOnStatusGreen.color : Theme.onSurface
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: nowSymbol)
                    .font(Theme.font(size: 20))
                    .foregroundColor(iconTint)
                    .frame(width: 44, height: 44)
                    .background(iconTint.opacity(nowCardAccented ? 0.18 : 0.08), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(nowName)
                        .font(Theme.font(size: 16, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    nowStatusLine
                    if let failureDetail {
                        Text(failureDetail)
                            .font(Theme.font(size: 12))
                            .foregroundColor(Theme.onSurfaceVariant)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                ViewThatFits(in: .horizontal) {
                    nowActionRow(stacks: false)
                    nowActionRow(stacks: true)
                }
                if let row = vm.selectedRemoteTV, row.paired, !row.ambiguous, !vm.isConnected {
                    Button {
                        vm.requestOpenPlayBridge()
                        nav.navigate(to: .remote)
                        dismiss()
                    } label: {
                        Text("Open PlayBridge on \(shown(row.device.name))")
                            .font(Theme.font(size: 14, weight: .semibold))
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(nowCardAccented ? PlayOnStatusGreen.color : Theme.primary)
                }
                if showsFailure && !vm.isConnected {
                    ShareLink(item: diagnosticsText) {
                        Label("Share diagnostics", systemImage: "square.and.arrow.up")
                            .font(Theme.font(size: 14, weight: .semibold))
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(Theme.onSurface)
                }
            }
        }
        .padding(14)
        .background {
            RoundedRectangle(cornerRadius: 16)
                .fill(nowCardAccented ? PlayOnStatusGreen.color.opacity(0.12) : Theme.surfaceContainer)
        }
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .stroke(nowCardAccented ? PlayOnStatusGreen.color.opacity(0.35) : Theme.outlineVariant, lineWidth: 1)
        }
    }

    private func nowActionRow(stacks: Bool) -> some View {
        let remoteTint = nowCardAccented ? PlayOnStatusGreen.color : Theme.onSurface
        let capsules = HStack(spacing: 8) {
            outlinedCapsule("Remote", systemImage: "av.remote", tint: remoteTint) {
                nav.navigate(to: .remote)
                dismiss()
            }
            .accessibilityLabel("Remote")
            if showConfigure {
                filledCapsule("Configure TV", systemImage: "slider.horizontal.3") {
                    path.append(.configure)
                }
            }
        }
        let disconnect = Button(receiverStandingBy || !vm.isAirPlay ? "Disconnect" : "Stop using AirPlay") {
            vm.disconnect()
        }
        .buttonStyle(.plain)
        .font(Theme.font(size: 14, weight: .semibold))
        .foregroundColor(Theme.danger)
        .frame(minHeight: 44)
        return VStack(alignment: .leading, spacing: 8) {
            if receiverStandingBy {
                tonalCapsule("Play on \(nowName)") { vm.selectNativeReceiver() }
            }
            if stacks {
                VStack(alignment: .leading, spacing: 4) {
                    capsules
                    disconnect.frame(maxWidth: .infinity, alignment: .trailing)
                }
            } else {
                HStack(spacing: 8) {
                    capsules.fixedSize(horizontal: true, vertical: false)
                    Spacer(minLength: 8)
                    disconnect.fixedSize(horizontal: true, vertical: false)
                }
            }
        }
    }

    @ViewBuilder private func playOnDestination(_ page: PlayOnPage) -> some View {
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

    private func outlinedCapsule(_ title: String, systemImage: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(Theme.font(size: 14, weight: .semibold))
                .foregroundColor(tint)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .overlay(Capsule().stroke(tint.opacity(0.85), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func filledCapsule(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(Theme.font(size: 14, weight: .semibold))
                .foregroundColor(PlayOnStatusGreen.color)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(PlayOnStatusGreen.color.opacity(0.22), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func tonalCapsule(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Theme.font(size: 14, weight: .semibold))
                .foregroundColor(Theme.onSecondaryContainer)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Theme.secondaryContainer, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private var devicesSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            sectionLabel("Devices")
            phoneRow
            AirPlayDestinationRow(flat: true)
            ForEach(sections.ready) { row in
                deviceRow(row)
            }
        }
    }

    private var phoneRow: some View {
        Button {
            let linked = vm.playBridgeLinkConnected
            vm.selectThisPhone()
            if !linked { dismiss() }
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
            .frame(minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(thisPhoneSelected ? .isSelected : [])
    }

    private var nearbySection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                sectionLabel("Nearby")
                Spacer()
                if vm.nearbySearchActive {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("Searching")
                            .font(Theme.font(size: 12))
                            .foregroundColor(Theme.onSurfaceVariant)
                    }
                    .frame(minHeight: 44)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Searching")
                } else {
                    Button {
                        vm.rescan()
                        vm.pingSavedDevices()
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.clockwise")
                            Text("Search again")
                        }
                        .font(Theme.font(size: 13, weight: .semibold))
                        .foregroundColor(Theme.primary)
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Search again")
                }
            }
            if sections.nearby.isEmpty {
                Text(vm.nearbySearchActive ? "Looking for TVs on this Wi‑Fi…" : "No new devices found.")
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            }
            ForEach(sections.nearby) { row in
                deviceRow(row)
            }
        }
    }

    @ViewBuilder private var unavailableSection: some View {
        if !sections.unavailable.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Divider().overlay(Theme.outlineVariant).padding(.top, 8)
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
        .overlay(Capsule().stroke(Theme.primary, lineWidth: 1))
    }

    private func deviceRow(_ row: PlayOnSheetRow, badge: String? = nil, dimmed: Bool = false) -> some View {
        let newBadge = badge ?? (row.listed.kind == .playBridge && row.listed.needsPairing ? "New" : nil)
        let highlighted = newBadge != nil && !dimmed
        return HStack(spacing: 0) {
            Button { select(row) } label: {
                HStack(spacing: 12) {
                    Image(systemName: PlayOnCatalog.symbol(kind: row.listed.kind, desktop: row.desktop))
                        .font(Theme.font(size: 20))
                        .foregroundColor(dimmed ? Theme.onSurfaceVariant : Theme.primary)
                        .frame(width: 28, height: 28)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.listed.name)
                            .font(Theme.font(size: 15, weight: .semibold))
                            .foregroundColor(dimmed ? Theme.onSurfaceVariant : Theme.onSurface)
                            .lineLimit(1)
                        rowSubtitleView(row)
                    }
                    Spacer(minLength: 0)
                    if let newBadge {
                        Text(newBadge)
                            .font(Theme.font(size: 12, weight: .semibold))
                            .foregroundColor(Theme.primary)
                            .padding(.horizontal, 10)
                            .frame(minHeight: 24)
                            .background(Theme.primary.opacity(0.18), in: Capsule())
                    }
                }
                .frame(minHeight: 64)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if row.listed.saved {
                Button { actionsRowID = row.id } label: {
                    Image(systemName: "ellipsis.vertical")
                        .font(Theme.font(size: 16, weight: .semibold))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("More options for \(row.listed.name)")
            }
        }
        .padding(.horizontal, highlighted ? 10 : 0)
        .background {
            if highlighted {
                RoundedRectangle(cornerRadius: 14).fill(Theme.primary.opacity(0.10))
            }
        }
        .opacity(dimmed ? 0.5 : 1)
    }

    private var pairingPage: some View {
        let name = pairingName
        return ScrollView {
            VStack(spacing: 18) {
                pairingIllustration
                if enteringPairingCode {
                    (Text("Enter the 6-digit code shown on ")
                        + Text(name).font(Theme.font(size: 15, weight: .semibold))
                        + Text("."))
                        .font(Theme.font(size: 15))
                        .foregroundColor(Theme.onSurface)
                        .multilineTextAlignment(.center)
                }
                pairingEntry
                if case .waitingForCodeInput(_, let attempts, true) = vm.state {
                    Text("Incorrect code — \(attempts) \(attempts == 1 ? "try" : "tries") left")
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.danger)
                        .multilineTextAlignment(.center)
                }
                if enteringPairingCode {
                    Text("Pairs automatically when all six digits are in")
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    Text("No code on the TV? Make sure the PlayBridge app is open on it, then tap the TV again.")
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle("Pair \(name)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { pairingFieldFocused = true }
        .onChange(of: vm.state) { state in
            if case .waitingForCodeInput = state { pairingFieldFocused = true }
        }
    }

    private var pairingIllustration: some View {
        VStack(spacing: 10) {
            Text("PlayBridge on your TV")
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
            Text("● ● ●  ● ● ●")
                .font(Theme.font(size: 28, weight: .semibold, design: .monospaced))
                .foregroundColor(Theme.onSurface)
        }
        .padding(.vertical, 22)
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity)
        .background(Theme.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.outlineVariant, lineWidth: 1.5))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Your TV shows a 6-digit code")
    }

    private var enteringPairingCode: Bool {
        switch vm.state {
        case .waitingForApproval, .verifyingCode, .connecting, .retrying, .pairingDenied, .authFailed:
            return false
        default:
            return true
        }
    }

    @ViewBuilder private var pairingEntry: some View {
        switch vm.state {
        case .waitingForApproval:
            pairingStatus("Approve on the TV")
        case .verifyingCode:
            pairingStatus("Checking the code…")
        case .connecting, .retrying:
            pairingStatus("Connecting…")
        case .pairingDenied, .authFailed:
            pairingStatus("The connection wasn’t approved. Try again.", spinning: false, tint: Theme.danger)
        default:
            pairingDigits
        }
    }

    private func pairingStatus(_ title: String, spinning: Bool = true, tint: Color = Theme.onSurface) -> some View {
        VStack(spacing: 12) {
            if spinning { ProgressView() }
            Text(title)
                .font(Theme.font(size: 16, weight: .semibold))
                .foregroundColor(tint)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    private var pairingDigits: some View {
        let rejected = pairingCodeRejected && pairingCode.isEmpty
        return HStack(spacing: 8) {
            ForEach(0..<6, id: \.self) { index in
                let active = !rejected && index == pairingCode.count
                Text(digit(index))
                    .font(Theme.font(size: 28, weight: .bold, design: .monospaced))
                    .foregroundColor(Theme.onSurface)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(rejected ? Theme.danger : (active ? Theme.primary : Theme.outlineVariant),
                                    lineWidth: (rejected || active) ? 2 : 1)
                    )
            }
        }
        .accessibilityHidden(true)
        .background {
            TextField("", text: $pairingCode)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($pairingFieldFocused)
                .opacity(0.02)
                .accessibilityLabel("Pairing code")
                .onChange(of: pairingCode) { newValue in
                    let filtered = String(newValue.filter(\.isNumber).prefix(6))
                    if filtered != newValue { pairingCode = filtered }
                    if filtered.count == 6 { vm.submitPairingCode(filtered) }
                }
        }
        .onTapGesture { pairingFieldFocused = true }
    }

    private var pairingCodeRejected: Bool {
        if case .waitingForCodeInput(_, _, true) = vm.state { return true }
        return false
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
                            systemImage: "play.tv")
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
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(Theme.surface.ignoresSafeArea())
        .navigationTitle("Add a device")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func actionsPage(_ id: String) -> some View {
        let row = rowIndex[id]
        return ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                if let row {
                    HStack(alignment: .center, spacing: 12) {
                        Image(systemName: PlayOnCatalog.symbol(kind: row.listed.kind, desktop: row.desktop))
                            .font(Theme.font(size: 20))
                            .foregroundColor(Theme.primary)
                            .frame(width: 44, height: 44)
                            .background(Theme.primary.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.listed.name)
                                .font(Theme.font(size: 17, weight: .semibold))
                                .foregroundColor(Theme.onSurface)
                                .lineLimit(2)
                            Text(PlayOnCatalog.actionsSubtitle(protocolName: row.listed.protocolName, address: row.listed.address, used: usedText(row.listed.lastConnected)))
                                .font(Theme.font(size: 13))
                                .foregroundColor(Theme.onSurfaceVariant)
                                .lineLimit(2)
                        }
                    }
                    .padding(.bottom, 8)
                    Divider().overlay(Theme.outlineVariant)
                    actionButton("Play on this TV", systemImage: "arrow.right") {
                        actionsRowID = nil
                        select(row)
                    }
                    if row.playBridge != nil {
                        let paired = row.paired && !row.ambiguous
                        Button { openFullTVRemote(row) } label: {
                            HStack(alignment: .center, spacing: 12) {
                                Image(systemName: "av.remote")
                                    .foregroundColor(Theme.primary)
                                    .frame(width: 28)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Full TV remote")
                                        .font(Theme.font(size: 15, weight: .semibold))
                                        .foregroundColor(Theme.onSurface)
                                    Text("Power, volume and apps — pair once with a code")
                                        .font(Theme.font(size: 13))
                                        .foregroundColor(Theme.onSurfaceVariant)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                                Spacer(minLength: 8)
                                if paired {
                                    Text("Paired")
                                        .font(Theme.font(size: 13, weight: .semibold))
                                        .foregroundColor(Theme.onSurfaceVariant)
                                } else {
                                    Text("Set up")
                                        .font(Theme.font(size: 12, weight: .semibold))
                                        .foregroundColor(Theme.primary)
                                        .padding(.horizontal, 10)
                                        .frame(minHeight: 28)
                                        .background(Theme.primary.opacity(0.18), in: Capsule())
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Full TV remote, \(paired ? "Paired" : "Set up")")
                    }
                    Button(role: .destructive) { forget(row) } label: {
                        Label(row.playBridge == nil ? "Forget this device" : "Forget this TV", systemImage: "trash")
                            .font(Theme.font(size: 15, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(Theme.danger)
                } else {
                    Text("This device is no longer available.")
                        .foregroundColor(Theme.onSurfaceVariant)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 16)
        }
        .background(Theme.surface.ignoresSafeArea())
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
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
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
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
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
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .foregroundColor(Theme.primary)
                    .frame(width: 28)
                Text(title)
                    .font(Theme.font(size: 15, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
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
            let address = "\(device.ip):\(device.port)"
            let sightings = vm.browser.devices.map { (uuid: $0.uuid, address: "\($0.ip):\($0.port)") }
            let held = PlayOnCatalog.addressHeldByOther(savedUUID: device.uuid, savedAddress: address, sightings: sightings)
            let seenLive = vm.browser.devices.contains { SavedReceiverEndpoint.sameIdentity(device, $0) }
            let reachable = PlayOnCatalog.savedReachable(
                probe: vm.onlineStatus[key] == true,
                seenLive: seenLive,
                remoteReachable: remote?.receiverReachable == true || remote?.tvReachable == true,
                addressHeldByOther: held)
            var keys: Set<String> = [id, key, address]
            if !device.uuid.isEmpty { keys.insert(device.uuid) }
            rows.append(PlayOnSheetRow(
                listed: PlayOnListedDevice(id: id, name: shown(device.name), kind: .playBridge, address: address,
                                            reachable: reachable, saved: true, lastConnected: device.lastConnected, needsPairing: false, matchKeys: keys, uuid: device.uuid),
                playBridge: device, external: nil, paired: remote?.paired == true, ambiguous: remote?.ambiguous == true,
                desktop: PlayOnCatalog.isDesktopName(device.name)))
        }
        let liveIDs = Set((vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices).map(\.identity))
        for (index, device) in vm.savedExternalReceiverDevices.enumerated() where device.protocolID != "dial" {
            let id = "ex:\(device.identity)"
            if id == activeExternalID { continue }
            let address = device.addresses.first.map { "\($0):\(device.port)" } ?? device.model
            rows.append(PlayOnSheetRow(
                listed: PlayOnListedDevice(id: id, name: shown(device.name), kind: kind(device.protocolID), address: address,
                                            reachable: liveIDs.contains(device.identity), saved: true,
                                            lastConnected: Date(timeIntervalSince1970: TimeInterval(1_000_000 - index)),
                                            needsPairing: false, matchKeys: [id, device.identity]),
                playBridge: nil, external: device, paired: false, ambiguous: false, desktop: false))
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
                listed: PlayOnListedDevice(id: id, name: shown(device.name), kind: .playBridge, address: "\(device.ip):\(device.port)",
                                            reachable: true, saved: false, lastConnected: nil, needsPairing: true, matchKeys: keys, uuid: device.uuid),
                playBridge: nil, external: nil, discovered: device, paired: false, ambiguous: false,
                desktop: PlayOnCatalog.isDesktopName(device.name)))
        }
        for device in vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices where device.protocolID != "dial" {
            let id = "ex:\(device.identity)"
            if id == activeExternalID { continue }
            let address = device.addresses.first.map { "\($0):\(device.port)" } ?? device.model
            rows.append(PlayOnSheetRow(
                listed: PlayOnListedDevice(id: id, name: shown(device.name), kind: kind(device.protocolID), address: address,
                                            reachable: true, saved: false, lastConnected: nil, needsPairing: false, matchKeys: [id, device.identity]),
                playBridge: nil, external: device, paired: false, ambiguous: false, desktop: false))
        }
        return rows
    }

    private var activePlayBridgeID: String? {
        guard !vm.isExternalReceiver, let device = vm.pairedDevice else { return nil }
        let live = vm.isConnected || vm.connectionIsConnecting || vm.selectedRemoteTV != nil || showsFailure
        guard receiverStandingBy || (vm.nativeReceiverSelected && !vm.isAirPlay && live) else { return nil }
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
        !vm.playBridgeOutputSelected && !vm.isAirPlay && vm.externalReceiver == nil && !vm.connectionIsConnecting
            && (!vm.isConnected || receiverStandingBy)
    }

    /// The receiver socket is up, but This phone or AirPlay is the selected output.
    private var receiverStandingBy: Bool {
        vm.playBridgeLinkConnected && !vm.playBridgeOutputSelected && vm.externalReceiver == nil
    }

    /// Connected playback uses the green card. Connecting, standby, and failures stay neutral.
    private var nowCardAccented: Bool {
        guard !showsFailure, !receiverStandingBy else { return false }
        if vm.isConnected { return true }
        return vm.isAirPlay && vm.airPlay.routeAvailable
    }

    private var nowStatusColor: Color {
        if showsFailure && !vm.isConnected { return Theme.danger }
        if nowCardAccented { return PlayOnStatusGreen.color }
        return Theme.onSurfaceVariant
    }

    private var actionsSheetPresented: Binding<Bool> {
        Binding(get: { actionsRowID != nil }, set: { if !$0 { actionsRowID = nil } })
    }

    private var showNowCard: Bool {
        vm.isAirPlay || vm.isConnected || vm.connectionIsConnecting || showsFailure
            || (vm.nativeReceiverSelected && vm.selectedRemoteTV != nil)
            || vm.externalReceiver != nil
    }

    private var showConfigure: Bool {
        guard vm.isConnected, vm.playBridgeOutputSelected, !vm.isExternalReceiver, !vm.isAirPlay else { return false }
        // TV Default is always first. Configure needs two real engines past that.
        return TvCapabilityOptions.playerOptions(for: vm.pairedDevice).count > 2
    }

    private var showsFailure: Bool {
        switch vm.state {
        case .error, .authFailed, .pinMismatch, .pairingDenied: return true
        default: return false
        }
    }

    private var nowName: String {
        if receiverStandingBy, let name = vm.pairedDevice?.name, !name.isEmpty { return shown(name) }
        if vm.isAirPlay { return shown(vm.airPlay.routeName) }
        switch vm.state {
        case .connected(let name, _): return shown(name)
        case .waitingForApproval(let name), .waitingForCodeInput(let name, _, _), .verifyingCode(let name),
                .pairingDenied(let name), .pinMismatch(let name):
            return name.isEmpty ? fallbackName : shown(name)
        default:
            return fallbackName
        }
    }

    private var fallbackName: String {
        shown(vm.externalReceiver?.name ?? vm.selectedRemoteTV?.device.name ?? vm.pairedDevice?.name ?? "TV")
    }

    private var pairingName: String {
        switch vm.state {
        case .waitingForCodeInput(let name, _, _), .verifyingCode(let name), .waitingForApproval(let name):
            return name.isEmpty ? fallbackName : shown(name)
        default:
            return fallbackName
        }
    }

    private func shown(_ name: String) -> String { PlayOnCatalog.displayName(name) }

    private var protocolName: String {
        if vm.isAirPlay { return "AirPlay" }
        return vm.externalReceiver?.protocolName ?? "PlayBridge"
    }

    private var nowSymbol: String {
        if receiverStandingBy {
            return PlayOnCatalog.isDesktopName(vm.pairedDevice?.name ?? "") ? "laptopcomputer" : "tv"
        }
        if vm.isAirPlay || vm.isExternalReceiver { return "airplayvideo" }
        if PlayOnCatalog.isDesktopName(connectedRawName) { return "laptopcomputer" }
        return "tv"
    }

    /// Name before display trimming, so a ".local" desktop still picks the laptop icon.
    private var connectedRawName: String {
        switch vm.state {
        case .connected(let name, _) where !name.isEmpty: return name
        default: return vm.pairedDevice?.name ?? vm.selectedRemoteTV?.device.name ?? ""
        }
    }

    private var nowStatusText: String {
        if receiverStandingBy { return "Connected · Standing by" }
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

    @ViewBuilder private func rowSubtitleView(_ row: PlayOnSheetRow) -> some View {
        if row.listed.saved && row.listed.reachable {
            HStack(spacing: 4) {
                Text("\(row.listed.protocolName) ·")
                Circle().fill(PlayOnStatusGreen.color).frame(width: 6, height: 6).accessibilityHidden(true)
                Text("Ready")
            }
            .font(Theme.font(size: 13))
            .foregroundColor(Theme.onSurfaceVariant)
            .lineLimit(1)
            .accessibilityElement(children: .combine)
        } else {
            Text(rowSubtitle(row))
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
                .lineLimit(1)
        }
    }

    private var nowStatusLine: some View {
        let connected = nowStatusText.hasPrefix("Connected")
        return HStack(spacing: 4) {
            if connected {
                Circle().fill(nowStatusColor).frame(width: 6, height: 6).accessibilityHidden(true)
            }
            Text(nowStatusText)
        }
        .font(Theme.font(size: 13))
        .foregroundColor(nowStatusColor)
        .accessibilityElement(children: .combine)
    }

    private var wifiBannerHidden: Bool { wifiDismissed || nav.playOnDismissedBanners.contains("wifi") }
    private var vpnBannerHidden: Bool { vpnDismissed || nav.playOnDismissedBanners.contains("vpn") }

    private func restoreDismissedBanners() {
        if nav.playOnDismissedBanners.contains("wifi") { wifiDismissed = true }
        if nav.playOnDismissedBanners.contains("vpn") { vpnDismissed = true }
    }

    private func dismissBanner(_ key: String) {
        nav.playOnDismissedBanners.insert(key)
        if key == "wifi" { wifiDismissed = true }
        if key == "vpn" { vpnDismissed = true }
    }

    private func clearDismissedBanner(_ key: String) {
        nav.playOnDismissedBanners.remove(key)
        if key == "wifi" { wifiDismissed = false }
        if key == "vpn" { vpnDismissed = false }
    }

    private func usedText(_ date: Date?) -> String? {
        date.map { PlayOnRelativeTime.used($0) }
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
        if actionsRowID != nil {
            actionsRowID = nil
        } else if !path.isEmpty {
            path.removeLast()
        }
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
        actionsRowID = nil
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
    var desktop = false
    var id: String { listed.id }
}

private enum PlayOnStatusGreen {
    /// Ready green from the Play on mockup. Theme has no success token.
    static let color = Color(hex: 0x46D483)
}

/// Keeps page 1's scroll offset when an in-sheet page covers the list and the scroll view is rebuilt.
private struct PlayOnListOffset: UIViewRepresentable {
    @Binding var offset: CGFloat
    var restore: Bool

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        let binding = $offset
        let shouldRestore = restore
        DispatchQueue.main.async {
            guard let scroll = context.coordinator.scrollView(from: view) else { return }
            context.coordinator.track(scroll, offset: binding)
            if shouldRestore { context.coordinator.restore(scroll, to: binding.wrappedValue) }
        }
    }

    final class Coordinator {
        private var observation: NSKeyValueObservation?
        private var suppress = false
        private weak var tracked: UIScrollView?
        private var offset: Binding<CGFloat>?

        func scrollView(from view: UIView) -> UIScrollView? {
            var current: UIView? = view
            while let next = current?.superview {
                if let scroll = next as? UIScrollView { return scroll }
                current = next
            }
            return nil
        }

        func track(_ scroll: UIScrollView, offset: Binding<CGFloat>) {
            self.offset = offset
            guard tracked !== scroll else { return }
            tracked = scroll
            observation = scroll.observe(\.contentOffset, options: [.new]) { [weak self] scroll, _ in
                guard let self, !self.suppress else { return }
                // A page push can rebuild the scroll view at the top. Only a finger scroll commits.
                guard scroll.isTracking || scroll.isDragging || scroll.isDecelerating else { return }
                let y = max(0, scroll.contentOffset.y)
                if abs((self.offset?.wrappedValue ?? 0) - y) > 0.5 {
                    self.offset?.wrappedValue = y
                }
            }
        }

        func restore(_ scroll: UIScrollView, to y: CGFloat) {
            guard y > 1, abs(scroll.contentOffset.y - y) > 1 else { return }
            suppress = true
            scroll.setContentOffset(CGPoint(x: 0, y: y), animated: false)
            suppress = false
        }
    }
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
