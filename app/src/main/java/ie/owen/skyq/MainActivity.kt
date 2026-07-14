package ie.owen.skyq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.data.settings.AppSettings
import ie.owen.skyq.navigation.NavDestination
import ie.owen.skyq.ui.guide.TvGuideScreen
import ie.owen.skyq.ui.guide.TvGuideViewModel
import ie.owen.skyq.ui.home.HomeScreen
import ie.owen.skyq.ui.settings.SettingsScreen
import ie.owen.skyq.ui.shell.AppShell
import ie.owen.skyq.ui.shell.SidebarBorderOverlay
import ie.owen.skyq.ui.theme.SkyQTheme
import ie.owen.skyq.ui.video.ChannelMeta
import ie.owen.skyq.ui.video.VideoOverlay
import ie.owen.skyq.ui.video.VideoViewModel

private val DPAD_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter
)

/** Builds the fullscreen OSD metadata for a channel from its currently-airing EPG event. */
private fun channelMeta(channel: Channel, eventsByChannel: Map<String, List<EpgEvent>>): ChannelMeta {
    val live = eventsByChannel[channel.uuid]?.firstOrNull { it.isLive }
    return ChannelMeta(
        name        = channel.name,
        number      = channel.number.toString(),
        title       = live?.title ?: "",
        iconPath    = live?.channelIcon ?: channel.iconPublicUrl ?: "",
        startTime   = live?.start,
        stopTime    = live?.stop,
        description = live?.description ?: live?.summary ?: live?.subtitle
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SkyQTheme {
                SkyQApp()
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun SkyQApp() {
    val videoViewModel: VideoViewModel = viewModel()
    val guideViewModel: TvGuideViewModel = viewModel()
    var selectedNav     by remember { mutableStateOf<NavDestination>(NavDestination.Guide) }
    var previewBounds   by remember { mutableStateOf(Rect.Zero) }
    var isFullscreen    by remember { mutableStateOf(false) }
    var fullscreenMeta  by remember { mutableStateOf<ChannelMeta?>(null) }
    var fullscreenUuid  by remember { mutableStateOf<String?>(null) }
    var osdTrigger      by remember { mutableIntStateOf(0) }
    val guideState      by guideViewModel.state.collectAsStateWithLifecycle()

    // Up/Down while fullscreen: hop to the previous/next channel in the (tag-filtered)
    // guide list, wrapping at the ends. Retunes, refreshes the info OSD from the new
    // channel's live event, and persists the choice as the last channel.
    fun stepChannel(delta: Int) {
        val chans = guideState.channels
        if (chans.isEmpty()) return
        val curIdx  = chans.indexOfFirst { it.uuid == fullscreenUuid }
        val nextIdx = if (curIdx < 0) 0 else (curIdx + delta + chans.size) % chans.size
        val ch = chans[nextIdx]
        fullscreenUuid = ch.uuid
        fullscreenMeta = channelMeta(ch, guideState.eventsByChannel)
        AppSettings.setLastChannel(ch.uuid)
        videoViewModel.setChannel(ch.uuid)
        osdTrigger++
    }

    val density = LocalDensity.current
    val borderAlpha by animateFloatAsState(
        targetValue = if (isFullscreen) 0f else 1f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "borderAlpha"
    )

    // Auto-tune to the last channel on startup (starts the sidebar preview).
    LaunchedEffect(Unit) {
        AppSettings.lastChannelUuid?.let { videoViewModel.setChannel(it) }
    }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { keyEvent ->
        // While fullscreen, Up/Down change the channel; every other D-pad key is eaten so
        // the EPG behind can't receive focus-traversal events (which stall ExoPlayer via
        // main-thread recompositions).
        if (isFullscreen && keyEvent.type == KeyEventType.KeyDown) {
            when (keyEvent.key) {
                Key.DirectionUp   -> { stepChannel(-1); true }
                Key.DirectionDown -> { stepChannel(+1); true }
                in DPAD_KEYS      -> true
                else              -> false
            }
        } else false
    }) {
        AppShell(
            selectedNav = selectedNav,
            onNavSelect = { dest ->
                selectedNav = dest
                when (dest) {
                    is NavDestination.Tag   -> guideViewModel.setTagFilter(dest.uuid)
                    is NavDestination.Guide -> guideViewModel.setTagFilter(null)
                    else                    -> Unit
                }
            },
            tags = guideState.tags,
            onPreviewBoundsChanged = { previewBounds = it },
            isFullscreen = isFullscreen
        ) {
            when (val nav = selectedNav) {
                is NavDestination.Guide,
                is NavDestination.Tag -> TvGuideScreen(
                    viewModel = guideViewModel,
                    onChannelSelected = { uuid, name, number, title, iconPath, startTime, stopTime, description ->
                        AppSettings.setLastChannel(uuid)
                        videoViewModel.setChannel(uuid)
                        fullscreenMeta = ChannelMeta(name, number, title, iconPath, startTime, stopTime, description)
                        fullscreenUuid = uuid
                        isFullscreen = true
                    },
                    onPreviewChannelChanged = { uuid ->
                        videoViewModel.setChannel(uuid)
                    }
                )
                is NavDestination.Settings -> SettingsScreen()
                else -> HomeScreen()
            }
        }

        if (previewBounds.width > 0f) {
            VideoOverlay(
                player          = videoViewModel.player,
                isFullscreen    = isFullscreen,
                previewBounds   = previewBounds,
                meta            = if (isFullscreen) fullscreenMeta else null,
                osdTrigger      = osdTrigger,
                onBack          = { isFullscreen = false }
            )

            if (borderAlpha > 0f) {
                val leftDp  = with(density) { previewBounds.left.toDp() }
                val topDp   = with(density) { previewBounds.top.toDp() }
                val widthDp = with(density) { previewBounds.width.toDp() }
                val heightDp = with(density) { previewBounds.height.toDp() }
                SidebarBorderOverlay(
                    modifier = Modifier
                        .absoluteOffset(leftDp, topDp)
                        .size(widthDp, heightDp)
                        .graphicsLayer { alpha = borderAlpha }
                )
            }
        }
    }
}
