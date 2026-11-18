import AVFoundation
import AnalogCore
import AudioToolbox
import AppKit
import Combine
import SwiftUI
import UniformTypeIdentifiers
import os
import MediaPlayer

struct MusicTrack: Identifiable, Hashable, Codable, Sendable {
    var path: String
    var title: String
    var artist: String
    var album: String
    var duration: Double
    var artworkPath: String? = nil
    var fileSizeBytes: Int64? = nil
    var sampleRateHz: Double? = nil
    var averageBitrateKbps: Int? = nil
    var audioChannels: Int? = nil
    var dateAdded: Date? = nil
    var id: String { path }
    var url: URL { URL(fileURLWithPath: path) }
}

private let analogSupportedAudioExtensions: Set<String> = [
    "mp3", "m4a", "m4b", "aac", "adts", "wav", "wave",
    "aif", "aiff", "caf", "flac", "alac", "ac3", "au", "snd",
    "ogg", "oga", "opus", "wma", "ape", "wv", "dsf", "dff", "mka", "mp4", "3gp"
]

private func makeMusicTrack(from url: URL) -> MusicTrack? {
    let extensionName = url.pathExtension.lowercased()
    let isAudio = analogSupportedAudioExtensions.contains(extensionName)
        || (UTType(filenameExtension: extensionName)?.conforms(to: .audio) ?? false)
    guard isAudio, let file = try? AVAudioFile(forReading: url) else { return nil }

    let asset = AVURLAsset(url: url)
    let metadata = asset.commonMetadata
    func metadataValue(_ names: Set<String>) -> String? {
        metadata.first(where: { item in
            guard let key = item.commonKey?.rawValue.lowercased() else { return false }
            return names.contains(key)
        })?.stringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
    }

    let filename = url.deletingPathExtension().lastPathComponent
    let title = metadataValue(["title"]) ?? filename
    let artist = metadataValue(["artist", "author"]) ?? "Unknown Artist"
    let album = metadataValue(["album", "albumname", "albumtitle"]) ?? "Unknown Album"

    let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Analog/Artwork", isDirectory: true)
    try? FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)

    var artworkData = metadata.first(where: { $0.commonKey == .commonKeyArtwork })?.dataValue
    if artworkData == nil || artworkData?.isEmpty == true {
        let folder = url.deletingLastPathComponent()
        for name in ["cover.jpg", "cover.jpeg", "cover.png", "folder.jpg",
                     "folder.jpeg", "folder.png", "AlbumArt.jpg", "AlbumArt.jpeg"] {
            if let data = try? Data(contentsOf: folder.appendingPathComponent(name)), !data.isEmpty {
                artworkData = data
                break
            }
        }
    }

    var artworkPath: String?
    if let artwork = artworkData, !artwork.isEmpty {
        var hash: UInt64 = 14695981039346656037
        for byte in url.path.utf8 { hash = (hash ^ UInt64(byte)) &* 1099511628211 }
        let destination = cache.appendingPathComponent(String(hash, radix: 16) + ".artwork")
        if !FileManager.default.fileExists(atPath: destination.path) {
            try? artwork.write(to: destination, options: .atomic)
        }
        if FileManager.default.fileExists(atPath: destination.path) { artworkPath = destination.path }
    }

    let duration = file.processingFormat.sampleRate > 0
        ? Double(file.length) / file.processingFormat.sampleRate : 0
    let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
    let fileSize = (attributes?[.size] as? NSNumber)?.int64Value
    let sampleRate = file.processingFormat.sampleRate
    let averageBitrate = duration > 0 ? Int((Double(fileSize ?? 0) * 8 / duration / 1000).rounded()) : nil

    return MusicTrack(
        path: url.path,
        title: title.isEmpty ? filename : title,
        artist: artist.isEmpty ? "Unknown Artist" : artist,
        album: album.isEmpty ? "Unknown Album" : album,
        duration: duration,
        artworkPath: artworkPath,
        fileSizeBytes: fileSize,
        sampleRateHz: sampleRate > 0 ? sampleRate : nil,
        averageBitrateKbps: averageBitrate,
        audioChannels: Int(file.processingFormat.channelCount),
        dateAdded: (attributes?[.creationDate] as? Date) ?? Date()
    )
}
private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private let analogAudioAnalysisGate = OSAllocatedUnfairLock(
    initialState: (visualizerEnabled: false, meterEnabled: false, lastUpdate: UInt64(0))
)

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
    @Published var libraryShuffleOrder: [String] = []
    @Published var skippedAudioFiles: [String] = []
    @Published var customPlaylists: [String: [String]] = [:]
    @Published var eqEnabled = true
    @Published var eqOutputPeak: Float = 0
    @Published var eqOutputRMS: Float = 0
    @Published var eqGains: [Float] = Array(repeating: 0, count: 10)
    @Published var eqBandwidths: [Float] = Array(repeating: 1.0, count: 10)
    @Published var eqSnapshotA: [Float] = Array(repeating: 0, count: 10)
    @Published var eqSnapshotB: [Float] = Array(repeating: 0, count: 10)
    @Published var repeatQueue = false
    @Published var repeatCurrentTrack = false
    @Published var shuffleQueue = false
    @Published var sleepMinutes = 0
    @Published var visualizerBins: [Double] = Array(repeating: 0, count: 36)
    @Published var visualizerWaveform: [Double] = Array(repeating: 0, count: 128)
    @Published var visualizerEnabled = false
    @Published var preampDB: Float = 0
    @Published var stereoPan: Float = 0
    @Published var outputVolume: Float = 0.85
    @Published var peakGuardEnabled = false
    @Published var peakGuardThresholdDB: Float = -1
    @Published var playHistory: [String] = []
    @Published var playCounts: [String: Int] = [:]
    @Published var devicePresets: [EQDevicePreset] = []
    @Published var outputDeviceName = "Detecting output…"
    @Published var activeDevicePresetName: String?
    @Published var autoMatchOutputDevice = true
    @Published var gaplessPlaybackEnabled = true
    @Published var crossfeedEnabled = false
    @Published var crossfeedAmount: Float = 0.12
    @Published private(set) var crossfeedAvailable = false

    let frequencies: [Float] = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let equalizer = AVAudioUnitEQ(numberOfBands: 10)
    private let peakGuard: AVAudioUnitEffect = {
        let description = AudioComponentDescription(
            componentType: kAudioUnitType_Effect,
            componentSubType: kAudioUnitSubType_DynamicsProcessor,
            componentManufacturer: kAudioUnitManufacturer_Apple,
            componentFlags: 0,
            componentFlagsMask: 0
        )
        return AVAudioUnitEffect(audioComponentDescription: description)
    }()
    private var audioFile: AVAudioFile?
    private var timer: Timer?
    private var sleepTimer: Timer?
    private var sleepFadeTimer: Timer?
    private var segmentStart: Double = 0
    private var playbackToken = UUID()
    private let defaults = UserDefaults.standard
    private var mediaCommandsConfigured = false
    private let outputDeviceMonitor = OutputDeviceMonitor()
    private var outputDeviceCancellable: AnyCancellable?
    private var gaplessFiles: [AVAudioFile] = []
    private var gaplessSegmentStartedAt = Date()
    private var crossfeedUnit: AVAudioUnit?

    func configureMediaCommands() {
        guard !mediaCommandsConfigured else { return }
        mediaCommandsConfigured = true
        let center = MPRemoteCommandCenter.shared()
        center.previousTrackCommand.isEnabled = true
        center.nextTrackCommand.isEnabled = true
        center.togglePlayPauseCommand.isEnabled = true
        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previous() }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.next() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayback() }
            return .success
        }
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if let self, !self.isPlaying { self.togglePlayback() }
            }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                if let self, self.isPlaying { self.togglePlayback() }
            }
            return .success
        }
        updateSystemNowPlayingInfo()
    }

    private func updateSystemNowPlayingInfo() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPMediaItemPropertyPlaybackDuration: track.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        if let path = track.artworkPath, let image = NSImage(contentsOfFile: path) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(
                boundsSize: image.size,
                requestHandler: { _ in image }
            )
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    init() {
        audioEngine.attach(playerNode)
        audioEngine.attach(equalizer)
        audioEngine.attach(peakGuard)
        audioEngine.connect(playerNode, to: equalizer, format: nil)
        audioEngine.connect(equalizer, to: peakGuard, format: nil)
        audioEngine.connect(peakGuard, to: audioEngine.mainMixerNode, format: nil)
        peakGuard.auAudioUnit.shouldBypassEffect = true
        configurePeakGuard()
        equalizer.installTap(onBus: 0, bufferSize: 4096, format: nil) { [weak self] buffer, _ in
            guard let channel = buffer.floatChannelData?[0] else { return }
            let count = Int(buffer.frameLength)
            guard count > 0 else { return }

            let work = analogAudioAnalysisGate.withLock { state -> (meter: Bool, visualizer: Bool) in
                let now = DispatchTime.now().uptimeNanoseconds
                guard (state.meterEnabled || state.visualizerEnabled),
                      now &- state.lastUpdate >= 100_000_000 else { return (false, false) }
                state.lastUpdate = now
                return (state.meterEnabled, state.visualizerEnabled)
            }
            guard work.meter || work.visualizer else { return }

            var peak: Float = 0
            var rms: Float = 0
            if work.meter {
                var sumSquares: Double = 0
                for index in 0..<count {
                    let sample = channel[index]
                    let magnitude = abs(sample)
                    if magnitude > peak { peak = magnitude }
                    sumSquares += Double(sample) * Double(sample)
                }
                rms = Float(sqrt(sumSquares / Double(count)))
            }

            let samples = work.visualizer
                ? Array(UnsafeBufferPointer(start: channel, count: count))
                : []
            let sampleRate = buffer.format.sampleRate

            Task { @MainActor [weak self] in
                guard let self else { return }
                if work.meter {
                    self.eqOutputPeak = peak
                    self.eqOutputRMS = rms
                }
                if work.visualizer {
                    self.visualizerBins = analogSpectrum(
                        samples: samples,
                        sampleRate: sampleRate,
                        binCount: 36
                    )
                    self.visualizerWaveform = analogWaveform(samples: samples, pointCount: 128)
                }
            }
        }
        for (index, band) in equalizer.bands.enumerated() {
            band.filterType = index == 0 ? .lowShelf : (index == frequencies.count - 1 ? .highShelf : .parametric)
            band.frequency = frequencies[index]
            band.bandwidth = 1.0
            band.gain = 0
            band.bypass = false
        }
        restoreState()
        applyEqualizer()
        configureCrossfeedUnit()
        outputDeviceName = outputDeviceMonitor.deviceName
        outputDeviceCancellable = outputDeviceMonitor.$deviceName.removeDuplicates().sink { [weak self] name in
            Task { @MainActor in self?.handleOutputDeviceChange(name) }
        }
        if autoMatchOutputDevice { applyMatchingDevicePreset(for: outputDeviceName) }
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
        if let data = defaults.data(forKey: "libraryTracks"), let saved = try? JSONDecoder().decode([MusicTrack].self, from: data) { tracks = saved.filter { FileManager.default.fileExists(atPath: $0.path) } }
        moods = defaults.dictionary(forKey: "moods") as? [String: String] ?? [:]
        libraryShuffleOrder = defaults.stringArray(forKey: "libraryShuffleOrder") ?? []
        customPlaylists = defaults.dictionary(forKey: "customPlaylists") as? [String: [String]] ?? [:]
        eqEnabled = defaults.object(forKey: "eqEnabled") as? Bool ?? true
        preampDB = defaults.object(forKey: "preampDB") as? Float ?? 0
        stereoPan = defaults.object(forKey: "stereoPan") as? Float ?? 0
        outputVolume = defaults.object(forKey: "outputVolume") as? Float ?? 0.85
        peakGuardEnabled = defaults.object(forKey: "peakGuardEnabled") as? Bool ?? false
        peakGuardThresholdDB = defaults.object(forKey: "peakGuardThresholdDB") as? Float ?? -1
        playHistory = defaults.stringArray(forKey: "playHistory") ?? []
        playCounts = defaults.dictionary(forKey: "playCounts") as? [String: Int] ?? [:]
        if let data = defaults.data(forKey: "deviceEQPresets"), let saved = try? JSONDecoder().decode([EQDevicePreset].self, from: data) { devicePresets = saved.filter { $0.gains.count == 10 && $0.bandwidths.count == 10 } }
        autoMatchOutputDevice = defaults.object(forKey: "autoMatchOutputDevice") as? Bool ?? true
        gaplessPlaybackEnabled = defaults.object(forKey: "gaplessPlaybackEnabled") as? Bool ?? true
        crossfeedEnabled = defaults.object(forKey: "crossfeedEnabled") as? Bool ?? false
        crossfeedAmount = min(0.35, max(0, defaults.object(forKey: "crossfeedAmount") as? Float ?? 0.12))
        audioEngine.mainMixerNode.outputVolume = outputVolume
        if let values = defaults.array(forKey: "eqGains") as? [Float], values.count == 10 { eqGains = values }
        if let values = defaults.array(forKey: "eqBandwidths") as? [Float], values.count == 10 { eqBandwidths = values.map { min(4, max(0.1, $0)) } }
        if let values = defaults.array(forKey: "eqSnapshotA") as? [Float], values.count == 10 { eqSnapshotA = values }
        if let values = defaults.array(forKey: "eqSnapshotB") as? [Float], values.count == 10 { eqSnapshotB = values }
    }

    private func persistListeningState() {
        defaults.set(playHistory, forKey: "playHistory")
        defaults.set(playCounts, forKey: "playCounts")
    }

    func persistState() {
        defaults.set(Array(favorites), forKey: "favorites")
        if let data = try? JSONEncoder().encode(tracks) { defaults.set(data, forKey: "libraryTracks") }
        defaults.set(moods, forKey: "moods")
        defaults.set(libraryShuffleOrder, forKey: "libraryShuffleOrder")
        defaults.set(customPlaylists, forKey: "customPlaylists")
        defaults.set(eqEnabled, forKey: "eqEnabled")
        defaults.set(preampDB, forKey: "preampDB")
        defaults.set(stereoPan, forKey: "stereoPan")
        defaults.set(outputVolume, forKey: "outputVolume")
        defaults.set(peakGuardEnabled, forKey: "peakGuardEnabled")
        defaults.set(peakGuardThresholdDB, forKey: "peakGuardThresholdDB")
        defaults.set(playHistory, forKey: "playHistory")
        defaults.set(playCounts, forKey: "playCounts")
        if let data = try? JSONEncoder().encode(devicePresets) { defaults.set(data, forKey: "deviceEQPresets") }
        defaults.set(autoMatchOutputDevice, forKey: "autoMatchOutputDevice")
        defaults.set(gaplessPlaybackEnabled, forKey: "gaplessPlaybackEnabled")
        defaults.set(crossfeedEnabled, forKey: "crossfeedEnabled")
        defaults.set(crossfeedAmount, forKey: "crossfeedAmount")
        defaults.set(eqGains, forKey: "eqGains")
        defaults.set(eqBandwidths, forKey: "eqBandwidths")
        defaults.set(eqSnapshotA, forKey: "eqSnapshotA")
        defaults.set(eqSnapshotB, forKey: "eqSnapshotB")
    }

    func importAudioFiles() {
        let panel = NSOpenPanel()
        panel.title = "Add audio files to Analog"
        panel.prompt = "Add Files"
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = true
        panel.allowedContentTypes = [.audio]
        guard panel.runModal() == .OK else { return }
        loadTracks(from: panel.urls, folder: nil)
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

    @Published var isScanning = false
    @Published var scanSummary = ""

    func scan(_ folder: URL) {
        guard !isScanning else { return }
        isScanning = true
        scanSummary = "Scanning your music collection…"
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let enumerator = FileManager.default.enumerator(
                at: folder,
                includingPropertiesForKeys: [.isRegularFileKey],
                options: [.skipsHiddenFiles]
            )
            var found: [MusicTrack] = []
            var skippedFiles: [String] = []
            while let url = enumerator?.nextObject() as? URL {
                let isAudio = analogSupportedAudioExtensions.contains(url.pathExtension.lowercased())
                    || (UTType(filenameExtension: url.pathExtension)?.conforms(to: .audio) ?? false)
                guard isAudio else { continue }
                if let track = makeMusicTrack(from: url) {
                    found.append(track)
                } else {
                    skippedFiles.append(url.path)
                }
            }
            DispatchQueue.main.async {
                guard let self else { return }
                var merged = Dictionary(uniqueKeysWithValues: self.tracks.map { ($0.id, $0) })
                for var track in found {
                    if let existing = merged[track.id] { track.dateAdded = existing.dateAdded ?? track.dateAdded }
                    merged[track.id] = track
                }
                self.skippedAudioFiles = skippedFiles.sorted()
                self.tracks = merged.values.sorted {
                    $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
                }
                self.defaults.set(folder.path, forKey: "musicFolder")
                self.persistState()
                self.isScanning = false
                self.scanSummary = "Library: \(self.tracks.count) tracks • \(skippedFiles.count) unsupported/unreadable audio files"
            }
        }
    }

    private func loadTracks(from urls: [URL], folder: URL?) {
        guard !isScanning else { return }
        isScanning = true
        scanSummary = "Scanning audio files…"
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var found: [MusicTrack] = []
            var skippedFiles: [String] = []
            for url in urls {
                let extensionName = url.pathExtension.lowercased()
                let isAudio = analogSupportedAudioExtensions.contains(extensionName)
                    || (UTType(filenameExtension: extensionName)?.conforms(to: .audio) ?? false)
                guard isAudio else { continue }
                if let track = makeMusicTrack(from: url) {
                    found.append(track)
                } else {
                    skippedFiles.append(url.path)
                }
            }
            DispatchQueue.main.async {
                guard let self else { return }
                var merged = Dictionary(uniqueKeysWithValues: self.tracks.map { ($0.id, $0) })
                for var track in found {
                    if let existing = merged[track.id] { track.dateAdded = existing.dateAdded ?? track.dateAdded }
                    merged[track.id] = track
                }
                self.skippedAudioFiles = skippedFiles.sorted()
                self.tracks = merged.values.sorted {
                    $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
                }
                if let folder {
                    self.defaults.set(folder.path, forKey: "musicFolder")
                }
                self.persistState()
                self.isScanning = false
                self.scanSummary = "Added/updated \(found.count) playable tracks • \(skippedFiles.count) unreadable files"
            }
        }
    }

    func setVisualizerEnabled(_ enabled: Bool) {
        visualizerEnabled = enabled
        let meterEnabled = selectedTab == "Equalizer"
        analogAudioAnalysisGate.withLock { state in
            state.visualizerEnabled = enabled
            state.meterEnabled = meterEnabled
            state.lastUpdate = 0
        }
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

    func shuffleLibrary() {
        libraryShuffleOrder = tracks.map(\.id).shuffled()
        persistState()
    }

    func playRandomLibraryTrack() {
        guard !tracks.isEmpty else { return }
        let shuffled = tracks.shuffled()
        queue = shuffled
        queueIndex = 0
        startTrack(shuffled[0], at: 0)
    }

    func addNext(_ track: MusicTrack) {
        if queue.isEmpty {
            queue = [track]
            queueIndex = 0
            return
        }
        let insertion = min(max(queueIndex + 1, 0), queue.count)
        queue.insert(track, at: insertion)
    }

    func addToQueue(_ track: MusicTrack) {
        queue.append(track)
    }

    func removeFromQueue(at index: Int) {
        // The active track is controlled by the transport, not the upcoming queue.
        guard queue.indices.contains(index), index != queueIndex else { return }
        queue.remove(at: index)
        if index < queueIndex { queueIndex -= 1 }
    }

    func moveQueueItem(from source: Int, to destination: Int) {
        guard queue.indices.contains(source), queue.indices.contains(destination), source != destination else { return }
        let item = queue.remove(at: source)
        queue.insert(item, at: destination)
        if queueIndex == source { queueIndex = destination }
        else if source < queueIndex && destination >= queueIndex { queueIndex -= 1 }
        else if source > queueIndex && destination <= queueIndex { queueIndex += 1 }
    }

    func clearUpcomingQueue() {
        guard queueIndex < queue.count else { queue = []; queueIndex = 0; return }
        queue = Array(queue.prefix(queueIndex + 1))
    }

    var historyTracks: [MusicTrack] {
        playHistory.compactMap { id in tracks.first { $0.id == id } }
    }

    var mostPlayedTracks: [MusicTrack] {
        tracks.sorted { (playCounts[$0.id] ?? 0) > (playCounts[$1.id] ?? 0) }.filter { (playCounts[$0.id] ?? 0) > 0 }
    }

    private func startTrack(_ track: MusicTrack, at seconds: Double, recordPlay: Bool = true) {
        playbackToken = UUID()
        let token = playbackToken
        playerNode.stop()
        gaplessFiles.removeAll()
        do {
            let firstFile = try AVAudioFile(forReading: track.url)
            audioFile = firstFile
            if recordPlay { recordGaplessPlay(track) }
            currentTrack = track
            currentTime = min(max(0, seconds), max(0, track.duration))
            segmentStart = currentTime
            gaplessSegmentStartedAt = Date().addingTimeInterval(-currentTime)
            if !audioEngine.isRunning { try audioEngine.start() }

            if gaplessPlaybackEnabled {
                var sequence: [(track: MusicTrack, index: Int)] = []
                if queue.indices.contains(queueIndex) {
                    sequence.append((queue[queueIndex], queueIndex))
                    if repeatCurrentTrack {
                        sequence.append((queue[queueIndex], queueIndex))
                    } else {
                        var remaining = Array(queue.indices.filter { $0 != queueIndex })
                        if shuffleQueue { remaining.shuffle() }
                        sequence.append(contentsOf: remaining.map { (queue[$0], $0) })
                        if repeatQueue && queue.count > 1 {
                            let prefix = Array(queue.indices.filter { $0 < queueIndex })
                            sequence.append(contentsOf: prefix.map { (queue[$0], $0) })
                        }
                    }
                } else {
                    sequence = [(track, 0)]
                }

                var files: [AVAudioFile] = []
                var scheduled: [(track: MusicTrack, index: Int, file: AVAudioFile, startFrame: AVAudioFramePosition, frameCount: AVAudioFrameCount)] = []
                for (position, item) in sequence.enumerated() {
                    let file: AVAudioFile
                    if position == 0 { file = firstFile }
                    else {
                        do { file = try AVAudioFile(forReading: item.track.url) }
                        catch { continue }
                    }
                    let startFrame = position == 0 ? AVAudioFramePosition(currentTime * file.processingFormat.sampleRate) : 0
                    let remaining = max(0, file.length - startFrame)
                    let frameCount = AVAudioFrameCount(min(remaining, AVAudioFramePosition(UInt32.max)))
                    guard frameCount > 0 else { continue }
                    files.append(file)
                    scheduled.append((item.track, item.index, file, startFrame, frameCount))
                }
                guard !scheduled.isEmpty else { throw NSError(domain: "AnalogPlayback", code: 1) }
                gaplessFiles = files
                for (position, item) in scheduled.enumerated() {
                    playerNode.scheduleSegment(
                        item.file,
                        startingFrame: item.startFrame,
                        frameCount: item.frameCount,
                        at: nil,
                        completionCallbackType: .dataPlayedBack
                    ) { [weak self] _ in
                        DispatchQueue.main.async {
                            guard let self, self.playbackToken == token else { return }
                            if position + 1 < scheduled.count {
                                let next = scheduled[position + 1]
                                self.queueIndex = next.index
                                self.currentTrack = next.track
                                self.currentTime = 0
                                self.segmentStart = 0
                                self.gaplessSegmentStartedAt = Date()
                                self.recordGaplessPlay(next.track)
                                self.updateSystemNowPlayingInfo()
                            } else {
                                self.currentTime = self.currentTrack?.duration ?? 0
                                self.isPlaying = false
                                self.timer?.invalidate()
                                self.gaplessFiles.removeAll()
                                self.updateSystemNowPlayingInfo()
                                if self.repeatCurrentTrack, let current = self.currentTrack {
                                    self.startTrack(current, at: 0, recordPlay: false)
                                } else if self.repeatQueue, let first = self.queue.first {
                                    self.queueIndex = 0
                                    self.startTrack(first, at: 0)
                                }
                            }
                        }
                    }
                }
            } else {
                let startFrame = AVAudioFramePosition(currentTime * firstFile.processingFormat.sampleRate)
                let remaining = max(0, firstFile.length - startFrame)
                let frameCount = AVAudioFrameCount(min(remaining, AVAudioFramePosition(UInt32.max)))
                guard frameCount > 0 else { advance(); return }
                playerNode.scheduleSegment(firstFile, startingFrame: startFrame, frameCount: frameCount, at: nil, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                    DispatchQueue.main.async {
                        guard let self, self.playbackToken == token else { return }
                        self.advance()
                    }
                }
            }
            playerNode.play()
            isPlaying = true
            updateSystemNowPlayingInfo()
            startClock()
        } catch {
            isPlaying = false
            currentTrack = track
            gaplessFiles.removeAll()
            updateSystemNowPlayingInfo()
            print("Analog playback error: \(error.localizedDescription)")
        }
    }

    private func recordGaplessPlay(_ track: MusicTrack) {
        playHistory.removeAll { $0 == track.id }
        playHistory.insert(track.id, at: 0)
        if playHistory.count > 500 { playHistory = Array(playHistory.prefix(500)) }
        playCounts[track.id, default: 0] += 1
        persistListeningState()
    }

    func togglePlayback() {
        if isPlaying {
            updateClock()
            playerNode.pause()
            isPlaying = false
            timer?.invalidate()
        } else if let track = currentTrack {
            let restartTime = currentTime >= track.duration ? 0 : currentTime
            startTrack(track, at: restartTime, recordPlay: false)
        } else {
            playRandomLibraryTrack()
        }
        updateSystemNowPlayingInfo()
    }

    func seek(to seconds: Double) {
        guard let track = currentTrack else { return }
        let wasPlaying = isPlaying
        let target = min(max(0, seconds), max(0, track.duration))
        if target >= track.duration {
            if wasPlaying {
                advance()
            } else {
                currentTime = track.duration
                updateSystemNowPlayingInfo()
            }
            return
        }
        startTrack(track, at: target, recordPlay: false)
        if !wasPlaying {
            playerNode.pause()
            isPlaying = false
            timer?.invalidate()
        }
    }

    func previous() {
        if currentTime > 3 { seek(to: 0); return }
        guard !queue.isEmpty else { return }
        let index = queueIndex > 0 ? queueIndex - 1 : (repeatQueue ? queue.count - 1 : 0)
        queueIndex = index
        startTrack(queue[index], at: 0)
    }

    func next() { advance() }

    private func advance() {
        if repeatCurrentTrack, let track = currentTrack { startTrack(track, at: 0); return }
        guard !queue.isEmpty else { isPlaying = false; timer?.invalidate(); return }
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
        guard isPlaying else { return }
        if gaplessPlaybackEnabled {
            currentTime = min(currentTrack?.duration ?? .greatestFiniteMagnitude, Date().timeIntervalSince(gaplessSegmentStartedAt))
            return
        }
        guard let nodeTime = playerNode.lastRenderTime, let playerTime = playerNode.playerTime(forNodeTime: nodeTime), playerTime.sampleRate > 0 else { return }
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

    func exportPlaylist(_ name: String, tracks: [MusicTrack]) {
        guard !tracks.isEmpty else { return }
        let panel = NSSavePanel()
        panel.title = "Export playlist"
        panel.nameFieldStringValue = "\(name).m3u8"
        panel.allowedContentTypes = [.plainText]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let lines = tracks.flatMap { track in
            ["#EXTINF:\(Int(track.duration)),\(track.artist) - \(track.title)", track.path]
        }
        let contents = (["#EXTM3U"] + lines).joined(separator: "\n") + "\n"
        do { try contents.write(to: url, atomically: true, encoding: .utf8) }
        catch { print("Analog playlist export error: \(error.localizedDescription)") }
    }

    func renamePlaylist(_ oldName: String, to newName: String) {
        let clean = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, oldName != clean, let items = customPlaylists.removeValue(forKey: oldName) else { return }
        customPlaylists[clean] = items
        persistState()
    }

    func deletePlaylist(_ name: String) { customPlaylists.removeValue(forKey: name); persistState() }

    func remove(_ track: MusicTrack, fromPlaylist name: String) {
        customPlaylists[name]?.removeAll { $0 == track.id }
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

    func add(_ track: MusicTrack, toPlaylist name: String) {
        var ids = customPlaylists[name] ?? []
        if !ids.contains(track.id) { ids.append(track.id) }
        customPlaylists[name] = ids
        persistState()
    }

    func clearHistory() { playHistory = []; playCounts = [:]; persistState() }

    func removeTrackFromLibrary(_ track: MusicTrack) {
        tracks.removeAll { $0.id == track.id }
        favorites.remove(track.id)
        moods.removeValue(forKey: track.id)
        playHistory.removeAll { $0 == track.id }
        playCounts.removeValue(forKey: track.id)
        for name in Array(customPlaylists.keys) { customPlaylists[name]?.removeAll { $0 == track.id } }
        let currentQueuePosition = min(queueIndex, queue.count)
        let removedBeforeCurrent = queue.prefix(currentQueuePosition).filter { $0.id == track.id }.count
        let removesCurrentTrack = currentTrack?.id == track.id
        queue.removeAll { $0.id == track.id }
        if removesCurrentTrack {
            playbackToken = UUID()
            playerNode.stop()
            gaplessFiles.removeAll()
            isPlaying = false
            self.currentTrack = nil
            currentTime = 0
            timer?.invalidate()
            queueIndex = min(queueIndex, max(0, queue.count - 1))
        } else {
            queueIndex = max(0, queueIndex - removedBeforeCurrent)
        }
        persistState()
    }

    var smartMixes: [AnalogSmartMix] {
        let recent = historyTracks
        let longTracks = tracks.filter { $0.duration >= 300 }
        let shortTracks = tracks.filter { $0.duration > 0 && $0.duration <= 180 }
        return [
            AnalogSmartMix(name: "Recently Played", subtitle: "Your latest listening rotation", tracks: recent),
            AnalogSmartMix(name: "Most Played", subtitle: "Tracks you return to most", tracks: mostPlayedTracks),
            AnalogSmartMix(name: "Long Sessions", subtitle: "Tracks longer than five minutes", tracks: longTracks),
            AnalogSmartMix(name: "Quick Listen", subtitle: "Tracks under three minutes", tracks: shortTracks),
            AnalogSmartMix(name: "Favourites", subtitle: "Your saved favourite tracks", tracks: tracks.filter { favorites.contains($0.id) })
        ]
    }



    private func configureCrossfeedUnit() {
        let description = AudioComponentDescription(
            componentType: kAudioUnitType_Mixer,
            componentSubType: kAudioUnitSubType_MatrixMixer,
            componentManufacturer: kAudioUnitManufacturer_Apple,
            componentFlags: 0,
            componentFlagsMask: 0
        )
        AVAudioUnit.instantiate(with: description, options: []) { [weak self] unit, error in
            guard let unit else {
                if let error { print("Analog crossfeed unavailable: \\(error.localizedDescription)") }
                return
            }
            Task { @MainActor in self?.installCrossfeedUnit(unit) }
        }
    }

    private func installCrossfeedUnit(_ unit: AVAudioUnit) {
        var inputBusCount: UInt32 = 1
        var outputBusCount: UInt32 = 1
        let elementCountSize = UInt32(MemoryLayout<UInt32>.size)
        let inputStatus = AudioUnitSetProperty(
            unit.audioUnit,
            kAudioUnitProperty_ElementCount,
            kAudioUnitScope_Input,
            0,
            &inputBusCount,
            elementCountSize
        )
        let outputStatus = AudioUnitSetProperty(
            unit.audioUnit,
            kAudioUnitProperty_ElementCount,
            kAudioUnitScope_Output,
            0,
            &outputBusCount,
            elementCountSize
        )
        guard inputStatus == noErr, outputStatus == noErr else {
            crossfeedAvailable = false
            crossfeedEnabled = false
            print("Analog crossfeed bus configuration failed: input=\\(inputStatus), output=\\(outputStatus)")
            return
        }

        let wasRunning = audioEngine.isRunning
        let wasPlaying = isPlaying
        if wasPlaying { updateClock() }
        let resumeTrack = currentTrack
        let resumeTime = currentTime
        if wasRunning { audioEngine.stop() }
        audioEngine.disconnectNodeOutput(playerNode)
        audioEngine.attach(unit)
        audioEngine.connect(playerNode, to: unit, format: nil)
        audioEngine.connect(unit, to: equalizer, format: nil)
        audioEngine.prepare()
        crossfeedUnit = unit
        crossfeedAvailable = true
        applyCrossfeedMatrix()
        if wasRunning {
            do {
                try audioEngine.start()
                if let resumeTrack {
                    startTrack(resumeTrack, at: resumeTime, recordPlay: false)
                    if !wasPlaying {
                        playerNode.pause()
                        isPlaying = false
                        timer?.invalidate()
                    }
                }
            } catch {
                crossfeedAvailable = false
                crossfeedEnabled = false
                print("Analog audio graph update failed: \\(error.localizedDescription)")
            }
        }
    }

    private func bypassCrossfeedUnit() {
        guard let unit = crossfeedUnit else { return }
        let wasRunning = audioEngine.isRunning
        let wasPlaying = isPlaying
        if wasPlaying { updateClock() }
        let resumeTrack = currentTrack
        let resumeTime = currentTime
        if wasRunning { audioEngine.stop() }
        audioEngine.disconnectNodeOutput(playerNode)
        audioEngine.disconnectNodeOutput(unit)
        audioEngine.detach(unit)
        audioEngine.connect(playerNode, to: equalizer, format: nil)
        crossfeedUnit = nil
        crossfeedEnabled = false
        crossfeedAvailable = false
        if wasRunning {
            do {
                try audioEngine.start()
                if let resumeTrack {
                    startTrack(resumeTrack, at: resumeTime, recordPlay: false)
                    if !wasPlaying {
                        playerNode.pause()
                        isPlaying = false
                        timer?.invalidate()
                    }
                }
            } catch {
                isPlaying = false
                timer?.invalidate()
                print("Analog crossfeed bypass recovery failed: \\(error.localizedDescription)")
            }
        }
    }

    private func applyCrossfeedMatrix() {
        guard let unit = crossfeedUnit else { return }
        var dimensions = [UInt32](repeating: 0, count: 2)
        var size = UInt32(MemoryLayout<UInt32>.size * dimensions.count)
        guard AudioUnitGetProperty(
            unit.audioUnit,
            kAudioUnitProperty_MatrixDimensions,
            kAudioUnitScope_Global,
            0,
            &dimensions,
            &size
        ) == noErr, dimensions == [2, 2] else {
            bypassCrossfeedUnit()
            return
        }
        let amount = crossfeedEnabled ? min(0.35, max(0, crossfeedAmount)) : 0
        var levels = stereoCrossfeedMatrix(amount: amount)
        let status = levels.withUnsafeMutableBufferPointer { buffer in
            AudioUnitSetProperty(
                unit.audioUnit,
                kAudioUnitProperty_MatrixLevels,
                kAudioUnitScope_Global,
                0,
                buffer.baseAddress!,
                UInt32(buffer.count * MemoryLayout<Float32>.size)
            )
        }
        if status != noErr {
            print("Analog crossfeed matrix update failed: \\(status)")
            bypassCrossfeedUnit()
        }
    }

    func setCrossfeed(enabled: Bool, amount: Float? = nil) {
        crossfeedEnabled = enabled
        if let amount { crossfeedAmount = min(0.35, max(0, amount)) }
        applyCrossfeedMatrix()
        persistState()
    }

    private func normalizedDeviceName(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
    }

    private func handleOutputDeviceChange(_ name: String) {
        outputDeviceName = name
        guard autoMatchOutputDevice else { return }
        applyMatchingDevicePreset(for: name)
    }

    private func applyMatchingDevicePreset(for name: String) {
        let key = normalizedDeviceName(name)
        guard !key.isEmpty,
              let preset = devicePresets.first(where: { $0.matchesOutput(named: name) }) else {
            activeDevicePresetName = nil
            eqGains = Array(repeating: 0, count: 10)
            eqBandwidths = Array(repeating: 1, count: 10)
            preampDB = 0
            eqEnabled = true
            applyEqualizer()
            persistState()
            return
        }
        applyDevicePreset(preset)
    }

    func setAutomaticDeviceMatching(_ enabled: Bool) {
        autoMatchOutputDevice = enabled
        if enabled { applyMatchingDevicePreset(for: outputDeviceName) }
        persistState()
    }

    func saveDevicePreset(named rawName: String) {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, eqGains.count == 10, eqBandwidths.count == 10 else { return }
        let preset = EQDevicePreset(name: name, gains: eqGains, bandwidths: eqBandwidths, preampDB: preampDB, eqEnabled: eqEnabled)
        if let index = devicePresets.firstIndex(where: { normalizedDeviceName($0.name) == normalizedDeviceName(name) }) {
            devicePresets[index] = preset
        } else {
            devicePresets.append(preset)
        }
        activeDevicePresetName = name
        persistState()
    }

    func applyDevicePreset(_ preset: EQDevicePreset) {
        guard preset.gains.count == 10, preset.bandwidths.count == 10 else { return }
        eqGains = preset.gains.map { min(12, max(-12, $0)) }
        eqBandwidths = preset.bandwidths.map { min(4, max(0.1, $0)) }
        preampDB = min(12, max(-12, preset.preampDB))
        eqEnabled = preset.eqEnabled
        activeDevicePresetName = preset.name
        applyEqualizer()
        persistState()
    }

    func renameDevicePreset(_ preset: EQDevicePreset, to rawName: String) {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty,
              !devicePresets.contains(where: { $0.id != preset.id && normalizedDeviceName($0.name) == normalizedDeviceName(name) }),
              let index = devicePresets.firstIndex(where: { $0.id == preset.id }) else { return }
        devicePresets[index].name = name
        if activeDevicePresetName == preset.name { activeDevicePresetName = name }
        persistState()
    }

    func deleteDevicePreset(_ preset: EQDevicePreset) {
        devicePresets.removeAll { $0.id == preset.id }
        if activeDevicePresetName == preset.name { activeDevicePresetName = nil }
        persistState()
        if autoMatchOutputDevice { applyMatchingDevicePreset(for: outputDeviceName) }
    }

    func exportDevicePresets() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "Analog-Device-EQ-Presets.json"
        panel.allowedContentTypes = [.json]
        guard panel.runModal() == .OK, let url = panel.url,
              let data = try? JSONEncoder().encode(devicePresets) else { return }
        try? data.write(to: url, options: .atomic)
    }

    func importDevicePresets() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url,
              let data = try? Data(contentsOf: url),
              let imported = try? JSONDecoder().decode([EQDevicePreset].self, from: data) else { return }
        for preset in imported where preset.gains.count == 10 && preset.bandwidths.count == 10 {
            if let index = devicePresets.firstIndex(where: { normalizedDeviceName($0.name) == normalizedDeviceName(preset.name) }) {
                devicePresets[index] = preset
            } else {
                devicePresets.append(preset)
            }
        }
        persistState()
        if autoMatchOutputDevice { applyMatchingDevicePreset(for: outputDeviceName) }
    }

    func setOutputVolume(_ value: Float) {
        outputVolume = min(1, max(0, value))
        audioEngine.mainMixerNode.outputVolume = outputVolume
        persistState()
    }

    func setEQ(_ index: Int, _ gain: Float) {
        guard eqGains.indices.contains(index) else { return }
        eqGains[index] = min(12, max(-12, gain))
        applyEqualizer()
        persistState()
    }

    func setPreamp(_ gain: Float) {
        preampDB = min(12, max(-12, gain))
        applyEqualizer()
        persistState()
    }

    func storeEQSnapshot(_ slot: String) {
        if slot == "A" { eqSnapshotA = eqGains } else { eqSnapshotB = eqGains }
        persistState()
    }

    func recallEQSnapshot(_ slot: String) {
        let values = slot == "A" ? eqSnapshotA : eqSnapshotB
        guard values.count == frequencies.count else { return }
        eqGains = values.map { min(12, max(-12, $0)) }
        applyEqualizer()
        persistState()
    }

    func setEQBandwidth(_ index: Int, _ bandwidth: Float) {
        guard eqBandwidths.indices.contains(index), index > 0, index < frequencies.count - 1 else { return }
        eqBandwidths[index] = min(4, max(0.1, bandwidth))
        applyEqualizer()
        persistState()
    }

    func setEQEnabled(_ enabled: Bool) {
        eqEnabled = enabled
        applyEqualizer()
        persistState()
    }

    private func configurePeakGuard() {
        let threshold = min(-0.1, max(-12, peakGuardThresholdDB))
        _ = AudioUnitSetParameter(
            peakGuard.audioUnit,
            AudioUnitParameterID(kDynamicsProcessorParam_Threshold),
            kAudioUnitScope_Global,
            0,
            threshold,
            0
        )
        peakGuard.auAudioUnit.shouldBypassEffect = !peakGuardEnabled
    }

    func applyEqualizer() {
        equalizer.globalGain = preampDB
        configurePeakGuard()
        playerNode.pan = stereoPan
        for index in equalizer.bands.indices {
            equalizer.bands[index].gain = eqGains[index]
            equalizer.bands[index].bandwidth = eqBandwidths[index]
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
        case "Jazz": values = [3, 2, 1, 2, -1, -1, 0, 2, 3, 4]
        case "Electronic": values = [5, 4, 1, 0, -1, 2, 1, 3, 4, 5]
        case "Acoustic": values = [2, 2, 1, 0, 2, 3, 3, 2, 1, 1]
        case "Podcast": values = [-3, -2, 0, 3, 5, 4, 2, 0, -1, -2]
        case "Night": values = [-4, -3, -1, 2, 3, 2, 0, -1, -2, -3]
        case "R&B": values = [4, 3, 1, 0, -1, 1, 2, 3, 3, 4]
        case "Hip-Hop": values = [6, 5, 2, 1, -1, 0, 2, 1, 2, 3]
        case "Metal": values = [4, 3, 1, -1, -2, 2, 4, 3, 1, 0]
        case "Small Speakers": values = [7, 5, 1, -1, 0, 2, 3, 2, 1, 0]
        case "Headphones": values = [2, 1, 0, -1, 0, 1, 2, 1, 0, 1]
        default: values = Array(repeating: 0, count: 10)
        }
        eqGains = values
        applyEqualizer()
        persistState()
    }

    func setPeakGuard(enabled: Bool) {
        peakGuardEnabled = enabled
        applyEqualizer()
        persistState()
    }

    func setPeakGuardThreshold(_ value: Float) {
        peakGuardThresholdDB = min(-0.1, max(-12, value))
        applyEqualizer()
        persistState()
    }

    func setSleepTimer(minutes: Int) {
        sleepTimer?.invalidate()
        sleepFadeTimer?.invalidate()
        playerNode.volume = 1
        sleepMinutes = max(0, minutes)
        guard sleepMinutes > 0 else { return }
        let total = Double(sleepMinutes * 60)
        let fadeDuration = min(15.0, total)
        sleepTimer = Timer.scheduledTimer(withTimeInterval: max(0, total - fadeDuration), repeats: false) { [weak self] _ in
            Task { @MainActor in self?.beginSleepFade(duration: fadeDuration) }
        }
    }

    private func beginSleepFade(duration: Double) {
        let started = Date()
        sleepFadeTimer?.invalidate()
        sleepFadeTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] timer in
            Task { @MainActor in
                guard let self else { timer.invalidate(); return }
                let progress = min(1, Date().timeIntervalSince(started) / max(0.1, duration))
                self.playerNode.volume = Float(1 - progress)
                if progress >= 1 {
                    timer.invalidate()
                    self.playerNode.pause()
                    self.playerNode.volume = 1
                    self.isPlaying = false
                    self.sleepMinutes = 0
                    self.timer?.invalidate()
                    self.sleepFadeTimer = nil
                }
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
    @Published var inputPeak: Float = 0
    @Published var inputRMS: Float = 0
    private let engine = AVAudioEngine()
    private var outputFile: AVAudioFile?
    private var startedAt: Date?
    private var timer: Timer?
    private var inputTapInstalled = false

    func start() {
        guard !isRecording else { return }
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
                guard let self else { return }
                do {
                    try self.outputFile?.write(from: buffer)
                } catch {
                    let message = error.localizedDescription
                    Task { @MainActor [weak self] in
                        self?.errorMessage = "Recording write failed: \(message)"
                        self?.stop()
                    }
                }
                guard let channels = buffer.floatChannelData, buffer.frameLength > 0 else { return }
                let frames = Int(buffer.frameLength)
                var peak: Float = 0
                var squares = 0.0
                for frame in 0..<frames {
                    for channel in 0..<Int(buffer.format.channelCount) {
                        let sample = channels[channel][frame]
                        peak = max(peak, abs(sample))
                        squares += Double(sample) * Double(sample)
                    }
                }
                let rms = Float(sqrt(squares / Double(frames * Int(buffer.format.channelCount))))
                Task { @MainActor [weak self] in
                    self?.inputPeak = peak
                    self?.inputRMS = rms
                }
            }
            inputTapInstalled = true
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
            if inputTapInstalled {
                engine.inputNode.removeTap(onBus: 0)
                inputTapInstalled = false
            }
            engine.stop()
            outputFile = nil
            timer?.invalidate()
            isRecording = false
            startedAt = nil
        }
    }

    func stop() {
        guard isRecording || inputTapInstalled || engine.isRunning else { return }
        if inputTapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            inputTapInstalled = false
        }
        engine.stop()
        outputFile = nil
        timer?.invalidate()
        isRecording = false
        startedAt = nil
        inputPeak = 0
        inputRMS = 0
    }
}

