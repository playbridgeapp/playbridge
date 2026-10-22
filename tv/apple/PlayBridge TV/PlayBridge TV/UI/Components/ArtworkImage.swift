import SwiftUI

/// Loads the first artwork URL that succeeds (`ArtworkURL.candidates`): the
/// upgraded 4K size, then the URL as sent. `onResult` reports once: true when an
/// image is shown, false when every candidate failed or none was given.
struct ArtworkImage<Content: View, Placeholder: View>: View {
    let urls: [URL]
    var onResult: (Bool) -> Void = { _ in }
    @ViewBuilder let content: (Image) -> Content
    @ViewBuilder let placeholder: () -> Placeholder
    @State private var index = 0

    var body: some View {
        if urls.indices.contains(index) {
            AsyncImage(url: urls[index]) { phase in
                switch phase {
                case .success(let image):
                    content(image).onAppear { onResult(true) }
                case .failure:
                    placeholder().onAppear {
                        if urls.indices.contains(index + 1) { index += 1 } else { onResult(false) }
                    }
                default:
                    placeholder()
                }
            }
            .id(index)
        } else {
            placeholder().onAppear { onResult(false) }
        }
    }
}
