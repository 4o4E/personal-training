package top.e404.training

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import top.e404.training.shared.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

private fun Document.key() = "${kind.name}/$id"
private data class Cached(val document: Document, val dirty: Boolean, val remote: Document? = null)
data class AppData(
    val ready: Boolean = false,
    val documents: Map<Kind, Map<String, Document>> = Kind.entries.associateWith { emptyMap() },
    val pending: Int = 0, val conflicts: List<Conflict> = emptyList(), val syncMessage: String = "尚未连接后端"
) {
    fun records(kind: Kind): List<Document> = documents.getValue(kind).values.filterNot { it.deleted }
    fun record(kind: Kind, id: String): Document? = documents.getValue(kind)[id]?.takeUnless { it.deleted }
}
class ApiProblem(val status: Int, message: String) : RuntimeException(message)

/** 界面只读取持久化成功的数据；网络等待不占用本地写入锁。 */
class TrainingRepository(
    private val context: Context, private val database: TrainingDatabase, val vault: ConnectionVault
) {
    private val dao = database.records()
    private val gate = Mutex()
    private val syncGate = Mutex()
    private val cache = linkedMapOf<String, Cached>()
    private val pending = mutableSetOf<String>()
    private val conflicting = mutableSetOf<String>()
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data
    private val _timer = MutableStateFlow<TimerState?>(null)
    val timer: StateFlow<TimerState?> = _timer
    private val mediaDirectory = File(context.filesDir, "media").also { check(it.exists() || it.mkdirs()) }
    private val bundledMedia by lazy { context.assets.list("media")!!.toSet() }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(wireJson) }
        install(HttpTimeout) { connectTimeoutMillis = 10000; requestTimeoutMillis = 30000 }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        gate.withLock {
            if (_data.value.ready) return@withLock
            val rows = dao.all()
            val existing = rows.map { "${it.kind}/${it.id}" }.toSet()
            val seed = context.assets.open("catalog.json").bufferedReader().use { wireJson.decodeFromString<List<Document>>(it.readText()) }
                .filterNot { it.key() in existing }
            database.withTransaction { dao.putAll(seed.map { it.local(false) }) }
            val values = rows.map { Cached(it.document(), it.dirty, it.conflict?.let { c -> wireJson.decodeFromString<Document>(c) }) } + seed.map { Cached(it, false) }
            install(values)
            _timer.value = dao.value("timer")?.let { wireJson.decodeFromString<TimerState>(it) }
            clearInactiveTimer()
            _data.update { it.copy(ready = true, syncMessage = if (vault.load().configured) "连接已配置，等待同步" else "尚未连接后端，可离线使用") }
        }
    }

    private fun install(values: List<Cached>) {
        val grouped = _data.value.documents.toMutableMap()
        values.groupBy { it.document.kind }.forEach { (kind, updates) ->
            val byId = grouped.getValue(kind).toMutableMap()
            updates.forEach { value ->
                val key = value.document.key()
                cache[key] = value; byId[value.document.id] = value.document
                if (value.dirty) pending.add(key) else pending.remove(key)
                if (value.remote != null) conflicting.add(key) else conflicting.remove(key)
            }
            grouped[kind] = byId
        }
        _data.update { it.copy(documents = grouped, pending = pending.size, conflicts = conflicting.map { key -> cache.getValue(key).let { c -> Conflict(c.document, c.remote!!) } }) }
    }

    /** 同步、导入或冲突选择可以结束训练；计时只属于进行中的训练，必须同时撤销系统提醒。 */
    private suspend fun clearInactiveTimer() {
        val timer = _timer.value ?: return
        val session = lookup(Kind.session, timer.sessionId)
        if (session != null && !session.deleted && session.data.getValue("status").jsonPrimitive.content == "active") return
        dao.removeValue("timer")
        _timer.value = null
        TimerEngine.cancel(context)
    }
    private fun Cached.row() = document.local(dirty, remote?.let { wireJson.encodeToString(it) })
    private fun lookup(kind: Kind, id: String) = cache["${kind.name}/$id"]?.document
    private fun list(kind: Kind) = _data.value.records(kind)

    suspend fun save(document: Document, original: Document) = withContext(Dispatchers.IO) {
        initialize()
        gate.withLock {
            validateDocument(document, ::list, ::lookup)
            val old = cache[document.key()]
            val acknowledgedOnly = old != null && old.document.data == original.data && old.document.deleted == original.deleted
            val base = if (acknowledgedOnly) old!!.document.revision else original.revision
            val remote = old?.remote ?: old?.document?.takeIf { it.revision != base }
            val entry = Cached(document.copy(revision = base), true, remote)
            dao.put(entry.row()); install(listOf(entry))
            clearInactiveTimer()
        }
        enqueueSync()
    }
    /** 一次确认对应完整一轮，事务中验证全部引用，避免只保存分化却缺少部分训练日。 */
    suspend fun saveRound(program: Program, plans: List<Plan>) = withContext(Dispatchers.IO) {
        initialize()
        gate.withLock {
            val documents = listOf(document(Kind.program, program.id, program)) + plans.map { document(Kind.plan, it.id, it) }
            val combined = cache.values.associate { it.document.key() to it.document } + documents.associateBy { it.key() }
            documents.forEach { value ->
                validateDocument(value, { kind -> combined.values.filter { it.kind == kind && !it.deleted } }, { kind, id -> combined["${kind.name}/$id"] })
            }
            val entries = documents.map { Cached(it, true) }
            database.withTransaction { dao.putAll(entries.map { it.row() }) }
            install(entries)
        }
        enqueueSync()
    }

    suspend fun remove(original: Document) = save(original.copy(deleted = true), original)

    /** 实际组与计时在同一事务内更新；读取和修改也在同一锁内，快速连续操作不会互相覆盖。 */
    private suspend fun persistSession(session: Session, timer: TimerState?) {
        val doc = document(Kind.session, session.id, session)
        validateDocument(doc, ::list, ::lookup)
        val old = cache[doc.key()]
        val changed = old?.document?.data != doc.data
        val timerChanged = _timer.value != timer
        val entry = Cached(doc.copy(revision = old?.document?.revision ?: 0), true, old?.remote)
        database.withTransaction {
            if (changed) dao.put(entry.row())
            if (timerChanged) {
                if (timer == null) dao.removeValue("timer") else dao.putValue(LocalValue("timer", wireJson.encodeToString(timer)))
            }
        }
        if (changed) install(listOf(entry))
        if (timerChanged) {
            _timer.value = timer
            if (timer == null) TimerEngine.cancel(context) else TimerEngine.schedule(context, timer)
        }
    }
    suspend fun beginSession(plan: Plan) = withContext(Dispatchers.IO) {
        initialize()
        gate.withLock {
            require(cache.values.none { it.document.kind == Kind.session && !it.document.deleted && it.document.value<Session>().status == "active" }) { "请先完成或取消当前训练" }
            require(plan.items.isNotEmpty()) { "请先添加训练动作" }
            validateDocument(document(Kind.plan, plan.id, plan), ::list, ::lookup)
            validatePlanEquipment(plan, list(Kind.equipment).map { it.value<Equipment>() }, ::lookup)
            val exercises = plan.items.map { lookup(Kind.exercise, it.exerciseId)!!.value<Exercise>() }.distinctBy { it.id }
            persistSession(Session(UUID.randomUUID().toString(), plan.name, plan, exercises, System.currentTimeMillis(), status = "active"), null)
        }
        enqueueSync()
    }
    suspend fun updateSession(id: String, update: (Session, TimerState?) -> Pair<Session, TimerState?>) = withContext(Dispatchers.IO) {
        gate.withLock {
            val current = requireNotNull(lookup(Kind.session, id)) { "训练不存在" }.value<Session>()
            require(current.status == "active") { "这次训练已经结束" }
            val (session, timer) = update(current, _timer.value)
            persistSession(session, timer)
        }
        enqueueSync()
    }
    suspend fun setTimer(timer: TimerState?) = withContext(Dispatchers.IO) {
        gate.withLock {
            if (timer == null) dao.removeValue("timer") else dao.putValue(LocalValue("timer", wireJson.encodeToString(timer)))
            _timer.value = timer
            if (timer == null) TimerEngine.cancel(context) else TimerEngine.schedule(context, timer)
        }
    }
    suspend fun restoreTimer() {
        initialize()
        gate.withLock {
            val current = _timer.value
            if (current != null && current.deadline > System.currentTimeMillis()) TimerEngine.schedule(context, current)
            else TimerEngine.cancel(context)
        }
    }
    suspend fun notifyTimer(timer: TimerState) {
        initialize()
        gate.withLock { if (_timer.value == timer) TimerEngine.notifyFinished(context, timer) }
    }

    suspend fun saveConnection(connection: BackendConnection) = withContext(Dispatchers.IO) {
        syncGate.withLock {
            vault.save(connection)
            gate.withLock { dao.putValue(LocalValue("cursor", "0")) }
            _data.update { it.copy(syncMessage = "连接已保存，等待同步") }
            enqueueSync()
        }
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        syncGate.withLock {
            vault.clear()
            WorkManager.getInstance(context).apply { cancelUniqueWork("training-sync"); cancelUniqueWork("training-periodic-sync") }
            _data.update { it.copy(syncMessage = "尚未连接后端，可离线使用") }
        }
    }
    fun enqueueSync() {
        if (!vault.load().configured) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
        val work = WorkManager.getInstance(context)
        work.enqueueUniqueWork("training-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        work.enqueueUniquePeriodicWork("training-periodic-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    fun syncFailure(message: String) { _data.update { it.copy(syncMessage = message) } }

    suspend fun sync() = withContext(Dispatchers.IO) {
        syncGate.withLock {
            initialize()
            val connection = vault.load()
            require(connection.configured) { "请先填写后端地址和 token" }
            _data.update { it.copy(syncMessage = "正在同步") }
            val request = gate.withLock {
                SyncRequest((dao.value("cursor") ?: "0").toLong(), pending.map { key -> cache.getValue(key).document.let { Mutation(it, it.revision) } })
            }
            request.mutations.filter { it.document.kind == Kind.media && !it.document.deleted }.forEach { mutation ->
                val media = mutation.document.value<Media>()
                val file = File(mediaDirectory, media.fileName)
                if (file.exists()) {
                    val response = client.put("${connection.url}/v1/files/${media.fileName}") {
                        bearerAuth(connection.token)
                        setBody(object : OutgoingContent.WriteChannelContent() {
                            override val contentType = ContentType.parse(media.mimeType)
                            override val contentLength = file.length()
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                file.inputStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    while (true) { val size = input.read(buffer); if (size < 0) break; channel.writeFully(buffer, 0, size) }
                                }
                            }
                        })
                    }
                    requireSuccess(response)
                }
            }
            val response = client.post("${connection.url}/v1/sync") { bearerAuth(connection.token); contentType(ContentType.Application.Json); setBody(request) }
            requireSuccess(response)
            val result = response.body<SyncResponse>()
            val sent = request.mutations.associateBy { it.document.key() }
            gate.withLock {
                val patch = linkedMapOf<String, Cached>()
                fun current(key: String) = patch[key] ?: cache[key]
                result.accepted.forEach { accepted ->
                    val key = accepted.key(); val local = current(key)!!; val submitted = sent.getValue(key).document
                    val unchanged = local.document.data == submitted.data && local.document.deleted == submitted.deleted
                    patch[key] = if (unchanged) Cached(accepted, false) else Cached(local.document.copy(revision = accepted.revision), true)
                }
                result.documents.forEach { remote ->
                    val key = remote.key(); val local = current(key)
                    patch[key] = when {
                        local == null || !local.dirty -> Cached(remote, false)
                        local.document.data == remote.data && local.document.deleted == remote.deleted -> Cached(remote, false)
                        local.document.revision != remote.revision -> local.copy(remote = remote)
                        else -> local
                    }
                }
                result.conflicts.forEach { conflict ->
                    val key = conflict.local.key(); val local = current(key)!!
                    patch[key] = local.copy(remote = conflict.remote)
                }
                database.withTransaction { dao.putAll(patch.values.map { it.row() }); dao.putValue(LocalValue("cursor", result.cursor.toString())) }
                install(patch.values.toList())
                clearInactiveTimer()
            }
            _data.update { it.copy(syncMessage = "数据已同步，正在检查素材") }
            // 修订号已确认后仍可能下载中断；每次同步都检查缺失素材，不依赖本次增量再次包含它。
            val missing = gate.withLock {
                val candidates = linkedMapOf<String, Document>()
                fun consider(doc: Document) {
                    if (doc.kind == Kind.media && !doc.deleted) {
                        val name = doc.data.getValue("fileName").jsonPrimitive.content
                        if (!hasMedia(name)) candidates[name] = doc
                    }
                }
                _data.value.documents.getValue(Kind.media).values.forEach(::consider)
                conflicting.forEach { key -> cache.getValue(key).remote?.let(::consider) }
                candidates.values.toList()
            }
            missing.forEach { doc ->
                val media = doc.value<Media>()
                    val file = File(mediaDirectory, media.fileName)
                    val temporary = File(mediaDirectory, "${media.fileName}.part")
                    try {
                        client.prepareGet("${connection.url}/v1/files/${media.fileName}") { bearerAuth(connection.token) }.execute { download ->
                            requireSuccess(download)
                            download.bodyAsChannel().toInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                        }
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
            }
            _data.update { it.copy(syncMessage = if (it.conflicts.isNotEmpty()) "有 ${it.conflicts.size} 项冲突待选择" else if (it.pending > 0) "有 ${it.pending} 项新改动等待同步" else "已同步") }
        }
    }
    private suspend fun requireSuccess(response: HttpResponse) {
        if (response.status.value in 200..299) return
        val text = when (response.status.value) {
            401 -> "连接 token 无效，请检查设置"
            else -> response.bodyAsText().let { body ->
                if (response.contentType()?.match(ContentType.Application.Json) == true) wireJson.decodeFromString<ApiError>(body).message else "后端请求失败（${response.status.value}）"
            }
        }
        throw ApiProblem(response.status.value, text)
    }

    suspend fun resolveConflict(kind: Kind, id: String, keepLocal: Boolean) = withContext(Dispatchers.IO) {
        gate.withLock {
            val local = cache.getValue("${kind.name}/$id")
            val remote = requireNotNull(local.remote) { "冲突已处理" }
            val entry = if (keepLocal) Cached(local.document.copy(revision = remote.revision), true) else Cached(remote, false)
            dao.put(entry.row()); install(listOf(entry))
            clearInactiveTimer()
        }
        enqueueSync()
    }
    fun hasMedia(name: String) = name in bundledMedia || File(mediaDirectory, name).exists()
    fun mediaModel(media: Media): Any = File(mediaDirectory, media.fileName).let { file ->
        if (file.exists()) file else "file:///android_asset/media/${media.fileName}"
    }
    private fun openMedia(name: String): InputStream? = if (!hasMedia(name)) null else File(mediaDirectory, name).let { if (it.exists()) it.inputStream() else context.assets.open("media/$name") }

    suspend fun addPhoto(uri: Uri, name: String): Media = withContext(Dispatchers.IO) {
        // 图库实拍可能依赖 EXIF 方向，复用图像加载器处理旋转和采样。
        val decoded = context.imageLoader.execute(ImageRequest.Builder(context).data(uri).size(1600).allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).build())
        require(decoded is SuccessResult) { "无法读取器械图片，请重新选择图片" }
        val bitmap = decoded.drawable.toBitmap()
        val id = UUID.randomUUID().toString()
        val item = Media(id, name, "$id.jpg", "image/jpeg", "用户图片")
        File(mediaDirectory, item.fileName).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) { "无法保存图片" } }
        val doc = document(Kind.media, id, item)
        save(doc, doc)
        item
    }

    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) {
        initialize()
        val backup = gate.withLock { Backup(documents = cache.values.map { it.document }, exportedAt = System.currentTimeMillis()) }
        writeBackup(backup, output, ::openMedia)
    }
    suspend fun import(input: InputStream, replace: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        val temporary = Files.createTempDirectory(context.cacheDir.toPath(), "training-import-")
        val created = mutableListOf<File>()
        var committed = false
        try {
            val backup = readBackup(input, temporary)
            gate.withLock {
                val incoming = backup.documents.associateBy { it.key() }
                val combined = (cache.values.map { it.document } + backup.documents).associateBy { it.key() }.values.filterNot { it.deleted }.groupBy { it.kind }
                backup.documents.forEach { doc ->
                    val old = cache[doc.key()]?.document
                    validateDocument(doc, { kind -> combined[kind] ?: emptyList() }) { kind, id ->
                        if (kind == doc.kind && id == doc.id) old else incoming["${kind.name}/$id"] ?: lookup(kind, id)
                    }
                    require(replace || old == null || old.data == doc.data && old.deleted == doc.deleted) { "已有不同内容：${doc.displayName()}。请明确选择替换后导入。" }
                }
                val docs = backup.documents.map { doc ->
                    if (doc.kind != Kind.media || doc.deleted) doc else {
                        val media = doc.value<Media>()
                        val staged = temporary.resolve(media.fileName)
                        require(Files.exists(staged)) { "数据包缺少素材：${media.name}" }
                        val existing = openMedia(media.fileName)
                        val same = existing?.use { old -> Files.newInputStream(staged).use { source -> mediaDigest(old).contentEquals(mediaDigest(source)) } } ?: false
                        if (same) doc else {
                            require(existing == null || replace) { "已有不同素材：${media.name}，请明确选择替换后导入" }
                            val newName = "import-${UUID.randomUUID()}.${media.fileName.substringAfterLast('.')}"
                            val destination = File(mediaDirectory, newName)
                            Files.copy(staged, destination.toPath()); created += destination
                            document(Kind.media, media.id, media.copy(fileName = newName))
                        }
                    }
                }
                val values = docs.mapNotNull { doc ->
                    val old = cache[doc.key()]
                    if (old != null && old.document.data == doc.data && old.document.deleted == doc.deleted) null
                    else Cached(doc.copy(revision = old?.document?.revision ?: 0), true, old?.remote)
                }
                database.withTransaction { dao.putAll(values.map { it.row() }); dao.putValue(LocalValue("cursor", "0")) }
                install(values); committed = true
                clearInactiveTimer()
            }
        } finally {
            if (!committed) created.forEach { it.delete() }
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
        enqueueSync()
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as TrainingApplication).repository
        return try { repository.sync(); Result.success() }
        catch (error: IOException) { repository.syncFailure("网络不可用，记录已保存在手机，等待同步"); Result.retry() }
        catch (error: ApiProblem) { repository.syncFailure(error.message!!); Result.failure() }
        catch (error: IllegalArgumentException) { repository.syncFailure(error.message!!); Result.failure() }
    }
}
