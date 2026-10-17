import AppKit
import SwiftUI

/// Offline host layout checks, using the production bounds container and fixed control envelopes.
/// These exercise the smallest iOS 16 phone size without booting/installing on a simulator.
@main
struct RemoteContentBoundsTests {
    @MainActor static func main() async {
        _ = NSApplication.shared
        let old = await measure(width: 320, height: 548, bounded: false)
        precondition(old["header"]!.minY < 0 && old["viewport"]!.maxY > 548,
                     "The old minimum-height stack reproduces overflow of its screen frame")

        // Conservative 320×568 envelope, SE 2/3 (375×667), and 12 Pro Max (428×926), minus portrait safe areas.
        for (width, height) in [(320.0, 548.0), (375.0, 647.0), (428.0, 845.0)] {
            let frames = await measure(width: width, height: height)
            checkBounds(frames, height: height)
            let viewport = frames["viewport"]!, content = frames["content"]!, pad = frames["pad"]!
            if height < 668 {
                precondition(content.height > viewport.height && abs(pad.height - 100) < 1,
                             "Short phones must scroll the minimum-height controls, not overflow the header")
            } else {
                precondition(abs(content.height - viewport.height) < 1 && pad.height > 100,
                             "Fitting content gives surplus height to the pad without scrolling")
            }
        }
        let medium = await measure(width: 428, height: 700)
        let tall = await measure(width: 428, height: 845)
        precondition(medium["pad"]!.height < tall["pad"]!.height && medium["pad"]!.height > 100,
                     "The pad shrinks first while the other controls retain their minimum sizes")
        let enlarged = await measure(width: 428, height: 845, textScale: 1.5)
        checkBounds(enlarged, height: 845)
        precondition(enlarged["content"]!.height > enlarged["viewport"]!.height,
                     "A large phone can also require scrolling with enlarged controls")
        let landscape = await measure(width: 568, height: 300)
        checkBounds(landscape, height: 300)
        precondition(landscape["content"]!.height > landscape["viewport"]!.height)
        for (width, height) in [(320.0, 548.0), (428.0, 845.0)] {
            let fullControl = await measure(width: width, height: height, tvOnly: true)
            checkBounds(fullControl, height: height)
            precondition(fullControl["pad"]!.height > 100, "Receiver-down/ATV-up uses freed media height for the pad")
            let repair = await measure(width: width, height: height, tallHeader: true)
            checkBounds(repair, height: height)
        }
        print("PASS: ATV-only pad height and expanded repair/storage header stay within phone bounds")
        print("PASS: old overflow reproduced; bounded header/content at 320×568, SE 2/3, 12 Pro Max, enlarged controls and short landscape; pad absorbs height before scroll fallback")
    }

    @MainActor private static func checkBounds(_ frames: [String: CGRect], height: CGFloat) {
        let header = frames["header"]!, viewport = frames["viewport"]!, content = frames["content"]!
        precondition(abs(header.minY) < 1 && viewport.minY >= header.maxY,
                     "Remote content must start below the header")
        precondition(viewport.maxY <= height + 1 && abs(viewport.maxY - height) < 1,
                     "The clipped/hit-test viewport must stay inside the screen")
        precondition(content.minY >= viewport.minY - 1, "Scrolling starts at the content's leading edge")
    }

    @MainActor private static func measure(width: CGFloat, height: CGFloat, textScale: CGFloat = 1, bounded: Bool = true, tvOnly: Bool = false, tallHeader: Bool = false) async -> [String: CGRect] {
        var frames: [String: CGRect] = [:]
        let root = RemoteBoundsFixture(bounded: bounded, textScale: textScale, tvOnly: tvOnly, tallHeader: tallHeader)
            .frame(width: width, height: height)
            .coordinateSpace(name: "remote-layout-test")
            .onPreferenceChange(RemoteBoundsFrames.self) { frames = $0 }
        let host = NSHostingView(rootView: root)
        host.frame = NSRect(x: 0, y: 0, width: width, height: height)
        host.layoutSubtreeIfNeeded()
        await Task.yield()
        host.layoutSubtreeIfNeeded()
        precondition(["header", "viewport", "content", "pad"].allSatisfy { frames[$0] != nil })
        return frames
    }
}

private struct RemoteBoundsFixture: View {
    let bounded: Bool
    let textScale: CGFloat
    let tvOnly: Bool
    let tallHeader: Bool

    var body: some View {
        VStack(spacing: 8) {
            Color.green.frame(height: (tallHeader ? 100 : 56) * textScale).modifier(RemoteBoundsProbe(name: "header"))
            if bounded {
                RemoteContentBounds { player }.modifier(RemoteBoundsProbe(name: "viewport"))
            } else {
                player.frame(maxWidth: .infinity, maxHeight: .infinity).modifier(RemoteBoundsProbe(name: "viewport"))
            }
        }
    }

    private var player: some View {
        VStack(spacing: 12) {
            if !tvOnly {
                Color.blue.frame(height: 96 * textScale) // Title/chips section.
                Color.orange.frame(height: 142 * textScale) // Up Next section.
            }
            Color.gray.frame(minHeight: 100, maxHeight: .infinity).modifier(RemoteBoundsProbe(name: "pad"))
            if !tvOnly {
                Color.purple.frame(height: 86) // Existing seek surface.
                Color.red.frame(height: 64 * textScale) // Media row.
            }
            Color.yellow.frame(height: 56 * textScale) // Navigation row.
        }.modifier(RemoteBoundsProbe(name: "content"))
    }
}

private struct RemoteBoundsProbe: ViewModifier {
    let name: String
    func body(content: Content) -> some View {
        content.background(GeometryReader { proxy in
            Color.clear.preference(key: RemoteBoundsFrames.self,
                                   value: [name: proxy.frame(in: .named("remote-layout-test"))])
        })
    }
}

private struct RemoteBoundsFrames: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, next in next })
    }
}
