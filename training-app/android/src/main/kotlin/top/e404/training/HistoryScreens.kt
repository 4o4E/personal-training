package top.e404.training
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.e404.training.shared.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable fun HistoryCalendarScreen(state: AppData, today: LocalDate, viewModel: TrainingViewModel, onDay: (String) -> Unit) {
    var showingHealth by rememberSaveable { mutableStateOf(false) }
    var showingConflicts by rememberSaveable { mutableStateOf(false) }
    val allSessions = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it.value<Session>() } }
    val sessions = remember(allSessions) { allSessions.filter { it.results.isNotEmpty() } }
    val activities = remember(state.documents[Kind.activity]) { state.records(Kind.activity).map { it.value<ActivityRecord>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val sessionsByDate = remember(sessions) {
        sessions.groupBy(::sessionDate).mapValues { (_, dailySessions) -> dailySessions.singleOrNull()?.let(::listOf).orEmpty() }
    }
    val activitiesByDate = remember(activities) { activities.groupBy { LocalDate.parse(it.localDate) } }
    val arrangement = remember(
        state.documents[Kind.arrangement], state.documents[Kind.session],
        state.pendingArrangementMigration, state.arrangementCloudRead
    ) { state.arrangement }
    val programDocument = arrangement?.programId?.let { state.record(Kind.program, it) }
    val program = remember(programDocument) { programDocument?.value<Program>() }
    val previewDays = program?.slots?.size ?: 0
    val selectedPlans = program?.let { arrangement?.selectedPlans?.get(it.id).orEmpty() }.orEmpty()
    val override = program?.let { arrangement?.nextOverrides?.get(it.id) }
    val previews = remember(today, previewDays, program, allSessions, plans, selectedPlans, override) {
        futureCalendarPlans(today, previewDays, program, allSessions, plans, selectedPlans, override)
    }
    val actualDates = remember(sessions, activities) { sessions.map(::sessionDate) + activities.map { LocalDate.parse(it.localDate) } }
    val firstAllowed = remember(actualDates, today) {
        YearMonth.from(actualDates.minOrNull()?.coerceAtMost(today) ?: today).atDay(1)
    }
    val lastAllowedDate = remember(today, previews) { previews.keys.maxOrNull()?.coerceAtLeast(today) ?: today }
    val lastAllowed = remember(lastAllowedDate) { YearMonth.from(lastAllowedDate).atEndOfMonth() }
    val firstWeek = remember(firstAllowed) { firstAllowed.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val lastWeek = remember(lastAllowed) { lastAllowed.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)) }
    val weeks = remember(firstWeek, lastWeek) {
        generateSequence(firstWeek) { previous -> previous.plusWeeks(1).takeIf { !it.isAfter(lastWeek) } }
            .map { start -> (0L..6L).map(start::plusDays) }
            .toList()
    }
    val todayWeek = remember(weeks, today) { weeks.indexOfFirst { today in it }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = todayWeek)
    val visibleMonth by remember(weeks, firstAllowed, lastAllowed, listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            val visibleWeekIndex = layout.visibleItemsInfo
                .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - viewportCenter) }
                ?.index ?: listState.firstVisibleItemIndex
            val week = weeks.getOrNull(visibleWeekIndex) ?: weeks.first()
            YearMonth.from(week[3].coerceIn(firstAllowed, lastAllowed))
        }
    }
    val monthFormatter = remember { DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINA) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HealthSyncCard(state, viewModel) { showingConflicts = true }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !showingHealth, onClick = { showingHealth = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("训练记录") }
            SegmentedButton(selected = showingHealth, onClick = { showingHealth = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("健康数据") }
        }
        if (showingHealth) {
            Box(Modifier.weight(1f).fillMaxWidth()) { HealthHistoryScreen(state, viewModel, today) }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(visibleMonth.format(monthFormatter), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(weekday, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(weeks, key = { week -> "week-${week.first()}" }) { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        val selectable = date >= firstAllowed && date <= lastAllowed
                        val dailySessions = sessionsByDate[date].orEmpty()
                        val dailyActivities = activitiesByDate[date].orEmpty()
                        val planned = previews[date].orEmpty()
                        val sessionLabels = dailySessions.singleOrNull()?.let(::sessionTrainingLabels).orEmpty()
                            .map { if (it == "自由锻炼") "自由" else it }
                        val completedSets = dailySessions.singleOrNull()?.results?.size ?: 0
                        val todayCell = date == today
                        Column(
                            Modifier.weight(1f).height(64.dp).padding(1.dp)
                                .background(
                                    if (todayCell) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small
                                )
                                .then(
                                    if (planned.isNotEmpty()) Modifier.calendarOutline(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small, dashed = true)
                                    else if (todayCell) Modifier.calendarOutline(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small, dashed = false)
                                    else Modifier
                                )
                                .then(if (selectable) Modifier.clickable { onDay(date.toString()) } else Modifier)
                                .then(if (selectable) Modifier else Modifier.alpha(0.38f))
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            val colors = MaterialTheme.colorScheme
                            val summary = androidx.compose.ui.text.buildAnnotatedString {
                                var hasSummary = false
                                fun appendSummary(label: String, category: String) {
                                    if (hasSummary) append(" · ")
                                    val color = if (category.isNotEmpty()) sessionCategoryColor(category, colors) else colors.secondary
                                    pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
                                    append(label)
                                    pop()
                                    hasSummary = true
                                }
                                sessionLabels.forEach { appendSummary(it, it) }
                                dailyActivities.forEach { appendSummary(it.name, "") }
                                planned.forEach { appendSummary(it.shortLabel, it.shortLabel) }
                            }
                            Text(
                                summary,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                            val targetSets = planned.mapNotNull { it.targetSets }
                            val groupLabel = when {
                                completedSets > 0 -> "${completedSets}组"
                                targetSets.isNotEmpty() -> "${targetSets.sum()}组"
                                else -> ""
                            }
                            Text(groupLabel, maxLines = 1, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
            }
}
    if (showingConflicts) ConflictResolutionDialog(state, viewModel) { showingConflicts = false }
}

private fun sessionCategoryColor(label: String, colors: ColorScheme) = when {
    label.startsWith("推") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFF81C784 else 0xFF2E7D32)
    label.startsWith("拉") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFF82B1FF else 0xFF1565C0)
    label.startsWith("腿") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFFFFB74D else 0xFFC65D00)
    label.contains("自由") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFFBDBDBD else 0xFF616161)
    else -> colors.secondary
}

