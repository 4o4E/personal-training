package top.e404.training

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import top.e404.training.shared.*

@Serializable data class DaySetupRequest(val program: Program, val slot: SplitSlot, val original: Document?)

@Composable private fun CandidateCard(title: String, selected: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
        }
    }
}

@Composable fun PresetSetupScreen(state: AppData, viewModel: TrainingViewModel, day: DaySetupRequest?, onDismiss: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(if (day == null) 0 else 1) }
    var count by rememberSaveable { mutableIntStateOf(day?.program?.slots?.size ?: 3) }
    var actions by rememberSaveable { mutableIntStateOf(4) }
    var groups by rememberSaveable { mutableIntStateOf(4) }
    var style by rememberSaveable { mutableStateOf(PresetStyle.MIXED) }
    var gymId by rememberSaveable { mutableStateOf(day?.original?.value<Plan>()?.gymId) }
    var draftProgram by rememberSaveable(stateSaver = jsonSaver(Program.serializer().nullable)) { mutableStateOf<Program?>(null) }
    var draftPlans by rememberSaveable(stateSaver = jsonSaver(ListSerializer(Plan.serializer()))) { mutableStateOf(emptyList<Plan>()) }
    var stretchIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var stretchDraft by rememberSaveable(stateSaver = jsonSaver(PlanItem.serializer().nullable)) { mutableStateOf<PlanItem?>(null) }
    var stretchPosition by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Exercise?>(null) }
    val exercises = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).associate { it.id to it.value<Exercise>() } }
    val equipment = remember(state.documents[Kind.equipment]) { state.records(Kind.equipment).map { it.value<Equipment>() } }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).associate { it.id to it.displayName() } }
    val gyms = state.records(Kind.gym)
    val tags = equipmentNames()
    val candidates = remember(count, actions, exercises, equipment, gymId, day) {
        PresetStyle.entries.map { option ->
            val preview = previewPreset(count, option, actions, exercises, gymId, equipment)
            if (day == null) preview else {
                val selected = preview.days.filter { it.muscles.toSet() == day.slot.muscles.toSet() }
                preview.copy(days = selected, unavailable = selected.flatMap { it.unavailable })
            }
        }
    }
    val chosen = candidates.single { it.style == style }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onDismiss) { Text("返回") }
        Text(if (day == null) "整轮计划配置" else "调整${day.slot.name}未来计划", style = MaterialTheme.typography.headlineSmall)
        when (step) {
            0 -> {
                Text("分化是一轮中的训练顺序，不是每周必须训练的天数。完成后推进，休息或取消不会跳过下一项。")
                presetSplits.forEach { split -> CandidateCard(split.title, count == split.count, { count = split.count }) { Text(split.description) } }
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("选择训练量") }
            }
            1 -> {
                Text("动作数量和组数用于力量训练，不包含热身及拉伸。这里选择总量，预览后仍可单独调整。")
                presetActionCounts.forEach { number ->
                    CandidateCard("每次 $number 个动作 · ${number * groups} 组", actions == number, { actions = number }) {
                        Text(when (number) { 3 -> "使用每项候选的前三个动作。"; 4 -> "在前三个动作之外加入第四个动作。"; else -> "使用每项候选的全部五个动作。" })
                    }
                }
                Text("每个力量动作的组数", style = MaterialTheme.typography.titleMedium)
                presetSetCounts.forEach { number ->
                    CandidateCard("每个动作 $number 组 · 每次 ${actions * number} 组", groups == number, { groups = number }) {
                        Text("一轮 ${if (day == null) count else 1} 项，共 ${actions * number * if (day == null) count else 1} 个计划力量组；不计拉伸。")
                    }
                }
                Text("动作和组数增加都会增加训练量，不代表更适合当前恢复；实际部位、动作顺序和每组要求见预览。", style = MaterialTheme.typography.bodySmall)
                if (day == null) TextButton(onClick = { step = 0 }) { Text("返回分化选择") }
                Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text("比较候选方案") }
            }
            2 -> {
                Choice<String?>("器械条件（可选）", gymId, listOf(null to "不指定健身房，查看器械条件") + gyms.map { it.id to it.displayName() }, { gymId = it })
                Text(if (gymId == null) "未登记健身房也能选用；请自行确认预览中的器械现场可用。" else "按已登记器械核对，不会用其他动作补足缺少的器械。")
                candidates.forEach { candidate ->
                    CandidateCard(candidate.style.title, style == candidate.style, { style = candidate.style }) {
                        Text(candidate.style.description)
                        Text("${candidate.days.size} 项训练 · 每项 $actions 个动作、${actions * groups} 组")
                        candidate.days.forEach { section -> Text("${section.name}：${section.exercises.joinToString("、") { it.name }}", style = MaterialTheme.typography.bodySmall) }
                        Text("器械条件：" + candidate.days.flatMap { it.exercises }.flatMap { it.equipmentTags }.distinct().joinToString("、") { tags[it] ?: it }, style = MaterialTheme.typography.bodySmall)
                        candidate.unavailable.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                TextButton(onClick = { step = 1 }) { Text("返回训练量选择") }
                Button(enabled = chosen.unavailable.isEmpty() && chosen.days.isNotEmpty(), onClick = {
                    val program = day?.program ?: Program(newId(), "${chosen.split.title} · ${chosen.style.title}", chosen.days.map { SplitSlot(newId(), it.name, it.muscles) })
                    draftProgram = program
                    draftPlans = chosen.days.mapIndexed { index, section ->
                        val slot = day?.slot ?: program.slots[index]
                        val source = day?.original?.value<Plan>() ?: Plan(newId(), slot.name, program.id, slot.id)
                        source.copy(gymId = gymId, items = section.exercises.map { newPlanItem(it, gymId, state, groups) })
                    }
                    step = 3
                }, modifier = Modifier.fillMaxWidth()) { Text("预览选中方案") }
            }
            3 -> {
                Text("${chosen.split.title} · ${chosen.style.title}", style = MaterialTheme.typography.titleMedium)
                Text(if (day == null) "确认前没有保存任何资料。保存后整轮可用，再从调整计划修改单日。" else "只修改此训练项的后续循环计划，其他训练项、指定日期安排和已开始的训练保持不变。")
                draftPlans.forEachIndexed { index, plan ->
                    InfoCard("${index + 1}. ${plan.name}") {
                        PlanContents(plan, exercises, muscles, tags, { details = it })
                        plan.items.filter { exercises[it.exerciseId]?.type == "stretch" }.forEach { item ->
                            TextButton(onClick = { draftPlans = draftPlans.mapIndexed { i, old -> if (i == index) old.copy(items = old.items.filterNot { it.id == item.id }) else old } }) { Text("移除${exercises[item.exerciseId]?.name}") }
                        }
                        OutlinedButton(onClick = { stretchIndex = index }) { Text("选择此项对应拉伸") }
                    }
                }
                TextButton(onClick = { step = 2 }) { Text("返回候选选择") }
                Button(enabled = !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                    saving = true
                    viewModel.action {
                        try {
                            if (day == null) {
                                viewModel.repository.saveRound(draftProgram!!, draftPlans)
                                viewModel.repository.selectProgram(draftProgram!!.id)
                                draftPlans.forEach { plan -> viewModel.repository.selectPlan(draftProgram!!.id, plan.slotId!!, plan.id) }
                            } else {
                                val plan = draftPlans.single()
                                val document = document(Kind.plan, plan.id, plan)
                                viewModel.repository.save(document, day.original ?: document)
                                viewModel.repository.selectPlan(day.program.id, day.slot.id, plan.id)
                            }
                            onDismiss()
                        } finally { saving = false }
                    }
                }) { Text(if (day == null) "确认保存整轮计划" else "确认保存本项计划") }
            }
        }
    }
    stretchIndex?.let { index ->
        val plan = draftPlans[index]
        if (stretchDraft == null) ExercisePicker(state, viewModel.repository, plan.gymId,
            plan.items.mapNotNull { exercises[it.exerciseId] }.filter { it.type == "strength" }.flatMap { it.muscles + it.secondaryMuscles }.toSet(),
            "stretch", { stretchIndex = null }) { exercise -> stretchDraft = newPlanItem(exercise, plan.gymId, state); stretchPosition = plan.items.size }
        stretchDraft?.let { item ->
            PlanItemEditor(item, exercises.getValue(item.exerciseId), plan.gymId, state, viewModel,
                (0..plan.items.size).map { position -> position to if (position == 0) "放在最前面" else "放在${exercises[plan.items[position - 1].exerciseId]?.name}之后" },
                stretchPosition, { stretchDraft = null; stretchIndex = null }) { updated, position ->
                draftPlans = draftPlans.mapIndexed { i, old -> if (i == index) old.copy(items = old.items.toMutableList().apply { add(position, updated) }) else old }
                stretchDraft = null; stretchIndex = null
            }
        }
    }
    details?.let { ExerciseDetail(it, state, viewModel.repository) { details = null } }
}

