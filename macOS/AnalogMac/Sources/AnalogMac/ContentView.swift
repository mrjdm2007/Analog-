import AppKit
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

    var body: some View {
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
        .background(AnalogPalette.ink)
        .frame(minWidth: 1180, minHeight: 760)
        .preferredColorScheme(.dark)
        .onAppear {
            if let folder = UserDefaults.standard.string(forKey: "musicFolder") {
                player.scan(URL(fileURLWithPath: folder))
            }
        }
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
        case "Journal": return "YOUR TRACK NOTES AND RATINGS"
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
        case "Journal": JournalView()
        case "Equalizer": EqualizerView()
        case "Visualiser": VisualiserView()
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
        ("Journal", "book.closed.fill"),
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
                }
                .buttonStyle(.plain).padding(.horizontal, 10)
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

struct LibraryView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @State private var showCreate = false
    @State private var playlistName = ""
    @State private var libraryMode = "Songs"
    @State private var sortMode = "Title"
    @State private var sortAscending = true

    private var sortedTracks: [MusicTrack] {
        player.filteredTracks.sorted { left, right in
            switch sortMode {
            case "Artist":
                let order = left.artist.localizedCaseInsensitiveCompare(right.artist)
                return sortAscending ? order == .orderedAscending : order == .orderedDescending
            case "Album":
                let order = left.album.localizedCaseInsensitiveCompare(right.album)
                return sortAscending ? order == .orderedAscending : order == .orderedDescending
            case "Duration":
                return sortAscending ? left.duration < right.duration : left.duration > right.duration
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
                    Text("Browse, queue and organise your local audio files").font(.system(size: 11)).foregroundStyle(AnalogPalette.muted)
                }
                Spacer()
                Button { player.importAudioFiles() } label: { Label("ADD FILES", systemImage: "plus.square.on.square") }.buttonStyle(AnalogOutlineButton())
                Button { player.importMusicFolder() } label: { Label("SCAN FOLDER", systemImage: "folder.badge.plus") }.buttonStyle(AnalogOutlineButton())
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
                    ForEach(["Title", "Artist", "Album", "Duration", "Play Count"], id: \.self) { option in
                        Button { sortMode = option } label: { if sortMode == option { Label(option, systemImage: "checkmark") } else { Text(option) } }
                    }
                } label: { Label("SORT: \(sortMode.uppercased())", systemImage: "arrow.up.arrow.down").font(.system(size: 9, weight: .bold)).foregroundStyle(AnalogPalette.cream).padding(8).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 7)) }
                Button { sortAscending.toggle() } label: { Image(systemName: sortAscending ? "arrow.up" : "arrow.down").foregroundStyle(AnalogPalette.amber).padding(8).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 7)) }.buttonStyle(.plain).help("Reverse sort order")
                Spacer()
                Text("\(player.filteredTracks.count) MATCHING TRACKS").font(.system(size: 8, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.muted)
            }
            HStack {
                Text(libraryMode == "Albums" ? "ALBUM" : libraryMode == "Artists" ? "ARTIST" : libraryMode == "Folders" ? "FOLDER" : "TRACK").frame(maxWidth: .infinity, alignment: .leading)
                Text("ALBUM").frame(width: 135, alignment: .leading)
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
    }
}

struct TrackRow: View {
    @EnvironmentObject private var player: AnalogPlayer
    let track: MusicTrack

    var body: some View {
        HStack(spacing: 10) {
            Button { player.play(track) } label: {
                HStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 7).fill(AnalogPalette.raised)
                        Image(systemName: player.currentTrack?.id == track.id && player.isPlaying ? "waveform" : "music.note")
                            .font(.system(size: 13, weight: .bold)).foregroundStyle(player.currentTrack?.id == track.id ? AnalogPalette.amber : AnalogPalette.muted)
                    }.frame(width: 34, height: 34)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title).font(.system(size: 11, weight: .semibold)).foregroundStyle(player.currentTrack?.id == track.id ? AnalogPalette.amber : AnalogPalette.cream).lineLimit(1)
                        Text(track.artist).font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }.buttonStyle(.plain)
            Text(track.album).font(.system(size: 9)).foregroundStyle(AnalogPalette.muted).frame(width: 135, alignment: .leading).lineLimit(1)
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
                    Button("Open journal") { player.selectedTab = "Journal" }
                } label: {
                    Image(systemName: "ellipsis").foregroundStyle(AnalogPalette.cream.opacity(0.7)).frame(width: 26, height: 26)
                }.menuStyle(.borderlessButton)
            }.frame(width: 95, alignment: .trailing)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
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
            Button { player.playQueue(tracks) } label: { Image(systemName: "play.fill").foregroundStyle(AnalogPalette.amber).padding(10).background(AnalogPalette.amber.opacity(0.1), in: Circle()) }.buttonStyle(.plain)
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

