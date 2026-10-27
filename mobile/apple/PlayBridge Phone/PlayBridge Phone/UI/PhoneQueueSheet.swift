import SwiftUI

/// Up next for a phone list (Remote canvas, board 31): the whole list this iPhone holds, not
/// just the window the receiver has, laid out like a music player's queue. Played items sit
/// behind a "Played" toggle (collapsed until opened; the choice is kept), then the current item
/// as a card that fills as it plays, a line naming the list with Repeat, Save and Clear, and
/// what's next. Hold and drag a row to reorder, swipe it to remove, tap it to play it (the sheet stays open).
/// The receiver follows through PhoneQueueCoordinator.
struct PhoneQueueSheet: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var library: PhoneMediaLibrary
    @ObservedObject private var queue = PhoneQueueCoordinator.shared
    @Environment(\.dismiss) private var dismiss
    @AppStorage("pb_phone_queue_show_played") private var showPlayed = false
    let looping: Bool
    let canLoop: Bool
    let onTogglePlay: () -> Void
    let onLoop: (Bool) -> Void
    @State private var saving: SavingItems?
    @ObservedObject private var tints = QueueTintStore.shared
    private var tint: QueueTint { tints.tint }
    private static let secondaryText = Color.white.opacity(0.68)

    private var played: [PhoneQueueCoordinator.Item] { Array(queue.items.prefix(queue.position)) }
    private var upcoming: [PhoneQueueCoordinator.Item] { Array(queue.items.dropFirst(queue.position + 1)) }

    var body: some View {
        NavigationStack {
            ScrollViewReader { reader in
                List {
                    if showPlayed {
                        ForEach(played) { item in
                            row(item, muted: true)
                                .listRowBackground(Color.clear)
                                .swipeActions(edge: .trailing) { removeAction(item) }
                        }
                    }
                    if !played.isEmpty { playedToggle.listRowBackground(Color.clear) }
                    if let current = queue.current {
                        currentCard(current)
                            .listRowInsets(EdgeInsets(top: 4, leading: 12, bottom: 4, trailing: 12))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .id("current")
                    }
                    listLine.listRowSeparator(.hidden).listRowBackground(Color.clear)
                    if upcoming.isEmpty {
                        Text("Nothing else is queued.").foregroundColor(Self.secondaryText).listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(upcoming) { item in
                        row(item, muted: false)
                            .listRowBackground(Color.clear)
                            .swipeActions(edge: .trailing) { removeAction(item) }
                    }
                    .onMove { offsets, destination in queue.moveUpcoming(from: offsets, to: destination) }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .background(LinearGradient(colors: [tint.top, tint.middle, tint.bottom], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
                .task(id: queue.current?.id) {
                    if let current = queue.current { await tints.load(current.media, url: library.localURL(current.media)) }
                }
                .onAppear { scrollToCurrent(reader, animated: false) }
                .onChange(of: queue.position) { _ in scrollToCurrent(reader, animated: true) }
                .onChange(of: showPlayed) { _ in scrollToCurrent(reader, animated: true) }
            }
            .safeAreaInset(edge: .bottom) { phoneNote }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(tint.top, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text("Up next").font(Theme.font(.headline))
                        Text("On \(vm.receiverName ?? "TV")").font(Theme.font(.caption)).foregroundColor(Self.secondaryText)
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .sheet(item: $saving) { target in
                AddPhoneMediaToCollection(items: target.items)
            }
        }
    }

    private var playedToggle: some View {
        Button { withAnimation { showPlayed.toggle() } } label: {
            HStack(spacing: 6) {
                Text("Played · \(played.count)").font(Theme.font(.subheadline).weight(.semibold))
                Image(systemName: showPlayed ? "chevron.up" : "chevron.down").font(.system(size: 13, weight: .semibold))
            }
            .foregroundColor(Color.white)
            .frame(minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(showPlayed ? "Hide \(played.count) played" : "Show \(played.count) played")
        .listRowSeparator(.hidden)
        .id("played-toggle")
    }

    private var phoneNote: some View {
        HStack(spacing: 8) {
            Image(systemName: "iphone").foregroundColor(.orange).font(.system(size: 13))
            Text("Plays from your phone. Keep it on the same Wi-Fi.")
                .font(Theme.font(.caption)).foregroundColor(Self.secondaryText)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18).padding(.vertical, 10)
        .background(tint.bottom)
    }

    private func scrollToCurrent(_ reader: ScrollViewProxy, animated: Bool) {
        let target = played.isEmpty ? "current" : "played-toggle"
        if animated { withAnimation { reader.scrollTo(target, anchor: .top) } } else { reader.scrollTo(target, anchor: .top) }
    }

    /// The current item: artwork doubling as play/pause, and a fill that tracks playback.
    private func currentCard(_ item: PhoneQueueCoordinator.Item) -> some View {
        let playback = vm.coordinator.playback
        let duration = Double(playback?.durationMs ?? 0) / 1000
        let position = Double(playback?.positionMs ?? 0) / 1000
        let progress = duration > 0 ? min(1, max(0, position / duration)) : 0
        let playing = playback?.state == "playing"
        return ZStack(alignment: .leading) {
            tint.cardBase
            GeometryReader { geo in tint.cardFill.frame(width: geo.size.width * progress) }
            HStack(spacing: 12) {
                Button(action: onTogglePlay) {
                    PhoneMediaThumbnailView(item: item.media, url: library.localURL(item.media), size: CGSize(width: 320, height: 320))
                        .id(item.id)
                        .frame(width: 80, height: 80).clipped()
                        .overlay(Color.black.opacity(0.35))
                        .overlay(Image(systemName: playing ? "pause.fill" : "play.fill").font(.system(size: 28)).foregroundColor(.white))
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(playing ? "Pause" : "Play")
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.media.title).font(Theme.font(.headline)).lineLimit(1)
                    HStack {
                        Text(subtitle(item)).lineLimit(1)
                        Spacer(minLength: 8)
                        if duration > 0 { Text("\(PhoneFilesModel.clock(position)) / \(PhoneFilesModel.clock(duration))") }
                    }
                    .font(Theme.font(.footnote))
                }
                .foregroundColor(tint.onCard)
                .padding(.trailing, 14)
            }
        }
        .background(Color.black)
        .frame(height: 80)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    /// Seconds left in the list, or nil when an upcoming item's length is unknown.
    private var timeLeft: Double? {
        guard upcoming.allSatisfy({ length($0) > 0 }) else { return nil }
        let playback = vm.coordinator.playback
        let reported = Double(playback?.durationMs ?? 0) / 1000
        let currentLength = reported > 0 ? reported : queue.current.map(length) ?? 0
        let currentLeft = max(0, currentLength - Double(playback?.positionMs ?? 0) / 1000)
        return upcoming.reduce(currentLeft) { $0 + length($1) }
    }

    /// "Playing from …", where the list is and the time left, with Repeat, Save and Clear.
    private var listLine: some View {
        let leftText = timeLeft.flatMap { $0 > 0 ? " · \(PhoneFilesModel.clock($0)) left" : nil } ?? ""
        let from = Text("Playing from ").foregroundColor(Self.secondaryText)
            + Text(queue.source ?? "your phone").bold().foregroundColor(Color.white)
        let place = Text("\(queue.position + 1) / \(queue.count)") + Text(leftText).foregroundColor(Self.secondaryText)
        return HStack(spacing: 2) {
            VStack(alignment: .leading, spacing: 2) {
                from.font(Theme.font(.subheadline)).lineLimit(1)
                place.font(Theme.font(.subheadline))
            }
            Spacer(minLength: 4)
            iconButton(looping ? "repeat.1.circle.fill" : "repeat.1", label: looping ? "Repeat this, on" : "Repeat this, off",
                       tint: looping ? tint.cardFill : Self.secondaryText) { onLoop(!looping) }
                .disabled(!canLoop)
            iconButton("bookmark", label: "Save as collection", tint: Self.secondaryText) {
                saving = SavingItems(items: queue.items.map(\.media))
            }
            iconButton("text.badge.xmark", label: "Clear queue", tint: Self.secondaryText) { queue.clearUpcoming() }
                .disabled(upcoming.isEmpty)
        }
    }

    private func iconButton(_ systemImage: String, label: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: 19)).foregroundColor(tint).frame(width: 44, height: 44)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(label)
    }

    /// A list row: artwork (square for songs, wide for videos and photos), title, artist, length.
    private func row(_ item: PhoneQueueCoordinator.Item, muted: Bool) -> some View {
        let square = item.media.kind == .audio
        return Button {
            queue.play(item.id)
        } label: {
            HStack(spacing: 12) {
                PhoneMediaThumbnailView(item: item.media, url: library.localURL(item.media),
                                        size: square ? CGSize(width: 208, height: 208) : CGSize(width: 320, height: 180))
                    .frame(width: square ? 52 : 80, height: square ? 52 : 45)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.media.title).font(Theme.font(.body).weight(.medium)).lineLimit(1)
                        .foregroundColor(muted ? Self.secondaryText : Color.white)
                    Text(subtitle(item)).font(Theme.font(.footnote)).foregroundColor(Self.secondaryText).lineLimit(1)
                }
                Spacer(minLength: 0)
                if let duration = item.media.duration, duration > 0 {
                    Text(PhoneFilesModel.clock(duration)).font(Theme.font(.footnote)).foregroundColor(Self.secondaryText)
                }
                if !muted {
                    Image(systemName: "line.3.horizontal").foregroundColor(Self.secondaryText).accessibilityHidden(true)
                }
            }
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Play \(item.media.title) now")
    }

    private func removeAction(_ item: PhoneQueueCoordinator.Item) -> some View {
        Button(role: .destructive) { queue.remove(item.id) } label: { Label("Remove", systemImage: "trash") }
    }

    private func subtitle(_ item: PhoneQueueCoordinator.Item) -> String {
        if let artist = item.media.artist, !artist.isEmpty { return artist }
        switch item.media.kind {
        case .audio: return "Song"
        case .video: return "Video"
        case .image: return "Photo"
        }
    }

    /// How long an item plays: its length, or a photo's slideshow time.
    private func length(_ item: PhoneQueueCoordinator.Item) -> Double {
        if let duration = item.media.duration, duration > 0 { return duration }
        return Double(item.displayDurationMs ?? 0) / 1000
    }

    private struct SavingItems: Identifiable {
        let id = UUID()
        let items: [PhoneMedia]
    }
}
