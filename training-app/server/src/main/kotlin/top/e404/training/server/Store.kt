package top.e404.training.server

import kotlinx.serialization.decodeFromString
import top.e404.training.shared.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** 每份业务文档原子保存；单人后端串行使用 SQLite，冲突不会阻止其他文档同步。 */
class Store(dataDirectory: Path, seed: List<Document>) : AutoCloseable {
    private val connection: Connection
    private val health: HealthStore

    init {
        Files.createDirectories(dataDirectory)
        connection = DriverManager.getConnection("jdbc:sqlite:${dataDirectory.resolve("training.db").toAbsolutePath()}")
        connection.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("PRAGMA busy_timeout=5000")
            s.execute("CREATE TABLE IF NOT EXISTS documents(kind TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(kind,id))")
            s.execute("CREATE INDEX IF NOT EXISTS documents_revision ON documents(revision)")
            s.execute("CREATE TABLE IF NOT EXISTS revision_clock(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL)")
            s.execute("INSERT OR IGNORE INTO revision_clock(id,value) VALUES(1,0)")
            s.execute("CREATE TABLE IF NOT EXISTS health_records(dataset TEXT NOT NULL,source_id TEXT NOT NULL,local_date TEXT NOT NULL,measured_at INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(dataset,source_id))")
            s.execute("CREATE INDEX IF NOT EXISTS health_records_date ON health_records(dataset,local_date,measured_at,source_id)")
            s.execute("CREATE TABLE IF NOT EXISTS health_days(local_date TEXT PRIMARY KEY,revision INTEGER NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS health_collection_state(id INTEGER PRIMARY KEY CHECK(id=1),data TEXT NOT NULL)")
            s.execute("INSERT OR IGNORE INTO health_collection_state(id,data) VALUES(1,'{}')")
            s.execute("INSERT OR IGNORE INTO health_days(local_date,revision) SELECT DISTINCT local_date,0 FROM health_records")
            s.execute("CREATE TABLE IF NOT EXISTS health_activity_baselines(source_id TEXT PRIMARY KEY,local_date TEXT NOT NULL,payload TEXT NOT NULL)")
        }
        connection.autoCommit = false
        try {
            val seedBase = maxOf(revision() + 1, System.currentTimeMillis())
            connection.prepareStatement("INSERT OR IGNORE INTO documents(kind,id,revision,deleted,data) VALUES(?,?,?,0,?)").use { statement ->
                seed.forEachIndexed { index, doc ->
                    statement.setString(1, doc.kind.name); statement.setString(2, doc.id); statement.setLong(3, seedBase + index)
                    statement.setString(4, wireJson.encodeToString(doc.data)); statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.createStatement().use { it.executeUpdate("UPDATE revision_clock SET value=MAX(value,(SELECT COALESCE(MAX(revision),0) FROM documents)) WHERE id=1") }
            connection.commit()
        } catch (e: Exception) { connection.rollback(); throw e }
        finally { connection.autoCommit = true }
        migrateDailySessions()
        health = HealthStore(connection)
    }

    @Synchronized fun get(kind: Kind, id: String): Document? = connection.prepareStatement("SELECT revision,deleted,data FROM documents WHERE kind=? AND id=?").use { statement ->
        statement.setString(1, kind.name); statement.setString(2, id)
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else Document(kind, id, wireJson.parseToJsonElement(rows.getString(3)).let { it as kotlinx.serialization.json.JsonObject }, rows.getLong(1), rows.getBoolean(2))
        }
    }

    @Synchronized fun list(kind: Kind? = null, includeDeleted: Boolean = false): List<Document> {
        val conditions = mutableListOf<String>()
        if (kind != null) conditions += "kind=?"
        if (!includeDeleted) conditions += "deleted=0"
        val sql = "SELECT kind,id,revision,deleted,data FROM documents" + if (conditions.isEmpty()) " ORDER BY kind,id" else " WHERE ${conditions.joinToString(" AND ")} ORDER BY kind,id"
        return query(sql) { if (kind != null) it.setString(1, kind.name) }
    }

