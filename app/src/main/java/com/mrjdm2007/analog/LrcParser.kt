package com.mrjdm2007.analog

/** Parses standard LRC timestamps, multiple timestamps per line, and global [offset:] tags. */
data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(source: String): List<LyricLine> {
        if (source.isBlank()) return emptyList()
        val offset = offsetTag.find(source)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        return source.lineSequence().flatMap { raw ->
            val matches = timestamp.findAll(raw).toList()
            if (matches.isEmpty()) return@flatMap emptySequence()
            val lyric = raw.substring(matches.last().range.last + 1).trim()
            matches.asSequence().mapNotNull { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                if (seconds !in 0L..59L) return@mapNotNull null
                val fractionText = match.groupValues[3]
                val fractionMs = when (fractionText.length) {
                    1 -> (fractionText.toLongOrNull() ?: 0L) * 100L
                    2 -> (fractionText.toLongOrNull() ?: 0L) * 10L
                    3 -> fractionText.toLongOrNull() ?: 0L
                    else -> 0L
                }
                LyricLine(((minutes * 60L + seconds) * 1000L + fractionMs + offset).coerceAtLeast(0L), lyric)
            }
        }.sortedBy { it.timeMs }.toList()
    }

    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else high = mid - 1
        }
        return result
    }
}
