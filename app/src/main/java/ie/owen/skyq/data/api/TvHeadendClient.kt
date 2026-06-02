package ie.owen.skyq.data.api

import ie.owen.skyq.data.settings.AppSettings
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

object TvHeadendClient {

    val baseUrl: String
        get() = "http://${AppSettings.serverHost}:${AppSettings.serverPort}/"

    @Volatile private var _okHttpClient: OkHttpClient? = null
    @Volatile private var _api: TvHeadendApi? = null

    // Snapshot of config used to build the current instances; detect staleness
    private var builtHost = ""; private var builtPort = -1
    private var builtUser = ""; private var builtPass = ""

    private fun isStale() = AppSettings.serverHost != builtHost
            || AppSettings.serverPort != builtPort
            || AppSettings.username   != builtUser
            || AppSettings.password   != builtPass

    @Synchronized
    private fun ensureBuilt() {
        if (_okHttpClient != null && !isStale()) return
        builtHost = AppSettings.serverHost
        builtPort = AppSettings.serverPort
        builtUser = AppSettings.username
        builtPass = AppSettings.password

        val url = if (builtHost.isBlank()) "http://localhost:9981/" else baseUrl
        val client = OkHttpClient.Builder()
            .authenticator(DigestAuthenticator(builtUser, builtPass))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
        _okHttpClient = client
        _api = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TvHeadendApi::class.java)
    }

    val api: TvHeadendApi get() { ensureBuilt(); return _api!! }

    fun authenticatedOkHttpClient(): OkHttpClient { ensureBuilt(); return _okHttpClient!! }

    fun buildStreamUrl(channelUuid: String) =
        "${baseUrl}stream/channel/$channelUuid?profile=mp2-audio-to-aac-lc"

    fun buildHlsUrl(channelUuid: String) =
        "${baseUrl}hls/channel/$channelUuid.m3u8?profile=hls-transcode"

    fun buildHlsLlUrl(channelUuid: String) =
        "${baseUrl}hls/channel/$channelUuid.m3u8?profile=hls-ll"

    fun resolveUrl(path: String): String =
        if (path.startsWith("http")) path else "$baseUrl$path"
}

private class DigestAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {

    private var nonceCount = 0

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header("Authorization") != null) return null
        val wwwAuth = response.header("WWW-Authenticate") ?: return null
        if (!wwwAuth.startsWith("Digest", ignoreCase = true)) return null

        val params = parseParams(wwwAuth)
        val realm  = params["realm"] ?: return null
        val nonce  = params["nonce"] ?: return null
        val opaque = params["opaque"]
        val qop    = params["qop"]

        nonceCount++
        val nc     = "%08x".format(nonceCount)
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)

        val uri = response.request.url.encodedPath.let { path ->
            val q = response.request.url.encodedQuery
            if (q != null) "$path?$q" else path
        }

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("${response.request.method}:$uri")
        val digestResponse = if (qop == "auth") {
            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val authHeader = buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (qop == "auth") append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
            append(", response=\"$digestResponse\"")
            opaque?.let { append(", opaque=\"$it\"") }
        }

        return response.request.newBuilder().header("Authorization", authHeader).build()
    }

    private fun parseParams(header: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("""(\w+)="([^"]*)"""").findAll(header).forEach {
            map[it.groupValues[1]] = it.groupValues[2]
        }
        return map
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
