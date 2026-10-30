package top.e404.training.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

enum class Kind { muscle, media, exercise, gym, equipment, program, plan, session, arrangement, activity }
const val SOURCE_UNKNOWN_EQUIPMENT_TAG = "source_unknown"

@Serializable data class Muscle(val id: String, val name: String, val mapKeys: List<String> = emptyList())
@Serializable data class Media(val id: String, val name: String, val fileName: String, val mimeType: String, val source: String = "", val sourceUrl: String = "")
@Serializable data class Exercise(
    val id: String, val name: String, val type: String = "strength",
    val muscles: List<String>, val secondaryMuscles: List<String> = emptyList(),
    val equipmentTags: List<String> = emptyList(), val instructions: List<String>,
    val mediaIds: List<String> = emptyList(), val unilateral: Boolean = false,
    val bodyweight: Boolean = false, val measurement: String = "reps", val defaultSeconds: Int = 30,
    val notes: String = "", val loadMode: String = "external"
)
@Serializable data class Gym(val id: String, val name: String, val notes: String = "")
@Serializable data class Equipment(
    val id: String, val name: String, val gymId: String, val tags: List<String>,
    val unit: String = "kg", val meaning: String = "total", val mediaId: String? = null,
    val instructions: String = "", val notes: String = ""
)
@Serializable data class SplitSlot(val id: String, val name: String, val muscles: List<String>)
@Serializable data class Program(val id: String, val name: String, val slots: List<SplitSlot>)
@Serializable data class Load(val value: Double, val unit: String = "kg", val meaning: String = "total", val mode: String = "external")
@Serializable data class SetTarget(
    val reps: Int? = null, val repsMax: Int? = null, val seconds: Int? = null,
    val load: Load? = null, val side: String = "both", val restSeconds: Int = 90
)
/** A plan set records its own input mode; exercise.measurement supplies only a default. */
fun targetMeasurement(target: SetTarget?, fallback: String): String = when {
    target?.seconds != null -> "seconds"
    target?.reps != null -> "reps"
    else -> fallback
}
@Serializable data class PlanItem(
    val id: String, val exerciseId: String, val equipmentId: String? = null,
    val sets: List<SetTarget>, val notes: String = ""
)
@Serializable data class Plan(
    val id: String, val name: String, val programId: String? = null, val slotId: String? = null,
    val gymId: String? = null, val date: String? = null, val items: List<PlanItem> = emptyList(), val notes: String = ""
)
@Serializable data class SetSegment(val reps: Int, val load: Load? = null)
@Serializable data class SetResult(
    val itemId: String, val setIndex: Int, val reps: Int? = null, val seconds: Int? = null,
    val load: Load? = null, val side: String = "both", val completedAt: Long? = null,
    val segments: List<SetSegment> = emptyList()
)
@Serializable data class HistoricalSource(val sourceId: String, val localDate: String)
@Serializable data class SessionSource(
    val sourceId: String, val name: String, val localDate: String,
    val snapshot: Plan? = null, val startedAt: Long? = null, val endedAt: Long? = null,
    val historicalSource: HistoricalSource? = null, val arrangementOverrideId: String? = null,
    val splitSlotName: String? = null, val itemIds: List<String> = emptyList(), val notes: String = ""
)
@Serializable data class ArrangementOverride(val id: String, val slotId: String)
@Serializable data class Arrangement(
    val id: String = "current", val programId: String? = null,
    val selectedPlans: Map<String, Map<String, String>> = emptyMap(),
    val nextOverrides: Map<String, ArrangementOverride> = emptyMap()
)
/** 训练开始时保存计划和动作说明；canonical session 按本地日期保存所有实际结果。 */
@Serializable data class Session(
    val id: String, val name: String, val snapshot: Plan?, val exercises: List<Exercise>,
    val startedAt: Long?, val endedAt: Long? = null, val status: String,
    val results: List<SetResult> = emptyList(), val notes: String = "",
    val items: List<PlanItem> = snapshot?.items ?: emptyList(), val skippedItems: List<String> = emptyList(),
    val arrangementOverrideId: String? = null, val splitSlotName: String? = null,
    val localDate: String, val sourceRecords: List<SessionSource> = emptyList()
)

fun splitTrainingLabel(name: String?): String? = when (name) {
    null, "自由锻炼" -> null
    "推类训练", "推" -> "推"
    "拉类训练", "拉" -> "拉"
    "腿部训练", "腿类训练", "腿" -> "腿"
    else -> name
}

fun dailySessionId(date: LocalDate): String = "day-$date"

fun sessionTrainingLabels(session: Session): List<String> {
    if (session.results.isEmpty()) return listOf(splitTrainingLabel(session.splitSlotName) ?: "自由锻炼")
    val completedItems = session.results.mapTo(hashSetOf()) { it.itemId }
    val sourcedItems = session.sourceRecords.flatMapTo(hashSetOf()) { it.itemIds }
    val labels = linkedSetOf<String>()
    session.sourceRecords.filter { source -> source.itemIds.any(completedItems::contains) }
        .forEach { labels += splitTrainingLabel(it.splitSlotName) ?: "自由锻炼" }
    val nativeItems = completedItems - sourcedItems
    if (nativeItems.isNotEmpty()) labels += splitTrainingLabel(session.splitSlotName) ?: "自由锻炼"
    return (labels.ifEmpty { setOf(splitTrainingLabel(session.splitSlotName) ?: "自由锻炼") }).toList()
}

@Serializable data class Document(
    val kind: Kind, val id: String, val data: JsonObject,
    val revision: Long = 0, val deleted: Boolean = false
)
@Serializable data class Mutation(
    val document: Document, val baseRevision: Long, val restoreSession: Boolean = false
)
@Serializable data class SyncRequest(val cursor: Long = 0, val mutations: List<Mutation> = emptyList())
@Serializable data class Conflict(val local: Document, val remote: Document)
@Serializable data class SyncResponse(
    val cursor: Long, val documents: List<Document>, val accepted: List<Document> = emptyList(),
    val conflicts: List<Conflict> = emptyList()
)
class ImportConflict(message: String) : IllegalArgumentException(message)
@Serializable data class Backup(
    val formatVersion: Int = 6,
    val documents: List<Document>,
    val exportedAt: Long,
    val healthManifest: HealthBackupManifest? = null
)
@Serializable data class ApiError(val message: String)
@Serializable data class TimerState(
    val deadline: Long, val label: String, val sessionId: String,
    val itemId: String, val setIndex: Int, val type: String = "rest",
    val notified: Boolean = false, val alertDismissed: Boolean = false
)
@Serializable data class ActivityRecord(
    val id: String, val name: String, val localDate: String,
    val durationSeconds: Int? = null, val distanceMeters: Double? = null,
    val notes: String = "", val sourceId: String? = null,
    val startedAt: Long? = null, val endedAt: Long? = null
)
/** 旧自动投影同时使用专用ID和小米来源；明确导入的用户活动可共享来源，不能仅按来源过滤。 */
fun ActivityRecord.isXiaomiAutoProjection(): Boolean =
    id.startsWith("xiaomi-") && sourceId?.startsWith("misports:activities:") == true
