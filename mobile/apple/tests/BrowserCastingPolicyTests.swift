import Foundation

@main struct BrowserCastingPolicyChecks {
    static func main() {
        for host in BrowserCastingPolicy.blockedHosts {
            for address in ["https://\(host)/watch", "https://player.\(host)/", "https://\(host.uppercased()):8443/", "https://\(host)./"] {
                let url = URL(string: address)!
                precondition(BrowserCastingPolicy.blocks(url), address)
                precondition(!BrowserCastingPolicy.allows(pageURL: url, mediaURL: URL(string: "https://media.example/movie.mp4")))
                precondition(!BrowserCastingPolicy.allows(pageURL: URL(string: "https://site.example"), mediaURL: url))
            }
            for lookalike in ["not\(host)", "\(host).example", "\(host.replacingOccurrences(of: ".", with: "-"))"] {
                precondition(!BrowserCastingPolicy.blocks(URL(string: "https://\(lookalike)/")), lookalike)
            }
        }
        for allowed in ["https://amazon.com/video", "https://apple.com/", "https://notyoutube.com", "https://example.com:443", "file:///movie.mp4"] {
            precondition(BrowserCastingPolicy.allows(pageURL: URL(string: allowed)))
        }
        precondition(BrowserCastingPolicy.allows(pageURL: nil, mediaURL: nil))
        print("PASS: casting host boundaries, subdomains, ports, case, lookalikes and page/media decisions")
        if CommandLine.arguments.count > 1 {
            try! DetectionScript.source.write(toFile: CommandLine.arguments[1], atomically: true, encoding: .utf8)
        }
    }
}
