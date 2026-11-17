package app.clipsaver

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** The download queue shared by the screen and the background service. Newest job first. */
object Downloads {
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    fun get(id: String): DownloadJob? = _jobs.value.firstOrNull { it.id == id }

    /** Oldest job that is still waiting (the list is newest-first). */
    fun nextQueued(): DownloadJob? = _jobs.value.lastOrNull { it.status == JobStatus.QUEUED }

    fun enqueue(context: Context, details: VideoDetails, option: DownloadOption) {
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            url = details.url,
            title = details.title,
            thumbnail = details.thumbnail,
            option = option,
        )
        _jobs.update { listOf(job) + it }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun modify(id: String, change: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id && it.status != JobStatus.RUNNING } }
    }

    fun cancel(id: String) {
        val job = get(id) ?: return
        when (job.status) {
            JobStatus.QUEUED -> modify(id) { it.copy(status = JobStatus.CANCELLED) }
            JobStatus.RUNNING -> {
                modify(id) { it.copy(status = JobStatus.CANCELLED) }
                try {
                    YoutubeDL.getInstance().destroyProcessById(id)
                } catch (_: Throwable) {
                }
            }
            else -> Unit
        }
    }
}
