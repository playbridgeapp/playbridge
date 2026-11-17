import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct WatchTogetherScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var together: PhoneTogether
    @State private var showSaved = false

    var body: some View {
        VStack(spacing: 0) {
            headerBar
            ScrollView {
                VStack(spacing: 14) {
                    switch together.state {
                    case .idle:
                        StartPartySection()
                    case .starting:
                        BusyPartySection(message: "Connecting…")
                    case .gettingCode:
                        BusyPartySection(message: "Getting a code…")
                    case .code(let code, let qrSize, let qrModules, let expiresAtMs):
                        ShowCodeSection(code: code, qrSize: qrSize, qrModules: qrModules, expiresAtMs: expiresAtMs)
                    case .invited(let from, let relay):
                        InvitedSection(from: from, relay: relay)
                    case .ended(let message):
                        EndedSection(message: message)
                    case .inParty(let party):
                        ActivePartySection(party: party, vm: vm)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
        }
        .background(Theme.surface.ignoresSafeArea())
        .onAppear { together.loadSavedParties() }
        .sheet(isPresented: $showSaved) {
            SavedPartiesSheet(watchHere: together.watchHereByDefault(connectedTvName: vm.connectedPlayBridgeTvName))
                .environmentObject(together)
        }
        .alert("Join another party?", isPresented: Binding(
            get: { together.switchRequest != nil },
            set: { if !$0 { together.cancelSwitch() } }
        )) {
            Button("Leave and join", role: .destructive) { together.confirmSwitch() }
            Button("Stay", role: .cancel) { together.cancelSwitch() }
        } message: {
            Text("You'll leave the party you're in now.")
        }
    }

    private var headerBar: some View {
        HStack(spacing: 12) {
            DashboardNavigationButton()
            Text("Watch Together")
                .font(Theme.font(.title2).weight(.semibold))
                .foregroundStyle(Theme.onSurface)
            Spacer()
            Button {
                showSaved = true
            } label: {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.title3)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Recent parties")
            .foregroundStyle(Theme.onSurface)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Theme.surface)
    }
}

private struct PartyPanel<Content: View>: View {
    let title: String?
    @ViewBuilder let content: Content

    init(_ title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                Text(title)
                    .font(Theme.font(.headline))
                    .foregroundStyle(Theme.onSurface)
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Theme.outlineVariant, lineWidth: 1)
        )
    }
}

private struct BusyPartySection: View {
    let message: String
    @EnvironmentObject private var together: PhoneTogether

    var body: some View {
        PartyPanel {
            HStack(spacing: 12) {
                ProgressView()
                Text(message)
                    .font(Theme.font(.body))
                    .foregroundStyle(Theme.onSurface)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Cancel", role: .cancel) {
                together.cancelCode()
                together.leave()
            }
            .buttonStyle(.bordered)
        }
    }
}

private struct EndedSection: View {
    let message: String
    @EnvironmentObject private var together: PhoneTogether

    var body: some View {
        PartyPanel("Watch party ended") {
            Text(message.isEmpty ? "The watch party has ended." : message)
                .font(Theme.font(.body))
                .foregroundStyle(Theme.onSurfaceVariant)
            Button("Done") { together.dismissEnded() }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
        }
    }
}

