package com.unciv.logic.files

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveFileMetricsTest {
    @Test
    fun `counters accumulate across threads and snapshots stay unchanged`() {
        val metrics = SaveFileMetrics()
        metrics.record(SavePhase.LocalSave, 10, bytesWritten = 20)
        val before = metrics.snapshot()
        val workers = List(4) { Thread { repeat(100) { metrics.record(SavePhase.LocalSave, 30, 5, 20) } } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(SavePhaseMetrics(1, 10, 10, 0, 20), before[SavePhase.LocalSave])
        assertEquals(SavePhaseMetrics(401, 12010, 30, 2000, 8020), metrics.snapshot()[SavePhase.LocalSave])
    }
}
