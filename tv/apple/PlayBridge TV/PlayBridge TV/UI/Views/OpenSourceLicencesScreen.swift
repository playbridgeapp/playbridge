import SwiftUI
import UIKit

/// Lists the text files in the app bundle's Licenses folder.
struct OpenSourceLicencesScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var files: [URL] = []
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    Button("Close") { dismiss() }
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
                        }
                    }
                }
            }
            .navigationTitle("Open source licences")
            .navigationDestination(for: String.self) { name in
                if let url = files.first(where: { $0.lastPathComponent == name }) {
                    TVLicenceText(url: url)
                }
            }
        }
        .onAppear { files = BundledLicenceFiles.urls() }
    }
}

private struct TVLicenceText: View {
    let url: URL
    @State private var text = ""

    var body: some View {
        FocusableLicenceText(text: text)
            .ignoresSafeArea(edges: .horizontal)
            .navigationTitle(url.lastPathComponent)
            .onAppear {
                if text.isEmpty { text = BundledLicenceFiles.text(at: url) }
            }
    }
}

/// A text view the Siri remote can focus and scroll. A plain ScrollView of Text
/// often never becomes focused, so the remote cannot move through a long licence.
private struct FocusableLicenceText: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> FocusableTextView {
        let view = FocusableTextView()
        view.text = text
        return view
    }

    func updateUIView(_ uiView: FocusableTextView, context: Context) {
        if uiView.text != text { uiView.text = text }
    }
}

private final class FocusableTextView: UITextView {
    override var canBecomeFocused: Bool { true }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: UIView.noIntrinsicMetric)
    }

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        isSelectable = true
        isScrollEnabled = true
        isUserInteractionEnabled = true
        backgroundColor = .clear
        textColor = .white
        font = .monospacedSystemFont(ofSize: 22, weight: .regular)
        textContainerInset = UIEdgeInsets(top: 24, left: 48, bottom: 80, right: 48)
        panGestureRecognizer.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.indirect.rawValue)]
        showsVerticalScrollIndicator = true
        bounces = true
    }

    required init?(coder: NSCoder) {
        fatalError("FocusableTextView is created in code")
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard presses.count == 1, let press = presses.first else {
            super.pressesBegan(presses, with: event)
            return
        }
        let page = max(bounds.height * 0.85, 1)
        let maxY = max(0, contentSize.height - bounds.height + adjustedContentInset.bottom)
        switch press.type {
        case .upArrow where contentOffset.y > 1:
            setContentOffset(CGPoint(x: contentOffset.x, y: max(0, contentOffset.y - page)), animated: true)
        case .downArrow where contentOffset.y < maxY - 1:
            setContentOffset(CGPoint(x: contentOffset.x, y: min(maxY, contentOffset.y + page)), animated: true)
        default:
            super.pressesBegan(presses, with: event)
        }
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
