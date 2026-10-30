package top.e404.training

import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.launch
import android.net.Uri
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

@Composable fun FormDialog(title: String, onDismiss: () -> Unit, onSave: (() -> Unit)? = null, dismissLabel: String = "关闭", dismissOnClickOutside: Boolean = true, scrollKey: Int = 0, confirmLabel: String = "保存", content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(scrollKey) { scroll.scrollTo(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOnClickOutside)) {
        Surface(modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(dismissLabel) }
                    if (onSave != null) Button(onClick = onSave) { Text(confirmLabel) }
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
    val selectedColor = MaterialTheme.colorScheme.primary
    val otherColor = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("front" to "正面", "back" to "背面").forEach { (pose, name) ->
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelMedium)
                Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                    scale(size.width / 1000f, size.height / 2200f, Offset.Zero) {
                        poses.getValue(pose).forEach { drawPath(it.path, if (it.muscle in keys) selectedColor else otherColor) }
                    }
                }
            }
        }
    }
}
@Composable fun MediaImage(media: Media, repository: TrainingRepository, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(model = repository.mediaModel(media), contentDescription = media.name,
        modifier = modifier.background(if (media.mimeType == "image/svg+xml") Color(0xFF273E48) else MaterialTheme.colorScheme.surfaceContainerHighest),
        loading = { Box(Modifier.padding(24.dp)) { CircularProgressIndicator() } },
        error = { Text("素材尚未下载或无法读取", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) })
}
@Composable private fun RemoteExerciseImage(url: String) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = "动作教学图片",
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
        loading = { Box(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator() } },
        error = { Text("教学图片暂时无法加载；其他教学内容仍可查看。", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
    )
}
private val guideStepPrefix = Regex("""^\s*\d+\s*[.、)）:：]\s*""")
private val guideStepWhitespace = Regex("""\s+""")
private fun guideStepKey(step: String) = guideStepWhitespace.replace(guideStepPrefix.replace(step.trim(), ""), " ")
private fun distinctGuideSteps(steps: List<String>, excluding: List<String> = emptyList()): List<String> {
    val excluded = excluding.mapTo(hashSetOf(), ::guideStepKey)
    return steps.filter(String::isNotBlank).distinctBy(::guideStepKey).filterNot { guideStepKey(it) in excluded }
}
@Composable private fun RemoteExerciseVideo(url: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var playbackError by remember(url) { mutableStateOf(false) }
    var prepared by remember(url) { mutableStateOf(false) }
    val videoView = remember(url, context, lifecycleOwner) {
        VideoView(context).apply {
            setVideoURI(Uri.parse(url))
            setOnPreparedListener { player ->
                player.isLooping = true
                prepared = true
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
            }
            setOnCompletionListener { start() }
            setOnErrorListener { _, _, _ -> playbackError = true; true }
        }
    }
    DisposableEffect(videoView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> videoView.pause()
                Lifecycle.Event.ON_RESUME -> { if (prepared) videoView.start() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            videoView.stopPlayback()
        }
    }
    AndroidView(factory = { videoView }, modifier = Modifier.fillMaxWidth().height(230.dp))
    if (playbackError) Text("视频暂时无法播放；其他教学内容仍可查看。", color = MaterialTheme.colorScheme.error)
}

@Composable private fun SmartWorkoutExercisePreview(
    source: SmartWorkoutExerciseSource, repository: TrainingRepository, onDismiss: () -> Unit
) {
    var guide by remember(source.sourceId) { mutableStateOf<ExerciseGuide?>(null) }
    var status by remember(source.sourceId) { mutableStateOf("正在读取只读教学预览…") }
    LaunchedEffect(source.sourceId, repository) {
        try {
            if (!repository.vault.load().configured) {
                status = "后端未连接，无法读取网站教学预览。"
                return@LaunchedEffect
            }
            val response = repository.loadSmartWorkoutExerciseGuide(source.sourceId)
            if (response == null) status = "该来源动作暂无网站教学资料。"
            else { guide = response; status = "" }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            status = "网站教学预览暂不可用；可以返回继续选择本地动作或来源动作。"
        }
    }
    FormDialog(source.name, onDismiss) {
        Text("SmartWorkout 动作教学", style = MaterialTheme.typography.bodySmall)
        val preview = guide
        if (preview == null) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val imageUrl = preview.imageUrl?.takeIf(String::isNotBlank)
            val videoUrl = preview.videoUrl?.takeIf(String::isNotBlank)
            if (videoUrl != null) RemoteExerciseVideo(videoUrl)
            else if (imageUrl != null) RemoteExerciseImage(imageUrl)
            val sourceHost = runCatching { Uri.parse(preview.sourceUrl).host }.getOrNull()
            Text("来源：${sourceHost ?: "SmartWorkout"}", style = MaterialTheme.typography.bodySmall)
            if (preview.description.isNotBlank()) Text(preview.description)
            val steps = distinctGuideSteps(preview.instructions)
            if (steps.isNotEmpty()) {
                Text("动作步骤", style = MaterialTheme.typography.titleSmall)
                steps.forEachIndexed { index, step -> Text("${index + 1}. $step") }
            }
            if (preview.tips.isNotEmpty()) {
                Text("动作提示", style = MaterialTheme.typography.titleSmall)
                preview.tips.forEach { Text("• $it") }
            }
            if (preview.mistakes.isNotEmpty()) {
                Text("常见错误", style = MaterialTheme.typography.titleSmall)
                preview.mistakes.forEach { Text("• $it") }
            }
        }
    }
}

