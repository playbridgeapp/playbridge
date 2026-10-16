import Foundation

/// Host-only parser/lifetime checks. No socket or receiver is opened.
enum ReceiverPlaybackFailureTests {
    @MainActor static func run(root: URL) async throws {
        func drain() async throws { try await Task.sleep(nanoseconds: 25_000_000) }
        let fixture = try String(contentsOf: root.appendingPathComponent("protocol/tests/fixtures/receiver-capabilities/status-terminal-error.json"), encoding: .utf8)
        let coordinator = ConnectionCoordinator()
        coordinator.handle(#"{"type":"context","active":"player"}"#)
        coordinator.handle(#"{"type":"status","state":"buffering","position":0,"duration":0,"playbackId":"playback-1"}"#)
        coordinator.handle(fixture)
        try await drain()
        precondition(coordinator.playback?.state == "error")
        precondition(coordinator.playbackFailure?.message == "The media couldn't be loaded (HTTP 403)")
        let notice = coordinator.playbackFailure
        coordinator.handle(#"{"type":"context","active":"idle"}"#)
        try await drain()
        precondition(coordinator.activeContext == "idle" && coordinator.playback == nil)
        precondition(coordinator.playbackFailure == notice, "Idle must not flash away the reason")

        // Retain identity through idle; an old failure cannot replace the notice or playback.
        coordinator.handle(#"{"type":"status","state":"error","position":0,"duration":0,"playbackId":"old","error":"Old failure"}"#)
        try await drain()
        precondition(coordinator.playbackFailure == notice && coordinator.playback == nil)
        coordinator.beginNativePlayback()
        precondition(coordinator.playbackFailure == nil)
        coordinator.handle(#"{"type":"playlist_status","playbackId":"new","currentIndex":0,"totalCount":0,"items":[]}"#)
        coordinator.handle(#"{"type":"status","state":"playing","position":12,"duration":100,"playbackId":"new"}"#)
        coordinator.handle(fixture)
        try await drain()
        precondition(coordinator.playback?.state == "playing" && coordinator.playback?.positionMs == 12)
        precondition(coordinator.playbackFailure == nil)

        // Missing/blank/non-string error uses generic wording without dropping status.
        for value in [nil, NSNull(), 403, true, ["message": "not a string"], [], " \n\t\u{2028} "] as [Any?] {
            coordinator.beginNativePlayback()
            var frame: [String: Any] = ["type": "status", "state": "error", "position": 0, "duration": 0]
            if let value { frame["error"] = value }
            let data = try JSONSerialization.data(withJSONObject: frame)
            coordinator.handle(String(decoding: data, as: UTF8.self))
            try await drain()
            precondition(coordinator.playback?.state == "error")
            precondition(coordinator.playbackFailure?.message == "Playback failed on the TV")
        }
        precondition(ReceiverPlaybackFailure.message("  Failed\r\n\t on\u{2028}the TV  ") == "Failed on the TV")
        precondition(ReceiverPlaybackFailure.message("\u{0085}\u{2028}") == "Playback failed on the TV")
        let literal = "<b>Failed</b> https://example.test"
        precondition(ReceiverPlaybackFailure.message(literal) == literal)
        let bounded = ReceiverPlaybackFailure.message(String(repeating: "😀", count: 600))
        precondition(bounded.unicodeScalars.count == 512 && !bounded.contains("\n"))

        // New success/new queue, local cast, dismissal and full clear remove old notices.
        coordinator.handle(#"{"type":"status","state":"playing","position":0,"duration":1}"#)
        try await drain()
        precondition(coordinator.playbackFailure == nil)
        coordinator.handle(fixture)
        try await drain()
        coordinator.handle(#"{"type":"playlist_status","playbackId":"next","currentIndex":0,"totalCount":0,"items":[]}"#)
        try await drain()
        precondition(coordinator.playbackFailure == nil)
        coordinator.beginNativePlayback()
        coordinator.handle(fixture)
        try await drain()
        coordinator.dismissPlaybackFailure()
        precondition(coordinator.playbackFailure == nil)
        coordinator.handle(fixture)
        try await drain()
        coordinator.clear()
        precondition(coordinator.playbackFailure == nil)

        // Same generation cannot clear a new notice via a delayed session callback.
        var current = UUID()
        coordinator.beginReceiverSession(current)
        coordinator.handle(fixture, sessionID: current)
        try await drain()
        let authenticatedNotice = coordinator.playbackFailure
        coordinator.beginReceiverSession(current)
        precondition(coordinator.playbackFailure == authenticatedNotice)
        let retired = current
        coordinator.handle(fixture, sessionID: retired, isCurrentSession: { current == retired })
        current = UUID()
        coordinator.beginReceiverSession(current)
        try await drain()
        precondition(coordinator.playbackFailure == nil)
        coordinator.handle(fixture, isCurrentSession: { false }) // External destination/current-session rejection.
        try await drain()
        precondition(coordinator.playbackFailure == nil)
        coordinator.handle(fixture, sessionID: current)
        try await drain()
        precondition(coordinator.playbackFailure != nil)
        coordinator.beginReceiverSession(UUID()) // Disconnect/reconnect/switch generation.
        precondition(coordinator.playbackFailure == nil)
        print("PASS: receiver reason/generic fallback; bounded plain text; playback identity; idle retention; next cast/success/dismiss/session reset; retired queued callbacks")
    }
}
