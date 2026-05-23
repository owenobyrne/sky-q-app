package ie.owen.skyq.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ie.owen.skyq.data.model.Channel
import ie.owen.skyq.data.model.EpgEvent
import ie.owen.skyq.data.repository.EpgRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TvGuideUiState(
    val channels: List<Channel> = emptyList(),
    val eventsByChannel: Map<String, List<EpgEvent>> = emptyMap(),
    val windowStart: Long = defaultWindowStart(),
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

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val windowStart = defaultWindowStart()
            val windowEnd = windowStart + 24 * 60 * 60L
            try {
                repository.getGuideDataFlow(windowStart, windowEnd).collect { data ->
                    if (_focusedEvent.value == null) {
                        _focusedEvent.value = data.channels.firstOrNull()?.let { ch ->
                            data.eventsByChannel[ch.uuid]?.firstOrNull { it.isLive }
                                ?: data.eventsByChannel[ch.uuid]?.firstOrNull()
                        }
                    }
                    _state.value = TvGuideUiState(
                        channels = data.channels,
                        eventsByChannel = data.eventsByChannel,
                        windowStart = windowStart,
                        previewChannelUuid = _state.value.previewChannelUuid
                            ?: data.channels.firstOrNull()?.uuid,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun onEventFocused(event: EpgEvent?) {
        _focusedEvent.value = event
    }
}
