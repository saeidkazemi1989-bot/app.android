package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.core.TraderController
import com.saeidkazemi.trader.review.Finding
import com.saeidkazemi.trader.review.SelfReview
import com.saeidkazemi.trader.ui.UiState
import kotlinx.coroutines.launch

private val GoodC = Color(0xFF2FB47C)
private val BadC = Color(0xFFE5534B)

@Composable
private fun FindingRow(f: Finding) {
    val color = when (f.good) {
        true -> GoodC
        false -> BadC
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            (when (f.good) { true -> "✔ "; false -> "✘ "; else -> "• " }) + (if (f.severity >= 2 && f.good == false) "[مهم] " else "") + f.title,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, lineHeight = 18.sp
        )
        Text(f.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
        f.fix?.let { Text("اصلاح پیشنهادی: $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp) }
    }
}

/**
 * کارت «خودارزیابی ربات»: خلاصه کجا درست فکر کرد و کجا اشتباه، و خروجی گزارش کامل برای تحلیلگر
 * (ارسال آنلاین با کد کوتاه، فایل، یا کپی).
 */
@Composable
fun ReviewCard(state: UiState, vm: TraderController) {
    val r = state.review
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        val onS = MaterialTheme.colorScheme.onSurface
        Text("خودارزیابی ربات: کجا درست فکر کردم، کجا اشتباه", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = onS)
        Text(
            "هر دارایی که تحلیل می‌شود، امتیاز و دلایلش ثبت و ۲۴ و ۷۲ ساعت بعد با قیمت واقعی مقایسه می‌شود؛ معاملات بسته هم کالبدشکافی می‌شوند. " +
                "گزارش کامل را برای تحلیلگر بفرستید تا ایرادها در نسخه بعد رفع شوند.",
            fontSize = 11.sp, color = muted, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp)
        )
        if (r == null) {
            Text(
                if (state.sync.role == "FOLLOWER") "خلاصه از دستگاه اصلی هنوز نرسیده (دستگاه اصلی باید نسخه ۱.۱۰ یا جدیدتر باشد)." else "در حال جمع‌آوری داده…",
                fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            val lines = ArrayList<String>()
            if (r.buyN1 > 0) {
                lines.add(
                    "سیگنال‌های خرید: " + SelfReview.pc(r.buyHit1) + " از " + r.buyN1 + " مورد ۲۴ ساعت بعد بالاتر بودند (کل بازار " + SelfReview.pc(r.baseUp1) +
                        ")، بازده نسبی " + SelfReview.sp(r.buyExcess1) + (if (r.buyN3 > 0) " • ۷۲ ساعت: " + SelfReview.pc(r.buyHit3) + "، نسبی " + SelfReview.sp(r.buyExcess3) else "")
                )
            }
            if (r.sellN1 > 0) lines.add("سیگنال‌های فروش: " + SelfReview.pc(r.sellHit1) + " از " + r.sellN1 + " مورد ۲۴ ساعت بعد پایین‌تر بودند.")
            if (r.closedTrades > 0) lines.add("معاملات بسته: " + r.rightTrades + " از " + r.closedTrades + " درست (سودده).")
            lines.add("پیش‌بینی ثبت‌شده: " + r.predictions + " • ارزیابی‌شده: " + r.evaluated1 + " (۲۴س) / " + r.evaluated3 + " (۷۲س) • مشکل فنی فعال: " + r.issues +
                (if (r.issuesResolved > 0) " (" + r.issuesResolved + " مشکل قبلی برطرف شده)" else ""))
            lines.forEach { Text(it, fontSize = 12.sp, lineHeight = 19.sp, color = onS, modifier = Modifier.padding(top = 6.dp)) }
            val fl = r.findings.orEmpty()
            val shown = if (expanded) fl else fl.filter { it.good != null }.take(5)
            shown.forEach { FindingRow(it) }
            if (fl.size > shown.size || expanded) {
                Text(
                    if (expanded) "نمایش کمتر" else "نمایش همه یافته‌ها (" + fl.size + ")",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded }
                )
            }
        }

        if (state.reportCode.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(GoodC.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("کد گزارش: " + state.reportCode, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GoodC)
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString("کد گزارش معامله‌یار: " + state.reportCode))
                        vm.toast("کد گزارش کپی شد؛ آن را برای تحلیلگر بفرستید.")
                    }) { Text("کپی کد", fontSize = 12.sp) }
                }
                Text(
                    "این کد را در گفتگو برای تحلیلگر بفرستید. گزارش حدود ۱۲ ساعت روی سرور ntfy.sh می‌ماند و فقط با همین کد خوانده می‌شود (توکن نوبیتکس داخلش نیست).",
                    fontSize = 10.sp, color = muted, lineHeight = 16.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Button(onClick = { vm.sendReportOnline() }, enabled = !state.reportBusy, modifier = Modifier.weight(1f)) {
                Text(if (state.reportBusy) "در حال ساخت…" else "ارسال برای تحلیل", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.shareReport() }, enabled = !state.reportBusy, modifier = Modifier.weight(1f)) {
                Text("فایل گزارش", fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val t = vm.reportShortText()
                    if (t != null) {
                        clipboard.setText(AnnotatedString(t))
                        vm.toast("خلاصه گزارش کپی شد (" + (t.length / 1000) + " هزار حرف)؛ در گفتگو بچسبانید.")
                    } else vm.toast("ساخت گزارش ممکن نشد (دستگاه اصلی در دسترس نیست).")
                }
            }, enabled = !state.reportBusy, modifier = Modifier.weight(1f)) {
                Text("کپی خلاصه", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.refreshReview() }, modifier = Modifier.weight(1f)) {
                Text("محاسبه دوباره", fontSize = 12.sp)
            }
        }
        Text(
            "«ارسال برای تحلیل» گزارش را آنلاین می‌فرستد و یک کد کوتاه می‌دهد؛ اگر ntfy.sh فیلتر بود، «فایل گزارش» را بزنید و فایل را در گفتگو بفرستید.",
            fontSize = 10.sp, color = muted, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp)
        )
    }
}
