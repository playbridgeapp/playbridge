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

        // User cosmetic rules (element-picker "Block").
        let userText = userRulesText()
        if !userText.isEmpty {
            let uid = cacheIdentifier(for: userText)
            desired.insert(uid)
            if let cached = await lookup(store: store, identifier: uid) {
                lists.append(cached)
            } else {
                let json = await parseOffMain(userText)
                if let list = try? await compile(store: store, identifier: uid, json: json, sourceName: "user-cosmetic") {
                    lists.append(list)
                }
            }
        }

        // User blocked source domains (element-picker "Block source").
        let domJSON = userDomainsJSON()
        if !domJSON.isEmpty {
            let did = cacheIdentifier(for: domJSON)
            desired.insert(did)
            if let cached = await lookup(store: store, identifier: did) {
                lists.append(cached)
            } else if let list = try? await compile(store: store, identifier: did, json: domJSON, sourceName: "user-domains") {
                lists.append(list)
            }
        }

        // Built-in iframe/srcdoc ad networks (child-frame document blocking).
        let builtinJSON = builtinIframeAdJSON()
        if !builtinJSON.isEmpty {
            let bid = cacheIdentifier(for: builtinJSON)
            desired.insert(bid)
            if let cached = await lookup(store: store, identifier: bid) {
                lists.append(cached)
            } else if let list = try? await compile(store: store, identifier: bid, json: builtinJSON, sourceName: "builtin-iframe-ads") {
                lists.append(list)
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

        let userText = userRulesText()
        if !userText.isEmpty {
            let uid = cacheIdentifier(for: userText)
            desired.insert(uid)
            let json = await parseOffMain(userText)
            let userList = try await compile(store: store, identifier: uid, json: json, sourceName: "user-cosmetic")
            lists.append(userList)
        }

        let domJSON = userDomainsJSON()
        if !domJSON.isEmpty {
            let did = cacheIdentifier(for: domJSON)
            desired.insert(did)
            let domList = try await compile(store: store, identifier: did, json: domJSON, sourceName: "user-domains")
            lists.append(domList)
        }

        let builtinJSON = builtinIframeAdJSON()
        if !builtinJSON.isEmpty {
            let bid = cacheIdentifier(for: builtinJSON)
            desired.insert(bid)
            let builtinList = try await compile(store: store, identifier: bid, json: builtinJSON, sourceName: "builtin-iframe-ads")
            lists.append(builtinList)
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

    // MARK: - User element-block rules (in-browser element picker)

    struct UserCosmeticRule: Codable, Identifiable {
        var id = UUID()
        var domain: String       // "" = all sites
        var selector: String
        var addedAt: Date
    }

    private static let userRulesKey = "pb_user_cosmetic_rules"

    static func userRules() -> [UserCosmeticRule] {
        guard let data = UserDefaults.standard.data(forKey: userRulesKey),
              let rules = try? JSONDecoder().decode([UserCosmeticRule].self, from: data) else { return [] }
        return rules
    }

    private static func saveUserRules(_ rules: [UserCosmeticRule]) {
        if let data = try? JSONEncoder().encode(rules) { UserDefaults.standard.set(data, forKey: userRulesKey) }
    }

    @discardableResult
    static func addUserRule(domain: String, selector: String) -> Bool {
        let sel = selector.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sel.isEmpty, isValidCSSSelector(sel) else { return false }
        let d = domain.lowercased().trimmingCharacters(in: .whitespaces)
        var rules = userRules()
        if rules.contains(where: { $0.domain == d && $0.selector == sel }) { return true }
        rules.insert(UserCosmeticRule(domain: d, selector: sel, addedAt: Date()), at: 0)
        saveUserRules(rules)
        return true
    }

    static func removeUserRule(_ id: UUID) { saveUserRules(userRules().filter { $0.id != id }) }

    // User-blocked resource domains (from "Block source" in the picker). These block the
    // ad's image/iframe host at the network layer — robust against random element IDs.
    private static let userDomainsKey = "pb_user_blocked_domains"

    static func userBlockedDomains() -> [String] {
        UserDefaults.standard.stringArray(forKey: userDomainsKey) ?? []
    }

    @discardableResult
    static func addUserBlockedDomain(_ host: String) -> Bool {
        let h = host.lowercased().trimmingCharacters(in: .whitespaces)
        guard isValidRuleDomain(h) else { return false }
        var list = userBlockedDomains()
        if list.contains(h) { return true }
        list.insert(h, at: 0)
        UserDefaults.standard.set(list, forKey: userDomainsKey)
        return true
    }

    static func removeUserBlockedDomain(_ host: String) {
        UserDefaults.standard.set(userBlockedDomains().filter { $0 != host }, forKey: userDomainsKey)
    }

    /// User cosmetic rules rendered as EasyList lines for the parser.
    private static func userRulesText() -> String {
        userRules().map { $0.domain.isEmpty ? "##\($0.selector)" : "\($0.domain)##\($0.selector)" }
            .joined(separator: "\n")
    }

    /// Blocked source domains compiled directly so we can also block them as
    /// **child-frame documents** (ad iframes) — which the normal rules skip to avoid
    /// breaking top-level navigation. Top-frame navigation to the domain stays allowed.
    /// Known ad-network domains blocked aggressively — including as child-frame
    /// documents — so iframe/srcdoc ads (TrafficJunky/ExoClick/adtng/etc.) are removed
    /// by default, which plain EasyList rules can't do (they skip the document type).
    private static let iframeAdDomains = [
        "adtng.com", "trafficjunky.net", "trafficjunky.com", "trafficfactory.biz",
        "exoclick.com", "exosrv.com", "exdynsrv.com", "realsrv.com", "magsrv.com",
        "tsyndicate.com", "trafficstars.com", "juicyads.com", "ero-advertising.com",
        "plugrush.com", "popcash.net", "popads.net", "clickadu.com", "adsterra.com",
        "doubleclick.net", "googlesyndication.com",
    ]

    private static func userDomainsJSON() -> String { domainsBlockJSON(userBlockedDomains()) }
    private static func builtinIframeAdJSON() -> String { domainsBlockJSON(iframeAdDomains) }

    /// Blocks each domain as sub-resources (any frame) AND as child-frame documents
    /// (ad iframes), but never the top frame — so navigating to the domain still works.
    private static func domainsBlockJSON(_ domains: [String]) -> String {
        guard !domains.isEmpty else { return "" }
        var rules: [[String: Any]] = []
        for d in domains {
            let escaped = d.replacingOccurrences(of: ".", with: "\\.")
            let filter = "^https?://([^/]+\\.)?\(escaped)"
            rules.append(["trigger": ["url-filter": filter, "resource-type": blockResourceTypes],
                          "action": ["type": "block"]])
            rules.append(["trigger": ["url-filter": filter, "resource-type": ["document"], "load-context": ["child-frame"]],
                          "action": ["type": "block"]])
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rules),
              let json = String(data: data, encoding: .utf8) else { return "" }
        return json
    }

    /// Injected on demand to let the user tap an element to block (uBlock-style picker).
    /// Hides the element immediately and reports its selector to native for persistence.
    static let elementPickerJS = #"""
    (function () {
      if (window.__pb_picker) return;
      window.__pb_picker = true;

      var target = null, previewing = false, previewEls = [], currentHosts = [];

      var hl = document.createElement('div');
      hl.style.cssText = 'position:fixed;z-index:2147483646;pointer-events:none;background:rgba(85,101,242,0.28);border:2px solid #5565F2;border-radius:3px;';
      document.documentElement.appendChild(hl);

      var panel = document.createElement('div');
      panel.style.cssText = 'position:fixed;left:8px;right:8px;bottom:8px;z-index:2147483647;background:#181241;color:#E7E2FF;font:13px -apple-system,Helvetica,Arial;padding:12px;border-radius:14px;box-shadow:0 4px 24px rgba(0,0,0,.5);';
      panel.innerHTML =
        '<div id="pbsel" style="font:600 12px ui-monospace,Menlo,monospace;color:#B0A8D8;word-break:break-all;margin-bottom:4px;min-height:16px;">Tap an element to block</div>'
        + '<div id="pbcount" style="font-size:11px;color:#B0A8D8;margin-bottom:8px;"></div>'
        + '<div id="pbsrc" style="font-size:11px;color:#FF8A80;word-break:break-all;margin-bottom:10px;"></div>'
        + '<div style="display:flex;gap:8px;">'
        + '<button id="pbup" style="flex:1;padding:10px;border:none;border-radius:10px;background:#241D54;color:#E7E2FF;font-weight:600;">Up</button>'
        + '<button id="pbdown" style="flex:1;padding:10px;border:none;border-radius:10px;background:#241D54;color:#E7E2FF;font-weight:600;">Down</button>'
        + '<button id="pbprev" style="flex:1;padding:10px;border:none;border-radius:10px;background:#241D54;color:#E7E2FF;font-weight:600;">Preview</button>'
        + '</div>'
        + '<div style="display:flex;gap:8px;margin-top:8px;">'
        + '<button id="pbcancel" style="flex:1;padding:11px;border:none;border-radius:10px;background:#3A2330;color:#FF6B6B;font-weight:700;">Cancel</button>'
        + '<button id="pbblock" style="flex:1;padding:11px;border:none;border-radius:10px;background:#5565F2;color:#fff;font-weight:700;">Block</button>'
        + '<button id="pbsource" style="flex:1;padding:11px;border:none;border-radius:10px;background:#3A2330;color:#FF8A80;font-weight:700;">Block source</button>'
        + '</div>';
      document.documentElement.appendChild(panel);

      function isUI(el){ return el===hl || el===panel || (el && panel.contains(el)); }
      function elAt(e){ var t=e.changedTouches?e.changedTouches[0]:(e.touches?e.touches[0]:e); return document.elementFromPoint(t.clientX, t.clientY); }

      // Simple, WebKit-compatible selector: #id, or tag.class.class, else tag.
      function sel(el){
        if(!el || el.nodeType!==1) return '';
        if(el.id) return '#'+CSS.escape(el.id);
        var tag = el.tagName.toLowerCase();
        if(tag==='body' || tag==='html') return tag;
        var cls = (typeof el.className==='string') ? el.className.trim().split(/\s+/).filter(function(c){return c && c.length<40;}).slice(0,3) : [];
        return cls.length ? tag+'.'+cls.map(function(c){return CSS.escape(c);}).join('.') : tag;
      }

      // Resource hosts (image / background / iframe URLs) inside the element — used to
      // block the ad's source domain, which survives random element IDs.
      function resourceHosts(el){
        var out = [];
        function add(u){ if(!u) return; try{ var h=new URL(u, location.href).hostname; if(h && h!==location.hostname) out.push(h); }catch(_){} }
        function urlsIn(text){ if(!text || text.indexOf('http')<0) return; var re=/https?:\/\/[^\s"'<>)\\]+/g, mm; while((mm=re.exec(text))){ add(mm[0]); } }
        function scan(node){
          if(!node || node.nodeType!==1) return;
          if(node.currentSrc) add(node.currentSrc);
          try { if(node.src) add(node.src); } catch(_){}
          if(node.attributes){ for(var a=0;a<node.attributes.length;a++){ urlsIn(node.attributes[a].value); } }
          try { var bg=getComputedStyle(node).backgroundImage; if(bg && bg!=='none'){ var m=bg.match(/url\(["']?([^"')]+)["']?\)/); if(m) add(m[1]); } } catch(_){}
          // Descend into same-origin (srcdoc) ad iframes to find the inner ad source.
          if(node.tagName && node.tagName.toLowerCase()==='iframe'){
            try { var d=node.contentDocument; if(d){ var sub=d.querySelectorAll('*'); for(var j=0;j<sub.length && j<600;j++){ scan(sub[j]); } } } catch(_){}
          }
        }
        scan(el);
        var kids = el.querySelectorAll('*'); for(var i=0;i<kids.length && i<500;i++){ scan(kids[i]); }
        return out.filter(function(v,i){ return out.indexOf(v)===i; }).slice(0,8);
      }

      function updateHL(el){ if(!el) return; var r=el.getBoundingClientRect(); hl.style.top=r.top+'px'; hl.style.left=r.left+'px'; hl.style.width=r.width+'px'; hl.style.height=r.height+'px'; }
      function clearPreview(){ previewEls.forEach(function(el){ el.style.outline=''; el.style.outlineOffset=''; }); previewEls=[]; }
      function applyPreview(){
        clearPreview();
        if(!previewing || !target) return;
        try { document.querySelectorAll(sel(target)).forEach(function(el){ if(!isUI(el)){ el.style.outline='2px dashed #FF6B6B'; el.style.outlineOffset='-2px'; previewEls.push(el); } }); } catch(_){}
      }
      function refresh(){
        if(!target) return;
        var s = sel(target);
        document.getElementById('pbsel').textContent = s;
        var n = 0; try { n = document.querySelectorAll(s).length; } catch(_){}
        document.getElementById('pbcount').textContent = n>1 ? ('matches '+n+' elements') : '';
        currentHosts = resourceHosts(target);
        var srcEl = document.getElementById('pbsrc');
        srcEl.textContent = currentHosts.length ? ('Sources: '+currentHosts.join(', ')) : 'No external source found';
        var srcBtn = document.getElementById('pbsource');
        if(srcBtn){ srcBtn.style.opacity = currentHosts.length ? '1' : '0.4'; }
        updateHL(target);
        if(previewing) applyPreview();
      }
      function cleanup(){
        window.__pb_picker=false; clearPreview(); hl.remove(); panel.remove();
        document.removeEventListener('touchmove',hover,true); document.removeEventListener('mousemove',hover,true);
        document.removeEventListener('click',firstPick,true); document.removeEventListener('touchend',firstPick,true);
      }
      function hover(e){ if(target) return; var el=elAt(e); if(el && !isUI(el)) updateHL(el); }
      function firstPick(e){
        var el=elAt(e); if(!el || isUI(el)) return;
        e.preventDefault(); e.stopPropagation();
        target = el;
        document.removeEventListener('click',firstPick,true); document.removeEventListener('touchend',firstPick,true);
        refresh();
      }

      document.addEventListener('touchmove',hover,true);
      document.addEventListener('mousemove',hover,true);
      document.addEventListener('click',firstPick,true);
      document.addEventListener('touchend',firstPick,true);

      panel.addEventListener('click', function(e){
        var id = e.target && e.target.id; if(!id) return;
        e.preventDefault(); e.stopPropagation();
        if(id==='pbup'){ if(target && target.parentElement && target.parentElement.tagName!=='HTML'){ target=target.parentElement; refresh(); } }
        else if(id==='pbdown'){ if(target && target.firstElementChild){ target=target.firstElementChild; refresh(); } }
        else if(id==='pbprev'){ previewing=!previewing; e.target.style.background = previewing ? '#5565F2' : '#241D54'; applyPreview(); }
        else if(id==='pbcancel'){ cleanup(); }
        else if(id==='pbblock'){
          // Cosmetic: hide the selected element by selector.
          if(!target){ cleanup(); return; }
          var s = sel(target); clearPreview();
          try{ document.querySelectorAll(s).forEach(function(el){ if(!isUI(el)) el.style.setProperty('display','none','important'); }); }catch(_){}
          try{ window.webkit.messageHandlers.playbridge.postMessage({type:'pickedElement', selector:s, host:location.hostname}); }catch(_){}
          cleanup();
        }
        else if(id==='pbsource'){
          // Network: block every source domain found in the element.
          if(!currentHosts || !currentHosts.length){ return; }
          try{ window.webkit.messageHandlers.playbridge.postMessage({type:'pickedResources', hosts:currentHosts}); }catch(_){}
          cleanup();
        }
      }, true);
    })();
    """#

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
