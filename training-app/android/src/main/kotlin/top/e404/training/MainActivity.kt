package top.e404.training

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.jsonPrimitive
import top.e404.training.shared.*

class MainActivity : ComponentActivity() {
    private lateinit var model: TrainingViewModel
    private var permissionRevision by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[TrainingViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF236A58), secondary = Color(0xFF516A60), surface = Color(0xFFFAFCFA))) {
                TrainingApp(model, permissionRevision)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        permissionRevision++
        model.action { model.repository.restoreTimer(); model.repository.enqueueSync() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrainingApp(viewModel: TrainingViewModel, permissionRevision: Int) {
    val state by viewModel.repository.data.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("训练") }
    var selectedProgram by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(stateSaver = jsonSaver(Document.serializer().nullable)) { mutableStateOf<Document?>(null) }
    var deleting by rememberSaveable(stateSaver = jsonSaver(Document.serializer().nullable)) { mutableStateOf<Document?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingStart by rememberSaveable(stateSaver = jsonSaver(Plan.serializer().nullable)) { mutableStateOf<Plan?>(null) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.let(viewModel::start); pendingStart = null
    }
    val startTraining: (Plan) -> Unit = { plan ->
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = plan; requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.start(plan)
    }
    val activeDocument = remember(state.documents[Kind.session]) {
        state.records(Kind.session).firstOrNull { it.data["status"]?.jsonPrimitive?.content == "active" }
    }
    val active = remember(activeDocument) { activeDocument?.value<Session>() }
    if (!state.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else Scaffold(
        topBar = { TopAppBar(title = { Text("个人训练") }, actions = { TextButton(onClick = { showSettings = true }) { Text("设置") } }) },
        bottomBar = { NavigationBar { listOf("训练", "计划", "动作", "健身房", "记录").forEach { title ->
            NavigationBarItem(selected = title == tab, onClick = { tab = title }, icon = { Text(when (title) { "训练" -> "练习"; "计划" -> "安排"; "动作" -> "教学"; "健身房" -> "器械"; else -> "历史" }, style = MaterialTheme.typography.labelMedium) }, label = { Text(title) })
        } } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.syncMessage, style = MaterialTheme.typography.bodySmall)
                if (state.pending > 0) Text("待同步 ${state.pending} 项", style = MaterialTheme.typography.bodySmall)
            }
            when (tab) {
                "训练" -> if (active != null) WorkoutScreen(active, state, viewModel, permissionRevision) else OverviewScreen(state, selectedProgram, { selectedProgram = it }, startTraining, { editing = it }) { tab = "计划" }
                "计划" -> PlansScreen(state, startTraining, { editing = it }, { deleting = it })
                "动作" -> CatalogScreen(state, viewModel)
                "健身房" -> GymsScreen(state, viewModel, { editing = it }, { deleting = it })
                "记录" -> HistoryScreen(state, viewModel, { deleting = it })
            }
        }
    }
    editing?.let { source -> key(source.kind, source.id) {
        when (source.kind) {
            Kind.gym -> GymEditor(source, viewModel) { editing = null }
            Kind.equipment -> EquipmentEditor(source, state, viewModel) { editing = null }
            Kind.program -> ProgramEditor(source, state, viewModel) { editing = null }
            Kind.plan -> PlanEditor(source, state, viewModel) { editing = null }
            else -> error("此资料不使用该编辑入口")
        }
    } }
    deleting?.let { source ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${source.displayName()}？") },
            text = { Text(if (source.kind == Kind.session) "删除训练记录会改变历史统计和按完成情况计算的下一项训练。" else "删除会同步到后端。正在被其他资料使用的内容需要先解除关联。") },
            confirmButton = { TextButton(onClick = { viewModel.action { viewModel.repository.remove(source); deleting = null } }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (showSettings) SettingsScreen(state, viewModel, permissionRevision) { showSettings = false }
    message?.let { text -> AlertDialog(onDismissRequest = viewModel::dismissMessage, title = { Text("操作未完成") }, text = { Text(text) }, confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("知道了") } }) }
}

private fun AppData.completedSessions(): List<Session> = records(Kind.session).filter { it.data["status"]?.jsonPrimitive?.content == "completed" }.map { it.value<Session>() }
private fun AppData.planParts(plan: Plan): String {
    val ids = plan.items.flatMap { item -> record(Kind.exercise, item.exerciseId)?.value<Exercise>()?.muscles ?: emptyList() }.distinct()
    return ids.joinToString("、") { record(Kind.muscle, it)?.value<Muscle>()?.name ?: it }.ifEmpty { "尚未选择动作" }
}
private fun AppData.planDate(plan: Plan) = plan.date ?: "未指定日期"

@Composable fun OverviewScreen(state: AppData, selectedProgram: String?, selectProgram: (String) -> Unit, start: (Plan) -> Unit, edit: (Document) -> Unit, showPlans: () -> Unit) {
    val programs = remember(state.documents[Kind.program]) { state.records(Kind.program).map { it.value<Program>() } }
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it.value<Plan>() } }
    val complete = remember(state.documents[Kind.session]) { state.completedSessions() }
    val consumed = remember(complete) { complete.map { it.snapshot.id }.toSet() }
    val program = programs.firstOrNull { it.id == selectedProgram } ?: programs.firstOrNull()
    val next = remember(program, complete) { program?.let { nextSlot(it, complete) } }
    // 未指定日期的计划跟随当前轮换顺序，不受创建先后影响。
    val slotOrder = remember(program, next) {
        program?.slots?.let { slots ->
            val start = slots.indexOfFirst { it.id == next!!.id }
            slots.indices.associate { index -> slots[index].id to ((index - start + slots.size) % slots.size) }
        } ?: emptyMap()
    }
    val upcoming = remember(plans, consumed, program, slotOrder) { plans.filter { it.id !in consumed && (program == null || it.programId == program.id) }.sortedWith(compareBy<Plan> { it.date == null }.thenBy { it.date }.thenBy { plan -> plan.slotId?.let { slotOrder[it] } }) }
    val nextPlan = if (program == null) upcoming.firstOrNull() else upcoming.firstOrNull { it.slotId == next!!.id }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            InfoCard("下次训练") {
                if (programs.isEmpty()) {
                    Text("先配置分化方式和训练部位，再安排动作。")
                    Button(onClick = { val p = Program(newId(), "", listOf(SplitSlot(newId(), "训练一", emptyList()), SplitSlot(newId(), "训练二", emptyList()), SplitSlot(newId(), "训练三", emptyList()))); edit(document(Kind.program, p.id, p)) }) { Text("配置分化训练") }
                } else {
                    Choice("分化方案", program!!.id, programs.map { it.id to "${it.name} · ${it.slots.size}分化" }, selectProgram)
                    Text(next!!.name, style = MaterialTheme.typography.titleLarge)
                    Text(next.muscles.joinToString("、") { state.record(Kind.muscle, it)?.value<Muscle>()?.name ?: it })
                    Text("完成训练后推进，休息不会跳过这一项。", style = MaterialTheme.typography.bodySmall)
                }
                if (nextPlan != null) {
                    Text(nextPlan.name, style = MaterialTheme.typography.titleMedium)
                    Text("${state.planDate(nextPlan)} · ${state.planParts(nextPlan)}")
                    Text("${nextPlan.items.size} 个动作 · ${nextPlan.items.sumOf { it.sets.size }} 组")
                    Row { Button(onClick = { start(nextPlan) }) { Text("开始训练") }; TextButton(onClick = { edit(state.record(Kind.plan, nextPlan.id)!!) }) { Text("预览与调整") } }
                } else if (program != null) {
                    Text("这项训练还没有待执行的具体计划。")
                    Button(onClick = { val plan = Plan(newId(), next!!.name, program.id, next.id); edit(document(Kind.plan, plan.id, plan)) }) { Text("安排这次训练") }
                }
            }
        }
        item { Text("未来安排", style = MaterialTheme.typography.titleLarge) }
        if (upcoming.isEmpty()) item { EmptyMessage("暂无待训练计划。助手通过接口安排后，会在同步后出现在这里。") }
        items(upcoming, key = { it.id }) { plan ->
            InfoCard(plan.name) {
                Text(state.planDate(plan))
                Text(state.planParts(plan))
                TextButton(onClick = { edit(state.record(Kind.plan, plan.id)!!) }) { Text("预览动作与组要求") }
            }
        }
        item { OutlinedButton(onClick = showPlans) { Text("管理分化与全部计划") } }
    }
}

