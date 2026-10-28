import Foundation
import CoreGraphics

enum AppFocusDirection: String {
    case up = "dpad_up", down = "dpad_down", left = "dpad_left", right = "dpad_right"
}

struct AppFocusTarget {
    let id: Int
    let rect: CGRect
}

/// Prefer an overlapping directional beam, then the nearest edges and centers.
enum AppFocusGeometry {
    static func nearest(from source: CGRect, direction: AppFocusDirection, targets: [AppFocusTarget]) -> Int? {
        func valid(_ rect: CGRect) -> Bool {
            [rect.minX, rect.minY, rect.width, rect.height].allSatisfy(\.isFinite) && rect.width > 0 && rect.height > 0
        }
        guard valid(source) else { return nil }
        let horizontal = direction == .left || direction == .right
        let positive = direction == .right || direction == .down
        let sourceAlong = horizontal ? source.midX : source.midY
        let sourceCross = horizontal ? source.midY : source.midX
        let sourceMin = horizontal ? source.minY : source.minX
        let sourceMax = horizontal ? source.maxY : source.maxX
        var best: (id: Int, beam: Bool, distance: CGFloat, center: CGFloat)?
        for target in targets where valid(target.rect) {
            let rect = target.rect
            let along = horizontal ? rect.midX : rect.midY
            guard positive ? along > sourceAlong : along < sourceAlong else { continue }
            let cross = horizontal ? rect.midY : rect.midX
            let overlap = min(sourceMax, horizontal ? rect.maxY : rect.maxX) - max(sourceMin, horizontal ? rect.minY : rect.minX)
            let beam = overlap > 0
            let gapX = max(0, max(source.minX - rect.maxX, rect.minX - source.maxX))
            let gapY = max(0, max(source.minY - rect.maxY, rect.minY - source.maxY))
            let distance = gapX * gapX + gapY * gapY
            let center = pow(along - sourceAlong, 2) + pow(cross - sourceCross, 2)
            if let previous = best {
                if previous.beam != beam { if previous.beam { continue } }
                else if previous.distance != distance { if previous.distance < distance { continue } }
                else if previous.center != center { if previous.center < center { continue } }
                else if previous.id < target.id { continue }
            }
            best = (target.id, beam, distance, center)
        }
        return best?.id
    }

    /// The smallest containing registration wins for nested controls such as Favorite.
    static func action(at focus: CGRect, targets: [AppFocusTarget]) -> Int? {
        targets.filter { $0.rect.contains(CGPoint(x: focus.midX, y: focus.midY)) }
            .min { a, b in
                let first = a.rect.width * a.rect.height, second = b.rect.width * b.rect.height
                return first == second ? a.id < b.id : first < second
            }?.id
    }
}

enum AppRemoteInputGate {
    static func allows(foreground: Bool, hasPlayer: Bool, pairingPending: Bool) -> Bool {
        foreground && !hasPlayer && !pairingPending
    }
}
