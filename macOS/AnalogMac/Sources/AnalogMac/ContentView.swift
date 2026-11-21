import AppKit
import AnalogCore
import SwiftUI

private enum AnalogPalette {
    static let ink = Color(red: 0.055, green: 0.063, blue: 0.055)
    static let panel = Color(red: 0.105, green: 0.115, blue: 0.10)
    static let raised = Color(red: 0.15, green: 0.16, blue: 0.14)
    static let cream = Color(red: 0.91, green: 0.88, blue: 0.80)
    static let amber = Color(red: 1.0, green: 0.69, blue: 0.35)
    static let green = Color(red: 0.70, green: 0.83, blue: 0.60)
    static let muted = Color(red: 0.56, green: 0.57, blue: 0.52)
}

struct ContentView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @EnvironmentObject private var recorder: AnalogRecorder
    @State private var mainWindowVisible = true
    @State private var visualizerFocusMode = false

    var body: some View {
        Group {
            if visualizerFocusMode {
                VisualiserView(focusMode: $visualizerFocusMode)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                SidebarView()
                    .frame(width: 218)
                Rectangle().fill(AnalogPalette.cream.opacity(0.10)).frame(width: 1)
                VStack(spacing: 0) {
                    topBar
                    Rectangle().fill(AnalogPalette.cream.opacity(0.08)).frame(height: 1)
                    mainContent
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                Rectangle().fill(AnalogPalette.cream.opacity(0.10)).frame(width: 1)
                NowPlayingPanel()
                    .frame(width: 286)
            }
            Rectangle().fill(AnalogPalette.cream.opacity(0.12)).frame(height: 1)
                    TransportBar()
                }
            }
        }
        .background(AnalogPalette.ink)
        .animation(.easeInOut(duration: 0.22), value: visualizerFocusMode)
        .background(SpacebarShortcutMonitor { player.togglePlayback() }.frame(width: 0, height: 0))
        .background(FocusModeShortcutMonitor {
            if visualizerFocusMode {
                visualizerFocusMode = false
            } else {
                // Focus Mode is a full-screen visualizer, so route the shortcut to
                // the Visualiser workspace before presenting it from another tab.
                if player.selectedTab != "Visualiser" { player.selectedTab = "Visualiser" }
                visualizerFocusMode = true
            }
        }.frame(width: 0, height: 0))
        .background(EscapeKeyMonitor(isActive: visualizerFocusMode) {
            visualizerFocusMode = false
        }.frame(width: 0, height: 0))
        .background(CursorVisibilityController(isHidden: visualizerFocusMode).frame(width: 0, height: 0))
        .background(ArrowKeyNavigationMonitor(
            onNavigate: { delta in
                if visualizerFocusMode {
                    if delta < 0 { player.previous() } else { player.next() }
                    return
                }
                let tabs = ["Library", "Queue", "History", "Smart Mixes", "Moods", "Playlists", "Equalizer", "Visualiser", "Recorder", "Settings"]
                let current = tabs.firstIndex(of: player.selectedTab) ?? 0
                player.selectedTab = tabs[(current + delta + tabs.count) % tabs.count]
                visualizerFocusMode = false
            }
        ).frame(width: 0, height: 0))
        .background(MainWindowVisibilityProbe { visible in
            mainWindowVisible = visible
            player.setVisualizerEnabled(player.selectedTab == "Visualiser" && visible, windowVisible: visible)
        }.frame(width: 0, height: 0))
        .frame(minWidth: 1180, minHeight: 760)
        .preferredColorScheme(.dark)
        .onAppear {
            player.configureMediaCommands()
            player.setVisualizerEnabled(player.selectedTab == "Visualiser" && mainWindowVisible)
            if let folder = UserDefaults.standard.string(forKey: "musicFolder") {
                player.scan(URL(fileURLWithPath: folder))
            }
        }
        .onChange(of: player.selectedTab) { tab in
            player.setVisualizerEnabled(tab == "Visualiser" && mainWindowVisible)
        }
        .onChange(of: player.shuffleQueue) { _ in player.refreshPlaybackSchedule() }
        .onChange(of: player.repeatQueue) { _ in player.refreshPlaybackSchedule() }
        .onChange(of: player.repeatCurrentTrack) { _ in player.refreshPlaybackSchedule() }
        .onChange(of: player.gaplessPlaybackEnabled) { _ in player.refreshPlaybackSchedule(force: true) }
    }

    private var topBar: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(player.selectedTab.uppercased())
                    .font(.system(size: 11, weight: .black, design: .rounded))
                    .tracking(2.4)
                    .foregroundStyle(AnalogPalette.amber)
                Text(pageSubtitle)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(AnalogPalette.muted)
            }
            Spacer()
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(AnalogPalette.muted)
                TextField("Search tracks, artists, albums", text: $player.searchText)
                    .textFieldStyle(.plain)
                    .font(.system(size: 12))
                    .frame(width: 210)
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            Button { player.importMusicFolder() } label: {
                Label("ADD MUSIC", systemImage: "folder.badge.plus")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1)
            }
            .buttonStyle(AnalogActionButton())
        }
        .padding(.horizontal, 22).padding(.vertical, 16)
        .background(AnalogPalette.ink.opacity(0.94))
    }

    private var pageSubtitle: String {
        switch player.selectedTab {
        case "Library": return "\(player.tracks.count) LOCAL TRACKS • LOSSLESS-READY PLAYBACK"
        case "Queue": return "\(player.queue.count) QUEUED TRACKS • PLAYBACK ORDER"
        case "History": return "RECENTLY PLAYED • LISTENING STATISTICS"
        case "Equalizer": return "10-BAND PARAMETRIC TONE CONTROL"
        case "Visualiser": return "LIVE SPECTRUM ANALYSIS • MAC ONLY"
        case "Recorder": return "LOCAL RECORDING • PCM CAPTURE"
        default: return "ANALOG MUSIC SYSTEM"
        }
    }

    @ViewBuilder private var mainContent: some View {
        switch player.selectedTab {
        case "Library": LibraryView()
        case "Queue": QueueView()
        case "History": HistoryView()
        case "Smart Mixes": SmartMixesView()
        case "Moods": MoodCollectionsView()
        case "Playlists": PlaylistsView()
        case "Equalizer": EqualizerView()
        case "Visualiser": VisualiserView(focusMode: $visualizerFocusMode)
        case "Recorder": RecorderView()
        case "Settings": SettingsView()
        default: LibraryView()
        }
    }
}

