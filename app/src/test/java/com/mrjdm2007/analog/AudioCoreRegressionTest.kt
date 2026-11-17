package com.mrjdm2007.analog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
