package ie.owen.skyq.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class StreamingMode(val label: String, val description: String) {
    HTSP("HTSP", "Direct HTSP subscription. Fastest channel changes; live edge only."),
    HLS("HLS", "HTTP Live Streaming with server-side DVR buffer. Pause and rewind."),
    HLS_LL("LL-HLS", "Low-Latency HLS. Sub-second live edge with DVR buffer support.")
}

/**
 * Persisted app configuration.
 *
 * Loading is deliberately **off the main thread**: [EncryptedSharedPreferences] has to go
 * through the Android Keystore (key generation on first run, a keystore round-trip plus Tink
 * primitive setup thereafter), which is far too slow to sit in `Application.onCreate`.
 * [init] therefore kicks the load off on [Dispatchers.IO] and returns immediately; anything
 * that needs the values suspends on [awaitReady] first.
 */
object AppSettings {

    private const val PREFS        = "skyq_settings"
    private const val SECURE_PREFS = "skyq_secure"

    private const val KEY_MODE         = "streaming_mode"
    private const val KEY_HOST         = "server_host"
    private const val KEY_PORT         = "server_port"
    private const val KEY_USERNAME     = "username"
    private const val KEY_PASSWORD     = "password"
    private const val KEY_LAST_CHANNEL = "last_channel_uuid"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var securePrefs: SharedPreferences? = null

    private val _streamingMode = MutableStateFlow(StreamingMode.HLS_LL)
    val streamingMode: StateFlow<StreamingMode> = _streamingMode.asStateFlow()

    // Server config — read by TvHeadendClient on each (re)build. Volatile because the load
    // runs on an IO thread while the UI thread reads these afterwards.
    @Volatile var serverHost:      String  = ""; private set
    @Volatile var serverPort:      Int     = 9981; private set
    @Volatile var username:        String  = ""; private set
    @Volatile var password:        String  = ""; private set
    @Volatile var lastChannelUuid: String? = null; private set

    val isConfigured: Boolean get() = serverHost.isNotBlank()

    private val readyLatch = CompletableDeferred<Unit>()

    /** True once the persisted settings have finished loading. */
    val isReady: Boolean get() = readyLatch.isCompleted

    /** Suspends until the persisted settings are loaded. Returns immediately once ready. */
    suspend fun awaitReady() = readyLatch.await()

    /** Starts the (background) settings load. Returns immediately — call from `Application.onCreate`. */
    fun init(context: Context) {
        if (readyLatch.isCompleted) return
        val app = context.applicationContext
        scope.launch {
            try {
                loadBlocking(app)
            } finally {
                readyLatch.complete(Unit)
            }
        }
    }

    /** The actual disk + keystore work. Must not run on the main thread. */
    private fun loadBlocking(app: Context) {
        val plain = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }

        val secure = runCatching {
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
        }.also { securePrefs = it }

        _streamingMode.value = plain.getString(KEY_MODE, null)
            ?.let { runCatching { StreamingMode.valueOf(it) }.getOrNull() }
            ?: StreamingMode.HLS_LL
        serverHost      = plain.getString(KEY_HOST, "") ?: ""
        serverPort      = plain.getInt(KEY_PORT, 9981)
        lastChannelUuid = plain.getString(KEY_LAST_CHANNEL, null)

        username = secure.getString(KEY_USERNAME, "") ?: ""
        password = secure.getString(KEY_PASSWORD, "") ?: ""

        // One-time bootstrap: if credentials are still empty, check a plain prefs file
        // that can be pushed via ADB for first-run device setup.
        if (username.isBlank() || password.isBlank()) {
            val bootstrap = app.getSharedPreferences("skyq_bootstrap", Context.MODE_PRIVATE)
            bootstrap.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }?.let { u ->
                bootstrap.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }?.let { p ->
                    setServerConfig(
                        host = bootstrap.getString(KEY_HOST, serverHost) ?: serverHost,
                        port = bootstrap.getInt(KEY_PORT, serverPort),
                        user = u,
                        pass = p
                    )
                }
            }
        }
    }

    fun setStreamingMode(mode: StreamingMode) {
        _streamingMode.value = mode
        writeAsync { prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply() }
    }

    fun setLastChannel(uuid: String) {
        lastChannelUuid = uuid
        writeAsync { prefs?.edit()?.putString(KEY_LAST_CHANNEL, uuid)?.apply() }
    }

    fun setServerConfig(host: String, port: Int, user: String, pass: String) {
        serverHost = host
        serverPort = port
        username   = user
        password   = pass
        writeAsync {
            prefs?.edit()?.putString(KEY_HOST, host)?.putInt(KEY_PORT, port)?.apply()
            // EncryptedSharedPreferences encrypts on the calling thread before apply() queues
            // the write, so this must not run on the main thread.
            securePrefs?.edit()?.putString(KEY_USERNAME, user)?.putString(KEY_PASSWORD, pass)?.apply()
        }
    }

    /**
     * Runs a preference write on the IO scope. In-memory state is updated by the caller so
     * reads are immediately consistent; only the (encrypt +) disk-queue work is deferred.
     */
    private fun writeAsync(block: () -> Unit) {
        scope.launch { runCatching(block) }
    }
}
