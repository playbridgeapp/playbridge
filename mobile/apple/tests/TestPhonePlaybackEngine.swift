import Foundation

/// Deterministic mpv callbacks for standalone host-model checks, not native decoding.
@MainActor final class TestAlternativeEngine: PhoneAlternativePlaybackEngine {
    var onState: ((PhonePlaybackState) -> Void)?
    var onEnd: (() -> Void)?
    var onFailure: ((Int32) -> Void)?
    var networkIssue: PhonePlaybackNetworkIssue?
    var loads: [(URL, [String: String], Double, Bool)] = []
    var plays = 0
    var pauses = 0
    var closes = 0
    var seeks: [Double] = []
    var selectedSubtitle: Int?
    var selectedAudio: Int?
    var configurations: [PhonePlayerOptions] = []
    func configure(_ options: PhonePlayerOptions) { configurations.append(options) }
    func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool) {
        loads.append((url, headers, resume, autoplay))
        onState?(PhonePlaybackState(position: resume, paused: !autoplay))
    }
    func play() { plays += 1 }
    func pause() { pauses += 1 }
    func seek(to seconds: Double) { seeks.append(seconds) }
    func selectAudio(_ id: Int?) { selectedAudio = id }
    func selectSubtitle(_ id: Int?) { selectedSubtitle = id }
    func close() { closes += 1 }
}
