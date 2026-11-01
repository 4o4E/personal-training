package top.e404.training.collector

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Coordinates cloud fetches, raw captures, imports, and FDS completion state. */
class SourceSync(val conn: Connection, val client: MiClient, val root: Path) {
    fun sync(
        days: Int = 7,
        start: String? = null,
        end: String? = null,
        includeFds: Boolean = true,
        sportOnly: Boolean = false,
    ): Path {
        val today = LocalDate.now(CN_ZONE)
        val endDate = end ?: today.toString()
        val captureDir = root.resolve("data/raw").resolve(CAPTURE_FORMAT.format(Instant.now().atZone(CN_ZONE)))
        Files.createDirectories(captureDir)

        val results = linkedMapOf<String, Int>()
        val errors = linkedMapOf<String, String>()
        val importer = SourceImporter(conn)

        if (!sportOnly) {
            for (key in FITNESS_KEYS) {
                val last = getLastSynced("fitness", key)
                val keyStart = when {
                    start != null -> start
                    last != null -> Instant.ofEpochSecond(last).atZone(CN_ZONE).toLocalDate().minusDays(1).toString()
                    else -> today.minusDays(days.toLong()).toString()
                }
                if (keyStart > endDate) continue
                println("抓取 $key ($keyStart 到 $endDate) ...")
                try {
                    var total = 0
                    val windows = dateWindows(keyStart, endDate, 31)
                    for ((windowStart, windowEnd) in windows) {
                        val label = if (windows.size > 1) "${windowStart}_${windowEnd}" else null
                        total += syncFitnessKey(importer, key, windowStart, windowEnd, captureDir, label)
                    }
                    results[key] = total
                } catch (exc: Exception) {
                    errors[key] = describe(exc)
                    println("  $key 失败: ${errors.getValue(key)}")
                }
            }
        }

        val sportLast = getLastSynced("fitness", "sport_records")
        val sportStart = when {
            start != null -> start
            sportLast != null -> Instant.ofEpochSecond(sportLast).atZone(CN_ZONE).toLocalDate().minusDays(1).toString()
            else -> today.minusDays(maxOf(days, 90).toLong()).toString()
        }
        if (sportStart <= endDate) {
            println("抓取运动记录 ($sportStart 到 $endDate) ...")
            try {
                var total = 0
                val windows = dateWindows(sportStart, endDate, 90)
                for ((windowStart, windowEnd) in windows) {
                    val label = if (windows.size > 1) "${windowStart}_${windowEnd}" else null
                    total += syncSportRecords(importer, windowStart, windowEnd, captureDir, label)
                }
                results["sport_records"] = total
            } catch (exc: Exception) {
                errors["sport_records"] = describe(exc)
                println("  运动记录失败: ${errors.getValue("sport_records")}")
            }
        }


        if (includeFds) {
            println("补全运动 FDS 明细 ...")
            try {
                results["fds"] = syncFds(importer, captureDir)
            } catch (exc: Exception) {
                errors["fds"] = describe(exc)
                println("  FDS 补全失败: ${errors.getValue("fds")}")
            }
        }

        val metaPath = captureDir.resolve("meta.json")
        saveJson(
            metaPath,
            buildJsonObject {
                put("fetched_at", nowIso())
                put("range", JsonArray(listOf(JsonPrimitive(start ?: "last-${days}d"), JsonPrimitive(endDate))))
                put("counts", JsonObject(results.mapValues { JsonPrimitive(it.value) }))
                put("errors", JsonObject(errors.mapValues { JsonPrimitive(it.value) }))
            },
        )
        return metaPath
    }

    private fun syncFitnessKey(
        importer: SourceImporter,
        key: String,
        startDate: String,
        endDate: String,
        captureDir: Path,
        windowLabel: String?,
    ): Int {
        var outDir = captureDir.resolve("fitness").resolve(key)
        if (windowLabel != null) outDir = outDir.resolve(windowLabel)
        Files.createDirectories(outDir)
        val items = client.fetchKey(key, startDate, endDate) { page, body ->
            saveJson(outDir.resolve("page_${page.toString().padStart(3, '0')}.json"), body)
        }
        saveJson(outDir.resolve("all.json"), JsonArray(items))
        val rawRef = relativeRawPath(outDir)
        val timestamps = importer.importFitnessKey(key, items, rawRef)
        updateSyncState("fitness", key, timestamps)
        recordCapture("fitness", "$rawRef/all.json", items.size)
        conn.commit()
        println("  $key: ${items.size} 条")
        return items.size
    }

