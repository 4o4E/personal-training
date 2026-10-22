package top.e404.training

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.builtins.nullable
import java.time.LocalDate
import java.time.ZoneOffset
import top.e404.training.shared.*

@Composable fun PlanItemEditor(
    original: PlanItem, exercise: Exercise, gymId: String?, state: AppData, viewModel: TrainingViewModel,
    positions: List<Pair<Int, String>>, initialPosition: Int,
    onDismiss: () -> Unit, onSave: (PlanItem, Int) -> Unit
) {
    var draft by rememberSaveable(stateSaver = jsonSaver(PlanItem.serializer())) { mutableStateOf(original) }
    var position by rememberSaveable { mutableStateOf(initialPosition) }
    var editingSet by rememberSaveable { mutableStateOf<Int?>(null) }
    var details by remember { mutableStateOf(false) }
    val equipment = remember(state.documents[Kind.equipment], gymId, exercise.id) {
        state.records(Kind.equipment).map { it.value<Equipment>() }.filter { it.gymId == gymId && exercise.equipmentTags.firstOrNull() in it.tags }
    }
    FormDialog(exercise.name, onDismiss, {
        viewModel.action {
            require(draft.sets.isNotEmpty()) { "至少需要一组" }
            draft.sets.forEach(::checkTarget)
            onSave(draft, position)
        }
    }) {
        TextButton(onClick = { details = true }) { Text("查看动作教学与肌肉图") }
        Choice("加入位置", position, positions, { position = it })
        if (exercise.equipmentTags.isNotEmpty()) {
            Choice<String?>("使用的实际器械", draft.equipmentId,
                listOf(null to "不指定，保留计划中的原始读数") + equipment.map { it.id to "${it.name} · ${it.unit}" },
                { draft = draft.copy(equipmentId = it) })
            Text("更换器械不会自动换算或改写各组重量，请确认该器械的原始读数。", style = MaterialTheme.typography.bodySmall)
        }
        draft.sets.forEachIndexed { index, target ->
            InfoCard("第 ${index + 1} 组") {
                Text(targetText(target))
                Row {
                    TextButton(onClick = { editingSet = index }) { Text("编辑要求") }
                    TextButton(onClick = { draft = draft.copy(sets = draft.sets.filterIndexed { i, _ -> i != index }) }, enabled = draft.sets.size > 1) { Text("移除该组") }
                }
            }
        }
        OutlinedButton(onClick = { draft = draft.copy(sets = draft.sets + draft.sets.last()) }) { Text("增加一组") }
        TextInput("动作备注", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
    }
    editingSet?.let { index ->
        SetEditor(draft.sets[index], exercise, equipment.firstOrNull { it.id == draft.equipmentId }, false, viewModel, { editingSet = null }) { target, applyAll ->
            draft = draft.copy(sets = draft.sets.mapIndexed { i, old -> if (applyAll || i == index) target else old }); editingSet = null
        }
    }
    if (details) ExerciseDetail(exercise, state, viewModel.repository) { details = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlanEditor(original: Document, state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    var draft by rememberSaveable(stateSaver = jsonSaver(Plan.serializer())) { mutableStateOf(original.value<Plan>()) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var pickerType by rememberSaveable { mutableStateOf<String?>(null) }
    var replacingId by rememberSaveable { mutableStateOf<String?>(null) }
    var itemDraft by rememberSaveable(stateSaver = jsonSaver(PlanItem.serializer().nullable)) { mutableStateOf<PlanItem?>(null) }
    var itemPosition by rememberSaveable { mutableStateOf(0) }
    var details by remember { mutableStateOf<Exercise?>(null) }
    val programs = remember(state.documents[Kind.program]) { state.records(Kind.program).map { it.value<Program>() } }
    val gyms = remember(state.documents[Kind.gym]) { state.records(Kind.gym).map { it.value<Gym>() } }
    val exercises = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).associate { it.id to it.value<Exercise>() } }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).associate { it.id to it.value<Muscle>().name } }
    val program = programs.firstOrNull { it.id == draft.programId }
    val slot = program?.slots?.firstOrNull { it.id == draft.slotId }
    FormDialog("训练计划", onDismiss, { viewModel.save(document(Kind.plan, draft.id, draft), original, onDismiss) }) {
        TextInput("计划名称", draft.name, { draft = draft.copy(name = it) })
        Choice<String?>("分化方案", draft.programId, listOf(null to "不绑定分化方案") + programs.map { it.id to "${it.name} · ${it.slots.size}分化" }, { id ->
            val selected = programs.firstOrNull { it.id == id }
            draft = draft.copy(programId = id, slotId = selected?.slots?.first()?.id)
        })
        if (program != null) Choice("分化中的训练项", draft.slotId, program.slots.map { it.id as String? to it.name }, { draft = draft.copy(slotId = it) })
        if (slot != null) Text("计划部位：" + slot.muscles.joinToString("、") { muscles[it] ?: it })
        Choice<String?>("健身房", draft.gymId, listOf(null to "未指定，查看所有器械条件") + gyms.map { it.id to it.name }, { id ->
            draft = draft.copy(gymId = id, items = draft.items.map { it.copy(equipmentId = null) })
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choosingDate = true }) { Text(draft.date ?: "指定训练日期") }
            if (draft.date != null) TextButton(onClick = { draft = draft.copy(date = null) }) { Text("清除日期") }
        }
        Text(if (draft.date == null) "未指定日期，按分化顺序安排，不假定每天训练。" else "休息不会自动跳过这次训练。", style = MaterialTheme.typography.bodySmall)
        if (draft.items.isEmpty()) EmptyMessage("尚未选择动作。可以先保存计划，训练前再安排。")
        draft.items.forEachIndexed { index, item ->
            val exercise = exercises[item.exerciseId]
            InfoCard("${index + 1}. ${exercise?.name ?: "动作已删除，请替换"}") {
                Text("${item.sets.size} 组 · " + item.sets.firstOrNull()?.let(::targetText).orEmpty())
                Row {
                    TextButton(onClick = { if (exercise != null) { itemDraft = item; itemPosition = index } }, enabled = exercise != null) { Text("编辑要求") }
                    TextButton(onClick = { replacingId = item.id; pickerType = exercise?.type ?: "strength" }) { Text("替换动作") }
                    TextButton(onClick = { if (exercise != null) details = exercise }, enabled = exercise != null) { Text("查看教学") }
                }
                Row {
                    TextButton(onClick = { draft = draft.copy(items = draft.items.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = index > 0) { Text("上移") }
                    TextButton(onClick = { draft = draft.copy(items = draft.items.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = index < draft.items.lastIndex) { Text("下移") }
                    TextButton(onClick = { draft = draft.copy(items = draft.items.filterNot { it.id == item.id }) }) { Text("移除") }
                }
            }
        }
        Button(onClick = { replacingId = null; pickerType = "strength" }) { Text("添加训练动作") }
        OutlinedButton(onClick = { replacingId = null; pickerType = "stretch" }) { Text("查看对应拉伸并选择加入") }
        Text("拉伸只推荐候选。选中后仍可设置组要求和加入位置。", style = MaterialTheme.typography.bodySmall)
        TextInput("计划备注", draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
    }
    pickerType?.let { type ->
        val targetMuscles = if (type == "stretch") draft.items.flatMap { item -> exercises[item.exerciseId]?.takeIf { it.type == "strength" }?.let { it.muscles + it.secondaryMuscles } ?: emptyList() }.toSet()
            else replacingId?.let { id -> draft.items.firstOrNull { it.id == id }?.let { exercises[it.exerciseId]?.muscles?.toSet() } } ?: slot?.muscles?.toSet() ?: emptySet()
        ExercisePicker(state, viewModel.repository, draft.gymId, targetMuscles, type, { pickerType = null; replacingId = null }) { exercise ->
            val old = draft.items.firstOrNull { it.id == replacingId }
            itemDraft = newPlanItem(exercise, draft.gymId, state, old?.sets?.size)
            itemPosition = old?.let { draft.items.indexOf(it) } ?: draft.items.size
            pickerType = null
        }
    }
    itemDraft?.let { item ->
        val exercise = exercises.getValue(item.exerciseId)
        val remaining = draft.items.filterNot { it.id == item.id || it.id == replacingId }
        val positions = (0..remaining.size).map { index -> index to if (index == 0) "放在最前面" else "放在 ${exercises[remaining[index - 1].exerciseId]?.name ?: "上一动作"} 之后" }
        PlanItemEditor(item, exercise, draft.gymId, state, viewModel, positions, itemPosition.coerceAtMost(remaining.size), { itemDraft = null; replacingId = null }) { updated, position ->
            draft = draft.copy(items = remaining.toMutableList().apply { add(position, updated) })
            itemDraft = null; replacingId = null
        }
    }
    if (choosingDate) {
        val day = draft.date?.let(LocalDate::parse) ?: LocalDate.now(ZoneOffset.ofHours(8))
        val picker = rememberDatePickerState(initialSelectedDateMillis = day.toEpochDay() * 86_400_000L)
        DatePickerDialog(onDismissRequest = { choosingDate = false },
            confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                draft = draft.copy(date = LocalDate.ofEpochDay(picker.selectedDateMillis!! / 86_400_000L).toString())
                choosingDate = false
            }) { Text("确定日期") } },
            dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }
        ) { DatePicker(picker) }
    }
    details?.let { ExerciseDetail(it, state, viewModel.repository) { details = null } }
}
