package com.saeidkazemi.trader.news

import kotlin.math.tanh

/**
 * تحلیل احساس خبر با واژه‌نامه وزن‌دار فارسی و انگلیسی (سبک، آفلاین و قابل توضیح).
 *
 * عبارت‌های چندکلمه‌ای (مثل «افزایش سرمایه» یا «کاهش زیان») قبل از کلمات تکی بررسی می‌شوند
 * و بخش تطبیق‌خورده از متن حذف می‌شود تا دوبار شمرده نشود.
 * این روش جایگزین تحلیل انسانی نیست؛ فقط یک سیگنال کمکی کنار تحلیل تکنیکال است.
 */
object Sentiment {

    data class Result(val score: Double, val matched: List<String>)

    // ---------- فارسی (اطلاعیه‌های کدال و اخبار بورس) ----------
    private val fa: List<Pair<String, Double>> = listOf(
        // عبارات ترکیبی (اولویت بالاتر)
        "کاهش زیان" to 1.5,
        "افزایش زیان" to -2.0,
        "شناسایی زیان" to -2.0,
        "زیان انباشته" to -1.5,
        "کاهش سود" to -1.8,
        "افت سود" to -1.8,
        "افزایش سود" to 1.8,
        "رشد سود" to 1.8,
        "تعدیل مثبت" to 2.0,
        "تعدیل منفی" to -2.0,
        "افزایش سرمایه از محل سود انباشته" to 2.2,
        "افزایش سرمایه از محل تجدید ارزیابی" to 1.8,
        "افزایش سرمایه از محل آورده" to 0.8,
        "افزایش سرمایه" to 1.2,
        "تقسیم سود" to 1.0,
        "سود نقدی" to 1.0,
        "افزایش فروش" to 1.5,
        "کاهش فروش" to -1.5,
        "افزایش درآمد" to 1.5,
        "کاهش درآمد" to -1.5,
        "افزایش نرخ" to 0.8,
        "رشد داشته" to 1.2,
        "کاهش داشته" to -1.2,
        "صف خرید" to 1.5,
        "صف فروش" to -1.5,
        "ورود پول حقیقی" to 1.2,
        "خروج پول حقیقی" to -1.2,
        "توقف نماد" to -1.2,
        "توقف معاملات" to -1.2,
        "تعلیق نماد" to -1.5,
        "بازگشایی نماد" to 0.3,
        "افشای اطلاعات با اهمیت" to 0.0,
        "قرارداد جدید" to 1.2,
        "انعقاد قرارداد" to 1.2,
        "افزایش تولید" to 1.3,
        "کاهش تولید" to -1.3,
        "نظر مقبول" to 1.0,
        "نظر مشروط" to -0.8,
        "عدم اظهار نظر" to -1.5,
        "نظر مردود" to -2.0,
        "رد صلاحیت" to -1.5,
        "قطعی برق" to -1.2,
        "قطع گاز" to -1.2,
        // تک‌واژه‌ها
        "زیان" to -1.5,
        "زیان‌ده" to -1.5,
        "ضرر" to -1.3,
        "سودآوری" to 1.2,
        "سود" to 0.6,
        "رشد" to 1.0,
        "صعود" to 1.0,
        "رکورد" to 1.0,
        "افزایش" to 0.4,
        "کاهش" to -0.4,
        "افت" to -1.0,
        "ریزش" to -1.3,
        "سقوط" to -1.6,
        "نزول" to -1.0,
        "تحریم" to -1.2,
        "جریمه" to -1.2,
        "بحران" to -1.3,
        "ابطال" to -1.0,
        "توقف" to -0.8,
        "تعلیق" to -1.0,
        "صادرات" to 0.6,
        "توسعه" to 0.6,
        "بهره‌برداری" to 1.0,
        "مثبت" to 0.8,
        "منفی" to -0.8,
    )

