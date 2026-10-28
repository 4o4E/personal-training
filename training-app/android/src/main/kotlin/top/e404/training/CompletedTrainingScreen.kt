package top.e404.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.nullable
import top.e404.training.shared.*

@kotlinx.serialization.Serializable
private data class ResultTarget(val itemId: String, val setIndex: Int)
private data class SegmentInput(val reps: String, val load: String, val unit: String)

@Composable
fun CompletedTrainingScreen(session: Session, today: java.time.LocalDate, viewModel: TrainingViewModel) {
    var editingResult by rememberSaveable(stateSaver = jsonSaver(ResultTarget.serializer().nullable)) { mutableStateOf<ResultTarget?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("今日训练记录", style = MaterialTheme.typography.titleMedium)
            Text(session.name, style = MaterialTheme.typography.headlineSmall)
            Text("${if (session.status == "cancelled") "已取消" else "已完成"} · ${session.startedAt?.let(::localDateTime) ?: session.localDate}")
            TextButton(onClick = { viewModel.resumeToday(today) }) { Text("继续当天训练") }
        }
        items(session.items, key = { it.id }) { item ->
            val exercise = session.exercises.singleOrNull { it.id == item.exerciseId } ?: return@items
            val results = session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }
            if (results.isNotEmpty()) InfoCard(exercise.name) {
                results.forEach { result ->
                    Text("第 ${result.setIndex + 1} 组 · ${resultText(result)}")
                    TextButton(onClick = { editingResult = ResultTarget(item.id, result.setIndex) }) { Text("修改实际结果") }
                }
            }
        }
    }
    editingResult?.let { target ->
        val result = session.results.firstOrNull { it.itemId == target.itemId && it.setIndex == target.setIndex }
        val exercise = session.items.firstOrNull { it.id == target.itemId }?.let { item ->
            session.exercises.firstOrNull { it.id == item.exerciseId }
        }
        if (result != null && exercise != null) {
            var repsText by remember(result) { mutableStateOf(result.reps?.toString().orEmpty()) }
            var secondsText by remember(result) { mutableStateOf(result.seconds?.toString().orEmpty()) }
            var weightText by remember(result) { mutableStateOf(result.load?.let { numericText(it.value) }.orEmpty()) }
            var unit by remember(result) { mutableStateOf(result.load?.unit ?: "kg") }
            var segments by remember(result) {
                mutableStateOf(result.segments.map { segment ->
                    SegmentInput(segment.reps.toString(), segment.load?.let { numericText(it.value) }.orEmpty(), segment.load?.unit ?: "kg")
                })
            }
            FormDialog("修改第 ${target.setIndex + 1} 组", { editingResult = null }, {
                viewModel.action {
                    val revisedSegments = if (result.segments.isEmpty()) null else result.segments.mapIndexed { index, original ->
                        val input = segments[index]
                        val repetitions = input.reps.toIntOrNull() ?: throw IllegalArgumentException("每段次数须为正整数")
                        require(repetitions > 0) { "每段次数须为正整数" }
                        val load = completedLoad(input.load, input.unit, original.load, exercise)
                        SetSegment(repetitions, load)
                    }
                    val reps = if (revisedSegments != null) revisedSegments.sumOf { it.reps }
                        else if (exercise.measurement == "reps") repsText.toIntOrNull() ?: throw IllegalArgumentException("次数须为正整数") else result.reps
                    val seconds = if (exercise.measurement == "seconds") secondsText.toIntOrNull() ?: throw IllegalArgumentException("时长须为正整数秒") else result.seconds
                    require(reps == null || reps > 0) { "次数须为正整数" }
                    require(seconds == null || seconds > 0) { "时长须为正整数秒" }
                    val load = if (result.segments.isNotEmpty()) null else completedLoad(weightText, unit, result.load, exercise)
                    viewModel.repository.updateCompletedResult(session.id, target.itemId, target.setIndex, reps, seconds, load, revisedSegments)
                    editingResult = null
                }
            }) {
                if (result.segments.isEmpty()) {
                    if (exercise.measurement == "seconds") TextInput("实际时长（秒）", secondsText, { secondsText = it }, numeric = true)
                    else TextInput("实际次数", repsText, { repsText = it }, numeric = true)
                    TextInput("实际重量（留空表示未记录）", weightText, { weightText = it }, numeric = true)
                    Choice("原始单位", unit, listOf("kg" to "公斤 kg", "lb" to "磅 lb"), { unit = it })
                } else {
                    Text("分段次数合计将同步更新整组次数；分段数量保持不变。", style = MaterialTheme.typography.bodySmall)
                    segments.forEachIndexed { index, draft ->
                        TextInput("第 ${index + 1} 段次数", draft.reps, { value -> segments = segments.toMutableList().also { it[index] = draft.copy(reps = value) } }, numeric = true)
                        TextInput("第 ${index + 1} 段重量（留空表示未记录）", draft.load, { value -> segments = segments.toMutableList().also { it[index] = draft.copy(load = value) } }, numeric = true)
                        Choice("第 ${index + 1} 段原始单位", draft.unit, listOf("kg" to "公斤 kg", "lb" to "磅 lb"), { value -> segments = segments.toMutableList().also { it[index] = draft.copy(unit = value) } })
                    }
                }
            }
        }
    }
}

private fun completedLoad(value: String, unit: String, original: Load?, exercise: Exercise): Load? {
    if (value.isBlank()) return null
    val amount = doubleInput(value, "重量")
    require(amount.isFinite() && amount >= 0.0) { "重量必须为非负有限数值" }
    return Load(amount, unit, original?.meaning ?: "unknown", original?.mode ?: exercise.loadMode)
}
