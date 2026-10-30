package top.e404.training

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.e404.training.shared.Plan
import top.e404.training.shared.PlanItem
import top.e404.training.shared.Program
import top.e404.training.shared.Session
import top.e404.training.shared.SetResult
import top.e404.training.shared.SetTarget
import top.e404.training.shared.SplitSlot

class CalendarSupportTest {
    private val program = Program(
        "rotation", "推拉腿",
        listOf(
            SplitSlot("push", "推", listOf("chest")),
            SplitSlot("pull", "拉", listOf("lats")),
            SplitSlot("legs", "腿", listOf("quadriceps"))
        )
    )
    private val plans = program.slots.map { slot ->
        Plan(
            "plan-${slot.id}", "${slot.name}训练", program.id, slot.id,
            items = listOf(PlanItem("item-${slot.id}", "exercise", sets = List(4) { SetTarget(reps = 10) }))
        )
    }

    private fun actualSession(date: LocalDate, slotId: String): Session {
        val plan = plans.single { it.slotId == slotId }
        return Session(
            "session-$date", "实际训练", plan, emptyList(), null,
            status = "active",
            results = listOf(SetResult(plan.items.single().id, 0, reps = 10)),
            localDate = date.toString()
        )
    }

    private fun preview(today: LocalDate, days: Int, sessions: List<Session>, plans: List<Plan> = this.plans) =
        futureCalendarPlans(today, days, program, sessions, plans, emptyMap(), null)

    @Test
    fun `昨日腿训练且今日未训练时从今天预览推拉腿`() {
        val today = LocalDate.of(2026, 10, 28)
        val history = listOf(actualSession(today.minusDays(1), "legs"))

        val result = preview(today, 3, history)

        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), result.keys.toList())
        assertEquals(listOf("推", "拉", "腿"), result.values.map { it.single().shortLabel })
        assertEquals(listOf(4, 4, 4), result.values.map { it.single().targetSets })
    }

    @Test
    fun `今日已有实际组时从明天开始并从下一分化项轮换`() {
        val today = LocalDate.of(2026, 10, 28)

        val result = preview(today, 3, listOf(actualSession(today, "push")))

        assertEquals(listOf(today.plusDays(1), today.plusDays(2), today.plusDays(3)), result.keys.toList())
        assertEquals(listOf("拉", "腿", "推"), result.values.map { it.single().shortLabel })
    }

    @Test
    fun `今日零组会话不跳过分化项且连续休息多日仍从今天起算`() {
        val today = LocalDate.of(2026, 10, 28)
        val earlierLegs = actualSession(today.minusDays(4), "legs")
        val zeroGroupToday = actualSession(today, "push").copy(results = emptyList())

        val result = preview(today, 3, listOf(earlierLegs, zeroGroupToday))

        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), result.keys.toList())
        assertEquals(listOf("推", "拉", "腿"), result.values.map { it.single().shortLabel })
        assertFalse(result.containsKey(today.plusDays(3)))
        assertTrue(result.values.all { it.single().targetSets == 4 })
    }

    @Test
    fun `今天明确日期计划优先于轮换模板`() {
        val today = LocalDate.of(2026, 10, 28)
        val datedPlan = Plan(
            "dated-today", "今天特别腿训", program.id, "legs", date = today.toString(),
            items = listOf(PlanItem("dated-item", "exercise", sets = listOf(SetTarget(reps = 8), SetTarget(reps = 8))))
        )

        val result = preview(today, 3, emptyList(), plans + datedPlan)

        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)), result.keys.toList())
        assertEquals(listOf("腿", "推", "拉"), result.values.map { it.single().shortLabel })
        assertEquals(listOf(2, 4, 4), result.values.map { it.single().targetSets })
    }

    @Test
    fun `月末起算的未训练窗口包含今天且日期正确跨月`() {
        val today = LocalDate.of(2026, 10, 31)

        val result = preview(today, 3, emptyList())

        assertEquals(
            listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)),
            result.keys.toList()
        )
        assertEquals(listOf("推", "拉", "腿"), result.values.map { it.single().shortLabel })
    }
}