private struct StartPartySection: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var together: PhoneTogether

    @State private var watchHere = true
    @State private var initializedWatchHere = false
    @State private var joinMode = 0
    @State private var inviteInput = ""
    @State private var editingRelay = false
    @State private var relayDraft = ""

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 8) {
                Image(systemName: "person.3.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(Theme.primary)
                    .accessibilityHidden(true)
                Text("Watch in step, anywhere")
                    .font(Theme.font(.title3).weight(.semibold))
                    .foregroundStyle(Theme.onSurface)
                    .multilineTextAlignment(.center)
                Text("Friends and your other devices each play their own copy. Only play, pause and seek are shared, end-to-end encrypted.")
                    .font(Theme.font(.subheadline))
                    .foregroundStyle(Theme.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                PartyLinksNote()
                    .multilineTextAlignment(.center)
            }
            .padding(.vertical, 8)

            Button {
                together.startHosting(watchHere: watchHere)
            } label: {
                Text("Start a watch party")
                    .font(Theme.font(.headline))
                    .frame(maxWidth: .infinity, minHeight: 46)
            }
            .buttonStyle(.borderedProminent)

            PartyPanel("Join a party") {
                Picker("Join method", selection: $joinMode) {
                    Text("Invite link").tag(0)
                    Text("Code").tag(1)
                }
                .pickerStyle(.segmented)

                if joinMode == 0 {
                    HStack(spacing: 8) {
                        TextField("Paste invite link", text: $inviteInput)
                            .textFieldStyle(.roundedBorder)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Button {
                            #if canImport(UIKit)
                            if let clip = UIPasteboard.general.string {
                                inviteInput = clip.trimmingCharacters(in: .whitespacesAndNewlines)
                            }
                            #endif
                        } label: {
                            Image(systemName: "doc.on.clipboard")
                                .frame(minWidth: 36, minHeight: 36)
                        }
                        .accessibilityLabel("Paste invite link")
                        .buttonStyle(.bordered)
                    }
                    Button {
                        together.join(invite: inviteInput, watchHere: watchHere)
                    } label: {
                        Text("Join")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(WatchTogetherLinks.invite(inviteInput) == nil)
                } else {
                    Text("Show a 6-character code and QR code on this screen. Someone already in the party enters your code under Add a device.")
                        .font(Theme.font(.subheadline))
                        .foregroundStyle(Theme.onSurfaceVariant)
                    Button {
                        together.showCode(watchHere: watchHere)
                    } label: {
                        Text("Show my code")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            PartyPanel {
                Toggle(isOn: $watchHere) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Watch on this phone")
                            .font(Theme.font(.body))
                        Text("Turn off to use this phone as a remote only.")
                            .font(Theme.font(.caption))
                            .foregroundStyle(Theme.onSurfaceVariant)
                    }
                }
            }

            PartyPanel {
                Button {
                    relayDraft = together.isSharedRelay ? "" : together.relayURL
                    editingRelay = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "cloud")
                            .foregroundStyle(Theme.onSurfaceVariant)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Room relay")
                                .font(Theme.font(.body))
                                .foregroundStyle(Theme.onSurface)
                            Text(together.isSharedRelay
                                 ? "PlayBridge shared: up to 4 devices, 3 hours"
                                 : (URL(string: together.relayURL)?.host ?? together.relayURL))
                                .font(Theme.font(.caption))
                                .foregroundStyle(Theme.onSurfaceVariant)
                                .lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(Theme.onSurfaceVariant)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .onAppear {
            if !initializedWatchHere {
                watchHere = together.watchHereByDefault(connectedTvName: vm.connectedPlayBridgeTvName)
                initializedWatchHere = true
            }
        }
        .sheet(isPresented: $editingRelay) {
            NavigationStack {
                Form {
                    Section {
                        TextField("https://…", text: $relayDraft)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                    } footer: {
                        Text("Parties you start run on this relay. Leave empty to use PlayBridge's shared relay.")
                    }
                }
                .navigationTitle("Room relay")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { editingRelay = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            together.setRelayURL(relayDraft)
                            editingRelay = false
                        }
                    }
                }
            }
        }
    }
}

private struct ShowCodeSection: View {
    let code: String
    let qrSize: Int
    let qrModules: String
    let expiresAtMs: Int64
    @EnvironmentObject private var together: PhoneTogether

    var body: some View {
        PartyPanel("Your join code") {
            VStack(spacing: 14) {
                Text(TogetherFormatting.groupPartyCode(code))
                    .font(.system(.largeTitle, design: .monospaced).weight(.bold))
                    .foregroundStyle(Theme.primary)
                    .accessibilityLabel("Party code \(code)")
                if qrSize > 0, !qrModules.isEmpty {
                    TogetherQRView(size: qrSize, modules: qrModules)
                        .frame(width: 196, height: 196)
                        .accessibilityHidden(true)
                }
                Text("Ask someone in the party to open Add a device and enter this code or scan it.")
                    .font(Theme.font(.subheadline))
                    .foregroundStyle(Theme.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                Button("Cancel", role: .cancel) { together.cancelCode() }
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

private struct TogetherQRView: View {
    let size: Int
    let modules: String

    var body: some View {
        Canvas { context, canvasSize in
            context.fill(Path(CGRect(origin: .zero, size: canvasSize)), with: .color(.white))
            guard size > 0, modules.count >= size * size else { return }
            let inset: CGFloat = 12
            let cell = (min(canvasSize.width, canvasSize.height) - inset * 2) / CGFloat(size)
            let chars = Array(modules)
            for row in 0..<size {
                for col in 0..<size {
                    if chars[row * size + col] == "1" {
                        let rect = CGRect(
                            x: inset + CGFloat(col) * cell,
                            y: inset + CGFloat(row) * cell,
                            width: ceil(cell),
                            height: ceil(cell)
                        )
                        context.fill(Path(rect), with: .color(.black))
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct InvitedSection: View {
    let from: String
    let relay: String
    @EnvironmentObject private var together: PhoneTogether

    var body: some View {
        PartyPanel("Party invite") {
            Text("\(from) invited you to a watch party.")
                .font(Theme.font(.headline))
                .foregroundStyle(Theme.onSurface)
            if !relay.isEmpty {
                Text("Relay: \(URL(string: relay)?.host ?? relay)")
                    .font(Theme.font(.caption))
                    .foregroundStyle(Theme.onSurfaceVariant)
            }
            HStack(spacing: 12) {
                Button("Decline", role: .cancel) { together.cancelCode() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Button("Join party") { together.acceptInvite() }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

private struct ActivePartySection: View {
    let party: TogetherPartyState
    @ObservedObject var vm: ConnectionViewModel
    @EnvironmentObject private var together: PhoneTogether

    @State private var codeInput = ""
    @State private var showVideoLinkPrompt = false
    @State private var videoURLDraft = ""
    @State private var videoTitleDraft = ""
    @State private var shareInviteSheet = false

    var body: some View {
        let canControl = party.host || !party.locked

        PartyPanel {
            HStack(spacing: 12) {
                Image(systemName: "person.3.fill")
                    .font(.title2)
                    .foregroundStyle(Theme.primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(party.host ? "Your watch party" : "\(hostName(party) ?? savedHostName ?? "A") watch party")
                        .font(Theme.font(.title3).weight(.semibold))
                        .foregroundStyle(Theme.onSurface)
                    HStack(spacing: 6) {
                        Circle()
                            .fill(party.connected ? Theme.primary : Theme.danger)
                            .frame(width: 8, height: 8)
                        Text(party.connected
                             ? "\(deviceCountLabel(party.members.count)) · \(URL(string: party.relay)?.host ?? party.relay)"
                             : "Connecting to \(URL(string: party.relay)?.host ?? party.relay)…")
                            .font(Theme.font(.caption))
                            .foregroundStyle(Theme.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                Spacer()
            }

            HStack(spacing: 8) {
                Button {
                    shareInviteSheet = true
                } label: {
                    Label("Share invite link", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    #if canImport(UIKit)
                    UIPasteboard.general.string = party.invite
                    #endif
                } label: {
                    Image(systemName: "doc.on.doc")
                        .frame(width: 36, height: 28)
                }
                .accessibilityLabel("Copy invite link")
                .buttonStyle(.bordered)
            }
        }

        HostAwayNote(party: party)

        if let notice = party.notice, !notice.isEmpty {
            PartyPanel {
                HStack(spacing: 10) {
                    Image(systemName: party.connected ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(party.connected ? Theme.primary : Theme.danger)
                    Text(notice)
                        .font(Theme.font(.subheadline))
                        .foregroundStyle(Theme.onSurface)
                    Spacer()
                    if party.reconnectFailed {
                        Button("Leave", role: .destructive) { together.leave() }
                            .buttonStyle(.bordered)
                    }
                }
            }
        }

        if let req = party.requests.first {
            PartyPanel("\(memberName(party, id: req.from)) wants to play") {
                Text(req.title + (req.site.map { " (\($0))" } ?? ""))
                    .font(Theme.font(.body))
                    .foregroundStyle(Theme.onSurface)
                HStack(spacing: 8) {
                    Button("Allow") { together.answer(request: req, allowed: true) }
                        .buttonStyle(.borderedProminent)
                    Button("Always allow") { together.answer(request: req, allowed: true, always: true) }
                        .buttonStyle(.bordered)
                    Button("Deny", role: .destructive) { together.answer(request: req, allowed: false) }
                        .buttonStyle(.bordered)
                }
            }
        }

        NowPlayingSection(
            party: party,
            canControl: canControl,
            onOpenLinkSheet: { showVideoLinkPrompt = true }
        )

        if party.canChangeVideo || !together.queue.isEmpty {
            UpNextSection(party: party)
        }

        PartyPanel("In the party") {
            ForEach(party.orderedMembers, id: \.id) { member in
                HStack(spacing: 10) {
                    Image(systemName: iconName(for: member.kind))
                        .foregroundStyle(Theme.onSurfaceVariant)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(member.name)
                                .font(Theme.font(.body))
                                .foregroundStyle(Theme.onSurface)
                            if member.host {
                                RoleBadge("Host")
                            }
                            if member.id == party.me {
                                RoleBadge("You")
                            } else if party.own.contains(member.id) {
                                RoleBadge("Yours")
                            }
                        }
                    }
                    Spacer()
                    if together.canOfferShareSignIn(for: member.id) {
                        Button("Share sign-in") {
                            together.shareSignIn(member: member.id)
                        }
                        .buttonStyle(.bordered)
                    } else if party.sharedSecretsForItem.contains(member.id) {
                        Text("Signed-in shared")
                            .font(Theme.font(.caption))
                            .foregroundStyle(Theme.onSurfaceVariant)
                    }
                }
            }
        }

        PartyPanel("Add a device") {
            if let tvName = vm.connectedPlayBridgeTvName,
               vm.tvSupportsWatchTogether,
               !together.isConnectedTvInParty(using: vm) {
                Button {
                    together.addConnectedTv(using: vm)
                } label: {
                    HStack {
                        Image(systemName: "plus.circle.fill")
                        Text(together.addingConnectedTv ? "Adding \(tvName)…" : "Add \(tvName)")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(together.addingConnectedTv)
            }

            HStack(spacing: 8) {
                TextField("6-character code", text: $codeInput)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Button("Invite") {
                    together.addByCode(codeInput)
                    codeInput = ""
                }
                .buttonStyle(.bordered)
                .disabled(codeInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }

        PartyPanel {
            Toggle(
                isOn: Binding(
                    get: { party.watching },
                    set: { together.setWatchHere($0) }
                )
            ) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Watch on this phone")
                        .font(Theme.font(.body))
                    Text("Turn off to control the party without playing audio here.")
                        .font(Theme.font(.caption))
                        .foregroundStyle(Theme.onSurfaceVariant)
                }
            }
        }

        if party.host {
            PartyPanel("Host controls") {
                Toggle(
                    isOn: Binding(
                        get: { party.locked },
                        set: { together.lock($0) }
                    )
                ) {
                    Text("Lock controls")
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("Who can change the video")
                        .font(Theme.font(.subheadline))
                        .foregroundStyle(Theme.onSurfaceVariant)
                    Picker(
                        "Who can change the video",
                        selection: Binding(
                            get: { party.policy },
                            set: { together.policy($0) }
                        )
                    ) {
                        Text("Only me").tag(TogetherPolicy.onlyHost)
                        Text("Ask me").tag(TogetherPolicy.ask)
                        Text("Anyone").tag(TogetherPolicy.anyone)
                    }
                    .pickerStyle(.segmented)
                }
            }
        }

        Button(role: .destructive) {
            together.leave()
        } label: {
            Text("Leave party")
                .font(Theme.font(.headline))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .tint(Theme.danger)
        .sheet(isPresented: $shareInviteSheet) {
            #if canImport(UIKit)
            ActivityShareView(items: [party.invite])
            #endif
        }
        .sheet(isPresented: $showVideoLinkPrompt) {
            NavigationStack {
                Form {
                    Section {
                        TextField("https://…", text: $videoURLDraft)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                        TextField("Title (optional)", text: $videoTitleDraft)
                    } footer: {
                        Text("Enter a direct web video URL (http/https) to load in the watch party.")
                    }
                }
                .navigationTitle(party.canChangeVideo ? "Play video link" : "Request video")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showVideoLinkPrompt = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(party.canChangeVideo ? "Play" : "Ask host") {
                            if together.playInParty(url: videoURLDraft, title: videoTitleDraft) {
                                videoURLDraft = ""
                                videoTitleDraft = ""
                                showVideoLinkPrompt = false
                            }
                        }
                        .disabled(PartyMediaURL.evaluate(videoURLDraft).normalizedURL == nil)
                    }
                }
            }
        }
    }

    private func hostName(_ party: TogetherPartyState) -> String? {
        party.members.values.first(where: { $0.host })?.name
    }

    /// While the host is away, the name this phone saw before.
    private var savedHostName: String? {
        together.savedParties.first(where: { $0.roomId == party.roomId })?.hostName
    }

    private func memberName(_ party: TogetherPartyState, id: String) -> String {
        party.members[id]?.name ?? "Someone"
    }

    private func deviceCountLabel(_ count: Int) -> String {
        count == 1 ? "1 device" : "\(count) devices"
    }

    private func iconName(for kind: TogetherDeviceKind) -> String {
        switch kind {
        case .tv: return "tv"
        case .phone: return "iphone"
        case .desktop: return "desktopcomputer"
        case .cli: return "terminal"
        }
    }
}

private struct NowPlayingSection: View {
    let party: TogetherPartyState
    let canControl: Bool
    let onOpenLinkSheet: () -> Void
    @EnvironmentObject private var together: PhoneTogether

    @State private var dragging = false
    @State private var dragPositionMs: Int64 = 0
    @State private var nowPositionMs: Int64 = 0
    private let ticker = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    var body: some View {
        let displayedMs = dragging ? dragPositionMs : nowPositionMs
        PartyPanel {
            if let item = party.item {
                let durationMs = party.durationMs.flatMap { $0 > 0 ? $0 : nil }
                let showSeek = !party.live && durationMs != nil

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(Theme.font(.headline))
                        .foregroundStyle(Theme.onSurface)
                        .lineLimit(2)
                    if party.live {
                        Text([item.site, "Live"].compactMap { $0 }.joined(separator: " · "))
                            .font(Theme.font(.subheadline))
                            .foregroundStyle(Theme.onSurfaceVariant)
                    } else {
                        let subtitle = showSeek
                            ? (item.site ?? "")
                            : [item.site, Self.formatTime(displayedMs)].compactMap { $0 }.joined(separator: " · ")
                        if !subtitle.isEmpty {
                            Text(subtitle)
                                .font(Theme.font(.subheadline))
                                .foregroundStyle(Theme.onSurfaceVariant)
                        }
                    }
                }

                if showSeek, let totalMs = durationMs {
                    let fraction = min(1.0, max(0.0, Double(displayedMs) / Double(totalMs)))
                    VStack(spacing: 4) {
                        Slider(
                            value: Binding(
                                get: { fraction },
                                set: { next in
                                    dragging = true
                                    dragPositionMs = Int64((next * Double(totalMs)).rounded())
                                }
                            ),
                            onEditingChanged: { editing in
                                if !editing {
                                    let target = dragPositionMs
                                    dragging = false
                                    _ = together.seekTo(positionMs: target)
                                }
                            }
                        )
                        .disabled(!canControl)

                        HStack {
                            Text(Self.formatTime(displayedMs))
                            Spacer()
                            Text(Self.formatTime(totalMs))
                        }
                        .font(Theme.font(.caption).monospacedDigit())
                        .foregroundStyle(Theme.onSurfaceVariant)
                    }
                }

                HStack(spacing: 24) {
                    if !party.live {
                        Button { _ = together.seekBy(deltaMs: -10_000) } label: {
                            Image(systemName: "gobackward.10")
                                .font(.title2)
                                .frame(width: 48, height: 48)
                        }
                        .accessibilityLabel("Back 10 seconds")
                        .disabled(!canControl)
                    }

                    Button {
                        if party.playing {
                            _ = together.pause()
                        } else {
                            _ = together.play()
                        }
                    } label: {
                        Image(systemName: party.playing ? "pause.fill" : "play.fill")
                            .font(.title)
                            .frame(width: 56, height: 56)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityLabel(party.playing ? "Pause" : "Play")
                    .disabled(!canControl)

                    if !party.live {
                        Button { _ = together.seekBy(deltaMs: 10_000) } label: {
                            Image(systemName: "goforward.10")
                                .font(.title2)
                                .frame(width: 48, height: 48)
                        }
                        .accessibilityLabel("Forward 10 seconds")
                        .disabled(!canControl)
                    }
                }
                .frame(maxWidth: .infinity)

                if party.canChangeVideo {
                    HStack(spacing: 8) {
                        Button {
                            _ = together.stopVideo()
                        } label: {
                            Label("Stop video", systemImage: "stop.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)

                        Button(action: onOpenLinkSheet) {
                            Label("Change video", systemImage: "link")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                } else if !canControl {
                    Text("The host locked the controls.")
                        .font(Theme.font(.caption))
                        .foregroundStyle(Theme.onSurfaceVariant)
                }
            } else {
                Text("Nothing playing yet")
                    .font(Theme.font(.headline))
                    .foregroundStyle(Theme.onSurface)
                Text(party.canChangeVideo
                     ? "Start a web video link for everyone in the party."
                     : (party.policy == .ask && !party.locked
                        ? "Suggest a web video link, or wait for the host to start one."
                        : "Waiting for the host to pick a video."))
                    .font(Theme.font(.subheadline))
                    .foregroundStyle(Theme.onSurfaceVariant)
                PartyLinksNote()
                if party.canChangeVideo || (party.policy == .ask && !party.locked) {
                    Button(action: onOpenLinkSheet) {
                        Label(party.canChangeVideo ? "Play video link" : "Ask host to play a link", systemImage: "link")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }

            if !party.waitingFor.isEmpty {
                let names = party.waitingFor.map { party.members[$0]?.name ?? "Someone" }.joined(separator: ", ")
                Text("Waiting for \(names)…")
                    .font(Theme.font(.subheadline))
                    .foregroundStyle(Theme.onSurfaceVariant)
            }
            if party.host {
                ForEach(party.continueWithout, id: \.self) { member in
                    Button("Continue without \(party.members[member]?.name ?? "device")") {
                        together.continueWithout(member: member)
                    }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .onAppear { nowPositionMs = party.positionNow() }
        .onReceive(ticker) { _ in nowPositionMs = party.positionNow() }
        .onChange(of: party.positionMs) { _ in nowPositionMs = party.positionNow() }
        .onChange(of: party.item?.id) { _ in
            dragging = false
            nowPositionMs = party.positionNow()
        }
    }

    private static func formatTime(_ ms: Int64) -> String {
        let totalSeconds = max(0, Int(ms / 1000))
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}

/// Which links play for everyone. Shown where a party starts and while it waits for a video.
private struct PartyLinksNote: View {
    var body: some View {
        Text("Each device loads the video itself. Public links, and sites that let several devices watch at once, work best. Links tied to one device or one IP address, and files on this phone, won't play for others.")
            .font(Theme.font(.caption))
            .foregroundStyle(Theme.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Up next: the videos this phone queued, in order. The next one starts when the current one ends.
private struct UpNextSection: View {
    let party: TogetherPartyState
    @EnvironmentObject private var together: PhoneTogether

    var body: some View {
        let queue = together.queue
        let canChange = party.canChangeVideo
        PartyPanel {
            HStack(spacing: 8) {
                Text("UP NEXT · \(queue.count)")
                    .font(Theme.font(.caption).weight(.semibold))
                    .foregroundStyle(Theme.onSurfaceVariant)
                Spacer()
                if !queue.isEmpty && canChange {
                    Button { together.playNextInQueueNow() } label: {
                        Image(systemName: "forward.end.fill")
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Play the next video now")
                }
                if !queue.isEmpty {
                    Button("Clear") { together.clearQueue() }
                        .frame(minHeight: 44)
                }
            }
            if let notice = together.queueNotice {
                Text(notice)
                    .font(Theme.font(.caption))
                    .foregroundStyle(Theme.danger)
            }
            if queue.isEmpty {
                Text("Nothing queued yet. Choose Queue in the cast sheet to add videos to Up next.")
                    .font(Theme.font(.subheadline))
                    .foregroundStyle(Theme.onSurfaceVariant)
            } else {
                ForEach(Array(queue.enumerated()), id: \.element.id) { index, item in
                    UpNextRow(item: item, index: index, count: queue.count, canChange: canChange)
                }
            }
            Text("Up next is kept on this phone. The next video starts when this one ends. If you leave the party, the list goes with you.")
                .font(Theme.font(.caption))
                .foregroundStyle(Theme.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct UpNextRow: View {
    let item: PartyQueueItem
    let index: Int
    let count: Int
    let canChange: Bool
    @EnvironmentObject private var together: PhoneTogether

    var body: some View {
        HStack(spacing: 10) {
            Text("\(index + 1)")
                .font(Theme.font(.caption).monospacedDigit())
                .foregroundStyle(Theme.onSurfaceVariant)
                .frame(minWidth: 18)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(Theme.font(.body))
                    .foregroundStyle(Theme.onSurface)
                    .lineLimit(2)
                if let site = item.site {
                    Text(site)
                        .font(Theme.font(.caption))
                        .foregroundStyle(Theme.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if canChange {
                Button { together.playQueuedItemNow(item.id) } label: {
                    Image(systemName: "play.fill").frame(width: 44, height: 44)
                }
                .accessibilityLabel("Play \(item.title) now")
            }
            Menu {
                if index > 0 {
                    Button { together.moveQueueItem(from: index, to: index - 1) } label: {
                        Label("Move up", systemImage: "arrow.up")
                    }
                }
                if index < count - 1 {
                    Button { together.moveQueueItem(from: index, to: index + 1) } label: {
                        Label("Move down", systemImage: "arrow.down")
                    }
                }
                Button(role: .destructive) { together.removeFromQueue(item.id) } label: {
                    Label("Remove", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis").frame(width: 44, height: 44)
            }
            .accessibilityLabel("More for \(item.title)")
        }
        .foregroundStyle(Theme.onSurface)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Move up") {
            if index > 0 { together.moveQueueItem(from: index, to: index - 1) }
        }
        .accessibilityAction(named: "Move down") {
            if index < count - 1 { together.moveQueueItem(from: index, to: index + 1) }
        }
        .accessibilityAction(named: "Remove") { together.removeFromQueue(item.id) }
    }
}

private struct RoleBadge: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(Theme.font(.caption2).weight(.medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Theme.primary.opacity(0.18), in: Capsule())
            .foregroundStyle(Theme.primary)
    }
}

#if canImport(UIKit)
private struct ActivityShareView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
#endif

/// Only the host can act as host, so while the host is away nobody stands in. Waits a few
/// seconds after joining, while the host's hello is still on its way.
private struct HostAwayNote: View {
    let party: TogetherPartyState
    @State private var shown = false

    private var away: Bool { !party.host && party.connected && !party.members.values.contains { $0.host } }

    var body: some View {
        Group {
            if away && shown {
                PartyPanel {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("The host is away")
                            .font(Theme.font(.headline))
                            .foregroundStyle(Theme.onSurface)
                        Text(hostAwayMessage(party))
                            .font(Theme.font(.subheadline))
                            .foregroundStyle(Theme.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .task(id: away) {
            shown = false
            guard away else { return }
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            shown = !Task.isCancelled
        }
    }
}

func hostAwayMessage(_ party: TogetherPartyState) -> String {
    if party.locked { return "The video keeps playing. Controls stay locked until they're back." }
    if party.policy == .anyone { return "The video keeps playing, and anyone can still change it." }
    return "The video keeps playing. Nobody can change it until they're back."
}

/// Parties this phone joined recently and can go back to, as the same member.
private struct SavedPartiesSheet: View {
    let watchHere: Bool
    @EnvironmentObject private var together: PhoneTogether
    @Environment(\.dismiss) private var dismiss
    @State private var now = SavedWatchParties.nowMs()

    var body: some View {
        NavigationStack {
            List {
                if together.savedParties.isEmpty {
                    Text("Parties you start or join show up here until they end, so you can go back to them.")
                        .font(Theme.font(.subheadline))
                        .foregroundStyle(Theme.onSurfaceVariant)
                }
                ForEach(together.savedParties, id: \.roomId) { saved in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(saved.title)
                                .font(Theme.font(.body))
                                .lineLimit(1)
                            Text("\(SavedWatchParties.endsIn(saved, nowMs: now)) · \(saved.relay)")
                                .font(Theme.font(.caption))
                                .foregroundStyle(Theme.onSurfaceVariant)
                                .lineLimit(1)
                        }
                        Spacer()
                        if saved.roomId == together.currentParty?.roomId {
                            Text("You're in it")
                                .font(Theme.font(.caption).weight(.semibold))
                                .foregroundStyle(Theme.primary)
                        } else {
                            Button(saved.host ? "Rejoin as host" : "Rejoin") {
                                together.rejoin(saved, watchHere: watchHere)
                                dismiss()
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                    .swipeActions {
                        Button("Remove", role: .destructive) { together.forgetSaved(saved.roomId) }
                    }
                }
            }
            .navigationTitle("Recent parties")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                now = SavedWatchParties.nowMs()
                together.loadSavedParties()
            }
        }
    }
}
