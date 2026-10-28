package top.e404.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HealthHistoryScreen(state: AppData, viewModel: TrainingViewModel, today: LocalDate) {
    val health = state.health
    val selectedDate = health.selectedDate ?: today
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var expandedDataset by rememberSaveable(selectedDate.toString()) { mutableStateOf<String?>(null) }
    val zone = remember { ZoneId.of("Asia/Shanghai") }

    LaunchedEffect(Unit) { viewModel.refreshHealthDays(today.minusDays(90), today) }
    LaunchedEffect(health.selectedDate) {
        val date = health.selectedDate
        if (date == null) viewModel.selectHealthDate(today)
        else viewModel.loadHealthDate(date)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.selectHealthDate(selectedDate.minusDays(1)) }) { Text("前一天") }
            OutlinedButton(onClick = { choosingDate = true }) { Text(selectedDate.toString()) }
            TextButton(onClick = { viewModel.selectHealthDate(selectedDate.plusDays(1)) }) { Text("后一天") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val summary = health.summary?.takeIf { it.localDate == selectedDate.toString() }
            item(key = "health-summary") {
                InfoCard("${selectedDate} 健康概览") {
                    summary?.let { day ->
                        day.steps?.let { Text("步数：$it") }
                        day.distanceMeters?.let { Text("步行距离：${healthNumber(it)} 米") }
                        day.stepCaloriesKcal?.let { Text("步数消耗：${healthNumber(it)} 千卡") }
                        day.calorieMinutesKcal?.let { Text("活动消耗：${healthNumber(it)} 千卡") }
                        day.heartRateSamples?.let { Text("心率样本：$it 条") }
                        day.restingHeartRateBpm?.let { Text("静息心率：$it 次/分钟") }
                        day.sleepSessions?.let { Text("睡眠段：$it") }
                        day.bodyMeasurements?.let { Text("体成分测量：$it 次") }
                        day.activities?.let { Text("运动摘要：$it 条") }
                        if (listOf(day.steps, day.distanceMeters, day.stepCaloriesKcal, day.calorieMinutesKcal, day.heartRateSamples, day.restingHeartRateBpm, day.sleepSessions, day.bodyMeasurements, day.activities).all { it == null }) {
                            Text("该日期概览没有可用指标。")
                        }
                    } ?: Text("该日期概览尚未取得。")
                    Text("仅展示实际下载并缓存的健康数据；未取得不表示为零或无记录。", style = MaterialTheme.typography.bodySmall)
                }
            }
            HealthDataset.entries.forEach { dataset ->
                val rows = health.recordsByDataset[dataset].orEmpty()
                val expanded = expandedDataset == dataset.name
                val detailState = health.detailStateByDataset[dataset]
                item(key = "dataset-${dataset.name}") {
                    InfoCard(dataset.displayTitle()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(dataset.availability(rows.size, detailState?.status), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                expandedDataset = if (expanded) null else dataset.name
                            }) { Text(if (expanded) "收起" else "查看详情") }
                        }
                        detailState?.error?.let { Text("此类详情不可用：$it", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (expanded && dataset == HealthDataset.sleep) {
                    val sleeps = rows.map { record -> record to record.decodePayload<SleepPayload>() }
                        .sortedByDescending { it.second.durationMinutes }
                    itemsIndexed(sleeps, key = { _, entry -> "sleep-${entry.first.sourceId}" }) { index, entry ->
                        SleepRecordCard(index, entry.second, zone)
                    }
                } else if (expanded) {
                    items(rows, key = { "health-${dataset.name}-${it.sourceId}" }) { record ->
                        Text(record.displayHealthPayload(dataset, zone), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }

    if (choosingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = selectedDate.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    viewModel.selectHealthDate(LocalDate.ofEpochDay(Math.floorDiv(picker.selectedDateMillis!!, 86_400_000L)))
                    choosingDate = false
                }) { Text("查看该日") }
            },
            dismissButton = { TextButton(onClick = { choosingDate = false }) { Text("取消") } }
        ) { DatePicker(picker) }
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

private fun HealthRecord.displayHealthPayload(dataset: HealthDataset, zone: ZoneId): String = when (dataset) {
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
    count > 0 -> "当前缓存 $count 条"
    status == HealthDetailStatus.complete -> "已完整读取，该日没有记录"
    status == HealthDetailStatus.partial -> "缓存不完整，尚不能确认该日无记录"
    status == HealthDetailStatus.neverRequested || status == null -> "详情尚未读取"
    else -> "当前无可查看缓存"
}
@Composable fun HealthSyncCard(state: AppData, viewModel: TrainingViewModel, openConflicts: () -> Unit) {
    val selectedDate = state.health.selectedDate
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val details = state.health.detailStateByDataset.values
    val healthStage = when {
        state.health.healthError != null || details.any { it.status == HealthDetailStatus.error } -> "下载失败"
        details.any { it.status == HealthDetailStatus.loading } -> "下载中"
        details.any { it.status == HealthDetailStatus.partial } -> "部分缓存"
        details.isNotEmpty() && details.all { it.status == HealthDetailStatus.complete } -> "已完整下载"
        details.any { it.status != HealthDetailStatus.neverRequested } -> "尚未完整下载"
        else -> "尚未请求"
    }
    val collection = state.health.healthStatus?.collection
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.health.healthStatus?.collection?.stage, state.health.collectionError) {
        val stage = state.health.healthStatus?.collection?.stage
        if (state.health.collectionError == null &&
            (stage == "requested" || stage == "running" || stage == "submitted")
        ) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.observeCollectionStatus()
            }
        }
    }
    val collectionStage = collection?.let {
        listOfNotNull(it.stage.collectionStageText(), it.phase?.collectionPhaseText()).joinToString(" · ")
    } ?: "尚无状态"
    val failureSummaries = buildList {
        if (state.syncStatus == SyncStatus.error) add("训练同步失败：${state.syncMessage}")
        if (state.mediaSyncStatus == MediaSyncStatus.error) add("素材下载失败")
        state.health.healthError?.let { add("健康下载失败：$it") }
        if (state.health.healthError == null) {
            details.firstNotNullOfOrNull { it.error }?.let { add("健康详情读取失败：$it") }
        }
        state.health.collectionError?.let { add("采集请求失败：$it") }
        collection?.error?.let { add("小米采集失败：$it") }
        if (collection?.stage == "failed" && collection.error == null) add("小米采集失败")
    }
    InfoCard("同步状态") {
        Text("训练 ${state.syncStatus.compactStatus()} · 健康 $healthStage · 小米 $collectionStage")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("待上传 ${state.pending} 项")
            if (state.conflicts.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = openConflicts) {
                    Text("处理冲突（${state.conflicts.size}）")
                }
            } else Text("冲突 0 项")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(
                onClick = viewModel::retryAll,
                enabled = state.syncStatus != SyncStatus.syncing && healthStage != "下载中"
            ) { Text("重试同步") }
            androidx.compose.material3.TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "收起详情" else "展开详情")
            }
        }
        if (!detailsExpanded) {
            failureSummaries.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        } else {
            Text("训练资料：${state.syncStatus.displayStatus()} · ${state.syncMessage}")
            Text("最近成功：${state.lastSuccessfulSyncAt?.let(::localDateTime) ?: "尚无"} · 待上传 ${state.pending} 项")
            Text("素材：${state.mediaSyncStatus.displayStatus()}")
            Text("健康下载${selectedDate?.let { "（$it）" } ?: ""}：$healthStage")
            state.health.healthError?.let { Text("健康错误：$it", color = MaterialTheme.colorScheme.error) }
            details.firstNotNullOfOrNull { it.error }?.let { Text("健康详情错误：$it", color = MaterialTheme.colorScheme.error) }
            Text("小米采集：$collectionStage")
            state.health.collectionError?.let { Text("采集请求错误：$it", color = MaterialTheme.colorScheme.error) }
            collection?.submittedAt?.let { Text("健康资料最近提交：${localDateTime(it)}") }
            collection?.lastSuccessAt?.let { Text("小米最近完整采集成功：${localDateTime(it)}") }
            collection?.error?.let { Text("采集错误：$it", color = MaterialTheme.colorScheme.error) }
            val collectionBusy = state.health.collectionRequesting ||
                (state.health.collectionError == null &&
                    (collection?.stage == "requested" || collection?.stage == "running" || collection?.stage == "submitted"))
            androidx.compose.material3.OutlinedButton(onClick = viewModel::requestCollection, enabled = !collectionBusy) {
                Text(if (collectionBusy) "采集中" else "重新请求小米采集")
            }
        }
    }
}