/** 预览统计来自实际动作与组要求；主要、辅助及拉伸分别显示，避免把计划量当作完成量。 */
@Composable fun PlanContents(plan: Plan, exercises: Map<String, Exercise>, muscles: Map<String, String>, tags: Map<String, String>, details: (Exercise) -> Unit) {
    val strength = plan.items.filter { exercises[it.exerciseId]?.type == "strength" }
    val stretch = plan.items.filter { exercises[it.exerciseId]?.type == "stretch" }
    val primary = strength.flatMap { exercises.getValue(it.exerciseId).muscles }.distinct()
    val secondary = strength.flatMap { exercises.getValue(it.exerciseId).secondaryMuscles }.distinct() - primary.toSet()
    Text("力量：${strength.size} 个动作 · ${strength.sumOf { it.sets.size }} 组")
    Text("主要部位：" + primary.joinToString("、") { muscles[it] ?: it })
    if (secondary.isNotEmpty()) Text("辅助涉及：" + secondary.joinToString("、") { muscles[it] ?: it })
    plan.items.forEachIndexed { index, item ->
        val exercise = exercises[item.exerciseId]
        Text("${index + 1}. ${exercise?.name ?: "动作资料尚未取得"} · ${item.sets.size} 组", style = MaterialTheme.typography.titleSmall)
        item.sets.distinct().forEach { Text(targetText(it), style = MaterialTheme.typography.bodySmall) }
        exercise?.let { value ->
            Text("器械：" + value.equipmentTags.joinToString("、") { tags[it] ?: it }.ifEmpty { "无需专用器械" }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { details(value) }) { Text("查看${value.name}教学") }
        }
    }
    Text("拉伸：${stretch.size} 个动作 · ${stretch.sumOf { it.sets.size }} 组（单独计数）", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
