package top.e404.training

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import top.e404.training.shared.HealthDataset
import top.e404.training.shared.HealthDaySummary
import top.e404.training.shared.HealthRecord
import top.e404.training.shared.HealthStatus
import top.e404.training.shared.wireJson
import java.time.LocalDate

/** State is derived only from durable Room rows; network availability never implies an empty day. */
data class HealthUiState(
    val selectedDate: LocalDate? = null,
    val days: List<HealthDaySummary> = emptyList(),
    val summary: HealthDaySummary? = null,
    val recordsByDataset: Map<HealthDataset, List<HealthRecord>> = emptyMap(),
    val detailStateByDataset: Map<HealthDataset, HealthDetailCacheState> = emptyMap(),
    val healthStatus: HealthStatus? = null,
    val collectionRequesting: Boolean = false,
    val healthError: String? = null,
    val collectionError: String? = null
)

enum class HealthDetailStatus { neverRequested, loading, complete, partial, error }
data class HealthDetailCacheState(
    val status: HealthDetailStatus,
    val revision: Long? = null,
    val cachedAt: Long? = null,
    val error: String? = null
)


internal fun HealthDaySummary.payload() = wireJson.encodeToString(this)
internal fun LocalHealthDay.summary() = wireJson.decodeFromString<HealthDaySummary>(payload)
/** 运动文档的来源包含采集数据集前缀；健康记录保留源记录标识。 */
internal fun HealthRecord.activitySourceId(): String = "misports:activities:$sourceId"
internal fun HealthRecord.cacheRow(dataset: HealthDataset, revision: Long, cachedAt: Long) = LocalHealthRecord(
    localDate = localDate,
    dataset = dataset.name,
    sourceId = sourceId,
    measuredAt = measuredAt,
    payload = wireJson.encodeToString(payload),
    revision = revision,
    cachedAt = cachedAt
)

internal fun LocalHealthDetailState.uiState(expectedRevision: Long? = null) = HealthDetailCacheState(
    status = when {
        error != null -> HealthDetailStatus.error
        complete && (expectedRevision == null || revision == expectedRevision) -> HealthDetailStatus.complete
        else -> HealthDetailStatus.partial
    },
    revision = revision,
    cachedAt = cachedAt,
    error = error
)
internal fun LocalHealthRecord.record() = HealthRecord(
    sourceId = sourceId,
    localDate = localDate,
    measuredAt = measuredAt,
    payload = wireJson.decodeFromString<JsonObject>(payload)
)
