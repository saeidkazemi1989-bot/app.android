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
        val keys = MarketKind.TRADED.map { it.name }
        val vals = keys.associateWith { maxOf(0.0, raw[it] ?: 0.0) }
        val sum = vals.values.sum()
        return if (sum <= 0) keys.associateWith { if (it == MarketKind.CRYPTO.name) 1.0 else 0.0 }
        else vals.mapValues { it.value / sum }
    }

    /**
     * انتقال کامل نقد و سرمایه یک بازار به بازار دیگر بدون ساختن دوباره حساب (سود قبلی حفظ می‌شود).
     * فقط وقتی انجام می‌شود که بازار مبدأ موقعیت باز نداشته باشد.
     * @return مبلغ منتقل‌شده (دلار) یا null اگر انجام نشد.
     */
    fun moveSleeve(from: MarketKind, to: MarketKind): Double? = synchronized(lock) {
        val a = ensure()
        if (from == to || a.positions.any { it.market == from }) return@synchronized null
        val cash = a.cashByMarket[from.name] ?: 0.0
        val next = a.copy(
            cashByMarket = a.cashByMarket + (from.name to 0.0) + (to.name to (a.cashByMarket[to.name] ?: 0.0) + cash),
            capitalByMarket = a.capitalByMarket + (from.name to 0.0) + (to.name to (a.capitalByMarket[to.name] ?: 0.0) + cash)
        )
        acc = next
        store.saveAccount(next)
        cash
    }

    /**
     * سرمایه مشترک: انتقال نقد آزاد از یک بازار به بازار دیگر (نقد و سرمایه پایه با هم جابه‌جا می‌شوند تا
     * سود/زیان هر بازار درست بماند). @return مبلغ منتقل‌شده.
     */
    fun lendCash(from: MarketKind, to: MarketKind, amount: Double): Double = synchronized(lock) {
        if (from == to || !amount.isFinite() || amount <= 0) return@synchronized 0.0
        val a = ensure()
        val avail = a.cashByMarket[from.name] ?: 0.0
        val amt = minOf(amount, avail)
        if (amt <= 1e-9) return@synchronized 0.0
        val next = a.copy(
            cashByMarket = a.cashByMarket + (from.name to avail - amt) + (to.name to (a.cashByMarket[to.name] ?: 0.0) + amt),
            capitalByMarket = a.capitalByMarket + (from.name to (a.capitalByMarket[from.name] ?: 0.0) - amt) +
                (to.name to (a.capitalByMarket[to.name] ?: 0.0) + amt)
        )
        acc = next
        store.saveAccount(next)
        amt
    }

    /**
     * تغییر تقسیم سرمایه بین بازارها **بدون** بستن خریدهای باز.
     * موقعیت‌های باز سر جایشان می‌مانند و فقط نقد آزاد طوری جابه‌جا می‌شود که سهم هر بازار (نقد + ارزش موقعیت‌ها)
     * تا حد ممکن به درصد جدید برسد. سود/زیان کل حساب تغییر نمی‌کند.
     * @param valueOf ارزش دلاری فعلی هر موقعیت.
     */
    fun reallocate(allocations: Map<String, Double>, valueOf: (Position) -> Double): AccountState = synchronized(lock) {
        val a = ensure()
        val alloc = normalizedAlloc(allocations)
        val keys = alloc.keys
        val posVal = keys.associateWith { k ->
            a.positions.filter { it.market.name == k }.sumOf { p -> valueOf(p).takeIf { it.isFinite() && it >= 0 } ?: p.cost() }
        }
        val totalCash = a.cashByMarket.values.filter { it.isFinite() }.sum()
        val equity = totalCash + posVal.values.sum()
        val desired = keys.associateWith { k -> maxOf(0.0, equity * (alloc[k] ?: 0.0) - (posVal[k] ?: 0.0)) }
        val sumDesired = desired.values.sum()
        val cash = keys.associateWith { k ->
            if (sumDesired > 0) totalCash * (desired[k] ?: 0.0) / sumDesired else totalCash * (alloc[k] ?: 0.0)
        }
        val next = a.copy(
            cashByMarket = cash,
            // مبنای سود/زیان هر بخش از همین لحظه (سود محقق‌شده قبلی هر بازار حفظ می‌شود)
            capitalByMarket = keys.associateWith { k -> (cash[k] ?: 0.0) + (posVal[k] ?: 0.0) }
        )
        acc = next
        store.saveAccount(next)
        next
    }

    /**
     * افزایش یا کاهش سرمایه **بدون** بستن خریدهای باز.
     * افزایش: مابه‌التفاوت به نسبت تقسیم سرمایه به نقد هر بازار اضافه می‌شود.
     * کاهش: فقط از نقد آزاد برداشته می‌شود؛ اگر نقد آزاد کافی نباشد انجام نمی‌شود (null).
     */
    fun adjustCapital(newCapitalUsd: Double, allocations: Map<String, Double>): AccountState? = synchronized(lock) {
        val a = ensure()
        if (!newCapitalUsd.isFinite() || newCapitalUsd <= 0) return@synchronized null
        val delta = newCapitalUsd - a.initialCapitalUsd
        val alloc = normalizedAlloc(allocations)
        val keys = alloc.keys + a.cashByMarket.keys
        val change: Map<String, Double> = if (delta >= 0) {
            keys.associateWith { k -> delta * (alloc[k] ?: 0.0) }
        } else {
            val totalCash = a.cashByMarket.values.filter { it > 0 }.sum()
            if (totalCash + 1e-9 < -delta) return@synchronized null
            keys.associateWith { k -> delta * (maxOf(0.0, a.cashByMarket[k] ?: 0.0) / totalCash) }
        }
        val next = a.copy(
            cashUsd = a.cashUsd + delta,
            initialCapitalUsd = newCapitalUsd,
            cashByMarket = keys.associateWith { k -> (a.cashByMarket[k] ?: 0.0) + (change[k] ?: 0.0) },
            capitalByMarket = keys.associateWith { k -> (a.capitalByMarket[k] ?: 0.0) + (change[k] ?: 0.0) }
        )
        acc = next
        store.saveAccount(next)
        next
    }

    /** نقد آزاد یک بازار. */
    fun cashOf(market: MarketKind): Double = synchronized(lock) { ensure().cashByMarket[market.name] ?: 0.0 }

    override fun account(): AccountState = synchronized(lock) { ensure() }

    /** حالت آینه: حساب دستگاه اصلی جایگزین حساب این دستگاه می‌شود. */
    fun replaceAccount(a: AccountState) = synchronized(lock) {
        acc = a
        store.saveAccount(a)
    }

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
        trailPct: Double = 0.0,
        fxRate: Double = 0.0,
        addPendingUsd: Double = 0.0,
        addTriggerUsd: Double = 0.0,
        addDeadline: Long = 0L,
        riskPct: Double = 0.0
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
            costUsd = usdAmount,
            fxRate = if (fxRate.isFinite() && fxRate > 0) fxRate else 0.0,
            addPendingUsd = if (addPendingUsd.isFinite() && addPendingUsd > 0) addPendingUsd else 0.0,
            addTriggerUsd = addTriggerUsd,
            addDeadline = addDeadline,
            riskPct = if (riskPct.isFinite() && riskPct > 0) riskPct else 0.0
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

    /** ثبت نرخ دلار ثابت برای موقعیت‌های ریالی قدیمی (قبل از نسخه ۱.۵.۲). */
    fun setFxRate(assetId: String, rate: Double): Boolean = synchronized(lock) {
        if (!rate.isFinite() || rate <= 0) return@synchronized false
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized false
        if (pos.fxRate > 0) return@synchronized false
        val next = a.copy(positions = a.positions.map { if (it.assetId == assetId) it.copy(fxRate = rate) else it })
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
            if (peak > p.peakUsd + 1e-12) { next = next.copy(peakUsd = peak, peakAt = System.currentTimeMillis()); changed = true }
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
    /** فقط بالا بردن حد ضرر (مثلاً بی‌ضرر کردن). true اگر تغییر کرد. */
    fun raiseStop(assetId: String, stopUsd: Double): Boolean = synchronized(lock) {
        if (!stopUsd.isFinite() || stopUsd <= 0) return@synchronized false
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized false
        if (stopUsd <= pos.stopLossUsd * 1.0000001) return@synchronized false
        val next = a.copy(positions = a.positions.map { if (it.assetId == assetId) it.copy(stopLossUsd = stopUsd) else it })
        acc = next
        store.saveAccount(next)
        true
    }

    /** تمدید در حد سود: نمی‌فروشد؛ حد ضرر (فقط بالا) و حد سود تازه. */
    fun rollTarget(assetId: String, stopUsd: Double, takeProfitUsd: Double): Boolean = synchronized(lock) {
        if (!stopUsd.isFinite() || !takeProfitUsd.isFinite() || takeProfitUsd <= 0) return@synchronized false
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized false
        if (takeProfitUsd <= pos.takeProfitUsd) return@synchronized false
        val updated = pos.copy(stopLossUsd = maxOf(pos.stopLossUsd, stopUsd), takeProfitUsd = takeProfitUsd, rolls = pos.rolls + 1)
        val next = a.copy(positions = a.positions.map { if (it.assetId == assetId) updated else it })
        acc = next
        store.saveAccount(next)
        true
    }

    /**
     * ثبت (یا پاک کردن با at=0) لحظه‌ای که قیمت زیر حد ضرر رفت ولی فروش ممکن نبود؛
     * فقط برای توضیح در گزارش و ژورنال است و روی منطق فروش اثری ندارد.
     */
    fun markStopHit(assetId: String, at: Long, marketOpen: Boolean): Boolean = synchronized(lock) {
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized false
        if (at > 0 && pos.stopHitAt > 0) return@synchronized false
        if (at == 0L && pos.stopHitAt == 0L) return@synchronized false
        val updated = pos.copy(stopHitAt = at, stopHitOpen = at > 0 && marketOpen)
        val next = a.copy(positions = a.positions.map { if (it.assetId == assetId) updated else it })
        acc = next
        store.saveAccount(next)
        true
    }

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

    /**
     * پله دوم خرید پله‌ای: به موقعیت موجود اضافه می‌کند (میانگین قیمت و بهای تمام‌شده به‌روز می‌شود؛
     * حد ضرر و حد سود همان قبلی می‌ماند) و پله در انتظار پاک می‌شود.
     */
    fun addToPosition(assetId: String, usdPrice: Double, usdAmount: Double, feePct: Double, reason: String): Trade? = synchronized(lock) {
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized null
        if (!usdPrice.isFinite() || usdPrice <= 0 || !usdAmount.isFinite() || usdAmount <= 0) return@synchronized null
        val key = pos.market.name
        val sleeve = a.cashByMarket[key] ?: 0.0
        if (usdAmount > sleeve + 1e-9 || usdAmount > a.cashUsd + 1e-9) return@synchronized null
        val fee = usdAmount * feePct
        val addQty = (usdAmount - fee) / usdPrice
        if (addQty <= 0) return@synchronized null
        val now = System.currentTimeMillis()
        val newQty = pos.qty + addQty
        val updated = pos.copy(
            qty = newQty,
            avgBuyUsd = (pos.qty * pos.avgBuyUsd + addQty * usdPrice) / newQty,
            costUsd = pos.cost() + usdAmount,
            peakUsd = maxOf(pos.peakUsd, usdPrice),
            addPendingUsd = 0.0,
            addTriggerUsd = 0.0,
            addDeadline = 0L,
            addedAt = now
        )
        val trade = Trade(
            id = shortId(), ts = now, assetId = assetId, symbol = pos.symbol, side = "BUY",
            qty = addQty, priceUsd = usdPrice, usdValue = usdAmount, feeUsd = fee, reason = reason, mode = mode
        )
        val next = a.copy(
            cashUsd = a.cashUsd - usdAmount,
            cashByMarket = a.cashByMarket + (key to (sleeve - usdAmount).coerceAtLeast(0.0)),
            positions = a.positions.map { if (it.assetId == assetId) updated else it },
            trades = listOf(trade) + a.trades
        )
        acc = next
        store.saveAccount(next)
        trade
    }

    /** لغو پله دوم در انتظار (مهلت تمام شد یا نقد کافی نبود). */
    fun clearPendingAdd(assetId: String): Boolean = synchronized(lock) {
        val a = ensure()
        val pos = a.positions.firstOrNull { it.assetId == assetId } ?: return@synchronized false
        if (pos.addPendingUsd <= 0) return@synchronized false
        val next = a.copy(positions = a.positions.map {
            if (it.assetId == assetId) it.copy(addPendingUsd = 0.0, addTriggerUsd = 0.0, addDeadline = 0L) else it
        })
        acc = next
        store.saveAccount(next)
        true
    }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
}
