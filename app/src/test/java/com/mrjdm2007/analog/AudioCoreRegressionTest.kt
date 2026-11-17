package com.mrjdm2007.analog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCoreRegressionTest {
    @Test fun stereoPcmDropsIncompleteTrailingFrames() {
        assertEquals(4, RecordingPcm.completeSampleCount(readBytes = 11, bytesPerSample = 2, channels = 2))
        assertEquals(4, RecordingPcm.completeSampleCount(readBytes = 14, bytesPerSample = 3, channels = 2))
        assertEquals(0, RecordingPcm.completeSampleCount(readBytes = 3, bytesPerSample = 3, channels = 2))
    }

    @Test fun integerPcmUsesFullNegativeAndPositiveRanges() {
        assertEquals(-32768, RecordingPcm.toPcm16(-1f))
        assertEquals(32767, RecordingPcm.toPcm16(1f))
        assertEquals(-8388608, RecordingPcm.toPcm24(-1f))
        assertEquals(8388607, RecordingPcm.toPcm24(1f))
        assertEquals(0, RecordingPcm.toPcm16(Float.NaN))
        assertEquals(0, RecordingPcm.toPcm24(Float.POSITIVE_INFINITY))
    }

    @Test fun parsesFlacAndOggVorbisCommentGainAndPeak() {
        val comment = "fLaC\u0000vendor=Analog\u0000REPLAYGAIN_TRACK_GAIN=+6.00 dB\u0000REPLAYGAIN_TRACK_PEAK=2.0"
        assertEquals(-602, ReplayGainReader.parseBytes(comment.toByteArray(Charsets.UTF_8)))
    }

    @Test fun trackGainTakesPriorityOverAlbumGain() {
        val comment = "REPLAYGAIN_TRACK_GAIN=-4.25 dB\u0000REPLAYGAIN_ALBUM_GAIN=-8.00 dB"
        assertEquals(-425, ReplayGainReader.parseBytes(comment.toByteArray(Charsets.UTF_8)))
    }

    @Test fun parsesId3v23TxxxReplayGain() {
        val tag = id3Tag(version = 3, extendedHeader = false, txxx("REPLAYGAIN_TRACK_GAIN", "-5.00 dB"))
        assertEquals(-500, ReplayGainReader.parseBytes(tag))
    }

    @Test fun parsesId3v24WithExtendedHeader() {
        val ext = byteArrayOf(0, 0, 0, 6, 1, 0)
        val tag = id3Tag(version = 4, extendedHeader = true, ext + txxx("REPLAYGAIN_TRACK_GAIN", "-3.50 dB"))
        assertEquals(-350, ReplayGainReader.parseBytes(tag))
    }

    @Test fun ignoresMalformedAndUnrelatedTags() {
        assertNull(ReplayGainReader.parseBytes(byteArrayOf(1, 2, 3, 4, 5)))
        val tag = id3Tag(version = 3, extendedHeader = false, txxx("ARTIST", "Analog"))
        assertNull(ReplayGainReader.parseBytes(tag))
    }

    @Test fun writesValidBwfHeaderWithEmbeddedCuePoints() {
        val file = File.createTempFile("analog-bwf-test", ".wav")
        try {
            file.writeBytes(ByteArray(WaveHeaderWriter.headerSizeBytes(32) + 16))
            WaveHeaderWriter.write(
                file, dataSize = 16, rate = 48000, channels = 2, bitDepth = 32,
                startedAtMillis = 1_794_923_696_000L, markerTimes = listOf(0L, 1000L)
            )
            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(bytes.size - 8, leInt(bytes, 4))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("bext", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 622, 4, Charsets.US_ASCII))
            assertEquals(3, leShort(bytes, 630)) // IEEE float format code
            assertEquals(2, leShort(bytes, 632))
            assertEquals(48000, leInt(bytes, 634))
            assertEquals(32, leShort(bytes, 644))
            assertEquals("fact", String(bytes, 646, 4, Charsets.US_ASCII))
            assertEquals(2, leInt(bytes, 654)) // 16 bytes / (2 channels × 4 bytes)
            assertEquals("cue ", String(bytes, 658, 4, Charsets.US_ASCII))
            assertEquals(2, leInt(bytes, 666))
            assertEquals(0, leInt(bytes, 674))
            assertEquals(48000, leInt(bytes, 698))
            assertEquals("data", String(bytes, 3742, 4, Charsets.US_ASCII))
            assertEquals(16, leInt(bytes, 3746))
            assertEquals(WaveHeaderWriter.headerSizeBytes(32) + 16, bytes.size)
        } finally {
            file.delete()
        }
    }

    @Test fun writesIntegerPcmFormatCodeFor24BitWav() {
        val file = File.createTempFile("analog-pcm-test", ".wav")
        try {
            file.writeBytes(ByteArray(WaveHeaderWriter.headerSizeBytes(24)))
            WaveHeaderWriter.write(file, 0, 44100, 1, 24, 0L, emptyList())
            val bytes = file.readBytes()
            assertEquals(1, leShort(bytes, 630)) // Integer PCM
            assertEquals(1, leShort(bytes, 632))
            assertEquals(44100, leInt(bytes, 634))
            assertEquals(3, leShort(bytes, 642))
            assertEquals(24, leShort(bytes, 644))
            assertEquals(0, leInt(bytes, 3734))
        } finally {
            file.delete()
        }
    }

    @Test fun padsOddLengthPcmDataToAWordBoundary() {
        val file = File.createTempFile("analog-pad-test", ".wav")
        try {
            file.writeBytes(ByteArray(WaveHeaderWriter.headerSizeBytes(24) + 3))
            WaveHeaderWriter.write(file, 3, 44100, 1, 24, 0L, emptyList())
            val bytes = file.readBytes()
            assertEquals(3, leInt(bytes, 3734))
            assertEquals(0, bytes.last().toInt())
            assertEquals(bytes.size - 8, leInt(bytes, 4))
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsRiffDataSizesThatWouldOverflowTheContainer() {
        val file = File.createTempFile("analog-limit-test", ".wav")
        try {
            try {
                WaveHeaderWriter.write(file, WaveHeaderWriter.maxDataBytes(24) + 1, 48000, 2, 24, 0L, emptyList())
                throw AssertionError("Expected oversized RIFF data to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: caller must stop and finalize before the 32-bit RIFF limit.
            }
        } finally {
            file.delete()
        }
    }

    @Test fun abLoopRequiresIncreasingRangeAndWrapsAtEnd() {
        assertEquals(false, PlaybackLoop.isValidRange(1000L, 1000L))
        assertEquals(false, PlaybackLoop.isValidRange(2000L, 1000L))
        assertEquals(false, PlaybackLoop.isValidRange(2000L, Long.MIN_VALUE))
        assertEquals(false, PlaybackLoop.isValidRange(1000L, 1200L))
        assertEquals(true, PlaybackLoop.isValidRange(1000L, 1500L))
        assertEquals(true, PlaybackLoop.shouldWrap(1500L, 1000L, 1500L))
        assertEquals(false, PlaybackLoop.shouldWrap(1499L, 1000L, 1500L))
        assertEquals(false, PlaybackLoop.shouldWrap(2000L, 1000L, 1000L))
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun txxx(description: String, value: String): ByteArray {
        val payload = byteArrayOf(3) + description.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0) + value.toByteArray(Charsets.UTF_8)
        return "TXXX".toByteArray(Charsets.ISO_8859_1) + be32(payload.size) +
            byteArrayOf(0, 0) + payload
    }

    private fun id3Tag(version: Int, extendedHeader: Boolean, body: ByteArray): ByteArray {
        val size = body.size
        val synchsafe = byteArrayOf(
            ((size shr 21) and 0x7f).toByte(),
            ((size shr 14) and 0x7f).toByte(),
            ((size shr 7) and 0x7f).toByte(),
            (size and 0x7f).toByte()
        )
        return byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            version.toByte(), 0, if (extendedHeader) 0x40 else 0
        ) + synchsafe + body
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(),
        (value shr 8).toByte(), value.toByte()
    )
}
