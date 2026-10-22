package top.e404.training

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import top.e404.training.shared.wireJson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
private data class AppRelease(val version: String, val versionCode: Int, val apk: String, val bytes: Long, val sha256: String, val notes: String)
private data class DownloadProgress(val received: Long, val total: Long, val ready: Boolean)

/** 更新只使用公开 Release；不访问训练资料，也不携带后端 token。系统下载独立于设置页面生命周期。 */
private class AppUpdater(private val context: Context) {
    private val preferences = context.getSharedPreferences("application-update", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val repository = "https://github.com/4o4E/personal-training"
    val downloadId: Long get() = preferences.getLong("download", -1)
    fun savedRelease(): AppRelease? = preferences.getString("release", null)?.let { wireJson.decodeFromString<AppRelease>(it) }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "PersonalTraining/${BuildConfig.VERSION_NAME}")
        try {
            check(connection.responseCode in 200..299) { "GitHub 请求失败：HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    fun checkRelease(): AppRelease {
        val release = wireJson.parseToJsonElement(get("https://api.github.com/repos/4o4E/personal-training/releases/latest")).jsonObject
        val asset = release.getValue("assets").jsonArray.single { it.jsonObject.getValue("name").jsonPrimitive.content == "update.json" }.jsonObject
        return wireJson.decodeFromString(get(asset.getValue("browser_download_url").jsonPrimitive.content))
    }

    private fun file(release: AppRelease) = File(context.getExternalFilesDir("updates"), "update-${release.versionCode}.apk")

    fun download(release: AppRelease) {
        if (downloadId != -1L) downloads.remove(downloadId)
        val destination = file(release)
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "无法创建更新目录" }
        if (destination.exists()) check(destination.delete()) { "无法清理旧安装包" }
        val request = DownloadManager.Request(Uri.parse("$repository/releases/download/v${release.version}/${release.apk}"))
            .setTitle("个人训练 ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
        val id = downloads.enqueue(request)
        check(preferences.edit().putLong("download", id).putString("release", wireJson.encodeToString(release)).commit()) { "无法保存更新任务" }
    }

    fun progress(): DownloadProgress = downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
        check(cursor.moveToFirst()) { "下载任务已不存在，请重新下载" }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        check(status != DownloadManager.STATUS_FAILED) { "下载失败，系统代码 ${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}，请重新下载" }
        DownloadProgress(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), status == DownloadManager.STATUS_SUCCESSFUL)
    }

    @Suppress("DEPRECATION")
    fun verify(release: AppRelease): File {
        val apk = file(release)
        check(apk.length() == release.bytes) { "安装包下载不完整，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包校验失败，请重新下载" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = checkNotNull(context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)) { "无法读取安装包" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(archive.packageName == context.packageName && archive.versionName == release.version && versionCode == release.versionCode.toLong() && versionCode > BuildConfig.VERSION_CODE) { "安装包身份或版本不符" }
        val newSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo!!.apkContentsSigners else archive.signatures!!
        val currentSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo!!.apkContentsSigners else installed.signatures!!
        check(newSigners.contentEquals(currentSigners)) { "安装包签名与当前应用不一致" }
        return apk
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
internal fun AppUpdateCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { AppUpdater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf(updater.savedRelease()) }
    var downloadRevision by remember { mutableIntStateOf(0) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var message by remember { mutableStateOf("点击检查正式版本") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRevision++ }

    LaunchedEffect(downloadRevision) {
        val saved = updater.savedRelease()
        if (saved != null && updater.downloadId != -1L) {
            release = saved
            downloading = true
            ready = false
            try {
                while (true) {
                    val current = withContext(Dispatchers.IO) { updater.progress() }
                    progress = current
                    if (current.ready) {
                        withContext(Dispatchers.IO) { updater.verify(saved) }
                        ready = true
                        message = "下载完成，点击安装更新"
                        break
                    }
                    message = "正在下载，可离开设置页面"
                    delay(750)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "更新下载失败" }
            finally { downloading = false }
        }
    }
    val canInstall = remember(permissionRevision) { context.packageManager.canRequestPackageInstalls() }
    InfoCard("应用更新") {
        Text("当前版本 ${BuildConfig.VERSION_NAME}")
        Text(message)
        Button(enabled = !busy && !downloading, onClick = {
            scope.launch {
                busy = true
                try {
                    val latest = withContext(Dispatchers.IO) { updater.checkRelease() }
                    ready = ready && release?.versionCode == latest.versionCode
                    release = latest.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                    message = if (release == null) "已是最新版本" else "发现新版本 ${latest.version}"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { message = "检查失败：${error.message ?: "无法连接 GitHub"}" }
                finally { busy = false }
            }
        }) { Text(if (busy) "正在检查" else "检查更新") }
        release?.let { candidate ->
            Text("新版本 ${candidate.version} · ${candidate.bytes / 1024 / 1024} MB")
            Text(candidate.notes)
            progress?.let { current ->
                if (downloading && current.total > 0) LinearProgressIndicator(progress = { (current.received.toFloat() / current.total).coerceIn(0f, 1f) })
            }
            if (!ready) OutlinedButton(enabled = !busy && !downloading, onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { updater.download(candidate) }
                        progress = null
                        downloadRevision++
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = "下载失败：${error.message}" }
                    finally { busy = false }
                }
            }) { Text("下载更新") }
            if (ready) Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        val apk = withContext(Dispatchers.IO) { updater.verify(candidate) }
                        if (!canInstall) {
                            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                            message = "授权后返回，点击安装更新"
                        } else updater.install(apk)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = "安装失败：${error.message}" }
                    finally { busy = false }
                }
            }) { Text("安装更新") }
        }
    }
}
