package top.e404.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.e404.training.shared.*

internal fun currentProgram(programs: List<Program>, choices: DeviceChoices): Program? =
    programs.singleOrNull() ?: programs.firstOrNull { it.id == choices.programId }

@Composable private fun ProgramChoice(programs: List<Program>, current: Program?, preferences: AppPreferences) {
    if (programs.size > 1) Choice<String?>("当前训练方案", current?.id, programs.map { it.id to "${it.name} · ${it.slots.size}分化" }, { it?.let(preferences::selectProgram) })
    else current?.let { Text("${it.name} · ${it.slots.size}分化", style = MaterialTheme.typography.titleMedium) }
}

private fun planMuscles(plan: Plan, state: AppData): String = plan.items.flatMap { item ->
    state.record(Kind.exercise, item.exerciseId)?.value<Exercise>()?.takeIf { it.type == "strength" }?.muscles ?: emptyList()
}.distinct().joinToString("、") { state.record(Kind.muscle, it)?.displayName() ?: it }
private fun planVolume(plan: Plan, state: AppData): String {
    val strength = plan.items.filter { state.record(Kind.exercise, it.exerciseId)?.value<Exercise>()?.type == "strength" }
    val stretch = plan.items.filter { state.record(Kind.exercise, it.exerciseId)?.value<Exercise>()?.type == "stretch" }
    return "力量 ${strength.size} 个动作 · ${strength.sumOf { it.sets.size }} 组；拉伸 ${stretch.size} 个动作 · ${stretch.sumOf { it.sets.size }} 组"
}

@Composable fun StartOverview(state: AppData, choices: DeviceChoices, preferences: AppPreferences, start: (Plan) -> Unit, preview: (Plan) -> Unit, setup: () -> Unit, adjust: () -> Unit) {
    val programs = remember(state.documents[Kind.program]) { state.records(Kind.program).map { it.value<Program>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val sessions = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it.value<Session>() } }
    val program = currentProgram(programs, choices)
    val selectedPlans = if (program == null) emptyMap() else choices.slotPlans[program.id].orEmpty()
    val rotation = remember(program, plans, sessions, selectedPlans) { program?.let { rotationPlans(it, plans, sessions, selectedPlans) } ?: emptyList() }
    val completed = remember(sessions) { sessions.filter { it.status == "completed" }.map { it.snapshot.id }.toSet() }
    val independent = plans.filter { it.programId == null && it.id !in completed }.sortedWith(compareBy<Plan> { it.date == null }.thenBy { it.date })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            InfoCard("下一次训练") {
                ProgramChoice(programs, program, preferences)
                if (programs.isEmpty() && independent.isEmpty()) {
                    Text("选择三、四、五分化，再比较整轮方案。不需要先登记健身房。")
                    Button(onClick = setup) { Text("选择训练方案") }
                } else if (program == null && programs.isNotEmpty()) Text("请选择当前训练方案；不同方案的轮换分别保留。")
                else {
                    val next = rotation.firstOrNull()
                    val plan = if (program == null) independent.firstOrNull() else next?.plan
                    next?.let { Text(it.slot.name, style = MaterialTheme.typography.titleLarge) }
                    Text("完成训练后推进；休息和取消不改变下一项。", style = MaterialTheme.typography.bodySmall)
                    if (plan != null) {
                        Text(plan.name, style = MaterialTheme.typography.titleMedium)
                        Text(planMuscles(plan, state)); Text(planVolume(plan, state))
                        Text(plan.date?.let { "指定日期：$it" } ?: "循环计划，不限定训练日期", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { start(plan) }, enabled = plan.items.isNotEmpty()) { Text("开始这次训练") }
                        TextButton(onClick = { preview(plan) }) { Text("预览动作与教学") }
                    } else {
                        Text("本项尚未选择计划；有多份循环计划时，请在调整计划中明确选择。")
                        Button(onClick = adjust) { Text("选择本项计划") }
                    }
                }
            }
        }
        if (rotation.isNotEmpty()) {
            item { Text("这一轮的训练顺序", style = MaterialTheme.typography.titleLarge); Text("按完成顺序预览，不代表连续每天训练或固定每周频率。", style = MaterialTheme.typography.bodySmall) }
            items(rotation, key = { it.slot.id }) { entry ->
                InfoCard(entry.slot.name) {
                    Text(entry.slot.muscles.joinToString("、") { state.record(Kind.muscle, it)?.displayName() ?: it })
                    entry.plan?.let { plan ->
                        Text(planVolume(plan, state))
                        TextButton(onClick = { preview(plan) }) { Text("预览${entry.slot.name}") }
                    } ?: TextButton(onClick = adjust) { Text("选择本项计划") }
                }
            }
        }
        if (independent.isNotEmpty()) {
            item { Text("独立训练安排", style = MaterialTheme.typography.titleMedium) }
            items(independent, key = { it.id }) { plan -> InfoCard(plan.name) {
                Text(plan.date ?: "单次安排，未指定日期"); Text(planVolume(plan, state))
                TextButton(onClick = { preview(plan) }) { Text("预览独立安排") }
                Button(onClick = { start(plan) }, enabled = plan.items.isNotEmpty()) { Text("开始独立训练") }
            } }
        }
        item { OutlinedButton(onClick = adjust) { Text("调整当前训练计划") } }
    }
}

