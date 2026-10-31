import Foundation

@main struct PageCastNetworkPolicyVectorChecks {
    struct Vector: Decodable {
        let name: String
        let url: String
        let allowed_private_origins: [String]
        let dns_answers: [String]?
        let expected: String
        let note: String
    }

    // These existing Swift checks are stricter than Rust. Keep them explicit
    // rather than weakening Swift or changing the shared expected result.
    static let stricterCases = [
        "private granted 100.64.0.1": "Swift forbids CGNAT",
        "private granted 100.127.255.254": "Swift forbids CGNAT",
        "transition [::8.8.8.8]": "Swift forbids IPv4-compatible IPv6",
        "long URL": "Swift caps URLs at 8192 UTF-16 code units",
        "inert loopback literal grant": "Swift rejects forbidden literal grants",
        "inert forbidden literal grant": "Swift rejects forbidden literal grants",
    ]

    static func allowed(_ vector: Vector) -> Bool {
        guard let grants = try? PageCastRequest.parsePrivateOrigins(vector.allowed_private_origins),
              let parts = PageCastNetworkPolicy.components(vector.url), let host = parts.host,
              !PageCastNetworkPolicy.isLoopbackHost(host),
              let origin = PageCastNetworkPolicy.origin(vector.url) else { return false }
        // Apply the same aggregation as resolve(), using supplied literal answers
        // instead of getaddrinfo. No fixture performs a live DNS lookup.
        let answers = vector.dns_answers ?? [host]
        guard !answers.isEmpty else { return false }
        var privateLAN = host.lowercased().hasSuffix(".local")
        for answer in answers {
            guard let classification = PageCastNetworkPolicy.literalClass(answer) else {
                preconditionFailure("Nonliteral DNS fixture in \(vector.name)")
            }
            switch classification {
            case .forbidden: return false
            case .privateLAN: privateLAN = true
            case .publicInternet: break
            }
        }
        return !privateLAN || grants.contains(origin)
    }

    static func main() throws {
        let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let vectors = try JSONDecoder().decode([Vector].self, from: data)
        precondition(!vectors.isEmpty)
        var failures = [String]()
        var seenDifferences = Set<String>()
        for vector in vectors {
            precondition(["allow", "deny"].contains(vector.expected))
            let rustAllows = vector.expected == "allow"
            let actual = allowed(vector)
            if let reason = stricterCases[vector.name] {
                precondition(rustAllows, "Only stricter differences may be recorded")
                seenDifferences.insert(vector.name)
                if actual { failures.append("\(vector.name): expected stricter deny: \(reason)") }
            } else if actual != rustAllows {
                failures.append("\(vector.name): expected \(vector.expected), got \(actual ? "allow" : "deny"): \(vector.note)")
            }
        }
        precondition(seenDifferences == Set(stricterCases.keys), "Stale difference entry")
        for failure in failures { print(failure) }
        precondition(failures.isEmpty, "Shared vector disagreements")
        print("Swift: \(vectors.count) shared vectors checked; \(seenDifferences.count) documented stricter differences")
    }
}
