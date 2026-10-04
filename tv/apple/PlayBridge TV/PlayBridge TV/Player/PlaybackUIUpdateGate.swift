/// Limits timeline publications without changing the decoder or presentation cadence.
struct PlaybackUIUpdateGate {
    private var lastUpdate: Double?
    private let interval: Double = 0.1

    mutating func shouldUpdate(at time: Double) -> Bool {
        guard time.isFinite else { return false }
        if let lastUpdate, time >= lastUpdate, time - lastUpdate < interval {
            return false
        }
        lastUpdate = time
        return true
    }

    mutating func reset() {
        lastUpdate = nil
    }
}
