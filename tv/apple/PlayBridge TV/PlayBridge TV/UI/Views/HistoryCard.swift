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
        Button(action: action) {
            HStack(spacing: 0) {
                // 16:9 thumbnail
                ZStack(alignment: .bottomLeading) {
                    Color.white.opacity(0.08)
                    if isFocused {
                        Image(systemName: "play.fill")
                            .font(.system(size: 40))
                            .foregroundColor(.white)
                    } else {
                        Image(systemName: "film")
                            .font(.system(size: 34))
                            .foregroundColor(.white.opacity(0.3))
                    }
                }
                .aspectRatio(16 / 9, contentMode: .fit)

                // Info
                HStack(alignment: .center, spacing: 16) {
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
                            ProgressView(value: Double(position), total: Double(duration))
                                .tint(Theme.accent)
                            Text("Continue at \(resumeTimeLabel)")
                                .font(.system(size: 18))
                                .foregroundColor(isFocused ? .black.opacity(0.55) : .white.opacity(0.6))
                        }
                    }
                    Spacer()
                    Button(action: { historyStore.toggleFavorite(item: item) }) {
                        Image(systemName: item.isFavorite ? "star.fill" : "star")
                            .font(.system(size: 22))
                            .foregroundColor(item.isFavorite ? .yellow : (isFocused ? .black.opacity(0.4) : .white.opacity(0.3)))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 28)
                .frame(maxWidth: .infinity)
                .background(isFocused ? Color.white : Color.black.opacity(0.35))
            }
            .frame(height: 160)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .scaleEffect(isFocused ? 1.03 : 1.0)
            .animation(.interactiveSpring(), value: isFocused)
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}
