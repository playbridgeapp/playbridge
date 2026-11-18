import SwiftUI
import UniformTypeIdentifiers

/// A subtitle line drawn the way the players draw plain subtitles.
struct SubtitleCaptionText: View {
    let text: String
    let style: SubtitleStylePreferences
    var baseSize: CGFloat = 20
    /// Draw the custom font when it is installed on this phone.
    var usesCustomFont = true

    var body: some View {
        let size = baseSize * CGFloat(style.size.scale)
        Text(text)
            .font(font(size: size))
            .foregroundColor(.white)
            .multilineTextAlignment(.center)
            .modifier(SubtitleEdge(edge: style.background == .none ? style.edge : .none, size: size))
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(Color.black.opacity(style.background.opacity), in: RoundedRectangle(cornerRadius: 4))
    }

    private func font(size: CGFloat) -> Font {
        if usesCustomFont, style.font == .custom, let family = style.customFont,
           let font = SubtitleFontLibrary.shared.uiFont(family: family, size: size) {
            return Font(font)
        }
        return .system(size: size, weight: .semibold)
    }
}

/// mpv draws edges only without a background box, so the preview does the same.
private struct SubtitleEdge: ViewModifier {
    let edge: SubtitleEdgeOption
    let size: CGFloat

    func body(content: Content) -> some View {
        switch edge {
        case .outline:
            let width = max(1, size / 20)
            content
                .shadow(color: .black, radius: 0, x: width, y: width)
                .shadow(color: .black, radius: 0, x: -width, y: -width)
                .shadow(color: .black, radius: 0, x: width, y: -width)
                .shadow(color: .black, radius: 0, x: -width, y: width)
        case .dropShadow:
            content.shadow(color: .black.opacity(0.85), radius: 3, x: 2, y: 3)
        case .none:
            content
        }
    }
}

/// Names for the style's choices, in the editor and in Settings summaries.
enum SubtitleStyleLabels {
    static func short(_ size: SubtitleSizeOption) -> String {
        switch size {
        case .small: return "S"
        case .medium: return "M"
        case .large: return "L"
        case .extraLarge: return "XL"
        }
    }

    static func name(_ size: SubtitleSizeOption) -> String {
        switch size {
        case .small: return "Small"
        case .medium: return "Medium"
        case .large: return "Large"
        case .extraLarge: return "Extra large"
        }
    }

    static func name(_ background: SubtitleBackgroundOption) -> String {
        switch background {
        case .none: return "None"
        case .dim: return "See-through"
        case .box: return "Solid"
        }
    }

    static func name(_ edge: SubtitleEdgeOption) -> String {
        switch edge {
        case .outline: return "Outline"
        case .dropShadow: return "Shadow"
        case .none: return "None"
        }
    }

    /// "Medium · Outline · Poppins": size, then the box or (without one) the edge, then a custom font.
    static func summary(_ style: SubtitleStylePreferences) -> String {
        var parts = [name(style.size)]
        switch style.background {
        case .none: parts.append(style.edge == .none ? "No edge" : name(style.edge))
        case .dim: parts.append("See-through box")
        case .box: parts.append("Solid box")
        }
        if style.font == .custom, let family = style.customFont { parts.append(family) }
        return parts.joined(separator: " · ")
    }
}

/// The custom font part of the editor, for the phone player or a TV.
struct SubtitleFontChoices {
    /// Installed custom font families, selected or not.
    let installed: [String]
    /// Progress or an error, shown under the fonts.
    let status: String?
    /// A font is being installed; picking another waits.
    let busy: Bool
    let onPick: (URL) -> Void
    let onRemove: (String) -> Void
}

/// Edits a subtitle style with a live preview, as Form sections. Used for the phone player
/// and, over the connection, for a TV's style; `enabled` is false until the TV reports it.
struct SubtitleStyleEditor: View {
    let style: SubtitleStylePreferences
    let onChange: (SubtitleStylePreferences) -> Void
    var enabled = true
    var fonts: SubtitleFontChoices?
    /// The TV's fonts may not be on this phone, so its preview uses the default font.
    var previewUsesCustomFont = true
    @State private var picking = false

