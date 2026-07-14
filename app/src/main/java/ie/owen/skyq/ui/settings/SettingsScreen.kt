package ie.owen.skyq.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import ie.owen.skyq.data.settings.AppSettings
import ie.owen.skyq.data.settings.StreamingMode
import ie.owen.skyq.ui.theme.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val mode       by AppSettings.streamingMode.collectAsStateWithLifecycle()
    val discovered by vm.discovered.collectAsStateWithLifecycle()
    val scanning   by vm.scanning.collectAsStateWithLifecycle()
    val saved      by vm.saved.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        item {
            Text("Settings", color = SkyText, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = AppFontFamily)
        }

        // ── Server ────────────────────────────────────────────────────────────
        item {
            SectionHeader("TVHeadend Server")
            Spacer(Modifier.height(16.dp))

            // Scan button
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionButton(
                    label = if (scanning) "Scanning…" else "Scan for TVHeadend",
                    onClick = { if (!scanning) vm.scan() }
                )
                if (scanning) {
                    Text("Searching for _htsp._tcp on local network…", color = SkyTextDim, fontSize = 13.sp, fontFamily = AppFontFamily)
                }
            }

            // Discovered servers
            if (discovered.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Found:", color = SkyTextDim, fontSize = 13.sp, fontFamily = AppFontFamily)
                Spacer(Modifier.height(6.dp))
                discovered.forEach { server ->
                    DiscoveredServerRow(server = server, onClick = { vm.selectServer(server) })
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // Manual entry
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvTextField(
                    value = vm.host,
                    onValueChange = { vm.host = it },
                    label = "Host / IP",
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next
                )
                TvTextField(
                    value = vm.port,
                    onValueChange = { vm.port = it },
                    label = "HTTP Port",
                    modifier = Modifier.width(140.dp),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            }
        }

        // ── Credentials ───────────────────────────────────────────────────────
        item {
            SectionHeader("Credentials")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvTextField(
                    value = vm.username,
                    onValueChange = { vm.username = it },
                    label = "Username",
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next
                )
                TvTextField(
                    value = vm.password,
                    onValueChange = { vm.password = it },
                    label = "Password",
                    modifier = Modifier.weight(1f),
                    password = true,
                    imeAction = ImeAction.Done
                )
            }
        }

        // ── Streaming mode ────────────────────────────────────────────────────
        item {
            SectionHeader("Streaming method")
            Text(
                "How live TV is streamed from the server.",
                color = SkyTextDim, fontSize = 14.sp, fontWeight = FontWeight.Light, fontFamily = AppFontFamily
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StreamingMode.values().forEach { option ->
                    StreamingOption(
                        option = option,
                        selected = option == mode,
                        autoFocus = option == mode && !AppSettings.isConfigured,
                        onClick = { AppSettings.setStreamingMode(option) }
                    )
                }
            }
        }

        // ── Save ──────────────────────────────────────────────────────────────
        item {
            ActionButton(
                label = if (saved) "Saved ✓" else "Save",
                onClick = { vm.save() },
                highlight = true
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(title: String) {
    Text(title, color = SkyText, fontSize = 20.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
    imeAction: ImeAction = ImeAction.Next
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = modifier) {
        Text(label, color = SkyTextDim, fontSize = 13.sp, fontFamily = AppFontFamily, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) keyboardController?.show()
                }
                .background(SkyCellBg, RoundedCornerShape(6.dp))
                .border(
                    1.5.dp,
                    if (focused) SkySelected else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = SkyText, fontSize = 16.sp, fontFamily = AppFontFamily),
            cursorBrush = SolidColor(SkyText),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                onDone = { keyboardController?.hide() }
            )
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoveredServerRow(
    server: ie.owen.skyq.data.discovery.TvHeadendDiscovery.Server,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) SkySelected else SkyCellBg)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = false, onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(server.name, color = SkyText, fontSize = 15.sp, fontFamily = AppFontFamily, fontWeight = FontWeight.Medium)
        Text(server.httpUrl, color = SkyTextDim, fontSize = 13.sp, fontFamily = AppFontFamily)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(label: String, onClick: () -> Unit, highlight: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    highlight && focused -> SkySelected
                    highlight -> SkyHighlight
                    focused -> SkyCellBg.copy(alpha = 0.8f)
                    else -> SkyCellBg
                }
            )
            .border(1.5.dp, if (focused) SkyText else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = false, onClick = onClick)
            .focusable()
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(label, color = SkyText, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
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
            .width(240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SkySelected else SkyCellBg)
            .border(2.dp, if (focused) SkyText else Color.Transparent, RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, onClick = onClick)
            .focusable()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(option.label, color = SkyText, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = AppFontFamily)
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Text("✓", color = SkyText, fontSize = 16.sp, fontFamily = AppFontFamily)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(option.description, color = SkyText.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Light, fontFamily = AppFontFamily)
    }
}
