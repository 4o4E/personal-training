package top.e404.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { (context.applicationContext as TrainingApplication).preferences }
    val choices by preferences.data.collectAsStateWithLifecycle()
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
            val completedIndices = results.mapTo(hashSetOf()) { it.setIndex }
            val skipped = item.id in session.skippedItems
            val targetSetIndices = if (skipped) completedIndices.sorted() else item.sets.indices.toList()
            val targetCount = targetSetIndices.size
            val complete = !skipped && targetCount > 0 && targetSetIndices.all { it in completedIndices }
            var showPlan by rememberSaveable(session.id, item.id) { mutableStateOf(false) }
            var showActual by rememberSaveable(session.id, item.id) { mutableStateOf(false) }
            val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val bg = if (complete) {
                if (dark) Color(0xFF1B3A25) else Color(0xFFE8F5E9)
            } else MaterialTheme.colorScheme.surfaceVariant
            val fg = if (complete) {
                if (dark) Color(0xFFD9F2DC) else Color(0xFF1B5E20)
            } else MaterialTheme.colorScheme.onSurfaceVariant
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(exercise.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (complete) Icon(Icons.Default.Check, contentDescription = "动作已完成", tint = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32))
                    }
                    val samePlan = item.sets.map(::targetText).distinct().size <= 1
                    Text("计划 · ${item.sets.size}组 · ${if (samePlan) item.sets.firstOrNull()?.let(::targetText) ?: "未设置目标" else "各组目标不同"}", style = MaterialTheme.typography.bodySmall)
                    if (item.sets.isNotEmpty()) {
                        TextButton(onClick = { showPlan = !showPlan }) { Text(if (showPlan) "收起计划" else "查看各组计划") }
                        if (showPlan) item.sets.forEachIndexed { index, target ->
                            Text("第 ${index + 1} 组 · ${targetText(target)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val completedCount = targetSetIndices.count { it in completedIndices }
                    Text("进度 · $completedCount/$targetCount 组", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { if (targetCount == 0) 0f else (completedCount.toFloat() / targetCount).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (results.isEmpty()) Text("实际记录 · 暂无组", style = MaterialTheme.typography.bodySmall)
                    else {
                        TextButton(onClick = { showActual = !showActual }) {
                            Text(if (showActual) "收起实际记录" else "查看实际记录 ${results.size} 组")
                        }
                        if (showActual) results.forEach { result ->
                            Text("第 ${result.setIndex + 1} 组 · ${resultText(result)}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { editingResult = ResultTarget(item.id, result.setIndex) }) { Text("修改实际结果") }
                        }
                    }
                    if (skipped) Text("该动作剩余目标已由替代动作安排；这不代表该动作已完成。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    editingResult?.let { target ->
        val result = session.results.firstOrNull { it.itemId == target.itemId && it.setIndex == target.setIndex }
        val planItem = session.items.firstOrNull { it.id == target.itemId }
        val exercise = planItem?.let { item -> session.exercises.firstOrNull { it.id == item.exerciseId } }
        val equipmentId = planItem?.equipmentId
        if (result != null && exercise != null) {
            val measurement = targetMeasurement(planItem?.sets?.getOrNull(target.setIndex), exercise.measurement)
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
                        else if (measurement == "reps") repsText.toIntOrNull() ?: throw IllegalArgumentException("次数须为正整数") else result.reps
                    val seconds = if (measurement == "seconds") secondsText.toIntOrNull() ?: throw IllegalArgumentException("时长须为正整数秒") else result.seconds
                    require(reps == null || reps > 0) { "次数须为正整数" }
                    require(seconds == null || seconds > 0) { "时长须为正整数秒" }
                    val load = if (result.segments.isNotEmpty()) null else completedLoad(weightText, unit, result.load, exercise)
                    viewModel.repository.updateCompletedResult(session.id, target.itemId, target.setIndex, reps, seconds, load, revisedSegments)
                    editingResult = null
                }
            }) {
                if (result.segments.isEmpty()) {
                    if (measurement == "seconds") TextInput("实际时长（秒）", secondsText, { secondsText = it }, numeric = true)
                    else WorkoutCountInput(repsText, { repsText = it })
                    WorkoutWeightInput(weightText, { weightText = it }, unit,
                        choices.weightSteps[weightStepKey(exercise.id, equipmentId, unit)] ?: 1.0,
                        { value -> preferences.weightStep(weightStepKey(exercise.id, equipmentId, unit), value) })
                    Choice("原始单位", unit, listOf("kg" to "公斤 kg", "lb" to "磅 lb"), { unit = it })
                } else {
                    Text("分段次数合计将同步更新整组次数；分段数量保持不变。", style = MaterialTheme.typography.bodySmall)
                    segments.forEachIndexed { index, draft ->
                        Text("第 ${index + 1} 段", style = MaterialTheme.typography.labelMedium)
                        WorkoutCountInput(draft.reps, { value -> segments = segments.toMutableList().also { it[index] = draft.copy(reps = value) } })
                        WorkoutWeightInput(draft.load, { value -> segments = segments.toMutableList().also { it[index] = draft.copy(load = value) } },
                            draft.unit, choices.weightSteps[weightStepKey(exercise.id, equipmentId, draft.unit)] ?: 1.0,
                            { value -> preferences.weightStep(weightStepKey(exercise.id, equipmentId, draft.unit), value) })
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
