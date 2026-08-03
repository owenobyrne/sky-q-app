package ie.owen.skyq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.viewmodel.compose.viewModel
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.data.settings.AppSettings
import ie.owen.skyq.navigation.NavDestination
import ie.owen.skyq.ui.guide.TvGuideScreen
import ie.owen.skyq.ui.guide.TvGuideViewModel
import ie.owen.skyq.ui.settings.SettingsScreen
import ie.owen.skyq.ui.theme.SkyQTheme
import ie.owen.skyq.ui.video.BROWSE_PANE_FRACTION
import ie.owen.skyq.ui.video.BrowsePane
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
    var browseOpen      by remember { mutableStateOf(false) }
    var browseUuid      by remember { mutableStateOf<String?>(null) }
    var browseMeta      by remember { mutableStateOf<ChannelMeta?>(null) }

    // The guide state is read imperatively from the key handlers below rather than collected
    // into Compose state. Collecting it here would recompose this entire tree — guide,
    // VideoOverlay and all — on every progressive EPG emission, which is exactly when the
    // user is trying to navigate.
    fun guideState() = guideViewModel.state.value

    // Up/Down while fullscreen: hop to the previous/next channel in the (tag-filtered)
    // guide list, wrapping at the ends. Retunes, refreshes the info OSD from the new
    // channel's live event, and persists the choice as the last channel.
    fun stepChannel(delta: Int) {
        val state = guideState()
        val chans = state.channels
        if (chans.isEmpty()) return
        val curIdx  = chans.indexOfFirst { it.uuid == fullscreenUuid }
        val nextIdx = if (curIdx < 0) 0 else (curIdx + delta + chans.size) % chans.size
        val ch = chans[nextIdx]
        fullscreenUuid = ch.uuid
        fullscreenMeta = channelMeta(ch, state.eventsByChannel)
        AppSettings.setLastChannel(ch.uuid)
        videoViewModel.setChannel(ch.uuid)
        osdTrigger++
    }

    // ── Browse pane (Right in fullscreen): peek at other channels in a live preview
    // on the left while the main video keeps playing on the right. ──────────────
    fun previewBrowse(uuid: String, ch: Channel) {
        browseUuid = uuid
        browseMeta = channelMeta(ch, guideState().eventsByChannel)
        videoViewModel.setPreviewChannel(uuid)
    }
    fun openBrowse() {
        val chans = guideState().channels
        if (chans.isEmpty()) return
        val curIdx   = chans.indexOfFirst { it.uuid == fullscreenUuid }
        val startIdx = if (curIdx < 0) 0 else (curIdx + 1) % chans.size
        previewBrowse(chans[startIdx].uuid, chans[startIdx])
        browseOpen = true
    }
    fun stepBrowse(delta: Int) {
        val chans = guideState().channels
        if (chans.isEmpty()) return
        val curIdx  = chans.indexOfFirst { it.uuid == browseUuid }
        val nextIdx = if (curIdx < 0) 0 else (curIdx + delta + chans.size) % chans.size
        previewBrowse(chans[nextIdx].uuid, chans[nextIdx])
    }
    fun closeBrowse() {
        browseOpen = false
        videoViewModel.stopPreview()
    }
    // Enter on the preview: swap the two channels but stay in split view — the browsed
    // channel becomes the main (right, with audio) and the old main drops into the
    // preview (left). The main [player] always holds the audio, so no muting is needed.
    fun swapBrowse() {
        val newMain    = browseUuid ?: return
        val newPreview = fullscreenUuid
        val newMainMeta    = browseMeta
        val newPreviewMeta = fullscreenMeta
        fullscreenUuid = newMain
        fullscreenMeta = newMainMeta
        browseUuid = newPreview
        browseMeta = newPreviewMeta
        AppSettings.setLastChannel(newMain)
        videoViewModel.setChannel(newMain)
        newPreview?.let { videoViewModel.setPreviewChannel(it) }
    }

    // Auto-tune to the last channel on startup (starts the preview). Settings load off the
    // main thread, so wait for them rather than reading a not-yet-populated value.
    LaunchedEffect(Unit) {
        AppSettings.awaitReady()
        AppSettings.lastChannelUuid?.let { videoViewModel.setChannel(it) }
    }

    Box(Modifier
        .fillMaxSize()
        .background(Brush.horizontalGradient(0f to Color(0xFF011799), 1f to Color(0xFF0051FB)))
        .onPreviewKeyEvent { keyEvent ->
        // While fullscreen, the D-pad drives playback (not the EPG behind, whose focus
        // traversal stalls ExoPlayer via main-thread recompositions), so all of it is eaten.
        if (isFullscreen && keyEvent.type == KeyEventType.KeyDown) {
            if (browseOpen) {
                // Browse pane: Up/Down change the previewed channel, OK swaps it into the
                // main (staying in split view), Left closes.
                when (keyEvent.key) {
                    Key.DirectionUp                 -> { stepBrowse(-1); true }
                    Key.DirectionDown               -> { stepBrowse(+1); true }
                    Key.DirectionCenter, Key.Enter  -> { swapBrowse(); true }
                    Key.DirectionLeft, Key.Back     -> { closeBrowse(); true }
                    in DPAD_KEYS                    -> true
                    else                            -> false
                }
            } else {
                // Plain fullscreen: Right opens the browse pane, Up/Down zap channels.
                when (keyEvent.key) {
                    Key.DirectionRight -> { openBrowse(); true }
                    Key.DirectionUp    -> { stepChannel(-1); true }
                    Key.DirectionDown  -> { stepChannel(+1); true }
                    in DPAD_KEYS       -> true
                    else               -> false
                }
            }
        } else false
    }) {
        val onGuide = selectedNav is NavDestination.Guide

        when (selectedNav) {
            is NavDestination.Guide -> TvGuideScreen(
                viewModel = guideViewModel,
                onChannelSelected = { uuid, name, number, title, iconPath, startTime, stopTime, description ->
                    AppSettings.setLastChannel(uuid)
                    videoViewModel.setChannel(uuid)
                    fullscreenMeta = ChannelMeta(name, number, title, iconPath, startTime, stopTime, description)
                    fullscreenUuid = uuid
                    isFullscreen = true
                },
                onPreviewChannelChanged = { uuid -> videoViewModel.setChannel(uuid) },
                onPreviewBoundsChanged = { previewBounds = it },
                onOpenSettings = { selectedNav = NavDestination.Settings }
            )
            is NavDestination.Settings -> SettingsScreen()
        }

        // Back out of Settings returns to the guide.
        BackHandler(enabled = !onGuide) { selectedNav = NavDestination.Guide }

        // Preview / fullscreen video — only over the guide.
        if (onGuide && previewBounds.width > 0f) {
            VideoOverlay(
                player          = videoViewModel.player,
                isFullscreen    = isFullscreen,
                previewBounds   = previewBounds,
                meta            = if (isFullscreen) fullscreenMeta else null,
                osdTrigger      = osdTrigger,
                browseOpen      = browseOpen,
                // Browse pane, rendered behind the main video on the blue wash.
                browseContent   = {
                    if (browseOpen) {
                        BrowsePane(
                            player = videoViewModel.previewPlayer,
                            meta   = browseMeta,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth(BROWSE_PANE_FRACTION)
                        )
                    }
                },
                onBack          = { isFullscreen = false }
            )
            BackHandler(enabled = browseOpen) { closeBrowse() }
        }
    }
}
