package com.saeidkazemi.trader.review

import com.saeidkazemi.trader.data.local.JsonStore

/**
 * یک «پیش‌بینی» ثبت‌شده: امتیاز و اجزای تحلیل یک دارایی در یک لحظه + قیمت ۲۴ و ۷۲ ساعت بعد.
 * با این‌ها معلوم می‌شود موتور کجا درست فکر کرده و کجا اشتباه (حتی برای دارایی‌هایی که خریده نشدند).
 * قیمت‌ها به ارز خود دارایی‌اند (ریال/تومان/دلار) تا نوسان نرخ دلار روی نتیجه اثر نگذارد.
 */
data class Prediction(
    val ts: Long = 0L,
    val assetId: String = "",
    val symbol: String = "",
    val market: String = "",
    val price: Double = 0.0,
    val score: Int = 0,
    val tech: Int = 0,
    val news: Int = 0,
    val pro: Int = 0,
    /** آستانه خرید و فروش همان بازار در آن لحظه. */
    val th: Int = 0,
    val sellTh: Int = 0,
    val action: String = "",
    /** پیش‌بینی آماری ۷روزه (درصد) و احتمال بالا رفتن. */
    val fcExp: Double? = null,
    val fcUp: Double? = null,
    /** مهم‌ترین عوامل تحلیل تخصصی: عنوان ← اثر روی امتیاز. */
    val factors: Map<String, Int>? = null,
    val held: Boolean = false,
    val p1: Double? = null,
    val p3: Double? = null,
    val hi3: Double? = null,
    val lo3: Double? = null,
    val done1: Boolean = false,
    val done3: Boolean = false
) {
    val ret1: Double? get() = p1?.let { if (price > 0) (it / price - 1) * 100 else null }
    val ret3: Double? get() = p3?.let { if (price > 0) (it / price - 1) * 100 else null }
    val maxUp3: Double? get() = hi3?.let { if (price > 0) (it / price - 1) * 100 else null }
    val maxDown3: Double? get() = lo3?.let { if (price > 0) (it / price - 1) * 100 else null }

    /** جهت پیش‌بینی‌شده: ۱ خرید (امتیاز ≥ آستانه)، −۱ فروش (امتیاز ≤ آستانه فروش)، ۰ خنثی. */
    val dir: Int get() = if (score >= th) 1 else if (score <= sellTh) -1 else 0
}

/** مشکل فنی یا داده‌ای تکرارشونده (با تعداد دفعات). */
data class DiagIssue(
    val key: String = "",
    val area: String = "",
    val message: String = "",
    val count: Int = 0,
    val firstAt: Long = 0L,
    val lastAt: Long = 0L
)

/** خلاصه یک دور بررسی بازار. */
data class CycleDiag(
    val ts: Long = 0L,
    val trigger: String = "",
    val ms: Long = 0L,
    val assets: Int = 0,
    /** تعداد دارایی با داده واقعی / شبیه‌سازی‌شده در هر بازار. */
    val real: Map<String, Int>? = null,
    val sim: Map<String, Int>? = null,
    val signals: Int = 0,
    val buys: Int = 0,
    val sells: Int = 0,
    val problems: Int = 0
)

/**
 * دفتر خودارزیابی: پیش‌بینی‌ها، مشکلات و دورهای بررسی را نگه می‌دارد تا گزارش «کجا درست، کجا غلط»
 * ساخته شود. روی فایل review.json ذخیره می‌شود و با به‌روزرسانی برنامه پاک نمی‌شود.
 */
class ReviewLog(private val store: JsonStore) {

    data class ReviewFile(
        val startedAt: Long = 0L,
        val predictions: List<Prediction>? = null,
        val issues: List<DiagIssue>? = null,
        val cycles: List<CycleDiag>? = null
    )

    private val lock = Any()
    private var loaded = false
    private val preds = ArrayList<Prediction>()
    private val lastByAsset = HashMap<String, Long>()
    private val issues = LinkedHashMap<String, DiagIssue>()
    private val cycles = ArrayDeque<CycleDiag>()
    private var dirty = false
    private var lastSave = 0L

    var startedAt: Long = 0L
        private set

    private fun ensure() {
        if (loaded) return
        loaded = true
        val f = try { store.loadFile(FILE, ReviewFile::class.java) } catch (_: Exception) { null }
        startedAt = f?.startedAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
        f?.predictions.orEmpty().filterNotNull().filter { it.assetId.isNotEmpty() && it.price > 0 }.forEach {
            preds.add(it)
            if (it.ts > (lastByAsset[it.assetId] ?: 0L)) lastByAsset[it.assetId] = it.ts
        }
        f?.issues.orEmpty().filterNotNull().forEach { if (it.key.isNotEmpty()) issues[it.key] = it }
        f?.cycles.orEmpty().filterNotNull().forEach { cycles.addLast(it) }
        if (f == null) dirty = true
    }

    fun predictions(): List<Prediction> = synchronized(lock) { ensure(); preds.toList() }
    fun issues(): List<DiagIssue> = synchronized(lock) { ensure(); issues.values.sortedByDescending { it.lastAt } }
    fun cycles(): List<CycleDiag> = synchronized(lock) { ensure(); cycles.toList() }
    fun since(): Long = synchronized(lock) { ensure(); startedAt }

    fun lastPredictionAt(assetId: String): Long = synchronized(lock) { ensure(); lastByAsset[assetId] ?: 0L }

    /** ثبت یک مشکل (پیام‌های مشابه با اعداد متفاوت یکی شمرده می‌شوند). */
    fun issue(area: String, message: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        ensure()
        val msg = message.trim().take(300)
        if (msg.isEmpty()) return@synchronized
        val key = area + "|" + normalize(msg)
        val old = issues[key]
        issues.remove(key)
        issues[key] = if (old == null) DiagIssue(key, area, msg, 1, now, now)
        else old.copy(message = msg, count = old.count + 1, lastAt = now)
        while (issues.size > MAX_ISSUES) issues.remove(issues.keys.first())
        dirty = true
    }

