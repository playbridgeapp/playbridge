import SwiftUI
import WebKit

/// Compact tab rows and management actions matching the Android switcher.
struct TabsScreen: View {
    @ObservedObject var store: BrowserStore
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @FocusState private var searchFocused: Bool
    @State private var selecting = false
    @State private var selected: Set<UUID> = []
    @State private var closing: Set<UUID> = []
    @State private var confirmClose = false
    @State private var feedback: String?

    @StateObject private var scrollDriver = TabScrollDriver()
    @State private var contentFrame: CGRect = .zero
    @State private var scrollingDown = false
    @State private var showScrollButton = false
    @State private var scrollNoticeTask: Task<Void, Never>?

    private var filtered: [BrowserTab] {
        store.tabs.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.urlString.localizedCaseInsensitiveContains(search) }
    }
    private var allVisibleSelected: Bool { !filtered.isEmpty && Set(filtered.map(\.id)).isSubset(of: selected) }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(Theme.onSurfaceVariant)
                    TextField("Search tabs", text: $search)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($searchFocused).submitLabel(.search)
                        .onSubmit { searchFocused = false }
                    if !search.isEmpty {
                        Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                            .accessibilityLabel("Clear tab search")
                    }
                }.padding(12).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 10))
                    .padding(.horizontal, 16).padding(.top, 8)
                GeometryReader { viewport in
                    ScrollViewReader { proxy in
                        ZStack(alignment: .bottomTrailing) {
                            ScrollView {
                                LazyVStack(spacing: 8) {
                                    Color.clear.frame(height: 0).id("tabs-start")
                                    if let feedback { Text(feedback).font(Theme.font(.caption)).foregroundStyle(.secondary) }
                                    ForEach(filtered) { tab in
                                        TabRow(tab: tab, active: tab.id == store.activeID, selecting: selecting,
                                               checked: selected.contains(tab.id), onSelect: {
                                            if selecting {
                                                if !selected.insert(tab.id).inserted { selected.remove(tab.id) }
                                            } else {
                                                // Keep the row's active styling from animating during sheet dismissal.
                                                var transaction = Transaction(animation: nil)
                                                transaction.disablesAnimations = true
                                                withTransaction(transaction) { store.select(tab.id) }
                                                dismiss()
                                            }
                                        }, onClose: { store.closeTab(tab.id) })
                                        .id(tab.id)
                                        .contextMenu {
                                            if !selecting {
                                                Button("Duplicate tab", systemImage: "plus.square.on.square") { searchFocused = false; store.duplicateTab(tab.id) }
                                                Button("Bookmark tab", systemImage: "bookmark") {
                                                    store.bookmarkTabs([tab.id]); feedback = "Tab bookmarked"
                                                }.disabled(tab.isHome || tab.urlString.isEmpty)
                                                Button("Copy link", systemImage: "doc.on.doc") { UIPasteboard.general.string = tab.urlString }
                                                    .disabled(tab.isHome || tab.urlString.isEmpty)
                                                if !tab.isHome, let url = URL(string: tab.urlString) {
                                                    ShareLink(item: url) { Label("Share link", systemImage: "square.and.arrow.up") }
                                                }
                                            }
                                        }
                                    }
                                    if filtered.isEmpty {
                                        Text("No matching tabs").foregroundStyle(.secondary).padding()
                                        Button("Clear search") { search = "" }
                                    }
                                    Color.clear.frame(height: 0).id("tabs-end")
                                }.padding(16)
                                    .background(TabScrollProbe(driver: scrollDriver).frame(width: 0, height: 0))
                                    .background(GeometryReader { geometry in
                                        Color.clear.preference(key: TabListFrameKey.self,
                                            value: geometry.frame(in: .named("tabList")))
                                    })
                            }
                            .coordinateSpace(name: "tabList")
                            .onPreferenceChange(TabListFrameKey.self) { frame in
                                let delta = frame.minY - contentFrame.minY
                                if contentFrame != .zero && abs(delta) > 0.5 {
                                    scrollingDown = delta < 0
                                    showScrollButton = true
                                    scrollNoticeTask?.cancel()
                                    scrollNoticeTask = Task { @MainActor in
                                        do { try await Task.sleep(nanoseconds: 3_000_000_000) }
                                        catch { return }
                                        showScrollButton = false
                                    }
                                }
                                contentFrame = frame
                            }
                            // Wait for the scroll content to be laid out before requesting its initial position.
                            .task {
                                // SwiftUI installs ScrollViewReader's scroll target registry after appearance.
                                do { try await Task.sleep(nanoseconds: 150_000_000) }
                                catch { return }
                                if let id = store.activeID { proxy.scrollTo(id, anchor: .center) }
                            }
                            .onDisappear { scrollNoticeTask?.cancel() }

                            if showScrollButton && contentFrame.height > viewport.size.height + 1 {
                                let atTop = contentFrame.minY >= -17
                                let atBottom = contentFrame.maxY <= viewport.size.height + 17
                                let pointUp = atBottom || (!atTop && !scrollingDown)
                                TabJumpButton(pointUp: pointUp) {
                                    guard !filtered.isEmpty else { return }
                                    scrollDriver.stopScrolling()
                                    proxy.scrollTo(pointUp ? "tabs-start" : "tabs-end", anchor: pointUp ? .top : .bottom)
                                }
                                .frame(width: 48, height: 48)
                                .padding(24)
                            }
                        }
                    }
                }
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle(selecting ? "\(selected.count) selected" : "\(store.tabs.count) Tab\(store.tabs.count == 1 ? "" : "s")")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(selecting ? "Cancel" : "Done") {
                        if selecting { selecting = false; selected.removeAll() } else { dismiss() }
                    }
                }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    if !selecting {
                        Button { store.newTab(); dismiss() } label: { Image(systemName: "plus") }
                            .accessibilityLabel("New tab")
                        Menu {
                            Button("Select tabs", systemImage: "checkmark.circle") { searchFocused = false; selecting = true }
                            Button("Close all tabs", systemImage: "trash", role: .destructive) {
                                closing = Set(store.tabs.map(\.id)); confirmClose = true
                            }
                        } label: { Image(systemName: "ellipsis.circle") }
                        .accessibilityLabel("Tab actions")
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if selecting {
                    HStack {
                        Button(allVisibleSelected ? "Deselect all" : "Select all") {
                            selected = allVisibleSelected ? [] : Set(filtered.map(\.id))
                        }
                        Spacer()
                        Button {
                            store.bookmarkTabs(selected); feedback = "Selected web tabs bookmarked"
                            selected.removeAll(); selecting = false
                        } label: { Image(systemName: "bookmark") }
                        .accessibilityLabel("Bookmark selected tabs").disabled(selected.isEmpty)
                        Button(role: .destructive) { closing = selected; confirmClose = true } label: { Image(systemName: "trash") }
                            .accessibilityLabel("Close selected tabs").disabled(selected.isEmpty)
                    }.padding().background(Theme.surfaceContainer)
                }
            }
            .confirmationDialog("Close \(closing.count) tab\(closing.count == 1 ? "" : "s")?", isPresented: $confirmClose, titleVisibility: .visible) {
                Button("Close tabs", role: .destructive) {
                    store.closeTabs(closing); selected.removeAll(); selecting = false
                    if store.tabs.count == 1 && store.activeTab?.isHome == true { search = "" }
                }
            }
            .onChange(of: store.tabs.map(\.id)) { ids in selected.formIntersection(Set(ids)) }
        }
    }
}

