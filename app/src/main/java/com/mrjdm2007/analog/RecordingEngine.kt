package com.mrjdm2007.analog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.log10
import kotlin.math.pow

data class RecordingSnapshot(
    val status: String = "IDLE",
    val file: File? = null,
    val elapsedMs: Long = 0L,
    val bytesWritten: Long = 0L,
    val peakDbfs: Float = -96f,
    val rmsDbfs: Float = -96f,
    val waveform: List<Float> = List(160) { 0f },
    val spectrum: List<Float> = List(48) { 0f },
    val spectrogram: List<List<Float>> = emptyList(),
    val error: String? = null
)

/** Real AudioRecord capture with live waveform/FFT and standards-compliant PCM WAV output. */
object RecordingEngine {
    private val mutableState = MutableStateFlow(RecordingSnapshot())
    val state: StateFlow<RecordingSnapshot> = mutableState
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pauseGeneration = 0L
    @Volatile private var recorder: AudioRecord? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var markers = mutableListOf<Long>()

    @Synchronized
    fun start(context: Context, rate: Int, channels: Int, highPass: Boolean, limiter: Boolean, gain: Float, inputDeviceId: Int = -1, lowGainDb: Float = 0f, midGainDb: Float = 0f, highGainDb: Float = 0f, compressorEnabled: Boolean = false, compressorThresholdDb: Float = -18f) {
        if (running) return
        markers = mutableListOf()
        pausedTotal = 0L
        pausedAt = 0L
        startedAt = 0L
        pauseGeneration = 0L
        mutableState.value = RecordingSnapshot(status = "STARTING")
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Analog/Recordings")
        if (!directory.exists() && !directory.mkdirs()) {
            mutableState.value = RecordingSnapshot(status = "ERROR", error = "Could not create recordings folder.")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        var output = File(directory, "Analog_Take_" + stamp + ".wav")
        var suffix = 1
        var created = runCatching { output.createNewFile() }.getOrDefault(false)
        while (!created && suffix <= 100) {
            output = File(directory, "Analog_Take_" + stamp + "_" + suffix++ + ".wav")
            created = runCatching { output.createNewFile() }.getOrDefault(false)
        }
        if (!created) {
            mutableState.value = RecordingSnapshot(status = "ERROR", error = "Could not create a unique recording file.")
            return
        }
        running = true
        Thread({ capture(output, rate, channels.coerceIn(1, 2), highPass, limiter, gain.coerceIn(0.25f, 2f), context, inputDeviceId, lowGainDb, midGainDb, highGainDb, compressorEnabled, compressorThresholdDb) }, "AnalogAudioCapture")
            .apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun capture(output: File, rate: Int, channels: Int, highPass: Boolean, limiter: Boolean, gain: Float, context: Context, inputDeviceId: Int, lowGainDb: Float, midGainDb: Float, highGainDb: Float, compressorEnabled: Boolean, compressorThresholdDb: Float) {
        var stream: FileOutputStream? = null
        var audio: AudioRecord? = null
        var totalBytes = 0L
        try {
            val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IllegalStateException("Unsupported input format: " + rate + " Hz.")
            val bufferSize = max(minimum * 2, rate * channels * 2 / 5)
            val unprocessed = runCatching { AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, mask, AudioFormat.ENCODING_PCM_16BIT, bufferSize) }.getOrNull()
            audio = if (unprocessed?.state == AudioRecord.STATE_INITIALIZED) {
                unprocessed
            } else {
                runCatching { unprocessed?.release() }
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, mask, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            }
            val activeAudio = audio
            if (activeAudio == null || activeAudio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Android could not initialize this microphone format.")
            if (inputDeviceId >= 0) {
                val manager = context.getSystemService(AudioManager::class.java)
                val preferred = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == inputDeviceId }
                if (preferred != null) activeAudio.setPreferredDevice(preferred)
            }
            recorder = activeAudio
            stream = FileOutputStream(output)
            stream.write(ByteArray(44))
            activeAudio.startRecording()
            startedAt = SystemClock.elapsedRealtime()
            mutableState.value = RecordingSnapshot(status = "RECORDING", file = output)

            val samples = ShortArray(1024 * channels)
            val pcm = ByteArray(samples.size * 2)
            val fft = FloatArray(1024)
            var fftCount = 0
            var previousIn = FloatArray(channels)
            var previousOut = FloatArray(channels)
            val lowState = FloatArray(channels)
            val highState = FloatArray(channels)
            val compressorEnvelope = FloatArray(channels)
            val lowLinear = 10.0.pow(lowGainDb.coerceIn(-12f, 12f) / 20.0).toFloat()
            val midLinear = 10.0.pow(midGainDb.coerceIn(-12f, 12f) / 20.0).toFloat()
            val highLinear = 10.0.pow(highGainDb.coerceIn(-12f, 12f) / 20.0).toFloat()
            val compressorThreshold = 10.0.pow(compressorThresholdDb.coerceIn(-36f, -3f) / 20.0).toFloat()
            val compressorRatio = 3f
            val compressorAttack = 1f - kotlin.math.exp(-1f / (0.010f * rate))
            val compressorRelease = 1f - kotlin.math.exp(-1f / (0.120f * rate))
            var leftSample = 0f
            val spectrogramHistory = ArrayDeque<List<Float>>()
            var lastUi = 0L
            while (running) {
                if (paused) {
                    // Drain input while paused so buffered audio cannot be written after resume.
                    val discarded = activeAudio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (discarded < 0) throw IllegalStateException("Microphone read failed: " + discarded)
                    continue
                }
                val readGeneration = pauseGeneration
                val count = activeAudio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) throw IllegalStateException("Microphone read failed: " + count)
                if (count == 0 || paused || readGeneration != pauseGeneration) continue
                var peak = 0f
                var sum = 0.0
                for (i in 0 until count) {
                    val channel = i % channels
                    var value = samples[i].toFloat() / 32768f * gain
                    if (highPass) {
                        val rc = 1.0 / (2.0 * PI * 80.0)
                        val dt = 1.0 / rate
                        val alpha = (rc / (rc + dt)).toFloat()
                        val filtered = alpha * (previousOut[channel] + value - previousIn[channel])
                        previousIn[channel] = value
                        previousOut[channel] = filtered
                        value = filtered
                    }
                    val lowAlpha = (1.0 - kotlin.math.exp(-2.0 * PI * 250.0 / rate)).toFloat()
                    val highAlpha = (1.0 - kotlin.math.exp(-2.0 * PI * 4000.0 / rate)).toFloat()
                    lowState[channel] += lowAlpha * (value - lowState[channel])
                    highState[channel] += highAlpha * (value - highState[channel])
                    val lowBand = lowState[channel]
                    val highBand = value - highState[channel]
                    val midBand = value - lowBand - highBand
                    value = lowBand * lowLinear + midBand * midLinear + highBand * highLinear
                    if (compressorEnabled) {
                        val coefficient = if (kotlin.math.abs(value) > compressorEnvelope[channel]) compressorAttack else compressorRelease
                        compressorEnvelope[channel] += coefficient * (kotlin.math.abs(value) - compressorEnvelope[channel])
                        if (compressorEnvelope[channel] > compressorThreshold) {
                            val compressed = compressorThreshold + (compressorEnvelope[channel] - compressorThreshold) / compressorRatio
                            value *= (compressed / compressorEnvelope[channel].coerceAtLeast(1.0e-6f))
                        }
                    }
                    if (limiter) value = value.coerceIn(-0.98f, 0.98f)
                    value = value.coerceIn(-1f, 0.99997f)
                    peak = max(peak, kotlin.math.abs(value))
                    sum += value.toDouble() * value
                    val shortValue = (value * 32767f).toInt().coerceIn(-32768, 32767)
                    pcm[i * 2] = (shortValue and 255).toByte()
                    pcm[i * 2 + 1] = ((shortValue shr 8) and 255).toByte()
                    if (channels == 1) {
                        if (fftCount < fft.size) fft[fftCount++] = value
                    } else if (channel == 0) {
                        leftSample = value
                    } else if (fftCount < fft.size) {
                        fft[fftCount++] = (leftSample + value) * 0.5f
                    }
                }
                stream.write(pcm, 0, count * 2)
                totalBytes += count * 2L
                if (fftCount == fft.size) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi >= 33) {
                        val wave = List(160) { index ->
                            val start = index * fft.size / 160
                            val end = ((index + 1) * fft.size / 160).coerceAtMost(fft.size)
                            (start until end).maxOfOrNull { kotlin.math.abs(fft[it]) } ?: 0f
                        }
                        val rms = sqrt(sum / count.coerceAtLeast(1)).toFloat()
                        val currentSpectrum = spectrumOf(fft)
                        spectrogramHistory.addLast(currentSpectrum)
                        if (spectrogramHistory.size > 64) spectrogramHistory.removeFirst()
                        mutableState.value = RecordingSnapshot(
                            status = if (paused) "PAUSED" else "RECORDING",
                            file = output,
                            elapsedMs = elapsed(),
                            bytesWritten = totalBytes,
                            peakDbfs = toDb(peak),
                            rmsDbfs = toDb(rms),
                            waveform = wave,
                            spectrum = currentSpectrum,
                            spectrogram = spectrogramHistory.toList()
                        )
                        lastUi = now
                    }
                    fftCount = 0
                }
            }
            stream.flush()
            stream.fd.sync()
            stream.close()
            stream = null
            writeHeader(output, totalBytes, rate, channels)
            val markerSnapshot = synchronized(this) { markers.toList() }
            if (markerSnapshot.isNotEmpty()) {
                File(output.parentFile, output.nameWithoutExtension + ".markers.txt")
                    .writeText(markerSnapshot.mapIndexed { index, time -> "Marker " + (index + 1) + ": " + (time / 1000.0) + "s" }.joinToString("\n"))
            }
            mutableState.value = mutableState.value.copy(status = "SAVED", file = output, elapsedMs = elapsed(), bytesWritten = totalBytes)
        } catch (error: Throwable) {
            runCatching {
                stream?.flush()
                stream?.fd?.sync()
                stream?.close()
                stream = null
                if (totalBytes > 0L && output.exists()) writeHeader(output, totalBytes, rate, channels)
                else output.delete()
            }
            mutableState.value = mutableState.value.copy(status = "ERROR", file = output.takeIf { it.exists() && totalBytes > 0L }, bytesWritten = totalBytes, error = error.message ?: "Recording failed.")
        } finally {
            running = false
            paused = false
            runCatching { stream?.close() }
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            recorder = null
        }
    }

    @Synchronized fun pause() {
        if (!running || paused) return
        paused = true
        pauseGeneration++
        pausedAt = SystemClock.elapsedRealtime()
        mutableState.value = mutableState.value.copy(status = "PAUSED")
    }

    @Synchronized fun resume() {
        if (!running || !paused) return
        pausedTotal += SystemClock.elapsedRealtime() - pausedAt
        paused = false
        mutableState.value = mutableState.value.copy(status = "RECORDING")
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
    }

    @Synchronized fun addMarker() { if (running) markers.add(elapsed()) }

    private fun elapsed(): Long {
        if (startedAt == 0L) return 0L
        val now = if (paused) pausedAt else SystemClock.elapsedRealtime()
        return (now - startedAt - pausedTotal).coerceAtLeast(0L)
    }

    private fun toDb(value: Float): Float = if (value <= 0.000015f) -96f else (20f * log10(value)).coerceAtLeast(-96f)

    private fun spectrumOf(source: FloatArray): List<Float> {
        val n = source.size
        val real = FloatArray(n) { index ->
            val window = 0.5f - 0.5f * cos((2.0 * PI * index / (n - 1)).toFloat())
            source[index] * window
        }
        val imag = FloatArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val temp = real[i]; real[i] = real[j]; real[j] = temp }
        }
        var length = 2
        while (length <= n) {
            val angle = (-2.0 * PI / length).toFloat()
            val wrStep = cos(angle)
            val wiStep = kotlin.math.sin(angle)
            var base = 0
            while (base < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until length / 2) {
                    val even = base + k
                    val odd = even + length / 2
                    val tr = wr * real[odd] - wi * imag[odd]
                    val ti = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                    val next = wr * wrStep - wi * wiStep
                    wi = wr * wiStep + wi * wrStep
                    wr = next
                }
                base += length
            }
            length = length shl 1
        }
        return List(48) { band ->
            val low = (2.0 * (n / 2.0).pow(band / 48.0)).toInt().coerceIn(1, n / 2 - 1)
            val high = (2.0 * (n / 2.0).pow((band + 1) / 48.0)).toInt().coerceIn(low, n / 2 - 1)
            var energy = 0.0
            for (bin in low..high) energy += real[bin] * real[bin] + imag[bin] * imag[bin]
            val amplitude = sqrt(energy / (high - low + 1)) * (4.0 / n)
            val dbfs = if (amplitude <= 1.0e-6) -120.0 else 20.0 * kotlin.math.log10(amplitude)
            ((dbfs + 72.0) / 72.0).toFloat().coerceIn(0.01f, 1f)
        }
    }

    private fun writeHeader(file: File, dataSize: Long, rate: Int, channels: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF"); writeInt(raf, (36L + dataSize).toInt())
            raf.writeBytes("WAVEfmt "); writeInt(raf, 16); writeShort(raf, 1)
            writeShort(raf, channels); writeInt(raf, rate); writeInt(raf, rate * channels * 2)
            writeShort(raf, channels * 2); writeShort(raf, 16)
            raf.writeBytes("data"); writeInt(raf, dataSize.toInt())
        }
    }
    private fun writeInt(raf: RandomAccessFile, value: Int) {
        raf.write(value and 255); raf.write((value shr 8) and 255)
        raf.write((value shr 16) and 255); raf.write((value shr 24) and 255)
    }
    private fun writeShort(raf: RandomAccessFile, value: Int) {
        raf.write(value and 255); raf.write((value shr 8) and 255)
    }
}
