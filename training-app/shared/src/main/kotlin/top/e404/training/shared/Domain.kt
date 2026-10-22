package top.e404.training.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate

val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

enum class Kind { muscle, media, exercise, gym, equipment, program, plan, session }

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
@Serializable data class SetResult(
    val itemId: String, val setIndex: Int, val reps: Int? = null, val seconds: Int? = null,
    val load: Load? = null, val side: String = "both", val completedAt: Long
)
/** 训练开始时保存计划和动作说明；未来编辑计划不能重写这次实际训练。 */
@Serializable data class Session(
    val id: String, val name: String, val snapshot: Plan, val exercises: List<Exercise>,
    val startedAt: Long, val endedAt: Long? = null, val status: String,
    val results: List<SetResult> = emptyList(), val notes: String = "",
    val items: List<PlanItem> = snapshot.items, val skippedItems: List<String> = emptyList()
)
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
@Serializable data class Backup(val formatVersion: Int = 1, val documents: List<Document>, val exportedAt: Long)
@Serializable data class ApiError(val message: String)
@Serializable data class TimerState(
    val deadline: Long, val label: String, val sessionId: String,
    val itemId: String, val setIndex: Int, val type: String = "rest"
)
@Serializable data class HistorySummary(val sessions: List<Session>, val strengthSets: Int, val stretchSets: Int, val strengthSetsByMuscle: Map<String, Int>)

inline fun <reified T> Document.value(): T = wireJson.decodeFromJsonElement(data)
inline fun <reified T> document(kind: Kind, id: String, value: T): Document =
    Document(kind, id, wireJson.encodeToJsonElement(value).jsonObject)

fun Document.displayName(): String = data["name"]?.jsonPrimitive?.content ?: id
fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))

