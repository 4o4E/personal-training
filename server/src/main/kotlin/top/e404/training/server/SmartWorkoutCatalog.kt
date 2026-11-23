package top.e404.training.server

import kotlinx.serialization.Serializable
import top.e404.training.shared.Document
import top.e404.training.shared.SmartWorkoutExerciseEntry
import top.e404.training.shared.SmartWorkoutGuide
import top.e404.training.shared.SmartWorkoutCatalog as SharedSmartWorkoutCatalog

@Serializable
data class SmartWorkoutSelectionRequest(val type: String)

typealias ExerciseGuide = SmartWorkoutGuide

class SmartWorkoutCatalog private constructor(private val catalog: SharedSmartWorkoutCatalog) {
    fun list(): List<SmartWorkoutExerciseEntry> = catalog.list()

    fun guide(sourceId: String): SmartWorkoutGuide? = catalog.guide(sourceId)

    fun associatedExerciseId(sourceId: String, type: String): String? =
        catalog.associatedExerciseId(sourceId, type)

    fun exerciseDocument(sourceId: String, type: String): Document? =
        catalog.exerciseDocument(sourceId, type)

    companion object {
        fun load(): SmartWorkoutCatalog = SmartWorkoutCatalog(SharedSmartWorkoutCatalog.load())
    }
}
