package ie.owen.skyq.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

private fun interTypography() = Typography(
    displayLarge  = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    displayMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    displaySmall  = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    headlineLarge = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    headlineMedium= TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    headlineSmall = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    titleLarge    = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    titleMedium   = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    titleSmall    = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    bodyLarge     = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    bodyMedium    = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    bodySmall     = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    labelLarge    = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    labelMedium   = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
    labelSmall    = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Light),
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
        typography = interTypography(),
        content = content
    )
}
