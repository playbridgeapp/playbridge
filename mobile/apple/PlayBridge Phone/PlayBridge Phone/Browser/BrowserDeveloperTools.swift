// SPDX-License-Identifier: MPL-2.0
#if PB_DEVELOPER_TOOLS
import Foundation

/// Off by default. Turns on the cast sheet's JSON and cURL export and the network
/// log's full (unredacted) export.
enum BrowserDeveloperTools {
    static let key = "pb_developer_tools"
}
#endif
