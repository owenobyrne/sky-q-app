package ie.owen.skyq.data.repository

import android.util.Log
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.ChannelTagEntry
import ie.owen.skyq.data.model.EpgEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

private const val TAG = "EpgLoad"
private const val EPG_CHUNK = 500

/**
 * Minimum gap between progressive emissions while the remaining EPG chunks stream in.
 * Each emission recomposes the whole guide, so emitting once per 500-event chunk (~20 times
 * for a full grid) starves the main thread exactly while the user is trying to navigate.
 */
private const val EMIT_INTERVAL_MS = 750L

data class GuideData(
    val channels: List<Channel>,
    val eventsByChannel: Map<String, List<EpgEvent>>,
    val tags: List<ChannelTagEntry> = emptyList()
)

class EpgRepository {

    private val api get() = TvHeadendClient.api

    private var cachedChannels: List<Channel>? = null
    private var cachedEvents: List<EpgEvent>? = null
    private var cachedTags: List<ChannelTagEntry>? = null
    private var cacheTimestamp: Long = 0L
    private val cacheTtlMs = 5 * 60 * 1000L

    private fun cacheStale() = System.currentTimeMillis() - cacheTimestamp > cacheTtlMs

    fun getGuideDataFlow(windowStart: Long, windowEnd: Long): Flow<GuideData> = flow {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "=== load start ===")

        if (cachedChannels != null && cachedEvents != null && cachedTags != null && !cacheStale()) {
            Log.d(TAG, "cache hit")
            emit(GuideData(cachedChannels!!, toWindowMap(cachedEvents!!, windowStart, windowEnd), cachedTags!!))
            return@flow
        }

        // Load channels, tags, encrypted-service UUIDs, and first EPG chunk concurrently
        val (channels, tags, firstChunk) = coroutineScope {
            val channelsDeferred = async {
                val t = System.currentTimeMillis()
                val encryptedUuids = runCatching {
                    api.getEncryptedServices().entries
                        .flatMap { it.channel }
                        .toSet()
                }.getOrDefault(emptySet())
                api.getChannels().entries
                    .filter { it.uuid !in encryptedUuids }
                    .sortedBy { it.number }
                    .also { Log.d(TAG, "+${System.currentTimeMillis() - t}ms  channels: ${it.size} (${encryptedUuids.size} encrypted filtered)") }
            }
            val tagsDeferred = async {
                runCatching { api.getChannelTags().entries }.getOrDefault(emptyList())
                    .also { Log.d(TAG, "tags: ${it.size}") }
            }
            val epgDeferred = async {
                val t = System.currentTimeMillis()
                api.getEpgEvents(limit = EPG_CHUNK, start = 0)
                    .also { Log.d(TAG, "+${System.currentTimeMillis() - t}ms  EPG chunk 0: ${it.entries.size}/${it.totalCount} events") }
            }
            Triple(channelsDeferred.await(), tagsDeferred.await(), epgDeferred.await())
        }

        val allEvents = ArrayList<EpgEvent>(firstChunk.totalCount.coerceIn(EPG_CHUNK, 20_000))

        // Per-channel window map built up incrementally. Channels that gain no events in a
        // chunk keep their *existing List instance*, so the grid's per-row `remember(cells)`
        // stays valid and those rows are not re-laid-out on every progressive emission.
        val byChannel = HashMap<String, List<EpgEvent>>()

        fun merge(chunk: List<EpgEvent>): Boolean {
            val inWindow = chunk.filter { it.stop > windowStart && it.start < windowEnd }
            if (inWindow.isEmpty()) return false
            for ((uuid, added) in inWindow.groupBy { it.channelUuid }) {
                val existing = byChannel[uuid]
                byChannel[uuid] = if (existing == null) added else existing + added
            }
            return true
        }

        allEvents.addAll(firstChunk.entries)
        merge(firstChunk.entries)
        emit(GuideData(channels, HashMap(byChannel), tags))
        Log.d(TAG, "+${System.currentTimeMillis() - t0}ms  first emit — ${channels.size} channels, ${allEvents.size} events fetched")

        // Fetch remaining EPG chunks in background, coalescing emissions so the UI thread
        // isn't asked to rebuild the guide once per network round-trip.
        var offset = allEvents.size
        var lastEmit = System.currentTimeMillis()
        var dirty = false
        val total = firstChunk.totalCount
        while (offset < total) {
            val t = System.currentTimeMillis()
            val chunk = api.getEpgEvents(limit = EPG_CHUNK, start = offset)
            Log.d(TAG, "+${System.currentTimeMillis() - t}ms  EPG chunk $offset: ${chunk.entries.size} events")
            if (chunk.entries.isEmpty()) break
            allEvents.addAll(chunk.entries)
            if (merge(chunk.entries)) dirty = true

            val now = System.currentTimeMillis()
            if (dirty && now - lastEmit >= EMIT_INTERVAL_MS) {
                emit(GuideData(channels, HashMap(byChannel), tags))
                lastEmit = now
                dirty = false
            }

            if (chunk.entries.all { it.start >= windowEnd }) break
            offset += chunk.entries.size
        }
        if (dirty) emit(GuideData(channels, HashMap(byChannel), tags))

        cachedChannels = channels
        cachedEvents = allEvents.toList()
        cachedTags = tags
        cacheTimestamp = System.currentTimeMillis()
        Log.d(TAG, "+${System.currentTimeMillis() - t0}ms  === load complete — ${allEvents.size} events total ===")
    }.flowOn(Dispatchers.IO)

    private fun toWindowMap(events: List<EpgEvent>, windowStart: Long, windowEnd: Long) =
        events.filter { it.stop > windowStart && it.start < windowEnd }.groupBy { it.channelUuid }
}
