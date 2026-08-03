package ie.owen.skyq.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.data.repository.EpgRepository
import ie.owen.skyq.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvGuideUiState(
    val channels: List<Channel> = emptyList(),
    val eventsByChannel: Map<String, List<EpgEvent>> = emptyMap(),
    /** Grid cells (programmes + gap fillers), pre-built off the main thread. */
    val cellsByChannel: Map<String, List<EpgCell>> = emptyMap(),
    val windowStart: Long = defaultWindowStart(),
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

    // Previous emission's inputs/outputs, used to reuse cell lists for channels whose events
    // didn't change between progressive emissions. Only touched from the single collect
    // coroutine below, so no synchronisation is needed.
    private var lastEventsByChannel: Map<String, List<EpgEvent>> = emptyMap()
    private var lastCellsByChannel: Map<String, List<EpgCell>> = emptyMap()

    init {
        load()
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
                    // buildCells filters + sorts + fills gaps for every channel. Doing it
                    // here (on Default) rather than inside LazyColumn item composition keeps
                    // it off the main thread entirely.
                    val cells = buildCellsByChannel(data.eventsByChannel, windowStart, windowEnd)

                    if (_focusedEvent.value == null) {
                        val startCh = initialChannelUuid
                            ?.let { uuid -> data.channels.firstOrNull { it.uuid == uuid } }
                            ?: data.channels.firstOrNull()
                        _focusedEvent.value = startCh?.let { ch ->
                            data.eventsByChannel[ch.uuid]?.firstOrNull { it.isLive }
                                ?: data.eventsByChannel[ch.uuid]?.firstOrNull()
                        }
                    }
                    _state.value = TvGuideUiState(
                        channels = data.channels,
                        eventsByChannel = data.eventsByChannel,
                        cellsByChannel = cells,
                        windowStart = windowStart,
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
     * Builds the per-channel cell lists on [Dispatchers.Default], reusing the previous list
     * instance for any channel whose event list is unchanged. That keeps both the CPU cost
     * and the recomposition scope of a progressive emission proportional to what actually
     * changed rather than to the size of the whole grid.
     */
    private suspend fun buildCellsByChannel(
        eventsByChannel: Map<String, List<EpgEvent>>,
        windowStart: Long,
        windowEnd: Long
    ): Map<String, List<EpgCell>> = withContext(Dispatchers.Default) {
        val prevEvents = lastEventsByChannel
        val prevCells  = lastCellsByChannel
        val out = HashMap<String, List<EpgCell>>(eventsByChannel.size)
        for ((uuid, events) in eventsByChannel) {
            val reusable = if (prevEvents[uuid] === events) prevCells[uuid] else null
            out[uuid] = reusable ?: buildCells(events, windowStart, windowEnd)
        }
        lastEventsByChannel = eventsByChannel
        lastCellsByChannel = out
        out
    }

    fun onEventFocused(event: EpgEvent?) {
        _focusedEvent.value = event
    }
}
