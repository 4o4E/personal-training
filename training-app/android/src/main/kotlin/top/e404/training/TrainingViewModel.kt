package top.e404.training

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.e404.training.shared.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun localDateTime(milliseconds: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(milliseconds))

class TrainingViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as TrainingApplication).repository
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    init { action { repository.initialize() } }
    fun dismissMessage() { _message.value = null }
    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (error: IllegalArgumentException) { _message.value = error.message ?: "输入内容不正确" }
            catch (error: ApiProblem) { _message.value = error.message; repository.syncFailure(error.message!!) }
            catch (error: IOException) { _message.value = "网络或文件读取失败，手机中的已有记录保持保存"; repository.syncFailure("未完成同步，请检查网络或文件") }
        }
    }
    fun save(doc: Document, original: Document, done: () -> Unit = {}) = action { repository.save(doc, original); done() }
    fun start(plan: Plan) = action { repository.beginSession(plan) }
    fun finish(sessionId: String, cancelled: Boolean) = action {
        repository.updateSession(sessionId) { session, _ ->
            require(cancelled || session.results.isNotEmpty()) { "没有完成任何组，请选择取消训练" }
            session.copy(status = if (cancelled) "cancelled" else "completed", endedAt = System.currentTimeMillis()) to null
        }
    }
    fun completeSet(sessionId: String, itemId: String, index: Int, target: SetTarget) = action {
        repository.updateSession(sessionId) { session, currentTimer ->
            val item = session.items.single { it.id == itemId }
            val old = session.results.singleOrNull { it.itemId == itemId && it.setIndex == index }
            val now = System.currentTimeMillis()
            val result = SetResult(itemId, index, target.reps, target.seconds, target.load, target.side, old?.completedAt ?: now)
            val revised = session.copy(results = session.results.filterNot { it.itemId == itemId && it.setIndex == index } + result)
            val timer = if (old != null) currentTimer else if (target.restSeconds > 0) TimerState(now + target.restSeconds * 1000L,
                "${session.exercises.single { it.id == item.exerciseId }.name} · 第 ${index + 1} 组", session.id, itemId, index) else null
            revised to timer
        }
    }
    fun undoSet(sessionId: String, itemId: String, index: Int) = action {
        repository.updateSession(sessionId) { session, timer ->
            session.copy(results = session.results.filterNot { it.itemId == itemId && it.setIndex == index }) to
                timer?.takeUnless { it.sessionId == sessionId && it.itemId == itemId && it.setIndex == index }
        }
    }
    fun exerciseTimer(sessionId: String, itemId: String, index: Int, seconds: Int) = action {
        repository.updateSession(sessionId) { session, _ ->
            val item = session.items.single { it.id == itemId }
            require(seconds > 0) { "请填写计时时长" }
            session to TimerState(System.currentTimeMillis() + seconds * 1000L,
                "${session.exercises.single { it.id == item.exerciseId }.name} · ${sideText(item.sets[index].side)}", sessionId, itemId, index, "exercise")
        }
    }
    fun stopTimer() = action { repository.setTimer(null) }
    fun updateSessionNotes(sessionId: String, notes: String) = action {
        repository.updateSession(sessionId) { session, timer -> session.copy(notes = notes) to timer }
    }
    fun addSessionExercise(sessionId: String, item: PlanItem, exercise: Exercise, position: Int) = action {
        repository.updateSession(sessionId) { session, timer ->
            val items = session.items.toMutableList().apply { add(position.coerceIn(0, size), item) }
            val exercises = if (session.exercises.any { it.id == exercise.id }) session.exercises else session.exercises + exercise
            session.copy(items = items, exercises = exercises) to timer
        }
    }
    /** 已完成组保留原动作，剩余训练通过新条目执行；不改写开始时计划。 */
    fun replaceSessionExercise(sessionId: String, originalId: String, replacement: PlanItem, exercise: Exercise) = action {
        repository.updateSession(sessionId) { session, timer ->
            val original = session.items.single { it.id == originalId }
            val complete = session.results.count { it.itemId == originalId }
            require(complete < original.sets.size) { "该动作已完成，请使用添加动作继续训练" }
            val position = session.items.indexOf(original)
            val items = session.items.toMutableList()
            val skipped = session.skippedItems.toMutableList()
            if (complete == 0) items[position] = replacement else { skipped += originalId; items.add(position + 1, replacement) }
            val exercises = if (session.exercises.any { it.id == exercise.id }) session.exercises else session.exercises + exercise
            session.copy(items = items, skippedItems = skipped, exercises = exercises) to timer
        }
    }
}

/** 新动作参考同一器械上的上次实际值；找不到记录时不虚构重量或换算读数。 */
fun newPlanItem(exercise: Exercise, gymId: String?, state: AppData, count: Int? = null): PlanItem {
    val equipment = state.records(Kind.equipment).map { it.value<Equipment>() }
        .firstOrNull { it.gymId == gymId && exercise.equipmentTags.firstOrNull() in it.tags }
    var previous: SetResult? = null
    state.records(Kind.session).forEach { doc ->
        val session = doc.value<Session>()
        session.results.forEach { result ->
            if ((previous == null || result.completedAt > previous!!.completedAt) && session.items.any { it.id == result.itemId && it.exerciseId == exercise.id && it.equipmentId == equipment?.id }) previous = result
        }
    }
    val target = SetTarget(
        reps = if (exercise.measurement == "reps") previous?.reps ?: 10 else null,
        seconds = if (exercise.measurement == "seconds") previous?.seconds ?: exercise.defaultSeconds else null,
        load = previous?.load, restSeconds = if (exercise.type == "stretch") 15 else 90
    )
    val size = count ?: if (exercise.type == "stretch") if (exercise.unilateral) 2 else 1 else 4
    val sets = List(size) { index -> if (exercise.type == "stretch" && exercise.unilateral) target.copy(side = if (index % 2 == 0) "left" else "right") else target }
    return PlanItem(newId(), exercise.id, equipment?.id, sets)
}
