import Foundation
import Combine
import Darwin

struct AtvBonjourServiceID: Hashable {
    let domain: String
    let type: String
    let name: String
}

enum AtvBonjourEvent {
    case found(AtvBonjourServiceID, UUID)
    case resolved(AtvBonjourServiceID, UUID, AtvDiscoveredDevice)
    case removed(AtvBonjourServiceID, UUID)
    case failed
}

@MainActor protocol AtvBonjourDriver: AnyObject {
    func start(deliver: @escaping @MainActor (AtvBonjourEvent) -> Void)
    func stop()
}

/// Foreground native Bonjour only. Construction does no network work. The later coordinator
/// owns start/stop; an empty scan does not prove Local Network permission was denied.
@MainActor final class AtvBonjourBrowser: ObservableObject {
    static let serviceType = "_androidtvremote2._tcp."
    @Published private(set) var devices: [AtvDiscoveredDevice] = []
    @Published private(set) var isScanning = false
    @Published private(set) var failed = false
    private let makeDriver: @MainActor () -> AtvBonjourDriver
    private var driver: AtvBonjourDriver?
    private var generation = UUID()
    private var instances: [AtvBonjourServiceID: UUID] = [:]
    private var resolved: [AtvBonjourServiceID: AtvDiscoveredDevice] = [:]

    init(makeDriver: @escaping @MainActor () -> AtvBonjourDriver = { AtvNetServiceDriver() }) {
        self.makeDriver = makeDriver
    }
    func start() {
        guard driver == nil else { return }
        generation = UUID()
        let ticket = generation
        instances = [:]; resolved = [:]; devices = []; failed = false; isScanning = true
        let driver = makeDriver()
        self.driver = driver
        driver.start { [weak self] event in
            guard let self, self.generation == ticket, self.driver != nil else { return }
            self.receive(event)
        }
    }
    func stop() {
        generation = UUID()
        let previous = driver
        driver = nil
        previous?.stop()
        instances = [:]; resolved = [:]; devices = []; isScanning = false
    }
    private func receive(_ event: AtvBonjourEvent) {
        switch event {
        case .found(let id, let token):
            guard instances[id] != nil || instances.count < 256 else { return }
            instances[id] = token
            resolved[id] = nil
        case .resolved(let id, let token, let device):
            guard instances[id] == token, Self.valid(device) else { return }
            resolved[id] = device
        case .removed(let id, let token):
            guard instances[id] == token else { return }
            instances[id] = nil; resolved[id] = nil
        case .failed:
            stop(); failed = true
        }
        // Stable order also makes Android's first-address/first-device matching deterministic.
        devices = resolved.sorted {
            ($0.key.name, $0.key.domain, $0.key.type) < ($1.key.name, $1.key.domain, $1.key.type)
        }.map(\.value)
    }
    private static func valid(_ device: AtvDiscoveredDevice) -> Bool {
        !device.host.isEmpty && device.host.utf8.count <= 1024 && device.name.utf8.count <= 1024 && device.port > 0 &&
        !device.addresses.isEmpty && device.addresses.count <= 32 && device.addresses.allSatisfy { !$0.isEmpty && $0.utf8.count <= 1024 } &&
        device.txt.count <= 64 && device.txt.allSatisfy { $0.count == 2 && $0.allSatisfy { $0.utf8.count <= 4096 } }
    }
}

/// NetService delegates run on the main run loop used by search/resolve. Both browser identity
/// and resolver instance identity are checked before producing an event, including removal.
@MainActor final class AtvNetServiceDriver: NSObject, AtvBonjourDriver, @preconcurrency NetServiceBrowserDelegate, @preconcurrency NetServiceDelegate {
    private struct Service {
        let resolver: NetService
        let token: UUID
    }
    private var browser: NetServiceBrowser?
    private var services: [AtvBonjourServiceID: Service] = [:]
    private var deliver: (@MainActor (AtvBonjourEvent) -> Void)?

