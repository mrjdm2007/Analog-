import Foundation

public func stereoCrossfeedMatrix(amount rawAmount: Float) -> [Float32] {
    let amount = min(0.35, max(0, rawAmount))
    return [
        1 - amount, amount, 0,
        amount, 1 - amount, 0,
        0, 0, 1
    ]
}

public struct EQDevicePreset: Identifiable, Codable, Equatable {
    public var id: UUID
    public var name: String
    public var gains: [Float]
    public var bandwidths: [Float]
    public var preampDB: Float
    public var eqEnabled: Bool

    public init(id: UUID = UUID(), name: String, gains: [Float], bandwidths: [Float], preampDB: Float, eqEnabled: Bool) {
        self.id = id
        self.name = name
        self.gains = gains
        self.bandwidths = bandwidths
        self.preampDB = preampDB
        self.eqEnabled = eqEnabled
    }

    public func matchesOutput(named outputName: String) -> Bool {
        let normalize: (String) -> String = {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
                .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
        }
        return !normalize(name).isEmpty && normalize(name) == normalize(outputName)
    }
}
