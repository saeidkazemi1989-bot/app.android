package com.saeidkazemi.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saeidkazemi.trader.core.AppVersion
import com.saeidkazemi.trader.update.UpdateState

private val NewGreen = Color(0xFF2FB47C)

/** دکمه اصلی به‌روزرسانی (با وضعیت دانلود). */
@Composable
private fun UpdateButton(u: UpdateState, onInstall: () -> Unit, onCheck: () -> Unit) {
    when {
        u.progress != null -> {
            Text("در حال دانلود نسخه جدید… " + u.progress + "٪", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            LinearProgressIndicator(
                progress = { (u.progress ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
        u.installing -> Text("در حال باز کردن نصب‌کننده…", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        u.available -> Button(onClick = onInstall, modifier = Modifier.padding(top = 6.dp)) {
            Text("به‌روزرسانی به نسخه " + (u.info?.name ?: ""))
        }
        else -> OutlinedButton(onClick = onCheck, enabled = !u.checking, modifier = Modifier.padding(top = 6.dp)) {
            Text(if (u.checking) "در حال بررسی…" else "بررسی نسخه جدید", fontSize = 12.sp)
        }
    }
}

/** نوار «نسخه جدید آماده است» در داشبورد. */
@Composable
fun UpdateBanner(u: UpdateState, onInstall: () -> Unit, onCheck: () -> Unit) {
    if (!u.supported || !(u.available || u.progress != null || u.installing)) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .background(NewGreen.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            "نسخه جدید " + (u.info?.name ?: "") + " آماده است (نسخه فعلی " + AppVersion.NAME + "). با یک دکمه نصب می‌شود؛ خریدها و ژورنال حفظ می‌شوند.",
            fontSize = 12.sp,
            color = NewGreen,
            lineHeight = 19.sp
        )
        UpdateButton(u, onInstall, onCheck)
    }
}

/** کارت به‌روزرسانی در تنظیمات. */
@Composable
fun UpdateCard(u: UpdateState, isDesktop: Boolean, onInstall: () -> Unit, onCheck: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("به‌روزرسانی برنامه", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("نسخه فعلی: " + AppVersion.NAME, fontSize = 12.sp, color = muted)
        }
        val info = u.info
        Text(
            when {
                !u.supported -> "در این نسخه به‌روزرسانی داخلی فعال نیست؛ از صفحه دانلود نصب کنید."
                u.available && info != null -> "نسخه جدید " + info.name + " آماده است."
                info != null -> "برنامه به‌روز است." + (if (u.lastCheckedAt > 0) " (آخرین بررسی: " + agoFa(u.lastCheckedAt) + ")" else "")
                u.error != null -> "بررسی نسخه جدید ممکن نشد: " + u.error
                else -> "هر ۶ ساعت خودکار بررسی می‌شود."
            },
            fontSize = 12.sp,
            color = if (u.available) NewGreen else muted,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (u.available && info != null && info.notes.isNotBlank()) {
            Text("تغییرات: " + info.notes, fontSize = 11.sp, color = muted, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (u.supported) {
            UpdateButton(u, onInstall, onCheck)
            if (u.available && u.progress == null && !u.installing) {
                OutlinedButton(onClick = onCheck, enabled = !u.checking, modifier = Modifier.padding(top = 4.dp)) {
                    Text("بررسی دوباره", fontSize = 12.sp)
                }
            }
            Text(
                if (isDesktop) "با زدن دکمه، نسخه جدید دانلود می‌شود، برنامه چند لحظه بسته و نصب می‌شود و دوباره باز می‌شود. اطلاعات شما در %APPDATA%\\MoameleYar می‌ماند."
                else "با زدن دکمه، نسخه جدید دانلود می‌شود و صفحه نصب اندروید باز می‌شود؛ فقط «به‌روزرسانی» را بزنید (اندروید برای امنیت، این یک تأیید را همیشه از خود کاربر می‌خواهد؛ بار اول هم اجازه «نصب از این منبع» را می‌پرسد). خریدها، ژورنال و تنظیمات حفظ می‌شوند.",
                fontSize = 10.sp,
                color = muted,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
