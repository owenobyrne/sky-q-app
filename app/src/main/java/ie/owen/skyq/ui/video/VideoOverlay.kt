package ie.owen.skyq.ui.video

import android.app.Activity
import android.graphics.Outline
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import ie.owen.skyq.ui.theme.AppFontFamily
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

/** Fraction of the screen width taken by the left browse pane (main video keeps the rest). */
const val BROWSE_PANE_FRACTION = 1f / 3f
/** In browse mode the main video pulls in this fraction of the screen width from the right edge,
 *  so the blue wash peeks behind it. */
private const val BROWSE_MAIN_RIGHT_INSET = 0.01f

// Blue wash shown behind the browse pane — matches the AppShell / EPG background gradient.
private val BrowseWash = Brush.horizontalGradient(0f to Color(0xFF011799), 1f to Color(0xFF0051FB))

// While browsing, the (shrunken) main video gets rounded corners and a soft drop shadow.
private val BrowseCornerRadius = 14.dp
private val BrowseElevation    = 12.dp

@Composable
fun VideoOverlay(
    player: ExoPlayer,
    isFullscreen: Boolean,
    previewBounds: Rect,
    meta: ChannelMeta?,
    osdTrigger: Int = 0,
    browseOpen: Boolean = false,
    browseContent: @Composable BoxScope.() -> Unit = {},
    onBack: () -> Unit
) {
    val config  = LocalConfiguration.current
    val density = LocalDensity.current
    val screenW = with(density) { config.screenWidthDp.dp.roundToPx().toFloat() }
    val screenH = with(density) { config.screenHeightDp.dp.roundToPx().toFloat() }

    val videoSpec = tween<Float>(durationMillis = 380, easing = FastOutSlowInEasing)
    val scrimSpec = tween<Float>(durationMillis = 180)

    // When the browse pane is open, the main video contracts to the right, pulling in a
    // little from the right edge so the blue wash shows behind it.
    val fsLeft  = if (browseOpen) screenW * BROWSE_PANE_FRACTION else 0f
    val fsRight = if (browseOpen) screenW * (1f - BROWSE_MAIN_RIGHT_INSET) else screenW
    val left   by animateFloatAsState(if (isFullscreen) fsLeft else previewBounds.left,  videoSpec, label = "vL")
    val top    by animateFloatAsState(if (isFullscreen) 0f else previewBounds.top,   videoSpec, label = "vT")
    val right  by animateFloatAsState(if (isFullscreen) fsRight else previewBounds.right,  videoSpec, label = "vR")
    val bottom by animateFloatAsState(if (isFullscreen) screenH else previewBounds.bottom, videoSpec, label = "vB")
    // Two full-screen scrims that hide the EPG behind the fullscreen video: black normally,
    // and the blue wash while browsing (so the browse pane sits on the EPG-style background).
    // The blue base is always present under fullscreen; the black scrim fades away to reveal it.
    val blueBaseAlpha  by animateFloatAsState(if (isFullscreen) 1f else 0f, scrimSpec, label = "blueBase")
    val blackScrimAlpha by animateFloatAsState(if (isFullscreen && !browseOpen) 1f else 0f, scrimSpec, label = "blackScrim")

    // Rounded corners + drop shadow only while the main video is shrunken for browsing.
    val cornerRadius by animateDpAsState(if (browseOpen) BrowseCornerRadius else 0.dp, tween(380, easing = FastOutSlowInEasing), label = "corner")
    val elevation    by animateDpAsState(if (browseOpen) BrowseElevation else 0.dp, tween(380, easing = FastOutSlowInEasing), label = "elev")
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    val widthDp  = with(density) { (right - left).toDp() }
    val heightDp = with(density) { (bottom - top).toDp() }
    val leftDp   = with(density) { left.toDp() }
    val topDp    = with(density) { top.toDp() }

    // Bottom edge of the letterboxed 16:9 video within the (full-height) box — the caption
    // shown while browsing hangs just below this.
    val videoBottomPx = top + ((bottom - top) + (right - left) * 9f / 16f) / 2f
    val videoBottomDp = with(density) { videoBottomPx.toDp() }

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

    // Re-runs on fullscreen entry and on every channel hop (osdTrigger) so the info
    // overlay flashes up again each time the channel changes.
    var osdVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isFullscreen, osdTrigger) {
        if (isFullscreen) {
            delay(300)
            osdVisible = true
            delay(8000)
            osdVisible = false
        } else {
            osdVisible = false
        }
    }

    val osdGradientHeight = (config.screenHeightDp * 0.55f).dp
    val osdHPadding       = (config.screenWidthDp  * 0.045f).dp
    val osdBottomPadding  = (config.screenHeightDp * 0.06f).dp

    Box(Modifier.fillMaxSize()) {
        // Blue wash base — the EPG-style background the browse pane sits on.
        if (blueBaseAlpha > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = blueBaseAlpha }.background(BrowseWash))
        }
        // Black scrim over the wash — fades in so AppShell disappears behind the growing
        // video, and fades away (revealing the blue wash) while browsing.
        if (blackScrimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blackScrimAlpha)))
        }

        // Browse pane — sits on the blue wash, behind the main video.
        browseContent()

        Box(
            modifier = Modifier
                .absoluteOffset(leftDp, topDp)
                .size(widthDp, heightDp),
            // No background: the video letterbox area stays transparent so the blue wash
            // shows through the bars above/below the (16:9) video while browsing.
            contentAlignment = Alignment.Center
        ) {
            // The 16:9 video itself — rounded + drop-shadowed while browsing. The black
            // background makes the shadow render and is fully covered by the video surface.
            Box(
                modifier = Modifier
                    .aspectRatio(16f / 9f)
                    .shadow(elevation, RoundedCornerShape(cornerRadius))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                VideoSurface(player, cornerRadiusPx, Modifier.fillMaxSize())

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
        }

        // Subtle caption under the main video while browsing: logo · channel · programme,
        // right-aligned to the video with a little right padding.
        if (browseOpen && meta != null) {
            Box(
                modifier = Modifier
                    .absoluteOffset(leftDp, videoBottomDp)
                    .width(widthDp)
                    .padding(top = 12.dp, end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (meta.iconPath.isNotEmpty()) {
                        AsyncImage(
                            model = TvHeadendClient.resolveUrl(meta.iconPath),
                            contentDescription = meta.name,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        meta.name,
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (meta.title.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            meta.title,
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (isFullscreen && !browseOpen && meta != null) {
            AnimatedVisibility(
                visible = osdVisible,
                enter = fadeIn(tween(300)),
                exit  = fadeOut(tween(600)),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                // Translucent gradient (transparent → black) at the bottom of the screen;
                // the now-playing info sits on it directly, no box.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(osdGradientHeight)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.88f)
                            )
                        ),
                    contentAlignment = Alignment.BottomStart
                ) {
                    ChannelOsd(
                        meta = meta,
                        modifier = Modifier.padding(
                            start = osdHPadding, end = osdHPadding, bottom = osdBottomPadding
                        )
                    )
                }
            }
        }
    }
}

/**
 * Renders [player] into an Amlogic SurfaceView (hole-punch) or a TextureView elsewhere,
 * rounding the corners via [cornerRadiusPx] with a ViewOutline (a Compose clip can't round a
 * hole-punch surface). [cornerRadiusPx] is held in an array the outline provider reads, so
 * animating the radius just needs invalidateOutline() on each update.
 */
@Composable
private fun VideoSurface(player: ExoPlayer, cornerRadiusPx: Float, modifier: Modifier = Modifier) {
    val radius = remember { floatArrayOf(0f) }
    radius[0] = cornerRadiusPx
    val outline = remember {
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) {
                o.setRoundRect(0, 0, view.width, view.height, radius[0])
            }
        }
    }
    if (isAmlogicDevice) {
        val holder = remember { arrayOfNulls<SurfaceView>(1) }
        DisposableEffect(player) { onDispose { holder[0]?.let { player.clearVideoSurfaceView(it) } } }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    outlineProvider = outline
                    clipToOutline = true
                    holder[0] = this
                    player.setVideoSurfaceView(this)
                }
            },
            update = { sv ->
                if (holder[0] !== sv) { holder[0] = sv; player.setVideoSurfaceView(sv) }
                sv.invalidateOutline()
            },
            modifier = modifier
        )
    } else {
        val holder = remember { arrayOfNulls<TextureView>(1) }
        DisposableEffect(player) { onDispose { holder[0]?.let { player.clearVideoTextureView(it) } } }
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    outlineProvider = outline
                    clipToOutline = true
                    holder[0] = this
                    player.setVideoTextureView(this)
                }
            },
            update = { tv ->
                if (holder[0] !== tv) { holder[0] = tv; player.setVideoTextureView(tv) }
                tv.invalidateOutline()
            },
            modifier = modifier
        )
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
private fun ChannelOsd(meta: ChannelMeta, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Channel row: icon + name + number
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (meta.iconPath.isNotEmpty()) {
                AsyncImage(
                    model = TvHeadendClient.resolveUrl(meta.iconPath),
                    contentDescription = meta.name,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(meta.name, color = Color.White, fontSize = 17.sp, fontFamily = AppFontFamily, fontWeight = FontWeight.Medium)
            if (meta.number.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = meta.number,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    fontFamily = AppFontFamily
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Programme title — large & light
        if (meta.title.isNotEmpty()) {
            Text(
                text = meta.title,
                color = Color.White,
                fontSize = 44.sp,
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(12.dp))

        // Description
        if (!meta.description.isNullOrEmpty()) {
            Text(
                text = meta.description,
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 15.sp,
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Light,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 21.sp
            )
        }

        // Progress bar + start/end times
        if (meta.startTime != null && meta.stopTime != null) {
            Spacer(Modifier.height(18.dp))
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
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.width(14.dp))
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
                Spacer(Modifier.width(14.dp))
                Text(
                    text = formatOsdTime(meta.stopTime),
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp,
                    fontFamily = AppFontFamily,
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
