package ie.owen.skyq.ui.video

import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.htsp.TimeshiftState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChannelMeta(
    val name: String,
    val number: String,
    val title: String,
    val iconPath: String,
    val startTime: Long? = null,
    val stopTime: Long? = null,
    val description: String? = null
)

private val OsdShape      = RoundedCornerShape(8.dp)
private val OsdBackground = Color(0xCC1E2020)
private val OsdBorder     = Color(0x55CCCCCC)

@Composable
fun VideoOverlay(
    player: ExoPlayer,
    isFullscreen: Boolean,
    previewBounds: Rect,
    meta: ChannelMeta?,
    timeshiftState: TimeshiftState = TimeshiftState(),
    onTogglePause: () -> Unit = {},
    onBack: () -> Unit
) {
    val config  = LocalConfiguration.current
    val density = LocalDensity.current
    val screenW = with(density) { config.screenWidthDp.dp.roundToPx().toFloat() }
    val screenH = with(density) { config.screenHeightDp.dp.roundToPx().toFloat() }

    val videoSpec = tween<Float>(durationMillis = 380, easing = FastOutSlowInEasing)
    val scrimSpec = tween<Float>(durationMillis = 180)

    val left   by animateFloatAsState(if (isFullscreen) 0f else previewBounds.left,  videoSpec, label = "vL")
    val top    by animateFloatAsState(if (isFullscreen) 0f else previewBounds.top,   videoSpec, label = "vT")
    val right  by animateFloatAsState(if (isFullscreen) screenW else previewBounds.right,  videoSpec, label = "vR")
    val bottom by animateFloatAsState(if (isFullscreen) screenH else previewBounds.bottom, videoSpec, label = "vB")
    val scrimAlpha by animateFloatAsState(if (isFullscreen) 1f else 0f, scrimSpec, label = "scrim")

    val widthDp  = with(density) { (right - left).toDp() }
    val heightDp = with(density) { (bottom - top).toDp() }
    val leftDp   = with(density) { left.toDp() }
    val topDp    = with(density) { top.toDp() }

    BackHandler(enabled = isFullscreen, onBack = onBack)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP  -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var playbackState by remember { mutableIntStateOf(player.playbackState) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { playbackState = state }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val isLoading = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE

    // Sync ExoPlayer paused state with HTSP controller
    LaunchedEffect(timeshiftState.isPaused) {
        if (timeshiftState.isPaused) player.pause() else player.play()
    }

    var osdVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            delay(300)
            osdVisible = true
            delay(8000)
            osdVisible = false
        } else {
            osdVisible = false
        }
    }
    // Keep OSD visible while paused
    LaunchedEffect(timeshiftState.isPaused) {
        if (timeshiftState.isPaused) osdVisible = true
    }

    val hMargin    = (config.screenWidthDp  * 0.10f).dp
    val osdHeight  = (config.screenHeightDp * 0.40f).dp
    val osdVMargin = (config.screenHeightDp * 0.08f).dp

    Box(Modifier.fillMaxSize()) {
        // Scrim — fades in quickly so AppShell disappears behind the growing video
        if (scrimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
        }

        Box(
            modifier = Modifier
                .absoluteOffset(leftDp, topDp)
                .size(widthDp, heightDp)
                .background(Color.Black)
        ) {
            // Hold a reference so we can detach the surface on dispose
            val tvHolder = remember { arrayOfNulls<TextureView>(1) }
            DisposableEffect(player) {
                onDispose { tvHolder[0]?.let { player.clearVideoTextureView(it) } }
            }

            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        tvHolder[0] = this
                        player.setVideoTextureView(this)
                    }
                },
                update = { tv ->
                    if (tvHolder[0] !== tv) {
                        tvHolder[0] = tv
                        player.setVideoTextureView(tv)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(tween(150)),
                exit  = fadeOut(tween(400)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingSpinner()
                }
            }
        }

        if (isFullscreen && meta != null) {
            AnimatedVisibility(
                visible = osdVisible,
                enter = fadeIn(tween(300)),
                exit  = fadeOut(tween(600)),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(start = hMargin, end = hMargin, bottom = osdVMargin)
                    .onKeyEvent { ev ->
                        // OK / centre D-pad toggles pause while OSD is showing
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionCenter) {
                            onTogglePause(); true
                        } else false
                    }
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(osdHeight)) { ChannelOsd(meta) }
                    Spacer(Modifier.height(8.dp))
                    TimeshiftBar(
                        state = timeshiftState,
                        onTogglePause = onTogglePause
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingSpinner() {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle"
    )
    Canvas(Modifier.size(32.dp)) {
        drawArc(
            color = Color.White.copy(alpha = 0.85f),
            startAngle = angle,
            sweepAngle = 260f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelOsd(meta: ChannelMeta) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OsdBackground, OsdShape)
            .border(1.5.dp, OsdBorder, OsdShape)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Channel row: icon + name + number
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (meta.iconPath.isNotEmpty()) {
                AsyncImage(
                    model = TvHeadendClient.resolveUrl(meta.iconPath),
                    contentDescription = meta.name,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(meta.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (meta.number.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = meta.number,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Programme title
        if (meta.title.isNotEmpty()) {
            Text(
                text = meta.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))

        // Description
        if (!meta.description.isNullOrEmpty()) {
            Text(
                text = meta.description,
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                maxLines = 7,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
        }

        Spacer(Modifier.weight(1f))

        // Progress bar + start/end times
        if (meta.startTime != null && meta.stopTime != null) {
            val now = System.currentTimeMillis() / 1000L
            val progress = ((now - meta.startTime).toFloat() /
                    (meta.stopTime - meta.startTime).toFloat()).coerceIn(0f, 1f)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatOsdTime(meta.startTime),
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.20f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Color.White.copy(alpha = 0.75f))
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = formatOsdTime(meta.stopTime),
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimeshiftBar(
    state: TimeshiftState,
    onTogglePause: () -> Unit
) {
    val behindSec = state.timeBehindLiveSec
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OsdBackground, OsdShape)
            .border(1.5.dp, OsdBorder, OsdShape)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pause / play indicator — tappable on TV remote via key event on parent
        Text(
            text = if (state.isPaused) "⏸" else "▶",
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.padding(end = 16.dp)
        )

        if (behindSec > 0) {
            // Time-behind-live label
            val mins = behindSec / 60
            val secs = behindSec % 60
            Text(
                text = "-%02d:%02d".format(mins, secs),
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(end = 16.dp)
            )

            // Progress bar: position within timeshift buffer (rough — based on time only)
            val maxBuf = 7200f   // 2-hour buffer configured in HtspDataSource
            val progress = (1f - (behindSec / maxBuf).toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.20f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(Color.White.copy(alpha = 0.70f))
                )
            }
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

        // LIVE badge
        Box(
            modifier = Modifier
                .background(
                    if (state.isAtLive) Color(0xFFCC0000) else Color.White.copy(alpha = 0.15f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "● LIVE",
                color = if (state.isAtLive) Color.White else Color.White.copy(alpha = 0.50f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatOsdTime(unixSeconds: Long): String {
    val fmt = SimpleDateFormat("h:mma", Locale.getDefault())
    return fmt.format(Date(unixSeconds * 1000)).lowercase().replace(".", "")
}
