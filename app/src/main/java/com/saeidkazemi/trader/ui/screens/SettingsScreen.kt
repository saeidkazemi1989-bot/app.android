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
import com.saeidkazemi.trader.ui.UiState
import com.saeidkazemi.trader.util.Format
import com.saeidkazemi.trader.viewmodel.MainViewModel

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    var capitalInput by remember(state.settings.capitalUsd) {
        mutableStateOf(Format.raw(state.settings.capitalUsd, 0))
    }
    var feeInput by remember(state.settings.feePct) {
        mutableStateOf(Format.raw(state.settings.feePct * 100, 2))
    }
    var tokenInput by remember(state.settings.nobitexToken) {
        mutableStateOf(state.settings.nobitexToken)
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

        // سطح ریسک
        item {
            SettingsCard("سطح ریسک") {
                Text(
                    "تعداد موقعیت، سهم هر خرید و حد ضرر/حد سود را تعیین می‌کند.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = listOf(
                        "کم‌ریسک" to "LOW",
                        "متوسط" to "MED",
                        "پرریسک" to "HIGH"
                    )
                    options.forEach { (title, key) ->
                        FilterChip(
                            selected = state.settings.riskLevel == key,
                            onClick = { vm.setRiskLevel(key) },
                            label = { Text(title, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    text = when (state.settings.riskLevel) {
                        "LOW" -> "تا ۶ موقعیت، هر کدام ۱۰٪ سرمایه، حد ضرر ۴٪"
                        "HIGH" -> "تا ۴ موقعیت، هر کدام ۲۲٪ سرمایه، حد ضرر ۸٪"
                        else -> "تا ۵ موقعیت، هر کدام ۱۵٪ سرمایه، حد ضرر ۶٪"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
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
                            "سرویس پس‌زمینه هر ۶۰ ثانیه بازار را بررسی کرده و طبق سیگنال‌ها خرید و فروش می‌کند.",
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

        // کارمزد
        item {
            SettingsCard("کارمزد معاملات") {
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
                        "حتماً ابتدا با مبلغ کم آزمایش کنید. توکن ای‌پی‌آی را از پنل نوبیتکس بسازید؛ فقط روی همین گوشی ذخیره می‌شود.",
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
                    "نمادهای متصل: بیت‌کوین، اتریوم، تتر و لایت‌کوین (بازار تومانی). اتصال بورس تهران به کارگزاری در نسخه‌های بعدی اضافه می‌شود.",
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
                    "منابع داده: کوین‌گکو (کریپتو)، فرانکفورت (ارز)، گلد‌ای‌پی‌آی (طلا)، اوپن‌ای‌آر (ریال)، " +
                        "و تلاش برای تی‌اس‌ای‌تی‌ام‌سی (بورس تهران با فالبک شبیه‌سازی).",
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
