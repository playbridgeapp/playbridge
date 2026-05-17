import SwiftUI

struct LibraryListView: View {
    let title: String
    let items: [PlaybackHistoryItem]
    @EnvironmentObject var historyStore: HistoryStore
    @EnvironmentObject var server: WebSocketServer
    @State private var showClearConfirm = false

    var body: some View {
        VStack(alignment: .leading) {
            HStack(alignment: .center, spacing: 30) {
                Text(title).font(.system(size: 50, weight: .black))
                if title == "History" && !items.isEmpty {
                    DangerButton(title: "Clear All", icon: "trash") { showClearConfirm = true }
                        .frame(width: 250)
                }
                Spacer()
            }
            .padding([.leading, .trailing, .top], 60)
            if items.isEmpty {
                Spacer()
                HStack {
                    Spacer()
                    VStack(spacing: 20) {
                        Image(systemName: title == "History" ? "clock.badge.exclamationmark" : "star.slash")
                            .font(.system(size: 100))
                            .foregroundColor(Theme.secondaryText.opacity(0.5))
                        Text("Nothing to see here yet.")
                            .font(.system(size: 36, weight: .semibold))
                            .foregroundColor(Theme.secondaryText)
                    }
                    Spacer()
                }
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 16) {
                        ForEach(items) { item in
                            HistoryCard(item: item) {
                                var payload = Playbridge_PlayPayload()
                                payload.url = item.url.absoluteString
                                if let headers = item.headers {
                                    payload.headers = headers
                                }
                                server.currentPlayRequest = payload
                            }
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 20)
                }
            }
        }
        .alert("Clear Data", isPresented: $showClearConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Erase All", role: .destructive) { historyStore.clearHistory() }
        }
    }
}
