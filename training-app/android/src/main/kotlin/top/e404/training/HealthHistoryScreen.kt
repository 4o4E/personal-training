package top.e404.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.e404.training.shared.ActivityPayload
import top.e404.training.shared.BodyPayload
import top.e404.training.shared.CaloriesPayload
import top.e404.training.shared.HealthDataset
import top.e404.training.shared.HealthRecord
import top.e404.training.shared.HeartRatePayload
import top.e404.training.shared.RestingHeartRatePayload
import top.e404.training.shared.SleepPayload
import top.e404.training.shared.StepsPayload
import top.e404.training.shared.decodePayload
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
private val healthZone = ZoneId.of("Asia/Shanghai")

internal fun LazyListScope.healthDateDetails(
    state: AppData,
    date: LocalDate,
    detailsExpanded: Boolean,
    onDetailsExpandedChange: (Boolean) -> Unit,
    expandedDataset: String?,
    onExpandedChange: (String?) -> Unit
) {
    val health = state.health
    val zone = healthZone
    val summary = health.summary?.takeIf { it.localDate == date.toString() }
    val body = health.recordsByDataset[HealthDataset.body].orEmpty()
        .asSequence().filter { it.localDate == date.toString() }.maxByOrNull { it.measuredAt }
    val longestSleep = health.recordsByDataset[HealthDataset.sleep].orEmpty()
        .asSequence().filter { it.localDate == date.toString() }
        .maxByOrNull { it.decodePayload<SleepPayload>().durationMinutes }
    val sleepPayload = longestSleep?.decodePayload<SleepPayload>()
    item(key = "health-overview") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("全天健康概览", style = MaterialTheme.typography.titleMedium)
            summary?.steps?.let { Text("步数：$it") }
            summary?.distanceMeters?.let { Text("步行距离：${healthNumber(it)} 米") }
            summary?.calorieMinutesKcal?.let { Text("活动消耗：${healthNumber(it)} 千卡") }
            summary?.restingHeartRateBpm?.let { Text("静息心率：$it 次/分钟") }
            sleepPayload?.let { Text("主睡眠：${it.durationMinutes / 60} 小时 ${it.durationMinutes % 60} 分钟") }
            body?.let { record ->
                val payload = record.decodePayload<BodyPayload>()
                val measures = buildList {
                    add("体重 ${healthNumber(payload.weightKg)} 千克")
                    payload.bmi?.let { add("BMI ${healthNumber(it)}") }
                    payload.bodyFatPct?.let { add("体脂 ${healthNumber(it)}%") }
                    payload.muscleMassKg?.let { add("肌肉 ${healthNumber(it)} 千克") }
                    payload.visceralFat?.let { add("内脏脂肪 ${healthNumber(it)}") }
                }
                Text("最近身体测量（${healthTime(record.measuredAt, zone)}）：${measures.joinToString(" · ")}")
            }
            if (summary == null && sleepPayload == null && body == null) Text("该日期尚无已取得的概览量值。")
            Text("仅展示实际取得的数据；未取得不表示为零。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onDetailsExpandedChange(!detailsExpanded) }) {
                Text(if (detailsExpanded) "收起健康明细" else "查看健康明细")
            }
        }
    }
    if (detailsExpanded) {
        HealthDataset.entries.forEach { dataset ->
            val rows = health.recordsByDataset[dataset].orEmpty().filter { it.localDate == date.toString() }
            val status = health.detailStateByDataset[dataset]
            item(key = "health-dataset-${dataset.name}") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        "${dataset.displayTitle()}：${dataset.availability(rows.size, status?.status)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = { onExpandedChange(if (expandedDataset == dataset.name) null else dataset.name) }) {
                            Text(if (expandedDataset == dataset.name) "收起" else "查看")
                        }
                    }
                }
                status?.error?.let { Text("此类详情不可用：$it", color = MaterialTheme.colorScheme.error) }
            }
            if (expandedDataset == dataset.name && rows.isNotEmpty()) {
                if (dataset == HealthDataset.sleep) {
                    val sleepRows = rows.sortedByDescending { it.decodePayload<SleepPayload>().durationMinutes }
                    itemsIndexed(sleepRows, key = { _, record -> "health-${dataset.name}-${record.sourceId}" }) { index, record ->
                        SleepRecordCard(index, record.decodePayload<SleepPayload>(), zone)
                    }
                } else {
                    items(rows, key = { record -> "health-${dataset.name}-${record.sourceId}" }) { record ->
                        if (dataset == HealthDataset.activity) {
                            val activity = record.decodePayload<ActivityPayload>()
                            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(record.displayHealthPayload(dataset, zone))
                                ActivityHeartRateChart(
                                    startAt = activity.startAt,
                                    endAt = activity.endAt,
                                    records = health.recordsByDataset[HealthDataset.heartRate].orEmpty(),
                                    detailStatus = health.detailStateByDataset[HealthDataset.heartRate]?.status
                                )
                            }
                        } else {
                            Text(record.displayHealthPayload(dataset, zone), Modifier.fillMaxWidth().padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SleepRecordCard(index: Int, sleep: SleepPayload, zone: ZoneId) {
    InfoCard(if (index == 0) "主睡眠（当日最长段）" else "其他睡眠段") {
        Text("${healthTime(sleep.startAt, zone)} – ${healthTime(sleep.endAt, zone)}")
        Text("来源时长：${sleep.durationMinutes} 分钟")
        sleep.asleepMinutes?.let { Text("睡眠：$it 分钟") }
        sleep.awakeMinutes?.let { Text("清醒：$it 分钟") }
        sleep.awakeCount?.let { Text("清醒次数：$it") }
        sleep.deepMinutes?.let { Text("深睡：$it 分钟") }
        sleep.lightMinutes?.let { Text("浅睡：$it 分钟") }
        sleep.remMinutes?.let { Text("快速眼动：$it 分钟") }
        sleep.avgHeartRate?.let { Text("平均心率：${healthNumber(it)} 次/分钟") }
        sleep.minHeartRate?.let { Text("最低心率：${healthNumber(it)} 次/分钟") }
        sleep.maxHeartRate?.let { Text("最高心率：${healthNumber(it)} 次/分钟") }
        sleep.score?.let { Text("睡眠评分：${healthNumber(it)}") }
        sleep.stages.forEach { stage ->
            Text("${stageLabel(stage.stage)}：${healthTime(stage.startAt, zone)} – ${healthTime(stage.endAt, zone)}")
        }
    }
}

internal fun HealthRecord.displayHealthPayload(dataset: HealthDataset, zone: ZoneId): String = when (dataset) {
    HealthDataset.steps -> {
        val payload = decodePayload<StepsPayload>()
        buildList {
            add("${payload.steps} 步")
            payload.distanceMeters?.let { add("距离 ${healthNumber(it)} 米") }
            payload.caloriesKcal?.let { add("消耗 ${healthNumber(it)} 千卡") }
            add(healthTime(measuredAt, zone))
        }.joinToString(" · ")
    }
    HealthDataset.calories -> {
        val payload = decodePayload<CaloriesPayload>()
        "活动消耗 ${healthNumber(payload.caloriesKcal)} 千卡 · ${healthTime(measuredAt, zone)}"
    }
    HealthDataset.heartRate -> {
        val payload = decodePayload<HeartRatePayload>()
        "${payload.bpm} 次/分钟 · ${sampleTypeLabel(payload.sampleType)} · ${healthTime(measuredAt, zone)}"
    }
    HealthDataset.restingHeartRate -> {
        val payload = decodePayload<RestingHeartRatePayload>()
        "${payload.bpm} 次/分钟 · ${healthTime(measuredAt, zone)}"
    }
    HealthDataset.body -> bodyText(decodePayload<BodyPayload>(), measuredAt, zone)
    HealthDataset.activity -> activityText(decodePayload<ActivityPayload>(), zone)
    HealthDataset.sleep -> error("睡眠记录必须使用睡眠详情卡片")
}

private fun bodyText(payload: BodyPayload, measuredAt: Long, zone: ZoneId): String {
    val values = buildList {
        add("体重：${healthNumber(payload.weightKg)} kg")
        payload.bmi?.let { add("BMI：${healthNumber(it)}") }
        payload.bodyFatPct?.let { add("体脂率：${healthNumber(it)}%") }
        payload.muscleMassKg?.let { add("肌肉量：${healthNumber(it)} kg") }
        payload.muscleRatePct?.let { add("肌肉率：${healthNumber(it)}%") }
        payload.skeletalMuscleMassKg?.let { add("骨骼肌量：${healthNumber(it)} kg") }
        payload.bodyMoistureMassKg?.let { add("水分量：${healthNumber(it)} kg") }
        payload.moistureRatePct?.let { add("水分率：${healthNumber(it)}%") }
        payload.boneMassKg?.let { add("骨量：${healthNumber(it)} kg") }
        payload.proteinMassKg?.let { add("蛋白质量：${healthNumber(it)} kg") }
        payload.proteinRatePct?.let { add("蛋白质率：${healthNumber(it)}%") }
        payload.fatMassKg?.let { add("脂肪量：${healthNumber(it)} kg") }
        payload.fatFreeBodyKg?.let { add("去脂体重：${healthNumber(it)} kg") }
        payload.visceralFat?.let { add("内脏脂肪：${healthNumber(it)}") }
        payload.basalMetabolismKcal?.let { add("基础代谢：${healthNumber(it)} 千卡") }
        payload.bodyAge?.let { add("身体年龄：${healthNumber(it)}") }
        payload.bodyScore?.let { add("身体评分：${healthNumber(it)}") }
        payload.whr?.let { add("腰臀比：${healthNumber(it)}") }
        payload.standardWeightKg?.let { add("标准体重：${healthNumber(it)} kg") }
        payload.extra?.let { addAll(it.displayBodyExtra()) }
        add(healthTime(measuredAt, zone))
    }
    return values.joinToString(" · ")
}

private val bodyExtraMetadataKeys = setOf("time", "score_standard_type")
private fun JsonObject.displayBodyExtra(): List<String> =
    displayExtra(bodyExtraLabels, bodyExtraMetadataKeys, "扩展指标")

private fun JsonObject.displayExtra(
    labels: Map<String, String>,
    excludedKeys: Set<String>,
    fallbackLabel: String
): List<String> = buildList {
    fun addExtra(path: String, value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, nested) -> addExtra("$path.$key", nested) }
            is JsonArray -> value.forEachIndexed { index, nested -> addExtra("$path[$index]", nested) }
            JsonNull -> Unit
            else -> {
                val key = path.substringAfterLast('.').substringBefore('[')
                val label = labels[path] ?: labels[key]
                val displayName = when {
                    label == null && fallbackLabel == "扩展指标" -> "$fallbackLabel（$path，单位未注明）"
                    label == null -> "$fallbackLabel（$path）"
                    fallbackLabel == "扩展指标" && key !in bodyExtraNonMeasurementKeys && !key.endsWith("_rank") -> "$label（单位未注明）"
                    else -> label
                }
                add("$displayName：${value.jsonPrimitive.contentOrNull ?: value.jsonPrimitive}")
            }
        }
    }
    this@displayExtra.forEach { (key, value) -> if (key !in excludedKeys) addExtra(key, value) }
}

private val bodyExtraNonMeasurementKeys = setOf("body_shape", "bpm", "somatotype")

private val bodyExtraLabels = mapOf(
    "body_shape" to "体型", "bone_rate" to "骨率", "bpm" to "心率（次/分钟）",
    "fat_control" to "脂肪控制量", "muscle_control" to "肌肉控制量",
    "left_lower_limb_fat_mass" to "左下肢脂肪量", "left_lower_limb_fat_rank" to "左下肢脂肪等级",
    "left_lower_limb_muscle_mass" to "左下肢肌肉量", "left_lower_limb_muscle_rank" to "左下肢肌肉等级",
    "left_upper_limb_fat_mass" to "左上肢脂肪量", "left_upper_limb_fat_rank" to "左上肢脂肪等级",
    "left_upper_limb_muscle_mass" to "左上肢肌肉量", "left_upper_limb_muscle_rank" to "左上肢肌肉等级",
    "limbs_fat_balance" to "四肢脂肪平衡", "limbs_muscle_balance" to "四肢肌肉平衡",
    "limbs_skeletal_muscle_index" to "四肢骨骼肌指数",
    "lower_limb_fat_balance" to "下肢脂肪平衡", "lower_limb_muscle_balance" to "下肢肌肉平衡",
    "recommended_calories_intake" to "推荐热量摄入",
    "right_lower_limb_fat_mass" to "右下肢脂肪量", "right_lower_limb_fat_rank" to "右下肢脂肪等级",
    "right_lower_limb_muscle_mass" to "右下肢肌肉量", "right_lower_limb_muscle_rank" to "右下肢肌肉等级",
    "right_upper_limb_fat_mass" to "右上肢脂肪量", "right_upper_limb_fat_rank" to "右上肢脂肪等级",
    "right_upper_limb_muscle_mass" to "右上肢肌肉量", "right_upper_limb_muscle_rank" to "右上肢肌肉等级",
    "somatotype" to "体型分类", "trunk_fat_mass" to "躯干脂肪量", "trunk_fat_rank" to "躯干脂肪等级",
    "trunk_muscle_mass" to "躯干肌肉量", "trunk_muscle_rank" to "躯干肌肉等级",
    "upper_limb_fat_balance" to "上肢脂肪平衡", "upper_limb_muscle_balance" to "上肢肌肉平衡",
    "weight_control" to "体重控制量"
)
private val activityExtraLabels = mapOf(
    "aerobic_train_effect_level" to "有氧训练效果等级",
    "anaerobic_train_effect_level" to "无氧训练效果等级",
    "energy_consume" to "能量消耗",
    "train_load_level" to "训练负荷等级",
    "training_experience" to "训练经验",
    "vitality" to "活力"
)
private fun activityText(payload: ActivityPayload, zone: ZoneId): String = buildList {
    add(activityLabel(payload.sportKey))
    payload.sportCategory?.let { add("类别：${activityLabel(it)}") }
    add("运动区间：${healthTime(payload.startAt, zone)} – ${healthTime(payload.endAt, zone)}")
    payload.durationSeconds?.let { add("来源时长：$it 秒") }
    payload.validDurationSeconds?.let { add("有效运动时长：$it 秒") }
    payload.distanceMeters?.let { add("距离：${healthNumber(it)} 米") }
    payload.caloriesKcal?.let { add("运动消耗：${healthNumber(it)} 千卡") }
    payload.totalCaloriesKcal?.let { add("总消耗：${healthNumber(it)} 千卡") }
    payload.steps?.let { add("步数：$it") }
    payload.avgHeartRate?.let { add("平均心率：${healthNumber(it)} 次/分钟") }
    payload.maxHeartRate?.let { add("最高心率：${healthNumber(it)} 次/分钟") }
    payload.minHeartRate?.let { add("最低心率：${healthNumber(it)} 次/分钟") }
    payload.avgCadence?.let { add("平均步频：${healthNumber(it)}") }
    payload.maxCadence?.let { add("最高步频：${healthNumber(it)}") }
    payload.avgStrideCm?.let { add("平均步幅：${healthNumber(it)} 厘米") }
    payload.avgSpeedMps?.let { add("平均速度：${healthNumber(it)} 米/秒") }
    payload.maxSpeedMps?.let { add("最高速度：${healthNumber(it)} 米/秒") }
    payload.avgPaceSecondsPerKm?.let { add("平均配速：${healthNumber(it)} 秒/公里") }
    payload.maxPaceSecondsPerKm?.let { add("最快配速：${healthNumber(it)} 秒/公里") }
    payload.minPaceSecondsPerKm?.let { add("最慢配速：${healthNumber(it)} 秒/公里") }
    payload.hrWarmUpSeconds?.let { add("热身心率区间：$it 秒") }
    payload.hrFatBurningSeconds?.let { add("燃脂心率区间：$it 秒") }
    payload.hrAerobicSeconds?.let { add("有氧心率区间：$it 秒") }
    payload.hrAnaerobicSeconds?.let { add("无氧心率区间：$it 秒") }
    payload.hrExtremeSeconds?.let { add("极限心率区间：$it 秒") }
    payload.avgHeightMeters?.let { add("平均海拔：${healthNumber(it)} 米") }
    payload.maxHeightMeters?.let { add("最高海拔：${healthNumber(it)} 米") }
    payload.minHeightMeters?.let { add("最低海拔：${healthNumber(it)} 米") }
    payload.vo2max?.let { add("最大摄氧量：${healthNumber(it)}") }
    payload.trainEffect?.let { add("训练效果：${healthNumber(it)}") }
    payload.anaerobicTrainEffect?.let { add("无氧训练效果：${healthNumber(it)}") }
    payload.trainingLoad?.let { add("训练负荷：${healthNumber(it)}") }
    payload.recoveryTime?.let { add("恢复时间：${healthNumber(it)}") }
    payload.avgSpo2?.let { add("平均血氧：${healthNumber(it)}%") }
    payload.strokeCount?.let { add("划水次数：$it") }
    payload.avgStrokeFrequency?.let { add("平均划频：${healthNumber(it)}") }
    payload.maxStrokeFrequency?.let { add("最高划频：${healthNumber(it)}") }
    payload.avgSwolf?.let { add("平均 SWOLF：${healthNumber(it)}") }
    payload.bestSwolf?.let { add("最佳 SWOLF：${healthNumber(it)}") }
    payload.turnCount?.let { add("转身次数：$it") }
    payload.poolWidth?.let { add("泳池宽度数值：${healthNumber(it)}") }
    payload.mainPosture?.let { add("主要泳姿代码：$it") }
    payload.avgPaceSecondsPer100m?.let { add("平均配速：${healthNumber(it)} 秒/100米") }
    payload.maxPaceSecondsPer100m?.let { add("最快配速：${healthNumber(it)} 秒/100米") }
    payload.extra?.let { addAll(it.displayExtra(activityExtraLabels, emptySet(), "其他运动指标")) }
}.joinToString(" · ")

private fun activityLabel(key: String): String = when (key) {
    "pool_swimming" -> "泳池游泳"
    "outdoor_running" -> "户外跑步"
    "outdoor_walking" -> "户外步行"
    else -> key
}

private fun sampleTypeLabel(type: String) = when (type) {
    "active" -> "主动采样"
    "passive" -> "被动采样"
    else -> type
}

private fun stageLabel(stage: String) = when (stage) {
    "deep" -> "深睡"
    "light" -> "浅睡"
    "rem" -> "快速眼动"
    "awake" -> "清醒"
    else -> stage
}

private fun healthTime(epochMillis: Long, zone: ZoneId) = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime().toString().replace('T', ' ').take(16)
private fun healthNumber(value: Number) = value.toString()
private fun HealthDataset.displayTitle() = when (this) {
    HealthDataset.steps -> "步数与距离"
    HealthDataset.calories -> "热量"
    HealthDataset.heartRate -> "心率采样"
    HealthDataset.restingHeartRate -> "静息心率"
    HealthDataset.sleep -> "睡眠"
    HealthDataset.body -> "体成分测量"
    HealthDataset.activity -> "小米运动摘要"
}
private fun HealthDataset.availability(count: Int, status: HealthDetailStatus?) = when {
    count > 0 && status == HealthDetailStatus.complete -> "完整缓存 $count 条"
    count > 0 && status == HealthDetailStatus.loading -> "当前缓存 $count 条 · 正在更新"
    count > 0 && status == HealthDetailStatus.partial -> "部分缓存 $count 条"
    count > 0 && status == HealthDetailStatus.error -> "当前缓存 $count 条 · 更新失败"
    count > 0 -> "当前缓存 $count 条"
    status == HealthDetailStatus.complete -> "已完整读取，该日没有记录"
    status == HealthDetailStatus.partial -> "缓存不完整，尚不能确认该日无记录"
    status == HealthDetailStatus.loading -> "详情读取中，尚无缓存"
    status == HealthDetailStatus.error -> "读取失败，尚无缓存"
    status == HealthDetailStatus.neverRequested || status == null -> "详情尚未读取"
    else -> "当前无可查看缓存"
}
