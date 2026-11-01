package top.e404.training.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import top.e404.training.shared.validId
import java.net.URI

@Serializable
data class ExerciseGuide(
    val sourceUrl: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val description: String,
    val tips: List<String>,
    val mistakes: List<String>,
    val instructions: List<String>,
)

class ExerciseGuides private constructor(private val guides: Map<String, ExerciseGuide>) {
    operator fun get(exerciseId: String): ExerciseGuide? = guides[exerciseId]

    companion object {
        fun load(): ExerciseGuides {
            val stream = requireNotNull(ExerciseGuides::class.java.classLoader.getResourceAsStream("smartworkout-guides.json")) {
                "缺少smartworkout-guides.json，无法加载线上动作教学资料"
            }
            val guides = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                top.e404.training.shared.wireJson.decodeFromString<Map<String, ExerciseGuide>>(reader.readText())
            }
            guides.forEach { (exerciseId, guide) ->
                require(validId(exerciseId)) { "smartworkout-guides.json包含无效动作标识：$exerciseId" }
                requireWebUrl(guide.sourceUrl, "sourceUrl", exerciseId)
                guide.imageUrl?.let { requireWebUrl(it, "imageUrl", exerciseId) }
                guide.videoUrl?.let { requireWebUrl(it, "videoUrl", exerciseId) }
            }
            return ExerciseGuides(guides)
        }

        private fun requireWebUrl(value: String, field: String, exerciseId: String) {
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri != null && uri.isAbsolute &&
                (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
                "smartworkout-guides.json中动作${exerciseId}的${field}必须是有效HTTP(S)网址"
            }
        }
    }
}
