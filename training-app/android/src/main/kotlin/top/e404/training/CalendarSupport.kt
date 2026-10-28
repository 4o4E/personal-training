package top.e404.training

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import top.e404.training.shared.ArrangementOverride
import top.e404.training.shared.Load
import top.e404.training.shared.Plan
import top.e404.training.shared.Program
import top.e404.training.shared.Session
import top.e404.training.shared.SetResult
import top.e404.training.shared.nextSlot
import top.e404.training.shared.resolveTrainingPlan
import top.e404.training.shared.recordedPlanIds
import top.e404.training.shared.splitTrainingLabel

private val historyTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    .withZone(ZoneOffset.ofHours(8))

private fun historyTime(timestamp: Long): String =
    historyTimeFormatter.format(Instant.ofEpochMilli(timestamp))

internal fun historyTimeRange(startedAt: Long?, endedAt: Long?): String? {
    if (startedAt == null && endedAt == null) return null
    return "${startedAt?.let(::historyTime) ?: "--:--"} - " +
        (endedAt?.let(::historyTime) ?: "--:--")
}

internal fun historyResultText(result: SetResult): String = buildString {
    if (result.segments.isEmpty()) {
        appendHistoryValues(result.load, result.reps, result.seconds)
    } else {
        result.segments.forEachIndexed { index, segment ->
            if (index > 0) append("，")
            appendHistoryValues(segment.load, segment.reps, null)
        }
    }
    when (result.side) {
        "left" -> appendHistoryQualifier("左侧")
        "right" -> appendHistoryQualifier("右侧")
    }
    result.completedAt?.let { appendHistoryQualifier(historyTime(it)) }
}

private fun StringBuilder.appendHistoryValues(load: Load?, reps: Int?, seconds: Int?) {
    load?.let { append(numericText(it.value)).append(' ').append(it.unit) }
    reps?.let {
        if (load != null) append(" × ")
        append(it).append(" 次")
    }
    seconds?.let {
        if (load != null || reps != null) append(" · ")
        append(it).append(" 秒")
    }
    if (load != null) {
        when (load.meaning) {
            "per_hand" -> appendHistoryQualifier("单只")
            "per_side" -> appendHistoryQualifier("单侧")
            "unknown" -> Unit
            else -> appendHistoryQualifier("总重量")
        }
        if (load.mode == "assistance") appendHistoryQualifier("助力")
    }
}

private fun StringBuilder.appendHistoryQualifier(value: String) {
    if (isNotEmpty()) append(" · ")
    append(value)
}


internal data class CalendarPlanPreview(val shortLabel: String, val fullName: String, val targetSets: Int?)

internal fun futureCalendarPlans(
    today: LocalDate,
    days: Int,
    program: Program?,
    sessions: List<Session>,
    plans: List<Plan>,
    selectedPlans: Map<String, String>,
    override: ArrangementOverride?
): Map<LocalDate, List<CalendarPlanPreview>> {
    if (program == null || program.slots.isEmpty() || days <= 0) return emptyMap()

    val executedPlanIds = recordedPlanIds(sessions)
    val plansByDate = plans.asSequence()
        .filter { it.id !in executedPlanIds && it.date != null }
        .groupBy { LocalDate.parse(requireNotNull(it.date)) }
        .mapValues { (_, datePlans) -> datePlans.sortedWith(compareBy<Plan> { it.name }.thenBy { it.id }) }
    val previews = linkedMapOf<LocalDate, List<CalendarPlanPreview>>()
    var nextSlot = nextSlot(program, sessions, override)
    val todayDate = today.toString()
    val startsToday = sessions.none { session ->
        session.localDate == todayDate && session.results.isNotEmpty()
    }
    val firstOffset = if (startsToday) 0 else 1

    for (offset in firstOffset until firstOffset + days) {
        val date = today.plusDays(offset.toLong())
        val explicit = plansByDate[date].orEmpty()
        if (explicit.isEmpty()) {
            val plan = resolveTrainingPlan(program, nextSlot, plans, sessions, selectedPlans, date)
            val slotLabel = splitTrainingLabel(nextSlot.name) ?: nextSlot.name
            val shortLabel = if (slotLabel == "自由锻炼") "自由" else slotLabel
            previews[date] = listOf(
                CalendarPlanPreview(
                    shortLabel,
                    nextSlot.name,
                    plan?.items?.sumOf { it.sets.size }
                )
            )
            val index = program.slots.indexOfFirst { it.id == nextSlot.id }
            nextSlot = program.slots[(index + 1) % program.slots.size]
            continue
        }

        previews[date] = explicit.map { plan ->
            val slot = program.slots.singleOrNull { it.id == plan.slotId }
                ?.takeIf { plan.programId == program.id }
            val resolved = slot?.let { resolveTrainingPlan(program, it, plans, sessions, selectedPlans, date) } ?: plan
            val slotLabel = slot?.let { splitTrainingLabel(it.name) ?: it.name }
            val planLabel = splitTrainingLabel(plan.name) ?: plan.name
            val shortLabel = (slotLabel ?: planLabel).let { if (it == "自由锻炼") "自由" else it }
            CalendarPlanPreview(
                shortLabel,
                slot?.let { "${it.name} · ${resolved.name}" } ?: resolved.name,
                resolved.items.sumOf { it.sets.size }
            )
        }

        val explicitSlotIds = explicit.mapNotNull { plan ->
            plan.slotId?.takeIf { plan.programId == program.id && program.slots.any { slot -> slot.id == it } }
        }.distinct()
        val allUseCurrentProgram = explicit.all { plan ->
            val slotId = plan.slotId
            plan.programId == program.id && slotId != null && program.slots.any { it.id == slotId }
        }
        if (allUseCurrentProgram && explicitSlotIds.size == 1) {
            val index = program.slots.indexOfFirst { it.id == explicitSlotIds.single() }
            nextSlot = program.slots[(index + 1) % program.slots.size]
        }
    }
    return previews
}
