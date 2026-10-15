import Foundation

@main struct PlayBridgeListEmptyStateTests {
    static func main() {
        precondition(
            PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 0, activeKey: nil),
            "No saved TVs and nothing connected shows the empty copy"
        )
        precondition(
            PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 0, activeKey: nil, unpairedCount: 0),
            "The Devices screen uses the same empty copy when discovery found nothing"
        )
        precondition(
            !PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 1, activeKey: "living-room"),
            "The only saved TV is the connected card, so the filtered list stays blank"
        )
        precondition(
            !PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 1, activeKey: "living-room", unpairedCount: 0),
            "Devices screen: a connected saved TV is not 'no PlayBridge TVs yet'"
        )
        precondition(
            !PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 2, activeKey: "living-room"),
            "Other saved TVs still fill the list"
        )
        precondition(
            !PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 1, activeKey: nil),
            "A saved TV that is not the current destination is a row, not an empty state"
        )
        precondition(
            !PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 0, activeKey: "living-room"),
            "A connected PlayBridge TV suppresses the empty copy even before it is counted as saved"
        )
        precondition(
            !PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 0, activeKey: nil, unpairedCount: 1),
            "An unpaired discovery is listed instead of the empty copy"
        )
        precondition(
            !PlayBridgeListEmptyState.showsEmptyCopy(savedCount: 1, activeKey: "living-room", unpairedCount: 2),
            "Unpaired discoveries stay listed beside a connected saved TV"
        )
        print("PASS: empty copy only when nothing is saved, connected, or waiting to pair")
    }
}
