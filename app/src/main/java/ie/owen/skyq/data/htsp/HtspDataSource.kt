package ie.owen.skyq.data.htsp

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.common.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger

private const val TAG          = "HtspDS"
private const val PIPE_SIZE    = 512 * 1024
private const val TIMESHIFT_S  = 7200          // 2-hour timeshift buffer
private const val STREAM_PROFILE = "mp2-audio-to-aac-lc"  // TVHeadend transcode profile (video copy, audio→AAC-LC)

// ─── Timeshift state ─────────────────────────────────────────────────────────

data class TimeshiftState(
    val isPaused: Boolean = false,
    val livePts:    Long  = 0L,
    val currentPts: Long  = 0L
) {
    /** Seconds behind live (0 when at or near live). */
    val timeBehindLiveSec: Long get() =
        if (livePts > 0 && currentPts > 0 && livePts > currentPts)
            (livePts - currentPts) / 90_000L
        else 0L

    val isAtLive: Boolean get() = timeBehindLiveSec < 5L
}

// ─── HtspController ──────────────────────────────────────────────────────────

/**
 * Shared bridge between [VideoViewModel] (UI controls) and [HtspDataSource]
 * (the active subscription).  The ViewModel holds a reference; the DataSource
 * binds itself on open and unbinds on close.
 */
class HtspController {
    private val _state = MutableStateFlow(TimeshiftState())
    val state: StateFlow<TimeshiftState> = _state.asStateFlow()

    private var conn: HtspConnection? = null
    private var subId: Int = -1

    internal fun bind(connection: HtspConnection, subscriptionId: Int) {
        conn  = connection
        subId = subscriptionId
        _state.value = TimeshiftState()
    }

    internal fun unbind() { conn = null; subId = -1 }

    internal fun updatePts(live: Long, current: Long) {
        _state.update { it.copy(livePts = live, currentPts = current) }
    }

    fun pause() {
        if (_state.value.isPaused) return
        _state.update { it.copy(isPaused = true) }
        speed(0)
    }

    fun resume() {
        if (!_state.value.isPaused) return
        _state.update { it.copy(isPaused = false) }
        speed(100)
    }

    fun togglePause() { if (_state.value.isPaused) resume() else pause() }

    private fun speed(value: Int) {
        val c = conn ?: return
        if (subId < 0) return
        c.send(htspMsg(
            "method"         to "subscriptionSpeed",
            "subscriptionId" to subId.toLong(),
            "speed"          to value.toLong()
        ))
    }
}

// ─── HtspDataSource ──────────────────────────────────────────────────────────

/**
 * Media3 [DataSource] that connects to TVHeadend via HTSP, subscribes to a
 * channel, re-muxes the incoming codec frames into MPEG-TS via [TsMuxer],
 * and exposes the byte stream through a [PipedInputStream].
 *
 * URI format: `htsp:///<numericChannelId>`  (e.g. `htsp:///42`)
 */
@UnstableApi
class HtspDataSource private constructor(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val controller: HtspController
) : DataSource {

    private var uri: Uri? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var muxJob: Job? = null

    private lateinit var conn:    HtspConnection
    private lateinit var pipe:    PipedInputStream
    private lateinit var pipeOut: PipedOutputStream

    private val subId = SUB_SEQ.getAndIncrement()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val channelId = dataSpec.uri.path?.trimStart('/')?.toLongOrNull()
            ?: throw IOException("Invalid HTSP URI: ${dataSpec.uri}")

        pipe    = PipedInputStream(PIPE_SIZE)
        pipeOut = PipedOutputStream(pipe)
        conn    = HtspConnection(host, port, username, password)

        muxJob = scope.launch {
            try {
                if (!conn.connect()) { pipeOut.close(); return@launch }

                val muxer = TsMuxer(pipeOut)
                controller.bind(conn, subId)

                conn.events.onSubscription {
                    // Send subscribe only after we're registered on the events
                    // flow. Otherwise subscriptionStart (which has no seq) can be
                    // emitted before collect() begins and dropped — the SharedFlow
                    // has no replay buffer — leaving the muxer uninitialised.
                    conn.send(htspMsg(
                        "method"          to "subscribe",
                        "subscriptionId"  to subId.toLong(),
                        "channelId"       to channelId,
                        // Server-side transcode profile: copies video, re-encodes
                        // MPEG-2 audio to AAC-LC (most Android devices can't decode MP2).
                        "profile"         to STREAM_PROFILE,
                        "queueDepth"      to 5_000_000L,
                        "weight"          to 150L,
                        "90khz"           to 1L   // request 90kHz PTS/DTS (default is µs)
                    ))
                }.collect { msg ->
                    when (msg.str("method")) {
                        "subscriptionStart" -> {
                            val streams = msg.list("streams") ?: return@collect
                            if (!muxer.init(streams)) Log.w(TAG, "No usable streams in subscriptionStart")
                        }
                        "muxpkt" -> {
                            if ((msg.int("subscriptionId") ?: -1) != subId) return@collect
                            val idx     = msg.int("stream")   ?: return@collect
                            val pts     = msg.long("pts")     ?: return@collect
                            val dts     = msg.long("dts")     ?: pts
                            val payload = msg.bytes("payload") ?: return@collect
                            val isKey   = msg.int("frametype") == 0x49  // 'I' = 73 = 0x49
                            controller.updatePts(muxer.livePts, muxer.currentPts)
                            try {
                                muxer.mux(idx, pts, dts, payload, isKey)
                                pipeOut.flush()
                            } catch (_: IOException) {
                                return@collect   // pipe was closed
                            }
                        }
                        "subscriptionStop" -> {
                            Log.i(TAG, "subscriptionStop received")
                            pipeOut.close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "mux loop error: ${e.message}")
                runCatching { pipeOut.close() }
            }
        }

        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = pipe.read(buffer, offset, length)
        return if (n == -1) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        controller.unbind()
        muxJob?.cancel()
        runCatching { pipeOut.close() }
        runCatching { pipe.close() }
        runCatching {
            conn.send(htspMsg(
                "method"         to "unsubscribe",
                "subscriptionId" to subId.toLong()
            ))
        }
        conn.disconnect()
    }

    override fun addTransferListener(transferListener: TransferListener) { /* unused */ }

    // ─── Factory ─────────────────────────────────────────────────────────────

    class Factory(
        private val host: String,
        private val port: Int,
        private val username: String,
        private val password: String,
        val controller: HtspController = HtspController()
    ) : DataSource.Factory {
        override fun createDataSource() =
            HtspDataSource(host, port, username, password, controller)
    }

    companion object {
        private val SUB_SEQ = AtomicInteger(1)

        /** Builds an HTSP stream URI from a numeric channel ID. */
        fun channelUri(channelId: Long): Uri = Uri.parse("htsp:///$channelId")
    }
}
