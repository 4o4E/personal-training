package top.e404.training

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.e404.training.shared.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val dayEditorZone = ZoneId.of("Asia/Shanghai")
private fun dayField(document: Document, field: String) = "${document.kind}/${document.id}/$field"
private fun nullableText(value: Any?) = value?.toString().orEmpty()
private fun dayDocumentKey(document: Document) = "${document.kind.name}/${document.id}"
private fun parseRequiredInt(value: String, label: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("${label}须为非负整数")
private fun draftOptionalInt(fields: Map<String, String>, document: Document, field: String, label: String, original: Int?): Int? {
    val key = dayField(document, field)
    return if (!fields.containsKey(key)) original else fields[key]?.trim()?.takeIf(String::isNotEmpty)?.let { parseRequiredInt(it, label) }
}
private fun parseOptionalDouble(value: String, label: String): Double? = value.trim().takeIf(String::isNotEmpty)?.let {
    it.toDoubleOrNull()?.takeIf(Double::isFinite)?.also { number -> require(number >= 0) { "${label}不能为负数" } } ?: throw IllegalArgumentException("${label}须为有效数字")
}
private fun showInstant(value: Long?): String = value?.let {
    Instant.ofEpochMilli(it).atZone(dayEditorZone).toLocalDateTime().toString().replace('T', ' ')
}.orEmpty()
private fun parseInstant(value: String): Long? = value.trim().takeIf(String::isNotEmpty)?.let {
    LocalDateTime.parse(it.replace(' ', 'T')).atZone(dayEditorZone).toInstant().toEpochMilli()
}
private fun activityHistoryTimeRange(activity: ActivityRecord): String? {
    val start = activity.startedAt?.let { Instant.ofEpochMilli(it).atZone(dayEditorZone).toLocalDateTime() }
    val end = activity.endedAt?.let { Instant.ofEpochMilli(it).atZone(dayEditorZone).toLocalDateTime() }
    if (start == null && end == null) return null
    val day = LocalDate.parse(activity.localDate)
    val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val full = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    fun format(value: LocalDateTime) = (if (value.toLocalDate() == day) time else full).format(value)
    return when {
        start != null && end != null -> "运动区间：${format(start)} — ${format(end)}"
        start != null -> "开始：${format(start)}"
        else -> "结束：${format(end!!)}"
    }
}

private fun initialDayDrafts(documents: List<Document>): Map<String, String> = buildMap {
    documents.forEach { doc ->
        when (doc.kind) {
            Kind.session -> {
                val session = doc.value<Session>()
                put(dayField(doc, "notes"), session.notes)
                session.results.forEach { result ->
                    val base = "result-${result.itemId}-${result.setIndex}"
                    put(dayField(doc, "$base/reps"), nullableText(result.reps))
                    put(dayField(doc, "$base/seconds"), nullableText(result.seconds))
                    val parts = result.segments.ifEmpty { listOf(SetSegment(result.reps ?: 0, result.load)) }
                    parts.forEachIndexed { index, part ->
                        put(dayField(doc, "$base/$index/reps"), part.reps.toString())
                        put(dayField(doc, "$base/$index/load"), nullableText(part.load?.value))
                        put(dayField(doc, "$base/$index/unit"), part.load?.unit.orEmpty())
                    }
                }
            }
            Kind.activity -> {
                val activity = doc.value<ActivityRecord>()
                put(dayField(doc, "name"), activity.name)
                put(dayField(doc, "localDate"), activity.localDate)
                put(dayField(doc, "start"), showInstant(activity.startedAt))
                put(dayField(doc, "end"), showInstant(activity.endedAt))
                put(dayField(doc, "duration"), activity.durationSeconds?.let { numericText(it / 60.0) }.orEmpty())
                put(dayField(doc, "distance"), activity.distanceMeters?.let { numericText(it / 1000.0) }.orEmpty())
                put(dayField(doc, "notes"), activity.notes)
            }
            else -> Unit
        }
    }
}

@Composable
internal fun DayHistoryDialog(date: String, today: LocalDate, state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    val day = LocalDate.parse(date)
    val allSessions = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it.value<Session>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val arrangement = remember(state.documents[Kind.arrangement], state.documents[Kind.session], state.pendingArrangementMigration, state.arrangementCloudRead) { state.arrangement }
    val programDocument = arrangement?.programId?.let { state.record(Kind.program, it) }
    val program = remember(programDocument) { programDocument?.value<Program>() }
    val selectedPlans = program?.let { arrangement?.selectedPlans?.get(it.id).orEmpty() }.orEmpty()
    val override = program?.let { arrangement?.nextOverrides?.get(it.id) }
    val previews = remember(day, today, program, allSessions, plans, selectedPlans, override) {
        if (day.isBefore(today)) emptyList()
        else futureCalendarPlans(today, ChronoUnit.DAYS.between(today, day).toInt() + 1, program, allSessions, plans, selectedPlans, override)[day].orEmpty()
    }
    LaunchedEffect(day) { viewModel.selectHealthDate(day) }
    val currentDocuments = remember(state.documents, day) {
        (state.records(Kind.session).filter { !it.deleted && it.value<Session>().let { it.results.isNotEmpty() && sessionDate(it) == day } } +
            state.records(Kind.activity).filter { !it.deleted && it.value<ActivityRecord>().let { activity -> !activity.isXiaomiAutoProjection() && activity.localDate == date } })
    }
    var editing by remember(date) { mutableStateOf(false) }
    var originals by remember(date) { mutableStateOf(emptyList<Document>()) }
    var drafts by remember(date) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var removed by remember(date) { mutableStateOf(emptySet<String>()) }
    var saving by remember(date) { mutableStateOf(false) }
    var discardPrompt by remember(date) { mutableStateOf(false) }
    var expandedHealth by remember(date) { mutableStateOf<String?>(null) }
    var expandedHealthDetails by remember(date) { mutableStateOf(false) }
    var errorMessage by remember(date) { mutableStateOf<String?>(null) }
    val visibleDocuments = if (editing) originals else currentDocuments
    val dirty = remember(drafts, removed, originals) { originals.any { dayDocumentKey(it) in removed || docChanged(it, drafts) } }
    fun update(doc: Document, field: String, value: String) { drafts = drafts + (dayField(doc, field) to value); errorMessage = null }
    fun beginEditing() {
        originals = currentDocuments.toList()
        drafts = initialDayDrafts(originals)
        removed = emptySet()
        editing = true
        errorMessage = null
    }
    fun abandonEditing() {
        editing = false
        originals = emptyList()
        drafts = emptyMap()
        removed = emptySet()
        errorMessage = null
    }
    Dialog(onDismissRequest = { if (saving) Unit else if (editing && dirty) discardPrompt = true else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.systemBarsPadding().imePadding()) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * 0.9f).padding(horizontal = 16.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$date 运动记录", style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                    healthDateDetails(state, day, expandedHealthDetails, { expandedHealthDetails = it }, expandedHealth) { expandedHealth = it }
                    previews.forEachIndexed { index, preview ->
                        item(key = "preview-$index") { Text("待训练计划 · ${preview.fullName}", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(visibleDocuments, key = { dayDocumentKey(it) }) { original ->
                        if (dayDocumentKey(original) in removed) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${if (original.kind == Kind.session) "训练记录" else "运动记录"}（待删除）", Modifier.weight(1f))
                                if (editing && !saving) TextButton(onClick = { removed = removed - dayDocumentKey(original) }) { Text("撤销删除") }
                            }
                        } else when (original.kind) {
                            Kind.session -> SessionDraftCard(original, drafts, state.health, editing && !saving, ::update, onDelete = { removed = removed + dayDocumentKey(original) })
                            Kind.activity -> ActivityDraftCard(original, drafts, state.health, editing && !saving, ::update, onDelete = { removed = removed + dayDocumentKey(original) })
                            else -> Unit
                        }
                    }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (editing) {
                        TextButton(enabled = !saving, onClick = { abandonEditing() }) { Text("取消") }
                        TextButton(enabled = !saving, onClick = {
                            try {
                                val changes = originals.mapNotNull { doc ->
                                    if (dayDocumentKey(doc) in removed) doc.copy(deleted = true)
                                    else buildChangedDocument(doc, drafts)
                                }
                                saving = true
                                viewModel.saveDayHistory(originals, changes, {
                                    saving = false
                                    abandonEditing()
                                }, { message -> saving = false; errorMessage = message })
                            } catch (failure: IllegalArgumentException) {
                                saving = false; errorMessage = failure.message ?: "请检查输入内容"
                            } catch (failure: java.time.format.DateTimeParseException) {
                                saving = false; errorMessage = failure.message ?: "日期格式无效"
                            }
                        }) { Text(if (saving) "保存中…" else "保存") }
                    } else if (currentDocuments.any { doc -> doc.kind == Kind.activity || doc.value<Session>().status != "active" }) {
                        TextButton(onClick = { beginEditing() }) { Text("修改") }
                }
            }
        }
        }
    }
    }
    if (discardPrompt) AlertDialog(onDismissRequest = { discardPrompt = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("当前输入和删除标记都将丢弃。") },
        confirmButton = { TextButton(onClick = { discardPrompt = false; abandonEditing(); onDismiss() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discardPrompt = false }) { Text("继续编辑") } })
}