@Composable fun PlansScreen(state: AppData, start: (Plan) -> Unit, edit: (Document) -> Unit, remove: (Document) -> Unit) {
    val programs = state.records(Kind.program)
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it to it.value<Plan>() }.sortedWith(compareBy<Pair<Document, Plan>> { it.second.date == null }.thenBy { it.second.date }) }
    val completed = remember(state.documents[Kind.session]) { state.completedSessions().map { it.snapshot.id }.toSet() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Row { Button(onClick = { val p = Program(newId(), "", listOf(SplitSlot(newId(), "训练一", emptyList()))); edit(document(Kind.program, p.id, p)) }) { Text("新增分化") }; TextButton(onClick = { val p = Plan(newId(), ""); edit(document(Kind.plan, p.id, p)) }) { Text("新增计划") } } }
        items(programs, key = { "program-${it.id}" }) { doc ->
            val program = remember(doc) { doc.value<Program>() }
            InfoCard("${program.name} · ${program.slots.size}分化") {
                program.slots.forEachIndexed { index, slot -> Text("${index + 1}. ${slot.name}：" + slot.muscles.joinToString("、") { state.record(Kind.muscle, it)?.value<Muscle>()?.name ?: it }) }
                Row { TextButton(onClick = { edit(doc) }) { Text("编辑分化") }; TextButton(onClick = { remove(doc) }) { Text("删除方案") } }
            }
        }
        item { Text("具体训练计划", style = MaterialTheme.typography.titleLarge) }
        if (plans.isEmpty()) item { EmptyMessage("可以在这里创建计划，也可以由助手通过同一套接口安排。") }
        items(plans, key = { "plan-${it.first.id}" }) { (doc, plan) ->
            InfoCard(plan.name) {
                Text("${state.planDate(plan)} · ${if (plan.id in completed) "已有完成记录" else "待训练"}")
                Text(state.planParts(plan))
                Row { TextButton(onClick = { edit(doc) }) { Text("查看与编辑") }; TextButton(onClick = { start(plan) }, enabled = plan.items.isNotEmpty()) { Text("开始训练") } }
                Row {
                    TextButton(onClick = { val copy = plan.copy(id = newId(), name = "${plan.name}（复制）", date = null, items = plan.items.map { it.copy(id = newId()) }); edit(document(Kind.plan, copy.id, copy)) }) { Text("复制为新计划") }
                    TextButton(onClick = { remove(doc) }) { Text("删除计划") }
                }
            }
        }
    }
}

