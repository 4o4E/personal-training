package top.e404.training

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import top.e404.training.shared.*

@Composable fun GymEditor(original: Document, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    var draft by rememberSaveable(stateSaver = jsonSaver(Gym.serializer())) { mutableStateOf(original.value<Gym>()) }
    FormDialog("健身房", onDismiss, { viewModel.save(document(Kind.gym, draft.id, draft), original, onDismiss) }) {
        TextInput("健身房名称", draft.name, { draft = draft.copy(name = it) })
        TextInput("备注", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
    }
}
@Composable fun EquipmentEditor(original: Document, state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    var draft by rememberSaveable(stateSaver = jsonSaver(Equipment.serializer())) { mutableStateOf(original.value<Equipment>()) }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var tagsOpen by remember { mutableStateOf(false) }
    val names = equipmentNames()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> photoUri = uri?.toString() }
    val gyms = remember(state.documents[Kind.gym]) { state.records(Kind.gym).map { it.value<Gym>() } }
    FormDialog("器械资料", onDismiss, {
        viewModel.action {
            require(draft.name.isNotBlank() && draft.tags.isNotEmpty()) { "请填写器械名称并选择器械类别" }
            val photo = photoUri?.let { viewModel.repository.addPhoto(Uri.parse(it), "${draft.name} · 器械图片") }
            viewModel.repository.save(document(Kind.equipment, draft.id, draft.copy(mediaId = photo?.id ?: draft.mediaId)), original)
            onDismiss()
        }
    }) {
        Choice("所属健身房", draft.gymId, gyms.map { it.id to it.name }, { draft = draft.copy(gymId = it) })
        TextInput("器械名称", draft.name, { draft = draft.copy(name = it) })
        OutlinedButton(onClick = { tagsOpen = true }) { Text("器械类别（${draft.tags.size}）") }
        Text(draft.tags.joinToString("、") { names[it] ?: it })
        Choice("器械标示单位", draft.unit, listOf("kg" to "公斤 kg", "lb" to "磅 lb"), { draft = draft.copy(unit = it) })
        Choice("读数含义", draft.meaning, listOf("total" to "总重量", "per_hand" to "单只重量", "per_side" to "单侧配重"), { draft = draft.copy(meaning = it) })
        TextInput("使用方式", draft.instructions, { draft = draft.copy(instructions = it) }, singleLine = false)
        TextInput("其他备注", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
        if (photoUri != null) AsyncImage(Uri.parse(photoUri), "已选择的器械图片", Modifier.fillMaxWidth().height(220.dp))
        else draft.mediaId?.let { id -> state.record(Kind.media, id)?.value<Media>()?.let { MediaImage(it, viewModel.repository, Modifier.fillMaxWidth().height(220.dp)) } }
        OutlinedButton(onClick = { picker.launch("image/*") }) { Text("选择器械图片") }
        Text("器械单位只作录入参考。各组保留自己的原始读数，不自动换算。", style = MaterialTheme.typography.bodySmall)
    }
    if (tagsOpen) MultiChoiceDialog("选择器械支持的类别", names.entries.map { it.key to it.value }.sortedBy { it.second }, draft.tags.toSet(), { tagsOpen = false }) {
        draft = draft.copy(tags = it.toList()); tagsOpen = false
    }
}

@Composable fun ProgramEditor(original: Document, state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    var draft by rememberSaveable(stateSaver = jsonSaver(Program.serializer())) { mutableStateOf(original.value<Program>()) }
    var selecting by rememberSaveable { mutableStateOf<String?>(null) }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).map { it.value<Muscle>() } }
    FormDialog("配置 ${draft.slots.size} 分化", onDismiss, { viewModel.save(document(Kind.program, draft.id, draft), original, onDismiss) }) {
        TextInput("方案名称", draft.name, { draft = draft.copy(name = it) })
        Text("按以下顺序轮换。完成一次训练后推进，休息不会跳过训练部位。")
        draft.slots.forEachIndexed { index, slot ->
            InfoCard("第 ${index + 1} 项训练") {
                TextInput("训练名称", slot.name, { name -> draft = draft.copy(slots = draft.slots.map { if (it.id == slot.id) it.copy(name = name) else it }) })
                Text("部位：" + slot.muscles.joinToString("、") { id -> muscles.firstOrNull { it.id == id }?.name ?: id })
                TextButton(onClick = { selecting = slot.id }) { Text("选择训练部位") }
                Row {
                    TextButton(onClick = { if (index > 0) draft = draft.copy(slots = draft.slots.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) { Text("上移") }
                    TextButton(onClick = { if (index < draft.slots.lastIndex) draft = draft.copy(slots = draft.slots.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < draft.slots.lastIndex) { Text("下移") }
                    TextButton(onClick = { draft = draft.copy(slots = draft.slots.filterNot { it.id == slot.id }) }, enabled = draft.slots.size > 1) { Text("移除") }
                }
            }
        }
        OutlinedButton(onClick = { draft = draft.copy(slots = draft.slots + SplitSlot(newId(), "训练${draft.slots.size + 1}", emptyList())) }) { Text("增加一项训练") }
    }
    selecting?.let { id ->
        val slot = draft.slots.single { it.id == id }
        MultiChoiceDialog("选择 ${slot.name} 的训练部位", muscles.map { it.id to it.name }, slot.muscles.toSet(), { selecting = null }) { selected ->
            draft = draft.copy(slots = draft.slots.map { if (it.id == id) it.copy(muscles = selected.toList()) else it }); selecting = null
        }
    }
}

@Composable fun SetEditor(
    original: SetTarget, exercise: Exercise, equipment: Equipment?, actual: Boolean,
    viewModel: TrainingViewModel, onDismiss: () -> Unit, onSave: (SetTarget, Boolean) -> Unit
) {
    var reps by rememberSaveable { mutableStateOf(original.reps?.toString() ?: "") }
    var maximum by rememberSaveable { mutableStateOf(original.repsMax?.toString() ?: "") }
    var seconds by rememberSaveable { mutableStateOf(original.seconds?.toString() ?: "") }
    var rest by rememberSaveable { mutableStateOf(original.restSeconds.toString()) }
    var hasLoad by rememberSaveable { mutableStateOf(original.load != null) }
    var weight by rememberSaveable { mutableStateOf(original.load?.let { numericText(it.value) } ?: "") }
    var unit by rememberSaveable { mutableStateOf(original.load?.unit ?: equipment?.unit ?: "kg") }
    var meaning by rememberSaveable { mutableStateOf(original.load?.meaning ?: equipment?.meaning ?: "total") }
    var mode by rememberSaveable { mutableStateOf(original.load?.mode ?: if (exercise.loadMode == "assistance") "assistance" else "external") }
    var side by rememberSaveable { mutableStateOf(original.side) }
    var applyAll by rememberSaveable { mutableStateOf(false) }
    val measurement = targetMeasurement(original, exercise.measurement)
    val context = LocalContext.current
    val preferences = remember { (context.applicationContext as TrainingApplication).preferences }
    val choices by preferences.data.collectAsStateWithLifecycle()
    FormDialog(if (actual) "实际完成情况" else "计划组要求", onDismiss, {
        viewModel.action {
            val target = SetTarget(
                reps = if (measurement == "reps") intInput(reps, "次数") else null,
                repsMax = if (!actual && maximum.isNotBlank() && measurement == "reps") intInput(maximum, "次数上限") else null,
                seconds = if (measurement == "seconds") intInput(seconds, "时长") else null,
                load = if (hasLoad) Load(doubleInput(weight, "重量"), unit, meaning, mode) else null,
                side = side, restSeconds = intInput(rest, "休息秒数")
            )
            checkTarget(target); onSave(target, applyAll)
        }
    }, confirmLabel = if (actual) "完成并记录" else "保存") {
        if (actual) Text("新组确认后保存实际结果并按该组休息时长计时；修改已完成组不会重启计时。", style = MaterialTheme.typography.bodySmall)
        Text(exercise.name, style = MaterialTheme.typography.titleMedium)
        if (measurement == "reps") {
            if (actual) WorkoutCountInput(reps, { reps = it })
            else TextInput("目标次数或范围下限", reps, { reps = it }, numeric = true)
            if (!actual) TextInput("次数范围上限（可不填写）", maximum, { maximum = it }, numeric = true)
        } else TextInput(if (actual) "实际时长（秒）" else "目标时长（秒）", seconds, { seconds = it }, numeric = true)
        Choice("左右侧", side, listOf("both" to "双侧 / 不分侧", "left" to "左侧", "right" to "右侧"), { side = it })
        Row { Checkbox(hasLoad, { hasLoad = it }); Text("填写外部负重或助力读数", Modifier.padding(top = 12.dp)) }
        if (hasLoad) {
            if (actual) {
                val key = weightStepKey(exercise.id, equipment?.id, unit)
                WorkoutWeightInput(weight, { weight = it }, unit, choices.weightSteps[key] ?: 1.0,
                    { value -> preferences.weightStep(key, value) })
            } else TextInput("器械原始重量", weight, { weight = it }, numeric = true)
            Choice("原始单位", unit, listOf("kg" to "公斤 kg", "lb" to "磅 lb"), { unit = it })
            Choice("重量含义", meaning, listOf("total" to "总重量", "per_hand" to "单只重量", "per_side" to "单侧配重"), { meaning = it })
            Choice("负重或助力", mode, listOf("external" to "外部负重", "assistance" to "助力重量"), { mode = it })
        } else Text(if (exercise.bodyweight) "自重练习，不填写外部读数。" else "未填写重量不会被记录为零重量。", style = MaterialTheme.typography.bodySmall)
        TextInput("该组之后休息（秒，零表示不自动计时）", rest, { rest = it }, numeric = true)
        if (!actual) Row { Checkbox(applyAll, { applyAll = it }); Text("应用到该动作所有组", Modifier.padding(top = 12.dp)) }
    }
}
