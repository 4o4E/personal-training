package top.e404.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import top.e404.training.shared.Session
import top.e404.training.shared.SetResult

class WorkoutCompletionTest {
    private val startedAt = 1_799_999_000_000L
    private val workout = Session(
        id = "day-2027-01-15", name = "训练", snapshot = null, exercises = emptyList(), startedAt = startedAt,
        status = "active", localDate = "2027-01-15",
        results = listOf(
            SetResult("movement", 1, reps = 12, completedAt = startedAt + 120_000),
            SetResult("movement", 0, reps = 10, completedAt = startedAt + 60_000)
        )
    )

    @Test fun `修正或续练延后自动完成但不改写实际结束时间`() {
        val lastSet = startedAt + 120_000
        val editedAt = lastSet + 20 * 60_000
        assertEquals(lastSet + 30 * 60_000, workout.workoutIdleDeadline(null))
        assertEquals(editedAt + 30 * 60_000, workout.workoutIdleDeadline(editedAt))
        assertEquals(lastSet, workout.finishWorkout(false, editedAt).endedAt)
        assertEquals(lastSet, workout.finishWorkout(false, editedAt + 30 * 60_000).endedAt)
    }

    @Test fun `撤销最后一组按剩余真实组结算`() {
        val remaining = workout.copy(results = workout.results.drop(1))
        val undoneAt = startedAt + 180_000
        assertEquals(undoneAt + 30 * 60_000, remaining.workoutIdleDeadline(undoneAt))
        assertEquals(startedAt + 60_000, remaining.finishWorkout(false, undoneAt).endedAt)
    }

    @Test fun `无实际组不会自动完成且历史未知时刻不填假数据`() {
        val draft = workout.copy(results = emptyList())
        assertNull(draft.workoutIdleDeadline(startedAt))
        assertFailsWith<IllegalArgumentException> { draft.finishWorkout(false, startedAt) }
        val imported = workout.copy(startedAt = null, results = workout.results.map { it.copy(completedAt = null) })
        assertEquals(startedAt + 30 * 60_000, imported.workoutIdleDeadline(startedAt))
        assertNull(imported.finishWorkout(false, startedAt + 30 * 60_000).endedAt)
    }
}