struct SidebarView: View {
    @EnvironmentObject private var player: AnalogPlayer
    private let sections: [(String, String)] = [
        ("Library", "square.grid.2x2.fill"),
        ("Queue", "text.line.first.and.arrowtriangle.forward"),
        ("History", "clock.arrow.circlepath"),
        ("Smart Mixes", "sparkles"),
        ("Moods", "circle.lefthalf.filled"),
        ("Playlists", "music.note.list"),
        ("Equalizer", "slider.horizontal.3"),
        ("Visualiser", "waveform.path"),
        ("Recorder", "waveform.badge.mic"),
        ("Settings", "gearshape.fill")
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 11) {
                ZStack {
                    RoundedRectangle(cornerRadius: 11).fill(LinearGradient(colors: [AnalogPalette.amber, Color(red: 0.72, green: 0.35, blue: 0.15)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Image(systemName: "waveform").font(.system(size: 19, weight: .black)).foregroundStyle(AnalogPalette.ink)
                }
                .frame(width: 39, height: 39)
                VStack(alignment: .leading, spacing: 1) {
                    Text("ANALOG").font(.system(size: 17, weight: .black, design: .rounded)).tracking(2).foregroundStyle(AnalogPalette.cream)
                    Text("MUSIC SYSTEM / MAC").font(.system(size: 8, weight: .bold)).tracking(1.2).foregroundStyle(AnalogPalette.green)
                }
            }
            .padding(.horizontal, 17).padding(.top, 22).padding(.bottom, 30)

            Text("WORKSPACE").font(.system(size: 9, weight: .black)).tracking(2).foregroundStyle(AnalogPalette.muted).padding(.horizontal, 19).padding(.bottom, 9)
            ForEach(sections, id: \.0) { title, icon in
                Button { player.selectedTab = title } label: {
                    HStack(spacing: 12) {
                        Image(systemName: icon).font(.system(size: 13, weight: .semibold)).frame(width: 18)
                        Text(title).font(.system(size: 12, weight: player.selectedTab == title ? .bold : .medium))
                        Spacer()
                        if title == "Library" {
                            Text("\(player.tracks.count)").font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
                        }
                    }
                    .foregroundStyle(player.selectedTab == title ? AnalogPalette.amber : AnalogPalette.cream.opacity(0.72))
                    .padding(.horizontal, 12).padding(.vertical, 11)
                    .background(player.selectedTab == title ? AnalogPalette.amber.opacity(0.11) : .clear, in: RoundedRectangle(cornerRadius: 9))
                    .overlay(alignment: .leading) {
                        if player.selectedTab == title { RoundedRectangle(cornerRadius: 2).fill(AnalogPalette.amber).frame(width: 3).padding(.vertical, 5) }
                    }
                    .padding(.horizontal, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            Spacer(minLength: 20)
            Text("MOOD COLLECTIONS").font(.system(size: 9, weight: .black)).tracking(1.8).foregroundStyle(AnalogPalette.muted).padding(.horizontal, 19).padding(.bottom, 9)
            ForEach(player.moodNames, id: \.self) { mood in
                Button { player.selectedTab = "Moods" } label: {
                    HStack(spacing: 10) {
                        Circle().fill(moodColor(mood)).frame(width: 7, height: 7)
                        Text(mood).font(.system(size: 11, weight: .medium)).foregroundStyle(AnalogPalette.cream.opacity(0.78))
                        Spacer()
                        Text("\(player.moodTracks(mood).count)").font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
                    }
                    .padding(.horizontal, 19).padding(.vertical, 8)
                    .contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
            Spacer()
            HStack(spacing: 7) {
                Circle().fill(AnalogPalette.green).frame(width: 6, height: 6)
                Text("LOCAL LIBRARY").font(.system(size: 8, weight: .bold)).tracking(1.4).foregroundStyle(AnalogPalette.muted)
            }
            .padding(18)
        }
        .background(AnalogPalette.panel.opacity(0.72))
    }

    private func moodColor(_ mood: String) -> Color {
        switch mood { case "Calm": return AnalogPalette.green; case "Energy": return AnalogPalette.amber; case "Focus": return Color.cyan; default: return Color.purple.opacity(0.9) }
    }
}

struct TrackGroup: Identifiable {
    let title: String
    let tracks: [MusicTrack]
    var id: String { title }
}

struct TrackArtwork: View {
    let track: MusicTrack
    let size: CGFloat
    @State private var artwork: NSImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(AnalogPalette.raised.gradient)
            if let artwork {
                Image(nsImage: artwork)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: size, height: size)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                Image(systemName: "music.note")
                    .font(.system(size: max(13, size * 0.36), weight: .bold))
                    .foregroundStyle(AnalogPalette.amber)
            }
        }
        .frame(width: size, height: size)
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(AnalogPalette.cream.opacity(0.12), lineWidth: 1))
        .task(id: track.artworkPath) {
            // Clear immediately so a previous track's cover is never shown while
            // the next track's artwork is loading.
            artwork = nil
            guard let path = track.artworkPath else { return }
            let data = await Task.detached(priority: .utility) {
                try? Data(contentsOf: URL(fileURLWithPath: path))
            }.value
            guard !Task.isCancelled else { return }
            artwork = data.flatMap { NSImage(data: $0) }
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @State private var showCreate = false
    @State private var playlistName = ""
    @State private var libraryMode = "Songs"
    @AppStorage("analog.library.sortMode") private var sortMode = "Title"
    @AppStorage("analog.library.sortAscending") private var sortAscending = true
    @State private var showSkippedFiles = false

    private var sortedTracks: [MusicTrack] {
        let shufflePositions = Dictionary(player.libraryShuffleOrder.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
        return player.filteredTracks.sorted { left, right in
            switch sortMode {
            case "Artist":
                let order = left.artist.localizedCaseInsensitiveCompare(right.artist)
                return sortAscending ? order == .orderedAscending : order == .orderedDescending
            case "Album":
                let order = left.album.localizedCaseInsensitiveCompare(right.album)
                return sortAscending ? order == .orderedAscending : order == .orderedDescending
            case "Duration":
                return sortAscending ? left.duration < right.duration : left.duration > right.duration
            case "Date Added":
                let leftDate = left.dateAdded ?? .distantPast
                let rightDate = right.dateAdded ?? .distantPast
                return sortAscending ? leftDate < rightDate : leftDate > rightDate
            case "Shuffle":
                return (shufflePositions[left.id] ?? Int.max) < (shufflePositions[right.id] ?? Int.max)
            case "Play Count":
                let leftCount = player.playCounts[left.id] ?? 0
                let rightCount = player.playCounts[right.id] ?? 0
                return sortAscending ? leftCount < rightCount : leftCount > rightCount
            default:
                let order = left.title.localizedCaseInsensitiveCompare(right.title)
                return sortAscending ? order == .orderedAscending : order == .orderedDescending
            }
        }
    }

    private var albumGroups: [TrackGroup] {
        Dictionary(grouping: sortedTracks, by: { $0.album }).map { TrackGroup(title: $0.key, tracks: $0.value) }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }
    private var artistGroups: [TrackGroup] {
        Dictionary(grouping: sortedTracks, by: { $0.artist }).map { TrackGroup(title: $0.key, tracks: $0.value) }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }
    private var folderGroups: [TrackGroup] {
        Dictionary(grouping: sortedTracks, by: { $0.url.deletingLastPathComponent().lastPathComponent }).map { TrackGroup(title: $0.key, tracks: $0.value) }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("YOUR COLLECTION").font(.system(size: 21, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                    Text(player.isScanning ? "Scanning your music collection…" : (player.scanSummary.isEmpty ? "Browse, queue and organise your local audio files" : player.scanSummary)).font(.system(size: 10)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
                }
                Spacer()
                Button { player.importAudioFiles() } label: { Label("ADD FILES", systemImage: "plus.square.on.square") }.buttonStyle(AnalogOutlineButton())
                Button { player.importMusicFolder() } label: { Label("SCAN FOLDER", systemImage: "folder.badge.plus") }.buttonStyle(AnalogOutlineButton())
                if !player.skippedAudioFiles.isEmpty {
                    Button { showSkippedFiles = true } label: { Label("SKIPPED (\(player.skippedAudioFiles.count))", systemImage: "exclamationmark.triangle") }.buttonStyle(AnalogOutlineButton())
                }
                Button { showCreate = true } label: { Label("NEW PLAYLIST", systemImage: "plus") }.buttonStyle(AnalogOutlineButton())
                    .alert("Create playlist", isPresented: $showCreate) {
                        TextField("Playlist name", text: $playlistName)
                        Button("Create") {
                            let name = playlistName.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !name.isEmpty { player.createPlaylist(name, tracks: []); player.selectedTab = "Playlists" }
                            playlistName = ""
                        }
                        Button("Cancel", role: .cancel) { playlistName = "" }
                    }
            }
            HStack(spacing: 9) {
                MetricTile(label: "TRACKS", value: "\(player.tracks.count)", symbol: "music.note")
                MetricTile(label: "FAVOURITES", value: "\(player.favorites.count)", symbol: "heart.fill")
                MetricTile(label: "MOOD TAGGED", value: "\(player.moods.count)", symbol: "tag.fill")
                MetricTile(label: "PLAYLISTS", value: "\(player.customPlaylists.count + 4)", symbol: "list.bullet")
            }
            HStack(spacing: 6) {
                ForEach(["Songs", "Albums", "Artists", "Folders", "Favourites"], id: \.self) { mode in
                    Button(mode.uppercased()) { libraryMode = mode }.buttonStyle(AnalogOutlineButton())
                        .overlay(RoundedRectangle(cornerRadius: 7).stroke(libraryMode == mode ? AnalogPalette.amber.opacity(0.7) : .clear, lineWidth: 1))
                }
            }
            HStack(spacing: 8) {
                Menu {
                    ForEach(["Title", "Artist", "Album", "Duration", "Date Added", "Shuffle", "Play Count"], id: \.self) { option in
                        Button {
                            if option == "Shuffle" { player.shuffleLibrary(); sortMode = "Shuffle" }
                            else { sortMode = option; if option == "Date Added" { sortAscending = false } }
                        } label: { if sortMode == option { Label(option, systemImage: "checkmark") } else { Text(option) } }
                    }
                } label: { Label("SORT: \(sortMode.uppercased())", systemImage: "arrow.up.arrow.down").font(.system(size: 9, weight: .bold)).foregroundStyle(AnalogPalette.cream).padding(8).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 7)) }
                Button { player.shuffleLibrary(); sortMode = "Shuffle" } label: { Label("SHUFFLE LIBRARY", systemImage: "shuffle").font(.system(size: 9, weight: .bold)).foregroundStyle(AnalogPalette.amber).padding(8).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 7)) }.buttonStyle(.plain).help("Randomise the order of songs in your library")
                Button { sortAscending.toggle() } label: { Image(systemName: sortAscending ? "arrow.up" : "arrow.down").foregroundStyle(AnalogPalette.amber).padding(8).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 7)) }.buttonStyle(.plain).help("Reverse sort order")
                Spacer()
                Text("\(player.filteredTracks.count) MATCHING TRACKS").font(.system(size: 8, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
            }
            HStack {
                Text(libraryMode == "Albums" ? "ALBUM" : libraryMode == "Artists" ? "ARTIST" : libraryMode == "Folders" ? "FOLDER" : "TRACK").frame(maxWidth: .infinity, alignment: .leading)
                Text("LENGTH").frame(width: 60, alignment: .trailing)
                Text("ACTIONS").frame(width: 95, alignment: .trailing)
            }
            .font(.system(size: 8, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.muted)
            .padding(.horizontal, 12).padding(.vertical, 8)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 7) {
                    if libraryMode == "Albums" {
                        ForEach(albumGroups) { group in
                            Group {
                                Text(group.title.uppercased()).font(.system(size: 9, weight: .black)).tracking(1.3).foregroundStyle(AnalogPalette.green).padding(.top, 8)
                                ForEach(group.tracks) { TrackRow(track: $0) }
                            }
                        }
                    } else if libraryMode == "Artists" {
                        ForEach(artistGroups) { group in
                            Group {
                                Text(group.title.uppercased()).font(.system(size: 9, weight: .black)).tracking(1.3).foregroundStyle(AnalogPalette.green).padding(.top, 8)
                                ForEach(group.tracks) { TrackRow(track: $0) }
                            }
                        }
                    } else if libraryMode == "Folders" {
                        ForEach(folderGroups) { group in
                            Group {
                                Text(group.title.uppercased()).font(.system(size: 9, weight: .black)).tracking(1.3).foregroundStyle(AnalogPalette.green).padding(.top, 8)
                                ForEach(group.tracks) { TrackRow(track: $0) }
                            }
                        }
                    } else {
                        ForEach(libraryMode == "Favourites" ? sortedTracks.filter { player.favorites.contains($0.id) } : sortedTracks) { track in
                            TrackRow(track: track)
                        }
                    }
                }
            }
            .overlay {
                if player.tracks.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "music.note.list").font(.system(size: 30)).foregroundStyle(AnalogPalette.amber)
                        Text("Your library is empty").font(.system(size: 15, weight: .bold)).foregroundStyle(AnalogPalette.cream)
                        Text("Choose a music folder to import your local collection.").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                    }
                }
            }
        }
        .padding(22)
        .sheet(isPresented: $showSkippedFiles) {
            VStack(alignment: .leading, spacing: 12) {
                Text("SKIPPED AUDIO FILES").font(.system(size: 16, weight: .black)).foregroundStyle(AnalogPalette.cream)
                Text("These files were recognised as audio but Analog could not open them with its current audio decoder. The list includes their full paths so you can identify the missing tracks.")
                    .font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                List(player.skippedAudioFiles, id: \.self) { path in
                    Text(path).font(.system(size: 10, design: .monospaced)).foregroundStyle(AnalogPalette.cream).textSelection(.enabled)
                }.listStyle(.plain)
                HStack {
                    Text("\(player.skippedAudioFiles.count) files").foregroundStyle(AnalogPalette.muted)
                    Spacer()
                    Button("Done") { showSkippedFiles = false }.buttonStyle(AnalogActionButton())
                }
            }.padding(18).frame(minWidth: 620, minHeight: 420).background(AnalogPalette.ink)
        }
    }
}

struct TrackRow: View {
    @EnvironmentObject private var player: AnalogPlayer
    let track: MusicTrack

    var body: some View {
        HStack(spacing: 10) {
            Button { player.play(track) } label: {
                HStack(spacing: 10) {
                    TrackArtwork(track: track, size: 38)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title).font(.system(size: 11, weight: .semibold)).foregroundStyle(player.currentTrack?.id == track.id ? AnalogPalette.amber : AnalogPalette.cream).lineLimit(1)
                        Text("\(track.album)  •  \(track.artist)").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 12).padding(.vertical, 6)
                    .contentShape(Rectangle())
            }.buttonStyle(.plain)
            Text(formatTime(track.duration)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.muted).frame(width: 60, alignment: .trailing)
            HStack(spacing: 2) {
                Button { player.toggleFavorite(track) } label: {
                    Image(systemName: player.favorites.contains(track.id) ? "heart.fill" : "heart")
                        .foregroundStyle(player.favorites.contains(track.id) ? AnalogPalette.amber : AnalogPalette.muted)
                }.buttonStyle(.plain).help("Favourite")
                Menu {
                    ForEach(player.moodNames, id: \.self) { mood in
                        Button(mood) { player.assignMood(mood, to: track) }
                    }
                    Divider()
                    Button("Add to queue") { player.addToQueue(track) }
                    Button("Play next") { player.addNext(track) }
                    ForEach(player.customPlaylists.keys.sorted(), id: \.self) { name in
                        Button("Add to \(name)") { player.add(track, toPlaylist: name) }
                    }
                } label: {
                    Image(systemName: "ellipsis").foregroundStyle(AnalogPalette.cream.opacity(0.7)).frame(width: 26, height: 26)
                }.menuStyle(.borderlessButton)
            }.frame(width: 95, alignment: .trailing)
        }
        .padding(.trailing, 12)
        .background(player.currentTrack?.id == track.id ? AnalogPalette.amber.opacity(0.07) : AnalogPalette.panel.opacity(0.45), in: RoundedRectangle(cornerRadius: 8))
        .contextMenu {
            Button("Play") { player.play(track) }
            Button("Add to queue") { player.addToQueue(track) }
            Button("Play next") { player.addNext(track) }
            ForEach(player.moodNames, id: \.self) { mood in Button("Add to \(mood)") { player.assignMood(mood, to: track) } }
            Divider()
            Button("Remove from library", role: .destructive) { player.removeTrackFromLibrary(track) }
        }
    }
}

