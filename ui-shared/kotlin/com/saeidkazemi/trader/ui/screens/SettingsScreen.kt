package com.saeidkazemi.trader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.analysis.RiskManager
import com.saeidkazemi.trader.data.model.MarketKind
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.util.Format
import com.saeidkazemi.trader.core.TraderController

@Composable
fun SettingsScreen(state: UiState, vm: TraderController) {
    var capitalInput by remember(state.settings.capitalUsd) {
        mutableStateOf(Format.raw(state.settings.capitalUsd, 0))
    }
    var feeInput by remember(state.settings.feePct) {
        mutableStateOf(Format.raw(state.settings.feePct * 100, 2))
    }
    var tokenInput by remember(state.settings.nobitexToken) {
        mutableStateOf(state.settings.nobitexToken)
    }
    var lockTrigger by remember(state.settings.profitLockTriggerPct) {
        mutableStateOf(Format.raw(state.settings.profitLockTriggerPct, 1))
    }
    var lockKeep by remember(state.settings.profitLockKeepPct) {
        mutableStateOf(Format.raw(state.settings.profitLockKeepPct, 1))
    }
    var guardMin by remember(state.settings.minWinRatePct) {
        mutableStateOf(Format.raw(state.settings.minWinRatePct, 0))
    }
    var guardWindow by remember(state.settings.guardWindow) {
        mutableStateOf(state.settings.guardWindow.toString())
    }
    var allocInputs by remember(state.settings.allocations) {
        mutableStateOf(
            listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX)
                .associateWith { Format.raw(state.settings.allocationPct(it), 0) }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // سرمایه حساب دمو
        item {
            SettingsCard("سرمایه حساب دمو") {
                Text(
                    "مبلغی که معامله‌گر (در حالت دمو) با آن کار می‌کند. با ذخیره، حساب دمو از نو شروع می‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = capitalInput,
                    onValueChange = { capitalInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("سرمایه به دلار") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        val v = capitalInput.toDoubleOrNull()
                        if (v != null) vm.setCapitalAndReset(v)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("ذخیره و شروع مجدد حساب دمو")
                }
            }
        }

        // تقسیم سرمایه بین بازارها
        item {
            SettingsCard("تقسیم سرمایه بین بازارها") {
                Text(
                    "هر بازار صندوق جداگانه دارد و فقط با سهم خودش معامله می‌کند؛ سود و زیان هر بازار هم جدا حساب می‌شود. " +
                        "مثلاً ۵۰٪ ارز دیجیتال، ۴۰٪ بورس تهران و ۱۰٪ ارز خارجی. صفر یعنی آن بازار معامله نمی‌شود. " +
                        "با ذخیره، حساب دمو با تقسیم جدید از نو ساخته می‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                val markets = listOf(MarketKind.CRYPTO, MarketKind.IR_STOCK, MarketKind.FX)
                markets.forEach { m ->
                    val cur = allocInputs[m] ?: ""
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(m.faTitle, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = cur,
                            onValueChange = { v -> allocInputs = allocInputs + (m to v) },
                            modifier = Modifier.weight(0.8f),
                            label = { Text("درصد") },
                            singleLine = true
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("کم‌ریسک" to "LOW", "متوسط" to "MED", "پرریسک" to "HIGH").forEach { (title, key) ->
                            FilterChip(
                                selected = state.settings.riskFor(m) == key,
                                onClick = { vm.setMarketRisk(m, key) },
                                label = { Text(title, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    val plan = RiskManager().plan(state.settings.riskFor(m), m)
                    Text(
                        "تا " + plan.maxPositions + " موقعیت، هر کدام " + Format.num(plan.positionPct * 100, 0) + "٪ این بخش، " +
                            "حد ضرر بر اساس نوسان " + Format.num(plan.minStopPct * 100, 1) + "–" + Format.num(plan.maxStopPct * 100, 1) + "٪، " +
                            "حد ضرر متحرک " + Format.num(plan.trailPct * 100, 1) + "٪ زیر قله" +
                            (if (m == MarketKind.IR_STOCK) "؛ فقط در ساعت بازار و بدون صف" else if (m == MarketKind.CRYPTO) "؛ ۲۴ ساعته" else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                val sum = markets.sumOf { allocInputs[it]?.toDoubleOrNull() ?: 0.0 }
                Text(
                    "جمع: " + Format.num(sum, 1) + "٪" + (if (kotlin.math.abs(sum - 100) > 0.5) " (باید ۱۰۰ باشد)" else ""),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (kotlin.math.abs(sum - 100) > 0.5) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        vm.setAllocationsAndReset(
                            allocInputs[MarketKind.CRYPTO]?.toDoubleOrNull() ?: -1.0,
                            allocInputs[MarketKind.IR_STOCK]?.toDoubleOrNull() ?: -1.0,
                            allocInputs[MarketKind.FX]?.toDoubleOrNull() ?: -1.0
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("ذخیره تقسیم سرمایه و شروع مجدد حساب دمو")
                }
            }
        }

        // محافظ نرخ برد
        item {
            SettingsCard("محافظ نرخ برد (win rate)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "معامله‌گر همیشه ادامه می‌دهد، ولی اگر در آخرین معاملات بسته‌شده یک بازار، نرخ برد کمتر از حد زیر " +
                            "و جمع سود/زیانشان منفی باشد، آن بازار «محتاط» می‌شود: فقط سیگنال‌های قوی‌تر (آستانه +" +
                            com.saeidkazemi.trader.analysis.Performance.GUARD_EXTRA_THRESHOLD + ") و با نصف حجم خرید می‌شوند. " +
                            "نرخ برد پایین با سودهای بزرگ (جمع مثبت) مشکلی ندارد و محافظ را فعال نمی‌کند.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = state.settings.winRateGuard, onCheckedChange = { vm.toggleWinRateGuard(it) })
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = guardMin,
                        onValueChange = { guardMin = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("حداقل نرخ برد ٪") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = guardWindow,
                        onValueChange = { guardWindow = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("تعداد معاملات اخیر") },
                        singleLine = true
                    )
                }
                Button(
                    onClick = { vm.setWinRateGuard(guardMin.toDoubleOrNull() ?: -1.0, guardWindow.trim().toIntOrNull() ?: -1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("ذخیره محافظ نرخ برد")
                }
                state.perf.guards.forEach { g ->
                    Text(
                        g.market.faTitle + ": " + g.text,
                        fontSize = 11.sp,
                        color = if (g.active) Color(0xFFF7B731) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // قفل سود
        item {
            SettingsCard("قفل سود (حفظ سود به‌دست‌آمده)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "وقتی سود خالص یک موقعیت (پس از کارمزد) به آستانه رسید، حد ضرر روی قیمتی می‌رود که فروش در آن " +
                            "همان سود را حفظ کند. از آن به بعد، این معامله دیگر با سود کمتر یا زیان بسته نمی‌شود. " +
                            "اگر قیمت باز هم بالا برود، حد ضرر متحرک آن را بالاتر می‌برد.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = state.settings.profitLock, onCheckedChange = { vm.toggleProfitLock(it) })
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = lockTrigger,
                        onValueChange = { lockTrigger = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("وقتی سود رسید به ٪") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = lockKeep,
                        onValueChange = { lockKeep = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("این سود حفظ شود ٪") },
                        singleLine = true
                    )
                }
                Button(
                    onClick = {
                        vm.setProfitLockLevels(lockTrigger.toDoubleOrNull() ?: -1.0, lockKeep.toDoubleOrNull() ?: -1.0)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("ذخیره قفل سود")
                }
                Text(
                    "نکته: اگر هر دو عدد برابر باشند (مثل ۱۰ و ۱۰)، با کوچک‌ترین برگشت قیمت بعد از رسیدن به ۱۰٪ فروخته می‌شود. " +
                        "اگر می‌خواهید معامله فرصت رشد بیشتر داشته باشد، عدد دوم را کمی کمتر بگذارید (مثلاً ۱۰ و ۸).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // تحلیل تخصصی
        item {
            SettingsCard("تحلیل تخصصی") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("فراتر از نمودار و کندل", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "بورس: قدرت خریدار حقیقی (سرانه خرید به فروش)، ورود/خروج پول حقیقی امروز و ۵ روز اخیر، حجم مشکوک، " +
                                "P/E در برابر میانه گروه صنعت، زیان‌دهی شرکت و وضعیت کل بازار (درصد نمادهای مثبت و جریان پول کل). " +
                                "ارز دیجیتال: شاخص ترس و طمع، فیلتر روند بیت‌کوین، قدرت نسبی در برابر بیت‌کوین، حجم غیرعادی و " +
                                "فشار دفتر سفارش نوبیتکس. تا ۲۰± امتیاز اثر دارد و در شرایط خطرناک خرید را متوقف می‌کند.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                            lineHeight = 18.sp
                        )
                    }
                    Switch(
                        checked = state.settings.proAnalysis,
                        onCheckedChange = { vm.toggleProAnalysis(it) }
                    )
                }
            }
        }

        // هشدار صوتی و لرزش
        item {
            SettingsCard("هشدار صوتی و لرزش معاملات") {
                Text(
                    "قبل از هر خرید/فروش یک صدای هشدار پخش می‌شود و پس از انجام معامله، صدای دیگری همراه با لرزش گوشی.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchRow("صدای شروع و پایان معامله", state.settings.soundAlerts) { vm.toggleSound(it) }
                if (!state.platform.isDesktop) {
                    SwitchRow("لرزش پس از انجام معامله", state.settings.vibrateAlerts) { vm.toggleVibrate(it) }
                    SwitchRow("پخش حتی در حالت بی‌صدا (کانال زنگ هشدار)", state.settings.loudAlerts) { vm.toggleLoud(it) }
                }
                OutlinedButton(
                    onClick = { vm.testAlert() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("آزمایش صدا و لرزش")
                }
            }
        }

        // کار دائمی با قفل گوشی
        if (!state.platform.isDesktop) {
            item {
                SettingsCard("کار دائمی حتی با قفل بودن گوشی") {
                    val ignored = state.platform.batteryOptimizationIgnored
                    Text(
                        "معامله‌گر در یک سرویس پیش‌زمینه با قفل پردازنده (WakeLock) و وای‌فای کار می‌کند تا با خاموش شدن صفحه " +
                            "متوقف نشود؛ اگر سیستم آن را ببندد، خودکار دوباره اجرا می‌شود. برای اطمینان کامل، برنامه باید از " +
                            "«بهینه‌سازی باتری» معاف باشد.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Text(
                        when (ignored) {
                            true -> "✅ معافیت از بهینه‌سازی باتری فعال است."
                            false -> "⚠️ برنامه هنوز از بهینه‌سازی باتری معاف نیست؛ ممکن است با قفل گوشی کند یا متوقف شود."
                            null -> ""
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (ignored == true) Color(0xFF2EBD85) else Color(0xFFF7B731),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (ignored != true) {
                        Button(
                            onClick = { vm.requestBatteryExemption() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Text("معاف کردن از بهینه‌سازی باتری")
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.openAppSettings() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text("تنظیمات برنامه (اجرای خودکار / باتری)")
                    }
                    Text(
                        autostartHint(state.platform.manufacturer),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // معامله خودکار
        item {
            SettingsCard("معامله‌گر خودکار") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("فعال‌سازی معامله خودکار", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "بدون تأیید موردی؛ هر ۶۰ ثانیه بازار را بررسی و طبق سیگنال‌ها خرید و فروش می‌کند. به‌صورت پیش‌فرض روشن است و فقط برای توقف موقت می‌توانید خاموشش کنید.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = state.settings.autoTrade,
                        onCheckedChange = { vm.toggleAutoTrade(it) }
                    )
                }
            }
        }

        // بررسی اخبار
        item {
            SettingsCard("بررسی اخبار و اطلاعیه‌ها") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("اثر دادن اخبار در تصمیم‌ها", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "برای سهام بورس تهران اطلاعیه‌های رسمی کدال (افزایش سرمایه، سود/زیان، گزارش ماهانه، توقف نماد و…) " +
                                "و اخبار فارسی، و برای ارز دیجیتال، ارز خارجی و طلا اخبار جهانی بررسی می‌شود. " +
                                "اخبار مثبت تا ۱۲ امتیاز اضافه و اخبار منفی تا ۱۲ امتیاز کم می‌کنند؛ " +
                                "خبر منفی مهمِ تازه، خرید را متوقف و موقعیت باز را می‌فروشد.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                            lineHeight = 18.sp
                        )
                    }
                    Switch(
                        checked = state.settings.newsEnabled,
                        onCheckedChange = { vm.toggleNews(it) }
                    )
                }
            }
        }

        // اجرای خودکار با ویندوز
        if (state.platform.isDesktop) {
            item {
                SettingsCard("اجرا با روشن شدن ویندوز") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("شروع خودکار در System Tray", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                if (state.platform.autostartSupported)
                                    "با روشن شدن کامپیوتر، برنامه بی‌صدا کنار ساعت ویندوز اجرا می‌شود و معامله‌گری را ادامه می‌دهد."
                                else
                                    "این گزینه فقط در نسخه نصب‌شده (MSI/EXE) فعال است.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = state.platform.autostartEnabled,
                            enabled = state.platform.autostartSupported,
                            onCheckedChange = { vm.setAutostart(it) }
                        )
                    }
                    Text(
                        "محل ذخیره اطلاعات: " + state.platform.dataLocation,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // کارمزد
        item {
            SettingsCard("کارمزد و هزینه واقعی معاملات") {
                Text(
                    "در هر خرید و فروش سه هزینه واقعی کم می‌شود: کارمزد رسمی، مالیات (فقط فروش سهام) و اسپرد، یعنی فاصله " +
                        "بهترین قیمت خرید و فروش. سفارش بازار به بهترین قیمت فروشنده می‌خرد و به بهترین قیمت خریدار می‌فروشد. " +
                        "اسپرد هر لحظه از دفتر سفارش زنده خوانده می‌شود.",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.saeidkazemi.trader.trading.Fees.table(state.settings).forEach { ln ->
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ln.market.faTitle + (if (ln.real) "" else " (فرضی)"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "خرید " + Format.trim(ln.buyPct, 4) + "٪ • فروش " + Format.trim(ln.sellPct, 4) + "٪",
                                fontSize = 12.sp,
                                color = if (ln.real) Color(0xFF16C784) else Color(0xFFF7B731)
                            )
                        }
                        Text(ln.detail, fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "پله کارمزد شما در نوبیتکس (بر اساس حجم معاملات ۳۰ روز اخیر؛ در پنل نوبیتکس ← سطح کاربری ببینید):",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
                com.saeidkazemi.trader.trading.Fees.NOBITEX_TIERS.withIndex().chunked(4).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (i, t) ->
                            FilterChip(
                                selected = state.settings.nobitexFeeTier == i,
                                onClick = { vm.setNobitexFeeTier(i) },
                                label = { Text(t.name, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    "کارمزد فرضی فارکس (درصد هر طرف معامله):",
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = feeInput,
                        onValueChange = { feeInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("درصد") },
                        singleLine = true
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                    OutlinedButton(onClick = {
                        val pct = feeInput.toDoubleOrNull()
                        if (pct != null) vm.setFeePct(pct / 100.0)
                    }) {
                        Text("ذخیره")
                    }
                }
            }
        }

        // معامله واقعی
        item {
            SettingsCard("معامله واقعی با نوبیتکس (آزمایشی)") {
                Text(
                    "هشدار: با فعال‌سازی این بخش، سفارش‌های واقعی به صرافی نوبیتکس با موجودی ریالی حساب شما ارسال می‌شود. " +
                        "حتماً ابتدا با مبلغ کم آزمایش کنید. توکن ای‌پی‌آی را از پنل نوبیتکس بسازید؛ فقط روی همین دستگاه ذخیره می‌شود.",
                    fontSize = 11.sp,
                    color = Color(0xFFF7B731)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ارسال سفارش واقعی", fontSize = 13.sp)
                    Switch(
                        checked = state.settings.realTrading,
                        onCheckedChange = { vm.toggleRealTrading(it) }
                    )
                }
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("توکن ای‌پی‌آی نوبیتکس") },
                    singleLine = true
                )
                Button(
                    onClick = { vm.saveNobitexToken(tokenInput) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("ذخیره توکن")
                }
                Text(
                    text = if (state.settings.nobitexToken.isNotBlank()) "وضعیت: توکن ذخیره شده است."
                    else "وضعیت: توکنی تنظیم نشده.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    "ارزهای متصل (بازار ریالی): بیت‌کوین، اتریوم، تتر، لایت‌کوین، ریپل، دوج‌کوین، ترون، کاردانو، سولانا، بایننس‌کوین، تون، آوالانچ، پولکادات و چین‌لینک. اتصال بورس تهران به کارگزاری در نسخه‌های بعدی اضافه می‌شود.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // درباره
        item {
            SettingsCard("درباره و هشدار مهم") {
                Text(
                    "معامله‌یار یک دستیار معاملاتی است، نه تضمین سود. هیچ رباتی نمی‌تواند سود را تضمین کند؛ " +
                        "بازارها ذاتاً پرریسک‌اند و احتمال زیان همیشه وجود دارد. این اپ به‌صورت پیش‌فرض در حالت دمو " +
                        "(بدون پول واقعی) کار می‌کند تا بتوانید بدون ریسک، عملکرد استراتژی را بسنجید. " +
                        "پیش از فعال‌سازی معامله واقعی، حداقل چند هفته خروجی حالت دمو را بررسی کنید و فقط با پولی وارد شوید که " +
                        "تحمل زیان آن را دارید.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
                Text(
                    "منابع داده: کوین‌گکو (کریپتو)، فرانکفورت (ارز)، گلد‌ای‌پی‌آی (طلا)، نرخ تتر نوبیتکس (دلار آزاد)، " +
                        "تی‌اس‌ای‌تی‌ام‌سی (بورس تهران با فالبک شبیه‌سازی)، کدال و کانال کدال۳۶۰ (اطلاعیه‌ها) و گوگل‌نیوز (اخبار).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                    lineHeight = 18.sp
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** راهنمای اجرای خودکار برای رابط‌های سفارشی سازندگان مختلف. */
private fun autostartHint(manufacturer: String): String {
    val m = manufacturer.lowercase()
    return when {
        "xiaomi" in m || "redmi" in m || "poco" in m ->
            "شیائومی/ردمی/پوکو: تنظیمات برنامه ← «اجرای خودکار» (Autostart) را روشن و «صرفه‌جویی باتری» را روی «بدون محدودیت» بگذارید؛ در لیست برنامه‌های اخیر هم برنامه را قفل کنید."
        "huawei" in m || "honor" in m ->
            "هواوی/آنر: تنظیمات ← باتری ← راه‌اندازی برنامه ← معامله‌یار را «مدیریت دستی» و هر سه گزینه را روشن کنید."
        "samsung" in m ->
            "سامسونگ: تنظیمات برنامه ← باتری ← «بدون محدودیت» را انتخاب کنید و برنامه را از «برنامه‌های در حالت خواب» خارج کنید."
        "oppo" in m || "realme" in m || "oneplus" in m || "vivo" in m ->
            "اوپو/ریلمی/وان‌پلاس/ویوو: تنظیمات برنامه ← باتری ← «اجازه فعالیت در پس‌زمینه» و «اجرای خودکار» را روشن کنید."
        else ->
            "در برخی گوشی‌ها (شیائومی، هواوی، سامسونگ، اوپو) علاوه بر این، باید در تنظیمات برنامه «اجرای خودکار» و «باتری بدون محدودیت» را هم فعال کنید."
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        content()
    }
}
