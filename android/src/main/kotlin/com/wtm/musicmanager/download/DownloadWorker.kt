package com.wtm.musicmanager.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wtm.musicmanager.data.SyncUpsertQueries
import com.wtm.musicmanager.domain.model.DownloadState
import com.wtm.musicmanager.network.MusicManagerApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readBytes
import java.io.File

/**
 * Single-track downloader.
 *
 * Reads from `/api/stream/{trackId}` via the same Ktor HttpClient that
 * MusicManagerApi uses (so bearer auth + logging just work) and writes
 * to:
 *
 *   {context.cacheDir}/files/{Artist}/{Album}/{Title}.{ext}
 *
 * The row is updated to [DownloadState.Downloaded] on success, or
 * [DownloadState.Failed] on any throw. Phase 3.A is intentionally
 * simple: ONE track per worker, ONE attempt, no partial-file tracking.
 * The backend's local library is small enough that a plain GET with
 * bearer auth fits in memory for any single track. Phase 4+ can add
 * chunked + resume + retry/backoff if it turns out to matter.
 */
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: MusicManagerApi,
    private val client: HttpClient,
    private val queries: SyncUpsertQueries,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val trackIdRaw = inputData.getLong(KEY_TRACK_ID, -1L)
        if (trackIdRaw < 0L) return Result.failure()

        val trackTitle = inputData.getString(KEY_TITLE).orEmpty()
        val albumTitle = inputData.getString(KEY_ALBUM_TITLE).orEmpty()
        val artistName = inputData.getString(KEY_ARTIST_NAME).orEmpty()
        val extension = inputData.getString(KEY_EXTENSION) ?: "mp3"

        val targetFile = targetFileFor(applicationContext, artistName, albumTitle, trackTitle, extension)
        val targetPath = targetFile.absolutePath

        return try {
            val response = client.get(api.streamUrl(trackIdRaw))
            val channel: ByteReadChannel = response.bodyAsChannel()
            val bytes = channel.readRemaining().readBytes()

            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(bytes)

            queries.updateTrackDownload(
                trackId = trackIdRaw,
                localPath = targetPath,
                state = DownloadState.Downloaded,
            )
            setProgress(workDataOf(KEY_PROGRESS to 100))
            Result.success()
        } catch (e: Throwable) {
            targetFile.delete()
            queries.updateTrackDownload(
                trackId = trackIdRaw,
                localPath = null,
                state = DownloadState.Failed,
            )
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "download failed")))
        }
    }

    companion object {
        const val KEY_TRACK_ID = "trackId"
        const val KEY_TITLE = "title"
        const val KEY_ALBUM_TITLE = "albumTitle"
        const val KEY_ARTIST_NAME = "artistName"
        const val KEY_EXTENSION = "extension"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        /**
         * Build the canonical local file path for a track. Layout
         * mirrors the backend's on-disk layout:
         *
         *   {cacheDir}/files/{Artist}/{Album}/{Title}.{ext}
         *
         * Invalid path chars are sanitized to '_'. Sanitization is
         * minimal — the backend already returns valid filenames from
         * its metadata step so this is belt-and-suspenders.
         */
        fun targetFileFor(
            context: Context,
            artist: String,
            album: String,
            title: String,
            extension: String,
        ): File {
            val sanitizer = Regex("[\\\\/:*?\"<>|]")
            fun safe(s: String) = sanitizer.replace(s, "_").trim().ifBlank { "_" }
            val dir = File(context.cacheDir, "files/${safe(artist)}/${safe(album)}")
            return File(dir, "${safe(title)}.${extension.trimStart('.').lowercase()}")
        }

        private fun workDataOf(vararg pairs: Pair<String, Any?>): androidx.work.Data {
            val builder = androidx.work.Data.Builder()
            pairs.forEach { (k, v) ->
                when (v) {
                    is Long -> builder.putLong(k, v)
                    is Int -> builder.putInt(k, v)
                    is String -> builder.putString(k, v)
                    is Boolean -> builder.putBoolean(k, v)
                    null -> {} // skip
                    else -> builder.putString(k, v.toString())
                }
            }
            return builder.build()
        }
    }
}