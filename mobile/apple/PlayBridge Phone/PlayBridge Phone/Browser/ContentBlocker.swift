import Foundation
import WebKit
import CryptoKit

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
    /// Identifiers are derived from each list's content hash, so a list whose
    /// contents changed recompiles automatically instead of serving a stale
    /// cached compilation.
    @MainActor
    static func compileAll() async -> [WKContentRuleList] {
        guard let store = WKContentRuleListStore.default() else { return [] }
        var lists: [WKContentRuleList] = []
        var desired: Set<String> = []
        var compiledAnyCustom = false

        for url in filterListURLs {
            // Read + hash off the main thread (lists can be multiple MB).
            guard let loaded = await loadList(getLocalListPath(for: url)) else { continue }
            desired.insert(loaded.id)
            if let cached = await lookup(store: store, identifier: loaded.id) {
                lists.append(cached); compiledAnyCustom = true; continue
            }
            // Parse off the main thread; only the WebKit compile (async) runs here.
            let json = await parseOffMain(loaded.text)
            if let list = try? await compile(store: store, identifier: loaded.id, json: json, sourceName: url.lastPathComponent) {
                lists.append(list); compiledAnyCustom = true
            }
        }

        if !compiledAnyCustom {
            let id = "playbridge-adblock-curated"
            desired.insert(id)
            if let cached = await lookup(store: store, identifier: id) {
                lists.append(cached)
            } else {
                let json = await Task.detached(priority: .utility) { makeCuratedRulesJSON() }.value
                if let list = try? await compile(store: store, identifier: id, json: json, sourceName: "curated") {
                    lists.append(list)
                }
            }
        }

        // Always-on extra rules from the bundled/hosted list (small; safe to read here).
        if let extra = extraListText(), !extra.isEmpty {
            let supId = cacheIdentifier(for: extra)
            desired.insert(supId)
            if let cached = await lookup(store: store, identifier: supId) {
                lists.append(cached)
            } else {
                let json = await parseOffMain(extra)
                if let list = try? await compile(store: store, identifier: supId, json: json, sourceName: "playbridge-extra") {
                    lists.append(list)
                }
            }
        }

        await pruneStaleRuleLists(store: store, keeping: desired)
        return lists
    }

    /// Forces compilation of all downloaded rules, ignoring any cached compilation.
    /// Throws on the first compilation failure so the UI can surface it.
    @MainActor
    static func forceCompileAll() async throws -> [WKContentRuleList] {
        lock.lock()
        isPatternsCompiled = false
        compiledBlockPatterns = []
        lock.unlock()

        guard let store = WKContentRuleListStore.default() else { return [] }

        var lists: [WKContentRuleList] = []
        var desired: Set<String> = []
        var compiledAnyCustom = false

        for url in filterListURLs {
            guard let loaded = await loadList(getLocalListPath(for: url)) else { continue }
            desired.insert(loaded.id)
            // Force a fresh compile rather than trusting any cached list.
            let json = await parseOffMain(loaded.text)
            let list = try await compile(store: store, identifier: loaded.id, json: json, sourceName: url.lastPathComponent)
            lists.append(list)
            compiledAnyCustom = true
        }

        if !compiledAnyCustom {
            let id = "playbridge-adblock-curated"
            desired.insert(id)
            let json = await Task.detached(priority: .utility) { makeCuratedRulesJSON() }.value
            let list = try await compile(store: store, identifier: id, json: json, sourceName: "curated")
            lists.append(list)
        }

        // Always-on extra rules from the bundled/hosted list.
        if let extra = extraListText(), !extra.isEmpty {
            let supId = cacheIdentifier(for: extra)
            desired.insert(supId)
            let json = await parseOffMain(extra)
            let supList = try await compile(store: store, identifier: supId, json: json, sourceName: "playbridge-extra")
            lists.append(supList)
        }

        await pruneStaleRuleLists(store: store, keeping: desired)
        return lists
    }

    @MainActor
    private static func lookup(store: WKContentRuleListStore, identifier: String) async -> WKContentRuleList? {
        await withCheckedContinuation { cont in
            store.lookUpContentRuleList(forIdentifier: identifier) { list, _ in
                cont.resume(returning: list)
            }
        }
    }

    @MainActor
    private static func compile(store: WKContentRuleListStore, identifier: String, json: String, sourceName: String) async throws -> WKContentRuleList {
        return try await withCheckedThrowingContinuation { cont in
            store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, error in
                if let error {
                    let nsError = error as NSError
                    Task { @MainActor in
                        let details = "List \(sourceName) [\(identifier)] compile failed: \(nsError.localizedDescription) — \(nsError.userInfo)"
                        ContentBlocker.lastCompilationError = details
                        print("ContentBlocker: \(details)")
                    }
                    cont.resume(throwing: error)
                } else if let list {
                    cont.resume(returning: list)
                } else {
                    cont.resume(throwing: NSError(domain: "ContentBlocker", code: -1))
                }
            }
        }
    }

    /// Reads a list file and computes its cache identifier off the main thread.
    private static func loadList(_ fileURL: URL) async -> (text: String, id: String)? {
        await Task.detached(priority: .utility) {
            guard let text = try? String(contentsOf: fileURL, encoding: .utf8), !text.isEmpty else { return nil }
            return (text, cacheIdentifier(for: text))
        }.value
    }

    /// Parses EasyList text into WebKit JSON off the main thread (CPU-heavy).
    private static func parseOffMain(_ text: String) async -> String {
        await Task.detached(priority: .utility) { parseListTextToJSON(text) }.value
    }

    /// Removes any of our previously-compiled rule lists that are no longer in use
    /// (e.g. after a list's contents changed, or a custom list was deleted). This
    /// prevents the store from accumulating stale compilations indefinitely.
    @MainActor
    private static func pruneStaleRuleLists(store: WKContentRuleListStore, keeping: Set<String>) async {
        let identifiers: [String] = await withCheckedContinuation { cont in
            store.getAvailableContentRuleListIdentifiers { ids in cont.resume(returning: ids ?? []) }
        }
        for id in identifiers where id.hasPrefix("playbridge-adblock") && !keeping.contains(id) {
            store.removeContentRuleList(forIdentifier: id) { _ in }
        }
    }

    /// Bump when the rule-generation logic changes so that existing cached
    /// compilations are invalidated and recompiled with the new parser.
    private static let rulesetVersion = "7"

    /// Every WKContentRuleList resource type except `document`, so a block rule can
    /// never cancel a page the user navigated to — only its sub-resources (scripts,
    /// images, trackers, media, pop-ups). Prevents "blocked by content blocker" (104)
    /// failures on normal navigation.
    private static let blockResourceTypes = ["image", "style-sheet", "script", "font", "raw", "svg-document", "media", "popup"]

    /// Stable identifier derived from a list's content (plus the parser version),
    /// so changed content — or a parser upgrade — maps to a fresh compilation.
    private static func cacheIdentifier(for text: String) -> String {
        let digest = SHA256.hash(data: Data((rulesetVersion + "\n" + text).utf8))
        let hex = digest.prefix(8).map { String(format: "%02x", $0) }.joined()
        return "playbridge-adblock-\(hex)"
    }

    // MARK: - Download & Compile Manager

    /// Download a list from a URL and save it to the local Documents directory.
    static func download(url: URL) async throws {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("PlayBridge", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)

        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw NSError(domain: "ContentBlocker", code: http.statusCode,
                          userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode) for \(url.lastPathComponent)"])
        }
        guard let text = String(data: data, encoding: .utf8) else {
            throw NSError(domain: "ContentBlocker", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to decode list"])
        }
        // Guard against saving an HTML error/redirect page as if it were a filter list.
        let head = text.prefix(4096).lowercased()
        if head.contains("<!doctype html") || head.contains("<html") {
            throw NSError(domain: "ContentBlocker", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "Response was not a filter list (got HTML) for \(url.lastPathComponent)"])
        }
        let fileURL = getLocalListPath(for: url)
        try text.write(to: fileURL, atomically: true, encoding: .utf8)
    }

    /// Ensures all configured lists are present on disk, downloading any that are
    /// missing or older than `maxAge`. Safe to call on every launch; failures are
    /// ignored so a flaky network never blocks the browser from starting.
    static func ensureListsDownloaded(maxAge: TimeInterval = 24 * 60 * 60) async {
        var urls = filterListURLs
        if let extra = remoteExtraListURL { urls.append(extra) }
        await withTaskGroup(of: Void.self) { group in
            for url in urls {
                group.addTask {
                    let fileURL = getLocalListPath(for: url)
                    let fm = FileManager.default
                    var needsDownload = !fm.fileExists(atPath: fileURL.path)
                    if !needsDownload,
                       let attrs = try? fm.attributesOfItem(atPath: fileURL.path),
                       let modDate = attrs[.modificationDate] as? Date {
                        needsDownload = Date().timeIntervalSince(modDate) > maxAge
                    }
                    if needsDownload {
                        try? await download(url: url)
                    }
                }
            }
        }
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
        var exceptionRules: [[String: Any]] = []
        var cosmeticSelectors: [String] = []
        var ruleCount = 0

        let lines = text.components(separatedBy: CharacterSet.newlines)
        for line in lines {
            let trimmed = line.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines)
            if trimmed.isEmpty || trimmed.hasPrefix("!") || trimmed.hasPrefix("[") { continue }
            
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
                                    // WebKit only matches subdomains when the entry is
                                    // prefixed with `*`; EasyList domain options are
                                    // subdomain-inclusive, so add the prefix.
                                    if isNegated {
                                        negativeDomains.append("*" + domainStr)
                                    } else {
                                        positiveDomains.append("*" + domainStr)
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
            
            // Skip cosmetic-exception / extended-cosmetic / scriptlet syntaxes
            // that have no WebKit content-rule equivalent.
            if trimmed.contains("#@#") || trimmed.contains("#?#")
                || trimmed.contains("#$#") || trimmed.contains("#%#") {
                continue
            }

            // 2. Network rules — block rules and `@@` exceptions, including
            // path/substring filters, not just domain-anchored ones.
            let isException = trimmed.hasPrefix("@@")
            var patternPart = Substring(trimmed)
            if isException { patternPart = patternPart.dropFirst(2) }

            // EasyList regex-literal filter: /pattern/ optionally followed by $options.
            if patternPart.hasPrefix("/"),
               let lastSlash = patternPart.range(of: "/", options: .backwards),
               lastSlash.lowerBound != patternPart.startIndex {
                let optsSeg = patternPart[lastSlash.upperBound...]
                if optsSeg.isEmpty || optsSeg.hasPrefix("$") {
                    let inner = String(patternPart[patternPart.index(after: patternPart.startIndex)..<lastSlash.lowerBound])
                    let o = parseOptions(optsSeg.hasPrefix("$") ? optsSeg.dropFirst() : Substring(""))
                    // Only accept "safe" literal-ish regexes; complex ones risk failing
                    // the entire list compilation in WebKit.
                    let safe = !inner.isEmpty && inner.allSatisfy {
                        $0.isLetter || $0.isNumber || "._-/".contains($0)
                    }
                    if !o.skip && safe, (try? NSRegularExpression(pattern: inner)) != nil {
                        var t: [String: Any] = ["url-filter": inner]
                        if !isException { t["resource-type"] = blockResourceTypes }
                        if let lt = o.loadType { t["load-type"] = lt }
                        if !o.ifDomain.isEmpty { t["if-domain"] = o.ifDomain }
                        else if !o.unlessDomain.isEmpty { t["unless-domain"] = o.unlessDomain }
                        let r: [String: Any] = ["trigger": t,
                                                "action": ["type": isException ? "ignore-previous-rules" : "block"]]
                        if isException { exceptionRules.append(r) }
                        else {
                            rules.append(r); ruleCount += 1
                            if ruleCount > 60000 { break }
                        }
                    }
                    continue
                }
            }

            // Separate EasyList options (everything after the last `$`).
            var optionsPart = Substring("")
            if let dollar = patternPart.lastIndex(of: "$") {
                optionsPart = patternPart[patternPart.index(after: dollar)...]
                patternPart = patternPart[..<dollar]
            }

            let opts = parseOptions(optionsPart)
            if opts.skip { continue }

            let netRules = makeNetworkRules(pattern: patternPart, options: opts, block: !isException)
            if !netRules.isEmpty {
                if isException {
                    exceptionRules.append(contentsOf: netRules)
                } else {
                    rules.append(contentsOf: netRules)
                    ruleCount += netRules.count
                    if ruleCount > 60000 { break }
                }
            }
            continue
        }

        // Exceptions go after all block rules so `ignore-previous-rules` can
        // override the blocks they whitelist.
        rules.append(contentsOf: exceptionRules)
        
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

    /// Parsed subset of EasyList rule options that WebKit can represent.
    private struct NetOptions {
        var loadType: [String]? = nil      // nil = both first- and third-party
        var ifDomain: [String] = []
        var unlessDomain: [String] = []
        var skip = false                   // rule uses an option we can't represent
    }

    private static func parseOptions(_ optStr: Substring) -> NetOptions {
        var o = NetOptions()
        if optStr.isEmpty { return o }
        for raw in optStr.split(separator: ",") {
            let opt = raw.trimmingCharacters(in: .whitespaces).lowercased()
            if opt.isEmpty { continue }
            if opt == "third-party" || opt == "3p" {
                o.loadType = ["third-party"]
            } else if opt == "~third-party" || opt == "first-party" || opt == "1p" || opt == "~3p" {
                o.loadType = ["first-party"]
            } else if opt.hasPrefix("domain=") {
                let val = opt.dropFirst("domain=".count)
                for d in val.split(separator: "|") {
                    var dd = d
                    var neg = false
                    if dd.hasPrefix("~") { neg = true; dd = dd.dropFirst() }
                    if dd.hasPrefix("*") { dd = dd.dropFirst() }
                    if dd.hasPrefix(".") { dd = dd.dropFirst() }
                    let ds = String(dd)
                    if isValidRuleDomain(ds) {
                        if neg { o.unlessDomain.append("*" + ds) } else { o.ifDomain.append("*" + ds) }
                    }
                }
            } else if opt.hasPrefix("csp") || opt.hasPrefix("redirect") || opt.hasPrefix("rewrite")
                || opt.hasPrefix("removeparam") || opt.hasPrefix("replace") || opt.hasPrefix("header")
                || opt.hasPrefix("cookie") || opt.hasPrefix("permissions") || opt == "inline-script"
                || opt == "inline-font" || opt == "generichide" || opt == "ghide" || opt == "elemhide"
                || opt == "ehide" || opt == "specifichide" || opt == "genericblock" || opt == "empty"
                || opt == "mp4" {
                o.skip = true
            }
            // Other options (resource-type filters, important, match-case, negated
            // types, etc.) are ignored: the rule is applied to all resource types.
        }
        return o
    }

    /// Translates a single EasyList network pattern (the part before `$`) into one
    /// or more WebKit content-blocker rules. Returns [] if it can't be represented.
    private static func makeNetworkRules(pattern rawPattern: Substring, options: NetOptions, block: Bool) -> [[String: Any]] {
        let actionType = block ? "block" : "ignore-previous-rules"

        func trigger(_ urlFilter: String) -> [String: Any]? {
            guard (try? NSRegularExpression(pattern: urlFilter)) != nil else { return nil }
            var t: [String: Any] = ["url-filter": urlFilter]
            if block { t["resource-type"] = blockResourceTypes }
            if let lt = options.loadType { t["load-type"] = lt }
            if !options.ifDomain.isEmpty { t["if-domain"] = options.ifDomain }
            else if !options.unlessDomain.isEmpty { t["unless-domain"] = options.unlessDomain }
            return ["trigger": t, "action": ["type": actionType]]
        }

        var p = rawPattern
        if p.isEmpty { return [] }

        var domainAnchor = false
        var anchorStart = false
        var anchorEnd = false
        if p.hasPrefix("||") { domainAnchor = true; p = p.dropFirst(2) }
        else if p.hasPrefix("|") { anchorStart = true; p = p.dropFirst() }
        if p.hasSuffix("|") { anchorEnd = true; p = p.dropLast() }
        while p.hasSuffix("^") { p = p.dropLast() }
        if p.isEmpty { return [] }

        // Pure domain anchor (||domain.com^): boundary-anchored block covering BOTH
        // first- and third-party (unless options say otherwise). Two rules handle
        // "domain + path/port" and "bare domain".
        if domainAnchor, !p.contains("/"), !p.contains("*"), !p.contains("^") {
            let ds = String(p).trimmingCharacters(in: .whitespaces)
            guard !ds.isEmpty, isValidRuleDomain(ds) else { return [] }
            let escaped = ds.replacingOccurrences(of: ".", with: "\\.")
            var out: [[String: Any]] = []
            if let r = trigger("^https?://([^/]+\\.)?\(escaped)[:/?]") { out.append(r) }
            if let r = trigger("^https?://([^/]+\\.)?\(escaped)$") { out.append(r) }
            return out
        }

        // General pattern -> WebKit url-filter regex.
        var body = ""
        for ch in p {
            switch ch {
            case "*": body += ".*"
            case "^": body += "[^a-zA-Z0-9._%-]"
            case ".", "?", "+", "(", ")", "[", "]", "{", "}", "$", "\\", "|":
                body += "\\" + String(ch)
            default: body += String(ch)
            }
        }
        // Refuse rules with no concrete token (they would match almost everything).
        guard body.contains(where: { $0.isLetter || $0.isNumber }) else { return [] }

        var urlFilter = body
        if domainAnchor { urlFilter = "^https?://([^/]+\\.)?" + body }
        else if anchorStart { urlFilter = "^" + body }
        if anchorEnd { urlFilter += "$" }

        if let r = trigger(urlFilter) { return [r] }
        return []
    }

    /// Generic ad/banner keyword patterns blocked on BOTH first- and third-party
    /// requests, independent of any downloaded list. These are compound, ad-specific
    /// tokens (low false-positive risk) plus Flash, covering banner-image ad units
    /// that domain-anchored rules miss when served from a site's own domain.
    /// Single source of truth for the custom rules: one hosted file on the site
    /// (Cloudflare). Editing the file at this URL updates the rules for all users
    /// without an app release.
    static let remoteExtraListURL = URL(string: "https://playbridge.app/filters/playbridge-extra.txt")

    /// The custom filter list (EasyList syntax), as downloaded from
    /// `remoteExtraListURL`. Until the first successful download (e.g. a fresh
    /// install while offline) there are no custom rules and the in-code curated
    /// fallback applies. Recompiled automatically whenever the file changes.
    private static func extraListText() -> String? {
        guard let remote = remoteExtraListURL else { return nil }
        let local = getLocalListPath(for: remote)
        guard let text = try? String(contentsOf: local, encoding: .utf8), !text.isEmpty else { return nil }
        return text
    }

    private static func makeCuratedRulesJSON() -> String {
        var rules: [[String: Any]] = blockedDomains.map { domain in
            let escaped = domain.replacingOccurrences(of: ".", with: "\\.")
            return [
                "trigger": ["url-filter": "^https?://([^/]+\\.)?\(escaped)", "load-type": ["third-party"], "resource-type": blockResourceTypes],
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
    private static var isCompilingPatterns = false
    private static let lock = NSLock()

    /// Checks if a URL matches any blocked domain (fast) or a precompiled EasyList
    /// pattern. The heavy regex compilation runs once on a background thread; until
    /// it's ready only the fast domain check is used, so the caller (often the main
    /// thread, via video detection) never blocks.
    static func shouldBlock(urlString: String) -> Bool {
        guard isEnabled else { return false }

        let lowerUrl = urlString.lowercased()
        for domain in blockedDomains where lowerUrl.contains(domain) { return true }

        lock.lock()
        let ready = isPatternsCompiled
        let patterns = compiledBlockPatterns
        lock.unlock()

        guard ready else {
            warmInMemoryRulesIfNeeded()
            return false
        }

        let nsString = urlString as NSString
        let range = NSRange(location: 0, length: nsString.length)
        for regex in patterns where regex.firstMatch(in: urlString, options: [], range: range) != nil {
            return true
        }
        return false
    }

    /// Starts background compilation of the in-memory patterns exactly once.
    private static func warmInMemoryRulesIfNeeded() {
        lock.lock()
        if isPatternsCompiled || isCompilingPatterns { lock.unlock(); return }
        isCompilingPatterns = true
        lock.unlock()
        Task.detached(priority: .utility) { compileInMemoryRules() }
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
        lock.lock()
        compiledBlockPatterns = compiled
        isPatternsCompiled = true
        isCompilingPatterns = false
        lock.unlock()
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
