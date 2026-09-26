package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.AccountState
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.Position
import com.saeidkazemi.trader.data.model.Trade
import java.util.UUID

/**
 * کارگزار دمو (معامله آزمایشی): لجر داخلی اپ با دلار، بدون هیچ ریسک واقعی.
 * همه حسابداری بر مبنای دلار انجام می‌شود؛ دارایی‌های ریالی هنگام معامله با نرخ روز تبدیل می‌شوند.
 *
 * سرمایه بین بازارها تقسیم می‌شود (مثلاً ۵۰٪ ارز دیجیتال، ۴۰٪ بورس، ۱۰٪ ارز خارجی) و هر بازار
 * «صندوق» جداگانه دارد: خرید فقط از نقد همان بازار و پول فروش به همان بازار برمی‌گردد.
 */
class PaperBroker(private val store: JsonStore) : Broker {

    override val mode: String = "PAPER"

    private val lock = Any()
    private var acc: AccountState? = null

    private fun ensure(): AccountState {
        val a = acc
        if (a != null) return a
        val loaded = migrate(store.loadAccount(store.loadSettings().capitalUsd))
        acc = loaded
        return loaded
    }

    /** حساب‌های قدیمی (بدون تقسیم بازار): نقد فعلی به نسبت تنظیمات بین بازارها پخش می‌شود. */
    private fun migrate(a: AccountState): AccountState {
        if (a.cashByMarket.isNotEmpty()) return a
        val alloc = normalizedAlloc(store.loadSettings().allocations)
        val cash = alloc.mapValues { (_, pct) -> a.cashUsd * pct }
        val cap = alloc.mapValues { (_, pct) -> a.initialCapitalUsd * pct }
        val m = a.copy(cashByMarket = cash, capitalByMarket = cap)
        store.saveAccount(m)
        return m
    }

    private fun normalizedAlloc(raw: Map<String, Double>): Map<String, Double> {
        val keys = listOf(MarketKind.CRYPTO.name, MarketKind.IR_STOCK.name, MarketKind.FX.name)
        val vals = keys.associateWith { maxOf(0.0, raw[it] ?: 0.0) }
        val sum = vals.values.sum()
        return if (sum <= 0) mapOf(MarketKind.CRYPTO.name to 1.0, MarketKind.IR_STOCK.name to 0.0, MarketKind.FX.name to 0.0)
        else vals.mapValues { it.value / sum }
    }

    /** نقد آزاد یک بازار. */
    fun cashOf(market: MarketKind): Double = synchronized(lock) { ensure().cashByMarket[market.name] ?: 0.0 }

    override fun account(): AccountState = synchronized(lock) { ensure() }

    fun reset(capitalUsd: Double, allocations: Map<String, Double> = store.loadSettings().allocations): AccountState = synchronized(lock) {
        val alloc = normalizedAlloc(allocations)
        val split = alloc.mapValues { (_, pct) -> capitalUsd * pct }
        val fresh = AccountState(
            cashUsd = capitalUsd,
            initialCapitalUsd = capitalUsd,
            cashByMarket = split,
            capitalByMarket = split
        )
        acc = fresh
        store.saveAccount(fresh)
        fresh
    }

    fun buy(
        asset: Asset,
        usdPrice: Double,
        usdAmount: Double,
        feePct: Double,
        stopLossUsd: Double,
        takeProfitUsd: Double,
        reason: String,
        trailPct: Double = 0.0
    ): Trade? = synchronized(lock) {
        val a = ensure()
        if (!usdPrice.isFinite() || usdPrice <= 0 || usdAmount <= 0) return@synchronized null
        val key = asset.market.name
        val sleeve = a.cashByMarket[key] ?: 0.0
        if (usdAmount > sleeve + 1e-9 || usdAmount > a.cashUsd + 1e-9) return@synchronized null
        val fee = usdAmount * feePct
        val net = usdAmount - fee
        if (net <= 0) return@synchronized null
        val qty = net / usdPrice
        val now = System.currentTimeMillis()
        val pos = Position(
            assetId = asset.id,
            symbol = asset.symbol,
            name = asset.name,
            market = asset.market,
            nativeCurrency = asset.baseCurrency,
            qty = qty,
            avgBuyUsd = usdPrice,
            openedAt = now,
            stopLossUsd = stopLossUsd,
            takeProfitUsd = takeProfitUsd,
            peakUsd = usdPrice,
            trailPct = trailPct,
            costUsd = usdAmount
        )
        val trade = Trade(
            id = shortId(),
            ts = now,
            assetId = asset.id,
            symbol = asset.symbol,
            side = "BUY",
            qty = qty,
            priceUsd = usdPrice,
            usdValue = usdAmount,
            feeUsd = fee,
            reason = reason,
            mode = mode
        )
        val next = a.copy(
            cashUsd = a.cashUsd - usdAmount,
            cashByMarket = a.cashByMarket + (key to (sleeve - usdAmount).coerceAtLeast(0.0)),
            positions = a.positions + pos,
            trades = listOf(trade) + a.trades
        )
        acc = next
        store.saveAccount(next)
        trade
    }