    /** یادداشت‌های هر دور را بررسی می‌کند و خطاها/کمبود داده را ثبت می‌کند. */
    fun ingestNotes(notes: List<String>): Int {
        var n = 0
        for (note in notes) {
            val area = classify(note) ?: continue
            issue(area, note)
            n++
        }
        return n
    }

    fun cycle(c: CycleDiag) = synchronized(lock) {
        ensure()
        cycles.addLast(c)
        while (cycles.size > MAX_CYCLES) cycles.removeFirst()
        dirty = true
    }

    fun record(items: List<Prediction>) = synchronized(lock) {
        if (items.isEmpty()) return@synchronized
        ensure()
        for (p in items) {
            preds.add(p)
            lastByAsset[p.assetId] = p.ts
        }
        if (preds.size > MAX_PREDICTIONS) {
            val drop = preds.size - MAX_PREDICTIONS
            preds.subList(0, drop).clear()
        }
        dirty = true
    }

    /**
     * ثبت نتیجه پیش‌بینی‌ها با قیمت فعلی. ارز دیجیتال و طلا: قیمت ۲۴ و ۷۲ ساعت بعد (اگر برنامه آن موقع
     * خاموش بوده، آن پیش‌بینی ارزیابی نمی‌شود). بورس تهران: اولین قیمت بازار باز بعد از ۲۴/۷۲ ساعت.
     */
    fun evaluate(prices: Map<String, Double>, irOpen: Boolean, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        ensure()
        for (i in preds.indices) {
            val p = preds[i]
            if (p.done1 && p.done3) continue
            val cur = prices[p.assetId]?.takeIf { it.isFinite() && it > 0 } ?: continue
            val age = now - p.ts
            val ir = p.market == "IR_STOCK"
            var q = p
            if (age <= H72) {
                q = q.copy(hi3 = maxOf(q.hi3 ?: cur, cur), lo3 = minOf(q.lo3 ?: cur, cur))
            }
            if (!q.done1 && age >= H24) {
                q = when {
                    ir && !irOpen && age < 5 * H24 -> q
                    ir -> if (age < 5 * H24) q.copy(p1 = cur, done1 = true) else q.copy(done1 = true)
                    age <= H24 + 6 * H1 -> q.copy(p1 = cur, done1 = true)
                    else -> q.copy(done1 = true)
                }
            }
            if (!q.done3 && age >= H72) {
                q = when {
                    ir && !irOpen && age < 8 * H24 -> q
                    ir -> if (age < 8 * H24) q.copy(p3 = cur, done3 = true) else q.copy(done3 = true)
                    age <= H72 + 12 * H1 -> q.copy(p3 = cur, done3 = true)
                    else -> q.copy(done3 = true)
                }
            }
            if (q != p) {
                preds[i] = q
                dirty = true
            }
        }
    }

    fun maybeSave(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val snapshot: ReviewFile
        synchronized(lock) {
            ensure()
            if (!dirty) return
            if (!force && now - lastSave < SAVE_GAP_MS) return
            snapshot = ReviewFile(startedAt, preds.toList(), issues.values.toList(), cycles.toList())
            dirty = false
            lastSave = now
        }
        try { store.saveFile(FILE, snapshot) } catch (_: Exception) { }
    }

    fun clear() = synchronized(lock) {
        ensure()
        preds.clear(); lastByAsset.clear(); issues.clear(); cycles.clear()
        startedAt = System.currentTimeMillis()
        dirty = true
    }

    companion object {
        const val FILE = "review.json"
        const val MAX_PREDICTIONS = 6000
        const val MAX_ISSUES = 150
        const val MAX_CYCLES = 300
        const val SAVE_GAP_MS = 10 * 60_000L
        const val H1 = 3_600_000L
        const val H24 = 24 * H1
        const val H72 = 72 * H1

        /** فاصله ثبت دو پیش‌بینی برای یک دارایی. */
        const val PREDICTION_GAP_MS = 6 * H1

        const val AREA_DATA = "داده بازار"
        const val AREA_NEWS = "اخبار"
        const val AREA_TRADE = "اجرای معامله"
        const val AREA_ENGINE = "موتور"
        const val AREA_STORAGE = "ذخیره‌سازی"
        const val AREA_SYNC = "اتصال دستگاه‌ها"
        const val AREA_UPDATE = "به‌روزرسانی"

        private val digits = Regex("[0-9۰-۹][0-9۰-۹.,٫٬]*")

        fun normalize(msg: String): String = msg.replace(digits, "#").take(160)

        private val problemWords = listOf(
            "خطا", "دریافت نشد", "در دسترس نبود", "در دسترس نیست", "ممکن نشد", "ناموفق", "شبیه‌سازی",
            "پیدا نشد", "خراب", "بازیابی شد", "انجام نشد", "رد شد"
        )

        /** دسته مشکل از روی متن یادداشت؛ null یعنی یادداشت عادی است. */
        fun classify(note: String): String? {
            if (problemWords.none { note.contains(it) }) return null
            return when {
                note.contains("خبر") -> AREA_NEWS
                note.contains("ذخیره") || note.contains("بازیابی") || note.contains("خراب") -> AREA_STORAGE
                (note.contains("فروش") || note.contains("خرید") || note.contains("سفارش")) &&
                    (note.contains("ممکن نشد") || note.contains("انجام نشد") || note.contains("ناموفق") || note.contains("رد شد")) -> AREA_TRADE
                else -> AREA_DATA
            }
        }
    }
}
