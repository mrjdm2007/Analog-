// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AnalogMac",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "AnalogMac", targets: ["AnalogMac"])],
    targets: [
        .executableTarget(name: "AnalogMac", path: "Sources/AnalogMac"),
        .testTarget(name: "AnalogAudioTests", path: "Tests/AnalogAudioTests")
    ]
)
