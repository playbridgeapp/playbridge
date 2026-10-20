import SwiftUI
import UniformTypeIdentifiers

/// One stable-height sheet; adding never replaces the presentation or discards its filters.
struct RemoteSubtitlesSheet: View {
    let tracks: [MediaTrack]
    let canSelect: Bool
    let canAdjust: Bool
    let canAdd: Bool
    let offset: Int64
    let airPlay: Bool
    let preparing: Bool
    let detector: VideoDetector?
    let detected: [DetectedVideo]
    let destinationID: String?
    let select: (String) -> Void
    let adjust: (Int64) -> Void
    let addDetected: (DetectedVideo) -> Bool
    let addLocal: (URL) async -> Bool
    let addLink: (String) -> Bool
    let done: () -> Void

    private enum Page: String {
        case tracks = "Tracks", adjust = "Adjust", add = "Add"
        var icon: String { switch self { case .tracks: "captions.bubble"; case .adjust: "slider.horizontal.3"; case .add: "plus" } }
    }
    private enum Source: String { case found, file, link }
    @State private var page: Page = .tracks
    @State private var origin: SubtitleOrigin?
    @State private var language: String?
    @State private var query = ""
    @State private var source: Source = .file
    @State private var link = ""
    @State private var importing = false
    @State private var working = false
    @State private var error: String?

