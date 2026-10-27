package top.e404.training.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

enum class Kind { muscle, media, exercise, gym, equipment, program, plan, session, arrangement }

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
@Serializable data class ArrangementOverride(val id: String, val slotId: String)
@Serializable data class Arrangement(
    val id: String = "current", val programId: String? = null,
    val selectedPlans: Map<String, Map<String, String>> = emptyMap(),
    val nextOverrides: Map<String, ArrangementOverride> = emptyMap()
)
/** 训练开始时保存计划和动作说明；历史训练可以明确缺少计划和精确时间。 */
@Serializable data class Session(
    val id: String, val name: String, val snapshot: Plan?, val exercises: List<Exercise>,
    val startedAt: Long?, val endedAt: Long? = null, val status: String,
    val results: List<SetResult> = emptyList(), val notes: String = "",
    val items: List<PlanItem> = snapshot?.items ?: emptyList(), val skippedItems: List<String> = emptyList(),
    val historicalSource: HistoricalSource? = null, val arrangementOverrideId: String? = null,
    val splitSlotName: String? = null
)

fun sessionTrainingLabel(session: Session): String = when (session.splitSlotName) {
    null -> "自由锻炼"
    "推类训练", "推" -> "推"
    "拉类训练", "拉" -> "拉"
    "腿部训练", "腿类训练", "腿" -> "腿"
    else -> requireNotNull(session.splitSlotName)
}

@Serializable data class Document(
    val kind: Kind, val id: String, val data: JsonObject,
    val revision: Long = 0, val deleted: Boolean = false
)
@Serializable data class Mutation(val document: Document, val baseRevision: Long)
@Serializable data class SyncRequest(val cursor: Long = 0, val mutations: List<Mutation> = emptyList())
@Serializable data class Conflict(val local: Document, val remote: Document)
@Serializable data class SyncResponse(
    val cursor: Long, val documents: List<Document>, val accepted: List<Document> = emptyList(),
    val conflicts: List<Conflict> = emptyList()
)
@Serializable data class Backup(val formatVersion: Int = 3, val documents: List<Document>, val exportedAt: Long)
@Serializable data class ApiError(val message: String)
@Serializable data class TimerState(
    val deadline: Long, val label: String, val sessionId: String,
    val itemId: String, val setIndex: Int, val type: String = "rest",
    val notified: Boolean = false
)
@Serializable data class HistorySummary(val sessions: List<Session>, val strengthSets: Int, val stretchSets: Int, val strengthSetsByMuscle: Map<String, Int>)

inline fun <reified T> Document.value(): T = wireJson.decodeFromJsonElement(
    if (kind == Kind.session) withoutDeprecatedHistoricalText(this).data else data
)
inline fun <reified T> document(kind: Kind, id: String, value: T): Document =
    Document(kind, id, wireJson.encodeToJsonElement(value).jsonObject)

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

private fun Document.hasDeprecatedHistoricalText(): Boolean =
    data["historicalSource"]?.takeUnless { it is JsonNull }?.jsonObject?.containsKey("originalText") == true

/** 同一入口核验客户端和接口数据；只校验业务边界，不推测或换算器械读数。 */
fun validateDocument(doc: Document, list: (Kind) -> List<Document>, lookup: (Kind, String) -> Document?) {
    require(validId(doc.id)) { "资料标识不正确" }
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
            Kind.session, Kind.arrangement -> false
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
            require(it.name.isNotBlank() && it.instructions.isNotEmpty()) { "动作需要中文名称和执行说明" }
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
                    ?: throw IllegalArgumentException("下次安排引用的分化方案不存在：$programId")
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
                p.equipmentId?.let { id ->
                    ref(Kind.equipment, id)
                }
            }
        }.id
        Kind.session -> doc.value<Session>().also { session ->
            require(!doc.hasDeprecatedHistoricalText()) { "新写入不能包含已废弃的 historicalSource.originalText" }
            require(session.name.isNotBlank()) { "训练缺少名称" }
            require(session.status in setOf("active", "completed", "cancelled")) { "训练状态不正确" }
            val history = session.historicalSource
            if (history == null) {
                require(session.snapshot != null && session.startedAt != null && session.startedAt > 0) { "实时训练缺少计划或开始时间" }
                require((session.status == "active") == (session.endedAt == null)) { "训练状态与结束时间不一致" }
                require(session.endedAt == null || session.endedAt >= session.startedAt) { "训练结束时间早于训练开始" }
            } else {
                require(session.status != "active" && session.snapshot == null && session.startedAt == null) { "历史训练不能作为实时训练或包含推断的计划时间" }
                LocalDate.parse(history.localDate)
                require(history.sourceId.isNotBlank()) { "历史来源不能为空" }
            }
            require(session.status != "completed" || session.results.isNotEmpty()) { "没有完成组的训练不能标记完成" }
            require(session.results.map { it.itemId to it.setIndex }.distinct().size == session.results.size) { "同一训练组不能重复记录" }
            session.snapshot?.items?.forEach { p -> require(session.exercises.any { it.id == p.exerciseId }) { "训练缺少开始时动作说明" } }
            require(session.exercises.map { it.id }.distinct().size == session.exercises.size) { "动作说明不能重复" }
            require(session.items.map { it.id }.distinct().size == session.items.size) { "实际训练动作标识不能重复" }
            require(session.items.all { p -> session.exercises.any { it.id == p.exerciseId } }) { "实际训练缺少对应动作说明" }
            require(session.skippedItems.all { id -> session.items.any { it.id == id } }) { "跳过的动作不存在" }
            session.items.forEach { it.sets.forEach(::checkTarget) }
            session.results.forEach { result ->
                val item = session.items.singleOrNull { it.id == result.itemId }
                require(item != null && result.setIndex >= 0 && (history != null || result.setIndex in item.sets.indices)) { "实际组没有对应计划组" }
                if (history == null) require(result.completedAt != null) { "实时完成组缺少真实完成时间" }
                if (result.completedAt != null && session.startedAt != null) require(result.completedAt >= session.startedAt) { "完成组时间早于训练开始" }
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
            lookup(Kind.session, session.id)?.takeUnless { it.deleted }?.value<Session>()?.let { old ->
                require(old.snapshot == session.snapshot && old.startedAt == session.startedAt && old.historicalSource == session.historicalSource) { "已有训练来源及开始内容不能改写" }
                require(old.exercises.all { it in session.exercises }) { "已经记录的动作说明不能改写" }
                old.results.forEach { result -> require(old.items.single { it.id == result.itemId } == session.items.singleOrNull { it.id == result.itemId }) { "已经完成组的动作与目标不能被替换" } }
                require(old.status == "active" || old.status == session.status) { "已经结束的训练不能重新开始或取消" }
            }
        }.id
    }
    require(entityId == doc.id) { "资料标识与内容不一致" }
}

