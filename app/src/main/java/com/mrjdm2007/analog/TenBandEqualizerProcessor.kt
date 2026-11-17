package com.mrjdm2007.analog

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ten-band parametric equalizer that runs inside Media3's PCM audio pipeline.
 * It is independent of Android's optional system AudioEffect implementations.
 */
@UnstableApi
class TenBandEqualizerProcessor : BaseAudioProcessor() {
    private val centersHz = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

    @Volatile private var gainsDb = FloatArray(10)
    @Volatile private var enabled = true
    @Volatile private var preampLinear = 1f

    private var sampleRate = 44_100f
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var previousGains = FloatArray(10) { Float.NaN }
    private var previousEnabled = true
    private var coefficients = Array(10) { Biquad() }
    private var z1 = Array(10) { FloatArray(2) }
    private var z2 = Array(10) { FloatArray(2) }

    fun setBands(gains: FloatArray) {
        gainsDb = FloatArray(10) { index -> gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f) }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setPreamp(gainDb: Float) {
        val boundedGain = gainDb.coerceIn(-12f, 6f)
        preampLinear = 10.0.pow(boundedGain.toDouble() / 20.0).toFloat()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate.toFloat()
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        z1 = Array(10) { FloatArray(channelCount) }
        z2 = Array(10) { FloatArray(channelCount) }
        previousGains = FloatArray(10) { Float.NaN }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes <= 0) return
        if (enabled != previousEnabled) {
            for (band in 0 until 10) {
                z1[band].fill(0f)
                z2[band].fill(0f)
            }
            previousEnabled = enabled
        }
        updateCoefficients()
        val output = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        var sampleIndex = 0

        if (encoding == C.ENCODING_PCM_16BIT) {
            while (input.remaining() >= 2) {
                val raw = input.short
                var sample = raw / 32768f
                if (enabled) sample = processSample(sample, sampleIndex % channelCount)
                val clipped = (sample.coerceIn(-1f, 0.9999695f) * 32768f).toInt().coerceIn(-32768, 32767)
                output.putShort(clipped.toShort())
                sampleIndex++
            }
        } else {
            while (input.remaining() >= 4) {
                var sample = input.float
                if (enabled) sample = processSample(sample, sampleIndex % channelCount)
                output.putFloat(sample.coerceIn(-1f, 1f))
                sampleIndex++
            }
        }

        input.position(input.limit())
        output.flip()
    }

    private fun processSample(input: Float, channel: Int): Float {
        var value = input * preampLinear
        for (band in 0 until 10) {
            val c = coefficients[band]
            val output = c.b0 * value + z1[band][channel]
            z1[band][channel] = c.b1 * value - c.a1 * output + z2[band][channel]
            z2[band][channel] = c.b2 * value - c.a2 * output
            value = output
        }
        return value
    }

    private fun updateCoefficients() {
        val current = gainsDb
        if (current.contentEquals(previousGains)) return
        for (index in 0 until 10) {
            val gain = current[index]
            if (gain == previousGains[index]) continue
            if (kotlin.math.abs(gain) < 0.001f) {
                coefficients[index] = Biquad()
            } else {
                val frequency = centersHz[index].coerceAtMost(sampleRate * 0.45f)
                val a = 10.0.pow(gain.toDouble() / 40.0)
                val omega = 2.0 * PI * frequency / sampleRate
                val alpha = sin(omega) / (2.0 * 1.05)
                val cosine = cos(omega)
                val a0 = 1.0 + alpha / a
                coefficients[index] = Biquad(
                    b0 = ((1.0 + alpha * a) / a0).toFloat(),
                    b1 = ((-2.0 * cosine) / a0).toFloat(),
                    b2 = ((1.0 - alpha * a) / a0).toFloat(),
                    a1 = ((-2.0 * cosine) / a0).toFloat(),
                    a2 = ((1.0 - alpha / a) / a0).toFloat()
                )
            }
            // Reset only the changed band's delay state; untouched bands keep their tails intact.
            z1[index].fill(0f)
            z2[index].fill(0f)
        }
        previousGains = current.copyOf()
    }

    override fun onReset() {
        z1 = Array(10) { FloatArray(channelCount) }
        z2 = Array(10) { FloatArray(channelCount) }
        previousGains = FloatArray(10) { Float.NaN }
        previousEnabled = enabled
    }

    private data class Biquad(
        val b0: Float = 1f,
        val b1: Float = 0f,
        val b2: Float = 0f,
        val a1: Float = 0f,
        val a2: Float = 0f
    )
}
