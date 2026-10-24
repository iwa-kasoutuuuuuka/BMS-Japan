package jp.bmsjapan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF0D2B45)
private val Green = Color(0xFF2E7D32)
private val GreenLight = Color(0xFF81C784)
private val Red = Color(0xFFD32F2F)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEFDC),
    secondary = Navy,
    error = Red,
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF0B2E0D),
    primaryContainer = Color(0xFF1E4620),
    secondary = Color(0xFF9EC3E6),
    error = Color(0xFFFF8A80),
)

@Composable
fun BmsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
