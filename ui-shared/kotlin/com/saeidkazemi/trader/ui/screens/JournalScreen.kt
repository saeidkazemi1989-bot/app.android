package com.saeidkazemi.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.analysis.PerfStats
import com.saeidkazemi.trader.data.model.JournalEntry
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.data.model.PricePoint
import com.saeidkazemi.trader.data.remote.DataSources
import com.saeidkazemi.trader.journal.JournalExport
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.ui.components.InfoCard
import com.saeidkazemi.trader.ui.components.PriceChart
import com.saeidkazemi.trader.ui.components.SectionTitle
import com.saeidkazemi.trader.ui.components.pnlColor
import com.saeidkazemi.trader.util.Format

/**
 * پنل سودآوری (نرخ برد، ضریب سود، امید ریاضی، …)، منابع اطلاعات و ژورنال کامل معاملات
 * با همه دلایل و داده‌های لحظه خرید و فروش.
 */
@Composable
fun JournalScreen(
    state: UiState,
    onToast: (String) -> Unit,
    onOpenAsset: (String) -> Unit
) {
    val perf = state.perf
    val s = perf.all
    val clipboard = LocalClipboardManager.current
    var filter by remember { mutableStateOf("ALL") }
    val entries = state.journal.filter {
        when (filter) {
            "OPEN" -> it.isOpen
            "WIN" -> !it.isOpen && it.pnlUsd != null && it.isWin
            "LOSS" -> !it.isOpen && it.pnlUsd != null && !it.isWin
            else -> true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionTitle("پنل سودآوری") }
        item {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard(
                        title = "نرخ برد (win rate)",
                        value = JournalExport.pct(s.winRate),
                        sub = s.breakEvenWinRate?.let { "سربه‌سر در " + JournalExport.pct(it) } ?: (s.wins.toString() + " برد از " + s.closed),
                        valueColor = winRateColor(s),
                        modifier = Modifier.weight(1f)
                    )
                    InfoCard(
                        title = "سود/زیان تحقق‌یافته",
                        value = "$" + Format.money(s.totalPnlUsd),
                        sub = s.closed.toString() + " معامله بسته‌شده",
                        valueColor = pnlColor(s.totalPnlUsd),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard(
                        title = "ضریب سود",
                        value = JournalExport.pf(s.profitFactor),
                        sub = "بالای ۱ یعنی سودده",
                        valueColor = s.profitFactor?.let { pnlColor(it - 1) } ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    InfoCard(
                        title = "امید ریاضی هر معامله",
                        value = s.expectancyUsd?.let { "$" + Format.money(it) } ?: "—",
                        sub = Format.pct(s.expectancyPct),
                        valueColor = pnlColor(s.expectancyUsd ?: 0.0),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                JournalExport.statsLines(s).drop(1).forEach {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
                if (perf.curve.size >= 2) {
                    Text("منحنی سود تحقق‌یافته", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    PriceChart(
                        points = listOf(PricePoint(perf.curve.first().first - 1, 0.0)) + perf.curve.map { PricePoint(it.first, it.second) },
                        height = 110.dp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (perf.last10.closed > 0 && s.closed > 10) {
                    Text(
                        "۱۰ معامله آخر: نرخ برد " + JournalExport.pct(perf.last10.winRate) + " • $" + Format.money(perf.last10.totalPnlUsd),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor(perf.last10.totalPnlUsd),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Text(
                    "خودکار: " + perf.auto.closed + " معامله، نرخ برد " + JournalExport.pct(perf.auto.winRate) + ", $" + Format.money(perf.auto.totalPnlUsd) +
                        " • دستی: " + perf.manual.closed + " معامله، $" + Format.money(perf.manual.totalPnlUsd),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // هر بازار + محافظ نرخ برد
        item {
            Card {
                Text("هر بازار و محافظ نرخ برد", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX).forEach { m ->
                    val ms = perf.byMarket[m] ?: PerfStats()
                    val g = perf.guards.firstOrNull { it.market == m }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(m.faTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            ms.closed.toString() + " معامله • برد " + JournalExport.pct(ms.winRate) + " • $" + Format.money(ms.totalPnlUsd),
                            fontSize = 12.sp,
                            color = pnlColor(ms.totalPnlUsd)
                        )
                    }
                    if (g != null) {
                        Text(
                            (if (g.active) "⚠ " else "✓ ") + g.text,
                            fontSize = 10.sp,
                            color = if (g.active) Color(0xFFF7B731) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "معامله‌گر با هر نرخ بردی ادامه می‌دهد (نرخ برد به‌تنهایی معیار سود نیست؛ مهم ضریب سود و امید ریاضی است). " +
                        "فقط وقتی نرخ برد " + state.settings.guardWindow + " معامله آخر یک بازار زیر " +
                        Format.num(state.settings.minWinRatePct, 0) + "٪ و جمعشان زیان‌ده باشد، آن بازار محتاط می‌شود. تنظیم در «تنظیمات».",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        if (perf.byExit.isNotEmpty()) {
            item {
                Card {
                    Text("نتیجه بر اساس دلیل فروش", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    perf.byExit.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(r.category, fontSize = 12.sp)
                            Text(
                                r.count.toString() + " بار • برد " + r.wins + " • $" + Format.money(r.pnlUsd),
                                fontSize = 12.sp,
                                color = pnlColor(r.pnlUsd)
                            )
                        }
                    }
                }
            }
        }

        // منابع اطلاعات
        item {
            var open by remember { mutableStateOf(false) }
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = !open },
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("اطلاعات از کجا جمع‌آوری می‌شود؟", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(if (open) "▲" else "▼", fontSize = 12.sp)
                }
                val live = { m: MarketKind ->
                    val list = state.assets.filter { it.market == m }
                    if (list.isEmpty()) "بدون داده" else {
                        val sim = list.count { it.isSimulated }
                        if (sim == 0) "زنده (" + list.size + " دارایی)" else if (sim == list.size) "⚠ شبیه‌سازی‌شده (منبع در دسترس نیست)" else "زنده " + (list.size - sim) + " • شبیه‌سازی " + sim
                    }
                }
                Text(
                    "وضعیت الان — ارز دیجیتال: " + live(MarketKind.CRYPTO) + " • بورس: " + live(MarketKind.IR_STOCK) +
                        " • ارز خارجی: " + live(MarketKind.FX) + " • نرخ دلار: " +
                        (if (state.rateIsFallback) "⚠ پشتیبان تنظیمات" else "زنده") + " (" + Format.num(state.usdIrr, 0) + " ریال)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                val failed = state.newsDigests.values.flatMap { it.sourcesFailed }.distinct()
                if (failed.isNotEmpty()) {
                    Text(
                        "منابع خبری در دسترس نبود: " + failed.joinToString("، "),
                        fontSize = 11.sp,
                        color = Color(0xFFF7B731),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (open) {
                    listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX, MarketKind.METAL).forEach { m ->
                        Text(m.faTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        DataSources.forMarket(m).forEach {
                            Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Text(
                        "همه منابع رایگان و عمومی‌اند و مستقیم از گوشی/کامپیوتر شما خوانده می‌شوند (سرور واسطه‌ای وجود ندارد). " +
                            "اگر منبعی در دسترس نباشد، داده با برچسب «شبیه‌سازی» نمایش داده می‌شود و روی آن خرید خودکار انجام نمی‌شود.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // ژورنال
        item { SectionTitle("ژورنال معاملات (" + state.journal.size + ")") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(JournalExport.text(state.journal, perf)))
                        onToast("متن کامل ژورنال کپی شد.")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("کپی متن ژورنال", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(JournalExport.csv(state.journal)))
                        onToast("ژورنال به‌صورت CSV کپی شد (قابل چسباندن در اکسل/گوگل‌شیت).")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("کپی CSV", fontSize = 12.sp) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "همه", "OPEN" to "باز", "WIN" to "برد", "LOSS" to "باخت").forEach { (k, t) ->
                    val sel = filter == k
                    Text(
                        t,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { filter = k }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    if (state.journal.isEmpty()) "هنوز معامله‌ای ثبت نشده. با هر خرید، دلایل و اطلاعات لحظه تصمیم اینجا ثبت می‌شود."
                    else "موردی با این فیلتر نیست.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(entries, key = { it.id + "_" + it.openedAt }) { e ->
                JournalCard(e, onOpenAsset)
            }
        }
    }
}

private fun winRateColor(s: PerfStats): Color {
    val wr = s.winRate ?: return Color(0xFF9AA7C0)
    val be = s.breakEvenWinRate ?: 0.5
    return pnlColor(wr - be)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) { content() }
}

@Composable
private fun JournalCard(e: JournalEntry, onOpenAsset: (String) -> Unit) {
    var expanded by remember(e.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    e.symbol + "  •  " + e.market.faTitle + "  •  " + (if (e.auto) "خودکار" else "دستی"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    "خرید " + Format.dateTime(e.openedAt) + " @ $" + Format.price(e.entryUsd) +
                        (e.score?.let { " • امتیاز $it" } ?: ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (e.isOpen) "باز — " + JournalExport.duration(e.holdMs)
                    else "فروش: " + (e.exitReason ?: "نامشخص") + " • " + JournalExport.duration(e.holdMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val pnl = e.pnlUsd
                if (pnl != null) {
                    Text((if (pnl >= 0) "+" else "") + "$" + Format.money(pnl), fontWeight = FontWeight.Bold, color = pnlColor(pnl), fontSize = 13.sp)
                    Text(Format.pct(e.pnlPct), color = pnlColor(pnl), fontSize = 11.sp)
                } else {
                    Text(if (e.isOpen) "باز" else "—", fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6), fontSize = 13.sp)
                }
                Text(if (expanded) "▲ جزئیات" else "▼ جزئیات", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            JournalExport.sections(e).forEach { sec ->
                Text(sec.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                sec.lines.forEach { line ->
                    Text(line, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            OutlinedButton(onClick = { onOpenAsset(e.assetId) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("نمودار و وضعیت فعلی " + e.symbol, fontSize = 12.sp)
            }
        }
    }
}
