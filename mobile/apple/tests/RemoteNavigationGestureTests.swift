import Foundation

/// Mirrors Android RemotePadGesturePolicyTest in logical points, not physical pixels.
enum RemoteNavigationGestureTests {
    private final class Pad {
        var now: Int64 = 0
        var policy = RemoteNavigationGesture()
        func down(_ x: Double = 0, _ y: Double = 0) { policy.down(x: x, y: y, at: now) }
        func move(_ x: Double, _ y: Double = 0) -> [RemoteNavigationGesture.Step] { policy.move(x: x, y: y, at: now) }
        func up(_ x: Double = 0, _ y: Double = 0) -> [RemoteNavigationGesture.Step] { policy.up(x: x, y: y, at: now) }
        func tick() -> [RemoteNavigationGesture.Step] { policy.tick(at: now) }
    }
    static func run() {
        precondition(RemoteNavigationGesture.touchSlop == 20 && RemoteNavigationGesture.dragStep == 56)
        precondition(RemoteNavigationGesture.holdSlop == 12 && RemoteNavigationGesture.tapTimeout == 300)
        precondition(RemoteNavigationGesture.holdDelay == 500 && RemoteNavigationGesture.holdCadence == 150)
        precondition(RemoteNavigationGesture.maxHoldSteps == 100 && RemoteNavigationGesture.maxStepsPerMove == 3)
        for (x, y, key) in [(30.0, 10.0, "dpad_right"), (-30, 10, "dpad_left"),
                            (10, 30, "dpad_down"), (10, -30, "dpad_up"),
                            (30, 30, "dpad_right"), (-30, -30, "dpad_left")] {
            let p = Pad(); p.down()
            precondition(p.move(x, y).map(\.key) == [key])
        }
        do {
            let p = Pad(); p.down()
            precondition(p.move(0, -19.9).isEmpty)
            precondition(p.move(0, -20).first?.kind == .swipe)
            precondition(p.move(0, -75.9).isEmpty)
            precondition(p.move(0, -76).first?.kind == .drag)
            precondition(p.move(0, -131.9).isEmpty)
            precondition(p.move(0, -132).map(\.key) == ["dpad_up"])
        }
        do {
            let p = Pad(); p.down(); p.now = 1
            precondition(p.move(40).map(\.kind) == [.swipe])
            p.now = 2; precondition(p.up(40).isEmpty)
            p.now = 10_000; precondition(p.tick().isEmpty)
        }
        do {
            let p = Pad(); p.down()
            let steps = p.move(20) + p.move(76) + p.move(132)
            precondition(steps.map(\.kind) == [.swipe, .drag, .drag])
            precondition(steps.allSatisfy { $0.key == "dpad_right" })
            precondition(p.up(132).isEmpty)
        }
        do {
            let p = Pad(); p.down(); _ = p.move(20)
            precondition(p.move(132).map(\.key) == ["dpad_right", "dpad_right"])
            precondition(p.move(187.9).isEmpty && p.move(188).count == 1)
        }
        do {
            let p = Pad(); p.down(); _ = p.move(20)
            precondition(p.move(320).map(\.kind) == [.drag, .drag, .drag])
            precondition(p.move(320).isEmpty, "Excess capped travel must not survive a stationary sample")
            precondition(p.up(320).isEmpty)
            p.now = 10_000; precondition(p.tick().isEmpty)
            p.down(); precondition(p.move(300).count == 1, "First crossing is just one swipe")
        }
        do {
            let p = Pad(); p.down()
            precondition(p.move(20).first?.direction == .right)
            precondition(p.move(76).first?.direction == .right)
            precondition(p.move(76, 56).first?.direction == .down)
            precondition(p.move(20, 56).first?.direction == .left)
            p.down(); _ = p.move(20)
            precondition(p.move(60, 40).isEmpty)
            precondition(p.move(76, 56).first?.direction == .right)
        }
        do {
            let p = Pad(); p.down(); p.now = 10; _ = p.move(-40)
            precondition(p.policy.holdDeadline == 510)
            p.now = 509; precondition(p.tick().isEmpty)
            p.now = 510; precondition(p.tick().map(\.kind) == [.hold])
            p.now = 659; precondition(p.tick().isEmpty)
            p.now = 660; precondition(p.tick().map(\.key) == ["dpad_left"])
            p.now = 10_000; precondition(p.tick().count == 1 && p.policy.holdDeadline == 10_150)
            precondition(p.tick().isEmpty, "Clock jump never catches up a hold backlog")
            p.now = 10_150; precondition(p.tick().count == 1)
        }
        do {
            let p = Pad(); p.down(); _ = p.move(20); p.now = 250
            precondition(p.move(32).isEmpty && p.policy.holdDeadline == 500)
            p.now = 500; precondition(p.tick().first?.kind == .hold)
            p.now = 550; precondition(p.move(32.1).isEmpty && p.policy.holdDeadline == nil)
            p.now = 650; precondition(p.tick().isEmpty)
            precondition(p.move(20).isEmpty && p.policy.holdDeadline == nil)
            p.now = 700; precondition(p.move(76).first?.kind == .drag)
            p.now = 1_199; precondition(p.tick().isEmpty)
            p.now = 1_200; precondition(p.tick().first?.kind == .hold)
        }
        do {
            let p = Pad(); p.down(); var steps = p.move(20)
            for time in stride(from: Int64(50), through: 1_500, by: 50) {
                p.now = time; steps += p.move(20 + Double(time) / 10)
                precondition(p.tick().isEmpty)
            }
            precondition(steps.count == 3 && steps.allSatisfy { $0.kind == .swipe || $0.kind == .drag })
        }
        do {
            let p = Pad(); p.down(); _ = p.move(20)
            p.now = 500; precondition(p.tick().first?.kind == .hold)
            p.now = 520; precondition(p.up(300).isEmpty && p.policy.holdDeadline == nil)
            p.now = 10_000; precondition(p.tick().isEmpty)
        }
        do {
            let p = Pad(); p.down(10, 10); p.now = 300
            precondition(p.up(18, 14).map(\.kind) == [.tap])
            precondition(p.tick().isEmpty)
            p.down(); p.now += 301; precondition(p.up(2, 2).isEmpty)
            p.down(); p.now += 1_000; precondition(p.tick().isEmpty && p.up(2, 2).isEmpty)
            p.down(); _ = p.move(20); _ = p.move(5)
            precondition(p.up(5).isEmpty, "Returning to the origin after a swipe cannot also tap")
            p.down(); precondition(p.up(20).isEmpty, "Exactly slop is not a tap; release adds no arrows")
            p.down(); precondition(p.up(100).isEmpty && p.move(200).isEmpty)
        }
        do {
            let p = Pad(); p.down(); _ = p.move(20); p.policy.cancel(); p.now = 10_000
            precondition(p.tick().isEmpty && p.move(200).isEmpty && p.up(200).isEmpty)
            precondition(p.policy.holdDeadline == nil)
            p.down(); _ = p.move(-40); p.down(5, 5); p.now += 1_000
            precondition(p.tick().isEmpty)
            p.down(5, 5); p.now += 40; precondition(p.up(5, 6).first?.kind == .tap)
        }
        do {
            let p = Pad(); p.down(); var steps = p.move(20); p.now = 500
            while p.policy.holdDeadline != nil { steps += p.tick(); p.now += 150 }
            precondition(steps.count == 100 && p.tick().isEmpty)
            precondition(p.move(76).first?.kind == .drag)
            p.now += 500; precondition(p.tick().first?.kind == .hold)
        }
        do {
            let p = Pad(); p.down(); _ = p.move(40); p.now = 200; _ = p.move(96)
            precondition(p.policy.holdDeadline == 700)
            p.now = 500; precondition(p.tick().isEmpty)
            p.now = 700; precondition(p.tick().count == 1)
            p.policy.cancel(); precondition(p.policy.holdDeadline == nil)
        }
        print("PASS: Android pad slop/step/cap, dominant-axis anchors, tap timeout, hold cadence/slop/cap, clock jumps and cancellation")
    }
}