struct MoodCollectionsView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @State private var selectedMood = "Calm"
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("LISTEN BY FEELING").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
            HStack(spacing: 10) {
                ForEach(player.moodNames, id: \.self) { mood in
                    Button { selectedMood = mood } label: {
                        VStack(alignment: .leading, spacing: 12) {
                            Circle().fill(color(mood)).frame(width: 12, height: 12)
                            Text(mood.uppercased()).font(.system(size: 11, weight: .black)).tracking(1.5)
                            Text("\(player.moodTracks(mood).count) TRACKS").font(.system(size: 8, weight: .bold)).foregroundStyle(AnalogPalette.muted)
                        }
                        .foregroundStyle(selectedMood == mood ? AnalogPalette.cream : AnalogPalette.muted)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(15)
                        .background(selectedMood == mood ? color(mood).opacity(0.14) : AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(selectedMood == mood ? color(mood).opacity(0.65) : .clear, lineWidth: 1))
                    }.buttonStyle(.plain)
                }
            }
            HStack {
                Text("\(selectedMood) collection").font(.system(size: 16, weight: .bold)).foregroundStyle(AnalogPalette.cream)
                Spacer()
                Button { player.playQueue(player.moodTracks(selectedMood)) } label: { Label("PLAY COLLECTION", systemImage: "play.fill") }.buttonStyle(AnalogActionButton())
            }
            ScrollView { LazyVStack(spacing: 4) { ForEach(player.moodTracks(selectedMood)) { TrackRow(track: $0) } } }
            Spacer()
        }.padding(22)
    }
    private func color(_ mood: String) -> Color {
        switch mood { case "Calm": return AnalogPalette.green; case "Energy": return AnalogPalette.amber; case "Focus": return .cyan; default: return .purple }
    }
}

struct PlaylistsView: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("PLAYLIST LIBRARY").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(player.moodNames, id: \.self) { mood in
                        PlaylistCard(name: mood, tracks: player.moodTracks(mood), subtitle: "Mood collection")
                    }
                    ForEach(player.customPlaylists.keys.sorted(), id: \.self) { name in
                        PlaylistCard(name: name, tracks: player.playlistTracks(name), subtitle: "Personal playlist")
                    }
                }
            }
            Spacer()
        }
        .padding(22)
    }
}

struct PlaylistCard: View {
    @EnvironmentObject private var player: AnalogPlayer
    @State private var showRename = false
    @State private var newName = ""
    let name: String
    let tracks: [MusicTrack]
    let subtitle: String
    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(LinearGradient(colors: [AnalogPalette.raised, AnalogPalette.panel], startPoint: .topLeading, endPoint: .bottomTrailing))
                Image(systemName: "music.note.list").font(.system(size: 22)).foregroundStyle(AnalogPalette.amber)
            }.frame(width: 54, height: 54)
            VStack(alignment: .leading, spacing: 4) {
                Text(name).font(.system(size: 13, weight: .bold)).foregroundStyle(AnalogPalette.cream)
                Text("\(subtitle) • \(tracks.count) tracks").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
            }
            Spacer()
            Button { player.exportPlaylist(name, tracks: tracks) } label: { Image(systemName: "square.and.arrow.down").foregroundStyle(AnalogPalette.green).padding(10).background(AnalogPalette.green.opacity(0.1), in: Circle()) }.buttonStyle(.plain).help("Export as M3U8")
            Button { player.playQueue(tracks) } label: { Image(systemName: "play.fill").foregroundStyle(AnalogPalette.amber).padding(10).background(AnalogPalette.amber.opacity(0.1), in: Circle()) }.buttonStyle(.plain).disabled(tracks.isEmpty).opacity(tracks.isEmpty ? 0.35 : 1)
        }
        .padding(10).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 12))
        .contextMenu {
            if subtitle == "Personal playlist" {
                Button("Rename playlist") { newName = name; showRename = true }
                Button("Delete playlist", role: .destructive) { player.deletePlaylist(name) }
            }
        }
        .alert("Rename playlist", isPresented: $showRename) {
            TextField("Playlist name", text: $newName)
            Button("Save") { player.renamePlaylist(name, to: newName) }
            Button("Cancel", role: .cancel) { }
        }
    }
}

struct EqualizerView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @State private var selectedBandwidthBand = 4
    @State private var newDevicePresetName = ""
    @State private var renameDevicePresetTarget: EQDevicePreset?
    @State private var renameDevicePresetText = ""
    @State private var showingDevicePresetRename = false
    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("MASTER EQUALIZER").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                    Text("10 independent bands • AVAudioUnitEQ • applied directly to the playback chain").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Toggle("EQ ACTIVE", isOn: Binding(get: { player.eqEnabled }, set: { player.setEQEnabled($0) }))
                    .toggleStyle(.switch).tint(AnalogPalette.green).font(.system(size: 10, weight: .bold))
            }

            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("HEADPHONE / DAC PROFILES").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.green)
                        Text("Detected output: \(player.outputDeviceName)").font(.system(size: 10, weight: .medium)).foregroundStyle(AnalogPalette.cream).lineLimit(1)
                        Text(player.activeDevicePresetName.map { "ACTIVE • \($0)" } ?? "ACTIVE • FLAT / NO MATCH")
                            .font(.system(size: 8, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
                    }
                    Spacer()
                    Toggle("AUTO MATCH", isOn: Binding(get: { player.autoMatchOutputDevice }, set: { player.setAutomaticDeviceMatching($0) }))
                        .toggleStyle(.switch).tint(AnalogPalette.green).font(.system(size: 9, weight: .bold))
                }
                HStack(spacing: 8) {
                    TextField("Use exact macOS output name", text: $newDevicePresetName)
                        .textFieldStyle(.roundedBorder).font(.system(size: 10))
                    Button("SAVE PROFILE") {
                        player.saveDevicePreset(named: newDevicePresetName.isEmpty ? player.outputDeviceName : newDevicePresetName)
                        newDevicePresetName = ""
                    }.buttonStyle(AnalogOutlineButton())
                    Button("EXPORT") { player.exportDevicePresets() }.buttonStyle(AnalogOutlineButton())
                    Button("IMPORT") { player.importDevicePresets() }.buttonStyle(AnalogOutlineButton())
                }
                if !player.devicePresets.isEmpty {
                    VStack(spacing: 5) {
                        ForEach(player.devicePresets) { preset in
                            HStack {
                                Button { player.applyDevicePreset(preset) } label: {
                                    HStack {
                                        Image(systemName: player.activeDevicePresetName == preset.name ? "checkmark.circle.fill" : "headphones")
                                            .foregroundStyle(player.activeDevicePresetName == preset.name ? AnalogPalette.green : AnalogPalette.muted)
                                        Text(preset.name).foregroundStyle(AnalogPalette.cream).lineLimit(1)
                                        Spacer()
                                        Text("APPLY").font(.system(size: 8, weight: .black)).foregroundStyle(AnalogPalette.amber)
                                    }
                                }.buttonStyle(.plain)
                                Menu {
                                    Button("Rename") {
                                        renameDevicePresetTarget = preset
                                        renameDevicePresetText = preset.name
                                        showingDevicePresetRename = true
                                    }
                                    Button("Delete", role: .destructive) { player.deleteDevicePreset(preset) }
                                } label: {
                                    Image(systemName: "ellipsis").foregroundStyle(AnalogPalette.muted).padding(5)
                                }.menuStyle(.borderlessButton)
                            }
                            .padding(8).background(AnalogPalette.raised.opacity(0.55), in: RoundedRectangle(cornerRadius: 7))
                        }
                    }
                }
            }
            .padding(13).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 11))
            .alert("Rename output profile", isPresented: $showingDevicePresetRename) {
                TextField("Profile name", text: $renameDevicePresetText)
                Button("Save") {
                    if let preset = renameDevicePresetTarget { player.renameDevicePreset(preset, to: renameDevicePresetText) }
                    renameDevicePresetTarget = nil
                }
                Button("Cancel", role: .cancel) { renameDevicePresetTarget = nil }
            } message: {
                Text("The profile name is matched against the detected macOS output device name.")
            }

            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("HEADPHONE CROSSFEED").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.green)
                        Text(player.crossfeedStatus)
                            .font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).lineLimit(2)
                    }
                    Spacer()
                    if !player.crossfeedAvailable && !player.crossfeedStatus.hasPrefix("Preparing") {
                        Button("RETRY") { player.retryCrossfeedSetup() }.buttonStyle(AnalogOutlineButton())
                    }
                    Toggle("ON", isOn: Binding(get: { player.crossfeedEnabled }, set: { player.setCrossfeed(enabled: $0) }))
                        .toggleStyle(.switch).tint(AnalogPalette.green).font(.system(size: 9, weight: .bold))
                        .disabled(!player.crossfeedAvailable)
                }
                HStack(spacing: 8) {
                    ForEach([("LOW", Float(0.08)), ("MED", Float(0.16)), ("HIGH", Float(0.25))], id: \.0) { item in
                        Button(item.0) { player.setCrossfeed(enabled: true, amount: item.1) }
                            .buttonStyle(AnalogOutlineButton()).disabled(!player.crossfeedAvailable)
                    }
                    Spacer()
                    Text(String(format: "%.0f%% blend", player.crossfeedAmount * 100))
                        .font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.amber)
                }
                Slider(value: Binding(get: { Double(player.crossfeedAmount) }, set: { player.setCrossfeed(enabled: player.crossfeedEnabled, amount: Float($0)) }), in: 0...0.35)
                    .tint(AnalogPalette.amber).disabled(!player.crossfeedAvailable)
            }
            .padding(13).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 11))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 86, maximum: 120), spacing: 8)], alignment: .leading, spacing: 8) {
                ForEach(["Flat", "Bass", "Treble", "Vocal", "Pop", "Rock", "Jazz", "Classical"], id: \.self) { preset in
                    Button(preset.uppercased()) { player.applyPreset(preset) }
                        .buttonStyle(AnalogOutlineButton())
                        .frame(maxWidth: .infinity)
                }
            }
            HStack {
                Button("RESET") { player.applyPreset("Flat") }
                    .buttonStyle(.plain).foregroundStyle(AnalogPalette.amber)
                Spacer()
            }
            HStack(spacing: 8) {
                Button("STORE A") { player.storeEQSnapshot("A") }.buttonStyle(AnalogOutlineButton())
                Button("LOAD A") { player.recallEQSnapshot("A") }.buttonStyle(AnalogOutlineButton())
                Rectangle().fill(AnalogPalette.cream.opacity(0.12)).frame(width: 1, height: 22)
                Button("STORE B") { player.storeEQSnapshot("B") }.buttonStyle(AnalogOutlineButton())
                Button("LOAD B") { player.recallEQSnapshot("B") }.buttonStyle(AnalogOutlineButton())
                Spacer()
                Text("A/B CUSTOM CURVES").font(.system(size: 8, weight: .black, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
            }
            HStack(alignment: .bottom, spacing: 0) {
                ForEach(player.frequencies.indices, id: \.self) { index in
                    VStack(spacing: 10) {
                        Text(String(format: "%+.0f", player.eqGains[index])).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.amber)
                        VerticalEQSlider(value: Binding(get: { player.eqGains[index] }, set: { player.setEQ(index, $0) }), enabled: player.eqEnabled)
                            .frame(width: 40, height: 220)
                        Text(freqLabel(player.frequencies[index])).font(.system(size: 9, weight: .bold)).foregroundStyle(AnalogPalette.cream)
                    }.frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 18)
            .background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 14))
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("PARAMETRIC BANDWIDTH").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
                    Spacer()
                    Menu {
                        ForEach(1..<9, id: \.self) { index in
                            Button(freqLabel(player.frequencies[index])) { selectedBandwidthBand = index }
                        }
                    } label: {
                        Text(freqLabel(player.frequencies[selectedBandwidthBand]) + " Hz").font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.amber)
                    }
                }
                HStack {
                    Text("NARROW").font(.system(size: 8, weight: .bold)).foregroundStyle(AnalogPalette.muted)
                    Slider(value: Binding(get: { Double(player.eqBandwidths[selectedBandwidthBand]) }, set: { player.setEQBandwidth(selectedBandwidthBand, Float($0)) }), in: 0.1...4.0).tint(AnalogPalette.green).disabled(!player.eqEnabled)
                    Text("WIDE").font(.system(size: 8, weight: .bold)).foregroundStyle(AnalogPalette.muted)
                    Text(String(format: "%.2f oct", player.eqBandwidths[selectedBandwidthBand])).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.amber).frame(width: 62, alignment: .trailing)
                }
            }.padding(13).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 11))
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("DYNAMIC PEAK GUARD").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
                        Text("Optional dynamics processor after the 10-band EQ").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                    }
                    Spacer()
                    Toggle("LIMITER", isOn: Binding(get: { player.peakGuardEnabled }, set: { player.setPeakGuard(enabled: $0) }))
                        .toggleStyle(.switch).tint(AnalogPalette.green).labelsHidden()
                }
                HStack {
                    Text("OUTPUT CEILING").font(.system(size: 8, weight: .black)).foregroundStyle(AnalogPalette.muted)
                    Spacer()
                    Text(String(format: "%.1f dB", player.peakGuardThresholdDB)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.amber)
                }
                Slider(value: Binding(get: { Double(player.peakGuardThresholdDB) }, set: { player.setPeakGuardThreshold(Float($0)) }), in: -12 ... -0.1)
                    .tint(AnalogPalette.amber).disabled(!player.peakGuardEnabled)
            }.padding(13).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 11))
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("POST-EQ SIGNAL MONITOR").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
                        Text(player.eqOutputPeak > 0.0001 ? "LIVE AUDIO DETECTED" : "WAITING FOR PLAYBACK").font(.system(size: 8, weight: .bold, design: .monospaced)).foregroundStyle(player.eqOutputPeak > 0.0001 ? AnalogPalette.green : AnalogPalette.muted)
                    }
                    Spacer()
                    Text(String(format: "PEAK %.1f dBFS", 20 * log10(Double(max(player.eqOutputPeak, 0.00001)))))
                        .font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(player.eqOutputPeak >= 0.95 ? AnalogPalette.amber : AnalogPalette.green)
                }
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Capsule().fill(AnalogPalette.ink)
                        Capsule().fill(player.eqOutputPeak >= 0.95 ? AnalogPalette.amber : AnalogPalette.green)
                            .frame(width: geometry.size.width * CGFloat(min(1, player.eqOutputPeak)))
                    }
                }.frame(height: 9)
                HStack { Text("RMS").font(.system(size: 8, weight: .black)).foregroundStyle(AnalogPalette.muted); Spacer(); Text(String(format: "%.4f", player.eqOutputRMS)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.cream) }
                HStack { Text("PREAMP").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted); Spacer(); Text(String(format: "%+.1f dB", player.preampDB)).font(.system(size: 10, design: .monospaced)).foregroundStyle(AnalogPalette.amber) }
                Slider(value: Binding(get: { Double(player.preampDB) }, set: { player.setPreamp(Float($0)) }), in: -12...12).tint(AnalogPalette.amber)
                HStack { Text("STEREO BALANCE").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted); Spacer(); Text(player.stereoPan == 0 ? "CENTRE" : (player.stereoPan < 0 ? "L " : "R ") + String(format: "%.0f%%", abs(player.stereoPan) * 100)).font(.system(size: 10, design: .monospaced)).foregroundStyle(AnalogPalette.amber) }
                Slider(value: Binding(get: { Double(player.stereoPan) }, set: { player.stereoPan = Float($0); player.applyEqualizer(); player.persistState() }), in: -1...1).tint(AnalogPalette.green)
            }.padding(13).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 11))
            HStack(spacing: 10) {
                Image(systemName: "info.circle").foregroundStyle(AnalogPalette.green)
                Text("This is a native 10-band DSP equalizer in the Mac playback signal path. It does not depend on a system-wide audio effect or another app.")
                    .font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
            }.padding(12).background(AnalogPalette.raised.opacity(0.55), in: RoundedRectangle(cornerRadius: 9))
            }
            .padding(22)
        }
        .scrollIndicators(.visible)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    private func freqLabel(_ value: Float) -> String { value >= 1000 ? String(format: "%.0fk", value / 1000) : String(format: "%.0f", value) }
}

