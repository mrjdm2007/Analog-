import AVFoundation
import XCTest

final class AnalogAudioTests: XCTestCase {
    func testTenBandEQChangesRenderedAudioAndBypassRestoresIt() throws {
        let flatRMS = try renderOneKilohertzTone(eqGain: 0, bypass: false)
        let boostedRMS = try renderOneKilohertzTone(eqGain: 10, bypass: false)
        let bypassedBoostRMS = try renderOneKilohertzTone(eqGain: 10, bypass: true)

        XCTAssertGreaterThan(flatRMS, 0.01, "The offline audio engine should render the input tone.")
        XCTAssertGreaterThan(
            boostedRMS,
            flatRMS * 2.0,
            "A +10 dB 1 kHz EQ band should measurably increase the rendered 1 kHz signal."
        )
        XCTAssertEqual(
            bypassedBoostRMS,
            flatRMS,
            accuracy: 0.0005,
            "Bypassing the EQ should restore the flat response even when a band has a stored boost."
        )
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

        try engine.enableManualRenderingMode(
            .offline,
            format: format,
            maximumFrameCount: frameCount
        )
        try engine.start()

        let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount)!
        input.frameLength = frameCount
        let channels = input.floatChannelData!
        for frame in 0..<Int(frameCount) {
            let value = Float(0.05 * sin(2 * Double.pi * 1000 * Double(frame) / sampleRate))
            channels[0][frame] = value
            channels[1][frame] = value
        }

        let output = AVAudioPCMBuffer(
            pcmFormat: engine.manualRenderingFormat,
            frameCapacity: frameCount
        )!
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
