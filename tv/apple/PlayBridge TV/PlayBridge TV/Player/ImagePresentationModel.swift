import Foundation
import CoreFoundation

struct ImagePresentationTimer {
    static let maximumDurationMs: Int64 = 86_400_000
    static let loadTimeout: Double = 15
    let durationMs: Int
    private(set) var positionMs = 0
    private(set) var isPlaying: Bool
    private(set) var isShown = false
    private(set) var completed = false
    private var loadStarted: Double?
    private var previous: Double?
    private var elapsedMs = 0.0

    init(durationMs: Int64?) {
        self.durationMs = Int(min(max(durationMs ?? 0, 0), Self.maximumDurationMs))
        isPlaying = self.durationMs > 0
    }

    mutating func start(now: Double) { loadStarted = now; previous = now }

    mutating func shown(now: Double) {
        guard !isShown else { return }
        isShown = true
        previous = now
    }

    @discardableResult
    mutating func setPlaying(_ playing: Bool, now: Double) -> Bool {
        let advance = tick(now: now)
        isPlaying = playing && durationMs > 0 && !completed
        previous = now
        return advance
    }

    /// Returns true once, when this presentation should advance the queue.
    mutating func tick(now: Double) -> Bool {
        guard now.isFinite, !completed else { return false }
        if !isShown, let loadStarted, now - loadStarted >= Self.loadTimeout { shown(now: now) }
        defer { previous = now }
        guard isShown, isPlaying, let previous else { return false }
        elapsedMs = min(Double(durationMs), elapsedMs + max(0, now - previous) * 1000)
        positionMs = Int(elapsedMs)
        if elapsedMs >= Double(durationMs) {
            completed = true
            isPlaying = false
            return true
        }
        return false
    }

    var status: [String: Any] {
        ["type": "status", "state": isPlaying ? "playing" : "paused",
         "position": positionMs, "duration": durationMs, "mediaKind": "image"]
    }
}

struct ImagePointer: Equatable {
    let event: String
    let dx: Double
    let dy: Double
    static let events = ["move", "click", "scroll", "down", "up", "zoom", "reset", "rotate", "transform_anchor"]

    init?(event: String, dx: Double, dy: Double) {
        guard Self.events.contains(event), dx.isFinite, dy.isFinite else { return nil }
        self.event = event
        self.dx = dx
        self.dy = dy
    }

    init?(packet: Data) {
        guard packet.count == 9 else { return nil }
        let bytes = Array(packet)
        guard Int(bytes[0]) < Self.events.count else { return nil }
        func float(at index: Int) -> Double {
            let bits = bytes[index..<(index + 4)].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            return Double(Float(bitPattern: bits))
        }
        self.init(event: Self.events[Int(bytes[0])], dx: float(at: 1), dy: float(at: 5))
    }

    init?(json: [String: Any]) {
        guard let event = json["event"] as? String,
              let dx = (json["dx"] ?? 0) as? NSNumber, let dy = (json["dy"] ?? 0) as? NSNumber,
              CFGetTypeID(dx) != CFBooleanGetTypeID(), CFGetTypeID(dy) != CFBooleanGetTypeID() else { return nil }
        self.init(event: event, dx: dx.doubleValue, dy: dy.doubleValue)
    }
}

struct ImageTransform {
    private(set) var scale = 1.0
    private(set) var x = 0.0
    private(set) var y = 0.0
    private(set) var rotation = 0.0
    private var anchor: (Double, Double)?

    mutating func apply(_ pointer: ImagePointer, width: Double, height: Double) {
        guard width.isFinite, height.isFinite, width > 0, height > 0 else { return }
        let dx = pointer.dx, dy = pointer.dy
        let anchorX = anchor.map { ($0.0 - 0.5) * width } ?? x
        let anchorY = anchor.map { ($0.1 - 0.5) * height } ?? y
        func clamp(_ value: Double, extent: Double) -> Double { min(max(value, -extent), extent) }
        switch pointer.event {
        case "reset": self = ImageTransform()
        case "transform_anchor":
            anchor = dx < 0 || dy < 0 ? nil : (min(dx, 1), min(dy, 1))
        case "rotate":
            let degrees = min(max(dx, -90), 90)
            let radians = degrees * .pi / 180
            let relativeX = x - anchorX, relativeY = y - anchorY
            x = anchorX + relativeX * cos(radians) - relativeY * sin(radians)
            y = anchorY + relativeX * sin(radians) + relativeY * cos(radians)
            rotation = (rotation + degrees).truncatingRemainder(dividingBy: 360)
        case "move", "scroll":
            guard scale > 1 else { return }
            x = clamp(x + dx, extent: width * (scale - 1) / 2)
            y = clamp(y + dy, extent: height * (scale - 1) / 2)
        case "zoom":
            guard dx > 0 else { return }
            let old = scale
            scale = min(max(scale * min(max(dx, 0.5), 2), 1), 8)
            if scale == 1 { x = 0; y = 0 }
            else {
                x = clamp(anchorX + (x - anchorX) * scale / old, extent: width * (scale - 1) / 2)
                y = clamp(anchorY + (y - anchorY) * scale / old, extent: height * (scale - 1) / 2)
            }
        default: break
        }
    }
}