@Serializable data class HistorySummary(
    val sessions: List<Session>, val strengthSets: Int, val stretchSets: Int,
    val strengthSetsByMuscle: Map<String, Int>, val activities: List<ActivityRecord> = emptyList()
)

inline fun <reified T> Document.value(): T = wireJson.decodeFromJsonElement(data)
inline fun <reified T> document(kind: Kind, id: String, value: T): Document =
    Document(kind, id, wireJson.encodeToJsonElement(value).jsonObject)

fun Document.isCanonicalSessionDocument(): Boolean {
    if (kind != Kind.session || deleted) return false
    val date = data["localDate"]?.jsonPrimitive?.contentOrNull ?: return false
    return data["historicalSource"] == null && id == dailySessionId(LocalDate.parse(date))
}

/** Explicitly migrate one old wire document; normal reads never project or rewrite payloads. */
fun migrateSessionDocument(document: Document): Document {
    if (document.kind != Kind.session || document.deleted || document.isCanonicalSessionDocument()) return document
    val data = document.data
    val legacySource = data["historicalSource"]?.takeUnless { it is JsonNull }?.jsonObject
    val date = data["localDate"]?.jsonPrimitive?.contentOrNull
        ?: legacySource?.get("localDate")?.jsonPrimitive?.contentOrNull
        ?: data["startedAt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
        } ?: error("训练缺少真实日期：${document.id}")
    val source = buildJsonObject {
        put("sourceId", JsonPrimitive(document.id))
        put("name", data["name"] ?: JsonPrimitive(document.id))
        put("localDate", JsonPrimitive(date))
        put("snapshot", data["snapshot"] ?: JsonNull)
        put("startedAt", data["startedAt"] ?: JsonNull)
        put("endedAt", data["endedAt"] ?: JsonNull)
        put("historicalSource", legacySource?.let { JsonObject(it - "originalText") } ?: JsonNull)
        put("arrangementOverrideId", data["arrangementOverrideId"] ?: JsonNull)
        put("splitSlotName", data["splitSlotName"] ?: JsonNull)
        put("itemIds", JsonArray((data["items"]?.jsonArray ?: data["snapshot"]?.takeUnless { it is JsonNull }?.jsonObject?.get("items")?.jsonArray)
            .orEmpty().mapNotNull { it.jsonObject["id"] }))
        put("notes", JsonPrimitive(data["notes"]?.jsonPrimitive?.contentOrNull ?: ""))
    }
    return document.copy(data = JsonObject(data - "historicalSource" + mapOf(
        "localDate" to JsonPrimitive(date), "sourceRecords" to JsonArray(listOf(source))
    )))
}

