import Foundation

// A Direct route must never reach the native proxy, even with invalid proxy settings.
final class PhoneProxyRegistration { let url = URL(string: "http://phone.test/video")! }
final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(url: String, headers: [String: String], contentType: String?) async throws -> PhoneProxyRegistration {
        fatalError("Unexpected native proxy startup")
    }
}

@main struct StreamRouteTests {
    static func main() async throws {
        let source = "https://media.test/master.m3u8?token=original"
        let headers = ["Referer": "https://page.test/", "Authorization": "fixture-secret"]
        var phoneCalls = 0
        var remoteCalls = 0
        let router = StreamRouteService(phone: { _, forwarded, _ in
            phoneCalls += 1
            precondition(forwarded == headers)
            return RoutedStream(url: URL(string: "http://phone.test/s/session/master.m3u8")!, headers: [:])
        }, remote: { _, forwarded, _, config in
            remoteCalls += 1
            precondition(forwarded == headers && config.password == "a&b+? secret")
            return RoutedStream(url: URL(string: "https://proxy.test/s/session/master.m3u8")!, headers: [:])
        })
        let direct = try await router.prepare(url: source, headers: headers, contentType: nil, route: .direct, configuration: .init())
        precondition(direct.url.absoluteString == source && direct.headers == headers)
        precondition(phoneCalls == 0 && remoteCalls == 0)
        let phone = try await router.prepare(url: source, headers: headers, contentType: nil, route: .phone, configuration: .init())
        precondition(phone.url.host == "phone.test" && phone.headers.isEmpty && phoneCalls == 1 && remoteCalls == 0)
        let config = RemoteProxyConfiguration(baseURL: "https://proxy.test/prefix/", password: "a&b+? secret")
        let proxy = try await router.prepare(url: source, headers: headers, contentType: nil, route: .proxy, configuration: config)
        precondition(proxy.url.host == "proxy.test" && proxy.headers.isEmpty && phoneCalls == 1 && remoteCalls == 1)
        do {
            _ = try await router.prepare(url: source, headers: headers, contentType: nil, route: .proxy, configuration: .init())
            preconditionFailure("Unconfigured proxy must fail")
        } catch {}
        precondition(remoteCalls == 1)
        let failing = StreamRouteService(phone: { _, _, _ in throw StreamRoutingError.message("fixture failure") })
        do {
            _ = try await failing.prepare(url: source, headers: headers, contentType: nil, route: .phone, configuration: config)
            preconditionFailure("An explicit proxy route must not silently become Direct")
        } catch {}
        let request = try RemoteProxyClient.request(url: source, headers: headers, contentType: "application/vnd.apple.mpegurl", configuration: config)
        precondition(request.httpMethod == "POST" && request.url?.path == "/prefix/register")
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
        precondition(query.first?.value == config.password)
        precondition(request.url!.absoluteString.contains("%2B"))
        let json = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
        precondition(json["url"] as? String == source && json["headers"] as? [String: String] == headers)
        if let base = ProcessInfo.processInfo.environment["REMOTE_PROXY_TEST_BASE"] {
            let remote = try await RemoteProxyClient.register(url: source, headers: headers, contentType: nil,
                configuration: .init(baseURL: base + "/prefix", password: config.password))
            precondition(remote.url.host == "remote-fixture.test" && remote.headers.isEmpty)
            do {
                _ = try await RemoteProxyClient.register(url: source, headers: headers, contentType: nil,
                    configuration: .init(baseURL: base + "/prefix", password: "wrong"))
                preconditionFailure("Wrong remote password must fail")
            } catch {
                precondition(error.localizedDescription.contains("403"))
            }
        }
        print("PASS: Direct bypasses proxies; explicit phone/remote routes, no silent fallback, remote registration contract")
    }
}
