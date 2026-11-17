import CoreGraphics
import Foundation

/// Geometry and persistence for the movable PlayBridge edge handle inside bridged apps.
/// Default placement mirrors the front camera (right in portrait / landscape-right camera-left,
/// left in landscape-left camera-right) until the user explicitly drags it to a custom edge/height.
enum BridgedAppEdgeHandleLayout {
    enum Edge: String, Equatable {
        case left
        case right
    }

    enum CameraSide: Equatable {
        case top
        case left
        case right
    }

    struct SafeInsets: Equatable {
        var top: CGFloat = 0
        var left: CGFloat = 0
        var bottom: CGFloat = 0
        var right: CGFloat = 0
    }

    struct Position: Equatable {
        /// `nil` keeps the automatic camera-opposite side until the user drags the handle.
        var edge: Edge?
        var yFraction: CGFloat

        static let `default` = Position(edge: nil, yFraction: 0.5)
    }

    static let handleSize = CGSize(width: 28, height: 56)
    static let verticalMargin: CGFloat = 12

    static let edgeDefaultsKey = "bridged_app_edge_side"
    static let yFractionDefaultsKey = "bridged_app_edge_y_fraction"

    static func defaultEdge(cameraSide: CameraSide) -> Edge {
        cameraSide == .right ? .left : .right
    }

    static func effectiveEdge(for position: Position, cameraSide: CameraSide) -> Edge {
        position.edge ?? defaultEdge(cameraSide: cameraSide)
    }

    static func load(defaults: UserDefaults = .standard) -> Position {
        let rawSide = defaults.string(forKey: edgeDefaultsKey)
        let edge = rawSide.flatMap(Edge.init(rawValue:))
        let fraction: CGFloat
        if defaults.object(forKey: yFractionDefaultsKey) != nil {
            fraction = clampFraction(CGFloat(defaults.double(forKey: yFractionDefaultsKey)))
        } else {
            fraction = 0.5
        }
        return Position(edge: edge, yFraction: fraction)
    }

    static func save(_ position: Position, defaults: UserDefaults = .standard) {
        if let edge = position.edge {
            defaults.set(edge.rawValue, forKey: edgeDefaultsKey)
        } else {
            defaults.removeObject(forKey: edgeDefaultsKey)
        }
        defaults.set(Double(clampFraction(position.yFraction)), forKey: yFractionDefaultsKey)
    }

    static func horizontalBounds(
        in size: CGSize,
        safeInsets: SafeInsets,
        cameraSide: CameraSide
    ) -> (minX: CGFloat, maxX: CGFloat) {
        let halfW = handleSize.width / 2
        let leftInset = cameraSide == .left ? safeInsets.left : 0
        let rightInset = cameraSide == .right ? safeInsets.right : 0
        let minX = min(size.width / 2, leftInset + halfW)
        let maxX = max(minX, size.width - rightInset - halfW)
        return (minX, maxX)
    }

    static func verticalBounds(in size: CGSize, safeInsets: SafeInsets) -> (minY: CGFloat, maxY: CGFloat) {
        let halfH = handleSize.height / 2
        let topClearance = max(safeInsets.top, verticalMargin) + halfH
        let bottomClearance = max(safeInsets.bottom, verticalMargin) + halfH
        if topClearance >= size.height - bottomClearance {
            let mid = size.height / 2
            return (mid, mid)
        }
        return (topClearance, size.height - bottomClearance)
    }

    static func center(
        for position: Position,
        in size: CGSize,
        safeInsets: SafeInsets,
        cameraSide: CameraSide
    ) -> CGPoint {
        let edge = effectiveEdge(for: position, cameraSide: cameraSide)
        let (minX, maxX) = horizontalBounds(in: size, safeInsets: safeInsets, cameraSide: cameraSide)
        let (minY, maxY) = verticalBounds(in: size, safeInsets: safeInsets)
        let x = edge == .left ? minX : maxX
        let fraction = clampFraction(position.yFraction)
        let y = minY + fraction * (maxY - minY)
        return CGPoint(x: x, y: y)
    }

    static func clampedCenter(
        _ rawCenter: CGPoint,
        in size: CGSize,
        safeInsets: SafeInsets,
        cameraSide: CameraSide
    ) -> CGPoint {
        let (minX, maxX) = horizontalBounds(in: size, safeInsets: safeInsets, cameraSide: cameraSide)
        let (minY, maxY) = verticalBounds(in: size, safeInsets: safeInsets)
        return CGPoint(
            x: min(max(rawCenter.x, minX), maxX),
            y: min(max(rawCenter.y, minY), maxY)
        )
    }

    static func snapOnRelease(
        rawCenter: CGPoint,
        in size: CGSize,
        safeInsets: SafeInsets,
        cameraSide: CameraSide
    ) -> Position {
        let clamped = clampedCenter(rawCenter, in: size, safeInsets: safeInsets, cameraSide: cameraSide)
        let (minX, maxX) = horizontalBounds(in: size, safeInsets: safeInsets, cameraSide: cameraSide)
        let edge: Edge = (clamped.x - minX) <= (maxX - clamped.x) ? .left : .right
        let (minY, maxY) = verticalBounds(in: size, safeInsets: safeInsets)
        let fraction = maxY > minY ? clampFraction((clamped.y - minY) / (maxY - minY)) : 0.5
        return Position(edge: edge, yFraction: fraction)
    }

    private static func clampFraction(_ value: CGFloat) -> CGFloat {
        guard value.isFinite else { return 0.5 }
        return min(max(value, 0), 1)
    }
}
