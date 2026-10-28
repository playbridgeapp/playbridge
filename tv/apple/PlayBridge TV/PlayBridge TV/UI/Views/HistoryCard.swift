import SwiftUI

struct HistoryCard: View {
    let item: PlaybackHistoryItem
    @EnvironmentObject var historyStore: HistoryStore
    let action: () -> Void
    @FocusState private var isFocused: Bool

    private var resumeTimeLabel: String? {
        guard let position = item.resumePositionMs else { return nil }
        let seconds = position / 1_000
        let minutes = seconds / 60
        if minutes >= 60 {
            return String(format: "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
        }
        return String(format: "%d:%02d", minutes, seconds % 60)
    }

    var body: some View {
        HStack(spacing: 0) {
            Button(action: action) {
                HStack(spacing: 0) {
                    ZStack(alignment: .bottomLeading) {
                        Color.white.opacity(0.08)
                        Image(systemName: isFocused ? "play.fill" : "film")
                            .font(.system(size: isFocused ? 40 : 34))
                            .foregroundColor(isFocused ? .white : .white.opacity(0.3))
                    }
                    .frame(width: 284)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.title ?? "Unknown")
                            .font(.system(size: 32, weight: .semibold))
                            .foregroundColor(isFocused ? .black : .white)
                            .lineLimit(1)
                        Text(item.url.host ?? "Saved media")
                            .font(.system(size: 22))
                            .foregroundColor(isFocused ? .black.opacity(0.55) : .white.opacity(0.45))
                            .lineLimit(1)
                        if let position = item.resumePositionMs, let duration = item.durationMs,
                           let resumeTimeLabel {
                            ProgressView(value: Double(position), total: Double(duration)).tint(Theme.accent)
                            Text("Continue at \(resumeTimeLabel)")
                                .font(.system(size: 18))
                                .foregroundColor(isFocused ? .black.opacity(0.55) : .white.opacity(0.6))
                        }
                    }
                    .padding(.horizontal, 28)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(isFocused ? Color.white : Color.black.opacity(0.35))
                }
            }
            .buttonStyle(.plain)
            .focused($isFocused)
            .appRemoteAction(action)
            Button(action: { historyStore.toggleFavorite(item: item) }) {
                Image(systemName: item.isFavorite ? "star.fill" : "star")
                    .font(.system(size: 28))
                    .foregroundColor(item.isFavorite ? .yellow : .white.opacity(0.7))
                    .frame(width: 90, height: 160)
            }
            .buttonStyle(.card)
            .appRemoteAction { historyStore.toggleFavorite(item: item) }
        }
        .frame(height: 160)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .scaleEffect(isFocused ? 1.03 : 1.0)
        .animation(.interactiveSpring(), value: isFocused)
    }
}