    @Synchronized fun sync(request: SyncRequest): SyncResponse {
        require(request.cursor >= 0) { "同步游标不能为负数" }
        require(request.mutations.map { it.document.kind to it.document.id }.distinct().size == request.mutations.size) { "同一份资料不能在一次请求内重复修改" }
        val accepted = mutableListOf<Document>()
        val conflicts = mutableListOf<Conflict>()
        request.mutations.sortedWith(compareBy<Mutation> { it.document.deleted }.thenBy { if (it.document.deleted) -it.document.kind.ordinal else it.document.kind.ordinal }).forEach { mutation ->
            val incoming = mutation.document
            val current = get(incoming.kind, incoming.id)
            if (mutation.restoreSession) require(incoming.kind == Kind.session && !incoming.deleted) {
                "仅可显式恢复完整的非删除每日训练"
            }
            val revisionMatches = (current?.revision ?: 0) == mutation.baseRevision
            if (current != null && current.deleted == incoming.deleted && current.data == incoming.data) {
                accepted += current
            } else if (!revisionMatches) {
                conflicts += Conflict(incoming, current ?: Document(incoming.kind, incoming.id, kotlinx.serialization.json.JsonObject(emptyMap()), deleted = true))
            } else {
                validateDocument(
                    incoming, { kind -> list(kind) }, ::get,
                    restoreSession = mutation.restoreSession,
                    requireNewResultTimes = !mutation.restoreSession
                )
                accepted += transaction { persist(incoming) }
            }
        }
        val cursor = revision()
        val changed = query("SELECT kind,id,revision,deleted,data FROM documents WHERE revision>? AND revision<=? ORDER BY revision") {
            it.setLong(1, request.cursor); it.setLong(2, cursor)
        }
        return SyncResponse(cursor, changed, accepted, conflicts)
    }

    /** A backup is validated completely before any document is committed. */
    @Synchronized fun import(backup: Backup, replace: Boolean, healthEntries: Sequence<HealthBackupEntry>? = null): List<Document> {
        require(backup.formatVersion in 1..6) { "不支持此数据包版本" }
        val manifest = backup.healthManifest
        if (backup.formatVersion == 6) {
            requireNotNull(manifest) { "版本6数据包缺少健康清单" }
            require(manifest.scope == "complete") { "服务器只能恢复完整健康备份" }
            require(manifest.recordCount == 0L || healthEntries != null) { "完整健康备份缺少健康记录流" }
        } else require(healthEntries == null) { "旧版本数据包不能包含健康记录" }
        require(backup.documents.map { it.kind to it.id }.distinct().size == backup.documents.size) { "数据包包含重复资料" }
        if (backup.formatVersion >= 5) require(backup.documents.filter { it.kind == Kind.session }.all {
            it.deleted || it.isCanonicalSessionDocument()
        }) { "版本${backup.formatVersion}数据包必须使用canonical每日训练格式" }
        if (backup.formatVersion >= 3) require(backup.documents.none { it.hasDeprecatedHistoricalText() }) {
            "版本${backup.formatVersion}数据包不能包含已废弃的 historicalSource.originalText"
        }
        val imported = mergeImportedSessions(list(includeDeleted = true), backup.documents, replace)
        val canonicalSessionIds = if (backup.formatVersion >= 5) backup.documents
            .filter { it.kind == Kind.session && !it.deleted && it.isCanonicalSessionDocument() }
            .mapTo(hashSetOf()) { it.id } else emptySet()
        val replacedSourceIds = if (replace) backup.documents
            .filter { it.kind == Kind.session && !it.deleted }
            .flatMap { migrateSessionDocument(it).value<Session>().sourceRecords.map { source -> source.sourceId } }
            .toMutableSet()
            .apply {
                canonicalSessionIds.forEach { id ->
                    this@Store.get(Kind.session, id)?.takeUnless { it.deleted }?.value<Session>()?.sourceRecords
                        ?.mapTo(this) { source -> source.sourceId }
                }
            } else emptySet()
        val combined = (list(includeDeleted = true) + imported).associateBy { it.kind to it.id }
            .values.filterNot { it.deleted }.groupBy { it.kind }
        val byKey = imported.associateBy { it.kind to it.id }
        imported.forEach { doc ->
            validateDocument(
                doc,
                { kind -> combined[kind] ?: emptyList() },
                { kind, id -> if (kind == doc.kind && id == doc.id) get(kind, id) else byKey[kind to id] ?: get(kind, id) },
                replacedSourceIds,
                restoreSession = replace && doc.kind == Kind.session && doc.id in canonicalSessionIds,
                requireNewResultTimes = false
            )
            if (doc.kind != Kind.session) {
                val old = get(doc.kind, doc.id)
                if (!replace && old != null && (old.data != doc.data || old.deleted != doc.deleted))
                    throw ImportConflict("已有不同内容：${doc.displayName()}；需明确选择替换")
            }
        }
        if (healthEntries != null) {
            val count = health.validateEntries(healthEntries, replace)
            require(count == manifest?.recordCount) { "健康清单记录数与数据流不一致" }
        }
        return transaction {
            val saved = imported.sortedWith(compareBy<Document> { !it.deleted }.thenBy { it.kind.ordinal }).map { doc ->
                val old = get(doc.kind, doc.id)
                if (old != null && old.data == doc.data && old.deleted == doc.deleted) old else persist(doc)
            }
            healthEntries?.let { health.saveEntries(it, replace) }
            healthEntries?.filter { it.dataset == HealthDataset.activity }?.forEach { entry ->
                connection.prepareStatement("INSERT INTO health_activity_baselines(source_id,local_date,payload) VALUES(?,?,?) ON CONFLICT(source_id) DO UPDATE SET local_date=excluded.local_date,payload=excluded.payload").use { st ->
                    st.setString(1, entry.record.sourceId); st.setString(2, entry.record.localDate)
                    st.setString(3, wireJson.encodeToString(entry.record.decodePayload<ActivityPayload>())); st.executeUpdate()
                }
            }
            saved
        }
    }

