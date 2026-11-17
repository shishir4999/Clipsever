package app.clipsaver

/**
 * One thing the user can pick in the format sheet: a resolution or an audio format.
 * [args] are extra yt-dlp options as (flag, value) pairs; value == null means a bare flag.
 */
data class DownloadOption(
    val id: String,
    val label: String,
    val detail: String,
    val isAudio: Boolean,
    val args: List<Pair<String, String?>>,
)

data class VideoDetails(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val durationSec: Long,
    val videoOptions: List<DownloadOption>,
    val audioOptions: List<DownloadOption>,
)

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadJob(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val option: DownloadOption,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f,
    val etaSeconds: Long = -1L,
    val message: String? = null,
    val savedUri: String? = null,
)
