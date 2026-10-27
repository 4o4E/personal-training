package top.e404.training

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import top.e404.training.shared.ArrangementOverride
import top.e404.training.shared.Plan
import top.e404.training.shared.Program
import top.e404.training.shared.Session
import top.e404.training.shared.nextSlot
import top.e404.training.shared.splitTrainingLabel

internal data class CalendarPlanPreview(val shortLabel: String, val fullName: String)

internal fun calendarMonths(first: YearMonth, last: YearMonth): List<YearMonth> =
    if (last.isBefore(first)) emptyList() else (0L..first.until(last, ChronoUnit.MONTHS)).map(first::plusMonths)

internal fun futureCalendarPlans(
    today: LocalDate,
    days: Int,
    program: Program?,
    sessions: List<Session>,
    plans: List<Plan>,
    override: ArrangementOverride?
): Map<LocalDate, List<CalendarPlanPreview>> {
    if (program == null || days <= 0) return emptyMap()

    val executedPlanIds = sessions.mapNotNull { it.snapshot?.id }.toSet()
    val plansByDate = plans.asSequence()
        .filter { it.id !in executedPlanIds && it.date != null }
        .groupBy { LocalDate.parse(requireNotNull(it.date)) }
        .mapValues { (_, datePlans) -> datePlans.sortedWith(compareBy<Plan> { it.name }.thenBy { it.id }) }
    val programSlotIds = program.slots.map { it.id }.toSet()
    val previews = linkedMapOf<LocalDate, List<CalendarPlanPreview>>()
    var nextSlot = nextSlot(program, sessions, override)

    for (offset in 1..days) {
        val date = today.plusDays(offset.toLong())
        val explicit = plansByDate[date].orEmpty()
        if (explicit.isEmpty()) {
            previews[date] = listOf(CalendarPlanPreview(splitTrainingLabel(nextSlot.name) ?: nextSlot.name, nextSlot.name))
            val index = program.slots.indexOfFirst { it.id == nextSlot.id }
            nextSlot = program.slots[(index + 1) % program.slots.size]
            continue
        }

        previews[date] = explicit.map { plan ->
            val slot = program.slots.singleOrNull { it.id == plan.slotId }?.takeIf { plan.programId == program.id }
            val shortLabel = slot?.let { splitTrainingLabel(it.name) ?: it.name }
                ?: splitTrainingLabel(plan.name) ?: plan.name
            CalendarPlanPreview(shortLabel, slot?.let { "${it.name} · ${plan.name}" } ?: plan.name)
        }

        val explicitSlotIds = explicit.mapNotNull { plan ->
            plan.slotId?.takeIf { plan.programId == program.id && it in programSlotIds }
        }.distinct()
        val allUseCurrentProgram = explicit.all { plan ->
            val slotId = plan.slotId
            plan.programId == program.id && slotId != null && slotId in programSlotIds
        }
        if (allUseCurrentProgram && explicitSlotIds.size == 1) {
            val index = program.slots.indexOfFirst { it.id == explicitSlotIds.single() }
            nextSlot = program.slots[(index + 1) % program.slots.size]
        }
    }
    return previews
}
