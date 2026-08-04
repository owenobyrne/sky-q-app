package ie.owen.skyq.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.data.repository.EpgRepository
import ie.owen.skyq.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The currently-airing programme and the one after it for a single channel. */
data class NowNext(val now: EpgEvent?, val next: EpgEvent?)

data class TvGuideUiState(
    val channels: List<Channel> = emptyList(),
    val eventsByChannel: Map<String, List<EpgEvent>> = emptyMap(),
    /** Current + next programme per channel, pre-computed off the main thread so the
     *  Now/Next rows never scan event lists during composition or on every keypress. */
    val nowNextByChannel: Map<String, NowNext> = emptyMap(),
    val initialChannelUuid: String? = null,
    val previewChannelUuid: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

private fun defaultWindowStart(): Long {
    val now = System.currentTimeMillis() / 1000L
    return now - (now % 1800) - 1800
}

class TvGuideViewModel : ViewModel() {

    private val repository = EpgRepository()

    private val _state = MutableStateFlow(TvGuideUiState())
    val state: StateFlow<TvGuideUiState> = _state

    private val _focusedEvent = MutableStateFlow<EpgEvent?>(null)
    val focusedEvent: StateFlow<EpgEvent?> = _focusedEvent

    // Most recent per-channel event map, kept so the minute-ticker can recompute now/next
    // from it without re-fetching. Only touched from coroutines on viewModelScope, which are
    // confined to the main dispatcher for the assignment, so no synchronisation is needed.
    private var lastEventsByChannel: Map<String, List<EpgEvent>> = emptyMap()

    init {
        load()
        startClockTick()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            // Credentials/host load off the main thread at startup — wait for them before
            // hitting the API, otherwise the first request goes to a blank host.
            AppSettings.awaitReady()
            val initialChannelUuid = AppSettings.lastChannelUuid
            val windowStart = defaultWindowStart()
            val windowEnd = windowStart + WINDOW_HOURS * 3600L
            try {
                repository.getGuideDataFlow(windowStart, windowEnd).collect { data ->
                    // Resolve each channel's now/next here (on Default) rather than inside
                    // LazyColumn item composition, so scrolling and Up/Down keypresses never
                    // scan event lists on the main thread.
                    val nowNext = buildNowNextByChannel(data.eventsByChannel)

                    if (_focusedEvent.value == null) {
                        val startCh = initialChannelUuid
                            ?.let { uuid -> data.channels.firstOrNull { it.uuid == uuid } }
                            ?: data.channels.firstOrNull()
                        _focusedEvent.value = startCh?.let { ch ->
                            nowNext[ch.uuid]?.let { it.now ?: it.next }
                                ?: data.eventsByChannel[ch.uuid]?.firstOrNull()
                        }
                    }
                    _state.value = TvGuideUiState(
                        channels = data.channels,
                        eventsByChannel = data.eventsByChannel,
                        nowNextByChannel = nowNext,
                        initialChannelUuid = initialChannelUuid,
                        previewChannelUuid = _state.value.previewChannelUuid
                            ?: initialChannelUuid?.takeIf { uuid -> data.channels.any { it.uuid == uuid } }
                            ?: data.channels.firstOrNull()?.uuid,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /**
     * The guide flow completes once all EPG chunks are fetched, so nothing would otherwise
     * advance "now" as programmes end. Recompute now/next once a minute from the cached
     * events — off the main thread — so the list stays current without composition doing it.
     */
    private fun startClockTick() {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                val events = lastEventsByChannel
                if (events.isEmpty()) continue
                _state.value = _state.value.copy(nowNextByChannel = buildNowNextByChannel(events))
            }
        }
    }

    /**
     * Resolves the currently-airing programme and the following one for every channel on
     * [Dispatchers.Default]. Events per channel are already in chronological order, so the
     * live programme's successor is simply the next index. Results are value-equal to the
     * previous pass for unchanged channels, so Compose skips those rows.
     */
    private suspend fun buildNowNextByChannel(
        eventsByChannel: Map<String, List<EpgEvent>>
    ): Map<String, NowNext> = withContext(Dispatchers.Default) {
        val nowSecs = System.currentTimeMillis() / 1000L
        val out = HashMap<String, NowNext>(eventsByChannel.size)
        for ((uuid, events) in eventsByChannel) {
            val nowIdx = events.indexOfFirst { nowSecs in it.start..it.stop }
            val now = events.getOrNull(nowIdx)
            val next = if (nowIdx >= 0) events.getOrNull(nowIdx + 1)
                       else events.firstOrNull { it.start > nowSecs }
            out[uuid] = NowNext(now, next)
        }
        lastEventsByChannel = eventsByChannel
        out
    }

    fun onEventFocused(event: EpgEvent?) {
        _focusedEvent.value = event
    }
}
