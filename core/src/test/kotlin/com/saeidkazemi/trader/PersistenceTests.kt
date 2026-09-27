package com.saeidkazemi.trader

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.trading.PaperBroker
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** خریدها نباید با بسته شدن/به‌روزرسانی برنامه یا تغییر تنظیمات از بین بروند. */
class PersistenceTests {

    private val btc = Asset("bitcoin", "BTC", "Bitcoin", MarketKind.CRYPTO, "USD", 100_000.0, 0.0, 0L)
    private val eth = Asset("ethereum", "ETH", "Ethereum", MarketKind.CRYPTO, "USD", 4_000.0, 0.0, 0L)

    private fun freshBroker(dir: File): PaperBroker {
        val b = PaperBroker(JsonStore(dir))
        b.reset(1000.0, mapOf("CRYPTO" to 50.0, "IR_STOCK" to 40.0, "METAL" to 10.0))
        return b
    }

    @Test
    fun positionsSurviveRestart() {
        val dir = Files.createTempDirectory("persist").toFile()
        freshBroker(dir).buy(btc, 100_000.0, 200.0, 0.0025, 90_000.0, 150_000.0, "t")
        // «به‌روزرسانی» = پردازه جدید با همان پوشه داده
        val after = PaperBroker(JsonStore(dir)).account()
        assertEquals(1, after.positions.size)
        assertEquals("bitcoin", after.positions.single().assetId)
    }

    @Test
    fun nanDoesNotBreakSaving() {
        val dir = Files.createTempDirectory("nan").toFile()
        val writer = JsonStore(dir)
        val b = PaperBroker(writer)
        b.reset(1000.0, mapOf("CRYPTO" to 100.0))
        b.buy(btc, 100_000.0, 200.0, 0.0025, 90_000.0, 150_000.0, "t", trailPct = Double.NaN)
        b.buy(eth, 4_000.0, 100.0, 0.0025, Double.NaN, 6_000.0, "t")
        assertNull(writer.lastWriteError)
        val after = PaperBroker(JsonStore(dir)).account()
        assertEquals(2, after.positions.size, "با عدد نامعتبر هم باید ذخیره شود (قبلاً ذخیره بی‌صدا شکست می‌خورد)")
    }

    @Test
    fun corruptFileRecoversFromBackup() {
        val dir = Files.createTempDirectory("corrupt").toFile()
        val b = freshBroker(dir)
        b.buy(btc, 100_000.0, 200.0, 0.0025, 90_000.0, 150_000.0, "t")
        b.buy(eth, 4_000.0, 100.0, 0.0025, 3_000.0, 6_000.0, "t")
        File(dir, "account.json").writeText("{ broken", Charsets.UTF_8)
        val store = JsonStore(dir)
        val acc = PaperBroker(store).account()
        // پشتیبان = وضعیت قبل از آخرین ذخیره (خرید BTC)
        assertTrue(acc.positions.any { it.assetId == "bitcoin" })
        assertTrue(store.recoveryNotes.isNotEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("account.json.corrupt-") }, "فایل خراب نباید پاک شود")
    }

    @Test
    fun reallocateKeepsPositions() {
        val dir = Files.createTempDirectory("realloc").toFile()
        val b = freshBroker(dir)
        b.buy(btc, 100_000.0, 200.0, 0.0, 90_000.0, 150_000.0, "t")
        val before = b.account()
        val acc = b.reallocate(mapOf("CRYPTO" to 30.0, "IR_STOCK" to 30.0, "METAL" to 40.0)) { p -> p.qty * p.avgBuyUsd }
        assertEquals(1, acc.positions.size)
        assertEquals(before.cashUsd, acc.cashByMarket.values.sum(), 1e-6)
        // کریپتو: ۲۰۰ دلار در موقعیت؛ هدف ۳۰٪ از ۱۰۰۰ = ۳۰۰ → ۱۰۰ نقد
        assertEquals(100.0, acc.cashByMarket["CRYPTO"]!!, 1e-6)
        assertEquals(400.0, acc.cashByMarket["METAL"]!!, 1e-6)
        assertEquals(1000.0, acc.initialCapitalUsd, 1e-9)
    }

    @Test
    fun adjustCapitalKeepsPositions() {
        val dir = Files.createTempDirectory("cap").toFile()
        val b = freshBroker(dir)
        b.buy(btc, 100_000.0, 400.0, 0.0, 90_000.0, 150_000.0, "t")
        val alloc = mapOf("CRYPTO" to 50.0, "IR_STOCK" to 40.0, "METAL" to 10.0)
        val up = b.adjustCapital(2000.0, alloc)
        assertNotNull(up)
        assertEquals(1, up.positions.size)
        assertEquals(2000.0, up.initialCapitalUsd, 1e-9)
        assertEquals(1600.0, up.cashUsd, 1e-6)
        assertEquals(600.0, up.cashByMarket["CRYPTO"]!!, 1e-6)
        // کاهش بیش از نقد آزاد انجام نمی‌شود (خریدها بسته نمی‌شوند)
        assertNull(b.adjustCapital(100.0, alloc))
        assertEquals(1, b.account().positions.size)
        val down = b.adjustCapital(1000.0, alloc)
        assertNotNull(down)
        assertEquals(600.0, down.cashUsd, 1e-6)
    }
}
