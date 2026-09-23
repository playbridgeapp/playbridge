import Foundation
import Combine
#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

private final class DLNAScanCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false
    func cancel() { lock.lock(); stopped = true; lock.unlock() }
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return stopped }
}

/// Bounded SSDP scans use Rust discovery; closing setup cancels the worker.
final class DLNABrowser: ObservableObject {
    @Published private(set) var devices: [ExternalReceiverDevice] = []
    @Published private(set) var isScanning = false
    @Published private(set) var error: String?
    enum Kind: String { case dlna = "Dlna", roku = "Roku", dial = "Dial"
        var mask: UInt32 { switch self { case .dlna: return 2; case .roku: return 4; case .dial: return 8 } }
        var label: String { switch self { case .dlna: return "DLNA"; case .roku: return "Roku"; case .dial: return "DIAL" } }
    }
    private let kind: Kind
    init(kind: Kind = .dlna) { self.kind = kind }
    private let worker = DispatchQueue(label: "com.playbridge.dlna-discovery")
    private var scan: DLNAScanCancellation?
    private var generation = UUID()

    static func device(from receiver: [String: Any]) -> ExternalReceiverDevice? {
        guard let protocolValue = receiver["protocol"] as? String, let kind = Kind(rawValue: protocolValue),
              let id = receiver["id"] as? String,
              let location = receiver["location"] as? String,
              let url = URL(string: location), ["http", "https"].contains(url.scheme),
              let host = url.host, url.user == nil, url.password == nil else { return nil }
        let port = kind == .roku ? ((receiver["port"] as? NSNumber)?.intValue ?? url.port ?? 8060) : (url.port ?? (url.scheme == "https" ? 443 : 80))
        guard (1...65535).contains(port) else { return nil }
        return .init(id: id, name: receiver["name"] as? String ?? host,
            addresses: receiver["addresses"] as? [String] ?? [host], port: UInt16(port),
            model: kind == .dlna ? "Media renderer" : kind.label, location: location, receiverProtocol: kind.rawValue.lowercased())
    }

    static func manualRoku(_ input: String) -> ExternalReceiverDevice? {
        let text = input.contains("://") ? input : "http://" + input
        guard let url = URL(string: text), url.scheme == "http", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.path.isEmpty || url.path == "/" else { return nil }
        let port = url.port ?? 8060
        guard (1...65535).contains(port) else { return nil }
        return .init(id: host + ":" + String(port), name: host, addresses: [host], port: UInt16(port),
            model: "Roku", receiverProtocol: "roku")
    }

    func start() {
        guard !isScanning else { return }
        generation = UUID()
        let current = generation
        isScanning = true
        error = nil
#if DEBUG
        print("[SSDPDiscovery] Starting Rust \(kind.label) SSDP scan (5 seconds). Physical iPhone discovery requires multicast entitlement and Local Network permission.")
#endif
        let cancellation = DLNAScanCancellation()
        scan = cancellation
        worker.async { [weak self] in
            guard let self, !cancellation.isCancelled else { return }
#if canImport(PlayBridgeCastCore)
            guard let scanner = pb_discovery_start(kind.mask, 5_000) else {
#if DEBUG
                print("[SSDPDiscovery] Rust discovery could not start.")
#endif
                DispatchQueue.main.async { [weak self] in self?.finish(current, failed: true) }
                return
            }
            defer { pb_discovery_cancel(scanner); pb_discovery_free(scanner) }
            let deadline = Date().addingTimeInterval(7)
            while Date() < deadline {
                // The flag is scoped to this task; state updates are generation guarded.
                if cancellation.isCancelled { break }
                guard let pointer = pb_discovery_next_json(scanner, 100) else { continue }
                let data = Data(String(cString: pointer).utf8)
                pb_string_free(pointer)
                guard let event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
                if let receiver = event["receiver"] as? [String: Any], let device = Self.device(from: receiver) {
                    DispatchQueue.main.async { [weak self] in
                        guard let self, generation == current else { return }
                        devices.removeAll { $0.identity == device.identity }
                        devices.append(device)
                        devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                    }
                }
                if event["event"] as? String == "error" {
#if DEBUG
                    // Debug console only: preserve the actual native socket error.
                    print("[SSDPDiscovery] Rust error: \(event["message"] as? String ?? "No error detail")")
#endif
                    DispatchQueue.main.async { [weak self] in self?.finish(current, failed: true) }
                    break
                }
                if event["event"] as? String == "finished" { break }
            }
            DispatchQueue.main.async { [weak self] in self?.finish(current, failed: false) }
#else
            DispatchQueue.main.async { [weak self] in self?.finish(current, failed: true) }
#endif
        }
    }

    deinit { scan?.cancel() }

    private func finish(_ current: UUID, failed: Bool) {
        guard generation == current else { return }
        isScanning = false
        if failed { error = "\(kind.label) search failed. Check Local Network permission. On iPhone, this build also needs Apple’s Multicast Networking entitlement. Manual connections are available for DLNA and Roku." }
    }

    func stop() {
        generation = UUID()
        scan?.cancel()
        scan = nil
        isScanning = false
        devices = []
    }
}
