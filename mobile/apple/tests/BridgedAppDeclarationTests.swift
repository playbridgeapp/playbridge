import Foundation

private actor Probe {
    var calls = 0
    func run(_ origin: URL) async -> BridgedApp? {
        calls += 1
        try? await Task.sleep(nanoseconds: 20_000_000)
        return origin.host == "app.example" ? BridgedApp(origin: origin, name: "Streams", startURL: origin, iconURL: nil) : nil
    }
}

@main struct DeclarationChecks {
    static func main() async {
        let origin = URL(string: "https://app.example/")!
        func valid(_ json: String) -> Bool { BridgedAppDeclaration.isValid(Data(json.utf8), for: origin) }
        precondition(valid(#"{"protocol":"playbridge-app-v1","name":"Streams","start_url":"/watch"}"#))
        precondition(!valid(#"{"protocol":"wrong","name":"Streams"}"#))
        precondition(!valid(#"{"protocol":"playbridge-app-v1","name":"  "}"#))
        precondition(!valid(#"{"protocol":"playbridge-app-v1","name":"Streams","start_url":"https://other.example/"}"#))
        precondition(!valid(String(repeating: " ", count: 16385)))
        precondition(!valid(#"{"protocol":"playbridge-app-v1","name":"Streams","start_url":42}"#))
        let parsed = BridgedAppDeclaration.parse(Data(#"{"protocol":"playbridge-app-v1","name":" Streams ","start_url":"/watch","icon_url":"/logo.png"}"#.utf8), for: origin)!
        precondition(parsed.name == "Streams" && parsed.startURL == URL(string: "https://app.example/watch")!)
        precondition(parsed.iconURL == URL(string: "https://app.example/logo.png")! && parsed.isValid)
        let externalIcon = BridgedAppDeclaration.parse(Data(#"{"protocol":"playbridge-app-v1","name":"Streams","icon_url":"https://other.example/logo.png"}"#.utf8), for: origin)!
        precondition(externalIcon.iconURL == nil)
        let longName = #"{"protocol":"playbridge-app-v1","name":""# + String(repeating: "x", count: 100) + #""}"#
        precondition(BridgedAppDeclaration.parse(Data(longName.utf8), for: origin)!.name.count == 60)
        precondition(BridgedAppDeclaration.origin(of: URL(string: "https://APP.example:443/watch?q=1")!) == origin)
        precondition(BridgedAppDeclaration.origin(of: URL(string: "http://192.168.1.23:5182/watch")!) != nil)
        precondition(BridgedAppDeclaration.origin(of: URL(string: "http://8.8.8.8/watch")!) == nil)
        precondition(BridgedAppDeclaration.origin(of: URL(string: "https://user:pass@app.example/")!) == nil)
        let probe = Probe()
        let cache = BridgedAppDeclarationCache(probe: { await probe.run($0) })
        async let a = cache.isDeclared(origin)
        async let b = cache.isDeclared(URL(string: "https://app.example/watch")!)
        let results = await (a, b)
        precondition(results.0 && results.1)
        let calls = await probe.calls
        precondition(calls == 1, "Origin checks must share the same request")
        let cached = await cache.isDeclared(origin)
        precondition(cached)
        let app = await cache.discover(origin)
        precondition(app?.name == "Streams")
        let discoveryCalls = await probe.calls
        precondition(discoveryCalls == 1, "Installation discovery must reuse the detection policy request")
        let normal = await cache.isDeclared(URL(string: "https://ordinary.example/")!)
        let normalCached = await cache.isDeclared(URL(string: "https://ordinary.example/watch")!)
        precondition(!normal && !normalCached)
        let totalCalls = await probe.calls
        precondition(totalCalls == 2, "Positive and negative declarations must be cached")
        let expiring = BridgedAppDeclarationCache(probe: { await probe.run($0) }, lifetime: 0)
        _ = await expiring.isDeclared(origin)
        _ = await expiring.isDeclared(origin)
        let refreshedCalls = await probe.calls
        precondition(refreshedCalls == 4, "Expired declarations must be checked again")
        let suite = "bridged-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = BridgedAppStore(defaults: defaults)
        store.install(parsed)
        store.install(parsed)
        precondition(store.apps == [parsed], "Installation must deduplicate by origin")
        precondition(BridgedAppStore(defaults: defaults).apps == [parsed], "Installation must survive relaunch")
        store.install(BridgedApp(origin: origin, name: "Bad", startURL: URL(string: "https://other.example/")!, iconURL: nil))
        precondition(store.apps == [parsed], "Invalid saved apps must be rejected")
        store.remove(origin)
        precondition(BridgedAppStore(defaults: defaults).apps.isEmpty, "Removal must persist")
        defaults.set(try! JSONEncoder().encode([parsed, parsed]), forKey: "bridged_apps_v1")
        precondition(BridgedAppStore(defaults: defaults).apps == [parsed], "Duplicate saved origins must be filtered")
        print("PASS: bridged-app manifest, origin cache, installation persistence and removal")
    }
}