/** 器械是否仍然可用是训练开始时的条件，不让器械类别变动破坏计划的保存和备份恢复。 */
fun validatePlanEquipment(plan: Plan, equipment: List<Equipment>, lookup: (Kind, String) -> Document?) {
    plan.items.forEach { item ->
        val exercise = lookup(Kind.exercise, item.exerciseId)!!.value<Exercise>()
        require(exerciseAvailable(exercise, plan.gymId, equipment)) { "${exercise.name}：健身房缺少所需器械，请调整计划或器械记录" }
        item.equipmentId?.let { id ->
            val selected = lookup(Kind.equipment, id)!!.value<Equipment>()
            require(selected.gymId == plan.gymId) { "${exercise.name}：器械不在计划健身房，请重新选择" }
            require(exercise.equipmentTags.isEmpty() || exercise.equipmentTags.first() in selected.tags) { "${exercise.name}：所选器械不符合动作条件，请重新选择" }
        }
    }
}

/** 候选严格按所选健身房器械筛选；未选健身房时展示条件，不宣称现场可用。 */
fun exerciseAvailable(exercise: Exercise, gymId: String?, equipment: List<Equipment>): Boolean =
    gymId == null || exercise.equipmentTags.isEmpty() ||
        exercise.equipmentTags.all { tag -> equipment.any { it.gymId == gymId && tag in it.tags } }

fun recommendExercises(exercises: List<Exercise>, muscles: Set<String>, gymId: String?, equipment: List<Equipment>, type: String): List<Exercise> =
    exercises.filter {
        it.type == type && (it.muscles.any(muscles::contains) || type == "stretch" && it.secondaryMuscles.any(muscles::contains)) &&
            exerciseAvailable(it, gymId, equipment)
    }
        .sortedWith(compareByDescending<Exercise> { ex -> ex.muscles.count(muscles::contains) }.thenBy { it.name })

/** 只有真正完成的训练推进轮换，取消及日历经过均不改变下一项。 */
fun nextSlot(program: Program, sessions: List<Session>, override: ArrangementOverride? = null): SplitSlot {
    val completed = sessions.filter { it.status == "completed" && it.snapshot?.programId == program.id }
    val last = completed.maxWithOrNull(compareBy<Session> { it.endedAt ?: it.startedAt ?: 0 }.thenBy { it.id })
    val consumed = override?.takeIf { candidate -> completed.any { it.arrangementOverrideId == candidate.id } }
    val effectiveOverride = override?.takeUnless { it.id == consumed?.id }
    if (effectiveOverride != null) return program.slots.single { it.id == effectiveOverride.slotId }
    val index = last?.let { s -> program.slots.indexOfFirst { it.id == s.snapshot?.slotId } } ?: -1
    return program.slots[(index + 1) % program.slots.size]
}

/** 历史训练使用已记录的本地日期，实时训练从真实开始时间转换为 UTC+8。 */
fun sessionDate(session: Session): LocalDate = session.historicalSource?.let { LocalDate.parse(it.localDate) }
    ?: requireNotNull(session.startedAt) { "训练缺少真实日期" }.let {
        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
    }

val sessionComparator: Comparator<Session> = compareByDescending<Session> { sessionDate(it) }
    .thenByDescending { it.startedAt ?: Long.MIN_VALUE }.thenBy { it.id }

fun summarizeHistory(sessions: List<Session>): HistorySummary {
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
    return HistorySummary(ordered, strengthSets, stretchSets, counts)
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
