import AppKit
import Foundation

// Saturated fixture pixels at 25%/75% height must be absent in portrait Fit
// (letterboxing), and present in Fill. Checking the requested property or bounds
// alone cannot detect an unimplemented crop in the embedded renderer.
for path in CommandLine.arguments.dropFirst() {
    guard let image = NSImage(contentsOfFile: path), let tiff = image.tiffRepresentation,
          let bitmap = NSBitmapImageRep(data: tiff) else { fatalError("Missing screenshot: \(path)") }
    let name = URL(fileURLWithPath: path).lastPathComponent
    if name.contains("subtitle-") {
        var yellow = 0
        for y in stride(from: bitmap.pixelsHigh / 3, to: bitmap.pixelsHigh, by: 2) {
            for x in stride(from: 0, to: bitmap.pixelsWide, by: 2) {
                guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB) else { continue }
                if color.redComponent > 0.7 && color.greenComponent > 0.7 && color.blueComponent < 0.3 { yellow += 1 }
            }
        }
        let delayed = name.contains("delayed")
        guard delayed ? yellow == 0 : yellow > 50 else { fatalError("Incorrect native subtitle style/timing: \(yellow) yellow pixels") }
        let description = delayed ? "positive subtitle delay" : "yellow subtitle styling"
        print("PASS native \(description): \(yellow) yellow pixels")
        continue
    }
    var coloured = 0
    for yFraction in [0.25, 0.75] {
        for i in 0..<20 {
            let x = Int(Double(bitmap.pixelsWide) * (0.2 + Double(i) * 0.03))
            let y = Int(Double(bitmap.pixelsHigh) * yFraction)
            guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB) else { continue }
            let rgb = [color.redComponent, color.greenComponent, color.blueComponent]
            if rgb.max()! > 0.35 && rgb.max()! - rgb.min()! > 0.25 { coloured += 1 }
        }
    }
    let fill = URL(fileURLWithPath: path).lastPathComponent.contains("-fill")
    guard fill ? coloured >= 32 : coloured <= 2 else { fatalError("Incorrect \(fill ? "Fill" : "Fit") rendering: \(coloured)/40 coloured edge samples") }
    print("PASS native \(fill ? "Fill crops to viewport" : "Fit preserves letterboxing"): \(coloured)/40 coloured edge samples")
}
