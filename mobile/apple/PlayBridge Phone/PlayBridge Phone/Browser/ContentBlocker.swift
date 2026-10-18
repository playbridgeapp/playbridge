// SPDX-License-Identifier: MPL-2.0
import Foundation
import WebKit
import CryptoKit

/// One native engine supplies automatic WebKit rules, navigation and detector matches.
/// Explicit picker rules retain separate lists so site exemptions never disable them.
enum ContentBlocker {
    static let identifier = "playbridge-adblock-v2"
    private static let enabledKey = "pb_adblock_enabled"
    @MainActor static var lastCompilationError: String?
    struct CompilationMetrics {
        var engineMS: Double
        var conversionMS: Double
        var webKitMS: Double
        var reusedChunks: Int
        var compiledChunks: Int
    }
    @MainActor static var lastCompilationMetrics: CompilationMetrics?
    static var isEnabled: Bool {
        get { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }
    static var filterListURLs: [URL] {
        get {
            ContentBlockerListSettings.load(defaults: .standard,
                cacheDirectory: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!)
                .compactMap(URL.init(string:))
        }
        set { UserDefaults.standard.set(newValue.map(\.absoluteString), forKey: ContentBlockerListSettings.key) }
    }
    /// Saved lists that are switched on; only these are downloaded and compiled.
    static var enabledFilterListURLs: [URL] {
        ContentBlockerListSettings.enabled(defaults: .standard,
            cacheDirectory: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!)
            .compactMap(URL.init(string:))
    }
    static func isListEnabled(url: URL) -> Bool {
        !ContentBlockerListSettings.disabled(defaults: .standard).contains(url.absoluteString)
    }
    static func setListEnabled(_ enabled: Bool, url: URL) {
        ContentBlockerListSettings.setEnabled(enabled, url: url.absoluteString, defaults: .standard)
    }
    static var defaultFilterListURLs: [URL] { ContentBlockerListSettings.defaultURLs.compactMap(URL.init(string:)) }
    private static let blockResourceTypes = ["image", "style-sheet", "script", "font", "raw", "svg-document", "media", "popup"]
    @MainActor private static var generation = UUID()
    @MainActor private static var activeLists: [WKContentRuleList] = []
    @MainActor private static var popupRules = NavigationAdRules()

    /// Prior lists remain installed while all replacement chunks compile. A failed or
    /// superseded generation never publishes a partial set and never prunes the store.
    @MainActor static func invalidateGeneration() { generation = UUID() }

    @MainActor static func compileAll() async -> [WKContentRuleList] {
        do { return try await rebuild() }
        catch { return activeLists }
    }
    @MainActor static func forceCompileAll() async throws -> [WKContentRuleList] { try await rebuild() }

    /// Attach a small bundled fallback before waiting for the full engine/compile.
    @MainActor static func bootstrapRules() async -> [WKContentRuleList] {
        guard activeLists.isEmpty, let store = WKContentRuleListStore.default() else { return activeLists }
        let token = generation
        let json = domainsBlockJSON(blockedDomains)
        let id = "playbridge-adblock-bootstrap-" + cacheIdentifier(for: json)
        if let list = try? await cachedCompile(store: store, identifier: id, json: json),
           generation == token, activeLists.isEmpty { activeLists = [list] }
        return activeLists
    }

    @MainActor private static func rebuild() async throws -> [WKContentRuleList] {
        let token = UUID()
        generation = token
        do {
            guard let store = WKContentRuleListStore.default() else { return activeLists }
            if activeLists.isEmpty { _ = await bootstrapRules() }
            guard generation == token else { return activeLists }
            let urls = enabledFilterListURLs
            let engineStart = Date()
            let (engine, texts) = try await AdblockEngineWorker.shared.load(files: urls.map(getLocalListPath))
            guard generation == token else { return activeLists }
            let engineMS = Date().timeIntervalSince(engineStart) * 1000
            AdblockSnapshot.shared.publish(engine)
            let popups = await Task.detached(priority: .utility) { NavigationAdRules(text: texts.joined(separator: "\n")) }.value
            guard generation == token else { return activeLists }
            popupRules = popups
            let conversionStart = Date()
            let chunks = try await AdblockEngineWorker.shared.convert(lists: texts)
            guard generation == token else { return activeLists }
            let conversionMS = Date().timeIntervalSince(conversionStart) * 1000
            var sources = chunks.map { ($0.identifier, $0.json) }
            // With no downloads yet, retain the bundled minimal protection.
            if texts.isEmpty {
                let json = domainsBlockJSON(blockedDomains)
                sources.append(("playbridge-adblock-bootstrap-" + cacheIdentifier(for: json), json))
            }
            let builtin = builtinIframeAdJSON()
            if !builtin.isEmpty { sources.append(("playbridge-adblock-builtin-" + cacheIdentifier(for: builtin), builtin)) }
            let userCSS = userCosmeticJSON()
            if !userRules().isEmpty { sources.append(("playbridge-adblock-user-css-" + cacheIdentifier(for: userCSS), userCSS)) }
            let domains = userDomainsJSON()
            if !domains.isEmpty { sources.append((userDomainRuleIdentifier, domains)) }
            var replacement: [WKContentRuleList] = []
            let webKitStart = Date()
            var reused = 0
            var compiled = 0
            for (id, json) in sources {
                if let cached = await lookup(store: store, identifier: id) {
                    replacement.append(cached)
                    reused += 1
                } else {
                    replacement.append(try await compile(store: store, identifier: id, json: json, sourceName: "rules"))
                    compiled += 1
                }
                guard generation == token else { return activeLists }
            }
            guard generation == token else { return activeLists }
            activeLists = replacement
            lastCompilationMetrics = CompilationMetrics(engineMS: engineMS, conversionMS: conversionMS,
                webKitMS: Date().timeIntervalSince(webKitStart) * 1000, reusedChunks: reused, compiledChunks: compiled)
            lastCompilationError = nil
            await prune(store: store, keeping: Set(replacement.map(\.identifier)), token: token)
            return activeLists
        } catch {
            guard generation == token else { return activeLists }
            lastCompilationError = "Couldn’t compile updated filter rules."
            throw error
        }
    }
    @MainActor private static func cachedCompile(store: WKContentRuleListStore, identifier: String, json: String) async throws -> WKContentRuleList {
        if let cached = await lookup(store: store, identifier: identifier) { return cached }
        return try await compile(store: store, identifier: identifier, json: json, sourceName: "rules")
    }
    @MainActor private static func lookup(store: WKContentRuleListStore, identifier: String) async -> WKContentRuleList? {
        await withCheckedContinuation { continuation in
            store.lookUpContentRuleList(forIdentifier: identifier) { list, _ in continuation.resume(returning: list) }
        }
    }
    @MainActor private static func compile(store: WKContentRuleListStore, identifier: String, json: String, sourceName: String) async throws -> WKContentRuleList {
        try await withCheckedThrowingContinuation { continuation in
            store.compileContentRuleList(forIdentifier: identifier, encodedContentRuleList: json) { list, _ in
                if let list { continuation.resume(returning: list) }
                else { continuation.resume(throwing: AdblockFailure.conversionFailure) }
            }
        }
    }
    @MainActor private static func prune(store: WKContentRuleListStore, keeping: Set<String>, token: UUID) async {
        let ids: [String] = await withCheckedContinuation { continuation in
            store.getAvailableContentRuleListIdentifiers { continuation.resume(returning: $0 ?? []) }
        }
        guard generation == token else { return }
        for id in ids where id.hasPrefix("playbridge-adblock") && !keeping.contains(id) {
            store.removeContentRuleList(forIdentifier: id) { _ in }
        }
    }
    private static func cacheIdentifier(for text: String) -> String {
        AdblockEngineWorker.cacheIdentifier(for: text)
    }

    // Downloads persist expanded data only after the entire root/include transaction succeeds.
    static func download(url: URL) async throws {
        do {
            let text = try await fetchList(url)
            let expanded = try await AdblockListPreparser.expand(text: text, url: url, fetch: fetchList)
            try expanded.write(to: getLocalListPath(for: url), atomically: true, encoding: .utf8)
            UserDefaults.standard.removeObject(forKey: failureKey(url))
            UserDefaults.standard.set(true, forKey: preparedKey(url))
        } catch {
            UserDefaults.standard.set(true, forKey: failureKey(url))
            throw AdblockFailure.engineFailure
        }
    }
    private static func fetchList(_ url: URL) async throws -> String {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("PlayBridge", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
              data.count <= 16 * 1024 * 1024, let text = String(data: data, encoding: .utf8),
              !text.prefix(4096).lowercased().contains("<html"),
              !text.prefix(4096).lowercased().contains("<!doctype html") else { throw AdblockListPreparser.Failure.response }
        return text
    }
    static func ensureListsDownloaded(maxAge: TimeInterval = 24 * 60 * 60) async {
        let urls = enabledFilterListURLs
        await withTaskGroup(of: Void.self) { group in
            for url in urls {
                group.addTask {
                    if UserDefaults.standard.bool(forKey: preparedKey(url)),
                       let date = lastUpdated(url: url), Date().timeIntervalSince(date) < maxAge { return }
                    try? await download(url: url)
                }
            }
        }
    }
    static func getLocalListPath(for url: URL) -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            .appendingPathComponent(ContentBlockerListSettings.cacheFilename(for: url.absoluteString))
    }
    static func isListDownloaded(url: URL) -> Bool { FileManager.default.fileExists(atPath: getLocalListPath(for: url).path) }
    static func lastUpdated(url: URL) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: getLocalListPath(for: url).path)[.modificationDate]) as? Date
    }
    private static func preparedKey(_ url: URL) -> String { "pb_adblock_prepared_v1_" + cacheIdentifier(for: url.absoluteString) }
    private static func failureKey(_ url: URL) -> String { "pb_adblock_download_failed_" + url.absoluteString }
    static func listUpdateFailed(url: URL) -> Bool { UserDefaults.standard.bool(forKey: failureKey(url)) }
    private static func userCosmeticJSON() -> String {
        let rules: [[String: Any]] = userRules().map { rule in
            var trigger: [String: Any] = ["url-filter": ".*"]
            if !rule.domain.isEmpty { trigger["if-domain"] = ["*" + rule.domain] }
            return ["trigger": trigger, "action": ["type": "css-display-none", "selector": rule.selector]]
        }
        return String(decoding: (try? JSONSerialization.data(withJSONObject: rules, options: [.sortedKeys])) ?? Data("[]".utf8), as: UTF8.self)
    }
    static func isSiteExempt(_ source: String) -> Bool {
        guard let host = URL(string: source)?.host?.lowercased() else { return false }
        if AdblockSiteSettings.isDisabled(host: host) { return true }
        return ["youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com"]
            .contains { NavigationAdRules.host(host, matches: $0) }
    }
    @MainActor static func shouldBlockNavigation(_ url: URL, source: URL?, popup: Bool, method: String = "GET") -> Bool {
        guard isEnabled, let host = url.host?.lowercased(), ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return false }
        if userBlockedDomains().contains(where: { NavigationAdRules.host(host, matches: $0) }) { return true }
        let origin = source?.absoluteString ?? url.absoluteString
        guard !isSiteExempt(origin) else { return false }
        if popup, let decision = popupRules.decision(url: url, source: source, popup: true) { return decision }
        return AdblockSnapshot.shared.matches(url: url.absoluteString, source: origin, type: "document", method: method)
    }
    static func shouldBlock(urlString: String, sourceURL: String?, resourceType: String) -> Bool {
        guard isEnabled else { return false }
        if let host = URL(string: urlString)?.host?.lowercased(),
           userBlockedDomains().contains(where: { NavigationAdRules.host(host, matches: $0) }) { return true }
        let source = sourceURL ?? urlString
        guard !isSiteExempt(source) else { return false }
        return AdblockSnapshot.shared.matches(url: urlString, source: source, type: resourceType)
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

    static func isUserDomainRuleList(_ list: WKContentRuleList) -> Bool {
        return list.identifier.hasPrefix("playbridge-adblock-user-domain-")
    }

    static func isUserCosmeticRuleList(_ list: WKContentRuleList) -> Bool {
        return list.identifier.hasPrefix("playbridge-adblock-user-css-")
    }

    private static func userDomainsJSON() -> String { domainsBlockJSON(userBlockedDomains()) }
    private static func builtinIframeAdJSON() -> String { domainsBlockJSON(iframeAdDomains) }

    /// Blocks each domain as sub-resources (any frame) AND as child-frame documents
    /// (ad iframes), but never the top frame — so navigating to the domain still works.
    private static func domainsBlockJSON(_ domains: [String]) -> String {
        BrowserDomainRules.json(domains, resourceTypes: blockResourceTypes)
    }

    static var userDomainRuleIdentifier: String { "playbridge-adblock-user-domain-" + cacheIdentifier(for: userDomainsJSON()) }

    @MainActor static func compileUserDomainList() async throws -> WKContentRuleList? {
        let json = userDomainsJSON()
        guard !json.isEmpty else { return nil }
        guard let store = WKContentRuleListStore.default() else { throw NSError(domain: "ContentBlocker", code: -1) }
        let id = userDomainRuleIdentifier
        if let cached = await lookup(store: store, identifier: id) { return cached }
        return try await compile(store: store, identifier: id, json: json, sourceName: "user-domains")
    }

    /// Injected on demand to let the user tap an element to block (uBlock-style picker).
    /// Hides the element immediately and reports its selector to native for persistence.
    static let elementPickerJS = #"""
    (function () {
      if (window.__pb_picker) return;
      window.__pb_picker = true;

      var target = null, previewing = false, previewEls = [], currentHosts = [];
      // Native controls and touch capture sit above the WKWebView. This shield
      // remains as a fallback for direct script use and synthetic picker tests.
      var shield = document.createElement('div');
      shield.id = '__pb_picker_shield';
      shield.style.cssText = 'position:fixed;inset:0;z-index:2147483645;background:transparent;touch-action:none;';
      document.documentElement.appendChild(shield);

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
      panel.style.display='none';

      function isUI(el){ return el===shield || el===hl || el===panel || (el && panel.contains(el)); }
      function elAt(e){
        var t=e.changedTouches&&e.changedTouches.length?e.changedTouches[0]:(e.touches&&e.touches.length?e.touches[0]:e);
        shield.style.pointerEvents='none';
        var el=document.elementFromPoint(t.clientX, t.clientY);
        shield.style.pointerEvents='auto';
        return el;
      }

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
        panel.querySelector('#pbsel').textContent = s;
        var n = 0; try { n = Array.from(document.querySelectorAll(s)).filter(function(el){return !isUI(el);}).length; } catch(_){}
        panel.querySelector('#pbcount').textContent = n>1 ? ('matches '+n+' elements') : '';
        currentHosts = resourceHosts(target);
        try{ window.webkit.messageHandlers.playbridge.postMessage({type:'pickerSelection',selector:s,hasSource:currentHosts.length>0}); }catch(_){}
        var srcEl = panel.querySelector('#pbsrc');
        srcEl.textContent = currentHosts.length ? ('Sources: '+currentHosts.join(', ')) : 'No external source found';
        var srcBtn = panel.querySelector('#pbsource');
        if(srcBtn){ srcBtn.style.opacity = currentHosts.length ? '1' : '0.4'; }
        updateHL(target);
        if(previewing) applyPreview();
      }
      function swallow(e){ e.preventDefault(); e.stopImmediatePropagation(); }
      function cleanup(){
        if(!window.__pb_picker) return;
        window.__pb_picker=false; window.__pb_picker_cleanup=null;
        window.__pb_picker_block=null; window.__pb_picker_select_at=null; window.__pb_picker_action=null;
        clearPreview(); shield.remove(); hl.remove(); panel.remove();
        blockedEvents.forEach(function(type){ window.removeEventListener(type,intercept,true); });
        window.removeEventListener('mousemove',hover,true);
        // A touch gesture can produce a trailing click after the picker closes.
        window.addEventListener('click',swallow,true);
        setTimeout(function(){window.removeEventListener('click',swallow,true);},350);
        try{ window.webkit.messageHandlers.playbridge.postMessage({type:'pickerState', active:false}); }catch(_){}
      }
      window.__pb_picker_cleanup=cleanup;
      function hover(e){ if(target) return; var el=elAt(e); if(el && !isUI(el)) updateHL(el); }
      function intercept(e){
        if(isUI(e.target) && e.target!==shield) return;
        swallow(e);
        if(e.type==='touchmove' || e.type==='pointermove'){ hover(e); return; }
        if(e.type!=='click' && e.type!=='touchend') return;
        var el=elAt(e); if(!el || isUI(el)) return;
        target=el; refresh();
      }
      window.__pb_picker_select_at=function(xFraction,yFraction){
        if(!window.__pb_picker) return;
        var x=Math.max(0,Math.min(1,xFraction))*innerWidth;
        var y=Math.max(0,Math.min(1,yFraction))*innerHeight;
        var el=elAt({clientX:x,clientY:y});
        if(el && !isUI(el)){ target=el; refresh(); }
      };
      // Keep interception installed after selection, during Up/Down/Preview, and
      // until Block/Cancel. Capture on window precedes page document handlers.
      var blockedEvents=['pointerdown','pointerup','pointermove','touchstart','touchend','touchmove','mousedown','mouseup','click','auxclick','contextmenu'];
      blockedEvents.forEach(function(type){window.addEventListener(type,intercept,{capture:true,passive:false});});
      window.addEventListener('mousemove',hover,true);

      function blockSelected(){
        if(!target){ cleanup(); return; }
        var s = sel(target); clearPreview();
        try{ document.querySelectorAll(s).forEach(function(el){ if(!isUI(el)) el.style.setProperty('display','none','important'); }); }catch(_){}
        try{ window.webkit.messageHandlers.playbridge.postMessage({type:'pickedElement', selector:s, host:location.hostname}); }catch(_){}
        cleanup();
      }
      window.__pb_picker_block=blockSelected;

      window.__pb_picker_action=function(action){
        if(action==='up' && target && target.parentElement && target.parentElement.tagName!=='HTML'){
          target=target.parentElement; refresh();
        } else if(action==='down' && target && target.firstElementChild){
          target=target.firstElementChild; refresh();
        } else if(action==='preview'){
          previewing=!previewing; applyPreview();
        } else if(action==='source' && currentHosts && currentHosts.length){
          try{ window.webkit.messageHandlers.playbridge.postMessage({type:'pickedResources',hosts:currentHosts}); }catch(_){}
          cleanup();
        }
      };

      panel.addEventListener('click', function(e){
        var id = e.target && e.target.id; if(!id) return;
        e.preventDefault(); e.stopImmediatePropagation();
        if(id==='pbup'){ if(target && target.parentElement && target.parentElement.tagName!=='HTML'){ target=target.parentElement; refresh(); } }
        else if(id==='pbdown'){ if(target && target.firstElementChild){ target=target.firstElementChild; refresh(); } }
        else if(id==='pbprev'){ previewing=!previewing; e.target.style.background = previewing ? '#5565F2' : '#241D54'; applyPreview(); }
        else if(id==='pbcancel'){ cleanup(); }
        else if(id==='pbblock'){
          blockSelected();
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
