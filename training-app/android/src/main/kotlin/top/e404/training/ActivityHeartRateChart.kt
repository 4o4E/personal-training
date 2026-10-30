package top.e404.training

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import top.e404.training.shared.HealthRecord
import top.e404.training.shared.HeartRatePayload
import top.e404.training.shared.decodePayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
 

private const val MAX_CONNECTED_GAP_MS = 2_500L
private val chartZone = ZoneId.of("Asia/Shanghai")
private val chartTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private data class HeartRatePoint(val measuredAt: Long, val bpm: Int)
private data class HeartRateRange(val low: Int, val high: Int, val tickStep: Int) {
    val tickCount: Int get() = (high - low) / tickStep
}

/** Only actual Xiaomi workout samples inside the user's saved closed interval are returned. */
internal fun filteredHeartRateRecords(records: List<HealthRecord>, startAt: Long, endAt: Long): List<HealthRecord> {
    if (endAt < startAt) return emptyList()
    return records.asSequence()
        .filter { it.measuredAt >= startAt && it.measuredAt <= endAt }
        .filter {
            val payload = it.decodePayload<HeartRatePayload>()
            payload.source == "xiaomi_workout" && payload.sampleType == "active" && payload.bpm > 0
        }
        .sortedWith(compareBy<HealthRecord> { it.measuredAt }.thenBy { it.sourceId })
        .distinctBy { it.measuredAt }
        .toList()
}

private fun heartRateRange(points: List<HeartRatePoint>): HeartRateRange? {
    if (points.isEmpty()) return null
    var minimum = Int.MAX_VALUE
    var maximum = Int.MIN_VALUE
    points.forEach {
        minimum = minOf(minimum, it.bpm)
        maximum = maxOf(maximum, it.bpm)
    }
    val span = maximum - minimum + 10
    val tickStep = (ceil(span / 50.0).toInt() * 10).coerceAtLeast(10)
    val low = floor((minimum - 5).coerceAtLeast(0) / tickStep.toDouble()).toInt() * tickStep
    val high = (ceil((maximum + 5) / tickStep.toDouble()).toInt() * tickStep).coerceAtLeast(low + tickStep)
    return HeartRateRange(low, high, tickStep)
}


@Composable
internal fun ActivityHeartRateChart(
    startAt: Long?,
    endAt: Long?,
    records: List<HealthRecord>,
    detailStatus: HealthDetailStatus?
) {
    if (startAt == null || endAt == null || endAt < startAt) return
    val start = startAt
    val end = endAt
    val samples = remember(records, start, end) { filteredHeartRateRecords(records, start, end) }
    val points = remember(samples) {
        samples.map { record -> HeartRatePoint(record.measuredAt, record.decodePayload<HeartRatePayload>().bpm) }
    }
    val range = remember(points) { heartRateRange(points) }
    val startLabel = remember(start) { chartTimeFormatter.format(Instant.ofEpochMilli(start).atZone(chartZone)) }
    val endLabel = remember(end) { chartTimeFormatter.format(Instant.ofEpochMilli(end).atZone(chartZone)) }
    val crossesDate = remember(start, end) {
        Instant.ofEpochMilli(start).atZone(chartZone).toLocalDate() != Instant.ofEpochMilli(end).atZone(chartZone).toLocalDate()
    }
    val coverageIncomplete = detailStatus != HealthDetailStatus.complete || crossesDate
    val chartDescription = remember(points.size, startLabel, endLabel) {
        "运动心率图，$startLabel 至 $endLabel；显示${points.size}个真实运动采样，采样缺口不连接"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("运动期间心率（小米运动采样）", style = MaterialTheme.typography.labelLarge)
        when {
            points.isEmpty() -> Text(
                when {
                    crossesDate -> "跨日运动范围可能只覆盖当前日期缓存；没有匹配的运动心率采样"
                    detailStatus == HealthDetailStatus.complete -> "已完整读取的范围内没有运动心率采样"
                    detailStatus == HealthDetailStatus.loading -> "运动心率采样读取中，当前尚无匹配点"
                    detailStatus == HealthDetailStatus.partial -> "当前没有运动心率采样，缓存范围不完整"
                    detailStatus == HealthDetailStatus.error -> "当前没有运动心率采样，读取失败"
                    else -> "尚无运动心率采样；日常心率点不用于运动曲线"
                }, style = MaterialTheme.typography.bodySmall
            )
            else -> {
                if (coverageIncomplete) Text(
                    if (crossesDate) "仅显示已缓存的实际运动采样；跨日范围可能不完整，采样缺口不连接"
                    else "仅显示已缓存的实际运动采样；采样缺口不连接",
                    style = MaterialTheme.typography.bodySmall
                )
                val axis = requireNotNull(range)
                val low = axis.low
                val high = axis.high
                val tickStep = axis.tickStep
                val tickCount = axis.tickCount
                val axisColor = MaterialTheme.colorScheme.outline
                val lineColor = MaterialTheme.colorScheme.primary
                val pointColor = MaterialTheme.colorScheme.tertiary
                val axisLabelHeight = MaterialTheme.typography.labelSmall.lineHeight
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.height(112.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        for (index in tickCount downTo 0) {
                            Text("${low + index * tickStep}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Canvas(
                            Modifier.fillMaxWidth().height(112.dp).semantics(mergeDescendants = true) {
                                contentDescription = chartDescription
                            }
                        ) {
                            val left = 8.dp.toPx()
                            val right = size.width - 8.dp.toPx()
                            val top = 8.dp.toPx() + axisLabelHeight.toPx() / 2
                            val bottom = size.height - 8.dp.toPx() - axisLabelHeight.toPx() / 2
                            drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 1.dp.toPx())
                            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
                            for (index in tickCount downTo 0) {
                                val bpm = low + index * tickStep
                                val y = bottom - (bpm - low).toFloat() / (high - low) * (bottom - top)
                                drawLine(axisColor.copy(alpha = 0.35f), Offset(left, y), Offset(right, y), strokeWidth = 0.5.dp.toPx())
                            }
                            val span = (end - start).coerceAtLeast(1L).toDouble()
                            fun pointAt(index: Int): Offset {
                                val sample = points[index]
                                val x = left + ((sample.measuredAt - start) / span).toFloat() * (right - left)
                                val y = bottom - ((sample.bpm - low).toFloat() / (high - low)) * (bottom - top)
                                return Offset(x, y)
                            }
                            for (index in 1 until points.size) {
                                val gap = points[index].measuredAt - points[index - 1].measuredAt
                                if (gap <= MAX_CONNECTED_GAP_MS) {
                                    drawLine(lineColor, pointAt(index - 1), pointAt(index), strokeWidth = 2.dp.toPx())
                                } else {
                                    drawCircle(pointColor, radius = 3.dp.toPx(), center = pointAt(index - 1))
                                    drawCircle(pointColor, radius = 3.dp.toPx(), center = pointAt(index))
                                }
                            }
                            if (points.size == 1) {
                                drawCircle(pointColor, radius = 3.dp.toPx(), center = pointAt(0))
                            }
                            drawLine(lineColor, Offset(left, bottom - 3.dp.toPx()), Offset(left, bottom + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                            drawLine(lineColor, Offset(right, bottom - 3.dp.toPx()), Offset(right, bottom + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("开始 $startLabel", style = MaterialTheme.typography.labelSmall)
                            Text("结束 $endLabel", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("次/分钟", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
