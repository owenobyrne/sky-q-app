package ie.owen.skyq.data.api

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

    const val BASE_URL = "http://192.168.1.7:9981/"
    private const val USERNAME = "emby"
    private const val PASSWORD = "emby"

    private val okHttpClient = OkHttpClient.Builder()
        .authenticator(DigestAuthenticator(USERNAME, PASSWORD))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    val api: TvHeadendApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TvHeadendApi::class.java)

    fun buildStreamUrl(channelUuid: String) =
        "${BASE_URL}stream/channel/$channelUuid?profile=mp2-audio-to-aac-lc"

    // HLS entry playlist. hls-transcode = H.264 (High) + AAC-LC, widest device support.
    fun buildHlsUrl(channelUuid: String) =
        "${BASE_URL}hls/channel/$channelUuid.m3u8?profile=hls-transcode"

    fun buildHlsLlUrl(channelUuid: String) =
        "${BASE_URL}hls/channel/$channelUuid.m3u8?profile=hls-ll"

    fun resolveUrl(path: String): String =
        if (path.startsWith("http")) path else "$BASE_URL$path"

    fun authenticatedOkHttpClient() = okHttpClient
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
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val opaque = params["opaque"]
        val qop = params["qop"]

        nonceCount++
        val nc = "%08x".format(nonceCount)
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
