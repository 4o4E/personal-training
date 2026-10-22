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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.e404.training.shared.*

/** 每次训练只有一个当前计时；结束时间先持久化，到时唤醒应用并发送普通通知。 */
object TimerEngine {
    private const val CHANNEL = "training-rest-notifications"
    private const val ACTIVE = 4101
    private const val FINISHED = 4102
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
    fun canSchedule(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun showIntent(context: Context) = PendingIntent.getActivity(context, ACTIVE, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun alarmIntent(context: Context, timer: TimerState? = null) = PendingIntent.getBroadcast(context, ACTIVE,
        Intent(context, TimerReceiver::class.java).apply { if (timer != null) putExtra("timer", wireJson.encodeToString(TimerState.serializer(), timer)) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, timer: TimerState) {
        createChannel(context)
        if (timer.deadline <= System.currentTimeMillis()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(FINISHED)
        if (canSchedule(context)) {
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.deadline, alarmIntent(context, timer))
        }
        if (canNotify(context)) {
            manager.notify(ACTIVE, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(if (timer.type == "rest") "组间休息" else "拉伸计时")
                .setContentText(timer.label).setContentIntent(showIntent(context))
                .setWhen(timer.deadline).setUsesChronometer(true).setChronometerCountDown(true)
                .setOnlyAlertOnce(true).setSilent(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS).build())
        }
    }
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        context.getSystemService(NotificationManager::class.java).apply { cancel(ACTIVE); cancel(FINISHED) }
    }
    fun notifyFinished(context: Context, timer: TimerState) {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ACTIVE)
        if (!canNotify(context)) return
        manager.notify(FINISHED, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (timer.type == "rest") "休息结束，可以开始下一组" else "拉伸计时结束")
            .setContentText(timer.label).setContentIntent(showIntent(context))
            .setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PUBLIC).build())
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
