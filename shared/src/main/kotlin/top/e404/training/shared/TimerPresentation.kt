package top.e404.training.shared

/** Shared display for workout UI and timer notifications, based only on the persisted deadline. */
fun TimerState.displayText(now: Long): String {
    val untilDeadline = deadline - now
    if (untilDeadline > 0) {
        val seconds = (untilDeadline + 999) / 1000
        return if (type == "rest") {
            "休息 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        } else {
            "拉伸 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
    if (type != "rest") return "拉伸计时结束"
    val extraSeconds = (now - deadline) / 1000
    return "额外休息 $extraSeconds 秒"
}
