import SwiftUI

/// How the watch party looks wherever it shows as the cast target: the people icon and
/// the party's violet, matching the Android app.
enum PartyTargetStyle {
    static let symbol = "person.2.fill"
    static let accent = Color(hex: 0xC9B8FF)
    static let surface = Color(hex: 0x231F3A)
    static let border = Color(hex: 0x40367A)

    /// People in the party, counting this phone.
    static func watchingCount(_ party: TogetherPartyState) -> Int { max(1, party.members.count) }
}
