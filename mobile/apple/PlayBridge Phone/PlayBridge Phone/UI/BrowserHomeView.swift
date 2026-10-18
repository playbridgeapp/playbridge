// SPDX-License-Identifier: MPL-2.0
import SwiftUI

/// New-tab / home page: shows bookmarks and recent history.
struct BrowserHomeView: View {
    let onOpen: (String) -> Void
    @EnvironmentObject private var data: BrowserDataStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("PlayBridge")
                    .font(Theme.font(size: 26, weight: .bold, design: .rounded))
                    .foregroundColor(Theme.onSurface)
                    .frame(maxWidth: .infinity).padding(.top, 24)

                if !data.bookmarks.isEmpty {
                    sectionTitle("Bookmarks")
                    grid(items: data.bookmarks.prefix(8).map { ($0.title, $0.url) })
                }
                if !data.topVisited.isEmpty {
                    sectionTitle("Top Visited")
                    grid(items: data.topVisited.map { ($0.title, $0.url) })
                        .accessibilityIdentifier("browserTopVisited")
                }
                if !data.history.isEmpty {
                    sectionTitle("Recent")
                    VStack(spacing: 8) {
                        ForEach(Array(data.history.prefix(8))) { h in
                            Button { onOpen(h.url) } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "clock.arrow.circlepath")
                                        .font(Theme.font(size: 14)).foregroundColor(Theme.onSurfaceVariant).frame(width: 22)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(h.title).font(Theme.font(size: 14)).foregroundColor(Theme.onSurface).lineLimit(1)
                                        Text(URL(string: h.url)?.host ?? h.url).font(Theme.font(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                                    }
                                    Spacer()
                                }
                                .contentShape(Rectangle())
                                .padding(12)
                                .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surfaceContainer))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if data.bookmarks.isEmpty && data.history.isEmpty {
                    Text("Search or enter an address above to get started.")
                        .font(Theme.font(size: 14)).foregroundColor(Theme.onSurfaceVariant)
                        .frame(maxWidth: .infinity).padding(.top, 40)
                }
                Spacer(minLength: 20)
            }
            .padding(.horizontal, 16)
        }
        .background(Theme.surface)
    }

    private func sectionTitle(_ t: String) -> some View {
        Text(t).font(Theme.font(size: 13, weight: .bold)).foregroundColor(Theme.onSurfaceVariant)
    }

    private func grid(items: [(String, String)]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: 10)], spacing: 10) {
            ForEach(items.indices, id: \.self) { i in
                let item = items[i]
                Button { onOpen(item.1) } label: {
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainerHigh).frame(height: 56)
                            Image(systemName: "globe").font(.system(size: 22)).foregroundColor(Theme.primary)
                        }
                        Text(item.0).font(Theme.font(size: 11)).foregroundColor(Theme.onSurface).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}
