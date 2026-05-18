import SwiftUI

struct BrandingView: View {
    var body: some View {
        HStack(spacing: 12) {
            Image("Logo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 60, height: 60)
                .cornerRadius(12)
                .shadow(color: .black.opacity(0.3), radius: 10, x: 0, y: 5)
            
            VStack(alignment: .leading, spacing: -4) {
                Text("PLAY")
                    .font(.system(size: 32, weight: .black))
                Text("BRIDGE")
                    .font(.system(size: 32, weight: .light))
                    .foregroundColor(.white.opacity(0.7))
            }
        }
        .foregroundColor(.white)
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
