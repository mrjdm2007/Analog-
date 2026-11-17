import SwiftUI

@main
struct AnalogMacApp: App {
    @StateObject private var player = AnalogPlayer()
    @StateObject private var recorder = AnalogRecorder()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(player)
                .environmentObject(recorder)
        }
        .windowStyle(.titleBar)
        .defaultSize(width: 1440, height: 920)
    }
}
