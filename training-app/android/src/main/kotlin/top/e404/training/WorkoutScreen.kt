package top.e404.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.nullable
import top.e404.training.shared.*

@Composable fun RestTimerCard(viewModel: TrainingViewModel) {
    val timer by viewModel.repository.timer.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var remaining by remember { mutableLongStateOf(0) }
    LaunchedEffect(timer?.deadline, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val end = timer?.deadline ?: return@repeatOnLifecycle
            do { remaining = maxOf(0, (end - System.currentTimeMillis() + 999) / 1000); if (remaining > 0) delay(1000) } while (remaining > 0)
        }
    }
    timer?.let { current ->
        InfoCard(if (current.type == "rest") "组间休息" else "动作计时") {
            Text(current.label)
            Text(if (remaining == 0L) "计时结束" else "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = viewModel::stopTimer) { Text(if (current.type == "rest") "结束休息" else "结束计时") }
        }
    }
}

@Composable fun WorkoutScreen(session: Session, state: AppData, viewModel: TrainingViewModel, permissionRevision: Int) {
    var detail by remember { mutableStateOf<Exercise?>(null) }
    var editingResult by rememberSaveable { mutableStateOf<String?>(null) }
    var editingIndex by rememberSaveable { mutableStateOf(0) }
    var actualDraft by rememberSaveable(stateSaver = jsonSaver(SetTarget.serializer().nullable)) { mutableStateOf<SetTarget?>(null) }
    var selectingType by rememberSaveable { mutableStateOf<String?>(null) }
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingItem by rememberSaveable(stateSaver = jsonSaver(PlanItem.serializer().nullable)) { mutableStateOf<PlanItem?>(null) }
    var selectedExercise by rememberSaveable(stateSaver = jsonSaver(Exercise.serializer().nullable)) { mutableStateOf<Exercise?>(null) }
    var finishMode by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNotes by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(session.notes) }
    val context = LocalContext.current
    val reminderReady = remember(permissionRevision) { TimerEngine.canSchedule(context) && TimerEngine.canNotify(context) }
    val done = session.results.size
    val total = session.items.sumOf { item -> if (item.id in session.skippedItems) session.results.count { it.itemId == item.id } else item.sets.size }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (!reminderReady) Text("通知或定时唤醒权限未就绪：目前只能查看计时，请在设置中完成授权。", color = MaterialTheme.colorScheme.error)
        RestTimerCard(viewModel)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(session.name, style = MaterialTheme.typography.headlineSmall)
            Text("已完成 $done / $total 组 · ${localDateTime(session.startedAt)}")
        }
        items(session.items, key = { it.id }) { item ->
            val exercise = session.exercises.single { it.id == item.exerciseId }
            val skipped = item.id in session.skippedItems
            InfoCard(exercise.name) {
                Text(if (exercise.type == "stretch") "拉伸 · ${item.sets.size} 组" else "力量训练 · ${item.sets.size} 组")
                if (item.notes.isNotBlank()) Text(item.notes)
                Row {
                    TextButton(onClick = { detail = exercise }) { Text("查看教学") }
                    if (!skipped) TextButton(onClick = { replacing = item.id; selectingType = exercise.type }, enabled = session.results.count { it.itemId == item.id } < item.sets.size) { Text("替换剩余动作") }
                }
                item.sets.forEachIndexed { index, target ->
                    val result = session.results.firstOrNull { it.itemId == item.id && it.setIndex == index }
                    if (skipped && result == null) Text("第 ${index + 1} 组：已由替代动作安排，未记为完成", style = MaterialTheme.typography.bodySmall)
                    else {
                        HorizontalDivider()
                        Text("第 ${index + 1} 组 · ${sideText(target.side)}", style = MaterialTheme.typography.titleSmall)
                        Text("计划：${targetText(target)}", style = MaterialTheme.typography.bodySmall)
                        if (result != null) {
                            Text("实际：" + listOfNotNull(result.reps?.let { "$it 次" }, result.seconds?.let { "$it 秒" }, loadText(result.load)).joinToString(" · "), color = MaterialTheme.colorScheme.primary)
                            Row {
                                TextButton(onClick = { actualDraft = null; editingResult = item.id; editingIndex = index }) { Text("修改实际记录") }
                                TextButton(onClick = { viewModel.undoSet(session.id, item.id, index) }) { Text("撤销完成") }
                            }
                        } else {
                            ActualSetRow(session.id, item, index, target, exercise, viewModel) { draft -> actualDraft = draft; editingResult = item.id; editingIndex = index }
                        }
                    }
                }
            }
        }
        item { Button(onClick = { replacing = null; selectingType = "strength" }) { Text("添加训练动作") } }
        item { OutlinedButton(onClick = { replacing = null; selectingType = "stretch" }) { Text("推荐对应拉伸，选择加入位置") } }
        item { TextButton(onClick = { notes = session.notes; editingNotes = true }) { Text("记录训练反馈") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { finishMode = "complete" }, enabled = session.results.isNotEmpty()) { Text("完成本次训练") }
            OutlinedButton(onClick = { finishMode = "cancel" }) { Text("取消本次训练") }
        } }
    }
    }
    detail?.let { ExerciseDetail(it, state, viewModel.repository) { detail = null } }
    editingResult?.let { id ->
        val item = session.items.single { it.id == id }
        val result = session.results.firstOrNull { it.itemId == id && it.setIndex == editingIndex }
        val target = item.sets[editingIndex]
        val input = actualDraft ?: if (result == null) target else SetTarget(result.reps, seconds = result.seconds, load = result.load, side = result.side, restSeconds = target.restSeconds)
        val equipment = item.equipmentId?.let { state.record(Kind.equipment, it)?.value<Equipment>() }
        SetEditor(input, session.exercises.single { it.id == item.exerciseId }, equipment, true, viewModel, { editingResult = null; actualDraft = null }) { actual, _ ->
            viewModel.completeSet(session.id, id, editingIndex, actual); editingResult = null; actualDraft = null
        }
    }
    selectingType?.let { type ->
        val targets = if (replacing != null) session.items.single { it.id == replacing }.let { item -> session.exercises.single { it.id == item.exerciseId }.muscles }.toSet()
        else session.items.filter { it.id !in session.skippedItems || session.results.any { r -> r.itemId == it.id } }.flatMap { item -> session.exercises.single { it.id == item.exerciseId }.let { if (it.type == "strength") it.muscles + it.secondaryMuscles else emptyList() } }.toSet()
        ExercisePicker(state, viewModel.repository, session.snapshot.gymId, targets, type, { selectingType = null; replacing = null }) { exercise ->
            selectedExercise = exercise
            val old = replacing?.let { id -> session.items.single { it.id == id } }
            val remaining = old?.sets?.filterIndexed { index, _ -> session.results.none { it.itemId == old.id && it.setIndex == index } }
            val fresh = newPlanItem(exercise, session.snapshot.gymId, state, remaining?.size)
            pendingItem = if (remaining != null && exercise.type == "stretch" && exercise.unilateral) fresh.copy(sets = fresh.sets.mapIndexed { index, target -> target.copy(side = remaining[index].side) }) else fresh
            selectingType = null
        }
    }
    pendingItem?.let { item ->
        val exercise = selectedExercise!!
        val originalPosition = replacing?.let { id -> session.items.indexOfFirst { it.id == id } }
        val positions = if (originalPosition != null) listOf(originalPosition to "原动作位置，仅替换未完成部分")
            else (0..session.items.size).map { position -> position to if (position == 0) "放在最前面" else "放在 ${session.exercises.single { it.id == session.items[position - 1].exerciseId }.name} 之后" }
        PlanItemEditor(item, exercise, session.snapshot.gymId, state, viewModel, positions,
            originalPosition ?: session.items.size,
            { pendingItem = null; selectedExercise = null; replacing = null }) { revised, position ->
            if (replacing != null) viewModel.replaceSessionExercise(session.id, replacing!!, revised, exercise)
            else viewModel.addSessionExercise(session.id, revised, exercise, position)
            pendingItem = null; selectedExercise = null; replacing = null
        }
    }
    finishMode?.let { mode -> AlertDialog(onDismissRequest = { finishMode = null }, title = { Text(if (mode == "complete") "完成本次训练？" else "取消本次训练？") },
        text = { Text(if (mode == "complete") "保存实际完成的组并推进分化顺序。未完成的计划组不会计为完成。" else "已经完成的组仍会保留，但取消不会推进分化顺序。") },
        confirmButton = { TextButton(onClick = { viewModel.finish(session.id, mode == "cancel"); finishMode = null }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { finishMode = null }) { Text("继续训练") } }) }
    if (editingNotes) FormDialog("训练反馈", { editingNotes = false }, { viewModel.updateSessionNotes(session.id, notes); editingNotes = false }) {
        TextInput("实际感受与需要调整的内容", notes, { notes = it }, singleLine = false)
    }
}

