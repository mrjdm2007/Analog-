package com.mrjdm2007.analog

/** Pure PCM boundary helpers shared by capture and JVM regression tests. */
internal object RecordingPcm {
    fun completeSampleCount(readBytes: Int, bytesPerSample: Int, channels: Int): Int {
        if (readBytes <= 0 || bytesPerSample <= 0 || channels <= 0) return 0
        val completeSamples = readBytes / bytesPerSample
        return (completeSamples / channels) * channels
    }

    fun finiteOrZero(sample: Float): Float = if (sample.isFinite()) sample else 0f

    fun toPcm16(sample: Float): Int =
        (finiteOrZero(sample).coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767)

    fun toPcm24(sample: Float): Int =
        (finiteOrZero(sample).coerceIn(-1f, 1f) * 8388608f).toInt().coerceIn(-8388608, 8388607)
}
