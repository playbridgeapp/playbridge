import Foundation
import Network

/// Discovers PlayBridge receivers on the LAN via Bonjour, mirroring `NsdHelper` on Android.
/// Uses `NetService` because it resolves the IP, port, and TXT record in one shot (NWBrowser
/// needs a separate connection to resolve an address).
final class BonjourBrowser: NSObject, ObservableObject {
    @Published private(set) var devices: [DiscoveredDevice] = []
    @Published private(set) var isScanning = false

    private var browser: NetServiceBrowser?
    /// Services currently resolving — held strongly so they aren't deallocated mid-resolve.
    private var resolving: Set<NetService> = []

    func start() {
        guard browser == nil else { return }
        devices = []
        resolving = []
        let b = NetServiceBrowser()
        b.delegate = self
        b.searchForServices(ofType: "\(ProtocolConstants.bonjourServiceType).", inDomain: "local.")
        browser = b
        isScanning = true
    }

    func stop() {
        browser?.stop()
        browser = nil
        resolving.forEach { $0.stop() }
        resolving = []
        isScanning = false
    }

    private func upsert(_ device: DiscoveredDevice) {
        devices.removeAll { $0.ip == device.ip }
        devices.append(device)
        devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
}

extension BonjourBrowser: NetServiceBrowserDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        resolving.insert(service)
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        devices.removeAll { $0.name == service.name }
    }
}

extension BonjourBrowser: NetServiceDelegate {
    func netServiceDidResolveAddress(_ service: NetService) {
        defer { resolving.remove(service) }
        guard let ip = BonjourBrowser.firstIPv4(from: service.addresses ?? []) else { return }

        var txt: [String: String] = [:]
        if let txtData = service.txtRecordData() {
            for (k, v) in NetService.dictionary(fromTXTRecord: txtData) {
                txt[k] = String(data: v, encoding: .utf8)
            }
        }

        let device = BonjourBrowser.parseDevice(
            name: service.name,
            resolvedIP: ip,
            port: service.port,
            txt: txt
        )
        upsert(device)
    }

    func netService(_ service: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving.remove(service)
    }

    /// Pure parse of a resolved service into a `DiscoveredDevice` (mirrors `NsdHelper.parseDevice`).
    static func parseDevice(name: String, resolvedIP: String, port: Int, txt: [String: String]) -> DiscoveredDevice {
        let uuid = txt[ProtocolConstants.TXTKey.uuid] ?? ""
        let customIP = txt[ProtocolConstants.TXTKey.customIP]
        let ip = (customIP != nil && !customIP!.isEmpty && customIP != "auto") ? customIP! : resolvedIP
        let wssPort = txt[ProtocolConstants.TXTKey.wssPort].flatMap { Int($0) }
        return DiscoveredDevice(ip: ip, port: port, name: name, uuid: uuid, wssPort: wssPort)
    }

    /// First IPv4 dotted-quad from a list of `sockaddr` blobs.
    static func firstIPv4(from addresses: [Data]) -> String? {
        for data in addresses {
            guard data.count >= MemoryLayout<sockaddr>.size else { continue }
            var storage = sockaddr_storage()
            _ = withUnsafeMutableBytes(of: &storage) { dst in
                data.copyBytes(to: dst, count: min(data.count, MemoryLayout<sockaddr_storage>.size))
            }
            guard Int32(storage.ss_family) == AF_INET else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let res = withUnsafePointer(to: &storage) { ptr -> Int32 in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    getnameinfo(sa, socklen_t(data.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
                }
            }
            if res == 0 { return String(cString: host) }
        }
        return nil
    }
}
