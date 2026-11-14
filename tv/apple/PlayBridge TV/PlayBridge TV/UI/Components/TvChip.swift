import SwiftUI

struct TvChip: View {
    let text: String
    var dotColor: Color? = nil
    var tone: TvTone = .app

    @Environment(\.tvColors) private var colors

    var body: some View {
        let bg = tone == .player ? TvPlayerColors.panel : colors.containerHigh
        let fg = tone == .player ? TvPlayerColors.onSurface : colors.onSurface

        HStack(spacing: TvSpacing.s) {
            if let dotColor {
                Circle()
                    .fill(dotColor)
                    .frame(width: TvScale.pt(8), height: TvScale.pt(8))
            }
            Text(text)
                .font(TvText.value)
                .foregroundStyle(fg)
                .lineLimit(1)
        }
        .padding(.horizontal, TvSpacing.l)
        .frame(height: TvSize.chip)
        .background(bg, in: Capsule())
    }
}
