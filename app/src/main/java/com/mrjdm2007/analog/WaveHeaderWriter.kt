package com.mrjdm2007.analog

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes a fixed-layout Broadcast Wave header, including optional standard RIFF cue points. */
internal object WaveHeaderWriter {
    private const val CUE_RESERVE_BYTES = 3092
    private const val PCM_HEADER_SIZE_BYTES = 3746
    private const val FLOAT_HEADER_SIZE_BYTES = 3758

    fun headerSizeBytes(bitDepth: Int): Int = if (bitDepth == 32) FLOAT_HEADER_SIZE_BYTES else PCM_HEADER_SIZE_BYTES

    fun maxDataBytes(bitDepth: Int): Long = 0xFFFF_FFFFL - (headerSizeBytes(bitDepth) - 8L) - 1L

    fun write(file: File, dataSize: Long, rate: Int, channels: Int, bitDepth: Int, startedAtMillis: Long, markerTimes: List<Long>) {
        require(dataSize >= 0L && dataSize <= maxDataBytes(bitDepth)) { "Audio data exceeds the RIFF/WAV size limit." }
        require(rate > 0 && channels in 1..2 && bitDepth in listOf(16, 24, 32))
        val bytesPerSample = bitDepth / 8
        val blockAlign = channels * bytesPerSample
        val headerSize = headerSizeBytes(bitDepth)
        val padBytes = (dataSize and 1L).toInt()
        val cues = markerTimes.take(128)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF"); writeInt(raf, (headerSize - 8L + dataSize + padBytes).toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("bext"); writeInt(raf, 602)
            writeFixedAscii(raf, "Analog Studio field recording", 256)
            writeFixedAscii(raf, "Analog Android", 32)
            writeFixedAscii(raf, "ANALOG-" + startedAtMillis.toString(), 32)
            val started = Date(startedAtMillis)
            writeFixedAscii(raf, SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(started), 10)
            writeFixedAscii(raf, SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(started), 8)
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { time = started }
            val secondsSinceMidnight = calendar.get(Calendar.HOUR_OF_DAY) * 3600L +
                calendar.get(Calendar.MINUTE) * 60L + calendar.get(Calendar.SECOND)
            val timeReference = secondsSinceMidnight * rate.toLong()
            writeInt(raf, timeReference.toInt())
            writeInt(raf, (timeReference ushr 32).toInt())
            writeShort(raf, 1)
            raf.write(ByteArray(64 + 190))
            raf.writeBytes("fmt "); writeInt(raf, 16); writeShort(raf, if (bitDepth == 32) 3 else 1)
            writeShort(raf, channels); writeInt(raf, rate); writeInt(raf, rate * channels * bytesPerSample)
            writeShort(raf, blockAlign); writeShort(raf, bitDepth)
            // IEEE-float WAVE carries a fact chunk with the number of sample frames.
            if (bitDepth == 32) {
                raf.writeBytes("fact"); writeInt(raf, 4)
                writeInt(raf, (dataSize / blockAlign).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }

            val cuePayloadSize = 4 + cues.size * 24
            raf.writeBytes("cue "); writeInt(raf, cuePayloadSize); writeInt(raf, cues.size)
            cues.forEachIndexed { index, markerMs ->
                val safeMs = markerMs.coerceAtLeast(0L)
                val maxMs = Int.MAX_VALUE.toLong() * 1000L / rate
                val sampleOffset = if (safeMs > maxMs) Int.MAX_VALUE else (safeMs * rate / 1000L).toInt()
                writeInt(raf, index + 1)
                writeInt(raf, sampleOffset)
                raf.writeBytes("data")
                writeInt(raf, 0)
                writeInt(raf, 0)
                writeInt(raf, sampleOffset)
            }
            val cueChunkBytes = 8 + cuePayloadSize
            val junkBytes = CUE_RESERVE_BYTES - cueChunkBytes
            check(junkBytes >= 8) { "Cue reservation is too small." }
            raf.writeBytes("JUNK")
            writeInt(raf, junkBytes - 8)
            raf.write(ByteArray(junkBytes - 8))
            raf.writeBytes("data"); writeInt(raf, dataSize.toInt())
            if (padBytes != 0) raf.write(0)
            raf.setLength(headerSize.toLong() + dataSize + padBytes)
        }
    }

    private fun writeFixedAscii(raf: RandomAccessFile, value: String, length: Int) {
        val bytes = value.toByteArray(Charsets.US_ASCII).take(length).toByteArray()
        raf.write(bytes)
        if (bytes.size < length) raf.write(ByteArray(length - bytes.size))
    }

    private fun writeInt(raf: RandomAccessFile, value: Int) {
        raf.write(value and 255); raf.write((value shr 8) and 255)
        raf.write((value shr 16) and 255); raf.write((value shr 24) and 255)
    }

    private fun writeShort(raf: RandomAccessFile, value: Int) {
        raf.write(value and 255); raf.write((value shr 8) and 255)
    }
}
