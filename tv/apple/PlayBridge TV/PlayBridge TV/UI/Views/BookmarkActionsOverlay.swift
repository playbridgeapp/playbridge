import SwiftUI
import UIKit

/// Right-side action panel opened by long-pressing (or pressing Play/Pause on) a bookmark tile.
struct BookmarkActionsOverlay: View {
    let item: PlaybackHistoryItem
    let thumbnailURL: URL?
    let onPlay: (Bool) -> Void
    let onRemove: () -> Void
    let onDismiss: () -> Void

    @Environment(\.tvColors) private var colors
    @State private var resumeFocusID = AppFocusID()

    private var hasResume: Bool { item.resumePositionMs != nil }

    private var resumeTitle: String {
        if let resumeMs = item.resumePositionMs {
            return "Resume from \(HomeFormatting.formatShortTime(resumeMs))"
        }
        return "Play"
    }

    private var subtitleText: String? {
        HomeFormatting.bookmarkSubtitle(
            positionMs: item.positionMs,
            durationMs: item.durationMs,
            resumePositionMs: item.resumePositionMs
        )
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            Color.black.opacity(0.6)
                .ignoresSafeArea()
                .onTapGesture(perform: onDismiss)

            TvSidePanel(
                title: item.title ?? "Untitled Video",
                tone: .app
            ) {
                HStack(spacing: TvScale.pt(14)) {
                    Group {
                        if let thumbnailURL, let uiImage = UIImage(contentsOfFile: thumbnailURL.path) {
                            Image(uiImage: uiImage)
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                        } else {
                            colors.tileBlue
                                .overlay(
                                    Image(systemName: "film")
                                        .font(.system(size: TvScale.pt(18)))
                                        .foregroundColor(colors.onTileBlue.opacity(0.75))
                                )
                        }
                    }
                    .frame(width: TvScale.pt(96), height: TvScale.pt(54))
                    .clipShape(RoundedRectangle(cornerRadius: TvRadius.thumbSmall))

                    if let subtitleText {
                        Text(subtitleText)
                            .font(TvText.small)
                            .foregroundColor(colors.onSurfaceMuted)
                            .lineLimit(2)
                    }
                }
                .padding(.bottom, TvScale.pt(8))

                TvListRow(
                    title: resumeTitle,
                    selected: true,
                    activeFill: true,
                    tone: .app,
                    focusID: resumeFocusID,
                    action: { onPlay(false) }
                )

                if hasResume {
                    TvListRow(
                        title: "Start over",
                        tone: .app,
                        action: { onPlay(true) }
                    )
                }

                TvListRow(
                    title: "Remove bookmark",
                    tone: .app,
                    action: onRemove
                )
            }
            .frame(width: TvScale.pt(360))
            .padding(.trailing, TvScale.pt(28))
            .padding(.vertical, TvScale.pt(28))
        }
        .appRemoteScope(back: onDismiss, home: onDismiss, initialFocus: resumeFocusID)
        .onExitCommand(perform: onDismiss)
    }
}
