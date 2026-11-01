package top.e404.training

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.e404.training.shared.TimerState
import top.e404.training.shared.wireJson

/** Timer and screen state are serialized on the main dispatcher; storage operations remain in the repository. */
class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screenOn = MutableStateFlow(true)
    private var screenReceiverRegistered = false
    private var timerJob: Job? = null
    private var activeBuilder: NotificationCompat.Builder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockDeadline: Long? = null
    private val alertPlayback = AlertPlayback()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn.value = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val encoded = intent?.getStringExtra("timer") ?: run { stopSelf(startId); return START_NOT_STICKY }
        val timer = wireJson.decodeFromString(TimerState.serializer(), encoded)
        updateWakeLock(timer)
        val now = System.currentTimeMillis()
        val builder = activeBuilder
        if (builder == null) {
            activeBuilder = TimerEngine.activeBuilder(this, timer, now)
        } else {
            TimerEngine.updateActive(builder, timer, now)
        }
        val notification = requireNotNull(activeBuilder).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TimerEngine.ACTIVE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TimerEngine.ACTIVE, notification)
        }
        if (timerJob == null) {
            timerJob = scope.launch {
                val repository = (application as TrainingApplication).repository
                repository.initialize(triggerStartupSync = false)
                combine(repository.timer, screenOn) { current, isScreenOn -> current to isScreenOn }
                    .collectLatest { (current, isScreenOn) ->
                        if (current == null) {
                            stopSelf()
                            return@collectLatest
                        }
                        try {
                            var state: TimerState = current
                            updateWakeLock(state)
                            while (true) {
                                val currentTime = System.currentTimeMillis()
                                if (!state.notified && currentTime >= state.deadline) {
                                    repository.notifyTimer(state)
                                    state = repository.timer.value ?: return@collectLatest
                                    updateWakeLock(state)
                                }
                                if (state.notified && !state.alertDismissed) {
                                    val choices = (application as TrainingApplication).preferences.data.value
                                    alertPlayback.start(this@TimerService, state, choices.timerRingtoneUri)
                                } else {
                                    alertPlayback.stop()
                                }
                                if (state.type != "rest" && state.notified && state.alertDismissed) {
                                    TimerEngine.finishExercise(this@TimerService)
                                    stopSelf()
                                    return@collectLatest
                                }
                                val builderNow = System.currentTimeMillis()
                                TimerEngine.updateActive(requireNotNull(activeBuilder), state, builderNow)
                                TimerEngine.postActive(this@TimerService, requireNotNull(activeBuilder))
                                val remaining = state.deadline - builderNow
                                val nextDisplayChange = if (remaining > 0L) {
                                    (remaining - 1L) % 1000L + 1L
                                } else {
                                    1000L - ((builderNow - state.deadline).coerceAtLeast(0L) % 1000L)
                                }
                                if (isScreenOn) {
                                    delay(nextDisplayChange)
                                } else if (!state.notified) {
                                    delay(remaining.coerceAtLeast(1L))
                                } else if (state.type != "rest") {
                                    repository.timer.first { it == null || it.alertDismissed }
                                    return@collectLatest
                                } else {
                                    screenOn.first { it }
                                }
                                state = repository.timer.value ?: return@collectLatest
                            }
                        } finally {
                            releaseWakeLock()
                        }
                    }
            }
        }

        return START_NOT_STICKY
    }

    private fun updateWakeLock(timer: TimerState) {
        if (timer.notified || timer.deadline <= System.currentTimeMillis()) {
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld == true && wakeLockDeadline == timer.deadline) return
        releaseWakeLock()
        val remaining = timer.deadline - System.currentTimeMillis()
        if (remaining <= 0L) return
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:training-timer")
        lock.acquire(remaining + 5_000L)
        wakeLock = lock
        wakeLockDeadline = timer.deadline
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wakeLockDeadline = null
    }

    override fun onDestroy() {
        timerJob?.cancel()
        timerJob = null
        releaseWakeLock()
        alertPlayback.stop()
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
