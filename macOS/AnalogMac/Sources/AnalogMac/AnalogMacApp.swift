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

                Button("Seek Back 10 Seconds") {
                    player.seek(to: max(0, player.currentTime - 10))
                }
                .keyboardShortcut(.leftArrow, modifiers: [.option])

                Button("Seek Forward 10 Seconds") {
                    let duration = player.currentTrack?.duration ?? player.currentTime + 10
                    player.seek(to: min(duration, player.currentTime + 10))
                }
                .keyboardShortcut(.rightArrow, modifiers: [.option])

                Divider()

                Button(player.shuffleQueue ? "Turn Shuffle Off" : "Turn Shuffle On") {
                    player.shuffleQueue.toggle()
                    player.persistState()
                }
                .keyboardShortcut("s", modifiers: [.command, .option])

                Button(player.repeatCurrentTrack ? "Repeat Track: On" : "Repeat Track: Off") {
                    player.repeatCurrentTrack.toggle()
                    if player.repeatCurrentTrack { player.repeatQueue = false }
                    player.persistState()
                }
                .keyboardShortcut("t", modifiers: [.command, .option])

                Button(player.repeatQueue ? "Repeat Queue: On" : "Repeat Queue: Off") {
                    player.repeatQueue.toggle()
                    if player.repeatQueue { player.repeatCurrentTrack = false }
                    player.persistState()
                }
                .keyboardShortcut("r", modifiers: [.command, .option])

                Button("Favorite Current Track") {
                    if let track = player.currentTrack { player.toggleFavorite(track) }
                }
                .disabled(player.currentTrack == nil)
                .keyboardShortcut("f", modifiers: [.command, .option])
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
