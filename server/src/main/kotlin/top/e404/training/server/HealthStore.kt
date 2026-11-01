package top.e404.training.server

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import top.e404.training.shared.*
import java.sql.Connection
import java.time.LocalDate

/** Health rows share Store's SQLite connection and are always called while Store owns its monitor. */
internal class HealthStore(private val db: Connection) {
    private val pageSize = 500


    fun validateImport(request: HealthImportRequest) {
        val seen = HashSet<String>()
        request.records.forEach {
            require(seen.add(it.sourceId)) { "健康批次包含重复来源标识" }
            validate(request.dataset, it)
        }
    }

    /** Caller owns the shared Store transaction. */
    fun saveImport(request: HealthImportRequest): HealthImportResult {
        val changedDates = linkedSetOf<String>()
        val touchedDates = linkedSetOf<String>()
        var accepted = 0
        var unchanged = 0
        db.prepareStatement("SELECT local_date,measured_at,payload FROM health_records WHERE dataset=? AND source_id=?").use { select ->
            db.prepareStatement("INSERT INTO health_records(dataset,source_id,local_date,measured_at,payload) VALUES(?,?,?,?,?) ON CONFLICT(dataset,source_id) DO UPDATE SET local_date=excluded.local_date,measured_at=excluded.measured_at,payload=excluded.payload").use { upsert ->
                request.records.forEach { record ->
                    select.setString(1, request.dataset.name); select.setString(2, record.sourceId)
                    val old = select.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getLong(2), rs.getString(3)) else null }
                    touchedDates += record.localDate
                    if (old != null && old.first == record.localDate && old.second == record.measuredAt && samePayload(old.third, record.payload)) unchanged++
                    else {
                        upsert.setString(1, request.dataset.name); upsert.setString(2, record.sourceId)
                        upsert.setString(3, record.localDate); upsert.setLong(4, record.measuredAt)
                        upsert.setString(5, wireJson.encodeToString(record.payload)); upsert.executeUpdate()
                        old?.first?.let { changedDates += it; touchedDates += it }
                        changedDates += record.localDate; touchedDates += record.localDate; accepted++
                    }
                }
            }
        }
        db.prepareStatement("INSERT INTO health_days(local_date,revision) VALUES(?,1) ON CONFLICT(local_date) DO UPDATE SET revision=revision+1").use { revise ->
            changedDates.forEach { date -> revise.setString(1, date); revise.executeUpdate() }
        }
        val revisions = linkedMapOf<String, Long>()
        db.prepareStatement("SELECT revision FROM health_days WHERE local_date=?").use { selectRevision ->
            touchedDates.forEach { date ->
                selectRevision.setString(1, date)
                selectRevision.executeQuery().use { rs -> if (rs.next()) revisions[date] = rs.getLong(1) }
            }
        }
        return HealthImportResult(accepted, unchanged, revisions)
    }

    private fun validate(dataset: HealthDataset, row: HealthRecord) {
        require(row.sourceId.isNotBlank()) { "健康来源标识不能为空" }
        val date = LocalDate.parse(row.localDate)
        require(date.toString() == row.localDate) { "健康业务日期必须为 YYYY-MM-DD" }
        fun nonnegative(value: Double?, name: String) {
            if (value != null) require(value >= 0 && value.isFinite()) { "${name}必须为非负有限数" }
        }
        val expectedAt = when (dataset) {
            HealthDataset.steps -> {
                val p = row.decodePayload<StepsPayload>()
                require(p.steps >= 0); nonnegative(p.distanceMeters, "distanceMeters"); nonnegative(p.caloriesKcal, "caloriesKcal")
                row.measuredAt
            }
            HealthDataset.calories -> {
                val p = row.decodePayload<CaloriesPayload>(); nonnegative(p.caloriesKcal, "caloriesKcal"); row.measuredAt
            }
            HealthDataset.heartRate -> {
                val p = row.decodePayload<HeartRatePayload>()
                require(p.bpm > 0 && p.sampleType in setOf("active", "passive")) { "心率读数或采样类型不正确" }
                row.measuredAt
            }
            HealthDataset.restingHeartRate -> {
                require(row.decodePayload<RestingHeartRatePayload>().bpm > 0) { "静息心率必须大于零" }
                row.measuredAt
            }
            HealthDataset.sleep -> {
                val p = row.decodePayload<SleepPayload>()
                require(p.endAt >= p.startAt && p.durationMinutes >= 0) { "睡眠端点或时长不正确" }
                p.endAt
            }
            HealthDataset.body -> {
                val p = row.decodePayload<BodyPayload>(); require(p.weightKg > 0 && p.weightKg.isFinite()) { "体重必须为正数" }
                row.measuredAt
            }
            HealthDataset.activity -> {
                val p = row.decodePayload<ActivityPayload>()
                require(p.sportKey.isNotBlank() && p.endAt >= p.startAt) { "运动摘要标识或端点不正确" }
                nonnegative(p.distanceMeters, "distanceMeters"); nonnegative(p.durationSeconds?.toDouble(), "durationSeconds")
                p.startAt
            }
        }
        require(expectedAt == row.measuredAt) { "${dataset.name}测量时间与payload不一致" }
        require(LocalDate.ofInstant(java.time.Instant.ofEpochMilli(expectedAt), java.time.ZoneOffset.ofHours(8)) == date) {
            "健康日期必须匹配对应测量时间的UTC+8日期"
        }
    }

    fun status(): HealthStatus {
        val revisions = linkedMapOf<String, Long>()
        db.createStatement().use { st -> st.executeQuery("SELECT local_date,revision FROM health_days ORDER BY local_date").use { rs -> while (rs.next()) revisions[rs.getString(1)] = rs.getLong(2) } }
        val state = db.createStatement().use { st ->
            st.executeQuery("SELECT data FROM health_collection_state WHERE id=1").use { rs ->
                check(rs.next()) { "缺少健康采集状态记录" }
                wireJson.decodeFromString<HealthCollectionState>(rs.getString(1))
            }
        }
        return HealthStatus(revisions.keys.toList(), revisions, state)
    }

    fun days(from: LocalDate, to: LocalDate): HealthDaysResponse {
        require(!to.isBefore(from)) { "日期范围结束不能早于开始" }
        val revisions = db.prepareStatement("SELECT local_date,revision FROM health_days WHERE local_date BETWEEN ? AND ? ORDER BY local_date").use { st ->
            st.setString(1, from.toString()); st.setString(2, to.toString()); st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) } }
        }
        val rows = revisions.map { (date, rev) ->
            val result = db.prepareStatement("SELECT dataset,COUNT(*),SUM(CASE WHEN dataset='steps' THEN CAST(json_extract(payload,'$.steps') AS INTEGER) END),SUM(CASE WHEN dataset='steps' THEN json_extract(payload,'$.distanceMeters') END),SUM(CASE WHEN dataset='steps' THEN json_extract(payload,'$.caloriesKcal') END),SUM(CASE WHEN dataset='calories' THEN json_extract(payload,'$.caloriesKcal') END),MAX(CASE WHEN dataset='restingHeartRate' THEN json_extract(payload,'$.bpm') END) FROM health_records WHERE local_date=? GROUP BY dataset").use { st ->
                st.setString(1, date); st.executeQuery().use { rs ->
                    var steps: Long? = null; var distance: Double? = null; var stepCal: Double? = null; var cal: Double? = null; var hr: Int? = null; var sleep = 0; var body = 0; var activities = 0; var heart = 0
                    while (rs.next()) when (HealthDataset.valueOf(rs.getString(1))) {
                        HealthDataset.steps -> { steps = rs.getLong(3).takeUnless { rs.wasNull() }; distance = rs.getDouble(4).takeUnless { rs.wasNull() }; stepCal = rs.getDouble(5).takeUnless { rs.wasNull() } }
                        HealthDataset.calories -> cal = rs.getDouble(6).takeUnless { rs.wasNull() }
                        HealthDataset.heartRate -> heart = rs.getInt(2)
                        HealthDataset.restingHeartRate -> hr = rs.getInt(7).takeUnless { rs.wasNull() }
                        HealthDataset.sleep -> sleep = rs.getInt(2)
                        HealthDataset.body -> body = rs.getInt(2)
                        HealthDataset.activity -> activities = rs.getInt(2)
                    }
                    HealthDaySummary(date, rev, steps, distance, stepCal, cal, heart.takeIf { it > 0 }, hr, sleep.takeIf { it > 0 }, body.takeIf { it > 0 }, activities.takeIf { it > 0 })
                }
            }
            date to result
        }.toMap()
        return HealthDaysResponse(rows.values.toList(), revisions)
    }

    fun records(date: LocalDate, dataset: HealthDataset, cursor: String?): HealthRecordsPage {
        val revision = revision(date.toString()) ?: 0L
        val offset = cursor?.toIntOrNull() ?: 0
        require(offset >= 0) { "分页游标不正确" }
        val rows = db.prepareStatement("SELECT source_id,local_date,measured_at,payload FROM health_records WHERE dataset=? AND local_date=? ORDER BY measured_at,source_id LIMIT ? OFFSET ?").use { st ->
            st.setString(1, dataset.name); st.setString(2, date.toString()); st.setInt(3, pageSize + 1); st.setInt(4, offset)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(HealthRecord(rs.getString(1), rs.getString(2), rs.getLong(3), wireJson.decodeFromString<JsonObject>(rs.getString(4)))) } }
        }
        val hasMore = rows.size > pageSize
        return HealthRecordsPage(date.toString(), dataset, revision, rows.take(pageSize), if (hasMore) (offset + pageSize).toString() else null)
    }

    fun requestCollection(): HealthCollectionRequestResult {
        val current = state()
        val merge = current.requested || current.stage in setOf("running", "submitted")
        val next = if (merge) current else current.copy(requested = true, stage = "requested", requestedAt = System.currentTimeMillis(), error = null)
        saveState(next)
        return HealthCollectionRequestResult(!merge, next)
    }

    fun updateCollection(request: HealthCollectionStateRequest): HealthCollectionState {
        require(request.event in setOf("started", "submitted", "published", "succeeded", "failed")) { "采集阶段不正确" }
        val now = System.currentTimeMillis(); val old = state()
        val next = when (request.event) {
            "started" -> old.copy(requested = false, stage = "running", phase = request.phase, startedAt = now, error = null)
            "submitted" -> old.copy(stage = "submitted", phase = request.phase, submittedAt = now, error = null)
            "published" -> old.copy(requested = false, stage = "published", phase = request.phase, submittedAt = now, failedAt = null, error = null)
            "succeeded" -> old.copy(requested = false, stage = "succeeded", phase = request.phase, submittedAt = old.submittedAt ?: now, lastSuccessAt = now, failedAt = null, error = null)
            else -> old.copy(requested = false, stage = "failed", phase = request.phase, failedAt = now, error = request.error ?: "采集失败")
        }
        saveState(next); return next
    }

    fun allRecords(): Sequence<HealthBackupEntry> = sequence {
        db.createStatement().use { st ->
            st.executeQuery("SELECT dataset,source_id,local_date,measured_at,payload FROM health_records ORDER BY local_date,dataset,measured_at,source_id").use { rs ->
                while (rs.next()) yield(HealthBackupEntry(HealthDataset.valueOf(rs.getString(1)), HealthRecord(rs.getString(2), rs.getString(3), rs.getLong(4), wireJson.decodeFromString(rs.getString(5)))))
            }
        }
    }

    fun manifest(scope: String): HealthBackupManifest {
        require(scope in setOf("complete", "cache"))
        val count = db.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM health_records").use { rs -> rs.next(); rs.getLong(1) } }
        val dates = db.createStatement().use { it.executeQuery("SELECT local_date FROM health_days ORDER BY local_date").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        val datasets = db.createStatement().use { it.executeQuery("SELECT DISTINCT dataset FROM health_records ORDER BY dataset").use { rs -> buildList { while (rs.next()) add(HealthDataset.valueOf(rs.getString(1))) } } }
        return HealthBackupManifest(scope, dates, datasets, count)
    }

    fun validateEntries(entries: Sequence<HealthBackupEntry>, replace: Boolean): Long {
        var count = 0L
        db.prepareStatement("SELECT local_date,measured_at,payload FROM health_records WHERE dataset=? AND source_id=?").use { select ->
            entries.forEach { entry ->
                validate(entry.dataset, entry.record)
                select.setString(1, entry.dataset.name); select.setString(2, entry.record.sourceId)
                val old = select.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getLong(2), rs.getString(3)) else null }
                if (old != null && (old.first != entry.record.localDate || old.second != entry.record.measuredAt || !samePayload(old.third, entry.record.payload)) && !replace)
                    throw ImportConflict("健康来源内容不同：${entry.record.sourceId}；需明确选择替换")
                count++
            }
        }
        return count
    }


    /** Caller owns the shared SQLite transaction; entries must be a reiterable stream. */
    fun saveEntries(entries: Sequence<HealthBackupEntry>, replace: Boolean) {
        val changedDates = linkedSetOf<String>()
        db.prepareStatement("SELECT local_date,measured_at,payload FROM health_records WHERE dataset=? AND source_id=?").use { select ->
            db.prepareStatement("INSERT INTO health_records(dataset,source_id,local_date,measured_at,payload) VALUES(?,?,?,?,?) ON CONFLICT(dataset,source_id) DO UPDATE SET local_date=excluded.local_date,measured_at=excluded.measured_at,payload=excluded.payload").use { upsert ->
                entries.forEach { entry ->
                    val row = entry.record
                    select.setString(1, entry.dataset.name); select.setString(2, row.sourceId)
                    val old = select.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getLong(2), rs.getString(3)) else null }
                    val same = old != null && old.first == row.localDate && old.second == row.measuredAt && samePayload(old.third, row.payload)
                    if (old != null && !same && !replace) throw ImportConflict("健康来源内容不同：${row.sourceId}；需明确选择替换")
                    if (!same) {
                        upsert.setString(1, entry.dataset.name); upsert.setString(2, row.sourceId)
                        upsert.setString(3, row.localDate); upsert.setLong(4, row.measuredAt)
                        upsert.setString(5, wireJson.encodeToString(row.payload)); upsert.executeUpdate()
                        old?.first?.let(changedDates::add); changedDates += row.localDate
                    }
                }
            }
        }
        db.prepareStatement("INSERT INTO health_days(local_date,revision) VALUES(?,1) ON CONFLICT(local_date) DO UPDATE SET revision=revision+1").use { revise ->
            changedDates.forEach { date -> revise.setString(1, date); revise.executeUpdate() }
        }
    }

    private fun samePayload(stored: String, incoming: JsonObject): Boolean =
        wireJson.parseToJsonElement(stored) == incoming
    private fun revision(date: String): Long? = db.prepareStatement("SELECT revision FROM health_days WHERE local_date=?").use { st -> st.setString(1, date); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null } }
    private fun state(): HealthCollectionState = db.createStatement().use { st ->
        st.executeQuery("SELECT data FROM health_collection_state WHERE id=1").use { rs ->
            check(rs.next()) { "缺少健康采集状态记录" }
            wireJson.decodeFromString<HealthCollectionState>(rs.getString(1))
        }
    }
    private fun saveState(value: HealthCollectionState) { db.prepareStatement("UPDATE health_collection_state SET data=? WHERE id=1").use { it.setString(1, wireJson.encodeToString(value)); it.executeUpdate() } }
}
