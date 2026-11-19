package com.mrjdm2007.analog

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.math.roundToInt

/** Reads ReplayGain gain/peak metadata from ID3 and Vorbis comments without loading the audio payload. */
object ReplayGainReader {
    fun trackGainMilliBel(contentResolver: ContentResolver, uri: Uri): Int? = runCatching {
        val bytes = contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < 2 * 1024 * 1024) {
                val count = input.read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 - total))
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        } ?: return@runCatching null
        parseBytes(bytes)
    }.getOrNull()

    /** Pure parser entry point so tag formats and clipping protection can be regression-tested. */
    internal fun parseBytes(bytes: ByteArray): Int? {
        // FLAC Vorbis-comment blocks and Ogg Vorbis comments store ReplayGain as UTF-8 key/value text.
        val comments = String(bytes, Charsets.UTF_8)
        val commentTrack = commentGain(comments, "TRACK")
        val commentAlbum = commentGain(comments, "ALBUM")
        val commentTrackPeak = commentPeak(comments, "TRACK")
        val commentAlbumPeak = commentPeak(comments, "ALBUM")
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) {
            val useTrack = commentTrack != null
            val gain = commentTrack ?: commentAlbum
            val peak = if (useTrack) commentTrackPeak else commentAlbumPeak
            return gain?.let { safeGainMilliBel(it, peak) }
        }
        val version = bytes[3].toInt() and 0xff
        if (version !in 3..4) return null
        val tagSize = synchsafe(bytes, 6).coerceAtMost(bytes.size - 10)
        var offset = 10
        val flags = bytes[5].toInt() and 0xff
        val limit = (10 + tagSize).coerceAtMost(bytes.size)
        if (flags and 0x40 != 0 && offset + 4 <= limit) {
            val extSize = if (version == 4) synchsafe(bytes, offset) else int32(bytes, offset)
            // ID3v2.4 includes the four-byte size field; v2.3 does not.
            val totalExtendedSize = if (version == 4) extSize else 4 + extSize
            if (totalExtendedSize < 4 || offset + totalExtendedSize > limit) return null
            offset += totalExtendedSize
        }
        var trackDb: Float? = null
        var albumDb: Float? = null
        var trackPeak: Float? = null
        var albumPeak: Float? = null
        while (offset + 10 <= limit) {
            val id = String(bytes, offset, 4, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            val frameSize = if (version == 4) synchsafe(bytes, offset + 4) else int32(bytes, offset + 4)
            if (frameSize <= 0 || offset + 10 + frameSize > limit) break
            if (id == "TXXX") {
                val payload = bytes.copyOfRange(offset + 10, offset + 10 + frameSize)
                val decoded = decodeTxxx(payload)
                val name = decoded.first.uppercase()
                val value = decoded.second.trim().removeSuffix("dB").trim()
                val gain = Regex("[-+]?\\d+(?:\\.\\d+)?").find(value)?.value?.toFloatOrNull()
                if (gain != null && name == "REPLAYGAIN_TRACK_GAIN") trackDb = gain
                if (gain != null && name == "REPLAYGAIN_ALBUM_GAIN") albumDb = gain
                if (gain != null && name == "REPLAYGAIN_TRACK_PEAK") trackPeak = gain
                if (gain != null && name == "REPLAYGAIN_ALBUM_PEAK") albumPeak = gain
            }
            offset += 10 + frameSize
        }
        // Track gain/peak take priority; album values are the standards-compliant fallback.
        val useTrack = trackDb != null || commentTrack != null
        val gain = trackDb ?: commentTrack ?: albumDb ?: commentAlbum
        val peak = if (useTrack) trackPeak ?: commentTrackPeak else albumPeak ?: commentAlbumPeak
        return gain?.let { safeGainMilliBel(it, peak) }
    }

    private fun commentPeak(text: String, kind: String): Float? =
        Regex("(?i)REPLAYGAIN_${kind}_PEAK\\s*=\\s*([-+]?\\d+(?:\\.\\d+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    /** Applies positive ReplayGain only up to the tagged sample-peak headroom. */
    private fun safeGainMilliBel(gainDb: Float, peak: Float?): Int {
        val peakCeilingDb = if (peak != null && peak > 0f) -20f * kotlin.math.log10(peak) else 0f
        return (minOf(gainDb, peakCeilingDb).coerceIn(-12f, 12f) * 100f).roundToInt()
    }

    private fun commentGain(text: String, kind: String): Float? =
        Regex("(?i)REPLAYGAIN_${kind}_GAIN\\s*=\\s*([-+]?\\d+(?:\\.\\d+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    private fun decodeTxxx(payload: ByteArray): Pair<String, String> {
        if (payload.isEmpty()) return "" to ""
        val encoding = payload[0].toInt() and 0xff
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val body = payload.copyOfRange(1, payload.size)
        val separator = if (encoding == 1 || encoding == 2) {
            (0 until body.size - 1 step 2).firstOrNull { body[it] == 0.toByte() && body[it + 1] == 0.toByte() } ?: -1
        } else body.indexOf(0)
        if (separator < 0) return String(body, charset) to ""
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        val description = String(body, 0, separator, charset)
        val valueStart = (separator + step).coerceAtMost(body.size)
        return description to String(body, valueStart, body.size - valueStart, charset)
    }

    private fun synchsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7f) shl 21) or
        ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
        ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
        (bytes[offset + 3].toInt() and 0x7f)

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
}
