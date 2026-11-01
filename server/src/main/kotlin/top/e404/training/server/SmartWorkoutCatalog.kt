package top.e404.training.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import top.e404.training.shared.Document
import top.e404.training.shared.Exercise
import top.e404.training.shared.Kind
import top.e404.training.shared.validId
import top.e404.training.shared.wireJson
import java.net.URI

@Serializable
data class SmartWorkoutExerciseEntry(
    val sourceId: String,
    val name: String,
    val categories: List<String>,
    val sourceBodyPart: String,
    val mappedMuscles: List<String>?,
    val equipmentCategories: List<String>,
    val mappedEquipmentTags: List<String>?,
)

@Serializable
data class SmartWorkoutSelectionRequest(val type: String)

@Serializable
private data class SmartWorkoutSelectionMapping(
    val muscles: List<String>,
    val secondaryMuscles: List<String>,
    val equipmentTags: List<String>,
    val unilateral: Boolean,
    val bodyweight: Boolean,
    val loadMode: String,
)

class SmartWorkoutCatalog private constructor(
    private val entries: List<SmartWorkoutExerciseEntry>,
    private val sourceGuides: Map<String, ExerciseGuide>,
    private val selections: Map<String, SmartWorkoutSelectionMapping>,
    private val associations: Map<String, String>,
) {
    private val bySourceId = entries.associateBy(SmartWorkoutExerciseEntry::sourceId)

    fun list(): List<SmartWorkoutExerciseEntry> = entries

    fun guide(sourceId: String): ExerciseGuide? = sourceGuides[sourceId]

    fun associatedExerciseId(sourceId: String, type: String): String? = associations[associationKey(sourceId, type)]

    fun exerciseDocument(sourceId: String, type: String): Document? {
        require(type == "strength" || type == "stretch") { "动作类型必须来自当前 strength/stretch 添加上下文" }
        val entry = bySourceId[sourceId] ?: return null
        val selection = requireNotNull(selections[sourceId]) { "动作目录缺少选择映射：$sourceId" }
        val exerciseId = "${sourceId}_$type"
        val exercise = Exercise(
            id = exerciseId,
            name = entry.name,
            type = type,
            muscles = selection.muscles,
            secondaryMuscles = selection.secondaryMuscles,
            equipmentTags = selection.equipmentTags,
            instructions = requireNotNull(sourceGuides[sourceId]) { "动作目录缺少教学资料：$sourceId" }.instructions,
            unilateral = selection.unilateral,
            bodyweight = selection.bodyweight,
            measurement = if (type == "stretch") "seconds" else "reps",
            notes = "",
            loadMode = selection.loadMode,
        )
        return Document(
            kind = Kind.exercise,
            id = exerciseId,
            data = wireJson.parseToJsonElement(wireJson.encodeToString(exercise)) as JsonObject,
        )
    }

    companion object {
        private val json = wireJson

        fun load(): SmartWorkoutCatalog {
            val classLoader = SmartWorkoutCatalog::class.java.classLoader
            val entries = readResource(classLoader, "smartworkout-directory.json")
                .let { json.decodeFromString<List<SmartWorkoutExerciseEntry>>(it) }
            val sourceGuides = readResource(classLoader, "smartworkout-source-guides.json")
                .let { json.decodeFromString<Map<String, ExerciseGuide>>(it) }
            val selections = readResource(classLoader, "smartworkout-selections.json")
                .let { json.decodeFromString<Map<String, SmartWorkoutSelectionMapping>>(it) }
            val associations = readResource(classLoader, "smartworkout-associations.json")
                .let { json.decodeFromString<Map<String, String>>(it) }
            require(entries.isNotEmpty()) { "smartworkout-directory.json为空" }
            require(entries.map(SmartWorkoutExerciseEntry::sourceId).distinct().size == entries.size) {
                "smartworkout-directory.json含重复sourceId"
            }
            val sourceIds = entries.mapTo(hashSetOf(), SmartWorkoutExerciseEntry::sourceId)
            require(sourceGuides.keys == sourceIds) { "smartworkout-source-guides.json与动作目录不一致" }
            entries.forEach { entry ->
                require(validId(entry.sourceId)) { "SmartWorkout目录含无效sourceId：${entry.sourceId}" }
                require(entry.categories.all(String::isNotBlank)) { "SmartWorkout动作${entry.sourceId}含空来源分类" }
            }
            sourceGuides.forEach { (sourceId, guide) ->
                requireHttpUrl(guide.sourceUrl, "sourceUrl", sourceId)
                guide.imageUrl?.let { requireHttpUrl(it, "imageUrl", sourceId) }
                guide.videoUrl?.let { requireHttpUrl(it, "videoUrl", sourceId) }
            }
            require(selections.keys == entries.mapTo(hashSetOf(), SmartWorkoutExerciseEntry::sourceId)) {
                "smartworkout-selections.json与动作目录不一致"
            }
            associations.forEach { (key, exerciseId) ->
                val (sourceId, type) = key.split(":", limit = 2).let { parts ->
                    require(parts.size == 2) { "smartworkout-associations.json键格式错误：$key" }
                    parts[0] to parts[1]
                }
                require(sourceId in selections && type in setOf("strength", "stretch") && validId(exerciseId)) {
                    "smartworkout-associations.json含无效关联：$key"
                }
            }
            return SmartWorkoutCatalog(entries, sourceGuides, selections, associations)
        }

        private fun readResource(classLoader: ClassLoader, name: String): String {
            val stream = requireNotNull(classLoader.getResourceAsStream(name)) { "缺少服务端资源：$name" }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        private fun requireHttpUrl(value: String, field: String, sourceId: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri != null && uri.isAbsolute &&
                (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
                "SmartWorkout动作${sourceId}的${field}必须是有效HTTP(S)网址"
            }
        }

        private fun associationKey(sourceId: String, type: String): String = "$sourceId:$type"
    }
}
