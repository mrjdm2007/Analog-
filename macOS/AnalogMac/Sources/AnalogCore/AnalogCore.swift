import Foundation

/// Returns the indices that should follow the current track in a gapless queue.
/// Each queued track appears at most once before the queue repeats.
public func gaplessQueueIndices(count: Int, currentIndex: Int, repeatQueue: Bool, shuffleQueue: Bool = false) -> [Int] {
    guard count > 0, currentIndex >= 0, currentIndex < count else { return [] }
    let candidates = (0..<count).filter { index in
        index != currentIndex && (shuffleQueue || repeatQueue || index > currentIndex)
    }
    if shuffleQueue { return candidates.shuffled() }
    if repeatQueue {
        return Array((currentIndex + 1)..<count) + Array(0..<currentIndex)
    }
    return Array((currentIndex + 1)..<count)
}

public func stereoCrossfeedMatrix(amount rawAmount: Float) -> [Float32] {
    let amount = min(0.35, max(0, rawAmount))
    // AVAudioUnitMatrixMixer uses one coefficient per input/output pair.
    // For stereo this is a 2 × 2 matrix, not a 3 × 3 matrix.
    return [
        1 - amount, amount,
        amount, 1 - amount
    ]
}

public struct EQDevicePreset: Identifiable, Codable, Equatable {
    public var id: UUID
    public var name: String
    public var gains: [Float]
    public var bandwidths: [Float]
    public var preampDB: Float
    public var eqEnabled: Bool
    // Optional fields preserve compatibility with profile JSON exported by earlier versions.
    public var crossfeedEnabled: Bool?
    public var crossfeedAmount: Float?
    public var peakGuardEnabled: Bool?
    public var peakGuardThresholdDB: Float?
    public var stereoPan: Float?

    public init(
        id: UUID = UUID(),
        name: String,
        gains: [Float],
        bandwidths: [Float],
        preampDB: Float,
        eqEnabled: Bool,
        crossfeedEnabled: Bool? = nil,
        crossfeedAmount: Float? = nil,
        peakGuardEnabled: Bool? = nil,
        peakGuardThresholdDB: Float? = nil,
        stereoPan: Float? = nil
    ) {
        self.id = id
        self.name = name
        self.gains = gains
        self.bandwidths = bandwidths
        self.preampDB = preampDB
        self.eqEnabled = eqEnabled
        self.crossfeedEnabled = crossfeedEnabled
        self.crossfeedAmount = crossfeedAmount
        self.peakGuardEnabled = peakGuardEnabled
        self.peakGuardThresholdDB = peakGuardThresholdDB
        self.stereoPan = stereoPan
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, gains, bandwidths, preampDB, eqEnabled
        case crossfeedEnabled, crossfeedAmount, peakGuardEnabled, peakGuardThresholdDB, stereoPan
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        name = try values.decode(String.self, forKey: .name)
        gains = try values.decode([Float].self, forKey: .gains)
        bandwidths = try values.decode([Float].self, forKey: .bandwidths)
        preampDB = try values.decodeIfPresent(Float.self, forKey: .preampDB) ?? 0
        eqEnabled = try values.decodeIfPresent(Bool.self, forKey: .eqEnabled) ?? true
        crossfeedEnabled = try values.decodeIfPresent(Bool.self, forKey: .crossfeedEnabled)
        crossfeedAmount = try values.decodeIfPresent(Float.self, forKey: .crossfeedAmount)
        peakGuardEnabled = try values.decodeIfPresent(Bool.self, forKey: .peakGuardEnabled)
        peakGuardThresholdDB = try values.decodeIfPresent(Float.self, forKey: .peakGuardThresholdDB)
        stereoPan = try values.decodeIfPresent(Float.self, forKey: .stereoPan)
    }

    public func matchesOutput(named outputName: String) -> Bool {
        let normalize: (String) -> String = {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
                .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        }
        return !normalize(name).isEmpty && normalize(name) == normalize(outputName)
    }
}
