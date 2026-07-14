package ie.owen.skyq.ui.video

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.htsp.*
import ie.owen.skyq.data.settings.AppSettings
import ie.owen.skyq.data.settings.StreamingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG                 = "VideoVM"
private const val PREVIEW_DEBOUNCE_MS = 400L

@UnstableApi
class VideoViewModel(application: Application) : AndroidViewModel(application) {

    // Channel ID lookup: uuid (HTTP API) → numeric HTSP channel ID
    private val channelIdMap = mutableMapOf<String, Long>()

    // Recreated lazily each access so settings changes take effect on next channel switch
    private val htspFactory: HtspDataSource.Factory
        get() = HtspDataSource.Factory(
            AppSettings.serverHost,
            AppSettings.serverPort + 1,   // HTSP is always HTTP port + 1
            AppSettings.username,
            AppSettings.password
        )

    /** Exposed so the UI can read isPaused / timeBehindLiveSec. */
    val timeshiftController: HtspController get() = htspFactory.controller

    private val htspSourceFactory =
        ProgressiveMediaSource.Factory(htspFactory, DefaultExtractorsFactory())
    private val hlsSourceFactory =
        HlsMediaSource.Factory(OkHttpDataSource.Factory(TvHeadendClient.authenticatedOkHttpClient()))
    // Progressive MPEG-TS source for the low-res browse preview (webtv-h264-aac-mpegts).
    private val previewSourceFactory =
        ProgressiveMediaSource.Factory(
            OkHttpDataSource.Factory(TvHeadendClient.authenticatedOkHttpClient()),
            DefaultExtractorsFactory()
        )

    val player: ExoPlayer = ExoPlayer.Builder(
        application,
        if (isAmlogicDevice) AmlogicRenderersFactory(application)
        else DefaultRenderersFactory(application)
    ).build()

    // Second, muted player driving the left-hand browse-preview pane. Audio always
    // comes from the main [player]; this one only ever renders video. Verified on the
    // Amlogic Chromecast HD to decode concurrently with the main stream.
    val previewPlayer: ExoPlayer = ExoPlayer.Builder(
        application,
        if (isAmlogicDevice) AmlogicRenderersFactory(application)
        else DefaultRenderersFactory(application)
    ).build().apply { volume = 0f }

