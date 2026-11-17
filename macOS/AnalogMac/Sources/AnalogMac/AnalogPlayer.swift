import AVFoundation
import AppKit
import Combine
import SwiftUI

struct MusicTrack: Identifiable, Hashable, Codable {
    var path: String
    var title: String
    var artist: String
    var album: String
    var duration: Double
    var id: String { path }
    var url: URL { URL(fileURLWithPath: path) }
}

@MainActor
final class AnalogPlayer: ObservableObject {
    @Published var tracks: [MusicTrack] = []
    @Published var queue: [MusicTrack] = []
    @Published var queueIndex = 0
    @Published var currentTrack: MusicTrack?
    @Published var isPlaying = false
    @Published var currentTime: Double = 0
    @Published var selectedTab = "Library"
    @Published var searchText = ""
    @Published var favorites: Set<String> = []
    @Published var moods: [String: String] = [:]
    @Published var journal: [String: String] = [:]
    @Published var ratings: [String: Int] = [:]
    @Published var customPlaylists: [String: [String]] = [:]
    @Published var eqEnabled = true
    @Published var eqGains: [Float] = Array(repeating: 0, count: 10)
    @Published var repeatQueue = false
    @Published var shuffleQueue = false
    @Published var sleepMinutes = 0

    let frequencies: [Float] = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let equalizer = AVAudioUnitEQ(numberOfBands: 10)
    private var audioFile: AVAudioFile?
    private var timer: Timer?
    private var sleepTimer: Timer?
    private var segmentStart: Double = 0
    private var playbackToken = UUID()
    private let defaults = UserDefaults.standard

    init() {
        audioEngine.attach(playerNode)
        audioEngine.attach(equalizer)
        audioEngine.connect(playerNode, to: equalizer, format: nil)
        audioEngine.connect(equalizer, to: audioEngine.mainMixerNode, format: nil)
        for (index, band) in equalizer.bands.enumerated() {
            band.filterType = .parametric
            band.frequency = frequencies[index]
            band.bandwidth = 1.0
            band.gain = 0
            band.bypass = false
        }
        restoreState()
        applyEqualizer()
    }

