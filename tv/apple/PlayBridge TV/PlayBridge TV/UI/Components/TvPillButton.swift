import SwiftUI

enum TvPillStyle {
    case primary
    case secondary
    case subtle
    case destructive
}

struct TvPillButton: View {
    let title: String
    var icon: String? = nil
    var style: TvPillStyle = .secondary
    var tone: TvTone = .app
    var height: CGFloat = TvSize.pillHeight
    var focusID: AppFocusID? = nil
    let action: () -> Void

    @Environment(\.tvColors) private var colors

    private var palette: (fill: Color, fg: Color, focusedFill: Color, focusedFg: Color, ring: Color) {
        let focusFill = tone == .player ? TvPlayerColors.focusFill : colors.focusFill
        let onFocusFill = tone == .player ? TvPlayerColors.onFocusFill : colors.onFocusFill
        let ring = tone == .player ? TvPlayerColors.focusRing : colors.focusRing
        let onSurface = tone == .player ? TvPlayerColors.onSurface : colors.onSurface
        let subtleBg = tone == .player ? TvPlayerColors.control : colors.containerHigh
        switch style {
        case .primary:
            let fill = tone == .player ? TvPlayerColors.accent : colors.accent
            let onAccent = tone == .player ? TvPlayerColors.onFocusFill : colors.onAccent
            return (fill, onAccent, focusFill, onFocusFill, ring)
        case .secondary:
            return (subtleBg, onSurface, focusFill, onFocusFill, ring)
        case .subtle:
            return (.clear, tone == .player ? TvPlayerColors.onSurfaceMuted : colors.onSurfaceMuted,
                    focusFill, onFocusFill, ring)
        case .destructive:
            return (colors.errorContainer, colors.onErrorContainer, colors.error, colors.onError, colors.error)
        }
    }

    var body: some View {
        let p = palette
        Button(action: action) {
            HStack(spacing: TvSpacing.m) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: TvScale.pt(18), weight: .semibold))
                }
                Text(title)
                    .font(TvText.button)
                    .lineLimit(1)
            }
            .padding(.horizontal, TvSpacing.xl)
            .frame(height: height)
        }
        .buttonStyle(TvFocusButtonStyle(
            shape: Capsule(),
            fill: p.fill,
            focusedFill: p.focusedFill,
            foreground: p.fg,
            focusedForeground: p.focusedFg,
            ringColor: p.ring
        ))
        .appRemoteActionIfApp(tone, id: focusID, action: action)
    }
}
