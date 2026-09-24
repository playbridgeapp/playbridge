import SwiftUI

struct LibraryListView: View {
    let title: String
    let items: [PlaybackHistoryItem]
    @EnvironmentObject var historyStore: HistoryStore
    @EnvironmentObject var server: WebSocketServer
    @State private var showClearConfirm = false

    private var continueWatching: [PlaybackHistoryItem] {
        title == "History" ? items.filter { $0.resumePositionMs != nil } : []
    }

    private var recentItems: [PlaybackHistoryItem] {
        title == "History" ? items.filter { $0.resumePositionMs == nil } : items
    }

    var body: some View {
        VStack(alignment: .leading) {
            HStack(alignment: .center, spacing: 30) {
                Text(title).font(.system(size: 50, weight: .black))
                if title == "History" && (!items.isEmpty || historyStore.storageUnavailable) {
                    DangerButton(title: "Clear All", icon: "trash") { showClearConfirm = true }
                        .frame(width: 250)
                }
                Spacer()
            }
            .padding([.leading, .trailing, .top], 60)
            if historyStore.storageUnavailable {
                Text("History storage is unavailable. Changes made now may not be saved.")
                    .foregroundColor(.orange)
                    .padding(.horizontal, 60)
            }
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
                        if !continueWatching.isEmpty {
                            Text("Continue Watching")
                                .font(.system(size: 34, weight: .bold))
                                .frame(maxWidth: .infinity, alignment: .leading)
                            ForEach(continueWatching) { item in historyCard(for: item) }
                        }
                        if !recentItems.isEmpty {
                            if !continueWatching.isEmpty {
                                Text("Recent")
                                    .font(.system(size: 34, weight: .bold))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.top, 20)
                            }
                            ForEach(recentItems) { item in historyCard(for: item) }
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

    private func historyCard(for item: PlaybackHistoryItem) -> some View {
        HistoryCard(item: item) {
            var payload = Playbridge_PlayPayload()
            payload.url = item.url.absoluteString
            if let headers = item.headers { payload.headers = headers }
            if let title = item.title { payload.title = title }
            if let resumePositionMs = item.resumePositionMs {
                payload.startPositionMs = resumePositionMs
            }
            server.handlePlay(payload)
        }
    }
}
