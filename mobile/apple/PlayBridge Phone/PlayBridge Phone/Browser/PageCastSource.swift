import Foundation

/// Native-owned page identity. Website-supplied URLs never determine permission ownership.
protocol PageCastSource: AnyObject {
    var pageCastDocumentID: UUID { get }
    var pageCastOrigin: String? { get }
    var pageCastTitle: String { get }
    var pageCastCanRequest: Bool { get }
    func deliverPageCast(_ message: [String: Any], documentID: UUID)
}
