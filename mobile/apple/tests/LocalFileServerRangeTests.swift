import Foundation

@main
struct LocalFileServerRangeTests {
    static func check(_ condition: Bool, _ message: String) {
        if !condition {
            fatalError("CHECK FAILED: \(message)")
        }
    }

    static func main() async throws {
        // 1. parseByteRange unit checks (matching stream-proxy-rust parse_byte_range)
        // Suffix range: bytes=-500 on 1000-byte file -> last 500 bytes (500...999)
        let suffix = LocalFileServer.parseByteRange("bytes=-500", total: 1000)
        check(suffix?.start == 500 && suffix?.end == 999, "Suffix range bytes=-500 must return (500, 999)")

        // Suffix range larger than file: bytes=-1500 on 1000-byte file -> (0, 999)
        let largeSuffix = LocalFileServer.parseByteRange("bytes=-1500", total: 1000)
        check(largeSuffix?.start == 0 && largeSuffix?.end == 999, "Suffix range larger than file must saturate to 0")

        // Suffix zero: bytes=-0 -> nil (unsatisfiable in Rust)
        check(LocalFileServer.parseByteRange("bytes=-0", total: 1000) == nil, "Suffix range of 0 must return nil")

        // Start >= length: bytes=1000- on 1000-byte file -> nil (416 in Rust)
        check(LocalFileServer.parseByteRange("bytes=1000-", total: 1000) == nil, "Start >= total must return nil")

        // Start >= length: bytes=1500- on 1000-byte file -> nil
        check(LocalFileServer.parseByteRange("bytes=1500-", total: 1000) == nil, "Start > total must return nil")

        // Start-only: bytes=0- on 1000-byte file -> (0, 999)
        let startOnlyZero = LocalFileServer.parseByteRange("bytes=0-", total: 1000)
        check(startOnlyZero?.start == 0 && startOnlyZero?.end == 999, "bytes=0- must return full range (0, 999)")

        // Start-only: bytes=500- on 1000-byte file -> (500, 999)
        let startOnly = LocalFileServer.parseByteRange("bytes=500-", total: 1000)
        check(startOnly?.start == 500 && startOnly?.end == 999, "bytes=500- must return (500, 999)")

        // Explicit range: bytes=0-499 -> (0, 499)
        let explicit = LocalFileServer.parseByteRange("bytes=0-499", total: 1000)
        check(explicit?.start == 0 && explicit?.end == 499, "bytes=0-499 must return (0, 499)")

        // Clamped end: bytes=500-2000 on 1000-byte file -> (500, 999)
        let clamped = LocalFileServer.parseByteRange("bytes=500-2000", total: 1000)
        check(clamped?.start == 500 && clamped?.end == 999, "End beyond total must clamp to total - 1")

        // Inverted range: bytes=500-400 -> nil
        check(LocalFileServer.parseByteRange("bytes=500-400", total: 1000) == nil, "Inverted range must return nil")

        // Multi-range: bytes=0-100,200-300 -> nil
        check(LocalFileServer.parseByteRange("bytes=0-100,200-300", total: 1000) == nil, "Multi-range must return nil")

        // Empty file: total == 0 -> nil
        check(LocalFileServer.parseByteRange("bytes=0-10", total: 0) == nil, "Range on empty file must return nil")

        // Malformed ranges
        check(LocalFileServer.parseByteRange("bytes=", total: 1000) == nil, "Empty range must return nil")
        check(LocalFileServer.parseByteRange("bytes=-", total: 1000) == nil, "Dash-only range must return nil")
        check(LocalFileServer.parseByteRange("bytes=abc-def", total: 1000) == nil, "Non-numeric range must return nil")
        check(LocalFileServer.parseByteRange("bytes=--500", total: 1000) == nil, "Double negative must return nil")
        check(LocalFileServer.parseByteRange("notbytes=0-500", total: 1000) == nil, "Missing bytes= prefix must return nil")

        // 2. End-to-end HTTP tests with local server
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let testFile = tempDir.appendingPathComponent("sample.dat")
        var testData = Data(count: 1000)
        for i in 0..<1000 { testData[i] = UInt8(i % 256) }
        try testData.write(to: testFile)

        let server = LocalFileServer()
        if let servedURLString = await server.serve(fileURL: testFile), let base = URL(string: servedURLString) {
            // Replace host with 127.0.0.1 for local test connection
            var comps = URLComponents(url: base, resolvingAgainstBaseURL: false)!
            comps.host = "127.0.0.1"
            let servedURL = comps.url!

            let session = URLSession(configuration: .ephemeral)

            // GET without Range -> 200 OK
            do {
                let (data, response) = try await session.data(from: servedURL)
                let http = response as! HTTPURLResponse
                check(http.statusCode == 200, "Full request must return 200 OK, got \(http.statusCode)")
                check(data.count == 1000, "Full request body size must be 1000, got \(data.count)")
                check(data == testData, "Full request body content mismatch")
            }

            // GET with Range: bytes=-500 -> 206 Partial Content, last 500 bytes
            do {
                var req = URLRequest(url: servedURL)
                req.setValue("bytes=-500", forHTTPHeaderField: "Range")
                let (data, response) = try await session.data(for: req)
                let http = response as! HTTPURLResponse
                check(http.statusCode == 206, "Suffix range request must return 206 Partial Content, got \(http.statusCode)")
                check(http.value(forHTTPHeaderField: "Content-Range") == "bytes 500-999/1000",
                      "Content-Range mismatch: \(http.value(forHTTPHeaderField: "Content-Range") ?? "nil")")
                check(data.count == 500, "Suffix range body count must be 500, got \(data.count)")
                check(data == testData.subdata(in: 500..<1000), "Suffix range body content mismatch")
            }

            // GET with Range: bytes=1000- -> 416 Range Not Satisfiable
            do {
                var req = URLRequest(url: servedURL)
                req.setValue("bytes=1000-", forHTTPHeaderField: "Range")
                let (data, response) = try await session.data(for: req)
                let http = response as! HTTPURLResponse
                check(http.statusCode == 416, "bytes=1000- must return 416 Range Not Satisfiable, got \(http.statusCode)")
                check(http.value(forHTTPHeaderField: "Content-Range") == "bytes */1000",
                      "Content-Range mismatch for 416: \(http.value(forHTTPHeaderField: "Content-Range") ?? "nil")")
                check(data.isEmpty, "416 body must be empty")
            }

            // GET with Range: bytes=0-499 -> 206 Partial Content, first 500 bytes
            do {
                var req = URLRequest(url: servedURL)
                req.setValue("bytes=0-499", forHTTPHeaderField: "Range")
                let (data, response) = try await session.data(for: req)
                let http = response as! HTTPURLResponse
                check(http.statusCode == 206, "Explicit range request must return 206, got \(http.statusCode)")
                check(http.value(forHTTPHeaderField: "Content-Range") == "bytes 0-499/1000",
                      "Content-Range mismatch: \(http.value(forHTTPHeaderField: "Content-Range") ?? "nil")")
                check(data.count == 500, "Body count must be 500, got \(data.count)")
                check(data == testData.subdata(in: 0..<500), "Body content mismatch")
            }

            server.stop()
        }

        // 3. Several files at once (phone-file queues) plus a subtitle sidecar.
        let second = tempDir.appendingPathComponent("second.mp4")
        let third = tempDir.appendingPathComponent("third.jpg")
        let subtitle = tempDir.appendingPathComponent("captions.srt")
        let secondData = Data((0..<300).map { UInt8($0 % 7) })
        let thirdData = Data((0..<200).map { UInt8($0 % 11) })
        try secondData.write(to: second)
        try thirdData.write(to: third)
        try Data("1\n00:00:01,000 --> 00:00:02,000\nHi\n".utf8).write(to: subtitle)
        let multi = LocalFileServer()
        func local(_ string: String) -> URL {
            var comps = URLComponents(string: string)!
            comps.host = "127.0.0.1"
            return comps.url!
        }
        if let first = await multi.serve(fileURL: testFile, subtitle: subtitle),
           let secondURL = await multi.add(fileURL: second),
           let thirdURL = await multi.add(fileURL: third) {
            let session = URLSession(configuration: .ephemeral)
            check(secondURL != thirdURL && secondURL.hasSuffix(".mp4") && thirdURL.hasSuffix(".jpg"), "Queued files need distinct paths")
            let (firstBody, _) = try await session.data(from: local(first.media))
            check(firstBody == testData, "First file must still be served after adding others")
            var req = URLRequest(url: local(secondURL))
            req.setValue("bytes=100-199", forHTTPHeaderField: "Range")
            let (rangeBody, rangeResponse) = try await session.data(for: req)
            let rangeHTTP = rangeResponse as! HTTPURLResponse
            check(rangeHTTP.statusCode == 206 && rangeBody == secondData.subdata(in: 100..<200), "Queued file range mismatch")
            check(rangeHTTP.value(forHTTPHeaderField: "Content-Type") == "video/mp4", "Queued file content type mismatch")
            let (thirdBody, thirdResponse) = try await session.data(from: local(thirdURL))
            check(thirdBody == thirdData && (thirdResponse as! HTTPURLResponse).value(forHTTPHeaderField: "Content-Type") == "image/jpeg",
                  "Second queued file mismatch")
            let (subtitleBody, subtitleResponse) = try await session.data(from: local(first.subtitle!))
            check(String(decoding: subtitleBody, as: UTF8.self).contains("Hi") &&
                  (subtitleResponse as! HTTPURLResponse).value(forHTTPHeaderField: "Content-Type") == "application/x-subrip",
                  "Subtitle sidecar mismatch")
            let (_, missing) = try await session.data(from: local(first.media.replacingOccurrences(of: "/media.dat", with: "/q/unknown.mp4")))
            check((missing as! HTTPURLResponse).statusCode == 404, "Unknown path must be 404")
            // A new single cast replaces the queue: older queued files stop being served.
            let replaced = await multi.serve(fileURL: third, subtitle: nil)
            check(replaced != nil, "Re-serving failed")
            let (_, gone) = try await session.data(from: local(secondURL.replacingOccurrences(of: ":\(URL(string: secondURL)!.port!)", with: ":\(URL(string: replaced!.media)!.port!)")))
            check((gone as! HTTPURLResponse).statusCode == 404, "A new cast must stop serving the old queue")
            multi.stop()
            print("PASS: LocalFileServer serves several files and a subtitle")
        }

        print("PASS: LocalFileServer range handling matches stream-proxy-rust")
    }
}
