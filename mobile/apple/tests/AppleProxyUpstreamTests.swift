import Foundation
import PlayBridgeCastCore

@main
struct AppleProxyUpstreamTests {
    nonisolated static func main() throws {
        try AppleProxyUpstream.install()
        let callbacks = test_upstream_callbacks()
        let origin = ProcessInfo.processInfo.environment["UPSTREAM_TEST_ORIGIN"]!
        func open(_ path: String, headers: String = "{}") -> (Int64, Int32, String) {
            var status: Int32 = 0
            var metadata: UnsafeMutablePointer<CChar>?
            var error: UnsafeMutablePointer<CChar>?
            let handle = (origin + path).withCString { url in
                headers.withCString { headers in callbacks.open(url, headers, nil, &status, &metadata, &error) }
            }
            let text = metadata.map { String(cString: $0) } ?? error.map { String(cString: $0) } ?? ""
            if let metadata { callbacks.free_string(metadata) }
            if let error { callbacks.free_string(error) }
            return (handle, status, text)
        }
        func read(_ handle: Int64, count: Int = 131071) -> (Int32, [UInt8]) {
            var bytes = [UInt8](repeating: 0, count: count)
            var error: UnsafeMutablePointer<CChar>?
            let length = callbacks.read(handle, &bytes, Int32(count), &error)
            if let error { callbacks.free_string(error) }
            return (length, length > 0 ? Array(bytes.prefix(Int(length))) : [])
        }
        let (handle, status, metadata) = open("/body", headers: "{\"Referer\":\"https://example.test/player\",\"User-Agent\":\"AppleFixture\"}")
        precondition(handle > 0 && status == 200 && metadata.contains("content-length"))
        var total = 0
        while true {
            let (count, bytes) = read(handle)
            precondition(count >= 0)
            if count == 0 { break }
            precondition(bytes.allSatisfy { $0 == 65 })
            total += Int(count)
        }
        precondition(total == 2 * 1024 * 1024)
        callbacks.close(handle)
        callbacks.close(handle)
        precondition(read(handle).0 == -1)

        let (rangeHandle, rangeStatus, rangeMetadata) = open("/range", headers: "{\"Range\":\"bytes=4-7\"}")
        let decoded = try JSONDecoder().decode([String: String].self, from: Data(rangeMetadata.utf8))
        precondition(rangeStatus == 206 && decoded["content-range"] == "bytes 4-7/10")
        precondition(read(rangeHandle).1 == Array("4567".utf8))
        callbacks.close(rangeHandle)

        let (slow, _, _) = open("/slow")
        precondition(slow > 0)
        var initial = 0
        while initial < 65536 { initial += Int(read(slow, count: 65536 - initial).0) }
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.global().async {
            var byte: UInt8 = 0
            var error: UnsafeMutablePointer<CChar>?
            precondition(callbacks.read(slow, &byte, 1, &error) == -1)
            if let error { callbacks.free_string(error) }
            group.leave()
        }
        Thread.sleep(forTimeInterval: 0.1)
        callbacks.close(slow)
        precondition(group.wait(timeout: .now() + 2) == .success)

        let (redirect, redirectStatus, redirectMetadata) = open("/redirect")
        precondition(redirect > 0 && redirectStatus == 302)
        let redirectHeaders = try JSONDecoder().decode([String: String].self, from: Data(redirectMetadata.utf8))
        precondition(redirectHeaders["location"] == "/body")
        callbacks.close(redirect)
        // The URL hostname cannot resolve: success proves the configured native
        // proxy, rather than an independent origin DNS lookup, carried this request.
        let proxyPort = URL(string: origin)!.port!
        let gateway = "{\"host\":\"127.0.0.1\",\"port\":\(proxyPort),\"username\":\"playbridge\",\"password\":\"\(String(repeating: "A", count: 43))\"}"
        var checkedStatus: Int32 = 0
        var checkedHeaders: UnsafeMutablePointer<CChar>?
        var checkedError: UnsafeMutablePointer<CChar>?
        let checked = "http://checked-origin.invalid:\(proxyPort)/checked".withCString { url in
            "{\"Authorization\":\"Bearer original-origin\"}".withCString { headers in
                gateway.withCString { proxy in callbacks.open(url, headers, proxy, &checkedStatus, &checkedHeaders, &checkedError) }
            }
        }
        if let checkedError { print(String(cString: checkedError)); callbacks.free_string(checkedError) }
        if let checkedHeaders { callbacks.free_string(checkedHeaders) }
        precondition(checked > 0 && checkedStatus == 200, "checked handle=\(checked) status=\(checkedStatus)")
        precondition(read(checked).1 == Array("checked-native-routing".utf8))
        callbacks.close(checked)
        var tlsStatus: Int32 = 0
        var tlsHeaders: UnsafeMutablePointer<CChar>?
        var tlsError: UnsafeMutablePointer<CChar>?
        let tls = "https://checked-origin.invalid/checked".withCString { url in
            "{}".withCString { headers in
                gateway.withCString { proxy in callbacks.open(url, headers, proxy, &tlsStatus, &tlsHeaders, &tlsError) }
            }
        }
        if let tlsHeaders { callbacks.free_string(tlsHeaders) }
        if let tlsError { callbacks.free_string(tlsError) }
        precondition(tls == 0, "missing TLS peer must not be accepted")
        let marker = ProcessInfo.processInfo.environment["UPSTREAM_PROXY_CONNECT_MARKER"]!
        precondition((try? String(contentsOfFile: marker, encoding: .utf8)) == "authenticated", "HTTPS must use authenticated CONNECT instead of independent DNS")
        print("Apple proxy upstream fixtures passed, including checked native proxy routing")
    }
}