    // ---------- انگلیسی (اخبار جهانی ارز دیجیتال، فارکس و طلا) ----------
    private val en: List<Pair<String, Double>> = listOf(
        "all-time high" to 2.0,
        "all time high" to 2.0,
        "record high" to 1.8,
        "etf approval" to 2.0,
        "etf inflows" to 1.5,
        "etf outflows" to -1.5,
        "short squeeze" to 1.0,
        "sell-off" to -1.5,
        "selloff" to -1.5,
        "price target raised" to 1.5,
        "price target cut" to -1.5,
        "rate cut" to 0.8,
        "rate hike" to -0.8,
        "security breach" to -2.0,
        "class action" to -1.5,
        "under investigation" to -1.5,
        "files for bankruptcy" to -2.5,
        "surge" to 1.5, "surges" to 1.5, "surged" to 1.5, "soar" to 1.6, "soars" to 1.6, "soared" to 1.6,
        "rally" to 1.3, "rallies" to 1.3, "rallied" to 1.3, "jump" to 1.2, "jumps" to 1.2, "jumped" to 1.2,
        "gain" to 0.8, "gains" to 0.8, "climb" to 1.0, "climbs" to 1.0, "rise" to 0.8, "rises" to 0.8, "rising" to 0.7,
        "rebound" to 1.0, "rebounds" to 1.0, "recover" to 0.8, "recovers" to 0.8, "breakout" to 1.3,
        "bullish" to 1.5, "upgrade" to 1.2, "upgraded" to 1.2, "approval" to 1.2, "approved" to 1.2, "approves" to 1.2,
        "adoption" to 1.0, "partnership" to 1.0, "launches" to 0.6, "inflows" to 1.0, "beats" to 1.2, "strong" to 0.6,
        "optimism" to 1.0, "boost" to 1.0, "boosts" to 1.0, "outperform" to 1.2, "accumulate" to 0.8,
        "plunge" to -1.8, "plunges" to -1.8, "plunged" to -1.8, "crash" to -2.0, "crashes" to -2.0, "crashed" to -2.0,
        "tumble" to -1.5, "tumbles" to -1.5, "slump" to -1.5, "slumps" to -1.5, "drop" to -1.0, "drops" to -1.0,
        "fall" to -0.9, "falls" to -0.9, "fell" to -0.9, "decline" to -0.9, "declines" to -0.9, "slide" to -1.0, "slides" to -1.0,
        "bearish" to -1.5, "downgrade" to -1.2, "downgraded" to -1.2, "hack" to -2.2, "hacked" to -2.2, "exploit" to -2.0,
        "lawsuit" to -1.5, "sues" to -1.3, "sued" to -1.3, "fraud" to -2.0, "scam" to -2.0, "ban" to -1.5, "bans" to -1.5,
        "banned" to -1.5, "delist" to -2.0, "delisted" to -2.0, "delisting" to -2.0, "bankruptcy" to -2.5, "insolvent" to -2.2,
        "liquidation" to -1.2, "liquidations" to -1.2, "outflows" to -1.0, "warning" to -0.8, "warns" to -0.8, "fear" to -0.8,
        "fears" to -0.8, "dump" to -1.3, "dumps" to -1.3, "losses" to -1.0, "weak" to -0.6, "weakens" to -0.8,
        "sanctions" to -1.0, "probe" to -1.2, "charges" to -1.0, "halt" to -1.2, "halts" to -1.2, "suspend" to -1.2,
    )

    private val negators = setOf("not", "no", "never", "without", "fails", "failed", "denies", "unlikely")

    private val enPatterns: List<Triple<String, Double, Regex>> = en
        .sortedByDescending { it.first.length }
        .map { (t, w) -> Triple(t, w, Regex("(?<![a-z])" + Regex.escape(t) + "(?![a-z])")) }

    private val faSorted: List<Pair<String, Double>> = fa
        .map { normalizeFa(it.first) to it.second }
        .sortedByDescending { it.first.length }

    /** یکسان‌سازی حروف عربی/فارسی، نیم‌فاصله و ارقام. */
    fun normalizeFa(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'ي', 'ى' -> sb.append('ی')
                'ك' -> sb.append('ک')
                'ة' -> sb.append('ه')
                'أ', 'إ' -> sb.append('ا')
                '\u200c', '\u200f', '\u200e', '_' -> sb.append(' ')
                in '\u064B'..'\u065F' -> {} // اعراب
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }

    fun analyze(text: String): Result {
        if (text.isBlank()) return Result(0.0, emptyList())
        var raw = 0.0
        val matched = mutableListOf<String>()

        // فارسی
        var t = normalizeFa(text)
        for ((term, w) in faSorted) {
            var idx = t.indexOf(term)
            while (idx >= 0) {
                raw += w
                if (w != 0.0) matched.add(term)
                t = t.substring(0, idx) + " ".repeat(term.length) + t.substring(idx + term.length)
                idx = t.indexOf(term, idx + term.length)
            }
        }

        // انگلیسی
        var e = text.lowercase()
        for ((term, w, rx) in enPatterns) {
            val hits = rx.findAll(e).toList()
            if (hits.isEmpty()) continue
            for (m in hits) {
                val before = e.substring(0, m.range.first).trimEnd()
                val prevWord = before.substringAfterLast(' ')
                val sign = if (prevWord in negators) -0.6 else 1.0
                raw += w * sign
                matched.add(term)
            }
            e = rx.replace(e) { " ".repeat(it.value.length) }
        }

        val score = tanh(raw / 2.5)
        return Result(score, matched.distinct().take(6))
    }
}
