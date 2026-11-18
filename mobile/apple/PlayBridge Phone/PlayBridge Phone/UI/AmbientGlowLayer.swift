import SwiftUI
import UIKit

/// Ambient mode's glow. Core Animation shows the (already blurred) tiny frame stretched over
/// the view and crossfades to the next one. A SwiftUI Image swap can draw nothing for a frame
/// while the new image uploads, which dimmed the glow at every update.
struct AmbientGlowLayer: UIViewRepresentable {
    let image: CGImage
    var crossfade: TimeInterval = 0.9

    func makeUIView(context: Context) -> GlowView {
        let view = GlowView()
        view.isUserInteractionEnabled = false
        view.layer.contentsGravity = .resizeAspectFill
        view.layer.magnificationFilter = .linear
        view.layer.contents = image
        return view
    }

    func updateUIView(_ view: GlowView, context: Context) {
        guard (view.layer.contents as AnyObject?) !== image else { return }
        if crossfade > 0 {
            let fade = CATransition()
            fade.type = .fade
            fade.duration = crossfade
            fade.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            view.layer.add(fade, forKey: "ambient")
        }
        view.layer.contents = image
    }

    final class GlowView: UIView {}
}
