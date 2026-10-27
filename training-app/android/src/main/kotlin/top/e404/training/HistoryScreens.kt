package top.e404.training
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.e404.training.shared.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun syncStatusText(status: SyncStatus): String = when (status) {
    SyncStatus.offline -> "离线"
    SyncStatus.syncing -> "同步中"
    SyncStatus.synced -> "已同步"
    SyncStatus.pending -> "待同步"
    SyncStatus.conflict -> "存在冲突"
    SyncStatus.error -> "同步失败"
}

private fun sessionStatusText(status: String): String = when (status) {
    "active" -> "进行中"
    "completed" -> "已完成"
    "cancelled" -> "已取消"
    else -> "状态未知"
}

private fun Modifier.dashedOutline(color: androidx.compose.ui.graphics.Color): Modifier = drawWithCache {
    val strokeWidth = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    onDrawWithContent {
        drawContent()
        drawRoundRect(color = color, style = Stroke(width = strokeWidth, pathEffect = dash))
    }
}

@Composable fun HistoryCalendarScreen(state: AppData, onDay: (String) -> Unit, onNewActivity: (String) -> Unit) {
    val allSessions = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it.value<Session>() } }
    val sessions = remember(allSessions) { allSessions.filter { it.results.isNotEmpty() } }
    val activities = remember(state.documents[Kind.activity]) { state.records(Kind.activity).map { it.value<ActivityRecord>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val sessionsByDate = remember(sessions) { sessions.groupBy(::sessionDate) }
    val activitiesByDate = remember(activities) { activities.groupBy { LocalDate.parse(it.localDate) } }
    val arrangement = remember(
        state.documents[Kind.arrangement], state.documents[Kind.session],
        state.pendingArrangementMigration, state.arrangementCloudRead
    ) { state.arrangement }
    val programDocument = arrangement?.programId?.let { state.record(Kind.program, it) }
    val program = remember(programDocument) { programDocument?.value<Program>() }
    val today = remember { LocalDate.now(ZoneOffset.ofHours(8)) }
    val previewDays = program?.slots?.size ?: 0
    val override = program?.let { arrangement?.nextOverrides?.get(it.id) }
    val previews = remember(today, previewDays, program, allSessions, plans, override) {
        futureCalendarPlans(today, previewDays, program, allSessions, plans, override)
    }
    val currentMonth = remember(today) { YearMonth.from(today) }
    val actualDates = remember(sessions, activities) { sessions.map(::sessionDate) + activities.map { LocalDate.parse(it.localDate) } }
    val firstDate = remember(actualDates, today) { actualDates.minOrNull()?.coerceAtMost(today) ?: today }
    val lastDate = remember(today, previewDays) { today.plusDays(previewDays.toLong()) }
    val months = remember(firstDate, lastDate) { calendarMonths(YearMonth.from(firstDate), YearMonth.from(lastDate)) }
    val currentMonthIndex = months.indexOf(currentMonth).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentMonthIndex)

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onNewActivity(today.toString()) }, modifier = Modifier.fillMaxWidth()) { Text("记录其他运动") }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(months, key = { "month-$it" }) { month ->
                MonthCalendar(month, sessionsByDate, activitiesByDate, previews, onDay)
            }
        }
    }
}

