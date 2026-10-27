package top.e404.training

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
import top.e404.training.shared.ActivityRecord
import top.e404.training.shared.Kind
import top.e404.training.shared.document
import top.e404.training.shared.value
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ActivityEditor(original: top.e404.training.shared.Document, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    val originalValue = remember(original) { original.value<ActivityRecord>() }
    var draft by rememberSaveable(original.id, stateSaver = jsonSaver(ActivityRecord.serializer())) { mutableStateOf(originalValue) }
    var durationText by rememberSaveable(original.id) { mutableStateOf(originalValue.durationSeconds?.let { numericText(it / 60.0) }.orEmpty()) }
    var distanceText by rememberSaveable(original.id) { mutableStateOf(originalValue.distanceMeters?.let { numericText(it / 1000.0) }.orEmpty()) }
    var choosingDate by rememberSaveable(original.id) { mutableStateOf(false) }
    var inputError by rememberSaveable(original.id) { mutableStateOf<String?>(null) }
    val title = if (originalValue.name.isBlank()) "记录其他运动" else "编辑其他运动"

    FormDialog(title, onDismiss, onSave = {
        val durationMinutes = durationText.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
        val durationSecondsValue = durationMinutes?.times(60.0)
        val distanceKilometers = distanceText.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
        val distanceMeters = distanceKilometers?.times(1000.0)
        inputError = when {
            draft.name.isBlank() -> "请填写运动名称"
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
            val durationSeconds = durationSecondsValue?.roundToInt()
            viewModel.save(
                document(Kind.activity, draft.id, draft.copy(durationSeconds = durationSeconds, distanceMeters = distanceMeters)),
                original,
                onDismiss
            )
        }
    }) {
        TextInput("运动名称", draft.name, { draft = draft.copy(name = it); inputError = null })
        OutlinedButton(onClick = { choosingDate = true }) { Text("日期：${draft.localDate}") }
        TextInput("时长（分钟，可空）", durationText, { durationText = it; inputError = null }, numeric = true)
        TextInput("距离（公里，可空）", distanceText, { distanceText = it; inputError = null }, numeric = true)
        TextInput("备注（可空）", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
        inputError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
    }

    if (choosingDate) {
        val selectedDate = LocalDate.parse(draft.localDate)
        val picker = rememberDatePickerState(initialSelectedDateMillis = selectedDate.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    draft = draft.copy(localDate = LocalDate.ofEpochDay(picker.selectedDateMillis!! / 86_400_000L).toString())
                    choosingDate = false
                }) { Text("确定日期") }
            },
            dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }
        ) { DatePicker(picker) }
    }
}
