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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.nullable
import top.e404.training.shared.*

internal fun workoutResultSummary(result: SetResult): String {
    fun reading(load: Load?): String? = load?.let { "${numericText(it.value)} ${it.unit}" }
    if (result.segments.isNotEmpty()) {
        return result.segments.joinToString("，") { listOfNotNull(reading(it.load), it.reps.toString()).joinToString(" × ") }
    }
    val reps = result.reps?.let { if (result.load == null) "$it 次" else it.toString() }
    return listOfNotNull(reading(result.load), reps, result.seconds?.let { "$it 秒" }).joinToString(" × ")
}

@Composable
internal fun WorkoutSetProgress(completed: Int, total: Int) {
    if (total == 0) return
    val colors = MaterialTheme.colorScheme
    val fraction = (completed.toFloat() / total).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(6.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        val radius = 2.dp.toPx()
        val length = size.width - radius * 2
        val start = Offset(radius, center.y)
        fun position(fraction: Float) = Offset(radius + length * fraction, center.y)
        drawLine(colors.surfaceContainerHighest, start, position(1f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        if (completed > 0) drawLine(colors.primary, start, position(fraction), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        for (group in 1..total) {
            val color = if (group <= completed) colors.onPrimary else colors.onSurfaceVariant
            drawCircle(color, radius, position(group.toFloat() / total))
        }
    }
}

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
                if (current.type != "rest" && current.notified && !current.alertDismissed) {
                    TextButton(onClick = { viewModel.action { viewModel.repository.dismissTimerAlert(current) } }) { Text("停止提醒") }
                }
                TextButton(onClick = {
                    if (current.type == "rest") viewModel.endRestTimer(current) else viewModel.stopTimer()
                }) { Text(if (current.type == "rest") "结束休息" else "结束计时") }
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
    var editingItemNotes by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var itemNotesDraft by rememberSaveable(session.id) { mutableStateOf("") }
    var selectingType by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var pendingItem by rememberSaveable(session.id, stateSaver = jsonSaver(PlanItem.serializer().nullable)) { mutableStateOf<PlanItem?>(null) }
    var selectedExercise by rememberSaveable(session.id, stateSaver = jsonSaver(Exercise.serializer().nullable)) { mutableStateOf<Exercise?>(null) }
    var correctingItemId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var correctingExercise by rememberSaveable(session.id, stateSaver = jsonSaver(Exercise.serializer().nullable)) { mutableStateOf<Exercise?>(null) }
    var correctingEquipmentId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var replacingItemId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        RestTimerCard(viewModel)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(session.items.filter { it.id !in session.skippedItems || resultsByItem[it.id].orEmpty().isNotEmpty() }, key = { it.id }) { item ->
                val exercise = session.exercises.single { it.id == item.exerciseId }
                val results = resultsByItem[item.id].orEmpty()
                val progress = progressByItem.getValue(item.id)
                val completedIndices = progress.completedSetIndices
                val completed = progress.targetSetIndices.count { it in completedIndices }
                val skipped = progress.skipped
                val targetCount = progress.targetSetIndices.size
                val nextIndex = nextWorkoutTargetIndex(progress)
                val isCurrent = item.id == currentItemId
                val isComplete = !skipped && targetCount > 0 && nextIndex == null
                val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
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
                var showItemActions by remember(session.id, item.id) { mutableStateOf(false) }
                var showCompleted by rememberSaveable(session.id, item.id) { mutableStateOf(false) }
                val editNotes = { editingItemNotes = item.id; itemNotesDraft = item.notes }
                Card(
                    Modifier.fillMaxWidth().then(
                        if (nextIndex != null && !isCurrent) Modifier.clickable { selectedItemId = item.id } else Modifier
                    ),
                    colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = contentColor)
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!isCurrent) ExerciseCardImage(
                            exercise, state, viewModel.repository,
                            Modifier.padding(top = 8.dp, end = 8.dp).size(56.dp)
                        )
                        Text(exercise.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (isComplete) Icon(Icons.Default.Check, contentDescription = "动作已完成", tint = completeColor)
                        if (item.notes.isNotBlank()) IconButton(onClick = editNotes) {
                            Icon(Icons.Default.Edit, contentDescription = "修改动作备注")
                        }
                        IconButton(onClick = { detail = exercise }) {
                            Icon(Icons.Default.Info, contentDescription = "查看${exercise.name}教学")
                        }
                    }
                    Column(
                        Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val actions: @Composable ((() -> Unit)?) -> Unit = { editActual ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (completed > 0) TextButton(onClick = { showCompleted = !showCompleted }) {
                                    Text(if (showCompleted) "收起记录" else "查看记录")
                                    Icon(
                                        if (showCompleted) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        null, Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                if (nextIndex != null) TextButton(onClick = {
                                    replacingItemId = item.id
                                    selectingType = exercise.type
                                }) { Text("更换动作") }
                                Box {
                                    IconButton(onClick = { showItemActions = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "更多动作操作")
                                    }
                                    DropdownMenu(showItemActions, { showItemActions = false }) {
                                        if (editActual != null) DropdownMenuItem(text = { Text("调整实际读数") }, onClick = {
                                            showItemActions = false
                                            editActual()
                                        })
                                        DropdownMenuItem(text = { Text("订正实际动作 / 器械") }, onClick = {
                                            showItemActions = false
                                            correctingItemId = item.id
                                            selectingType = exercise.type
                                        })
                                        if (item.notes.isBlank()) DropdownMenuItem(text = { Text("添加动作备注") }, onClick = {
                                            showItemActions = false
                                            editNotes()
                                        })
                                        if (isComplete) DropdownMenuItem(text = { Text("追加一组待执行目标") }, onClick = {
                                            showItemActions = false
                                            viewModel.appendSessionSet(session.id, item.id)
                                        })
                                    }
                                }
                            }
                            if (showCompleted) results.sortedBy { it.setIndex }.forEach { result ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(workoutResultSummary(result), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        actualDraft = null
                                        editingResult = item.id
                                        editingIndex = result.setIndex
                                    }) { Icon(Icons.Default.Edit, contentDescription = "修改实际记录") }
                                    IconButton(onClick = { viewModel.undoSet(session.id, item.id, result.setIndex) }) {
                                        Icon(painterResource(R.drawable.ic_workout_undo), contentDescription = "撤销完成")
                                    }
                                }
                            }
                        }
                        if (nextIndex != null && isCurrent) {
                            val equipment = item.equipmentId?.let { state.record(Kind.equipment, it)?.value<Equipment>() }
                            expandedState.SaveableStateProvider("${session.id}/${item.id}/$nextIndex") {
                                ActualSetRow(
                                    session, item, nextIndex, item.sets[nextIndex], exercise, equipment, viewModel,
                                    choices, completed, targetCount,
                                    image = { modifier -> ExerciseCardImage(
                                        exercise, state, viewModel.repository, modifier, animate = true
                                    ) },
                                    onExerciseTimer = { seconds ->
                                        runTimerAction(true) { viewModel.exerciseTimer(session.id, item.id, nextIndex, seconds) }
                                    },
                                    onCompleteSet = { actual ->
                                        val startsRest = willStartRestTimer(session, item.id, nextIndex, actual.restSeconds)
                                        runTimerAction(startsRest) { viewModel.completeSet(session.id, item.id, nextIndex, actual) }
                                    },
                                    onEditReadings = { draft ->
                                        actualDraft = draft
                                        editingResult = item.id
                                        editingIndex = nextIndex
                                    },
                                    actions = actions
                                )
                            }
                        } else {
                            if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                            WorkoutSetProgress(completed, targetCount)
                            actions(null)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selectingType = "strength" }) { Text("添加训练动作") }
                    OutlinedButton(onClick = { selectingType = "stretch" }) { Text("添加拉伸") }
                }
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
            val input = actualDraft ?: result?.let {
                target.copy(reps = it.reps, seconds = it.seconds, load = it.load, side = it.side)
            } ?: target
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
    editingItemNotes?.let { itemId ->
        FormDialog("动作备注", { editingItemNotes = null }, {
            viewModel.updateSessionItemNotes(session.id, itemId, itemNotesDraft)
            editingItemNotes = null
        }) {
            TextInput("仅属于本次训练此动作", itemNotesDraft, { itemNotesDraft = it }, singleLine = false)
        }
    }
    selectingType?.let { type ->
        val targets = session.items
            .filter { it.id !in session.skippedItems || session.results.any { result -> result.itemId == it.id } }
            .flatMap { item ->
                session.exercises.single { it.id == item.exerciseId }.let {
                    if (it.type == "strength") it.muscles + it.secondaryMuscles else emptyList()
                }
            }.toSet()
        ExercisePicker(state, viewModel.repository, session.snapshot?.gymId, targets,
            if (correctingItemId != null) null else type,
            { selectingType = null; correctingItemId = null; replacingItemId = null }) { exercise ->
            selectingType = null
            val correctingId = correctingItemId
            val replacingId = replacingItemId
            when {
                correctingId != null -> {
                    val oldItem = session.items.single { it.id == correctingId }
                    val equipment = state.records(Kind.equipment).map { it.value<Equipment>() }
                        .filter { it.gymId == session.snapshot?.gymId && exercise.equipmentTags.firstOrNull() in it.tags }
                    correctingExercise = exercise
                    correctingEquipmentId = oldItem.equipmentId.takeIf { id -> equipment.any { it.id == id } }
                }
                replacingId != null -> {
                    val original = session.items.single { it.id == replacingId }
                    val remaining = original.sets.indices.count { index ->
                        session.results.none { it.itemId == replacingId && it.setIndex == index }
                    }
                    selectedExercise = exercise
                    pendingItem = newPlanItem(exercise, session.snapshot?.gymId, state, remaining)
                }
                else -> {
                    selectedExercise = exercise
                    pendingItem = newPlanItem(exercise, session.snapshot?.gymId, state)
                }
            }
        }
    }
    correctingItemId?.let { itemId ->
        val exercise = correctingExercise
        if (exercise != null) {
            val equipment = state.records(Kind.equipment).map { it.value<Equipment>() }
                .filter { it.gymId == session.snapshot?.gymId && exercise.equipmentTags.firstOrNull() in it.tags }
            val equipmentChoices = listOf(null to "不指定器械") + equipment.map { it.id to it.name }
            if (SOURCE_UNKNOWN_EQUIPMENT_TAG !in exercise.equipmentTags) {
                FormDialog("订正实际动作", {
                    correctingExercise = null; correctingItemId = null
                }, {
                    viewModel.correctSessionExercise(session.id, itemId, exercise, correctingEquipmentId)
                    correctingExercise = null; correctingItemId = null
                }) {
                    Text("只更正本次实际动作与器械；完成组读数、组数、时间和开始时计划保持不变。", style = MaterialTheme.typography.bodySmall)
                    if (exercise.equipmentTags.isNotEmpty()) Choice("实际器械", correctingEquipmentId, equipmentChoices, { correctingEquipmentId = it })
                }
            } else FormDialog("订正实际动作", {
                correctingExercise = null; correctingItemId = null
            }, {
                viewModel.correctSessionExercise(session.id, itemId, exercise, null)
                correctingExercise = null; correctingItemId = null
            }) {
                Text("器械条件未知；本次记录不关联具体器械。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    pendingItem?.let { item ->
        val exercise = selectedExercise ?: return@let
        val replacingId = replacingItemId
        val originalPosition = replacingId?.let { id -> session.items.indexOfFirst { it.id == id } }
        val positions = if (originalPosition != null) listOf(
            originalPosition to "原动作位置，仅替换未完成部分"
        ) else (0..session.items.size).map { position ->
            position to if (position == 0) "放在最前面" else "放在 ${session.exercises.single { it.id == session.items[position - 1].exerciseId }.name} 之后"
        }
        PlanItemEditor(item, exercise, session.snapshot?.gymId, state, viewModel, positions,
            originalPosition ?: session.items.size, {
                pendingItem = null; selectedExercise = null; replacingItemId = null
            },
            allowMeasurementChange = exercise.id.startsWith("smartworkout_")
        ) { revised, position ->
            if (replacingId != null) viewModel.replaceSessionExercise(session.id, replacingId, revised, exercise) { savedId ->
                selectedItemId = savedId
            }
            else viewModel.addSessionExercise(session.id, revised, exercise, position)
            pendingItem = null
            selectedExercise = null
            replacingItemId = null
        }
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
                        ?: Load(value, input.unit, "unknown", "external")
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
    session: Session, item: PlanItem, index: Int, planned: SetTarget, exercise: Exercise, equipment: Equipment?,
    viewModel: TrainingViewModel, choices: DeviceChoices, completed: Int, targetCount: Int,
    image: @Composable (Modifier) -> Unit, onCompleteSet: (SetTarget) -> Unit,
    onExerciseTimer: (Int) -> Unit, onEditReadings: (SetTarget) -> Unit,
    actions: @Composable ((() -> Unit)?) -> Unit
) {
    val sessionId = session.id
    val previous = session.results.asSequence()
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
    var amount by rememberSaveable(sessionId, item.id, index) { mutableStateOf(inputGoal.load?.let { numericText(it.value) } ?: "0") }
    var unit by rememberSaveable(sessionId, item.id, index) { mutableStateOf(loadTemplate?.unit ?: equipment?.unit ?: "kg") }
    var measurementText by rememberSaveable(sessionId, item.id, index) {
        mutableStateOf(
            if (measurement == "seconds") (inputGoal.seconds ?: planned.seconds)?.toString().orEmpty()
            else (inputGoal.reps ?: planned.reps)?.toString().orEmpty()
        )
    }
    var submitting by remember(sessionId, item.id, index) { mutableStateOf(false) }
    val persisted = session.results.any { it.itemId == item.id && it.setIndex == index }
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(persisted, message) {
        if (persisted || message != null) submitting = false
    }
    val timed = measurement == "seconds"
    fun input(): SetTarget {
        val value = doubleInput(amount, "重量")
        val load = when {
            loadTemplate != null -> loadTemplate.copy(value = value, unit = unit)
            exercise.type == "strength" || value != 0.0 -> Load(value, unit, equipment?.meaning ?: "total")
            else -> null
        }
        return inputGoal.copy(
            reps = if (timed) null else intInput(measurementText, "实际次数"), repsMax = null,
            seconds = if (timed) intInput(measurementText, "实际秒数") else null,
            load = load, restSeconds = planned.restSeconds
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val imageWidth = (maxWidth - 200.dp).coerceIn(64.dp, 112.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            image(Modifier.width(imageWidth).height(106.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val field = Modifier.width(128.dp)
                WorkoutWeightRocker(
                    amount, { amount = it }, unit,
                    step = choices.weightSteps[weightStepKey(exercise.id, item.equipmentId, unit)] ?: 1.0,
                    onUnitClick = { unit = if (unit == "kg") "lb" else "kg" }
                )
                WorkoutRockerInput(measurementText, { measurementText = it }, if (timed) "秒" else "次", field)
            }
        }
    }
    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
    WorkoutSetProgress(completed, targetCount)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !submitting && !persisted, modifier = Modifier.weight(1f), onClick = {
            submitting = true
            viewModel.dismissMessage()
            viewModel.action {
                val target = input()
                checkTarget(target)
                onCompleteSet(target)
            }
        }) { Text(if (submitting) "正在保存…" else "完成一组") }
        if (timed) TextButton(onClick = {
            viewModel.action { onExerciseTimer(intInput(measurementText, "计时秒数")) }
        }) { Text("开始计时") }
    }
    actions(if (submitting || persisted) null else { ->
        viewModel.dismissMessage()
        viewModel.action {
            val target = input()
            checkTarget(target)
            onEditReadings(target)
        }
    })
}