@Composable private fun MonthCalendar(
    month: YearMonth,
    sessionsByDate: Map<LocalDate, List<Session>>,
    activitiesByDate: Map<LocalDate, List<ActivityRecord>>,
    previews: Map<LocalDate, List<CalendarPlanPreview>>,
    onDay: (String) -> Unit
) {
    val startOffset = month.atDay(1).dayOfWeek.value - 1
    val cellCount = ((startOffset + month.lengthOfMonth() + 6) / 7) * 7
    val dates = (0 until cellCount).map { index ->
        val day = index - startOffset + 1
        if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(month.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINA)), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(weekday, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        }
        dates.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).height(80.dp).padding(1.dp))
                    } else {
                        val dailySessions = sessionsByDate[date].orEmpty()
                        val dailyActivities = activitiesByDate[date].orEmpty()
                        val planned = previews[date].orEmpty()
                        val hasActual = dailySessions.isNotEmpty() || dailyActivities.isNotEmpty()
                        val sessionLabels = dailySessions.map(::sessionTrainingLabel).map { if (it == "自由锻炼") "自由" else it }.distinct()
                        val completedSets = dailySessions.sumOf { it.results.size }
                        Column(
                            Modifier.weight(1f).height(80.dp).padding(1.dp)
                                .background(if (hasActual) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                                .then(if (planned.isNotEmpty()) Modifier.dashedOutline(MaterialTheme.colorScheme.primary) else Modifier)
                                .clickable { onDay(date.toString()) }
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium)
                            if (sessionLabels.isNotEmpty()) {
                                Text(sessionLabels.joinToString("、"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                Text("${completedSets}组", maxLines = 1, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                            if (dailyActivities.isNotEmpty()) {
                                Text(dailyActivities.joinToString("、") { it.name }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                            if (planned.isNotEmpty()) {
                                Text(planned.joinToString("、") { it.shortLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun DayHistoryScreen(
    date: String,
    state: AppData,
    viewModel: TrainingViewModel,
    remove: (Document) -> Unit,
    editActivity: (Document) -> Unit,
    addActivity: (String) -> Unit
) {
    val day = LocalDate.parse(date)
    val sessionRecords = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it to it.value<Session>() } }
    val allSessions = remember(sessionRecords) { sessionRecords.map { it.second } }
    val sessions = remember(sessionRecords, day) {
        sessionRecords.filter { (_, session) -> sessionDate(session) == day && session.results.isNotEmpty() }
            .sortedWith(compareBy(sessionComparator) { it.second })
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
    val today = remember { LocalDate.now(ZoneOffset.ofHours(8)) }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val override = program?.let { arrangement?.nextOverrides?.get(it.id) }
    val previewByDate = remember(today, program, allSessions, plans, override) {
        futureCalendarPlans(today, program?.slots?.size ?: 0, program, allSessions, plans, override)
    }
    val previews = previewByDate[day].orEmpty()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$date 运动记录", style = MaterialTheme.typography.headlineSmall)
        if (previews.isNotEmpty()) InfoCard("未来计划预览 · 不计入实际记录") {
            previews.forEach { Text(it.fullName) }
        }
        if (sessions.isEmpty() && activities.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("这一天没有实际运动记录。") }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(sessions, key = { "session-${it.first.id}" }) { (document, session) ->
                var editingResult by remember { mutableStateOf<SetResult?>(null) }
                val dirty = state.documentSync["${document.kind.name}/${document.id}"]?.dirty == true
                val conflict = state.documentSync["${document.kind.name}/${document.id}"]?.conflict == true
                val startedAt = session.startedAt
                val snapshot = session.snapshot
                InfoCard(sessionTrainingLabel(session)) {
                    Text(when {
                        conflict -> "同步冲突 · 本机记录保留"
                        dirty -> "本机待上传"
                        state.syncStatus == SyncStatus.synced -> "云端已同步"
                        else -> "本机保存副本 · ${syncStatusText(state.syncStatus)}"
                    }, color = if (dirty || conflict) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
                    if (startedAt != null) Text("开始：${localDateTime(startedAt)}")
                    else Text("训练日期：${session.historicalSource?.localDate ?: date} · 未记录具体时间")
                    session.endedAt?.let { Text("结束：${localDateTime(it)}") }
                    Text("状态：${sessionStatusText(session.status)}")
                    if (snapshot != null) Text("开始时计划：${snapshot.name}") else Text("开始时计划：未记录")
                    session.items.forEach { item ->
                        val exercise = session.exercises.firstOrNull { it.id == item.exerciseId }
                        val results = session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }
                        if (results.isNotEmpty()) {
                            HorizontalDivider()
                            Text(exercise?.name ?: "动作", style = MaterialTheme.typography.titleMedium)
                            results.forEach { result ->
                                val completedAt = result.completedAt?.let(::localDateTime)?.let { " · $it" }.orEmpty()
                                Text("第 ${result.setIndex + 1} 组$completedAt：${resultText(result)}")
                                if (session.status != "active") TextButton(onClick = { editingResult = result }) { Text("修改重量") }
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
                val dirty = state.documentSync["${document.kind.name}/${document.id}"]?.dirty == true
                val conflict = state.documentSync["${document.kind.name}/${document.id}"]?.conflict == true
                InfoCard(activity.name) {
                    Text(when {
                        conflict -> "同步冲突 · 本机记录保留"
                        dirty -> "本机待上传"
                        state.syncStatus == SyncStatus.synced -> "云端已同步"
                        else -> "本机保存副本 · ${syncStatusText(state.syncStatus)}"
                    }, color = if (dirty || conflict) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
                    Text("日期：${activity.localDate}")
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
