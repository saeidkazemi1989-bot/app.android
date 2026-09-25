package com.saeidkazemi.trader.core

import android.content.Context
import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.analysis.StrategyEngine
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.remote.MarketDataService
import com.saeidkazemi.trader.trading.NobitexClient
import com.saeidkazemi.trader.trading.PaperBroker
import com.saeidkazemi.trader.trading.TradeEngine

/** تزریق وابستگی دستی: همه اجزای اصلی اپ یک‌بار ساخته و بین صفحه‌ها و سرویس مشترک می‌شوند. */
class AppContainer(context: Context) {

    val store = JsonStore(context)
    val marketDataService = MarketDataService()
    val strategyEngine = StrategyEngine()
    val riskManager = RiskManager()
    val broker = PaperBroker(store)
    val nobitexClient = NobitexClient(store)
    val tradeEngine = TradeEngine(
        market = marketDataService,
        strategy = strategyEngine,
        riskManager = riskManager,
        broker = broker,
        nobitex = nobitexClient,
        store = store
    )

    companion object {
        const val CHANNEL_SERVICE = "trader_service"
        const val CHANNEL_TRADES = "trader_trades"
    }
}
