import SwiftUI

/// The queue as a bottom sheet of 16:9 cards, matching the mpv Chapters strip:
/// artwork, "NOW PLAYING" on the current item, then "S1 · E6" and the title.
/// Shared by both engines; native focus moves along the cards.
struct PlaylistOverlay: View {
    @EnvironmentObject var playlistStore: PlaylistStore
    @Binding var highlightedIndex: Int
    let onItemSelected: (Int) -> Void
    let onDismiss: () -> Void

    @FocusState private var focusedIndex: Int?

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 16) {
                    Text("Playlist").font(.system(size: 32, weight: .bold))
                    if playlistStore.currentIndex >= 0 {
                        Text("\(playlistStore.currentIndex + 1) of \(playlistStore.items.count)")
                            .font(.system(size: 24, weight: .medium))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .padding(.horizontal, 80)

                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 40) {
                            ForEach(playlistStore.items.indices, id: \.self) { index in
                                card(index)
                            }
                        }
                        .padding(.horizontal, 80)
                        .padding(.vertical, 28)
                    }
                    // A horizontal ScrollView takes all offered height; size it to the cards.
                    .fixedSize(horizontal: false, vertical: true)
                    .onAppear {
                        let start = max(playlistStore.currentIndex, 0)
                        if highlightedIndex != start { highlightedIndex = start }
                        focusedIndex = start
                        proxy.scrollTo(start, anchor: .center)
                    }
                    .onChange(of: highlightedIndex) { _, index in
                        if focusedIndex != index { focusedIndex = index }
                        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(index, anchor: .center) }
                    }
                }
            }
            .padding(.top, 32)
            .padding(.bottom, 48)
            .foregroundStyle(.white)
            .background(
                LinearGradient(colors: [.black.opacity(0), .black.opacity(0.75), .black.opacity(0.9)],
                               startPoint: .top, endPoint: .bottom)
                    .background(.ultraThinMaterial.opacity(0.6))
                    .ignoresSafeArea()
            )
        }
        // The host's container may be content-sized; pin the sheet to the bottom.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .ignoresSafeArea()
        .onChange(of: focusedIndex) { _, index in
            guard let index, highlightedIndex != index else { return }
            highlightedIndex = index
        }
        .transition(.move(edge: .bottom).combined(with: .opacity))
        .onExitCommand { onDismiss() }
    }

    private func card(_ index: Int) -> some View {
        let item = playlistStore.items[index]
        let metadata = item.hasVisualMetadata ? item.visualMetadata : nil
        let isCurrent = index == playlistStore.currentIndex
        let focused = focusedIndex == index
        let text = PlaylistCardText.lines(
            title: metadata.map(\.title).flatMap { $0.isEmpty ? nil : $0 } ?? item.titleOrNil,
            season: metadata.flatMap { $0.hasSeason ? Int($0.season) : nil },
            episode: metadata.flatMap { $0.hasEpisode ? Int($0.episode) : nil },
            episodeTitle: metadata.flatMap { $0.hasEpisodeTitle ? $0.episodeTitle : nil },
            index: index)
        return VStack(alignment: .leading, spacing: 10) {
            Button { onItemSelected(index) } label: {
                ZStack(alignment: .bottomLeading) {
                    ArtworkImage(urls: artwork(metadata)) { image in
                        image.resizable().aspectRatio(contentMode: .fill)
                    } placeholder: {
                        LinearGradient(colors: [.white.opacity(0.16), .white.opacity(0.05)],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                            .overlay(Image(systemName: "play.rectangle").font(.system(size: 44)).opacity(0.35))
                    }
                    .frame(width: Self.cardSize.width, height: Self.cardSize.height)
                    .clipped()
                    if isCurrent {
                        Text("NOW PLAYING")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Theme.accent, in: Capsule())
                            .padding(16)
                    }
                }
                .frame(width: Self.cardSize.width, height: Self.cardSize.height)
            }
            .buttonStyle(.card)
            .focused($focusedIndex, equals: index)
            .id(index)

            VStack(alignment: .leading, spacing: 2) {
                if !text.eyebrow.isEmpty {
                    Text(text.eyebrow).font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.6))
                }
                Text(text.title).font(.system(size: 24, weight: .semibold)).lineLimit(1)
                    .foregroundStyle(.white.opacity(focused ? 1 : 0.8))
            }
            .frame(width: Self.cardSize.width, alignment: .leading)
            .offset(y: focused ? 8 : 0)
            .animation(.easeOut(duration: 0.15), value: focused)
        }
    }

    private static let cardSize = CGSize(width: 400, height: 225)

    /// Landscape art first (backdrop, then album artwork), then the poster.
    private func artwork(_ metadata: Playbridge_VisualMetadata?) -> [URL] {
        guard let metadata else { return [] }
        if metadata.hasBackdropURL { return ArtworkURL.candidates(metadata.backdropURL, kind: .card) }
        if metadata.hasArtworkURL { return ArtworkURL.candidates(metadata.artworkURL, kind: .poster) }
        if metadata.hasPosterURL { return ArtworkURL.candidates(metadata.posterURL, kind: .poster) }
        return []
    }
}
