package top.e404.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.e404.training.shared.*

private fun planMuscles(plan: Plan, state: AppData): String = plan.items.flatMap { item ->
    state.record(Kind.exercise, item.exerciseId)?.value<Exercise>()?.takeIf { it.type == "strength" }?.muscles ?: emptyList()
}.distinct().joinToString("、") { state.record(Kind.muscle, it)?.displayName() ?: it }
private fun planVolume(plan: Plan, state: AppData): String {
    val strength = plan.items.filter { state.record(Kind.exercise, it.exerciseId)?.value<Exercise>()?.type == "strength" }
    val stretch = plan.items.filter { state.record(Kind.exercise, it.exerciseId)?.value<Exercise>()?.type == "stretch" }
    return "力量 ${strength.size} 个动作 · ${strength.sumOf { it.sets.size }} 组；拉伸 ${stretch.size} 个动作 · ${stretch.sumOf { it.sets.size }} 组"
}

@Composable fun StartOverview(state: AppData, start: (Plan) -> Unit, preview: (Plan) -> Unit, adjust: () -> Unit) {
    val programs = remember(state.documents[Kind.program]) { state.records(Kind.program).map { it.value<Program>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val sessions = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it.value<Session>() } }
    val arrangement = state.arrangement
    val program = programs.firstOrNull { it.id == arrangement?.programId }
    val selectedPlans = program?.let { arrangement?.selectedPlans?.get(it.id).orEmpty() }.orEmpty()
    val nextSplitSlot = program?.let { nextSlot(it, sessions, arrangement?.nextOverrides?.get(it.id)) }
    val nextPlan = program?.let { selected ->
        rotationPlans(selected, plans, sessions, selectedPlans, arrangement?.nextOverrides?.get(selected.id))
            .firstOrNull()?.let { entry -> entry.plan?.takeIf { it.date != null || it.id == selectedPlans[entry.slot.id] } }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            InfoCard("下一次训练") {
                if (program == null) {
                    Text(if (programs.isEmpty()) "还没有训练分化。请先在调整计划中配置整轮计划。" else "当前分化尚未由云端安排选定。")
                    Button(onClick = adjust) { Text("前往调整计划") }
                } else if (nextSplitSlot == null) {
                    Text("当前分化没有可安排的训练项。")
                    Button(onClick = adjust) { Text("前往调整计划") }
                } else {
                    Text("${program.name} · ${nextSplitSlot.name}", style = MaterialTheme.typography.titleLarge)
                    Text("按实际完成记录推进；取消不会消耗手动指定的下一项。", style = MaterialTheme.typography.bodySmall)
                    if (nextPlan != null) {
                        Text(nextPlan.name, style = MaterialTheme.typography.titleMedium)
                        nextPlan.date?.let { Text("指定日期：$it", style = MaterialTheme.typography.bodySmall) }
                        Text(planMuscles(nextPlan, state)); Text(planVolume(nextPlan, state))
                        Button(onClick = { start(nextPlan) }, enabled = nextPlan.items.isNotEmpty()) { Text("开始这次训练") }
                        TextButton(onClick = { preview(nextPlan) }) { Text("预览动作与教学") }
                    } else {
                        Text("此项尚未在云端安排中选定计划；不自动推断候选。")
                        Button(onClick = adjust) { Text("前往调整计划") }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = adjust) { Text("调整未来计划") } }
    }
}

@Composable fun AdjustPrograms(state: AppData, viewModel: TrainingViewModel, edit: (Document) -> Unit, preview: (Plan) -> Unit, onAdjustCurrent: () -> Unit, setup: () -> Unit, setupDay: (DaySetupRequest) -> Unit, advanced: () -> Unit) {
    val programs = remember(state.documents[Kind.program]) { state.records(Kind.program).map { it.value<Program>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val activeSession = remember(state.documents[Kind.session]) { state.records(Kind.session).firstOrNull { it.value<Session>().status == "active" }?.value<Session>() }
    val program = programs.firstOrNull { it.id == state.arrangement?.programId }
    val exercises = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).associate { it.id to it.value<Exercise>() } }
    val templates = remember(program, exercises) {
        if (program != null && presetSplits.any { it.count == program.slots.size }) previewPreset(program.slots.size, PresetStyle.MIXED, 3, exercises, null, emptyList()).days else emptyList()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        activeSession?.let { current ->
            item {
                InfoCard("本次剩余内容") {
                    Text("${current.name} · 已完成 ${current.results.size} 组")
                    Text("只影响当前会话尚未完成的动作；已记录结果保持不变。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onAdjustCurrent) { Text("调整本次剩余内容") }
                }
            }
        }
        item {
            InfoCard("训练安排") {
                Text("整轮、各次计划和下次部位在此维护；开始训练只执行已确定的安排。")
                if (programs.isNotEmpty()) Choice<String?>("当前训练分化", program?.id, programs.map { it.id to "${it.name} · ${it.slots.size}分化" }, { id ->
                    viewModel.action { viewModel.repository.selectProgram(id) }
                })
                Button(onClick = setup) { Text(if (programs.isEmpty()) "配置整轮训练方案" else "新增整轮训练方案") }
                program?.let { selected ->
                    val overrideSlot = state.arrangement?.nextOverrides?.get(selected.id)?.slotId
                    Choice<String?>("下次训练项", overrideSlot, listOf(null to "按实际完成轮换") + selected.slots.map { it.id to it.name }, { id ->
                        viewModel.action { viewModel.repository.selectNextOverride(selected.id, id) }
                    })
                }
            }
        }
        if (program == null && programs.isNotEmpty()) item { EmptyMessage("云端当前分化尚未选定；请在上方选定后继续配置。") }
        program?.let { selected ->
            items(selected.slots, key = { it.id }) { slot ->
                val options = plans.filter { it.programId == selected.id && it.slotId == slot.id && it.date == null }
                val selectedId = state.arrangement?.selectedPlans?.get(selected.id)?.get(slot.id)
                val plan = options.singleOrNull { it.id == selectedId }
                InfoCard(slot.name) {
                    Text(slot.muscles.joinToString("、") { state.record(Kind.muscle, it)?.displayName() ?: it })
                    if (options.isNotEmpty()) Choice<String?>("此项循环计划", plan?.id, listOf(null to "未选定") + options.map { it.id to it.name }, { id ->
                        viewModel.action { viewModel.repository.selectPlan(selected.id, slot.id, id) }
                    })
                    if (plan != null) {
                        Text(planVolume(plan, state))
                        TextButton(onClick = { preview(plan) }) { Text("预览本项计划与教学") }
                        OutlinedButton(onClick = { edit(state.record(Kind.plan, plan.id)!!) }) { Text("调整未来计划动作与组数") }
                    } else Text("尚未在云端安排中选择此项计划。")
                    if (templates.any { it.muscles.toSet() == slot.muscles.toSet() }) {
                        OutlinedButton(onClick = { setupDay(DaySetupRequest(selected, slot, plan?.let { state.record(Kind.plan, it.id) })) }) { Text("为本项选择候选方案") }
                    }
                    if (options.isEmpty()) TextButton(onClick = { val created = Plan(newId(), slot.name, selected.id, slot.id); edit(document(Kind.plan, created.id, created)) }) { Text("自行编排本项动作") }
                }
            }
            item { TextButton(onClick = { edit(state.record(Kind.program, selected.id)!!) }) { Text("自定义分化部位与顺序") } }
        }
        item { OutlinedButton(onClick = advanced) { Text("全部计划与日期安排") } }
        item { Text("本页编辑整轮与单日未来计划；当前训练仅通过上方「调整本次剩余内容」入口修改。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable fun PlanPreview(plan: Plan, state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit, onEdit: (() -> Unit)? = null) {
    val exercises = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).associate { it.id to it.value<Exercise>() } }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).associate { it.id to it.displayName() } }
    val tags = equipmentNames()
    var details by remember { mutableStateOf<Exercise?>(null) }
    FormDialog(plan.name, onDismiss) {
        Text(plan.date?.let { "指定日期：$it" } ?: if (plan.programId == null) "独立安排" else "循环计划")
        PlanContents(plan, exercises, muscles, tags, { details = it })
        if (plan.notes.isNotBlank()) Text(plan.notes)
        onEdit?.let { TextButton(onClick = it) { Text("调整此计划") } }
    }
    details?.let { ExerciseDetail(it, state, viewModel.repository) { details = null } }
}
