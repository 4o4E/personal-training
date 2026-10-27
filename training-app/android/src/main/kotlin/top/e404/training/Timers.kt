package top.e404.training

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.serialization.encodeToString
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.e404.training.shared.*

/** 每次训练只有一个当前计时；截止时间持久化，前台服务和系统闹钟竞争同一提醒闸门。 */
object TimerEngine {
    const val CHANNEL = "training-rest-notifications"
    const val ACTIVE = 4101
    const val FINISHED = 4102

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.deleteNotificationChannel("training-timers")
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "训练计时提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "组间休息和拉伸结束时提醒"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
    }

    fun canSchedule(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Android原生提升展示仅从API36开始提供；此判断只表示当前系统与应用设置允许请求。 */
    fun canRequestLiveUpdates(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 && canNotify(context) &&
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    /** 检查系统实际授予该活动通知的提升标记，不把请求成功误报为展示成功。 */
    fun isActiveNotificationPromoted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == ACTIVE }?.notification?.flags
            ?.let { it and Notification.FLAG_PROMOTED_ONGOING != 0 } ?: false
    }

    fun showIntent(context: Context) = PendingIntent.getActivity(context, ACTIVE,
        Intent(context, MainActivity::class.java)
            .putExtra("training.destination", "workout")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun alarmIntent(context: Context, timer: TimerState? = null) = PendingIntent.getBroadcast(context, ACTIVE,
        Intent(context, TimerReceiver::class.java).apply {
            if (timer != null) putExtra("timer", wireJson.encodeToString(TimerState.serializer(), timer))
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, timer: TimerState, clearFinished: Boolean = true) {
        createChannel(context)
        if (clearFinished && !timer.notified) context.getSystemService(NotificationManager::class.java).cancel(FINISHED)
        cancelAlarm(context)
        if (!timer.notified && canSchedule(context)) {
            context.getSystemService(AlarmManager::class.java)
                .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.deadline, alarmIntent(context, timer))
        }
        ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java).apply {
            putExtra("timer", wireJson.encodeToString(TimerState.serializer(), timer))
        })
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    fun clearActive(context: Context) {
        cancelAlarm(context)
        context.stopService(Intent(context, TimerService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(ACTIVE)
    }

    fun finishExercise(context: Context) {
        clearActive(context)
    }

    fun cancel(context: Context) {
        clearActive(context)
        context.getSystemService(NotificationManager::class.java).cancel(FINISHED)
    }

    fun activeBuilder(context: Context, timer: TimerState, now: Long): NotificationCompat.Builder {
        createChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(showIntent(context))
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setWhen(timer.deadline).setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(Build.VERSION.SDK_INT >= 36)
            .apply { updateActive(this, timer, now) }
    }

    fun updateActive(builder: NotificationCompat.Builder, timer: TimerState, now: Long) {
        val title = timer.displayText(now)
        builder.setContentTitle(title).setContentText(timer.label)
            .setWhen(timer.deadline).setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(title)
    }

    fun postActive(context: Context, builder: NotificationCompat.Builder) {
        context.getSystemService(NotificationManager::class.java).notify(ACTIVE, builder.build())
    }

    fun notifyFinished(context: Context, timer: TimerState) {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!canNotify(context)) return
        manager.notify(FINISHED, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (timer.type == "rest") "休息结束，可以开始下一组" else "拉伸计时结束")
            .setContentText(timer.label).setContentIntent(showIntent(context))
            .setAutoCancel(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC).build())
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val encoded = intent.getStringExtra("timer") ?: return
        val timer = wireJson.decodeFromString(TimerState.serializer(), encoded)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as TrainingApplication).repository.notifyTimer(timer)
            } finally { pending.finish() }
        }
    }
}
