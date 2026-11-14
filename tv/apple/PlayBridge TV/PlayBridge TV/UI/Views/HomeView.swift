import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

struct HomeView: View {
    @EnvironmentObject var server: WebSocketServer
    @EnvironmentObject var historyStore: HistoryStore
    @Environment(\.tvColors) private var colors

    let onOpenWatchTogether: () -> Void
    let onOpenSettings: () -> Void

    @State private var selectedActionItem: PlaybackHistoryItem?

    private var bookmarks: [PlaybackHistoryItem] {
        historyStore.bookmarkedItems
    }

    private var isFirstRun: Bool {
        HomeFormatting.isFirstRun(
            pairedCount: max(server.pairedDevicesList.count, server.connectedCount),
            bookmarkCount: bookmarks.count
        )
    }

    private var addressLabel: String {
        if let port = server.wssPort {
            return "\(server.localIP):\(port)"
        }
        return server.localIP
    }

    var body: some View {
        ZStack {
            colors.surface.ignoresSafeArea()

            VStack(alignment: .leading, spacing: TvScale.pt(22)) {
                topBar

                if isFirstRun {
                    firstRunContent
                } else {
                    bookmarksContent
                }
            }
            .padding(.horizontal, TvSpacing.screenGutter)
            .padding(.top, TvSpacing.screenTop)
            .padding(.bottom, TvScale.pt(32))
            .disabled(selectedActionItem != nil)

            if let actionItem = selectedActionItem {
                BookmarkActionsOverlay(
                    item: actionItem,
                    thumbnailURL: historyStore.validThumbnailURL(for: actionItem),
                    onPlay: { startOver in
                        selectedActionItem = nil
                        server.playBookmark(actionItem, startOver: startOver)
                    },
                    onRemove: {
                        historyStore.toggleBookmark(
                            url: actionItem.url,
                            title: actionItem.title,
                            headers: actionItem.headers
                        )
                        selectedActionItem = nil
                    },
                    onDismiss: {
                        selectedActionItem = nil
                    }
                )
                .zIndex(15)
            }
        }
    }

