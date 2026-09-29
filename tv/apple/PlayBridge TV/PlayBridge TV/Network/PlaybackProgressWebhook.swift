import Foundation
import Network
import Security
import Darwin

/// Generic cast-scoped reporting. This object and its pending requests are never persisted.
final class PlaybackProgressWebhook {
    private var destination: URL?
    private var token = ""
    private var playbackID = ""
    private var itemID: String?
    private var content: [String: Any] = [:]
    private var position = 0
    private var duration = 0
    private var playing = false
    private var started = false
    private var lastReport = Date.distantPast
    private let transport = ProgressWebhookTransport()
    private let now: () -> Date
    private let sendOverride: ((URL, String, Data) -> Void)?

    init(now: @escaping () -> Date = Date.init, send: ((URL, String, Data) -> Void)? = nil) {
        self.now = now
        self.sendOverride = send
    }

    func configure(url: String?, token: String?, playbackID: String) {
        finish(clear: true)
        guard let url, let token else { return }
        guard let parsed = URL(string: url), Self.validURL(parsed), !token.isEmpty,
              token.utf8.count <= 4096,
              token.utf8.allSatisfy({ (33...126).contains(Int($0)) }) else {
            print("Progress webhook disabled: invalid configuration")
            return
        }
        destination = parsed
        self.token = token
        self.playbackID = playbackID
    }

    static func validURL(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.fragment == nil, url.query == nil,
              (url.port ?? 443) == 443, url.absoluteString.utf8.count <= 2048 else { return false }
        let normalized = host.lowercased()
        return !normalized.hasSuffix(".") && normalized != "localhost"
            && !normalized.hasSuffix(".localhost") && !normalized.hasSuffix(".local")
    }

    func sample(itemID: String, content: [String: Any], state: String, position: Int, duration: Int) {
        guard destination != nil, Self.validContent(content) else { return }
        if self.itemID != itemID {
            finish(clear: false)
            self.itemID = itemID
            self.content = content
        }
        if duration > 0 {
            self.duration = duration
            if !((state == "stopped" || state == "ended") && position == 0 && self.position > 0) {
                self.position = min(max(0, position), duration)
            }
        }
        if state == "playing" {
            if (!playing || !started) && self.duration > 0 { emit("started"); started = true }
            else if now().timeIntervalSince(lastReport) >= 30 { emit("progress") }
            playing = true
        } else if state == "paused" {
            if playing { emit("paused") }
            playing = false
        }
    }

    func finish(clear: Bool, completed: Bool = false) {
        if started { emit(completed ? "ended" : "stopped") }
        itemID = nil
        content = [:]
        playing = false
        started = false
        position = 0
        duration = 0
        if clear { destination = nil; token = ""; playbackID = "" }
    }

    /// Engines use the same next callback for EOF and failures: require a near-end sample.
    func advance() {
        finish(clear: false, completed: duration > 0 && position >= duration - 2000)
    }

    private func emit(_ event: String) {
        guard let destination, let itemID, duration > 0 else { return }
        let body: [String: Any] = [
            "version": 1, "eventId": UUID().uuidString, "playbackId": playbackID,
            "itemId": itemID, "event": event, "content": content,
            "positionMs": position, "durationMs": duration,
            "occurredAt": ISO8601DateFormatter().string(from: now())
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }
        lastReport = now()
        if let sendOverride { sendOverride(destination, token, data) }
        else { transport.post(url: destination, token: token, body: data) }
    }

    private static func validContent(_ value: [String: Any]) -> Bool {
        guard let type = value["type"] as? String, type == "movie" || type == "series",
              let contentID = value["contentId"] as? String, !contentID.isEmpty, contentID.utf8.count <= 256,
              let videoID = value["videoId"] as? String, !videoID.isEmpty, videoID.utf8.count <= 256 else { return false }
        if type == "series" {
            let season = (value["season"] as? NSNumber)?.intValue
            let episode = (value["episode"] as? NSNumber)?.intValue
            guard let season, season >= 0, let episode, episode >= 0 else { return false }
        }
        return true
    }
}

/// Resolve once, reject all non-public answers, then connect to that exact address.
/// TLS retains the original hostname for SNI and certificate validation. No redirects,
/// cookies, caches, response bodies, or credentials in diagnostics.
final class ProgressWebhookTransport {
    private struct Job {
        let url: URL
        let token: String
        let body: Data
        let created: Date
        let retry: Int
    }
    private let queue = DispatchQueue(label: "playbridge.progress-webhook")
    private let dnsQueue = DispatchQueue(label: "playbridge.progress-webhook-dns")
    private var jobs: [Job] = []
    private var active = false

    func post(url: URL, token: String, body: Data) {
        queue.async {
            guard self.jobs.count < 32 else { return }
            self.jobs.append(Job(url: url, token: token, body: body, created: Date(), retry: 0))
            self.startNext()
        }
    }

    private func startNext() {
        guard !active else { return }
        while !jobs.isEmpty {
            let job = jobs.removeFirst()
            if Date().timeIntervalSince(job.created) > 90 { continue }
            active = true
            attempt(job)
            return
        }
    }