struct JournalView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @State private var selectedID: String?
    @State private var note = ""
    @State private var rating = 0
    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 12) {
                Text("SONG JOURNAL").font(.system(size: 21, weight: .black, design: .rounded)).foregroundStyle(AnalogPalette.cream)
                Text("Keep a note about the songs that matter to you.").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(player.tracks) { track in
                            Button {
                                selectedID = track.id
                                note = player.journal[track.id] ?? ""
                                rating = player.ratings[track.id] ?? 0
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(track.title).font(.system(size: 10, weight: .semibold)).foregroundStyle(AnalogPalette.cream)
                                        Text(track.artist).font(.system(size: 9)).foregroundStyle(AnalogPalette.muted)
                                    }
                                    Spacer()
                                    if player.journal[track.id]?.isEmpty == false { Image(systemName: "note.text").foregroundStyle(AnalogPalette.green) }
                                }.padding(9).background(selectedID == track.id ? AnalogPalette.amber.opacity(0.12) : AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 8))
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }.frame(width: 250)
            Rectangle().fill(AnalogPalette.cream.opacity(0.1)).frame(width: 1)
            VStack(alignment: .leading, spacing: 14) {
                Text("LISTENING NOTES").font(.system(size: 10, weight: .black)).tracking(1.8).foregroundStyle(AnalogPalette.amber)
                Text(player.tracks.first(where: { $0.id == selectedID })?.title ?? "Choose a song")
                    .font(.system(size: 18, weight: .bold)).foregroundStyle(AnalogPalette.cream)
                HStack {
                    ForEach(1...5, id: \.self) { value in
                        Button { rating = value } label: {
                            Image(systemName: value <= rating ? "star.fill" : "star").foregroundStyle(value <= rating ? AnalogPalette.amber : AnalogPalette.muted)
                        }.buttonStyle(.plain)
                    }
                }
                TextEditor(text: $note).font(.system(size: 12)).scrollContentBackground(.hidden)
                    .padding(10).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
                HStack {
                    Spacer()
                    Button("SAVE JOURNAL ENTRY") {
                        if let track = player.tracks.first(where: { $0.id == selectedID }) { player.saveJournal(note, rating: rating, for: track) }
                    }.buttonStyle(AnalogActionButton()).disabled(selectedID == nil)
                }
                Spacer()
            }.padding(16).frame(maxWidth: .infinity, maxHeight: .infinity)
        }.padding(22)
    }
}