/** Migrate old daily duplicates while leaving already-canonical singleton payloads byte-for-byte intact. */
fun normalizeDailySessionDocuments(documents: List<Document>): List<Document> {
    require(documents.map { it.kind to it.id }.distinct().size == documents.size) { "资料包含重复标识" }
    val nonSessions = documents.filter { it.kind != Kind.session }
    val sessions = documents.filter { it.kind == Kind.session }
    val deleted = sessions.filter { it.deleted }
    val deletedDates = deleted.mapNotNull { doc ->
        if (!doc.id.startsWith("day-")) null else runCatching { LocalDate.parse(doc.id.removePrefix("day-")) }.getOrNull()
    }.toSet()
    val candidateEntries = sessions.filterNot { it.deleted }.map { raw ->
        val migrated = migrateSessionDocument(raw)
        raw to migrated.value<Session>()
    }
    val suppressed = candidateEntries.filter { (_, session) -> LocalDate.parse(session.localDate) in deletedDates }
    val active = candidateEntries.filterNot { (_, session) -> LocalDate.parse(session.localDate) in deletedDates }
    val canonical = active.groupBy { (_, session) -> session.localDate }.map { (date, entries) ->
        val existingCanonical = entries.filter { (raw, _) -> raw.isCanonicalSessionDocument() }
        require(existingCanonical.size <= 1) { "同一天存在多个 canonical Session：$date" }
        if (existingCanonical.size == 1 && entries.size == 1) return@map existingCanonical.single().first

        val canonicalEntry = existingCanonical.singleOrNull()
        val candidates = entries.map { it.second }
        val base = canonicalEntry?.second ?: candidates.sortedWith(
            compareBy<Session> { it.results.isNotEmpty() }.thenBy { it.startedAt ?: Long.MIN_VALUE }.thenBy { it.id }
        ).last()
        val itemById = linkedMapOf<String, PlanItem>()
        val resultByKey = linkedMapOf<Pair<String, Int>, SetResult>()
        val exerciseById = linkedMapOf<String, Exercise>()
        val skipped = linkedSetOf<String>()
        val sourceById = linkedMapOf<String, SessionSource>()
        val notes = linkedSetOf<String>()
        base.exercises.forEach { exerciseById[it.id] = it }
        if (canonicalEntry != null) {
            base.items.forEach { itemById[it.id] = it }
            base.results.forEach { resultByKey[it.itemId to it.setIndex] = it }
            skipped += base.skippedItems
            if (base.notes.isNotBlank()) notes += base.notes.split("\n\n").filter { it.isNotBlank() }
            base.sourceRecords.forEach { sourceById[it.sourceId] = it }
        }

        val legacyEntries = entries.filter { (raw, _) -> !raw.isCanonicalSessionDocument() }.sortedBy { it.first.id }
        for ((_, migrated) in legacyEntries) {
            val source = migrated.sourceRecords.single()
            val oldItems = migrated.items.associateBy { it.id }
            val idMap = oldItems.keys.associateWith { oldId ->
                "daily-item-${java.util.UUID.nameUUIDFromBytes("${source.sourceId}/$oldId".toByteArray(Charsets.UTF_8))}"
            }
            val mappedSource = source.copy(itemIds = source.itemIds.mapNotNull(idMap::get))
            val priorSource = sourceById[source.sourceId]
            if (priorSource != null) {
                val incomingItems = oldItems.mapValues { (id, item) -> item.copy(id = idMap.getValue(id)) }
                val existingItems = priorSource.itemIds.mapNotNull { itemById[it]?.let { item -> item.id to item } }.toMap()
                val incomingResults = migrated.results.mapNotNull { result ->
                    idMap[result.itemId]?.let { result.copy(itemId = it) }
                }
                val existingResults = priorSource.itemIds.flatMap { itemId ->
                    resultByKey.values.filter { it.itemId == itemId }
                }
                if (priorSource.copy(itemIds = emptyList(), notes = "") != mappedSource.copy(itemIds = emptyList(), notes = "") ||
                    priorSource.notes != mappedSource.notes && (priorSource.notes.isNotBlank() || mappedSource.notes !in notes) ||
                    incomingItems.values.toSet() != existingItems.values.toSet() ||
                    incomingResults.toSet() != existingResults.toSet()) {
                    throw ImportConflict("相同历史来源包含不同训练事实：${source.sourceId}")
                }
                continue
            }
            sourceById[source.sourceId] = mappedSource
            for (exercise in migrated.exercises) {
                val existing = exerciseById[exercise.id]
                require(existing == null || existing == exercise) { "同一动作来源存在不同说明：${exercise.id}" }
                exerciseById[exercise.id] = exercise
            }
            for ((oldId, item) in oldItems) {
                val mapped = item.copy(id = idMap.getValue(oldId))
                require(mapped.id !in itemById) { "历史来源动作标识与当日原生动作冲突：${mapped.id}" }
                itemById[mapped.id] = mapped
            }
            for (result in migrated.results) {
                val mappedId = idMap[result.itemId] ?: continue
                val mapped = result.copy(itemId = mappedId)
                val key = mapped.itemId to mapped.setIndex
                val existing = resultByKey[key]
                if (existing != null && existing != mapped) throw ImportConflict("相同历史来源的完成组内容不同：${source.sourceId}")
                resultByKey[key] = mapped
            }
            migrated.skippedItems.mapNotNullTo(skipped) { idMap[it] }
            if (migrated.notes.isNotBlank()) notes += migrated.notes
        }


        val merged = base.copy(
            id = dailySessionId(LocalDate.parse(date)), localDate = date,
            status = if (resultByKey.isEmpty() && base.status == "completed") "cancelled" else base.status,
            sourceRecords = sourceById.values.toList(), exercises = exerciseById.values.toList(),
            items = itemById.values.toList(), results = resultByKey.values.toList(),
            skippedItems = skipped.toList(), notes = notes.joinToString("\n\n")
        )
        require(merged.sourceRecords.map { it.sourceId }.distinct().size == merged.sourceRecords.size) { "每日历史来源标识不能重复" }
        val sourceItemIds = merged.sourceRecords.flatMap { it.itemIds }
        require(sourceItemIds.distinct().size == sourceItemIds.size && sourceItemIds.all { id -> merged.items.any { it.id == id } }) {
            "每日历史来源动作映射无效或相互重叠"
        }
        document(Kind.session, merged.id, merged).copy(revision = canonicalEntry?.first?.revision ?: 0)
    }
    val activeById = active.associateBy { it.first.id }
    val mergedTombstones = canonical.flatMap { doc ->
        doc.value<Session>().sourceRecords.asSequence().map { it.sourceId }
            .filter { it != doc.id && it in activeById }
            .distinct().map { id ->
                Document(Kind.session, id, JsonObject(emptyMap()), activeById.getValue(id).first.revision, deleted = true)
            }.toList()
    }
    val suppressedTombstones = suppressed.filterNot { (raw, _) -> raw.isCanonicalSessionDocument() }
        .map { (raw, _) -> Document(Kind.session, raw.id, JsonObject(emptyMap()), raw.revision, deleted = true) }
    val all = nonSessions + deleted + mergedTombstones + suppressedTombstones + canonical
    require(all.map { it.kind to it.id }.distinct().size == all.size) { "每日归并产生重复资料标识" }
    return all
}

/** Merge imported day sources into the current canonical day without replacing unrelated facts. */
fun mergeImportedSessions(existing: List<Document>, incoming: List<Document>, replace: Boolean): List<Document> {
    val incomingNormalized = normalizeDailySessionDocuments(incoming)
    val existingNormalized = normalizeDailySessionDocuments(existing)
    val incomingSessions = incomingNormalized.filter { it.kind == Kind.session && !it.deleted }
    val existingByDate = existingNormalized.filter { it.kind == Kind.session && !it.deleted }
        .associateBy { it.value<Session>().localDate }
    val existingDeletedDays = existingNormalized.filter { it.kind == Kind.session && it.deleted && it.id.startsWith("day-") }
        .mapTo(hashSetOf()) { it.id }
    val incomingCanonicalDates = incoming.filter { it.kind == Kind.session && !it.deleted && it.isCanonicalSessionDocument() }
        .mapTo(hashSetOf()) { it.value<Session>().localDate }
    val incomingDeletedDays = incomingNormalized.filter { it.kind == Kind.session && it.deleted && it.id.startsWith("day-") }
        .map { it.id }.toSet()
    if (!replace && existingByDate.values.any { dailySessionId(LocalDate.parse(it.value<Session>().localDate)) in incomingDeletedDays })
        throw ImportConflict("导入包包含已存在训练日的删除标记；需明确选择替换")
    val mergedSessions = incomingSessions.map { incomingDoc ->
        val session = incomingDoc.value<Session>()
        val current = existingByDate[session.localDate]
        if (current == null) {
            if (!replace && dailySessionId(LocalDate.parse(session.localDate)) in existingDeletedDays)
                throw ImportConflict("已删除训练日不能被导入资料静默恢复；需明确选择替换")
            incomingDoc
        } else if (session.localDate in incomingCanonicalDates) {
            if (current.data == incomingDoc.data) current
            else if (!replace) throw ImportConflict("完整每日备份与已有训练不同；需明确选择替换")
            else incomingDoc.copy(revision = current.revision)
        } else mergeImportedDay(current, incomingDoc, replace)
    }
    val tombstones = incomingNormalized.filter { it.kind == Kind.session && it.deleted }
    val otherIncoming = incomingNormalized.filter { it.kind != Kind.session }
    val result = otherIncoming + tombstones + mergedSessions
    require(result.map { it.kind to it.id }.distinct().size == result.size) { "导入归并产生重复资料标识" }
    return result
}

