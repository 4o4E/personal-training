package top.e404.training.server

import kotlinx.serialization.encodeToString
import top.e404.training.shared.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** 每份业务文档原子保存；单人后端串行使用 SQLite，冲突不会阻止其他文档同步。 */
class Store(dataDirectory: Path, seed: List<Document>) : AutoCloseable {
    private val connection: Connection

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
            if (current != null && current.deleted == incoming.deleted && current.data == incoming.data) {
                accepted += current
            } else if ((current?.revision ?: 0) != mutation.baseRevision) {
                conflicts += Conflict(incoming, current ?: Document(incoming.kind, incoming.id, kotlinx.serialization.json.JsonObject(emptyMap()), deleted = true))
            } else {
                validateDocument(incoming, { kind -> list(kind) }, ::get)
                accepted += transaction { persist(incoming) }
            }
        }
        val cursor = revision()
        val changed = query("SELECT kind,id,revision,deleted,data FROM documents WHERE revision>? AND revision<=? ORDER BY revision") {
            it.setLong(1, request.cursor); it.setLong(2, cursor)
        }
        return SyncResponse(cursor, changed, accepted, conflicts)
    }

    /** 导入先核验整包；遇到同标识的不同数据必须明确允许替换，不能导入一半才发现冲突。 */
    @Synchronized fun import(backup: Backup, replace: Boolean): List<Document> {
        require(backup.formatVersion in 1..4) { "不支持此数据包版本" }
        require(backup.documents.map { it.kind to it.id }.distinct().size == backup.documents.size) { "数据包包含重复资料" }
        val incoming = backup.documents.associateBy { it.kind to it.id }
        val combined = (list(includeDeleted = true) + backup.documents).associateBy { it.kind to it.id }.values.filterNot { it.deleted }.groupBy { it.kind }
        backup.documents.forEach { doc ->
            val old = get(doc.kind, doc.id)
            // 关联资料使用整包内容，已经开始的训练仍与当前保存的原始内容核验。
            validateDocument(doc, { kind -> combined[kind] ?: emptyList() }) { kind, id ->
                if (kind == doc.kind && id == doc.id) old else incoming[kind to id] ?: get(kind, id)
            }
            if (!replace && old != null && (old.data != doc.data || old.deleted != doc.deleted)) throw ImportConflict("已有不同内容：${doc.displayName()}；需明确选择替换")
        }
        return transaction {
            backup.documents.sortedBy { it.kind.ordinal }.map { doc ->
                val old = get(doc.kind, doc.id)
                if (old != null && old.data == doc.data && old.deleted == doc.deleted) old else persist(doc)
            }
        }
    }

    @Synchronized fun backup(): Backup = Backup(documents = list(includeDeleted = true), exportedAt = System.currentTimeMillis())

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
class ImportConflict(message: String) : RuntimeException(message)