struct EqualizerView: View {
    @EnvironmentObject private var player: AnalogPlayer
    var body: some View {
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
            HStack(spacing: 8) {
                ForEach(["Flat", "Bass", "Treble", "Vocal", "Rock", "Classical", "Jazz", "Electronic", "Acoustic", "Podcast", "Night", "R&B", "Hip-Hop", "Metal", "Small Speakers", "Headphones"], id: \.self) { preset in
                    Button(preset.uppercased()) { player.applyPreset(preset) }.buttonStyle(AnalogOutlineButton())
                }
                Spacer()
                Button("RESET") { player.applyPreset("Flat") }.buttonStyle(.plain).foregroundStyle(AnalogPalette.amber)
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
            VStack(alignment: .leading, spacing: 12) {
                HStack { Text("PREAMP").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted); Spacer(); Text(String(format: "%+.1f dB", player.preampDB)).font(.system(size: 10, design: .monospaced)).foregroundStyle(AnalogPalette.amber) }
                Slider(value: Binding(get: { Double(player.preampDB) }, set: { player.preampDB = Float($0); player.setEQEnabled(player.eqEnabled) }), in: -12...12).tint(AnalogPalette.amber)
                HStack { Text("STEREO BALANCE").font(.system(size: 9, weight: .black)).tracking(1.2).foregroundStyle(AnalogPalette.muted); Spacer(); Text(player.stereoPan == 0 ? "CENTRE" : (player.stereoPan < 0 ? "L " : "R ") + String(format: "%.0f%%", abs(player.stereoPan) * 100)).font(.system(size: 10, design: .monospaced)).foregroundStyle(AnalogPalette.amber) }
                Slider(value: Binding(get: { Double(player.stereoPan) }, set: { player.stereoPan = Float($0); player.setEQEnabled(player.eqEnabled) }), in: -1...1).tint(AnalogPalette.green)
            }.padding(13).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 11))
            HStack(spacing: 10) {
                Image(systemName: "info.circle").foregroundStyle(AnalogPalette.green)
                Text("This is a native 10-band DSP equalizer in the Mac playback signal path. It does not depend on a system-wide audio effect or another app.")
                    .font(.system(size: 10)).foregroundStyle(AnalogPalette.muted)
            }.padding(12).background(AnalogPalette.raised.opacity(0.55), in: RoundedRectangle(cornerRadius: 9))
            Spacer()
        }.padding(22)
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
            VStack(alignment: .leading, spacing: 9) {
                HStack { Text("MASTER OUTPUT").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.green); Spacer(); Text("\(Int(player.outputVolume * 100))%").font(.system(size: 10, design: .monospaced)).foregroundStyle(AnalogPalette.amber) }
                Slider(value: Binding(get: { Double(player.outputVolume) }, set: { player.setOutputVolume(Float($0)) }), in: 0...1).tint(AnalogPalette.amber)
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 8) {
                Text("SLEEP TIMER").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.green)
                HStack {
                    ForEach([15, 30, 45, 60, 90], id: \.self) { minutes in
                        Button("\(minutes) MIN") { player.setSleepTimer(minutes: minutes) }.buttonStyle(AnalogOutlineButton())
                    }
                    Button("CANCEL") { player.setSleepTimer(minutes: 0) }.buttonStyle(.plain).foregroundStyle(AnalogPalette.amber)
                }
                if player.sleepMinutes > 0 { Text("Sleep timer set for \(player.sleepMinutes) minutes").font(.system(size: 10)).foregroundStyle(AnalogPalette.amber) }
            }.padding(14).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 10))
            Spacer()
        }.padding(22)
    }
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
                Circle().fill(Color.black.opacity(0.55)).frame(width: 168, height: 168)
                Circle().stroke(AnalogPalette.cream.opacity(0.12), lineWidth: 1).frame(width: 150, height: 150)
                Circle().stroke(AnalogPalette.cream.opacity(0.10), lineWidth: 1).frame(width: 118, height: 118)
                Circle().stroke(AnalogPalette.cream.opacity(0.10), lineWidth: 1).frame(width: 86, height: 86)
                Circle().fill(AnalogPalette.amber.gradient).frame(width: 25, height: 25)
                Circle().fill(AnalogPalette.ink).frame(width: 6, height: 6)
                Image(systemName: "waveform").font(.system(size: 20, weight: .light)).foregroundStyle(AnalogPalette.amber.opacity(0.8)).offset(y: 106)
            }.aspectRatio(1, contentMode: .fit)
            VStack(alignment: .leading, spacing: 5) {
                Text(player.currentTrack?.title ?? "Nothing playing").font(.system(size: 16, weight: .bold)).foregroundStyle(AnalogPalette.cream).lineLimit(2)
                Text(player.currentTrack?.artist ?? "Choose a song from your library").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted).lineLimit(1)
            }
            HStack {
                Text("FORMAT").font(.system(size: 8, weight: .black)).foregroundStyle(AnalogPalette.muted)
                Spacer()
                Text(player.currentTrack?.url.pathExtension.uppercased() ?? "—").font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(AnalogPalette.green)
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
                Button { player.previous() } label: { Image(systemName: "backward.end.fill") }
                Button { player.togglePlayback() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 14, weight: .black))
                        .foregroundStyle(AnalogPalette.ink)
                        .frame(width: 36, height: 36)
                        .background(AnalogPalette.amber, in: Circle())
                }
                Button { player.next() } label: { Image(systemName: "forward.end.fill") }
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
    }
}

