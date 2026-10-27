package top.e404.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.e404.training.shared.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
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

@Composable fun HistoryCalendarScreen(state: AppData, onDay: (String) -> Unit) {
    var displayedMonth by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(displayedMonth)
    val sessionsByDate = state.records(Kind.session).map { it.value<Session>() }
        .filter { it.results.isNotEmpty() }
        .groupBy(::sessionDate)
    val startOffset = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val endPadding = (7 - ((startOffset + month.lengthOfMonth()) % 7)) % 7
    val cells = List<LocalDate?>(startOffset) { null } + (1..month.lengthOfMonth()).map(month::atDay) + List<LocalDate?>(endPadding) { null }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { displayedMonth = month.minusMonths(1).toString() }) { Text("上月") }
            Text(month.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINA)), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { displayedMonth = month.plusMonths(1).toString() }) { Text("下月") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(day, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { date ->
                        Box(Modifier.weight(1f).height(80.dp).padding(1.dp)) {
                            if (date != null) {
                                val daily = sessionsByDate[date].orEmpty()
                                val isSelected = selectedDate == date.toString()
                                Column(Modifier.fillMaxSize().background(
                                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small
                                ).clickable {
                                    selectedDate = date.toString()
                                    onDay(date.toString())
                                }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium)
                                    if (daily.isNotEmpty()) {
                                        val labels = daily.map(::sessionTrainingLabel).distinct()
                                        val completedSets = daily.sumOf { it.results.size }
                                        Text(labels.joinToString("、"), maxLines = 2, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                        Text("${completedSets}组", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun DayHistoryScreen(date: String, state: AppData, viewModel: TrainingViewModel, remove: (Document) -> Unit) {
    val day = LocalDate.parse(date)
    val sessions = state.records(Kind.session).map { it to it.value<Session>() }
        .filter { (_, session) -> sessionDate(session) == day && session.results.isNotEmpty() }
        .sortedWith(compareBy(sessionComparator) { it.second })
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("$date 训练记录", style = MaterialTheme.typography.headlineSmall)
        if (sessions.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("这一天没有训练记录。") }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(sessions, key = { it.first.id }) { (document, session) ->
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
                        val exerciseName = session.exercises.firstOrNull { it.id == item.exerciseId }?.name ?: "动作"
                        val results = session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }
                        if (results.isNotEmpty()) {
                            HorizontalDivider()
                            Text(exerciseName, style = MaterialTheme.typography.titleMedium)
                            results.forEach { result ->
                                val completedAt = result.completedAt?.let(::localDateTime)?.let { " · $it" }.orEmpty()
                                Text("第 ${result.setIndex + 1} 组$completedAt：${resultText(result)}")
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
            }
        }
    }
}
