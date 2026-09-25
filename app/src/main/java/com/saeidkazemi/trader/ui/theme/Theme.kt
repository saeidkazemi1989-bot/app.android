package com.saeidkazemi.trader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ProfitGreen = Color(0xFF16C784)
val LossRed = Color(0xFFEA3943)
val AccentGold = Color(0xFFF7B731)
val BgDark = Color(0xFF0B1120)
val CardDark = Color(0xFF141B2D)
val CardDarkAlt = Color(0xFF1B2440)
val TextPrimary = Color(0xFFE8ECF4)
val TextSecondary = Color(0xFF9AA7C0)
val OutlineSoft = Color(0xFF2C3A57)

private val DarkColors = darkColorScheme(
    primary = AccentGold,
    onPrimary = Color(0xFF221B00),
    secondary = ProfitGreen,
    onSecondary = Color.Black,
    background = BgDark,
    onBackground = TextPrimary,
    surface = CardDark,
    onSurface = TextPrimary,
    surfaceVariant = CardDarkAlt,
    onSurfaceVariant = TextSecondary,
    error = LossRed,
    onError = Color.White,
    outline = OutlineSoft
)

@Composable
fun TraderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