@Composable private fun ActualSetRow(
    sessionId: String, item: PlanItem, index: Int, planned: SetTarget,
    exercise: Exercise, viewModel: TrainingViewModel, more: (SetTarget) -> Unit
) {
    val inputGoal = rememberSaveable(sessionId, item.id, index, saver = jsonSaver(SetTarget.serializer())) { planned }
    var amount by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.load?.let { numericText(it.value) } ?: "") }
    var reps by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.reps?.toString() ?: "") }
    var seconds by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.seconds?.toString() ?: "") }
    fun input() = inputGoal.copy(reps = if (exercise.measurement == "reps") intInput(reps, "实际次数") else null, repsMax = null,
        seconds = if (exercise.measurement == "seconds") intInput(seconds, "实际秒数") else null,
        load = inputGoal.load?.copy(value = doubleInput(amount, "重量")), restSeconds = planned.restSeconds)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        inputGoal.load?.let { TextInput("重量 ${it.unit}", amount, { amount = it }, numeric = true, modifier = Modifier.weight(1f)) }
        if (exercise.measurement == "seconds") TextInput("实际秒数", seconds, { seconds = it }, numeric = true, modifier = Modifier.weight(1f))
        else TextInput("实际次数", reps, { reps = it }, numeric = true, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = {
            viewModel.action {
                val target = input()
                checkTarget(target); viewModel.completeSet(sessionId, item.id, index, target)
            }
        }) { Text("完成一组") }
        TextButton(onClick = { viewModel.action { more(input()) } }) { Text("调整读数") }
        if (exercise.measurement == "seconds") TextButton(onClick = {
            viewModel.action { viewModel.exerciseTimer(sessionId, item.id, index, intInput(seconds, "计时秒数")) }
        }) { Text("开始计时") }
    }
}
