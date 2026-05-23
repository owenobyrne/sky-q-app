package ie.owen.skyq.data.repository

import android.util.Log
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

private const val TAG = "EpgLoad"
private const val EPG_CHUNK = 500

data class GuideData(
    val channels: List<Channel>,
    val eventsByChannel: Map<String, List<EpgEvent>>
)

class EpgRepository {

    private val api = TvHeadendClient.api

    private var cachedChannels: List<Channel>? = null
    private var cachedEvents: List<EpgEvent>? = null
    private var cacheTimestamp: Long = 0L
    private val cacheTtlMs = 5 * 60 * 1000L

    private fun cacheStale() = System.currentTimeMillis() - cacheTimestamp > cacheTtlMs

    fun getGuideDataFlow(windowStart: Long, windowEnd: Long): Flow<GuideData> = flow {
        val t0 = System.currentTimeMillis()
        Log.d(TAG, "=== load start ===")

        if (cachedChannels != null && cachedEvents != null && !cacheStale()) {
            Log.d(TAG, "cache hit")
            emit(GuideData(cachedChannels!!, toWindowMap(cachedEvents!!, windowStart, windowEnd)))
            return@flow
        }

        // Load channels and first EPG chunk concurrently
        val (channels, firstChunk) = coroutineScope {
            val channelsDeferred = async {
                val t = System.currentTimeMillis()
                api.getChannels().entries
                    .sortedBy { it.number }
                    .also { Log.d(TAG, "+${System.currentTimeMillis() - t}ms  channels: ${it.size}") }
            }
            val epgDeferred = async {
                val t = System.currentTimeMillis()
                api.getEpgEvents(limit = EPG_CHUNK, start = 0)
                    .also { Log.d(TAG, "+${System.currentTimeMillis() - t}ms  EPG chunk 0: ${it.entries.size}/${it.totalCount} events") }
            }
            Pair(channelsDeferred.await(), epgDeferred.await())
        }

        val allEvents = firstChunk.entries.toMutableList()
        emit(GuideData(channels, toWindowMap(allEvents, windowStart, windowEnd)))
        Log.d(TAG, "+${System.currentTimeMillis() - t0}ms  first emit — ${channels.size} channels, ${allEvents.size} events fetched")

        // Fetch remaining EPG chunks in background
        var offset = allEvents.size
        val total = firstChunk.totalCount
        while (offset < total) {
            val t = System.currentTimeMillis()
            val chunk = api.getEpgEvents(limit = EPG_CHUNK, start = offset)
            Log.d(TAG, "+${System.currentTimeMillis() - t}ms  EPG chunk $offset: ${chunk.entries.size} events")
            if (chunk.entries.isEmpty()) break
            allEvents.addAll(chunk.entries)
            emit(GuideData(channels, toWindowMap(allEvents, windowStart, windowEnd)))
            if (chunk.entries.all { it.start >= windowEnd }) break
            offset += chunk.entries.size
        }

        cachedChannels = channels
        cachedEvents = allEvents.toList()
        cacheTimestamp = System.currentTimeMillis()
        Log.d(TAG, "+${System.currentTimeMillis() - t0}ms  === load complete — ${allEvents.size} events total ===")
    }.flowOn(Dispatchers.IO)

    private fun toWindowMap(events: List<EpgEvent>, windowStart: Long, windowEnd: Long) =
        events.filter { it.stop > windowStart && it.start < windowEnd }.groupBy { it.channelUuid }
}