@Composable fun ExerciseDetail(exercise: Exercise, state: AppData, repository: TrainingRepository, onDismiss: () -> Unit) {
    var onlineGuide by remember(exercise.id) { mutableStateOf<ExerciseGuide?>(null) }
    var guideStatus by remember(exercise.id) { mutableStateOf("正在读取在线教学…") }
    LaunchedEffect(exercise.id, repository) {
        try {
            if (!repository.vault.load().configured) {
                guideStatus = "尚未连接后端，当前显示本地教学。"
                return@LaunchedEffect
            }
            val guide = repository.loadExerciseGuide(exercise.id)
            if (guide == null) guideStatus = "后端暂无该动作的在线教学，当前显示本地教学。"
            else { onlineGuide = guide; guideStatus = "" }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            guideStatus = "在线教学暂不可用（网络或服务器错误），当前显示本地教学。"
        }
    }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).associate { val m = it.value<Muscle>(); m.id to m } }
    val tagNames = equipmentNames()
    val guide = onlineGuide
    val imageUrl = guide?.imageUrl?.takeIf(String::isNotBlank)
    val videoUrl = guide?.videoUrl?.takeIf(String::isNotBlank)
    FormDialog(exercise.name, onDismiss) {
        Text(if (exercise.type == "stretch") "拉伸动作" else "力量训练", color = MaterialTheme.colorScheme.primary)
        if (videoUrl != null) RemoteExerciseVideo(videoUrl)
        else if (imageUrl != null) RemoteExerciseImage(imageUrl)
        Text("主要部位：" + exercise.muscles.joinToString("、") { muscles[it]?.name ?: it })
        if (exercise.secondaryMuscles.isNotEmpty()) Text("辅助涉及：" + exercise.secondaryMuscles.joinToString("、") { muscles[it]?.name ?: it })
        if (guide == null || videoUrl != null) {
            MuscleDiagram((exercise.muscles + exercise.secondaryMuscles).flatMap { muscles[it]?.mapKeys ?: emptyList() }.toSet())
        }
        Text("器械条件：" + equipmentRequirementText(exercise.equipmentTags, tagNames))
        if (guide == null) {
            exercise.mediaIds.forEach { id -> state.record(Kind.media, id)?.value<Media>()?.let { MediaImage(it, repository, Modifier.fillMaxWidth().height(260.dp)) } }
        }
        Text("执行方式", style = MaterialTheme.typography.titleMedium)
        exercise.instructions.forEachIndexed { index, text -> Text("${index + 1}. $text") }
        if (exercise.unilateral) Text("需要区分左右侧时，请在计划组中分别选择左侧和右侧。")
        if (exercise.notes.isNotBlank()) Text(exercise.notes)
        Text("在线教学", style = MaterialTheme.typography.titleMedium)
        if (guide == null) Text(guideStatus, color = if (guideStatus.startsWith("在线教学暂不可用")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val sourceHost = runCatching { Uri.parse(guide.sourceUrl).host }.getOrNull()
            Text("来源：${sourceHost ?: "在线动作库"}", style = MaterialTheme.typography.bodySmall)
            if (guide.description.isNotBlank()) Text(guide.description)
            val websiteSteps = distinctGuideSteps(guide.instructions, exercise.instructions)
            if (websiteSteps.isNotEmpty()) {
                Text("网站动作步骤", style = MaterialTheme.typography.titleSmall)
                websiteSteps.forEachIndexed { index, step -> Text("${index + 1}. $step") }
            }
            if (guide.tips.isNotEmpty()) {
                Text("动作提示", style = MaterialTheme.typography.titleSmall)
                guide.tips.forEach { Text("• $it") }
            }
            if (guide.mistakes.isNotEmpty()) {
                Text("常见错误", style = MaterialTheme.typography.titleSmall)
                guide.mistakes.forEach { Text("• $it") }
            }
        }
    }
}
@Composable fun equipmentNames(): Map<String, String> {
    val context = LocalContext.current
    return remember(context) { context.assets.open("equipment-tags.json").bufferedReader().use { wireJson.decodeFromString<Map<String, String>>(it.readText()) } }
}
internal fun equipmentRequirementText(equipmentTags: List<String>, names: Map<String, String>): String {
    val unknown = SOURCE_UNKNOWN_EQUIPMENT_TAG in equipmentTags
    val labels = equipmentTags.filterNot { it == SOURCE_UNKNOWN_EQUIPMENT_TAG }.map { names[it] ?: it }
    return when {
        unknown && labels.isNotEmpty() -> "器械条件未知（来源标注：${labels.joinToString("、")}）"
        unknown -> "器械条件未知"
        labels.isEmpty() -> "无需专用器械"
        else -> labels.joinToString("、")
    }
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
    var sourceDetail by remember { mutableStateOf<SmartWorkoutExerciseSource?>(null) }
    var smartWorkoutSources by remember { mutableStateOf<List<SmartWorkoutExerciseSource>>(emptyList()) }
    var smartWorkoutStatus by remember { mutableStateOf("正在读取网站动作目录…") }
    var smartWorkoutError by remember { mutableStateOf<String?>(null) }
    var selectingSourceId by remember { mutableStateOf<String?>(null) }
    var sourceReloadRequest by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val all = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).map { it.value<Exercise>() } }
    val equipment = remember(state.documents[Kind.equipment]) { state.records(Kind.equipment).map { it.value<Equipment>() } }
    val muscles = remember(state.documents[Kind.muscle]) { state.records(Kind.muscle).map { it.value<Muscle>() } }
    val names = equipmentNames()
    val choices = remember(all, equipment, filterMuscles, filterType, query, gymId) {
        all.filter { it.type == filterType && (filterMuscles.isEmpty() || it.muscles.any(filterMuscles::contains) || filterType == "stretch" && it.secondaryMuscles.any(filterMuscles::contains)) && exerciseAvailable(it, gymId, equipment) && it.name.contains(query, true) }.sortedBy { it.name }
    }
    val sourceChoices = remember(smartWorkoutSources, query) {
        smartWorkoutSources.filter { it.name.contains(query, true) }
    }
    LaunchedEffect(repository, sourceReloadRequest) {
        smartWorkoutStatus = try {
            if (!repository.vault.load().configured) "后端未连接；本地动作仍可选择。"
            else {
                smartWorkoutSources = repository.loadSmartWorkoutExercises()
                "网站动作目录已载入 ${smartWorkoutSources.size} 个动作。"
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            "网站动作目录暂不可用；本地动作仍可选择。"
        }
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
                Text(if (gymId == null) "未选择健身房，显示动作所需条件" else "本地动作仅显示当前健身房器械可执行的候选", style = MaterialTheme.typography.bodySmall)
                if (filterType == "stretch" && initialMuscles.isEmpty()) Text("没有已选力量动作作为匹配来源，可以手动筛选拉伸部位。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choices.isNotEmpty()) item { Text("本地动作", style = MaterialTheme.typography.titleSmall) }
                    if (choices.isEmpty()) item { EmptyMessage("当前条件没有匹配的本地动作；也可从网站动作目录中选择。") }
                    items(choices, key = { "local-${it.id}" }) { exercise ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val media = exercise.mediaIds.firstOrNull()?.let { state.record(Kind.media, it)?.value<Media>() }
                                if (media != null) MediaImage(media, repository, Modifier.size(78.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                                    Text(exercise.muscles.joinToString("、") { id -> muscles.firstOrNull { it.id == id }?.name ?: id }, style = MaterialTheme.typography.bodySmall)
                                    Text(equipmentRequirementText(exercise.equipmentTags, names), style = MaterialTheme.typography.bodySmall)
                                    Row { TextButton(onClick = { detail = exercise }) { Text("查看教学") }; TextButton(onClick = { onChoose(exercise) }) { Text("选择动作") } }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("SmartWorkout 网站动作", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { sourceReloadRequest++ }) { Text("重新加载") }
                        }
                        Text(smartWorkoutStatus, style = MaterialTheme.typography.bodySmall)
                        Text("网站动作未按部位或健身房器械筛选。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        smartWorkoutError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    items(sourceChoices, key = { "source-${it.sourceId}" }) { source ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.name, style = MaterialTheme.typography.titleSmall)
                                    Row {
                                        TextButton(onClick = { sourceDetail = source }) { Text("查看教学") }
                                        TextButton(
                                            enabled = selectingSourceId == null,
                                            onClick = {
                                                selectingSourceId = source.sourceId
                                                smartWorkoutError = null
                                                scope.launch {
                                                    try {
                                                        val selected = repository.selectSmartWorkoutExercise(source.sourceId, filterType)
                                                        onChoose(selected)
                                                    } catch (error: kotlinx.coroutines.CancellationException) {
                                                        throw error
                                                    } catch (error: Exception) {
                                                        smartWorkoutError = error.message ?: "选择网站动作失败；本地动作仍可使用。"
                                                    } finally {
                                                        selectingSourceId = null
                                                    }
                                                }
                                            }
                                        ) { Text(if (selectingSourceId == source.sourceId) "添加中…" else "选择动作") }
                                    }
                                }
                            }
                        }
                    }
                    if (sourceChoices.isEmpty() && smartWorkoutSources.isNotEmpty()) item { Text("网站动作目录中没有匹配名称的动作。", style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = onDismiss) { Text("返回") }
            }
        }
    }
    if (muscleDialog) MultiChoiceDialog("筛选训练部位", muscles.map { it.id to it.name }, filterMuscles, { muscleDialog = false }) { filterMuscles = it; muscleDialog = false }
    detail?.let { ExerciseDetail(it, state, repository) { detail = null } }
    sourceDetail?.let { SmartWorkoutExercisePreview(it, repository) { sourceDetail = null } }
}
