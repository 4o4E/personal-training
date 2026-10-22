package top.e404.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import top.e404.training.shared.*

fun <T> jsonSaver(serializer: KSerializer<T>) = Saver<T, String>(save = { wireJson.encodeToString(serializer, it) }, restore = { wireJson.decodeFromString(serializer, it) })
fun numericText(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
fun intInput(text: String, name: String): Int = text.toIntOrNull() ?: throw IllegalArgumentException("请输入有效的$name")
fun doubleInput(text: String, name: String): Double = text.toDoubleOrNull() ?: throw IllegalArgumentException("请输入有效的$name")

@Composable fun FormDialog(title: String, onDismiss: () -> Unit, onSave: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    if (onSave != null) Button(onClick = onSave) { Text("保存") }
                }
            }
        }
    }
}
@Composable fun TextInput(label: String, value: String, onValueChange: (String) -> Unit, numeric: Boolean = false, singleLine: Boolean = true, modifier: Modifier = Modifier, keyboardType: KeyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), modifier = modifier.fillMaxWidth())
}
@Composable fun <T> Choice(label: String, value: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(choices.firstOrNull { it.first == value }?.second ?: "请选择") }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
            choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}
@Composable fun MultiChoiceDialog(title: String, options: List<Pair<String, String>>, selected: Set<String>, onDismiss: () -> Unit, onSelect: (Set<String>) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(selected) }
    var query by rememberSaveable { mutableStateOf("") }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.85f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                TextInput("搜索", query, { query = it })
                LazyColumn(Modifier.weight(1f)) {
                    items(options.filter { it.second.contains(query, true) }, key = { it.first }) { (id, name) ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (id in chosen) chosen - id else chosen + id }.padding(vertical = 4.dp)) {
                            Checkbox(id in chosen, { checked -> chosen = if (checked) chosen + id else chosen - id })
                            Text(name, Modifier.padding(top = 12.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onSelect(chosen) }) { Text("确认选择（${chosen.size}）") }
                }
            }
        }
    }
}
@Composable fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } }
}
@Composable fun EmptyMessage(text: String) { Text(text, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Serializable private data class BodyShape(val muscle: String, val polygons: List<String>)
private data class BodyPath(val muscle: String, val path: Path)
@Composable fun MuscleDiagram(keys: Set<String>) {
    val context = LocalContext.current
    val poses = remember(context) {
        val raw = context.assets.open("muscle-map.json").bufferedReader().use { wireJson.decodeFromString<Map<String, List<BodyShape>>>(it.readText()) }
        raw.mapValues { (_, shapes) -> shapes.flatMap { shape -> shape.polygons.map { polygon ->
            val points = polygon.split(' ').map(String::toFloat)
            val path = Path().apply { moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]); close() }
            BodyPath(shape.muscle, path)
        } } }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("front" to "正面", "back" to "背面").forEach { (pose, name) ->
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelMedium)
                Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                    scale(size.width / 1000f, size.height / 2200f, Offset.Zero) {
                        poses.getValue(pose).forEach { drawPath(it.path, if (it.muscle in keys) Color(0xFFE06E69) else Color(0xFF9DAAA6)) }
                    }
                }
            }
        }
    }
}
@Composable fun MediaImage(media: Media, repository: TrainingRepository, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(model = repository.mediaModel(media), contentDescription = media.name,
        modifier = modifier.background(if (media.mimeType == "image/svg+xml") Color(0xFF273E48) else Color(0xFFF2F5F4)),
        loading = { Box(Modifier.padding(24.dp)) { CircularProgressIndicator() } },
        error = { Text("素材尚未下载或无法读取", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) })
}
@Composable fun ExerciseDetail(exercise: Exercise, state: AppData, repository: TrainingRepository, onDismiss: () -> Unit) {
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).associate { val m = it.value<Muscle>(); m.id to m } }
    val tagNames = equipmentNames()
    FormDialog(exercise.name, onDismiss) {
        Text(if (exercise.type == "stretch") "拉伸动作" else "力量训练", color = MaterialTheme.colorScheme.primary)
        Text("主要部位：" + exercise.muscles.joinToString("、") { muscles[it]?.name ?: it })
        if (exercise.secondaryMuscles.isNotEmpty()) Text("辅助涉及：" + exercise.secondaryMuscles.joinToString("、") { muscles[it]?.name ?: it })
        MuscleDiagram((exercise.muscles + exercise.secondaryMuscles).flatMap { muscles[it]?.mapKeys ?: emptyList() }.toSet())
        Text("器械条件：" + if (exercise.equipmentTags.isEmpty()) "无需专用器械" else exercise.equipmentTags.joinToString("、") { tagNames[it] ?: it })
        exercise.mediaIds.forEach { id -> state.record(Kind.media, id)?.value<Media>()?.let { MediaImage(it, repository, Modifier.fillMaxWidth().height(260.dp)) } }
        Text("执行方式", style = MaterialTheme.typography.titleMedium)
        exercise.instructions.forEachIndexed { index, text -> Text("${index + 1}. $text") }
        if (exercise.unilateral) Text("需要区分左右侧时，请在计划组中分别选择左侧和右侧。")
        if (exercise.notes.isNotBlank()) Text(exercise.notes)
    }
}
@Composable fun equipmentNames(): Map<String, String> {
    val context = LocalContext.current
    return remember(context) { context.assets.open("equipment-tags.json").bufferedReader().use { wireJson.decodeFromString<Map<String, String>>(it.readText()) } }
}

