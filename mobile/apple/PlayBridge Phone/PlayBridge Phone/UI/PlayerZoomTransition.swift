import SwiftUI

/// The phone player grows out of the cast sheet card's thumbnail (iOS 18's zoom transition).
/// Earlier iOS versions keep the standard full-screen presentation.
extension View {
    @ViewBuilder func playerZoomSource(id: String, in namespace: Namespace.ID?) -> some View {
        if #available(iOS 18.0, *), let namespace {
            matchedTransitionSource(id: id, in: namespace)
        } else {
            self
        }
    }

    @ViewBuilder func playerZoomTransition(id: String?, in namespace: Namespace.ID?) -> some View {
        if #available(iOS 18.0, *), let namespace, let id {
            navigationTransition(.zoom(sourceID: id, in: namespace))
        } else {
            self
        }
    }
}