    private var pages: [Page] { (canSelect ? [.tracks] : []) + (canAdjust ? [.adjust] : []) + (canAdd ? [.add] : []) }
    private var selectedPage: Page { pages.contains(page) ? page : pages.first ?? .tracks }
    private var rows: [SubtitleRow] { RemoteControlPresentation.subtitleRows(tracks) }
    private var current: SubtitleRow? { rows.first { $0.track.selected } }
    private var byOrigin: [SubtitleRow] { RemoteControlPresentation.filterSubtitles(rows, origin: origin, language: nil, query: "") }
    private var languages: [(String, Int)] {
        let preferred = [Locale.preferredLanguages.first.flatMap { RemoteControlPresentation.language(Locale(identifier: $0).language.languageCode?.identifier ?? "") }, current?.language]
        let choices = RemoteControlPresentation.languageFilters(byOrigin, preferred: preferred)
        return byOrigin.count > 8 && choices.count > 1 ? choices : []
    }
    private var shown: [SubtitleRow] {
        RemoteControlPresentation.filterSubtitles(byOrigin, origin: nil,
            language: languages.contains(where: { $0.0 == language }) ? language : nil, query: byOrigin.count > 8 ? query : "")
    }
    private var offID: String { tracks.first(where: RemoteControlPresentation.isOff)?.id ?? "none" }
    private var height: CGFloat { min(560, max(320, UIScreen.main.bounds.height * 0.6)) }
    private var validLink: String? {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: text), ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !host.isEmpty else { return nil }
        return text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Subtitles").font(Theme.font(.title2).bold())
                Spacer()
                Button("Done", action: done).frame(minHeight: 44)
            }
            Text(current.map { "On: " + $0.label } ?? "Off")
                .font(Theme.font(.subheadline)).foregroundStyle(current == nil ? Theme.onSurfaceVariant : Theme.primary)
                .lineLimit(1)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(pages, id: \.self) { candidate in
                        chip(candidate.rawValue, icon: candidate.icon, selected: selectedPage == candidate, filled: true) { page = candidate }
                    }
                }
            }.fixedSize(horizontal: false, vertical: true)
            Group {
                switch selectedPage {
                case .tracks: tracksPage
                case .adjust: adjustPage
                case .add: addPage
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            if airPlay {
                Text("Added SRT or WebVTT subtitles require a compatible HLS stream and Wi-Fi. Adding a track may briefly reload the video at its current position.")
                    .font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.surface).tint(Theme.primary)
        .presentationDetents([.height(height)]).presentationDragIndicator(.visible)
        .onAppear { source = detected.isEmpty ? .file : .found }
        .onChange(of: origin) { _ in language = nil; query = "" }
        .onChange(of: rows.map { $0.origin }) { _ in
            if let origin, !rows.contains(where: { $0.origin == origin }) { self.origin = nil }
        }
        .onChange(of: destinationID) { _ in importing = false; working = false }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            guard case .success(let file) = result else { return }
            guard ["srt", "vtt"].contains(file.pathExtension.lowercased()) else {
                error = "Choose an SRT or WebVTT subtitle file."; return
            }
            working = true
            Task { @MainActor in
                let scoped = file.startAccessingSecurityScopedResource()
                defer { if scoped { file.stopAccessingSecurityScopedResource() }; working = false }
                added(await addLocal(file))
            }
        }
    }

    private var tracksPage: some View {
        VStack(spacing: 8) {
            let origins = RemoteControlPresentation.originFilters(rows)
            if origins.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        chip("All \(rows.count)", selected: origin == nil) { origin = nil }
                        ForEach(origins, id: \.0) { value, count in
                            chip("\(value.title) \(count)", selected: origin == value) { origin = value }
                        }
                    }
                }.fixedSize(horizontal: false, vertical: true)
            }
            if !languages.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(languages, id: \.0) { value, count in
                            chip("\(value) \(count)", selected: language == value) { language = language == value ? nil : value }
                        }
                    }
                }.fixedSize(horizontal: false, vertical: true)
            }
            if byOrigin.count > 8 {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(Theme.onSurfaceVariant)
                    TextField("Search subtitles", text: $query).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Clear search") }
                }.padding(.horizontal, 12).frame(height: 40).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
            }
            ScrollViewReader { reader in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        trackRow("Off", id: offID, selected: current == nil, tag: nil).id(offID)
                        ForEach(shown) { row in
                            trackRow(row.label, id: row.id, selected: row.track.selected,
                                tag: origin == nil && !row.track.selected ? row.origin.tag : nil).id(row.id)
                        }
                    }
                }
                .onAppear {
                    guard let index = shown.firstIndex(where: { $0.track.selected }) else { return }
                    reader.scrollTo(index > 0 ? shown[index - 1].id : offID, anchor: .top)
                }
            }
        }
    }

    private func trackRow(_ label: String, id: String, selected: Bool, tag: String?) -> some View {
        Button { select(id) } label: {
            HStack(spacing: 8) {
                Text(label).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                if let tag { Text(tag).font(Theme.font(.caption2)).padding(.horizontal, 6).padding(.vertical, 3).background(Theme.surfaceContainerHigh, in: Capsule()) }
                if selected { Image(systemName: "checkmark").foregroundStyle(Theme.primary) }
            }.font(Theme.font(.subheadline)).padding(.horizontal, 10).frame(minHeight: 48)
                .foregroundStyle(selected ? Theme.primary : Theme.onSurface)
                .background(selected ? Theme.primary.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var adjustPage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Timing").font(Theme.font(.headline)).foregroundStyle(Theme.onSurfaceVariant)
                Text(RemoteControlPresentation.offsetText(offset)).font(Theme.font(size: 32, weight: .semibold)).monospacedDigit()
                    .foregroundStyle(offset == 0 ? Theme.onSurface : Theme.primary)
                    .frame(maxWidth: .infinity, alignment: .center)
                HStack(spacing: 8) {
                    ForEach([-500, -100, 100, 500], id: \.self) { step in
                        Button(RemoteControlPresentation.offsetStepLabel(Int64(step))) { adjust(Int64(step)) }
                            .frame(maxWidth: .infinity, minHeight: 44).background(Theme.surfaceContainerHigh, in: Capsule())
                    }
                }
                HStack(alignment: .center, spacing: 12) {
                    Text("If subtitles are out of sync with speech, nudge them until they line up.")
                        .font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("Reset") { adjust(-offset) }
                        .font(Theme.font(.subheadline).weight(.semibold))
                        .foregroundStyle(offset == 0 ? Theme.onSurfaceVariant.opacity(0.5) : Theme.primary)
                        .frame(minWidth: 44, minHeight: 44)
                        .disabled(offset == 0)
                }
            }.padding(.top, 12)
        }
    }

    private var addPage: some View {
        VStack(alignment: .leading, spacing: 12) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    if !detected.isEmpty { chip("Found on page \(detected.count)", selected: source == .found) { source = .found } }
                    chip("Phone file", selected: source == .file) { source = .file }
                    chip("Link", selected: source == .link) { source = .link }
                }
            }.fixedSize(horizontal: false, vertical: true)
            if let error { Text(error).font(Theme.font(.caption)).foregroundStyle(Theme.danger) }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if source == .found && !detected.isEmpty {
                        ForEach(SubtitleOrdering.newestFirst(detected)) { subtitle in
                            Button { added(addDetected(subtitle)) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(subtitle.displayTitle).font(Theme.font(.subheadline).bold())
                                    Text("Detected \(Date(timeIntervalSince1970: Double(subtitle.timestamp) / 1000).formatted(date: .omitted, time: .standard))")
                                        .font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
                                    if let detector { DetectedSubtitlePreview(detector: detector, subtitle: subtitle) }
                                }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                                    .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                            }.buttonStyle(.plain)
                        }
                    } else if source == .link {
                        TextField("Subtitle URL (.srt / .vtt)", text: $link)
                            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                        Button("Add link") { if let validLink { added(addLink(validLink)) } }
                            .buttonStyle(.borderedProminent).disabled(validLink == nil)
                    } else {
                        Text("Choose an SRT or WebVTT file from this phone.").font(Theme.font(.subheadline)).foregroundStyle(Theme.onSurfaceVariant)
                        Button { importing = true } label: { Label("Choose file", systemImage: "folder") }.buttonStyle(.borderedProminent)
                    }
                    if working { ProgressView("Adding subtitles…") }
                }
            }.disabled(working || preparing)
        }
    }

    private func added(_ success: Bool) {
        if success {
            error = nil; origin = nil; language = nil; query = ""
            page = canSelect ? .tracks : pages.first ?? .add
        }
        else { error = "Couldn’t add this subtitle. Check Wi-Fi and the receiver connection." }
    }

    private func chip(_ title: String, icon: String? = nil, selected: Bool, filled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.system(size: 14, weight: .semibold)) }
                Text(title)
            }.font(Theme.font(.caption).weight(.semibold)).padding(.horizontal, 12).frame(minHeight: filled ? 44 : 36)
                .foregroundStyle(selected ? (filled ? Theme.onPrimary : Theme.primary) : Theme.onSurfaceVariant)
                .background(selected ? (filled ? Theme.primary : Theme.primary.opacity(0.12)) : .clear, in: Capsule())
                .overlay(Capsule().stroke(selected && filled ? .clear : Theme.outlineVariant, lineWidth: 1))
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }
}
