package top.e404.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.nullable
import top.e404.training.shared.*

@Composable fun SessionAdjustScreen(session: Session, state: AppData, viewModel: TrainingViewModel) {
    var selectingType by rememberSaveable { mutableStateOf<String?>(null) }
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingItem by rememberSaveable(stateSaver = jsonSaver(PlanItem.serializer().nullable)) { mutableStateOf<PlanItem?>(null) }
    var selectedExercise by rememberSaveable(stateSaver = jsonSaver(Exercise.serializer().nullable)) { mutableStateOf<Exercise?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("本次剩余内容", style = MaterialTheme.typography.headlineSmall)
        Text("此处只调整当前训练尚未完成的部分；已完成组与已记录结果保持不变。未来计划请从“未来计划”入口调整。", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(session.items, key = { it.id }) { item ->
                val exercise = session.exercises.single { it.id == item.exerciseId }
                val completed = session.results.count { it.itemId == item.id }
                val skipped = item.id in session.skippedItems
                val remaining = if (skipped) 0 else maxOf(0, item.sets.size - completed)
                InfoCard(exercise.name) {
                    Text("已完成 $completed 组 · 剩余 $remaining 组")
                    if (skipped) Text("该动作剩余目标已由替代动作安排。", style = MaterialTheme.typography.bodySmall)
                    item.notes.takeIf(String::isNotBlank)?.let { Text(it) }
                    if (remaining > 0) {
                        TextButton(onClick = { replacing = item.id; selectingType = exercise.type }) { Text("替换剩余动作") }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { replacing = null; selectingType = "strength" }) { Text("添加训练动作") }
                    OutlinedButton(onClick = { replacing = null; selectingType = "stretch" }) { Text("添加拉伸") }
                }
            }
        }
    }

    selectingType?.let { type ->
        val targets = if (replacing != null) {
            session.items.single { it.id == replacing }.let { item -> session.exercises.single { it.id == item.exerciseId }.muscles.toSet() }
        } else {
            session.items.filter { it.id !in session.skippedItems || session.results.any { result -> result.itemId == it.id } }
                .flatMap { item -> session.exercises.single { it.id == item.exerciseId }.let { if (it.type == "strength") it.muscles + it.secondaryMuscles else emptyList() } }.toSet()
        }
        ExercisePicker(state, viewModel.repository, session.snapshot?.gymId, targets, type,
            { selectingType = null; replacing = null }) { exercise ->
            selectedExercise = exercise
            val old = replacing?.let { id -> session.items.single { it.id == id } }
            val remaining = old?.sets?.filterIndexed { index, _ -> session.results.none { it.itemId == old.id && it.setIndex == index } }
            val fresh = newPlanItem(exercise, session.snapshot?.gymId, state, remaining?.size)
            pendingItem = if (remaining != null && exercise.type == "stretch" && exercise.unilateral)
                fresh.copy(sets = fresh.sets.mapIndexed { index, target -> target.copy(side = remaining[index].side) }) else fresh
            selectingType = null
        }
    }
    pendingItem?.let { item ->
        val exercise = selectedExercise ?: return@let
        val originalPosition = replacing?.let { id -> session.items.indexOfFirst { it.id == id } }
        val positions = if (originalPosition != null) listOf(originalPosition to "原动作位置，仅替换未完成部分")
            else (0..session.items.size).map { position -> position to if (position == 0) "放在最前面" else "放在 ${session.exercises.single { it.id == session.items[position - 1].exerciseId }.name} 之后" }
        PlanItemEditor(item, exercise, session.snapshot?.gymId, state, viewModel, positions,
            originalPosition ?: session.items.size, { pendingItem = null; selectedExercise = null; replacing = null },
            allowMeasurementChange = exercise.id.startsWith("smartworkout_")
        ) { revised, position ->
            val replacingId = replacing
            if (replacingId != null) viewModel.replaceSessionExercise(session.id, replacingId, revised, exercise)
            else viewModel.addSessionExercise(session.id, revised, exercise, position)
            pendingItem = null; selectedExercise = null; replacing = null
        }
    }
}
