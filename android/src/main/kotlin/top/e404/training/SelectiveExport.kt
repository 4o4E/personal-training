package top.e404.training

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.e404.training.shared.ActivityRecord
import top.e404.training.shared.HealthDataset
import top.e404.training.shared.HealthDaySummary
import top.e404.training.shared.HealthRecord
import top.e404.training.shared.Kind
import top.e404.training.shared.Load
import top.e404.training.shared.Session
import top.e404.training.shared.SetSegment
import top.e404.training.shared.isXiaomiAutoProjection
import top.e404.training.shared.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.e404.training.shared.wireJson
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class SelectiveExportItem(val label: String) {
    training("训练记录"), activities("自定义活动"), steps("步数"), calories("热量"), heartRate("心率"),
    restingHeartRate("静息心率"), sleep("睡眠"), body("身体测量"), healthActivities("健康活动");

    val dataset: HealthDataset? get() = when (this) {
        steps -> HealthDataset.steps
        calories -> HealthDataset.calories
        heartRate -> HealthDataset.heartRate
        restingHeartRate -> HealthDataset.restingHeartRate
        sleep -> HealthDataset.sleep
        body -> HealthDataset.body
        healthActivities -> HealthDataset.activity
        else -> null
    }
}

@Serializable
internal data class SelectiveExportManifest(
    val formatVersion: Int = 1,
    val dateFrom: String,
    val dateTo: String,
    val timeZone: String = "Asia/Shanghai",
    val selections: List<String>,
    val health: List<SelectiveHealthSource> = emptyList()
)

@Serializable
internal data class SelectiveHealthSource(
    val date: String,
    val dataset: String,
    val source: String,
    val complete: Boolean,
    val recordCount: Int
)

@Serializable
internal data class SelectiveSetResult(
    val setIndex: Int,
    val reps: Int?,
    val seconds: Int?,
    val load: Load?,
    val side: String,
    val completedAt: Long?,
    val segments: List<SetSegment>
)

@Serializable
internal data class SelectivePerformedAction(
    val name: String,
    val notes: String,
    val sets: List<SelectiveSetResult>
)

@Serializable
internal data class SelectiveSession(
    val id: String,
    val name: String,
    val localDate: String,
    val startedAt: Long?,
    val endedAt: Long?,
    val status: String,
    val actions: List<SelectivePerformedAction>,
    val notes: String
)

internal fun Session.toSelectiveExportRecord(): SelectiveSession? {
    val resultsByItem = results.groupBy { it.itemId }
    val actualActions = items.mapNotNull { item ->
        val completed = resultsByItem[item.id].orEmpty().sortedBy { it.setIndex }
        if (completed.isEmpty()) return@mapNotNull null
        val name = exercises.singleOrNull { it.id == item.exerciseId }?.name ?: "未知动作"
        SelectivePerformedAction(
            name = name,
            notes = item.notes,
            sets = completed.map { result ->
                SelectiveSetResult(result.setIndex, result.reps, result.seconds, result.load,
                    result.side, result.completedAt, result.segments)
            }
        )
    }
    if (actualActions.isEmpty()) return null
    return SelectiveSession(id, name, localDate, startedAt, endedAt, status, actualActions, notes)
}

@Serializable
internal data class SelectiveHealthDay(
    val date: String,
    val dataset: String,
    val revision: Long?,
    val summary: JsonObject? = null,
    val records: List<HealthRecord> = emptyList()
)

internal data class SelectiveHealthResult(val row: SelectiveHealthDay, val source: SelectiveHealthSource)

internal data class SelectiveExportSummary(val online: Int, val missing: Int, val cached: Int, val partial: Int)

internal fun validateSelectiveExportRange(from: LocalDate, to: LocalDate, selected: Set<SelectiveExportItem>) {
    require(!to.isBefore(from)) { "结束日期不能早于开始日期" }
    require(selected.isNotEmpty()) { "请至少选择一类资料" }
}

