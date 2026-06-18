import Foundation
import WebKit

/// Ad/tracker blocking for the browser via `WKContentRuleList` — the Safari content-blocker
/// mechanism. Supports standard curated block lists, custom filter lists (like EasyList),
/// downloading lists in the background, and offline compilation.
enum ContentBlocker {
    static let identifier = "playbridge-adblock-v2"
    private static let enabledKey = "pb_adblock_enabled"
    private static let filterListsKey = "pb_adblock_filter_lists"

    /// Stored compilation error message for diagnostic display in the UI.
    @MainActor static var lastCompilationError: String?

    /// Persisted toggle. Defaults to on.
    static var isEnabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    /// List of URLs to fetch rules from. Pre-populated with default lists.
    static var filterListURLs: [URL] {
        get {
            let list = UserDefaults.standard.stringArray(forKey: filterListsKey) ?? [
                "https://easylist.to/easylist/easylist.txt",
                "https://easylist.to/easylist/easyprivacy.txt",
                "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt",
                "https://easylist.to/easylist/fanboy-annoyances.txt"
            ]
            return list.compactMap { URL(string: $0) }
        }
        set {
            let strings = newValue.map { $0.absoluteString }
            UserDefaults.standard.set(strings, forKey: filterListsKey)
        }
    }

    /// Compile (or fetch the cached) rule lists. Returns compiled lists.
    @MainActor
    static func compileAll() async -> [WKContentRuleList] {
        var lists: [WKContentRuleList] = []
        let store = WKContentRuleListStore.default()
        
        var compiledAnyCustom = false
        for (index, url) in filterListURLs.enumerated() {
            let fileURL = getLocalListPath(for: url)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                let identifier = "playbridge-adblock-list-\(index)"
                if let list = try? await lookupOrCompile(store: store, identifier: identifier, fileURL: fileURL) {
                    lists.append(list)
                    compiledAnyCustom = true
                }
            }
        }
        
        if !compiledAnyCustom {
            let identifier = "playbridge-adblock-curated"
            if let list = try? await lookupOrCompileCurated(store: store, identifier: identifier) {
                lists.append(list)
            }
        }
        
