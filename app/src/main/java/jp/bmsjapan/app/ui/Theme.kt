package jp.bmsjapan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 表示モード（設定として保存） */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Material の色に無い、このアプリ専用の色 */
@Immutable
data class BmsColors(
    val charge: Color,
    val discharge: Color,
    val idle: Color,
    val mixed: Color,
    val cellMax: Color,
    val cellMin: Color,
    val gaugeTrack: Color,
    val gaugeStart: Color,
    val gaugeEnd: Color,
    val cardBorder: Color,
    val good: Color,
)

private val DarkBmsColors = BmsColors(
    charge = Color(0xFF34D399),
    discharge = Color(0xFFFBBF24),
    idle = Color(0xFF94A3B8),
    mixed = Color(0xFFA78BFA),
    cellMax = Color(0xFFFB923C),
    cellMin = Color(0xFF38BDF8),
    gaugeTrack = Color(0xFF1E293B),
    gaugeStart = Color(0xFF22D3EE),
    gaugeEnd = Color(0xFF34D399),
    cardBorder = Color(0xFF1F2A40),
    good = Color(0xFF34D399),
)

private val LightBmsColors = BmsColors(
    charge = Color(0xFF059669),
    discharge = Color(0xFFD97706),
    idle = Color(0xFF64748B),
    mixed = Color(0xFF7C3AED),
    cellMax = Color(0xFFEA580C),
    cellMin = Color(0xFF0284C7),
    gaugeTrack = Color(0xFFE2E8F0),
    gaugeStart = Color(0xFF06B6D4),
    gaugeEnd = Color(0xFF10B981),
    cardBorder = Color(0xFFE2E8F0),
    good = Color(0xFF059669),
)

val LocalBmsColors = staticCompositionLocalOf { DarkBmsColors }

/** 深い紺色にティールのアクセント */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    // 選択中のタブ・セグメントボタンの背景（既定の薄紫をやめてティール系に）
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFA78BFA),
    background = Color(0xFF0A0F1C),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0A0F1C),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1A2236),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF070B15),
    surfaceContainerLow = Color(0xFF0F1626),
    surfaceContainer = Color(0xFF111A2C),
    surfaceContainerHigh = Color(0xFF141E32),
    surfaceContainerHighest = Color(0xFF141E32),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1F2A40),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF4C1D1D),
    onErrorContainer = Color(0xFFFECACA),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFF5B6475),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/** 数字の桁がそろう等幅数字（tnum）で、値が変わっても表示が揺れないようにする */
private val Numeric = TextStyle(fontFeatureSettings = "tnum")

private val BmsTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.merge(Numeric).copy(fontWeight = FontWeight.Bold, fontSize = 56.sp, letterSpacing = (-1).sp),
        headlineMedium = t.headlineMedium.merge(Numeric).copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.merge(Numeric).copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.merge(Numeric).copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.merge(Numeric),
        bodyMedium = t.bodyMedium.merge(Numeric),
        labelMedium = t.labelMedium.copy(letterSpacing = 0.4.sp),
        labelSmall = t.labelSmall.merge(Numeric).copy(letterSpacing = 0.4.sp),
    )
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun BmsTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    CompositionLocalProvider(LocalBmsColors provides if (dark) DarkBmsColors else LightBmsColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = BmsTypography,
            content = content,
        )
    }
}
