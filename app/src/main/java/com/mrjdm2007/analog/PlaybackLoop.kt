package com.mrjdm2007.analog

/** Deterministic A/B loop rules shared by the player UI and JVM regression tests. */
object PlaybackLoop {
    fun isValidRange(startMs: Long, endMs: Long): Boolean = startMs >= 0L && endMs - startMs >= 300L
    fun shouldWrap(positionMs: Long, startMs: Long, endMs: Long): Boolean = isValidRange(startMs, endMs) && positionMs >= endMs
}
