package top.e404.training

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.e404.training.shared.Load
import top.e404.training.shared.SetResult
import top.e404.training.shared.SetSegment

class HistoryFormattingTest {
    @Test
    fun `历史训练时间使用UTC加八并保留未知端点`() {
        val start = Instant.parse("2024-01-02T17:05:00Z").toEpochMilli()
        val end = Instant.parse("2024-01-02T18:40:00Z").toEpochMilli()

        assertEquals("01:05 - 02:40", historyTimeRange(start, end))
        assertEquals("01:05 - --:--", historyTimeRange(start, null))
        assertEquals("--:-- - 02:40", historyTimeRange(null, end))
        assertNull(historyTimeRange(null, null))
    }

    @Test
    fun `历史组展示重量次数零重量未知负重和侧别`() {
        val zeroLoad = SetResult(
            itemId = "row", setIndex = 0, reps = 8,
            load = Load(0.0, "lb", meaning = "per_hand", mode = "assistance"),
            completedAt = Instant.parse("2024-01-02T17:35:00Z").toEpochMilli()
        )
        val unknownMeaning = SetResult(
            itemId = "row", setIndex = 1, reps = 5,
            load = Load(20.5, "kg", meaning = "unknown"), side = "left"
        )
        val missingLoad = SetResult(itemId = "row", setIndex = 2, reps = 3)

        assertEquals("0 lb × 8 次 · 单只 · 助力 · 01:35", historyResultText(zeroLoad))
        assertEquals("20.5 kg × 5 次 · 左侧", historyResultText(unknownMeaning))
        assertEquals("3 次", historyResultText(missingLoad))
    }

    @Test
    fun `历史组按原单位分别展示降重分段和计时`() {
        val dropset = SetResult(
            itemId = "row", setIndex = 0,
            segments = listOf(
                SetSegment(6, Load(20.5, "kg", meaning = "unknown")),
                SetSegment(8, Load(0.0, "lb", meaning = "per_side")),
                SetSegment(10)
            )
        )
        val timed = SetResult(
            itemId = "row", setIndex = 1, seconds = 47,
            completedAt = Instant.parse("2024-01-02T01:30:00Z").toEpochMilli()
        )

        assertEquals(
            "20.5 kg × 6 次，0 lb × 8 次 · 单侧，10 次",
            historyResultText(dropset)
        )
        assertEquals("47 秒 · 09:30", historyResultText(timed))
    }
}