    private func complete(_ job: Job, retryable: Bool) {
        if retryable && job.retry < 2 && Date().timeIntervalSince(job.created) < 90 {
            let next = Job(url: job.url, token: job.token, body: job.body,
                           created: job.created, retry: job.retry + 1)
            queue.asyncAfter(deadline: .now() + Double(1 << job.retry)) { self.attempt(next) }
            return
        }
        active = false
        startNext()
    }

    private func attempt(_ job: Job) {
        guard Date().timeIntervalSince(job.created) < 90, let host = job.url.host else {
            complete(job, retryable: false)
            return
        }
        var resolved = false
        queue.asyncAfter(deadline: .now() + 5) {
            guard !resolved else { return }
            resolved = true
            self.complete(job, retryable: true)
        }
        dnsQueue.async {
            let address = Self.resolvePublic(host)
            self.queue.async {
                guard !resolved else { return }
                resolved = true
                guard let address else {
                    print("Progress webhook destination rejected")
                    self.complete(job, retryable: false)
                    return
                }
                self.connect(job: job, host: host, address: address)
            }
        }
    }

    private func connect(job: Job, host: String, address: String) {
        guard let port = NWEndpoint.Port(rawValue: UInt16(exactly: job.url.port ?? 443) ?? 0), port.rawValue > 0 else {
            complete(job, retryable: false)
            return
        }
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions, host)
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let serverTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            SecTrustSetPolicies(serverTrust, SecPolicyCreateSSL(true, host as CFString))
            complete(SecTrustEvaluateWithError(serverTrust, nil))
        }, queue)
        let connection = NWConnection(host: NWEndpoint.Host(address), port: port,
                                      using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
        var finished = false
        var response = Data()
        let finish: (Bool) -> Void = { retryable in
            guard !finished else { return }
            finished = true
            connection.stateUpdateHandler = nil
            connection.cancel()
            self.complete(job, retryable: retryable)
        }
        func receive() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, complete, error in
                if let data { response.append(data) }
                if let end = response.range(of: Data("\r\n\r\n".utf8)) {
                    let header = String(decoding: response[..<end.lowerBound], as: UTF8.self)
                    let status = header.split(separator: "\r\n").first?.split(separator: " ").dropFirst().first.flatMap { Int($0) } ?? 0
                    // Retry only server failures; redirects/auth failures are terminal.
                    if (300..<500).contains(status) { print("Progress webhook endpoint rejected event") }
                    finish(status == 429 || status >= 500)
                } else if response.count > 16384 || complete || error != nil { finish(true) }
                else { receive() }
            }
        }
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                let components = URLComponents(url: job.url, resolvingAgainstBaseURL: false)!
                let path = (components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath)
                    + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
                let hostHeader = host + (job.url.port.map { ":\($0)" } ?? "")
                var request = Data("POST \(path) HTTP/1.1\r\nHost: \(hostHeader)\r\nAuthorization: Bearer \(job.token)\r\nContent-Type: application/json\r\nContent-Length: \(job.body.count)\r\nConnection: close\r\n\r\n".utf8)
                request.append(job.body)
                connection.send(content: request, completion: .contentProcessed { error in
                    if error != nil { finish(true) } else { receive() }
                })
            case .failed: finish(true)
            default: break
            }
        }
        queue.asyncAfter(deadline: .now() + 10) { finish(true) }
        connection.start(queue: queue)
    }

    private static func resolvePublic(_ hostname: String) -> String? {
        let host = hostname.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else { return nil }
        defer { freeaddrinfo(first) }
        var current: UnsafeMutablePointer<addrinfo>? = first
        var selected: String?
        while let entry = current {
            let info = entry.pointee
            guard let address = info.ai_addr else { return nil }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, info.ai_addrlen, &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
            let numeric = String(cString: buffer)
            guard isPublicAddress(numeric) else { return nil }
            selected = selected ?? numeric
            current = info.ai_next
        }
        return selected
    }

    static func isPublicAddress(_ value: String) -> Bool {
        var v4 = in_addr()
        if inet_pton(AF_INET, value, &v4) == 1 {
            let n = UInt32(bigEndian: v4.s_addr)
            let a = n >> 24, b = (n >> 16) & 255
            return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && (64...127).contains(b)) && !(a == 169 && b == 254)
                && !(a == 172 && (16...31).contains(b)) && !(a == 192 && (b == 168 || b == 0 || b == 2))
                && !(a == 198 && (b == 18 || b == 19 || b == 51)) && !(a == 203 && b == 0)
        }
        var v6 = in6_addr()
        guard inet_pton(AF_INET6, value, &v6) == 1 else { return false }
        return withUnsafeBytes(of: &v6) { bytes in
            // Only global unicast; reject mapped IPv4, ULA, link-local and transition ranges.
            bytes[0] & 0xe0 == 0x20 && !(bytes[0] == 0x20 && bytes[1] == 0x02)
                && !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] < 0x02)
                && !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
        }
    }
}
