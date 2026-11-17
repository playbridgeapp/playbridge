import Foundation

/// Why a website's party request was refused. `endSession` also ends the linked session
/// with that reason; `clearNeed` settles a pending `needitems` even though nothing was added.
struct PageCastPartyFailure: Error, Equatable {
    let code: String
    var endSession: String? = nil
    var clearNeed = false
}

/// What a website sees of its party session in `statechange`.
struct PageCastPartySnapshot: Equatable {
    struct Item: Equatable {
        let id: String
        let title: String?
    }
    var state: String
    var positionMs: Int64
    var durationMs: Int64
    var title: String?
    var currentIndex: Int
    var items: [Item]

    var totalCount: Int { items.count }

    /// The event detail. Everything except the position, which moves on its own.
    var structure: [String: Any] {
        ["state": state, "durationMs": durationMs, "title": title ?? "", "currentIndex": currentIndex,
         "totalCount": totalCount, "items": items.enumerated().map { offset, item -> [String: Any] in
             var entry: [String: Any] = ["index": offset, "id": item.id]
             if let title = item.title { entry["title"] = title }
             return entry
         }]
    }

    var detail: [String: Any] {
        var detail = structure
        detail["positionMs"] = positionMs
        return detail
    }
}

/// A website's linked session running in the watch party. The page-cast coordinator
/// checks the website's requests and ids; the link turns them into party actions.
@MainActor
protocol PageCastPartyLink: AnyObject {
    /// Every item id the website sent that the party took.
    var knownIds: Set<String> { get }
    /// Don't ask the website for more items: Up next isn't available, or the user cleared it.
    var isDemandSuppressed: Bool { get }
    /// The party changed. `evaluateDemand` asks for more items if the list runs short.
    var onChange: ((_ evaluateDemand: Bool) -> Void)? { get set }
    /// The session can't go on, for this reason.
    var onFinish: ((_ reason: String) -> Void)? { get set }
    func snapshot() -> PageCastPartySnapshot
    func validateAvailable() -> PageCastPartyFailure?
    func append(_ items: [[String: Any]]) -> PageCastPartyFailure?
    func supply(_ items: [[String: Any]]) -> PageCastPartyFailure?
    func replace(_ items: [[String: Any]], startIndex: Int) -> PageCastPartyFailure?
    func jump(_ index: Int) -> PageCastPartyFailure?
    func close()
}
