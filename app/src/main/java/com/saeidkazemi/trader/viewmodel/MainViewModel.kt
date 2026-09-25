package com.saeidkazemi.trader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saeidkazemi.trader.TraderApp
import com.saeidkazemi.trader.data.model.AppSettings
import com.saeidkazemi.trader.data.model.Asset
import com.saeidkazemi.trader.data.model.Signal
import com.saeidkazemi.trader.service.TradingService
import com.saeidkazemi.trader.ui.AssetDetail
import com.saeidkazemi.trader.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as TraderApp).container
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings()
            val account = container.broker.account()
            _state.update { it.copy(settings = settings, account = account, loading = true) }
            // معامله‌گر خودکار به‌صورت پیش‌فرض روشن است؛ سرویس با شروع اپ راه می‌افتد.
            if (settings.autoTrade) {
                TradingService.start(getApplication())
            }
            runCycleInternal("initial")
        }
    }

    /** یک دور کامل: به‌روزرسانی بازار، تحلیل، مدیریت ریسک و (در حالت خودکار) خرید و فروش. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(refreshing = true) }
            runCycleInternal("manual")
        }
    }

    private suspend fun runCycleInternal(trigger: String) {
        try {
            val report = container.tradeEngine.runCycle(trigger)
            val settings = container.store.loadSettings()
            val assets = container.marketDataService.cachedAssets()
            val priceMap = HashMap<String, Double>()
            for (a in assets) priceMap[a.id] = container.marketDataService.usdPriceOf(a, settings)
            val signals = computeSignals(assets, settings)
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = null,
                    notes = report.notes,
                    assets = assets,
                    signals = signals,
                    priceMap = priceMap,
                    account = container.broker.account(),
                    settings = settings,
                    usdIrr = container.marketDataService.usdIrr(settings),
                    rateIsFallback = container.marketDataService.rateIsFallback(),
                    lastCycle = report
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = "خطا در اجرای موتور: " + (e.message ?: "")
                )
            }
        }
    }

    private suspend fun computeSignals(assets: List<Asset>, settings: AppSettings): List<Signal> {
        val out = mutableListOf<Signal>()
        for (a in assets) {
            if (a.isDisplayOnly) continue
            val h = try {
                container.marketDataService.historyFor(a)
            } catch (e: Exception) {
                emptyList()
            }
            val s = container.strategyEngine.analyze(a, h, settings) ?: continue
            out.add(s)
        }
        out.sortByDescending { it.score }
        return out
    }

    // ---- تنظیمات ----

    fun toggleAutoTrade(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(autoTrade = on)
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings) }
            if (on) TradingService.start(getApplication()) else TradingService.stop(getApplication())
            toast(if (on) "معامله‌گر خودکار روشن شد و هر ۶۰ ثانیه بازار را بررسی می‌کند." else "معامله‌گر خودکار خاموش شد.")
        }
    }

    fun setRiskLevel(level: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(riskLevel = level)
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings) }
        }
    }

    fun setFeePct(pct: Double) {
        if (!pct.isFinite()) return
        viewModelScope.launch(Dispatchers.IO) {
            val clamped = maxOf(0.0, minOf(0.02, pct))
            val settings = container.store.loadSettings().copy(feePct = clamped)
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings) }
            toast("کارمزد ذخیره شد.")
        }
    }

    fun setCapitalAndReset(amountUsd: Double) {
        if (!amountUsd.isFinite() || amountUsd <= 0) {
            toast("سرمایه معتبر وارد کنید.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(capitalUsd = amountUsd)
            container.store.saveSettings(settings)
            container.broker.reset(amountUsd)
            _state.update { it.copy(settings = settings, account = container.broker.account()) }
            toast("حساب دمو با سرمایه جدید از نو ساخته شد.")
        }
    }

    fun toggleRealTrading(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(realTrading = on)
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings) }
            if (on) toast("هشدار: معامله واقعی فعال شد. فقط ارزهای متصل به نوبیتکس با پول واقعی معامله می‌شوند.")
        }
    }

    fun saveNobitexToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.store.loadSettings().copy(nobitexToken = token.trim())
            container.store.saveSettings(settings)
            _state.update { it.copy(settings = settings) }
            toast("توکن نوبیتکس به‌صورت محلی روی گوشی ذخیره شد.")
        }
    }

    // ---- معاملات دستی ----

    fun manualBuy(assetId: String, usdAmountStr: String) {
        val amount = usdAmountStr.toDoubleOrNull()
        if (amount == null || !amount.isFinite() || amount <= 0) {
            toast("مبلغ معتبر (دلار) وارد کنید.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = container.tradeEngine.manualBuy(assetId, amount)
            syncAccount()
            toast(msg)
        }
    }

    fun manualSell(assetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = container.tradeEngine.manualSell(assetId)
            syncAccount()
            toast(msg)
        }
    }

    private fun syncAccount() {
        _state.update { it.copy(account = container.broker.account()) }
    }

    fun openAsset(assetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val asset = container.marketDataService.cachedAssets().firstOrNull { it.id == assetId }
                ?: return@launch
            val settings = container.store.loadSettings()
            val history = try {
                container.marketDataService.historyFor(asset)
            } catch (e: Exception) {
                emptyList()
            }
            val signal = container.strategyEngine.analyze(asset, history, settings)
            val held = container.broker.account().positions.any { it.assetId == assetId }
            val usdPrice = container.marketDataService.usdPriceOf(asset, settings)
            _state.update {
                it.copy(detail = AssetDetail(asset, history, signal, usdPrice, held))
            }
        }
    }

    fun toast(message: String) {
        _state.update { it.copy(toastMessage = message) }
    }

    fun consumeToast() {
        _state.update { it.copy(toastMessage = null) }
    }
}