struct VerticalEQSlider: View {
    @Binding var value: Float
    var enabled: Bool
    var body: some View {
        GeometryReader { geo in
            let fraction = CGFloat((value + 12) / 24)
            ZStack {
                Capsule().fill(AnalogPalette.ink).frame(width: 5)
                VStack(spacing: 0) {
                    Spacer()
                    Capsule().fill(AnalogPalette.green.opacity(0.75)).frame(width: 5, height: max(0, geo.size.height * fraction))
                }
                Circle().fill(enabled ? AnalogPalette.amber : AnalogPalette.muted).frame(width: 15, height: 15)
                    .overlay(Circle().stroke(AnalogPalette.cream.opacity(0.65), lineWidth: 1))
                    .position(x: geo.size.width / 2, y: geo.size.height * (1 - fraction))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { point in
                guard enabled else { return }
                let position = min(1, max(0, 1 - point.location.y / geo.size.height))
                value = Float(position * 24 - 12)
            })
        }
        .opacity(enabled ? 1 : 0.42)
    }
}

struct RecorderView: View {
    @EnvironmentObject private var recorder: AnalogRecorder
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("ANALOG RECORDER").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
            Text("Capture microphone input directly to a local PCM audio file.").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
            Spacer()
            ZStack {
                Circle().fill(AnalogPalette.panel).frame(width: 210, height: 210)
                Circle().stroke(AnalogPalette.amber.opacity(0.24), lineWidth: 1).frame(width: 184, height: 184)
                Circle().stroke(AnalogPalette.amber.opacity(0.12), lineWidth: 1).frame(width: 150, height: 150)
                VStack(spacing: 8) {
                    Image(systemName: recorder.isRecording ? "waveform" : "mic.fill").font(.system(size: 32, weight: .light)).foregroundStyle(recorder.isRecording ? AnalogPalette.amber : AnalogPalette.green)
                    Text(formatTime(recorder.elapsed)).font(.system(size: 22, weight: .light, design: .monospaced)).foregroundStyle(AnalogPalette.cream)
                    Text(recorder.isRecording ? "RECORDING" : "STANDBY").font(.system(size: 9, weight: .black)).tracking(2).foregroundStyle(recorder.isRecording ? AnalogPalette.amber : AnalogPalette.muted)
                }
            }
            .frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    Text("MIC INPUT PEAK").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
                    Spacer()
                    Text(String(format: "%.1f dBFS", 20 * log10(Double(max(recorder.inputPeak, 0.00001)))))
                        .font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(recorder.inputPeak >= 0.95 ? AnalogPalette.amber : AnalogPalette.green)
                }
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Capsule().fill(AnalogPalette.panel)
                        Capsule().fill(recorder.inputPeak >= 0.95 ? AnalogPalette.amber : AnalogPalette.green)
                            .frame(width: geometry.size.width * CGFloat(min(1, recorder.inputPeak)))
                    }
                }.frame(height: 9)
                HStack { Text("RMS").font(.system(size: 8, weight: .black)).foregroundStyle(AnalogPalette.muted); Spacer(); Text(String(format: "%.4f", recorder.inputRMS)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.cream) }
            }.padding(13).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            HStack {
                Spacer()
                Button {
                    if recorder.isRecording { recorder.stop() } else { recorder.start() }
                } label: {
                    Label(recorder.isRecording ? "STOP RECORDING" : "START RECORDING", systemImage: recorder.isRecording ? "stop.fill" : "record.circle")
                }.buttonStyle(AnalogActionButton())
                Spacer()
            }
            if !recorder.lastRecording.isEmpty {
                Label("Last file: \(URL(fileURLWithPath: recorder.lastRecording).lastPathComponent)", systemImage: "waveform.path")
                    .font(.system(size: 10)).foregroundStyle(AnalogPalette.green).lineLimit(1)
            }
            if !recorder.errorMessage.isEmpty {
                Text(recorder.errorMessage).font(.system(size: 10)).foregroundStyle(.red)
            }
            Spacer()
        }.padding(22)
    }
}

