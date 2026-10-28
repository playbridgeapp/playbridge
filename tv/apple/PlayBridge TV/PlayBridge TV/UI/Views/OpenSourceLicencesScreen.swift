import SwiftUI
import UIKit
import Combine

/// Lists the text files in the app bundle's Licenses folder.
struct OpenSourceLicencesScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var files: [URL] = []
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Section {
                        Button("Close") { dismiss() }
                            .appRemoteAction { dismiss() }
                        Text("PlayBridge's own code is licensed under MPL-2.0. The text is in this list.")
                        Text("Source: https://github.com/playbridgeapp/playbridge")
                            .foregroundColor(Theme.secondaryText)
                    }
                    Section("Bundled notices") {
                        if files.isEmpty {
                            Text("No licence files were found in this build.")
                        } else {
                            ForEach(files, id: \.lastPathComponent) { url in
                                Button(url.lastPathComponent) {
                                    path.append(url.lastPathComponent)
                                }
                                .appRemoteAction { path.append(url.lastPathComponent) }
                            }
                        }
                    }
                }
                .padding(40)
            }
            .appRemoteScroll()
            .navigationTitle("Open source licences")
            .navigationDestination(for: String.self) { name in
                if let url = files.first(where: { $0.lastPathComponent == name }) {
                    TVLicenceText(url: url, onBack: { if !path.isEmpty { path.removeLast() } }, onHome: { dismiss() })
                        .navigationBarBackButtonHidden(true)
                }
            }
        }
        .onAppear { files = BundledLicenceFiles.urls() }
        .onExitCommand {
            if path.isEmpty { dismiss() } else { path.removeLast() }
        }
        .appRemoteScope(back: {
            if path.isEmpty { dismiss() } else { path.removeLast() }
        }, home: { dismiss() })
    }
}

private struct TVLicenceText: View {
    let url: URL
    let onBack: () -> Void
    let onHome: () -> Void
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Button("Back", action: onBack)
                .appRemoteAction(onBack)
                .padding(.horizontal, 48)
            FocusableLicenceText(text: text)
                .ignoresSafeArea(edges: .horizontal)
        }
        .appRemoteScope(back: onBack, home: onHome)
        .navigationTitle(url.lastPathComponent)
        .onAppear {
            if text.isEmpty { text = BundledLicenceFiles.text(at: url) }
        }
    }
}

/// A text view the Siri remote can focus and scroll. A plain ScrollView of Text
/// often never becomes focused, so the remote cannot move through a long licence.
private struct FocusableLicenceText: View {
    let text: String
    @StateObject private var scrolling = LicenceTextScroll()
    @Environment(\.appRemoteDriver) private var driver
    var body: some View {
        LicenceText(text: text, scrolling: scrolling)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .focusable()
            .appRemoteAction({}, direction: scrolling.move)
            .onMoveCommand { direction in
                let key: String
                switch direction {
                case .up: key = "dpad_up"
                case .down: key = "dpad_down"
                case .left: key = "dpad_left"
                case .right: key = "dpad_right"
                @unknown default: return
                }
                driver?.handle(key)
            }
    }
}

private final class LicenceTextScroll: ObservableObject {
    weak var view: UITextView?
    func move(_ direction: AppFocusDirection) -> Bool {
        guard let view, direction == .up || direction == .down else { return false }
        let maximum = max(0, view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
        let offset = min(maximum, max(0, view.contentOffset.y + (direction == .up ? -1 : 1) * view.bounds.height * 0.85))
        guard abs(offset - view.contentOffset.y) > 1 else { return false }
        view.setContentOffset(CGPoint(x: view.contentOffset.x, y: offset), animated: false)
        return true
    }
}

private final class PassiveLicenceTextView: UITextView {
    override var canBecomeFocused: Bool { false }
}

private struct LicenceText: UIViewRepresentable {
    let text: String
    let scrolling: LicenceTextScroll
    func makeUIView(context: Context) -> UITextView {
        let view = PassiveLicenceTextView()
        view.isSelectable = false
        view.isScrollEnabled = true
        view.backgroundColor = .clear
        view.textColor = .white
        view.font = .monospacedSystemFont(ofSize: 22, weight: .regular)
        view.textContainerInset = UIEdgeInsets(top: 24, left: 48, bottom: 80, right: 48)
        view.panGestureRecognizer.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.indirect.rawValue)]
        scrolling.view = view
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        if view.text != text { view.text = text }
    }
}

enum BundledLicenceFiles {
    static func urls() -> [URL] {
        var found: [URL] = []
        if let listed = Bundle.main.urls(forResourcesWithExtension: "txt", subdirectory: "Licenses") {
            found.append(contentsOf: listed)
        }
        if let root = Bundle.main.resourceURL {
            let folder = root.appendingPathComponent("Licenses", isDirectory: true)
            if let listed = try? FileManager.default.contentsOfDirectory(
                at: folder,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ) {
                found.append(contentsOf: listed.filter { $0.pathExtension.lowercased() == "txt" })
            }
        }
        if found.isEmpty, let root = Bundle.main.resourceURL,
           let listed = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
           ) {
            found.append(contentsOf: listed.filter { $0.pathExtension.lowercased() == "txt" })
        }
        var seen = Set<String>()
        return found
            .filter { seen.insert($0.lastPathComponent).inserted }
            .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
    }

    static func text(at url: URL) -> String {
        if let text = try? String(contentsOf: url, encoding: .utf8) { return text }
        if let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .isoLatin1) {
            return text
        }
        return "This file could not be read."
    }
}
