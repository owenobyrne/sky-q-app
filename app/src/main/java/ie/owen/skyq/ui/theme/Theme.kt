package ie.owen.skyq.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

private fun appTypography() = Typography(
    displayLarge  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    displayMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    displaySmall  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    headlineLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    headlineMedium= TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    headlineSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    titleLarge    = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    titleMedium   = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    titleSmall    = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    bodyLarge     = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    bodyMedium    = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    bodySmall     = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    labelLarge    = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    labelMedium   = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
    labelSmall    = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Light),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SkyQTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = SkyDarkest,
            surface    = SkyNavy,
            primary    = SkyHighlight,
            onPrimary  = SkyText,
            onBackground = SkyText,
            onSurface  = SkyText,
            secondary  = SkyBlue,
            onSecondary = SkyText,
        ),
        typography = appTypography(),
        content = content
    )
}