private fun mergeImportedDay(existingDoc: Document, incomingDoc: Document, replace: Boolean): Document {
    val existing = existingDoc.value<Session>()
    val incoming = incomingDoc.value<Session>()
    require(existing.localDate == incoming.localDate && existing.id == incoming.id) { "每日训练标识冲突" }
    val items = existing.items.associateByTo(linkedMapOf()) { it.id }
    val results = existing.results.associateByTo(linkedMapOf()) { it.itemId to it.setIndex }
    val exercises = existing.exercises.associateByTo(linkedMapOf()) { it.id }
    val skipped = (existing.skippedItems + incoming.skippedItems).distinct().toMutableList()
    val sources = existing.sourceRecords.associateByTo(linkedMapOf()) { it.sourceId }
    val sourceItems = sources.values.flatMapTo(hashSetOf()) { it.itemIds }
    val incomingSourceItems = incoming.sourceRecords.flatMapTo(hashSetOf()) { it.itemIds }
    val notes = existing.notes.split("\n\n").filter { it.isNotBlank() }.toMutableList()

    fun facts(session: Session, ids: Set<String>) = Triple(
        session.items.filter { it.id in ids }.sortedBy { it.id },
        session.results.filter { it.itemId in ids }.sortedWith(compareBy<SetResult> { it.itemId }.thenBy { it.setIndex }),
        session.exercises
    )

    for (source in incoming.sourceRecords) {
        require(source.itemIds.all { id -> incoming.items.any { it.id == id } }) { "导入来源动作映射缺少动作内容" }
        val prior = sources[source.sourceId]
        if (prior != null) {
            if (prior.itemIds != source.itemIds) throw ImportConflict("同一来源动作映射不能删除或重新关联：${source.sourceId}")
            val priorFacts = facts(existing, prior.itemIds.toSet())
            val nextFacts = facts(incoming, source.itemIds.toSet())
            val same = prior.copy(itemIds = emptyList(), notes = "") == source.copy(itemIds = emptyList(), notes = "") &&
                priorFacts.first == nextFacts.first && priorFacts.second == nextFacts.second &&
                priorFacts.third.filter { ex -> priorFacts.first.any { it.exerciseId == ex.id } } ==
                nextFacts.third.filter { ex -> nextFacts.first.any { it.exerciseId == ex.id } } &&
                (prior.notes == source.notes || prior.notes.isBlank() && source.notes in notes)
            if (same) continue
            if (!replace) throw ImportConflict("同一来源已有不同内容：${source.sourceId}；需明确替换")
            prior.notes.takeIf { it.isNotBlank() }?.let { note -> notes.remove(note) }
            prior.itemIds.forEach { id ->
                if (sources.values.any { it.sourceId != source.sourceId && id in it.itemIds }) return@forEach
                items.remove(id)
                results.keys.removeAll { it.first == id }
                skipped.remove(id)
            }
        }
        source.itemIds.forEach { id ->
            val nextItem = incoming.items.single { it.id == id }
            val priorItem = items[id]
            if (priorItem != null && priorItem != nextItem) throw ImportConflict("不同来源动作标识冲突：$id")
            items[id] = nextItem
            incoming.results.filter { it.itemId == id }.forEach { result ->
                val key = result.itemId to result.setIndex
                val priorResult = results[key]
                if (priorResult != null && priorResult != result) throw ImportConflict("不同来源完成组标识冲突：$id/${result.setIndex}")
                results[key] = result
            }
        }
        sources[source.sourceId] = source
        sourceItems += source.itemIds
        source.itemIds.filter { it in incoming.skippedItems }.forEach { id -> if (id !in skipped) skipped += id }
        source.notes.takeIf { it.isNotBlank() }?.let { if (it !in notes) notes += it }
        incoming.exercises.filter { exercise -> source.itemIds.any { id -> incoming.items.single { it.id == id }.exerciseId == exercise.id } }
            .forEach { exercise ->
                val priorExercise = exercises[exercise.id]
                val otherUsesExercise = items.values.any { item ->
                    item.exerciseId == exercise.id && item.id !in source.itemIds
                }
                if (priorExercise != null && priorExercise != exercise && otherUsesExercise)
                    throw ImportConflict("不能替换其他来源的动作说明：${exercise.id}")
                exercises[exercise.id] = exercise
            }
    }

    val nativeIncomingItems = incoming.items.filterNot { it.id in incomingSourceItems }
    for (item in nativeIncomingItems) {
        val prior = items[item.id]
        if (prior != null && prior != item && !replace) throw ImportConflict("当日原生动作内容不同：${item.id}")
        if (prior != null && prior != item && item.id in sourceItems) throw ImportConflict("不能替换其他来源的动作：${item.id}")
        items[item.id] = item
    }
    val nativeExerciseIds = nativeIncomingItems.mapTo(hashSetOf()) { it.exerciseId }
    incoming.exercises.filter { it.id in nativeExerciseIds }.forEach { exercise ->
        val prior = exercises[exercise.id]
        val otherUsesExercise = items.values.any { it.exerciseId == exercise.id && it.id !in incomingSourceItems }
        if (prior != null && prior != exercise && otherUsesExercise && !replace)
            throw ImportConflict("当日动作说明不同：${exercise.id}")
        if (prior != null && prior != exercise && otherUsesExercise && sourceItems.any { id -> items[id]?.exerciseId == exercise.id })
            throw ImportConflict("不能替换其他来源的动作说明：${exercise.id}")
        exercises[exercise.id] = exercise
    }
    if (incoming.sourceRecords.isEmpty() && incoming.notes.isNotBlank()) {
        if (!replace && notes.isNotEmpty() && notes.joinToString("\n\n") != incoming.notes)
            throw ImportConflict("当日反馈内容不同；需明确选择替换")
        if (replace && sources.isEmpty()) notes.clear()
        if (incoming.notes !in notes) notes += incoming.notes
    }
    val nativeIncomingResults = incoming.results.filterNot { it.itemId in incomingSourceItems }
    for (result in nativeIncomingResults) {
        val key = result.itemId to result.setIndex
        val prior = results[key]
        if (prior != null && prior != result && !replace) throw ImportConflict("当日原生完成组内容不同：${result.itemId}/${result.setIndex}")
        if (prior != null && prior != result && result.itemId in sourceItems) throw ImportConflict("不能替换其他来源的完成组")
        results[key] = result
    }
    val merged = existing.copy(
        status = if (results.isEmpty() && existing.status == "completed") "cancelled" else existing.status,
        sourceRecords = sources.values.toList(),
        exercises = exercises.values.toList(),
        items = items.values.toList(),
        results = results.values.toList(),
        skippedItems = skipped,
        notes = notes.joinToString("\n\n")
    )
    return document(Kind.session, merged.id, merged).copy(revision = existingDoc.revision)
}

