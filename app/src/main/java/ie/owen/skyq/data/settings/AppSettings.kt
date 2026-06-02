package ie.owen.skyq.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamingMode(val label: String, val description: String) {
    HTSP("HTSP", "Direct HTSP subscription. Fastest channel changes; live edge only."),
    HLS("HLS", "HTTP Live Streaming with server-side DVR buffer. Pause and rewind."),
    HLS_LL("LL-HLS", "Low-Latency HLS. Sub-second live edge with DVR buffer support.")
}

object AppSettings {

    private const val PREFS        = "skyq_settings"
    private const val SECURE_PREFS = "skyq_secure"

    private const val KEY_MODE     = "streaming_mode"
    private const val KEY_HOST     = "server_host"
    private const val KEY_PORT     = "server_port"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    private var prefs: SharedPreferences? = null
    private var securePrefs: SharedPreferences? = null

    private val _streamingMode = MutableStateFlow(StreamingMode.HLS_LL)
    val streamingMode: StateFlow<StreamingMode> = _streamingMode.asStateFlow()

    // Server config — read by TvHeadendClient on each (re)build
    var serverHost: String = ""; private set
    var serverPort: Int    = 9981; private set
    var username:   String = ""; private set
    var password:   String = ""; private set

    val isConfigured: Boolean get() = serverHost.isNotBlank()

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        securePrefs = runCatching {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app, SECURE_PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // Keystore unavailable (e.g. after factory reset in some edge cases) — fall back to plain prefs
            app.getSharedPreferences("${SECURE_PREFS}_fallback", Context.MODE_PRIVATE)
        }

        prefs!!.let { p ->
            _streamingMode.value = p.getString(KEY_MODE, null)
                ?.let { runCatching { StreamingMode.valueOf(it) }.getOrNull() }
                ?: StreamingMode.HLS_LL
            serverHost = p.getString(KEY_HOST, "") ?: ""
            serverPort = p.getInt(KEY_PORT, 9981)
        }
        securePrefs!!.let { s ->
            username = s.getString(KEY_USERNAME, "") ?: ""
            password = s.getString(KEY_PASSWORD, "") ?: ""
        }
    }

    fun setStreamingMode(mode: StreamingMode) {
        _streamingMode.value = mode
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
    }

    fun setServerConfig(host: String, port: Int, user: String, pass: String) {
        serverHost = host
        serverPort = port
        username   = user
        password   = pass
        prefs?.edit()?.putString(KEY_HOST, host)?.putInt(KEY_PORT, port)?.apply()
        securePrefs?.edit()?.putString(KEY_USERNAME, user)?.putString(KEY_PASSWORD, pass)?.apply()
    }
}
