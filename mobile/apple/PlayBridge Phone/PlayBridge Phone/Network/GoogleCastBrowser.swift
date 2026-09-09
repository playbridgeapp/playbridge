import Foundation
import Combine
import Darwin

struct ExternalReceiverDevice: Identifiable, Codable, Equatable {
    let id: String
    var name: String
    let addresses: [String]
    let port: UInt16
    let model: String
    var location: String? = nil
    var receiverProtocol: String? = nil
    var protocolID: String { receiverProtocol ?? (location != nil ? "dlna" : "google_cast") }
    var isDLNA: Bool { protocolID == "dlna" }
    var protocolName: String {
        switch protocolID { case "dlna": return "DLNA"; case "roku": return "Roku"; case "dial": return "DIAL"; default: return "Google Cast" }
    }
    var identity: String { protocolID + ":" + id }

    static func parse(serviceName: String, addresses: [String], port: Int, txt: [String: String]) -> ExternalReceiverDevice? {
        guard !addresses.isEmpty, (1...65535).contains(port) else { return nil }
        let identifier = txt["id"].flatMap { $0.isEmpty ? nil : $0 } ?? serviceName
        return .init(id: identifier, name: txt["fn"].flatMap { $0.isEmpty ? nil : $0 } ?? serviceName,
                     addresses: Array(Set(addresses)).sorted(), port: UInt16(port), model: txt["md"] ?? "Google Cast")
    }
}

/// Use OS Bonjour for the declared service type on iOS; raw multicast discovery
/// would require an additional entitlement. Resolved endpoints feed Rust CastV2.
final class GoogleCastBrowser: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    @Published private(set) var devices: [ExternalReceiverDevice] = []
    @Published private(set) var error: String?
    @Published private(set) var isScanning = false
    private var browser: NetServiceBrowser?
    private var owners = 0
    private var services: [String: NetService] = [:]
    private var resolved: [String: ExternalReceiverDevice] = [:]

    func start() {
        owners += 1
        guard browser == nil else { return }
        error = nil
        let browser = NetServiceBrowser()
        self.browser = browser
        browser.delegate = self
        browser.searchForServices(ofType: "_googlecast._tcp.", inDomain: "local.")
        isScanning = true
    }

    func stop() {
        owners = max(0, owners - 1)
        guard owners == 0 else { return }
        browser?.stop()
        browser = nil
        services.values.forEach { $0.stop(); $0.delegate = nil }
        services.removeAll()
        resolved.removeAll()
        devices = []
        isScanning = false
    }

    private func key(_ service: NetService) -> String { service.domain + service.type + service.name }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard self.browser === browser else { return }
        let key = key(service)
        services[key]?.stop()
        services[key] = service
        service.delegate = self
        service.resolve(withTimeout: 8)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        guard self.browser === browser else { return }
        services.removeValue(forKey: key(service))?.stop()
        resolved.removeValue(forKey: key(service))
        publish()
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        guard self.browser === browser else { return }
        error = "Couldn’t discover Google Cast devices. Check Wi-Fi and Local Network access in Settings."
        isScanning = false
    }

    func netServiceDidResolveAddress(_ service: NetService) {
        guard services[key(service)] === service else { return }
        let addresses = (service.addresses ?? []).compactMap { data -> String? in
            guard data.count >= MemoryLayout<sockaddr>.size else { return nil }
            var storage = sockaddr_storage()
            _ = withUnsafeMutableBytes(of: &storage) { data.copyBytes(to: $0, count: min(data.count, $0.count)) }
            guard [AF_INET, AF_INET6].contains(Int32(storage.ss_family)) else { return nil }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = withUnsafePointer(to: &storage) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    getnameinfo($0, socklen_t(min(data.count, MemoryLayout<sockaddr_storage>.size)), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                }
            }
            return result == 0 ? String(cString: host) : nil
        }
        let txt = service.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        resolved[key(service)] = ExternalReceiverDevice.parse(serviceName: service.name, addresses: addresses, port: service.port,
            txt: txt.compactMapValues { String(data: $0, encoding: .utf8) })
        publish()
    }

    private func publish() {
        var unique: [String: ExternalReceiverDevice] = [:]
        for device in resolved.values {
            if let old = unique[device.id] {
                unique[device.id] = .init(id: device.id, name: device.name, addresses: Array(Set(old.addresses + device.addresses)).sorted(), port: device.port, model: device.model)
            } else { unique[device.id] = device }
        }
        devices = unique.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
}