@Composable fun GymsScreen(state: AppData, viewModel: TrainingViewModel, edit: (Document) -> Unit, remove: (Document) -> Unit) {
    val gyms = state.records(Kind.gym)
    val equipment = remember(state.documents[Kind.equipment]) { state.records(Kind.equipment) }
    val names = equipmentNames()
    var selectedGym by rememberSaveable { mutableStateOf<String?>(null) }
    val current = gyms.firstOrNull { it.id == selectedGym } ?: gyms.firstOrNull()
    var inspect by remember { mutableStateOf<Equipment?>(null) }
    var exerciseDetail by remember { mutableStateOf<Exercise?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Button(onClick = { val gym = Gym(newId(), ""); edit(document(Kind.gym, gym.id, gym)) }) { Text("新增健身房") } }
        if (current == null) item { EmptyMessage("记录实际健身房和器械后，动作候选会按这些器械筛选。") }
        else {
            item {
                Choice("当前健身房", current.id, gyms.map { it.id to it.value<Gym>().name }, { selectedGym = it })
                Row { TextButton(onClick = { edit(current) }) { Text("编辑健身房") }; TextButton(onClick = { remove(current) }) { Text("删除健身房") } }
                Button(onClick = { val item = Equipment(newId(), "", current.id, emptyList()); edit(document(Kind.equipment, item.id, item)) }) { Text("记录一台器械") }
            }
            items(equipment.filter { it.value<Equipment>().gymId == current.id }, key = { it.id }) { doc ->
                val item = remember(doc) { doc.value<Equipment>() }
                InfoCard(item.name) {
                    Text("${item.unit} · " + when (item.meaning) { "per_hand" -> "单只重量"; "per_side" -> "单侧配重"; else -> "总重量" })
                    Text(item.tags.joinToString("、") { names[it] ?: it })
                    Row { TextButton(onClick = { inspect = item }) { Text("图片与使用方式") }; TextButton(onClick = { edit(doc) }) { Text("编辑器械") }; TextButton(onClick = { remove(doc) }) { Text("删除") } }
                }
            }
        }
    }
    inspect?.let { item -> FormDialog(item.name, { inspect = null }) {
        item.mediaId?.let { id -> state.record(Kind.media, id)?.value<Media>()?.let { MediaImage(it, viewModel.repository, Modifier.fillMaxWidth().height(240.dp)) } }
        Text(item.instructions.ifBlank { "尚未填写该台器械的使用说明，可以在编辑器械中补充。" })
        val related = state.records(Kind.exercise).map { it.value<Exercise>() }.filter { it.equipmentTags.firstOrNull() in item.tags }
        Text("对应动作教学", style = MaterialTheme.typography.titleMedium)
        related.forEach { exercise -> TextButton(onClick = { exerciseDetail = exercise }) { Text("查看${exercise.name}教学") } }
    } }
    exerciseDetail?.let { ExerciseDetail(it, state, viewModel.repository) { exerciseDetail = null } }
}

