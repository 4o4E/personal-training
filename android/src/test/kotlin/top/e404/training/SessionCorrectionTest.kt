package top.e404.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.e404.training.shared.*

class SessionCorrectionTest {
    private val originalExercise = Exercise("original-exercise", "原动作", muscles = listOf("core"), instructions = emptyList())
    private val actualExercise = Exercise("actual-exercise", "实际动作", muscles = listOf("core"), instructions = emptyList())
    private val item = PlanItem("item", originalExercise.id, sets = List(4) { SetTarget(reps = 10) })
    private val snapshot = Plan("snapshot", "开始时计划", items = listOf(item))
    private val session = Session(
        id = "day-2026-10-22", name = "训练", snapshot = snapshot, exercises = listOf(originalExercise),
        startedAt = 1_792_500_000_000L, status = "active", items = listOf(item), localDate = "2026-10-22"
    )

    @Test fun `订正实际动作保留组目标和实际结果但不改开始快照`() {
        val result = SetResult("item", 0, reps = 8, load = Load(10.0, "kg"), completedAt = session.startedAt!! + 1000)
        val recorded = session.copy(results = listOf(result))
        val corrected = correctSessionExercise(recorded, item.id, actualExercise, "actual-equipment")

        assertEquals(snapshot, corrected.snapshot)
        assertEquals(item.sets, corrected.items.single().sets)
        assertEquals(actualExercise.id, corrected.items.single().exerciseId)
        assertEquals("actual-equipment", corrected.items.single().equipmentId)
        assertEquals(listOf(result), corrected.results)
        assertEquals(session.startedAt, corrected.results.single().completedAt!! - 1000)
    }

    @Test fun `零完成组更换直接替换条目而不复制计划组`() {
        val replacement = PlanItem("replacement", actualExercise.id, sets = List(4) { SetTarget(reps = 9) })
        val replaced = replaceRemainingSessionExercise(session, item.id, replacement, actualExercise)

        assertEquals(snapshot, replaced.snapshot)
        assertEquals(listOf(replacement), replaced.items)
        assertTrue(replaced.results.isEmpty())
        assertEquals(listOf(originalExercise, actualExercise), replaced.exercises)
    }

    @Test fun `部分完成后更换只把剩余组交给新动作`() {
        val result = SetResult("item", 0, reps = 8, completedAt = session.startedAt!! + 1000)
        val recorded = session.copy(results = listOf(result))
        val replacement = PlanItem("replacement", actualExercise.id, sets = List(3) { SetTarget(reps = 9) })
        val replaced = replaceRemainingSessionExercise(recorded, item.id, replacement, actualExercise)

        assertEquals(snapshot, replaced.snapshot)
        assertEquals(listOf(item, replacement), replaced.items)
        assertEquals(listOf(result), replaced.results)
        assertEquals(listOf(item.id), replaced.skippedItems)
        assertEquals(3, replaced.items.last().sets.size)
    }
    @Test fun `订正和换练不消费分化但新增完成组才消费`() {
        val arranged = session.copy(
            snapshot = snapshot.copy(programId = "program"),
            arrangementOverrideId = "override"
        )
        val corrected = correctSessionExercise(arranged, item.id, actualExercise, null)
        val replacement = PlanItem("replacement", actualExercise.id, sets = listOf(SetTarget(reps = 9)))
        val swapped = replaceRemainingSessionExercise(arranged, item.id, replacement, actualExercise)
        assertNull(arrangementOverrideToConsume(arranged, corrected))
        assertNull(arrangementOverrideToConsume(arranged, swapped))

        val completed = arranged.copy(results = listOf(SetResult(item.id, 0, reps = 8)))
        assertEquals("program" to "override", arrangementOverrideToConsume(arranged, completed))
    }
    @Test fun `历史订正允许真实动作器械备注但拒绝改写快照时间和原始组目标`() {
        val startedAt = session.startedAt!!
        val result = SetResult(item.id, 0, reps = 8, completedAt = startedAt + 1000)
        val before = session.copy(status = "completed", endedAt = startedAt + 2000, results = listOf(result))
        val original = document(Kind.session, before.id, before)
        val corrected = correctSessionExercise(before, item.id, actualExercise, "actual-equipment")
        val correctedItems = corrected.items.map { it.copy(notes = "本次动作订正备注") }
        val accepted = document(Kind.session, before.id, corrected.copy(items = correctedItems, notes = "更新训练反馈"))

        validateDayHistorySessionEdit(original, accepted)

        val changedTarget = corrected.copy(items = correctedItems.map { changedItem ->
            changedItem.copy(sets = changedItem.sets.map { it.copy(reps = 11) })
        })
        assertFailsWith<IllegalArgumentException> {
            validateDayHistorySessionEdit(original, document(Kind.session, before.id, changedTarget))
        }
        assertFailsWith<IllegalArgumentException> {
            validateDayHistorySessionEdit(
                original, document(Kind.session, before.id, accepted.value<Session>().copy(endedAt = startedAt + 3000))
            )
        }
    }
}
