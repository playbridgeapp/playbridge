import SwiftUI

struct HistoryCard: View {
    let item: PlaybackHistoryItem
    @EnvironmentObject var historyStore: HistoryStore
    let action: () -> Void
    @FocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack {
                    LinearGradient(
                        colors: [Color.blue.opacity(0.4), Color.purple.opacity(0.4)],
                        startPoint: .top, endPoint: .bottom)
                    if isFocused {
                        Image(systemName: "play.fill").font(.system(size: 60)).foregroundColor(
                            .white)
                    }
                }
                .frame(height: 200)

                HStack {
                    VStack(alignment: .leading) {
                        Text(item.title ?? "Unknown").font(.headline).foregroundColor(
                            isFocused ? .black : .white)
                        Text(item.url.host ?? "Link").font(.subheadline).foregroundColor(
                            isFocused ? .black.opacity(0.6) : .white.opacity(0.5))
                    }
                    Spacer()
                    Button(action: { historyStore.toggleFavorite(item: item) }) {
                        Image(systemName: item.isFavorite ? "star.fill" : "star")
                            .foregroundColor(
                                item.isFavorite ? .yellow : (isFocused ? .black : .white))
                    }.buttonStyle(.plain)
                }
                .padding()
                .background(isFocused ? Color.white : Color.black.opacity(0.4))
            }
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .scaleEffect(isFocused ? 1.05 : 1.0)
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}