private fun checkLoad(load: Load?) {
    if (load == null) return
    require(load.value.isFinite() && load.value >= 0) { "重量必须是非负有限数值" }
    require(load.unit in setOf("kg", "lb")) { "重量单位必须为 kg 或 lb" }
    require(load.meaning in setOf("total", "per_hand", "per_side")) { "重量含义不正确" }
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

/** 同一入口核验客户端和接口数据；只校验业务边界，不推测或换算器械读数。 */
fun validateDocument(doc: Document, list: (Kind) -> List<Document>, lookup: (Kind, String) -> Document?) {
    require(validId(doc.id)) { "资料标识不正确" }
    if (doc.deleted) {
        fun plans() = list(Kind.plan).map { it.value<Plan>() }
        val inUse = when (doc.kind) {
            Kind.gym -> list(Kind.equipment).any { it.value<Equipment>().gymId == doc.id } || plans().any { it.gymId == doc.id }
            Kind.equipment -> plans().any { p -> p.items.any { it.equipmentId == doc.id } }
            Kind.program -> plans().any { it.programId == doc.id }
            Kind.exercise -> plans().any { p -> p.items.any { it.exerciseId == doc.id } }
            Kind.media -> list(Kind.exercise).any { doc.id in it.value<Exercise>().mediaIds } ||
                list(Kind.equipment).any { it.value<Equipment>().mediaId == doc.id } ||
                list(Kind.session).any { s -> s.value<Session>().exercises.any { doc.id in it.mediaIds } }
            Kind.muscle -> list(Kind.exercise).any { e -> e.value<Exercise>().let { doc.id in it.muscles || doc.id in it.secondaryMuscles } } ||
                list(Kind.program).any { p -> p.value<Program>().slots.any { doc.id in it.muscles } } ||
                list(Kind.session).any { s -> s.value<Session>().exercises.any { doc.id in it.muscles || doc.id in it.secondaryMuscles } }
            Kind.plan, Kind.session -> false
        }
        require(!inUse) { "该资料仍被计划、器械或训练记录使用，请先解除关联" }
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
            require(it.meaning in setOf("total", "per_hand", "per_side")) { "器械重量含义不正确" }
            it.mediaId?.let { id -> ref(Kind.media, id) }
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
            require(session.name.isNotBlank() && session.startedAt > 0) { "训练缺少名称或开始时间" }
            require(session.status in setOf("active", "completed", "cancelled")) { "训练状态不正确" }
            require((session.status == "active") == (session.endedAt == null)) { "训练状态与结束时间不一致" }
            require(session.endedAt == null || session.endedAt >= session.startedAt) { "训练结束时间早于开始时间" }
            require(session.status != "completed" || session.results.isNotEmpty()) { "没有完成组的训练不能标记完成" }
            require(session.results.map { it.itemId to it.setIndex }.distinct().size == session.results.size) { "同一训练组不能重复记录" }
            require(session.snapshot.items.all { p -> session.exercises.any { it.id == p.exerciseId } }) { "训练缺少开始时动作说明" }
            require(session.exercises.map { it.id }.distinct().size == session.exercises.size) { "动作说明不能重复" }
            require(session.items.map { it.id }.distinct().size == session.items.size) { "实际训练动作标识不能重复" }
            require(session.items.all { p -> session.exercises.any { it.id == p.exerciseId } }) { "实际训练缺少对应动作说明" }
            require(session.skippedItems.all { id -> session.items.any { it.id == id } }) { "跳过的动作不存在" }
            session.items.forEach { it.sets.forEach(::checkTarget) }
            session.results.forEach { r ->
                val p = session.items.singleOrNull { it.id == r.itemId }
                require(p != null && r.setIndex in p.sets.indices) { "实际组没有对应计划组" }
                require(r.completedAt >= session.startedAt) { "完成组时间早于训练开始" }
                checkTarget(SetTarget(r.reps, seconds = r.seconds, load = r.load, side = r.side, restSeconds = 0))
            }
            lookup(Kind.session, session.id)?.takeUnless { it.deleted }?.value<Session>()?.let { old ->
                require(old.snapshot == session.snapshot && old.startedAt == session.startedAt) { "已经开始的训练不能改写原计划内容" }
                require(old.exercises.all { it in session.exercises }) { "已经记录的动作说明不能改写" }
                old.results.forEach { result ->
                    require(old.items.single { it.id == result.itemId } == session.items.singleOrNull { it.id == result.itemId }) { "已经完成组的动作与目标不能被替换" }
                }
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
fun nextSlot(program: Program, sessions: List<Session>): SplitSlot {
    val last = sessions.filter { it.status == "completed" && it.snapshot.programId == program.id }
        .maxWithOrNull(compareBy<Session> { it.endedAt ?: 0 }.thenBy { it.id })
    val index = last?.let { s -> program.slots.indexOfFirst { it.id == s.snapshot.slotId } } ?: -1
    return program.slots[(index + 1) % program.slots.size]
}

fun summarizeHistory(sessions: List<Session>): HistorySummary {
    val ordered = sessions.sortedByDescending { it.startedAt }
    val counts = linkedMapOf<String, Int>()
    var strengthSets = 0
    var stretchSets = 0
    // 实际完成的组始终保留；取消训练不推进分化，但不能抹去已经进行的练习。
    ordered.forEach { s -> s.results.forEach { r ->
        val item = s.items.single { it.id == r.itemId }
        val exercise = s.exercises.single { it.id == item.exerciseId }
        if (exercise.type == "stretch") stretchSets++ else {
            strengthSets++
            exercise.muscles.forEach { m -> counts[m] = (counts[m] ?: 0) + 1 }
        }
    } }
    return HistorySummary(ordered, strengthSets, stretchSets, counts)
}

fun loadText(load: Load?): String = if (load == null) "未记录外部负重" else {
    val amount = if (load.value % 1.0 == 0.0) load.value.toLong().toString() else load.value.toString()
    val meaning = when (load.meaning) { "per_hand" -> "单只"; "per_side" -> "单侧"; else -> "总重量" }
    "$amount ${load.unit} · $meaning${if (load.mode == "assistance") " · 助力" else ""}"
}
fun sideText(side: String): String = when (side) { "left" -> "左侧"; "right" -> "右侧"; else -> "双侧" }
fun targetText(t: SetTarget): String = listOfNotNull(
    t.reps?.let { if (t.repsMax != null && t.repsMax != it) "$it–${t.repsMax} 次" else "$it 次" },
    t.seconds?.let { "$it 秒" }, sideText(t.side), loadText(t.load), "休息 ${t.restSeconds} 秒"
).joinToString(" · ")
