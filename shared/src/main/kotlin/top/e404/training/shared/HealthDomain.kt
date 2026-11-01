package top.e404.training.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Values and names mirror the normalized source columns; nullable means absent, never zero. */
@Serializable data class StepsPayload(
    val steps: Long, val distanceMeters: Double? = null, val caloriesKcal: Double? = null,
    val deviceId: String? = null, val source: String? = null
)
@Serializable data class CaloriesPayload(val caloriesKcal: Double, val deviceId: String? = null, val source: String? = null)
@Serializable data class HeartRatePayload(val bpm: Int, val sampleType: String, val deviceId: String? = null, val source: String? = null)
@Serializable data class RestingHeartRatePayload(val bpm: Int, val source: String? = null)
@Serializable data class SleepStagePayload(val startAt: Long, val endAt: Long, val stage: String)
@Serializable data class SleepPayload(
    val startAt: Long, val endAt: Long, val durationMinutes: Int,
    val asleepMinutes: Int? = null, val awakeMinutes: Int? = null, val awakeCount: Int? = null,
    val avgHeartRate: Double? = null, val minHeartRate: Double? = null, val maxHeartRate: Double? = null,
    val deepMinutes: Int? = null, val lightMinutes: Int? = null, val remMinutes: Int? = null,
    val score: Double? = null, val isNap: Boolean, val algorithmVersion: Int? = null,
    val stages: List<SleepStagePayload> = emptyList()
)
@Serializable data class BodyPayload(
    val weightKg: Double, val bmi: Double? = null, val bodyFatPct: Double? = null,
    val muscleMassKg: Double? = null, val muscleRatePct: Double? = null,
    val skeletalMuscleMassKg: Double? = null, val bodyMoistureMassKg: Double? = null,
    val moistureRatePct: Double? = null, val boneMassKg: Double? = null,
    val proteinMassKg: Double? = null, val proteinRatePct: Double? = null,
    val fatMassKg: Double? = null, val fatFreeBodyKg: Double? = null,
    val visceralFat: Double? = null, val basalMetabolismKcal: Double? = null,
    val bodyAge: Double? = null, val bodyScore: Double? = null, val whr: Double? = null,
    val standardWeightKg: Double? = null, val extra: JsonObject? = null, val deviceId: String? = null
)
@Serializable data class ActivityPayload(
    val sportKey: String, val sportCategory: String? = null, val sportTypeCode: Int? = null,
    val startAt: Long, val endAt: Long, val durationSeconds: Int? = null,
    val distanceMeters: Double? = null, val caloriesKcal: Double? = null, val steps: Int? = null,
    val avgHeartRate: Double? = null, val maxHeartRate: Double? = null, val minHeartRate: Double? = null,
    val avgCadence: Double? = null, val maxCadence: Double? = null, val avgStrideCm: Double? = null,
    val avgSpeedMps: Double? = null, val maxSpeedMps: Double? = null,
    val avgPaceSecondsPerKm: Double? = null, val maxPaceSecondsPerKm: Double? = null,
    val minPaceSecondsPerKm: Double? = null,
    val hrWarmUpSeconds: Int? = null, val hrFatBurningSeconds: Int? = null,
    val hrAerobicSeconds: Int? = null, val hrAnaerobicSeconds: Int? = null, val hrExtremeSeconds: Int? = null,
    val avgHeightMeters: Double? = null, val maxHeightMeters: Double? = null, val minHeightMeters: Double? = null,
    val vo2max: Double? = null, val trainEffect: Double? = null, val anaerobicTrainEffect: Double? = null,
    val trainingLoad: Double? = null, val recoveryTime: Double? = null, val avgSpo2: Double? = null,
    val validDurationSeconds: Int? = null, val strokeCount: Int? = null,
    val avgStrokeFrequency: Double? = null, val maxStrokeFrequency: Double? = null,
    val avgSwolf: Double? = null, val bestSwolf: Double? = null, val turnCount: Int? = null,
    val poolWidth: Double? = null, val mainPosture: Int? = null, val totalCaloriesKcal: Double? = null,
    val avgPaceSecondsPer100m: Double? = null, val maxPaceSecondsPer100m: Double? = null,
    val minPaceSecondsPer100m: Double? = null, val extra: JsonObject? = null
)

inline fun <reified T> HealthRecord.decodePayload(json: Json = wireJson): T = json.decodeFromJsonElement(payload)

/** Health API v1; instants are epoch milliseconds and localDate is an Asia/Shanghai business date. */
@Serializable enum class HealthDataset { steps, calories, heartRate, restingHeartRate, sleep, body, activity }

/** Structured dataset-specific payload; see docs/API.md for each dataset's closed field set. */
@Serializable data class HealthRecord(
    val sourceId: String,
    val localDate: String,
    val measuredAt: Long,
    val payload: JsonObject
)

@Serializable data class HealthCapabilities(
    val appVersion: String,
    val formatVersion: Int = 6,
    val backupFormatVersion: Int = 6,
    val healthApiVersion: Int = 1
)

@Serializable data class HealthImportRequest(val dataset: HealthDataset, val records: List<HealthRecord>)
@Serializable data class HealthImportResult(
    val accepted: Int,
    val unchanged: Int,
    val revisionByDate: Map<String, Long>
)

@Serializable data class HealthDaySummary(
    val localDate: String,
    val revision: Long,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val stepCaloriesKcal: Double? = null,
    val calorieMinutesKcal: Double? = null,
    val heartRateSamples: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val sleepSessions: Int? = null,
    val bodyMeasurements: Int? = null,
    val activities: Int? = null
)

@Serializable data class HealthDaysResponse(
    val days: List<HealthDaySummary>,
    val revisionByDate: Map<String, Long>
)

@Serializable data class HealthRecordsPage(
    val date: String,
    val dataset: HealthDataset,
    val revision: Long,
    val records: List<HealthRecord>,
    val nextCursor: String? = null
)

@Serializable data class HealthCollectionState(
    val requested: Boolean = false,
    val stage: String = "idle",
    val phase: String? = null,
    val requestedAt: Long? = null,
    val startedAt: Long? = null,
    val submittedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val failedAt: Long? = null,
    val error: String? = null
)

@Serializable data class HealthStatus(
    val coveredDates: List<String>,
    val revisionByDate: Map<String, Long>,
    val collection: HealthCollectionState
)

@Serializable data class HealthCollectionRequestResult(
    val requested: Boolean,
    val state: HealthCollectionState
)

/** Strictly decodes the wire object into its concrete shared dataset DTO. */
fun decodeHealthPayload(dataset: HealthDataset, record: HealthRecord): Any = when (dataset) {
    HealthDataset.steps -> record.decodePayload<StepsPayload>()
    HealthDataset.calories -> record.decodePayload<CaloriesPayload>()
    HealthDataset.heartRate -> record.decodePayload<HeartRatePayload>()
    HealthDataset.restingHeartRate -> record.decodePayload<RestingHeartRatePayload>()
    HealthDataset.sleep -> record.decodePayload<SleepPayload>()
    HealthDataset.body -> record.decodePayload<BodyPayload>()
    HealthDataset.activity -> record.decodePayload<ActivityPayload>()
}
@Serializable data class HealthCollectionStateRequest(
    val event: String,
    val phase: String? = null,
    val error: String? = null
)

@Serializable data class HealthBackupManifest(
    val scope: String,
    val coveredDates: List<String> = emptyList(),
    val datasets: List<HealthDataset> = emptyList(),
    val recordCount: Long = 0
)

@Serializable data class HealthBackupEntry(val dataset: HealthDataset, val record: HealthRecord)
