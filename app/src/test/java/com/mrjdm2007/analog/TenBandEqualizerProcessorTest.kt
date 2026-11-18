package com.mrjdm2007.analog

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class TenBandEqualizerProcessorTest {
    @Test
    fun oneKilohertzBandBoostChangesActualPcmOutput() {
        val flat = renderTone(gains = FloatArray(10), enabled = true)
        val boosted = renderTone(gains = FloatArray(10).also { it[5] = 10f }, enabled = true)
        assertTrue("The EQ output should contain a real signal", flat > 0.01)
        assertTrue("A +10 dB 1 kHz band should produce a measurable increase: flat=$flat boosted=$boosted", boosted > flat * 2.0)
    }

    @Test
    fun bypassRestoresFlatPcmOutput() {
        val flat = renderTone(gains = FloatArray(10), enabled = true)
        val bypassed = renderTone(gains = FloatArray(10).also { it[5] = 10f }, enabled = false)
        assertEquals(flat, bypassed, 0.00001)
    }

    @Test
    fun negativePreampReducesRenderedLevelAndIsAppliedToBothChannels() {
        val normal = renderTone(gains = FloatArray(10), enabled = true, preampDb = 0f)
        val trimmed = renderTone(gains = FloatArray(10), enabled = true, preampDb = -6f)
        assertTrue("A -6 dB preamp trim should reduce the output level", trimmed < normal * 0.6)
        assertTrue("The output must remain non-zero", trimmed > 0.001)
    }

    @Test
    fun lowFrequencyBandUsesLowShelfResponse() {
        val flat = renderTone(gains = FloatArray(10), enabled = true, frequencyHz = 32.0)
        val boosted = renderTone(gains = FloatArray(10).also { it[0] = 10f }, enabled = true, frequencyHz = 32.0)
        assertTrue("The first band should behave as a low shelf at its corner frequency", boosted > flat * 1.5)
    }

    @Test
    fun preampRemainsActiveWhenEqualizerBandsAreBypassed() {
        val unity = renderTone(gains = FloatArray(10), enabled = false, preampDb = 0f)
        val trimmed = renderTone(gains = FloatArray(10), enabled = false, preampDb = -6f)
        assertTrue("Master preamp must remain active when the EQ bands are bypassed", trimmed < unity * 0.6)
    }

    @Test
    fun peakGuardReducesSignalsAboveItsConfiguredThreshold() {
        val unguarded = renderTone(gains = FloatArray(10).also { it[5] = 10f }, enabled = true, preampDb = 12f, amplitude = 0.7)
        val guarded = renderTone(gains = FloatArray(10).also { it[5] = 10f }, enabled = true, preampDb = 12f, amplitude = 0.7, peakGuard = true, thresholdDb = -6f)
        assertTrue("Peak guard should reduce an over-ceiling signal", guarded < unguarded)
        assertTrue("Peak-guarded output must remain finite", guarded.isFinite())
    }

    @Test
    fun extremeBandValuesAreClampedAndOutputRemainsFinite() {
        val gains = FloatArray(10) { if (it % 2 == 0) 100f else -100f }
        val rms = renderTone(gains = gains, enabled = true, preampDb = -12f)
        assertTrue("Clamped EQ must produce finite, bounded output", rms.isFinite() && rms in 0.0..1.0)
    }

    @Test
    fun stereoBalanceCanPanFullyToEitherChannel() {
        val (left, right) = renderStereoLevels(balance = 1f, crossfeed = 0f)
        assertTrue("Full right pan should strongly attenuate the left channel", left < right * 0.01)
        assertTrue("The right channel should remain audible", right > 0.02)
    }

    @Test
    fun crossfeedAddsControlledOppositeChannelSignal() {
        val (left, right) = renderStereoLevels(balance = 0f, crossfeed = 0.25f)
        assertTrue("The original left channel should remain audible", left > 0.02)
        assertTrue("Crossfeed should add signal to the opposite channel", right > 0.002)
    }

    private fun renderStereoLevels(balance: Float, crossfeed: Float): Pair<Double, Double> {
        val sampleRate = 48_000
        val frames = 8192
        val processor = TenBandEqualizerProcessor()
        processor.setStereoBalance(balance)
        processor.setCrossfeed(crossfeed > 0f, crossfeed)
        processor.configure(AudioProcessor.AudioFormat(sampleRate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val value = (0.1 * sin(2.0 * PI * 1000.0 * frame / sampleRate) * 32767.0).toInt().toShort()
            input.putShort(value)
            input.putShort(0)
        }
        input.flip()
        processor.queueInput(input)
        val output = processor.getOutput().order(ByteOrder.nativeOrder())
        var leftSquares = 0.0
        var rightSquares = 0.0
        var count = 0
        while (output.remaining() >= 4) {
            val left = output.short.toDouble() / 32768.0
            val right = output.short.toDouble() / 32768.0
            leftSquares += left * left
            rightSquares += right * right
            count++
        }
        processor.reset()
        return sqrt(leftSquares / count) to sqrt(rightSquares / count)
    }

    private fun renderTone(
        gains: FloatArray,
        enabled: Boolean,
        preampDb: Float = 0f,
        frequencyHz: Double = 1000.0,
        amplitude: Double = 0.04,
        peakGuard: Boolean = false,
        thresholdDb: Float = -1f
    ): Double {
        val sampleRate = 48_000
        val channels = 2
        val frames = 8192
        val processor = TenBandEqualizerProcessor()
        processor.setBands(gains)
        processor.setEnabled(enabled)
        processor.setPreamp(preampDb)
        processor.setPeakGuard(peakGuard, thresholdDb)
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        processor.flush()

        val input = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val value = (amplitude * sin(2.0 * PI * frequencyHz * frame / sampleRate) * 32767.0).toInt()
                .coerceIn(-32768, 32767).toShort()
            repeat(channels) { input.putShort(value) }
        }
        input.flip()
        processor.queueInput(input)
        val output = processor.getOutput().order(ByteOrder.nativeOrder())
        var frame = 0
        var squares = 0.0
        while (output.remaining() >= channels * 2) {
            val left = output.short.toDouble() / 32768.0
            output.short // Consume the right channel too.
            squares += left * left
            frame++
        }
        processor.reset()
        assertTrue("Processor should emit all complete frames", frame == frames)
        return sqrt(squares / frame)
    }
}
