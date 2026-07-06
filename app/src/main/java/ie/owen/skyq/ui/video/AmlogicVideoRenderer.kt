package ie.owen.skyq.ui.video

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** True when the device has an Amlogic codec (c2.amlogic.* or OMX.amlogic.*). */
val isAmlogicDevice: Boolean by lazy {
    MediaCodecList(MediaCodecList.ALL_CODECS)
        .codecInfos.any { it.name.contains("amlogic", ignoreCase = true) }
}

/**
 * Works around the Amlogic Codec2 output-buffer saturation on the Chromecast HD.
 *
 * The c2.amlogic.avc.decoder has a 9-slot output buffer pool. Under LL-HLS the pool
 * saturates (9/9) because:
 *   1. The present fence is never signalled ("no present fence for frame N"), so Codec2
 *      doesn't know when SurfaceFlinger has consumed a frame and holds slots indefinitely.
 *   2. Audio HAL stalls cause the media clock (audio-based) to pause, making every video
 *      frame appear "too early". ExoPlayer holds them all in TRY_AGAIN_LATER, occupying all
 *      9 output slots and deadlocking the pipeline.
 *
 * Mitigations applied here:
 *   • KEY_LOW_LATENCY = 1  — decoder minimises output-buffer hold time, reducing build-up.
 *   • KEY_ALLOW_FRAME_DROP = 1 — decoder may drop a frame to free a slot rather than stalling.
 *   • releaseTimeNs = 0 — pass "render ASAP" so the Codec2 slot is freed at the next vsync
 *     rather than waiting for a broken fence callback.
 */
@UnstableApi
class AmlogicVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedVideoJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float,
    ): MediaCodecAdapter.Configuration {
        val config = super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate)
        // Allow the decoder to drop a frame to free an output slot rather than deadlocking.
        // KEY_LOW_LATENCY is intentionally NOT set: on c2.amlogic.avc.decoder it causes the
        // vendor Codec2 HAL service to crash, taking the entire device down with it.
        config.mediaFormat.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
        return config
    }

    override fun renderOutputBufferV21(
        codec: MediaCodecAdapter,
        index: Int,
        presentationTimeUs: Long,
        releaseTimeNs: Long,
    ) {
        // Pass 0 ("render ASAP") so the Codec2 slot is freed at the next vsync rather than
        // waiting for the present fence that never arrives on this device.
        super.renderOutputBufferV21(codec, index, presentationTimeUs, 0L)
    }
}

/**
 * Drop-in replacement for DefaultRenderersFactory that substitutes AmlogicVideoRenderer.
 */
@UnstableApi
class AmlogicRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            AmlogicVideoRenderer(
                context,
                getCodecAdapterFactory(),
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )
        )
    }
}
