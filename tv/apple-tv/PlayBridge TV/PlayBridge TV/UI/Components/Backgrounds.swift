import SwiftUI

struct BrandingView: View {
    var body: some View {
        HStack(spacing: 0) {
            Image(systemName: "play.tv.fill").foregroundColor(Theme.accent).font(.title)
                .padding(.trailing, 10)
            Text("PLAY").font(.system(size: 28, weight: .black))
            Text("BRIDGE").font(.system(size: 28, weight: .light))
        }.foregroundColor(.white)
    }
}

struct AuroraBackgroundView: View {
    var time: Double
    var body: some View {
        ZStack {
            Color(red: 0.01, green: 0.01, blue: 0.03).ignoresSafeArea()
            Circle().fill(Color.blue.opacity(0.15)).blur(radius: 100).offset(
                x: sin(time) * 200, y: cos(time) * 200)
            Circle().fill(Color.purple.opacity(0.15)).blur(radius: 100).offset(
                x: -sin(time) * 200, y: -cos(time) * 200)
        }
    }
}
