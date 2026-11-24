package top.e404.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
    val state by viewModel.repository.data.collectAsStateWithLifecycle()
    val choices by preferences.data.collectAsStateWithLifecycle()
    var editingResult by rememberSaveable(stateSaver = jsonSaver(ResultTarget.serializer().nullable)) { mutableStateOf<ResultTarget?>(null) }
    var selectingType by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingItem by rememberSaveable(stateSaver = jsonSaver(PlanItem.serializer().nullable)) { mutableStateOf<PlanItem?>(null) }
    var selectedExercise by rememberSaveable(stateSaver = jsonSaver(Exercise.serializer().nullable)) { mutableStateOf<Exercise?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val padding = PaddingValues(horizontal = 8.dp)
                Button(onClick = { viewModel.resumeToday(today) }, modifier = Modifier.weight(1f), contentPadding = padding) {
                    Text("继续训练")
                }
                if (session.status == "completed") {
                    OutlinedButton(onClick = { selectingType = "strength" }, modifier = Modifier.weight(1f), contentPadding = padding) {
                        Text("添加动作")
                    }
                    OutlinedButton(onClick = { selectingType = "stretch" }, modifier = Modifier.weight(1f), contentPadding = padding) {
                        Text("添加拉伸")
                    }
                }
            }
        }
        items(session.items.filter { item -> item.id !in session.skippedItems || session.results.any { it.itemId == item.id } }, key = { it.id }) { item ->
            val exercise = session.exercises.singleOrNull { it.id == item.exerciseId } ?: return@items
            val results = session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }
            val complete = item.id !in session.skippedItems && item.sets.isNotEmpty() &&
                item.sets.indices.all { index -> results.binarySearchBy(index) { it.setIndex } >= 0 }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ExerciseCardImage(exercise, state, viewModel.repository, Modifier.size(96.dp))
                        Column(Modifier.weight(1f)) {
                            if (results.isEmpty()) Text("无实际记录", style = MaterialTheme.typography.bodySmall)
                            results.forEach { result ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 36.dp)
                                        .clickable(role = Role.Button, onClickLabel = "修改实际结果") {
                                            editingResult = ResultTarget(item.id, result.setIndex)
                                        }
                                        .padding(end = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(workoutResultSummary(result), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
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
                        val load = completedLoad(input.load, input.unit, original.load)
                        SetSegment(repetitions, load)
                    }
                    val reps = if (revisedSegments != null) revisedSegments.sumOf { it.reps }
                        else if (measurement == "reps") repsText.toIntOrNull() ?: throw IllegalArgumentException("次数须为正整数") else result.reps
                    val seconds = if (measurement == "seconds") secondsText.toIntOrNull() ?: throw IllegalArgumentException("时长须为正整数秒") else result.seconds
                    require(reps == null || reps > 0) { "次数须为正整数" }
                    require(seconds == null || seconds > 0) { "时长须为正整数秒" }
                    val load = if (result.segments.isNotEmpty()) null else completedLoad(weightText, unit, result.load)
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
    selectingType?.let { type ->
        val targets = session.items.flatMap { item ->
            session.exercises.singleOrNull { it.id == item.exerciseId }?.let {
                if (it.type == "strength") it.muscles + it.secondaryMuscles else emptyList()
            }.orEmpty()
        }.toSet()
        ExercisePicker(state, viewModel.repository, session.snapshot?.gymId, targets,
            type, { selectingType = null }) { exercise ->
            selectingType = null
            selectedExercise = exercise
            pendingItem = newPlanItem(exercise, session.snapshot?.gymId, state)
        }
    }
    pendingItem?.let { item ->
        val exercise = selectedExercise ?: return@let
        val positions = (0..session.items.size).map { position ->
            position to if (position == 0) "放在最前面" else "放在 ${session.exercises.single { it.id == session.items[position - 1].exerciseId }.name} 之后"
        }
        PlanItemEditor(item, exercise, session.snapshot?.gymId, state, viewModel,
            positions, session.items.size, { pendingItem = null; selectedExercise = null },
            allowMeasurementChange = exercise.id.startsWith("smartworkout_")
        ) { revised, position ->
            viewModel.addSessionExercise(session.id, revised, exercise, position)
            pendingItem = null
            selectedExercise = null
        }
    }
}

private fun completedLoad(value: String, unit: String, original: Load?): Load? {
    if (value.isBlank()) return null
    val amount = doubleInput(value, "重量")
    require(amount.isFinite() && amount >= 0.0) { "重量必须为非负有限数值" }
    return Load(amount, unit, original?.meaning ?: "unknown", original?.mode ?: "external")
}
