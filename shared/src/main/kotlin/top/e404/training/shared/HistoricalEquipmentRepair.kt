package top.e404.training.shared

import kotlinx.serialization.json.*

/** 仅补齐历史器械资料；直接保留原始JSON，避免提前解析旧备份而丢失来源或省略字段。 */
fun repairHistoricalEquipmentDocument(document: Document): Document {
    if (document.deleted) return document
    val repaired = when (document.kind) {
        Kind.exercise -> repairHistoricalExercise(document.data)
        Kind.session -> {
            val original = document.data["exercises"]?.jsonArray ?: return document
            val exercises = original.map { repairHistoricalExercise(it.jsonObject) }
            if (exercises == original) document.data
            else JsonObject(document.data + ("exercises" to JsonArray(exercises)))
        }
        else -> document.data
    }
    return if (repaired == document.data) document else document.copy(data = repaired)
}

private fun repairHistoricalExercise(data: JsonObject): JsonObject {
    if (!data.getValue("id").jsonPrimitive.content.startsWith("historical-") ||
        data["equipmentTags"]?.jsonArray?.isNotEmpty() == true) return data
    val name = data.getValue("name").jsonPrimitive.content
    val tags = buildList {
        if ("绳索" in name) add("cable")
        if ("哑铃" in name) add("dumbbell")
        if (isEmpty()) add(SOURCE_UNKNOWN_EQUIPMENT_TAG)
    }
    return JsonObject(data + ("equipmentTags" to JsonArray(tags.map(::JsonPrimitive))))
}
