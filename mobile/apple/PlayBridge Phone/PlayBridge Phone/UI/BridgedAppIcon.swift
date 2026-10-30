import SwiftUI

/// Dashboard, reorder and Info share the same persistent image and failure fallback.
struct BridgedAppIcon: View {
    let app: BridgedApp
    @State private var image: UIImage?

    init(app: BridgedApp) {
        self.app = app
        _image = State(initialValue: BridgedAppIconCache.shared.cachedData(app).flatMap { UIImage(data: $0) })
    }

    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFit() }
            else {
                Text(BridgedAppAppearance.monogram(app.name))
                    .font(.system(size: 22, weight: .bold)).foregroundColor(.white)
            }
        }
        .accessibilityHidden(true)
        .task(id: app.origin.absoluteString + (app.iconURL?.absoluteString ?? "")) {
            image = BridgedAppIconCache.shared.cachedData(app).flatMap { UIImage(data: $0) }
            let data = await BridgedAppIconCache.shared.load(app)
            guard !Task.isCancelled else { return }
            image = data.flatMap { UIImage(data: $0) }
        }
    }
}