    private fun syncSportRecords(
        importer: SourceImporter,
        startDate: String,
        endDate: String,
        captureDir: Path,
        windowLabel: String?,
    ): Int {
        var outDir = captureDir.resolve("sport_records")
        if (windowLabel != null) outDir = outDir.resolve(windowLabel)
        Files.createDirectories(outDir)
        val items = client.fetchSportRecords(startDate, endDate) { page, body ->
            saveJson(outDir.resolve("page_${page.toString().padStart(3, '0')}.json"), body)
        }
        saveJson(outDir.resolve("all.json"), JsonArray(items))
        val rawRef = relativeRawPath(outDir)
        val timestamps = importer.importSportRecords(items, rawRef)
        updateSyncState("fitness", "sport_records", timestamps)
        recordCapture("sport_records", "$rawRef/all.json", items.size)
        conn.commit()
        println("  运动记录: ${items.size} 条")
        return items.size
    }

    private fun syncFds(importer: SourceImporter, captureDir: Path): Int {
        val rows = conn.prepareStatement(
            """SELECT a.id, a.source_id, a.fds_sid, a.proto_type, a.report_time,
               a.report_version, a.tz_in_15min, a.start_ts
               FROM activities a
               WHERE COALESCE(a.fds_collected, 0) = 0 AND a.report_version > 0
                 AND a.fds_sid IS NOT NULL AND a.proto_type IS NOT NULL AND a.report_time IS NOT NULL
               ORDER BY a.start_ts DESC""",
        ).use { statement -> statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    FdsActivity(
                        id = rs.getLong("id"), sourceId = rs.getString("source_id"),
                        sid = rs.getString("fds_sid"), protoType = rs.getInt("proto_type"),
                        reportTime = rs.getLong("report_time"), reportVersion = rs.getInt("report_version"),
                        tzIn15min = rs.getInt("tz_in_15min"), startTs = rs.getLong("start_ts"),
                    ),
                )
            }
        } }
        var completed = 0
        for (row in rows) {
            val supported = row.protoType in Fds.supportedProtoTypes
            val tzByte = row.tzIn15min and 0xff
            val fdsDir = captureDir.resolve("fds").resolve(row.sourceId)
            Files.createDirectories(fdsDir)
            val blobs = mutableMapOf<Int, ByteArray>()
            for ((fileType, name) in listOf(FDS_FILE_TYPE_SPORT_RECORD to "record", FDS_FILE_TYPE_GPS_TRACK to "gps")) {
                val suffix = Fds.buildSuffix(row.sid, row.reportTime, tzByte, row.protoType, fileType)
                val payload = buildJsonObject {
                    put("did", row.sid)
                    put("items", JsonArray(listOf(buildJsonObject {
                        put("timestamp", row.reportTime)
                        put("suffix", suffix)
                    })))
                }
                val (body, result) = client.request(
                    "/healthapp/service/gen_download_url", payload, "/service/gen_download_url",
                )
                saveJson(fdsDir.resolve("${name}_download.json"), body)
                val entry = result[Fds.serverKey(suffix, row.reportTime)] as? JsonObject ?: continue
                val url = entry.stringField("url") ?: continue
                val objKey = entry.stringField("obj_key") ?: continue
                val blob = client.downloadFds(url, objKey)
                Files.write(fdsDir.resolve("$name.bin"), blob)
                blobs[fileType] = blob
            }

            var status = "missing"
            if (blobs.isNotEmpty()) {
                if (supported) {
                    conn.createStatement().use { it.execute("SAVEPOINT fds_parse") }
                    try {
                        importer.importFds(
                            row.id, row.startTs, blobs[FDS_FILE_TYPE_SPORT_RECORD],
                            blobs[FDS_FILE_TYPE_GPS_TRACK], row.protoType, relativeRawPath(fdsDir),
                        )
                        conn.createStatement().use { it.execute("RELEASE fds_parse") }
                        status = "parsed"
                    } catch (exc: Exception) {
                        conn.createStatement().use {
                            it.execute("ROLLBACK TO fds_parse")
                            it.execute("RELEASE fds_parse")
                        }
                        status = "parse_error"
                        println("  FDS 解析失败: ${row.sourceId}: ${describe(exc)}")
                    }
                } else {
                    status = "raw_only"
                }
            }
            conn.prepareStatement(
                "UPDATE activities SET fds_status=?, fds_collected=1, fds_collected_at=? WHERE id=?",
            ).use { statement ->
                statement.setString(1, status)
                statement.setLong(2, Instant.now().epochSecond)
                statement.setLong(3, row.id)
                statement.executeUpdate()
            }
            if (blobs.isNotEmpty()) recordCapture("fds", relativeRawPath(fdsDir), 1)
            conn.commit()
            completed++
            println("  FDS 明细: ${row.sourceId} $status")
        }
        return completed
    }

    private fun getLastSynced(dataset: String, key: String): Long? = conn.prepareStatement(
        "SELECT last_synced_at FROM sync_state WHERE dataset=? AND data_key=?",
    ).use { statement ->
        statement.setString(1, dataset)
        statement.setString(2, key)
        statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).let { if (rs.wasNull()) null else it } else null }
    }

    private fun updateSyncState(dataset: String, key: String, timestamps: List<Long>) {
        if (timestamps.isEmpty()) return
        val coverageStart = timestamps.minOrNull()!!
        val coverageEnd = timestamps.maxOrNull()!!
        conn.prepareStatement(
            """INSERT INTO sync_state(dataset, data_key, last_synced_at, coverage_start, coverage_end)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(dataset, data_key) DO UPDATE SET
                 last_synced_at=MAX(COALESCE(sync_state.last_synced_at, 0), excluded.last_synced_at),
                 coverage_start=MIN(COALESCE(sync_state.coverage_start, excluded.coverage_start), excluded.coverage_start),
                 coverage_end=MAX(COALESCE(sync_state.coverage_end, 0), excluded.coverage_end)""",
        ).use { statement ->
            statement.setString(1, dataset)
            statement.setString(2, key)
            statement.setLong(3, coverageEnd)
            statement.setLong(4, coverageStart)
            statement.setLong(5, coverageEnd)
            statement.executeUpdate()
        }
    }

    private fun recordCapture(dataset: String, path: String, count: Int) {
        conn.prepareStatement(
            """INSERT INTO raw_captures(captured_at, dataset, path, record_count, sha256)
               VALUES (?, ?, ?, ?, NULL)
               ON CONFLICT(dataset, path) DO UPDATE SET captured_at=excluded.captured_at,
                 record_count=excluded.record_count, sha256=excluded.sha256""",
        ).use { statement ->
            statement.setString(1, nowIso())
            statement.setString(2, dataset)
            statement.setString(3, path)
            statement.setInt(4, count)
            statement.executeUpdate()
        }
    }

    private fun dateWindows(startDate: String, endDate: String, maxDays: Int): List<Pair<String, String>> {
        var startDateValue = LocalDate.parse(startDate)
        val endDateValue = LocalDate.parse(endDate)
        val windows = mutableListOf<Pair<String, String>>()
        while (!startDateValue.isAfter(endDateValue)) {
            val windowEnd = minOf(endDateValue, startDateValue.plusDays((maxDays - 1).toLong()))
            windows += startDateValue.toString() to windowEnd.toString()
            startDateValue = windowEnd.plusDays(1)
        }
        return windows
    }

    private fun saveJson(path: Path, value: kotlinx.serialization.json.JsonElement) {
        Files.createDirectories(path.parent)
        Files.writeString(path, JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), value))
    }

    private fun relativeRawPath(path: Path): String = root.toAbsolutePath().normalize()
        .relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')

    private fun nowIso(): String = ISO_FORMAT.format(Instant.now().atZone(CN_ZONE))

    private fun describe(exc: Exception): String = exc.javaClass.simpleName

    private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    private data class FdsActivity(
        val id: Long,
        val sourceId: String,
        val sid: String,
        val protoType: Int,
        val reportTime: Long,
        val reportVersion: Int,
        val tzIn15min: Int,
        val startTs: Long,
    )

    private companion object {
        val CN_ZONE: ZoneOffset = ZoneOffset.ofHours(8)
        val ISO_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val CAPTURE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val JSON = Json { prettyPrint = true; prettyPrintIndent = "  "; explicitNulls = true }
        val FITNESS_KEYS = listOf("steps", "calories", "heart_rate", "resting_heart_rate", "sleep", "weight")
        const val FDS_FILE_TYPE_SPORT_RECORD = 0
        const val FDS_FILE_TYPE_GPS_TRACK = 2
    }
}
