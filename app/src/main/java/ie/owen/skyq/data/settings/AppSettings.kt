package ie.owen.skyq.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamingMode(val label: String, val description: String) {
    HTSP("HTSP", "Direct HTSP subscription. Fastest channel changes; live edge only."),
    HLS("HLS", "HTTP Live Streaming with server-side DVR buffer. Pause and rewind.")
}

/**
 * App-wide user settings, persisted in SharedPreferences. Call [init] once at
 * startup (MainActivity) before reading. Reads are always safe; the StateFlow
 * carries the default until [init] loads the stored value.
 */
object AppSettings {

    private const val PREFS = "skyq_settings"
    private const val KEY_STREAMING_MODE = "streaming_mode"

    private var prefs: SharedPreferences? = null

    private val _streamingMode = MutableStateFlow(StreamingMode.HTSP)
    val streamingMode: StateFlow<StreamingMode> = _streamingMode.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val stored = p.getString(KEY_STREAMING_MODE, null)
        _streamingMode.value = stored?.let { runCatching { StreamingMode.valueOf(it) }.getOrNull() }
            ?: StreamingMode.HTSP
    }

    fun setStreamingMode(mode: StreamingMode) {
        _streamingMode.value = mode
        prefs?.edit()?.putString(KEY_STREAMING_MODE, mode.name)?.apply()
    }
}
