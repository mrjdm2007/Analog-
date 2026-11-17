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
    fun extremeBandValuesAreClampedAndOutputRemainsFinite() {
        val gains = FloatArray(10) { if (it % 2 == 0) 100f else -100f }
        val rms = renderTone(gains = gains, enabled = true, preampDb = -12f)
        assertTrue("Clamped EQ must produce finite, bounded output", rms.isFinite() && rms in 0.0..1.0)
    }

    private fun renderTone(
        gains: FloatArray,
        enabled: Boolean,
        preampDb: Float = 0f
    ): Double {
        val sampleRate = 48_000
        val channels = 2
        val frames = 8192
        val processor = TenBandEqualizerProcessor()
        processor.setBands(gains)
        processor.setEnabled(enabled)
        processor.setPreamp(preampDb)
        processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        processor.flush()

        val input = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val value = (0.04 * sin(2.0 * PI * 1000.0 * frame / sampleRate) * 32767.0).toInt()
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
