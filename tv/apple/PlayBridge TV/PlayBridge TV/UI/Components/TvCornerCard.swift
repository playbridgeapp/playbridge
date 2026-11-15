import SwiftUI

struct TvCornerCard<Content: View>: View {
    var width: CGFloat = TvSize.cornerCardWidth
    @ViewBuilder let content: () -> Content

    @Environment(\.tvColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: TvSpacing.l) {
            content()
        }
        .padding(TvScale.pt(28))
        .frame(width: width, alignment: .leading)
        .background(colors.container, in: RoundedRectangle(cornerRadius: TvRadius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: TvRadius.card, style: .continuous)
                .stroke(colors.outline.opacity(0.45), lineWidth: 1)
        )
    }
}