@Composable fun DayHistoryDialog(
    date: String,
    today: LocalDate,
    state: AppData,
    viewModel: TrainingViewModel,
    onDismiss: () -> Unit,
    remove: (Document) -> Unit,
    editActivity: (Document) -> Unit
) {
    val day = LocalDate.parse(date)
    val sessionRecords = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it to it.value<Session>() } }
    val allSessions = remember(sessionRecords) { sessionRecords.map { it.second } }
    val sessions = remember(sessionRecords, day) {
        sessionRecords.filter { (_, session) -> sessionDate(session) == day && session.results.isNotEmpty() }
            .singleOrNull()?.let(::listOf).orEmpty()
    }
    val activityRecords = remember(state.documents[Kind.activity]) { state.records(Kind.activity).map { it to it.value<ActivityRecord>() } }
    val activities = remember(activityRecords, day) {
        activityRecords.filter { (_, activity) -> LocalDate.parse(activity.localDate) == day }
            .sortedWith(compareBy({ it.second.name }, { it.first.id }))
    }
    val arrangement = remember(
        state.documents[Kind.arrangement], state.documents[Kind.session],
        state.pendingArrangementMigration, state.arrangementCloudRead
    ) { state.arrangement }
    val programDocument = arrangement?.programId?.let { state.record(Kind.program, it) }
    val program = remember(programDocument) { programDocument?.value<Program>() }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val selectedPlans = program?.let { arrangement?.selectedPlans?.get(it.id).orEmpty() }.orEmpty()
    val override = program?.let { arrangement?.nextOverrides?.get(it.id) }
    val previewByDate = remember(today, program, allSessions, plans, selectedPlans, override) {
        futureCalendarPlans(today, program?.slots?.size ?: 0, program, allSessions, plans, selectedPlans, override)
    }
    val previews = previewByDate[day].orEmpty()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * 0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$date 运动记录", style = MaterialTheme.typography.titleLarge)
                    if (previews.isNotEmpty()) InfoCard("待训练计划 · 不计入实际记录") {
                        previews.forEach { Text(it.fullName) }
                    }
                    if (sessions.isEmpty() && activities.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { Text("这一天没有实际运动记录。") }
                    } else LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(sessions, key = { "session-${it.first.id}" }) { (document, session) ->
                            var editingResult by remember { mutableStateOf<SetResult?>(null) }
                            val startedAt = session.startedAt
                            val snapshot = session.snapshot
                            InfoCard(sessionTrainingLabels(session).joinToString("、")) {
                                historyTimeRange(startedAt, session.endedAt)?.let { Text(it) }
                                Text("状态：${sessionStatusText(session.status)}")
                                if (snapshot != null) Text("开始时计划：${snapshot.name}") else Text("开始时计划：未记录")
                                session.items.forEach { item ->
                                    val exercise = session.exercises.firstOrNull { it.id == item.exerciseId }
                                    val results = session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }
                                    if (results.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text(exercise?.name ?: "动作", style = MaterialTheme.typography.titleMedium)
                                        results.forEach { result ->
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text(historyResultText(result), Modifier.weight(1f))
                                                if (session.status != "active") {
                                                    IconButton(onClick = { editingResult = result }) {
                                                        Icon(Icons.Default.Edit, contentDescription = "修改第 ${result.setIndex + 1} 组重量")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (session.notes.isNotBlank()) Text("反馈：${session.notes}")
                                Text("实际完成 ${session.results.size} 组")
                                if (session.status != "active") {
                                    TextButton(onClick = { remove(document) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                        Text("删除这次记录")
                                    }
                                }
                            }
                            editingResult?.let { result ->
                                val item = session.items.single { it.id == result.itemId }
                                val exercise = session.exercises.single { it.id == item.exerciseId }
                                HistoricalWeightEditor(result, exercise, viewModel, { editingResult = null }) { loads ->
                                    viewModel.repository.updateHistoricalResultLoads(session.id, result.itemId, result.setIndex, loads)
                                }
                            }
                        }
                        items(activities, key = { "activity-${it.first.id}" }) { (document, activity) ->
                            InfoCard(activity.name) {
                                activityTimeRange(activity)?.let { Text(it) }
                                activity.durationSeconds?.let { Text("时长：${formatActivityDuration(it)}") }
                                activity.distanceMeters?.let { Text("距离：${formatActivityDistance(it)}") }
                                if (activity.notes.isNotBlank()) Text("备注：${activity.notes}")
                                Row {
                                    TextButton(onClick = { editActivity(document) }) { Text("编辑") }
                                    TextButton(onClick = { remove(document) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sessionStatusText(status: String): String = when (status) {
    "active" -> "进行中"
    "completed" -> "已完成"
    "cancelled" -> "已取消"
    else -> "状态未知"
}

private fun Modifier.calendarOutline(color: androidx.compose.ui.graphics.Color, shape: Shape, dashed: Boolean): Modifier = drawWithCache {
    val strokeWidth = 1.dp.toPx()
    val inset = strokeWidth / 2
    val outline = shape.createOutline(
        androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
        layoutDirection,
        this
    )
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null
    val stroke = Stroke(width = strokeWidth, pathEffect = effect)
    onDrawWithContent {
        drawContent()
        drawContext.transform.translate(inset, inset)
        drawOutline(outline, color = color, style = stroke)
        drawContext.transform.translate(-inset, -inset)
    }
}
private fun activityTimeRange(activity: ActivityRecord): String? {
    val start = activity.startedAt
    val end = activity.endedAt
    if (start == null && end == null) return null
    val zone = java.time.ZoneId.of("Asia/Shanghai")
    val startDateTime = start?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
    val endDateTime = end?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
    return when {
        startDateTime != null && endDateTime != null && startDateTime.toLocalDate() == endDateTime.toLocalDate() ->
            "运动区间：${startDateTime.toLocalDate()} ${startDateTime.toLocalTime().toString().take(5)} — ${endDateTime.toLocalTime().toString().take(5)}"
        startDateTime != null && endDateTime != null -> "运动区间：${localDateTime(start!!)} — ${localDateTime(end!!)}"
        start != null -> "开始：${localDateTime(start)}"
        else -> "结束：${localDateTime(end!!)}"
    }
}



private fun formatActivityDuration(seconds: Int): String = buildString {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainingSeconds = seconds % 60
    if (hours > 0) append("${hours}小时")
    if (minutes > 0) append("${minutes}分钟")
    if (remainingSeconds > 0 || hours == 0 && minutes == 0) append("${remainingSeconds}秒")
}

private fun formatActivityDistance(meters: Double): String =
    if (meters >= 1000) "${numericText(meters / 1000)}公里" else "${numericText(meters)}米"
