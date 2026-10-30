package top.e404.training
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.e404.training.shared.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
private data class CalendarMonthWatermark(
    val month: YearMonth,
    val firstWeekIndex: Int,
    val lastWeekIndex: Int,
    val text: androidx.compose.ui.text.TextLayoutResult
)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable fun HistoryCalendarScreen(state: AppData, today: LocalDate, viewModel: TrainingViewModel, onDay: (String) -> Unit) {
    val allSessions = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it.value<Session>() } }
    val sessions = remember(allSessions) { allSessions.filter { it.results.isNotEmpty() } }
    val activities = remember(state.documents[Kind.activity]) { state.records(Kind.activity).map { it.value<ActivityRecord>() }.filterNot(ActivityRecord::isXiaomiAutoProjection) }
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
    val coveredHealthDates = remember(state.health.healthStatus?.coveredDates) {
        state.health.healthStatus?.coveredDates.orEmpty().map { LocalDate.parse(it) }
    }
    val actualDates = remember(sessions, activities, state.health.days, coveredHealthDates) {
        sessions.map(::sessionDate) + activities.map { LocalDate.parse(it.localDate) } +
            state.health.days.map { LocalDate.parse(it.localDate) } + coveredHealthDates
    }
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
    LaunchedEffect(visibleMonth) {
        viewModel.refreshHealthDays(visibleMonth.atDay(1), visibleMonth.atEndOfMonth())
    }
    val monthFormatter = remember { DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINA) }
    val months = remember(firstAllowed, lastAllowed) {
        val finalMonth = YearMonth.from(lastAllowed)
        generateSequence(YearMonth.from(firstAllowed)) { month ->
            month.plusMonths(1).takeIf { !it.isAfter(finalMonth) }
        }.toList()
    }
    val watermarkStyle = MaterialTheme.typography.displayLarge.copy(
        fontSize = 150.sp, lineHeight = 150.sp, fontWeight = FontWeight.Black
    )
    val textMeasurer = rememberTextMeasurer()
    // 月份背景固定在所属日期周行的中心；滚动只改变绘制偏移，不重测文字或重算日期。
    val monthWatermarks = remember(months, firstWeek, firstAllowed, lastAllowed, watermarkStyle, textMeasurer) {
        months.map { month ->
            val firstDate = maxOf(firstAllowed, month.atDay(1))
            val lastDate = minOf(lastAllowed, month.atEndOfMonth())
            val firstWeekDate = firstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val lastWeekDate = lastDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            CalendarMonthWatermark(
                month = month,
                firstWeekIndex = java.time.temporal.ChronoUnit.WEEKS.between(firstWeek, firstWeekDate).toInt(),
                lastWeekIndex = java.time.temporal.ChronoUnit.WEEKS.between(firstWeek, lastWeekDate).toInt(),
                text = textMeasurer.measure(AnnotatedString(month.monthValue.toString()), style = watermarkStyle)
            )
        }
    }
    val watermarkInk = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color.White else Color.Black
    val currentWatermarkColor = watermarkInk.copy(alpha = 0.34f)
    val otherWatermarkColor = watermarkInk.copy(alpha = 0.14f)
    val overscrollEffect = rememberOverscrollEffect()
    // 列表处理边缘拖动，外层统一绘制月份与日期，避免只拉伸日期内容。
    val scrollEvents = remember(overscrollEffect) { overscrollEffect?.withoutVisualEffect() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(visibleMonth.format(monthFormatter), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(weekday, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                overscrollEffect = scrollEvents,
                modifier = Modifier.fillMaxSize().overscroll(overscrollEffect).drawWithCache {
                    val rowSpacing = 6.dp.toPx()
                    onDrawBehind {
                        val layout = listState.layoutInfo
                        val anchor = layout.visibleItemsInfo.firstOrNull()
                        if (anchor != null) {
                            val rowStride = anchor.size + rowSpacing
                            val viewportTop = layout.viewportStartOffset.toFloat()
                            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                                monthWatermarks.forEach { watermark ->
                                    val firstRowCenter = anchor.offset - viewportTop + anchor.size / 2f +
                                        (watermark.firstWeekIndex - anchor.index) * rowStride
                                    val lastRowCenter = anchor.offset - viewportTop + anchor.size / 2f +
                                        (watermark.lastWeekIndex - anchor.index) * rowStride
                                    val textTop = (firstRowCenter + lastRowCenter - watermark.text.size.height) / 2f
                                    if (textTop + watermark.text.size.height >= 0f && textTop <= size.height) {
                                        drawText(
                                            watermark.text,
                                            color = if (watermark.month == visibleMonth) currentWatermarkColor else otherWatermarkColor,
                                            topLeft = Offset(
                                                (size.width - watermark.text.size.width) / 2f,
                                                textTop
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(weeks, key = { week -> "week-${week.first()}" }) { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        week.forEach { date ->
                            val selectable = date >= firstAllowed && date <= lastAllowed
                            val dailySessions = sessionsByDate[date].orEmpty()
                            val dailyActivities = activitiesByDate[date].orEmpty()
                            val planned = previews[date].orEmpty()
                            val sessionLabels = dailySessions.singleOrNull()?.let(::sessionTrainingLabels).orEmpty()
                                .map { if (it == "自由锻炼") "自由" else it }
                            val completedSets = dailySessions.singleOrNull()?.results?.size ?: 0
                            val todayCell = date == today
                            val currentMonthCell = date.year == visibleMonth.year && date.monthValue == visibleMonth.monthValue
                            Column(
                                Modifier.weight(1f).height(56.dp)
                                    .background(
                                        when {
                                            todayCell -> MaterialTheme.colorScheme.primaryContainer
                                            currentMonthCell -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        },
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
                                Text(
                                    date.dayOfMonth.toString(),
                                    modifier = if (!currentMonthCell && !todayCell) Modifier.alpha(0.48f) else Modifier,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
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
}

private fun sessionCategoryColor(label: String, colors: ColorScheme) = when {
    label.startsWith("推") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFF81C784 else 0xFF2E7D32)
    label.startsWith("拉") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFF82B1FF else 0xFF1565C0)
    label.startsWith("腿") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFFFFB74D else 0xFFC65D00)
    label.contains("自由") -> androidx.compose.ui.graphics.Color(if (colors.primary.luminance() > 0.4f) 0xFFBDBDBD else 0xFF616161)
    else -> colors.secondary
}


internal fun sessionStatusText(status: String): String = when (status) {
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

internal fun formatActivityDuration(seconds: Int): String = buildString {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainingSeconds = seconds % 60
    if (hours > 0) append("${hours}小时")
    if (minutes > 0) append("${minutes}分钟")
    if (remainingSeconds > 0 || hours == 0 && minutes == 0) append("${remainingSeconds}秒")
}

internal fun formatActivityDistance(meters: Double): String =
    if (meters >= 1000) "${numericText(meters / 1000)}公里" else "${numericText(meters)}米"