fun Document.displayName(): String = data["name"]?.jsonPrimitive?.content ?: id
fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))

private fun checkLoad(load: Load?) {
    if (load == null) return
    require(load.value.isFinite() && load.value >= 0) { "重量必须是非负有限数值" }
    require(load.unit in setOf("kg", "lb")) { "重量单位必须为 kg 或 lb" }
    require(load.meaning in setOf("total", "per_hand", "per_side", "unknown")) { "重量含义不正确" }
    require(load.mode in setOf("external", "assistance")) { "重量须区分负重或助力" }
}
fun checkTarget(target: SetTarget) {
    checkLoad(target.load)
    require(target.reps == null || target.reps > 0) { "次数必须大于零" }
    require(target.seconds == null || target.seconds > 0) { "时长必须大于零" }
    require(target.reps != null || target.seconds != null) { "请填写次数或时长" }
    require(target.repsMax == null || (target.reps != null && target.repsMax >= target.reps)) { "次数范围不正确" }
    require(target.restSeconds >= 0) { "休息时长不能为负数" }
    require(target.side in setOf("both", "left", "right")) { "左右侧要求不正确" }
}

fun Document.hasDeprecatedHistoricalText(): Boolean {
    val root = data["historicalSource"]?.takeUnless { it is JsonNull }?.jsonObject?.containsKey("originalText") == true
    val sources = data["sourceRecords"]?.takeUnless { it is JsonNull }?.jsonArray.orEmpty()
    return root || sources.any {
        it.jsonObject["historicalSource"]?.takeUnless { value -> value is JsonNull }?.jsonObject?.containsKey("originalText") == true
    }
}

