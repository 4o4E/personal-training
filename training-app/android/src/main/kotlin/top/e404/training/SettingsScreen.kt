package top.e404.training

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import top.e404.training.shared.*

@Composable fun SettingsScreen(state: AppData, viewModel: TrainingViewModel, permissionRevision: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = viewModel.repository
    val connection = remember { repository.vault.load() }
    var url by rememberSaveable { mutableStateOf(connection.url) }
    // token 草稿不进入 SavedState，也不进入业务数据库和备份。
    var token by remember { mutableStateOf(connection.token) }
    var permissionChanged by remember { mutableIntStateOf(0) }
    val notifications = remember(permissionRevision, permissionChanged) { TimerEngine.canNotify(context) }
    val exact = remember(permissionRevision) { TimerEngine.canSchedule(context) }
    var importUri by rememberSaveable { mutableStateOf<String?>(null) }
    var transferMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionChanged++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.action {
            context.contentResolver.openOutputStream(uri)!!.use { repository.export(it) }
            transferMessage = "已导出训练资料与素材，不包含连接 token。"
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri?.toString() }
    FormDialog("设置", onDismiss) {
        AppUpdateCard()
        InfoCard("原生提醒权限") {
            Text(if (notifications) "通知权限已具备" else "通知尚未授权或提醒渠道被关闭", color = if (notifications) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(if (exact) "定时唤醒权限已具备" else "需要允许定时唤醒", color = if (exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("通知授权与声音设置") }
            if (!exact && Build.VERSION.SDK_INT >= 31) Button(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("允许定时唤醒") }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("应用电池与后台设置") }
            Text("休息结束时唤醒应用并发送普通通知，不需要保持界面打开。提示音和振动遵循系统通知设置。", style = MaterialTheme.typography.bodySmall)
        }
        InfoCard("自有后端") {
            TextInput("后端地址", url, { url = it }, keyboardType = KeyboardType.Uri)
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("唯一 token") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("公网连接使用 HTTPS；离线训练不需要网络。")
            Row {
                Button(onClick = { viewModel.action { repository.saveConnection(BackendConnection(url, token.trim())) } }) { Text("保存并同步") }
                TextButton(onClick = { viewModel.action { repository.sync() } }) { Text("立即同步") }
            }
            Text(state.syncMessage)
            Text("待同步 ${state.pending} 项 · 待选择冲突 ${state.conflicts.size} 项")
            TextButton(onClick = { viewModel.action { repository.disconnect(); url = ""; token = "" } }) { Text("断开连接，保留手机资料") }
        }
        val tagNames = equipmentNames()
        state.conflicts.forEach { conflict ->
            InfoCard("内容冲突：${conflict.local.displayName()}") {
                Text("手机：\n${documentDescription(conflict.local, state, tagNames)}")
                ConflictImages(conflict.local, state, repository)
                Text("后端：\n${documentDescription(conflict.remote, state, tagNames)}")
                ConflictImages(conflict.remote, state, repository)
                Text("请选择要保留的内容，不会按时间自动覆盖。", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { viewModel.action { repository.resolveConflict(conflict.local.kind, conflict.local.id, true) } }) { Text("保留手机内容") }
                    TextButton(onClick = { viewModel.action { repository.resolveConflict(conflict.local.kind, conflict.local.id, false) } }) { Text("使用后端内容") }
                }
            }
        }
        InfoCard("导入与导出") {
            Text("完整数据包包含计划、器械、实际训练记录和教学素材，保留每组的原始单位。")
            Button(onClick = { export.launch("个人训练-${java.time.LocalDate.now(java.time.ZoneOffset.ofHours(8))}.zip") }) { Text("导出完整 ZIP") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !importing) { Text("选择 ZIP 导入") }
            transferMessage?.let { Text(it) }
        }
        InfoCard("教学素材") {
            Text("训练动作图解与中文说明：Hevy 公开动作目录。拉伸图解：RepDB、WorkoutGuide。肌肉部位示意：react-body-highlighter。")
            Text("动作资料和图片保存在应用内，可在离线时查看；通过自己的后端同步自建资料。")
        }
    }
    importUri?.let { uri ->
        AlertDialog(onDismissRequest = { if (!importing) importUri = null }, title = { Text("选择导入方式") }, text = { Text("合并会保留已有不同内容并报告冲突；明确替换则使用数据包内容覆盖手机中的同名标识，不会删除包外资料。导入之后仍需与后端同步。") },
            confirmButton = { TextButton(enabled = !importing, onClick = { importing = true; viewModel.action { try { context.contentResolver.openInputStream(Uri.parse(uri))!!.use { repository.import(it, true) }; transferMessage = "导入完成，已有内容已按数据包替换。"; importUri = null } finally { importing = false } } }) { Text("明确替换后导入") } },
            dismissButton = { Row {
                TextButton(enabled = !importing, onClick = { importing = true; viewModel.action { try { context.contentResolver.openInputStream(Uri.parse(uri))!!.use { repository.import(it, false) }; transferMessage = "合并导入完成。"; importUri = null } finally { importing = false } } }) { Text("仅合并相同与新增内容") }
                TextButton(enabled = !importing, onClick = { importUri = null }) { Text("取消") }
            } })
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
            append("${session.name} · ${localDateTime(session.startedAt)}\n")
            append(when (session.status) { "active" -> "进行中\n"; "completed" -> "已完成\n"; else -> "已取消\n" })
            session.items.forEach { item ->
                append("${session.exercises.single { it.id == item.exerciseId }.name}\n")
                session.results.forEach { result -> if (result.itemId == item.id) append("第 ${result.setIndex + 1} 组：" + listOfNotNull(result.reps?.let { "$it 次" }, result.seconds?.let { "$it 秒" }, sideText(result.side), loadText(result.load)).joinToString(" · ") + "\n") }
            }
            append(session.notes)
        } }
        Kind.program -> doc.value<Program>().let { "${it.name}\n" + it.slots.joinToString("\n") { slot -> "${slot.name}：${parts(slot.muscles)}" } }
        Kind.equipment -> doc.value<Equipment>().let { "${it.name} · ${it.unit} · " + when (it.meaning) { "per_hand" -> "单只重量"; "per_side" -> "单侧配重"; else -> "总重量" } + "\n" + it.tags.joinToString("、") { tag -> tagNames[tag] ?: tag } + "\n${it.instructions}\n${it.notes}" }
        Kind.gym -> doc.value<Gym>().let { "${it.name}\n${it.notes}" }
        Kind.exercise -> doc.value<Exercise>().let { "${it.name}\n主要部位：${parts(it.muscles)}\n辅助部位：${parts(it.secondaryMuscles)}\n" + it.instructions.joinToString("\n") + "\n${it.notes}" }
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
