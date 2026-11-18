// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AnalogMac",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "AnalogCore", targets: ["AnalogCore"]),
        .executable(name: "AnalogMac", targets: ["AnalogMac"])
    ],
    targets: [
        .target(name: "AnalogCore", path: "Sources/AnalogCore"),
        .executableTarget(name: "AnalogMac", dependencies: ["AnalogCore"], path: "Sources/AnalogMac"),
        .testTarget(name: "AnalogAudioTests", dependencies: ["AnalogCore"], path: "Tests/AnalogAudioTests")
    ]
)
