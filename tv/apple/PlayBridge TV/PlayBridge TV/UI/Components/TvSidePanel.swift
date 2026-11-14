import SwiftUI

struct TvSidePanel<Content: View>: View {
    let title: String
    var trailingHeader: String? = nil
    var footerText: String? = nil
    var tone: TvTone = .player
    @ViewBuilder let content: () -> Content

    @Environment(\.tvColors) private var colors

    var body: some View {
        let bg = tone == .player ? TvPlayerColors.panel : colors.container
        let fg = tone == .player ? TvPlayerColors.onSurface : colors.onSurface
        let muted = tone == .player ? TvPlayerColors.onSurfaceMuted : colors.onSurfaceMuted

        VStack(alignment: .leading, spacing: TvSpacing.l) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(TvText.panelTitle)
                    .foregroundStyle(fg)
                    .lineLimit(1)
                Spacer()
                if let trailingHeader, !trailingHeader.isEmpty {
                    Text(trailingHeader)
                        .font(TvText.value)
                        .foregroundStyle(muted)
                }
            }

            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: TvSpacing.rowGap) {
                    content()
                }
                .padding(.vertical, TvSpacing.s)
                .padding(.horizontal, TvScale.pt(6))
            }

            if let footerText, !footerText.isEmpty {
                Text(footerText)
                    .font(TvText.caption)
                    .foregroundStyle(muted)
            }
        }
        .padding(.horizontal, TvSpacing.panelPaddingH - TvScale.pt(6))
        .padding(.vertical, TvSpacing.panelPaddingV)
        .frame(width: TvSize.sidePanelWidth)
        .frame(maxHeight: .infinity, alignment: .topLeading)
        .background(bg)
        .ignoresSafeArea()
    }
}

struct TvSidePanelHost<Panel: View, Content: View>: View {
    let isPresented: Bool
    @ViewBuilder let panel: () -> Panel
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack(alignment: .trailing) {
            content()
            if isPresented {
                panel()
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.18), value: isPresented)
    }
}
