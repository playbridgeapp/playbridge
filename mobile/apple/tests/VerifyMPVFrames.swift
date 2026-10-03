import AppKit

// Generated testsrc2 frames are saturated. Checking the centre catches a Metal
// drawable that has stale portrait dimensions, even when the UIView's frame is valid.
for path in CommandLine.arguments.dropFirst() {
    guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)), let image = NSBitmapImageRep(data: data) else {
        print("FAIL: Could not read simulator frame \(path)"); exit(1)
    }
    var coloured = 0
    for y in 0..<10 {
        for x in 0..<10 {
            let px = Int(Double(image.pixelsWide) * (0.40 + Double(x) * 0.02))
            let py = Int(Double(image.pixelsHigh) * (0.40 + Double(y) * 0.02))
            guard let colour = image.colorAt(x: px, y: py)?.usingColorSpace(NSColorSpace.deviceRGB) else { continue }
            let channels = [colour.redComponent, colour.greenComponent, colour.blueComponent]
            if channels.max()! > 0.2 && channels.max()! - channels.min()! > 0.15 { coloured += 1 }
        }
    }
    guard coloured >= 25 else {
        print("FAIL: Video is not centred in \(URL(fileURLWithPath: path).lastPathComponent) (\(coloured)/100 coloured samples)")
        exit(1)
    }
    print("PASS centred native video: \(URL(fileURLWithPath: path).lastPathComponent) (\(coloured)/100)")
}
