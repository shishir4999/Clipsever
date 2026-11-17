package app.clipsaver

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EngineState {
    data object Loading : EngineState
    data object Ready : EngineState
    data class Failed(val reason: String) : EngineState
}

/**
 * Owns the yt-dlp engine: first-run setup (unpacks Python + FFmpeg) and updates.
 * YouTube changes often, so the engine tries to update itself once per app start.
 */
object Engine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<EngineState>(EngineState.Loading)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    private val _updateResult = MutableStateFlow<String?>(null)
    val updateResult: StateFlow<String?> = _updateResult.asStateFlow()

    fun start(app: Application) {
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _state.value = EngineState.Ready
            } catch (t: Throwable) {
                _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
                return@launch
            }
            update(app)
        }
    }

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        scope.launch { update(appContext) }
    }

    private fun update(context: Context) {
        if (!_updating.compareAndSet(false, true)) return
        _updateResult.value = null
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context)
            _updateResult.value = context.getString(R.string.engine_updated)
        } catch (t: Throwable) {
            val reason = (t.message ?: t.javaClass.simpleName).take(200)
            _updateResult.value = context.getString(R.string.engine_update_failed, reason)
        } finally {
            _updating.value = false
        }
    }
}