        return lists
    }

    /// Forces compilation of all downloaded rules.
    @MainActor
    static func forceCompileAll() async throws -> [WKContentRuleList] {
        lock.lock()
        isPatternsCompiled = false
        compiledBlockPatterns = []
        lock.unlock()

        let store = WKContentRuleListStore.default()
        guard let store = store else { return [] }
        
        var lists: [WKContentRuleList] = []
        var compiledAnyCustom = false
        
        for (index, url) in filterListURLs.enumerated() {
            let fileURL = getLocalListPath(for: url)
            if FileManager.default.fileExists(atPath: fileURL.path) {
                let identifier = "playbridge-adblock-list-\(index)"
                let text = try String(contentsOf: fileURL, encoding: .utf8)
                let json = parseListTextToJSON(text)
                
                let list = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<WKContentRuleList, Error>) in
                    store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                        if let error {
                            let nsError = error as NSError
                            let errorDesc = nsError.localizedDescription
                            let userInfoStr = nsError.userInfo.description
                            Task { @MainActor in
                                let details = "List: \(identifier) (URL: \(url.lastPathComponent)), Error: \(errorDesc), Info: \(userInfoStr)"
                                ContentBlocker.lastCompilationError = details
                                print("ContentBlocker: \(details)")
                            }
                            cont.resume(throwing: error)
                        }
                        else if let list { cont.resume(returning: list) }
                        else { cont.resume(throwing: NSError(domain: "ContentBlocker", code: -1)) }
                    }
                }
                lists.append(list)
                compiledAnyCustom = true
            }
        }
        
        if !compiledAnyCustom {
            let identifier = "playbridge-adblock-curated"
            let json = makeCuratedRulesJSON()
            let list = try await withCheckedThrowingContinuation { (cont: CheckedContinuation<WKContentRuleList, Error>) in
                store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                    if let error {
                        let nsError = error as NSError
                        let errorDesc = nsError.localizedDescription
                        let userInfoStr = nsError.userInfo.description
                        Task { @MainActor in
                            let details = "Curated rules failed. Error: \(errorDesc), Info: \(userInfoStr)"
                            ContentBlocker.lastCompilationError = details
                            print("ContentBlocker: \(details)")
                        }
                        cont.resume(throwing: error)
                    }
                    else if let list { cont.resume(returning: list) }
                    else { cont.resume(throwing: NSError(domain: "ContentBlocker", code: -1)) }
                }
            }
            lists.append(list)
        }
        
        return lists
    }

    @MainActor
    private static func lookup(store: WKContentRuleListStore?, identifier: String) async throws -> WKContentRuleList? {
        guard let store = store else { return nil }
        return try await withCheckedThrowingContinuation { cont in
            store.lookUpContentRuleList(forIdentifier: identifier) { list, error in
                if let list { cont.resume(returning: list) }
                else if let error { cont.resume(throwing: error) }
                else { cont.resume(returning: nil) }
            }
        }
    }

    @MainActor
    private static func lookupOrCompile(store: WKContentRuleListStore?, identifier: String, fileURL: URL) async throws -> WKContentRuleList? {
        guard let store = store else { return nil }
        if let cached = try? await lookup(store: store, identifier: identifier) {
            return cached
        }
        let text = try String(contentsOf: fileURL, encoding: .utf8)
        let json = parseListTextToJSON(text)
        return try await withCheckedThrowingContinuation { cont in
            store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                if let error {
                    let nsError = error as NSError
                    let errorDesc = nsError.localizedDescription
                    let userInfoStr = nsError.userInfo.description
                    Task { @MainActor in
                        let details = "List \(identifier) lookupOrCompile failed. Error: \(errorDesc), Info: \(userInfoStr)"
                        ContentBlocker.lastCompilationError = details
                        print("ContentBlocker: \(details)")
                    }
                    cont.resume(throwing: error)
                }
                else if let list { cont.resume(returning: list) }
                else { cont.resume(returning: nil) }
            }
        }
    }

    @MainActor
    private static func lookupOrCompileCurated(store: WKContentRuleListStore?, identifier: String) async throws -> WKContentRuleList? {
        guard let store = store else { return nil }
        if let cached = try? await lookup(store: store, identifier: identifier) {
            return cached
        }
        let json = makeCuratedRulesJSON()
        return try await withCheckedThrowingContinuation { cont in
            store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                if let error {
                    let nsError = error as NSError
                    let errorDesc = nsError.localizedDescription
                    let userInfoStr = nsError.userInfo.description
                    Task { @MainActor in
                        let details = "Curated list \(identifier) lookupOrCompile failed. Error: \(errorDesc), Info: \(userInfoStr)"
                        ContentBlocker.lastCompilationError = details
                        print("ContentBlocker: \(details)")
                    }
                    cont.resume(throwing: error)
                }
                else if let list { cont.resume(returning: list) }
                else { cont.resume(returning: nil) }
            }
        }
    }

    // MARK: - Download & Compile Manager

    /// Download a list from a URL and save it to the local Documents directory.
    static func download(url: URL) async throws {
        let (data, _) = try await URLSession.shared.data(from: url)
        guard let text = String(data: data, encoding: .utf8) else {
            throw NSError(domain: "ContentBlocker", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to decode list"])
        }
        let fileURL = getLocalListPath(for: url)
        try text.write(to: fileURL, atomically: true, encoding: .utf8)
    }

    static func getLocalListPath(for url: URL) -> URL {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first!
        let safeName = url.absoluteString.components(separatedBy: CharacterSet.alphanumerics.inverted).joined(separator: "_") + ".txt"
        return docs.appendingPathComponent(safeName)
    }

    static func isListDownloaded(url: URL) -> Bool {
        let path = getLocalListPath(for: url).path
        return FileManager.default.fileExists(atPath: path)
    }

    /// Parse EasyList text format into WebKit Content Blocker rules (with 60k rule limit and cosmetic chunking)
    private static func parseListTextToJSON(_ text: String) -> String {
        var rules: [[String: Any]] = []
        var cosmeticSelectors: [String] = []
        var ruleCount = 0
        
        let lines = text.components(separatedBy: CharacterSet.newlines)
        for line in lines {
            let trimmed = line.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
            if trimmed.isEmpty || trimmed.hasPrefix("!") { continue }
            
            // 1. Cosmetic rules
            if trimmed.contains("##") {
                let parts = trimmed.components(separatedBy: "##")
                if parts.count == 2 {
                    let domains = parts[0]
                    let selector = parts[1]
                    if domains.isEmpty {
                        if isValidCSSSelector(selector) {
                            cosmeticSelectors.append(selector)
                        }
                    } else {
                        if isValidCSSSelector(selector) {
                            let domainList = domains.components(separatedBy: ",").map { $0.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines) }
                            var positiveDomains: [String] = []
                            var negativeDomains: [String] = []
                            for d in domainList {
                                var cleaned = d
                                if cleaned.isEmpty { continue }
                                
                                var isNegated = false
                                if cleaned.hasPrefix("~") {
                                    isNegated = true
                                    cleaned = String(cleaned.dropFirst())
                                }
                                
                                // Strip leading wildcards like * or *.
                                if cleaned.hasPrefix("*") {
                                    cleaned = String(cleaned.dropFirst())
                                }
                                if cleaned.hasPrefix(".") {
                                    cleaned = String(cleaned.dropFirst())
                                }
                                
                                let domainStr = cleaned.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
                                if domainStr.isEmpty { continue }
                                
                                if isValidRuleDomain(domainStr) {
                                    if isNegated {
                                        negativeDomains.append(domainStr)
                                    } else {
                                        positiveDomains.append(domainStr)
                                    }
                                }
                            }
                            
                            if !positiveDomains.isEmpty || !negativeDomains.isEmpty {
                                var trigger: [String: Any] = ["url-filter": ".*"]
                                if !positiveDomains.isEmpty {
                                    trigger["if-domain"] = positiveDomains
                                } else if !negativeDomains.isEmpty {
                                    trigger["unless-domain"] = negativeDomains
                                }
                                
                                rules.append([
                                    "trigger": trigger,
                                    "action": ["type": "css-display-none", "selector": selector]
                                ])
                            }
                        }
                    }
                }
                continue
            }
            
            // 2. Exception rules
            if trimmed.hasPrefix("@@||") {
                let rule = trimmed.dropFirst(4)
                var domain = rule.prefix(while: { $0 != "^" && $0 != "/" && $0 != "$" })
                if domain.hasPrefix("*") {
                    domain = domain.dropFirst()
                }
                if domain.hasPrefix(".") {
                    domain = domain.dropFirst()
                }
                let domainStr = String(domain).trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
                if !domainStr.isEmpty && isValidRuleDomain(domainStr) {
                    let escaped = domainStr.replacingOccurrences(of: ".", with: "\\.")
                    rules.append([
                        "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)[:/?]", "load-type": ["third-party"]],
                        "action": ["type": "ignore-previous-rules"]
                    ])
                    rules.append([
                        "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)$", "load-type": ["third-party"]],
                        "action": ["type": "ignore-previous-rules"]
                    ])
                }
                continue
            }
            
            // 3. Block rules starting with ||
            if trimmed.hasPrefix("||") {
                let rule = trimmed.dropFirst(2)
                var domain = rule.prefix(while: { $0 != "^" && $0 != "/" && $0 != "$" })
                if domain.hasPrefix("*") {
                    domain = domain.dropFirst()
                }
                if domain.hasPrefix(".") {
                    domain = domain.dropFirst()
                }
                let domainStr = String(domain).trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
                if !domainStr.isEmpty && isValidRuleDomain(domainStr) {
                    let escaped = domainStr.replacingOccurrences(of: ".", with: "\\.")
                    rules.append([
                        "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)[:/?]", "load-type": ["third-party"]],
                        "action": ["type": "block"]
                    ])
                    rules.append([
                        "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)$", "load-type": ["third-party"]],
                        "action": ["type": "block"]
                    ])
                    ruleCount += 2
                    if ruleCount > 60000 { break }
                }
                continue
            }
        }
        
        // Chunk cosmetic rules to prevent WebKit length limits
        let chunkSize = 1000
        for i in stride(from: 0, to: cosmeticSelectors.count, by: chunkSize) {
            let end = min(i + chunkSize, cosmeticSelectors.count)
            let chunk = Array(cosmeticSelectors[i..<end])
            let joinedSelector = chunk.joined(separator: ", ")
            rules.append([
                "trigger": ["url-filter": ".*"],
                "action": ["type": "css-display-none", "selector": joinedSelector]
            ])
        }
        
        if rules.isEmpty {
            rules.append([
                "trigger": ["url-filter": "playbridge-dummy-rule-to-prevent-empty-list-error"],
                "action": ["type": "block"]
            ])
        }
        
        let dummyRuleJSON = """
        [
          {
            "trigger": { "url-filter": "playbridge-dummy-rule-to-prevent-empty-list-error" },
            "action": { "type": "block" }
          }
        ]
        """
        
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let json = String(data: data, encoding: .utf8) else { return dummyRuleJSON }
        return json
    }

    private static func makeCuratedRulesJSON() -> String {
        var rules: [[String: Any]] = blockedDomains.map { domain in
            let escaped = domain.replacingOccurrences(of: ".", with: "\\.")
            return [
                "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)", "load-type": ["third-party"]],
                "action": ["type": "block"],
            ]
        }
        rules.append([
            "trigger": ["url-filter": ".*"],
            "action": ["type": "css-display-none", "selector": cosmeticSelector],
        ])
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let json = String(data: data, encoding: .utf8) else { return "[]" }
        return json
    }

    private static let cosmeticSelector = [
        "ins.adsbygoogle", ".adsbygoogle", "#google_ads_iframe",
        "iframe[src*=\"doubleclick.net\"]", "iframe[src*=\"googlesyndication\"]",
        "iframe[src*=\"adservice\"]", "iframe[id^=\"google_ads\"]",
        "[id^=\"div-gpt-ad\"]", ".ad-slot", ".ad-banner", ".advertisement",
    ].joined(separator: ", ")

    private static let blockedDomains: [String] = [
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "2mdn.net",
        "app-measurement.com", "amazon-adsystem.com", "assoc-amazon.com", "connect.facebook.net",
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "casalemedia.com", "contextweb.com", "indexww.com", "bidswitch.net",
        "smartadserver.com", "gumgum.com", "sharethrough.com", "teads.tv", "yieldmo.com",
        "districtm.io", "3lift.com", "sonobi.com", "lijit.com", "sovrn.com", "spotxchange.com",
        "spotx.tv", "adform.net", "mathtag.com", "bluekai.com", "rlcdn.com", "crwdcntrl.net",
        "agkn.com", "tapad.com", "adroll.com", "stickyadstv.com", "taboola.com", "outbrain.com",
        "revcontent.com", "mgid.com", "zergnet.com", "scorecardresearch.com", "quantserve.com",
        "quantcount.com", "moatads.com", "hotjar.com", "mouseflow.com", "fullstory.com",
        "crazyegg.com", "mixpanel.com", "segment.com", "segment.io", "branch.io", "appsflyer.com",
        "adjust.com", "kochava.com", "chartbeat.com", "optimizely.com", "mparticle.com",
        "amplitude.com", "heapanalytics.com", "clarity.ms", "mc.yandex.ru", "adcolony.com",
        "applovin.com", "inmobi.com", "mopub.com", "chartboost.com", "vungle.com",
        "startappservice.com", "propellerads.com", "popads.net", "exoclick.com", "trafficjunky.com",
        "adsterra.com", "hilltopads.net", "juicyads.com", "onclkds.com",
    ]

    private static var compiledBlockPatterns: [NSRegularExpression] = []
    private static var isPatternsCompiled = false
    private static let lock = NSLock()

    /// Checks if a URL matches any blocked domain or EasyList pattern in memory.
    static func shouldBlock(urlString: String) -> Bool {
        guard isEnabled else { return false }
        
        let lowerUrl = urlString.lowercased()
        
        // Fast domain check
        for domain in blockedDomains {
            if lowerUrl.contains(domain) {
                return true
            }
        }
        
        // Thread-safe regex check
        lock.lock()
        if !isPatternsCompiled {
            compileInMemoryRules()
        }
        let patterns = compiledBlockPatterns
        lock.unlock()
        
        let nsString = urlString as NSString
        let range = NSRange(location: 0, length: nsString.length)
        for regex in patterns {
            if regex.firstMatch(in: urlString, options: [], range: range) != nil {
                return true
            }
        }
        
        return false
    }

    private static func compileInMemoryRules() {
        var patterns: [String] = []
        for url in filterListURLs {
            let fileURL = getLocalListPath(for: url)
            if let text = try? String(contentsOf: fileURL, encoding: .utf8) {
                let lines = text.components(separatedBy: CharacterSet.newlines)
                var count = 0
                for line in lines {
                    let trimmed = line.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
                    if trimmed.hasPrefix("||") {
                        let rule = trimmed.dropFirst(2)
                        var domain = rule.prefix(while: { $0 != "^" && $0 != "/" && $0 != "$" })
                        if domain.hasPrefix("*") {
                            domain = domain.dropFirst()
                        }
                        if domain.hasPrefix(".") {
                            domain = domain.dropFirst()
                        }
                        let domainStr = String(domain).trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
                        if !domainStr.isEmpty && isValidRuleDomain(domainStr) {
                            let escaped = domainStr.replacingOccurrences(of: ".", with: "\\.")
                            patterns.append("^https?://([^/]+\\.)?\(escaped)")
                            count += 1
                            if count > 2000 { break }
                        }
                    }
                }
            }
        }
        
        var compiled: [NSRegularExpression] = []
        for pattern in patterns {
            if let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) {
                compiled.append(regex)
            }
        }
        compiledBlockPatterns = compiled
        isPatternsCompiled = true
    }

    private static func isValidCSSSelector(_ selector: String) -> Bool {
        let trimmed = selector.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
        if trimmed.isEmpty { return false }
        
        // Block scriptlet injections
        if trimmed.contains("+js(") || trimmed.hasPrefix("+js(") { return false }
        
        // Block custom pseudo-classes / adblock extensions
        let invalidKeywords = [
            ":has(",
            ":contains(",
            ":has-text(",
            ":matches-css(",
            ":matches-css-before(",
            ":matches-css-after(",
            ":matches-attr(",
            ":matches-property(",
            ":xpath(",
            ":remove(",
            ":style(",
            ":-abp-",
            ":matches-path(",
            ":min-text-length(",
            "xpath("
        ]
        
        for keyword in invalidKeywords {
            if trimmed.lowercased().contains(keyword) {
                return false
            }
        }
        
        // Check for unbalanced parentheses/brackets in case it's a broken selector
        var parens = 0
        var brackets = 0
        for char in trimmed {
            if char == "(" { parens += 1 }
            else if char == ")" { parens -= 1 }
            else if char == "[" { brackets += 1 }
            else if char == "]" { brackets -= 1 }
            if parens < 0 || brackets < 0 { return false }
        }
        if parens != 0 || brackets != 0 { return false }
        
        return true
    }

    private static func isValidRuleDomain(_ domain: String) -> Bool {
        let allowedCharacters = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: ".-"))
        if domain.isEmpty { return false }
        if domain.hasPrefix(".") || domain.hasSuffix(".") || domain.hasPrefix("-") || domain.hasSuffix("-") {
            return false
        }
        if !domain.contains(".") && domain != "localhost" {
            return false
        }
        return domain.unicodeScalars.allSatisfy { allowedCharacters.contains($0) }
    }
}