    // UUID of the channel currently loaded (to avoid redundant restarts)
    private var activeUuid: String? = null
    private var pendingUuid: String? = null
    private var channelJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "player error: ${error.errorCodeName} — ${error.message}", error)
            }
        })
        previewPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "preview player error: ${error.errorCodeName} — ${error.message}", error)
            }
        })
        loadChannelIds()

        // Re-tune the current channel when the streaming mode changes (drop the
        // initial value — nothing is playing yet at construction).
        viewModelScope.launch {
            AppSettings.streamingMode.drop(1).collect {
                val uuid = activeUuid ?: return@collect
                activeUuid = null
                setChannel(uuid)
            }
        }
    }

    // ── Channel ID pre-fetch ──────────────────────────────────────────────────

    private fun loadChannelIds() {
        viewModelScope.launch(Dispatchers.IO) {
            val conn = HtspConnection(AppSettings.serverHost, AppSettings.serverPort + 1, AppSettings.username, AppSettings.password)
            try {
                if (!conn.connect()) { Log.w(TAG, "channel-ID fetch: connect failed"); return@launch }

                // Subscribe to events BEFORE sending enableAsyncMetadata so no channelAdd is missed.
                // onSubscription fires when the collector is actually active.
                val subscribed = CompletableDeferred<Unit>()
                val collectJob = launch {
                    conn.events
                        .onSubscription { subscribed.complete(Unit) }
                        .transformWhile { msg ->
                            if (msg.str("method") == "channelAdd") emit(msg)
                            msg.str("method") != "initialSyncCompleted"
                        }
                        .collect { msg ->
                            val id   = msg.long("channelId") ?: run { Log.w(TAG, "channelAdd missing channelId: keys=${msg.keys}"); return@collect }
                            val uuid = msg.str("channelIdStr") ?: run { Log.w(TAG, "channelAdd missing channelIdStr for id=$id"); return@collect }
                            Log.d(TAG, "channelAdd id=$id uuid=$uuid")
                            channelIdMap[uuid] = id
                        }
                }

                subscribed.await()
                // enableAsyncMetadata triggers channelAdd for every channel, then initialSyncCompleted
                val asyncResp = conn.rpc(htspMsg("method" to "enableAsyncMetadata", "epg" to 0L))
                Log.d(TAG, "enableAsyncMetadata response: keys=${asyncResp?.keys} noaccess=${asyncResp?.int("noaccess")}")

                withTimeoutOrNull(10_000) { collectJob.join() }
                collectJob.cancel()

                Log.d(TAG, "channel ID map: ${channelIdMap.size} entries")
            } finally {
                conn.disconnect()
            }
        }
    }

    // ── Playback control ─────────────────────────────────────────────────────

    /**
     * Switches the preview/playback channel. Debounced so that scrubbing focus
     * across the EPG (which fires this rapidly) doesn't restart the HTSP stream
     * on every step — the stream only (re)starts once focus settles.
     */
    fun setChannel(uuid: String) {
        // Focus returned to the channel already playing: cancel any pending switch.
        if (uuid == activeUuid) { channelJob?.cancel(); pendingUuid = null; return }
        // Same channel already scheduled: don't reset the debounce timer (the EPG
        // re-emits the focused channel on recomposition, which would otherwise
        // perpetually postpone the switch).
        if (uuid == pendingUuid) return

        pendingUuid = uuid
        channelJob?.cancel()
        channelJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val source = buildSource(uuid) ?: run {
                Log.w(TAG, "no source for uuid=$uuid"); pendingUuid = null; return@launch
            }
            activeUuid = uuid
            pendingUuid = null
            startStream(source)
        }
    }

    private suspend fun buildSource(uuid: String): MediaSource? =
        when (AppSettings.streamingMode.value) {
            StreamingMode.HLS    -> hlsSource(uuid)
            StreamingMode.HLS_LL -> hlsLlSource(uuid)
            StreamingMode.HTSP   -> htspSource(uuid)
        }

    // ── Browse-preview playback ──────────────────────────────────────────────
    private var previewJob: Job? = null
    private var previewPendingUuid: String? = null

    /** UUID currently loaded in the preview pane (null when the pane is closed). */
    var previewUuid: String? = null
        private set

    /**
     * Tunes the (muted) preview player to [uuid]. Debounced like [setChannel] so
     * holding Up/Down through the channel list only starts the stream once focus settles.
     */
    fun setPreviewChannel(uuid: String) {
        if (uuid == previewUuid || uuid == previewPendingUuid) return
        previewPendingUuid = uuid
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            // Always the low-res MPEG-TS transcode — keeps the second decoder light.
            val source = previewSourceFactory.createMediaSource(
                MediaItem.fromUri(TvHeadendClient.buildPreviewUrl(uuid))
            )
            previewUuid = uuid
            previewPendingUuid = null
            previewPlayer.setMediaSource(source)
            previewPlayer.prepare()
            previewPlayer.playWhenReady = true
        }
    }

    /** Stops and frees the preview decoder (call when the browse pane closes). */
    fun stopPreview() {
        previewJob?.cancel()
        previewPendingUuid = null
        previewUuid = null
        previewPlayer.stop()
        previewPlayer.clearMediaItems()
    }

    /** Resolves the numeric HTSP channel ID (waiting briefly for the async map). */
    private suspend fun htspSource(uuid: String): MediaSource? {
        val id = channelIdMap[uuid] ?: withTimeoutOrNull(1_500) {
            while (channelIdMap[uuid] == null) delay(100)
            channelIdMap[uuid]
        } ?: return null
        return htspSourceFactory.createMediaSource(
            MediaItem.fromUri(HtspDataSource.channelUri(id))
        )
    }

    private fun hlsSource(uuid: String): MediaSource =
        hlsSourceFactory.createMediaSource(
            MediaItem.fromUri(TvHeadendClient.buildHlsUrl(uuid))
        )

    private fun hlsLlSource(uuid: String): MediaSource =
        hlsSourceFactory.createMediaSource(
            MediaItem.Builder()
                .setUri(TvHeadendClient.buildHlsLlUrl(uuid))
                // Play 3 s behind live so parts are already available when requested,
                // avoiding blocking playlist requests at the live edge that cause
                // download bursts and decoder output-pool saturation.
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(3_000)
                        .setMaxOffsetMs(8_000)
                        .setMinOffsetMs(1_000)
                        .build()
                )
                .build()
        )

    private fun startStream(source: MediaSource) {
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePause() = htspFactory.controller.togglePause()
    fun pause()       = htspFactory.controller.pause()
    fun resume()      = htspFactory.controller.resume()

    override fun onCleared() {
        player.release()
        previewPlayer.release()
    }
}
