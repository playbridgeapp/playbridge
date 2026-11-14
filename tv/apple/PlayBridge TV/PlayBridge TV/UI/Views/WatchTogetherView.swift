import Combine
import SwiftUI

struct WatchTogetherView: View {
    @ObservedObject var together: TvTogether
    let onBack: () -> Void

    @Environment(\.appRemoteDriver) private var appRemoteDriver
    @State private var stepFocusIDs: [TogetherStepFocusTarget: AppFocusID] = Dictionary(
        uniqueKeysWithValues: TogetherStepFocusTarget.allCases.map { ($0, AppFocusID()) }
    )
    @State private var lastRefocusedStepKey: String?

    @Environment(\.tvColors) private var colors
    @State private var showPairingURLAlert = false
    @State private var pairingURLDraft = ""

    var body: some View {
        ZStack {
            colors.surface.ignoresSafeArea()

            VStack(alignment: .leading, spacing: TvSpacing.xxl) {
                headerRow

                Group {
                    if !together.isAvailable {
                        unavailableView
                    } else {
                        stepContent
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)

                footerContent
            }
            .padding(.horizontal, TvSpacing.screenGutter)
            .padding(.top, TvSpacing.screenTop)
            .padding(.bottom, TvScale.pt(44))
        }
        .appRemoteScope(back: handleBack, home: handleBack, initialFocus: currentPrimaryFocusID)
        .onAppear {
            refocusPrimaryIfNeeded(force: true)
        }
        .onChange(of: together.state.stepKey) { _, _ in
            refocusPrimaryIfNeeded(force: false)
        }
        .onChange(of: together.isAvailable) { _, _ in
            refocusPrimaryIfNeeded(force: true)
        }
        .onExitCommand(perform: handleBack)
        .alert("Pairing service", isPresented: $showPairingURLAlert) {
            TextField("https://pair.playbridge.app", text: $pairingURLDraft)
            Button("Save") {
                together.setPairingURL(pairingURLDraft)
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The address of the Watch Together pairing service, for example https://pair.example.workers.dev")
        }
    }

    private func handleBack() {
        switch together.state {
        case .registering, .code:
            together.cancelPairing()
        default:
            break
        }
        onBack()
    }

    private var headerRow: some View {
        HStack(spacing: TvSpacing.m) {
            Image(systemName: "person.2.fill")
                .font(.system(size: TvScale.pt(22), weight: .semibold))
                .foregroundColor(colors.onSurfaceMuted)
            Text("Watch Together")
                .font(TvText.row.weight(.regular))
                .foregroundColor(colors.onSurfaceMuted)
            Spacer()
        }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch together.state {
        case .idle:
            stepView(
                title: "Watch in sync",
                bodyText: "Watch a video in sync with friends, at home or anywhere. Someone in the party enters this TV’s code on their phone, then you confirm here.",
                spinner: false
            ) {
                TvPillButton(
                    title: "Join a watch party",
                    tone: .app,
                    focusID: stepFocusIDs[.joinParty],
                    action: { together.startPairing() }
                )
            }

        case .registering:
            stepView(
                title: "Getting a code…",
                bodyText: nil,
                spinner: true
            ) {
                TvPillButton(
                    title: "Cancel",
                    tone: .app,
                    focusID: stepFocusIDs[.cancelRegistering],
                    action: { together.cancelPairing() }
                )
            }

        case .code(let code, let qrSize, let qrModules, let expiresAtMs):
            codeView(code: code, qrSize: qrSize, qrModules: qrModules, expiresAtMs: expiresAtMs)

        case .invited(let from, let relay):
            HStack {
                Spacer()
                TogetherInviteCard(
                    from: from,
                    relay: relay,
                    joinFocusID: stepFocusIDs[.joinInvite],
                    onJoin: { together.accept() },
                    onDecline: { together.decline() }
                )
                Spacer()
            }

        case .inParty(let party):
            partyView(party.toPartyUi())

        case .ended(let message):
            stepView(title: message, bodyText: nil, spinner: false) {
                TvPillButton(
                    title: "OK",
                    tone: .app,
                    focusID: stepFocusIDs[.endedOk],
                    action: { together.cancelPairing() }
                )
            }
        }
    }

    private var currentPrimaryFocusID: AppFocusID? {
        stepFocusIDs[TogetherStepFocusTarget.target(
            isAvailable: together.isAvailable,
            stepKey: together.state.stepKey
        )]
    }

    private func refocusPrimaryIfNeeded(force: Bool) {
        let stepKey = together.state.stepKey
        guard force || TogetherStepFocusTarget.shouldRefocus(
            previousStepKey: lastRefocusedStepKey,
            currentStepKey: stepKey
        ) else { return }
        lastRefocusedStepKey = stepKey
        DispatchQueue.main.async {
            if let id = currentPrimaryFocusID {
                appRemoteDriver?.focus(id)
            }
        }
    }

    private var unavailableView: some View {
        stepView(
            title: "Watch Together isn't available on this Apple TV.",
            bodyText: "This build does not include the Watch Together native library.",
            spinner: false
        ) {
            TvPillButton(
                title: "Back",
                tone: .app,
                focusID: stepFocusIDs[.unavailableBack],
                action: onBack
            )
        }
    }

    private func stepView<Action: View>(
        title: String,
        bodyText: String?,
        spinner: Bool,
        @ViewBuilder action: () -> Action
    ) -> some View {
        VStack(alignment: .leading, spacing: TvSpacing.xxxl) {
            VStack(alignment: .leading, spacing: TvScale.pt(10)) {
                HStack(spacing: TvSpacing.l) {
                    if spinner {
                        ProgressView()
                            .tint(colors.accent)
                            .scaleEffect(1.2)
                    }
                    Text(title)
                        .font(TvText.screenTitle)
                        .foregroundColor(colors.onSurface)
                }
                if let bodyText {
                    Text(bodyText)
                        .font(TvText.settingTitle.weight(.regular))
                        .foregroundColor(colors.onSurfaceMuted)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: TvScale.pt(680), alignment: .leading)
                }
            }
            action()
        }
    }

    private func codeView(code: String, qrSize: Int, qrModules: String, expiresAtMs: Int64) -> some View {
        HStack(alignment: .center, spacing: TvScale.pt(56)) {
            VStack(alignment: .leading, spacing: TvScale.pt(26)) {
                VStack(alignment: .leading, spacing: TvScale.pt(10)) {
                    Text("Join a watch party")
                        .font(.system(size: TvScale.pt(46), weight: .bold))
                        .foregroundColor(colors.onSurface)
                    Text("On a phone that’s in the party, open Watch Together and enter this code. You’ll confirm here before this TV joins.")
                        .font(TvText.settingTitle.weight(.regular))
                        .foregroundColor(colors.onSurfaceMuted)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: TvScale.pt(640), alignment: .leading)
                }

                Text(TogetherFormatting.groupPartyCode(code))
                    .font(.system(size: TvScale.pt(68), weight: .bold, design: .monospaced))
                    .foregroundColor(colors.accent)
                    .padding(.horizontal, TvScale.pt(36))
                    .frame(height: TvScale.pt(120))
                    .background(
                        RoundedRectangle(cornerRadius: TvRadius.card)
                            .fill(colors.container)
                    )

                TogetherCodeExpiryView(expiresAtMs: expiresAtMs)

                TvPillButton(
                    title: "Cancel",
                    tone: .app,
                    focusID: stepFocusIDs[.cancelCode],
                    action: { together.cancelPairing() }
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(spacing: TvScale.pt(18)) {
                TogetherQrModulesView(width: qrSize, modules: qrModules)
                    .frame(width: TvScale.pt(248), height: TvScale.pt(248))
                Text("Or scan it from the party screen")
                    .font(TvText.value)
                    .foregroundColor(colors.onSurface)
                    .multilineTextAlignment(.center)
            }
            .frame(width: TvScale.pt(380))
            .padding(TvScale.pt(32))
            .background(
                RoundedRectangle(cornerRadius: TvRadius.card)
                    .fill(colors.container)
            )
        }
    }

    private func partyView(_ party: TogetherPartyUi) -> some View {
        VStack(alignment: .leading, spacing: TvScale.pt(30)) {
            VStack(alignment: .leading, spacing: TvScale.pt(10)) {
                Text(party.partyName)
                    .font(.system(size: TvScale.pt(50), weight: .bold))
                    .foregroundColor(colors.onSurface)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: TvScale.pt(14)) {
                    let line: String = {
                        switch party.status {
                        case .connecting(let relay):
                            return "Connecting to \(relay)…"
                        case .nowPlaying(let title):
                            return "Now playing: \(title)"
                        case .waitingForHost:
                            return "Waiting for the host to pick a video…"
                        }
                    }()
                    if case .nowPlaying = party.status {
                        EmptyView()
                    } else {
                        ProgressView()
                            .tint(colors.accent)
                    }
                    Text(line)
                        .font(TvText.cardTitle.weight(.regular))
                        .foregroundColor(colors.onSurfaceMuted)
                        .lineLimit(2)
                }
            }

            if !party.members.isEmpty {
                VStack(alignment: .leading, spacing: TvSpacing.m) {
                    Text("In the party")
                        .font(TvText.caption)
                        .foregroundColor(colors.onSurfaceMuted)

                    HStack(spacing: TvSpacing.m) {
                        ForEach(party.members) { member in
                            HStack(spacing: TvScale.pt(10)) {
                                Text(member.name)
                                    .font(TvText.row)
                                    .foregroundColor(colors.onSurface)
                                    .lineLimit(1)
                                    .frame(maxWidth: TvScale.pt(320), alignment: .leading)
                                if member.isHost {
                                    Text("Host")
                                        .font(TvText.small)
                                        .foregroundColor(colors.onSurfaceMuted)
                                }
                            }
                            .frame(height: TvScale.pt(52))
                            .padding(.horizontal, TvScale.pt(22))
                            .background(Capsule().fill(colors.container))
                        }
                    }
                }
            }

            if !party.waitingFor.isEmpty {
                Text(TogetherFormatting.waitingLabel(party.waitingFor))
                    .font(TvText.caption)
                    .foregroundColor(colors.onSurfaceMuted)
            }

            if let notice = party.notice, !notice.isEmpty {
                Text(notice)
                    .font(TvText.caption)
                    .foregroundColor(colors.error)
            }

            TvPillButton(
                title: "Leave party",
                tone: .app,
                focusID: stepFocusIDs[.leaveParty],
                action: { together.leave() }
            )
        }
    }

    @ViewBuilder
    private var footerContent: some View {
        VStack(alignment: .leading, spacing: TvSpacing.m) {
            switch together.state {
            case .inParty:
                Text("Nothing from the party is saved to this TV’s bookmarks or recent casts.")
                    .font(TvText.caption)
                    .foregroundColor(colors.onSurfaceMuted)
            case .code:
                Text("Already paired with a phone in the party? Add this TV from that phone instead. No code needed.")
                    .font(TvText.caption)
                    .foregroundColor(colors.onSurfaceMuted)
            default:
                EmptyView()
            }

            if !together.isInParty && together.isAvailable {
                TvListRow(
                    title: "Pairing service",
                    trailingText: together.pairingURL,
                    tone: .app,
                    action: {
                        pairingURLDraft = together.pairingURL
                        showPairingURLAlert = true
                    }
                )
                .frame(maxWidth: TvScale.pt(640))
            }
        }
    }
}

private struct TogetherCodeExpiryView: View {
    let expiresAtMs: Int64
    @Environment(\.tvColors) private var colors
    @State private var nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    @State private var initialDurationMs: Int64 = 1

