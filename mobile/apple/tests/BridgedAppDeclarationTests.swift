import Foundation

private actor Probe {
    var calls = 0
    func run(_ origin: URL) async -> Bool {
        calls += 1
        try? await Task.sleep(nanoseconds: 20_000_000)
        return origin.host == "app.example"
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
        print("PASS: bridged-app declaration validation and origin cache")
    }
}
