package com.mrjdm2007.analog

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** Ten-band EQ, crossfeed, stereo balance and peak protection in Media3's PCM pipeline. */
@UnstableApi
class TenBandEqualizerProcessor : BaseAudioProcessor() {
    private val centersHz = floatArrayOf(32f, 64f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    @Volatile private var gainsDb = FloatArray(10)
    @Volatile private var bandwidths = FloatArray(10) { 1f }
    @Volatile private var enabled = true
    @Volatile private var preampLinear = 1f
    @Volatile private var crossfeedEnabled = false
    @Volatile private var crossfeedAmount = 0.12f
    @Volatile private var peakGuardEnabled = false
    @Volatile private var peakGuardThresholdDb = -1f
    @Volatile private var stereoBalance = 0f
    @Volatile var outputPeak: Float = 0f
        private set
    @Volatile var outputRms: Float = 0f
        private set
    private var sampleRate = 44_100f
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var previousGains = FloatArray(10) { Float.NaN }
    private var previousBandwidths = FloatArray(10) { Float.NaN }
    private var previousEnabled = true
    private var coefficients = Array(10) { Biquad() }
    private var z1 = Array(10) { FloatArray(2) }
    private var z2 = Array(10) { FloatArray(2) }

    fun setBands(gains: FloatArray) { gainsDb = FloatArray(10) { i -> gains.getOrElse(i) { 0f }.let { if (it.isFinite()) it.coerceIn(-12f, 12f) else 0f } } }
    fun setBandwidths(values: FloatArray) { bandwidths = FloatArray(10) { i -> values.getOrElse(i) { 1f }.let { if (it.isFinite()) it.coerceIn(0.1f, 4f) else 1f } } }
    fun setEnabled(value: Boolean) { enabled = value }
    fun setPreamp(gainDb: Float) {
        val safeGainDb = if (gainDb.isFinite()) gainDb.coerceIn(-12f, 12f) else 0f
        preampLinear = 10.0.pow(safeGainDb.toDouble() / 20.0).toFloat()
    }
    fun setCrossfeed(isEnabled: Boolean, amount: Float) {
        crossfeedEnabled = isEnabled
        crossfeedAmount = if (amount.isFinite()) amount.coerceIn(0f, 0.35f) else 0.12f
    }
    fun setPeakGuard(isEnabled: Boolean, thresholdDb: Float) {
        peakGuardEnabled = isEnabled
        peakGuardThresholdDb = if (thresholdDb.isFinite()) thresholdDb.coerceIn(-12f, -0.1f) else -1f
    }
    fun setStereoBalance(value: Float) { stereoBalance = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f }

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
        previousBandwidths = FloatArray(10) { Float.NaN }
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
        val balance = stereoBalance
        val leftGain = if (balance <= 0f) 1f else sqrt(2f) * cos((balance + 1f) * PI.toFloat() / 4f)
        val rightGain = if (balance >= 0f) 1f else sqrt(2f) * sin((balance + 1f) * PI.toFloat() / 4f)
        var peak = 0f
        var sumSquares = 0.0
        var meterSamples = 0

        while (input.remaining() >= channelCount * bytesPerSample) {
            for (channel in 0 until channelCount) {
                val rawSample = if (encoding == C.ENCODING_PCM_16BIT) input.short / 32768f else input.float
                // A single invalid float must not poison the recursive biquad state.
                val sample = if (rawSample.isFinite()) rawSample else 0f
                frame[channel] = processSample(sample, channel)
            }
            if (crossfeedEnabled && channelCount >= 2) {
                val amount = crossfeedAmount
                val left = frame[0]
                val right = frame[1]
                frame[0] = left * (1f - amount) + right * amount
                frame[1] = right * (1f - amount) + left * amount
            }
            if (channelCount >= 2) {
                frame[0] *= leftGain
                frame[1] *= rightGain
            }
            for (channel in 0 until channelCount) {
                val protected = applyPeakGuard(frame[channel])
                val sample = if (protected.isFinite()) protected.coerceIn(-1f, 0.9999695f) else 0f
                peak = max(peak, abs(sample))
                sumSquares += sample.toDouble() * sample.toDouble()
                meterSamples++
                if (encoding == C.ENCODING_PCM_16BIT) {
                    output.putShort((sample * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                } else {
                    output.putFloat(sample)
                }
            }
        }
        outputPeak = peak
        outputRms = if (meterSamples > 0) sqrt(sumSquares / meterSamples).toFloat() else 0f
        input.position(input.limit())
        output.flip()
    }

    private fun processSample(input: Float, channel: Int): Float {
        // Preamp is a master gain and remains active when the EQ bands are bypassed.
        var value = input * preampLinear
        if (!enabled) return value
        for (band in 0 until 10) {
            val c = coefficients[band]
            val result = c.b0 * value + z1[band][channel]
            z1[band][channel] = c.b1 * value - c.a1 * result + z2[band][channel]
            z2[band][channel] = c.b2 * value - c.a2 * result
            value = result
        }
        return value
    }

    private fun applyPeakGuard(sample: Float): Float {
        if (!peakGuardEnabled || !sample.isFinite()) return sample
        val magnitude = abs(sample)
        val threshold = peakGuardThresholdDb
        val ceiling = 10.0.pow(threshold.toDouble() / 20.0).toFloat()
        if (magnitude <= ceiling || magnitude == 0f) return sample
        // 8:1 compression above the user-selected ceiling, with a smooth bounded output.
        val inputDb = 20f * log10(magnitude.coerceAtLeast(1e-8f))
        val outputDb = threshold + (inputDb - threshold) / 8f
        val outputMagnitude = 10.0.pow(outputDb.toDouble() / 20.0).toFloat()
        return if (sample < 0f) -outputMagnitude else outputMagnitude
    }

    private fun updateCoefficients() {
        val currentGains = gainsDb
        val currentBandwidths = bandwidths
        for (index in 0 until 10) {
            val gain = currentGains[index]
            val bandwidth = currentBandwidths[index]
            if (gain == previousGains[index] && bandwidth == previousBandwidths[index]) continue
            coefficients[index] = when {
                abs(gain) < 0.001f -> Biquad()
                index == 0 -> shelf(gain, centersHz[index], low = true)
                index == 9 -> shelf(gain, centersHz[index], low = false)
                else -> peaking(gain, centersHz[index], bandwidth)
            }
            z1[index].fill(0f)
            z2[index].fill(0f)
            previousGains[index] = gain
            previousBandwidths[index] = bandwidth
        }
    }

    private fun peaking(gainDb: Float, centerHz: Float, bandwidth: Float): Biquad {
        val frequency = centerHz.coerceAtMost(sampleRate * 0.45f)
        val amplitude = 10.0.pow(gainDb.toDouble() / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val sine = sin(omega)
        val cosine = cos(omega)
        val safeSine = abs(sine).coerceAtLeast(1e-6)
        val alpha = sine * sinh(ln(2.0) / 2.0 * bandwidth * omega / safeSine)
        val a0 = 1.0 + alpha / amplitude
        return Biquad(
            b0 = ((1.0 + alpha * amplitude) / a0).toFloat(),
            b1 = ((-2.0 * cosine) / a0).toFloat(),
            b2 = ((1.0 - alpha * amplitude) / a0).toFloat(),
            a1 = ((-2.0 * cosine) / a0).toFloat(),
            a2 = ((1.0 - alpha / amplitude) / a0).toFloat()
        )
    }

    /** RBJ cookbook low/high shelf; slope is fixed at 1, matching the Mac shelf-band intent. */
    private fun shelf(gainDb: Float, centerHz: Float, low: Boolean): Biquad {
        val frequency = centerHz.coerceAtMost(sampleRate * 0.45f)
        val a = 10.0.pow(gainDb.toDouble() / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / 2.0 * sqrt(2.0)
        val beta = 2.0 * sqrt(a) * alpha
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cosine + beta)
            b1 = 2 * a * ((a - 1) - (a + 1) * cosine)
            b2 = a * ((a + 1) - (a - 1) * cosine - beta)
            a0 = (a + 1) + (a - 1) * cosine + beta
            a1 = -2 * ((a - 1) + (a + 1) * cosine)
            a2 = (a + 1) + (a - 1) * cosine - beta
        } else {
            b0 = a * ((a + 1) + (a - 1) * cosine + beta)
            b1 = -2 * a * ((a - 1) + (a + 1) * cosine)
            b2 = a * ((a + 1) + (a - 1) * cosine - beta)
            a0 = (a + 1) - (a - 1) * cosine + beta
            a1 = 2 * ((a - 1) - (a + 1) * cosine)
            a2 = (a + 1) - (a - 1) * cosine - beta
        }
        return Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
            (a1 / a0).toFloat(), (a2 / a0).toFloat())
    }

    override fun onReset() {
        z1 = Array(10) { FloatArray(channelCount) }
        z2 = Array(10) { FloatArray(channelCount) }
        previousGains = FloatArray(10) { Float.NaN }
        previousBandwidths = FloatArray(10) { Float.NaN }
        previousEnabled = enabled
        outputPeak = 0f
        outputRms = 0f
    }

    private data class Biquad(
        val b0: Float = 1f, val b1: Float = 0f, val b2: Float = 0f,
        val a1: Float = 0f, val a2: Float = 0f
    )
}
