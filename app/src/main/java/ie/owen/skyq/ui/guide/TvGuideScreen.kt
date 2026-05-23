package ie.owen.skyq.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.ui.theme.*

private val dropShadow = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(1f, 2f), blurRadius = 2f)
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvGuideScreen(
    onChannelSelected: (uuid: String, name: String, number: String, title: String, iconPath: String, startTime: Long?, stopTime: Long?, description: String?) -> Unit,
    onPreviewChannelChanged: (channelUuid: String) -> Unit = {},
    viewModel: TvGuideViewModel = viewModel()
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
    // Held as a State object (not a delegated var) so the remember{} lambda below can read and
    // write it without a stale closure.
    val pendingChannelUuid = remember { mutableStateOf<String?>(null) }
    val onEventSelected: (EpgEvent) -> Unit = remember {
        { event ->
            if (event.isLive) {
                if (pendingChannelUuid.value == event.channelUuid) {
                    latestOnChannelSelected(
                        event.channelUuid, event.channelName, event.channelNumber,
                        event.title, event.channelIcon ?: "",
                        event.start, event.stop, event.description
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

    Column(modifier = Modifier.fillMaxSize()) {
        InfoPanel(
            event = focusedEvent,
            modifier = Modifier.fillMaxWidth().weight(0.38f)
        )

        when {
            state.isLoading -> {
                Box(Modifier.weight(0.62f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(Modifier.weight(0.62f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Failed to load: ${state.error}", color = SkyTextDim)
                }
            }
            else -> {
                EpgGrid(
                    channels = state.channels,
                    eventsByChannel = state.eventsByChannel,
                    windowStart = state.windowStart,
                    onEventFocused = onEventFocused,
                    onEventSelected = onEventSelected,
                    onChannelSelected = onChannelDirectSelected,
                    modifier = Modifier.weight(0.62f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoPanel(event: EpgEvent?, modifier: Modifier = Modifier) {
    val cellFont = LocalConfiguration.current.screenHeightDp * 0.025f
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(horizontal = 28.dp, vertical = 20.dp),
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
                            modifier = Modifier
                                .size(58.dp)
                                .padding(bottom = 3.dp)
                        )
                    }
                    Text(
                        text = event.title,
                        color = SkyText,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = dropShadow
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
                        Text(metaText, color = SkyTextDim, fontSize = 15.sp, style = dropShadow)
                        if (event.isLive) {
                            Spacer(Modifier.width(10.dp))
                            LiveBadge()
                        }
                    }
                }
                if (event.image != null) {
                    Spacer(Modifier.width(16.dp))
                    AsyncImage(
                        model = event.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(96.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = event.description ?: event.summary ?: event.subtitle ?: "",
                color = SkyText.copy(alpha = 0.8f),
                fontSize = cellFont.sp,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Light,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                style = dropShadow
            )
        } else {
            Text("TV Guide", color = SkyText, fontSize = 26.sp, fontWeight = FontWeight.Bold, style = dropShadow)
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
            .background(SkyLiveBadge)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("LIVE", color = SkyText, fontSize = 11.sp, fontWeight = FontWeight.Bold, style = dropShadow)
    }
}
