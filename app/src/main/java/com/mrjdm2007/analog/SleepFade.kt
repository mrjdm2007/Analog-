package com.mrjdm2007.analog

/** Pure volume envelope for the sleep timer's final fade-out window. */
object SleepFade {
    fun volumeAt(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 1f
        return (1f - elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}
