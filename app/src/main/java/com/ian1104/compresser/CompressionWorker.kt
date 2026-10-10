package com.ian1104.compresser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
        const val KEY_QUALITY = "quality"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_CODEC = "codec"
        const val KEY_OUTPUT_URIS = "outputUris"
        const val KEY_ORIGINAL_BYTES = "originalBytes"
        const val KEY_OUTPUT_BYTES = "outputBytes"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "compression"
        private const val NOTIFICATION_ID = 42
    }

    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray(KEY_URIS)?.map(Uri::parse).orEmpty()
        val quality = inputData.getInt(KEY_QUALITY, 50).coerceIn(1, 100)
        val resolution = inputData.getInt(KEY_RESOLUTION, 0)
        val codec = inputData.getInt(KEY_CODEC, 0)
        if (uris.isEmpty()) return Result.failure(workDataOf(KEY_ERROR to "선택된 영상이 없습니다."))

        createChannel()
        setForeground(makeForeground(0, "압축 준비 중…"))

        val outputUris = mutableListOf<String>()
        var originalTotal = 0L
        var outputTotal = 0L

        uris.forEachIndexed { index, uri ->
            if (isStopped) return Result.failure(workDataOf(KEY_ERROR to "작업이 취소되었습니다."))
            try {
                val result = compressOne(uri, quality, resolution, codec, index, uris.size)
                outputUris += result.outputUri.toString()
                originalTotal += result.originalBytes
                outputTotal += result.outputBytes
            } catch (e: Exception) {
                return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "알 수 없는 오류")))
            }
        }

        updateNotification(100, "압축 완료")
        return Result.success(workDataOf(
            KEY_OUTPUT_URIS to outputUris.toTypedArray(),
            KEY_ORIGINAL_BYTES to originalTotal,
            KEY_OUTPUT_BYTES to outputTotal
        ))
    }

    private data class CompressionResult(
        val outputUri: Uri,
        val originalBytes: Long,
        val outputBytes: Long
    )

    private suspend fun compressOne(
        uri: Uri,
        quality: Int,
        resolution: Int,
        codec: Int,
        index: Int,
        total: Int
    ): CompressionResult {
        val meta = readMeta(uri)
        val originalBytes = max(1L, meta.size)
        val durationSeconds = max(1.0, meta.durationMs / 1000.0)
        val targetRatio = 0.15 + (quality - 1) / 99.0 * 0.75

        val qualityMaxLongEdge = when {
            quality <= 20 -> 854
            quality <= 50 -> 1280
            quality <= 80 -> 1920
            else -> 3840
        }
        val requestedMaxLongEdge = when (resolution) {
            1 -> 854
            2 -> 1280
            3 -> 1920
            4 -> 3840
            else -> qualityMaxLongEdge
        }
        val maxLongEdge = min(qualityMaxLongEdge, requestedMaxLongEdge)
        val targetBytes = max(512L * 1024L, (originalBytes * targetRatio).toLong())
        val audioBits = durationSeconds * 128_000.0
        val rawBitrate = ((targetBytes * 8.0 - audioBits) / durationSeconds).toInt()
        val maxBitrate = when {
            quality <= 20 -> 1_500_000
            quality <= 50 -> 4_000_000
            quality <= 80 -> 8_000_000
            else -> 16_000_000
        }
        val bitrate = min(maxBitrate, max(350_000, rawBitrate))
        val scale = if (max(meta.width, meta.height) > maxLongEdge) {
            maxLongEdge.toFloat() / max(meta.width, meta.height).toFloat()
        } else {
            1f
        }

        val temp = File(applicationContext.cacheDir, "compress_${System.currentTimeMillis()}_$index.mp4")
        if (temp.exists()) temp.delete()

        withContext(Dispatchers.Main.immediate) {
            val settings = VideoEncoderSettings.Builder()
                .setBitrate(bitrate)
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(applicationContext)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(settings)
                .build()

            val videoEffects = if (scale < 0.999f) {
                listOf(ScaleAndRotateTransformation.Builder().setScale(scale, scale).build())
            } else {
                emptyList()
            }

            val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(applicationContext)
                .setVideoMimeType(if (codec == 1) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (!done.isCompleted) done.complete(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        if (!done.isCompleted) done.completeExceptionally(exception)
                    }
                })
                .build()

            try {
                transformer.start(edited, temp.absolutePath)
                val holder = ProgressHolder()

                while (!done.isCompleted) {
                    if (isStopped) {
                        transformer.cancel()
                        break
                    }

                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val local = holder.progress.coerceIn(0, 100)
                        val overall = (index * 100 + local) / total
                        setProgress(workDataOf(
                            "progress" to overall,
                            "file" to displayName(uri),
                            "fileProgress" to local,
                            "fileIndex" to index + 1,
                            "fileTotal" to total,
                            "targetRatio" to targetRatio
                        ))
                        updateNotification(overall, "${index + 1}/$total · ${displayName(uri)} · $local%")
                    }
                    delay(300)
                }
                done.await()
            } finally {
                if (!done.isCompleted) transformer.cancel()
            }
        }

        if (isStopped) throw IllegalStateException("작업이 취소되었습니다.")
        if (!temp.exists() || temp.length() == 0L) {
            throw IllegalStateException("출력 파일이 생성되지 않았습니다.")
        }

        val output = publishToGallery(temp, displayName(uri))
        temp.delete()
        return CompressionResult(output, originalBytes, querySize(output))
    }

    private suspend fun publishToGallery(temp: File, originalName: String): Uri = withContext(Dispatchers.IO) {
        val base = originalName.substringBeforeLast('.', "video")
        val outputName = "${base}_compressed.mp4"
        val resolver = applicationContext.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        resolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME}=?",
            arrayOf(outputName),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                resolver.delete(
                    ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    null,
                    null
                )
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Compresser")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val outputUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("저장 위치를 만들 수 없습니다.")

        try {
            resolver.openOutputStream(outputUri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("출력 파일을 저장할 수 없습니다.")

            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(
                    outputUri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            outputUri
        } catch (e: Exception) {
            resolver.delete(outputUri, null, null)
            throw e
        }
    }

    private data class Meta(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val size: Long
    )

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
        } finally {
            r.release()
        }
    }

    private fun querySize(uri: Uri): Long {
        applicationContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0).coerceAtLeast(0L)
        }
        return 0L
    }

    private fun displayName(uri: Uri): String {
        applicationContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        return "video.mp4"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            applicationContext
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Video compression",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
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
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun updateNotification(progress: Int, text: String) {
        setForeground(makeForeground(progress.coerceIn(0, 100), text))
    }
}
