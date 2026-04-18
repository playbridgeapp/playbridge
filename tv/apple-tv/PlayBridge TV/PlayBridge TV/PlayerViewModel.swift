import Combine
import Foundation
import Observation
import PlayBridgeProtocol

@Observable
class PlayerViewModel {
    var engine: (any PlaybackEngine)?
    var state: PlaybackState = .idle
    var position: TimeInterval = 0
    var duration: TimeInterval = 0
    var currentTitle: String?

    private var cancellables = Set<AnyCancellable>()

    func load(_ payload: PlayPayload) {
        // Stop current engine if exists
        engine?.stop()
        cancellables.removeAll()

        // Pick engine
        let engine: any PlaybackEngine
        if payload.playerMode == "internal_vlc" {
            engine = VLCPlayerEngine()
        } else {
            engine = AVPlayerEngine()
        }

        self.engine = engine
        self.currentTitle = payload.title

        // Bind state and position
        engine.state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newState in
                self?.state = newState
            }
            .store(in: &cancellables)

        engine.position
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newPos in
                self?.position = newPos
                self?.duration = self?.engine?.duration ?? 0
            }
            .store(in: &cancellables)

        // Load payload
        Task {
            do {
                try await engine.load(payload)
            } catch {
                print("Failed to load engine: \(error)")
                self.state = .error
            }
        }
    }

    func play() { engine?.play() }
    func pause() { engine?.pause() }
    func stop() {
        engine?.stop()
        engine = nil
        state = .idle
        position = 0
        duration = 0
        currentTitle = nil
        cancellables.removeAll()
    }

    func seek(to: TimeInterval) {
        Task {
            await engine?.seek(to: to)
        }
    }
}
