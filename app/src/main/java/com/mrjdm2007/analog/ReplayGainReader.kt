package com.mrjdm2007.analog

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.nio.charset.Charset
import kotlin.math.roundToInt

/** Reads standard ID3v2 TXXX ReplayGain fields without loading the audio payload. */
object ReplayGainReader {
    fun trackGainMilliBel(contentResolver: ContentResolver, uri: Uri): Int? = runCatching {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024 * 1024)
            val read = BufferedInputStream(input).read(buffer)
            if (read <= 0) return@runCatching null
            buffer.copyOf(read)
        } ?: return@runCatching null
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return@runCatching null
        val version = bytes[3].toInt() and 0xff
        if (version !in 3..4) return@runCatching null
        val tagSize = synchsafe(bytes, 6).coerceAtMost(bytes.size - 10)
        var offset = 10
        val flags = bytes[5].toInt() and 0xff
        if (flags and 0x40 != 0 && offset + 4 <= bytes.size) {
            val extSize = if (version == 4) synchsafe(bytes, offset) else int32(bytes, offset)
            offset += 4 + extSize.coerceAtLeast(0)
        }
        val limit = (10 + tagSize).coerceAtMost(bytes.size)
        var trackDb: Float? = null
        var albumDb: Float? = null
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
            }
            offset += 10 + frameSize
        }
        // Track gain takes priority; album gain is a standards-compliant fallback.
        (trackDb ?: albumDb)?.let { (it * 100f).roundToInt().coerceIn(-1200, 0) }
    }.getOrNull()

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