    private let timer = Timer.publish(every: 1.0, on: .main, in: .common).autoconnect()

    var body: some View {
        let remaining = expiresAtMs - nowMs
        let fraction = max(0, min(1, Double(remaining) / Double(max(1, initialDurationMs))))

        VStack(alignment: .leading, spacing: TvSpacing.s) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(colors.outline)
                    Capsule()
                        .fill(colors.accent)
                        .frame(width: geo.size.width * fraction)
                }
            }
            .frame(width: TvScale.pt(520), height: TvScale.pt(6))

            Text(
                TogetherFormatting.expiryLabel(remainingMs: remaining)
                    .map { "Expires in \($0)" }
                    ?? "This code has expired. Cancel and try again."
            )
            .font(TvText.caption)
            .foregroundColor(colors.onSurfaceMuted)
        }
        .onAppear {
            let current = Int64(Date().timeIntervalSince1970 * 1000)
            nowMs = current
            initialDurationMs = max(1, expiresAtMs - current)
        }
        .onReceive(timer) { date in
            nowMs = Int64(date.timeIntervalSince1970 * 1000)
        }
    }
}

struct TogetherInviteCard: View {
    let from: String
    let relay: String
    var joinFocusID: AppFocusID? = nil
    let onJoin: () -> Void
    let onDecline: () -> Void

