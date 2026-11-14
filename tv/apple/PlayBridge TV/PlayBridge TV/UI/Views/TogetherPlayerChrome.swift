import SwiftUI

/// Party chrome over the player (`TogetherPlayer.dc.html`), matching Android's `TogetherPartyChrome.kt`.
struct TogetherPlayerChrome: View {
    let party: TvTogether.PartyState
    var showMemberChips: Bool = true
    @Environment(\.tvColors) private var colors

    var body: some View {
        let ui = party.toPartyUi()
        ZStack {
            if showMemberChips && !ui.members.isEmpty {
                VStack {
                    HStack {
                        Spacer()
                        TogetherMemberChips(
                            names: ui.members.map(\.name),
                            waitingFor: ui.waitingFor
                        )
                    }
                    .padding(.top, TvSpacing.playerEdge)
                    .padding(.trailing, TvScale.pt(56))
                    Spacer()
                }
                .transition(.opacity)
            }

            VStack(spacing: TvSpacing.m) {
                if !ui.waitingFor.isEmpty {
                    TogetherWaitingPill(waitingFor: ui.waitingFor)
                }
                if let notice = ui.notice, !notice.isEmpty {
                    Text(notice)
                        .font(TvText.value)
                        .foregroundColor(TvPlayerColors.onSurface)
                        .lineLimit(2)
                        .padding(.horizontal, TvScale.pt(18))
                        .padding(.vertical, TvScale.pt(10))
                        .background(Capsule().fill(TvPlayerColors.panel.opacity(0.92)))
                }
            }
        }
        .allowsHitTesting(false)
    }
}

struct TogetherMemberChips: View {
    let names: [String]
    let waitingFor: [String]
    @Environment(\.tvColors) private var colors

    var body: some View {
        HStack(spacing: TvScale.pt(10)) {
            ForEach(Array(names.enumerated()), id: \.offset) { _, name in
                let waiting = waitingFor.contains(name)
                HStack(spacing: TvScale.pt(8)) {
                    if waiting {
                        ProgressView()
                            .tint(colors.surface)
                            .scaleEffect(0.7)
                    }
                    Text(name)
                        .font(.system(size: TvScale.pt(16), weight: waiting ? .semibold : .medium))
                        .foregroundColor(waiting ? colors.surface : TvPlayerColors.onSurface)
                        .lineLimit(1)
                        .frame(maxWidth: TvScale.pt(220), alignment: .leading)
                }
                .frame(height: TvScale.pt(40))
                .padding(.horizontal, TvScale.pt(16))
                .background(
                    Capsule().fill(waiting ? colors.onSurface : TvPlayerColors.control)
                )
            }
        }
    }
}

struct TogetherWaitingPill: View {
    let waitingFor: [String]
    @Environment(\.tvColors) private var colors

    var body: some View {
        if !waitingFor.isEmpty {
            HStack(spacing: TvSpacing.l) {
                ProgressView()
                    .tint(colors.accent)
                Text(TogetherFormatting.waitingLabel(waitingFor))
                    .font(TvText.cardTitle)
                    .foregroundColor(colors.onSurface)
                    .lineLimit(1)
            }
            .frame(height: TvScale.pt(76))
            .padding(.horizontal, TvScale.pt(32))
            .background(Capsule().fill(colors.container))
        }
    }
}
