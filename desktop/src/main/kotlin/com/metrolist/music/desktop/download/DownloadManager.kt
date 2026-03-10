package com.metrolist.music.desktop.download

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DownloadProgress(
    val songId: String,
    val songTitle: String,
    val progress: Int,
    val status: DownloadStatus,
    val error: String? = null
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    ERROR,
    CANCELLED
}

object DownloadManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    private val _downloadQueue = MutableStateFlow<List<SongInfo>>(emptyList())
    val downloadQueue: StateFlow<List<SongInfo>> = _downloadQueue.asStateFlow()

    private var isProcessing = false
    private val downloadJobs = mutableMapOf<String, Job>()

    private val downloadDir: File
        get() {
            val dir = PreferencesManager.getDownloadDirectory()
            if (!dir.exists()) Files.createDirectories(dir.toPath())
            return dir
        }

    /**
     * Generate a download file path using "Artist - Title.m4a" naming.
     * Falls back to songId.m4a if artist/title are unavailable.
     * Handles filename conflicts by appending (2), (3), etc.
     */
    fun getDownloadPath(song: SongInfo): File {
        val baseName = sanitizeFilename("${song.artist} - ${song.title}")
        var candidate = File(downloadDir, "$baseName.m4a")
        var counter = 2
        while (candidate.exists() && !isSameSong(candidate, song.id)) {
            candidate = File(downloadDir, "$baseName ($counter).m4a")
            counter++
        }
        return candidate
    }

    /** Legacy path lookup by songId — checks DB localPath first, then old songId.m4a format. */
    fun getDownloadPathById(songId: String): File? {
        val dbPath: String? = DatabaseHelper.getSongLocalPath(songId)
        if (dbPath != null) {
            val f = File(dbPath)
            if (f.exists()) return f
        }
        // Fallback: old naming scheme
        val legacyFile = File(downloadDir, "$songId.m4a")
        if (legacyFile.exists()) return legacyFile
        return null
    }

    fun isDownloaded(songId: String): Boolean {
        return getDownloadPathById(songId) != null
    }

    private fun sanitizeFilename(name: String): String {
        // Remove characters illegal in Windows/Mac/Linux filenames
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200) // Avoid exceeding filesystem limits
            .ifBlank { "Unknown" }
    }

    /** Check if an existing file belongs to the same song (by checking DB). */
    private fun isSameSong(file: File, songId: String): Boolean {
        val dbPath: String = DatabaseHelper.getSongLocalPath(songId) ?: return false
        return File(dbPath).absolutePath == file.absolutePath
    }

    fun queueDownload(song: SongInfo) {
        // Add to database queue
        DatabaseHelper.addToDownloadQueue(song.id)

        // Also save song info to database if not exists
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration,
            thumbnailUrl = song.thumbnailUrl,
            albumName = song.album
        )

        // Add to in-memory queue
        _downloadQueue.update { queue ->
            if (queue.none { it.id == song.id }) {
                queue + song
            } else queue
        }

        // Update progress state
        _activeDownloads.update { downloads ->
            downloads + (song.id to DownloadProgress(
                songId = song.id,
                songTitle = song.title,
                progress = 0,
                status = DownloadStatus.PENDING
            ))
        }

        // Start processing if not already
        if (!isProcessing) {
            processQueue()
        }
    }

    fun queueDownloads(songs: List<SongInfo>) {
        songs.forEach { queueDownload(it) }
    }

    fun cancelDownload(songId: String) {
        downloadJobs[songId]?.cancel()
        downloadJobs.remove(songId)

        DatabaseHelper.removeFromDownloadQueue(songId)

        _downloadQueue.update { queue ->
            queue.filter { it.id != songId }
        }

        _activeDownloads.update { downloads ->
            downloads - songId
        }
    }

    fun cancelAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()

        _downloadQueue.value.forEach { song ->
            DatabaseHelper.removeFromDownloadQueue(song.id)
        }

        _downloadQueue.value = emptyList()
        _activeDownloads.value = emptyMap()
        isProcessing = false
    }

    fun deleteDownload(songId: String) {
        val file = getDownloadPathById(songId)
        if (file != null && file.exists()) {
            file.delete()
        }
        DatabaseHelper.updateSongDownloaded(songId, false, null)
    }

    private fun processQueue() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            while (_downloadQueue.value.isNotEmpty()) {
                val song = _downloadQueue.value.firstOrNull() ?: break

                // Update status to downloading
                _activeDownloads.update { downloads ->
                    downloads + (song.id to DownloadProgress(
                        songId = song.id,
                        songTitle = song.title,
                        progress = 0,
                        status = DownloadStatus.DOWNLOADING
                    ))
                }
                DatabaseHelper.updateDownloadProgress(song.id, 0, "downloading")

                val job = launch {
                    try {
                        downloadSong(song)
                    } catch (e: CancellationException) {
                        // Download was cancelled
                        _activeDownloads.update { downloads ->
                            downloads + (song.id to DownloadProgress(
                                songId = song.id,
                                songTitle = song.title,
                                progress = 0,
                                status = DownloadStatus.CANCELLED
                            ))
                        }
                    } catch (e: Exception) {
                        handleDownloadError(song, e.message ?: "Unknown error")
                    }
                }

                downloadJobs[song.id] = job
                job.join()
                downloadJobs.remove(song.id)

                // Remove from queue
                _downloadQueue.update { queue ->
                    queue.filter { it.id != song.id }
                }
            }

            isProcessing = false
        }
    }

    private suspend fun downloadSong(song: SongInfo) {
        Timber.d("Downloading song: id=${song.id}, artist=${song.artist}, title=${song.title}")

        // Get stream URL
        val streamUrl = getStreamUrl(song.id) ?: throw Exception("Failed to get stream URL")

        val outputFile = getDownloadPath(song)
        val tempFile = File(downloadDir, "${song.id}.tmp")
        Timber.d("Download output: ${outputFile.absolutePath}")

        try {
            val url = URL(streamUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            // Some YouTube CDN endpoints need a user-agent
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            val totalSize = connection.contentLengthLong
            var downloadedSize = 0L
            var lastProgressUpdate = 0

            connection.inputStream.buffered(262144).use { input ->
                FileOutputStream(tempFile).use { fos ->
                    val output = fos.buffered(262144) // 256KB write buffer
                    val buffer = ByteArray(65536) // 64KB read chunks
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        val progress = if (totalSize > 0) {
                            ((downloadedSize * 100) / totalSize).toInt()
                        } else {
                            -1
                        }

                        // Only update UI/DB when progress changes by at least 1%
                        if (progress != lastProgressUpdate) {
                            lastProgressUpdate = progress
                            _activeDownloads.update { downloads ->
                                downloads + (song.id to DownloadProgress(
                                    songId = song.id,
                                    songTitle = song.title,
                                    progress = progress,
                                    status = DownloadStatus.DOWNLOADING
                                ))
                            }
                            // Only write to DB every 5%
                            if (progress % 5 == 0) {
                                DatabaseHelper.updateDownloadProgress(song.id, progress, "downloading")
                            }
                        }

                        // Check for cancellation periodically
                        yield()
                    }
                    output.flush()
                }
            }

            // Move temp file to final location (renameTo fails silently on Windows)
            Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

            // Update database
            DatabaseHelper.updateSongDownloaded(song.id, true, outputFile.absolutePath)
            DatabaseHelper.removeFromDownloadQueue(song.id)

            // Update status
            _activeDownloads.update { downloads ->
                downloads + (song.id to DownloadProgress(
                    songId = song.id,
                    songTitle = song.title,
                    progress = 100,
                    status = DownloadStatus.COMPLETED
                ))
            }

            // Remove from active downloads after a delay
            delay(2000)
            _activeDownloads.update { downloads ->
                downloads - song.id
            }

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private suspend fun getStreamUrl(videoId: String): String? {
        val clients = listOf(
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.IOS,
            YouTubeClient.WEB_REMIX
        )

        for (client in clients) {
            try {
                val playerResponse = YouTube.player(videoId, client = client).getOrNull()
                val format = playerResponse?.streamingData?.adaptiveFormats
                    ?.filter { it.isAudio }
                    ?.maxByOrNull { it.bitrate ?: 0 }

                if (format?.url != null) {
                    return format.url
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    private fun handleDownloadError(song: SongInfo, error: String) {
        _activeDownloads.update { downloads ->
            downloads + (song.id to DownloadProgress(
                songId = song.id,
                songTitle = song.title,
                progress = 0,
                status = DownloadStatus.ERROR,
                error = error
            ))
        }
        DatabaseHelper.updateDownloadError(song.id, error)
    }

    fun retryDownload(song: SongInfo) {
        // Remove from error state
        _activeDownloads.update { downloads ->
            downloads - song.id
        }

        // Re-queue
        queueDownload(song)
    }
}
