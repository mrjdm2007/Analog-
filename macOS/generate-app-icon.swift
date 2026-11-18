import AppKit
import Foundation

guard CommandLine.arguments.count == 2 else {
    fatalError("Usage: generate-app-icon.swift <output.icns>")
}
let output = URL(fileURLWithPath: CommandLine.arguments[1])
let iconset = output.deletingLastPathComponent().appendingPathComponent("Analog.iconset", isDirectory: true)
try? FileManager.default.removeItem(at: iconset)
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)

let sizes = [16, 32, 64, 128, 256, 512, 1024]
for size in sizes {
    let image = NSImage(size: NSSize(width: size, height: size))
    image.lockFocus()
    let rect = NSRect(x: 0, y: 0, width: size, height: size)
    let inset = CGFloat(size) * 0.035
    let background = NSBezierPath(roundedRect: rect.insetBy(dx: inset, dy: inset),
                                  xRadius: CGFloat(size) * 0.22,
                                  yRadius: CGFloat(size) * 0.22)
    NSGradient(colors: [NSColor(calibratedWhite: 0.24, alpha: 1),
                        NSColor(calibratedWhite: 0.035, alpha: 1)])!
        .draw(in: background, angle: -45)

    let center = NSPoint(x: CGFloat(size) / 2, y: CGFloat(size) / 2)
    let vinylSize = CGFloat(size) * 0.70
    let vinylRect = NSRect(x: center.x - vinylSize / 2, y: center.y - vinylSize / 2,
                           width: vinylSize, height: vinylSize)
    NSColor(calibratedWhite: 0.025, alpha: 1).setFill()
    NSBezierPath(ovalIn: vinylRect).fill()

    for fraction in [0.90, 0.74, 0.57, 0.39] {
        let diameter = vinylSize * fraction
        let ring = NSBezierPath(ovalIn: NSRect(x: center.x - diameter / 2,
                                              y: center.y - diameter / 2,
                                              width: diameter, height: diameter))
        ring.lineWidth = max(0.5, CGFloat(size) * 0.006)
        NSColor(calibratedWhite: 0.55, alpha: 0.22).setStroke()
        ring.stroke()
    }

    let labelSize = vinylSize * 0.31
    let label = NSRect(x: center.x - labelSize / 2, y: center.y - labelSize / 2,
                       width: labelSize, height: labelSize)
    NSColor(calibratedRed: 1.0, green: 0.67, blue: 0.18, alpha: 1).setFill()
    NSBezierPath(ovalIn: label).fill()
    NSColor(calibratedWhite: 0.08, alpha: 1).setFill()
    NSBezierPath(ovalIn: NSRect(x: center.x - labelSize * 0.10,
                               y: center.y - labelSize * 0.10,
                               width: labelSize * 0.20, height: labelSize * 0.20)).fill()

    let title = NSAttributedString(string: "A",
        attributes: [.font: NSFont.systemFont(ofSize: CGFloat(size) * 0.14, weight: .black),
                     .foregroundColor: NSColor(calibratedWhite: 0.96, alpha: 0.92)])
    let titleSize = title.size()
    title.draw(at: NSPoint(x: center.x - titleSize.width / 2,
                           y: CGFloat(size) * 0.105 - titleSize.height / 2))
    image.unlockFocus()

    guard let tiff = image.tiffRepresentation,
          let bitmap = NSBitmapImageRep(data: tiff),
          let png = bitmap.representation(using: .png, properties: [:]) else {
        fatalError("Could not render icon at \(size)px")
    }
    let baseName: String
    switch size {
    case 16: baseName = "icon_16x16"
    case 32: baseName = "icon_32x32"
    case 128: baseName = "icon_128x128"
    case 256: baseName = "icon_256x256"
    case 512: baseName = "icon_512x512"
    default: baseName = "icon_1024x1024"
    }
    try png.write(to: iconset.appendingPathComponent(baseName + ".png"), options: .atomic)
    if [16, 32, 128, 256, 512].contains(size) {
        try png.write(to: iconset.appendingPathComponent(baseName + "@2x.png"), options: .atomic)
    }
}
let process = Process()
process.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
process.arguments = ["-c", "icns", iconset.path, "-o", output.path]
try process.run()
process.waitUntilExit()
guard process.terminationStatus == 0 else {
    fatalError("iconutil failed with status \(process.terminationStatus)")
}
try? FileManager.default.removeItem(at: iconset)
print("Generated \(output.path)")
