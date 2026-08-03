package ie.owen.skyq.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

class TvHeadendDiscovery(private val context: Context) {

    data class Server(val name: String, val host: String, val port: Int = 9981) {
        val httpUrl: String get() = "http://$host:$port/"
        override fun toString() = "$name  [$host:$port]"
    }

    // Lazy so constructing a TvHeadendDiscovery (which happens on the caller's dispatcher)
    // never builds an OkHttpClient on the main thread.
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Sweeps the local /24 for a TVHeadend server. Runs entirely on IO — [localIpAddress]
     * is a binder call into system_server, which must not happen on the main thread.
     */
    suspend fun scan(): List<Server> = withContext(Dispatchers.IO) {
        val localIp = localIpAddress() ?: return@withContext emptyList()
        val subnet  = localIp.substringBeforeLast(".")
        coroutineScope {
            (1..254).map { i ->
                async { probe("$subnet.$i", 9981) }
            }.mapNotNull { it.await() }
        }
    }

    private fun probe(host: String, port: Int): Server? = runCatching {
        val req  = Request.Builder().url("http://$host:$port/api/serverinfo").build()
        val resp = probeClient.newCall(req).execute()
        when {
            resp.isSuccessful -> {
                val name = runCatching {
                    JSONObject(resp.body?.string() ?: "").optString("name", "TVHeadend")
                }.getOrDefault("TVHeadend")
                Server(name, host, port)
            }
            resp.code == 401 -> Server("TVHeadend", host, port)   // auth required but it's there
            else -> null
        }
    }.getOrNull()

    private fun localIpAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getLinkProperties(cm.activeNetwork)
            ?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress
    }
}
