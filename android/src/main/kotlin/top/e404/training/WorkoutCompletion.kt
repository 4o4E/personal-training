package top.e404.training

import top.e404.training.shared.Session
import java.util.concurrent.TimeUnit

internal val workoutIdleTimeout = TimeUnit.MINUTES.toMillis(30)

internal fun Session.lastCompletedSetAt(): Long? = results.asSequence().mapNotNull { it.completedAt }.maxOrNull()

internal fun Session.workoutIdleDeadline(lastUpdateAt: Long?): Long? {
    if (status != "active" || results.isEmpty()) return null
    val lastActivity = maxOf(lastUpdateAt ?: 0L, lastCompletedSetAt() ?: 0L, startedAt ?: 0L)
    return lastActivity.takeIf { it > 0L }?.plus(workoutIdleTimeout)
}

internal fun Session.finishWorkout(cancelled: Boolean, requestedAt: Long): Session {
    require(cancelled || results.isNotEmpty()) { "没有完成任何组，请选择取消训练" }
    return copy(
        status = if (cancelled) "cancelled" else "completed",
        endedAt = if (cancelled) requestedAt else lastCompletedSetAt()
    )
}