    private var topBar: some View {
        HStack {
            HStack(spacing: TvSpacing.m) {
                RoundedRectangle(cornerRadius: TvScale.pt(9))
                    .fill(colors.accent)
                    .frame(width: TvScale.pt(32), height: TvScale.pt(32))
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: TvScale.pt(15), weight: .bold))
                            .foregroundColor(colors.onAccent)
                    )
                Text("PlayBridge")
                    .font(.system(size: TvScale.pt(22), weight: .semibold))
                    .foregroundColor(colors.onSurface)
            }

            Spacer()

            HStack(spacing: TvSpacing.m) {
                TvPillButton(
                    title: "Watch Together",
                    icon: "person.2.fill",
                    tone: .app,
                    height: TvSize.pillHeightCompact,
                    action: onOpenWatchTogether
                )

                TvIconCircleButton(
                    systemImage: "gearshape.fill",
                    accessibilityLabel: "Settings",
                    diameter: TvSize.iconButtonSmall,
                    iconSize: TvScale.pt(22),
                    tone: .app,
                    action: onOpenSettings
                )
            }
        }
        .focusSection()
    }

    private var bookmarksContent: some View {
        VStack(alignment: .leading, spacing: TvScale.pt(26)) {
            VStack(alignment: .leading, spacing: TvScale.pt(10)) {
                Text(server.deviceName)
                    .font(TvText.pageTitle)
                    .foregroundColor(colors.onSurface)

                HStack(spacing: TvScale.pt(14)) {
                    Text(server.serverState == "Listening" ? "Ready to cast" : server.serverState)
                        .font(.system(size: TvScale.pt(18), weight: .regular))
                        .foregroundColor(colors.onSurfaceMuted)

                    if let summary = HomeFormatting.connectionSummary(
                        connectedCount: server.connectedCount,
                        connectedNames: server.connectedDeviceNames
                    ) {
                        HStack(spacing: TvScale.pt(8)) {
                            Image(systemName: "iphone")
                                .font(.system(size: TvScale.pt(14), weight: .semibold))
                            Text(summary)
                                .font(.system(size: TvScale.pt(16), weight: .medium))
                        }
                        .foregroundColor(colors.onAccentContainer)
                        .padding(.horizontal, TvScale.pt(14))
                        .frame(height: TvSize.chip)
                        .background(colors.accentContainer)
                        .clipShape(Capsule())
                    }

                    Text(addressLabel)
                        .font(TvText.small)
                        .foregroundColor(colors.onSurfaceMuted)
                        .lineLimit(1)
                }
            }

            VStack(alignment: .leading, spacing: TvScale.pt(12)) {
                Text("Bookmarks")
                    .font(TvText.sectionHeading)
                    .foregroundColor(colors.onSurface)

                if bookmarks.isEmpty {
                    bookmarkHint
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: TvScale.pt(22)) {
                            ForEach(Array(bookmarks.enumerated()), id: \.element.id) { index, item in
                                BookmarkTile(
                                    item: item,
                                    index: index,
                                    thumbnailURL: historyStore.validThumbnailURL(for: item),
                                    onSelect: { server.playBookmark(item, startOver: false) },
                                    onLongPress: { selectedActionItem = item }
                                )
                            }
                        }
                        // Generous padding prevents focus rings from clipping inside the ScrollView.
                        .padding(.horizontal, TvScale.pt(14))
                        .padding(.vertical, TvScale.pt(18))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .appRemoteScroll()
                    .focusSection()
                    .padding(.horizontal, -TvScale.pt(14))
                }
            }

            Spacer()
        }
    }

    private var firstRunContent: some View {
        VStack(alignment: .leading, spacing: TvScale.pt(24)) {
            HStack(alignment: .center, spacing: TvScale.pt(56)) {
                VStack(alignment: .leading, spacing: TvScale.pt(26)) {
                    VStack(alignment: .leading, spacing: TvScale.pt(8)) {
                        Text(server.deviceName)
                            .font(.system(size: TvScale.pt(48), weight: .bold))
                            .foregroundColor(colors.onSurface)
                        Text(
                            server.connectedCount > 0
                                ? "Ready to cast. Send a video from your phone."
                                : "Ready to cast. Connect a phone to start."
                        )
                        .font(.system(size: TvScale.pt(20)))
                        .foregroundColor(colors.onSurfaceMuted)
                    }

                    VStack(alignment: .leading, spacing: TvScale.pt(18)) {
                        firstRunStep(number: 1, text: "Scan the code to get PlayBridge on your phone")
                        firstRunStep(number: 2, text: "Join the same Wi-Fi as this TV")
                        firstRunStep(number: 3, text: "Pick \(server.deviceName) in the app and enter the code shown here")
                    }

                    Text("Not listed in the app? Add it by address: \(addressLabel)")
                        .font(TvText.caption)
                        .foregroundColor(colors.onSurfaceMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(spacing: TvScale.pt(18)) {
                    QRCodeCardView(urlString: "https://playbridge.app", size: TvScale.pt(248))
                    Text("Scan with your phone's camera")
                        .font(.system(size: TvScale.pt(18)))
                        .foregroundColor(colors.onSurface)
                        .multilineTextAlignment(.center)
                }
                .frame(width: TvScale.pt(380))
                .padding(TvScale.pt(32))
                .background(colors.container)
                .clipShape(RoundedRectangle(cornerRadius: TvScale.pt(20)))
            }
            .frame(maxHeight: .infinity)

            bookmarkHint
        }
    }

    private var bookmarkHint: some View {
        HStack(spacing: TvScale.pt(14)) {
            Image(systemName: "bookmark")
                .font(.system(size: TvScale.pt(20), weight: .semibold))
            Text("Bookmark a video while it plays and it will be waiting here, with your place saved.")
                .font(.system(size: TvScale.pt(17)))
        }
        .foregroundColor(colors.onSurfaceMuted)
        .padding(.horizontal, TvScale.pt(22))
        .frame(maxWidth: .infinity, minHeight: TvScale.pt(64), alignment: .leading)
        .background(colors.containerLow)
        .clipShape(RoundedRectangle(cornerRadius: TvScale.pt(16)))
    }

    private func firstRunStep(number: Int, text: String) -> some View {
        HStack(spacing: TvScale.pt(18)) {
            Text("\(number)")
                .font(.system(size: TvScale.pt(20), weight: .semibold))
                .foregroundColor(colors.onAccentContainer)
                .frame(width: TvScale.pt(44), height: TvScale.pt(44))
                .background(colors.accentContainer)
                .clipShape(Circle())
            Text(text)
                .font(.system(size: TvScale.pt(21)))
                .foregroundColor(colors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct BookmarkTile: View {
    let item: PlaybackHistoryItem
    let index: Int
    let thumbnailURL: URL?
    let onSelect: () -> Void
    let onLongPress: () -> Void

    @Environment(\.tvColors) private var colors
    @Environment(\.isFocused) private var envFocused
    @State private var isRemoteFocused = false

    private var isFocused: Bool { envFocused || isRemoteFocused }

    private var tileWidth: CGFloat { TvScale.pt(280) }
    private var tileHeight: CGFloat { TvScale.pt(158) }

    private var progressFraction: Double? {
        guard let pos = item.resumePositionMs, let dur = item.durationMs, dur > 0 else { return nil }
        return min(1.0, max(0.0, Double(pos) / Double(dur)))
    }

    private var subtitleText: String? {
        HomeFormatting.bookmarkSubtitle(
            positionMs: item.positionMs,
            durationMs: item.durationMs,
            resumePositionMs: item.resumePositionMs
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: TvScale.pt(10)) {
            Button(action: onSelect) {
                ZStack(alignment: .bottomLeading) {
                    if let thumbnailURL, let uiImage = UIImage(contentsOfFile: thumbnailURL.path) {
                        Image(uiImage: uiImage)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: tileWidth, height: tileHeight)
                            .clipped()
                    } else {
                        let bg = index.isMultiple(of: 2) ? colors.tileBlue : colors.tilePlum
                        let fg = index.isMultiple(of: 2) ? colors.onTileBlue : colors.onTilePlum
                        bg
                            .overlay(
                                Image(systemName: "film")
                                    .font(.system(size: TvScale.pt(36), weight: .medium))
                                    .foregroundColor(fg.opacity(0.65))
                            )
                    }

                    if let fraction = progressFraction {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Rectangle().fill(colors.containerLow.opacity(0.9))
                                Rectangle()
                                    .fill(colors.accent)
                                    .frame(width: geo.size.width * fraction)
                            }
                        }
                        .frame(height: TvScale.pt(6))
                    }
                }
                .frame(width: tileWidth, height: tileHeight)
                .clipShape(RoundedRectangle(cornerRadius: TvRadius.tile))
                .overlay(
                    RoundedRectangle(cornerRadius: TvRadius.tile + TvScale.pt(4))
                        .stroke(isFocused ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                        .padding(-TvSize.focusRingGap)
                )
            }
            .buttonStyle(TvNoChromeButtonStyle())
            .focusEffectDisabled()
            .appRemoteAction(onSelect, focusChanged: { isRemoteFocused = $0 })
            .onLongPressGesture(minimumDuration: 0.45, perform: onLongPress)
            .onPlayPauseCommand(perform: onLongPress)

            VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                Text(item.title ?? "Untitled Video")
                    .font(.system(size: TvScale.pt(18), weight: isFocused ? .semibold : .medium))
                    .foregroundColor(colors.onSurface)
                    .lineLimit(1)
                if let subtitleText {
                    Text(subtitleText)
                        .font(TvText.small)
                        .foregroundColor(colors.onSurfaceMuted)
                        .lineLimit(1)
                }
            }
            .frame(width: tileWidth, alignment: .leading)
        }
        .scaleEffect(isFocused ? 1.03 : 1.0)
        .animation(.easeOut(duration: 0.14), value: isFocused)
    }
}

struct QRCodeCardView: View {
    let urlString: String
    let size: CGFloat

    var body: some View {
        Group {
            if let image = generateQR(from: urlString) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                Image(systemName: "qrcode")
                    .resizable()
                    .scaledToFit()
                    .foregroundColor(.black)
                    .padding(TvScale.pt(24))
            }
        }
        .padding(TvScale.pt(14))
        .frame(width: size, height: size)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: TvScale.pt(12)))
    }

    private func generateQR(from string: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