@Composable fun ExercisePicker(
    state: AppData, repository: TrainingRepository, gymId: String?, initialMuscles: Set<String>,
    type: String?, onDismiss: () -> Unit, onChoose: (Exercise) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterMuscles by rememberSaveable { mutableStateOf(initialMuscles) }
    var filterType by rememberSaveable { mutableStateOf(type ?: "strength") }
    var muscleDialog by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Exercise?>(null) }
    val all = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).map { it.value<Exercise>() } }
    val equipment = remember(state.documents[Kind.equipment]) { state.records(Kind.equipment).map { it.value<Equipment>() } }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).map { it.value<Muscle>() } }
    val names = equipmentNames()
    val choices = remember(all, equipment, filterMuscles, filterType, query, gymId) {
        all.filter { it.type == filterType && (filterMuscles.isEmpty() || it.muscles.any(filterMuscles::contains) || filterType == "stretch" && it.secondaryMuscles.any(filterMuscles::contains)) && exerciseAvailable(it, gymId, equipment) && it.name.contains(query, true) }.sortedBy { it.name }
    }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.98f).fillMaxHeight(.95f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (filterType == "stretch") if (filterMuscles.isEmpty()) "按部位选择拉伸" else "选择对应拉伸" else "选择训练动作", style = MaterialTheme.typography.titleLarge)
                TextInput("搜索动作名称", query, { query = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (type == null) {
                        FilterChip(filterType == "strength", { filterType = "strength" }, label = { Text("力量训练") })
                        FilterChip(filterType == "stretch", { filterType = "stretch" }, label = { Text("拉伸") })
                    }
                    OutlinedButton(onClick = { muscleDialog = true }) { Text("筛选部位（${filterMuscles.size}）") }
                }
                Text(if (gymId == null) "未选择健身房，显示动作所需条件" else "仅显示当前健身房器械可执行的候选", style = MaterialTheme.typography.bodySmall)
                if (filterType == "stretch" && initialMuscles.isEmpty()) Text("没有已选力量动作作为匹配来源，可以手动筛选拉伸部位。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choices.isEmpty()) item { EmptyMessage("当前条件没有匹配动作，可以调整部位或器械条件。") }
                    items(choices, key = { it.id }) { exercise ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val media = exercise.mediaIds.firstOrNull()?.let { state.record(Kind.media, it)?.value<Media>() }
                                if (media != null) MediaImage(media, repository, Modifier.size(78.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                                    Text(exercise.muscles.joinToString("、") { id -> muscles.firstOrNull { it.id == id }?.name ?: id }, style = MaterialTheme.typography.bodySmall)
                                    Text(if (exercise.equipmentTags.isEmpty()) "无需专用器械" else exercise.equipmentTags.joinToString("、") { names[it] ?: it }, style = MaterialTheme.typography.bodySmall)
                                    Row { TextButton(onClick = { detail = exercise }) { Text("查看教学") }; TextButton(onClick = { onChoose(exercise) }) { Text("选择动作") } }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text("返回") }
            }
        }
    }
    if (muscleDialog) MultiChoiceDialog("筛选训练部位", muscles.map { it.id to it.name }, filterMuscles, { muscleDialog = false }) { filterMuscles = it; muscleDialog = false }
    detail?.let { ExerciseDetail(it, state, repository) { detail = null } }
}
