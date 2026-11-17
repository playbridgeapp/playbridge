import CoreGraphics

/// Classifies iOS keyboard frame notifications so only a docked on-screen software keyboard
/// hides the browser bottom toolbar; an external hardware keyboard's slim shortcut bar or an
/// offscreen/dismissed frame leaves the toolbar visible.
enum BrowserKeyboardVisibility {
    static let minimumSoftHeightAboveSafeArea: CGFloat = 100

    static func isSoftKeyboardVisible(
        endFrame: CGRect,
        screenBounds: CGRect,
        bottomSafeAreaInset: CGFloat = 0
    ) -> Bool {
        guard !endFrame.isNull, !endFrame.isEmpty,
              !screenBounds.isNull, !screenBounds.isEmpty else { return false }
        let overlap = screenBounds.intersection(endFrame)
        guard !overlap.isNull, !overlap.isEmpty else { return false }
        // Ignore non-docked accessory/floating overlays not anchored to the bottom of the screen.
        guard endFrame.maxY >= screenBounds.maxY - 50 else { return false }
        let heightAboveSafeArea = overlap.height - max(0, bottomSafeAreaInset)
        return heightAboveSafeArea >= minimumSoftHeightAboveSafeArea
    }
}
