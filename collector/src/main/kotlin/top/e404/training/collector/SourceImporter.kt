package top.e404.training.collector

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Imports Xiaomi cloud JSON and FDS details into the existing collector SQLite schema. */
class SourceImporter(val conn: Connection) {
    fun importFitnessKey(key: String, items: List<JsonObject>, rawRef: String?): List<Long> = when (key) {
        "steps" -> importSteps(items)
        "calories" -> importCalories(items)
        "heart_rate" -> importHeartRate(items)
        "resting_heart_rate" -> importRestingHeartRate(items)
        "sleep" -> importSleep(items, rawRef)
        "weight" -> importBodyMeasurements(items, rawRef)
        else -> emptyList()
    }

    fun importSportRecords(items: List<JsonObject>, rawRef: String?): List<Long> {
        val timestamps = ArrayList<Long>()
        for (item in items) {
            val value = valueOf(item)
            val start = (value["start_time"].longOrNull()?.takeIf { it != 0L } ?: item["time"].longOrNull())?.takeIf { it != 0L } ?: continue
            val end = value["end_time"].longOrNull()?.takeIf { it != 0L } ?: continue
            val duration = value["duration"].longOrNull() ?: (end - start).coerceAtLeast(0)
            val sourceId = "${item["sid"].pythonString()}_${item["key"].pythonString()}_${item["time"].pythonString()}"
            val swim = value["sport_type"].intOrNull() == 9 || item["category"].stringOrNull() == "swimming"
            val distance = value["distance"].doubleOrNull()
            var pace = value["avg_pace"].doubleOrNull()
            if (pace == null && distance != null && distance != 0.0 && duration != 0L) {
                pace = duration.toDouble() / (distance / if (swim) 100.0 else 1000.0)
            }
            val trainingLoad = value["training_load"].takeUnless { it == null || it == kotlinx.serialization.json.JsonNull } ?: value["train_load"]
            val recoveryTime = value["recovery_time"].takeUnless { it == null || it == kotlinx.serialization.json.JsonNull } ?: value["recover_time"]
            val extra = value.filterKeys { it !in ACTIVITY_CORE_FIELDS }
            val fields = listOf(
                "source_id" to sourceId,
                "sport_key" to (item["key"].stringOrNull()?.takeIf(String::isNotEmpty) ?: "workout"),
                "sport_category" to item["category"].sqlValue(),
                "sport_type_code" to value["sport_type"].sqlValue(),
                "start_ts" to start,
                "end_ts" to end,
                "date_local" to localDate(start),
                "duration_s" to duration,
                "distance_m" to value["distance"].sqlValue(),
                "calories_kcal" to value["calories"].sqlValue(),
                "steps" to value["steps"].sqlValue(),
                "avg_hr" to value["avg_hrm"].sqlValue(),
                "max_hr" to value["max_hrm"].sqlValue(),
                "min_hr" to value["min_hrm"].sqlValue(),
                "avg_cadence" to value["avg_cadence"].sqlValue(),
                "max_cadence" to value["max_cadence"].sqlValue(),
                "avg_stride_cm" to value["avg_stride"].sqlValue(),
                "avg_speed_mps" to value["avg_speed"].sqlValue(),
                "max_speed_mps" to value["max_speed"].sqlValue(),
                "avg_pace_s_per_km" to if (swim) null else pace,
                "max_pace_s_per_km" to if (swim) null else value["max_pace"].sqlValue(),
                "min_pace_s_per_km" to if (swim) null else value["min_pace"].sqlValue(),
                "hr_warm_up_s" to value["hrm_warm_up_duration"].sqlValue(),
                "hr_fat_burning_s" to value["hrm_fat_burning_duration"].sqlValue(),
                "hr_aerobic_s" to value["hrm_aerobic_duration"].sqlValue(),
                "hr_anaerobic_s" to value["hrm_anaerobic_duration"].sqlValue(),
                "hr_extreme_s" to value["hrm_extreme_duration"].sqlValue(),
                "avg_height_m" to value["avg_height"].sqlValue(),
                "max_height_m" to value["max_height"].sqlValue(),
                "min_height_m" to value["min_height"].sqlValue(),
                "vo2max" to value["vo2max"].sqlValue(),
                "train_effect" to value["train_effect"].sqlValue(),
                "anaerobic_train_effect" to value["anaerobic_train_effect"].sqlValue(),
                "training_load" to trainingLoad.sqlValue(),
                "recovery_time" to recoveryTime.sqlValue(),
                "avg_spo2" to value["avg_spo2"].sqlValue(),
                "valid_duration_s" to value["valid_duration"].sqlValue(),
                "stroke_count" to value["stroke_count"].sqlValue(),
                "avg_stroke_frequency" to value["avg_stroke_frequency"].sqlValue(),
                "max_stroke_freq" to value["max_stroke_freq"].sqlValue(),
                "avg_swolf" to value["avg_swolf"].sqlValue(),
                "best_swolf" to value["best_swolf"].sqlValue(),
                "turn_count" to value["turn_count"].sqlValue(),
                "pool_width" to value["pool_width"].sqlValue(),
                "main_posture" to value["main_posture"].sqlValue(),
                "total_calories_kcal" to value["total_cal"].sqlValue(),
                "avg_pace_s_per_100m" to if (swim) pace else null,
                "max_pace_s_per_100m" to if (swim) value["max_pace"].sqlValue() else null,
                "min_pace_s_per_100m" to if (swim) value["min_pace"].sqlValue() else null,
                "extra_json" to extra.takeIf { it.isNotEmpty() }?.let(::jsonText),
                "fds_sid" to item["sid"].sqlValue(),
                "proto_type" to value["proto_type"].sqlValue(),
                "report_version" to value["version"].sqlValue(),
                "report_time" to firstTruthy(value["time"], item["time"])?.sqlValue(),
                "tz_in_15min" to value["timezone"].sqlValue(),
                "raw_ref" to rawRef,
            )
            upsert("activities", fields, "source_id")
            timestamps += start
        }
        return timestamps
    }