private fun docChanged(doc: Document, fields: Map<String, String>) = when (doc.kind) {
    Kind.session -> {
        val session = doc.value<Session>()
        fields[dayField(doc, "notes")] != session.notes || session.results.any { result ->
            val base = "result-${result.itemId}-${result.setIndex}"
            if (result.segments.isEmpty()) {
                fields[dayField(doc, "$base/reps")] != nullableText(result.reps) ||
                    fields[dayField(doc, "$base/seconds")] != nullableText(result.seconds) ||
                    fields[dayField(doc, "$base/0/load")] != nullableText(result.load?.value) ||
                    fields[dayField(doc, "$base/0/unit")] != result.load?.unit.orEmpty()
            } else result.segments.indices.any { index ->
                val segment = result.segments[index]
                fields[dayField(doc, "$base/$index/reps")] != segment.reps.toString() ||
                    fields[dayField(doc, "$base/$index/load")] != nullableText(segment.load?.value) ||
                    fields[dayField(doc, "$base/$index/unit")] != segment.load?.unit.orEmpty()
            }
        }
    }
    Kind.activity -> {
        val value = doc.value<ActivityRecord>()
        fields[dayField(doc, "name")] != value.name || fields[dayField(doc, "localDate")] != value.localDate ||
            fields[dayField(doc, "start")] != showInstant(value.startedAt) || fields[dayField(doc, "end")] != showInstant(value.endedAt) ||
            fields[dayField(doc, "notes")] != value.notes || fields[dayField(doc, "duration")] != (value.durationSeconds?.let { numericText(it / 60.0) }.orEmpty()) ||
            fields[dayField(doc, "distance")] != (value.distanceMeters?.let { numericText(it / 1000.0) }.orEmpty())
    }
    else -> false
}