private struct TabRow: View {
    @ObservedObject var tab: BrowserTab
    let active: Bool
    let selecting: Bool
    let checked: Bool
    let onSelect: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onSelect) {
                HStack(spacing: 12) {
                    if selecting {
                        Image(systemName: checked ? "checkmark.circle.fill" : "circle").foregroundStyle(Theme.primary)
                    } else if active {
                        Capsule().fill(Theme.primary).frame(width: 4, height: 32)
                    }
                    BrowserFaviconView(pageURL: tab.isHome ? nil : tab.urlString)
                        .frame(width: 28, height: 28)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text(tab.title.isEmpty ? "New Tab" : tab.title)
                                .font(Theme.font(.subheadline).weight(active ? .semibold : .medium))
                                .lineLimit(active ? 2 : 1).fixedSize(horizontal: false, vertical: true)
                            if tab.isMediaPlaying {
                                Image(systemName: "speaker.wave.2.fill")
                                    .font(.system(size: 18, weight: .medium))
                                    .foregroundStyle(Theme.primary)
                                    .frame(width: 22, height: 22).fixedSize()
                                    .accessibilityLabel("Playing media")
                            }
                            if tab.isLoading { ProgressView().controlSize(.mini).fixedSize() }
                        }
                        Text(tab.isHome ? "New Tab" : tab.urlString).font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.foregroundStyle(Theme.onSurface).padding(.vertical, 12).padding(.leading, 12)
                    .contentShape(Rectangle())
            }.buttonStyle(.plain)
                .accessibilityLabel("Open tab \(tab.title)")
                .accessibilityAddTraits(active ? .isSelected : [])
            if !selecting {
                Button(action: onClose) { Image(systemName: "xmark").frame(width: 44, height: 44) }
                    .buttonStyle(.plain).foregroundStyle(Theme.onSurfaceVariant)
                    .accessibilityLabel("Close \(tab.title)")
            } else { Spacer().frame(width: 12) }
        }
        .background((active || checked ? Theme.primary.opacity(0.1) : Theme.surfaceContainerLow), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(active ? Theme.primary : Theme.onSurfaceVariant.opacity(0.2), lineWidth: active ? 1.5 : 1))
    }
}

