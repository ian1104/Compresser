package com.ian1104.compresser

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private val selectedUris = mutableListOf<Uri>()
    private lateinit var selectedText: TextView
    private lateinit var qualityBar: SeekBar
    private lateinit var qualityValue: TextView
    private lateinit var detailText: TextView
    private lateinit var estimateText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var shareButton: Button
    private var workId: java.util.UUID? = null
    private var lastOutputUris: Array<String> = emptyArray()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        selectedUris.clear()
        selectedUris.addAll(uris)
        uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        selectedText.text = "선택한 영상 ${uris.size}개 · 총 ${formatBytes(uris.sumOf { querySize(it) })}\n" + uris.take(4).joinToString("\n") { displayName(it) } + if (uris.size > 4) "\n…" else ""
        updateEstimate()
        startButton.isEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 24) }
        val title = TextView(this).apply { text = "Compresser"; textSize = 30f; gravity = Gravity.CENTER }
        val subtitle = TextView(this).apply { text = "하드웨어 가속 기반 영상 압축"; textSize = 14f; gravity = Gravity.CENTER }
        val pickButton = Button(this).apply { text = "영상 선택 · 여러 개 가능"; setOnClickListener { picker.launch(arrayOf("video/*")) } }
        selectedText = TextView(this).apply { text = "선택한 영상이 없습니다."; textSize = 14f }

        val qualityLabel = TextView(this).apply { text = "압축 강도"; textSize = 17f }
        qualityValue = TextView(this).apply { textSize = 25f; gravity = Gravity.CENTER }
        qualityBar = SeekBar(this).apply {
            max = 99
            progress = 49
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateQualityText(progress + 1); updateEstimate() }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        detailText = TextView(this).apply { textSize = 13f }
        estimateText = TextView(this).apply { textSize = 14f }

        resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("해상도 · 자동", "480p", "720p", "1080p", "최대 4K"))
            setOnItemSelectedListener(SimpleItemSelected { updateEstimate() })
        }
        codecSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("H.264 · 호환성 우선", "H.265/HEVC · 용량 우선"))
        }

        startButton = Button(this).apply { text = "압축 시작"; isEnabled = false; setOnClickListener { startCompression() } }
        cancelButton = Button(this).apply { text = "작업 취소"; isEnabled = false; setOnClickListener { workId?.let { WorkManager.getInstance(this@MainActivity).cancelWorkById(it) } } }
        shareButton = Button(this).apply { text = "완료 파일 공유"; isEnabled = false; setOnClickListener { shareResults() } }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        progressText = TextView(this).apply { text = "0%"; textSize = 14f }
        statusText = TextView(this).apply { text = "대기 중"; textSize = 14f }

        root.addView(title, LinearLayout.LayoutParams(-1, 52))
        root.addView(subtitle, LinearLayout.LayoutParams(-1, 38))
        root.addView(pickButton, LinearLayout.LayoutParams(-1, 56))
        root.addView(selectedText, LinearLayout.LayoutParams(-1, 92))
        root.addView(qualityLabel, LinearLayout.LayoutParams(-1, 32))
        root.addView(qualityValue, LinearLayout.LayoutParams(-1, 38))
        root.addView(qualityBar, LinearLayout.LayoutParams(-1, 48))
        root.addView(detailText, LinearLayout.LayoutParams(-1, 110))
        root.addView(estimateText, LinearLayout.LayoutParams(-1, 52))
        root.addView(resolutionSpinner, LinearLayout.LayoutParams(-1, 54))
        root.addView(codecSpinner, LinearLayout.LayoutParams(-1, 54))
        root.addView(startButton, LinearLayout.LayoutParams(-1, 56))
        root.addView(cancelButton, LinearLayout.LayoutParams(-1, 56))
        root.addView(shareButton, LinearLayout.LayoutParams(-1, 56))
        root.addView(progress, LinearLayout.LayoutParams(-1, 30))
        root.addView(progressText, LinearLayout.LayoutParams(-1, 28))
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateQualityText(50)
    }

    private fun updateQualityText(value: Int) {
        val q = value.coerceIn(1, 100)
        qualityValue.text = "$q / 100"
        val ratio = 15.0 + (q - 1) * 75.0 / 99.0
        val resolution = when {
            q <= 20 -> "480p급 이하로 적극 축소"
            q <= 50 -> "720p급까지 허용"
            q <= 80 -> "1080p급까지 허용"
            else -> "원본 해상도 우선, 최대 4K"
        }
        val bitrate = when {
            q <= 20 -> "약 0.35~1.5 Mbps 상한"
            q <= 50 -> "약 0.8~4 Mbps 상한"
            q <= 80 -> "약 2~8 Mbps 상한"
            else -> "약 4~16 Mbps 상한"
        }
        val quality = when {
            q <= 20 -> "화질 손실 큼 · 용량 최소화"
            q <= 50 -> "화질/용량 균형"
            q <= 80 -> "화질 우선"
            else -> "화질 손실 최소화"
        }
        detailText.text = "현재 목표: 원본의 약 ${ratio.toInt()}%\n$quality\n해상도: $resolution\n비트레이트: $bitrate · VBR\n오디오: 기본 AAC 계열 처리 · 실제 결과 크기는 영상 내용에 따라 달라짐"
    }

    private fun updateEstimate() {
        if (!::estimateText.isInitialized || selectedUris.isEmpty()) return
        val original = selectedUris.sumOf { querySize(it) }
        val q = qualityBar.progress + 1
        val ratio = (15.0 + (q - 1) * 75.0 / 99.0) / 100.0
        val estimated = (original * ratio).toLong()
        val reduction = max(0.0, (1.0 - ratio) * 100.0)
        estimateText.text = "예상 결과: ${formatBytes(estimated)} · 약 ${reduction.toInt()}% 감소\n※ 1-pass 인코딩 특성상 실제 크기는 오차가 있을 수 있음"
    }

    private fun startCompression() {
        if (selectedUris.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<CompressionWorker>().setInputData(workDataOf(
            CompressionWorker.KEY_URIS to selectedUris.map(Uri::toString).toTypedArray(),
            CompressionWorker.KEY_QUALITY to (qualityBar.progress + 1),
            CompressionWorker.KEY_RESOLUTION to resolutionSpinner.selectedItemPosition,
            CompressionWorker.KEY_CODEC to codecSpinner.selectedItemPosition
        )).build()
        workId = request.id
        WorkManager.getInstance(this).enqueue(request)
        startButton.isEnabled = false
        cancelButton.isEnabled = true
        shareButton.isEnabled = false
        observeWork(request.id)
    }

    private fun observeWork(id: java.util.UUID) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info == null) return@observe
            val p = info.progress.getInt("progress", 0).coerceIn(0, 100)
            progress.progress = p
            progressText.text = "$p%"
            val file = info.progress.getString("file") ?: ""
            val local = info.progress.getInt("fileProgress", p)
            statusText.text = when (info.state) {
                WorkInfo.State.ENQUEUED -> "대기 중…"
                WorkInfo.State.RUNNING -> if (file.isBlank()) "압축 준비 중…" else "압축 중 · $file · $local%"
                WorkInfo.State.SUCCEEDED -> {
                    lastOutputUris = info.outputData.getStringArray(CompressionWorker.KEY_OUTPUT_URIS) ?: emptyArray()
                    shareButton.isEnabled = lastOutputUris.isNotEmpty()
                    val before = info.outputData.getLong(CompressionWorker.KEY_ORIGINAL_BYTES, 0L)
                    val after = info.outputData.getLong(CompressionWorker.KEY_OUTPUT_BYTES, 0L)
                    if (before > 0 && after > 0) "완료 · ${formatBytes(before)} → ${formatBytes(after)} · 약 ${(100.0 - after * 100.0 / before).toInt()}% 감소" else "완료 · Movies/Compresser에 저장됨"
                }
                WorkInfo.State.CANCELLED -> "취소됨"
                WorkInfo.State.FAILED -> "실패 · ${info.outputData.getString(CompressionWorker.KEY_ERROR) ?: "알 수 없는 오류"}"
                else -> "처리 중…"
            }
            if (info.state.isFinished) {
                cancelButton.isEnabled = false
                startButton.isEnabled = selectedUris.isNotEmpty()
                if (info.state == WorkInfo.State.SUCCEEDED) Toast.makeText(this, "압축이 완료되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareResults() {
        if (lastOutputUris.isEmpty()) return
        val uris = lastOutputUris.map(Uri::parse)
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "압축 파일 공유"))
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0L
    }
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment ?: "video"
    }
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble(); var i = -1
        do { value /= 1024.0; i++ } while (value >= 1024 && i < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }
    private class SimpleItemSelected(private val action: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
