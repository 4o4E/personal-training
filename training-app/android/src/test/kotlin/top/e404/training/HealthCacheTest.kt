package top.e404.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HealthCacheTest {
    @Test fun detailCoverageRequiresCompletedDatasetAtRevision() {
        val incomplete = LocalHealthDetailState("2026-10-28", "heartRate", 7, false, 1000)
        val complete = LocalHealthDetailState("2026-10-28", "heartRate", 7, true, 2000)

        assertEquals(HealthDetailStatus.partial, incomplete.uiState(7).status)
        assertEquals(7L, incomplete.uiState(7).revision)
        assertEquals(HealthDetailStatus.complete, complete.uiState(7).status)
        assertEquals(HealthDetailStatus.partial, complete.uiState(8).status)
        assertFalse(incomplete.complete)
    }

}
