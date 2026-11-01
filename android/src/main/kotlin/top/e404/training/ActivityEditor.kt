package top.e404.training

import android.app.TimePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import top.e404.training.shared.ActivityRecord
import top.e404.training.shared.Kind
import top.e404.training.shared.document
import top.e404.training.shared.value
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ActivityEditor(original: top.e404.training.shared.Document, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    val originalValue = remember(original) { original.value<ActivityRecord>() }
    var draft by rememberSaveable(original.id, stateSaver = jsonSaver(ActivityRecord.serializer())) { mutableStateOf(originalValue) }
    var durationText by rememberSaveable(original.id) { mutableStateOf(originalValue.durationSeconds?.let { numericText(it / 60.0) }.orEmpty()) }
    var distanceText by rememberSaveable(original.id) { mutableStateOf(originalValue.distanceMeters?.let { numericText(it / 1000.0) }.orEmpty()) }
    var choosingDate by rememberSaveable(original.id) { mutableStateOf(false) }
    var endpoint by rememberSaveable(original.id) { mutableStateOf("local") }
    var inputError by rememberSaveable(original.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val zone = remember { ZoneId.of("Asia/Shanghai") }
    val title = if (originalValue.name.isBlank()) "记录其他运动" else "编辑其他运动"
    fun dateTime(at: Long?) = at?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
    fun formatEndpoint(at: Long?): String = dateTime(at)?.let { "${it.toLocalDate()} ${it.toLocalTime().toString().take(5)}" } ?: "未设置"
    fun setEndpoint(which: String, date: LocalDate, time: LocalTime) {
        val timestamp = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        draft = if (which == "start") draft.copy(localDate = date.toString(), startedAt = timestamp) else draft.copy(endedAt = timestamp)
    }

    FormDialog(title, onDismiss, onSave = {
        val startedAt = draft.startedAt
        val endedAt = draft.endedAt
        val durationMinutes = durationText.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
        val durationSecondsValue = durationMinutes?.times(60.0)
        val distanceKilometers = distanceText.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
        val distanceMeters = distanceKilometers?.times(1000.0)
        inputError = when {
            draft.name.isBlank() -> "请填写运动名称"
            startedAt != null && endedAt != null && endedAt < startedAt -> "结束时间不能早于开始时间"
            durationText.isNotBlank() && (
                durationMinutes == null || !durationMinutes.isFinite() || durationMinutes <= 0 ||
                    durationSecondsValue == null || !durationSecondsValue.isFinite() || durationSecondsValue > Int.MAX_VALUE || durationSecondsValue.roundToInt() <= 0
                ) -> "时长须为可换算成正整数秒的分钟数"
            distanceText.isNotBlank() && (
                distanceKilometers == null || !distanceKilometers.isFinite() || distanceKilometers <= 0 ||
                    distanceMeters == null || !distanceMeters.isFinite() || distanceMeters <= 0
                ) -> "距离须为正数公里"
            else -> null
        }
        if (inputError == null) {
            val manualDuration = durationSecondsValue?.roundToInt()
            val intervalDuration = if (originalValue.name.isBlank() && manualDuration == null && startedAt != null && endedAt != null)
                ((endedAt - startedAt) / 1000L).takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
            else null
            viewModel.save(
                document(Kind.activity, draft.id, draft.copy(
                    durationSeconds = manualDuration ?: intervalDuration,
                    distanceMeters = distanceMeters
                )),
                original,
                onDismiss
            )
        }
    }) {
        TextInput("运动名称", draft.name, { draft = draft.copy(name = it); inputError = null })
        OutlinedButton(onClick = { endpoint = "local"; choosingDate = true }, enabled = draft.startedAt == null) { Text("归档日期：${draft.localDate}") }
        OutlinedButton(onClick = { endpoint = "start"; choosingDate = true }) { Text("开始：${formatEndpoint(draft.startedAt)}") }
        OutlinedButton(onClick = { endpoint = "end"; choosingDate = true }) { Text("结束：${formatEndpoint(draft.endedAt)}") }
        if (draft.startedAt != null) TextButton(onClick = { draft = draft.copy(startedAt = null) }) { Text("清除开始时间") }
        if (draft.endedAt != null) TextButton(onClick = { draft = draft.copy(endedAt = null) }) { Text("清除结束时间") }
        if (draft.startedAt != null && draft.endedAt != null) Text(
            "运动区间：${formatEndpoint(draft.startedAt)} — ${formatEndpoint(draft.endedAt)}",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        TextInput("时长（分钟，可空；独立于起止区间）", durationText, { durationText = it; inputError = null }, numeric = true)
        TextInput("距离（公里，可空）", distanceText, { distanceText = it; inputError = null }, numeric = true)
        TextInput("备注（可空）", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
        inputError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
    }

    if (choosingDate && endpoint == "local") {
        val selectedDate = LocalDate.parse(draft.localDate)
        val picker = rememberDatePickerState(initialSelectedDateMillis = selectedDate.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    draft = draft.copy(localDate = LocalDate.ofEpochDay(Math.floorDiv(picker.selectedDateMillis!!, 86_400_000L)).toString())
                    choosingDate = false
                }) { Text("确定日期") }
            },
            dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }
        ) { DatePicker(picker) }
    } else if (choosingDate) {
        val which = endpoint
        val current = dateTime(if (which == "start") draft.startedAt else draft.endedAt)
        val selectedDate = current?.toLocalDate() ?: LocalDate.parse(draft.localDate)
        val selectedTime = current?.toLocalTime() ?: LocalTime.NOON
        val picker = rememberDatePickerState(initialSelectedDateMillis = selectedDate.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    val date = LocalDate.ofEpochDay(Math.floorDiv(picker.selectedDateMillis!!, 86_400_000L))
                    choosingDate = false
                    TimePickerDialog(context, { _, hour, minute -> setEndpoint(which, date, LocalTime.of(hour, minute)) }, selectedTime.hour, selectedTime.minute, false).show()
                }) { Text("下一步：选择时间") }
            },
            dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }
        ) { DatePicker(picker) }
    }
}
