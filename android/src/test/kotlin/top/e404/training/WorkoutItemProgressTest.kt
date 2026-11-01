package top.e404.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutItemProgressTest {
    @Test
    fun `保持用户选择的有效未完成动作`() {
        val items = listOf(
            WorkoutItemProgress("a", listOf(0, 1), setOf(0), skipped = false),
            WorkoutItemProgress("b", listOf(0), emptySet(), skipped = false)
        )

        assertEquals("b", resolveCurrentWorkoutItem(items, "b"))
    }

    @Test
    fun `当前动作全部目标完成后选择下一项`() {
        val items = listOf(
            WorkoutItemProgress("a", listOf(0, 1), setOf(0, 1), skipped = false),
            WorkoutItemProgress("b", listOf(0), emptySet(), skipped = false)
        )

        assertEquals("b", resolveCurrentWorkoutItem(items, "a"))
    }

    @Test
    fun `跳过动作不被选择且没有有效目标时返回空`() {
        val items = listOf(
            WorkoutItemProgress("skipped", listOf(0), emptySet(), skipped = true),
            WorkoutItemProgress("done", listOf(0), setOf(0), skipped = false)
        )

        assertNull(resolveCurrentWorkoutItem(items, "skipped"))
        assertNull(nextWorkoutTargetIndex(items.first()))
    }

    @Test
    fun `跳过当前项后推进到下一有效目标`() {
        val items = listOf(
            WorkoutItemProgress("skipped", listOf(0, 1), setOf(0), skipped = true),
            WorkoutItemProgress("next", listOf(0), emptySet(), skipped = false)
        )

        assertEquals("next", resolveCurrentWorkoutItem(items, "skipped"))
    }

    @Test
    fun `有效当前组按真实组索引选择`() {
        val item = WorkoutItemProgress("a", listOf(0, 1, 2), setOf(0, 2), skipped = false)

        assertEquals(1, nextWorkoutTargetIndex(item))
    }
}
