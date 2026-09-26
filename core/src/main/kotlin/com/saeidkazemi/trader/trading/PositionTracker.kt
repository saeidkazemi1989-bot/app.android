package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Position
import com.saeidkazemi.trader.data.model.PricePoint

/**
 * ثبت مسیر قیمت هر موقعیت باز از لحظه خرید (در هر دور موتور یک نقطه)،
 * تا نمودار «کجا خریدم و الان کجاست» حتی برای معاملات درون‌روزی هم دقیق باشد.
 * با بسته شدن موقعیت، مسیرش پاک می‌شود. برای محدود ماندن حجم، نقاط قدیمی به‌تدریج کم‌تراکم می‌شوند.
 */
class PositionTracker(private val store: JsonStore?) {

    companion object {
        const val MAX_POINTS = 600
        /** نقاط اخیر که همیشه با دقت کامل نگه داشته می‌شوند. */
        const val KEEP_RECENT = 200
    }

    private val lock = Any()
    private var tracks: MutableMap<String, MutableList<PricePoint>>? = null

    private fun ensure(): MutableMap<String, MutableList<PricePoint>> {
        tracks?.let { return it }
        val loaded = HashMap<String, MutableList<PricePoint>>()
        try {
            store?.loadTracks()?.forEach { (k, v) -> loaded[k] = v.toMutableList() }
        } catch (_: Exception) {
        }
        tracks = loaded
        return loaded
    }

    /** پاک کردن مسیر یک موقعیت (از نو با نقطه خرید شروع می‌شود). */
    fun clear(assetId: String) {
        synchronized(lock) {
            ensure().remove(assetId)
            try {
                store?.saveTracks(ensure())
            } catch (_: Exception) {
            }
        }
    }

    fun track(assetId: String): List<PricePoint> = synchronized(lock) { ensure()[assetId]?.toList().orEmpty() }

    /** ثبت قیمت فعلی همه موقعیت‌های باز و حذف مسیر موقعیت‌های بسته‌شده. */
    fun record(positions: List<Position>, pricesUsd: Map<String, Double>, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val map = ensure()
            val open = positions.associateBy { it.assetId }
            map.keys.retainAll(open.keys)
            for (pos in positions) {
                val list = map.getOrPut(pos.assetId) { mutableListOf() }
                // اگر این مسیر مربوط به خرید قبلی همین دارایی است، از نو شروع شود
                if (list.isNotEmpty() && list.first().t < pos.openedAt) list.clear()
                if (list.isEmpty()) list.add(PricePoint(pos.openedAt, pos.avgBuyUsd))
                val p = pricesUsd[pos.assetId] ?: continue
                if (!p.isFinite() || p <= 0) continue
                if (now <= list.last().t) continue
                list.add(PricePoint(now, p))
                thin(list)
            }
            try {
                store?.saveTracks(map)
            } catch (_: Exception) {
            }
        }
    }

    /** وقتی تعداد نقاط از حد گذشت، از بخش قدیمی (به جز نقطه خرید) یکی‌درمیان حذف می‌شود. */
    private fun thin(list: MutableList<PricePoint>) {
        if (list.size <= MAX_POINTS) return
        val oldEnd = list.size - KEEP_RECENT
        val kept = ArrayList<PricePoint>(list.size)
        kept.add(list[0])
        for (i in 1 until oldEnd) if (i % 2 == 0) kept.add(list[i])
        for (i in oldEnd until list.size) kept.add(list[i])
        list.clear()
        list.addAll(kept)
    }
}
