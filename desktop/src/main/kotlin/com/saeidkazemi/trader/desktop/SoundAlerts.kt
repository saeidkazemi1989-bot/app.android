package com.saeidkazemi.trader.desktop

import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.data.model.TradeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

/**
 * هشدار صوتی معاملات در ویندوز: قبل از معامله دو بوق کوتاه و پس از انجام آن، زنگ سه‌نتی
 * (همان فایل‌های صدای نسخه اندروید). اعلان کنار ساعت هم پس از هر دور معامله نمایش داده می‌شود.
 */
object SoundAlerts {

    fun play(name: String): Boolean {
        return try {
            val res = SoundAlerts::class.java.classLoader.getResourceAsStream("sounds/$name.wav") ?: return false
            val stream = AudioSystem.getAudioInputStream(BufferedInputStream(res))
            val clip: Clip = AudioSystem.getClip()
            clip.addLineListener { e -> if (e.type == LineEvent.Type.STOP) clip.close() }
            clip.open(stream)
            clip.start()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun attach(container: AppContainer, scope: CoroutineScope) {
        scope.launch {
            container.tradeEngine.events.collect { ev ->
                val s = container.store.loadSettings()
                when (ev) {
                    is TradeEvent.Starting -> if (s.soundAlerts) play("trade_start")
                    is TradeEvent.Completed -> if (s.soundAlerts) play("trade_done")
                }
            }
        }
    }
}
