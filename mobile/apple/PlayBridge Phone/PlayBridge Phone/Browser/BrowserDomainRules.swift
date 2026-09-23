import Foundation

/// WebKit accepts a restricted regex grammar: no alternation or nested end anchor.
enum BrowserDomainRules {
    static func json(_ domains: [String], resourceTypes: [String]) -> String {
        guard !domains.isEmpty else { return "" }
        var rules: [[String: Any]] = []
        for domain in domains {
            let prefix = "^https?://([^/?#:@]+\\.)?" + NSRegularExpression.escapedPattern(for: domain)
            // Separate rules cover a host boundary and a bare host. Keep '$' last.
            for filter in [prefix + "[:/?]", prefix + "$"] {
                rules.append(["trigger": ["url-filter": filter, "resource-type": resourceTypes],
                              "action": ["type": "block"]])
                rules.append(["trigger": ["url-filter": filter, "resource-type": ["document"], "load-context": ["child-frame"]],
                              "action": ["type": "block"]])
            }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let json = String(data: data, encoding: .utf8) else { return "" }
        return json
    }
}
