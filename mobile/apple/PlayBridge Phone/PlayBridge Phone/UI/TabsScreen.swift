import SwiftUI

/// Tab switcher: grid of open tabs with live snapshots, plus new/close. Switching dismisses.
struct TabsScreen: View {
    @ObservedObject var store: BrowserStore
    @Environment(\.dismiss) private var dismiss

    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 14)]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(store.tabs) { tab in
                        TabCard(tab: tab, isActive: tab.id == store.activeID,
                                onSelect: { store.select(tab.id); dismiss() },
                                onClose: { store.closeTab(tab.id) })
                    }
                }
                .padding(16)
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("\(store.tabs.count) Tab\(store.tabs.count == 1 ? "" : "s")")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { store.newTab(); dismiss() } label: { Image(systemName: "plus") }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

private struct TabCard: View {
    @ObservedObject var tab: BrowserTab
    let isActive: Bool
    let onSelect: () -> Void
    let onClose: () -> Void

    @State private var snapshot: UIImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                Group {
                    if let snapshot {
                        Image(uiImage: snapshot).resizable().aspectRatio(contentMode: .fill)
                    } else {
                        Theme.surfaceContainerLow
                    }
                }
                .frame(height: 110)
                .clipped()

                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.white)
                        .background(Circle().fill(Color.black.opacity(0.4)))
                }
                .padding(6)
            }
            Text(tab.title)
                .font(.caption).foregroundColor(Theme.onSurface)
                .lineLimit(1)
                .padding(8)
        }
        .background(Theme.surfaceContainer)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(isActive ? Theme.primary : Color.clear, lineWidth: 2)
        )
        .cornerRadius(12)
        .onTapGesture(perform: onSelect)
        .onAppear {
            tab.webView.takeSnapshot(with: nil) { image, _ in snapshot = image }
        }
    }
}
