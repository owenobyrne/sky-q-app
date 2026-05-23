package ie.owen.skyq.data.htsp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.DataInputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG         = "HtspConn"
private const val HTSP_VERSION = 35

class HtspConnection(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String
) {
    private var socket: Socket? = null
    private val seq    = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<HtspMsg>>()

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reader: Job? = null

    private val _events = MutableSharedFlow<HtspMsg>(extraBufferCapacity = 1024)
    val events: SharedFlow<HtspMsg> = _events

    /** Connects and authenticates. Returns true on success. */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val s = Socket(host, port).also { socket = it }
            val dis = DataInputStream(s.getInputStream())

            reader = scope.launch {
                try {
                    while (!s.isClosed) {
                        val len = dis.readInt()
                        if (len <= 0 || len > 20_000_000) break
                        val buf = ByteArray(len)
                        dis.readFully(buf)
                        dispatch(HtspMessage.parse(buf))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "reader ended: ${e.message}")
                }
            }

            Log.d(TAG, "sending hello to $host:$port")
            val hello = rpc(htspMsg(
                "method"      to "hello",
                "clientname"  to "SkyQ",
                "htspversion" to HTSP_VERSION.toLong()
            ))
            if (hello == null) {
                Log.e(TAG, "hello timed out — no response from server"); return@withContext false
            }
            Log.d(TAG, "hello response keys: ${hello.keys}")
            Log.d(TAG, "hello htspversion=${hello.int("htspversion")} servername=${hello.str("servername")}")

            val challenge = hello.bytes("challenge")
            if (challenge == null) {
                Log.w(TAG, "no challenge in hello — attempting unauthenticated access")
            } else {
                Log.d(TAG, "challenge length=${challenge.size}")
            }

            val digest = if (challenge != null && challenge.isNotEmpty())
                sha1(password.toByteArray() + challenge)
            else
                sha1(password.toByteArray())   // no challenge: hash password alone

            // Login — send full htspversion + credentials
            val auth = rpc(htspMsg(
                "method"      to "authenticate",
                "htspversion" to HTSP_VERSION.toLong(),
                "username"    to username,
                "digest"      to digest
            ))
            if (auth == null) {
                Log.e(TAG, "authenticate timed out"); return@withContext false
            }
            Log.d(TAG, "auth response keys: ${auth.keys} noaccess=${auth.int("noaccess")}")

            if ((auth.int("noaccess") ?: 0) != 0) {
                Log.e(TAG, "HTSP auth rejected"); return@withContext false
            }
            Log.i(TAG, "HTSP connected $host:$port (server htspversion=${hello.int("htspversion")})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}"); false
        }
    }

    /** Sends a message and awaits the seq-matched response (10 s timeout). */
    suspend fun rpc(msg: HtspMsg): HtspMsg? = withContext(Dispatchers.IO) {
        val id = seq.getAndIncrement()
        val d  = CompletableDeferred<HtspMsg>()
        pending[id] = d
        val withSeq = HashMap(msg).also { it["seq"] = id.toLong() }
        try {
            rawSend(withSeq)
            withTimeoutOrNull(10_000) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Fire-and-forget send (no seq, no response expected). */
    fun send(msg: HtspMsg) { rawSend(msg) }

    fun disconnect() {
        reader?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private fun rawSend(msg: HtspMsg) {
        val bytes = HtspMessage.serialize(msg)
        val out   = socket?.getOutputStream() ?: run { Log.e(TAG, "rawSend: no socket"); return }
        try {
            synchronized(out) { out.write(bytes); out.flush() }
            Log.d(TAG, "sent method=${msg.str("method")} (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "rawSend failed: ${e.message}")
            throw e
        }
    }

    private fun dispatch(msg: HtspMsg) {
        val id = msg.int("seq")
        if (id != null && pending.containsKey(id)) {
            pending[id]?.complete(msg)
        } else {
            _events.tryEmit(msg)
        }
    }

    private fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)
}