    fun importFds(
        activityId: Long,
        startTs: Long,
        recordBlob: ByteArray?,
        gpsBlob: ByteArray?,
        protoType: Int,
        rawRef: String?,
    ) {
        if (recordBlob != null && recordBlob.isNotEmpty()) {
            conn.prepareStatement(
                """INSERT OR REPLACE INTO activity_samples(
                    activity_id, offset_s, ts, hr, cadence, distance_m, calories_kcal,
                    stride_cm, integer_km_flag, speed_mps, altitude_m
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
            ).use { statement ->
                for ((segmentStart, records) in Fds.parseSportRecord(recordBlob, protoType)) {
                    for ((index, row) in records.withIndex()) {
                        val ts = segmentStart + index
                        statement.setLong(1, activityId)
                        statement.setLong(2, ts - startTs)
                        statement.setLong(3, ts)
                        statement.setObject(4, row[TYPE_HR])
                        statement.setObject(5, row[TYPE_CADENCE])
                        statement.setObject(6, row[TYPE_DISTANCE]?.toDouble()?.div(10.0))
                        statement.setObject(7, row[TYPE_CALORIES])
                        statement.setObject(8, row[40])
                        statement.setObject(9, row[TYPE_INTEGER_KM])
                        statement.setObject(10, row[TYPE_SPEED])
                        statement.setObject(11, row[TYPE_HEIGHT_VALUE])
                        statement.executeUpdate()
                    }
                }
            }
        }
        if (gpsBlob != null && gpsBlob.isNotEmpty()) {
            conn.prepareStatement(
                """INSERT OR REPLACE INTO activity_track_points(
                    activity_id, seq, ts, lat, lon, accuracy_m, speed_mps, gps_source, altitude_m, hdop
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent()
            ).use { statement ->
                Fds.parseGpsRecord(gpsBlob).forEachIndexed { seq, point ->
                    statement.setLong(1, activityId)
                    statement.setInt(2, seq)
                    statement.setLong(3, point.timestamp)
                    statement.setObject(4, point.latitude)
                    statement.setObject(5, point.longitude)
                    statement.setObject(6, point.accuracy)
                    statement.setObject(7, point.speedMps)
                    statement.setObject(8, point.gpsSource)
                    statement.setObject(9, point.altitudeM)
                    statement.setObject(10, point.hdop)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun importRawDir(rawDir: Path, root: Path): Map<String, Int> {
        val directory = rawDir.toRealPath()
        val normalizedRoot = root.toRealPath()
        val rawRef = if (directory.startsWith(normalizedRoot)) normalizedRoot.relativize(directory).toString().replace('\\', '/') else directory.toString()
        var capturedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.ofHours(8)))
        val metaPath = directory.resolve("meta.json")
        if (Files.exists(metaPath)) {
            runCatching { parseObject(Files.readString(metaPath))["fetched_at"].stringOrNull() }
                .getOrNull()?.let { capturedAt = it }
        }
        val counts = linkedMapOf<String, Int>()
        val fitnessDirectory = directory.resolve("fitness")
        if (Files.isDirectory(fitnessDirectory)) {
            Files.list(fitnessDirectory).use { stream ->
                stream.filter(Files::isDirectory).sorted().forEach { keyDir ->
                    val allPath = keyDir.resolve("all.json")
                    if (Files.exists(allPath)) {
                        val items = parseArray(Files.readString(allPath)).map { it.jsonObject }
                        val key = keyDir.fileName.toString()
                        val ref = "$rawRef/fitness/$key/all.json"
                        val timestamps = importFitnessKey(key, items, ref)
                        updateSyncState("fitness", key, timestamps)
                        recordCapture(capturedAt, "fitness", ref, items.size)
                        counts[key] = items.size
                    }
                }
            }
        }
        val sportPath = directory.resolve("sport_records/all.json")
        if (Files.exists(sportPath)) {
            val items = parseArray(Files.readString(sportPath)).map { it.jsonObject }
            val ref = "$rawRef/sport_records/all.json"
            val timestamps = importSportRecords(items, ref)
            updateSyncState("fitness", "sport_records", timestamps)
            recordCapture(capturedAt, "sport_records", ref, items.size)
            counts["sport_records"] = items.size
        }
        var fdsCount = 0
        val fdsRoot = directory.resolve("fds")
        if (Files.isDirectory(fdsRoot)) {
            Files.list(fdsRoot).use { stream ->
                stream.filter(Files::isDirectory).sorted().forEach { fdsDir ->
                    val metaFile = fdsDir.resolve("meta.json")
                    if (!Files.exists(metaFile)) return@forEach
                    val meta = parseObject(Files.readString(metaFile))
                    val sourceId = "${meta["sid"].pythonString()}_${meta["key"].pythonString()}_${meta["report_time"].pythonString()}"
                    val activity = conn.prepareStatement("SELECT id, start_ts FROM activities WHERE source_id=?").use { statement ->
                        statement.setString(1, sourceId)
                        statement.executeQuery().use { result ->
                            if (result.next()) result.getLong("id") to result.getLong("start_ts") else null
                        }
                    } ?: return@forEach
                    val recordPath = fdsDir.resolve("record.bin")
                    val gpsPath = fdsDir.resolve("gps.bin")
                    val record = if (Files.exists(recordPath)) Files.readAllBytes(recordPath) else null
                    val gps = if (Files.exists(gpsPath)) Files.readAllBytes(gpsPath) else null
                    val ref = "$rawRef/fds/${fdsDir.fileName}"
                    importFds(activity.first, activity.second, record, gps, meta["proto_type"].intOrNull() ?: 0, ref)
                    fdsCount++
                    recordCapture(capturedAt, "fds", ref, 1, record?.let(::sha256))
                }
            }
        }
        if (fdsCount > 0) counts["fds"] = fdsCount
        conn.commit()
        return counts
    }

    private fun importSteps(items: List<JsonObject>): List<Long> {
        val timestamps = ArrayList<Long>()
        for (item in items) {
            val value = valueOf(item)
            val ts = itemTimestamp(item, "steps") ?: continue
            val fields = listOf(
                "minute_ts" to ts - ts % 60, "ts" to ts, "date_local" to localDate(ts),
                "steps" to (value["steps"].intOrNull() ?: 0), "distance_m" to (value["distance"].doubleOrNull() ?: 0.0),
                "calories_kcal" to (value["calories"].doubleOrNull() ?: 0.0), "device_id" to item["sid"].sqlValue(), "source" to "cloud",
            )
            val columns = fields.joinToString(", ") { it.first }
            val placeholders = fields.joinToString(", ") { "?" }
            conn.prepareStatement("""INSERT INTO step_minutes($columns) VALUES ($placeholders)
                ON CONFLICT(minute_ts, source) DO UPDATE SET ts=excluded.ts,date_local=excluded.date_local,
                steps=excluded.steps,distance_m=excluded.distance_m,calories_kcal=excluded.calories_kcal,device_id=excluded.device_id
                WHERE excluded.steps > step_minutes.steps OR (excluded.steps = step_minutes.steps AND excluded.distance_m > step_minutes.distance_m)""".trimIndent()).use { st ->
                fields.forEachIndexed { i, pair -> st.setObject(i + 1, pair.second) }; st.executeUpdate()
            }
            timestamps += ts
        }
        return timestamps
    }

    private fun importCalories(items: List<JsonObject>): List<Long> {
        val timestamps = ArrayList<Long>()
        for (item in items) {
            val value = valueOf(item)
            val ts = itemTimestamp(item, "calories") ?: continue
            conn.prepareStatement("""INSERT INTO calorie_minutes(minute_ts,ts,date_local,calories_kcal,device_id,source)
                VALUES(?,?,?,?,?,'cloud') ON CONFLICT(minute_ts,source) DO UPDATE SET ts=excluded.ts,date_local=excluded.date_local,
                calories_kcal=excluded.calories_kcal,device_id=excluded.device_id WHERE excluded.calories_kcal > calorie_minutes.calories_kcal""".trimIndent()).use { st ->
                st.setLong(1, ts - ts % 60); st.setLong(2, ts); st.setString(3, localDate(ts)); st.setDouble(4, value["calories"].doubleOrNull() ?: 0.0); st.setObject(5, item["sid"].sqlValue()); st.executeUpdate()
            }
            timestamps += ts
        }
        return timestamps
    }

    private fun importHeartRate(items: List<JsonObject>): List<Long> {
        val timestamps = ArrayList<Long>()
        for (item in items) {
            val value = valueOf(item); val ts = itemTimestamp(item, "heart_rate") ?: continue
            val bpm = value["bpm"].intOrNull() ?: continue
            val sampleType = if ((value["type"].intOrNull() ?: 0) == 1) "active" else "passive"
            conn.prepareStatement("""INSERT INTO heart_rate_samples(ts,date_local,bpm,sample_type,device_id,source) VALUES(?,?,?,?,?,'cloud')
                ON CONFLICT(ts,sample_type,source) DO UPDATE SET bpm=excluded.bpm,date_local=excluded.date_local,device_id=excluded.device_id""".trimIndent()).use { st ->
                st.setLong(1, ts); st.setString(2, localDate(ts)); st.setInt(3, bpm); st.setString(4, sampleType); st.setObject(5, item["sid"].sqlValue()); st.executeUpdate()
            }
            timestamps += ts
        }
        return timestamps
    }

    private fun importRestingHeartRate(items: List<JsonObject>): List<Long> {
        val timestamps = ArrayList<Long>()
        for (item in items) {
            val value = valueOf(item); val ts = itemTimestamp(item, "resting_heart_rate") ?: continue
            val bpm = value["bpm"].intOrNull() ?: continue
            conn.prepareStatement("""INSERT INTO resting_heart_rate(date_local,ts,bpm,source) VALUES(?,?,?,'cloud')
                ON CONFLICT(date_local,source) DO UPDATE SET ts=excluded.ts,bpm=excluded.bpm""".trimIndent()).use { st ->
                st.setString(1, localDate(ts)); st.setLong(2, ts); st.setInt(3, bpm); st.executeUpdate()
            }
            timestamps += ts
        }
        return timestamps
    }

    private fun importSleep(items: List<JsonObject>, rawRef: String?): List<Long> {
        val timestamps = ArrayList<Long>()
        for (item in items) {
            val value = valueOf(item)
            val start = firstTruthy(value["bedtime"], value["device_bedtime"], value["bed_timestamp"])?.longOrNull() ?: continue
            val end = firstTruthy(value["wake_up_time"], value["device_wake_up_time"], value["out_bed_timestamp"], item["time"])?.longOrNull() ?: continue
            val duration = firstTruthy(value["duration"])?.longOrNull() ?: ((end - start) / 60).coerceAtLeast(0)
            val awake = (firstTruthy(value["sleep_awake_duration"], value["awake_duration"])?.longOrNull() ?: 0L).toInt()
            val asleep = (duration - awake).coerceAtLeast(0)
            val sourceId = "${item["sid"].pythonString()}_${firstTruthy(item["time"])?.stringOrNull() ?: end.toString()}"
            val isNap = if ((value["is_nap"].stringOrNull() ?: "false").lowercase() in setOf("true", "1", "yes")) 1 else 0
            upsert("sleep_sessions", listOf(
                "source_id" to sourceId, "date_local" to localDate(end), "start_ts" to start, "end_ts" to end,
                "duration_min" to duration, "asleep_min" to asleep, "awake_min" to awake,
                "awake_count" to value["awake_count"].sqlValue(), "avg_hr" to value["avg_hr"].sqlValue(),
                "min_hr" to value["min_hr"].sqlValue(), "max_hr" to value["max_hr"].sqlValue(),
                "deep_min" to value["sleep_deep_duration"].sqlValue(), "light_min" to value["sleep_light_duration"].sqlValue(),
                "rem_min" to value["sleep_rem_duration"].sqlValue(), "score" to value["score"].sqlValue(),
                "is_nap" to isNap, "algorithm_version" to value["sleep_algorithm_version"].sqlValue(), "raw_ref" to rawRef,
            ), "source_id")
            val sessionId = conn.prepareStatement("SELECT id FROM sleep_sessions WHERE source_id=?").use { st ->
                st.setString(1, sourceId); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("Inserted sleep session missing") }
            }
            conn.prepareStatement("DELETE FROM sleep_stages WHERE session_id=?").use { st -> st.setLong(1, sessionId); st.executeUpdate() }
            (value["items"] as? JsonArray ?: JsonArray(emptyList())).forEachIndexed { seq, segmentElement ->
                val segment = segmentElement as? JsonObject ?: return@forEachIndexed
                val segStart = segment["start_time"].longOrNull()?.takeIf { it != 0L } ?: return@forEachIndexed
                val segEnd = segment["end_time"].longOrNull()?.takeIf { it != 0L } ?: return@forEachIndexed
                val stage = when (segment["state"].intOrNull() ?: 0) { 2 -> "deep"; 3 -> "light"; 4 -> "rem"; 5 -> "awake"; else -> "unknown" }
                conn.prepareStatement("INSERT INTO sleep_stages(session_id,seq,start_ts,end_ts,stage) VALUES(?,?,?,?,?)").use { st ->
                    st.setLong(1, sessionId); st.setInt(2, seq); st.setLong(3, segStart); st.setLong(4, segEnd); st.setString(5, stage); st.executeUpdate()
                }
            }
            timestamps += end
        }
        return timestamps
    }

    private fun importBodyMeasurements(items: List<JsonObject>, rawRef: String?): List<Long> {
        val timestamps = ArrayList<Long>()
        for (item in items) {
            val value = valueOf(item); val weight = value["weight"].doubleOrNull() ?: continue
            val ts = itemTimestamp(item, "weight") ?: continue
            val sourceId = "${item["sid"].pythonString()}_${item["time"].pythonString()}"
            val extra = value.filterKeys { it !in BODY_CORE_FIELDS }
            upsert("body_measurements", listOf(
                "source_id" to sourceId, "ts" to ts, "date_local" to localDate(ts), "device_id" to item["sid"].sqlValue(),
                "weight_kg" to weight, "bmi" to value["bmi"].sqlValue(), "body_fat_pct" to value["body_fat_rate"].sqlValue(),
                "muscle_mass_kg" to value["muscle_mass"].sqlValue(), "muscle_rate_pct" to value["muscle_rate"].sqlValue(),
                "skeletal_muscle_mass_kg" to value["skeletal_muscle_mass"].sqlValue(), "body_moisture_mass_kg" to value["body_moisture_mass"].sqlValue(),
                "moisture_rate_pct" to value["moisture_rate"].sqlValue(), "bone_mass_kg" to value["bone_mass"].sqlValue(),
                "protein_mass_kg" to value["protein_mass"].sqlValue(), "protein_rate_pct" to value["protein_rate"].sqlValue(),
                "fat_mass_kg" to value["fat_mass"].sqlValue(), "fat_free_body_kg" to value["fat_free_body"].sqlValue(),
                "visceral_fat" to value["visceral_fat"].sqlValue(), "basal_metabolism_kcal" to value["basal_metabolism"].sqlValue(),
                "body_age" to value["body_age"].sqlValue(), "body_score" to value["body_score"].sqlValue(), "whr" to value["whr"].sqlValue(),
                "standard_weight_kg" to (value["standard_weight_v2"].takeUnless { it == null || it == kotlinx.serialization.json.JsonNull } ?: value["standard_weight"]).sqlValue(),
                "extra_json" to extra.takeIf { it.isNotEmpty() }?.let(::jsonText), "raw_ref" to rawRef,
            ), "source_id")
            timestamps += ts
        }
        return timestamps
    }

    private fun firstTruthy(vararg values: JsonElement?): JsonElement? =
        values.firstOrNull { element ->
            when (element) {
                null -> false
                is JsonPrimitive -> if (element.isString) element.content.isNotEmpty() else element.content.toDoubleOrNull()?.let { it != 0.0 } ?: (element.content == "true")
                else -> true
            }
        }

    private fun itemTimestamp(item: JsonObject, key: String): Long? {
        val value = valueOf(item)
        val candidate = when (key) {
            "resting_heart_rate" -> firstTruthy(value["date_time"], item["time"])
            "sleep" -> firstTruthy(value["wake_up_time"], item["time"])
            else -> firstTruthy(item["time"], value["time"])
        }
        return candidate.longOrNull()
    }

    private fun valueOf(item: JsonObject): JsonObject {
        val element = item["value"] ?: return JsonObject(emptyMap())
        if (element is JsonObject) return element
        val raw = element.stringOrNull() ?: return JsonObject(emptyMap())
        return runCatching { parseObject(raw) }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun updateSyncState(dataset: String, dataKey: String, timestamps: List<Long>) {
        if (timestamps.isEmpty()) return
        conn.prepareStatement("""INSERT INTO sync_state(dataset,data_key,last_synced_at,coverage_start,coverage_end) VALUES(?,?,?,?,?)
            ON CONFLICT(dataset,data_key) DO UPDATE SET last_synced_at=MAX(COALESCE(sync_state.last_synced_at,0),excluded.last_synced_at),
            coverage_start=MIN(COALESCE(sync_state.coverage_start,excluded.coverage_start),excluded.coverage_start),
            coverage_end=MAX(COALESCE(sync_state.coverage_end,0),excluded.coverage_end)""".trimIndent()).use { st ->
            st.setString(1, dataset); st.setString(2, dataKey); st.setLong(3, timestamps.maxOrNull()!!); st.setLong(4, timestamps.minOrNull()!!); st.setLong(5, timestamps.maxOrNull()!!); st.executeUpdate()
        }
        conn.commit()
    }

    private fun recordCapture(capturedAt: String, dataset: String, path: String, count: Int, sha256: String? = null) {
        conn.prepareStatement("""INSERT INTO raw_captures(captured_at,dataset,path,record_count,sha256) VALUES(?,?,?,?,?)
            ON CONFLICT(dataset,path) DO UPDATE SET captured_at=excluded.captured_at,record_count=excluded.record_count,sha256=excluded.sha256""".trimIndent()).use { st ->
            st.setString(1, capturedAt); st.setString(2, dataset); st.setString(3, path); st.setInt(4, count); st.setString(5, sha256); st.executeUpdate()
        }
    }

    private fun upsert(table: String, fields: List<Pair<String, Any?>>, conflictKey: String) {
        val names = fields.joinToString(",") { it.first }
        val marks = fields.joinToString(",") { "?" }
        val updates = fields.filter { it.first != conflictKey }.joinToString(",") { "${it.first}=excluded.${it.first}" }
        conn.prepareStatement("INSERT INTO $table($names) VALUES($marks) ON CONFLICT($conflictKey) DO UPDATE SET $updates").use { st ->
            fields.forEachIndexed { index, pair -> st.setObject(index + 1, pair.second) }
            st.executeUpdate()
        }
    }

    private fun localDate(timestamp: Long): String = Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.ofHours(8)).toLocalDate().toString()
    private fun parseObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun parseArray(text: String): JsonArray = Json.parseToJsonElement(text) as? JsonArray ?: error("Expected JSON array")
    private fun jsonText(value: Map<String, JsonElement>): String = JsonObject(value).toString()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonElement?.stringOrNull(): String? = when (this) {
        null -> null
        is JsonPrimitive -> if (isString) content else if (content == "null") null else content
        else -> toString()
    }
    private fun JsonElement?.pythonString(): String = this?.stringOrNull() ?: "None"
    private fun JsonElement?.longOrNull(): Long? = stringOrNull()?.toLongOrNull()
    private fun JsonElement?.intOrNull(): Int? = stringOrNull()?.toDoubleOrNull()?.toInt()
    private fun JsonElement?.doubleOrNull(): Double? = stringOrNull()?.toDoubleOrNull()
    private fun JsonElement?.sqlValue(): Any? = when (this) {
        null -> null
        is JsonPrimitive -> when {
            isString -> content
            content == "true" -> 1
            content == "false" -> 0
            content == "null" -> null
            content.toLongOrNull() != null -> content.toLong()
            content.toDoubleOrNull() != null -> content.toDouble()
            else -> content
        }
        else -> toString()
    }

    private companion object {
        val BODY_CORE_FIELDS = setOf("weight", "bmi", "body_fat_rate", "muscle_mass", "muscle_rate", "skeletal_muscle_mass", "body_moisture_mass", "moisture_rate", "bone_mass", "protein_mass", "protein_rate", "fat_mass", "fat_free_body", "visceral_fat", "basal_metabolism", "body_age", "body_score", "whr", "standard_weight", "standard_weight_v2")
        val ACTIVITY_CORE_FIELDS = setOf("start_time", "end_time", "duration", "distance", "calories", "total_cal", "steps", "avg_hrm", "max_hrm", "min_hrm", "avg_cadence", "max_cadence", "avg_stride", "avg_speed", "max_speed", "avg_pace", "max_pace", "min_pace", "hrm_warm_up_duration", "hrm_fat_burning_duration", "hrm_aerobic_duration", "hrm_anaerobic_duration", "hrm_extreme_duration", "avg_height", "max_height", "min_height", "vo2max", "train_effect", "anaerobic_train_effect", "training_load", "train_load", "recovery_time", "recover_time", "avg_spo2", "valid_duration", "stroke_count", "avg_stroke_frequency", "max_stroke_freq", "avg_swolf", "best_swolf", "turn_count", "pool_width", "main_posture", "proto_type", "version", "time", "timezone", "sport_type")
        const val TYPE_HR = 5
        const val TYPE_CADENCE = 49
        const val TYPE_DISTANCE = 9
        const val TYPE_CALORIES = 2
        const val TYPE_INTEGER_KM = 6
        const val TYPE_SPEED = 51
        const val TYPE_HEIGHT_VALUE = 87
    }
}