    var filteredTracks: [MusicTrack] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return tracks }
        return tracks.filter { "\($0.title) \($0.artist) \($0.album)".localizedCaseInsensitiveContains(query) }
    }

    var moodNames: [String] { ["Calm", "Energy", "Focus", "Nostalgia"] }

    func moodTracks(_ mood: String) -> [MusicTrack] {
        tracks.filter { moods[$0.id] == mood }
    }

    func restoreState() {
        favorites = Set(defaults.stringArray(forKey: "favorites") ?? [])
        moods = defaults.dictionary(forKey: "moods") as? [String: String] ?? [:]
        journal = defaults.dictionary(forKey: "journal") as? [String: String] ?? [:]
        ratings = defaults.dictionary(forKey: "ratings") as? [String: Int] ?? [:]
        customPlaylists = defaults.dictionary(forKey: "customPlaylists") as? [String: [String]] ?? [:]
        eqEnabled = defaults.object(forKey: "eqEnabled") as? Bool ?? true
        if let values = defaults.array(forKey: "eqGains") as? [Float], values.count == 10 { eqGains = values }
    }

    func persistState() {
        defaults.set(Array(favorites), forKey: "favorites")
        defaults.set(moods, forKey: "moods")
        defaults.set(journal, forKey: "journal")
        defaults.set(ratings, forKey: "ratings")
        defaults.set(customPlaylists, forKey: "customPlaylists")
        defaults.set(eqEnabled, forKey: "eqEnabled")
        defaults.set(eqGains, forKey: "eqGains")
    }

    func importMusicFolder() {
        let panel = NSOpenPanel()
        panel.title = "Choose your music folder"
        panel.prompt = "Add Music"
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let folder = panel.url else { return }
        scan(folder)
    }

    func scan(_ folder: URL) {
        let supported = Set(["mp3", "m4a", "aac", "wav", "aif", "aiff", "caf", "flac", "alac"])
        let keys: [URLResourceKey] = [.isRegularFileKey]
        let enumerator = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])
        var found: [MusicTrack] = []
        while let url = enumerator?.nextObject() as? URL {
            guard supported.contains(url.pathExtension.lowercased()) else { continue }
            let name = url.deletingPathExtension().lastPathComponent
            let parts = name.components(separatedBy: " - ")
            let artist = parts.count > 1 ? parts[0] : "Unknown Artist"
            let title = parts.count > 1 ? parts.dropFirst().joined(separator: " - ") : name
            let duration = (try? AVAudioFile(forReading: url)).map { Double($0.length) / $0.processingFormat.sampleRate } ?? 0
            found.append(MusicTrack(path: url.path, title: title, artist: artist, album: "Local Files", duration: duration))
        }
        tracks = found.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        defaults.set(folder.path, forKey: "musicFolder")
    }

    func play(_ track: MusicTrack, from list: [MusicTrack]? = nil) {
        let source = list ?? tracks
        queue = source
        queueIndex = queue.firstIndex(where: { $0.id == track.id }) ?? 0
        startTrack(track, at: 0)
    }

    func playQueue(_ items: [MusicTrack], startAt index: Int = 0) {
        guard !items.isEmpty else { return }
        queue = items
        queueIndex = min(max(0, index), items.count - 1)
        startTrack(items[queueIndex], at: 0)
    }

    func addNext(_ track: MusicTrack) {
        let insertion = min(queueIndex + 1, queue.count)
        queue.insert(track, at: insertion)
    }

    func addToQueue(_ track: MusicTrack) {
        queue.append(track)
    }

    private func startTrack(_ track: MusicTrack, at seconds: Double) {
        playbackToken = UUID()
        let token = playbackToken
        playerNode.stop()
        do {
            let file = try AVAudioFile(forReading: track.url)
            audioFile = file
            currentTrack = track
            currentTime = min(max(0, seconds), max(0, track.duration))
            segmentStart = currentTime
            if !audioEngine.isRunning { try audioEngine.start() }
            let startFrame = AVAudioFramePosition(currentTime * file.processingFormat.sampleRate)
            let remaining = max(0, file.length - startFrame)
            let frameCount = AVAudioFrameCount(min(remaining, AVAudioFramePosition(UInt32.max)))
            guard frameCount > 0 else { advance(); return }
            playerNode.scheduleSegment(file, startingFrame: startFrame, frameCount: frameCount, at: nil, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                DispatchQueue.main.async {
                    guard let self, self.playbackToken == token else { return }
                    self.advance()
                }
            }
            playerNode.play()
            isPlaying = true
            startClock()
        } catch {
            isPlaying = false
            currentTrack = track
            print("Analog playback error: \(error.localizedDescription)")
        }
    }

    func togglePlayback() {
        if isPlaying {
            updateClock()
            playerNode.pause()
            isPlaying = false
            timer?.invalidate()
        } else if let track = currentTrack {
            startTrack(track, at: currentTime)
        } else if let first = tracks.first {
            play(first)
        }
    }

    func seek(to seconds: Double) {
        guard let track = currentTrack else { return }
        startTrack(track, at: min(max(0, seconds), track.duration))
    }

    func previous() {
        if currentTime > 3 { seek(to: 0); return }
        guard !queue.isEmpty else { return }
        let index = queueIndex > 0 ? queueIndex - 1 : (repeatQueue ? queue.count - 1 : 0)
        startTrack(queue[index], at: 0)
        queueIndex = index
    }

    func next() { advance() }

    private func advance() {
        guard !queue.isEmpty else { isPlaying = false; return }
        var nextIndex = queueIndex + 1
        if shuffleQueue && queue.count > 1 {
            nextIndex = Int.random(in: 0..<queue.count)
            if nextIndex == queueIndex { nextIndex = (nextIndex + 1) % queue.count }
        } else if nextIndex >= queue.count {
            if repeatQueue { nextIndex = 0 } else { isPlaying = false; currentTime = 0; timer?.invalidate(); return }
        }
        queueIndex = nextIndex
        startTrack(queue[nextIndex], at: 0)
    }

    private func startClock() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.updateClock() }
        }
    }

    private func updateClock() {
        guard isPlaying, let nodeTime = playerNode.lastRenderTime, let playerTime = playerNode.playerTime(forNodeTime: nodeTime), playerTime.sampleRate > 0 else { return }
        currentTime = min(currentTrack?.duration ?? .greatestFiniteMagnitude, segmentStart + Double(playerTime.sampleTime) / playerTime.sampleRate)
    }

    func toggleFavorite(_ track: MusicTrack) {
        if favorites.contains(track.id) { favorites.remove(track.id) } else { favorites.insert(track.id) }
        persistState()
    }

    func assignMood(_ mood: String, to track: MusicTrack) {
        moods[track.id] = mood
        persistState()
    }

    func saveJournal(_ note: String, rating: Int, for track: MusicTrack) {
        journal[track.id] = note
        ratings[track.id] = rating
        persistState()
    }

    func createPlaylist(_ name: String, tracks: [MusicTrack]) {
        customPlaylists[name] = tracks.map(\.id)
        persistState()
    }

    func playlistTracks(_ name: String) -> [MusicTrack] {
        let ids = customPlaylists[name] ?? []
        return ids.compactMap { id in tracks.first { $0.id == id } }
    }

    func setEQ(_ index: Int, _ gain: Float) {
        guard eqGains.indices.contains(index) else { return }
        eqGains[index] = min(12, max(-12, gain))
        applyEqualizer()
        persistState()
    }

    func setEQEnabled(_ enabled: Bool) {
        eqEnabled = enabled
        applyEqualizer()
        persistState()
    }

    private func applyEqualizer() {
        for index in equalizer.bands.indices {
            equalizer.bands[index].gain = eqGains[index]
            equalizer.bands[index].bypass = !eqEnabled
        }
    }

    func applyPreset(_ name: String) {
        let values: [Float]
        switch name {
        case "Bass": values = [7, 6, 4, 2, 0, -1, -2, -2, -1, 0]
        case "Treble": values = [-2, -1, 0, 0, 1, 2, 4, 6, 7, 7]
        case "Vocal": values = [-2, -1, 0, 2, 4, 4, 3, 1, 0, -1]
        case "Rock": values = [5, 3, -1, -2, 1, 3, 5, 5, 3, 3]
        case "Classical": values = [4, 3, 2, 1, -1, -1, 1, 2, 3, 4]
        default: values = Array(repeating: 0, count: 10)
        }
        eqGains = values
        applyEqualizer()
        persistState()
    }

    func setSleepTimer(minutes: Int) {
        sleepTimer?.invalidate()
        sleepMinutes = minutes
        guard minutes > 0 else { return }
        sleepTimer = Timer.scheduledTimer(withTimeInterval: Double(minutes * 60), repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.playerNode.pause()
                self?.isPlaying = false
                self?.sleepMinutes = 0
                self?.timer?.invalidate()
            }
        }
    }
}

