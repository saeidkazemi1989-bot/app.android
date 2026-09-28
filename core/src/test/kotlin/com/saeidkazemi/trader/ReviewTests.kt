package com.saeidkazemi.trader

import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.review.Prediction
import com.saeidkazemi.trader.review.ReportUploader
import com.saeidkazemi.trader.review.ReviewLog
import com.saeidkazemi.trader.review.ReviewReport
import com.saeidkazemi.trader.review.SelfReview
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** خودارزیابی: ثبت و ارزیابی پیش‌بینی‌ها، تشخیص عامل درست/غلط، گزارش و تقسیم برای ارسال. */
class ReviewTests {

    private fun synth(n: Int, now: Long): List<Prediction> {
        val rnd = java.util.Random(3)
        return (0 until n).map { i ->
            val flow = if (i % 2 == 0) 6 else -6
            val fg = if ((i / 2) % 2 == 0) 4 else -4
            val r1 = flow * 0.4 - fg * 0.3 + rnd.nextGaussian()
            Prediction(
                ts = now - 100 * ReviewLog.H1 - (i / 10) * ReviewLog.PREDICTION_GAP_MS, assetId = "a" + (i % 10), symbol = "S" + (i % 10),
                market = "CRYPTO", price = 100.0, score = 55 + flow + fg, tech = 55, pro = flow + fg, th = 60, sellTh = 45,
                factors = mapOf("جریان پول" to flow, "ترس و طمع" to fg),
                p1 = 100 * (1 + r1 / 100), p3 = 100 * (1 + 1.5 * r1 / 100), done1 = true, done3 = true
            )
        }
    }

    @Test
    fun factorVerdicts() {
        val e = SelfReview.evaluate(synth(240, System.currentTimeMillis()))
        val fs = SelfReview.factors(e).associateBy { it.name }
        assertEquals("درست", fs["عامل تخصصی: جریان پول"]?.verdict)
        assertEquals("غلط", fs["عامل تخصصی: ترس و طمع"]?.verdict)
    }

    @Test
    fun evaluateWindowsAndPersistence() {
        val dir = Files.createTempDirectory("rv").toFile()
        val c = AppContainer(dir)
        val now = System.currentTimeMillis()
        c.review.record(
            listOf(
                Prediction(ts = now - 25 * ReviewLog.H1, assetId = "x", symbol = "X", market = "CRYPTO", price = 100.0, score = 70, th = 60, sellTh = 45),
                Prediction(ts = now - 40 * ReviewLog.H1, assetId = "y", symbol = "Y", market = "CRYPTO", price = 100.0, score = 30, th = 60, sellTh = 45),
                Prediction(ts = now - 73 * ReviewLog.H1, assetId = "z", symbol = "Z", market = "CRYPTO", price = 100.0, score = 50, th = 60, sellTh = 45, done1 = true)
            )
        )
        c.review.evaluate(mapOf("x" to 110.0, "y" to 90.0, "z" to 95.0), irOpen = false, now = now)
        val p = c.review.predictions().associateBy { it.assetId }
        assertEquals(10.0, p["x"]!!.ret1!!, 1e-9)
        // ۴۰ ساعت: پنجره ۲۴ساعته گذشته بود → بدون قیمت
        assertTrue(p["y"]!!.done1 && p["y"]!!.p1 == null)
        assertEquals(-5.0, p["z"]!!.ret3!!, 1e-9)
        c.review.issue(ReviewLog.AREA_DATA, "داده 12 سهم دریافت نشد")
        c.review.issue(ReviewLog.AREA_DATA, "داده 15 سهم دریافت نشد")
        assertEquals(1, c.review.issues().size)
        assertEquals(2, c.review.issues().first().count)
        c.review.maybeSave(force = true)
        val c2 = AppContainer(dir)
        assertEquals(3, c2.review.predictions().size)
        assertEquals(2, c2.review.issues().first().count)
    }

    @Test
    fun reportAndSplit() {
        val dir = Files.createTempDirectory("rv2").toFile()
        val c = AppContainer(dir)
        c.review.record(synth(200, System.currentTimeMillis()))
        val sum = ReviewReport.summary(c)
        assertTrue(sum.buyN1 > 0)
        assertNotNull(sum.findings)
        val text = ReviewReport.build(c, "test")
        assertTrue(text.contains("کجا اشتباه کردم"))
        assertTrue(text.contains("\"predCols\""))
        val parts = ReportUploader.split(text)
        assertTrue(parts.all { it.toByteArray().size <= 3700 })
        assertEquals(text, parts.joinToString(""))
        val code = ReportUploader.newCode()
        assertTrue(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}").matches(code))
    }
}
