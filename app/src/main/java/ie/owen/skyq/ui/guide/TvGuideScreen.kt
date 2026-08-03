package ie.owen.skyq.ui.guide

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ie.owen.skyq.R
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvGuideScreen(
    onChannelSelected: (uuid: String, name: String, number: String, title: String, iconPath: String, startTime: Long?, stopTime: Long?, description: String?) -> Unit,
    onPreviewChannelChanged: (channelUuid: String) -> Unit = {},
    onPreviewBoundsChanged: (Rect) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: TvGuideViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusedEvent by viewModel.focusedEvent.collectAsStateWithLifecycle()

    LaunchedEffect(state.previewChannelUuid) {
        state.previewChannelUuid?.let { onPreviewChannelChanged(it) }
    }

    // Stable callbacks — remembered so EpgGrid is never forced to recompose due to lambda identity
    val onEventFocused: (EpgEvent?) -> Unit = remember { viewModel::onEventFocused }
    val latestOnChannelSelected by rememberUpdatedState(onChannelSelected)
    val latestOnPreviewChanged by rememberUpdatedState(onPreviewChannelChanged)
    val pendingChannelUuid = remember { mutableStateOf<String?>(null) }
    val onEventSelected: (EpgEvent) -> Unit = remember {
        { event ->
            if (event.isLive) {
                if (pendingChannelUuid.value == event.channelUuid) {
                    latestOnChannelSelected(
                        event.channelUuid, event.channelName, event.channelNumber,
                        event.title, event.channelIcon ?: "",
                        event.start, event.stop, event.description ?: event.summary ?: event.subtitle
                    )
                } else {
                    pendingChannelUuid.value = event.channelUuid
                    latestOnPreviewChanged(event.channelUuid)
                }
            }
        }
    }
    val onChannelDirectSelected: (ie.owen.skyq.data.model.Channel) -> Unit = remember {
        { channel ->
            if (pendingChannelUuid.value == channel.uuid) {
                latestOnChannelSelected(
                    channel.uuid, channel.name, channel.number.toString(),
                    "", channel.iconPublicUrl ?: "",
                    null, null, null
                )
            } else {
                pendingChannelUuid.value = channel.uuid
                latestOnPreviewChanged(channel.uuid)
            }
        }
    }

    val config = LocalConfiguration.current
    val topRowHeight = (config.screenHeightDp * 0.38f).dp

    Column(modifier = Modifier.fillMaxSize()) {
        // Top section — preview + details, with the clock/settings overlaid top-right so they
        // don't push the preview down. Preview left edge aligns with the grid's channel names.
        Box(modifier = Modifier.fillMaxWidth().height(topRowHeight)) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 40.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(16f / 9f)
                        .onGloballyPositioned { onPreviewBoundsChanged(it.boundsInRoot()) }
                )
                Spacer(Modifier.width(28.dp))
                InfoPanel(event = focusedEvent, modifier = Modifier.weight(1f).fillMaxHeight())
            }

            // Clock + settings — overlaid in the top-right corner.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Clock()
                Spacer(Modifier.width(20.dp))
                SettingsButton(onOpenSettings)
            }
        }

        // EPG grid — full width along the bottom.
        when {
            state.isLoading -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Failed to load: ${state.error}", color = SkyTextDim)
                }
            }
            else -> {
                EpgGrid(
                    channels = state.channels,
                    cellsByChannel = state.cellsByChannel,
                    windowStart = state.windowStart,
                    onEventFocused = onEventFocused,
                    onEventSelected = onEventSelected,
                    onChannelSelected = onChannelDirectSelected,
                    initialChannelUuid = state.initialChannelUuid,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Clock() {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mma", Locale.getDefault())
        while (true) {
            time = fmt.format(Date()).lowercase().replace(".", "")
            kotlinx.coroutines.delay(30_000)
        }
    }
    Text(
        text = time,
        color = SkyText.copy(alpha = 0.85f),
        fontSize = 18.sp,
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Light
    )
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (focused) 0.22f else 0.08f))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = "Settings",
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoPanel(event: EpgEvent?, modifier: Modifier = Modifier) {
    val cellFont = LocalConfiguration.current.screenHeightDp * 0.023f
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        if (event != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (event.channelIcon != null) {
                        AsyncImage(
                            model = TvHeadendClient.resolveUrl(event.channelIcon),
                            contentDescription = event.channelName,
                            modifier = Modifier.size(52.dp).padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = event.title,
                        color = SkyText,
                        fontSize = 30.sp,
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val metaText = buildList {
                            add("${event.durationMinutes}m")
                            event.episodeOnscreen?.let { add(it) }
                            event.genre?.firstOrNull()?.let { dvbGenreLabel(it)?.let { g -> add(g) } }
                            event.starRating?.takeIf { it > 0 }?.let { add("★".repeat(it.coerceAtMost(5))) }
                            event.copyrightYear?.let { add(it.toString()) }
                        }.joinToString(" · ")
                        Text(metaText, color = SkyTextDim, fontSize = 15.sp, fontFamily = AppFontFamily)
                        if (event.isLive) {
                            Spacer(Modifier.width(10.dp))
                            LiveBadge()
                        }
                    }
                }
                if (event.image != null) {
                    Spacer(Modifier.width(20.dp))
                    AsyncImage(
                        model = event.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(104.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = event.description ?: event.summary ?: event.subtitle ?: "",
                color = SkyText.copy(alpha = 0.8f),
                fontSize = cellFont.sp,
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Light,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun dvbGenreLabel(code: Int): String? = when (code ushr 4) {
    1 -> "Movie"
    2 -> "News"
    3 -> "Entertainment"
    4 -> "Sport"
    5 -> "Children's"
    6 -> "Music"
    7 -> "Arts & Culture"
    8 -> "Social Issues"
    9 -> "Factual"
    0xA -> "Lifestyle"
    else -> null
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(SkyLiveBadge)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("LIVE", color = SkyText, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AppFontFamily)
    }
}