struct SettingsView: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("SYSTEM SETTINGS").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Repeat queue").font(.system(size: 12, weight: .semibold)).foregroundStyle(AnalogPalette.cream)
                    Text("Automatically restart the queue after its final track").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Toggle("", isOn: $player.repeatQueue).labelsHidden().tint(AnalogPalette.green)
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Shuffle queue").font(.system(size: 12, weight: .semibold)).foregroundStyle(AnalogPalette.cream)
                    Text("Choose the next queued track randomly").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Toggle("", isOn: $player.shuffleQueue).labelsHidden().tint(AnalogPalette.green)
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Repeat current track").font(.system(size: 12, weight: .semibold)).foregroundStyle(AnalogPalette.cream)
                    Text("Loop the currently playing song instead of advancing").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Toggle("", isOn: $player.repeatCurrentTrack).labelsHidden().tint(AnalogPalette.green)
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))

            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Gapless playback").font(.system(size: 12, weight: .semibold)).foregroundStyle(AnalogPalette.cream)
                    Text("Pre-queues upcoming tracks in the native audio engine to minimise transition silence").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Toggle("", isOn: Binding(get: { player.gaplessPlaybackEnabled }, set: { player.gaplessPlaybackEnabled = $0; player.persistState() }))
                    .labelsHidden().tint(AnalogPalette.green)
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Dynamic peak guard").font(.system(size: 12, weight: .semibold)).foregroundStyle(AnalogPalette.cream)
                    Text("Optional post-EQ peak control; adjust its ceiling in the Equalizer page").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Toggle("", isOn: Binding(get: { player.peakGuardEnabled }, set: { player.setPeakGuard(enabled: $0) })).labelsHidden().tint(AnalogPalette.green)
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 9) {
                HStack { Text("MASTER OUTPUT").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.green); Spacer(); Text("\(Int(player.outputVolume * 100))%").font(.system(size: 10, design: .monospaced)).foregroundStyle(AnalogPalette.amber) }
                Slider(value: Binding(get: { Double(player.outputVolume) }, set: { player.setOutputVolume(Float($0)) }), in: 0...1).tint(AnalogPalette.amber)
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 9) {
                Text("SLEEP TIMER").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.green)
                HStack(spacing: 8) {
                    Text("FADE").font(.system(size: 8, weight: .bold)).foregroundStyle(AnalogPalette.muted)
                    Slider(value: Binding(
                        get: { Double(player.sleepFadeSeconds) },
                        set: { player.setSleepFadeSeconds(Int($0.rounded())) }
                    ), in: 0...30, step: 5).tint(AnalogPalette.amber)
                    Text(player.sleepFadeSeconds == 0 ? "OFF" : "\(player.sleepFadeSeconds)s")
                        .font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.cream).frame(width: 34, alignment: .trailing)
                }
                HStack {
                    ForEach([15, 30, 45, 60, 90], id: \.self) { minutes in
                        Button("\(minutes) MIN") { player.setSleepTimer(minutes: minutes) }.buttonStyle(AnalogOutlineButton())
                    }
                }
                HStack {
                    Button("STOP AT END OF TRACK") { player.setSleepTimerEndOfTrack() }.buttonStyle(AnalogOutlineButton())
                    Button("CANCEL TIMER") { player.cancelSleepTimer() }.buttonStyle(.plain).foregroundStyle(AnalogPalette.amber)
                }
                if player.sleepStopsAtTrackEnd {
                    Text("Will fade over the final \(player.sleepFadeSeconds) seconds and stop after this track.")
                        .font(.system(size: 10)).foregroundStyle(AnalogPalette.amber)
                } else if player.sleepMinutes > 0 {
                    Text("Sleep timer set for \(player.sleepMinutes) minutes • final \(player.sleepFadeSeconds) seconds fade.")
                        .font(.system(size: 10)).foregroundStyle(AnalogPalette.amber)
                }
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            Spacer()
        }.padding(22)
    }
}

private func formattedFileSize(_ bytes: Int64?) -> String {
    guard let bytes, bytes >= 0 else { return "—" }
    let formatter = ByteCountFormatter()
    formatter.countStyle = .file
    return formatter.string(fromByteCount: bytes)
}

private func technicalValue(_ label: String, _ value: String) -> some View {
    VStack(alignment: .leading, spacing: 3) {
        Text(label).font(.system(size: 7, weight: .black)).tracking(0.8).foregroundStyle(AnalogPalette.muted)
        Text(value).font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.cream).lineLimit(1).minimumScaleFactor(0.75)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(7)
    .background(AnalogPalette.raised.opacity(0.7), in: RoundedRectangle(cornerRadius: 6))
}

struct NowPlayingPanel: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack {
                Text("NOW PLAYING").font(.system(size: 9, weight: .black)).tracking(2).foregroundStyle(AnalogPalette.muted)
                Spacer()
                Circle().fill(player.isPlaying ? AnalogPalette.green : AnalogPalette.muted).frame(width: 6, height: 6)
            }
            Spacer(minLength: 8)
            ZStack {
                RoundedRectangle(cornerRadius: 17).fill(LinearGradient(colors: [AnalogPalette.raised, AnalogPalette.panel, Color.black], startPoint: .topLeading, endPoint: .bottomTrailing))
                if let track = player.currentTrack, track.artworkPath != nil {
                    TrackArtwork(track: track, size: 184)
                        .clipShape(RoundedRectangle(cornerRadius: 13))
                        .overlay(RoundedRectangle(cornerRadius: 13).stroke(AnalogPalette.amber.opacity(0.35), lineWidth: 1))
                        .shadow(color: .black.opacity(0.5), radius: 12, y: 6)
                } else {
                    Circle().fill(Color.black.opacity(0.55)).frame(width: 168, height: 168)
                    Circle().stroke(AnalogPalette.cream.opacity(0.12), lineWidth: 1).frame(width: 150, height: 150)
                    Circle().stroke(AnalogPalette.cream.opacity(0.10), lineWidth: 1).frame(width: 118, height: 118)
                    Circle().stroke(AnalogPalette.cream.opacity(0.10), lineWidth: 1).frame(width: 86, height: 86)
                    Circle().fill(AnalogPalette.amber.gradient).frame(width: 25, height: 25)
                    Circle().fill(AnalogPalette.ink).frame(width: 6, height: 6)
                    Image(systemName: "waveform").font(.system(size: 20, weight: .light)).foregroundStyle(AnalogPalette.amber.opacity(0.8)).offset(y: 106)
                }
            }.aspectRatio(1, contentMode: .fit)
            VStack(alignment: .leading, spacing: 5) {
                Text(player.currentTrack?.title ?? "Nothing playing").font(.system(size: 16, weight: .bold)).foregroundStyle(AnalogPalette.cream).lineLimit(2)
                Text("ALBUM  •  " + (player.currentTrack?.album ?? "—")).font(.system(size: 9, weight: .medium)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
                Text("ARTIST  •  " + (player.currentTrack?.artist ?? "—")).font(.system(size: 9, weight: .medium)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("FILE DETAILS").font(.system(size: 8, weight: .black)).tracking(1.4).foregroundStyle(AnalogPalette.muted)
                HStack(spacing: 6) {
                    technicalValue("FORMAT", player.currentTrack?.url.pathExtension.uppercased() ?? "—")
                    technicalValue("SIZE", player.currentTrack.map { formattedFileSize($0.fileSizeBytes) } ?? "—")
                }
                HStack(spacing: 6) {
                    technicalValue("AVG. BITRATE", player.currentTrack.map { $0.averageBitrateKbps.map { "\($0) kbps" } ?? "—" } ?? "—")
                    technicalValue("SAMPLE RATE", player.currentTrack.map { $0.sampleRateHz.map { String(format: "%.1f kHz", $0 / 1000) } ?? "—" } ?? "—")
                }
                HStack(spacing: 6) {
                    technicalValue("DURATION", player.currentTrack.map { formatTime($0.duration) } ?? "—")
                    technicalValue("CHANNELS", player.currentTrack?.audioChannels.map { "\($0) ch" } ?? "—")
                }
                Text("Bitrate is an estimated average from file size and duration; lossless and VBR files can vary throughout playback.")
                    .font(.system(size: 8)).foregroundStyle(AnalogPalette.muted).fixedSize(horizontal: false, vertical: true)
            }.padding(10).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 8))
            HStack {
                Text("UP NEXT").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.muted)
                Spacer()
                Text("\(player.queue.count) TRACKS").font(.system(size: 8, weight: .bold)).foregroundStyle(AnalogPalette.amber)
            }
            if player.queueIndex + 1 < player.queue.count {
                Text(player.queue[player.queueIndex + 1].title).font(.system(size: 10, weight: .medium)).foregroundStyle(AnalogPalette.cream).lineLimit(2)
            } else {
                Text("Queue complete").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
            }
            Spacer()
            Button { player.selectedTab = "Equalizer" } label: {
                Label("OPEN EQUALIZER", systemImage: "slider.horizontal.3").font(.system(size: 9, weight: .black)).tracking(1)
                    .frame(maxWidth: .infinity).padding(11)
                    .background(AnalogPalette.green.opacity(0.12), in: RoundedRectangle(cornerRadius: 9))
                    .foregroundStyle(AnalogPalette.green)
            }.buttonStyle(.plain)
        }.padding(17).background(AnalogPalette.panel.opacity(0.48))
    }
}

struct TransportBar: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 3) {
                Text(player.currentTrack?.title ?? "ANALOG READY").font(.system(size: 10, weight: .bold)).foregroundStyle(AnalogPalette.cream).lineLimit(1)
                Text(player.currentTrack?.artist ?? "Select a track to begin").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
            }.frame(width: 180, alignment: .leading)
            HStack(spacing: 18) {
                Button { player.previous() } label: { Image(systemName: "backward.end.fill") }.keyboardShortcut(.leftArrow, modifiers: [.command]).help("Previous track (⌘←)")
                Button { player.togglePlayback() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 14, weight: .black))
                        .foregroundStyle(AnalogPalette.ink)
                        .frame(width: 36, height: 36)
                        .background(AnalogPalette.amber, in: Circle())
                }.help("Play / pause (Space)")
                Button { player.next() } label: { Image(systemName: "forward.end.fill") }.keyboardShortcut(.rightArrow, modifiers: [.command]).help("Next track (⌘→)")
            }.buttonStyle(.plain).foregroundStyle(AnalogPalette.cream)
            Text(formatTime(player.currentTime)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
            Slider(value: Binding(get: { player.currentTime }, set: { player.seek(to: $0) }), in: 0...max(1, player.currentTrack?.duration ?? 1))
                .tint(AnalogPalette.amber)
            Text(formatTime(player.currentTrack?.duration ?? 0)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
            Button { player.shuffleQueue.toggle() } label: { Image(systemName: "shuffle").foregroundStyle(player.shuffleQueue ? AnalogPalette.amber : AnalogPalette.muted) }.buttonStyle(.plain)
            Button { player.repeatCurrentTrack.toggle() } label: { Image(systemName: "repeat.1").foregroundStyle(player.repeatCurrentTrack ? AnalogPalette.amber : AnalogPalette.muted) }.buttonStyle(.plain).help("Repeat current track")
            Button { player.repeatQueue.toggle() } label: { Image(systemName: "repeat").foregroundStyle(player.repeatQueue ? AnalogPalette.amber : AnalogPalette.muted) }.buttonStyle(.plain).help("Repeat queue")
            Button { player.selectedTab = "Equalizer" } label: { Image(systemName: "slider.horizontal.3").foregroundStyle(AnalogPalette.green) }.buttonStyle(.plain)
        }
        .padding(.horizontal, 20).padding(.vertical, 10)
        .background(AnalogPalette.panel)
    }
}

struct MetricTile: View {
    let label: String
    let value: String
    let symbol: String
    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: symbol).font(.system(size: 13)).foregroundStyle(AnalogPalette.amber)
            VStack(alignment: .leading, spacing: 2) {
                Text(value).font(.system(size: 14, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                Text(label).font(.system(size: 7, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
            }
            Spacer(minLength: 0)
        }.padding(10).frame(maxWidth: .infinity).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 9))
    }
}

struct AnalogActionButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 9, weight: .black)).tracking(1)
            .padding(.horizontal, 13).padding(.vertical, 10)
            .foregroundStyle(AnalogPalette.ink)
            .background(AnalogPalette.amber.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
    }
}

struct AnalogOutlineButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 8, weight: .bold)).tracking(0.8)
            .padding(.horizontal, 10).padding(.vertical, 8)
            .foregroundStyle(AnalogPalette.cream)
            .background(AnalogPalette.raised.opacity(configuration.isPressed ? 0.6 : 1), in: RoundedRectangle(cornerRadius: 7))
            .overlay(RoundedRectangle(cornerRadius: 7).stroke(AnalogPalette.cream.opacity(0.12), lineWidth: 1))
            .contentShape(Rectangle())
    }
}