    @Synchronized fun healthImport(request: HealthImportRequest): HealthImportResult {
        health.validateImport(request)
        return transaction {
            val result = health.saveImport(request)
            if (request.dataset == HealthDataset.activity) projectActivities(request.records)
            result
        }
    }
    @Synchronized fun healthStatus(): HealthStatus = health.status()
    @Synchronized fun healthDays(from: java.time.LocalDate, to: java.time.LocalDate): HealthDaysResponse = health.days(from, to)
    private fun projectActivities(records: List<HealthRecord>) {
        data class Projection(val record: HealthRecord, val source: ActivityPayload, val doc: Document?)
        val projections = records.map { record ->
            val source = record.decodePayload<ActivityPayload>()
            val sourceId = "misports:activities:${record.sourceId}"
            val baseline = connection.prepareStatement("SELECT local_date,payload FROM health_activity_baselines WHERE source_id=?").use { st ->
                st.setString(1, record.sourceId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to wireJson.decodeFromString<ActivityPayload>(rs.getString(2)) else null }
            }
            val existing = list(Kind.activity, includeDeleted = true).firstOrNull {
                runCatching { it.value<ActivityRecord>().sourceId == sourceId }.getOrDefault(false)
            }
            val old = existing?.value<ActivityRecord>()
            val doc = if (existing?.deleted == true) null else {
                fun <T> project(current: T?, incoming: T?, previous: T?): T? =
                    if (current == null || previous != null && current == previous) incoming else current
                val id = existing?.id ?: "xiaomi-${java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(sourceId.toByteArray(Charsets.UTF_8))
                ).take(32)}"
                val value = ActivityRecord(
                    id = id,
                    name = old?.name ?: when (source.sportKey) {
                        "pool_swimming" -> "游泳"
                        "outdoor_running" -> "跑步"
                        "outdoor_walking" -> "徒步"
                        else -> source.sportKey
                    },
                    localDate = if (old == null || baseline != null && old.localDate == baseline.first) record.localDate else old.localDate,
                    durationSeconds = project(old?.durationSeconds, source.durationSeconds?.takeIf { it > 0 }, baseline?.second?.durationSeconds),
                    distanceMeters = project(old?.distanceMeters, source.distanceMeters?.takeIf { it > 0 }, baseline?.second?.distanceMeters),
                    notes = old?.notes ?: "",
                    sourceId = sourceId,
                    startedAt = project(old?.startedAt, source.startAt, baseline?.second?.startAt),
                    endedAt = project(old?.endedAt, source.endAt, baseline?.second?.endAt)
                )
                if (old == value) null else document(Kind.activity, id, value).copy(revision = existing?.revision ?: 0)
            }
            Projection(record, source, doc)
        }
        projections.forEach { projection ->
            projection.doc?.let { doc ->
                validateDocument(doc, { kind -> list(kind) }, ::get, requireNewResultTimes = false)
                val current = get(Kind.activity, doc.id)
                if (current?.deleted != true && (current == null || current.data != doc.data)) persist(doc)
            }
            connection.prepareStatement("INSERT INTO health_activity_baselines(source_id,local_date,payload) VALUES(?,?,?) ON CONFLICT(source_id) DO UPDATE SET local_date=excluded.local_date,payload=excluded.payload").use { st ->
                st.setString(1, projection.record.sourceId); st.setString(2, projection.record.localDate)
                st.setString(3, wireJson.encodeToString(projection.source)); st.executeUpdate()
            }
        }
    }
    @Synchronized fun healthRecords(date: java.time.LocalDate, dataset: HealthDataset, cursor: String?): HealthRecordsPage =
        health.records(date, dataset, cursor)
    @Synchronized fun exportBackup(output: java.io.OutputStream, openMedia: (String) -> java.io.InputStream?) {
        writeBackup(backup(), output, openMedia, health.allRecords())
    }
    @Synchronized fun collectionRequest(): HealthCollectionRequestResult = health.requestCollection()
    @Synchronized fun collectionState(request: HealthCollectionStateRequest): HealthCollectionState = health.updateCollection(request)
    @Synchronized fun healthManifest(scope: String = "complete"): HealthBackupManifest = health.manifest(scope)
    @Synchronized fun allHealthRecords(): Sequence<HealthBackupEntry> = health.allRecords()

    @Synchronized fun backup(): Backup = Backup(
        documents = list(includeDeleted = true), exportedAt = System.currentTimeMillis(),
        healthManifest = health.manifest("complete")
    )
    private fun migrateDailySessions() {
        val original = list(Kind.session, includeDeleted = true)
        val normalized = normalizeDailySessionDocuments(original)
        val oldById = original.associateBy { it.id }
        val changed = normalized.filter { next ->
            val old = oldById[next.id]
            old == null || old.data != next.data || old.deleted != next.deleted
        }
        if (changed.isEmpty()) return
        transaction {
            changed.filter { it.deleted }.forEach(::persist)
            changed.filterNot { it.deleted }.forEach { doc ->
                validateDocument(doc, { kind -> list(kind) }, ::get, requireNewResultTimes = false)
                persist(doc)
            }
        }
    }

    private fun revision(): Long = connection.createStatement().use { statement -> statement.executeQuery("SELECT value FROM revision_clock WHERE id=1").use { it.next(); it.getLong(1) } }

    private fun persist(doc: Document): Document {
        val next = maxOf(revision() + 1, System.currentTimeMillis())
        connection.prepareStatement("UPDATE revision_clock SET value=? WHERE id=1").use { it.setLong(1, next); it.executeUpdate() }
        val saved = doc.copy(revision = next)
        connection.prepareStatement("INSERT INTO documents(kind,id,revision,deleted,data) VALUES(?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET revision=excluded.revision,deleted=excluded.deleted,data=excluded.data").use {
            it.setString(1, saved.kind.name); it.setString(2, saved.id); it.setLong(3, saved.revision)
            it.setBoolean(4, saved.deleted); it.setString(5, wireJson.encodeToString(saved.data)); it.executeUpdate()
        }
        return saved
    }

    private fun query(sql: String, bind: (java.sql.PreparedStatement) -> Unit = {}): List<Document> = connection.prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(Document(Kind.valueOf(rows.getString(1)), rows.getString(2), wireJson.parseToJsonElement(rows.getString(5)) as kotlinx.serialization.json.JsonObject, rows.getLong(3), rows.getBoolean(4)))
        } }
    }

    private fun <T> transaction(action: () -> T): T {
        connection.autoCommit = false
        return try { val result = action(); connection.commit(); result }
        catch (e: Exception) { connection.rollback(); throw e }
        finally { connection.autoCommit = true }
    }

    @Synchronized override fun close() { connection.close() }
}

