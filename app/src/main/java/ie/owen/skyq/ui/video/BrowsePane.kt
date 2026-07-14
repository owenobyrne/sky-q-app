package ie.owen.skyq.ui.video

import android.graphics.Outline
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ie.owen.skyq.data.api.TvHeadendClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PaneAccent = Color(0xFF2B52E8)

/** Preview UI sits directly on the blue wash: pushed down the screen and inset from the
 *  screen edge and the main video. */
private const val PANE_TOP_FRACTION  = 0.16f
private const val PANE_SIDE_FRACTION = 0.02f
/** Rounded corners to match the main video (no shadow — the preview sits flat on the wash). */
private val PreviewCornerRadius = 14.dp

/**
 * Left-hand "browse other channels" pane shown when the main video contracts. Renders a
 * muted live preview of [player] (its own surface, z-ordered above the main video) plus the
 * previewed channel's now-playing info, sitting directly on the blue wash (no container box).
 * Up/Down retunes the preview; Enter swaps it into the main; Left closes the pane — all
 * handled by the caller's key router.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowsePane(
    player: ExoPlayer,
    meta: ChannelMeta?,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val cornerRadiusPx = with(LocalDensity.current) { PreviewCornerRadius.toPx() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(
                top   = (config.screenHeightDp * PANE_TOP_FRACTION).dp,
                start = (config.screenWidthDp  * PANE_SIDE_FRACTION).dp,
                end   = (config.screenWidthDp  * PANE_SIDE_FRACTION).dp
            )
    ) {
        // Live preview video — the padded pane width is already 16:9, so it fills cleanly.
        // Rounded to match the main video; no shadow, so it stays flat on the blue wash.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(PreviewCornerRadius))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            PreviewSurface(player, cornerRadiusPx, Modifier.fillMaxSize())

            var buffering by remember { mutableStateOf(true) }
            DisposableEffect(player) {
                val l = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                    }
                    override fun onRenderedFirstFrame() { buffering = false }
                }
                player.addListener(l)
                onDispose { player.removeListener(l) }
            }
            if (buffering) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("…", color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp)
                }
            }
        }

        // Now-playing info for the previewed channel, sitting directly on the blue wash.
        Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            if (meta == null) {
                Text("Browsing…", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.iconPath.isNotEmpty()) {
                    AsyncImage(
                        model = TvHeadendClient.resolveUrl(meta.iconPath),
                        contentDescription = meta.name,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(meta.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (meta.number.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(meta.number, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }

            if (meta.title.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    meta.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (meta.startTime != null && meta.stopTime != null) {
                Spacer(Modifier.height(12.dp))
                val now = System.currentTimeMillis() / 1000L
                val progress = ((now - meta.startTime).toFloat() /
                        (meta.stopTime - meta.startTime).toFloat()).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(PaneAccent))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(meta.startTime), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                    Text(formatTime(meta.stopTime),  color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                }
            }

            if (!meta.description.isNullOrEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    meta.description,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }

            Spacer(Modifier.weight(1f))
            Text(
                "▲ ▼ browse   •   OK swap   •   ◀ close",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun PreviewSurface(player: ExoPlayer, cornerRadiusPx: Float, modifier: Modifier = Modifier) {
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
                    // Sit above the main video SurfaceView (which is behind the window).
                    setZOrderMediaOverlay(true)
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

private fun formatTime(unixSeconds: Long): String =
    SimpleDateFormat("h:mma", Locale.getDefault()).format(Date(unixSeconds * 1000))
        .lowercase().replace(".", "")
