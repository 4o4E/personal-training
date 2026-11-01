package top.e404.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.e404.training.shared.*

@Composable fun ConflictResolutionDialog(state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    Text("处理内容冲突", style = MaterialTheme.typography.titleLarge)
                    Text("选择保留手机或后端内容；不会按时间自动覆盖。", style = MaterialTheme.typography.bodySmall)
                    if (state.conflicts.isEmpty()) {
                        Text("当前没有待处理冲突。")
                    } else {
                        val tagNames = equipmentNames()
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = LayoutArrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(state.conflicts, key = { "${it.local.kind.name}/${it.local.id}" }) { conflict ->
                                InfoCard(conflict.local.displayName()) {
                                    Text("手机内容", style = MaterialTheme.typography.titleSmall)
                                    Text(documentDescription(conflict.local, state, tagNames))
                                    ConflictImages(conflict.local, state, viewModel.repository)
                                    Text("后端内容", style = MaterialTheme.typography.titleSmall)
                                    Text(documentDescription(conflict.remote, state, tagNames))
                                    ConflictImages(conflict.remote, state, viewModel.repository)
                                    Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { viewModel.action { viewModel.repository.resolveConflict(conflict.local.kind, conflict.local.id, true) } }) { Text("保留手机内容") }
                                        TextButton(onClick = { viewModel.action { viewModel.repository.resolveConflict(conflict.local.kind, conflict.local.id, false) } }) { Text("使用后端内容") }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = LayoutArrangement.End) {
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}

private fun documentDescription(doc: Document, state: AppData, tagNames: Map<String, String>): String {
    if (doc.deleted) return "已删除"
    fun parts(ids: List<String>) = ids.joinToString("、") { state.record(Kind.muscle, it)?.displayName() ?: "部位资料未取得" }
    return when (doc.kind) {
        Kind.plan -> doc.value<Plan>().let { plan -> buildString {
            append("${plan.name} · ${plan.date ?: "未指定日期"}\n")
            plan.items.forEachIndexed { index, item ->
                append("${index + 1}. ${state.record(Kind.exercise, item.exerciseId)?.displayName() ?: "动作资料未取得"}\n")
                item.sets.forEachIndexed { group, target -> append("第 ${group + 1} 组：${targetText(target)}\n") }
                if (item.notes.isNotBlank()) append("${item.notes}\n")
            }
            append(plan.notes)
        } }
        Kind.session -> doc.value<Session>().let { session -> buildString {
            append("${session.name} · ${session.startedAt?.let(::localDateTime) ?: session.localDate}\n")
            append(when (session.status) { "active" -> "进行中\n"; "completed" -> "已完成\n"; else -> "已取消\n" })
            session.items.forEach { item ->
                append("${session.exercises.single { it.id == item.exerciseId }.name}\n")
                session.results.forEach { result -> if (result.itemId == item.id) append("第 ${result.setIndex + 1} 组：${sideText(result.side)} · ${resultText(result)}\n") }
            }
            append(session.notes)
        } }
        Kind.arrangement -> doc.value<top.e404.training.shared.Arrangement>().let { arrangement -> buildString {
            val currentProgramId = arrangement.programId
            val currentProgram = currentProgramId?.let { state.record(Kind.program, it)?.value<Program>() }
            append("当前分化：${currentProgram?.name ?: if (currentProgramId == null) "未选择" else "分化资料未取得"}\n")
            if (arrangement.selectedPlans.isEmpty()) append("尚未选用循环计划。\n")
            arrangement.selectedPlans.forEach { (programId, slots) ->
                val program = state.record(Kind.program, programId)?.value<Program>()
                slots.forEach { (slotId, planId) ->
                    val slotName = program?.slots?.firstOrNull { it.id == slotId }?.name ?: "训练项资料未取得"
                    val planName = state.record(Kind.plan, planId)?.value<Plan>()?.name ?: "计划资料未取得"
                    append("${program?.name ?: "分化资料未取得"} · $slotName → $planName\n")
                }
            }
            arrangement.nextOverrides.forEach { (programId, override) ->
                val program = state.record(Kind.program, programId)?.value<Program>()
                val slotName = program?.slots?.firstOrNull { it.id == override.slotId }?.name ?: "训练项资料未取得"
                append("下次训练：${program?.name ?: "分化资料未取得"} · $slotName\n")
            }
        } }
        Kind.program -> doc.value<Program>().let { "${it.name}\n" + it.slots.joinToString("\n") { slot -> "${slot.name}：${parts(slot.muscles)}" } }
        Kind.equipment -> doc.value<Equipment>().let { "${it.name} · ${it.unit} · " + when (it.meaning) { "per_hand" -> "单只重量"; "per_side" -> "单侧配重"; else -> "总重量" } + "\n" + it.tags.joinToString("、") { tag -> tagNames[tag] ?: tag } + "\n${it.instructions}\n${it.notes}" }
        Kind.gym -> doc.value<Gym>().let { "${it.name}\n${it.notes}" }
        Kind.exercise -> doc.value<Exercise>().let { "${it.name}\n主要部位：${parts(it.muscles)}\n辅助部位：${parts(it.secondaryMuscles)}\n" + it.instructions.joinToString("\n") + "\n${it.notes}" }
        Kind.activity -> doc.value<ActivityRecord>().let { activity -> buildString {
            append("${activity.name} · ${activity.localDate}\n")
            activity.startedAt?.let { append("开始：${localDateTime(it)}\n") }
            activity.endedAt?.let { append("结束：${localDateTime(it)}\n") }
            activity.durationSeconds?.let { append("来源/记录时长：${it} 秒\n") }
            activity.distanceMeters?.let { append("距离：${it} 米\n") }
            if (activity.notes.isNotBlank()) append(activity.notes)
        } }
        Kind.media -> doc.value<Media>().let { "${it.name} · ${it.source}" }
        Kind.muscle -> doc.value<Muscle>().name
    }
}

@Composable private fun ConflictImages(doc: Document, state: AppData, repository: TrainingRepository) {
    if (doc.deleted) return
    val images = when (doc.kind) {
        Kind.media -> listOf(doc.value<Media>())
        Kind.exercise -> doc.value<Exercise>().mediaIds.mapNotNull { state.record(Kind.media, it)?.value<Media>() }
        Kind.equipment -> listOfNotNull(doc.value<Equipment>().mediaId?.let { state.record(Kind.media, it)?.value<Media>() })
        else -> emptyList()
    }
    images.forEach { MediaImage(it, repository, Modifier.fillMaxWidth().height(160.dp)) }
}
