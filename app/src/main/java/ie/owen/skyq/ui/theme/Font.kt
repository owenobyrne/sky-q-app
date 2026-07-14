package ie.owen.skyq.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ie.owen.skyq.R

// Google Sans Flex — Google's brand typeface (open-sourced late 2025, SIL OFL), the font
// behind the Google TV look. Bundled as static weight instances rather than fetched via the
// Play Services downloadable-fonts provider, which doesn't serve the Google Sans family yet.
val AppFontFamily = FontFamily(
    Font(R.font.google_sans_flex_light,   FontWeight.Light),
    Font(R.font.google_sans_flex_regular, FontWeight.Normal),
    Font(R.font.google_sans_flex_medium,  FontWeight.Medium),
    Font(R.font.google_sans_flex_bold,    FontWeight.Bold),
)