    func start(deliver: @escaping @MainActor (AtvBonjourEvent) -> Void) {
        stop()
        self.deliver = deliver
        let browser = NetServiceBrowser()
        self.browser = browser
        browser.delegate = self
        browser.searchForServices(ofType: AtvBonjourBrowser.serviceType, inDomain: "local.")
    }
    func stop() {
        deliver = nil
        browser?.delegate = nil; browser?.stop(); browser = nil
        for service in services.values { service.resolver.delegate = nil; service.resolver.stop() }
        services = [:]
    }
    deinit {
        browser?.delegate = nil; browser?.stop()
        for service in services.values { service.resolver.delegate = nil; service.resolver.stop() }
    }
    private func id(_ service: NetService) -> AtvBonjourServiceID {
        .init(domain: service.domain, type: service.type, name: service.name)
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard self.browser === browser else { return }
        let key = id(service)
        guard services[key] != nil || services.count < 256 else { return }
        if let old = services[key] { old.resolver.delegate = nil; old.resolver.stop() }
        let token = UUID()
        services[key] = Service(resolver: service, token: token)
        deliver?(.found(key, token))
        service.delegate = self
        service.resolve(withTimeout: 5)
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        guard self.browser === browser, let current = services[id(service)], current.resolver === service else { return }
        services[id(service)] = nil
        service.delegate = nil; service.stop()
        deliver?(.removed(id(service), current.token))
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        guard self.browser === browser else { return }
        deliver?(.failed)
    }
    func netServiceDidResolveAddress(_ service: NetService) {
        guard let current = services[id(service)], current.resolver === service else { return }
        let txt = service.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        guard txt.count <= 64 else { return }
        let pairs = txt.sorted { $0.key < $1.key }.compactMap { key, data -> [String]? in
            guard data.count <= 4096, let value = String(data: data, encoding: .utf8) else { return nil }
            return [key, value]
        }
        guard let device = Self.parse(name: service.name, port: service.port,
                                      addresses: service.addresses ?? [], txt: pairs) else { return }
        deliver?(.resolved(id(service), current.token, device))
    }
    func netService(_ service: NetService, didNotResolve errorDict: [String: NSNumber]) {
        guard let current = services[id(service)], current.resolver === service else { return }
        services[id(service)] = nil
        service.delegate = nil; service.stop()
        deliver?(.removed(id(service), current.token))
    }

    /// Numeric IPv4/IPv6 endpoints, retaining IPv6 scope IDs. Never performs DNS or opens a socket.
    static func parse(name: String, port: Int, addresses: [Data], txt: [[String]]) -> AtvDiscoveredDevice? {
        guard (1...65535).contains(port), addresses.count <= 32 else { return nil }
        var hosts: [String] = []
        for data in addresses {
            guard data.count >= MemoryLayout<sockaddr>.size, data.count <= MemoryLayout<sockaddr_storage>.size else { continue }
            var storage = sockaddr_storage()
            _ = withUnsafeMutableBytes(of: &storage) { data.copyBytes(to: $0, count: data.count) }
            let family = Int32(storage.ss_family)
            let expected: Int
            switch family {
            case AF_INET: expected = MemoryLayout<sockaddr_in>.size
            case AF_INET6: expected = MemoryLayout<sockaddr_in6>.size
            default: continue
            }
            guard data.count >= expected, Int(storage.ss_len) >= expected, Int(storage.ss_len) <= data.count else { continue }
            var host = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            let value: String? = withUnsafePointer(to: &storage) { pointer in
                if family == AF_INET {
                    var address = pointer.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
                    guard inet_ntop(AF_INET, &address, &host, socklen_t(host.count)) != nil else { return nil }
                    return String(cString: host)
                }
                var address = pointer.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { $0.pointee }
                guard inet_ntop(AF_INET6, &address.sin6_addr, &host, socklen_t(host.count)) != nil else { return nil }
                let scope = address.sin6_scope_id == 0 ? "" : "%\(address.sin6_scope_id)"
                return String(cString: host) + scope
            }
            if let value, !hosts.contains(value) { hosts.append(value) }
        }
        guard let host = hosts.first else { return nil }
        // Retain the advertised port as metadata only. Pair/control use fixed 6467/6466.
        return .init(name: name, host: host, port: UInt16(port), addresses: hosts, txt: txt)
    }
}
