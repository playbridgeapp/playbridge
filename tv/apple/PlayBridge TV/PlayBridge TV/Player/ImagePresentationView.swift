import SwiftUI
import Combine

struct ImagePresentationView: View {
    let payload: Playbridge_PlayPayload
    let mediaURL: URL
    let engine: String
    let onBroadcast: ([String: Any]) -> Void
    let onNavigate: (Int) -> Void
    let onExit: () -> Void
    let prompting: Bool
    @State private var clock: ImagePresentationTimer
    @State private var transform = ImageTransform()
    @State private var image: UIImage?
    @State private var failed = false
    @State private var visible = false
    @State private var download: MediaImageDownload?
    private let ticker = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()
    private var now: Double { ProcessInfo.processInfo.systemUptime }

    init(payload: Playbridge_PlayPayload, mediaURL: URL, engine: String, prompting: Bool,
         onBroadcast: @escaping ([String: Any]) -> Void,
         onNavigate: @escaping (Int) -> Void, onExit: @escaping () -> Void) {
        self.payload = payload
        self.mediaURL = mediaURL
        self.engine = engine
        self.prompting = prompting
        self.onBroadcast = onBroadcast
        self.onNavigate = onNavigate
        self.onExit = onExit
        _clock = State(initialValue: ImagePresentationTimer(durationMs: payload.hasDisplayDurationMs ? payload.displayDurationMs : nil))
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black
                if let image {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .scaleEffect(transform.scale)
                        .rotationEffect(.degrees(transform.rotation))
                        .offset(x: transform.x, y: transform.y)
                        .onAppear { clock.shown(now: now) }
                } else if failed {
                    VStack(spacing: 20) {
                        Image(systemName: "photo").font(.system(size: 72))
                        Text("This image couldn’t be loaded").font(.title2)
                        Text(payload.titleOrNil ?? "").font(.headline)
                    }
                    .foregroundStyle(.white.opacity(0.8))
                    .onAppear { clock.shown(now: now) }
                } else {
                    ProgressView().tint(.white)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.imagePointer)) { note in
                guard visible, !prompting, let pointer = note.object as? ImagePointer else { return }
                transform.apply(pointer, width: geometry.size.width, height: geometry.size.height)
            }
        }
        .ignoresSafeArea()
        .focusable()
        .onTapGesture { control("toggle") }
        .onPlayPauseCommand { control("toggle") }
        .onMoveCommand { direction in
            guard !prompting else { return }
            if direction == .left { onNavigate(-1) }
            if direction == .right { onNavigate(1) }
        }
        .onExitCommand { onExit() }
        .onAppear {
            visible = true
            clock.start(now: now)
            snapshot()
            // `mediaURL` is the proxy's URL for a page-controlled image.
            let loader = MediaImageDownload(url: mediaURL, headerOrigin: mediaURL, headers: payload.headers,
                                            detectedBy: payload.detectedBy,
                                            allowedPrivateOrigins: payload.allowedPrivateOrigins,
                                            viaProxy: payload.isPageControlled)
            download = loader
            loader.start { result in
                guard visible else { return }
                image = result
                failed = result == nil
            }
        }
        .onDisappear {
            visible = false
            download?.cancel()
            download = nil
        }
        .onReceive(ticker) { _ in
            guard visible else { return }
            let advance = clock.tick(now: now)
            publishStatus()
            if advance { onNavigate(1) }
        }
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.controlCommand)) { note in
            guard visible, let command = note.userInfo?["command"] as? String else { return }
            control(command)
        }
        .onReceive(NotificationCenter.default.publisher(for: WebSocketServer.resyncRequest)) { _ in
            if visible { snapshot() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .playBridgeStillWatchingPause)) { _ in
            setPlaying(false)
        }
        .onReceive(NotificationCenter.default.publisher(for: .playBridgeStillWatchingResume)) { _ in
            setPlaying(true)
        }
    }

    private func snapshot() {
        publishStatus()
        onBroadcast(["type": "tracks", "audio": [], "subtitle": []])
        onBroadcast(TimelineReport.frame(chapters: [], segments: []))
        onBroadcast(PlayerPlaybackReport.imageSettings(engine: engine))
    }

    private func setPlaying(_ playing: Bool) {
        let advance = clock.setPlaying(playing, now: now)
        publishStatus()
        if advance { onNavigate(1) }
    }

    private func publishStatus() {
        onBroadcast(clock.status)
        NotificationCenter.default.post(name: .playBridgePlaybackActivity, object: nil,
                                        userInfo: ["isPlaying": clock.isPlaying])
    }

    private func control(_ command: String) {
        if prompting { return }
        if let paused = PlaybackPauseCommand.targetPaused(for: command, isPlaying: clock.isPlaying) {
            setPlaying(!paused)
        } else if command == "stop" { onExit() }
    }
}
