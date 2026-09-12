package com.unciv.logic.files

/** Successful save-path work only. Byte counts are logical stream I/O, not physical disk traffic. */
enum class SavePhase { Clone, LocalSave, CloudSnapshot, CloudQueue, HistoryCopy }

data class SavePhaseMetrics(
    val count: Long = 0,
    val totalNanos: Long = 0,
    val maxNanos: Long = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
)

/** Bounded in-memory counters; no save names, payloads, logging or additional disk writes. */
class SaveFileMetrics {
    private val totals = linkedMapOf<SavePhase, SavePhaseMetrics>()

    @Synchronized
    fun record(phase: SavePhase, elapsedNanos: Long, bytesRead: Long = 0, bytesWritten: Long = 0) {
        val previous = totals[phase] ?: SavePhaseMetrics()
        totals[phase] = SavePhaseMetrics(previous.count + 1, previous.totalNanos + elapsedNanos,
            maxOf(previous.maxNanos, elapsedNanos), previous.bytesRead + bytesRead, previous.bytesWritten + bytesWritten)
    }

    @Synchronized
    fun snapshot(): Map<SavePhase, SavePhaseMetrics> = LinkedHashMap(totals)
}
