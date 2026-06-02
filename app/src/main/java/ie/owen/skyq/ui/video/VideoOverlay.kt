package ie.owen.skyq.ui.video

import android.app.Activity
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ie.owen.skyq.data.api.TvHeadendClient
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

    // Keep the screen awake (and suppress the Google TV screensaver/daydream)
    // while a video is playing fullscreen.
    val context = LocalContext.current
    DisposableEffect(isFullscreen) {
        val window = (context as? Activity)?.window
        if (isFullscreen) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

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
    var firstFrameRendered by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_IDLE) firstFrameRendered = false
            }
            override fun onRenderedFirstFrame() { firstFrameRendered = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Fallback: if STATE_READY but no first frame after 2s, stop blocking (e.g. audio-only channel)
    LaunchedEffect(playbackState) {
        if (playbackState == Player.STATE_READY && !firstFrameRendered) {
            delay(5000)
            firstFrameRendered = true
        }
    }
    val isLoading = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE ||
                    !firstFrameRendered

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
            // SurfaceView lets the Amlogic decoder hand frames directly to SurfaceFlinger,
            // avoiding the GPU/dmabuf copy path that TextureView requires (and that SELinux
            // denies on this device), which was causing the video output buffer pool to fill
            // and the decoder to stall while audio kept playing fine.
            val svHolder = remember { arrayOfNulls<SurfaceView>(1) }
            DisposableEffect(player) {
                onDispose { svHolder[0]?.let { player.clearVideoSurfaceView(it) } }
            }

            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        svHolder[0] = this
                        player.setVideoSurfaceView(this)
                    }
                },
                update = { sv ->
                    if (svHolder[0] !== sv) {
                        svHolder[0] = sv
                        player.setVideoSurfaceView(sv)
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
                    modifier = Modifier.fillMaxSize().background(Color.Black),
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
            ) {
                Box(Modifier.fillMaxWidth().height(osdHeight)) { ChannelOsd(meta) }
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

private fun formatOsdTime(unixSeconds: Long): String {
    val fmt = SimpleDateFormat("h:mma", Locale.getDefault())
    return fmt.format(Date(unixSeconds * 1000)).lowercase().replace(".", "")
}
