package ie.owen.skyq.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight vertically-scrolled Now/Next list that replaces the Canvas EPG grid (too heavy
 * for the Google TV): one row per channel showing the current programme with a subtle progress
 * bar and the next programme with its start time.
 *
 * The now/next lookup is done off the main thread in [TvGuideViewModel]; this composable only
 * reads the precomputed [NowNext] per channel, so scrolling and Up/Down keypresses never scan
 * event lists in composition.
 *
 * The whole list is a single focus target with a selected-row highlight — Up/Down step through
 * channels and only leave the list at the very top/bottom, so fast presses can't accidentally
 * jump focus out to other controls.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowNextList(
    channels: List<Channel>,
    nowNextByChannel: Map<String, NowNext>,
    onEventFocused: (EpgEvent?) -> Unit,
    onEventSelected: (EpgEvent) -> Unit,
    onChannelSelected: (Channel) -> Unit = {},
    initialChannelUuid: String? = null,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val rowHeight = (config.screenHeightDp * 0.115f).dp
    val channelColWidth = (config.screenWidthDp * 0.22f).dp

    val listState = rememberLazyListState()
    var selectedIndex by remember { mutableIntStateOf(0) }
    var listFocused by remember { mutableStateOf(false) }

    fun nowOf(ch: Channel): EpgEvent? =
        nowNextByChannel[ch.uuid]?.let { it.now ?: it.next }

    // Position the selection on the last-used channel once the list loads.
    var initialised by remember { mutableStateOf(false) }
    LaunchedEffect(channels) {
        if (initialised || channels.isEmpty()) return@LaunchedEffect
        selectedIndex = channels.indexOfFirst { it.uuid == initialChannelUuid }.coerceAtLeast(0)
        listState.scrollToItem(selectedIndex)
        initialised = true
    }

    // Keep the selected row visible and mirror it into the details panel.
    LaunchedEffect(selectedIndex, channels) {
        val ch = channels.getOrNull(selectedIndex) ?: return@LaunchedEffect
        onEventFocused(nowOf(ch))
        val li = listState.layoutInfo
        val item = li.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (item == null) {
            listState.animateScrollToItem(selectedIndex)
        } else {
            val delta = when {
                item.offset < li.viewportStartOffset -> item.offset - li.viewportStartOffset
                item.offset + item.size > li.viewportEndOffset -> item.offset + item.size - li.viewportEndOffset
                else -> 0
            }
            if (delta != 0) listState.animateScrollBy(delta.toFloat())
        }
    }

    Column(modifier) {
        // Column headers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 40.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(channelColWidth))
            Text("NOW",  color = SkyTextDim, fontSize = 12.sp, fontFamily = AppFontFamily,
                letterSpacing = 1.sp, modifier = Modifier.weight(1.25f).padding(start = 16.dp))
            Text("NEXT", color = SkyTextDim, fontSize = 12.sp, fontFamily = AppFontFamily,
                letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    listFocused = it.isFocused
                    if (it.isFocused) channels.getOrNull(selectedIndex)?.let { ch -> onEventFocused(nowOf(ch)) }
                }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionUp ->
                            if (selectedIndex > 0) { selectedIndex--; true } else false
                        Key.DirectionDown ->
                            if (selectedIndex < channels.lastIndex) { selectedIndex++; true } else false
                        Key.DirectionCenter, Key.Enter -> {
                            channels.getOrNull(selectedIndex)?.let { ch ->
                                val now = nowNextByChannel[ch.uuid]?.now
                                if (now != null) onEventSelected(now) else onChannelSelected(ch)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
        ) {
            items(channels, key = { it.uuid }) { channel ->
                val nn = nowNextByChannel[channel.uuid]
                val selected = listFocused && channels.getOrNull(selectedIndex)?.uuid == channel.uuid

                NowNextRow(
                    channel = channel,
                    now = nn?.now,
                    next = nn?.next,
                    selected = selected,
                    rowHeight = rowHeight,
                    channelColWidth = channelColWidth
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowNextRow(
    channel: Channel,
    now: EpgEvent?,
    next: EpgEvent?,
    selected: Boolean,
    rowHeight: Dp,
    channelColWidth: Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SkySelected else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Channel — number, icon, name
        Row(
            modifier = Modifier.width(channelColWidth),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                channel.number.toString(),
                color = SkyTextDim,
                fontSize = 14.sp,
                fontFamily = AppFontFamily,
                modifier = Modifier.width(44.dp)
            )
            if (channel.iconPublicUrl != null) {
                AsyncImage(
                    model = TvHeadendClient.resolveUrl(channel.iconPublicUrl),
                    contentDescription = channel.name,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                channel.name,
                color = SkyText,
                fontSize = 16.sp,
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // NOW — title + progress
        Column(modifier = Modifier.weight(1.25f).padding(start = 16.dp, end = 24.dp)) {
            Text(
                now?.title ?: "—",
                color = SkyText,
                fontSize = 16.sp,
                fontFamily = AppFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (now?.start != null && now.stop > now.start) {
                Spacer(Modifier.height(7.dp))
                val progress = ((System.currentTimeMillis() / 1000L - now.start).toFloat() /
                        (now.stop - now.start).toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Color.White.copy(alpha = 0.70f))
                    )
                }
            }
        }

        // NEXT — start time + title
        Column(modifier = Modifier.weight(1f)) {
            Text(
                next?.let { formatTime(it.start) } ?: "",
                color = SkyTextDim,
                fontSize = 13.sp,
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Medium
            )
            if (next != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    next.title,
                    color = SkyText.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatTime(unixSeconds: Long): String =
    SimpleDateFormat("h:mma", Locale.getDefault()).format(Date(unixSeconds * 1000))
        .lowercase().replace(".", "")
