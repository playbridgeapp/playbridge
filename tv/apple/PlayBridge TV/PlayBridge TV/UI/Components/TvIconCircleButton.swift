import SwiftUI

struct TvIconCircleButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var diameter: CGFloat = TvSize.iconButton
    var iconSize: CGFloat = TvScale.pt(26)
    var selected: Bool = false
    var tone: TvTone = .player
    var focusID: AppFocusID? = nil
    let action: () -> Void

    @Environment(\.tvColors) private var colors

    var body: some View {
        let bg: Color = {
            if selected {
                return tone == .player ? TvPlayerColors.accentContainer : colors.accentContainer
            }
            return tone == .player ? TvPlayerColors.control : colors.containerHigh
        }()
        let fg: Color = {
            if selected {
                return tone == .player ? TvPlayerColors.onAccentContainer : colors.onAccentContainer
            }
            return tone == .player ? TvPlayerColors.onSurface : colors.onSurface
        }()
        let focusFill = tone == .player ? TvPlayerColors.focusFill : colors.focusFill
        let onFocusFill = tone == .player ? TvPlayerColors.onFocusFill : colors.onFocusFill
        let ring = tone == .player ? TvPlayerColors.focusRing : colors.focusRing

        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: iconSize, weight: .semibold))
                .frame(width: diameter, height: diameter)
        }
        .accessibilityLabel(accessibilityLabel)
        .buttonStyle(TvFocusButtonStyle(
            shape: Circle(),
            fill: bg,
            focusedFill: focusFill,
            foreground: fg,
            focusedForeground: onFocusFill,
            ringColor: ring,
            focusedScale: 1.05
        ))
        .appRemoteActionIfApp(tone, id: focusID, action: action)
    }
}