private func formatTime(_ seconds: Double) -> String {
    let value = max(0, Int(seconds))
    return String(format: "%d:%02d", value / 60, value % 60)
}

private struct FrequencyMarker: Identifiable {
    let frequency: Double
    let label: String
    var id: Double { frequency }
}

struct VisualiserView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @Binding var focusMode: Bool
    @State private var style = "Spectrum"
    @State private var peakHold = true
    @State private var heldPeaks: [Double] = Array(repeating: 0, count: 36)
    @State private var heldLeftPeak = 0.0
    @State private var heldRightPeak = 0.0
    @State private var scrubTime: Double?
    @State private var artworkGlow = AnalogPalette.amber

    var body: some View {
        Group {
            if focusMode {
                focusedLayout
            } else {
                standardLayout
            }
        }
        .onChange(of: player.visualizerBins) { values in
            guard peakHold else { return }
            if heldPeaks.count != values.count { heldPeaks = values; return }
            heldPeaks = zip(heldPeaks, values).map { max($0.0 * 0.965, $0.1) }
        }
        .onChange(of: player.visualizerLeftPeak) { value in
            guard peakHold else { heldLeftPeak = 0; return }
            heldLeftPeak = max(value, heldLeftPeak * 0.965)
        }
        .onChange(of: player.visualizerRightPeak) { value in
            guard peakHold else { heldRightPeak = 0; return }
            heldRightPeak = max(value, heldRightPeak * 0.965)
        }
        .onChange(of: peakHold) { enabled in
            if !enabled {
                heldPeaks = Array(repeating: 0, count: 36)
                heldLeftPeak = 0
                heldRightPeak = 0
            }
        }
        .padding(focusMode ? 18 : 22)
        .background {
            ZStack {
                AnalogPalette.ink
                if focusMode {
                    RadialGradient(colors: [artworkGlow.opacity(0.19), artworkGlow.opacity(0.07), .clear], center: .init(x: 0.78, y: 0.48), startRadius: 12, endRadius: 620)
                        .animation(.easeInOut(duration: 0.85), value: artworkGlow)
                    RadialGradient(colors: [AnalogPalette.amber.opacity(0.035), .clear], center: .init(x: 0.08, y: 0.9), startRadius: 0, endRadius: 420)
                }
            }
            .ignoresSafeArea()
        }
        .task(id: focusMode ? player.currentTrack?.artworkPath : nil) {
            guard focusMode, let path = player.currentTrack?.artworkPath else {
                artworkGlow = AnalogPalette.amber
                return
            }
            let components = await Task.detached(priority: .utility) { () -> (Double, Double, Double)? in
                guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)),
                      let bitmap = NSBitmapImageRep(data: data),
                      bitmap.pixelsWide > 0, bitmap.pixelsHigh > 0 else { return nil }
                let points = [(0.25, 0.25), (0.5, 0.25), (0.75, 0.25), (0.25, 0.5), (0.5, 0.5), (0.75, 0.5), (0.25, 0.75), (0.5, 0.75), (0.75, 0.75)]
                var red = 0.0, green = 0.0, blue = 0.0, weight = 0.0
                for (x, y) in points {
                    guard let color = bitmap.colorAt(x: min(bitmap.pixelsWide - 1, Int(Double(bitmap.pixelsWide - 1) * x)), y: min(bitmap.pixelsHigh - 1, Int(Double(bitmap.pixelsHigh - 1) * y)))?.usingColorSpace(.deviceRGB) else { continue }
                    let brightness = (color.redComponent + color.greenComponent + color.blueComponent) / 3
                    let sampleWeight = max(0.15, 1.0 - abs(brightness - 0.48))
                    red += color.redComponent * sampleWeight
                    green += color.greenComponent * sampleWeight
                    blue += color.blueComponent * sampleWeight
                    weight += sampleWeight
                }
                guard weight > 0 else { return nil }
                return (red / weight, green / weight, blue / weight)
            }.value
            guard !Task.isCancelled else { return }
            if let components {
                artworkGlow = Color(red: components.0, green: components.1, blue: components.2)
            } else {
                artworkGlow = AnalogPalette.amber
            }
        }
    }

    private var standardLayout: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("LIVE AUDIO ANALYSER").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                    Text("Real-time signal analysis from the Mac playback chain • Mac only").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Toggle("PEAK HOLD", isOn: $peakHold).toggleStyle(.switch).tint(AnalogPalette.green).font(.system(size: 9, weight: .bold))
            }
            HStack(spacing: 8) {
                visualizerStyleButtons
                Spacer()
                Button("FOCUS VIEW  ⤢") { focusMode = true }
                    .buttonStyle(AnalogActionButton())
                Button {
                    player.setVisualizerEnabled(!player.visualizerEnabled)
                } label: {
                    Label(player.visualizerEnabled ? "ANALYSER ON" : "ANALYSER PAUSED", systemImage: player.visualizerEnabled ? "waveform" : "pause.fill")
                }.buttonStyle(AnalogActionButton())
            }
            analyserCanvas.frame(height: 300)
            HStack(alignment: .top, spacing: 10) {
                MetricTile(label: "SOURCE", value: "POST-EQ", symbol: "arrow.down.to.line")
                MetricTile(label: "BANDS", value: "36", symbol: "chart.bar.fill")
                MetricTile(label: "RANGE", value: "35 Hz–16 kHz", symbol: "waveform")
            }
            Text("The analyser reads the live audio signal after the app's equalizer. It is not a decorative animation; bars respond to the playing audio.")
                .font(.system(size: 10)).foregroundStyle(AnalogPalette.muted).padding(12)
                .background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 9))
            Spacer()
        }
    }

    private var focusedLayout: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("ANALOG  /  LIVE SPECTRUM").font(.system(size: 11, weight: .black)).tracking(2).foregroundStyle(AnalogPalette.amber)
                    Text("POST-EQ AUDIO ANALYSIS  •  36 BANDS").font(.system(size: 9, weight: .bold)).tracking(1).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                HStack(spacing: 8) {
                    Circle().fill(player.isPlaying && player.visualizerEnabled ? AnalogPalette.green : AnalogPalette.muted).frame(width: 7, height: 7)
                    Text(player.isPlaying && player.visualizerEnabled ? "LIVE" : "STANDBY")
                        .font(.system(size: 9, weight: .bold)).tracking(1)
                        .foregroundStyle(player.isPlaying && player.visualizerEnabled ? AnalogPalette.green : AnalogPalette.muted)
                    Toggle("PEAK HOLD", isOn: $peakHold).toggleStyle(.switch).tint(AnalogPalette.green).font(.system(size: 9, weight: .bold))
                    Text("ESC TO EXIT").font(.system(size: 9, weight: .bold)).tracking(1).foregroundStyle(AnalogPalette.muted)
                }
            }
            HStack(spacing: 12) {
                VStack(spacing: 7) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("LIVE AUDIO ANALYSER").font(.system(size: 14, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                            Text("Real-time signal from the playback chain").font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                        }
                        Spacer()
                        Text("36 BANDS").font(.system(size: 9, weight: .bold)).tracking(1).foregroundStyle(AnalogPalette.amber)
                    }
                    analyserCanvas.frame(maxWidth: .infinity, maxHeight: .infinity)
                    frequencyScale
                    liveWaveformStrip
                    HStack(spacing: 8) {
                        MetricTile(label: "SOURCE", value: "POST-EQ", symbol: "arrow.down.to.line")
                        MetricTile(label: "BANDS", value: "36", symbol: "chart.bar.fill")
                        MetricTile(label: "RANGE", value: "35 Hz–16 kHz", symbol: "waveform")
                        stereoActivity
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(12)
                .background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 13))
                nowPlayingDetails
                    .frame(width: 360)
                    .frame(maxHeight: .infinity)
                    .padding(12)
                    .background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 13))
            }
            playbackControls
        }
        .animation(.easeInOut(duration: 0.22), value: focusMode)
    }

    private var frequencyScale: some View {
        GeometryReader { geometry in
            let markers = [
                FrequencyMarker(frequency: 35, label: "35 Hz"),
                FrequencyMarker(frequency: 100, label: "100 Hz"),
                FrequencyMarker(frequency: 500, label: "500 Hz"),
                FrequencyMarker(frequency: 1_000, label: "1 kHz"),
                FrequencyMarker(frequency: 5_000, label: "5 kHz"),
                FrequencyMarker(frequency: 16_000, label: "16 kHz")
            ]
            ZStack(alignment: .topLeading) {
                ForEach(markers) { marker in
                    let fraction = log(marker.frequency / 35) / log(16_000.0 / 35.0)
                    let x = min(max(CGFloat(fraction) * geometry.size.width, 18), geometry.size.width - 18)
                    Text(marker.label)
                        .font(.system(size: 8, weight: .medium, design: .monospaced))
                        .foregroundStyle(AnalogPalette.muted)
                        .position(x: x, y: 6)
                }
            }
        }
        .frame(height: 13)
    }

    private var liveWaveformStrip: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("LIVE WAVEFORM").font(.system(size: 8, weight: .black)).tracking(1.1).foregroundStyle(AnalogPalette.muted)
                Spacer()
                Text("REAL-TIME SIGNAL").font(.system(size: 7, weight: .bold)).tracking(0.8).foregroundStyle(AnalogPalette.muted)
            }
            VStack(spacing: 3) {
                channelWaveform(samples: player.visualizerLeftWaveform, tint: AnalogPalette.green)
                channelWaveform(samples: player.visualizerRightWaveform, tint: AnalogPalette.amber)
            }
            .frame(height: 42)
            .animation(.linear(duration: 0.10), value: player.visualizerLeftWaveform)
            .animation(.linear(duration: 0.10), value: player.visualizerRightWaveform)
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(Color.black.opacity(0.68), in: RoundedRectangle(cornerRadius: 8))
        }
    }

    private var stereoActivity: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text("CHANNEL INPUT").font(.system(size: 8, weight: .black)).tracking(1).foregroundStyle(AnalogPalette.muted)
                Spacer()
                Text(player.visualizerChannelCount == 1 ? "MONO" : (player.visualizerChannelCount >= 2 ? "STEREO" : "—"))
                    .font(.system(size: 7, weight: .bold, design: .monospaced))
                    .foregroundStyle(player.visualizerChannelCount == 1 ? AnalogPalette.muted : AnalogPalette.green)
            }
            channelMeter(label: "L", peak: player.visualizerLeftPeak, rms: player.visualizerLeftRMS, held: heldLeftPeak, tint: AnalogPalette.green)
            channelMeter(label: "R", peak: player.visualizerRightPeak, rms: player.visualizerRightRMS, held: heldRightPeak, tint: AnalogPalette.amber)
        }
        .padding(9).frame(width: 208, alignment: .leading)
        .background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 9))
    }

    private func channelMeter(label: String, peak: Double, rms: Double, held: Double, tint: Color) -> some View {
        HStack(spacing: 5) {
            Text(label).font(.system(size: 8, weight: .black, design: .monospaced)).foregroundStyle(tint).frame(width: 9)
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(AnalogPalette.ink)
                    Capsule().fill(tint.opacity(0.48))
                        .frame(width: proxy.size.width * CGFloat(min(1, max(0, rms))))
                    Capsule().fill(tint)
                        .frame(width: proxy.size.width * CGFloat(min(1, max(0, peak))))
                    if peakHold && held > 0 {
                        RoundedRectangle(cornerRadius: 1)
                            .fill(AnalogPalette.cream)
                            .frame(width: 2, height: 9)
                            .offset(x: max(0, min(proxy.size.width - 2, proxy.size.width * CGFloat(min(1, max(0, held))) - 1)))
                    }
                }
            }.frame(height: 9)
            VStack(alignment: .trailing, spacing: 1) {
                Text("P \(String(format: "%.2f", peak))")
                Text("R \(String(format: "%.2f", rms))").foregroundStyle(AnalogPalette.muted)
            }
            .font(.system(size: 6.5, weight: .medium, design: .monospaced))
            .frame(width: 48, alignment: .trailing)
        }
        .animation(.easeOut(duration: 0.12), value: peak)
        .animation(.easeOut(duration: 0.12), value: rms)
        .help("Peak \(label): \(String(format: "%.3f", peak)) • Peak hold: \(String(format: "%.3f", held)) • RMS: \(String(format: "%.3f", rms))")
    }

    private func channelWaveform(samples: [Double], tint: Color) -> some View {
        Canvas { context, size in
            guard samples.count > 1 else { return }
            let mid = size.height / 2
            var center = Path()
            center.move(to: CGPoint(x: 0, y: mid))
            center.addLine(to: CGPoint(x: size.width, y: mid))
            context.stroke(center, with: .color(.white.opacity(0.08)), lineWidth: 1)
            var waveform = Path()
            for index in samples.indices {
                let x = CGFloat(index) / CGFloat(samples.count - 1) * size.width
                let y = mid - CGFloat(samples[index]) * size.height * 0.43
                if index == 0 { waveform.move(to: CGPoint(x: x, y: y)) }
                else { waveform.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(waveform, with: .color(tint), style: StrokeStyle(lineWidth: 1.25, lineCap: .round, lineJoin: .round))
        }
        .frame(height: 18)
    }

    private var visualizerStyleButtons: some View {
        HStack(spacing: 8) {
            ForEach(["Spectrum", "Waveform"], id: \.self) { item in
                Button(item.uppercased()) { style = item }.buttonStyle(AnalogOutlineButton())
                    .overlay(RoundedRectangle(cornerRadius: 7).stroke(style == item ? AnalogPalette.amber.opacity(0.8) : .clear, lineWidth: 1))
            }
        }
    }

    private var analyserCanvas: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 15).fill(Color.black.opacity(0.68))
            Canvas { context, size in
                let bins = player.visualizerBins
                let count = max(1, bins.count)
                let gap: CGFloat = focusMode ? 5 : 4
                let barWidth = max(2, (size.width - CGFloat(count - 1) * gap) / CGFloat(count))
                if style == "Spectrum" {
                    for index in bins.indices {
                        let value = CGFloat(bins[index])
                        let height = max(2, value * (size.height - 20))
                        let rect = CGRect(x: CGFloat(index) * (barWidth + gap), y: size.height - height - 10, width: barWidth, height: height)
                        let color = index < 10 ? AnalogPalette.green : (index < 24 ? AnalogPalette.amber : Color(red: 0.9, green: 0.42, blue: 0.25))
                        context.fill(Path(roundedRect: rect, cornerRadius: 3), with: .linearGradient(Gradient(colors: [color.opacity(0.55), color]), startPoint: CGPoint(x: rect.midX, y: rect.minY), endPoint: CGPoint(x: rect.midX, y: rect.maxY)))
                        if peakHold, heldPeaks.indices.contains(index) {
                            let peakY = size.height - CGFloat(heldPeaks[index]) * (size.height - 20) - 10
                            let marker = CGRect(x: rect.minX, y: max(4, peakY - 2), width: barWidth, height: 2)
                            context.fill(Path(roundedRect: marker, cornerRadius: 1), with: .color(AnalogPalette.cream.opacity(0.9)))
                        }
                    }
                } else {
                    let wave = player.visualizerWaveform
                    var path = Path()
                    for index in wave.indices {
                        let x = CGFloat(index) / CGFloat(max(1, wave.count - 1)) * size.width
                        let y = size.height / 2 - CGFloat(wave[index]) * size.height * 0.42
                        if index == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
                    }
                    context.stroke(path, with: .color(AnalogPalette.green), style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                    var mirror = Path()
                    for index in wave.indices {
                        let x = CGFloat(index) / CGFloat(max(1, wave.count - 1)) * size.width
                        let y = size.height / 2 + CGFloat(wave[index]) * size.height * 0.12
                        if index == 0 { mirror.move(to: CGPoint(x: x, y: y)) } else { mirror.addLine(to: CGPoint(x: x, y: y)) }
                    }
                    context.stroke(mirror, with: .color(AnalogPalette.amber.opacity(0.85)), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
                }
                for fraction in [0.25, 0.5, 0.75] {
                    var grid = Path()
                    grid.move(to: CGPoint(x: 0, y: size.height * fraction))
                    grid.addLine(to: CGPoint(x: size.width, y: size.height * fraction))
                    context.stroke(grid, with: .color(.white.opacity(0.08)), lineWidth: 1)
                }
            }
            .padding(12)
            .animation(.easeOut(duration: 0.11), value: player.visualizerBins)
            if !player.isPlaying {
                VStack(spacing: 5) {
                    Image(systemName: "waveform.path").font(.system(size: 25)).foregroundStyle(AnalogPalette.amber)
                    Text(player.currentTrack == nil ? "PLAY A TRACK TO START ANALYSIS" : "PLAYBACK PAUSED")
                        .font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.muted)
                }
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 15).stroke(AnalogPalette.cream.opacity(0.12), lineWidth: 1))
    }

    private var nowPlayingDetails: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("NOW PLAYING").font(.system(size: 9, weight: .black)).tracking(1.8).foregroundStyle(AnalogPalette.muted)
                Spacer()
                Circle().fill(player.isPlaying ? AnalogPalette.green : AnalogPalette.muted).frame(width: 7, height: 7)
            }
            HStack(spacing: 12) {
                Group {
                    if let track = player.currentTrack, track.artworkPath != nil {
                        TrackArtwork(track: track, size: 124)
                    } else {
                        ZStack {
                            RoundedRectangle(cornerRadius: 10).fill(AnalogPalette.raised)
                            Image(systemName: "waveform").font(.system(size: 38, weight: .bold)).foregroundStyle(AnalogPalette.amber)
                        }
                    }
                }
                .frame(width: 124, height: 124).clipShape(RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 5) {
                    Text(player.currentTrack?.title ?? "Nothing playing")
                        .font(.system(size: 16, weight: .bold)).foregroundStyle(AnalogPalette.cream).lineLimit(2)
                    Text(player.currentTrack?.artist ?? "—")
                        .font(.system(size: 11, weight: .medium)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
                    Text("ALBUM  •  \(player.currentTrack?.album ?? "—")")
                        .font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            Text("FILE DETAILS").font(.system(size: 8, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
            let track = player.currentTrack
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 7) {
                focusDetail("FORMAT", track?.url.pathExtension.uppercased() ?? "—")
                focusDetail("SIZE", focusFileSize(track?.fileSizeBytes))
                focusDetail("AVG. BITRATE", track?.averageBitrateKbps.map { "\($0) kbps" } ?? "—")
                focusDetail("SAMPLE RATE", track?.sampleRateHz.map { String(format: "%.1f kHz", $0 / 1000) } ?? "—")
                focusDetail("DURATION", track.map { formatTime($0.duration) } ?? "—")
                focusDetail("CHANNELS", track?.audioChannels.map { String($0) } ?? "—")
            }
            Divider().overlay(AnalogPalette.cream.opacity(0.10))
            Text("PLAYBACK").font(.system(size: 8, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
            HStack {
                Text(formatTime(player.currentTime))
                Spacer()
                Text("-\(formatTime(max(0, (track?.duration ?? 0) - player.currentTime)))")
            }
            .font(.system(size: 9, weight: .medium, design: .monospaced)).foregroundStyle(AnalogPalette.cream)
            ProgressView(value: track.map { $0.duration > 0 ? min(1, max(0, player.currentTime / $0.duration)) : 0 } ?? 0)
                .tint(AnalogPalette.amber)
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("UP NEXT").font(.system(size: 8, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
                    Spacer()
                    Text(player.shuffleQueue ? "SHUFFLE QUEUE" : "PLAYBACK QUEUE")
                        .font(.system(size: 7, weight: .bold)).tracking(0.7).foregroundStyle(AnalogPalette.muted)
                }
                if let upcoming = upcomingTrack {
                    HStack(spacing: 9) {
                        Group {
                            if upcoming.artworkPath != nil {
                                TrackArtwork(track: upcoming, size: 38)
                            } else {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 6).fill(AnalogPalette.raised)
                                    Image(systemName: "music.note").font(.system(size: 14, weight: .semibold)).foregroundStyle(AnalogPalette.amber)
                                }
                            }
                        }
                        .frame(width: 38, height: 38).clipShape(RoundedRectangle(cornerRadius: 6))
                        VStack(alignment: .leading, spacing: 3) {
                            Text(upcoming.title).font(.system(size: 10, weight: .bold)).foregroundStyle(AnalogPalette.cream).lineLimit(1)
                            Text(upcoming.artist).font(.system(size: 8, weight: .medium)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(7)
                    .background(AnalogPalette.raised.opacity(0.72), in: RoundedRectangle(cornerRadius: 8))
                } else {
                    let status = player.currentTrack == nil
                        ? "Nothing queued"
                        : (player.shuffleQueue
                            ? "Next track will be selected randomly"
                            : (player.repeatQueue ? "Current queue will repeat" : "End of queue"))
                    Text(status)
                        .font(.system(size: 9, weight: .medium)).foregroundStyle(AnalogPalette.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(9)
                        .background(AnalogPalette.raised.opacity(0.55), in: RoundedRectangle(cornerRadius: 8))
                }
            }
            Text("SIGNAL CHAIN").font(.system(size: 8, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted)
            HStack(spacing: 7) {
                focusDetail("EQUALIZER", player.eqEnabled ? "ACTIVE" : "BYPASSED")
                focusDetail("CROSSFEED", player.crossfeedAvailable ? (player.crossfeedEnabled ? "ON" : "OFF") : "UNAVAILABLE")
            }
            Spacer(minLength: 0)
        }
    }

    private var upcomingTrack: MusicTrack? {
        // The random next item is chosen by the playback scheduler, not queue order.
        // Avoid displaying queueIndex + 1 as a promise when shuffle is enabled.
        guard !player.shuffleQueue, !player.queue.isEmpty else { return nil }
        if player.repeatCurrentTrack { return player.currentTrack }
        let nextIndex = player.queueIndex + 1
        if player.queue.indices.contains(nextIndex) { return player.queue[nextIndex] }
        return player.repeatQueue ? player.queue.first : nil
    }

    private func focusDetail(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 7, weight: .black)).tracking(0.8).foregroundStyle(AnalogPalette.muted)
            Text(value).font(.system(size: 10, weight: .bold, design: .rounded)).foregroundStyle(AnalogPalette.cream).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8).padding(.vertical, 7)
        .background(AnalogPalette.raised.opacity(0.72), in: RoundedRectangle(cornerRadius: 7))
    }

    private var playbackControls: some View {
        let duration = max(0, player.currentTrack?.duration ?? 0)
        return VStack(spacing: 7) {
            HStack(spacing: 10) {
                Text(formatTime(scrubTime ?? player.currentTime)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
                Slider(
                    value: Binding(
                        get: { min(scrubTime ?? player.currentTime, max(duration, 0.01)) },
                        set: { scrubTime = $0 }
                    ),
                    in: 0...max(duration, 0.01),
                    onEditingChanged: { editing in
                        if !editing {
                            if let target = scrubTime { player.seek(to: target) }
                            scrubTime = nil
                        }
                    }
                )
                .tint(AnalogPalette.amber)
                Text(formatTime(duration)).font(.system(size: 9, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
            }
            HStack(spacing: 28) {
                Button(action: { player.previous() }) {
                    Image(systemName: "backward.end.fill")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(AnalogPalette.cream)
                        .frame(width: 58, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(player.currentTrack == nil)
                .opacity(player.currentTrack == nil ? 0.35 : 1)
                .accessibilityLabel("Previous track")
                .help("Previous track")
                Button(action: { player.togglePlayback() }) {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 18, weight: .bold)).foregroundStyle(AnalogPalette.ink)
                        .frame(width: 48, height: 48).background(AnalogPalette.amber, in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(player.currentTrack == nil)
                .accessibilityLabel(player.isPlaying ? "Pause playback" : "Resume playback")
                Button(action: { player.next() }) {
                    Image(systemName: "forward.end.fill")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(AnalogPalette.cream)
                        .frame(width: 58, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(player.currentTrack == nil || player.queue.isEmpty)
                .opacity(player.currentTrack == nil || player.queue.isEmpty ? 0.35 : 1)
                .accessibilityLabel("Next track")
                .help("Next track")
            }
            .foregroundStyle(AnalogPalette.cream)
        }
        .padding(.horizontal, 10)
    }
}

private func focusFileSize(_ bytes: Int64?) -> String {
    guard let bytes, bytes > 0 else { return "—" }
    let formatter = ByteCountFormatter()
    formatter.countStyle = .file
    return formatter.string(fromByteCount: bytes)
}


struct QueueView: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("PLAYBACK QUEUE").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                    Text("Reorder tracks, remove items or jump directly to any queued song").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Button("CLEAR UPCOMING") { player.clearUpcomingQueue() }.buttonStyle(AnalogOutlineButton())
            }
            HStack(spacing: 9) {
                MetricTile(label: "TOTAL", value: "\(player.queue.count)", symbol: "list.number")
                MetricTile(label: "PLAYED POSITION", value: player.queue.isEmpty ? "—" : "\(player.queueIndex + 1)", symbol: "play.circle")
                MetricTile(label: "UP NEXT", value: "\(max(0, player.queue.count - player.queueIndex - 1))", symbol: "forward")
            }
            ScrollView {
                LazyVStack(spacing: 7) {
                    ForEach(player.queue.indices, id: \.self) { index in
                        let track = player.queue[index]
                        HStack(spacing: 10) {
                            Text(String(format: "%02d", index + 1)).font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(index == player.queueIndex ? AnalogPalette.amber : AnalogPalette.muted).frame(width: 28)
                            Button { player.playQueue(player.queue, startAt: index) } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(track.title).font(.system(size: 11, weight: .semibold)).foregroundStyle(index == player.queueIndex ? AnalogPalette.amber : AnalogPalette.cream).lineLimit(1)
                                    Text(track.artist + "  •  " + track.album).font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }.buttonStyle(.plain)
                            if index == player.queueIndex { Image(systemName: "speaker.wave.2.fill").foregroundStyle(AnalogPalette.green).font(.system(size: 11)) }
                            Button { if index > 0 { player.moveQueueItem(from: index, to: index - 1) } } label: { Image(systemName: "arrow.up").foregroundStyle(index > 0 ? AnalogPalette.cream : AnalogPalette.muted.opacity(0.25)) }.buttonStyle(.plain).disabled(index == 0)
                            Button { if index + 1 < player.queue.count { player.moveQueueItem(from: index, to: index + 1) } } label: { Image(systemName: "arrow.down").foregroundStyle(index + 1 < player.queue.count ? AnalogPalette.cream : AnalogPalette.muted.opacity(0.25)) }.buttonStyle(.plain).disabled(index + 1 >= player.queue.count)
                            Button { player.removeFromQueue(at: index) } label: { Image(systemName: "xmark").foregroundStyle(AnalogPalette.amber) }.buttonStyle(.plain).disabled(index == player.queueIndex)
                        }.padding(11).background(index == player.queueIndex ? AnalogPalette.amber.opacity(0.09) : AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 9))
                    }
                }
            }
            if player.queue.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "text.line.first.and.arrowtriangle.forward").font(.system(size: 28)).foregroundStyle(AnalogPalette.amber)
                    Text("Queue is empty").font(.system(size: 14, weight: .bold)).foregroundStyle(AnalogPalette.cream)
                    Text("Add tracks from your library using the track actions menu.").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Spacer(minLength: 0)
        }.padding(22)
    }
}

struct HistoryView: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("LISTENING HISTORY").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                    Text("Recent listening and your most-played local tracks").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Button("CLEAR HISTORY") { player.clearHistory() }.buttonStyle(AnalogOutlineButton())
            }
            HStack(spacing: 9) {
                MetricTile(label: "RECENT TRACKS", value: "\(player.playHistory.count)", symbol: "clock")
                MetricTile(label: "PLAYED TRACKS", value: "\(player.playCounts.count)", symbol: "music.note.list")
                MetricTile(label: "TOTAL PLAYS", value: "\(player.playCounts.values.reduce(0, +))", symbol: "chart.bar.fill")
            }
            Text("MOST PLAYED").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.green)
            ScrollView {
                LazyVStack(spacing: 7) {
                    ForEach(player.mostPlayedTracks) { track in
                        HStack {
                            Button { player.play(track, from: player.mostPlayedTracks) } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(track.title).font(.system(size: 11, weight: .semibold)).foregroundStyle(AnalogPalette.cream)
                                    Text(track.artist).font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }.buttonStyle(.plain)
                            Text("\(player.playCounts[track.id] ?? 0) PLAYS").font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.amber)
                        }.padding(11).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 9))
                    }
                    Divider().overlay(AnalogPalette.cream.opacity(0.15))
                    Text("RECENTLY PLAYED").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.green).frame(maxWidth: .infinity, alignment: .leading)
                    ForEach(player.historyTracks) { track in TrackRow(track: track) }
                }
            }
            Spacer(minLength: 0)
        }.padding(22)
    }
}