/** 同一入口核验客户端和接口数据；只校验业务边界，不推测或换算器械读数。 */
fun validateDocument(
    doc: Document,
    list: (Kind) -> List<Document>,
    lookup: (Kind, String) -> Document?,
    replaceSourceIds: Set<String> = emptySet(),
    restoreSession: Boolean = false,
    requireNewResultTimes: Boolean = true
) {
    require(validId(doc.id)) { "资料标识不正确" }
    if (restoreSession) require(doc.kind == Kind.session && !doc.deleted) {
        "仅可显式恢复完整的非删除每日训练"
    }
    if (doc.deleted) {
        fun plans() = list(Kind.plan).map { it.value<Plan>() }
        val arrangements = list(Kind.arrangement).filterNot { it.deleted }.map { it.value<Arrangement>() }
        val inUse = when (doc.kind) {
            Kind.gym -> list(Kind.equipment).any { it.value<Equipment>().gymId == doc.id } || plans().any { it.gymId == doc.id }
            Kind.equipment -> plans().any { p -> p.items.any { it.equipmentId == doc.id } }
            Kind.program -> plans().any { it.programId == doc.id } || arrangements.any { it.programId == doc.id || doc.id in it.selectedPlans || doc.id in it.nextOverrides }
            Kind.exercise -> plans().any { p -> p.items.any { it.exerciseId == doc.id } }
            Kind.media -> list(Kind.exercise).any { doc.id in it.value<Exercise>().mediaIds } ||
                list(Kind.equipment).any { it.value<Equipment>().mediaId == doc.id } ||
                list(Kind.session).any { s -> s.value<Session>().exercises.any { doc.id in it.mediaIds } }
            Kind.muscle -> list(Kind.exercise).any { e -> e.value<Exercise>().let { doc.id in it.muscles || doc.id in it.secondaryMuscles } } ||
                list(Kind.program).any { p -> p.value<Program>().slots.any { doc.id in it.muscles } } ||
                list(Kind.session).any { s -> s.value<Session>().exercises.any { doc.id in it.muscles || doc.id in it.secondaryMuscles } }
            Kind.plan -> arrangements.any { arrangement -> arrangement.selectedPlans.values.any { doc.id in it.values } }
            Kind.session, Kind.arrangement, Kind.activity -> false
        }
        require(!inUse) { "该资料仍被计划、器械、安排或训练记录使用，请先解除关联" }
        return
    }
    fun ref(kind: Kind, id: String) = require(lookup(kind, id)?.deleted == false) { "关联资料不存在：$kind/$id" }
    val entityId: String = when (doc.kind) {
        Kind.muscle -> doc.value<Muscle>().also { require(it.name.isNotBlank()) { "请填写部位名称" } }.id
        Kind.media -> doc.value<Media>().also {
            require(it.name.isNotBlank() && validId(it.fileName)) { "素材名称或文件名不正确" }
            require(it.mimeType in setOf("image/webp", "image/png", "image/jpeg", "image/gif", "image/svg+xml")) { "不支持该素材格式" }
        }.id
        Kind.exercise -> doc.value<Exercise>().also {
            require(it.name.isNotBlank()) { "动作需要名称" }
            require(it.type in setOf("strength", "stretch")) { "动作类型不正确" }
            require(it.measurement in setOf("reps", "seconds")) { "动作计量方式不正确" }
            require(it.muscles.isNotEmpty()) { "动作需要训练部位" }
            (it.muscles + it.secondaryMuscles).forEach { id -> ref(Kind.muscle, id) }
            it.mediaIds.forEach { id -> ref(Kind.media, id) }
        }.id
        Kind.gym -> doc.value<Gym>().also { require(it.name.isNotBlank()) { "请填写健身房名称" } }.id
        Kind.equipment -> doc.value<Equipment>().also {
            require(it.name.isNotBlank() && it.tags.isNotEmpty()) { "器械需要名称和类别" }
            ref(Kind.gym, it.gymId)
            require(it.unit in setOf("kg", "lb")) { "器械单位必须为 kg 或 lb" }
            require(it.meaning in setOf("total", "per_hand", "per_side", "unknown")) { "器械重量含义不正确" }
            it.mediaId?.let { id -> ref(Kind.media, id) }
        }.id
        Kind.arrangement -> doc.value<Arrangement>().also { arrangement ->
            require(arrangement.id == "current") { "安排标识必须为 current" }
            arrangement.programId?.let { id -> ref(Kind.program, id) }
            arrangement.selectedPlans.forEach { (programId, plansBySlot) ->
                val program = lookup(Kind.program, programId)?.takeUnless { it.deleted }?.value<Program>()
                    ?: throw IllegalArgumentException("安排引用的分化方案不存在：$programId")
                plansBySlot.forEach { (slotId, planId) ->
                    require(program.slots.any { it.id == slotId }) { "安排引用的分化项不存在：$slotId" }
                    val plan = lookup(Kind.plan, planId)?.takeUnless { it.deleted }?.value<Plan>()
                        ?: throw IllegalArgumentException("安排引用的计划不存在：$planId")
                    require(plan.programId == programId && plan.slotId == slotId) { "安排中的计划与分化项不匹配" }
                }
            }
            arrangement.nextOverrides.forEach { (programId, override) ->
                require(override.id.isNotBlank()) { "下次安排缺少选择标识" }
                val program = lookup(Kind.program, programId)?.takeUnless { it.deleted }?.value<Program>()
                    ?: throw IllegalArgumentException("安排引用的分化方案不存在：$programId")
                require(program.slots.any { it.id == override.slotId }) { "下次安排引用的分化项不存在：${override.slotId}" }
            }
        }.id
        Kind.program -> doc.value<Program>().also {
            require(it.name.isNotBlank() && it.slots.isNotEmpty()) { "分化方案至少需要一项训练" }
            require(it.slots.map { s -> s.id }.distinct().size == it.slots.size) { "分化项不能重复" }
            it.slots.forEach { s ->
                require(validId(s.id) && s.name.isNotBlank() && s.muscles.isNotEmpty()) { "分化项需要名称和训练部位" }
                s.muscles.forEach { id -> ref(Kind.muscle, id) }
            }
            val slotIds = it.slots.map { slot -> slot.id }.toSet()
            require(list(Kind.plan).none { plan -> plan.value<Plan>().let { p -> p.programId == it.id && p.slotId !in slotIds } }) { "被计划使用的训练项不能移除，请先调整相关计划" }
        }.id
        Kind.plan -> doc.value<Plan>().also {
            require(it.name.isNotBlank()) { "请填写计划名称" }
            it.date?.let { date -> LocalDate.parse(date) }
            it.gymId?.let { id -> ref(Kind.gym, id) }
            it.programId?.let { id ->
                ref(Kind.program, id)
                require(lookup(Kind.program, id)!!.value<Program>().slots.any { s -> s.id == it.slotId }) { "计划的分化项不存在" }
            }
            require(it.items.map { p -> p.id }.distinct().size == it.items.size) { "计划动作标识不能重复" }
            it.items.forEach { p ->
                ref(Kind.exercise, p.exerciseId)
                require(p.sets.isNotEmpty()) { "计划动作至少需要一组" }
                p.sets.forEach(::checkTarget)
                p.equipmentId?.let { id -> ref(Kind.equipment, id) }
            }
        }.id
        Kind.activity -> doc.value<ActivityRecord>().also { activity ->
            require(activity.name.isNotBlank()) { "请填写活动名称" }
            LocalDate.parse(activity.localDate)
            require(activity.durationSeconds == null || activity.durationSeconds > 0) { "活动时长必须大于零" }
            require(activity.distanceMeters == null || activity.distanceMeters.isFinite() && activity.distanceMeters > 0) { "活动距离必须是正数" }
            require(activity.startedAt == null || activity.endedAt == null || activity.endedAt >= activity.startedAt) { "活动结束时间不能早于开始时间" }
            if (activity.startedAt != null) {
                val startedDate = java.time.Instant.ofEpochMilli(activity.startedAt).atOffset(java.time.ZoneOffset.ofHours(8)).toLocalDate()
                require(startedDate == LocalDate.parse(activity.localDate)) { "活动日期必须为开始时间所在的UTC+8日期" }
            }
        }.id
        Kind.session -> {
            require("localDate" in doc.data) { "新写入训练缺少 localDate" }
            require("historicalSource" !in doc.data) { "新写入训练不能包含顶层 historicalSource" }
            val session = doc.value<Session>()
            require(!doc.hasDeprecatedHistoricalText()) { "新写入不能包含已废弃的 historicalSource.originalText" }
            require(session.name.isNotBlank()) { "训练缺少名称" }
            require(session.status in setOf("active", "completed", "cancelled")) { "训练状态不正确" }
            val date = LocalDate.parse(session.localDate)
            require(session.id == dailySessionId(date) && doc.id == session.id) { "每日训练必须使用日期稳定标识" }
            require(list(Kind.session).none { it.id != doc.id && !it.deleted && it.value<Session>().localDate == session.localDate }) {
                "同一天只能有一份训练记录"
            }
            val previous = lookup(Kind.session, session.id)?.takeUnless { it.deleted }?.value<Session>()
            val resumesHistory = previous != null && previous.localDate == session.localDate &&
                previous.startedAt == null &&
                (previous.results.isNotEmpty() || previous.sourceRecords.isNotEmpty())
            val restoresHistory = restoreSession && session.startedAt == null &&
                (session.results.isNotEmpty() || session.sourceRecords.isNotEmpty())
            val canContinueHistory = restoresHistory ||
                resumesHistory && session.snapshot == previous?.snapshot
            if (session.status == "active") {
                require(session.endedAt == null && (
                    session.snapshot != null && session.startedAt != null && session.startedAt > 0 ||
                        canContinueHistory && session.startedAt == null
                )) { "进行中的训练缺少真实开始内容" }
            }
            if (session.startedAt == null) require(session.status != "active" || canContinueHistory) {
                "只有已有历史训练可以在未知开始时间下继续"
            } else require(session.snapshot != null) { "实时训练缺少原始计划" }
            require(session.startedAt == null || session.startedAt > 0) { "训练开始时间不正确" }
            require(session.endedAt == null || session.endedAt > 0 && (session.startedAt == null || session.endedAt >= session.startedAt)) {
                "训练结束时间早于训练开始"
            }
            require(session.status != "completed" || session.results.isNotEmpty()) { "没有完成组的训练不能标记完成" }
            require(session.sourceRecords.map { it.sourceId }.distinct().size == session.sourceRecords.size) { "历史来源不能重复" }
            val sourceItemIds = session.sourceRecords.flatMap { it.itemIds }
            require(sourceItemIds.distinct().size == sourceItemIds.size) { "同一动作不能归属于多个来源" }
            session.sourceRecords.forEach { source ->
                require(source.sourceId.isNotBlank() && source.name.isNotBlank()) { "历史来源缺少标识或名称" }
                require(LocalDate.parse(source.localDate).toString() == session.localDate) { "每日来源日期不一致" }
                require(source.historicalSource == null || source.historicalSource.localDate == source.localDate) { "历史来源日期不一致" }
                require(source.itemIds.distinct().size == source.itemIds.size && source.itemIds.all { id -> session.items.any { it.id == id } }) {
                    "历史来源动作引用无效"
                }
            }
            require(session.results.map { it.itemId to it.setIndex }.distinct().size == session.results.size) { "同一训练组不能重复记录" }
            session.snapshot?.items?.forEach { p -> require(session.exercises.any { it.id == p.exerciseId }) { "训练缺少开始时动作说明" } }
            require(session.exercises.map { it.id }.distinct().size == session.exercises.size) { "动作说明不能重复" }
            require(session.items.map { it.id }.distinct().size == session.items.size) { "实际训练动作标识不能重复" }
            require(session.items.all { p -> session.exercises.any { it.id == p.exerciseId } }) { "实际训练缺少对应动作说明" }
            require(session.skippedItems.all { id -> session.items.any { it.id == id } }) { "跳过的动作不存在" }
            session.items.forEach { it.sets.forEach(::checkTarget) }
            session.results.forEach { result ->
                val item = session.items.singleOrNull { it.id == result.itemId }
                val source = session.sourceRecords.firstOrNull { result.itemId in it.itemIds }
                val historical = source?.historicalSource != null
                require(item != null && result.setIndex >= 0 && (historical || result.setIndex in item.sets.indices)) { "实际组没有对应计划组" }
                val sourceStart = source?.startedAt ?: session.startedAt.takeIf { source == null || session.sourceRecords.isEmpty() }
                if (!historical && sourceStart != null) require(result.completedAt != null) { "实时完成组缺少真实完成时间" }
                if (result.completedAt != null && sourceStart != null) require(result.completedAt >= sourceStart) { "完成组时间早于训练开始" }
                require(result.side in setOf("both", "left", "right")) { "左右侧记录不正确" }
                result.segments.forEach { segment ->
                    require(segment.reps > 0 && result.seconds == null) { "分段次数或时长不正确" }
                    checkLoad(segment.load)
                }
                if (result.segments.isNotEmpty()) {
                    require(result.reps == result.segments.sumOf { it.reps }) { "分段次数与整组次数不一致" }
                    require(result.load == null) { "分段重量不能同时填写整组重量" }
                } else checkTarget(SetTarget(result.reps, seconds = result.seconds, load = result.load, side = result.side, restSeconds = 0))
            }
            if (!restoreSession) previous?.let { old ->
                require(old.localDate == session.localDate && old.snapshot == session.snapshot && old.startedAt == session.startedAt) {
                    "已有训练日期、开始时间及原计划不能改写"
                }
                val replacedItemIds = old.sourceRecords.filter { it.sourceId in replaceSourceIds }.flatMapTo(hashSetOf()) { it.itemIds }
                val replacedExerciseIds = old.items.filter { it.id in replacedItemIds }.mapTo(hashSetOf()) { it.exerciseId }
                old.exercises.filterNot { it.id in replacedExerciseIds }.forEach { exercise ->
                    require(exercise in session.exercises) { "已经记录的动作说明不能改写" }
                }
                old.sourceRecords.forEach { source ->
                    val nextSource = session.sourceRecords.singleOrNull { it.sourceId == source.sourceId }
                    val replaceSource = source.sourceId in replaceSourceIds
                    require(nextSource != null && nextSource.localDate == source.localDate && nextSource.itemIds == source.itemIds &&
                        (replaceSource || nextSource.copy(notes = "") == source.copy(notes = ""))) {
                        "已有训练来源事实不能改写、删除或重新关联"
                    }
                }
                old.skippedItems.forEach { id ->
                    if (id !in replacedItemIds) require(id in session.skippedItems) { "已跳过动作不能被删除" }
                }
                old.items.forEach { oldItem ->
                    val nextItem = session.items.singleOrNull { it.id == oldItem.id }
                    require(nextItem != null && oldItem.exerciseId == nextItem.exerciseId &&
                        oldItem.equipmentId == nextItem.equipmentId &&
                        nextItem.sets.size >= oldItem.sets.size &&
                        nextItem.sets.take(oldItem.sets.size) == oldItem.sets) {
                        "已有动作、器械及目标不能改写；仅可在末尾追加目标"
                    }
                }
                val oldResultKeys = old.results.mapTo(hashSetOf()) { it.itemId to it.setIndex }
                old.results.forEach { result ->
                    session.results.singleOrNull { it.itemId == result.itemId && it.setIndex == result.setIndex }?.let { updated ->
                        require(updated.completedAt == result.completedAt) { "既有完成组的真实时间不能改写" }
                    }
                }
                val newlyImportedItems = session.sourceRecords
                    .filter { source -> old.sourceRecords.none { it.sourceId == source.sourceId } }
                    .flatMapTo(hashSetOf()) { it.itemIds }
                if (resumesHistory && requireNewResultTimes) session.results
                    .filter { (it.itemId to it.setIndex) !in oldResultKeys && it.itemId !in newlyImportedItems }
                    .forEach { result ->
                        require(result.completedAt != null) { "继续历史记录新增完成组必须保存真实完成时间" }
                    }
            }
            session.id
        }
    }
    require(entityId == doc.id) { "资料标识与内容不一致" }
}

