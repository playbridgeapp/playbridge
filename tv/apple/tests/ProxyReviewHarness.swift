import Foundation
import Network

// Logging is irrelevant to the HTTP transport test; the app supplies these helpers.
func debugLogNetworkRequest(_ source: String, url: URL, method: String = "GET", headers: [String: String]? = nil) {}
func debugLogNetworkResponse(_ source: String, url: URL?, statusCode: Int, headers: [AnyHashable: Any]) {}

@main
struct ProxyReviewHarness {
    static func main() {
        let proxy = VLCProxyServer(targetURL: URL(string: CommandLine.arguments[1])!, headers: ["X-Test": "fixture"])
        proxy.start { ready in
            guard ready else { exit(1) }
            print("READY \(proxy.port)")
            fflush(stdout)
        }
        withExtendedLifetime(proxy) { dispatchMain() }
    }
}