struct AnalogOutlineButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 8, weight: .bold)).tracking(0.8)
            .padding(.horizontal, 10).padding(.vertical, 8)
            .foregroundStyle(AnalogPalette.cream)
            .background(AnalogPalette.raised.opacity(configuration.isPressed ? 0.6 : 1), in: RoundedRectangle(cornerRadius: 7))
            .overlay(RoundedRectangle(cornerRadius: 7).stroke(AnalogPalette.cream.opacity(0.12), lineWidth: 1))
    }
}

private func formatTime(_ seconds: Double) -> String {
    let value = max(0, Int(seconds))
    return String(format: "%d:%02d", value / 60, value % 60)
}

struct VisualiserView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @State private var style = "Spectrum"
    @State private var peakHold = true
    @State private var heldPeaks: [Double] = Array(repeating: 0, count: 36)
    var body: some View {
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
                ForEach(["Spectrum", "Waveform"], id: \.self) { item in
                    Button(item.uppercased()) { style = item }.buttonStyle(AnalogOutlineButton())
                        .overlay(RoundedRectangle(cornerRadius: 7).stroke(style == item ? AnalogPalette.amber.opacity(0.8) : .clear, lineWidth: 1))
                }
                Spacer()
                Button { player.visualizerEnabled.toggle() } label: {
                    Label(player.visualizerEnabled ? "ANALYSER ON" : "ANALYSER PAUSED", systemImage: player.visualizerEnabled ? "waveform" : "pause.fill")
                }.buttonStyle(AnalogActionButton())
            }
            ZStack {
                RoundedRectangle(cornerRadius: 15).fill(Color.black.opacity(0.68))
                Canvas { context, size in
                    let bins = player.visualizerBins
                    let count = max(1, bins.count)
                    let gap: CGFloat = 4
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
                        var grid = Path(); grid.move(to: CGPoint(x: 0, y: size.height * fraction)); grid.addLine(to: CGPoint(x: size.width, y: size.height * fraction))
                        context.stroke(grid, with: .color(.white.opacity(0.08)), lineWidth: 1)
                    }
                }
                .padding(12)
                if !player.isPlaying {
                    VStack(spacing: 5) {
                        Image(systemName: "waveform.path").font(.system(size: 25)).foregroundStyle(AnalogPalette.amber)
                        Text(player.currentTrack == nil ? "PLAY A TRACK TO START ANALYSIS" : "PLAYBACK PAUSED").font(.system(size: 9, weight: .black)).tracking(1.5).foregroundStyle(AnalogPalette.muted)
                    }
                }
            }
            .frame(height: 300)
            .overlay(RoundedRectangle(cornerRadius: 15).stroke(AnalogPalette.cream.opacity(0.12), lineWidth: 1))
            HStack(alignment: .top, spacing: 10) {
                MetricTile(label: "SOURCE", value: "POST-EQ", symbol: "arrow.down.to.line")
                MetricTile(label: "BANDS", value: "36", symbol: "chart.bar.fill")
                MetricTile(label: "RANGE", value: "35 Hz–16 kHz", symbol: "waveform")
            }
            Text("The analyser reads the live audio signal after the app's equalizer. It is not a decorative animation; bars respond to the playing audio.").font(.system(size: 10)).foregroundStyle(AnalogPalette.muted).padding(12).background(AnalogPalette.panel, in: RoundedRectangle(cornerRadius: 9))
            Spacer()
        }
        .onChange(of: player.visualizerBins) { _, values in
            guard peakHold else { return }
            if heldPeaks.count != values.count { heldPeaks = values; return }
            heldPeaks = zip(heldPeaks, values).map { max($0 * 0.965, $1) }
        }
        .padding(22)
    }
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
                    ForEach(player.queue.indices, id: \\.self) { index in
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
                ForEach(player.smartMixes, id: \.name) { mix in
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 10).fill(LinearGradient(colors: [AnalogPalette.raised, AnalogPalette.panel], startPoint: .topLeading, endPoint: .bottomTrailing))
                            Image(systemName: mix.name == "Recently Played" ? "clock" : mix.name == "Most Played" ? "chart.bar.fill" : mix.name == "Top Rated" ? "star.fill" : mix.name == "Favourites" ? "heart.fill" : "sparkles").font(.system(size: 20)).foregroundStyle(AnalogPalette.amber)
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