    var body: some View {
        Section {
            preview
                .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Subtitle preview")
        }
        if let fonts { fontSection(fonts) }
        Section {
            choice("Size", selection: style.size, options: SubtitleSizeOption.allCases, label: SubtitleStyleLabels.short) { var next = style; next.size = $0; onChange(next) }
            choice("Background", selection: style.background, options: SubtitleBackgroundOption.allCases, label: SubtitleStyleLabels.name) { var next = style; next.background = $0; onChange(next) }
            choice("Edge", selection: style.edge, options: SubtitleEdgeOption.allCases, label: SubtitleStyleLabels.name) { var next = style; next.edge = $0; onChange(next) }
        }
        .disabled(!enabled)
        Section {
            Toggle("Restyle styled subtitles", isOn: Binding(get: { style.overrideStyled }, set: { value in
                var next = style; next.overrideStyled = value; onChange(next)
            }))
            .tint(Theme.primary)
            .disabled(!enabled)
        } footer: {
            Text("Also apply this style to subtitles with their own styling, such as ASS. Off keeps their original look.")
        }
    }

    private var preview: some View {
        ZStack(alignment: .bottom) {
            LinearGradient(colors: [Color(hex: 0xF4E3B1), Color(hex: 0x7A9CC6), Color(hex: 0x22303F)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            SubtitleCaptionText(text: "Subtitles look like this\nالترجمة تبدو هكذا", style: style,
                                baseSize: 18, usesCustomFont: previewUsesCustomFont)
                .padding(12)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 132)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func fontSection(_ fonts: SubtitleFontChoices) -> some View {
        let usable = enabled && !fonts.busy
        return Section {
            fontRow("Default", selected: style.font != .custom, enabled: usable, onRemove: nil) {
                var next = style; next.font = .default; next.customFont = nil; onChange(next)
            }
            ForEach(fonts.installed, id: \.self) { family in
                fontRow(family, selected: style.font == .custom && style.customFont == family, enabled: usable,
                        onRemove: { fonts.onRemove(family) }) {
                    var next = style; next.font = .custom; next.customFont = family; onChange(next)
                }
            }
            Button("Add a font file…") { picking = true }
                .disabled(!usable || fonts.installed.count >= SubtitleFontLimits.maxFonts)
        } header: {
            Text("Font")
        } footer: {
            Text(fonts.status ?? "Up to \(SubtitleFontLimits.maxFonts) .ttf or .otf files, 30 MB in all. Characters a font lacks use the device's fonts.")
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.font]) { result in
            if case .success(let url) = result { fonts.onPick(url) }
        }
    }

    private func fontRow(_ name: String, selected: Bool, enabled: Bool, onRemove: (() -> Void)?,
                         onSelect: @escaping () -> Void) -> some View {
        HStack {
            Button(action: onSelect) {
                HStack {
                    Text(name).foregroundColor(Theme.onSurface).lineLimit(1)
                    Spacer()
                    if selected { Image(systemName: "checkmark").foregroundColor(Theme.primary) }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityAddTraits(selected ? .isSelected : [])
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "trash").foregroundColor(Theme.danger)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Remove \(name)")
            }
        }
        .disabled(!enabled)
    }

    private func choice<Option: Hashable>(_ title: String, selection: Option, options: [Option],
                                          label: @escaping (Option) -> String,
                                          set: @escaping (Option) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
            Picker(title, selection: Binding(get: { selection }, set: set)) {
                ForEach(options, id: \.self) { Text(label($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
        .padding(.vertical, 2)
    }
}

/// The phone player's subtitle style and custom fonts, kept on this phone.
struct PhoneSubtitleStyleSections: View {
    @ObservedObject private var store = SubtitleStyleStore.shared
    @State private var status: String?
    @State private var busy = false

    var body: some View {
        SubtitleStyleEditor(
            style: store.style,
            onChange: { store.setStyle($0) },
            fonts: SubtitleFontChoices(
                installed: store.installedFonts,
                status: status,
                busy: busy,
                onPick: { url in
                    status = nil
                    busy = true
                    Task { @MainActor in
                        let picked = await Task.detached { PickedSubtitleFont.read(url) }.value
                        switch picked {
                        case .rejected(let message): status = message
                        case .font(let data, _):
                            if case .failed(let code) = store.installFont(data) {
                                status = PickedSubtitleFont.message(for: code, toTV: false)
                            }
                        }
                        busy = false
                    }
                },
                onRemove: { family in
                    status = nil
                    store.removeFont(family)
                }
            )
        )
    }
}
