package ma.benaly.miniserre

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URL

data class UiState(
    val origin: String, val live: Live? = null, val control: Control? = null,
    val history: List<Point> = emptyList(), val error: String? = null,
    val busy: Boolean = false, val lastChange: Long = 0
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("serre", 0)
    private val _s = MutableStateFlow(UiState(prefs.getString("origin", DEFAULT)!!))
    val state = _s.asStateFlow()
    private var api = FirebaseApi(_s.value.origin)
    private var job: Job? = null

    init { start() }

    private fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            var tick = 0
            while (true) {
                refresh()
                if (tick++ % 30 == 0) runCatching { api.history() }.onSuccess { h -> _s.update { it.copy(history = h) } }
                delay(2000)
            }
        }
    }

    private suspend fun refresh() {
        runCatching { api.live() to api.control() }.onSuccess { (l, c) ->
            _s.update {
                it.copy(live = l, control = c, error = null,
                    lastChange = if (l != it.live) System.currentTimeMillis() else it.lastChange)
            }
        }.onFailure { e -> _s.update { it.copy(error = e.message ?: "Erreur réseau") } }
    }

    fun send(key: String, value: Any) {
        if (_s.value.busy) return
        viewModelScope.launch {
            _s.update { it.copy(busy = true) }
            runCatching { api.set(key, value) }.onFailure { e -> _s.update { it.copy(error = "Commande refusée : ${e.message}") } }
            refresh()
            _s.update { it.copy(busy = false) }
        }
    }

    /** Returns an error message, or null on success. */
    fun connect(raw: String): String? {
        val u = runCatching { URL(raw.trim()) }.getOrNull() ?: return "URL invalide."
        val host = u.host.lowercase()
        if (u.protocol != "https" || !(host.endsWith(".firebasedatabase.app") || host.endsWith(".firebaseio.com")))
            return "Utilisez l’URL HTTPS de la Realtime Database."
        val origin = "https://$host"
        prefs.edit().putString("origin", origin).apply()
        api = FirebaseApi(origin)
        _s.value = UiState(origin)
        start()
        return null
    }

    companion object { const val DEFAULT = "https://miniserre-b4e07-default-rtdb.europe-west1.firebasedatabase.app" }
}
