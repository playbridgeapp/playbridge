import SwiftUI
import MediaPlayer

struct MusicPresentationView: View {
    let payload: Playbridge_PlayPayload
    var onArtwork: (UIImage?) -> Void = { _ in }
    @State private var image: UIImage?
    @State private var download: MediaImageDownload?
    @State private var visible = false
    private var metadata: MusicPresentationMetadata { payload.musicMetadata }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LinearGradient(colors: [Color(red: 0.10, green: 0.08, blue: 0.18), .black],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .clipped().blur(radius: 42).opacity(0.25)
                }
                HStack(spacing: 64) {
                    ZStack {
                        LinearGradient(colors: [.indigo, .black], startPoint: .topLeading, endPoint: .bottomTrailing)
                        if let image {
                            Image(uiImage: image).resizable().scaledToFill()
                        } else {
                            Image(systemName: "music.note").font(.system(size: 100)).foregroundStyle(.white)
                        }
                    }
                    .frame(width: min(geometry.size.width * 0.34, geometry.size.height * 0.56),
                           height: min(geometry.size.width * 0.34, geometry.size.height * 0.56))
                    .clipShape(RoundedRectangle(cornerRadius: 28))
                    .overlay(RoundedRectangle(cornerRadius: 28).stroke(.white.opacity(0.2)))
                    .shadow(color: .black.opacity(0.5), radius: 30, y: 12)
                    VStack(alignment: .leading, spacing: 20) {
                        Text("NOW PLAYING").font(.headline).tracking(3).foregroundStyle(.white.opacity(0.7))
                        Text(metadata.title).font(.system(size: 48, weight: .bold)).lineLimit(3)
                        if !metadata.artist.isEmpty { Text(metadata.artist).font(.title2).lineLimit(2) }
                        if !metadata.album.isEmpty { Text(metadata.album).font(.headline).foregroundStyle(.white.opacity(0.65)).lineLimit(2) }
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: 600, alignment: .leading)
                }
                .padding(.horizontal, 72)
                .padding(.bottom, 100)
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .onAppear { visible = true; loadArtwork(at: 0) }
        .onDisappear { visible = false; download?.cancel(); download = nil }
    }

    private func loadArtwork(at index: Int) {
        guard visible, metadata.artworkURLs.indices.contains(index), let source = payload.validURL else { return }
        let loader = MediaImageDownload(url: metadata.artworkURLs[index], headerOrigin: source,
                                        headers: payload.headers, detectedBy: payload.detectedBy,
                                        allowedPrivateOrigins: payload.allowedPrivateOrigins)
        download = loader
        loader.start { result in
            guard visible else { return }
            if let result { image = result; onArtwork(result) }
            else { loadArtwork(at: index + 1) }
        }
    }
}

/// Both engines place this below transport/subtitle chrome and above the video layer.
final class MusicPresentationHost {
    private var hosting: UIHostingController<MusicPresentationView>?
    private var payload: Playbridge_PlayPayload?
    private let identity = UUID()
    private static var nowPlayingOwner: UUID?

    func update(_ payload: Playbridge_PlayPayload?, in controller: UIViewController, container: UIView,
                behindSubviews: Bool = false) {
        guard self.payload != payload else { return }
        remove()
        self.payload = payload
        guard let payload else { return }
        Self.nowPlayingOwner = identity
        let metadata = payload.musicMetadata
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: metadata.title,
            MPMediaItemPropertyArtist: metadata.artist,
            MPMediaItemPropertyAlbumTitle: metadata.album,
        ]
        let presentation = MusicPresentationView(payload: payload, onArtwork: { [weak self] image in
            guard let self, Self.nowPlayingOwner == self.identity, self.payload == payload, let image else { return }
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        })
        let host = UIHostingController(rootView: presentation)
        host.view.frame = container.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.backgroundColor = .black
        host.view.isUserInteractionEnabled = false
        controller.addChild(host)
        if behindSubviews { container.insertSubview(host.view, at: 0) }
        else { container.addSubview(host.view) }
        host.didMove(toParent: controller)
        hosting = host
    }

    func remove() {
        hosting?.willMove(toParent: nil)
        hosting?.view.removeFromSuperview()
        hosting?.removeFromParent()
        hosting = nil
        payload = nil
        if Self.nowPlayingOwner == identity {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            Self.nowPlayingOwner = nil
        }
    }
}
