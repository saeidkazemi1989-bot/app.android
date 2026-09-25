package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.AccountState
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.Position
import com.saeidkazemi.trader.data.model.Trade
import java.util.UUID

/**
 * کارگزار دمو (معامله آزمایشی): لجر داخلی اپ با دلار، بدون هیچ ریسک واقعی.
 * همه حسابداری بر مبنای دلار انجام می‌شود؛ دارایی‌های ریالی هنگام معامله با نرخ روز تبدیل می‌شوند.
 */
class PaperBroker(private val store: JsonStore) : Broker {

    override val mode: String = "PAPER"

    private val lock = Any()
    private var acc: AccountState? = null

    private fun ensure(): AccountState {
        val a = acc
        if (a != null) return a
        val loaded = store.loadAccount(store.loadSettings().capitalUsd)
        acc = loaded
        return loaded
    }

    override fun account(): AccountState = synchronized(lock) { ensure() }

    fun reset(capitalUsd: Double): AccountState = synchronized(lock) {
        val fresh = AccountState(cashUsd = capitalUsd, initialCapitalUsd = capitalUsd)
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
        reason: String
    ): Trade? = synchronized(lock) {
        val a = ensure()
        if (!usdPrice.isFinite() || usdPrice <= 0 || usdAmount <= 0) return@synchronized null
        val fee = usdAmount * feePct
        val net = usdAmount - fee
        if (net <= 0 || net > a.cashUsd + 1e-9) return@synchronized null
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
            takeProfitUsd = takeProfitUsd
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
            val pnl = net - pos.qty * pos.avgBuyUsd
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
            val next = a.copy(
                cashUsd = a.cashUsd + net,
                realizedPnlUsd = a.realizedPnlUsd + pnl,
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
            adjDay = day
        )
        val next = a.copy(positions = a.positions.map { if (it.assetId == assetId) adjusted else it })
        acc = next
        store.saveAccount(next)
        true
    }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
}
