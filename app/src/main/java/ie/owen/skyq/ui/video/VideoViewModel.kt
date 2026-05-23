package ie.owen.skyq.ui.video

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import ie.owen.skyq.data.api.TvHeadendClient

private val liveExtractorsFactory = ExtractorsFactory {
    arrayOf(MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES))
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoViewModel(application: Application) : AndroidViewModel(application) {

    val player: ExoPlayer = ExoPlayer.Builder(application)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(TvHeadendClient.authenticatedOkHttpClient()),
                liveExtractorsFactory
            )
        )
        .build()

    fun setChannel(uuid: String) {
        val targetUrl = TvHeadendClient.buildStreamUrl(uuid)
        val alreadyActive = player.currentMediaItem
            ?.localConfiguration?.uri?.toString() == targetUrl &&
            player.playbackState.let { it == Player.STATE_READY || it == Player.STATE_BUFFERING }
        if (alreadyActive) return
        player.setMediaItem(MediaItem.fromUri(targetUrl))
        player.prepare()
        player.playWhenReady = true
    }

    override fun onCleared() {
        player.release()
    }
}