struct SmartMixesView: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            VStack(alignment: .leading, spacing: 4) {
                Text("SMART MIXES").font(.system(size: 22, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                Text("Automatically assembled collections from your listening activity and library").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
            }
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(player.smartMixes) { mix in
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 10).fill(LinearGradient(colors: [AnalogPalette.raised, AnalogPalette.panel], startPoint: .topLeading, endPoint: .bottomTrailing))
                            Image(systemName: mix.name == "Recently Played" ? "clock" : mix.name == "Most Played" ? "chart.bar.fill" : mix.name == "Favourites" ? "heart.fill" : "sparkles").font(.system(size: 20)).foregroundStyle(AnalogPalette.amber)
                        }.frame(width: 48, height: 48)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(mix.name).font(.system(size: 12, weight: .bold)).foregroundStyle(AnalogPalette.cream)
                            Text(mix.subtitle).font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).lineLimit(2)
                            Text("\(mix.tracks.count) TRACKS").font(.system(size: 8, weight: .black, design: .monospaced)).foregroundStyle(AnalogPalette.green)
                        }
                        Spacer(minLength: 2)
                        Button { player.playQueue(mix.tracks) } label: { Image(systemName: "play.fill").foregroundStyle(AnalogPalette.ink).frame(width: 30, height: 30).background(AnalogPalette.amber, in: Circle()) }.buttonStyle(.plain).disabled(mix.tracks.isEmpty).opacity(mix.tracks.isEmpty ? 0.35 : 1)
                    }.padding(11).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            Text("Smart mixes update automatically as you play, rate and favourite tracks.").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted).padding(12).background(AnalogPalette.raised.opacity(0.55), in: RoundedRectangle(cornerRadius: 9))
            Spacer()
        }.padding(22)
    }
}


