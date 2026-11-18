import Foundation

/// Native-owned page identity. Website-supplied URLs never determine permission ownership.
protocol PageCastSource: AnyObject {
    var pageCastDocumentID: UUID { get }
    var pageCastOrigin: String? { get }
    var pageCastTitle: String { get }
    var pageCastCanRequest: Bool { get }
    func deliverPageCast(_ message: [String: Any], documentID: UUID)
    /// The page's own address, for cast history; native-owned, never website-supplied.
    var pageCastPageURL: String? { get }
    /// The page is a bridged app; its casts are left out of history by default.
    var pageCastIsBridgedApp: Bool { get }
}

extension PageCastSource {
    var pageCastPageURL: String? { nil }
    var pageCastIsBridgedApp: Bool { false }
}