    fun sell(assetId: String, usdPrice: Double, feePct: Double, reason: String): Trade? =
        synchronized(lock) {
            val a = ensure()
            val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized null
            if (!usdPrice.isFinite() || usdPrice <= 0) return@synchronized null
            val gross = pos.qty * usdPrice
            val fee = gross * feePct
            val net = gross - fee
            val pnl = net - pos.cost()
            val trade = Trade(
                id = shortId(),
                ts = System.currentTimeMillis(),
                assetId = assetId,
                symbol = pos.symbol,
                side = "SELL",
                qty = pos.qty,
                priceUsd = usdPrice,
                usdValue = gross,
                feeUsd = fee,
                reason = reason,
                mode = mode
            )
            val key = pos.market.name
            val next = a.copy(
                cashUsd = a.cashUsd + net,
                cashByMarket = a.cashByMarket + (key to ((a.cashByMarket[key] ?: 0.0) + net)),
                realizedPnlUsd = a.realizedPnlUsd + pnl,
                realizedByMarket = a.realizedByMarket + (key to ((a.realizedByMarket[key] ?: 0.0) + pnl)),
                positions = a.positions.filterNot { it.assetId == assetId },
                trades = listOf(trade) + a.trades
            )
            acc = next
            store.saveAccount(next)
            trade
        }

    /**
     * اصلاح موقعیت پس از افزایش سرمایه/تقسیم سود: قیمت با ضریب factor کم شده، پس تعداد سهم زیاد می‌شود
     * و ارزش موقعیت ثابت می‌ماند (حد ضرر و حد سود هم به همان نسبت جابه‌جا می‌شوند).
     */
    fun adjustPosition(assetId: String, factor: Double, day: Int): Boolean = synchronized(lock) {
        if (!factor.isFinite() || factor <= 0.1 || factor >= 1.5) return@synchronized false
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized false
        if ((pos.adjDay ?: 0) >= day) return@synchronized false
        val adjusted = pos.copy(
            qty = pos.qty / factor,
            avgBuyUsd = pos.avgBuyUsd * factor,
            stopLossUsd = pos.stopLossUsd * factor,
            takeProfitUsd = pos.takeProfitUsd * factor,
            peakUsd = pos.peakUsd * factor,
            adjDay = day
        )
        val next = a.copy(positions = a.positions.map { if (it.assetId == assetId) adjusted else it })
        acc = next
        store.saveAccount(next)
        true
    }

    /**
     * حد ضرر متحرک: اگر قیمت به قله تازه رسید، قله ثبت و حد ضرر به «قله × (۱ − فاصله)» بالا کشیده می‌شود
     * (هیچ‌وقت پایین نمی‌آید). این‌طوری سود به‌دست‌آمده با برگشت قیمت از دست نمی‌رود.
     * خروجی: موقعیت‌هایی که حد ضررشان بالا کشیده شد.
     */
    fun trail(prices: Map<String, Double>): List<Position> = synchronized(lock) {
        val a = ensure()
        val moved = mutableListOf<Position>()
        var changed = false
        val updated = a.positions.map { p ->
            val px = prices[p.assetId] ?: return@map p
            if (!px.isFinite() || px <= 0) return@map p
            val peak = maxOf(if (p.peakUsd > 0) p.peakUsd else p.avgBuyUsd, px)
            var next = p
            if (peak > p.peakUsd + 1e-12) { next = next.copy(peakUsd = peak); changed = true }
            if (p.trailPct > 0) {
                val trailStop = peak * (1 - p.trailPct)
                // فقط وقتی حد ضرر را بالا می‌کشیم که حداقل به نقطه سربه‌سر (با کارمزد) نزدیک شده باشد یا بالاتر از حد قبلی باشد
                if (trailStop > next.stopLossUsd * 1.0005) {
                    next = next.copy(stopLossUsd = trailStop)
                    changed = true
                    moved += next
                }
            }
            next
        }
        if (changed) {
            val n = a.copy(positions = updated)
            acc = n
            store.saveAccount(n)
        }
        moved
    }

    /**
     * قفل سود: حد ضرر را به [stopUsd] می‌برد (فقط اگر بالاتر از حد فعلی باشد؛ هیچ‌وقت پایین نمی‌آورد)
     * و درصد سود قفل‌شده را ثبت می‌کند. خروجی: true اگر قفل تازه فعال شد.
     */
    fun lockProfit(assetId: String, stopUsd: Double, keepPct: Double): Boolean = synchronized(lock) {
        if (!stopUsd.isFinite() || stopUsd <= 0) return@synchronized false
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized false
        val newlyLocked = pos.profitLockedPct < keepPct - 1e-9
        val raise = stopUsd > pos.stopLossUsd * 1.0000001
        if (!newlyLocked && !raise) return@synchronized false
        val updated = pos.copy(
            stopLossUsd = maxOf(pos.stopLossUsd, stopUsd),
            profitLockedPct = maxOf(pos.profitLockedPct, keepPct)
        )
        val next = a.copy(positions = a.positions.map { if (it.assetId == assetId) updated else it })
        acc = next
        store.saveAccount(next)
        newlyLocked
    }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
}
