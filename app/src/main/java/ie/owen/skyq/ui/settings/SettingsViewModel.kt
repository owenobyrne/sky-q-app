package ie.owen.skyq.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.discovery.TvHeadendDiscovery
import ie.owen.skyq.data.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    var host     by mutableStateOf(AppSettings.serverHost)
    var port     by mutableStateOf(AppSettings.serverPort.toString())
    var username by mutableStateOf(AppSettings.username)
    var password by mutableStateOf(AppSettings.password)

    private val _discovered = MutableStateFlow<List<TvHeadendDiscovery.Server>>(emptyList())
    val discovered: StateFlow<List<TvHeadendDiscovery.Server>> = _discovered

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    fun scan() {
        _discovered.value = emptyList()
        _scanning.value = true
        viewModelScope.launch {
            _discovered.value = TvHeadendDiscovery(getApplication()).scan()
            _scanning.value = false
        }
    }

    fun selectServer(server: TvHeadendDiscovery.Server) {
        host = server.host
        port = server.port.toString()
    }

    fun save() {
        val portInt = port.toIntOrNull() ?: 9981
        AppSettings.setServerConfig(host.trim(), portInt, username, password)
        TvHeadendClient.apply { }   // isStale() will trigger rebuild on next api/client access
        viewModelScope.launch {
            _saved.value = true
            delay(2_000)
            _saved.value = false
        }
    }

    override fun onCleared() = super.onCleared()
}
