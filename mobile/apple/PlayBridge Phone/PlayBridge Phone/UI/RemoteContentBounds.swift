import SwiftUI

/// Give the pad the remaining height; scroll only when the controls' minimum height cannot fit.
/// A SwiftUI frame alone neither clips drawing nor restricts overflowing children's hit tests.
struct RemoteContentBounds<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        GeometryReader { geometry in
            ViewThatFits(in: .vertical) {
                content()
                ScrollView(.vertical) {
                    content().frame(minHeight: geometry.size.height, alignment: .top)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .top)
            .contentShape(Rectangle())
            .clipped()
        }
        .contentShape(Rectangle())
        .clipped()
    }
}
