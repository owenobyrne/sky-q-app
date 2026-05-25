package ie.owen.skyq.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import ie.owen.skyq.data.settings.AppSettings
import ie.owen.skyq.data.settings.StreamingMode
import ie.owen.skyq.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val mode by AppSettings.streamingMode.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp)
    ) {
        Text("Settings", color = SkyText, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = InterFontFamily)
        Spacer(Modifier.height(36.dp))

        Text("Streaming method", color = SkyText, fontSize = 20.sp, fontWeight = FontWeight.Medium, fontFamily = InterFontFamily)
        Spacer(Modifier.height(4.dp))
        Text(
            "How live TV is streamed from the server.",
            color = SkyTextDim, fontSize = 14.sp, fontWeight = FontWeight.Light, fontFamily = InterFontFamily
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StreamingMode.values().forEach { option ->
                StreamingOption(
                    option = option,
                    selected = option == mode,
                    autoFocus = option == mode,
                    onClick = { AppSettings.setStreamingMode(option) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamingOption(
    option: StreamingMode,
    selected: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SkySelected else SkyCellBg)
            .border(
                width = 2.dp,
                color = if (focused) SkyText else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, onClick = onClick)
            .focusable()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(option.label, color = SkyText, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = InterFontFamily)
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Text("✓", color = SkyText, fontSize = 18.sp, fontFamily = InterFontFamily)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            option.description,
            color = SkyText.copy(alpha = 0.85f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
            fontFamily = InterFontFamily
        )
    }
}
