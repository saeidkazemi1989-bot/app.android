package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.analysis.StrategyEngine
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.Action
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.CycleReport
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.OrderResult
import com.saeidkazemi.trader.data.model.Signal
import com.saeidkazemi.trader.data.remote.MarketDataService
import com.saeidkazemi.trader.data.remote.RefreshResult
import com.saeidkazemi.trader.util.Format
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * موتور معاملات: هر دور (سیکل) بازار را به‌روز می‌کند، سیگنال‌ها را می‌سازد،
 * حد ضرر/حد سود و ضعف سیگنال را روی موقعیت‌های باز اعمال می‌کند و در حالت خودکار،
 * بهترین فرصت‌ها را طبق بودجه و ریسک تعریف‌شده می‌خرد.
 */
class TradeEngine(
    private val market: MarketDataService,
    private val strategy: StrategyEngine,
    private val riskManager: RiskManager,
    private val broker: PaperBroker,
    private val nobitex: NobitexClient,
    private val store: JsonStore
) {

    private val mutex = Mutex()

    suspend fun runCycle(trigger: String): CycleReport = mutex.withLock {
        val settings = store.loadSettings()
        val plan = riskManager.plan(settings.riskLevel)
        val buys = mutableListOf<String>()
        val sells = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val result = try {
            market.refresh(settings)
        } catch (e: Exception) {
            notes.add("خطا در به‌روزرسانی بازار: " + (e.message ?: ""))
            RefreshResult(market.cachedAssets(), emptyList())
        }
        notes.addAll(result.notes)
        val assets = result.assets
        val usdIrr = market.usdIrr(settings)

        // ۱) مدیریت ریسک و فروش موقعیت‌های باز
        for (pos in broker.account().positions) {
            val asset = assets.firstOrNull { it.id == pos.assetId } ?: continue
            val curUsd = market.usdPriceOf(asset, settings)
            if (!curUsd.isFinite() || curUsd <= 0) continue
            var reason: String? = null
            if (curUsd <= pos.stopLossUsd) {
                reason = "فعال شدن حد ضرر"
            } else if (curUsd >= pos.takeProfitUsd) {
                reason = "فعال شدن حد سود"
            } else {
                val sig = strategy.analyze(asset, market.historyFor(asset), settings)
                if (sig != null && sig.score <= settings.sellThreshold) {
                    reason = "ضعیف شدن سیگنال (امتیاز " + sig.score + ")"
                }
            }
            if (reason != null) {
                val trade = broker.sell(pos.assetId, curUsd, settings.feePct, reason)
                if (trade != null) {
                    sells.add(pos.symbol)
                    maybeRealSell(settings, asset, pos.qty, notes)
                }
            }
        }

        // ۲) تحلیل و ساخت سیگنال برای همه دارایی‌های دارای داده کافی
        val signals = mutableListOf<Signal>()
        for (asset in assets) {
            if (asset.isDisplayOnly) continue
            val hist = try {
                market.historyFor(asset)
            } catch (e: Exception) {
                emptyList()
            }
            val sig = strategy.analyze(asset, hist, settings) ?: continue
            signals.add(sig)
        }
        signals.sortByDescending { it.score }

        // ۳) خرید خودکار بهترین فرصت‌ها
        if (settings.autoTrade) {
            val priceMap = assets.associate { it.id to market.usdPriceOf(it, settings) }
            val account = broker.account()
            val equity = account.cashUsd + account.positions.sumOf { p ->
                (priceMap[p.assetId] ?: p.avgBuyUsd) * p.qty
            }
            val reserve = equity * plan.cashReservePct
            var cash = account.cashUsd
            for (sig in signals) {
                if (sig.action != Action.BUY) continue
                if (broker.account().positions.size >= plan.maxPositions) break
                if (broker.account().positions.any { it.assetId == sig.assetId }) continue
                val asset = assets.firstOrNull { it.id == sig.assetId } ?: continue
                val usdPrice = priceMap[asset.id] ?: continue
                if (!usdPrice.isFinite() || usdPrice <= 0) continue
                val budget = equity * plan.positionPct
                val available = cash - reserve
                val amount = minOf(budget, available)
                if (amount < plan.minTradeUsd) break
                val reason = "خرید خودکار (امتیاز " + sig.score + ")"
                val trade = broker.buy(
                    asset = asset,
                    usdPrice = usdPrice,
                    usdAmount = amount,
                    feePct = settings.feePct,
                    stopLossUsd = usdPrice * (1 - plan.stopPct),
                    takeProfitUsd = usdPrice * (1 + plan.tpPct),
                    reason = reason
                )
                if (trade != null) {
                    cash -= amount
                    buys.add(asset.symbol)
                    maybeRealBuy(settings, asset, amount, usdIrr, notes)
                }
            }
            if (signals.isEmpty()) notes.add("دارایی با داده کافی برای تحلیل پیدا نشد.")
        }

        store.saveAccount(broker.account())
        CycleReport(
            ts = System.currentTimeMillis(),
            trigger = trigger,
            buys = buys,
            sells = sells,
            notes = notes.distinct(),
            auto = settings.autoTrade
        )
    }

    /** خرید دستی از صفحه جزئیات. */
    suspend fun manualBuy(assetId: String, usdAmount: Double): String {
        val settings = store.loadSettings()
        val plan = riskManager.plan(settings.riskLevel)
        val asset = market.cachedAssets().firstOrNull { it.id == assetId }
            ?: return "دارایی پیدا نشد؛ ابتدا بازار را به‌روزرسانی کنید."
        if (asset.isDisplayOnly) return "این دارایی فقط نمایشی است و معامله نمی‌شود."
        val usdPrice = market.usdPriceOf(asset, settings)
        if (!usdPrice.isFinite() || usdPrice <= 0) return "قیمت معتبر در دسترس نیست."
        val account = broker.account()
        if (account.positions.any { it.assetId == assetId }) return "این دارایی را از قبل در پرتفوی دارید."
        if (usdAmount < plan.minTradeUsd) return "حداقل مبلغ معامله " + plan.minTradeUsd.toInt() + " دلار است."
        if (usdAmount > account.cashUsd) return "موجودی نقد کافی نیست."
        val trade = broker.buy(
            asset = asset,
            usdPrice = usdPrice,
            usdAmount = usdAmount,
            feePct = settings.feePct,
            stopLossUsd = usdPrice * (1 - plan.stopPct),
            takeProfitUsd = usdPrice * (1 + plan.tpPct),
            reason = "خرید دستی"
        ) ?: return "خرید انجام نشد."
        if (settings.realTrading) {
            val notes = mutableListOf<String>()
            maybeRealBuy(settings, asset, usdAmount, market.usdIrr(settings), notes)
            if (notes.isNotEmpty()) return "خرید در دفتر ثبت شد. " + notes.joinToString(" ")
        }
        return "خرید " + asset.symbol + " به مبلغ " + Format.num(usdAmount) + " دلار ثبت شد."
    }

    /** فروش دستی کل یک موقعیت. */
    suspend fun manualSell(assetId: String): String {
        val settings = store.loadSettings()
        val pos = broker.account().positions.firstOrNull { it.assetId == assetId }
            ?: return "موقعیتی برای فروش ندارید."
        val asset = market.cachedAssets().firstOrNull { it.id == assetId }
        val usdPrice = if (asset != null) market.usdPriceOf(asset, settings) else pos.avgBuyUsd
        val trade = broker.sell(assetId, usdPrice, settings.feePct, "فروش دستی")
            ?: return "فروش انجام نشد."
        if (settings.realTrading && asset != null) {
            val notes = mutableListOf<String>()
            maybeRealSell(settings, asset, pos.qty, notes)
            if (notes.isNotEmpty()) return "فروش در دفتر ثبت شد. " + notes.joinToString(" ")
        }
        return "فروش " + pos.symbol + " با قیمت " + Format.num(usdPrice) + " دلار ثبت شد."
    }

    fun resetPaperAccount(capitalUsd: Double) {
        broker.reset(capitalUsd)
    }

    // ---- بخش معامله واقعی (آزمایشی، فقط نوبیتکس) ----

    private suspend fun maybeRealBuy(
        settings: com.saeidkazemi.trader.data.model.AppSettings,
        asset: Asset,
        usdAmount: Double,
        usdIrr: Double,
        notes: MutableList<String>
    ) {
        if (!settings.realTrading) return
        val sym = asset.nobitexSymbol
        if (asset.market != MarketKind.CRYPTO || sym == null) {
            notes.add("معامله واقعی فقط برای ارزهای متصل به نوبیتکس فعال است؛ " + asset.symbol + " فقط در دفتر دمو ثبت شد.")
            return
        }
        val lastIrt = nobitex.lastPriceIrt(sym)
        if (lastIrt == null || !lastIrt.isFinite() || lastIrt <= 0) {
            notes.add("قیمت تومانی " + asset.symbol + " از نوبیتکس گرفته نشد؛ سفارش واقعی ارسال نشد.")
            return
        }
        val baseQty = usdAmount * usdIrr / lastIrt
        when (val r = nobitex.placeOrder(settings, sym, "buy", baseQty)) {
            is OrderResult.Success -> notes.add("سفارش واقعی خرید " + asset.symbol + " در نوبیتکس ثبت شد.")
            is OrderResult.Failure -> notes.add("خطای سفارش واقعی " + asset.symbol + ": " + r.message)
            OrderResult.NotConfigured -> notes.add("توکن نوبیتکس تنظیم نشده؛ معامله فقط در حالت دمو ثبت شد.")
        }
    }

    private suspend fun maybeRealSell(
        settings: com.saeidkazemi.trader.data.model.AppSettings,
        asset: Asset,
        qty: Double,
        notes: MutableList<String>
    ) {
        if (!settings.realTrading) return
        val sym = asset.nobitexSymbol
        if (asset.market != MarketKind.CRYPTO || sym == null) {
            notes.add("فروش واقعی فقط برای ارزهای متصل به نوبیتکس فعال است؛ " + asset.symbol + " فقط در دفتر دمو ثبت شد.")
            return
        }
        when (val r = nobitex.placeOrder(settings, sym, "sell", qty)) {
            is OrderResult.Success -> notes.add("سفارش واقعی فروش " + asset.symbol + " در نوبیتکس ثبت شد.")
            is OrderResult.Failure -> notes.add("خطای سفارش واقعی " + asset.symbol + ": " + r.message)
            OrderResult.NotConfigured -> notes.add("توکن نوبیتکس تنظیم نشده؛ معامله فقط در حالت دمو ثبت شد.")
        }
    }
}
