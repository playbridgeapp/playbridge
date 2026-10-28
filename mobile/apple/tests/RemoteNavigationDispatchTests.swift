import Foundation

@MainActor enum RemoteNavigationDispatchTests {
    private static func pump() async { for _ in 0..<100 { await Task.yield() } }
    static func run() async {
        // Slow native writes cannot turn repeated input into a queue that drains on release.
        do {
            let dispatcher = RemoteNavigationDispatcher()
            var frames: [Int] = [], haptics = 0
            var gate: CheckedContinuation<Void, Error>?
            let id = dispatcher.beginGesture()
            func submit(_ value: Int, gesture: UUID? = id) {
                dispatcher.submit(gesture: gesture, send: { allowed in
                    guard allowed() else { throw AtvClientError.superseded }
                    frames.append(value)
                    if value == 0 { try await withCheckedThrowingContinuation { gate = $0 } }
                }, onSent: { haptics += 1 }, onError: { _ in })
            }
            submit(0); await pump()
            precondition(frames == [0] && dispatcher.inFlight)
            for value in 1...1_000 {
                submit(value)
                precondition(dispatcher.pendingCount == 1, "At most one pending navigation step")
            }
            precondition(frames == [0])
            dispatcher.endGesture(id)
            precondition(dispatcher.pendingCount == 0)
            submit(1_001) // A late hold tick from the retired gesture must be ignored.
            gate?.resume(); gate = nil; await pump()
            precondition(frames == [0] && haptics == 1 && !dispatcher.inFlight,
                         "Release must not drain steps or tick dropped steps")
        }
        do {
            let d = RemoteNavigationDispatcher()
            var frames: [Int] = [], haptics = 0
            var gate: CheckedContinuation<Void, Error>?
            for value in 0...100 {
                d.submit(send: { allowed in
                    guard allowed() else { throw AtvClientError.superseded }
                    frames.append(value)
                    if value == 0 { try await withCheckedThrowingContinuation { gate = $0 } }
                }, onSent: { haptics += 1 }, onError: { _ in })
                if value == 0 { await pump() }
                precondition(d.pendingCount <= 1)
            }
            gate?.resume(); gate = nil; await pump()
            precondition(frames == [0, 100] && haptics == 2, "D-pad keeps only the latest pending key")
        }
        do {
            let d = RemoteNavigationDispatcher()
            var frames = 0, haptics = 0
            let id = d.beginGesture()
            d.submit(gesture: id, send: { allowed in
                precondition(allowed()); frames += 1
            }, onSent: { haptics += 1 }, onError: { _ in })
            d.endGesture(id) // Release before the scheduled dispatch even starts.
            await pump()
            precondition(frames == 0 && haptics == 0)
            d.submit(send: { _ in frames += 1 }, onSent: { haptics += 1 }, onError: { _ in })
            d.cancel(); await pump()
            precondition(frames == 0 && haptics == 0, "Leaving the remote retires discrete unsent keys too")
        }
        do {
            let d = RemoteNavigationDispatcher()
            var frames: [Int] = []
            let old = d.beginGesture()
            d.submit(gesture: old, send: { _ in frames.append(0) }, onSent: {}, onError: { _ in })
            let current = d.beginGesture()
            d.endGesture(old) // Old UIKit cancellation cannot retire the new finger.
            d.submit(gesture: current, send: { allowed in
                precondition(allowed()); frames.append(1)
            }, onSent: {}, onError: { _ in })
            await pump()
            precondition(frames == [1])
            d.endGesture(current)
        }
        do {
            let d = RemoteNavigationDispatcher()
            var frames = 0, haptics = 0
            d.submit(send: { _ in frames += 1; throw AtvClientError.rejected },
                     onSent: { haptics += 1 }, onError: { _ in })
            await pump()
            precondition(frames == 1 && haptics == 0, "Failed writes never click or retry")
        }
        // Production router must await the receiver navigation write, not use its
        // ordinary fire-and-forget command callback. An interrupted await cannot retry on ATV.
        do {
            var receiver = ReceiverRemotePolicy(isLegacy: false,
                state: ReceiverRemoteState(connected: true, context: "idle"))
            receiver.keys = ["dpad_up"]
            let policy = CombinedRemotePolicy(receiver: receiver, endpoint: "playbridge:test",
                fullControl: .init(), native: true)
            var syncWrites = 0, writes = 0, atvWrites = 0, haptics = 0
            var gate: CheckedContinuation<Void, Error>?
            let router = RemoteInputRouter(latest: { policy }, keyboardTicket: { nil },
                sendReceiver: { _ in syncWrites += 1; return true },
                sendReceiverNavigation: { _ in writes += 1; try await withCheckedThrowingContinuation { gate = $0 } },
                sendAtv: { _, _ in atvWrites += 1 })
            let d = RemoteNavigationDispatcher(), id = d.beginGesture()
            for _ in 0..<100 {
                d.submit(gesture: id, send: { allowed in
                    guard allowed() else { throw AtvClientError.superseded }
                    try await router.key("dpad_up", route: .receiver)
                }, onSent: { haptics += 1 }, onError: { _ in })
            }
            await pump()
            precondition(writes == 1 && syncWrites == 0 && atvWrites == 0 && haptics == 0)
            d.endGesture(id); gate?.resume(throwing: AtvClientError.rejected); gate = nil; await pump()
            precondition(writes == 1 && atvWrites == 0 && haptics == 0 && !d.inFlight)
        }
        // Idle app OK is independent of the replaceable swipe slot and its gesture epoch.
        do {
            var receiver = ReceiverRemotePolicy(isLegacy: false,
                state: ReceiverRemoteState(connected: true, context: "idle"))
            receiver.keys = ["dpad_up", "dpad_down", "dpad_center"]
            let policy = CombinedRemotePolicy(receiver: receiver, endpoint: "playbridge:test", fullControl: .init(), native: true)
            precondition(policy.coalescesNavigation("dpad_up") && !policy.coalescesNavigation("dpad_center"))
            var writes: [String] = []
            var gate: CheckedContinuation<Void, Error>?
            let router = RemoteInputRouter(latest: { policy }, keyboardTicket: { nil }, sendReceiver: { _ in false },
                sendReceiverNavigation: { key in
                    writes.append(key)
                    if key == "dpad_up" { try await withCheckedThrowingContinuation { gate = $0 } }
                }, sendAtv: { _, _ in preconditionFailure("App OK must stay on the receiver") })
            let dispatcher = RemoteNavigationDispatcher()
            let gesture = dispatcher.beginGesture()
            dispatcher.submit(gesture: gesture, send: { authority in
                precondition(authority())
                try await router.key("dpad_up", route: .receiver)
            }, onSent: {}, onError: { _ in })
            await pump()
            dispatcher.cancel() // The visible OK button retires remaining swipe steps.
            let selected = Task { try await router.key(receiver.idleSelectKey!, route: .receiver) }
            _ = dispatcher.beginGesture() // A new swipe cannot cancel or replace this discrete activation.
            try! await selected.value
            precondition(writes == ["dpad_up", "dpad_center"])
            gate?.resume(); gate = nil; await pump()
            precondition(writes.filter { $0 == "dpad_center" }.count == 1)
            var playerState = receiver.state; playerState.context = "player"
            var playerReceiver = ReceiverRemotePolicy(isLegacy: false, state: playerState)
            playerReceiver.keys = receiver.keys
            let player = CombinedRemotePolicy(receiver: playerReceiver, endpoint: "playbridge:test", fullControl: .init(), native: true)
            precondition(player.coalescesNavigation("dpad_center"), "Existing player/companion navigation behavior remains unchanged")
        }
        print("PASS: one-flight/one-pending navigation, release/cancel/new-down retirement, no dropped-step haptics, awaited receiver writes and no retries")
    }
}
