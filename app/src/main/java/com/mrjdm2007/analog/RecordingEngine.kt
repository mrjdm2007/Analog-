package com.mrjdm2007.analog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Environment
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    val peakHoldDbfs: Float = -96f,
    val clippedSamples: Long = 0L,
    val rmsDbfs: Float = -96f,
    val channelPeakDbfs: List<Float> = listOf(-96f),
    val channelRmsDbfs: List<Float> = listOf(-96f),
    val waveform: List<Float> = List(160) { 0f },
    val spectrum: List<Float> = List(48) { 0f },
    val spectrogram: List<List<Float>> = emptyList(),
    val markersMs: List<Long> = emptyList(),
    val activeInput: String? = null,
    val captureMode: String = "PROCESSED",
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
    @Volatile private var startedAt = 0L
    @Volatile private var recordingStartedWallClock = 0L
    @Volatile private var pausedAt = 0L
    @Volatile private var pausedTotal = 0L
    private var markers = mutableListOf<Long>()
    @Volatile private var peakHoldDbfs = -96f
    @Volatile private var clippedSamples = 0L

    @Synchronized
    fun start(context: Context, rate: Int, channels: Int, highPass: Boolean, limiter: Boolean, gain: Float, inputDeviceId: Int = -1, lowGainDb: Float = 0f, midGainDb: Float = 0f, highGainDb: Float = 0f, compressorEnabled: Boolean = false, compressorThresholdDb: Float = -18f, bitDepth: Int = 16, rawMic: Boolean = false, highPassHz: Float = 80f, limiterCeilingDb: Float = -1f, compressorRatio: Float = 3f, compressorAttackMs: Float = 10f, compressorReleaseMs: Float = 120f) {
        if (running) return
        markers = mutableListOf()
        peakHoldDbfs = -96f
        clippedSamples = 0L
        pausedTotal = 0L
        pausedAt = 0L
        startedAt = 0L
        recordingStartedWallClock = 0L
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
        Thread({ capture(output, rate, channels.coerceIn(1, 2), highPass, limiter, gain.coerceIn(0.25f, 2f), context, inputDeviceId, lowGainDb, midGainDb, highGainDb, compressorEnabled, compressorThresholdDb, bitDepth, rawMic, highPassHz, limiterCeilingDb, compressorRatio, compressorAttackMs, compressorReleaseMs) }, "AnalogAudioCapture")
            .apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun capture(output: File, rate: Int, channels: Int, highPass: Boolean, limiter: Boolean, gain: Float, context: Context, inputDeviceId: Int, lowGainDb: Float, midGainDb: Float, highGainDb: Float, compressorEnabled: Boolean, compressorThresholdDb: Float, bitDepth: Int, rawMic: Boolean, highPassHz: Float, limiterCeilingDb: Float, compressorRatioSetting: Float, compressorAttackMs: Float, compressorReleaseMs: Float) {
        var stream: FileOutputStream? = null
        var audio: AudioRecord? = null
        var totalBytes = 0L
        try {
            if (bitDepth !in listOf(16, 24, 32)) throw IllegalStateException("Choose 16-bit PCM, 24-bit packed PCM or 32-bit float WAV.")
            if (bitDepth == 24 && Build.VERSION.SDK_INT < 31) throw IllegalStateException("24-bit packed PCM requires Android 12 or newer.")
            val bytesPerSample = when (bitDepth) { 24 -> 3; 32 -> 4; else -> 2 }
            val encoding = when (bitDepth) { 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED; 32 -> AudioFormat.ENCODING_PCM_FLOAT; else -> AudioFormat.ENCODING_PCM_16BIT }
            val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minimum = AudioRecord.getMinBufferSize(rate, mask, encoding)
            if (minimum <= 0) throw IllegalStateException("Unsupported " + bitDepth + "-bit input format at " + rate + " Hz.")
            val bufferSize = max(minimum * 2, rate * channels * bytesPerSample / 5)
            val audioFormat = AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(encoding).build()
            val audioManager = context.getSystemService(AudioManager::class.java)
            val deviceReportsUnprocessedSupport = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                ?.equals("true", ignoreCase = true) == true
            val unprocessed = if (deviceReportsUnprocessedSupport) runCatching {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(audioFormat).setBufferSizeInBytes(bufferSize).build()
            }.getOrNull() else null
            val unprocessedSelected = deviceReportsUnprocessedSupport && unprocessed?.state == AudioRecord.STATE_INITIALIZED
            audio = if (unprocessedSelected) {
                unprocessed
            } else {
                runCatching { unprocessed?.release() }
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat).setBufferSizeInBytes(bufferSize).build()
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
            stream.write(ByteArray(WaveHeaderWriter.headerSizeBytes(bitDepth)))
            activeAudio.startRecording()
            startedAt = SystemClock.elapsedRealtime()
            recordingStartedWallClock = System.currentTimeMillis()
            val captureModeLabel = if (rawMic) {
                if (unprocessedSelected) "RAW REQUEST • UNPROCESSED (DEVICE REPORTS SUPPORT)" else "RAW REQUEST • MIC FALLBACK (DSP MAY APPLY)"
            } else {
                if (unprocessedSelected) "STUDIO DSP • UNPROCESSED SOURCE" else "STUDIO DSP • MIC SOURCE"
            }
            mutableState.value = RecordingSnapshot(status = "RECORDING", file = output, activeInput = activeAudio.routedDevice?.productName?.toString(), captureMode = captureModeLabel)

            val inputBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
            val pcm = ByteArray(bufferSize)
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
            val limiterCeiling = RecordingPcm.linearFromDb(limiterCeilingDb.coerceIn(-12f, -0.1f))
            val compressorRatio = compressorRatioSetting.coerceIn(1f, 10f)
            val attackSeconds = compressorAttackMs.coerceIn(1f, 100f) / 1000f
            val releaseSeconds = compressorReleaseMs.coerceIn(20f, 500f) / 1000f
            val compressorAttack = 1f - kotlin.math.exp(-1f / (attackSeconds * rate))
            val compressorRelease = 1f - kotlin.math.exp(-1f / (releaseSeconds * rate))
            var leftSample = 0f
            val spectrogramHistory = ArrayDeque<List<Float>>()
            val highPassAlpha = RecordingPcm.highPassAlpha(highPassHz, rate)
            val lowAlpha = (1.0 - kotlin.math.exp(-2.0 * PI * 250.0 / rate)).toFloat()
            val highAlpha = (1.0 - kotlin.math.exp(-2.0 * PI * 4000.0 / rate)).toFloat()
            var lastUi = 0L
            while (running) {
                if (paused) {
                    // Drain input while paused so buffered audio cannot be written after resume.
                    inputBuffer.clear()
                    val discarded = activeAudio.read(inputBuffer, inputBuffer.capacity(), AudioRecord.READ_BLOCKING)
                    if (discarded < 0) throw IllegalStateException("Microphone read failed: " + discarded)
                    continue
                }
                val readGeneration = pauseGeneration
                inputBuffer.clear()
                val readBytes = activeAudio.read(inputBuffer, inputBuffer.capacity(), AudioRecord.READ_BLOCKING)
                if (readBytes < 0) throw IllegalStateException("Microphone read failed: " + readBytes)
                if (readBytes == 0 || paused || readGeneration != pauseGeneration) continue
                // AudioRecord may return a partial final frame. Drop incomplete channel frames
                // rather than writing a malformed interleaved PCM stream.
                val count = RecordingPcm.completeSampleCount(readBytes, bytesPerSample, channels)
                if (count == 0) continue
                inputBuffer.position(0)
                var peak = 0f
                var sum = 0.0
                val channelPeaks = FloatArray(channels)
                val channelSums = DoubleArray(channels)
                for (i in 0 until count) {
                    val channel = i % channels
                    val sample = if (bitDepth == 32) {
                        inputBuffer.getFloat(i * 4)
                    } else if (bitDepth == 24) {
                        val offset = i * 3
                        var raw = (inputBuffer.get(offset).toInt() and 0xff) or
                            ((inputBuffer.get(offset + 1).toInt() and 0xff) shl 8) or
                            ((inputBuffer.get(offset + 2).toInt() and 0xff) shl 16)
                        if ((raw and 0x800000) != 0) raw = raw or -0x1000000
                        raw.toFloat() / 8388608f
                    } else {
                        inputBuffer.getShort(i * 2).toFloat() / 32768f
                    }
                    // Meter the actual microphone input before gain, EQ, compression or limiting.
                    // This keeps clip warnings meaningful even when the limiter is enabled.
                    val inputSample = RecordingPcm.finiteOrZero(sample)
                    val inputAbsolute = kotlin.math.abs(inputSample)
                    peak = max(peak, inputAbsolute)
                    channelPeaks[channel] = max(channelPeaks[channel], inputAbsolute)
                    if (inputAbsolute >= 0.999f) clippedSamples++
                    peakHoldDbfs = max(peakHoldDbfs, toDb(inputAbsolute))
                    sum += inputSample.toDouble() * inputSample
                    channelSums[channel] += inputSample.toDouble() * inputSample
                    // Raw mode bypasses every Analog DSP stage. Only finite-value protection
                    // and the selected WAV sample-format conversion remain.
                    var value = inputSample * if (rawMic) 1f else gain
                    if (!rawMic && highPass) {
                        val filtered = highPassAlpha * (previousOut[channel] + value - previousIn[channel])
                        previousIn[channel] = value
                        previousOut[channel] = filtered
                        value = filtered
                    }
                    if (!rawMic) {
                        lowState[channel] += lowAlpha * (value - lowState[channel])
                        highState[channel] += highAlpha * (value - highState[channel])
                        val lowBand = lowState[channel]
                        val highBand = value - highState[channel]
                        val midBand = value - lowBand - highBand
                        value = lowBand * lowLinear + midBand * midLinear + highBand * highLinear
                    }
                    if (!rawMic && compressorEnabled) {
                        val coefficient = if (kotlin.math.abs(value) > compressorEnvelope[channel]) compressorAttack else compressorRelease
                        compressorEnvelope[channel] += coefficient * (kotlin.math.abs(value) - compressorEnvelope[channel])
                        if (compressorEnvelope[channel] > compressorThreshold) {
                            val compressed = compressorThreshold + (compressorEnvelope[channel] - compressorThreshold) / compressorRatio
                            value *= (compressed / compressorEnvelope[channel].coerceAtLeast(1.0e-6f))
                        }
                    }
                    if (!rawMic && limiter) value = value.coerceIn(-limiterCeiling, limiterCeiling)
                    // Integer PCM must be quantized into its representable range. Float WAV
                    // retains finite samples outside +/-1 when raw mode is selected.
                    if (bitDepth != 32) value = value.coerceIn(-1f, 0.99997f)
                    if (bitDepth == 32) {
                        val raw = java.lang.Float.floatToIntBits(value)
                        val offset = i * 4
                        pcm[offset] = (raw and 255).toByte()
                        pcm[offset + 1] = ((raw shr 8) and 255).toByte()
                        pcm[offset + 2] = ((raw shr 16) and 255).toByte()
                        pcm[offset + 3] = ((raw shr 24) and 255).toByte()
                    } else if (bitDepth == 24) {
                        val packed = RecordingPcm.toPcm24(value)
                        val offset = i * 3
                        pcm[offset] = (packed and 255).toByte()
                        pcm[offset + 1] = ((packed shr 8) and 255).toByte()
                        pcm[offset + 2] = ((packed shr 16) and 255).toByte()
                    } else {
                        val shortValue = RecordingPcm.toPcm16(value)
                        pcm[i * 2] = (shortValue and 255).toByte()
                        pcm[i * 2 + 1] = ((shortValue shr 8) and 255).toByte()
                    }
                    if (channels == 1) {
                        if (fftCount < fft.size) fft[fftCount++] = value
                    } else if (channel == 0) {
                        leftSample = value
                    } else if (fftCount < fft.size) {
                        fft[fftCount++] = (leftSample + value) * 0.5f
                    }
                }
                val outputBytes = count * bytesPerSample
                val remainingRiffBytes = (WaveHeaderWriter.maxDataBytes(bitDepth) - totalBytes).coerceAtLeast(0L)
                if (outputBytes.toLong() > remainingRiffBytes) {
                    // Keep the final WAV frame-aligned and finalize a valid RIFF file instead of
                    // silently overflowing the 32-bit RIFF chunk-size fields on long takes.
                    val frameBytes = bytesPerSample * channels
                    val safeBytes = ((remainingRiffBytes / frameBytes) * frameBytes).toInt()
                    if (safeBytes > 0) {
                        stream.write(pcm, 0, safeBytes)
                        totalBytes += safeBytes.toLong()
                    }
                    throw IllegalStateException("WAV reached the 4 GB RIFF limit. The take was safely finalized; start a new take to continue.")
                }
                stream.write(pcm, 0, outputBytes)
                totalBytes += outputBytes.toLong()
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
                            peakHoldDbfs = peakHoldDbfs,
                            clippedSamples = clippedSamples,
                            rmsDbfs = toDb(rms),
                            channelPeakDbfs = channelPeaks.map { toDb(it) },
                            channelRmsDbfs = channelSums.map { channelSum -> toDb(sqrt(channelSum / (count / channels).coerceAtLeast(1)).toFloat()) },
                            waveform = wave,
                            spectrum = currentSpectrum,
                            spectrogram = spectrogramHistory.toList(),
                            markersMs = synchronized(this) { markers.toList() },
                            activeInput = activeAudio.routedDevice?.productName?.toString(),
                            captureMode = captureModeLabel
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
            val markerSnapshot = synchronized(this) { markers.toList() }
            WaveHeaderWriter.write(output, totalBytes, rate, channels, bitDepth, recordingStartedWallClock, markerSnapshot)
            if (markerSnapshot.isNotEmpty()) {
                File(output.parentFile, output.nameWithoutExtension + ".markers.txt")
                    .writeText(markerSnapshot.mapIndexed { index, time -> "Marker " + (index + 1) + ": " + (time / 1000.0) + "s" }.joinToString("\n"))
            }
            // Publish SAVED only after the capture lock is released, so an immediate
            // next-take tap cannot be ignored by start() while running is still true.
            running = false
            mutableState.value = mutableState.value.copy(status = "SAVED", file = output, elapsedMs = elapsed(), bytesWritten = totalBytes, markersMs = markerSnapshot)
        } catch (error: Throwable) {
            runCatching {
                stream?.flush()
                stream?.fd?.sync()
                stream?.close()
                stream = null
                if (totalBytes > 0L && output.exists()) WaveHeaderWriter.write(output, totalBytes, rate, channels, bitDepth, recordingStartedWallClock, synchronized(this) { markers.toList() })
                else output.delete()
            }
            running = false
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

    @Synchronized fun addMarker() {
        if (running && markers.size < 128) {
            markers.add(elapsed())
            mutableState.value = mutableState.value.copy(markersMs = markers.toList())
        }
    }

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


}