@MainActor
final class AnalogRecorder: ObservableObject {
    @Published var isRecording = false
    @Published var elapsed: TimeInterval = 0
    @Published var lastRecording = ""
    @Published var errorMessage = ""
    private let engine = AVAudioEngine()
    private var outputFile: AVAudioFile?
    private var startedAt: Date?
    private var timer: Timer?

    func start() {
        let panel = NSSavePanel()
        panel.title = "Save Analog recording"
        panel.nameFieldStringValue = "Analog Recording.caf"
        panel.allowedContentTypes = [.init(filenameExtension: "caf")!]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let input = engine.inputNode
            let format = input.inputFormat(forBus: 0)
            guard format.channelCount > 0 else { throw NSError(domain: "AnalogRecorder", code: 1, userInfo: [NSLocalizedDescriptionKey: "No microphone input is available."]) }
            let file = try AVAudioFile(forWriting: url, settings: format.settings)
            outputFile = file
            input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
                try? self?.outputFile?.write(from: buffer)
            }
            try engine.start()
            startedAt = Date()
            elapsed = 0
            isRecording = true
            errorMessage = ""
            lastRecording = url.path
            timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let start = self.startedAt else { return }
                    self.elapsed = Date().timeIntervalSince(start)
                }
            }
        } catch {
            errorMessage = error.localizedDescription
            outputFile = nil
        }
    }

    func stop() {
        guard isRecording else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        outputFile = nil
        timer?.invalidate()
        isRecording = false
        startedAt = nil
    }
}
