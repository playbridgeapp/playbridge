import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Where a cast came from, as far as the phone knows when it starts.
struct CastOrigin {
    /// The page the video was found on, when known.
    var pageURL: String?
    /// A website asked for this cast, rather than the user's own cast sheet.
    var pageInitiated = false
    /// The page is a bridged app.
    var bridgedApp = false
#if canImport(UIKit)
    /// The picture the cast sheet already made; nothing new is fetched.
    var preview: UIImage?
#endif
}