private fun SyncStatus.compactStatus() = when (this) {
    SyncStatus.offline -> "离线"
    SyncStatus.syncing -> "进行中"
    SyncStatus.synced -> "完成"
    SyncStatus.pending -> "待同步"
    SyncStatus.conflict -> "有冲突"
    SyncStatus.error -> "失败"
}

private fun SyncStatus.displayStatus() = when (this) {
    SyncStatus.offline -> "离线"
    SyncStatus.syncing -> "进行中"
    SyncStatus.synced -> "完成"
    SyncStatus.pending -> "待上传"
    SyncStatus.conflict -> "存在冲突"
    SyncStatus.error -> "失败"
}

private fun MediaSyncStatus.displayStatus() = when (this) {
    MediaSyncStatus.idle -> "未开始"
    MediaSyncStatus.checking -> "检查中"
    MediaSyncStatus.ready -> "已就绪"
    MediaSyncStatus.error -> "下载失败"
}
private fun String.collectionStageText() = when (this) {
    "idle" -> "空闲"
    "requested" -> "已请求"
    "running" -> "进行中"
    "submitted" -> "已提交"
    "published" -> "已有健康资料已提交"
    "succeeded" -> "成功"
    "failed" -> "失败"
    else -> this
}
private fun String.collectionPhaseText() = when (this) {
    "mi-collection" -> "小米采集"
    "seed-read" -> "读取历史资料"
    "health-submit" -> "提交健康资料"
    "existing-data-publish" -> "提交已有健康资料"
    "complete" -> "流程完成"
    else -> this
}
