package com.mrjdm2007.analog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
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
    val error: String? = null
)

/** Real AudioRecord capture with live waveform/FFT and standards-compliant PCM WAV output. */
object RecordingEngine {
    private val mutableState = MutableStateFlow(RecordingSnapshot())
    val state: StateFlow<RecordingSnapshot> = mutableState
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var recorder: AudioRecord? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var markers = mutableListOf<Long>()

    @Synchronized
    fun start(context: Context, rate: Int, channels: Int, highPass: Boolean, limiter: Boolean, gain: Float) {
        if (running) return
        markers = mutableListOf()
        pausedTotal = 0L
        pausedAt = 0L
        mutableState.value = RecordingSnapshot(status = "STARTING")
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Analog/Recordings")
        if (!directory.exists() && !directory.mkdirs()) {
            mutableState.value = RecordingSnapshot(status = "ERROR", error = "Could not create recordings folder.")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(directory, "Analog_Take_" + stamp + ".wav")
        running = true
        Thread({ capture(output, rate, channels.coerceIn(1, 2), highPass, limiter, gain.coerceIn(0.25f, 2f)) }, "AnalogAudioCapture")
            .apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun capture(output: File, rate: Int, channels: Int, highPass: Boolean, limiter: Boolean, gain: Float) {
        var stream: FileOutputStream? = null
        var audio: AudioRecord? = null
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
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Android could not initialize this microphone format.")
            recorder = audio
            stream = FileOutputStream(output)
            stream.write(ByteArray(44))
            audio.startRecording()
            startedAt = SystemClock.elapsedRealtime()
            mutableState.value = RecordingSnapshot(status = "RECORDING", file = output)

            val samples = ShortArray(1024 * channels)
            val pcm = ByteArray(samples.size * 2)
            val fft = FloatArray(1024)
            var fftCount = 0
            var previousIn = FloatArray(channels)
            var previousOut = FloatArray(channels)
            var totalBytes = 0L
            var lastUi = 0L
            while (running) {
                if (paused) { Thread.sleep(30); continue }
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) throw IllegalStateException("Microphone read failed: " + count)
                if (count == 0 || paused) continue
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
                    if (limiter) value = value.coerceIn(-0.98f, 0.98f)
                    value = value.coerceIn(-1f, 0.99997f)
                    peak = max(peak, kotlin.math.abs(value))
                    sum += value.toDouble() * value
                    val shortValue = (value * 32767f).toInt().coerceIn(-32768, 32767)
                    pcm[i * 2] = (shortValue and 255).toByte()
                    pcm[i * 2 + 1] = ((shortValue shr 8) and 255).toByte()
                    if (channel == 0 && fftCount < fft.size) fft[fftCount++] = value
                }
                stream.write(pcm, 0, count * 2)
                totalBytes += count * 2L
                if (fftCount == fft.size) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi >= 33) {
                        val wave = List(160) { index -> kotlin.math.abs(fft[(index * fft.size / 160).coerceIn(0, fft.lastIndex)]) }
                        val rms = sqrt(sum / count.coerceAtLeast(1)).toFloat()
                        mutableState.value = RecordingSnapshot(
                            status = if (paused) "PAUSED" else "RECORDING",
                            file = output,
                            elapsedMs = elapsed(),
                            bytesWritten = totalBytes,
                            peakDbfs = toDb(peak),
                            rmsDbfs = toDb(rms),
                            waveform = wave,
                            spectrum = spectrumOf(fft)
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
            if (markers.isNotEmpty()) {
                File(output.parentFile, output.nameWithoutExtension + ".markers.txt")
                    .writeText(markers.mapIndexed { index, time -> "Marker " + (index + 1) + ": " + (time / 1000.0) + "s" }.joinToString("\n"))
            }
            mutableState.value = mutableState.value.copy(status = "SAVED", file = output, elapsedMs = elapsed(), bytesWritten = totalBytes)
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(status = "ERROR", error = error.message ?: "Recording failed.")
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
        val real = source.copyOf()
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
            (sqrt(energy / (high - low + 1)) / 18.0).toFloat().coerceIn(0.01f, 1f)
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