internal fun LocalDate.isInExportRange(from: LocalDate, to: LocalDate): Boolean = this >= from && this <= to
internal fun ActivityRecord.isSelectiveCustomActivity(): Boolean = !isXiaomiAutoProjection()

internal fun HealthDaySummary.toSelectiveHealthSummary(dataset: HealthDataset): JsonObject? {
    val values = buildJsonObject {
        when (dataset) {
            HealthDataset.steps -> {
                steps?.let { put("steps", it) }
                distanceMeters?.let { put("distanceMeters", it) }
            }
            HealthDataset.calories -> {
                stepCaloriesKcal?.let { put("stepCaloriesKcal", it) }
                calorieMinutesKcal?.let { put("calorieMinutesKcal", it) }
            }
            HealthDataset.heartRate -> heartRateSamples?.let { put("heartRateSamples", it) }
            HealthDataset.restingHeartRate -> restingHeartRateBpm?.let { put("restingHeartRateBpm", it) }
            HealthDataset.sleep -> sleepSessions?.let { put("sleepSessions", it) }
            HealthDataset.body -> bodyMeasurements?.let { put("bodyMeasurements", it) }
            HealthDataset.activity -> activities?.let { put("activities", it) }
        }
    }
    return values.takeIf { it.isNotEmpty() }
}

private fun SelectiveHealthSource(date: LocalDate, dataset: HealthDataset, source: String, complete: Boolean, count: Int) =
    SelectiveHealthSource(date.toString(), dataset.name, source, complete, count)