@Composable fun AdjustPrograms(state: AppData, choices: DeviceChoices, preferences: AppPreferences, edit: (Document) -> Unit, preview: (Plan) -> Unit, setup: () -> Unit, setupDay: (DaySetupRequest) -> Unit, advanced: () -> Unit) {
    val programs = remember(state.documents[Kind.program]) { state.records(Kind.program).map { it.value<Program>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val program = currentProgram(programs, choices)
    val exercises = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).associate { it.id to it.value<Exercise>() } }
    val templates = remember(program, exercises) {
        if (program != null && presetSplits.any { it.count == program.slots.size }) previewPreset(program.slots.size, PresetStyle.MIXED, 3, exercises, null, emptyList()).days else emptyList()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            InfoCard("整轮选择，按日调整") {
                Text("先选一套完整方案，再修改其中某次训练；开始后的训练内容独立保留。")
                Button(onClick = setup) { Text(if (programs.isEmpty()) "选择整轮训练方案" else "新增整轮训练方案") }
                ProgramChoice(programs, program, preferences)
            }
        }
        if (program == null && programs.isNotEmpty()) item { EmptyMessage("请选择要调整的方案。") }
        program?.let { selected ->
            items(selected.slots, key = { it.id }) { slot ->
                val options = plans.filter { it.programId == selected.id && it.slotId == slot.id && it.date == null }
                val selectedId = choices.slotPlans[selected.id]?.get(slot.id)
                val plan = options.singleOrNull() ?: options.firstOrNull { it.id == selectedId }
                InfoCard(slot.name) {
                    Text(slot.muscles.joinToString("、") { state.record(Kind.muscle, it)?.displayName() ?: it })
                    if (options.size > 1) Choice<String?>("此项循环计划", plan?.id, options.map { it.id to it.name }, { id -> id?.let { preferences.selectPlans(selected.id, mapOf(slot.id to it)) } })
                    if (plan != null) {
                        Text(planVolume(plan, state))
                        TextButton(onClick = { preview(plan) }) { Text("预览本项计划与教学") }
                        OutlinedButton(onClick = { edit(state.record(Kind.plan, plan.id)!!) }) { Text("调整动作与组数") }
                    } else Text(if (options.isEmpty()) "尚无循环计划，可以选择候选或自行编排。" else "请选择此项使用哪份循环计划。")
                    if (templates.any { it.muscles.toSet() == slot.muscles.toSet() }) {
                        OutlinedButton(onClick = { setupDay(DaySetupRequest(selected, slot, plan?.let { state.record(Kind.plan, it.id) })) }) { Text("选择本项候选方案") }
                    }
                    if (plan == null) TextButton(onClick = { val created = Plan(newId(), slot.name, selected.id, slot.id); edit(document(Kind.plan, created.id, created)) }) { Text("自行编排本项动作") }
                }
            }
            item { TextButton(onClick = { edit(state.record(Kind.program, selected.id)!!) }) { Text("自定义分化部位与顺序") } }
        }
        item { OutlinedButton(onClick = advanced) { Text("自定义、日期及全部计划") } }
    }
}

@Composable fun PlanPreview(plan: Plan, state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit, edit: () -> Unit) {
    val exercises = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).associate { it.id to it.value<Exercise>() } }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).associate { it.id to it.displayName() } }
    val tags = equipmentNames()
    var details by remember { mutableStateOf<Exercise?>(null) }
    FormDialog(plan.name, onDismiss) {
        Text(plan.date?.let { "指定日期：$it" } ?: if (plan.programId == null) "独立安排" else "循环计划")
        PlanContents(plan, exercises, muscles, tags, { details = it })
        if (plan.notes.isNotBlank()) Text(plan.notes)
        Button(onClick = edit) { Text("调整此计划") }
    }
    details?.let { ExerciseDetail(it, state, viewModel.repository) { details = null } }
}