private struct ArrowKeyNavigationMonitor: NSViewRepresentable {
    let onNavigate: (Int) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> NSView { NSView(frame: .zero) }
    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.onNavigate = onNavigate
        context.coordinator.installIfNeeded()
    }

    final class Coordinator {
        var onNavigate: (Int) -> Void = { _ in }
        private var monitor: Any?

        func installIfNeeded() {
            guard monitor == nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard let self else { return event }
                let modifiers = event.modifierFlags.intersection([.command, .control, .option, .shift])
                guard modifiers.isEmpty else { return event }
                if let editor = NSApp.keyWindow?.firstResponder as? NSTextView, editor.isFieldEditor {
                    return event
                }
                switch event.keyCode {
                case 123: self.onNavigate(-1)
                case 124: self.onNavigate(1)
                case 126, 125:
                    // Keep vertical navigation with the active content view. This lets
                    // lists and scrollable sections handle Up/Down without changing tabs.
                    return event
                default:
                    return event
                }
                return nil
            }
        }

        deinit {
            if let monitor { NSEvent.removeMonitor(monitor) }
        }
    }
}

private struct CursorVisibilityController: NSViewRepresentable {
    let isHidden: Bool

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> NSView { NSView(frame: .zero) }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.setHidden(isHidden)
    }

    static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) {
        coordinator.setHidden(false)
    }

    final class Coordinator {
        private var cursorIsHidden = false

        func setHidden(_ hidden: Bool) {
            guard cursorIsHidden != hidden else { return }
            if hidden {
                NSCursor.hide()
            } else {
                NSCursor.unhide()
            }
            cursorIsHidden = hidden
        }
    }
}

private struct FocusModeShortcutMonitor: NSViewRepresentable {
    let onToggle: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> NSView { NSView(frame: .zero) }
    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.onToggle = onToggle
        context.coordinator.installIfNeeded()
    }

    final class Coordinator {
        var onToggle: () -> Void = {}
        private var monitor: Any?

        func installIfNeeded() {
            guard monitor == nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard let self,
                      event.keyCode == 3,
                      event.modifierFlags.contains(.command),
                      event.modifierFlags.intersection([.control, .option, .shift]).isEmpty else {
                    return event
                }
                self.onToggle()
                return nil
            }
        }

        deinit {
            if let monitor { NSEvent.removeMonitor(monitor) }
        }
    }
}

private struct EscapeKeyMonitor: NSViewRepresentable {
    let isActive: Bool
    let onEscape: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> NSView { NSView(frame: .zero) }
    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.isActive = isActive
        context.coordinator.onEscape = onEscape
        context.coordinator.installIfNeeded()
    }

    final class Coordinator {
        var isActive = false
        var onEscape: () -> Void = {}
        private var monitor: Any?

        func installIfNeeded() {
            guard monitor == nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard let self, self.isActive, event.keyCode == 53 else { return event }
                self.onEscape()
                return nil
            }
        }

        deinit {
            if let monitor { NSEvent.removeMonitor(monitor) }
        }
    }
}
