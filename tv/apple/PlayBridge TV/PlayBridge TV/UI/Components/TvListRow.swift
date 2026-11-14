import SwiftUI

struct TvListRow: View {
    let title: String
    var leadingText: String? = nil
    var subtitle: String? = nil
    var trailingText: String? = nil
    var selected: Bool = false
    var showCheckmark: Bool = false
    var activeFill: Bool = false
    var height: CGFloat = TvSize.listRow
    var tone: TvTone = .player
    var focusID: AppFocusID? = nil
    let action: () -> Void

    @Environment(\.tvColors) private var colors

    var body: some View {
        let baseBg: Color = {
            if activeFill {
                return tone == .player ? TvPlayerColors.accentContainer : colors.accentContainer
            }
            return tone == .player ? TvPlayerColors.row : colors.containerHigh
        }()
        let baseFg: Color = {
            if activeFill {
                return tone == .player ? TvPlayerColors.onAccentContainer : colors.onAccentContainer
            }
            return tone == .player ? TvPlayerColors.onSurface : colors.onSurface
        }()
        let focusFill = tone == .player ? TvPlayerColors.focusFill : colors.focusFill
        let onFocusFill = tone == .player ? TvPlayerColors.onFocusFill : colors.onFocusFill
        let ring = tone == .player ? TvPlayerColors.focusRing : colors.focusRing

        Button(action: action) {
            HStack(spacing: TvSpacing.l) {
                if let leadingText {
                    Text(leadingText)
                        .font(TvText.value.monospacedDigit())
                        .opacity(0.75)
                        .frame(width: TvScale.pt(62), alignment: .leading)
                }
                VStack(alignment: .leading, spacing: TvSpacing.xxs) {
                    Text(title)
                        .font(selected || activeFill ? TvText.rowSelected : TvText.row)
                        .lineLimit(1)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(TvText.small)
                            .opacity(0.78)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: TvSpacing.m)
                if let trailingText, !trailingText.isEmpty {
                    Text(trailingText)
                        .font(TvText.value)
                        .opacity(0.78)
                        .lineLimit(1)
                }
                if showCheckmark && selected {
                    Image(systemName: "checkmark")
                        .font(.system(size: TvScale.pt(18), weight: .bold))
                }
            }
            .padding(.horizontal, TvSpacing.xl)
            .frame(maxWidth: .infinity)
            .frame(height: height)
        }
        .buttonStyle(TvFocusButtonStyle(
            shape: RoundedRectangle(cornerRadius: TvRadius.row, style: .continuous),
            fill: baseBg,
            focusedFill: focusFill,
            foreground: baseFg,
            focusedForeground: onFocusFill,
            ringColor: ring,
            focusedScale: 1.015
        ))
        .appRemoteActionIfApp(tone, id: focusID, action: action)
    }
}
