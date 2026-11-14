import SwiftUI

struct TvNoChromeButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .focusEffectDisabled()
    }
}

/// Shared focus treatment: colour swap + outer focus ring, no size change.
/// Never alters layout dimensions (padding, width, height) on focus.
struct TvFocusButtonStyle<S: InsettableShape>: ButtonStyle {
    let shape: S
    let fill: Color
    let focusedFill: Color
    let foreground: Color
    let focusedForeground: Color
    let ringColor: Color
    var focusedScale: CGFloat = 1.0

    @Environment(\.isFocused) private var isFocused

    func makeBody(configuration: Configuration) -> some View {
        let activeFill = isFocused ? focusedFill : fill
        let activeForeground = isFocused ? focusedForeground : foreground
        configuration.label
            .foregroundStyle(activeForeground)
            .background(activeFill, in: shape)
            .overlay {
                if isFocused {
                    shape
                        .strokeBorder(ringColor, lineWidth: TvSize.focusRingWidth)
                        .padding(-(TvSize.focusRingWidth + TvSize.focusRingGap))
                }
            }
            .scaleEffect(configuration.isPressed ? 0.97 : (isFocused ? focusedScale : 1.0))
            .animation(.easeOut(duration: 0.14), value: isFocused)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
            .focusEffectDisabled()
    }
}

extension View {
    @ViewBuilder
    func appRemoteActionIfApp(
        _ tone: TvTone,
        id: AppFocusID? = nil,
        action: @escaping () -> Void
    ) -> some View {
        if tone == .app {
            appRemoteAction(action, id: id)
        } else {
            self
        }
    }
}
