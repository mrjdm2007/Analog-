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
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Import Audio Files…") {
                    player.importAudioFiles()
                }
                .keyboardShortcut("o", modifiers: [.command, .shift])

                Button("Choose Music Folder…") {
                    player.importMusicFolder()
                }
                .keyboardShortcut("o", modifiers: [.command])

                Button("Rescan Music Folder") {
                    if let folder = UserDefaults.standard.string(forKey: "musicFolder") {
                        player.scan(URL(fileURLWithPath: folder))
                    } else {
                        player.importMusicFolder()
                    }
                }
                .keyboardShortcut("r", modifiers: [.command, .shift])
            }

            CommandMenu("Playback") {
                Button(player.isPlaying ? "Pause" : "Play") {
                    player.togglePlayback()
                }
                .keyboardShortcut(.space, modifiers: [])

                Divider()

                Button("Previous Track") {
                    player.previous()
                }
                .keyboardShortcut(.leftArrow, modifiers: [.command])

                Button("Next Track") {
                    player.next()
                }
                .keyboardShortcut(.rightArrow, modifiers: [.command])

                Divider()

                Button("Show Queue") {
                    player.selectedTab = "Queue"
                }
                .keyboardShortcut("q", modifiers: [.command, .shift])
            }

            CommandMenu("Navigate") {
                Button("Music Library") { player.selectedTab = "Library" }
                    .keyboardShortcut("1", modifiers: [.command])
                Button("Equalizer") { player.selectedTab = "Equalizer" }
                    .keyboardShortcut("2", modifiers: [.command])
                Button("Visualiser") { player.selectedTab = "Visualiser" }
                    .keyboardShortcut("3", modifiers: [.command])
                Button("Recorder") { player.selectedTab = "Recorder" }
                    .keyboardShortcut("4", modifiers: [.command])
                Button("Playlists") { player.selectedTab = "Playlists" }
                    .keyboardShortcut("5", modifiers: [.command])
                Button("Settings") { player.selectedTab = "Settings" }
                    .keyboardShortcut(",", modifiers: [.command])
            }
        }
    }
}
