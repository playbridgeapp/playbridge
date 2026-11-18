import AppKit
import Foundation

// The 16:9 fixture opens in landscape (Settings › Phone player › Fullscreen), and the
// simulator's screenshot stays in portrait pixels, so the landscape side bars are the
// screenshot's top and bottom 9% (sampled below the Dynamic Island). Saturated pixels there must be absent in Fit
// (pillarboxing), and present in Fill. Checking the requested property or bounds
// alone cannot detect an unimplemented crop in the embedded renderer.
for path in CommandLine.arguments.dropFirst() {
    guard let image = NSImage(contentsOfFile: path), let tiff = image.tiffRepresentation,
          let bitmap = NSBitmapImageRep(data: tiff) else { fatalError("Missing screenshot: \(path)") }
    let name = URL(fileURLWithPath: path).lastPathComponent
    if name.contains("subtitle-") {
        // The probe's style is extra-large text in a box over a black frame. In landscape the
        // caption runs along the screenshot's left edge (the picture's bottom), clear of the
        // seek bar, the chips and the icons.
        var text = 0
        for y in stride(from: Int(Double(bitmap.pixelsHigh) * 0.3), to: Int(Double(bitmap.pixelsHigh) * 0.75), by: 2) {
            for x in stride(from: 0, to: Int(Double(bitmap.pixelsWide) * 0.2), by: 2) {
                guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB) else { continue }
                let rgb = [color.redComponent, color.greenComponent, color.blueComponent]
                if rgb.max()! > 0.3 && rgb.max()! - rgb.min()! < 0.12 { text += 1 }
            }
        }
        let delayed = name.contains("delayed")
        guard delayed ? text == 0 : text > 50 else { fatalError("Incorrect native subtitle style/timing: \(text) caption pixels") }
        let description = delayed ? "positive subtitle delay" : "extra-large subtitle styling"
        print("PASS native \(description): \(text) caption pixels")
        continue
    }
    var coloured = 0
    for yFraction in [0.075, 0.925] { // clear of the Dynamic Island cut-out
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
