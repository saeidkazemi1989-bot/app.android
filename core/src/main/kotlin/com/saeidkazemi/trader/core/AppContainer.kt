package com.saeidkazemi.trader.core

import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.analysis.StrategyEngine
import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.remote.MarketDataService
import com.saeidkazemi.trader.news.NewsService
import com.saeidkazemi.trader.trading.NobitexClient
import com.saeidkazemi.trader.trading.PaperBroker
import com.saeidkazemi.trader.trading.TradeEngine
import java.io.File

/**
 * تزریق وابستگی دستی: همه اجزای اصلی یک‌بار ساخته و بین صفحه‌ها و سرویس مشترک می‌شوند.
 * این کلاس مستقل از پلتفرم است و در اندروید و ویندوز یکسان استفاده می‌شود.
 *
 * @param dataDir پوشه ذخیره تنظیمات و حساب (اندروید: filesDir، ویندوز: %APPDATA%\MoameleYar)
 */
class AppContainer(dataDir: File) {

    val store = JsonStore(dataDir)
    val marketDataService = MarketDataService()
    val strategyEngine = StrategyEngine()
    val riskManager = RiskManager()
    val broker = PaperBroker(store)
    val nobitexClient = NobitexClient(store)
    val newsService = NewsService()
    val tradeEngine = TradeEngine(
        market = marketDataService,
        strategy = strategyEngine,
        riskManager = riskManager,
        broker = broker,
        nobitex = nobitexClient,
        store = store,
        news = newsService
    )

    companion object {
        const val CHANNEL_SERVICE = "trader_service"
        const val CHANNEL_TRADES = "trader_trades"
        const val CYCLE_MS = 60_000L
    }
}
