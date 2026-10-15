/// Whether a PlayBridge section should show its existing "no TVs yet" copy.
///
/// The connected PlayBridge TV is already the destination card, so both lists
/// drop it before drawing rows. That filtered list can be empty while a TV is
/// connected. The copy is only for a phone with no saved PlayBridge TVs and
/// nothing unpaired still waiting to be listed.
enum PlayBridgeListEmptyState {
    static func showsEmptyCopy(savedCount: Int, activeKey: String?, unpairedCount: Int = 0) -> Bool {
        savedCount == 0 && activeKey == nil && unpairedCount == 0
    }
}
