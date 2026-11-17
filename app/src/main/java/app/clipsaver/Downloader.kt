package app.clipsaver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

object Downloader {

    private val SKIP_EXT = setOf(
        "part", "ytdl", "json", "temp", "tmp", "jpg", "jpeg", "png", "webp",
        "vtt", "srt", "description",
    )

    // ---------------------------------------------------------------- info

    // Short-lived in-memory cache avoids repeating the expensive extraction when
    // the user pastes or opens the same video again during the same app session.
    private data class CachedDetails(val savedAt: Long, val details: VideoDetails)
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val CACHE_MAX_ENTRIES = 32
    private val infoCache = object : LinkedHashMap<String, CachedDetails>(CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDetails>?): Boolean =
            size > CACHE_MAX_ENTRIES
    }

    /** Extract metadata off the main thread; serialize access to the shared yt-dlp runtime. */
    suspend fun fetchInfo(context: Context, url: String): VideoDetails = withContext(Dispatchers.IO) {
        val key = url.trim()
        synchronized(infoCache) {
            infoCache[key]?.takeIf { SystemClock.elapsedRealtime() - it.savedAt < CACHE_TTL_MS }
                ?.let { return@withContext it.details }
        }

        Engine.withYtDlpLock {
            // Another request may have populated the cache while this one waited.
            synchronized(infoCache) {
                infoCache[key]?.takeIf { SystemClock.elapsedRealtime() - it.savedAt < CACHE_TTL_MS }
                    ?.let { return@withYtDlpLock it.details }
            }

            val request = YoutubeDLRequest(key)
            request.addOption("--dump-single-json")
            request.addOption("--no-playlist")
            request.addOption("--no-warnings")
            request.addOption("--skip-download")

            val response = YoutubeDL.getInstance().execute(request)
            val raw = response.out
            val start = raw.indexOf('{')
            if (start < 0) throw IOException("The site returned no data")
            val details = parse(context, key, JSONObject(raw.substring(start)))
            synchronized(infoCache) {
                infoCache[key] = CachedDetails(SystemClock.elapsedRealtime(), details)
            }
            details
        }
    }

    private fun parse(context: Context, url: String, json: JSONObject): VideoDetails {
        val formatArray = json.optJSONArray("formats")
        if ((formatArray == null || formatArray.length() == 0) && json.has("entries")) {
            throw IOException(context.getString(R.string.error_playlist))
        }

        val items = (0 until (formatArray?.length() ?: 0)).mapNotNull { formatArray?.optJSONObject(it) }
        val bestAudioSize = items
            .filter { !it.hasVideo() && it.hasAudio() }
            .maxOfOrNull { it.sizeBytes() } ?: 0L

        val byHeight = items.filter { it.hasVideo() }.groupBy { it.heightPx() }
        val videoOptions = byHeight.keys.sortedDescending().map { h ->
            val group = byHeight.getValue(h)
            val videoSize = group.maxOf { it.sizeBytes() }
            val hasMuxedAudio = group.any { it.hasAudio() }
            val estimate = if (videoSize > 0) videoSize + (if (hasMuxedAudio) 0L else bestAudioSize) else 0L
            val container = if (h > 1080) "MP4/MKV" else "MP4"
            DownloadOption(
                id = "v$h",
                label = "${h}p",
                detail = if (estimate > 0) "$container · ${formatSize(estimate)}" else container,
                isAudio = false,
                args = listOf(
                    "-f" to "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b",
                    "--merge-output-format" to "mp4/mkv",
                ),
            )
        }.ifEmpty {
            // Some sites give no resolution info: offer a single "best" choice.
            listOf(
                DownloadOption(
                    id = "best",
                    label = context.getString(R.string.opt_best),
                    detail = "MP4",
                    isAudio = false,
                    args = listOf(
                        "-f" to "bv*+ba/b",
                        "--merge-output-format" to "mp4/mkv",
                    ),
                ),
            )
        }

        val audioOptions = listOf(
            DownloadOption(
                id = "mp3_high",
                label = context.getString(R.string.opt_mp3_high),
                detail = "",
                isAudio = true,
                args = listOf(
                    "-f" to "ba/b",
                    "-x" to null,
                    "--audio-format" to "mp3",
                    "--audio-quality" to "0",
                ),
            ),
            DownloadOption(
                id = "mp3_128",
                label = context.getString(R.string.opt_mp3_128),
                detail = "",
                isAudio = true,
                args = listOf(
                    "-f" to "ba/b",
                    "-x" to null,
                    "--audio-format" to "mp3",
                    "--audio-quality" to "128K",
                ),
            ),
            DownloadOption(
                id = "m4a",
                label = context.getString(R.string.opt_m4a),
                detail = "",
                isAudio = true,
                args = listOf(
                    "-f" to "ba[ext=m4a]/ba/b",
                    "-x" to null,
                    "--audio-format" to "m4a",
                ),
            ),
        )

        return VideoDetails(
            url = url,
            title = json.str("title") ?: url,
            thumbnail = json.str("thumbnail"),
            uploader = json.str("uploader") ?: json.str("channel"),
            durationSec = json.num("duration"),
            videoOptions = videoOptions,
            audioOptions = audioOptions,
        )
    }

    // ------------------------------------------------------------ download

    /**
     * Downloads one job into a private temp folder, then copies the finished file
     * into the public Downloads/ClipSaver folder. Returns the saved file's Uri.
     */
    suspend fun download(context: Context, job: DownloadJob, onProgress: () -> Unit): Uri =
        withContext(Dispatchers.IO) {
            Engine.withYtDlpLock {
            val base = context.getExternalFilesDir(null) ?: context.cacheDir
            val tmpDir = File(base, "tmp/${job.id}")
            tmpDir.mkdirs()
            try {
                val request = YoutubeDLRequest(job.url)
                request.addOption("--no-playlist")
                request.addOption("--no-mtime")
                request.addOption("-N", "4")
                request.addOption("-o", "${tmpDir.absolutePath}/%(title).80s [%(id)s].%(ext)s")
                for ((flag, value) in job.option.args) {
                    if (value == null) request.addOption(flag) else request.addOption(flag, value)
                }

                YoutubeDL.getInstance().execute(request, job.id) { progress, eta, _ ->
                    Downloads.modify(job.id) {
                        it.copy(
                            progress = (progress.toFloat() / 100f).coerceIn(0f, 1f),
                            etaSeconds = eta.toLong(),
                        )
                    }
                    onProgress()
                }

                val result = tmpDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) !in SKIP_EXT }
                    ?.maxByOrNull { it.length() }
                    ?: throw IOException("yt-dlp finished but no file was produced")
                saveToDownloads(context, result)
            } finally {
                tmpDir.deleteRecursively()
            }
            }
        }

    private fun saveToDownloads(context: Context, file: File): Uri {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ClipSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open the file for writing")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    // ------------------------------------------------------------- helpers

    fun formatSize(bytes: Long): String {
        val mb = maxOf(1.0, bytes / (1024.0 * 1024.0))
        return if (mb >= 1024) String.format(Locale.US, "~%.1f GB", mb / 1024.0)
        else String.format(Locale.US, "~%.0f MB", mb)
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.num(key: String): Long =
        if (isNull(key)) 0L else optLong(key, 0L)

    private fun JSONObject.heightPx(): Int = num("height").toInt()

    private fun JSONObject.hasVideo(): Boolean = heightPx() > 0 && str("vcodec") != "none"

    private fun JSONObject.hasAudio(): Boolean {
        val codec = str("acodec")
        return codec != null && codec != "none"
    }

    private fun JSONObject.sizeBytes(): Long {
        val exact = num("filesize")
        return if (exact > 0) exact else num("filesize_approx")
    }
}