/** Writes only selected actual data; credentials, plans, preferences and media are never read. */
internal suspend fun TrainingRepository.exportSelected(
    from: LocalDate,
    to: LocalDate,
    selected: Set<SelectiveExportItem>,
    database: TrainingDatabase,
    output: OutputStream
): SelectiveExportSummary = withContext(Dispatchers.IO) {
    validateSelectiveExportRange(from, to, selected)
    initialize(triggerStartupSync = false)
    val snapshot = data.value
    val sessions = if (SelectiveExportItem.training in selected) snapshot.records(Kind.session)
        .map { it.value<Session>() }
        .filter { runCatching { LocalDate.parse(it.localDate) }.getOrNull()?.isInExportRange(from, to) == true }
        .mapNotNull { it.toSelectiveExportRecord() }
    else emptyList()
    val activities = if (SelectiveExportItem.activities in selected) snapshot.records(Kind.activity)
        .map { it.value<ActivityRecord>() }
        .filter { it.isSelectiveCustomActivity() }
        .filter { runCatching { LocalDate.parse(it.localDate) }.getOrNull()?.isInExportRange(from, to) == true }
    else emptyList()
    val datasets = SelectiveExportItem.entries.filter { it in selected }.mapNotNull { it.dataset }
    val sources = mutableListOf<SelectiveHealthSource>()
    val dao = database.records()
    val onlineDays = if (datasets.isNotEmpty()) try {
        readExportHealthDays(from, to)
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        null
    } catch (_: ApiProblem) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } else null
    val cachedDays = if (onlineDays == null && datasets.isNotEmpty())
        dao.healthDays(from.toString(), to.toString()).associateBy { it.localDate }
    else emptyMap()

    ZipOutputStream(output).use { zip ->
        fun entry(path: String, content: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        if (SelectiveExportItem.training in selected)
            entry("training/sessions.json", wireJson.encodeToString(sessions))
        if (SelectiveExportItem.activities in selected)
            entry("training/activities.json", wireJson.encodeToString(activities))

        for (dataset in datasets) {
            zip.putNextEntry(ZipEntry("health/${dataset.name}.json"))
            zip.write('['.code)
            var first = true
            var date = from
            while (date <= to) {
                val dateText = date.toString()
                val revision = onlineDays?.revisionByDate?.get(dateText)
                val result = when {
                    onlineDays != null && revision == null -> SelectiveHealthResult(
                        SelectiveHealthDay(dateText, dataset.name, null),
                        SelectiveHealthSource(date, dataset, "online", true, 0)
                    )
                    onlineDays != null -> {
                        val summary = onlineDays.days.firstOrNull { it.localDate == dateText && it.revision == revision }
                            ?.toSelectiveHealthSummary(dataset)
                        val online = try {
                            readExportHealthRecords(date, dataset).also { page ->
                                require(page.date == dateText && page.dataset == dataset &&
                                    page.revision == revision && page.nextCursor == null) {
                                    "健康导出分页不完整或版本不匹配"
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: IOException) {
                            null
                        } catch (_: ApiProblem) {
                            null
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                        if (online != null) SelectiveHealthResult(
                            SelectiveHealthDay(dateText, dataset.name, online.revision, summary, online.records),
                            SelectiveHealthSource(date, dataset, "online", true, online.records.size)
                        ) else {
                            val localDay = dao.healthDay(dateText)
                            val localDetail = dao.healthDetailState(dateText, dataset.name)
                            val compatibleCache = localDetail != null &&
                                (localDay == null || localDetail.revision == localDay.revision)
                            if (compatibleCache) {
                                val cacheSummary = localDay?.takeIf { it.revision == localDetail!!.revision }
                                    ?.summary()?.toSelectiveHealthSummary(dataset)
                                val records = dao.healthRecords(dateText, dataset.name, localDetail!!.revision).map { it.record() }
                                SelectiveHealthResult(
                                    SelectiveHealthDay(dateText, dataset.name, localDetail.revision, cacheSummary, records),
                                    SelectiveHealthSource(date, dataset, "cached", localDetail.complete, records.size)
                                )
                            } else if (summary != null) SelectiveHealthResult(
                                SelectiveHealthDay(dateText, dataset.name, revision, summary),
                                SelectiveHealthSource(date, dataset, "online", false, 0)
                            ) else SelectiveHealthResult(
                                SelectiveHealthDay(dateText, dataset.name, revision),
                                SelectiveHealthSource(date, dataset, "missing", false, 0)
                            )
                        }
                    }
                    else -> {
                        val localDay = cachedDays[dateText]
                        val summary = localDay?.summary()?.toSelectiveHealthSummary(dataset)
                        val detail = dao.healthDetailState(dateText, dataset.name)
                        val compatible = detail != null && (localDay == null || detail.revision == localDay.revision)
                        if (summary != null || compatible) {
                            val records = if (compatible)
                                dao.healthRecords(dateText, dataset.name, detail!!.revision).map { it.record() }
                            else emptyList()
                            val complete = compatible && detail!!.complete
                            SelectiveHealthResult(
                                SelectiveHealthDay(
                                    dateText, dataset.name,
                                    if (compatible) detail!!.revision else localDay?.revision,
                                    summary, records
                                ),
                                SelectiveHealthSource(date, dataset, "cached", complete, records.size)
                            )
                        } else SelectiveHealthResult(
                            SelectiveHealthDay(dateText, dataset.name, localDay?.revision),
                            SelectiveHealthSource(date, dataset, "missing", false, 0)
                        )
                    }
                }
                if (!first) zip.write(','.code)
                first = false
                zip.write(wireJson.encodeToString(result.row).toByteArray(Charsets.UTF_8))
                sources += result.source
                date = date.plusDays(1)
            }
            zip.write(']'.code)
            zip.closeEntry()
        }
        entry("manifest.json", wireJson.encodeToString(SelectiveExportManifest(
            dateFrom = from.toString(),
            dateTo = to.toString(),
            selections = SelectiveExportItem.entries.filter { it in selected }.map { it.name },
            health = sources
        )))
    }
    SelectiveExportSummary(
        online = sources.count { it.source == "online" },
        missing = sources.count { it.source == "missing" },
        cached = sources.count { it.source == "cached" },
        partial = sources.count { it.source != "missing" && !it.complete }
    )
}
