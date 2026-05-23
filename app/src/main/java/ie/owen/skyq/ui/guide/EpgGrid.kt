package ie.owen.skyq.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val dropShadow = TextStyle(
    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(1f, 2f), blurRadius = 2f)
)

private const val DP_PER_MINUTE = 7f
private const val WINDOW_HOURS = 24
private val HEADER_HEIGHT: Dp = 36.dp

// Pre-allocated draw colours — avoids Color object allocation on every frame
private val RowDividerTop    = Color.White.copy(alpha = 0.25f)
private val RowDividerBottom = Color.Black.copy(alpha = 0.45f)
private val ChannelFocusTint = Color.White.copy(alpha = 0.12f)
private val CellFocusTint    = Color.White.copy(alpha = 0.10f)
private val CellRowFocusTint = Color.White.copy(alpha = 0.08f)
private val CellDivider      = Color(0xFF0681F4)
private val ChannelDivider   = Color(0xFF0681F4)

data class EpgCell(
    val event: EpgEvent?,
    val startMinutesFromWindow: Int,
    val durationMinutes: Int
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpgGrid(
    channels: List<Channel>,
    eventsByChannel: Map<String, List<EpgEvent>>,
    windowStart: Long,
    onEventFocused: (EpgEvent?) -> Unit,
    onEventSelected: (EpgEvent) -> Unit,
    onChannelSelected: (Channel) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val config          = LocalConfiguration.current
    val screenHeightDp  = config.screenHeightDp
    val screenWidthDp   = config.screenWidthDp
    val rowHeight       = (screenHeightDp * 0.07f).dp
    val cellFont        = screenHeightDp * 0.025f
    val channelColWidth = (screenWidthDp * 0.20f).dp

    val totalMinutes = WINDOW_HOURS * 60
    val windowEnd    = windowStart + totalMinutes * 60L
    val horizontalScroll    = rememberScrollState()
    val focusedChannelUuid  = remember { mutableStateOf<String?>(null) }
    val density             = LocalDensity.current

    // Pixel constants computed once per density/screen change
    val totalEpgWidthDp = remember(totalMinutes) { (totalMinutes * DP_PER_MINUTE).dp }
    val dpPerMinutePx   = remember(density) { with(density) { DP_PER_MINUTE.dp.toPx() } }
    val viewportWidthPx = remember(density, screenWidthDp) {
        with(density) { (screenWidthDp.dp - (screenWidthDp * 0.20f).dp).roundToPx().toFloat() }
    }
    val hPadPx      = remember(density) { with(density) { 8.dp.toPx() } }
    val selStrokePx = remember(density) { with(density) { 2.dp.toPx() } }

    // Two text styles (normal/light) pre-built so Canvas draw doesn't allocate per frame
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val cellTextStyleNormal = remember(cellFont) {
        TextStyle(
            color = SkyText, fontSize = cellFont.sp, fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
            shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1f, 2f), 2f)
        )
    }
    val cellTextStyleLight = remember(cellFont) {
        TextStyle(
            color = SkyText, fontSize = cellFont.sp, fontFamily = InterFontFamily,
            fontWeight = FontWeight.Light,
            shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1f, 2f), 2f)
        )
    }

    LaunchedEffect(windowStart) {
        val now = System.currentTimeMillis() / 1000L
        val minutesElapsed = ((now - windowStart) / 60).toInt().coerceAtLeast(0)
        val targetPx = with(density) { (minutesElapsed * DP_PER_MINUTE).dp.roundToPx() }
        horizontalScroll.scrollTo(targetPx)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Time header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .background(SkyNavy)
        ) {
            Box(
                modifier = Modifier
                    .width(channelColWidth)
                    .fillMaxHeight()
                    .border(width = 0.5.dp, color = SkyBorder),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    "Today",
                    color = SkyTextDim,
                    fontSize = cellFont.sp,
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(start = 12.dp),
                    style = dropShadow
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScroll)
            ) {
                val tickCount = totalMinutes / 30
                repeat(tickCount) { i ->
                    val tickUnix = windowStart + i * 30 * 60L
                    Box(
                        modifier = Modifier
                            .width((30 * DP_PER_MINUTE).dp)
                            .fillMaxHeight()
                            .border(width = 0.5.dp, color = SkyBorder),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = formatTime(tickUnix),
                            color = SkyTextDim,
                            fontSize = cellFont.sp,
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.padding(start = 8.dp),
                            style = dropShadow
                        )
                    }
                }
            }
        }

        // Channel rows — LazyColumn virtualises vertically; Canvas virtualises horizontally
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(channels, key = { it.uuid }) { channel ->
                val rawEvents = eventsByChannel[channel.uuid] ?: emptyList()
                val cells = remember(rawEvents, windowStart, windowEnd) {
                    buildCells(rawEvents, windowStart, windowEnd)
                }
                val isRowFocused by remember(channel.uuid) {
                    derivedStateOf { focusedChannelUuid.value == channel.uuid }
                }
                val channelFocusRequester  = remember { FocusRequester() }
                val programmeAreaRequester = remember { FocusRequester() }
                val firstProgrammeIndex    = remember(cells) {
                    cells.indexOfFirst { it.event != null }.coerceAtLeast(0)
                }

                var selectedCellIdx        by remember { mutableIntStateOf(firstProgrammeIndex) }
                var isProgrammeAreaFocused by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .drawWithContent {
                            drawContent()
                            drawLine(RowDividerTop,    Offset(0f, 0f),              Offset(size.width, 0f),              1f)
                            drawLine(RowDividerBottom, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
                        }
                ) {
                    ChannelCell(
                        channel        = channel,
                        cellFont       = cellFont,
                        width          = channelColWidth,
                        isRowFocused   = isRowFocused,
                        focusRequester = channelFocusRequester,
                        onFocused      = {
                            focusedChannelUuid.value = channel.uuid
                            onEventFocused(cells.firstOrNull { it.event != null }?.event)
                        },
                        onSelected  = {
                            val liveEvent = cells.firstOrNull { it.event?.isLive == true }?.event
                            if (liveEvent != null) onEventSelected(liveEvent)
                            else onChannelSelected(channel)
                        },
                        onMoveRight = { programmeAreaRequester.requestFocus() }
                    )

                    // Single focusable for the entire programme area — key events navigate selectedCellIdx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScroll)
                            .focusRequester(programmeAreaRequester)
                            .onFocusChanged { state ->
                                isProgrammeAreaFocused = state.isFocused
                                if (state.isFocused) {
                                    focusedChannelUuid.value = channel.uuid
                                    onEventFocused(cells.getOrNull(selectedCellIdx)?.event)
                                }
                            }
                            .focusable()
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                                fun scrollTo(idx: Int) {
                                    val cell = cells.getOrNull(idx) ?: return
                                    coroutineScope.launch {
                                        horizontalScroll.animateScrollTo(
                                            with(density) { (cell.startMinutesFromWindow * DP_PER_MINUTE).dp.roundToPx() }
                                        )
                                    }
                                }
                                when (keyEvent.key) {
                                    Key.DirectionLeft -> {
                                        val prev = (selectedCellIdx - 1 downTo 0)
                                            .firstOrNull { cells[it].event != null }
                                        if (prev != null) {
                                            selectedCellIdx = prev
                                            onEventFocused(cells[prev].event)
                                            scrollTo(prev)
                                        } else {
                                            channelFocusRequester.requestFocus()
                                        }
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        val next = (selectedCellIdx + 1 until cells.size)
                                            .firstOrNull { cells[it].event != null }
                                        if (next != null) {
                                            selectedCellIdx = next
                                            onEventFocused(cells[next].event)
                                            scrollTo(next)
                                            true
                                        } else false
                                    }
                                    Key.Enter, Key.DirectionCenter -> {
                                        cells.getOrNull(selectedCellIdx)?.event
                                            ?.let { onEventSelected(it) }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .pointerInput(cells) {
                                detectTapGestures { offset ->
                                    // offset is in viewport coords; add scroll to get content coords
                                    val contentX = offset.x + horizontalScroll.value
                                    val idx = cells.indexOfFirst { cell ->
                                        val startPx = cell.startMinutesFromWindow * dpPerMinutePx
                                        val endPx   = startPx + (cell.durationMinutes * dpPerMinutePx).coerceAtLeast(2f)
                                        contentX >= startPx && contentX < endPx
                                    }
                                    if (idx >= 0) {
                                        cells[idx].event?.let { event ->
                                            selectedCellIdx = idx
                                            onEventFocused(event)
                                            programmeAreaRequester.requestFocus()
                                            onEventSelected(event)
                                        }
                                    }
                                }
                            }
                    ) {
                        // Canvas spans the full 24-hour EPG width but only draws cells in the
                        // visible scroll window (plus a 30-minute buffer on each side).
                        Canvas(Modifier.width(totalEpgWidthDp).fillMaxHeight()) {
                            val scrollOffset = horizontalScroll.value.toFloat()
                            val bufferPx     = dpPerMinutePx * 30f

                            cells.forEachIndexed { idx, cell ->
                                val cellStartPx = cell.startMinutesFromWindow * dpPerMinutePx
                                val cellWidthPx = (cell.durationMinutes * dpPerMinutePx).coerceAtLeast(2f)
                                val cellEndPx   = cellStartPx + cellWidthPx

                                if (cellEndPx < scrollOffset - bufferPx ||
                                    cellStartPx > scrollOffset + viewportWidthPx + bufferPx) {
                                    return@forEachIndexed
                                }

                                val isSelected = isProgrammeAreaFocused && idx == selectedCellIdx
                                val bgColor = when {
                                    cell.event == null -> SkyDarkest
                                    isSelected         -> SkySelected
                                    cell.event.isLive  -> SkyHighlight
                                    else               -> SkyCellBg
                                }
                                drawRect(bgColor, Offset(cellStartPx, 0f), Size(cellWidthPx, size.height))

                                if (cell.event != null) {
                                    when {
                                        isSelected   -> {
                                            drawRect(CellFocusTint, Offset(cellStartPx, 0f), Size(cellWidthPx, size.height))
                                            drawRect(SkyText, Offset(cellStartPx, 0f), Size(cellWidthPx, size.height), style = Stroke(selStrokePx))
                                        }
                                        isRowFocused -> drawRect(CellRowFocusTint, Offset(cellStartPx, 0f), Size(cellWidthPx, size.height))
                                        else         -> drawLine(CellDivider, Offset(cellEndPx - 1f, 0f), Offset(cellEndPx - 1f, size.height), 1f)
                                    }

                                    val maxTextWidth = (cellWidthPx - hPadPx * 2f).coerceAtLeast(0f).toInt()
                                    if (maxTextWidth > 16) {
                                        val measured = textMeasurer.measure(
                                            text = cell.event.title,
                                            style = if (isSelected || isRowFocused) cellTextStyleNormal else cellTextStyleLight,
                                            constraints = Constraints(maxWidth = maxTextWidth),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        drawText(
                                            measured,
                                            topLeft = Offset(
                                                cellStartPx + hPadPx,
                                                (size.height - measured.size.height) / 2f
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelCell(
    channel: Channel,
    cellFont: Float,
    width: Dp,
    isRowFocused: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onSelected: () -> Unit,
    onMoveRight: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(SkyNavy)
            .drawWithContent {
                drawContent()
                if (isRowFocused) drawRect(ChannelFocusTint)
                drawLine(ChannelDivider, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 2f)
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (keyEvent.key) {
                    Key.DirectionRight -> { onMoveRight(); true }
                    Key.Enter, Key.DirectionCenter -> { onSelected(); true }
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusRequester(focusRequester)
            .clickable { onSelected() }
            .focusable()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = channel.name,
            color = SkyText,
            fontSize = cellFont.sp,
            fontFamily = InterFontFamily,
            fontWeight = if (isRowFocused) FontWeight.Normal else FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = channel.number.toString(),
            color = SkyTextDim,
            fontSize = cellFont.sp,
            fontFamily = InterFontFamily,
            fontWeight = if (isRowFocused) FontWeight.Normal else FontWeight.Light
        )
    }
}

private fun buildCells(
    events: List<EpgEvent>,
    windowStart: Long,
    windowEnd: Long
): List<EpgCell> {
    val cells  = mutableListOf<EpgCell>()
    var cursor = windowStart

    val relevant = events
        .filter { it.stop > windowStart && it.start < windowEnd }
        .sortedBy { it.start }

    for (event in relevant) {
        val effectiveStart = maxOf(event.start, windowStart)
        if (effectiveStart > cursor) {
            val gapMinutes = ((effectiveStart - cursor) / 60).toInt()
            cells.add(EpgCell(null, ((cursor - windowStart) / 60).toInt(), gapMinutes))
        }
        val effectiveStop    = minOf(event.stop, windowEnd)
        val durationMinutes  = ((effectiveStop - effectiveStart) / 60).toInt()
        if (durationMinutes > 0) {
            cells.add(EpgCell(event, ((effectiveStart - windowStart) / 60).toInt(), durationMinutes))
        }
        cursor = effectiveStop
    }

    if (cursor < windowEnd) {
        val trailingMinutes = ((windowEnd - cursor) / 60).toInt()
        cells.add(EpgCell(null, ((cursor - windowStart) / 60).toInt(), trailingMinutes))
    }

    return cells
}

private fun formatTime(unixSeconds: Long): String {
    val fmt = SimpleDateFormat("h:mma", Locale.getDefault())
    return fmt.format(Date(unixSeconds * 1000)).lowercase().replace(".", "")
}
