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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ie.owen.skyq.navigation.NavItem
import ie.owen.skyq.ui.guide.TvGuideScreen
import ie.owen.skyq.ui.home.HomeScreen
import ie.owen.skyq.ui.shell.AppShell
import ie.owen.skyq.ui.shell.SidebarBorderOverlay
import ie.owen.skyq.ui.theme.SkyQTheme
import ie.owen.skyq.ui.video.ChannelMeta
import ie.owen.skyq.ui.video.VideoOverlay
import ie.owen.skyq.ui.video.VideoViewModel

private val DPAD_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter
)

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
    val timeshiftState by videoViewModel.timeshiftController.state.collectAsStateWithLifecycle()
    var selectedNav     by remember { mutableStateOf(NavItem.GUIDE) }
    var previewBounds   by remember { mutableStateOf(Rect.Zero) }
    var isFullscreen    by remember { mutableStateOf(false) }
    var fullscreenMeta  by remember { mutableStateOf<ChannelMeta?>(null) }

    val density = LocalDensity.current
    val borderAlpha by animateFloatAsState(
        targetValue = if (isFullscreen) 0f else 1f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "borderAlpha"
    )

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { keyEvent ->
        // While fullscreen, eat all D-pad navigation so the EPG behind can't receive
        // focus-traversal events (which stall ExoPlayer via main-thread recompositions).
        // Up/Down will drive the mini-EPG overlay once that's built.
        isFullscreen && keyEvent.type == KeyEventType.KeyDown && keyEvent.key in DPAD_KEYS
    }) {
        AppShell(
            selectedNav = selectedNav,
            onNavSelect = { selectedNav = it },
            onPreviewBoundsChanged = { previewBounds = it },
            isFullscreen = isFullscreen
        ) {
            when (selectedNav) {
                NavItem.GUIDE -> TvGuideScreen(
                    onChannelSelected = { uuid, name, number, title, iconPath, startTime, stopTime, description ->
                        videoViewModel.setChannel(uuid)
                        fullscreenMeta = ChannelMeta(name, number, title, iconPath, startTime, stopTime, description)
                        isFullscreen = true
                    },
                    onPreviewChannelChanged = { uuid ->
                        videoViewModel.setChannel(uuid)
                    }
                )
                else -> HomeScreen()
            }
        }

        if (previewBounds.width > 0f) {
            VideoOverlay(
                player          = videoViewModel.player,
                isFullscreen    = isFullscreen,
                previewBounds   = previewBounds,
                meta            = if (isFullscreen) fullscreenMeta else null,
                timeshiftState  = timeshiftState,
                onTogglePause   = { videoViewModel.togglePause() },
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