private fun buildChangedDocument(doc: Document, fields: Map<String, String>): Document? = when (doc.kind) {
    Kind.session -> {
        val original = doc.value<Session>()
        val results = original.results.map { result ->
            val base = "result-${result.itemId}-${result.setIndex}"
            val item = original.items.firstOrNull { it.id == result.itemId }
            val exercise = item?.let { planItem -> original.exercises.firstOrNull { it.id == planItem.exerciseId } }
            val measurement = targetMeasurement(item?.sets?.getOrNull(result.setIndex), exercise?.measurement.orEmpty())
            if (result.segments.isEmpty()) result.copy(
                reps = if (measurement == "reps") draftOptionalInt(fields, doc, "$base/reps", "次数", result.reps) else result.reps,
                seconds = if (measurement == "seconds") draftOptionalInt(fields, doc, "$base/seconds", "时长", result.seconds) else result.seconds,
                load = loadFromDraft(fields[dayField(doc, "$base/0/load")], fields[dayField(doc, "$base/0/unit")], result.load, exercise)
            ) else {
                val segments = result.segments.mapIndexed { index, segment ->
                    val load = loadFromDraft(fields[dayField(doc, "$base/$index/load")], fields[dayField(doc, "$base/$index/unit")], segment.load, exercise)
                    segment.copy(reps = fields[dayField(doc, "$base/$index/reps")]?.let { parseRequiredInt(it, "分段次数") } ?: segment.reps, load = load)
                }
                val totalReps = segments.sumOf { it.reps.toLong() }
                require(totalReps <= Int.MAX_VALUE) { "分段总次数过大" }
                result.copy(segments = segments, reps = totalReps.toInt())
            }
        }
        val updated = original.copy(results = results, notes = fields[dayField(doc, "notes")] ?: original.notes)
        doc.takeIf { updated != original }?.copy(data = document(Kind.session, doc.id, updated).data)
    }
    Kind.activity -> {
        val original = doc.value<ActivityRecord>()
        val durationMinutes = parseOptionalDouble(fields[dayField(doc, "duration")].orEmpty(), "独立时长（分钟）")
        val originalDurationText = original.durationSeconds?.let { numericText(it / 60.0) }.orEmpty()
        val durationSeconds = if (fields[dayField(doc, "duration")].orEmpty() == originalDurationText) original.durationSeconds
            else durationMinutes?.times(60)?.also { require(it.isFinite() && it <= Int.MAX_VALUE && it.roundToInt() > 0) { "独立时长须可转换为正整数秒" } }?.roundToInt()
        val distanceKm = parseOptionalDouble(fields[dayField(doc, "distance")].orEmpty(), "距离（公里）")
        val originalDistanceText = original.distanceMeters?.let { numericText(it / 1000.0) }.orEmpty()
        val distanceMeters = if (fields[dayField(doc, "distance")].orEmpty() == originalDistanceText) original.distanceMeters else distanceKm?.times(1000)
        val startedAt = parseInstant(fields[dayField(doc, "start")].orEmpty())
        val endedAt = parseInstant(fields[dayField(doc, "end")].orEmpty())
        require(startedAt == null || endedAt == null || endedAt >= startedAt) { "结束时间不能早于开始时间" }
        val updated = original.copy(name = fields[dayField(doc, "name")].orEmpty(), localDate = fields[dayField(doc, "localDate")].orEmpty(),
            startedAt = startedAt, endedAt = endedAt, durationSeconds = durationSeconds,
            distanceMeters = distanceMeters, notes = fields[dayField(doc, "notes")].orEmpty())
        require(updated.name.isNotBlank()) { "请填写运动名称" }
        LocalDate.parse(updated.localDate)
        doc.takeIf { updated != original }?.copy(data = document(Kind.activity, doc.id, updated).data)
    }
    else -> null
}

