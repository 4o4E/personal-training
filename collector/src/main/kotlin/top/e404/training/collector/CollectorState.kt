package top.e404.training.collector

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** Durable collector outbox; source and backend watermarks intentionally remain separate. */
class CollectorState(path: Path) : AutoCloseable {
    private val db: Connection

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        db = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        db.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=FULL")
            statement.execute(
                """CREATE TABLE IF NOT EXISTS dataset_progress (
                   dataset TEXT PRIMARY KEY,
                   source_watermark INTEGER NOT NULL DEFAULT 0,
                   submitted_watermark INTEGER NOT NULL DEFAULT 0
                )""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS source_records (
                   dataset TEXT NOT NULL,
                   source_id TEXT NOT NULL,
                   local_date TEXT NOT NULL,
                   measured_at INTEGER NOT NULL,
                   fingerprint TEXT NOT NULL,
                   PRIMARY KEY(dataset, source_id)
                )""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS pending_records (
                   dataset TEXT NOT NULL,
                   source_id TEXT NOT NULL,
                   local_date TEXT NOT NULL,
                   measured_at INTEGER NOT NULL,
                   payload TEXT NOT NULL,
                   PRIMARY KEY(dataset, source_id)
                )""",
            )
            statement.execute("CREATE INDEX IF NOT EXISTS pending_dataset_order ON pending_records(dataset, measured_at, source_id)")
        }
        db.autoCommit = false
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
        } catch (_: java.io.IOException) {
        }
    }

    fun watermark(dataset: String, column: String): Long {
        require(column == "source_watermark" || column == "submitted_watermark") { "unsupported watermark" }
        db.prepareStatement("SELECT $column FROM dataset_progress WHERE dataset=?").use { statement ->
            statement.setString(1, dataset)
            statement.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    fun setSourceWatermark(dataset: String, value: Long) {
        db.prepareStatement(
            "INSERT INTO dataset_progress(dataset,source_watermark) VALUES(?,?) " +
                "ON CONFLICT(dataset) DO UPDATE SET source_watermark=MAX(dataset_progress.source_watermark,excluded.source_watermark)",
        ).use { statement ->
            statement.setString(1, dataset)
            statement.setLong(2, value)
            statement.executeUpdate()
        }
        db.commit()
    }
    fun setBackfillProgress(dataset: String, sourceWatermark: Long, submittedWatermark: Long) {
        db.prepareStatement(
            "INSERT INTO dataset_progress(dataset,source_watermark,submitted_watermark) VALUES(?,?,?) " +
                "ON CONFLICT(dataset) DO UPDATE SET source_watermark=excluded.source_watermark,submitted_watermark=excluded.submitted_watermark",
        ).use { statement ->
            statement.setString(1, dataset)
            statement.setLong(2, sourceWatermark)
            statement.setLong(3, submittedWatermark)
            statement.executeUpdate()
        }
        db.commit()
    }

    fun sourceRecordChanged(
        dataset: String,
        sourceId: String,
        localDate: String,
        measuredAt: Long,
        payload: JsonObject,
        force: Boolean = false,
    ): Boolean {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(payload.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        db.prepareStatement("SELECT local_date,measured_at,fingerprint FROM source_records WHERE dataset=? AND source_id=?").use { query ->
            query.setString(1, dataset)
            query.setString(2, sourceId)
            query.executeQuery().use { rs ->
                if (!force && rs.next() && rs.getString("local_date") == localDate &&
                    rs.getLong("measured_at") == measuredAt && rs.getString("fingerprint") == fingerprint
                ) return false
            }
        }
        db.prepareStatement(
            "INSERT INTO source_records(dataset,source_id,local_date,measured_at,fingerprint) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(dataset,source_id) DO UPDATE SET local_date=excluded.local_date,measured_at=excluded.measured_at,fingerprint=excluded.fingerprint",
        ).use { statement ->
            statement.setString(1, dataset)
            statement.setString(2, sourceId)
            statement.setString(3, localDate)
            statement.setLong(4, measuredAt)
            statement.setString(5, fingerprint)
            statement.executeUpdate()
        }
        enqueue(dataset, sourceId, localDate, measuredAt, payload)
        return true
    }

    fun enqueue(dataset: String, sourceId: String, localDate: String, measuredAt: Long, payload: JsonObject) {
        db.prepareStatement(
            "INSERT INTO pending_records(dataset,source_id,local_date,measured_at,payload) VALUES(?,?,?,?,?) " +
                "ON CONFLICT(dataset,source_id) DO UPDATE SET local_date=excluded.local_date,measured_at=excluded.measured_at,payload=excluded.payload",
        ).use { statement ->
            statement.setString(1, dataset)
            statement.setString(2, sourceId)
            statement.setString(3, localDate)
            statement.setLong(4, measuredAt)
            statement.setString(5, Json.encodeToString(JsonObject.serializer(), payload))
            statement.executeUpdate()
        }
    }

    fun commitEnqueue() = db.commit()

    fun nextBatch(dataset: String, limit: Int): List<PendingRecord> {
        val order = if (dataset == "heartRate") "DESC" else "ASC"
        db.prepareStatement(
            "SELECT source_id,local_date,measured_at,payload FROM pending_records WHERE dataset=? ORDER BY measured_at $order,source_id LIMIT ?",
        ).use { statement ->
            statement.setString(1, dataset)
            statement.setInt(2, limit)
            statement.executeQuery().use { rs ->
                return buildList {
                    while (rs.next()) add(
                        PendingRecord(rs.getString("source_id"), rs.getString("local_date"), rs.getLong("measured_at"),
                            Json.parseToJsonElement(rs.getString("payload")) as JsonObject),
                    )
                }
            }
        }
    }

    fun pendingCount(dataset: String): Long = db.prepareStatement("SELECT COUNT(*) FROM pending_records WHERE dataset=?").use { statement ->
        statement.setString(1, dataset)
        statement.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
    }

    fun acknowledge(dataset: String, rows: List<PendingRecord>) {
        if (rows.isEmpty()) return
        try {
            db.prepareStatement("DELETE FROM pending_records WHERE dataset=? AND source_id=?").use { delete ->
                rows.forEach { row ->
                    delete.setString(1, dataset)
                    delete.setString(2, row.sourceId)
                    delete.addBatch()
                }
                delete.executeBatch()
            }
            val maxTime = rows.maxOf { it.measuredAt }
            db.prepareStatement(
                "INSERT INTO dataset_progress(dataset,submitted_watermark) VALUES(?,?) " +
                    "ON CONFLICT(dataset) DO UPDATE SET submitted_watermark=MAX(dataset_progress.submitted_watermark,excluded.submitted_watermark)",
            ).use { statement ->
                statement.setString(1, dataset)
                statement.setLong(2, maxTime)
                statement.executeUpdate()
            }
            db.commit()
        } catch (exc: Exception) {
            db.rollback()
            throw exc
    }
    }

    override fun close() = db.close()
}

data class PendingRecord(val sourceId: String, val localDate: String, val measuredAt: Long, val payload: JsonObject)
