package top.e404.training

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.math.BigDecimal
import kotlin.math.abs

private val weightSteps = listOf(0.5, 1.0, 2.5, 5.0, 10.0)
private val weightInputPattern = Regex("\\d*([.]\\d*)?")

internal fun adjustedValue(value: String, step: Double, direction: Int, minimum: BigDecimal): String? {
    if (value.isBlank()) {
        if (direction < 0) return null
        return (if (minimum == BigDecimal.ONE) minimum else BigDecimal.valueOf(step)).toPlainString()
    }
    val increment = BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(direction.toLong()))
    val next = value.toBigDecimalOrNull()?.add(increment) ?: return null
    return next.takeIf { it >= minimum }?.stripTrailingZeros()?.toPlainString()
}

@Composable
internal fun WorkoutCountInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) =
    WorkoutNumberInput(value, onValueChange, 1.0, "次", true, modifier, null, emptyList())

@Composable
internal fun WorkoutWeightInput(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    step: Double,
    onStepChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    commonWeights: List<Double> = emptyList()
) = WorkoutNumberInput(value, onValueChange, step, unit, false, modifier, onStepChange, commonWeights)

@Composable
private fun WorkoutNumberInput(value: String, onValueChange: (String) -> Unit, step: Double, unit: String, count: Boolean, modifier: Modifier, onStepChange: ((Double) -> Unit)?, commonWeights: List<Double>) {
    val minimum = if (count) BigDecimal.ONE else BigDecimal.ZERO
    val currentValue by rememberUpdatedState(value)
    val onValueChangeState by rememberUpdatedState(onValueChange)
    val currentStep by rememberUpdatedState(if (count) 1.0 else step)
    val haptic = LocalHapticFeedback.current
    val hapticState by rememberUpdatedState(haptic)
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var scaleVisible by remember { mutableStateOf(false) }

    fun adjust(direction: Int): Boolean {
        val next = adjustedValue(currentValue, currentStep, direction, minimum) ?: return false
        onValueChangeState(next)
        hapticState.performHapticFeedback(HapticFeedbackType.SegmentTick)
        return true
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fieldWidth = (maxWidth - 208.dp).coerceIn(0.dp, 80.dp)
        Row(
            modifier = Modifier.width(fieldWidth + 208.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = { focusManager.clearFocus(); adjust(-1) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "减少$unit" }) {
                Text("−", fontSize = 24.sp)
            }
            Surface(
                modifier = Modifier.width(fieldWidth).height(40.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { text ->
                            val valid = if (count) text.isEmpty() || text.toIntOrNull()?.let { it >= 1 } == true
                            else text.isEmpty() || weightInputPattern.matches(text)
                            if (valid) onValueChange(text)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = if (count) KeyboardType.Number else KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "输入$unit" },
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                innerTextField()
                            }
                        }
                    )
                    Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { focusManager.clearFocus(); adjust(1) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "增加$unit" }) {
                Icon(Icons.Default.Add, contentDescription = "增加$unit", modifier = Modifier.size(24.dp))
            }
            Box(
                modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "长按后上下拖动调整$unit" }
                    .pointerInput(count, step) {
                        val threshold = with(density) { 12.dp.toPx() }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            longPress.consume()
                            focusManager.clearFocus()
                            hapticState.performHapticFeedback(HapticFeedbackType.LongPress)
                            scaleVisible = true
                            try {
                                var accumulatedDrag = 0f
                                var dragValue = currentValue
                                verticalDrag(longPress.id) { change ->
                                    val dragAmount = change.positionChange().y
                                    val previous = dragValue
                                    var nextValue = dragValue
                                    var movement = accumulatedDrag + dragAmount
                                    while (abs(movement) >= threshold) {
                                        val direction = if (movement < 0f) 1 else -1
                                        val next = adjustedValue(nextValue, currentStep, direction, minimum)
                                        if (next == null) {
                                            movement = 0f
                                            break
                                        }
                                        nextValue = next
                                        movement += if (movement < 0f) threshold else -threshold
                                    }
                                    accumulatedDrag = movement
                                    if (nextValue != previous) {
                                        dragValue = nextValue
                                        onValueChangeState(nextValue)
                                        hapticState.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                    change.consume()
                                }
                            } finally {
                                scaleVisible = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape, modifier = Modifier.size(32.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                if (scaleVisible) {
                    Popup(
                        alignment = Alignment.CenterStart,
                        offset = IntOffset(with(density) { (-112).dp.roundToPx() }, 0),
                        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)
                    ) {
                        DragValueScale(value, currentStep, unit, minimum)
                    }
                }
            }
            if (onStepChange != null) {
                Box {
                    IconButton(onClick = { focusManager.clearFocus(); menuOpen = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "重量步长与常用重量" }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "重量步长与常用重量", modifier = Modifier.size(24.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Text("调整步长", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                        weightSteps.forEach { amount ->
                            DropdownMenuItem(text = { Text(amount.toString()) }, onClick = { onStepChange(amount); menuOpen = false })
                        }
                        if (commonWeights.isNotEmpty()) {
                            Text("常用重量", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                            commonWeights.forEach { commonWeight ->
                                DropdownMenuItem(text = { Text("$commonWeight $unit") }, onClick = {
                                    onValueChange(commonWeight.toString())
                                    menuOpen = false
                                })
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun DragValueScale(value: String, step: Double, unit: String, minimum: BigDecimal) {
    val ticks = remember(value, step, minimum) {
        List(5) { index ->
            val offset = 2 - index
            if (offset == 0) value.ifBlank { "—" }
            else value.toBigDecimalOrNull()?.let { current ->
                val delta = BigDecimal.valueOf(step).multiply(BigDecimal.valueOf(offset.toLong()))
                current.add(delta).takeIf { it >= minimum }?.stripTrailingZeros()?.toPlainString()
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), shadowElevation = 4.dp) {
        Column(modifier = Modifier.width(104.dp).padding(vertical = 6.dp, horizontal = 8.dp)) {
            ticks.forEachIndexed { index, tick ->
                val selected = index == 2
                val tickColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Row(modifier = Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tick == null) {
                        Spacer(modifier = Modifier.width(12.dp))
                    } else {
                        androidx.compose.foundation.Canvas(modifier = Modifier.width(if (selected || index % 2 == 0) 12.dp else 7.dp).height(1.dp)) {
                            drawLine(tickColor, start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                        }
                        Text("$tick $unit", style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall, color = tickColor, maxLines = 1)
                    }
                }
            }
        }
    }
}
