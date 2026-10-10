package com.ian1104.compresser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.media3.common.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class CompressionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_URIS = "uris"
        const val KEY_PRESET = "preset"
        private const val CHANNEL_ID = "compression"
        private const val NOTIFICATION_ID = 42
    }

    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray(KEY_URIS)?.map(Uri::parse).orEmpty()
        val preset = inputData.getInt(KEY_PRESET, 1)
        if (uris.isEmpty()) return Result.failure()
        createChannel()
        setForeground(makeForeground(0, "압축 준비 중…"))

        val total = uris.size
        uris.forEachIndexed { index, uri ->
            if (isStopped) return Result.failure()
            try {
                compressOne(uri, preset, index, total)
            } catch (e: Exception) {
                updateNotification(index * 100 / total, "실패 · ${displayName(uri)}")
                return Result.failure(workDataOf("error" to (e.message ?: "알 수 없는 오류")))
            }
        }
        updateNotification(100, "압축 완료")
        return Result.success()
    }

    private suspend fun compressOne(uri: Uri, preset: Int, index: Int, total: Int) {
        val meta = readMeta(uri)
        val durationMs = max(1L, meta.durationMs)
        val originalBytes = max(1L, meta.size)
        val ratio = when (preset) { 0 -> 0.35; 1 -> 0.50; else -> 0.65 }
        val maxLongEdge = if (preset == 0) 1280 else 1920

        val targetBytes = max(2L * 1024L * 1024L, (originalBytes * ratio).toLong())
        val audioBits = durationMs / 1000.0 * 128_000.0
        val rawBitrate = ((targetBytes * 8.0 - audioBits) / (durationMs / 1000.0)).toInt()
        val maxBitrate = if (preset == 0) 4_000_000 else if (preset == 1) 8_000_000 else 12_000_000
        val bitrate = min(maxBitrate, max(500_000, rawBitrate))

        val scale = if (max(meta.width, meta.height) > maxLongEdge) {
            maxLongEdge.toFloat() / max(meta.width, meta.height).toFloat()
        } else 1f

        val temp = File(applicationContext.cacheDir, "compress_${System.currentTimeMillis()}_$index.mp4")
        if (temp.exists()) temp.delete()

        withContext(Dispatchers.Main.immediate) {
            val settings = VideoEncoderSettings.Builder()
                .setBitrate(bitrate)
                .setBitrateMode(VideoEncoderSettings.BitrateMode.BITRATE_MODE_VBR)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(applicationContext)
                .setEnableFallback(true)
                .setEnableFormatFallback(true)
                .setRequestedVideoEncoderSettings(settings)
                .build()

            val effects = if (scale < 0.999f) {
                listOf(ScaleAndRotateTransformation.Builder().setScale(scale, scale).build())
            } else emptyList()

            val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), effects))
                .build()

            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: Transformer.ExportResult) {
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    override fun onError(composition: Composition, result: Transformer.ExportResult, exception: ExportException) {
                        if (!done.isCompleted) done.completeExceptionally(exception)
                    }
                })
                .build()

            transformer.start(edited, temp.absolutePath)
            val holder = ProgressHolder()
            while (!done.isCompleted) {
                if (isStopped) {
                    transformer.cancel()
                    return@withContext
                }
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val local = holder.progress.coerceIn(0, 100)
                    val overall = (index * 100 + local) / total
                    setProgress(workDataOf("progress" to overall, "file" to displayName(uri), "fileProgress" to local))
                    updateNotification(overall, "${index + 1}/$total · ${displayName(uri)} · $local%")
                }
                delay(350)
            }
            done.await()
        }

        if (!temp.exists() || temp.length() == 0L) throw IllegalStateException("출력 파일이 생성되지 않았습니다.")
        publishToGallery(temp, displayName(uri))
        temp.delete()
    }

    private suspend fun publishToGallery(temp: File, originalName: String) = withContext(Dispatchers.IO) {
        val base = originalName.substringBeforeLast('.', "video")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_compressed_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Compresser")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("저장 위치를 만들 수 없습니다.")
        try {
            resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("출력 파일을 저장할 수 없습니다.")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private data class Meta(val width: Int, val height: Int, val durationMs: Long, val size: Long)

    private fun readMeta(uri: Uri): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(applicationContext, uri)
            Meta(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920,
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080,
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1,
                querySize(uri)
            )
        } finally { r.release() }
    }

    private fun querySize(uri: Uri): Long {
        applicationContext.contentResolver.query(uri, arrayOf("_size"), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 50L * 1024L * 1024L
    }

    private fun displayName(uri: Uri): String {
        applicationContext.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return "video.mp4"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Video compression", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun makeForeground(progress: Int, text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Compresser")
            .setContentText(text)
            .setProgress(100, progress, progress <= 0)
            .setOngoing(true)
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun updateNotification(progress: Int, text: String) {
        setForeground(makeForeground(progress.coerceIn(0, 100), text))
    }
}