/** 器械是否仍然可用是训练开始时的条件，不让器械类别变动破坏计划的保存和备份恢复。 */
fun validatePlanEquipment(plan: Plan, equipment: List<Equipment>, lookup: (Kind, String) -> Document?) {
    plan.items.forEach { item ->
        val exercise = lookup(Kind.exercise, item.exerciseId)!!.value<Exercise>()
        val equipmentUnknown = SOURCE_UNKNOWN_EQUIPMENT_TAG in exercise.equipmentTags
        if (equipmentUnknown) {
            require(item.equipmentId == null) { "${exercise.name}：器械条件未知，不能关联具体器械" }
        } else {
            require(exerciseAvailable(exercise, plan.gymId, equipment)) { "${exercise.name}：健身房缺少所需器械，请调整计划或器械记录" }
            item.equipmentId?.let { id ->
                val selected = lookup(Kind.equipment, id)!!.value<Equipment>()
                require(selected.gymId == plan.gymId) { "${exercise.name}：器械不在计划健身房，请重新选择" }
                require(exercise.equipmentTags.isEmpty() || exercise.equipmentTags.first() in selected.tags) { "${exercise.name}：所选器械不符合动作条件，请重新选择" }
            }
        }
    }
}

/** 未知器械条件不代表无需器械或当前健身房具备相应设备。 */
fun exerciseAvailable(exercise: Exercise, gymId: String?, equipment: List<Equipment>): Boolean =
    SOURCE_UNKNOWN_EQUIPMENT_TAG !in exercise.equipmentTags &&
        (gymId == null || exercise.equipmentTags.isEmpty() ||
            exercise.equipmentTags.all { tag -> equipment.any { it.gymId == gymId && tag in it.tags } })

