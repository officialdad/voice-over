package com.voiceover

import java.io.File

data class RecordedSegment(
    val audioFile: File,
    val startPositionMs: Long,
    val durationMs: Long
) {
    init {
        require(startPositionMs >= 0) { "startPositionMs must be non-negative" }
        require(durationMs > 0) { "durationMs must be positive" }
    }

    val endPositionMs: Long get() = startPositionMs + durationMs
}