@Composable fun CatalogScreen(state: AppData, viewModel: TrainingViewModel) {
    var picker by rememberSaveable { mutableStateOf(false) }
    var gymId by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Exercise?>(null) }
    val gyms = state.records(Kind.gym)
    val exercises = remember(state.documents[Kind.exercise]) { state.records(Kind.exercise).map { it.value<Exercise>() } }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("动作与拉伸教学", style = MaterialTheme.typography.headlineSmall)
        Text("${exercises.count { it.type == "strength" }} 项训练动作 · ${exercises.count { it.type == "stretch" }} 项拉伸")
        Choice<String?>("查看哪个健身房的可用动作", gymId, listOf(null to "全部动作，显示器械条件") + gyms.map { it.id to it.value<Gym>().name }, { gymId = it })
        Button(onClick = { picker = true }) { Text("按部位查找动作与拉伸") }
        Text("可以查看肌肉部位、器械条件、实际图解和中文执行方式。查看教学不会添加训练记录。")
    }
    if (picker) ExercisePicker(state, viewModel.repository, gymId, emptySet(), null, { picker = false }) { detail = it }
    detail?.let { ExerciseDetail(it, state, viewModel.repository) { detail = null } }
}

@Composable fun HistoryScreen(state: AppData, viewModel: TrainingViewModel, remove: (Document) -> Unit) {
    val sessions = remember(state.documents[Kind.session]) { state.records(Kind.session).map { it to it.value<Session>() }.sortedByDescending { it.second.startedAt } }
    val summary = remember(sessions) { summarizeHistory(sessions.map { it.second }) }
    var detail by remember { mutableStateOf<Session?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("力量 ${summary.strengthSets} 组 · 拉伸 ${summary.stretchSets} 组", style = MaterialTheme.typography.titleMedium) }
        if (sessions.isEmpty()) item { EmptyMessage("尚无训练记录。开始训练并完成组后会保存在这里。") }
        items(sessions, key = { it.first.id }) { (doc, session) ->
            InfoCard(session.name) {
                Text(localDateTime(session.startedAt))
                Text(when (session.status) { "active" -> "进行中"; "completed" -> "已完成"; else -> "已取消，已练的组仍保留" })
                Text("实际完成 ${session.results.size} 组")
                Row { TextButton(onClick = { detail = session }) { Text("查看实际记录") }; if (session.status != "active") TextButton(onClick = { remove(doc) }) { Text("删除记录") } }
            }
        }
    }
    detail?.let { SessionHistory(it, state, viewModel) { detail = null } }
}

@Composable fun SessionHistory(session: Session, state: AppData, viewModel: TrainingViewModel, onDismiss: () -> Unit) {
    FormDialog(session.name, onDismiss) {
        Text("开始：${localDateTime(session.startedAt)}")
        session.endedAt?.let { Text("结束：${localDateTime(it)}") }
        if (session.snapshot.date != null) Text("原计划日期：${session.snapshot.date}")
        session.items.forEach { item ->
            val exercise = session.exercises.single { it.id == item.exerciseId }
            InfoCard(exercise.name) {
                session.results.filter { it.itemId == item.id }.sortedBy { it.setIndex }.forEach { result ->
                    Text("第 ${result.setIndex + 1} 组：" + listOfNotNull(result.reps?.let { "$it 次" }, result.seconds?.let { "$it 秒" }, sideText(result.side), loadText(result.load)).joinToString(" · "))
                }
                if (session.results.none { it.itemId == item.id }) Text("没有完成记录")
                Text("计划 ${item.sets.size} 组；未完成的计划组不计入实际组数。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (session.notes.isNotBlank()) Text("训练反馈：${session.notes}")
        Text("开始时的计划", style = MaterialTheme.typography.titleMedium)
        session.snapshot.items.forEach { item -> Text("${session.exercises.single { it.id == item.exerciseId }.name} · ${item.sets.size} 组") }
    }
}
