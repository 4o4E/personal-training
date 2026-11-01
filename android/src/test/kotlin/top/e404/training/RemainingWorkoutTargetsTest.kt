package top.e404.training

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.e404.training.shared.PlanItem
import top.e404.training.shared.Session
import top.e404.training.shared.SetResult
import top.e404.training.shared.SetTarget

class RemainingWorkoutTargetsTest {
    private fun session(items: List<PlanItem>, results: List<SetResult> = emptyList(), skipped: List<String> = emptyList()) =
        Session(
            id = "session",
            name = "训练",
            snapshot = null,
            exercises = emptyList(),
            startedAt = null,
            status = "active",
            items = items,
            results = results,
            skippedItems = skipped,
            localDate = "2026-10-29"
        )

    private fun item(id: String, vararg targets: SetTarget) = PlanItem(id, "exercise-$id", sets = targets.toList())
    private fun result(itemId: String, index: Int) = SetResult(itemId, index, reps = 8)

    @Test
    fun `last target of entire session leaves no effective goals`() {
        val item = item("final", SetTarget(), SetTarget())

        assertFalse(hasRemainingWorkoutTargets(session(listOf(item), listOf(result("final", 0), result("final", 1)))))
    }

    @Test
    fun `another action with a remaining real target keeps rest applicable`() {
        val first = item("first", SetTarget())
        val otherAction = item("other", SetTarget())

        assertTrue(hasRemainingWorkoutTargets(session(listOf(first, otherAction), listOf(result("first", 0)))))
    }

    @Test
    fun `skipped remaining targets do not keep session goals alive`() {
        val completed = item("done", SetTarget())
        val skipped = item("skipped", SetTarget(), SetTarget())

        assertFalse(hasRemainingWorkoutTargets(session(listOf(completed, skipped), listOf(result("done", 0)), listOf("skipped"))))
    }

    @Test
    fun `stretch and appended items count their actual remaining targets`() {
        val stretch = item("stretch", SetTarget(seconds = 30))
        val appended = item("appended", SetTarget(reps = 10))

        assertTrue(hasRemainingWorkoutTargets(session(listOf(stretch))))
        assertTrue(hasRemainingWorkoutTargets(session(listOf(item("done", SetTarget()), appended), listOf(result("done", 0)))))
    }
}
