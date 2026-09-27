package com.saeidkazemi.trader

import com.saeidkazemi.trader.core.AppContainer
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.sync.SyncCrypto
import com.saeidkazemi.trader.sync.SyncRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** اتصال اندروید و ویندوز: رمزنگاری، و همگام‌سازی کامل در شبکه محلی بین دو دستگاه. */
class SyncTests {

    @Test
    fun cryptoRoundTripAndWrongCode() {
        val code = SyncCrypto.newCode()
        val a = SyncCrypto(code)
        val b = SyncCrypto(code.lowercase().replace("-", " "))
        val sealed = a.seal("سلام معامله‌یار".toByteArray())
        assertEquals("سلام معامله‌یار", String(b.open(sealed)!!))
        assertEquals(a.topic, b.topic)
        assertNull(SyncCrypto("ZZZZZ-ZZZZZ").open(sealed))
        // دست‌کاری داده
        val bad = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(a.open(bad))
        assertEquals(SyncCrypto.normalize("abcde-۱۲۳۴۵"), "ABCDE12345")
    }

    @Test
    fun lanMirrorAndCommands() = runBlocking {
        val host = AppContainer(Files.createTempDirectory("host").toFile())
        val btc = Asset("bitcoin", "BTC", "Bitcoin", MarketKind.CRYPTO, "USD", 100_000.0, 0.0, 0L)
        host.broker.reset(1000.0, mapOf("CRYPTO" to 50.0, "IR_STOCK" to 40.0, "METAL" to 10.0))
        host.store.saveSettings(host.store.loadSettings().copy(capitalUsd = 1000.0, nobitexToken = "SECRET"))
        host.broker.buy(btc, 100_000.0, 200.0, 0.0025, 90_000.0, 150_000.0, "t")
        host.sync.start(SyncRole.HOST, "host")
        host.sync.setRelay(false)
        val code = host.sync.status.value.code
        val addr = host.sync.status.value.localAddresses.firstOrNull()
        val port = addr?.substringAfterLast(':') ?: "47631"

        val f = AppContainer(Files.createTempDirectory("follower").toFile())
        f.sync.start(SyncRole.OFF, "follower")
        f.sync.setRelay(false)
        f.sync.setManualHost("127.0.0.1:$port")
        assertTrue(f.sync.becomeFollower(code))
        withTimeoutOrNull(20_000) { while (f.sync.mirror == null) delay(200) }
        assertNotNull(f.sync.mirror, "follower did not receive snapshot: " + f.sync.status.value.message)
        assertTrue(f.tradeEngine.followerMode)
        assertEquals(1, f.broker.account().positions.size)
        assertEquals(host.broker.account().cashUsd, f.broker.account().cashUsd, 1e-9)
        // توکن هرگز کپی نمی‌شود
        assertEquals("", f.store.loadSettings().nobitexToken)

        // تغییر تنظیمات از آینه (صدا مخصوص هر دستگاه است و کپی نمی‌شود)
        val old = f.store.loadSettings()
        val patch = f.sync.settingsPatch(old, old.copy(profitLockTriggerPct = 12.5, soundAlerts = !old.soundAlerts))
        assertNotNull(patch)
        assertTrue("soundAlerts" !in patch)
        f.sync.send("settings", emptyMap(), patch)
        assertEquals(12.5, host.store.loadSettings().profitLockTriggerPct)
        assertEquals("SECRET", host.store.loadSettings().nobitexToken)

        // افزایش سرمایه از آینه روی دستگاه اصلی اجرا و به آینه برمی‌گردد
        f.sync.send("capital", mapOf("usd" to "1500"))
        assertEquals(1500.0, host.store.loadSettings().capitalUsd)
        withTimeoutOrNull(20_000) { while (f.store.loadSettings().capitalUsd != 1500.0) delay(200) }
        assertEquals(1500.0, f.store.loadSettings().capitalUsd)
        assertEquals(host.broker.account().cashUsd, f.broker.account().cashUsd, 1e-9)

        f.sync.turnOff()
        host.sync.turnOff()
        assertTrue(!f.tradeEngine.followerMode)
    }
}
