import CoreGraphics
import Foundation

@main struct BridgedAppEdgeHandleLayoutTests {
    static func main() {
        let suiteName = "com.playbridge.tests.edge-handle.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        // Default remains opposite the front camera until the user explicitly drags it.
        let initial = BridgedAppEdgeHandleLayout.load(defaults: defaults)
        precondition(initial == .default)
        precondition(BridgedAppEdgeHandleLayout.effectiveEdge(for: initial, cameraSide: .top) == .right)
        precondition(BridgedAppEdgeHandleLayout.effectiveEdge(for: initial, cameraSide: .left) == .right)
        precondition(BridgedAppEdgeHandleLayout.effectiveEdge(for: initial, cameraSide: .right) == .left)

        // Default portrait placement touches the physical right edge and centers inside safe vertical bounds.
        let portraitSize = CGSize(width: 393, height: 852)
        let portraitSafe = BridgedAppEdgeHandleLayout.SafeInsets(top: 59, left: 0, bottom: 34, right: 0)
        let portraitCenter = BridgedAppEdgeHandleLayout.center(
            for: initial,
            in: portraitSize,
            safeInsets: portraitSafe,
            cameraSide: .top
        )
        precondition(portraitCenter.x == portraitSize.width - BridgedAppEdgeHandleLayout.handleSize.width / 2)
        precondition(portraitCenter.y > portraitSafe.top && portraitCenter.y < portraitSize.height - portraitSafe.bottom)

        // Snapping on release chooses the nearer edge and clamps vertically away from notch/island/home indicator.
        let snappedTopLeft = BridgedAppEdgeHandleLayout.snapOnRelease(
            rawCenter: CGPoint(x: 40, y: 10),
            in: portraitSize,
            safeInsets: portraitSafe,
            cameraSide: .top
        )
        precondition(snappedTopLeft.edge == .left && snappedTopLeft.yFraction == 0)
        let clampedTopLeftCenter = BridgedAppEdgeHandleLayout.center(
            for: snappedTopLeft,
            in: portraitSize,
            safeInsets: portraitSafe,
            cameraSide: .top
        )
        let halfH = BridgedAppEdgeHandleLayout.handleSize.height / 2
        precondition(clampedTopLeftCenter.y - halfH >= portraitSafe.top, "Handle must clear Dynamic Island/notch at top")

        let snappedBottomRight = BridgedAppEdgeHandleLayout.snapOnRelease(
            rawCenter: CGPoint(x: 350, y: 845),
            in: portraitSize,
            safeInsets: portraitSafe,
            cameraSide: .top
        )
        precondition(snappedBottomRight.edge == .right && snappedBottomRight.yFraction == 1)
        let clampedBottomCenter = BridgedAppEdgeHandleLayout.center(
            for: snappedBottomRight,
            in: portraitSize,
            safeInsets: portraitSafe,
            cameraSide: .top
        )
        precondition(clampedBottomCenter.y + halfH <= portraitSize.height - portraitSafe.bottom,
                     "Handle must clear home indicator at bottom")

        // Persisting side and height across launches preserves exact user placement and overrides camera default.
        let custom = BridgedAppEdgeHandleLayout.Position(edge: .left, yFraction: 0.25)
        BridgedAppEdgeHandleLayout.save(custom, defaults: defaults)
        let reloaded = BridgedAppEdgeHandleLayout.load(defaults: defaults)
        precondition(reloaded == custom)
        precondition(BridgedAppEdgeHandleLayout.effectiveEdge(for: reloaded, cameraSide: .left) == .left)

        // Landscape keeps the handle clear of the camera cutout when placed on the camera side and clear of the home indicator.
        let landscapeSize = CGSize(width: 852, height: 393)
        let landscapeSafeCameraLeft = BridgedAppEdgeHandleLayout.SafeInsets(top: 0, left: 59, bottom: 21, right: 0)
        let landscapeCustomLeft = BridgedAppEdgeHandleLayout.center(
            for: BridgedAppEdgeHandleLayout.Position(edge: .left, yFraction: 1),
            in: landscapeSize,
            safeInsets: landscapeSafeCameraLeft,
            cameraSide: .left
        )
        let halfW = BridgedAppEdgeHandleLayout.handleSize.width / 2
        precondition(landscapeCustomLeft.x - halfW >= landscapeSafeCameraLeft.left,
                     "Landscape left handle must clear Dynamic Island when camera is on left")
        precondition(landscapeCustomLeft.y + halfH <= landscapeSize.height - landscapeSafeCameraLeft.bottom,
                     "Landscape handle must clear bottom home indicator")

        let landscapeDefaultCameraLeft = BridgedAppEdgeHandleLayout.center(
            for: .default,
            in: landscapeSize,
            safeInsets: landscapeSafeCameraLeft,
            cameraSide: .left
        )
        precondition(landscapeDefaultCameraLeft.x + halfW == landscapeSize.width,
                     "Default opposite-camera edge stays flush at trailing screen edge")

        print("PASS: bridged app edge handle snap, safe-area clearance, camera default, and persistence")
    }
}