private struct TabListFrameKey: PreferenceKey {
    static var defaultValue: CGRect = .zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if next != .zero { value = next }
    }
}

/// Cancel the existing pan/deceleration before asking SwiftUI to jump to a row.
@MainActor
final class TabScrollDriver: ObservableObject {
    weak var scrollView: UIScrollView?

    func stopScrolling() {
        guard let scrollView else { return }
        let pan = scrollView.panGestureRecognizer
        let wasEnabled = pan.isEnabled
        pan.isEnabled = false
        scrollView.setContentOffset(scrollView.contentOffset, animated: false)
        pan.isEnabled = wasEnabled
    }
}

private struct TabScrollProbe: UIViewRepresentable {
    let driver: TabScrollDriver
    func makeUIView(context: Context) -> ProbeView { ProbeView(driver: driver) }
    func updateUIView(_ view: ProbeView, context: Context) { view.attach() }

    final class ProbeView: UIView {
        let driver: TabScrollDriver
        init(driver: TabScrollDriver) { self.driver = driver; super.init(frame: .zero); isUserInteractionEnabled = false }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override func didMoveToWindow() { super.didMoveToWindow(); attach() }
        func attach() {
            var ancestor = superview
            while let view = ancestor {
                if let scroll = view as? UIScrollView { driver.scrollView = scroll; return }
                ancestor = view.superview
            }
        }
    }
}

/// Act on finger-down: waiting for SwiftUI's release gesture can lose the tap as
/// the moving list updates the arrow's direction and redraws its controls.
struct TabJumpButton: UIViewRepresentable {
    let pointUp: Bool
    let action: () -> Void
    func makeUIView(context: Context) -> JumpButton { JumpButton() }
    func updateUIView(_ button: JumpButton, context: Context) {
        button.action = action
        button.setImage(UIImage(systemName: pointUp ? "arrow.up" : "arrow.down",
                                withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .semibold)), for: .normal)
        button.accessibilityLabel = pointUp ? "Scroll to top" : "Scroll to bottom"
    }

    final class JumpButton: UIButton {
        var action: (() -> Void)?
        init() {
            super.init(frame: .zero)
            backgroundColor = UIColor(Theme.surfaceContainer)
            tintColor = UIColor(Theme.primary)
            layer.cornerRadius = 24
            layer.shadowColor = UIColor.black.cgColor
            layer.shadowOpacity = 0.25
            layer.shadowRadius = 4
            layer.shadowOffset = CGSize(width: 0, height: 2)
            addTarget(self, action: #selector(jump), for: .touchDown)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        @objc private func jump() { action?() }
        override func accessibilityActivate() -> Bool { action?(); return true }
    }
}
