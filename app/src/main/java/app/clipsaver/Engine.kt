package app.clipsaver

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
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
 * Initialization is kept separate from updates so app startup stays fast.
 */
object Engine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ytDlpMutex = Mutex()
    private val updateRequested = AtomicBoolean(false)

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
        }
    }

    /**
     * Run yt-dlp operations through one lock. The embedded yt-dlp runtime is shared,
     * so an update must never race with metadata extraction or a download.
     */
    suspend fun <T> withYtDlpLock(block: suspend () -> T): T =
        ytDlpMutex.withLock { block() }

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        if (!updateRequested.compareAndSet(false, true)) return
        scope.launch {
            _updating.value = true
            _updateResult.value = null
            try {
                ytDlpMutex.withLock {
                    YoutubeDL.getInstance().updateYoutubeDL(appContext)
                }
                _updateResult.value = appContext.getString(R.string.engine_updated)
            } catch (t: Throwable) {
                val reason = (t.message ?: t.javaClass.simpleName).take(200)
                _updateResult.value = appContext.getString(R.string.engine_update_failed, reason)
            } finally {
                _updating.value = false
                updateRequested.set(false)
            }
        }
    }
}
