package top.e404.training

import androidx.compose.foundation.layout.Arrangement
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import android.media.RingtoneManager

@Composable fun SettingsScreen(viewModel: TrainingViewModel, permissionRevision: Int, preferences: AppPreferences) {
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
    var choosingCompleteRestore by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionChanged++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.action {
            requireNotNull(context.contentResolver.openOutputStream(uri)) { "无法创建本机备份文件" }.use { repository.export(it) }
            transferMessage = "已导出本机训练资料；健康数据仅包含已下载缓存，不是完整健康备份。"
        }
    }
    val exportComplete = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.action {
            requireNotNull(context.contentResolver.openOutputStream(uri)) { "无法创建完整备份文件" }.use { repository.exportCompleteBackup(it) }
            transferMessage = "已从后端导出完整备份，包含其完整健康资料。"
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri?.toString() }
    val chooseTimerSound = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            preferences.timerRingtone(uri?.toString() ?: "")
        }
    }
    fun importBackup(uri: String, complete: Boolean, replace: Boolean) {
        importing = true
        viewModel.action {
            try {
                requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))) { "无法打开所选备份文件" }.use { input ->
                    if (complete) repository.restoreCompleteBackup(input, replace) else repository.import(input, replace)
                }
                transferMessage = when {
                    complete && replace -> "完整备份已明确替换恢复。"
                    complete -> "完整备份已合并恢复。"
                    replace -> "本机训练资料已替换导入。"
                    else -> "本机训练资料已合并导入。"
                }
                importUri = null
                choosingCompleteRestore = false
            } finally {
                importing = false
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("返回后继续当前操作；训练读数和休息计时会保留。", style = MaterialTheme.typography.bodySmall)
        val choices by preferences.data.collectAsState()
        InfoCard("应用外观") {
            Choice("深浅模式", choices.appearance, Appearance.entries.map { it to it.title }, preferences::appearance)
            Text("默认跟随系统；切换外观不会重置训练、编辑内容或休息计时。", style = MaterialTheme.typography.bodySmall)
        }
        AppUpdateCard()
        InfoCard("计时与系统提醒") {
            Text(if (notifications) "通知显示已具备" else "通知未授权或提醒渠道已关闭", color = if (notifications) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text(if (exact) "精确定时唤醒权限已具备" else "精确定时唤醒权限未具备", color = if (exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("默认仅振动；选择铃声后按系统媒体音量播放。正常倒计时通知静默。系统勿扰可能抑制声音或振动。", style = MaterialTheme.typography.bodySmall)
            val ringtoneLabel = when {
                choices.timerRingtoneUri.isNullOrEmpty() -> "无声音（仅振动）"
                else -> RingtoneManager.getRingtone(context, Uri.parse(choices.timerRingtoneUri))?.getTitle(context) ?: "已选择的系统铃声"
            }
            Text("到时铃声：$ringtoneLabel")
            OutlinedButton(onClick = {
                chooseTimerSound.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, choices.timerRingtoneUri?.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                })
            }) { Text("选择提醒铃声") }
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("通知授权与声音设置") }
            if (!exact && Build.VERSION.SDK_INT >= 31) Button(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("允许定时唤醒") }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("应用电池与后台设置") }
            val liveUpdates = remember(permissionRevision, permissionChanged) { TimerEngine.canRequestLiveUpdates(context) }
            val promoted = remember(permissionRevision, permissionChanged) { TimerEngine.isActiveNotificationPromoted(context) }
            Text(when {
                promoted -> "当前休息通知已由系统提升展示。"
                Build.VERSION.SDK_INT < 36 -> "当前Android版本不支持Live Updates；仍使用普通倒计时通知。"
                !notifications -> "通知权限未具备，无法请求Live Updates；请先允许通知。"
                !liveUpdates -> "系统未允许提升此通知；可在系统通知设置中检查相关权限。"
                else -> "系统支持且允许请求Live Updates，实际是否提升以系统通知状态为准。"
            }, style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT >= 36) {
                Text("OxygenOS 还需在系统设置中开启“流体云显示实时活动”；允许应用通知不代表此开关已开启。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("实时活动显示设置") }
            }
        }
        InfoCard("自有后端") {
            TextInput("后端地址", url, { url = it }, keyboardType = KeyboardType.Uri)
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("唯一 token") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("公网连接使用 HTTPS；离线训练不需要网络。")
            Row {
                Button(onClick = { viewModel.action { repository.saveConnection(BackendConnection(url, token.trim())) } }) { Text("保存连接") }
            }
            TextButton(onClick = { viewModel.action { repository.disconnect(); url = ""; token = "" } }) { Text("断开连接，保留手机资料") }
        }
        InfoCard("导入与导出") {
            Text("本机资料备份的健康内容仅含已下载缓存；后端完整备份包含完整健康资料，在线导出与恢复。")
            Button(onClick = { export.launch("本机资料-${java.time.LocalDate.now(java.time.ZoneOffset.ofHours(8))}.zip") }) { Text("导出本机资料 ZIP") }
            OutlinedButton(onClick = { exportComplete.launch("完整备份-${java.time.LocalDate.now(java.time.ZoneOffset.ofHours(8))}.zip") }) { Text("导出后端完整备份") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !importing) { Text("选择 ZIP 导入或恢复") }
            transferMessage?.let { Text(it) }
        }
        InfoCard("教学素材") {
            Text("训练动作图解与中文说明：Hevy 公开动作目录。拉伸图解：RepDB、WorkoutGuide。肌肉部位示意：react-body-highlighter。")
            Text("内置动作资料和图解可离线查看；SmartWorkout教学和视频需要连接后端及互联网。自建资料通过自己的后端同步。")
        }
    }
    if (importUri != null && !choosingCompleteRestore) {
        val uri = importUri!!
        AlertDialog(
            onDismissRequest = { if (!importing) importUri = null },
            title = { Text("选择导入方式") },
            text = { Text("旧训练包继续使用离线本机导入。包含完整健康资料的备份必须通过在线恢复；未连接后端时会在任何本机资料修改前拒绝。请选择恢复范围与合并/替换策略。") },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(enabled = !importing, onClick = { choosingCompleteRestore = true }) { Text("在线完整恢复…") }
                    TextButton(enabled = !importing, onClick = { importBackup(uri, complete = false, replace = false) }) { Text("合并本机训练包") }
                    TextButton(enabled = !importing, onClick = { importBackup(uri, complete = false, replace = true) }) { Text("明确替换本机训练包") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !importing, onClick = { importUri = null }) { Text("取消") }
                    }
                }
            }
        )
    }
    if (importUri != null && choosingCompleteRestore) {
        val uri = importUri!!
        AlertDialog(
            onDismissRequest = { if (!importing) choosingCompleteRestore = false },
            title = { Text("在线完整备份恢复") },
            text = { Text("此操作通过已配置后端恢复完整训练与健康资料。合并保留已有不同内容并报告冲突；明确替换会按服务端规则覆盖同名资料。离线时请求会在手机本机资料变更前拒绝。") },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(enabled = !importing, onClick = { importBackup(uri, complete = true, replace = false) }) { Text("合并恢复") }
                    TextButton(enabled = !importing, onClick = { importBackup(uri, complete = true, replace = true) }) { Text("明确替换恢复") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !importing, onClick = { choosingCompleteRestore = false }) { Text("返回") }
                    }
                }
            }
        )
    }
}

