import Foundation

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

    public func matchesOutput(named outputName: String) -> Bool {
        let normalize: (String) -> String = {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
                .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        }
        return !normalize(name).isEmpty && normalize(name) == normalize(outputName)
    }
}
