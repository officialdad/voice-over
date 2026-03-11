package com.voiceover

import java.io.File

data class RecordedSegment(
    val audioFile: File,
    val startPositionMs: Long,
    val durationMs: Long
) {
    val endPositionMs: Long get() = startPositionMs + durationMs
}
