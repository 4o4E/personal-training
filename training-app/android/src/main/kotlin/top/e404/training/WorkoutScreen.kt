package top.e404.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer, lifecycle) {
        val current = timer ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val currentTime = System.currentTimeMillis()
                now = currentTime
                if (current.type != "rest" && currentTime >= current.deadline) break
                val nextChange = if (currentTime < current.deadline) {
                    val seconds = (current.deadline - currentTime + 999) / 1000
                    current.deadline - (seconds - 1) * 1000
                } else {
                    current.deadline + (((currentTime - current.deadline) / 1000) + 1) * 1000
                }
                delay((nextChange - currentTime).coerceAtLeast(1))
            }
        }
    }
    timer?.let { current ->
        InfoCard(if (current.type == "rest") "组间休息" else "动作计时") {
            Text(current.label)
            Text(current.displayText(now), style = MaterialTheme.typography.headlineLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.notified && !current.alertDismissed) {
                    TextButton(onClick = { viewModel.action { viewModel.repository.dismissTimerAlert(current) } }) { Text("停止提醒") }
                }
                TextButton(onClick = viewModel::stopTimer) { Text(if (current.type == "rest") "结束休息" else "结束计时") }
            }
        }
    }
}

@Composable fun WorkoutScreen(
    session: Session, state: AppData, viewModel: TrainingViewModel, permissionRevision: Int
) {
    var detail by remember { mutableStateOf<Exercise?>(null) }
    var editingResult by rememberSaveable { mutableStateOf<String?>(null) }
    var editingIndex by rememberSaveable { mutableIntStateOf(0) }
    var actualDraft by rememberSaveable(stateSaver = jsonSaver(SetTarget.serializer().nullable)) { mutableStateOf<SetTarget?>(null) }
    var finishMode by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNotes by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(session.notes) }
    val context = LocalContext.current
    val reminderReady = remember(permissionRevision) { TimerEngine.canSchedule(context) && TimerEngine.canNotify(context) }
    val done = session.results.size
    val total = session.items.sumOf { item ->
        val completed = session.results.count { it.itemId == item.id }
        if (item.id in session.skippedItems) completed else maxOf(item.sets.size, completed)
    }
    val historicalLoads = remember(state.documents.getValue(Kind.session), session.id) {
        buildMap<Pair<String, String?>, MutableSet<Load>> {
            state.documents.getValue(Kind.session).values
                .filterNot { it.deleted || it.id == session.id }
                .forEach { document ->
                    val historical = document.value<Session>()
                    historical.items.forEach { historicalItem ->
                        val historicalExercise = historical.exercises.singleOrNull { it.id == historicalItem.exerciseId }
                        if (historicalExercise != null) {
                            val loads = historical.results.filter { it.itemId == historicalItem.id }
                                .flatMap { result -> result.segments.mapNotNull { it.load } + listOfNotNull(result.load) }
                            val key = historicalExercise.id to historicalItem.equipmentId
                            getOrPut(key) { linkedSetOf() }.addAll(loads)
                        }
                    }
                }
        }.mapValues { it.value.toList() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (!reminderReady) Text("通知显示或定时唤醒授权未就绪：训练计时仍由前台服务运行；授权状态会影响到时通知或系统唤醒，请在设置中查看。", color = MaterialTheme.colorScheme.error)
        RestTimerCard(viewModel)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(session.name, style = MaterialTheme.typography.headlineSmall)
                Text("已完成 $done / $total 组 · ${session.startedAt?.let(::localDateTime) ?: session.localDate}")
            }
            items(session.items, key = { it.id }) { item ->
                val exercise = session.exercises.single { it.id == item.exerciseId }
                val completed = session.results.count { it.itemId == item.id }
                val skipped = item.id in session.skippedItems
                val targetCount = if (skipped) completed else maxOf(item.sets.size, completed)
                var showCompleted by rememberSaveable(session.id, item.id) { mutableStateOf(false) }
                val nextIndex = item.sets.indices.firstOrNull { index ->
                    session.results.none { it.itemId == item.id && it.setIndex == index }
                }
                InfoCard(exercise.name) {
                    Text(if (exercise.type == "stretch") "拉伸 · $completed/$targetCount 组" else "力量训练 · $completed/$targetCount 组")
                    LinearProgressIndicator(progress = { if (targetCount == 0) 0f else (completed.toFloat() / targetCount).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    if (item.notes.isNotBlank()) Text(item.notes)
                    TextButton(onClick = { detail = exercise }) { Text("查看教学") }
                    if (completed > 0) {
                        TextButton(onClick = { showCompleted = !showCompleted }) {
                            Text(if (showCompleted) "收起已完成 $completed 组" else "查看已完成 $completed 组")
                        }
                    }
                    if (showCompleted) {
                        session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }.forEach { result ->
                            HorizontalDivider()
                            Text("第 ${result.setIndex + 1} 组：${resultText(result)}")
                            Row {
                                TextButton(onClick = {
                                    actualDraft = null
                                    editingResult = item.id
                                    editingIndex = result.setIndex
                                }) { Text("修改实际记录") }
                                TextButton(onClick = { viewModel.undoSet(session.id, item.id, result.setIndex) }) { Text("撤销完成") }
                            }
                        }
                    }
                    when {
                        skipped -> Text("剩余目标已由替代动作安排；已完成组保留在上方记录中。", style = MaterialTheme.typography.bodySmall)
                        nextIndex != null -> {
                            val target = item.sets[nextIndex]
                            HorizontalDivider()
                            Text("当前第 ${nextIndex + 1} 组 · ${sideText(target.side)}", style = MaterialTheme.typography.titleSmall)
                            Text("计划：${targetText(target)}", style = MaterialTheme.typography.bodySmall)
                            ActualSetRow(session, item, nextIndex, target, exercise, viewModel,
                                historicalLoads[exercise.id to item.equipmentId].orEmpty()) { draft ->
                                actualDraft = draft
                                editingResult = item.id
                                editingIndex = nextIndex
                            }
                        }
                        else -> {
                            Text("该动作计划组已完成。", style = MaterialTheme.typography.bodySmall)
                            if (completed > 0) {
                                TextButton(onClick = { viewModel.appendSessionSet(session.id, item.id) }) { Text("追加一组待执行目标") }
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { notes = session.notes; editingNotes = true }) { Text("记录训练反馈") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { finishMode = "complete" }, enabled = session.results.isNotEmpty()) { Text("完成本次训练") }
                    OutlinedButton(onClick = { finishMode = "cancel" }) { Text("取消本次训练") }
                }
            }
        }
    }
    detail?.let { ExerciseDetail(it, state, viewModel.repository) { detail = null } }
    editingResult?.let { id ->
        val item = session.items.single { it.id == id }
        val result = session.results.firstOrNull { it.itemId == id && it.setIndex == editingIndex }
        val target = item.sets.getOrNull(editingIndex) ?: SetTarget(
            reps = result?.segments?.lastOrNull()?.reps ?: result?.reps,
            seconds = result?.seconds,
            load = result?.segments?.lastOrNull()?.load ?: result?.load,
            side = result?.side ?: "both",
            restSeconds = if (session.exercises.single { it.id == item.exerciseId }.type == "stretch") 15 else 90
        )
        val input = actualDraft ?: target.copy(reps = result?.reps ?: target.reps, seconds = result?.seconds ?: target.seconds,
            load = result?.load ?: target.load, side = result?.side ?: target.side)
        val equipment = item.equipmentId?.let { state.record(Kind.equipment, it)?.value<Equipment>() }
        SetEditor(input, session.exercises.single { it.id == item.exerciseId }, equipment, true, viewModel,
        { editingResult = null; actualDraft = null }) { actual, _ ->
            if (result == null) viewModel.completeSet(session.id, id, editingIndex, actual)
            else viewModel.reviseSet(session.id, id, editingIndex, actual)
            editingResult = null
            actualDraft = null
        }
    }
    finishMode?.let { mode ->
        AlertDialog(onDismissRequest = { finishMode = null }, title = { Text(if (mode == "complete") "完成本次训练？" else "取消本次训练？") },
            text = { Text(if (mode == "complete") "未完成的计划组不会计为完成。" else "已完成的组仍会保留；有实际组时按练过推进，零组取消不推进。") },
            confirmButton = { TextButton(onClick = { viewModel.finish(session.id, mode == "cancel"); finishMode = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { finishMode = null }) { Text("继续训练") } })
    }
    if (editingNotes) FormDialog("训练反馈", { editingNotes = false }, { viewModel.updateSessionNotes(session.id, notes); editingNotes = false }) {
        TextInput("实际感受与需要调整的内容", notes, { notes = it }, singleLine = false)
    }
}

@Composable private fun ActualSetRow(
    session: Session, item: PlanItem, index: Int, planned: SetTarget, exercise: Exercise, viewModel: TrainingViewModel,
    historicalLoads: List<Load>, onEditReadings: (SetTarget) -> Unit
) {
    val sessionId = session.id
    val previous = session.results
        .filter { it.itemId == item.id && it.setIndex < index }
        .maxByOrNull { it.setIndex }
    val previousSegment = previous?.segments?.lastOrNull()
    val inherited = when {
        previousSegment != null -> planned.copy(reps = previousSegment.reps, load = previousSegment.load, side = previous.side)
        previous != null -> planned.copy(reps = previous.reps, seconds = previous.seconds, load = previous.load, side = previous.side)
        else -> planned
    }
    val inputGoal = rememberSaveable(sessionId, item.id, index, saver = jsonSaver(SetTarget.serializer())) { inherited }
    val loadTemplate = inputGoal.load ?: planned.load
    var amount by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.load?.let { numericText(it.value) } ?: "") }
    var reps by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.reps?.toString() ?: "") }
    var seconds by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.seconds?.toString() ?: "") }
    var expanded by remember { mutableStateOf(false) }
    var submitting by remember(sessionId, item.id, index) { mutableStateOf(false) }
    val persisted = session.results.any { it.itemId == item.id && it.setIndex == index }
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(persisted, message) {
        if (persisted || message != null) submitting = false
    }
    fun input() = inputGoal.copy(reps = if (exercise.measurement == "reps") intInput(reps, "实际次数") else null, repsMax = null,
        seconds = if (exercise.measurement == "seconds") intInput(seconds, "实际秒数") else null,
        load = if (amount.isBlank()) null else loadTemplate?.copy(value = doubleInput(amount, "重量")),
        restSeconds = planned.restSeconds)
    val commonLoads = remember(historicalLoads, session.items, session.results, item.id, loadTemplate?.unit) {
        buildList {
            addAll(historicalLoads)
            session.results.filter { it.itemId == item.id }.forEach { result ->
                addAll(result.segments.mapNotNull { it.load } + listOfNotNull(result.load))
            }
            addAll(item.sets.mapNotNull { it.load })
        }.filter { loadTemplate != null && it.unit == loadTemplate.unit }
            .distinctBy { it.value }.sortedBy { it.value }
    }
    if (loadTemplate != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextInput("重量 ${loadTemplate.unit}", amount, { amount = it }, numeric = true, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { expanded = true }, enabled = commonLoads.isNotEmpty()) { Text("常用") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    commonLoads.forEach { load ->
                        DropdownMenuItem(text = { Text("${numericText(load.value)} ${load.unit}") }, onClick = {
                            amount = numericText(load.value)
                            expanded = false
                        })
                    }
                }
            }
            TextButton(onClick = {
                viewModel.action {
                    val value = if (amount.isBlank()) 0.0 else doubleInput(amount, "重量")
                    check(value.isFinite() && value >= 0.0) { "重量必须是非负有限数值" }
                    amount = numericText((value - 1.0).coerceAtLeast(0.0))
                }
            }) { Text("−1") }
            TextButton(onClick = {
                viewModel.action {
                    val value = if (amount.isBlank()) 0.0 else doubleInput(amount, "重量")
                    check(value.isFinite() && value >= 0.0) { "重量必须是非负有限数值" }
                    val next = value + 1.0
                    check(next.isFinite()) { "重量必须是非负有限数值" }
                    amount = numericText(next)
                }
            }) { Text("+1") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (exercise.measurement == "seconds") TextInput("实际秒数", seconds, { seconds = it }, numeric = true, modifier = Modifier.weight(1f))
        else TextInput("实际次数", reps, { reps = it }, numeric = true, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = !submitting && !persisted, onClick = {
            submitting = true
            viewModel.dismissMessage()
            viewModel.action {
                val target = input()
                checkTarget(target)
                viewModel.completeSet(sessionId, item.id, index, target)
            }
        }) { Text(if (submitting) "正在保存…" else "完成一组") }
        TextButton(enabled = !submitting && !persisted, onClick = {
            viewModel.dismissMessage()
            viewModel.action {
                val target = input()
                checkTarget(target)
                onEditReadings(target)
            }
        }) { Text("调整实际读数") }
        if (exercise.measurement == "seconds") TextButton(onClick = {
            viewModel.action { viewModel.exerciseTimer(sessionId, item.id, index, intInput(seconds, "计时秒数")) }
        }) { Text("开始计时") }
    }
}