private fun loadFromDraft(value: String?, unit: String?, original: Load?, exercise: Exercise?): Load? {
    if (value.isNullOrBlank()) return null
    val amount = parseOptionalDouble(value, "实际重量")!!
    val rawUnit = requireNotNull(unit?.takeIf(String::isNotBlank)) { "请为新填写的重量选择原始单位" }
    return Load(amount, rawUnit, original?.meaning ?: "unknown", original?.mode ?: exercise?.loadMode ?: "external")
}

@Composable
private fun SessionDraftCard(doc: Document, fields: Map<String, String>, health: HealthUiState, editing: Boolean, update: (Document, String, String) -> Unit, onDelete: () -> Unit) {
    val session = doc.value<Session>()
    val canEdit = editing && session.status != "active"
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(sessionTrainingLabels(session).joinToString("、"), style = MaterialTheme.typography.titleMedium)
        historyTimeRange(session.startedAt, session.endedAt)?.let { Text(it) }
        Text("状态：${sessionStatusText(session.status)}")
        Text("开始时计划：${session.snapshot?.name ?: "未记录"}", style = MaterialTheme.typography.bodySmall)
        session.items.forEach { item ->
            val exercise = session.exercises.firstOrNull { it.id == item.exerciseId }
            val results = session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }
            if (results.isNotEmpty()) {
                Text(exercise?.name ?: "动作", style = MaterialTheme.typography.labelLarge)
                results.forEach { result ->
                    val base = "result-${result.itemId}-${result.setIndex}"
                    val measurement = targetMeasurement(item.sets.getOrNull(result.setIndex), exercise?.measurement.orEmpty())
                    if (canEdit) Text(historyResultText(result), style = MaterialTheme.typography.bodySmall)
                    if (canEdit && result.segments.isEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextInput("重量", fields[dayField(doc, "$base/0/load")].orEmpty(), { update(doc, "$base/0/load", it) }, numeric = true, modifier = Modifier.weight(1f))
                            Choice<String?>("单位", fields[dayField(doc, "$base/0/unit")].orEmpty().takeIf(String::isNotBlank), listOf("kg" to "kg", "lb" to "lb"), { update(doc, "$base/0/unit", it.orEmpty()) }, modifier = Modifier.weight(1f))
                            when (measurement) {
                                "seconds" -> TextInput("秒", fields[dayField(doc, "$base/seconds")].orEmpty(), { update(doc, "$base/seconds", it) }, numeric = true, modifier = Modifier.weight(1f))
                                "reps" -> TextInput("次数", fields[dayField(doc, "$base/reps")].orEmpty(), { update(doc, "$base/reps", it) }, numeric = true, modifier = Modifier.weight(1f))
                            }
                        }
                    } else if (canEdit) result.segments.forEachIndexed { index, _ ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextInput("段${index + 1}重量", fields[dayField(doc, "$base/$index/load")].orEmpty(), { update(doc, "$base/$index/load", it) }, numeric = true, modifier = Modifier.weight(1f))
                            Choice<String?>("单位", fields[dayField(doc, "$base/$index/unit")].orEmpty().takeIf(String::isNotBlank), listOf("kg" to "kg", "lb" to "lb"), { update(doc, "$base/$index/unit", it.orEmpty()) }, modifier = Modifier.weight(1f))
                            TextInput("次数", fields[dayField(doc, "$base/$index/reps")].orEmpty(), { update(doc, "$base/$index/reps", it) }, numeric = true, modifier = Modifier.weight(1f))
                        }
                    } else Text(historyResultText(result), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (canEdit) TextInput("训练反馈（可空）", fields[dayField(doc, "notes")].orEmpty(), { update(doc, "notes", it) }, singleLine = false)
        else if (session.notes.isNotBlank()) Text("反馈：${session.notes}")
        Text("实际完成 ${session.results.size} 组", style = MaterialTheme.typography.bodySmall)
        ActivityHeartRateChart(
            session.startedAt,
            session.endedAt,
            health.recordsByDataset[HealthDataset.heartRate].orEmpty(),
            health.detailStateByDataset[HealthDataset.heartRate]?.status
        )
        if (editing && session.status != "active") TextButton(onClick = onDelete) { Text("删除这次记录") }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ActivityDraftCard(doc: Document, fields: Map<String, String>, health: HealthUiState, editing: Boolean, update: (Document, String, String) -> Unit, onDelete: () -> Unit) {
    val activity = doc.value<ActivityRecord>()
    val sources = health.recordsByDataset[HealthDataset.activity].orEmpty().filter { it.activitySourceId() == activity.sourceId }
    var expandedSource by remember(doc.id) { mutableStateOf(false) }
    val startValue = fields[dayField(doc, "start")].orEmpty()
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (editing) {
            TextInput("运动名称", fields[dayField(doc, "name")].orEmpty(), { update(doc, "name", it) })
            if (startValue.isBlank()) ActivityDateField(fields[dayField(doc, "localDate")].orEmpty()) { update(doc, "localDate", it) }
            else Text("归档日期：${fields[dayField(doc, "localDate")]}（随开始日期同步）", style = MaterialTheme.typography.bodySmall)
            ActivityDateTimeField("开始时间", startValue, fields[dayField(doc, "localDate")].orEmpty()) {
                update(doc, "start", it)
                if (it.isNotBlank()) update(doc, "localDate", it.substringBefore(' '))
            }
            ActivityDateTimeField("结束时间", fields[dayField(doc, "end")].orEmpty(), fields[dayField(doc, "localDate")].orEmpty()) { update(doc, "end", it) }
            TextInput("独立记录时长（分钟，可空）", fields[dayField(doc, "duration")].orEmpty(), { update(doc, "duration", it) }, numeric = true)
            TextInput("距离（公里，可空）", fields[dayField(doc, "distance")].orEmpty(), { update(doc, "distance", it) }, numeric = true)
            TextInput("备注（可空）", fields[dayField(doc, "notes")].orEmpty(), { update(doc, "notes", it) }, singleLine = false)
        } else {
            Text(activity.name, style = MaterialTheme.typography.titleMedium)
            activityHistoryTimeRange(activity)?.let { Text(it) }
            activity.durationSeconds?.let { Text("独立时长：${formatActivityDuration(it)}") }
            activity.distanceMeters?.let { Text("距离：${formatActivityDistance(it)}") }
            if (activity.notes.isNotBlank()) Text("备注：${activity.notes}")
        }
        if (activity.sourceId != null) {
            Text("设备来源：小米运动健康", style = MaterialTheme.typography.bodySmall)
            if (sources.isEmpty()) {
                val sourceStatus = health.detailStateByDataset[HealthDataset.activity]?.status
                val message = when (sourceStatus) {
                    HealthDetailStatus.complete -> "没有匹配的设备运动明细"
                    HealthDetailStatus.partial -> "设备运动明细缓存不完整"
                    HealthDetailStatus.loading -> "设备运动明细读取中"
                    HealthDetailStatus.error -> "设备运动明细读取失败"
                    else -> "设备运动明细尚未读取"
                }
                Text(message, style = MaterialTheme.typography.bodySmall)
            } else sources.forEach { record ->
                val payload = record.decodePayload<ActivityPayload>()
                val stats = buildList {
                    payload.durationSeconds?.let { add("设备时长：${formatActivityDuration(it)}") }
                    payload.distanceMeters?.let { add("设备距离：${formatActivityDistance(it)}") }
                    payload.caloriesKcal?.let { add("设备消耗：${numericText(it)} 千卡") }
                    payload.avgHeartRate?.let { add("设备平均心率：${numericText(it)} 次/分钟") }
                    payload.maxHeartRate?.let { add("设备最高心率：${numericText(it)} 次/分钟") }
                }.joinToString(" · ")
                Text(if (stats.isBlank()) "设备统计：暂无统计值" else "设备统计：$stats", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { expandedSource = !expandedSource }) { Text(if (expandedSource) "收起设备详情" else "查看设备原始详情") }
                if (expandedSource) Text(record.displayHealthPayload(HealthDataset.activity, dayEditorZone), style = MaterialTheme.typography.bodySmall)
            }
        }
        ActivityHeartRateChart(
            activity.startedAt,
            activity.endedAt,
            health.recordsByDataset[HealthDataset.heartRate].orEmpty(),
            health.detailStateByDataset[HealthDataset.heartRate]?.status
        )
        if (editing) TextButton(onClick = onDelete) { Text("删除这条运动") }
        HorizontalDivider()
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ActivityDateField(value: String, onValueChange: (String) -> Unit) {
    val date = try { LocalDate.parse(value) } catch (_: DateTimeParseException) { LocalDate.now(dayEditorZone) }
    var choosingDate by remember { mutableStateOf(false) }
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDay() * 86_400_000L)
    OutlinedButton(onClick = { choosingDate = true }, modifier = Modifier.fillMaxWidth()) { Text("归档日期：$value") }
    if (choosingDate) DatePickerDialog(
        onDismissRequest = { choosingDate = false },
        confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                onValueChange(LocalDate.ofEpochDay(Math.floorDiv(picker.selectedDateMillis!!, 86_400_000L)).toString())
                choosingDate = false
            }) { Text("确定日期") }
        },
        dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }
    ) { DatePicker(picker) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ActivityDateTimeField(label: String, value: String, fallbackDate: String, onValueChange: (String) -> Unit) {
    val context = LocalContext.current
    val current = try { LocalDateTime.parse(value.replace(' ', 'T')) } catch (_: DateTimeParseException) { null }
    val fallback = try { LocalDate.parse(fallbackDate) } catch (_: DateTimeParseException) { LocalDate.now(dayEditorZone) }
    val date = current?.toLocalDate() ?: fallback
    val time = current?.toLocalTime() ?: LocalTime.NOON
    var choosingDate by remember { mutableStateOf(false) }
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDay() * 86_400_000L)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { choosingDate = true }, modifier = Modifier.weight(1f)) {
            Text(if (value.isBlank()) "$label：未设置" else "$label：$value")
        }
        if (value.isNotBlank()) TextButton(onClick = { onValueChange("") }) { Text("清除") }
    }
    if (choosingDate) DatePickerDialog(
        onDismissRequest = { choosingDate = false },
        confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                val selected = LocalDate.ofEpochDay(Math.floorDiv(picker.selectedDateMillis!!, 86_400_000L))
                choosingDate = false
                TimePickerDialog(context, { _, hour, minute ->
                    onValueChange(selected.atTime(hour, minute).toString().replace('T', ' '))
                }, time.hour, time.minute, true).show()
            }) { Text("下一步：选择时间") }
        },
        dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }
    ) { DatePicker(picker) }
}
