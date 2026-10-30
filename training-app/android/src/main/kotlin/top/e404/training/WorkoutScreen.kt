package top.e404.training

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
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

internal data class WorkoutItemProgress(
    val itemId: String,
    val targetSetIndices: List<Int>,
    val completedSetIndices: Set<Int>,
    val skipped: Boolean
)

internal fun hasIncompleteWorkoutTarget(item: WorkoutItemProgress): Boolean =
    !item.skipped && item.targetSetIndices.any { it !in item.completedSetIndices }

internal fun resolveCurrentWorkoutItem(
    items: List<WorkoutItemProgress>,
    selectedItemId: String?
): String? =
    items.firstOrNull { it.itemId == selectedItemId && hasIncompleteWorkoutTarget(it) }?.itemId
        ?: items.firstOrNull(::hasIncompleteWorkoutTarget)?.itemId

internal fun nextWorkoutTargetIndex(item: WorkoutItemProgress): Int? =
    if (item.skipped) null else item.targetSetIndices.firstOrNull { it !in item.completedSetIndices }

private fun willStartRestTimer(session: Session, itemId: String, setIndex: Int, restSeconds: Int): Boolean =
    restSeconds > 0 && session.items.any { item ->
        item.id !in session.skippedItems && item.sets.indices.any { candidateIndex ->
            if (item.id == itemId && candidateIndex == setIndex) false
            else session.results.none { it.itemId == item.id && it.setIndex == candidateIndex }
        }
    }

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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.displayText(now), style = MaterialTheme.typography.headlineMedium)
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
    val context = LocalContext.current
    val preferences = remember { (context.applicationContext as TrainingApplication).preferences }
    val choices by preferences.data.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Exercise?>(null) }
    var editingResult by rememberSaveable { mutableStateOf<String?>(null) }
    var editingIndex by rememberSaveable { mutableIntStateOf(0) }
    var actualDraft by rememberSaveable(stateSaver = jsonSaver(SetTarget.serializer().nullable)) { mutableStateOf<SetTarget?>(null) }
    var finishMode by rememberSaveable { mutableStateOf<String?>(null) }
    var editingNotes by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(session.notes) }
    var selectedItemId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    val expandedState = rememberSaveableStateHolder()
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var showTimerPermissionDialog by remember { mutableStateOf(false) }
    var pendingTimerAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun continuePendingTimerAction() {
        permissionRefresh++
        val action = pendingTimerAction
        pendingTimerAction = null
        showTimerPermissionDialog = false
        action?.invoke()
    }
    fun onPermissionFlowReturned() {
        val notificationReadyNow = TimerEngine.canNotify(context)
        val exactReadyNow = TimerEngine.canSchedule(context)
        permissionRefresh++
        if (notificationReadyNow && exactReadyNow) continuePendingTimerAction()
    }
    fun runTimerAction(needsTimer: Boolean, action: () -> Unit) {
        if (!needsTimer || (TimerEngine.canNotify(context) && TimerEngine.canSchedule(context))) action()
        else {
            pendingTimerAction = action
            showTimerPermissionDialog = true
        }
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onPermissionFlowReturned()
    }
    val openNotificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPermissionFlowReturned()
    }
    val openExactSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPermissionFlowReturned()
    }
    val notificationReady = remember(permissionRevision, permissionRefresh) { TimerEngine.canNotify(context) }
    val exactReady = remember(permissionRevision, permissionRefresh) { TimerEngine.canSchedule(context) }
    val resultsByItem = remember(session.results) { session.results.groupBy { it.itemId } }
    val itemProgress = remember(session.items, session.results, session.skippedItems) {
        session.items.map { item ->
            val completedIndices = resultsByItem[item.id].orEmpty().mapTo(hashSetOf()) { it.setIndex }
            val skipped = item.id in session.skippedItems
            WorkoutItemProgress(
                item.id,
                if (skipped) completedIndices.sorted() else item.sets.indices.toList(),
                completedIndices,
                skipped
            )
        }
    }
    val progressByItem = remember(itemProgress) { itemProgress.associateBy { it.itemId } }
    val currentItemId = resolveCurrentWorkoutItem(itemProgress, selectedItemId)
    LaunchedEffect(session.id, currentItemId) {
        if (selectedItemId != currentItemId) selectedItemId = currentItemId
    }
    val done = itemProgress.sumOf { progress -> progress.targetSetIndices.count { it in progress.completedSetIndices } }
    val total = itemProgress.sumOf { it.targetSetIndices.size }
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
        RestTimerCard(viewModel)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(session.name, style = MaterialTheme.typography.headlineSmall)
                Text("已完成 $done / $total 组 · ${session.startedAt?.let(::localDateTime) ?: session.localDate}")
            }
            items(session.items, key = { it.id }) { item ->
                val exercise = session.exercises.single { it.id == item.exerciseId }
                val results = resultsByItem[item.id].orEmpty()
                val progress = progressByItem.getValue(item.id)
                val completedIndices = progress.completedSetIndices
                val completed = progress.targetSetIndices.count { it in completedIndices }
                val skipped = progress.skipped
                val targetCount = progress.targetSetIndices.size
                val nextIndex = nextWorkoutTargetIndex(progress)
                val previousResult = results.filter { it.setIndex < (nextIndex ?: 0) }.maxByOrNull { it.setIndex }
                val inputWeightUnit = previousResult?.segments?.lastOrNull()?.load?.unit
                    ?: previousResult?.load?.unit
                    ?: nextIndex?.let { item.sets.getOrNull(it)?.load?.unit } ?: ""
                val isCurrent = item.id == currentItemId
                val isComplete = !skipped && targetCount > 0 && nextIndex == null
                val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                val currentColor = if (darkTheme) Color(0xFF90CAF9) else Color(0xFF1565C0)
                val completeColor = if (darkTheme) Color(0xFF81C784) else Color(0xFF2E7D32)
                val cardColor = when {
                    isCurrent -> if (darkTheme) Color(0xFF17324D) else Color(0xFFE3F2FD)
                    isComplete -> if (darkTheme) Color(0xFF1B3A25) else Color(0xFFE8F5E9)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val contentColor = when {
                    isCurrent -> if (darkTheme) Color(0xFFD6E9FF) else Color(0xFF0D47A1)
                    isComplete -> if (darkTheme) Color(0xFFD9F2DC) else Color(0xFF1B5E20)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(Modifier.fillMaxWidth().clickable(enabled = nextIndex != null) { selectedItemId = item.id },
                    colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = contentColor)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(exercise.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (isCurrent) Text("当前动作", style = MaterialTheme.typography.labelMedium, color = currentColor)
                        if (isComplete) Icon(Icons.Default.Check, contentDescription = "动作已完成", tint = completeColor)
                        IconButton(onClick = { detail = exercise }) {
                            Icon(Icons.Default.Info, contentDescription = "查看${exercise.name}教学")
                        }
                    }
                    val samePlan = item.sets.distinct().size <= 1
                    Text("计划 · ${item.sets.size}组 · ${if (samePlan) item.sets.firstOrNull()?.let(::targetText) ?: "未设置目标" else "各组目标不同"}", style = MaterialTheme.typography.bodySmall)
                    if (!samePlan) {
                        var showPlan by rememberSaveable(session.id, item.id) { mutableStateOf(false) }
                        TextButton(onClick = { showPlan = !showPlan }) { Text(if (showPlan) "收起各组计划" else "查看各组计划") }
                        if (showPlan) item.sets.forEachIndexed { index, target -> Text("第 ${index + 1} 组 · ${targetText(target)}", style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("$completed/$targetCount 组", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { if (targetCount == 0) 0f else (completed.toFloat() / targetCount).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (item.notes.isNotBlank() && item.notes != exercise.name) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                    if (nextIndex != null && !isCurrent) TextButton(onClick = { selectedItemId = item.id }) { Text("切换到此动作") }
                    if (completed > 0) {
                        var showCompleted by rememberSaveable(session.id, item.id) { mutableStateOf(false) }
                        TextButton(onClick = { showCompleted = !showCompleted }) {
                            Text(if (showCompleted) "收起已完成 $completed 组" else "查看已完成 $completed 组")
                        }
                        if (showCompleted) results.sortedBy { it.setIndex }.forEach { result ->
                            HorizontalDivider()
                            Text("第 ${result.setIndex + 1} 组实际 · ${resultText(result)}", style = MaterialTheme.typography.bodySmall)
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
                        nextIndex != null && isCurrent -> {
                            val target = item.sets[nextIndex]
                            expandedState.SaveableStateProvider("${session.id}/${item.id}/$nextIndex") {
                                ActualSetRow(session, item, nextIndex, target, exercise, viewModel,
                                    historicalLoads[exercise.id to item.equipmentId].orEmpty(),
                                    choices.weightSteps[weightStepKey(exercise.id, item.equipmentId, inputWeightUnit)] ?: 1.0,
                                    { value -> preferences.weightStep(weightStepKey(exercise.id, item.equipmentId, inputWeightUnit), value) },
                                    onExerciseTimer = { seconds ->
                                        runTimerAction(true) { viewModel.exerciseTimer(session.id, item.id, nextIndex, seconds) }
                                    },
                                    onCompleteSet = { actual ->
                                        val startsRest = willStartRestTimer(session, item.id, nextIndex, actual.restSeconds)
                                        runTimerAction(startsRest) { viewModel.completeSet(session.id, item.id, nextIndex, actual) }
                                    }) { draft ->
                                    actualDraft = draft
                                    editingResult = item.id
                                    editingIndex = nextIndex
                                }
                            }
                        }
                        nextIndex != null -> Text("下一组待执行 · 第 ${nextIndex + 1} 组", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            Text(if (isComplete) "该动作计划组已完成。" else "暂无待执行目标。", style = MaterialTheme.typography.bodySmall)
                            if (completed > 0) TextButton(onClick = { viewModel.appendSessionSet(session.id, item.id) }) { Text("追加一组待执行目标") }
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
        val exercise = session.exercises.single { it.id == item.exerciseId }
        if (result?.segments?.isNotEmpty() == true) {
            WorkoutSegmentResultEditor(session.id, item.id, editingIndex, result, exercise, item.equipmentId,
                preferences, choices, viewModel) { editingResult = null }
        } else {
            val target = item.sets.getOrNull(editingIndex) ?: SetTarget(
                reps = result?.segments?.lastOrNull()?.reps ?: result?.reps,
                seconds = result?.seconds,
                load = result?.segments?.lastOrNull()?.load ?: result?.load,
                side = result?.side ?: "both",
                restSeconds = if (exercise.type == "stretch") 15 else 90
            )
            val input = actualDraft ?: target.copy(reps = result?.reps ?: target.reps, seconds = result?.seconds ?: target.seconds,
                load = result?.load ?: target.load, side = result?.side ?: target.side)
            val equipment = item.equipmentId?.let { state.record(Kind.equipment, it)?.value<Equipment>() }
            SetEditor(input, exercise, equipment, true, viewModel,
                { editingResult = null; actualDraft = null }) { actual, _ ->
                if (result == null) {
                    val startsRest = willStartRestTimer(session, id, editingIndex, actual.restSeconds)
                    runTimerAction(startsRest) { viewModel.completeSet(session.id, id, editingIndex, actual) }
                } else viewModel.reviseSet(session.id, id, editingIndex, actual)
                editingResult = null
                actualDraft = null
            }
        }
    }
    finishMode?.let { mode ->
        AlertDialog(onDismissRequest = { finishMode = null }, title = { Text(if (mode == "complete") "完成本次训练？" else "取消本次训练？") },
            text = { Text(if (mode == "complete") "未完成的计划组不会计为完成。" else "已完成的组仍会保留；有实际组时按练过推进，零组取消不推进。") },
            confirmButton = { TextButton(onClick = { viewModel.finish(session.id, mode == "cancel"); finishMode = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { finishMode = null }) { Text("继续训练") } })
    }
    if (showTimerPermissionDialog) {
        AlertDialog(
            onDismissRequest = ::continuePendingTimerAction,
            title = { Text("计时提醒权限未就绪") },
            text = {
                Text(buildString {
                    append("训练和计时仍可继续。")
                    if (!notificationReady) append("通知未具备，到时可能看不到应用通知。")
                    if (!exactReady) append("精确定时唤醒未具备，系统省电时提醒可能延迟。")
                    append("授权后会继续刚才的操作；拒绝或关闭设置也不会阻止训练记录。")
                })
            },
            confirmButton = {
                TextButton(onClick = ::continuePendingTimerAction) { Text("继续操作") }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (!notificationReady && Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        TextButton(onClick = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("请求通知权限")
                        }
                    }
                    if (!notificationReady) TextButton(onClick = {
                        openNotificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("打开通知设置") }
                    if (!exactReady && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                        openExactSettings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text("定时授权") }
                }
            }
        )
    }
    if (editingNotes) FormDialog("训练反馈", { editingNotes = false }, { viewModel.updateSessionNotes(session.id, notes); editingNotes = false }) {
        TextInput("实际感受与需要调整的内容", notes, { notes = it }, singleLine = false)
    }
}

private data class WorkoutSegmentDraft(val reps: String, val load: String, val unit: String)

@Composable private fun WorkoutSegmentResultEditor(
    sessionId: String,
    itemId: String,
    setIndex: Int,
    result: SetResult,
    exercise: Exercise,
    equipmentId: String?,
    preferences: AppPreferences,
    choices: DeviceChoices,
    viewModel: TrainingViewModel,
    onDismiss: () -> Unit
) {
    var segments by remember(result) {
        mutableStateOf(result.segments.map { segment ->
            WorkoutSegmentDraft(segment.reps.toString(), segment.load?.let { numericText(it.value) }.orEmpty(), segment.load?.unit ?: "kg")
        })
    }
    FormDialog("修改第 ${setIndex + 1} 组实际记录", onDismiss, {
        viewModel.action {
            val revised = result.segments.mapIndexed { index, original ->
                val input = segments[index]
                val repetitions = input.reps.toIntOrNull() ?: throw IllegalArgumentException("每段次数须为正整数")
                require(repetitions > 0) { "每段次数须为正整数" }
                val load = if (input.load.isBlank()) null else {
                    val value = doubleInput(input.load, "重量")
                    require(value.isFinite() && value >= 0.0) { "重量必须为非负有限数值" }
                    original.load?.copy(value = value, unit = input.unit)
                        ?: Load(value, input.unit, "unknown", exercise.loadMode)
                }
                SetSegment(repetitions, load)
            }
            viewModel.repository.updateCompletedResult(sessionId, itemId, setIndex, revised.sumOf { it.reps }, result.seconds, null, revised)
            onDismiss()
        }
    }) {
        Text("分段数量保持不变；分段次数合计同步更新整组次数。", style = MaterialTheme.typography.bodySmall)
        segments.forEachIndexed { index, draft ->
            Text("第 ${index + 1} 段", style = MaterialTheme.typography.labelMedium)
            WorkoutCountInput(draft.reps, { value ->
                segments = segments.toMutableList().also { it[index] = draft.copy(reps = value) }
            })
            WorkoutWeightInput(draft.load, { value ->
                segments = segments.toMutableList().also { it[index] = draft.copy(load = value) }
            }, draft.unit, choices.weightSteps[weightStepKey(exercise.id, equipmentId, draft.unit)] ?: 1.0, { value ->
                preferences.weightStep(weightStepKey(exercise.id, equipmentId, draft.unit), value)
            })
            Choice("第 ${index + 1} 段原始单位", draft.unit, listOf("kg" to "公斤 kg", "lb" to "磅 lb"), { value ->
                segments = segments.toMutableList().also { it[index] = draft.copy(unit = value) }
            })
        }
    }
}

@Composable private fun ActualSetRow(
    session: Session, item: PlanItem, index: Int, planned: SetTarget, exercise: Exercise, viewModel: TrainingViewModel,
    historicalLoads: List<Load>, step: Double, onStepChange: (Double) -> Unit,
    onCompleteSet: (SetTarget) -> Unit, onExerciseTimer: (Int) -> Unit, onEditReadings: (SetTarget) -> Unit
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
    val measurement = targetMeasurement(planned, exercise.measurement)
    val inputGoal = rememberSaveable(sessionId, item.id, index, saver = jsonSaver(SetTarget.serializer())) { inherited }
    val loadTemplate = inputGoal.load ?: planned.load
    var amount by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.load?.let { numericText(it.value) } ?: "") }
    var reps by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.reps?.toString() ?: "") }
    var seconds by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.seconds?.toString() ?: "") }
    var submitting by remember(sessionId, item.id, index) { mutableStateOf(false) }
    val persisted = session.results.any { it.itemId == item.id && it.setIndex == index }
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(persisted, message) {
        if (persisted || message != null) submitting = false
    }
    fun input() = inputGoal.copy(reps = if (measurement == "reps") intInput(reps, "实际次数") else null, repsMax = null,
        seconds = if (measurement == "seconds") intInput(seconds, "实际秒数") else null,
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
    val commonWeights = remember(commonLoads) { commonLoads.map { it.value } }
    if (loadTemplate != null) {
        WorkoutWeightInput(amount, { amount = it }, loadTemplate.unit, step, onStepChange, commonWeights = commonWeights)
    }
    if (measurement == "seconds") TextInput("实际秒数", seconds, { seconds = it }, numeric = true)
    else WorkoutCountInput(reps, { reps = it })
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = !submitting && !persisted, onClick = {
            submitting = true
            viewModel.dismissMessage()
            viewModel.action {
                val target = input()
                checkTarget(target)
                onCompleteSet(target)
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
        if (measurement == "seconds") TextButton(onClick = {
            viewModel.action { onExerciseTimer(intInput(seconds, "计时秒数")) }
        }) { Text("开始计时") }
    }
}
