import Foundation

@main struct StreamDebugTraceTests {
    static func main() async {
        let safe = StreamDebugTrace.safeURL("https://user:password@example.test/bcdn_token=secret&expires=123/video.m3u8?token=private#fragment")
        for secret in ["password", "secret", "private", "fragment", "user:"] {
            precondition(!safe.contains(secret), "Diagnostic URLs must redact secrets")
        }
        precondition(safe.contains("example.test") && safe.contains("video.m3u8"))
        let proxy = StreamDebugTrace.safeURL("http://192.168.1.4:8080/s/bearer-secret/manifest.m3u8?url=private")
        precondition(!proxy.contains("bearer-secret") && !proxy.contains("private"))
        let first = StreamDebugTrace(), second = StreamDebugTrace()
        await withTaskGroup(of: Void.self) { group in
            for (trace, value) in [(first, "first"), (second, "second")] {
                group.addTask {
                    StreamDebugTrace.$current.withValue(trace) {
                        for _ in 0..<45 { StreamDebugTrace.record(value) }
                    }
                }
            }
        }
#if DEBUG
        precondition(first.text.split(separator: "\n").count == 40)
        precondition(!first.text.contains("second") && !second.text.contains("first"))
#else
        precondition(first.text.isEmpty && second.text.isEmpty, "Release must not retain trace messages")
#endif
        print("PASS: diagnostic URL redaction, task isolation, limits and build-mode behavior")
    }
}
