import AVFoundation
import XCTest
import AnalogCore

final class AnalogAudioTests: XCTestCase {
    func testTenBandEQChangesRenderedAudioAndBypassRestoresIt() throws {
        let flatRMS = try renderOneKilohertzTone(eqGain: 0, bypass: false)
        let boostedRMS = try renderOneKilohertzTone(eqGain: 10, bypass: false)
        let bypassedBoostRMS = try renderOneKilohertzTone(eqGain: 10, bypass: true)

        XCTAssertGreaterThan(flatRMS, 0.01, "The offline audio engine should render the input tone.")
        XCTAssertGreaterThan(boostedRMS, flatRMS * 2.0, "A +10 dB 1 kHz EQ band should measurably increase the rendered 1 kHz signal.")
        XCTAssertEqual(bypassedBoostRMS, flatRMS, accuracy: 0.0005, "Bypassing the EQ should restore the flat response even when a band has a stored boost.")
    }

    private func renderOneKilohertzTone(eqGain: Float, bypass: Bool) throws -> Double {
        let sampleRate = 48_000.0
        let frameCount: AVAudioFrameCount = 4096
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2)!
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let equalizer = AVAudioUnitEQ(numberOfBands: 10)

        engine.attach(player)
        engine.attach(equalizer)
        engine.connect(player, to: equalizer, format: format)
        engine.connect(equalizer, to: engine.mainMixerNode, format: format)
        engine.mainMixerNode.outputVolume = 1

        let frequencies: [Float] = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
        for (index, band) in equalizer.bands.enumerated() {
            band.filterType = index == 0 ? .lowShelf : (index == 9 ? .highShelf : .parametric)
            band.frequency = frequencies[index]
            band.bandwidth = 1
            band.gain = index == 5 ? eqGain : 0
            band.bypass = bypass
        }

        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: frameCount)
        try engine.start()

        let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount)!
        input.frameLength = frameCount
        let channels = input.floatChannelData!
        for frame in 0..<Int(frameCount) {
            let value = Float(0.05 * sin(2 * Double.pi * 1000 * Double(frame) / sampleRate))
            channels[0][frame] = value
            channels[1][frame] = value
        }

        let output = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: frameCount)!
        player.scheduleBuffer(input, at: nil, options: [])
        player.play()
        let status = try engine.renderOffline(frameCount, to: output)
        XCTAssertEqual(status, .success, "The audio engine should render the scheduled test tone.")

        let rendered = output.floatChannelData!
        let frames = Int(output.frameLength)
        XCTAssertGreaterThan(frames, 0)
        var squares = 0.0
        for frame in 0..<frames {
            let sample = Double(rendered[0][frame])
            squares += sample * sample
        }

        player.stop()
        engine.stop()
        return sqrt(squares / Double(frames))
    }
}

extension AnalogAudioTests {
    func testDeviceEQProfileRequiresExactOutputNameMatch() {
        let preset = EQDevicePreset(name: "Realtek USB Audio", gains: Array(repeating: 0, count: 10),
                                    bandwidths: Array(repeating: 1, count: 10), preampDB: 0, eqEnabled: true)
        XCTAssertTrue(preset.matchesOutput(named: "  realtek usb audio  "))
        XCTAssertFalse(preset.matchesOutput(named: "Realtek USB"))
        XCTAssertFalse(preset.matchesOutput(named: "USB Audio Realtek"))
    }

    func testStereoCrossfeedMatrixIsAValidTwoByTwoMixerMatrix() {
        let matrix = stereoCrossfeedMatrix(amount: 0.2)
        XCTAssertEqual(matrix.count, 4)
        XCTAssertEqual(matrix[0], 0.8, accuracy: 0.0001)
        XCTAssertEqual(matrix[1], 0.2, accuracy: 0.0001)
        XCTAssertEqual(matrix[2], 0.2, accuracy: 0.0001)
        XCTAssertEqual(matrix[3], 0.8, accuracy: 0.0001)
    }

    func testStereoCrossfeedAmountIsClamped() {
        XCTAssertEqual(stereoCrossfeedMatrix(amount: -1), [1, 0, 0, 1])
        XCTAssertEqual(stereoCrossfeedMatrix(amount: 2), [0.65, 0.35, 0.35, 0.65])
    }

    func testDeviceProfileRoundTripsAllDSPSettingsAndReadsLegacyProfiles() throws {
        let preset = EQDevicePreset(
            name: "USB DAC",
            gains: Array(repeating: 1, count: 10),
            bandwidths: Array(repeating: 1.25, count: 10),
            preampDB: -3,
            eqEnabled: true,
            crossfeedEnabled: true,
            crossfeedAmount: 0.2,
            peakGuardEnabled: true,
            peakGuardThresholdDB: -2,
            stereoPan: 0.15
        )
        let encoded = try JSONEncoder().encode(preset)
        let decoded = try JSONDecoder().decode(EQDevicePreset.self, from: encoded)
        XCTAssertEqual(decoded.crossfeedEnabled, true)
        XCTAssertEqual(decoded.crossfeedAmount ?? 0, 0.2, accuracy: 0.0001)
        XCTAssertEqual(decoded.peakGuardEnabled, true)
        XCTAssertEqual(decoded.peakGuardThresholdDB ?? 0, -2, accuracy: 0.0001)
        XCTAssertEqual(decoded.stereoPan ?? 0, 0.15, accuracy: 0.0001)

        var legacyObject = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        legacyObject.removeValue(forKey: "id")
        legacyObject.removeValue(forKey: "crossfeedEnabled")
        legacyObject.removeValue(forKey: "crossfeedAmount")
        legacyObject.removeValue(forKey: "peakGuardEnabled")
        legacyObject.removeValue(forKey: "peakGuardThresholdDB")
        legacyObject.removeValue(forKey: "stereoPan")
        let legacyData = try JSONSerialization.data(withJSONObject: legacyObject)
        let legacy = try JSONDecoder().decode(EQDevicePreset.self, from: legacyData)
        XCTAssertNil(legacy.crossfeedEnabled)
        XCTAssertNil(legacy.stereoPan)
        XCTAssertNotNil(legacy.id)
    }
}


extension AnalogAudioTests {
    func testGaplessRepeatQueueVisitsEveryTrackOnceBeforeWrapping() {
        XCTAssertEqual(
            gaplessQueueIndices(count: 4, currentIndex: 2, repeatQueue: true),
            [3, 0, 1]
        )
        XCTAssertEqual(
            gaplessQueueIndices(count: 4, currentIndex: 2, repeatQueue: false),
            [3]
        )
    }

    func testGaplessShuffleQueueDoesNotDuplicateEarlierTracks() {
        let indices = gaplessQueueIndices(count: 4, currentIndex: 2, repeatQueue: true, shuffleQueue: true)
        XCTAssertEqual(indices.count, 3)
        XCTAssertEqual(Set(indices), Set([0, 1, 3]))
    }

    func testGaplessShuffleIncludesTheWholeRemainingQueueEvenWithoutRepeat() {
        let indices = gaplessQueueIndices(count: 4, currentIndex: 2, repeatQueue: false, shuffleQueue: true)
        XCTAssertEqual(indices.count, 3)
        XCTAssertEqual(Set(indices), Set([0, 1, 3]))
    }

    func testGaplessQueueHandlesInvalidCurrentIndex() {
        XCTAssertEqual(gaplessQueueIndices(count: 3, currentIndex: 3, repeatQueue: true), [])
        XCTAssertEqual(gaplessQueueIndices(count: 0, currentIndex: 0, repeatQueue: true), [])
    }
}
