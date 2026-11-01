package top.e404.training

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.e404.training.shared.HealthCollectionState
import top.e404.training.shared.HealthStatus

class HistorySyncResultTest {
    @Test
    fun `collection in progress outranks a synced training status`() {
        val state = AppData(
            syncStatus = SyncStatus.synced,
            health = HealthUiState(
                healthStatus = HealthStatus(emptyList(), emptyMap(), HealthCollectionState(stage = "running"))
            )
        )

        assertFalse(historySyncResult(state).successful)
    }

    @Test
    fun `media failure outranks a synced training status`() {
        val state = AppData(syncStatus = SyncStatus.synced, mediaSyncStatus = MediaSyncStatus.error)

        assertFalse(historySyncResult(state).successful)
    }
    @Test
    fun `health failure outranks synced training status`() {
        val state = AppData(
            syncStatus = SyncStatus.synced,
            health = HealthUiState(healthError = "读取失败")
        )

        assertFalse(historySyncResult(state).successful)
    }

    @Test
    fun `health details still loading are not complete`() {
        val state = AppData(
            syncStatus = SyncStatus.synced,
            health = HealthUiState(
                selectedDate = java.time.LocalDate.parse("2025-01-01"),
                detailStateByDataset = mapOf(
                    top.e404.training.shared.HealthDataset.body to
                        HealthDetailCacheState(HealthDetailStatus.loading)
                )
            )
        )

        assertFalse(historySyncResult(state).successful)
    }

    @Test
    fun `conflict state outranks synced training status`() {
        val state = AppData(syncStatus = SyncStatus.conflict)

        assertFalse(historySyncResult(state).successful)
    }

    @Test
    fun `clean completed retry is successful`() {
        val state = AppData(syncStatus = SyncStatus.synced, mediaSyncStatus = MediaSyncStatus.ready)

        assertTrue(historySyncResult(state).successful)
    }
}
