package top.e404.training

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.e404.training.shared.Exercise
import top.e404.training.shared.Load
import top.e404.training.shared.SetResult

private data class WeightDraft(val value: String, val unit: String?)

private fun weightDraft(load: Load?) = WeightDraft(
    value = load?.let { numericText(it.value) }.orEmpty(),
    unit = load?.unit
)

private fun WeightDraft.toLoad(original: Load?, exercise: Exercise): Load? {
    if (value.isBlank()) return null
    return Load(
        value = doubleInput(value, "重量"),
        unit = requireNotNull(unit) { "请为新填写的重量选择原始单位" },
        meaning = original?.meaning ?: "unknown",
        mode = original?.mode ?: exercise.loadMode
    )
}

@Composable
fun HistoricalWeightEditor(
    result: SetResult,
    exercise: Exercise,
    viewModel: TrainingViewModel,
    onDismiss: () -> Unit,
    onSave: suspend (List<Load?>) -> Unit
) {
    val originalLoads = remember(result) {
        if (result.segments.isEmpty()) listOf(result.load) else result.segments.map { it.load }
    }
    var drafts by remember(result) { mutableStateOf(originalLoads.map(::weightDraft)) }
    FormDialog("修改第 ${result.setIndex + 1} 组重量", onDismiss, {
        viewModel.action {
            val loads = drafts.mapIndexed { index, draft -> draft.toLoad(originalLoads[index], exercise) }
            onSave(loads)
            onDismiss()
        }
    }) {
        Text("按器械标示填写重量和单位；留空表示未记录。")
        drafts.forEachIndexed { index, draft ->
            Text(if (result.segments.isEmpty()) "整组重量" else "第 ${index + 1} 段重量")
            TextInput("实际重量（留空表示未记录）", draft.value, { value ->
                drafts = drafts.toMutableList().also { it[index] = draft.copy(value = value) }
            }, numeric = true)
            Choice<String?>(
                "原始单位",
                draft.unit,
                listOf<Pair<String?, String>>(null to "请选择", "kg" to "公斤 kg", "lb" to "磅 lb"),
                { unit -> drafts = drafts.toMutableList().also { it[index] = draft.copy(unit = unit) } }
            )
        }
    }
}
