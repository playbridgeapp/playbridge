// SPDX-License-Identifier: MPL-2.0
import UIKit
import WebKit
import Darwin

@main final class AdblockWebKitTests: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var view: WKWebView!
    private var installed: [WKContentRuleList] = []
    private var polls = 0
    private var phase = "initial"
    struct Failure: Error {}
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        view = WKWebView(frame: window!.bounds)
        window?.rootViewController?.view.addSubview(view)
        Task { @MainActor in
            do { try await run(); print("PASS: production adblock WebKit atomic swap, stale generation, previous lists on failure and compiled cache reuse"); exit(0) }
            catch { print("FAIL: adblock WebKit fixture phase=\(phase)"); exit(1) }
        }
        return true
    }
    @MainActor private func apply(_ lists: [WKContentRuleList]) {
        // The same no-await swap used by BrowserStore.applyRules.
        view.configuration.userContentController.removeAllContentRuleLists()
        for list in lists { view.configuration.userContentController.add(list) }
        installed = lists
    }
    @MainActor private func blocked(_ base: String) async throws -> Bool {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
            view.callAsyncJavaScript("try { await fetch(target, {cache:'no-store'}); return false; } catch { return true; }",
                arguments: ["target": base + "/engine-sentinel"], in: nil, in: .page) { result in
                    continuation.resume(with: result.map { $0 as? Bool == true })
                }
        }
    }
    private func rss() -> UInt64 {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return status == KERN_SUCCESS ? info.resident_size : 0
    }
    @MainActor private func run() async throws {
        guard let base = ProcessInfo.processInfo.environment["BROWSER_FIXTURE"] else { throw Failure() }
        let url = URL(string: "https://filters.test/synthetic.txt")!
        ContentBlocker.filterListURLs = [url]
        let file = ContentBlocker.getLocalListPath(for: url)
        let sentinel = "/engine-sentinel$xmlhttprequest"
        try sentinel.write(to: file, atomically: true, encoding: .utf8)
        guard ContentBlocker.addUserRule(domain: "127.0.0.1", selector: ".adblock-fixture"),
              ContentBlocker.addUserBlockedDomain("blocked-source.test") else { throw Failure() }
        let rssStart = rss()
        let bootstrap = Date()
        apply(await ContentBlocker.bootstrapRules())
        guard !installed.isEmpty else { throw Failure() }
        print("METRIC bootstrap_ms=\(Date().timeIntervalSince(bootstrap) * 1000)")
        let start = Date()
        phase = "initial-compile"
        apply(await ContentBlocker.compileAll())
        let firstMS = Date().timeIntervalSince(start) * 1000
        guard !installed.isEmpty, ContentBlocker.lastCompilationError == nil else { throw Failure() }
        guard installed.contains(where: ContentBlocker.isUserDomainRuleList),
              installed.contains(where: ContentBlocker.isUserCosmeticRuleList),
              ContentBlocker.shouldBlockNavigation(URL(string: "https://blocked-source.test")!, source: URL(string: "https://youtube.com"), popup: false) else { throw Failure() }
        print("CHECK: initial rule set, explicit picker lists and exemption priority compiled")
        phase = "initial-page"
        view.load(URLRequest(url: URL(string: base + "/parent")!))
        for _ in 0..<200 {
            if view.title == "Parent" && !view.isLoading { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        guard view.title == "Parent" else { throw Failure() }
        phase = "initial-enforcement"
        guard try await blocked(base) else { throw Failure() }
        phase = "update"
        let oldIDs = installed.map(\.identifier)
        // Force a substantial replacement so the previous enforcement can be polled.
        let many = (0..<50000).map { "||synthetic-\($0).invalid^$script" }.joined(separator: "\n")
        try (sentinel + "\n" + many + "\n||stale.test^$document").write(to: file, atomically: true, encoding: .utf8)
        var finished = false
        let updateStart = Date()
        let update = Task { @MainActor in
            let lists = await ContentBlocker.compileAll()
            finished = true
            return lists
        }
        while !finished {
            guard installed.map(\.identifier) == oldIDs, try await blocked(base) else { throw Failure() }
            polls += 1
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        apply(await update.value)
        guard polls > 0, try await blocked(base) else { throw Failure() }
        let updateMS = Date().timeIntervalSince(updateStart) * 1000
        phase = "reuse"
        let reuseStart = Date()
        let unchanged = await ContentBlocker.compileAll()
        guard unchanged.map(\.identifier) == installed.map(\.identifier) else { throw Failure() }
        let reuseMS = Date().timeIntervalSince(reuseStart) * 1000
        guard ContentBlocker.lastCompilationMetrics?.compiledChunks == 0 else { throw Failure() }

        phase = "generation"
        // A newer small generation wins while a previous large generation is suspended.
        try (sentinel + "\n" + many + "\n||old-generation.test^$document").write(to: file, atomically: true, encoding: .utf8)
        let stale = Task { @MainActor in await ContentBlocker.compileAll() }
        for _ in 0..<400 {
            if AdblockSnapshot.shared.matches(url: "https://old-generation.test", source: "https://page.test", type: "document") { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        try (sentinel + "\n||new-generation.test^$document").write(to: file, atomically: true, encoding: .utf8)
        let current = await ContentBlocker.compileAll()
        apply(current)
        let late = await stale.value
        guard late.map(\.identifier) == current.map(\.identifier), try await blocked(base),
              AdblockSnapshot.shared.matches(url: "https://new-generation.test", source: "https://page.test", type: "document"),
              !AdblockSnapshot.shared.matches(url: "https://old-generation.test", source: "https://page.test", type: "document") else { throw Failure() }
        phase = "failed-update"
        // Invalid input fails the generation, keeping the last complete WebKit set.
        try Data(repeating: 97, count: 17 * 1024 * 1024).write(to: file)
        let failed = await ContentBlocker.compileAll()
        guard failed.map(\.identifier) == current.map(\.identifier), ContentBlocker.lastCompilationError != nil,
              try await blocked(base) else { throw Failure() }
        print("METRIC synthetic_first_ms=\(firstMS) update_ms=\(updateMS) reuse_ms=\(reuseMS) polls=\(polls) rss_start=\(rssStart) rss_after=\(rss())")

        try await runStage2Checks(base: base, file: file, listURL: url)

        phase = "union"
        if let measurement = Bundle.main.url(forResource: "measurement-lists", withExtension: "json") {
            let lists = try JSONDecoder().decode([String].self, from: Data(contentsOf: measurement))
            var urls: [URL] = []
            for (index, text) in lists.enumerated() {
                let url = URL(string: "https://filters.test/measurement-\(index).txt")!
                urls.append(url)
                try text.write(to: ContentBlocker.getLocalListPath(for: url), atomically: true, encoding: .utf8)
            }
            ContentBlocker.filterListURLs = urls
            let before = rss()
            let start = Date()
            let first = await ContentBlocker.compileAll()
            guard ContentBlocker.lastCompilationError == nil else { throw Failure() }
            apply(first)
            let firstMS = Date().timeIntervalSince(start) * 1000
            let loadedRSS = rss()
            if let m = ContentBlocker.lastCompilationMetrics {
                print("METRIC union_first_engine_ms=\(m.engineMS) conversion_ms=\(m.conversionMS) webkit_ms=\(m.webKitMS) reused=\(m.reusedChunks) compiled=\(m.compiledChunks)")
            }
            let reuseStart = Date()
            let reused = await ContentBlocker.compileAll()
            guard ContentBlocker.lastCompilationMetrics?.compiledChunks == 0,
                  reused.map(\.identifier) == first.map(\.identifier) else { throw Failure() }
            print("METRIC union_unchanged_ms=\(Date().timeIntervalSince(reuseStart) * 1000) compiled=0")
            let root = ContentBlocker.getLocalListPath(for: urls[0])
            let text = try String(contentsOf: root, encoding: .utf8)
            try (text + "\n||stage1-measurement.invalid^$script").write(to: root, atomically: true, encoding: .utf8)
            let update = Date()
            apply(await ContentBlocker.compileAll())
            guard ContentBlocker.lastCompilationError == nil else { throw Failure() }
            let updateMS = Date().timeIntervalSince(update) * 1000
            if let m = ContentBlocker.lastCompilationMetrics {
                print("METRIC union_update_engine_ms=\(m.engineMS) conversion_ms=\(m.conversionMS) webkit_ms=\(m.webKitMS) reused=\(m.reusedChunks) compiled=\(m.compiledChunks)")
            }
            print("METRIC union_first_ms=\(firstMS) update_ms=\(updateMS) lists=\(lists.count) chunks_and_user_lists=\(installed.count) rss_before=\(before) rss_first=\(loadedRSS) rss_update=\(rss())")
        }
    }

    private static let cosmeticWorld = WKContentWorld.world(name: "PlayBridgeAdblockCosmetic")

    @MainActor private func applySiteScripts(for targetURL: URL?) {
        let cc = view.configuration.userContentController
        cc.removeAllUserScripts()
        let scripts = AdblockSnapshot.shared.siteScripts(for: targetURL, enabled: ContentBlocker.isEnabled, isBridgedApp: false)
        if let scriptlet = scripts.scriptlet {
            cc.addUserScript(WKUserScript(source: scriptlet, injectionTime: .atDocumentStart, forMainFrameOnly: false, in: .page))
        }
        if let cosmetic = scripts.cosmetic {
            cc.addUserScript(WKUserScript(source: cosmetic, injectionTime: .atDocumentStart, forMainFrameOnly: false, in: Self.cosmeticWorld))
        }
    }

    @MainActor private func evalBool(_ script: String) async throws -> Bool {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
            view.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { result in
                continuation.resume(with: result.map { $0 as? Bool == true })
            }
        }
    }

    @MainActor private func evalCheck(_ script: String) async throws {
        let status: String = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            view.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { result in
                switch result {
                case .success(let val):
                    continuation.resume(returning: (val as? String) ?? String(describing: val))
                case .failure(let err):
                    continuation.resume(returning: "js-error:\(err.localizedDescription)")
                }
            }
        }
        if status != "ok" {
            phase = "\(phase)(\(status))"
            throw Failure()
        }
    }

    @MainActor private func waitForTitle(_ expected: String) async throws {
        for _ in 0..<200 {
            if view.title == expected && !view.isLoading { return }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        throw Failure()
    }

    @MainActor private func runStage2Checks(base: String, file: URL, listURL: URL) async throws {
        phase = "stage2-compile"
        let stage2Rules = [
            "127.0.0.1##+js(aopr, adsbygoogle)",
            "127.0.0.1##+js(aopw, __ad_write)",
            "127.0.0.1##+js(acs, eval, adNeedle)",
            "127.0.0.1##+js(acis, atob, inlineNeedle)",
            "127.0.0.1##+js(set, adConfig.enabled, false)",
            "127.0.0.1##+js(aeld, click, popMagic)",
            "127.0.0.1##+js(nostif, badTimer, 10)",
            "127.0.0.1##+js(nosiif, badInterval, 10)",
            "127.0.0.1##+js(nowoif, /popup\\.test/)",
            "127.0.0.1##+js(json-prune, adSlots.primary, data)",
            "127.0.0.1##+js(ra, oncontextmenu, body)",
            "127.0.0.1##+js(rc, ad-locked, .article)",
            "127.0.0.1##+js(no-fetch-if, /ads-api/, emptyObj)",
            "127.0.0.1##+js(no-xhr-if, /xhr-ad/, emptyArr)",
            "127.0.0.1##+js(set, exceptedProp, true)",
            "127.0.0.1#@#+js(set, exceptedProp, true)",
            "127.0.0.1##.card > .inner > span:has-text(Sponsored):upward(2)",
            "127.0.0.1##.except-card:has-text(Excepted Ad)",
            "127.0.0.1#@#.except-card:has-text(Excepted Ad)",
            "127.0.0.1##body.locked:style(overflow: auto !important)",
            "127.0.0.1##.removable-ad:remove()",
            "127.0.0.1##.attr-target:matches-attr(data-sponsor=\"true\"):remove-attr(data-remove-me)",
            "127.0.0.1##.cls-target:remove-class(strip-me)",
            "127.0.0.1##div:matches-css(position: fixed)",
            "127.0.0.1##:matches-path(/stage2) .path-ad",
            "127.0.0.1##p:min-text-length(25)",
            "127.0.0.1##:xpath(//div[@id=\"xpath-ad\"][em[text()=\"XPathBanner\"]])",
            "youtube.com##+js(set, ytBlocked, true)",
            "youtube.com##.yt-ad:has-text(Ad)"
        ].joined(separator: "\n")
        try stage2Rules.write(to: file, atomically: true, encoding: .utf8)
        apply(await ContentBlocker.compileAll())
        guard ContentBlocker.lastCompilationError == nil else { throw Failure() }

        phase = "stage2-active"
        let stage2URL = URL(string: base + "/stage2")!
        applySiteScripts(for: stage2URL)
        view.load(URLRequest(url: stage2URL))
        try await waitForTitle("Stage2")
        try await Task.sleep(nanoseconds: 80_000_000)

        try await evalCheck("""
            const s = window.__stage2 || {};
            if (s.aoprSurvived !== false) return 'aopr:' + s.aoprSurvived;
            if (s.aopwSurvived !== false) return 'aopw:' + s.aopwSurvived;
            if (s.acsSurvived !== false) return 'acs:' + s.acsSurvived;
            if (s.acisSurvived !== false) return 'acis:' + s.acisSurvived;
            if (s.constantVal !== false) return 'set:' + s.constantVal;
            if (s.defusedClick !== 0 || s.safeClick !== 1) return 'aeld:' + s.defusedClick + '/' + s.safeClick;
            if (s.badTimerRan !== false || s.goodTimerRan !== true) return 'nostif:' + s.badTimerRan + '/' + s.goodTimerRan;
            if (s.badIntervalTicks !== 0 || s.goodIntervalTicks < 1) return 'nosiif:' + s.badIntervalTicks + '/' + s.goodIntervalTicks;
            if (s.nowoifClosed !== true) return 'nowoif:' + s.nowoifClosed;
            if (s.pruned !== true) return 'prune:' + s.pruned;
            if (window.exceptedProp !== undefined) return 'except:' + window.exceptedProp;
            if (document.body.hasAttribute('oncontextmenu')) return 'ra';
            if (document.getElementById('article-box').classList.contains('ad-locked')) return 'rc';
            const adFetch = await (await fetch('/ads-api')).text();
            const safeFetch = await (await fetch('/safe-api')).text();
            if (adFetch !== '{}' || safeFetch !== 'safe-ok') return 'fetch:' + adFetch + '/' + safeFetch;
            const xhrText = (u) => new Promise((resolve, reject) => {
                const x = new XMLHttpRequest();
                x.onload = () => resolve(x.responseText);
                x.onerror = reject;
                x.open('GET', u);
                x.send();
            });
            const adXhr = await xhrText('/xhr-ad');
            const safeXhr = await xhrText('/xhr-safe');
            if (adXhr !== '[]' || safeXhr !== 'safe-ok') return 'xhr:' + adXhr + '/' + safeXhr;
            return 'ok';
        """)

        phase = "stage2-procedural"
        try await evalCheck("""
            if (getComputedStyle(document.getElementById('sponsored-card')).display !== 'none') return 'sponsored-card';
            if (getComputedStyle(document.getElementById('organic-card')).display === 'none') return 'organic-card';
            if (getComputedStyle(document.getElementById('except-card')).display === 'none') return 'except-card';
            if (getComputedStyle(document.body).overflow !== 'auto') return 'style-overflow:' + getComputedStyle(document.body).overflow;
            if (document.getElementById('removable-ad') !== null) return 'remove';
            if (document.getElementById('attr-target').hasAttribute('data-remove-me')) return 'remove-attr';
            const cls = document.getElementById('cls-target').classList;
            if (cls.contains('strip-me') || !cls.contains('keep-me')) return 'remove-class';
            if (getComputedStyle(document.getElementById('css-fixed-ad')).display !== 'none') return 'matches-css';
            if (getComputedStyle(document.getElementById('path-ad')).display !== 'none') return 'matches-path';
            if (getComputedStyle(document.getElementById('long-ad')).display !== 'none') return 'min-text-long';
            if (getComputedStyle(document.getElementById('short-ad')).display === 'none') return 'min-text-short';
            if (getComputedStyle(document.getElementById('xpath-ad')).display !== 'none') return 'xpath';
            return 'ok';
        """)

        phase = "stage2-mutation"
        let mutationOK = try await evalBool("""
            const card = document.createElement('div');
            card.className = 'card';
            card.id = 'dyn-card';
            card.innerHTML = '<div class="inner"><span>Sponsored</span></div>';
            document.body.appendChild(card);
            const art = document.createElement('div');
            art.className = 'article ad-locked';
            art.id = 'dyn-art';
            document.body.appendChild(art);
            document.body.setAttribute('oncontextmenu', 'return false');
            for (let i = 0; i < 30; i++) {
                await new Promise(r => setTimeout(r, 25));
                if (getComputedStyle(card).display === 'none' &&
                    !art.classList.contains('ad-locked') &&
                    !document.body.hasAttribute('oncontextmenu')) {
                    return true;
                }
            }
            return false;
        """)
        guard mutationOK else { throw Failure() }

        phase = "stage2-unmatched-host"
        let localhostURL = URL(string: base.replacingOccurrences(of: "127.0.0.1", with: "localhost") + "/stage2")!
        applySiteScripts(for: localhostURL)
        view.load(URLRequest(url: localhostURL))
        try await waitForTitle("Stage2")
        let unmatchedOK = try await evalBool("""
            return window.__stage2 && window.__stage2.aoprSurvived === true &&
                   getComputedStyle(document.getElementById('sponsored-card')).display !== 'none';
        """)
        guard unmatchedOK else { throw Failure() }

        phase = "stage2-youtube-exempt"
        let ytURL = URL(string: "https://www.youtube.com/watch?v=1")!
        applySiteScripts(for: ytURL)
        view.loadHTMLString("<html><title>YT</title><body><div class='yt-ad' id='yt-ad'>Ad</div></body></html>", baseURL: ytURL)
        try await waitForTitle("YT")
        let ytExemptOK = try await evalBool("""
            return window.ytBlocked === undefined &&
                   getComputedStyle(document.getElementById('yt-ad')).display !== 'none';
        """)
        guard ytExemptOK else { throw Failure() }

        phase = "stage2-list-toggle-and-disable"
        ContentBlocker.setListEnabled(false, url: listURL)
        defer {
            ContentBlocker.setListEnabled(true, url: listURL)
            ContentBlocker.isEnabled = true
        }
        apply(await ContentBlocker.compileAll())
        applySiteScripts(for: stage2URL)
        view.load(URLRequest(url: stage2URL))
        try await waitForTitle("Stage2")
        let listOffOK = try await evalBool("""
            return window.__stage2 && window.__stage2.aoprSurvived === true &&
                   getComputedStyle(document.getElementById('sponsored-card')).display !== 'none';
        """)
        guard listOffOK else { throw Failure() }

        ContentBlocker.setListEnabled(true, url: listURL)
        apply(await ContentBlocker.compileAll())
        ContentBlocker.isEnabled = false
        applySiteScripts(for: stage2URL)
        view.load(URLRequest(url: stage2URL))
        try await waitForTitle("Stage2")
        let disabledOK = try await evalBool("""
            return window.__stage2 && window.__stage2.aoprSurvived === true &&
                   getComputedStyle(document.getElementById('sponsored-card')).display !== 'none';
        """)
        guard disabledOK else { throw Failure() }
        ContentBlocker.isEnabled = true
        print("CHECK: stage2 document-start scriptlets, procedural cosmetics, exceptions, mutations, list toggle and host exemptions passed")
    }
}