fun recommendExercises(exercises: List<Exercise>, muscles: Set<String>, gymId: String?, equipment: List<Equipment>, type: String): List<Exercise> =
    exercises.filter {
        it.type == type && (it.muscles.any(muscles::contains) || type == "stretch" && it.secondaryMuscles.any(muscles::contains)) &&
            exerciseAvailable(it, gymId, equipment)
    }
        .sortedWith(compareByDescending<Exercise> { ex -> ex.muscles.count(muscles::contains) }.thenBy { it.name })

/** Any real group advances the next training slot, regardless of manual session completion. */
fun nextSlot(program: Program, sessions: List<Session>, override: ArrangementOverride? = null): SplitSlot {
    data class Candidate(val date: LocalDate, val time: Long, val id: String, val slotIndex: Int, val overrideId: String?)
    val candidates = mutableListOf<Candidate>()
    for (session in sessions) {
        if (session.results.isEmpty()) continue
        val completedItems = session.results.mapTo(hashSetOf()) { it.itemId }
        val representedItems = session.sourceRecords.flatMapTo(hashSetOf()) { it.itemIds }
        val nativeItems = session.items.map { it.id }.filterNot { it in representedItems }
        val sources = session.sourceRecords + if (nativeItems.isNotEmpty()) listOf(
            SessionSource(session.id, session.name, session.localDate, session.snapshot, session.startedAt,
                session.endedAt, null, session.arrangementOverrideId, session.splitSlotName, nativeItems)
        ) else emptyList()
        for (source in sources) {
            if (source.itemIds.none(completedItems::contains)) continue
            val snapshot = source.snapshot
            val directIndex = when {
                snapshot == null -> -1
                snapshot.programId != program.id -> continue
                else -> program.slots.indexOfFirst { it.id == snapshot.slotId }
            }
            val label = splitTrainingLabel(source.splitSlotName)
            val labelMatches = program.slots.indices.filter { splitTrainingLabel(program.slots[it].name) == label && label != null }
            val slotIndex = if (snapshot != null) directIndex
                else if (labelMatches.size == 1) labelMatches.single() else -1
            if (slotIndex >= 0) candidates += Candidate(
                LocalDate.parse(source.localDate), source.endedAt ?: source.startedAt ?: Long.MIN_VALUE,
                source.sourceId, slotIndex, source.arrangementOverrideId
            )
        }
    }
    val latestDate = candidates.maxOfOrNull { it.date }
    val latestDay = candidates.filter { it.date == latestDate }
    val knownTimes = latestDay.filter { it.time != Long.MIN_VALUE }
    val latest = if (knownTimes.isNotEmpty()) {
        val latestTime = knownTimes.maxOf { it.time }
        knownTimes.filter { it.time == latestTime }
    } else latestDay
    val lastSlot = latest.maxByOrNull { it.id }?.slotIndex
    val consumed = override != null && candidates.any { it.overrideId == override.id }
    if (override != null && !consumed) return program.slots.single { it.id == override.slotId }
    if (lastSlot == null) return program.slots.first()
    return program.slots[(lastSlot + 1) % program.slots.size]
}

/** Plan snapshots count as executed only when their source contributed a real set. */
fun recordedPlanIds(sessions: List<Session>): Set<String> = sessions.asSequence()
    .flatMap { session ->
        if (session.results.isEmpty()) emptySequence()
        else {
            val completedItems = session.results.mapTo(hashSetOf()) { it.itemId }
            val representedItems = session.sourceRecords.flatMapTo(hashSetOf()) { it.itemIds }
            val sourcePlans = session.sourceRecords.asSequence()
                .filter { source -> source.itemIds.any(completedItems::contains) }
                .map { it.snapshot?.id }
            val nativePlan = if (completedItems.any { it !in representedItems }) sequenceOf(session.snapshot?.id) else emptySequence()
            sourcePlans + nativePlan
        }
    }
    .filterNotNull()
    .toSet()
/** 每条 canonical 训练记录都使用已保存的业务本地日期。 */
fun sessionDate(session: Session): LocalDate = LocalDate.parse(session.localDate)

val sessionComparator: Comparator<Session> = compareByDescending<Session> { sessionDate(it) }
    .thenByDescending { it.startedAt ?: Long.MIN_VALUE }.thenBy { it.id }

fun summarizeHistory(sessions: List<Session>, activities: List<ActivityRecord> = emptyList()): HistorySummary {
    val ordered = sessions.sortedWith(sessionComparator)
    val counts = linkedMapOf<String, Int>()
    var strengthSets = 0
    var stretchSets = 0
    ordered.forEach { session -> session.results.forEach { result ->
        val item = session.items.single { it.id == result.itemId }
        val exercise = session.exercises.single { it.id == item.exerciseId }
        if (exercise.type == "stretch") stretchSets++ else {
            strengthSets++
            exercise.muscles.forEach { muscle -> counts[muscle] = (counts[muscle] ?: 0) + 1 }
        }
    } }
    return HistorySummary(ordered, strengthSets, stretchSets, counts, activities.sortedWith(compareByDescending<ActivityRecord> { it.localDate }.thenBy { it.id }))
}

fun loadText(load: Load?): String = if (load == null) "未记录外部负重" else {
    val amount = if (load.value % 1.0 == 0.0) load.value.toLong().toString() else load.value.toString()
    val meaning = when (load.meaning) {
        "per_hand" -> " · 单只"
        "per_side" -> " · 单侧"
        "unknown" -> ""
        else -> " · 总重量"
    }
    "$amount ${load.unit}$meaning${if (load.mode == "assistance") " · 助力" else ""}"
}
fun resultText(result: SetResult): String {
    val value = result.segments.takeIf { it.isNotEmpty() }?.joinToString("，") {
        "${loadText(it.load)} × ${it.reps} 次"
    } ?: listOfNotNull(result.reps?.let { "$it 次" }, result.seconds?.let { "$it 秒" }, result.load?.let(::loadText))
        .joinToString(" · ")
    return listOf(value, sideText(result.side)).filter { it.isNotBlank() }.joinToString(" · ")
}
fun sideText(side: String): String = when (side) { "left" -> "左侧"; "right" -> "右侧"; else -> "双侧" }
fun targetText(t: SetTarget): String = listOfNotNull(
    t.reps?.let { if (t.repsMax != null && t.repsMax != it) "$it–${t.repsMax} 次" else "$it 次" },
    t.seconds?.let { "$it 秒" }, sideText(t.side), loadText(t.load), "休息 ${t.restSeconds} 秒"
).joinToString(" · ")
