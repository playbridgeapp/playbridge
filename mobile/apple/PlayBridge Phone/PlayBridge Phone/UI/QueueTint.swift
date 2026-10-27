import SwiftUI
import UIKit
import Photos
import AVFoundation
import QuickLookThumbnailing

/// Colours for the Up next sheet taken from the current item's artwork, like a music player's
/// queue: a dark gradient behind the list and a light card for what's playing. The hue comes
/// from the art; brightness is fixed so any artwork gives readable text.
struct QueueTint: Equatable {
    let top: Color
    let middle: Color
    let bottom: Color
    let cardFill: Color
    let cardBase: Color
    let onCard: Color

    init(hue: CGFloat, saturation raw: CGFloat) {
        // Muted artwork still gets a clear colour; truly grey art stays grey (its hue means nothing).
        let saturation = raw < 0.06 ? raw : max(raw, 0.45)
        func tone(_ s: CGFloat, _ b: CGFloat) -> Color {
            Color(hue: Double(hue), saturation: Double(min(1, max(0, s))), brightness: Double(b))
        }
        top = tone(saturation * 0.6, 0.10)
        middle = tone(saturation * 0.65, 0.24)
        bottom = tone(saturation * 0.6, 0.08)
        cardFill = tone(max(saturation, 0.45) * 0.4, 0.97)
        cardBase = tone(saturation * 0.3, 0.70)
        onCard = tone(saturation * 0.6, 0.18)
    }

    /// The accent's tint, for items with no artwork.
    static let fallback = QueueTint(color: UIColor(Theme.primary))

    init(color: UIColor) {
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        self.init(hue: h, saturation: s)
    }

    /// The artwork's most colourful tone: an average of a tiny copy, weighted toward saturated
    /// pixels so black borders and white backgrounds don't wash it out.
    static func from(_ image: UIImage) -> QueueTint? {
        guard let cgImage = image.cgImage else { return nil }
        let side = 24
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        guard let context = CGContext(data: &pixels, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.interpolationQuality = .low
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: side, height: side))
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, total: CGFloat = 0
        for i in stride(from: 0, to: pixels.count, by: 4) {
            let pr = CGFloat(pixels[i]) / 255, pg = CGFloat(pixels[i + 1]) / 255, pb = CGFloat(pixels[i + 2]) / 255
            let high = max(pr, pg, pb), low = min(pr, pg, pb)
            let weight = (high - low) * high + 0.02
            r += pr * weight; g += pg * weight; b += pb * weight; total += weight
        }
        guard total > 0 else { return nil }
        return QueueTint(color: UIColor(red: r / total, green: g / total, blue: b / total, alpha: 1))
    }
}

/// The current phone-list item's tint, shared by Up next, the Remote and the mini player.
/// Each view asks for its item; the colour is worked out once per item and cached.
@MainActor final class QueueTintStore: ObservableObject {
    static let shared = QueueTintStore()
    @Published private(set) var tint = QueueTint.fallback
    private var cache: [String: QueueTint] = [:]
    private var requested: String?

    func load(_ media: PhoneMedia, url: URL?) async {
        requested = media.id
        if let cached = cache[media.id] { apply(cached); return }
        let image = await Self.artwork(media, url: url)
        let tint = image.flatMap(QueueTint.from) ?? .fallback
        if !Task.isCancelled { cache[media.id] = tint }
        if requested == media.id { apply(tint) }
    }

    private func apply(_ value: QueueTint) {
        guard value != tint else { return }
        withAnimation(.easeInOut(duration: 0.45)) { tint = value }
    }

    /// A small copy of the item's picture: Photos asset, song artwork, photo, or video frame.
    private static func artwork(_ media: PhoneMedia, url: URL?) async -> UIImage? {
        let side: CGFloat = 160
        if let id = media.assetIdentifier, let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject {
            let options = PHImageRequestOptions()
            options.deliveryMode = .fastFormat; options.isNetworkAccessAllowed = false; options.isSynchronous = false
            return await withCheckedContinuation { continuation in
                PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: side, height: side),
                                                      contentMode: .aspectFill, options: options) { image, _ in
                    continuation.resume(returning: image)
                }
            }
        }
        guard let url else { return nil }
        switch media.kind {
        case .image:
            return await PhoneMediaThumbnail.image(at: url, maxPixel: Int(side))
        case .audio:
            let metadata = (try? await AVURLAsset(url: url).load(.commonMetadata)) ?? []
            for entry in AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierArtwork) {
                if let data = try? await entry.load(.dataValue), let image = UIImage(data: data) { return image }
            }
            return nil
        case .video:
            let request = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: side, height: side), scale: 1, representationTypes: .thumbnail)
            return try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request).uiImage
        }
    }
}

extension PhoneQueueCoordinator {
    /// The item whose artwork colours the Remote and mini player: the list's current one while a
    /// list is active on a connected receiver. Not tied to the receiver's reported item, which
    /// lags or blanks while it changes songs and would flash the theme colours.
    @MainActor func tintedItem(on vm: ConnectionViewModel) -> Item? {
        guard active, vm.isConnected else { return nil }
        return current
    }
}

/// Fills for the Remote: the theme's, or tones of the artwork colour over a phone list.
struct RemotePalette {
    let surfaceLow: Color
    let surface: Color
    let surfaceHigh: Color
    let selected: Color
    let accent: Color
    let onAccent: Color

    static let theme = RemotePalette(surfaceLow: Theme.surfaceContainerLow, surface: Theme.surfaceContainer,
                                     surfaceHigh: Theme.surfaceContainerHigh, selected: Theme.secondaryContainer,
                                     accent: Theme.primary, onAccent: Theme.onPrimary)

    init(surfaceLow: Color, surface: Color, surfaceHigh: Color, selected: Color, accent: Color, onAccent: Color) {
        self.surfaceLow = surfaceLow; self.surface = surface; self.surfaceHigh = surfaceHigh
        self.selected = selected; self.accent = accent; self.onAccent = onAccent
    }

    init(_ tint: QueueTint) {
        surfaceLow = Color.white.opacity(0.06); surface = Color.white.opacity(0.09)
        surfaceHigh = Color.white.opacity(0.13); selected = Color.white.opacity(0.18)
        accent = tint.cardFill; onAccent = tint.onCard
    }
}
