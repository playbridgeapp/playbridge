import Foundation

/// Main-queue owner of debounced probes, including results from an earlier network path.
final class SavedReceiverReachability {
    typealias Cancel = () -> Void
    typealias Probe = (PairedDevice, @escaping (Bool) -> Void) -> Cancel
    typealias Schedule = (TimeInterval, @escaping () -> Void) -> Cancel

    private let keyFor: (PairedDevice) -> String
    private let probe: Probe
    private let publish: ([String: Bool]) -> Void
    private let schedule: Schedule
    private var generation = UUID()
    private var pending: Cancel?
    private var checks: [Cancel] = []
    private(set) var results: [String: Bool] = [:]

    init(keyFor: @escaping (PairedDevice) -> String, probe: @escaping Probe,
         publish: @escaping ([String: Bool]) -> Void,
         schedule: @escaping Schedule = { delay, action in
             let work = DispatchWorkItem(block: action)
             DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
             return { work.cancel() }
         }) {
        self.keyFor = keyFor; self.probe = probe; self.publish = publish; self.schedule = schedule
    }

    func recheck(_ devices: [PairedDevice]) {
        cancelChecks()
        let keys = Set(devices.map(keyFor))
        let retained = results.filter { keys.contains($0.key) }
        if retained != results { results = retained; publish(results) }
        let ticket = generation
        pending = schedule(0.35) { [weak self] in
            guard let self, self.generation == ticket else { return }
            self.pending = nil
            for device in devices {
                let key = self.keyFor(device)
                let cancel = self.probe(device) { [weak self] reachable in
                    guard let self, self.generation == ticket else { return }
                    self.results[key] = reachable
                    self.publish(self.results)
                }
                guard self.generation == ticket else { cancel(); return }
                self.checks.append(cancel)
            }
        }
    }

    func invalidate() {
        cancelChecks()
        results = [:]
        publish(results)
    }

    private func cancelChecks() {
        generation = UUID()
        pending?(); pending = nil
        let previous = checks
        checks = []
        previous.forEach { $0() }
    }

    deinit { cancelChecks() }
}
