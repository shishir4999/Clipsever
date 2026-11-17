package app.clipsaver

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val input: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** When not null, the format sheet is shown for this video. */
    val details: VideoDetails? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val jobs: StateFlow<List<DownloadJob>> = Downloads.jobs

    private var fetchJob: Job? = null

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text, error = null) }
    }

    /** Called when another app shares a link to ClipSaver. */
    fun onSharedText(text: String) {
        val url = extractUrl(text) ?: return
        _state.update { it.copy(input = url, error = null) }
        fetch()
    }

    fun fetch() {
        val app = getApplication<Application>()
        val url = extractUrl(_state.value.input)
        if (url == null) {
            _state.update { it.copy(error = app.getString(R.string.error_bad_link)) }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, details = null) }
            try {
                val engine = Engine.state.first { it !is EngineState.Loading }
                if (engine is EngineState.Failed) throw IllegalStateException(engine.reason)
                val details = Downloader.fetchInfo(app, url)
                _state.update { it.copy(loading = false, details = details) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val reason = (t.message ?: t.javaClass.simpleName).trim().take(500)
                _state.update {
                    it.copy(loading = false, error = app.getString(R.string.error_generic, reason))
                }
            }
        }
    }

    fun dismissSheet() {
        _state.update { it.copy(details = null) }
    }

    fun download(option: DownloadOption) {
        val details = _state.value.details ?: return
        Downloads.enqueue(getApplication<Application>(), details, option)
        _state.update { it.copy(details = null) }
    }

    fun cancel(id: String) {
        viewModelScope.launch(Dispatchers.IO) { Downloads.cancel(id) }
    }

    fun remove(id: String) {
        Downloads.remove(id)
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value
}
