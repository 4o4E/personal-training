package top.e404.training

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
/** Service-owned media-volume audio and alarm vibration for its one active timer. */
class AlertPlayback {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var target: top.e404.training.shared.TimerState? = null
    private var ringtoneUri: String? = null

    fun start(context: Context, timer: top.e404.training.shared.TimerState, selectedUri: String?) {
        if (target == timer && ringtoneUri == selectedUri) return
        stop()
        val sound = selectedUri?.takeIf { it.isNotEmpty() }?.let { selected ->
            MediaPlayer.create(context.applicationContext, Uri.parse(selected), null,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0)
                ?: throw IllegalStateException("无法打开所选铃声")
        }
        try {
            sound?.isLooping = true
            sound?.setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            val systemVibrator = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            else context.getSystemService(Vibrator::class.java)
            vibrator = systemVibrator
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                systemVibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
            } else {
                systemVibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            sound?.start()
            player = sound
            target = timer
            ringtoneUri = selectedUri
        } catch (failure: RuntimeException) {
            sound?.release()
            stop()
            throw failure
        }
    }

    fun stop() {
        player?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        target = null
        ringtoneUri = null
    }
}
