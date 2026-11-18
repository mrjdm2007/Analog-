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

/** Ten-band parametric EQ and stereo crossfeed in Media3's PCM pipeline. */
@UnstableApi
class TenBandEqualizerProcessor : BaseAudioProcessor() {
    private val centersHz = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    @Volatile private var gainsDb = FloatArray(10)
    @Volatile private var bandwidths = FloatArray(10) { 1f }
    @Volatile private var enabled = true
    @Volatile private var preampLinear = 1f
    @Volatile private var crossfeedEnabled = false
    @Volatile private var crossfeedAmount = 0.12f
    private var sampleRate = 44_100f
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var previousGains = FloatArray(10) { Float.NaN }
    private var previousBandwidths = FloatArray(10) { Float.NaN }
    private var previousEnabled = true
    private var coefficients = Array(10) { Biquad() }
    private var z1 = Array(10) { FloatArray(2) }
    private var z2 = Array(10) { FloatArray(2) }

    fun setBands(gains: FloatArray) { gainsDb = FloatArray(10) { i -> gains.getOrElse(i) { 0f }.coerceIn(-12f, 12f) } }
    fun setBandwidths(values: FloatArray) { bandwidths = FloatArray(10) { i -> values.getOrElse(i) { 1f }.coerceIn(0.1f, 4f) } }
    fun setEnabled(value: Boolean) { enabled = value }
    fun setPreamp(gainDb: Float) { preampLinear = 10.0.pow(gainDb.coerceIn(-12f, 12f).toDouble() / 20.0).toFloat() }
    fun setCrossfeed(isEnabled: Boolean, amount: Float) { crossfeedEnabled = isEnabled; crossfeedAmount = amount.coerceIn(0f, 0.35f) }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        sampleRate = inputAudioFormat.sampleRate.toFloat()
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        encoding = inputAudioFormat.encoding
        z1 = Array(10) { FloatArray(channelCount) }; z2 = Array(10) { FloatArray(channelCount) }
        previousGains = FloatArray(10) { Float.NaN }; previousBandwidths = FloatArray(10) { Float.NaN }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes <= 0) return
        if (enabled != previousEnabled) {
            for (band in 0 until 10) { z1[band].fill(0f); z2[band].fill(0f) }
            previousEnabled = enabled
        }
        updateCoefficients()
        val output = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frame = FloatArray(channelCount)
        val bytesPerSample = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
        while (input.remaining() >= channelCount * bytesPerSample) {
            for (channel in 0 until channelCount) {
                frame[channel] = if (encoding == C.ENCODING_PCM_16BIT) input.short / 32768f else input.float
                if (enabled) frame[channel] = processSample(frame[channel], channel)
            }
            if (crossfeedEnabled && channelCount >= 2) {
                val amount = crossfeedAmount
                val left = frame[0]; val right = frame[1]
                frame[0] = left * (1f - amount) + right * amount
                frame[1] = right * (1f - amount) + left * amount
            }
            for (channel in 0 until channelCount) {
                val sample = frame[channel].coerceIn(-1f, 0.9999695f)
                if (encoding == C.ENCODING_PCM_16BIT) output.putShort((sample * 32768f).toInt().coerceIn(-32768, 32767).toShort()) else output.putFloat(sample)
            }
        }
        input.position(input.limit())
        output.flip()
    }

    private fun processSample(input: Float, channel: Int): Float {
        var value = input * preampLinear
        for (band in 0 until 10) {
            val c = coefficients[band]
            val result = c.b0 * value + z1[band][channel]
            z1[band][channel] = c.b1 * value - c.a1 * result + z2[band][channel]
            z2[band][channel] = c.b2 * value - c.a2 * result
            value = result
        }
        return value
    }

    private fun updateCoefficients() {
        val currentGains = gainsDb; val currentBandwidths = bandwidths
        for (index in 0 until 10) {
            val gain = currentGains[index]; val bandwidth = currentBandwidths[index]
            if (gain == previousGains[index] && bandwidth == previousBandwidths[index]) continue
            if (kotlin.math.abs(gain) < 0.001f) coefficients[index] = Biquad() else {
                val frequency = centersHz[index].coerceAtMost(sampleRate * 0.45f)
                val amplitude = 10.0.pow(gain.toDouble() / 40.0)
                val omega = 2.0 * PI * frequency / sampleRate
                val sine = sin(omega); val cosine = cos(omega)
                val safeSine = kotlin.math.abs(sine).coerceAtLeast(1e-6)
                val alpha = sine * kotlin.math.sinh(kotlin.math.ln(2.0) / 2.0 * bandwidth * omega / safeSine)
                val a0 = 1.0 + alpha / amplitude
                coefficients[index] = Biquad(
                    b0 = ((1.0 + alpha * amplitude) / a0).toFloat(),
                    b1 = ((-2.0 * cosine) / a0).toFloat(),
                    b2 = ((1.0 - alpha * amplitude) / a0).toFloat(),
                    a1 = ((-2.0 * cosine) / a0).toFloat(),
                    a2 = ((1.0 - alpha / amplitude) / a0).toFloat()
                )
            }
            z1[index].fill(0f); z2[index].fill(0f)
            previousGains[index] = gain; previousBandwidths[index] = bandwidth
        }
    }

    override fun onReset() {
        z1 = Array(10) { FloatArray(channelCount) }; z2 = Array(10) { FloatArray(channelCount) }
        previousGains = FloatArray(10) { Float.NaN }; previousBandwidths = FloatArray(10) { Float.NaN }
        previousEnabled = enabled
    }

    private data class Biquad(val b0: Float = 1f, val b1: Float = 0f, val b2: Float = 0f, val a1: Float = 0f, val a2: Float = 0f)
}