private func analogSpectrum(samples: [Float], sampleRate: Double, binCount: Int) -> [Double] {
    guard samples.count > 8, sampleRate > 0 else { return Array(repeating: 0, count: binCount) }
    let low = 35.0
    let high = min(16_000.0, sampleRate * 0.45)
    return (0..<binCount).map { index in
        let fraction = Double(index) / Double(max(1, binCount - 1))
        let frequency = low * pow(high / low, fraction)
        let omega = 2.0 * Double.pi * frequency / sampleRate
        let coefficient = 2.0 * cos(omega)
        var q1 = 0.0, q2 = 0.0
        let count = min(samples.count, 1024)
        for i in 0..<count {
            let window = 0.5 - 0.5 * cos(2.0 * Double.pi * Double(i) / Double(max(1, count - 1)))
            let sample = Double(samples[i]) * window
            let q0 = coefficient * q1 - q2 + sample
            q2 = q1; q1 = q0
        }
        let power = max(0, q1 * q1 + q2 * q2 - coefficient * q1 * q2)
        let magnitude = sqrt(power) / Double(count)
        return min(1, max(0, pow(magnitude * 14.0, 0.55)))
    }
}

private func analogWaveform(samples: [Float], pointCount: Int) -> [Double] {
    guard !samples.isEmpty, pointCount > 0 else { return Array(repeating: 0, count: pointCount) }
    return (0..<pointCount).map { index in
        let sampleIndex = min(samples.count - 1, index * samples.count / pointCount)
        return min(1, max(-1, Double(samples[sampleIndex])))
    }
}

struct AnalogSmartMix: Identifiable {
    let name: String
    let subtitle: String
    let tracks: [MusicTrack]
    var id: String { name }
}
