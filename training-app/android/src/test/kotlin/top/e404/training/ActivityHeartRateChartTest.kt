package top.e404.training

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import top.e404.training.shared.HealthRecord

private fun record(
    id: String,
    at: Long,
    bpm: Int,
    sampleType: String = "active",
    source: String? = "xiaomi_workout"
) = HealthRecord(
    id, "2024-01-01", at,
    JsonObject(buildMap {
        put("bpm", JsonPrimitive(bpm))
        put("sampleType", JsonPrimitive(sampleType))
        source?.let { put("source", JsonPrimitive(it)) }
    })
)

class ActivityHeartRateChartTest {

    @Test
    fun `闭区间只保留真实运动采样并按时间排序`() {
        val input = listOf(
            record("end", 300, 130), record("before", 99, 90),
            record("middle", 200, 110), record("start", 100, 100),
            record("after", 301, 140), record("daily", 150, 80, "passive", "xiaomi_daily"),
            record("active-without-workout-source", 175, 92, source = null),
            record("other-active-source", 180, 95, source = "other"),
            record("invalid-zero-bpm", 190, 0)
        )

        assertEquals(
            listOf("start", "middle", "end"),
            filteredHeartRateRecords(input, 100, 300).map { it.sourceId }
        )
    }

    @Test
    fun `同一时刻重复运动来源只保留sourceId排序最前项`() {
        val duplicates = listOf(record("z-copy", 200, 120), record("a-copy", 200, 118))

        assertEquals(listOf("a-copy"), filteredHeartRateRecords(duplicates, 100, 300).map { it.sourceId })
    }

    @Test
    fun `单个运动采样保留其实际时刻`() {
        val only = record("only", 200, 120)

        assertEquals(listOf(200L), filteredHeartRateRecords(listOf(only), 100, 300).map { it.measuredAt })
    }
}
