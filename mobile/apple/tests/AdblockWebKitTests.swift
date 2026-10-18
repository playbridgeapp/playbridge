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
}
