import Foundation

private actor IconProbe {
    var urls: [URL] = []
    let response: Data?
    let goodPath: String
    let unreachable: Bool
    init(response: Data?, goodPath: String = "/favicon.ico", unreachable: Bool = false) {
        self.response = response; self.goodPath = goodPath; self.unreachable = unreachable
    }
    func fetch(_ url: URL) -> BridgedAppIconFetch {
        urls.append(url)
        if unreachable { return .unreachable }
        return url.path == goodPath ? response.map { .data($0) } ?? .missing : .missing
    }
}

func checkBridgedAppAppearance() async {
    let expected: [(String, UInt32, UInt32)] = [
        ("pine", 0x153D2C, 0x23573F), ("teal", 0x16433F, 0x235E58),
        ("olive", 0x3E4218, 0x565C24), ("ochre", 0x513B13, 0x6C501D),
        ("brick", 0x5E2428, 0x7C353B), ("cranberry", 0x581F36, 0x763047),
        ("plum", 0x46284F, 0x603B6B), ("indigo", 0x2B335B, 0x404D7B),
        ("ocean", 0x193E57, 0x295773), ("graphite", 0x343840, 0x4B515B),
    ]
    precondition(BridgedAppAppearance.palette.count == expected.count)
    for (color, fixture) in zip(BridgedAppAppearance.palette, expected) {
        precondition(color.id == fixture.0 && color.dark == fixture.1 && color.light == fixture.2)
        precondition(BridgedAppAppearance.contrast(color.dark) >= 4.5 && BridgedAppAppearance.contrast(color.light) >= 4.5)
    }
    for (text, index) in [("https://app.example", 9), ("https://streams.example", 7),
                          ("https://example.com", 9), ("http://localhost:5182", 8),
                          ("https://app.example:8443", 2), ("https://xn--bcher-kva.example", 9)] {
        precondition(BridgedAppAppearance.paletteIndex(URL(string: text + "/")!) == index, "FNV-1a fixture changed")
    }
    let origin = URL(string: "https://app.example/")!
    let app = BridgedApp(origin: origin, name: " Streams ", startURL: origin, iconURL: nil)
    for (text, rgb): (String, UInt32) in [("#123", 0x112233), ("#aBc", 0xAABBCC), ("#12abEF", 0x12ABEF), ("#000000", 0)] {
        precondition(BridgedAppAppearance.parseTheme(text) == rgb)
        let json = try! JSONSerialization.data(withJSONObject: ["protocol": "playbridge-app-v1", "name": "Streams", "theme_color": text])
        precondition(BridgedAppDeclaration.parse(json, for: origin)?.themeColor == text)
    }
    for text in [nil, "", "red", "rgb(1,2,3)", "#abcd", "#11223344", "123456", "#ggg", " #fff", "#fff\n"] {
        precondition(BridgedAppAppearance.parseTheme(text) == nil)
    }
    for r in stride(from: UInt32(0), through: 255, by: 17) {
        for g in stride(from: UInt32(0), through: 255, by: 17) {
            for b in stride(from: UInt32(0), through: 255, by: 17) {
                let stops = BridgedAppAppearance.themeGradient((r << 16) | (g << 8) | b)
                precondition(stops.allSatisfy { BridgedAppAppearance.contrast($0) >= 4.5 })
                precondition(stops[0] != stops[1])
            }
        }
    }
    precondition(BridgedAppAppearance.themeGradient(0x12344A) == [0x0D2738, 0x12344A])
    precondition(BridgedAppAppearance.gradient(app) == [0x343840, 0x4B515B])
    var themed = app
    themed.themeColor = "#abc"
    precondition(BridgedAppAppearance.gradient(themed) == BridgedAppAppearance.themeGradient(0xAABBCC))
    themed.tileColorID = "unknown"
    precondition(BridgedAppAppearance.gradient(themed) == BridgedAppAppearance.themeGradient(0xAABBCC))
    themed.tileColorID = "teal"
    precondition(BridgedAppAppearance.gradient(themed) == [0x16433F, 0x235E58])
    precondition(BridgedAppAppearance.monogram(app.name) == "S")
    precondition(BridgedAppAppearance.monogram("éclair") == "É")
    precondition(BridgedAppAppearance.monogram("  ") == "?")

    let suite = "bridged-appearance-" + UUID().uuidString
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let store = BridgedAppStore(defaults: defaults, iconCache: nil)
    store.install(themed)
    precondition(BridgedAppStore(defaults: defaults, iconCache: nil).apps == [themed])
    precondition(store.edit(origin, name: "Edited", homeURL: "https://app.example/home"))
    precondition(store.apps[0].tileColorID == "teal" && store.apps[0].themeColor == "#abc")
    let beforeDraft = defaults.data(forKey: "bridged_apps_v1")
    let discarded = store.apps[0].editing(name: "Unsaved", homeURL: origin.absoluteString, tileColorID: "plum")!
    precondition(discarded.tileColorID == "plum" && store.apps[0].tileColorID == "teal")
    precondition(defaults.data(forKey: "bridged_apps_v1") == beforeDraft, "A discarded draft must not change storage")
    let refreshed = BridgedApp(origin: origin, name: "Site name", startURL: origin, iconURL: origin.appendingPathComponent("icon.png"), themeColor: "#123")
    store.refreshDeclaration(refreshed)
    precondition(store.apps[0].name == "Edited" && store.apps[0].tileColorID == "teal" && store.apps[0].themeColor == "#123")
    precondition(store.apps[0].startURL.path == "/home" && store.apps[0].iconURL == refreshed.iconURL)
    precondition(store.edit(origin, name: "Edited", homeURL: origin.absoluteString, tileColorID: nil))
    precondition(BridgedAppStore(defaults: defaults, iconCache: nil).apps[0].tileColorID == nil)
    let old = Data(#"[{"origin":"https://app.example/","name":"Old","startURL":"https://app.example/"}]"#.utf8)
    defaults.set(old, forKey: "bridged_apps_v1")
    let loaded = BridgedAppStore(defaults: defaults, iconCache: nil).apps[0]
    precondition(loaded.tileColorID == nil && loaded.themeColor == nil)
    defaults.set(Data(#"[{"origin":"https://app.example/","name":"Old","startURL":"https://app.example/","tileColorId":"future","themeColor":"red"}]"#.utf8), forKey: "bridged_apps_v1")
    let unknown = BridgedAppStore(defaults: defaults, iconCache: nil).apps[0]
    precondition(unknown.tileColorID == nil && unknown.themeColor == nil)

    defaults.set(Data(#"[{"origin":"https://app.example/","name":"Old","startURL":"https://app.example/","tileColorId":42,"themeColor":true}]"#.utf8), forKey: "bridged_apps_v1")
    let malformed = BridgedAppStore(defaults: defaults, iconCache: nil).apps[0]
    precondition(malformed.tileColorID == nil && malformed.themeColor == nil)

    let declared = BridgedApp(origin: origin, name: "Streams", startURL: origin, iconURL: origin.appendingPathComponent("icon.png"))
    precondition(BridgedAppAppearance.iconCandidates(declared).map(\.path) == ["/icon.png", "/favicon.ico"])
    for text in ["https://other.example/icon.png", "http://app.example/icon.png", "https://user:pass@app.example/icon", "data:image/png;base64,AA", "javascript:void(0)"] {
        let unsafe = BridgedApp(origin: origin, name: "Unsafe", startURL: origin, iconURL: URL(string: text))
        precondition(BridgedAppAppearance.iconCandidates(unsafe) == [origin.appendingPathComponent("favicon.ico")])
    }
    let local = URL(string: "http://192.168.1.23:5182/")!
    precondition(BridgedAppAppearance.iconCandidates(BridgedApp(origin: local, name: "Local", startURL: local, iconURL: local.appendingPathComponent("touch.png"))).count == 2)

    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==")!
    let probe = IconProbe(response: png)
    let cache = BridgedAppIconCache(directory: directory, fetch: { await probe.fetch($0) })
    let image = await cache.load(declared)
    precondition(image != nil, "A failed declared icon must use the site's favicon")
    let urls = await probe.urls
    precondition(urls.map(\.path) == ["/icon.png", "/favicon.ico"])
    let relaunched = BridgedAppIconCache(directory: directory, fetch: { _ in preconditionFailure("Cached artwork must survive relaunch") })
    let cached = await relaunched.load(declared)
    precondition(cached == image && relaunched.cachedData(declared) == image)
    await cache.remove(origin)
    precondition((try! FileManager.default.contentsOfDirectory(atPath: directory.path)).isEmpty)
    let missing = IconProbe(response: nil)
    let negative = BridgedAppIconCache(directory: directory, fetch: { await missing.fetch($0) })
    let missingImage = await negative.load(declared)
    precondition(missingImage == nil, "No usable artwork selects the monogram")
    let negativeRelaunch = BridgedAppIconCache(directory: directory, fetch: { _ in preconditionFailure("Failures must also persist") })
    let negativeCached = await negativeRelaunch.load(declared)
    precondition(negativeCached == nil)
    await negative.remove(origin)
    let offline = IconProbe(response: nil, unreachable: true)
    let offlineCache = BridgedAppIconCache(directory: directory, fetch: { await offline.fetch($0) })
    let offlineImage = await offlineCache.load(declared)
    precondition(offlineImage == nil && (try? FileManager.default.contentsOfDirectory(atPath: directory.path))?.isEmpty != false,
                 "A transient failure must not persist a miss")
    let retry = IconProbe(response: png)
    let retried = await BridgedAppIconCache(directory: directory, fetch: { await retry.fetch($0) }).load(declared)
    precondition(retried != nil, "The next load retries after a transient failure")
    await offlineCache.remove(origin)
    let preferred = IconProbe(response: png, goodPath: "/icon.png")
    let preferredCache = BridgedAppIconCache(directory: directory, fetch: { await preferred.fetch($0) })
    let preferredImage = await preferredCache.load(declared)
    let preferredURLs = await preferred.urls
    precondition(preferredImage != nil && preferredURLs.map(\.path) == ["/icon.png"], "A declared icon wins without an extra fetch")
    await preferredCache.remove(origin)
    let delayed = BridgedAppIconCache(directory: directory, fetch: { _ in
        try? await Task.sleep(nanoseconds: 100_000_000)
        return .data(png)
    })
    let pending = Task { await delayed.load(declared) }
    try? await Task.sleep(nanoseconds: 20_000_000)
    await delayed.remove(origin)
    _ = await pending.value
    precondition((try! FileManager.default.contentsOfDirectory(atPath: directory.path)).isEmpty, "Removing an app must fence late downloads")
    print("PASS: matching palette/hash, parsing/contrast, precedence, color persistence/drafts and origin-safe persistent icon fallbacks")
}
