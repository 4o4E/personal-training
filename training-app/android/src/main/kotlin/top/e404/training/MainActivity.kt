package top.e404.training

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.jsonPrimitive
import top.e404.training.shared.*

class MainActivity : ComponentActivity() {
    private lateinit var model: TrainingViewModel
    private var permissionRevision by mutableIntStateOf(0)
    private var pendingWorkoutIntent by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[TrainingViewModel::class.java]
        val launchIntent = intent
        pendingWorkoutIntent = launchIntent?.getStringExtra("training.destination") == "workout"
        launchIntent?.removeExtra("training.destination")
        setContent {
            val preferences = (application as TrainingApplication).preferences
            val choices by preferences.data.collectAsStateWithLifecycle()
            TrainingTheme(choices.appearance) {
                TrainingApp(model, permissionRevision, preferences, pendingWorkoutIntent) { pendingWorkoutIntent = false }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val openWorkout = intent.getStringExtra("training.destination") == "workout"
        intent.removeExtra("training.destination")
        setIntent(intent)
        if (openWorkout) pendingWorkoutIntent = true
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
        model.action { model.repository.restoreTimer(); model.repository.enqueueSync() }
    }
}

private object Routes {
    const val workout = "workout"
    const val adjust = "adjust"
    const val history = "history"
    const val settings = "settings"
    const val day = "history/{date}"
    const val sessionAdjust = "session-adjust"
    const val setup = "adjust/setup"
    const val advanced = "adjust/advanced"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrainingApp(viewModel: TrainingViewModel, permissionRevision: Int, preferences: AppPreferences, openWorkout: Boolean = false, consumedWorkout: () -> Unit = {}) {
    val state by viewModel.repository.data.collectAsStateWithLifecycle()
    val choices by preferences.data.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val nav = androidx.navigation.compose.rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.workout
    val context = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable(stateSaver = jsonSaver(Document.serializer().nullable)) { mutableStateOf<Document?>(null) }
    var deleting by rememberSaveable(stateSaver = jsonSaver(Document.serializer().nullable)) { mutableStateOf(null) }
    var preview by rememberSaveable(stateSaver = jsonSaver(Plan.serializer().nullable)) { mutableStateOf<Plan?>(null) }
    var previewEditable by rememberSaveable { mutableStateOf(false) }
    var daySetup by rememberSaveable(stateSaver = jsonSaver(DaySetupRequest.serializer().nullable)) { mutableStateOf<DaySetupRequest?>(null) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showGyms by rememberSaveable { mutableStateOf(false) }
    var pendingStart by rememberSaveable(stateSaver = jsonSaver(Plan.serializer().nullable)) { mutableStateOf<Plan?>(null) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.let(viewModel::start); pendingStart = null
    }
    val startTraining: (Plan) -> Unit = { plan ->
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = plan
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else viewModel.start(plan)
        nav.navigate(Routes.workout) { launchSingleTop = true }
        showGyms = false
    }
    val activeDocument = remember(state.documents[Kind.session]) {
        state.records(Kind.session).firstOrNull { it.data["status"]?.jsonPrimitive?.content == "active" }
    }
    val active = remember(activeDocument) { activeDocument?.value<Session>() }
    LaunchedEffect(state.ready) {
        if (state.ready && choices.guide == null) {
            if (viewModel.repository.vault.load().configured || listOf(Kind.program, Kind.plan, Kind.session).any { state.records(it).isNotEmpty() })
                preferences.finishGuide("existing")
            else showGuide = true
        }
    }
    LaunchedEffect(openWorkout, activeDocument?.id) {
        if (openWorkout) {
            nav.navigate(Routes.workout) { launchSingleTop = true }
            consumedWorkout()
        }
    }
    val mainRoute = route in setOf(Routes.workout, Routes.adjust, Routes.history)
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text("个人训练", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
            NavigationDrawerItem(label = { Text("设置") }, selected = false, onClick = { nav.navigate(Routes.settings); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(label = { Text("健身房与器械（可选）") }, selected = showGyms, onClick = { showGyms = true; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(label = { Text("使用教程") }, selected = false, onClick = { showGuide = true; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(when { showGyms -> "健身房与器械"; route == Routes.settings -> "设置"; route == Routes.day -> "每日运动"; route == Routes.sessionAdjust -> "本次剩余内容"; route == Routes.setup -> "整轮计划配置"; route == Routes.advanced -> "全部计划"; else -> when(route) { Routes.workout -> "开始训练"; Routes.adjust -> "调整计划"; else -> "运动记录" } }) }, navigationIcon = {
                    if (!mainRoute || showGyms) TextButton(onClick = { if (showGyms) showGyms = false else nav.popBackStack() }) { Text("返回") }
                    else IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.semantics { contentDescription = "打开菜单" }) { NavigationGlyph("菜单") }
                })
            },
            bottomBar = {
                if (mainRoute && !showGyms) NavigationBar {
                    listOf(Routes.workout to "开始训练", Routes.adjust to "调整计划", Routes.history to "运动记录").forEach { (destination, title) ->
                        NavigationBarItem(selected = route == destination, onClick = {
                            nav.navigate(destination) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }, modifier = Modifier.semantics { contentDescription = title }, icon = { NavigationGlyph(title) }, label = { Text(title) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (mainRoute && !showGyms) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("${state.syncMessage} · 最近同步 ${state.lastSuccessfulSyncAt?.let(::localDateTime) ?: "尚无"}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("待上传 ${state.pending} · 冲突 ${state.conflicts.size} · 素材 ${when (state.mediaSyncStatus) { MediaSyncStatus.idle -> "未开始"; MediaSyncStatus.checking -> "检查中"; MediaSyncStatus.ready -> "已就绪"; MediaSyncStatus.error -> "异常" }}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (showGyms) GymsScreen(state, viewModel, { editing = it }, { deleting = it })
                    else androidx.navigation.compose.NavHost(navController = nav, startDestination = Routes.workout, modifier = Modifier.fillMaxSize()) {
                        composable(Routes.workout) {
                            if (active != null) WorkoutScreen(active, state, viewModel, permissionRevision) { nav.navigate(Routes.sessionAdjust) }
                            else StartOverview(state, startTraining, { preview = it; previewEditable = false }) { nav.navigate(Routes.adjust) }
                        }
                        composable(Routes.adjust) {
                            AdjustPrograms(state, viewModel, { editing = it }, { preview = it; previewEditable = true }, { nav.navigate(Routes.sessionAdjust) }, { daySetup = null; nav.navigate(Routes.setup) }, { request -> daySetup = request; nav.navigate(Routes.setup) }, { nav.navigate(Routes.advanced) })
                        }
                        composable(Routes.history) {
                            HistoryCalendarScreen(
                                state = state,
                                onDay = { date -> nav.navigate("history/$date") },
                                onNewActivity = { date ->
                                    val id = newId()
                                    editing = document(Kind.activity, id, ActivityRecord(id, "", date))
                                }
                            )
                        }
                        composable(Routes.settings) {
                            SettingsScreen(state, viewModel, permissionRevision, preferences) { nav.popBackStack() }
                        }
                        composable(Routes.day, arguments = listOf(navArgument("date") { type = NavType.StringType })) { backStack ->
                            DayHistoryScreen(
                                backStack.arguments?.getString("date").orEmpty(),
                                state,
                                viewModel,
                                remove = { deleting = it },
                                editActivity = { editing = it },
                                addActivity = { date ->
                                    val id = newId()
                                    editing = document(Kind.activity, id, ActivityRecord(id, "", date))
                                }
                            )
                        }
                        composable(Routes.sessionAdjust) {
                            LaunchedEffect(state.ready, active?.id) { if (state.ready && active == null) nav.popBackStack() }
                            if (active != null) SessionAdjustScreen(active, state, viewModel)
                            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                        composable(Routes.setup) {
                            PresetSetupScreen(state, viewModel, daySetup) { daySetup = null; nav.popBackStack() }
                        }
                        composable(Routes.advanced) {
                            AdvancedPlansScreen(state, startTraining, { editing = it }, { deleting = it })
                        }
                    }
                }
            }
        }
    }
    editing?.let { source -> key(source.kind, source.id) {
        when (source.kind) {
            Kind.gym -> GymEditor(source, viewModel) { editing = null }
            Kind.equipment -> EquipmentEditor(source, state, viewModel) { editing = null }
            Kind.program -> ProgramEditor(source, state, viewModel) { editing = null }
            Kind.plan -> PlanEditor(source, state, viewModel) { editing = null }
            Kind.activity -> ActivityEditor(source, viewModel) { editing = null }
            else -> error("此资料不使用该编辑入口")
        }
    } }
    preview?.let { plan ->
        PlanPreview(
            plan, state, viewModel,
            onDismiss = { preview = null; previewEditable = false },
            onEdit = if (previewEditable) ({
                editing = state.record(Kind.plan, plan.id)
                preview = null
                previewEditable = false
            }) else null
        )
    }
    deleting?.let { source ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${source.displayName()}？") },
            text = { Text(if (source.kind == Kind.session) "删除训练记录会改变历史统计和按完成情况计算的下一项训练。" else "删除会同步到后端。正在被其他资料使用的内容需要先解除关联。") },
            confirmButton = { TextButton(onClick = { viewModel.action { viewModel.repository.removeTrainingDocument(source); deleting = null } }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (showGuide) FirstUseGuide(state, viewModel, { if (choices.guide == null) preferences.finishGuide("skipped"); showGuide = false }) { choosePlan ->
        preferences.finishGuide("completed"); showGuide = false
        if (choosePlan) nav.navigate(Routes.adjust)
    }
    message?.let { text -> AlertDialog(onDismissRequest = viewModel::dismissMessage, title = { Text("操作未完成") }, text = { Text(text) }, confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("知道了") } }) }
}


@Composable private fun NavigationGlyph(name: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), strokeWidth = 2 * unit)
        when (name) {
            "菜单" -> { line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f) }
            "开始训练" -> { line(7f, 4f, 20f, 12f); line(20f, 12f, 7f, 20f); line(7f, 20f, 7f, 4f) }
            "调整计划" -> { line(4f, 20f, 19f, 5f); line(4f, 20f, 9f, 19f); line(4f, 20f, 5f, 15f); line(15f, 5f, 19f, 9f) }
            "运动记录" -> { drawCircle(color, 10 * unit, style = Stroke(2 * unit)); line(12f, 6f, 12f, 12f); line(12f, 12f, 17f, 15f) }
        }
    }
}

private fun AppData.completedSessions(): List<Session> = records(Kind.session).filter { it.data["status"]?.jsonPrimitive?.content == "completed" }.map { it.value<Session>() }
private fun AppData.planParts(plan: Plan): String {
    val ids = plan.items.flatMap { item -> record(Kind.exercise, item.exerciseId)?.value<Exercise>()?.muscles ?: emptyList() }.distinct()
    return ids.joinToString("、") { record(Kind.muscle, it)?.value<Muscle>()?.name ?: it }.ifEmpty { "尚未选择动作" }
}
private fun AppData.planDate(plan: Plan) = plan.date ?: "未指定日期"


@Composable fun AdvancedPlansScreen(state: AppData, start: (Plan) -> Unit, edit: (Document) -> Unit, remove: (Document) -> Unit) {
    val programs = state.records(Kind.program)
    val plans = remember(state.documents[Kind.plan]) { state.records(Kind.plan).map { it to it.value<Plan>() }.sortedWith(compareBy<Pair<Document, Plan>> { it.second.date == null }.thenBy { it.second.date }) }
    val completed = remember(state.documents[Kind.session]) { state.completedSessions().mapNotNull { it.snapshot?.id }.toSet() }
    val hasActiveSession = state.records(Kind.session).any { it.value<Session>().status == "active" }
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
                if (plan.date != null || plan.programId == null) Button(onClick = { start(plan) }, enabled = !hasActiveSession && plan.items.isNotEmpty()) {
                    Text(if (plan.date != null) "开始指定日期训练" else "开始独立训练")
                }
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


