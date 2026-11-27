import SwiftUI

/// Corner pairing card shown over whatever screen is active (Home, Settings, or Player)
/// without navigating away or hiding the underlying content.
struct PairingRequestOverlay: View {
    let request: PairingRequest
    var overVideo: Bool = false
    @EnvironmentObject var server: WebSocketServer

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.opacity(overVideo ? 0.25 : 0.65)
                .ignoresSafeArea()

            PairingCornerCard(request: request, overVideo: overVideo)
                .padding(.top, overVideo ? TvSpacing.cornerInset : TvScale.pt(48))
                .padding(.trailing, overVideo ? TvSpacing.cornerInset : TvScale.pt(56))
        }
        .focusSection()
        .onExitCommand { server.denyPairing() }
    }
}

struct PairingCornerCard: View {
    let request: PairingRequest
    var overVideo: Bool = false
    @EnvironmentObject var server: WebSocketServer
    @Environment(\.tvColors) private var themeColors
    @FocusState private var denyFocused: Bool

    private var colors: TvColors {
        overVideo ? .dark : themeColors
    }

    private let totalSeconds: Double = 60

    var body: some View {
        TimelineView(.periodic(from: request.createdAt, by: 1.0)) { context in
            let elapsed = max(0, context.date.timeIntervalSince(request.createdAt))
            let remaining = max(0, Int(ceil(totalSeconds - elapsed)))
            let fraction = max(0, min(1, (totalSeconds - elapsed) / totalSeconds))

            VStack(alignment: .leading, spacing: TvScale.pt(20)) {
                HStack(spacing: TvScale.pt(16)) {
                    Circle()
                        .fill(colors.accentContainer)
                        .frame(width: TvScale.pt(52), height: TvScale.pt(52))
                        .overlay(
                            Image(systemName: "iphone")
                                .font(.system(size: TvScale.pt(24), weight: .semibold))
                                .foregroundColor(colors.onAccentContainer)
                        )

                    VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                        Text("\(request.deviceName) wants to connect")
                            .font(TvText.cardTitle)
                            .foregroundColor(colors.onSurface)
                            .lineLimit(2)
                        Text("Enter this code on the phone")
                            .font(TvText.caption)
                            .foregroundColor(colors.onSurfaceMuted)
                    }
                }

                Text(HomeFormatting.formattedSASCode(request.sasCode))
                    .font(.system(size: TvScale.pt(68), weight: .bold, design: .monospaced))
                    .foregroundColor(colors.accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: TvScale.pt(116))
                    .background(colors.surface)
                    .clipShape(RoundedRectangle(cornerRadius: TvRadius.code))

                VStack(alignment: .leading, spacing: TvScale.pt(8)) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(colors.bright)
                            Capsule()
                                .fill(colors.accent)
                                .frame(width: geo.size.width * fraction)
                        }
                    }
                    .frame(height: TvScale.pt(6))

                    Text(HomeFormatting.pairingExpiryText(secondsRemaining: remaining))
                        .font(TvText.caption)
                        .foregroundColor(colors.onSurfaceMuted)
                }

                Button(action: { server.denyPairing() }) {
                    Text("Don't connect")
                        .font(.system(size: TvScale.pt(19), weight: .semibold))
                        .foregroundColor(denyFocused ? colors.onFocusFill : colors.onSurface)
                        .frame(maxWidth: .infinity)
                        .frame(height: TvScale.pt(56))
                        .background(denyFocused ? colors.focusFill : colors.containerHigh)
                        .clipShape(Capsule())
                        .overlay(
                            Capsule()
                                .stroke(denyFocused ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                                .padding(-TvSize.focusRingGap)
                        )
                }
                .buttonStyle(TvNoChromeButtonStyle())
                .focusEffectDisabled()
                .focused($denyFocused)
                .appRemoteAction { server.denyPairing() }

                Text("Only connect phones you recognise. A connected phone can play video and open pages on this TV.")
                    .font(TvText.small)
                    .foregroundColor(colors.onSurfaceMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(TvScale.pt(32))
            .frame(width: overVideo ? TvSize.pairingCardWidth : TvScale.pt(680))
            .background(colors.container)
            .clipShape(RoundedRectangle(cornerRadius: TvRadius.card))
            .shadow(color: .black.opacity(0.45), radius: 30, y: 12)
        }
        .onAppear {
            denyFocused = true
        }
    }
}
