package com.saeidkazemi.trader.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.saeidkazemi.trader.R

/**
 * پخش هشدار معاملات:
 * - قبل از معامله: دو بوق کوتاه رو به بالا (trade_start)
 * - پس از انجام معامله: زنگ سه‌نتی (trade_done) + لرزش
 *
 * صدا از کانال «اعلان» پخش می‌شود؛ اگر گزینه «پخش در حالت بی‌صدا» روشن باشد از کانال «زنگ هشدار».
 * با قفل بودن گوشی هم کار می‌کند چون سرویس پیش‌زمینه برنامه را زنده نگه می‌دارد.
 */
class AlertPlayer(private val context: Context) {

    private var current: MediaPlayer? = null

    private fun attrs(loud: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun playStart(loud: Boolean) = play(R.raw.trade_start, loud)

    fun playDone(loud: Boolean) = play(R.raw.trade_done, loud)

    @Synchronized
    private fun play(resId: Int, loud: Boolean) {
        try {
            current?.let {
                try { it.stop() } catch (_: Exception) { }
                it.release()
            }
            current = null
            val am = context.getSystemService(AudioManager::class.java)
            val session = am?.generateAudioSessionId() ?: AudioManager.AUDIO_SESSION_ID_GENERATE
            val mp = MediaPlayer.create(context, resId, attrs(loud), session) ?: return
            mp.setOnCompletionListener { p ->
                synchronized(this) {
                    if (current === p) current = null
                }
                p.release()
            }
            current = mp
            mp.start()
        } catch (_: Exception) {
        }
    }

    /** الگوی لرزش «معامله انجام شد»: کوتاه، کوتاه، بلند. */
    fun vibrateDone(success: Boolean) {
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib == null || !vib.hasVibrator()) return
            val pattern = if (success) longArrayOf(0, 180, 110, 180, 110, 450) else longArrayOf(0, 600)
            val effect = VibrationEffect.createWaveform(pattern, -1)
            @Suppress("DEPRECATION")
            vib.vibrate(effect, attrs(false))
        } catch (_: Exception) {
        }
    }

    fun hasVibrator(): Boolean = try {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vib?.hasVibrator() == true
    } catch (_: Exception) {
        false
    }
}
