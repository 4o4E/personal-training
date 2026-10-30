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
import kotlinx.coroutines.CancellationException
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
@kotlinx.serialization.Serializable
data class ExerciseGuide(
    val sourceUrl: String,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val description: String,
    val tips: List<String>,
    val mistakes: List<String>,
    val instructions: List<String> = emptyList()
)
data class SmartWorkoutExerciseSource(val sourceId: String, val name: String)
@kotlinx.serialization.Serializable
private data class SmartWorkoutSelectionRequest(val type: String)
enum class SyncStatus { offline, syncing, synced, pending, conflict, error }
@kotlinx.serialization.Serializable
private data class SessionMigrationBase(val baseline: Document?, val sourceIds: Set<String>)
private data class SessionMigrationCandidate(val cached: Cached, val state: SessionMigrationBase)
enum class MediaSyncStatus { idle, checking, ready, error }
data class DocumentSyncState(val dirty: Boolean, val conflict: Boolean)
data class AppData(
    val ready: Boolean = false,
    val documents: Map<Kind, Map<String, Document>> = Kind.entries.associateWith { emptyMap() },
    val pending: Int = 0, val conflicts: List<Conflict> = emptyList(), val syncMessage: String = "尚未连接后端",
    val pendingArrangementMigration: Arrangement? = null, val arrangementCloudRead: Boolean = false,
    val lastSuccessfulSyncAt: Long? = null, val syncStatus: SyncStatus = SyncStatus.offline,
    val syncCompletionSequence: Long = 0,
    val documentSync: Map<String, DocumentSyncState> = emptyMap(),
    val mediaSyncStatus: MediaSyncStatus = MediaSyncStatus.idle,
    val health: HealthUiState = HealthUiState()
) {
    val arrangement: Arrangement? get() {
        val saved = if (!arrangementCloudRead) pendingArrangementMigration ?: record(Kind.arrangement, "current")?.value<Arrangement>()
            else record(Kind.arrangement, "current")?.value<Arrangement>() ?: pendingArrangementMigration
        val consumed = records(Kind.session).asSequence().map { it.value<Session>() }
            .filter { it.results.isNotEmpty() }
            .flatMap { session -> sequenceOf(session.arrangementOverrideId) + session.sourceRecords.asSequence().map { it.arrangementOverrideId } }
            .filterNotNull().toSet()
        return saved?.copy(nextOverrides = saved.nextOverrides.filterValues { it.id !in consumed })
    }
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
    private val healthGate = Mutex()
    private var arrangementCloudRead = false
    private var arrangementMigrationDirty = false
    private var startupSyncStarted = false
    private val cache = linkedMapOf<String, Cached>()
    private val pending = mutableSetOf<String>()
    private val conflicting = mutableSetOf<String>()
    private val _preparedTraining = MutableStateFlow<Session?>(null)
    val preparedTraining: StateFlow<Session?> = _preparedTraining
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

    suspend fun initialize(triggerStartupSync: Boolean = true) = withContext(Dispatchers.IO) {
        var startSync = false
        gate.withLock {
            if (!_data.value.ready) {
                val rows = dao.all()
                val originalByKey = rows.associateBy { "${it.kind}/${it.id}" }
                val rawDocuments = rows.map { it.document() }
                val migrated = normalizeDailySessionDocuments(rawDocuments)
                val migrationBases = linkedMapOf<String, SessionMigrationBase>()
                val migratedRows = migrated.map { doc ->
                    val old = originalByKey[doc.key()]
                    if (doc.kind != Kind.session) old ?: doc.local(false)
                    else {
                        val session = doc.takeUnless { it.deleted }?.value<Session>()
                        val sourceIds = session?.sourceRecords.orEmpty().map { it.sourceId }.toSet()
                        val relatedIds = if (doc.deleted) setOf(doc.id) else sourceIds + doc.id
                        val related = relatedIds.mapNotNull { originalByKey["session/$it"] }
                        val extraLegacy = !doc.deleted && related.any { it.id != doc.id && !it.deleted }
                        val changed = old?.document()?.let { it.data != doc.data || it.deleted != doc.deleted } ?: true
                        fun confirmedDocument(row: LocalDocument): Document? =
                            row.conflict?.let { wireJson.decodeFromString<Document>(it) }
                                ?: row.takeIf { !it.dirty && it.revision > 0 }?.document()
                        val remoteById = (listOfNotNull(old) + related.filter { it.id != doc.id })
                            .mapNotNull(::confirmedDocument)
                            .groupBy { it.key() }
                            .values.map { sameId ->
                                val highestRevision = sameId.maxOf { it.revision }
                                val highest = sameId.filter { it.revision == highestRevision }.distinct()
                                require(highest.size == 1) { "旧训练来源的远端冲突无法自动合并：${sameId.first().id}" }
                                highest.single()
                            }
                        val normalizedRemote = normalizeDailySessionDocuments(remoteById)
                            .firstOrNull { it.kind == Kind.session && it.id == doc.id }
                            ?: remoteById.firstOrNull { it.kind == Kind.session && it.id == doc.id }
                        val allSourcesConfirmed = sourceIds.isNotEmpty() && sourceIds.all { id ->
                            originalByKey["session/$id"]?.let { row ->
                                row.conflict != null || !row.dirty && row.revision > 0
                            } == true
                        }
                        val hasCanonicalRemote = remoteById.any { it.kind == Kind.session && it.id == doc.id }
                        val completeRemote = hasCanonicalRemote || allSourcesConfirmed
                        val hadConflict = old?.conflict != null || related.any { it.conflict != null }
                        if (old == null && session != null && related.any { it.id != doc.id && !it.deleted }) {
                            migrationBases[doc.id] = SessionMigrationBase(
                                baseline = normalizedRemote?.takeIf { completeRemote && !it.deleted },
                                sourceIds = sourceIds
                            )
                        }
                        doc.copy(revision = old?.revision ?: 0)
                            .local(
                                dirty = old?.dirty == true || related.any { it.dirty } || extraLegacy || changed,
                                conflict = if (doc.deleted) old?.conflict else
                                    normalizedRemote?.takeIf { hadConflict && completeRemote }?.let { wireJson.encodeToString(it) }
                            )
                    }
                }
                val timer = dao.value("timer")?.let { wireJson.decodeFromString<TimerState>(it) }
                val migratedTimer = timer?.let { oldTimer ->
                    migrated.asSequence().filter { it.kind == Kind.session && !it.deleted }
                        .map { it.value<Session>() }
                        .firstNotNullOfOrNull { session ->
                            val source = session.sourceRecords.singleOrNull { it.sourceId == oldTimer.sessionId }
                                ?: return@firstNotNullOfOrNull null
                            val itemId = if (oldTimer.itemId in source.itemIds) oldTimer.itemId else
                                "daily-item-${java.util.UUID.nameUUIDFromBytes("${source.sourceId}/${oldTimer.itemId}".toByteArray())}"
                            if (itemId !in source.itemIds || session.items.none { it.id == itemId }) null
                            else oldTimer.copy(sessionId = session.id, itemId = itemId)
                        }
                } ?: timer
                val existing = migratedRows.map { "${it.kind}/${it.id}" }.toSet()
                val seed = context.assets.open("catalog.json").bufferedReader().use { wireJson.decodeFromString<List<Document>>(it.readText()) }
                    .filterNot { it.key() in existing }
                database.withTransaction {
                    val changedRows = migratedRows.filter { replacement -> originalByKey["${replacement.kind}/${replacement.id}"] != replacement }
                    if (changedRows.isNotEmpty()) dao.putAll(changedRows)
                    if (seed.isNotEmpty()) dao.putAll(seed.map { it.local(false) })
                    migrationBases.forEach { (id, baseline) ->
                        dao.putValue(LocalValue("session-migration-base/$id", wireJson.encodeToString(baseline)))
                    }
                    if (timer != migratedTimer) {
                        if (migratedTimer == null) dao.removeValue("timer")
                        else dao.putValue(LocalValue("timer", wireJson.encodeToString(migratedTimer)))
                    }
                }
                val values = migratedRows.map { Cached(it.document(), it.dirty, it.conflict?.let { c -> wireJson.decodeFromString<Document>(c) }) } +
                    seed.map { Cached(it, false) }
                install(values)
                _timer.value = migratedTimer
                clearInactiveTimer()
                arrangementMigrationDirty = dao.value("arrangement-migration-dirty") == "true"
                arrangementCloudRead = dao.value("arrangement-cloud-read") == "true"
                val preferences = (context.applicationContext as TrainingApplication).preferences
                val savedMigration = dao.value("arrangement-migration")?.let { wireJson.decodeFromString<Arrangement>(it) }
                val (legacyProgram, legacyPlans) = preferences.legacySelection()
                val pendingMigration = savedMigration ?: if (_data.value.record(Kind.arrangement, "current") == null &&
                    !arrangementCloudRead && (legacyProgram != null || legacyPlans.isNotEmpty())) {
                    Arrangement(programId = legacyProgram, selectedPlans = legacyPlans)
                } else null
                if (savedMigration == null && pendingMigration != null) {
                    dao.putValue(LocalValue("arrangement-migration", wireJson.encodeToString(pendingMigration)))
                }
                val configured = vault.load().configured
                _data.update { it.copy(ready = true, pendingArrangementMigration = pendingMigration,
                    arrangementCloudRead = arrangementCloudRead,
                    lastSuccessfulSyncAt = dao.value("lastSuccessfulSyncAt")?.toLongOrNull(),
                    syncMessage = if (configured) "连接已配置，正在同步" else "尚未连接后端，可离线使用",
                    syncStatus = if (configured) SyncStatus.pending else SyncStatus.offline) }
            }
            if (triggerStartupSync && _data.value.ready && vault.load().configured && !startupSyncStarted) {
                startupSyncStarted = true
                startSync = true
            }
        }
        if (startSync) {
            try {
                sync()
            } catch (error: IOException) {
                syncFailure("网络不可用，记录已保存在手机，等待同步")
            } catch (error: ApiProblem) {
                syncFailure(error.message!!)
            }
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
        val documentsState = cache.mapValues { (_, value) -> DocumentSyncState(value.dirty, value.remote != null) }
        val status = when {
            conflicting.isNotEmpty() -> SyncStatus.conflict
            pending.isNotEmpty() -> SyncStatus.pending
            !vault.load().configured -> SyncStatus.offline
            else -> _data.value.syncStatus
        }
        _data.update { it.copy(documents = grouped, pending = pending.size,
            conflicts = conflicting.map { key -> cache.getValue(key).let { c -> Conflict(c.document, c.remote!!) } },
            documentSync = documentsState, syncStatus = status) }
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
    suspend fun saveDayHistory(originals: List<Document>, changes: List<Document>) = withContext(Dispatchers.IO) {
        initialize()
        if (changes.isEmpty()) return@withContext
        val entries = gate.withLock {
            val originalsByKey = originals.associateBy { it.key() }
            val changedDocuments = changes.filter { changed ->
                originalsByKey[changed.key()]?.let { it.data != changed.data || it.deleted != changed.deleted } ?: true
            }
            if (changedDocuments.isEmpty()) return@withLock emptyList()
            val entries = changedDocuments.map { changed ->
                val key = changed.key()
                val original = requireNotNull(originalsByKey[key]) { "日期修改缺少原记录" }
                require(!original.deleted) { "不能编辑已删除记录" }
                require(changed.kind == original.kind && changed.id == original.id) { "日期记录标识不能修改" }
                require(changed.kind == Kind.session || changed.kind == Kind.activity) { "日期中包含不可编辑的记录" }
                val persisted = requireNotNull(lookup(changed.kind, changed.id)) { "日期记录已不存在" }
                require(!persisted.deleted) { "日期记录已删除" }
                if (changed.kind == Kind.session) {
                    val oldSession = original.value<Session>()
                    require(oldSession.status != "active" && persisted.value<Session>().status != "active") {
                        "正在进行的训练请在训练页面修改"
                    }
                    if (changed.deleted) require(changed.data == original.data) { "删除训练不能同时改写其内容" }
                    else validateDayHistorySession(original, changed)
                } else {
                    val oldActivity = original.value<ActivityRecord>()
                    if (changed.deleted) {
                        require(changed.data == original.data) { "删除运动不能同时改写其内容" }
                    } else validateDayHistoryActivity(oldActivity, changed.value<ActivityRecord>())
                }
                val old = cache[key]
                val acknowledgedOnly = old != null &&
                    old.document.data == original.data && old.document.deleted == original.deleted
                val base = if (acknowledgedOnly) old!!.document.revision else original.revision
                Cached(changed.copy(revision = base), true, old?.remote ?: old?.document?.takeIf { it.revision != base })
            }
            entries.forEach { entry ->
                validateDocument(entry.document, ::list, ::lookup)
            }
            database.withTransaction { dao.putAll(entries.map { it.row() }) }
            install(entries)
            entries
        }
        if (entries.isNotEmpty()) enqueueSync()
    }
    private fun validateDayHistorySession(original: Document, changed: Document) {
        val before = original.value<Session>()
        val after = changed.value<Session>()
        require(before.status != "active" && after.status != "active") { "正在进行的训练请在训练页面修改" }
        require(after.copy(notes = before.notes, results = before.results) == before) { "历史编辑不能改写训练计划或会话信息" }
        require(after.results.size == before.results.size) { "历史编辑不能增删完成组" }
        val itemById = before.items.associateBy { it.id }
        val exerciseById = before.exercises.associateBy { it.id }
        before.results.zip(after.results).forEach { (old, next) ->
            require(old.itemId == next.itemId && old.setIndex == next.setIndex &&
                old.side == next.side && old.completedAt == next.completedAt &&
                old.segments.size == next.segments.size) { "历史编辑不能改变组身份、时间或分段数量" }
            val item = requireNotNull(itemById[old.itemId]) { "完成组动作不存在" }
            val exercise = requireNotNull(exerciseById[item.exerciseId]) { "完成组动作说明不存在" }
            val measurement = targetMeasurement(item.sets.getOrNull(old.setIndex), exercise.measurement)
            if (measurement == "seconds") {
                require(old.reps == next.reps && old.segments.isEmpty() && next.segments.isEmpty()) { "该动作按时长记录" }
            } else require(old.seconds == next.seconds) { "该动作按次数记录" }
            old.segments.zip(next.segments).forEach { (oldSegment, nextSegment) ->
                require(oldSegment.copy(reps = nextSegment.reps, load = nextSegment.load) == nextSegment) {
                    "历史编辑只能修改分段重量和次数"
                }
            }
            require(old.copy(reps = next.reps, seconds = next.seconds, load = next.load, segments = next.segments) == next) {
                "历史编辑只能修改实际读数"
            }
        }
    }
    private fun validateDayHistoryActivity(original: ActivityRecord, changed: ActivityRecord) {
        require(original.copy(
            name = changed.name, localDate = changed.localDate, durationSeconds = changed.durationSeconds,
            distanceMeters = changed.distanceMeters, notes = changed.notes,
            startedAt = changed.startedAt, endedAt = changed.endedAt
        ) == changed) { "历史编辑不能修改运动来源标识" }
    }
    /** 一次确认完整保存分化、计划和安排引用，避免云端产生失效选择。 */
    suspend fun saveRound(program: Program, plans: List<Plan>) = withContext(Dispatchers.IO) {
        initialize()
        gate.withLock {
            val documents = listOf(document(Kind.program, program.id, program)) + plans.map { document(Kind.plan, it.id, it) }
            val prior = cache.values.associate { it.document.key() to it.document }
            val combined = prior + documents.associateBy { it.key() }
            val previousArrangementDoc = combined["arrangement/current"]?.takeUnless { it.deleted }
            val previousArrangement = _data.value.arrangement ?: previousArrangementDoc?.value<Arrangement>() ?: Arrangement()
            val availablePrograms = combined.values.filter { it.kind == Kind.program && !it.deleted }.associate { it.id to it.value<Program>() }
            val availablePlans = combined.values.filter { it.kind == Kind.plan && !it.deleted }.associate { it.id to it.value<Plan>() }
            val selected = previousArrangement.selectedPlans.mapNotNull { (programId, bySlot) ->
                val targetProgram = availablePrograms[programId] ?: return@mapNotNull null
                val valid = bySlot.filter { (slotId, planId) ->
                    targetProgram.slots.any { it.id == slotId } && availablePlans[planId]?.let { it.programId == programId && it.slotId == slotId } == true
                }
                if (valid.isEmpty()) null else programId to valid
            }.toMap()
            val overrides = previousArrangement.nextOverrides.filter { (programId, override) ->
                availablePrograms[programId]?.slots?.any { it.id == override.slotId } == true
            }
            val arrangement = previousArrangement.copy(
                programId = previousArrangement.programId?.takeIf { it in availablePrograms },
                selectedPlans = selected,
                nextOverrides = overrides
            )
            val arrangementDocument = document(Kind.arrangement, "current", arrangement)
            val complete = combined + (arrangementDocument.key() to arrangementDocument)
            val all = if (arrangementCloudRead) documents + arrangementDocument else documents
            all.forEach { value ->
                validateDocument(value, { kind -> complete.values.filter { it.kind == kind && !it.deleted } }, { kind, id -> complete["${kind.name}/$id"] })
            }
            val migrationChanged = !arrangementCloudRead && arrangement != previousArrangement
            val entries = all.map { doc ->
                val old = cache[doc.key()]
                Cached(doc.copy(revision = old?.document?.revision ?: 0), true, old?.remote)
            }
            database.withTransaction {
                dao.putAll(entries.map { it.row() })
                if (migrationChanged) {
                    dao.putValue(LocalValue("arrangement-migration", wireJson.encodeToString(arrangement)))
                    dao.putValue(LocalValue("arrangement-migration-dirty", "true"))
                }
            }
            if (migrationChanged) {
                arrangementMigrationDirty = true
                _data.update { it.copy(pendingArrangementMigration = arrangement) }
            }
            install(entries)
        }
        enqueueSync()
    }
    private suspend fun saveArrangement(update: (Arrangement) -> Arrangement) {
        val old = _data.value.record(Kind.arrangement, "current")
        val current = _data.value.arrangement ?: Arrangement()
        val changed = update(current).copy(id = "current")
        if (!arrangementCloudRead) {
            gate.withLock {
                dao.putValue(LocalValue("arrangement-migration", wireJson.encodeToString(changed)))
                dao.putValue(LocalValue("arrangement-migration-dirty", "true"))
                arrangementMigrationDirty = true
                _data.update { it.copy(pendingArrangementMigration = changed) }
            }
            enqueueSync()
            return
        }
        val document = document(Kind.arrangement, "current", changed)
        save(document, old ?: document)
    }
    private suspend fun clearPendingArrangementMigration(clearLegacy: Boolean) {
        gate.withLock {
            dao.removeValue("arrangement-migration")
            dao.removeValue("arrangement-migration-dirty")
            arrangementMigrationDirty = false
            _data.update { it.copy(pendingArrangementMigration = null) }
        }
        if (clearLegacy) (context.applicationContext as TrainingApplication).preferences.clearLegacyChoices()
    }

    suspend fun selectProgram(programId: String?) {
        initialize()
        saveArrangement { it.copy(programId = programId) }
    }

    suspend fun selectPlan(programId: String, slotId: String, planId: String?) {
        initialize()
        saveArrangement { arrangement ->
            val plans = arrangement.selectedPlans[programId].orEmpty().toMutableMap()
            if (planId == null) plans.remove(slotId) else plans[slotId] = planId
            arrangement.copy(selectedPlans = arrangement.selectedPlans + (programId to plans))
        }
    }

    suspend fun selectNextOverride(programId: String, slotId: String?) {
        initialize()
        saveArrangement { arrangement ->
            val overrides = arrangement.nextOverrides.toMutableMap()
            if (slotId == null) overrides.remove(programId)
            else overrides[programId] = ArrangementOverride(UUID.randomUUID().toString(), slotId)
            arrangement.copy(nextOverrides = overrides)
        }
    }

    suspend fun removeTrainingDocument(original: Document) {
        if (original.kind !in setOf(Kind.program, Kind.plan)) {
            remove(original)
            return
        }
        initialize()
        if (!arrangementCloudRead) {
            remove(original)
            saveArrangement { arrangement ->
                if (original.kind == Kind.program) arrangement.copy(
                    programId = arrangement.programId.takeUnless { it == original.id },
                    selectedPlans = arrangement.selectedPlans - original.id,
                    nextOverrides = arrangement.nextOverrides - original.id
                ) else arrangement.copy(selectedPlans = arrangement.selectedPlans.mapValues { (_, plans) -> plans.filterValues { it != original.id } })
            }
            return
        }
        gate.withLock {
            val arrangementDoc = lookup(Kind.arrangement, "current")
            val arrangement = arrangementDoc?.value<Arrangement>() ?: Arrangement()
            val next = if (original.kind == Kind.program) arrangement.copy(
                programId = arrangement.programId.takeUnless { it == original.id },
                selectedPlans = arrangement.selectedPlans - original.id,
                nextOverrides = arrangement.nextOverrides - original.id
            ) else arrangement.copy(selectedPlans = arrangement.selectedPlans.mapValues { (_, plans) -> plans.filterValues { it != original.id } })
            val arrangementDocument = document(Kind.arrangement, "current", next)
            val deleted = original.copy(deleted = true)
            val combined = cache.values.associate { it.document.key() to it.document } +
                mapOf(deleted.key() to deleted, arrangementDocument.key() to arrangementDocument)
            listOf(deleted, arrangementDocument).forEach { doc ->
                validateDocument(
                    doc,
                    list = { kind -> combined.values.filter { it.kind == kind && !it.deleted } },
                    lookup = { kind, id -> combined["${kind.name}/$id"] }
                )
            }
            val entries = listOf(deleted, arrangementDocument).map { doc ->
                val old = cache[doc.key()]
                Cached(doc.copy(revision = old?.document?.revision ?: 0), true, old?.remote)
            }
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
    suspend fun prepareToday(date: java.time.LocalDate) = withContext(Dispatchers.IO) {
        initialize()
        var changedData = false
        gate.withLock {
            val before = list(Kind.session).map { it.value<Session>() }
            val archived = before.filter { session ->
                session.status == "active" && java.time.LocalDate.parse(session.localDate).isBefore(date)
            }.map { session ->
                val revised = session.copy(status = if (session.results.isEmpty()) "cancelled" else "completed")
                val doc = document(Kind.session, revised.id, revised)
                validateDocument(doc, ::list, ::lookup)
                val old = cache.getValue(doc.key())
                Cached(doc.copy(revision = old.document.revision), true, old.remote)
            }
            if (archived.isNotEmpty()) {
                database.withTransaction { dao.putAll(archived.map { it.row() }) }
                install(archived)
                clearInactiveTimer()
                changedData = true
            }
            val dailyId = dailySessionId(date)
            lookup(Kind.session, dailyId)?.takeUnless { it.deleted }?.let { existing ->
                val session = existing.value<Session>()
                _preparedTraining.value = if (session.results.isEmpty() && session.status != "active")
                    session.copy(status = "active", endedAt = null)
                else session
                return@withLock
            }
            val sessions = list(Kind.session).map { it.value<Session>() }
            val plans = list(Kind.plan).map { it.value<Plan>() }
            val arrangement = _data.value.arrangement
            val persistedArrangement = lookup(Kind.arrangement, "current")?.takeUnless { it.deleted }?.value<Arrangement>()
            val arrangementToSave = persistedArrangement ?: _data.value.pendingArrangementMigration ?: Arrangement()
            val program = arrangement?.programId?.let { id -> lookup(Kind.program, id)?.value<Program>() }
            val dated = plans.filter { it.date == date.toString() }.minByOrNull { it.id }
            val selectedPlans = program?.let { arrangement?.selectedPlans?.get(it.id).orEmpty() }.orEmpty()
            val resolvedBySlot = linkedMapOf<String, Plan>()
            val generatedTemplates = linkedMapOf<String, Plan>()
            val nextSelection = selectedPlans.toMutableMap()
            if (program != null) {
                for (candidateSlot in program.slots) {
                    val candidate = resolveTrainingPlan(program, candidateSlot, plans, sessions, selectedPlans, date)
                        ?: continue
                    if (candidate.date != null) continue
                    resolvedBySlot[candidateSlot.id] = candidate
                    val existingTemplate = lookup(Kind.plan, candidate.id)?.takeUnless { it.deleted }?.value<Plan>()
                    if (candidate.id.startsWith("template-") && existingTemplate != candidate)
                        generatedTemplates[candidate.id] = candidate
                    nextSelection[candidateSlot.id] = candidate.id
                }
            }
            val slot = program?.let { currentProgram ->
                val override = arrangement?.nextOverrides?.get(currentProgram.id)
                val explicitlyPlannedSlot = dated?.takeIf {
                    it.programId == currentProgram.id && it.slotId != null
                }?.slotId
                explicitlyPlannedSlot?.let { id -> currentProgram.slots.singleOrNull { it.id == id } }
                    ?: nextSlot(currentProgram, sessions, override)
            }
            val resolved = dated ?: slot?.let { resolvedBySlot[it.id] }
                ?: if (program != null && slot != null)
                    resolveTrainingPlan(program, slot, plans, sessions, selectedPlans, date)
                else null
            if (resolved == null) {
                _preparedTraining.value = null
                return@withLock
            }
            require(resolved.items.isNotEmpty()) { "当日训练计划尚未添加动作" }
            val plansToResolve = (resolvedBySlot.values + listOfNotNull(dated, resolved)).distinctBy { it.id }
            val requiredExerciseIds = plansToResolve.flatMap { plan -> plan.items.map { it.exerciseId } }.distinct()
            val historicalExercises = sessions.sortedWith(sessionComparator)
                .asSequence()
                .flatMap { it.exercises.asSequence() }
                .distinctBy { it.id }
                .associateBy { it.id }
            val missingExerciseIds = requiredExerciseIds.filter { id ->
                lookup(Kind.exercise, id)?.deleted != false
            }
            val missingSnapshots = missingExerciseIds.mapNotNull(historicalExercises::get)
            val unresolvedExerciseIds = missingExerciseIds - missingSnapshots.map { it.id }.toSet()
            require(unresolvedExerciseIds.isEmpty()) {
                "训练计划引用的动作资料不存在：${unresolvedExerciseIds.joinToString("、")}"
            }
            val exerciseIds = resolved.items.map { it.exerciseId }.distinct()
            val exercises = exerciseIds.map { id ->
                lookup(Kind.exercise, id)?.takeUnless { it.deleted }?.value<Exercise>()
                    ?: historicalExercises[id]
                    ?: throw IllegalArgumentException("训练计划引用的动作资料不存在：$id")
            }

            val updatedArrangement = if (program != null && nextSelection != selectedPlans) {
                arrangementToSave.copy(
                    programId = program.id,
                    selectedPlans = arrangementToSave.selectedPlans + (program.id to nextSelection)
                )
            } else null
            val exerciseDocuments = missingSnapshots.map { snapshot -> document(Kind.exercise, snapshot.id, snapshot) }
            val planDocuments = generatedTemplates.values.map { document(Kind.plan, it.id, it) }
            val arrangementDocument = updatedArrangement?.let { document(Kind.arrangement, "current", it) }
                ?.takeIf { arrangementCloudRead }
            val documentsToWrite = exerciseDocuments + planDocuments + listOfNotNull(arrangementDocument)
            if (documentsToWrite.isNotEmpty()) {
                val complete = cache.values.associate { it.document.key() to it.document } +
                    documentsToWrite.associateBy { it.key() }
                documentsToWrite.forEach { doc ->
                    validateDocument(
                        doc,
                        list = { kind -> complete.values.filter { it.kind == kind && !it.deleted } },
                        lookup = { kind, id -> complete["${kind.name}/$id"] }
                    )
                }
                val entries = documentsToWrite.map { doc ->
                    val old = cache[doc.key()]
                    Cached(doc.copy(revision = old?.document?.revision ?: 0), true, old?.remote)
                }
                database.withTransaction {
                    dao.putAll(entries.map { it.row() })
                    if (!arrangementCloudRead && updatedArrangement != null) {
                        dao.putValue(LocalValue("arrangement-migration", wireJson.encodeToString(updatedArrangement)))
                        dao.putValue(LocalValue("arrangement-migration-dirty", "true"))
                    }
                }
                install(entries)
                if (!arrangementCloudRead && updatedArrangement != null) {
                    arrangementMigrationDirty = true
                    _data.update { it.copy(pendingArrangementMigration = updatedArrangement) }
                }
                changedData = true
            } else if (!arrangementCloudRead && updatedArrangement != null) {
                database.withTransaction {
                    dao.putValue(LocalValue("arrangement-migration", wireJson.encodeToString(updatedArrangement)))
                    dao.putValue(LocalValue("arrangement-migration-dirty", "true"))
                }
                arrangementMigrationDirty = true
                _data.update { it.copy(pendingArrangementMigration = updatedArrangement) }
                changedData = true
            }
            val overrideId = program?.id?.let { programId ->
                arrangement?.nextOverrides?.get(programId)?.takeIf { it.slotId == resolved.slotId }?.id
            }
            _preparedTraining.value = Session(
                id = dailyId, name = resolved.name, snapshot = resolved, exercises = exercises,
                startedAt = null, endedAt = null, status = "active", items = resolved.items,
                arrangementOverrideId = overrideId, splitSlotName = slot?.name, localDate = date.toString()
            )
        }
        if (changedData) enqueueSync()
    }

    suspend fun resumeToday(date: java.time.LocalDate) = withContext(Dispatchers.IO) {
        prepareToday(date)
        val id = dailySessionId(date)
        var changed = false
        gate.withLock {
            val savedDocument = lookup(Kind.session, id)?.takeUnless { it.deleted }
            val saved = savedDocument?.value<Session>()
            val prepared = _preparedTraining.value?.takeIf { it.id == id }
            val session = saved ?: requireNotNull(prepared) { "当天训练尚未准备好" }
            if (session.results.isEmpty()) {
                _preparedTraining.value = if (session.status == "active") session else
                    session.copy(status = "active", endedAt = null)
                clearInactiveTimer()
            } else if (session.status != "active") {
                val resumed = session.copy(status = "active", endedAt = null)
                val timer = _timer.value?.takeUnless { it.sessionId == id }
                persistSession(resumed, timer)
                _preparedTraining.value = resumed
                changed = true
            } else {
                _preparedTraining.value = session
            }
        }
        if (changed) enqueueSync()
    }
    suspend fun updateSession(id: String, update: (Session, TimerState?) -> Pair<Session, TimerState?>) = withContext(Dispatchers.IO) {
        var completedOverride: Pair<String, String>? = null
        var persisted = false
        gate.withLock {
            val savedDocument = lookup(Kind.session, id)?.takeUnless { it.deleted }
            val saved = savedDocument?.value<Session>()
            val prepared = _preparedTraining.value?.takeIf { it.id == id }
            val restoringEmptySaved = saved != null && saved.results.isEmpty() && saved.status != "active" &&
                prepared?.status == "active"
            val current = when {
                restoringEmptySaved -> requireNotNull(prepared)
                saved != null -> saved
                else -> requireNotNull(prepared) { "训练不存在" }
            }
            val currentTimer = _timer.value
            val sessionTimer = currentTimer?.takeIf { it.sessionId == id }
            val operationAt = System.currentTimeMillis()
            val (requested, requestedTimer) = update(current, sessionTimer)
            val timer = if (sessionTimer != null) requestedTimer else requestedTimer ?: currentTimer
            val began = requested.results.size > current.results.size ||
                requestedTimer != null && requestedTimer != sessionTimer
            val started = if (began && requested.snapshot != null && requested.startedAt == null)
                requested.copy(startedAt = operationAt)
            else requested
            val next = if (saved != null && saved.status != "active" && began)
                started.copy(status = "active", endedAt = null)
            else started
            val persistExisting = saved != null &&
                (saved.status == "active" || saved.results.isNotEmpty() || sessionTimer != null)
            val shouldPersist = persistExisting || began
            val session = if (shouldPersist) {
                persistSession(next, timer)
                persisted = true
                next
            } else {
                next.copy(status = "active", endedAt = null)
            }
            _preparedTraining.value = session
            val programId = session.snapshot?.programId
            val overrideId = session.arrangementOverrideId
            if (session.results.isNotEmpty() && programId != null && overrideId != null)
                completedOverride = programId to overrideId
        }
        completedOverride?.let { (programId, id) ->
            saveArrangement { arrangement ->
                if (arrangement.nextOverrides[programId]?.id == id) arrangement.copy(nextOverrides = arrangement.nextOverrides - programId) else arrangement
            }
        }
        if (persisted) enqueueSync()
    }
    /** 已完成训练仅允许修改反馈或明确的一组实际读数，不暴露会话级任意变换。 */
    suspend fun updateCompletedNotes(sessionId: String, notes: String) = withContext(Dispatchers.IO) {
        initialize()
        gate.withLock {
            val original = requireNotNull(lookup(Kind.session, sessionId)) { "训练不存在" }
            require(!original.deleted) { "训练记录不存在" }
            val current = original.value<Session>()
            require(current.status == "completed") { "仅可修改已完成的训练" }
            saveCompletedSession(original, current.copy(notes = notes))
        }
        enqueueSync()
    }
    suspend fun updateCompletedResult(
        sessionId: String, itemId: String, setIndex: Int,
        reps: Int?, seconds: Int?, load: Load?, segments: List<SetSegment>? = null
    ) = withContext(Dispatchers.IO) {
        initialize()
        gate.withLock {
            val original = requireNotNull(lookup(Kind.session, sessionId)) { "训练不存在" }
            require(!original.deleted) { "训练记录不存在" }
            val current = original.value<Session>()
            require(current.status == "completed") { "仅可修改已完成的训练" }
            val resultIndex = current.results.indexOfFirst { it.itemId == itemId && it.setIndex == setIndex }
            require(resultIndex >= 0) { "完成组不存在" }
            val oldResult = current.results[resultIndex]
            val item = requireNotNull(current.items.singleOrNull { it.id == itemId }) { "完成组动作不存在" }
            val exercise = current.exercises.single { it.id == item.exerciseId }
            val measurement = targetMeasurement(item.sets.getOrNull(oldResult.setIndex), exercise.measurement)
            if (measurement == "seconds") {
                require(reps == oldResult.reps && segments == null) { "该动作按时长记录" }
            } else require(seconds == oldResult.seconds) { "该动作按次数记录" }
            val updatedResult = if (segments != null) {
                require(oldResult.segments.isNotEmpty()) { "该组不是分段记录" }
                require(segments.size == oldResult.segments.size) { "分段数量已变化，请重新打开编辑" }
                val updatedSegments = oldResult.segments.mapIndexed { index, originalSegment ->
                    originalSegment.copy(reps = segments[index].reps, load = segments[index].load)
                }
                val totalReps = updatedSegments.sumOf { it.reps }
                require(reps == totalReps) { "分段次数合计不一致" }
                oldResult.copy(reps = totalReps, segments = updatedSegments)
            } else oldResult.copy(reps = reps, seconds = seconds, load = load)
            val revised = current.copy(results = current.results.toMutableList().apply { set(resultIndex, updatedResult) })
            saveCompletedSession(original, revised)
        }
        enqueueSync()
    }
    private suspend fun saveCompletedSession(original: Document, revised: Session) {
        val doc = document(Kind.session, original.id, revised)
        validateDocument(doc, ::list, ::lookup)
        val old = cache.getValue(doc.key())
        val entry = Cached(doc.copy(revision = old.document.revision), true, old.remote)
        database.withTransaction { dao.put(entry.row()) }
        install(listOf(entry))
    }
    suspend fun appendSessionSet(sessionId: String, itemId: String) = withContext(Dispatchers.IO) {
        updateSession(sessionId) { session, timer ->
            require(session.status == "active") { "只有进行中的训练可以追加目标组" }
            val itemIndex = session.items.indexOfFirst { it.id == itemId }
            require(itemIndex >= 0) { "训练动作不存在" }
            val item = session.items[itemIndex]
            val itemResults = session.results.filter { it.itemId == itemId }
            val completed = itemResults.map { it.setIndex }.toSet()
            require(item.sets.indices.all { it in completed }) { "仍有未完成目标，不能追加训练组" }
            val last = itemResults.maxByOrNull { it.setIndex }
                ?: throw IllegalArgumentException("该动作没有实际完成组，不能追加训练组")
            require((0..last.setIndex).all { it in completed }) { "已完成组索引不连续，无法追加目标组" }
            val exercise = session.exercises.single { it.id == item.exerciseId }
            val defaultRest = if (exercise.type == "stretch") 15 else 90
            fun targetFrom(result: SetResult, restSeconds: Int) = SetTarget(
                reps = result.segments.lastOrNull()?.reps ?: result.reps,
                seconds = result.seconds,
                load = result.segments.lastOrNull()?.load ?: result.load,
                side = result.side,
                restSeconds = restSeconds
            )
            val targets = item.sets.toMutableList()
            while (targets.size <= last.setIndex) {
                val resultAtIndex = itemResults.singleOrNull { it.setIndex == targets.size }
                    ?: throw IllegalArgumentException("已完成组索引不连续，无法追加目标组")
                targets += targetFrom(resultAtIndex, targets.lastOrNull()?.restSeconds ?: defaultRest)
            }
            val target = targetFrom(last, targets.lastOrNull()?.restSeconds ?: defaultRest)
            checkTarget(target)
            targets += target
            val items = session.items.toMutableList().apply {
                set(itemIndex, item.copy(sets = targets))
            }
            session.copy(items = items) to timer
        }
    }
    suspend fun setTimer(timer: TimerState?) = withContext(Dispatchers.IO) {
        gate.withLock {
            if (timer == null) dao.removeValue("timer") else dao.putValue(LocalValue("timer", wireJson.encodeToString(timer)))
            _timer.value = timer
            if (timer == null) TimerEngine.cancel(context) else TimerEngine.schedule(context, timer)
        }
    }
    suspend fun restoreTimer() {
        initialize(triggerStartupSync = false)
        gate.withLock {
            val current = _timer.value
            when {
                current == null -> TimerEngine.clearActive(context)
                current.type != "rest" && current.notified && current.alertDismissed -> TimerEngine.finishExercise(context)
                else -> TimerEngine.schedule(context, current, clearFinished = false)
            }
        }
    }
    suspend fun notifyTimer(timer: TimerState) {
        initialize(triggerStartupSync = false)
        gate.withLock {
            val current = _timer.value ?: return@withLock
            val sameTimer = current.deadline == timer.deadline && current.sessionId == timer.sessionId &&
                current.itemId == timer.itemId && current.setIndex == timer.setIndex && current.type == timer.type
            if (!sameTimer || current.deadline > System.currentTimeMillis()) return@withLock
            TimerEngine.cancelAlarm(context)
            if (!current.notified) {
                val notified = current.copy(notified = true)
                dao.putValue(LocalValue("timer", wireJson.encodeToString(notified)))
                _timer.value = notified
                TimerEngine.notifyFinished(context, notified)
                TimerEngine.schedule(context, notified, clearFinished = false)
            }
        }
    }
    suspend fun dismissTimerAlert(expected: TimerState) = withContext(Dispatchers.IO) {
        initialize(triggerStartupSync = false)
        gate.withLock {
            val current = _timer.value ?: return@withLock
            val sameTimer = current.sessionId == expected.sessionId && current.itemId == expected.itemId &&
                current.setIndex == expected.setIndex && current.deadline == expected.deadline && current.type == expected.type
            if (!sameTimer || !current.notified || current.alertDismissed) return@withLock
            val dismissed = current.copy(alertDismissed = true)
            dao.putValue(LocalValue("timer", wireJson.encodeToString(dismissed)))
            _timer.value = dismissed
            context.getSystemService(android.app.NotificationManager::class.java).cancel(TimerEngine.FINISHED)
        }
    }

    suspend fun saveConnection(connection: BackendConnection) = withContext(Dispatchers.IO) {
        syncGate.withLock {
            vault.save(connection)
            gate.withLock { dao.putValue(LocalValue("cursor", "0")) }
            _data.update { it.copy(syncMessage = "连接已保存，等待同步", syncStatus = SyncStatus.pending) }
            enqueueSync()
        }
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        syncGate.withLock {
            vault.clear()
            WorkManager.getInstance(context).apply { cancelUniqueWork("training-sync"); cancelUniqueWork("training-periodic-sync") }
            _data.update { it.copy(syncMessage = "尚未连接后端，可离线使用", syncStatus = SyncStatus.offline) }
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
    fun syncFailure(message: String) { _data.update { it.copy(
        syncMessage = message,
        syncStatus = SyncStatus.error,
        mediaSyncStatus = if (it.mediaSyncStatus == MediaSyncStatus.checking) MediaSyncStatus.error else it.mediaSyncStatus,
        syncCompletionSequence = it.syncCompletionSequence + 1
    ) } }
    fun healthFailure(message: String) { _data.update { it.copy(health = it.health.copy(healthError = message)) } }
    fun collectionFailure(message: String) { _data.update { it.copy(health = it.health.copy(collectionError = message)) } }
    private suspend fun reconcileSessionMigrationBases(connection: BackendConnection) {
        val candidates = gate.withLock {
            cache.values.mapNotNull { cached ->
                val document = cached.document
                if (document.kind != Kind.session || document.revision != 0L || !cached.dirty) return@mapNotNull null
                val raw = dao.value("session-migration-base/${document.id}") ?: return@mapNotNull null
                SessionMigrationCandidate(cached, wireJson.decodeFromString<SessionMigrationBase>(raw))
            }
        }
        if (candidates.isEmpty()) return
        val response = client.get("${connection.url}/v1/documents?kind=session&includeDeleted=true") {
            bearerAuth(connection.token)
        }
        requireSuccess(response)
        val remoteSessions = response.body<List<Document>>().filter { it.kind == Kind.session }
        val remoteById = normalizeDailySessionDocuments(remoteSessions)
            .filter { it.kind == Kind.session }
            .associateBy { it.id }
        gate.withLock {
            val updates = linkedMapOf<String, Cached>()
            for (candidate in candidates) {
                val id = candidate.cached.document.id
                val key = "session/$id"
                val current = cache[key] ?: continue
                if (current.document.revision != 0L || !current.dirty ||
                    dao.value("session-migration-base/$id") == null
                ) continue
                val remote = remoteById[id]
                if (remote != null) {
                    updates[key] = when {
                        current.remote != null -> current.copy(remote = remote)
                        candidate.state.baseline != null &&
                            candidate.state.baseline.data == remote.data &&
                            candidate.state.baseline.deleted == remote.deleted ->
                            current.copy(document = current.document.copy(revision = remote.revision))
                        else -> current.copy(remote = remote)
                    }
                }
                for (sourceId in candidate.state.sourceIds) {
                    val sourceKey = "session/$sourceId"
                    val source = updates[sourceKey] ?: cache[sourceKey] ?: continue
                    val remoteSource = remoteById[sourceId] ?: continue
                    if (source.remote == null && source.document.deleted && remoteSource.deleted &&
                        source.document.data == remoteSource.data
                    ) {
                        updates[sourceKey] = source.copy(document = source.document.copy(revision = remoteSource.revision))
                    }
                }
            }
            val entries = updates.values.filter { it != cache[it.document.key()] }
            if (entries.isNotEmpty()) {
                database.withTransaction { dao.putAll(entries.map { it.row() }) }
                install(entries)
            }
        }
    }

    suspend fun sync(): Unit = withContext(Dispatchers.IO) {
        initialize(triggerStartupSync = false)
        syncGate.withLock {
            val connection = vault.load()
            require(connection.configured) { "请先填写后端地址和 token" }
            _data.update { it.copy(syncMessage = "正在同步", syncStatus = SyncStatus.syncing, mediaSyncStatus = MediaSyncStatus.idle) }
            checkCapabilities(connection)
            reconcileSessionMigrationBases(connection)
            val cursor = if (!arrangementCloudRead) "0" else dao.value("cursor") ?: "0"
            val restoreIntentByKey = mutableMapOf<String, String>()
            val request = gate.withLock {
                SyncRequest(cursor.toLong(), pending.mapNotNull { key ->
                    val cached = cache.getValue(key)
                    val value = cached.document
                    if (cached.remote != null || (!arrangementCloudRead && value.kind == Kind.arrangement)) null
                    else {
                        val restoreIntent = if (value.kind == Kind.session && !value.deleted)
                            dao.value("session-restore-intent/${value.id}")
                        else null
                        if (restoreIntent != null) {
                            restoreIntentByKey[key] = restoreIntent
                            Mutation(value, value.revision, restoreSession = true)
                        } else Mutation(value, value.revision)
                    }
                })
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
                val acceptedRestoreKeys = mutableSetOf<String>()
                result.accepted.forEach { accepted ->
                    val key = accepted.key(); val local = current(key)!!; val submitted = sent.getValue(key).document
                    if (key in restoreIntentByKey) acceptedRestoreKeys += key
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
                val syncedRestoreIntents = restoreIntentByKey.filter { (key, _) ->
                    key in acceptedRestoreKeys ||
                        patch[key]?.let { !it.dirty && it.remote == null } == true
                }
                val syncedAt = System.currentTimeMillis()
                database.withTransaction {
                    dao.putAll(patch.values.map { it.row() })
                    result.accepted.filter { it.kind == Kind.session }.forEach {
                        dao.removeValue("session-migration-base/${it.id}")
                    }
                    syncedRestoreIntents.forEach { (key, sentIntent) ->
                        val id = key.substringAfter('/')
                        val intentKey = "session-restore-intent/$id"
                        if (dao.value(intentKey) == sentIntent) dao.removeValue(intentKey)
                    }
                    dao.putValue(LocalValue("cursor", result.cursor.toString()))
                    dao.putValue(LocalValue("lastSuccessfulSyncAt", syncedAt.toString()))
                    if (request.cursor == 0L) dao.putValue(LocalValue("arrangement-cloud-read", "true"))
                }
                install(patch.values.toList())
                if (request.cursor == 0L) {
                    arrangementCloudRead = true
                    _data.update { it.copy(arrangementCloudRead = true) }
                }
                _data.update { it.copy(lastSuccessfulSyncAt = syncedAt) }
                clearInactiveTimer()
            }
            val remoteArrangement = (result.documents + result.accepted)
                .firstOrNull { it.kind == Kind.arrangement && it.id == "current" }
            if (remoteArrangement != null) {
                val migration = _data.value.pendingArrangementMigration
                if (arrangementMigrationDirty && migration != null) {
                    val local = document(Kind.arrangement, "current", migration).copy(revision = remoteArrangement.revision)
                    val conflict = Cached(local, true, remoteArrangement)
                    gate.withLock {
                        dao.put(conflict.row())
                        install(listOf(conflict))
                    }
                }
                clearPendingArrangementMigration(clearLegacy = true)
            } else if (request.cursor == 0L && _data.value.record(Kind.arrangement, "current") == null) {
                val migration = _data.value.pendingArrangementMigration
                if (migration != null) saveArrangement { migration }
            }
            _data.update { it.copy(syncMessage = "数据已同步，正在检查素材", mediaSyncStatus = MediaSyncStatus.checking) }
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
            val status = when {
                _data.value.conflicts.isNotEmpty() -> SyncStatus.conflict
                _data.value.pending > 0 -> SyncStatus.pending
                else -> SyncStatus.synced
            }
            _data.update { it.copy(
                syncMessage = if (status == SyncStatus.conflict) "有 ${it.conflicts.size} 项冲突待选择" else if (status == SyncStatus.pending) "有 ${it.pending} 项新改动等待同步" else "已同步",
                syncStatus = status, mediaSyncStatus = MediaSyncStatus.ready,
                syncCompletionSequence = it.syncCompletionSequence + 1
            ) }
        }
    }
    private suspend fun loadHealthCache(date: java.time.LocalDate) {
        val text = date.toString()
        val dayRow = dao.healthDay(text)
        val day = dayRow?.summary()?.copy(revision = dayRow.revision)
        val stateRows = dao.healthDetailStates(text).associateBy { HealthDataset.valueOf(it.dataset) }
        val states = HealthDataset.entries.associateWith { dataset ->
            stateRows[dataset]?.uiState(dayRow?.revision)
                ?: HealthDetailCacheState(HealthDetailStatus.neverRequested)
        }
        val records = HealthDataset.entries.associateWith { dataset ->
            stateRows[dataset]?.let { state ->
                dao.healthRecords(text, dataset.name, state.revision).map { it.record() }
            }.orEmpty()
        }
        val cachedDays = dao.healthDays(date.minusDays(45).toString(), text).map { row ->
            row.summary().copy(revision = row.revision)
        }
        val cachedDates = cachedDays.mapTo(hashSetOf()) { it.localDate }
        _data.update { current ->
            if (current.health.selectedDate != date) current
            else {
                val days = (current.health.days.filterNot { it.localDate in cachedDates } + cachedDays)
                    .sortedByDescending { it.localDate }
                current.copy(health = current.health.copy(
                    days = days,
                    summary = day,
                    recordsByDataset = records,
                    detailStateByDataset = states
                ))
            }
        }
    }

    suspend fun selectHealthDate(date: java.time.LocalDate) {
        initialize(triggerStartupSync = false)
        _data.update { current -> current.copy(health = current.health.copy(selectedDate = date)) }
        loadHealthCache(date)
    }

    suspend fun refreshHealthDays(from: java.time.LocalDate, to: java.time.LocalDate) = withContext(Dispatchers.IO) {
        require(!to.isBefore(from)) { "健康日期范围无效" }
        initialize(triggerStartupSync = false)
        val cachedDays = dao.healthDays(from.toString(), to.toString()).map { row ->
            row.summary().copy(revision = row.revision)
        }
        _data.update { current ->
            val cachedDates = cachedDays.mapTo(hashSetOf()) { it.localDate }
            current.copy(health = current.health.copy(days =
                (current.health.days.filterNot { it.localDate in cachedDates } + cachedDays)
                    .sortedByDescending { it.localDate }
            ))
        }
        val connection = vault.load()
        if (!connection.configured) {
            _data.update { it.copy(health = it.health.copy(healthError = "未连接后端；显示的日期仅来自本机缓存")) }
            return@withContext
        }
        try {
            val response = client.get("${connection.url}/v1/health/days") {
                bearerAuth(connection.token)
                url { parameters.append("from", from.toString()); parameters.append("to", to.toString()) }
            }
            requireSuccess(response)
            val result = response.body<HealthDaysResponse>()
            val cachedAt = System.currentTimeMillis()
            val byDate = result.days.associateBy { it.localDate }
            database.withTransaction {
                dao.putHealthDays(result.days.map { day ->
                    LocalHealthDay(day.localDate, day.payload(), result.revisionByDate[day.localDate] ?: day.revision, cachedAt)
                })
            }
            val days = dao.healthDays(from.toString(), to.toString()).map { row ->
                row.summary().copy(revision = row.revision)
            }
            _data.update { current ->
                val merged = (current.health.days.filterNot { it.localDate in byDate } + days)
                    .sortedByDescending { it.localDate }
                current.copy(health = current.health.copy(days = merged, healthError = null))
            }
            _data.value.health.selectedDate?.takeIf { it >= from && it <= to }?.let { loadHealthCache(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _data.update { current -> current.copy(health = current.health.copy(healthError = error.message ?: "读取健康日期失败")) }
            throw error
        }
    }

    suspend fun loadExerciseGuide(exerciseId: String): ExerciseGuide? = withContext(Dispatchers.IO) {
        val connection = vault.load()
        require(connection.configured) { "尚未连接后端" }
        val response = client.get("${connection.url}/v1/exercise-guide/${exerciseId.encodeURLPathPart()}") {
            bearerAuth(connection.token)
        }
        if (response.status.value == 404) return@withContext null
        requireSuccess(response)
        response.body<ExerciseGuide>()
    }
    suspend fun loadSmartWorkoutExerciseGuide(sourceId: String): ExerciseGuide? = withContext(Dispatchers.IO) {
        val connection = vault.load()
        require(connection.configured) { "尚未连接后端" }
        val response = client.get("${connection.url}/v1/smartworkout/exercises/${sourceId.encodeURLPathPart()}/guide") {
            bearerAuth(connection.token)
        }
        if (response.status.value == 404) return@withContext null
        requireSuccess(response)
        response.body<ExerciseGuide>()
    }


    suspend fun loadSmartWorkoutExercises(): List<SmartWorkoutExerciseSource> = withContext(Dispatchers.IO) {
        val connection = vault.load()
        require(connection.configured) { "尚未连接后端" }
        val response = client.get("${connection.url}/v1/smartworkout/exercises") {
            bearerAuth(connection.token)
        }
        requireSuccess(response)
        response.body<List<kotlinx.serialization.json.JsonObject>>().map { source ->
            SmartWorkoutExerciseSource(
                source.getValue("sourceId").jsonPrimitive.content,
                source.getValue("name").jsonPrimitive.content
            )
        }
    }

    suspend fun selectSmartWorkoutExercise(sourceId: String, type: String): Exercise = withContext(Dispatchers.IO) {
        require(type == "strength" || type == "stretch") { "动作类型无效" }
        initialize(triggerStartupSync = false)
        val connection = vault.load()
        require(connection.configured) { "尚未连接后端" }
        val response = client.post("${connection.url}/v1/smartworkout/exercises/${sourceId.encodeURLPathPart()}/select") {
            bearerAuth(connection.token)
            contentType(ContentType.Application.Json)
            setBody(SmartWorkoutSelectionRequest(type))
        }
        requireSuccess(response)
        val selected = response.body<Document>()
        require(selected.kind == Kind.exercise && !selected.deleted) { "后端返回的来源动作文档无效" }
        val selectedExercise = selected.value<Exercise>()
        require(selectedExercise.id == selected.id && selectedExercise.type == type) { "后端返回的动作类型或标识不匹配" }
        var resolved = selected
        gate.withLock {
            val existing = cache[selected.key()]
            if (existing?.dirty == true) {
                check(!existing.document.deleted) { "该动作在本机有待同步删除，无法直接选择" }
                resolved = existing.document
            } else if (existing == null || existing.document.revision < selected.revision ||
                existing.document.revision == selected.revision && existing.document.data != selected.data ||
                existing.document.deleted
            ) {
                val imported = Cached(selected, false)
                dao.put(imported.row())
                install(listOf(imported))
            } else {
                resolved = existing.document
            }
        }
        val resolvedExercise = resolved.value<Exercise>()
        require(resolvedExercise.type == type) { "本机该动作资料的类型与当前选择上下文不一致" }
        resolvedExercise
    }


    suspend fun loadHealthDate(date: java.time.LocalDate) = withContext(Dispatchers.IO) {
        initialize(triggerStartupSync = false)
        loadHealthCache(date)
        refreshHealthDays(date, date)
        val connection = vault.load()
        require(connection.configured) { "未连接后端；仅能查看已下载的健康内容" }
        _data.update { it.copy(health = it.health.copy(healthError = null)) }
        healthGate.withLock {
            for (dataset in HealthDataset.entries) {
                val prior = dao.healthDetailState(date.toString(), dataset.name)
                _data.update { current ->
                    val states = current.health.detailStateByDataset.toMutableMap()
                    states[dataset] = HealthDetailCacheState(
                        HealthDetailStatus.loading, prior?.revision, prior?.cachedAt, prior?.error
                    )
                    current.copy(health = current.health.copy(detailStateByDataset = states))
                }
                try {
                    var restarted = true
                    var collected = linkedMapOf<String, HealthRecord>()
                    var revision = 0L
                    while (restarted) {
                        restarted = false
                        collected = linkedMapOf()
                        var cursor: String? = null
                        var pageRevision: Long? = null
                        do {
                            val response = client.get("${connection.url}/v1/health/records") {
                                bearerAuth(connection.token)
                                url {
                                    parameters.append("date", date.toString())
                                    parameters.append("dataset", dataset.name)
                                    cursor?.let { parameters.append("cursor", it) }
                                }
                            }
                            requireSuccess(response)
                            val page = response.body<HealthRecordsPage>()
                            require(page.date == date.toString() && page.dataset == dataset) { "健康分页返回了不匹配的日期或数据集" }
                            if (pageRevision != null && page.revision != pageRevision) {
                                restarted = true
                                break
                            }
                            pageRevision = page.revision
                            page.records.forEach { record ->
                                require(record.localDate == date.toString()) { "健康分页包含其他日期" }
                                collected[record.sourceId] = record
                            }
                            cursor = page.nextCursor
                        } while (cursor != null)
                        if (!restarted) revision = pageRevision ?: dao.healthDay(date.toString())?.revision ?: 0L
                    }
                    val completedAt = System.currentTimeMillis()
                    val newRows = collected.values.map { it.cacheRow(dataset, revision, completedAt) }
                    database.withTransaction {
                        dao.clearHealthRecords(date.toString(), dataset.name)
                        if (newRows.isNotEmpty()) dao.putHealthRecords(newRows)
                        dao.putHealthDetailState(LocalHealthDetailState(date.toString(), dataset.name, revision, true, completedAt))
                    }
                    loadHealthCache(date)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val cached = dao.healthDetailState(date.toString(), dataset.name)
                    val now = System.currentTimeMillis()
                    dao.putHealthDetailState(LocalHealthDetailState(
                        date.toString(), dataset.name, cached?.revision ?: dao.healthDay(date.toString())?.revision ?: 0L,
                        cached?.complete ?: false, cached?.cachedAt ?: now, error.message ?: "健康详情读取失败"
                    ))
                    loadHealthCache(date)
                }
            }
        }
        val detailErrors = dao.healthDetailStates(date.toString()).mapNotNull { it.error }.distinct()
        _data.update { it.copy(health = it.health.copy(healthError = detailErrors.takeIf { it.isNotEmpty() }?.joinToString("；"))) }
        refreshHealthStatus()
    }

    suspend fun refreshHealthStatus() = withContext(Dispatchers.IO) {
        try {
            val connection = vault.load()
            require(connection.configured) { "请先连接后端" }
            val response = client.get("${connection.url}/v1/health/status") { bearerAuth(connection.token) }
            requireSuccess(response)
            val status = response.body<HealthStatus>()
            _data.update { it.copy(health = it.health.copy(healthStatus = status, collectionError = null)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            collectionFailure(error.message ?: "读取服务器采集状态失败")
        }
    }

    suspend fun requestHealthCollection() = withContext(Dispatchers.IO) {
        val connection = vault.load()
        _data.update { it.copy(health = it.health.copy(collectionRequesting = true, collectionError = null)) }
        try {
            require(connection.configured) { "请先连接后端" }
            checkCapabilities(connection)
            val response = client.post("${connection.url}/v1/health/collection/request") { bearerAuth(connection.token) }
            requireSuccess(response)
            val result = response.body<HealthCollectionRequestResult>()
            _data.update { it.copy(health = it.health.copy(
                collectionRequesting = false,
                collectionError = null,
                healthStatus = it.health.healthStatus?.copy(collection = result.state)
                    ?: HealthStatus(emptyList(), emptyMap(), result.state)
            )) }
        } catch (error: CancellationException) {
            _data.update { it.copy(health = it.health.copy(collectionRequesting = false)) }
            throw error
        } catch (error: Exception) {
            _data.update { it.copy(health = it.health.copy(
                collectionRequesting = false, collectionError = error.message ?: "请求服务器采集失败"
            )) }
            throw error
        }
    }
    suspend fun retryAll() = withContext(Dispatchers.IO) {
        try {
            sync()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            syncFailure(error.message ?: "训练资料或素材同步失败")
        }
        _data.update { it.copy(health = it.health.copy(collectionError = null, healthError = null)) }
        val selectedDate = _data.value.health.selectedDate
        try {
            if (selectedDate != null) loadHealthDate(selectedDate) else refreshHealthStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _data.update { it.copy(health = it.health.copy(healthError = error.message ?: "健康读取失败")) }
        }
        if (_data.value.health.healthStatus?.collection?.stage == "failed") {
            try {
                requestHealthCollection()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _data.update { it.copy(health = it.health.copy(collectionError = error.message ?: "服务器采集重试失败")) }
            }
        }
    }


    private suspend fun checkCapabilities(connection: BackendConnection) {
        val response = client.get("${connection.url}/v1/capabilities") { bearerAuth(connection.token) }
        if (response.status.value == 404) throw ApiProblem(404, "后端版本过旧，需先升级到支持业务/备份格式6及健康接口1的版本；手机本机资料与待上传训练已保留")
        requireSuccess(response)
        val capabilities = response.body<HealthCapabilities>()
        require(capabilities.formatVersion == 6 && capabilities.backupFormatVersion == 6 &&
            capabilities.healthApiVersion == 1) {
            "后端契约不兼容（业务格式${capabilities.formatVersion}、备份格式${capabilities.backupFormatVersion}、健康接口${capabilities.healthApiVersion}）；请先升级后端，手机资料保持不变"
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
            database.withTransaction {
                dao.put(entry.row())
                if (kind == Kind.session) {
                    dao.removeValue("session-migration-base/$id")
                    if (!keepLocal) dao.removeValue("session-restore-intent/$id")
                }
            }
            install(listOf(entry))
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
        val backupParts = gate.withLock { cache.values.map { it.document } }
        val exportable = dao.allHealthDetailStates()
        val allowed = exportable.mapTo(hashSetOf()) { "${it.localDate}/${it.dataset}/${it.revision}" }
        val records = dao.allHealthRecords().filter { "${it.localDate}/${it.dataset}/${it.revision}" in allowed }
        val dates = exportable.map { it.localDate }.distinct().sorted()
        val datasets = exportable.map { HealthDataset.valueOf(it.dataset) }.distinct().sortedBy { it.name }
        val backup = Backup(
            documents = backupParts,
            exportedAt = System.currentTimeMillis(),
            healthManifest = HealthBackupManifest("cache", dates, datasets, records.size.toLong())
        )
        writeBackup(backup, output, ::openMedia, records.asSequence().map { row ->
            HealthBackupEntry(HealthDataset.valueOf(row.dataset), row.record())
        })
    }
    suspend fun exportCompleteBackup(output: OutputStream) = withContext(Dispatchers.IO) {
        val connection = vault.load()
        require(connection.configured) { "完整健康备份需要连接后端" }
        checkCapabilities(connection)
        client.prepareGet("${connection.url}/v1/export") { bearerAuth(connection.token) }.execute { response ->
            requireSuccess(response)
            response.bodyAsChannel().toInputStream().use { source -> source.copyTo(output) }
        }
    }

    suspend fun restoreCompleteBackup(input: InputStream, replace: Boolean) = withContext(Dispatchers.IO) {
        val connection = vault.load()
        require(connection.configured) { "完整健康备份必须连接配套后端在线恢复；本机资料保持不变" }
        checkCapabilities(connection)
        val response = client.post("${connection.url}/v1/import") {
            bearerAuth(connection.token)
            url { parameters.append("replace", replace.toString()) }
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.parse("application/zip")
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    input.use { source ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val size = source.read(buffer)
                            if (size < 0) break
                            channel.writeFully(buffer, 0, size)
                        }
                    }
                }
            })
        }
        requireSuccess(response)
        try {
            sync()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            syncFailure(error.message ?: "备份已恢复到后端，但训练资料尚未重新读取")
        }
        val selectedDate = _data.value.health.selectedDate
        try {
            if (selectedDate != null) loadHealthDate(selectedDate) else refreshHealthStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _data.update { it.copy(health = it.health.copy(healthError = error.message ?: "备份已恢复到后端，但健康缓存尚未刷新")) }
        }
    }
    suspend fun import(input: InputStream, replace: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        val temporary = Files.createTempDirectory(context.cacheDir.toPath(), "training-import-")
        val created = mutableListOf<File>()
        var committed = false
        try {
            val healthStage = temporary.resolve("health-cache.jsonl")
            val backup = Files.newBufferedWriter(healthStage, Charsets.UTF_8).use { writer ->
                readBackup(input, temporary) { entry ->
                    writer.write(wireJson.encodeToString(entry))
                    writer.newLine()
                }
            }
            require(backup.healthManifest?.scope != "complete") {
                "完整健康备份必须连接配套后端并在线恢复；本机训练资料尚未修改"
            }
            gate.withLock {
                val incomingSessions = backup.documents.filter { it.kind == Kind.session }
                val mergedSessions = mergeImportedSessions(
                    existing = cache.values.map { it.document }.filter { it.kind == Kind.session },
                    incoming = incomingSessions,
                    replace = replace
                )
                val docsToImport = backup.documents.filterNot { it.kind == Kind.session } + mergedSessions
                val replacementSessionIds = if (replace && backup.formatVersion >= 5)
                    incomingSessions.filterNot { it.deleted }.mapTo(hashSetOf()) { it.id }
                else emptySet()
                val replaceSourceIdsByDate = if (replace) incomingSessions.filterNot { it.deleted }
                    .map { migrateSessionDocument(it).value<Session>() }
                    .groupBy { it.localDate }
                    .mapValues { (_, sessions) -> sessions.flatMapTo(linkedSetOf()) { it.sourceRecords.map(SessionSource::sourceId) } }
                else emptyMap()
                val incoming = docsToImport.associateBy { it.key() }
                val combined = (cache.values.map { it.document } + docsToImport).associateBy { it.key() }
                    .values.filterNot { it.deleted }.groupBy { it.kind }
                docsToImport.filterNot { it.kind == Kind.session }.forEach { doc ->
                    val old = cache[doc.key()]?.document
                    validateDocument(
                        doc,
                        list = { kind -> combined[kind] ?: emptyList() },
                        lookup = { kind, id ->
                            if (kind == doc.kind && id == doc.id) old
                            else incoming["${kind.name}/$id"] ?: lookup(kind, id)
                        }
                    )
                    require(replace || old == null || old.data == doc.data && old.deleted == doc.deleted) {
                        "已有不同内容：${doc.displayName()}。请明确选择替换后导入。"
                    }
                }
                docsToImport.filter { it.kind == Kind.session }.forEach { doc ->
                    val old = cache[doc.key()]?.document
                    val localDate = if (doc.deleted) null else doc.value<Session>().localDate
                    validateDocument(
                        doc,
                        list = { kind -> combined[kind] ?: emptyList() },
                        lookup = { kind, id ->
                            if (kind == doc.kind && id == doc.id) old
                            else incoming["${kind.name}/$id"] ?: lookup(kind, id)
                        },
                        restoreSession = doc.id in replacementSessionIds,
                        requireNewResultTimes = false
                    )
                }
                val docs = docsToImport.map { doc ->
                    if (doc.kind != Kind.media || doc.deleted) doc else {
                        val media = doc.value<Media>()
                        val staged = temporary.resolve(media.fileName)
                        require(Files.exists(staged)) { "数据包缺少素材：${media.name}" }
                        val existing = openMedia(media.fileName)
                        val same = existing?.use { old ->
                            Files.newInputStream(staged).use { source -> mediaDigest(old).contentEquals(mediaDigest(source)) }
                        } ?: false
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
                database.withTransaction {
                    dao.putAll(values.map { it.row() })
                    val healthPairs = linkedSetOf<Pair<String, String>>()
                    val healthBatch = ArrayList<LocalHealthRecord>(100)
                    val importCachedAt = System.currentTimeMillis()
                    healthStage.toFile().bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            val entry = wireJson.decodeFromString<HealthBackupEntry>(line)
                            val record = entry.record
                            java.time.LocalDate.parse(record.localDate)
                            healthPairs += record.localDate to entry.dataset.name
                            healthBatch += record.cacheRow(entry.dataset, revision = 0L, cachedAt = importCachedAt)
                            if (healthBatch.size == 100) {
                                dao.putHealthRecords(healthBatch.toList())
                                healthBatch.clear()
                            }
                        }
                    }
                    if (healthBatch.isNotEmpty()) dao.putHealthRecords(healthBatch)
                    backup.healthManifest?.takeIf { it.scope == "cache" }?.let { manifest ->
                        manifest.coveredDates.forEach { date -> java.time.LocalDate.parse(date) }
                        manifest.coveredDates.forEach { date ->
                            manifest.datasets.forEach { dataset -> healthPairs += date to dataset.name }
                        }
                    }
                    healthPairs.forEach { (date, dataset) ->
                        dao.putHealthDetailState(LocalHealthDetailState(
                            date, dataset, revision = 0L, complete = false, cachedAt = importCachedAt
                        ))
                    }
                    docs.asSequence()
                        .filter { it.kind == Kind.session && it.deleted }
                        .forEach { dao.removeValue("session-restore-intent/${it.id}") }
                    if (replace) {
                        values.asSequence()
                            .filter { it.document.kind == Kind.session && !it.document.deleted && it.document.id in replacementSessionIds }
                            .forEach { cached ->
                                dao.putValue(LocalValue(
                                    "session-restore-intent/${cached.document.id}",
                                    UUID.randomUUID().toString()
                                ))
                            }
                    }
                    dao.putValue(LocalValue("cursor", "0"))
                }
                install(values); committed = true
                _data.value.health.selectedDate?.let { loadHealthCache(it) }
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