    @Environment(\.tvColors) private var colors

    var body: some View {
        let inviter = from.trimmingCharacters(in: .whitespacesAndNewlines)
        VStack(alignment: .leading, spacing: TvScale.pt(22)) {
            HStack(spacing: TvSpacing.l) {
                Circle()
                    .fill(colors.tileBlue)
                    .frame(width: TvScale.pt(52), height: TvScale.pt(52))
                    .overlay(
                        Image(systemName: "person.2.fill")
                            .font(.system(size: TvScale.pt(22), weight: .semibold))
                            .foregroundColor(colors.onTileBlue)
                    )

                Text("Join \(inviter.isEmpty ? "someone" : inviter)’s watch party?")
                    .font(TvText.cardHeadline)
                    .foregroundColor(colors.onSurface)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Text("This TV will play in sync with everyone in the party. Only join parties from people you know.")
                .font(TvText.value.weight(.regular))
                .foregroundColor(colors.onSurfaceMuted)
                .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: TvSpacing.xxs) {
                Text("The party runs on")
                    .font(TvText.small)
                    .foregroundColor(colors.onSurfaceMuted)
                Text(relay)
                    .font(TvText.row)
                    .foregroundColor(colors.onSurface)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, TvScale.pt(18))
            .padding(.vertical, TvScale.pt(16))
            .background(
                RoundedRectangle(cornerRadius: TvRadius.row)
                    .fill(colors.surface)
            )

            HStack(spacing: TvSpacing.m) {
                TvPillButton(title: "Join", tone: .app, focusID: joinFocusID, action: onJoin)
                TvPillButton(title: "Not now", style: .secondary, tone: .app, action: onDecline)
            }
        }
        .padding(TvScale.pt(32))
        .frame(width: TvScale.pt(520))
        .background(
            RoundedRectangle(cornerRadius: TvRadius.card)
                .fill(colors.container)
        )
    }
}

private struct TogetherQrModulesView: View {
    let width: Int
    let modules: String

    var body: some View {
        Canvas { context, size in
            guard width > 0, modules.count == width * width else { return }
            let inset = TvScale.pt(12)
            let side = max(1, min(size.width, size.height) - inset * 2)
            let cell = side / CGFloat(width)
            let originX = (size.width - side) / 2
            let originY = (size.height - side) / 2
            let chars = Array(modules)

            for row in 0..<width {
                for col in 0..<width {
                    if chars[row * width + col] == "1" {
                        let rect = CGRect(
                            x: originX + CGFloat(col) * cell,
                            y: originY + CGFloat(row) * cell,
                            width: ceil(cell),
                            height: ceil(cell)
                        )
                        context.fill(Path(rect), with: .color(.black))
                    }
                }
            }
        }
        .background(
            RoundedRectangle(cornerRadius: TvScale.pt(12))
                .fill(Color.white)
        )
    }
}
