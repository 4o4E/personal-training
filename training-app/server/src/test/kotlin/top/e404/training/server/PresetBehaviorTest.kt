package top.e404.training.server

import org.junit.jupiter.api.Test
import top.e404.training.shared.*
import kotlin.test.*

class PresetBehaviorTest {
    private val exercises by lazy {
        javaClass.classLoader.getResourceAsStream("catalog.json")!!.bufferedReader().use { reader ->
            wireJson.decodeFromString<List<Document>>(reader.readText()).filter { it.kind == Kind.exercise }.associate { it.id to it.value<Exercise>() }
        }
    }

    @Test fun `全部候选在三至五动作时覆盖声明部位且不重复补足`() {
        for (split in presetSplits) for (style in PresetStyle.entries) for (actions in 3..5) {
            val preview = previewPreset(split.count, style, actions, exercises, null, emptyList())
            val selection = "${split.count} / $style / $actions"
            assertEquals(emptyList(), preview.unavailable, selection)
            assertEquals(split.count, preview.days.size, selection)
            preview.days.forEach { day ->
                assertEquals(actions, day.exercises.size, "$selection / ${day.name}")
                assertEquals(actions, day.exercises.map { it.id }.distinct().size, "$selection / ${day.name}")
                assertTrue(day.exercises.all { it.type == "strength" }, selection)
                val covered = day.exercises.flatMap { it.muscles + it.secondaryMuscles }.toSet()
                assertTrue(covered.containsAll(day.muscles), "$selection / ${day.name} 缺少 ${day.muscles - covered}")
                day.exercises.forEach { exercise ->
                    assertTrue((exercise.muscles + exercise.secondaryMuscles).any { it in day.muscles }, "$selection / ${day.name} 的 ${exercise.name}不对应目标部位")
                }
            }
        }
    }

    @Test fun `健身房缺少训练凳会阻止候选且不借用其他场所器械`() {
        val dumbbell = Equipment("dumbbell", "哑铃", "gym", listOf("dumbbell"), unit = "lb", meaning = "per_hand")
        val elsewhere = Equipment("bench-elsewhere", "其他场所训练凳", "another", listOf("flat_bench", "adjustable_bench"))
        val missing = previewPreset(3, PresetStyle.DUMBBELLS, 4, exercises, "gym", listOf(dumbbell, elsewhere))
        assertTrue(missing.days.first().unavailable.any { "卧推" in it })
        assertEquals(emptyList(), missing.days.last().unavailable)
        val bench = elsewhere.copy(id = "bench", gymId = "gym")
        val available = previewPreset(3, PresetStyle.DUMBBELLS, 5, exercises, "gym", listOf(dumbbell, bench))
        assertEquals(emptyList(), available.unavailable)
        val machines = previewPreset(3, PresetStyle.MACHINES, 3, exercises, "gym", listOf(dumbbell, bench))
        assertTrue(machines.days.all { it.unavailable.isNotEmpty() })
    }

    private val program = Program("rotation", "轮换", listOf(SplitSlot("push", "推", listOf("chest")), SplitSlot("pull", "拉", listOf("lats")), SplitSlot("legs", "腿", listOf("quadriceps"))))
    private fun plan(slot: String, id: String = "plan-$slot", date: String? = null) = Plan(id, "训练", program.id, slot, date = date,
        items = listOf(PlanItem("item-$id", "exercise_3601968b", sets = List(4) { SetTarget(reps = 10) })))
    private fun completed(plan: Plan, order: Int, status: String = "completed"): Session {
        val time = 1_792_500_000_000L + order * 100_000L
        return Session("session-$order", plan.name, plan, listOf(exercises.getValue("exercise_3601968b")), time, time + 20_000, status,
            listOf(SetResult(plan.items.first().id, 0, reps = 10, completedAt = time + 10_000)))
    }

    @Test fun `完成一轮后原模板重新可练而取消不会跳过`() {
        val plans = program.slots.map { plan(it.id) }
        val history = mutableListOf<Session>()
        assertEquals(plans.first(), rotationPlans(program, plans, history, emptyMap()).first().plan)
        history += completed(plans[0], 1)
        history += completed(plans[1], 2, "cancelled")
        assertEquals(plans[1], rotationPlans(program, plans, history, emptyMap()).first().plan)
        history += completed(plans[1], 3)
        history += completed(plans[2], 4)
        assertEquals(plans[0], rotationPlans(program, plans, history, emptyMap()).first().plan)
        history += completed(plans[0], 5)
        assertEquals(plans[1], rotationPlans(program, plans, history, emptyMap()).first().plan)
    }

    @Test fun `日期执行一次且多个循环模板只采用明确选择`() {
        val first = plan("push", "template-kg").copy(items = listOf(PlanItem("kg", "exercise_3601968b", sets = listOf(SetTarget(reps = 10, load = Load(20.0, "kg", "per_hand"))))))
        val preferred = plan("push", "template-lb").copy(items = listOf(PlanItem("lb", "exercise_3601968b", sets = listOf(SetTarget(reps = 12, load = Load(45.0, "lb", "per_hand"))))))
        val dated = plan("push", "dated", "2026-10-25")
        assertNull(rotationPlans(program, listOf(first, preferred), emptyList(), emptyMap()).first().plan)
        assertEquals(preferred, rotationPlans(program, listOf(first, preferred), emptyList(), mapOf("push" to preferred.id)).first().plan)
        assertEquals(dated, rotationPlans(program, listOf(first, preferred, dated), emptyList(), mapOf("push" to preferred.id)).first().plan)
        val history = listOf(completed(dated, 1), completed(plan("legs"), 2))
        assertEquals(preferred, rotationPlans(program, listOf(first, preferred, dated), history, mapOf("push" to preferred.id)).first().plan)
        assertNull(rotationPlans(program, listOf(first, preferred), history, mapOf("push" to "deleted")).first().plan)
    }
}
