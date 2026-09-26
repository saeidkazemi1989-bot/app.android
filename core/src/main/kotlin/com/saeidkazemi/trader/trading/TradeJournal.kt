package com.saeidkazemi.trader.trading

import com.saeidkazemi.trader.data.local.JsonStore
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.Trade

/**
 * ژورنال معاملات: برای هر خرید یک ردیف با همه دلایل و داده‌های لحظه تصمیم ساخته می‌شود و
 * هنگام فروش، دلیل خروج و نتیجه به همان ردیف اضافه می‌شود. در فایل journal.json ذخیره می‌شود.
 */
class TradeJournal(private val store: JsonStore?) {

    companion object {
        const val MAX_ENTRIES = 1000
    }

    private val lock = Any()
    private var entries: MutableList<JournalEntry>? = null

    private fun ensure(): MutableList<JournalEntry> {
        entries?.let { return it }
        val loaded = try {
            store?.loadJournal()?.toMutableList()
        } catch (_: Exception) {
            null
        } ?: mutableListOf()
        entries = loaded
        return loaded
    }

    private fun save(list: List<JournalEntry>) {
        try {
            store?.saveJournal(list)
        } catch (_: Exception) {
        }
    }

    /** همه ردیف‌ها، جدیدترین اول. */
    fun all(): List<JournalEntry> = synchronized(lock) { ensure().sortedByDescending { it.openedAt } }

    fun isEmpty(): Boolean = synchronized(lock) { ensure().isEmpty() }

    fun open(entry: JournalEntry) = synchronized(lock) {
        val list = ensure()
        // اگر به هر دلیلی ردیف باز قبلی برای همین دارایی مانده، ناقص بسته می‌شود
        for (i in list.indices) {
            val e = list[i]
            if (e.assetId == entry.assetId && e.isOpen) list[i] = e.copy(closedAt = entry.openedAt, exitReason = "نامشخص (ردیف جدید)")
        }
        list.add(entry)
        if (list.size > MAX_ENTRIES) {
            val drop = list.filter { !it.isOpen }.sortedBy { it.openedAt }.take(list.size - MAX_ENTRIES).toSet()
            list.removeAll(drop)
        }
        save(list)
    }

    /** بستن ردیف باز یک دارایی با اطلاعات خروج. */
    fun close(assetId: String, change: (JournalEntry) -> JournalEntry): Boolean = synchronized(lock) {
        val list = ensure()
        val i = list.indexOfLast { it.assetId == assetId && it.isOpen }
        if (i < 0) return@synchronized false
        list[i] = change(list[i])
        save(list)
        true
    }

    fun clear() = synchronized(lock) {
        val list = ensure()
        list.clear()
        save(list)
    }

    /**
     * ساخت ردیف‌های ژورنال از تاریخچه معاملات قبلی (قبل از وجود ژورنال): هر خرید با فروش بعدی همان دارایی
     * جفت می‌شود. دلایل ورود در دسترس نیست ولی نتیجه (سود/زیان، دلیل فروش) در آمار حساب می‌شود.
     */
    fun backfill(trades: List<Trade>, marketOf: (String) -> MarketKind) = synchronized(lock) {
        val list = ensure()
        if (list.isNotEmpty() || trades.isEmpty()) return@synchronized
        val openBuys = HashMap<String, Trade>()
        for (t in trades.sortedBy { it.ts }) {
            if (t.side == "BUY") {
                openBuys[t.assetId] = t
            } else if (t.side == "SELL") {
                val b = openBuys.remove(t.assetId) ?: continue
                val proceeds = t.usdValue - t.feeUsd
                val pnl = proceeds - b.usdValue
                list.add(entryFromBuy(b, marketOf(b.assetId)).copy(
                    closedAt = t.ts,
                    exitUsd = t.priceUsd,
                    exitReason = t.reason,
                    proceedsUsd = proceeds,
                    sellFeeUsd = t.feeUsd,
                    pnlUsd = pnl,
                    pnlPct = if (b.usdValue > 0) pnl / b.usdValue * 100 else null
                ))
            }
        }
        for (b in openBuys.values) list.add(entryFromBuy(b, marketOf(b.assetId)))
        save(list)
    }

    private fun entryFromBuy(b: Trade, m: MarketKind) = JournalEntry(
        id = b.id,
        assetId = b.assetId,
        symbol = b.symbol,
        name = b.symbol,
        market = m,
        auto = b.reason.contains("خودکار"),
        mode = b.mode,
        openedAt = b.ts,
        entryUsd = b.priceUsd,
        entryNative = b.priceUsd,
        nativeCurrency = "USD",
        usdIrr = 0.0,
        amountUsd = b.usdValue,
        buyFeeUsd = b.feeUsd,
        qty = b.qty,
        entryReason = b.reason,
        backfilled = true
    )
}
