package top.e404.training.collector

import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val SOURCE_ZONE = ZoneOffset.ofHours(8)
private val HEALTH_DATASETS = listOf("heartRate", "steps", "calories", "restingHeartRate", "sleep", "body", "activity")

/** Reads the existing SQLite source database and durably stages health records for publication. */
object SourceRecords {
    fun enqueueSourceRecords(
        conn: Connection,
        state: CollectorState,
        startDate: String? = null,
        endDate: String? = null,
        overlapSeconds: Long = 86_400,
    ): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        val maxSeen = mutableMapOf<String, Long>()
        val lowerDate = startDate?.let { LocalDate.parse(it).atStartOfDay(SOURCE_ZONE).toEpochSecond() }
        val upperDate = endDate?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(SOURCE_ZONE).toEpochSecond() }
        for (dataset in HEALTH_DATASETS) {
            val watermark = state.watermark(dataset, "source_watermark")
            val lowerBound = lowerDate ?: if (watermark != 0L) maxOf(0L, watermark - overlapSeconds) else 0L
            var count = 0
            for (entry in entries(conn, dataset, lowerBound)) {
                val measuredSeconds = entry.measuredAt / 1000
                if (upperDate != null && measuredSeconds >= upperDate) continue
                if (state.sourceRecordChanged(dataset, entry.sourceId, entry.localDate, entry.measuredAt, entry.payload, force = startDate != null)) count++
                maxSeen[dataset] = maxOf(maxSeen[dataset] ?: 0L, measuredSeconds)
            }
            counts[dataset] = count
        }
        state.commitEnqueue()
        for (dataset in HEALTH_DATASETS) maxSeen[dataset]?.let { state.setSourceWatermark(dataset, it) }
        return counts
    }

    fun enqueueRecentWorkoutHeartRate(conn: Connection, state: CollectorState, overlapSeconds: Long = 3600): Int {
        val key = "workoutHeartRateLate"
        val watermark = state.watermark(key, "source_watermark")
        val floor = maxOf(0L, watermark - overlapSeconds)
        var count = 0
        var newest = watermark
        conn.prepareStatement("SELECT id,fds_collected_at FROM activities WHERE fds_collected=1 AND fds_collected_at>=? ORDER BY fds_collected_at DESC,id DESC").use { query ->
            query.setLong(1, floor)
            query.executeQuery().use { rs ->
                while (rs.next()) {
                    val activityId = rs.getLong("id")
                    newest = maxOf(newest, rs.getLong("fds_collected_at"))
                    for (entry in workoutHeartRateEntries(conn, listOf(activityId))) {
                        if (state.sourceRecordChanged("heartRate", entry.sourceId, entry.localDate, entry.measuredAt, entry.payload)) count++
                    }
                }
            }
        }
        state.commitEnqueue()
        if (newest > watermark) state.setSourceWatermark(key, newest)
        return count
    }

    fun enqueueWorkoutHeartRateBackfill(conn: Connection, state: CollectorState, activityLimit: Int = 100): Pair<Int, Boolean> {
        val key = "workoutHeartRateBackfill"
        var cursor = state.watermark(key, "source_watermark")
        var status = state.watermark(key, "submitted_watermark")
        if (status == 1L) return 0 to true
        if (status == 0L) {
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT COALESCE(MAX(id),0) FROM activities").use { rs -> rs.next(); cursor = rs.getLong(1) + 1 }
            }
            state.setBackfillProgress(key, cursor, 2)
            status = 2
        }
        val ids = mutableListOf<Long>()
        conn.prepareStatement("SELECT id FROM activities WHERE id<? ORDER BY id DESC LIMIT ?").use { query ->
            query.setLong(1, cursor)
            query.setInt(2, activityLimit)
            query.executeQuery().use { rs -> while (rs.next()) ids += rs.getLong(1) }
        }
        var count = 0
        for (entry in workoutHeartRateEntries(conn, ids)) {
            if (state.sourceRecordChanged("heartRate", entry.sourceId, entry.localDate, entry.measuredAt, entry.payload)) count++
        }
        state.commitEnqueue()
        if (ids.isNotEmpty()) {
            state.setBackfillProgress(key, ids.last(), 2)
            return count to false
        }
        state.setBackfillProgress(key, 0, 1)
        return count to true
    }

    private fun entries(conn: Connection, dataset: String, minSeconds: Long): Sequence<Record> {
        val minDate = Instant.ofEpochSecond(minSeconds).atZone(SOURCE_ZONE).toLocalDate().toString()
        return when (dataset) {
            "steps" -> conn.query("SELECT * FROM step_minutes WHERE date_local>=? AND ts>=? ORDER BY ts,minute_ts", minDate, minSeconds) { row ->
                val ts = row.getLong("ts")
                Record("${row.getString("source")}:${row.getLong("minute_ts")}", row.getString("date_local"), ts * 1000,
                    buildJsonObject { put("steps", row.jsonValue("steps")); put("distanceMeters", row.jsonValue("distance_m")); put("caloriesKcal", row.jsonValue("calories_kcal")); put("deviceId", row.jsonValue("device_id")); put("source", row.jsonValue("source")) })
            }
            "calories" -> conn.query("SELECT * FROM calorie_minutes WHERE date_local>=? AND ts>=? ORDER BY ts,minute_ts", minDate, minSeconds) { row ->
                val ts = row.getLong("ts")
                Record("${row.getString("source")}:${row.getLong("minute_ts")}", row.getString("date_local"), ts * 1000,
                    buildJsonObject { put("caloriesKcal", row.jsonValue("calories_kcal")); put("deviceId", row.jsonValue("device_id")); put("source", row.jsonValue("source")) })
            }
            "heartRate" -> conn.query("SELECT * FROM heart_rate_samples WHERE date_local>=? AND ts>=? ORDER BY ts,sample_type", minDate, minSeconds) { row ->
                val ts = row.getLong("ts")
                Record("${row.getString("source")}:${row.getString("sample_type")}:$ts", row.getString("date_local"), ts * 1000,
                    buildJsonObject { put("bpm", row.jsonValue("bpm")); put("sampleType", row.jsonValue("sample_type")); put("deviceId", row.jsonValue("device_id")); put("source", row.jsonValue("source")) })
            }
            "restingHeartRate" -> conn.query("SELECT * FROM resting_heart_rate WHERE date_local>=? AND ts>=? ORDER BY ts,date_local", minDate, minSeconds) { row ->
                val ts = row.getLong("ts")
                Record("${row.getString("source")}:${row.getString("date_local")}", row.getString("date_local"), ts * 1000,
                    buildJsonObject { put("bpm", row.jsonValue("bpm")); put("source", row.jsonValue("source")) })
            }
            "sleep" -> conn.query("SELECT * FROM sleep_sessions WHERE date_local>=? AND end_ts>=? ORDER BY end_ts,source_id", minDate, minSeconds) { row ->
                val id = row.getLong("id")
                val stages = conn.query("SELECT start_ts,end_ts,stage FROM sleep_stages WHERE session_id=? ORDER BY seq", id) { stage ->
                    buildJsonObject { put("startAt", stage.getLong("start_ts") * 1000); put("endAt", stage.getLong("end_ts") * 1000); put("stage", stage.getString("stage")) }
                }.toList()
                val end = row.getLong("end_ts")
                Record(row.getString("source_id"), row.getString("date_local"), end * 1000, buildJsonObject {
                    put("startAt", row.getLong("start_ts") * 1000); put("endAt", end * 1000)
                    put("durationMinutes", row.jsonValue("duration_min")); put("asleepMinutes", row.jsonValue("asleep_min")); put("awakeMinutes", row.jsonValue("awake_min")); put("awakeCount", row.jsonValue("awake_count"))
                    put("avgHeartRate", row.jsonValue("avg_hr")); put("minHeartRate", row.jsonValue("min_hr")); put("maxHeartRate", row.jsonValue("max_hr")); put("deepMinutes", row.jsonValue("deep_min")); put("lightMinutes", row.jsonValue("light_min")); put("remMinutes", row.jsonValue("rem_min")); put("score", row.jsonValue("score")); put("isNap", row.getInt("is_nap") != 0); put("algorithmVersion", row.jsonValue("algorithm_version")); put("stages", JsonArray(stages))
                })
            }
            "body" -> conn.query("SELECT * FROM body_measurements WHERE date_local>=? AND ts>=? ORDER BY ts,source_id", minDate, minSeconds) { row ->
                val ts = row.getLong("ts")
                Record(row.getString("source_id"), row.getString("date_local"), ts * 1000, buildJsonObject {
                    put("weightKg", row.jsonValue("weight_kg")); put("bmi", row.jsonValue("bmi")); put("bodyFatPct", row.jsonValue("body_fat_pct")); put("muscleMassKg", row.jsonValue("muscle_mass_kg")); put("muscleRatePct", row.jsonValue("muscle_rate_pct")); put("skeletalMuscleMassKg", row.jsonValue("skeletal_muscle_mass_kg")); put("bodyMoistureMassKg", row.jsonValue("body_moisture_mass_kg")); put("moistureRatePct", row.jsonValue("moisture_rate_pct")); put("boneMassKg", row.jsonValue("bone_mass_kg")); put("proteinMassKg", row.jsonValue("protein_mass_kg")); put("proteinRatePct", row.jsonValue("protein_rate_pct")); put("fatMassKg", row.jsonValue("fat_mass_kg")); put("fatFreeBodyKg", row.jsonValue("fat_free_body_kg")); put("visceralFat", row.jsonValue("visceral_fat")); put("basalMetabolismKcal", row.jsonValue("basal_metabolism_kcal")); put("bodyAge", row.jsonValue("body_age")); put("bodyScore", row.jsonValue("body_score")); put("whr", row.jsonValue("whr")); put("standardWeightKg", row.jsonValue("standard_weight_kg")); put("extra", row.jsonObject("extra_json")); put("deviceId", row.jsonValue("device_id"))
                })
            }
            "activity" -> conn.query("SELECT * FROM activities WHERE start_ts>=? ORDER BY start_ts,source_id", minSeconds) { row ->
                val ts = row.getLong("start_ts")
                Record(row.getString("source_id"), row.getString("date_local"), ts * 1000, buildJsonObject {
                    put("sportKey", row.jsonValue("sport_key")); put("sportCategory", row.jsonValue("sport_category")); put("sportTypeCode", row.jsonValue("sport_type_code")); put("startAt", ts * 1000); put("endAt", row.getLong("end_ts") * 1000)
                    put("durationSeconds", row.jsonValue("duration_s")); put("distanceMeters", row.jsonValue("distance_m")); put("caloriesKcal", row.jsonValue("calories_kcal")); put("steps", row.jsonValue("steps")); put("avgHeartRate", row.jsonValue("avg_hr")); put("maxHeartRate", row.jsonValue("max_hr")); put("minHeartRate", row.jsonValue("min_hr")); put("avgCadence", row.jsonValue("avg_cadence")); put("maxCadence", row.jsonValue("max_cadence")); put("avgStrideCm", row.jsonValue("avg_stride_cm")); put("avgSpeedMps", row.jsonValue("avg_speed_mps")); put("maxSpeedMps", row.jsonValue("max_speed_mps")); put("avgPaceSecondsPerKm", row.jsonValue("avg_pace_s_per_km")); put("maxPaceSecondsPerKm", row.jsonValue("max_pace_s_per_km")); put("minPaceSecondsPerKm", row.jsonValue("min_pace_s_per_km")); put("hrWarmUpSeconds", row.jsonValue("hr_warm_up_s")); put("hrFatBurningSeconds", row.jsonValue("hr_fat_burning_s")); put("hrAerobicSeconds", row.jsonValue("hr_aerobic_s")); put("hrAnaerobicSeconds", row.jsonValue("hr_anaerobic_s")); put("hrExtremeSeconds", row.jsonValue("hr_extreme_s")); put("avgHeightMeters", row.jsonValue("avg_height_m")); put("maxHeightMeters", row.jsonValue("max_height_m")); put("minHeightMeters", row.jsonValue("min_height_m")); put("vo2max", row.jsonValue("vo2max")); put("trainEffect", row.jsonValue("train_effect")); put("anaerobicTrainEffect", row.jsonValue("anaerobic_train_effect")); put("trainingLoad", row.jsonValue("training_load")); put("recoveryTime", row.jsonValue("recovery_time")); put("avgSpo2", row.jsonValue("avg_spo2")); put("validDurationSeconds", row.jsonValue("valid_duration_s")); put("strokeCount", row.jsonValue("stroke_count")); put("avgStrokeFrequency", row.jsonValue("avg_stroke_frequency")); put("maxStrokeFrequency", row.jsonValue("max_stroke_freq")); put("avgSwolf", row.jsonValue("avg_swolf")); put("bestSwolf", row.jsonValue("best_swolf")); put("turnCount", row.jsonValue("turn_count")); put("poolWidth", row.jsonValue("pool_width")); put("mainPosture", row.jsonValue("main_posture")); put("totalCaloriesKcal", row.jsonValue("total_calories_kcal")); put("avgPaceSecondsPer100m", row.jsonValue("avg_pace_s_per_100m")); put("maxPaceSecondsPer100m", row.jsonValue("max_pace_s_per_100m")); put("minPaceSecondsPer100m", row.jsonValue("min_pace_s_per_100m")); put("extra", row.jsonObject("extra_json"))
                })
            }
            else -> throw IllegalArgumentException("unknown health dataset: $dataset")
        }
    }

    private fun workoutHeartRateEntries(conn: Connection, ids: Iterable<Long>): Sequence<Record> = sequence {
        for (activityId in ids) {
            val sourceId = conn.prepareStatement("SELECT source_id FROM activities WHERE id=?").use { query ->
                query.setLong(1, activityId)
                query.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            } ?: continue
            for (entry in conn.query("SELECT offset_s,ts,hr FROM activity_samples WHERE activity_id=? AND hr>0 ORDER BY offset_s", activityId) { row ->
                val ts = row.getLong("ts")
                Record("workout:$sourceId:${row.getInt("offset_s")}", Instant.ofEpochSecond(ts).atZone(SOURCE_ZONE).toLocalDate().toString(), ts * 1000,
                    buildJsonObject { put("bpm", row.getInt("hr")); put("sampleType", "active"); put("source", "xiaomi_workout") })
            }) yield(entry)
        }
    }

    private fun <T> Connection.query(sql: String, vararg args: Any, transform: (java.sql.ResultSet) -> T): Sequence<T> = sequence {
        prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                while (rs.next()) yield(transform(rs))
            }
        }
    }

    private fun java.sql.ResultSet.jsonValue(name: String): JsonElement = when (val value = getObject(name)) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun java.sql.ResultSet.jsonObject(name: String): JsonElement {
        val value = getString(name) ?: return JsonNull
        if (value.isEmpty()) return JsonNull
        return Json.parseToJsonElement(value).jsonObject
    }

    private data class Record(val sourceId: String, val localDate: String, val measuredAt: Long, val payload: JsonObject)
}
