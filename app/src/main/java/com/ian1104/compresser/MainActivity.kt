package com.ian1104.compresser

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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

    private val bg = Color.rgb(13, 15, 18)
    private val card = Color.rgb(23, 26, 31)
    private val card2 = Color.rgb(29, 33, 39)
    private val text = Color.rgb(242, 244, 247)
    private val muted = Color.rgb(157, 165, 176)
    private val accent = Color.rgb(105, 170, 255)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        selectedUris.clear()
        selectedUris.addAll(uris)
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        selectedText.text = "${uris.size}개 선택됨  ·  ${formatBytes(uris.sumOf { querySize(it) })}\n" +
            uris.take(3).joinToString("\n") { "• ${displayName(it)}" } +
            if (uris.size > 3) "\n• 외 ${uris.size - 3}개" else ""
        updateEstimate()
        startButton.isEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = 0
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        buildUi()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 13f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(muted)
    }

    private fun cardBackground(color: Int = card): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            clipToPadding = false
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(18))
        }
        header.addView(TextView(this).apply {
            text = "Compresser"
            textSize = 30f
            setTextColor(text)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = "빠르고 간단한 영상 압축"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(header)

        val sourceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        sourceCard.addView(label("VIDEO", 11f))
        val pickButton = Button(this).apply {
            text = "영상 선택"
            textSize = 15f
            setTextColor(text)
            background = cardBackground(card2)
            setOnClickListener { picker.launch(arrayOf("video/*")) }
        }
        sourceCard.addView(pickButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        selectedText = TextView(this).apply {
            text = "선택한 영상이 없습니다."
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(2), dp(12), dp(2), 0)
            maxLines = 4
        }
        sourceCard.addView(selectedText)
        root.addView(sourceCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val qualityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val qualityHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        qualityHeader.addView(label("압축 강도", 16f), LinearLayout.LayoutParams(0, -2, 1f))
        qualityValue = TextView(this).apply {
            text = "50 / 100"
            textSize = 18f
            setTextColor(accent)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        qualityHeader.addView(qualityValue, LinearLayout.LayoutParams(dp(90), -2))
        qualityCard.addView(qualityHeader)
        qualityBar = SeekBar(this).apply {
            max = 99
            progress = 49
            setPadding(dp(4), 0, dp(4), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateQualityText(progress + 1)
                    updateEstimate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        qualityCard.addView(qualityBar, LinearLayout.LayoutParams(-1, dp(48)))
        detailText = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setLineSpacing(0f, 1.15f)
        }
        qualityCard.addView(detailText, LinearLayout.LayoutParams(-1, -2))
        root.addView(qualityCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val optionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        optionsCard.addView(label("OPTIONS", 11f))
        resolutionSpinner = makeSpinner(arrayOf("해상도 · 자동", "480p", "720p", "1080p", "최대 4K")) {
            updateEstimate()
        }
        codecSpinner = makeSpinner(arrayOf("H.264 · 호환성 우선", "H.265/HEVC · 용량 우선")) {}
        optionsCard.addView(resolutionSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        optionsCard.addView(codecSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(optionsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        resultCard.addView(label("RESULT", 11f))
        estimateText = TextView(this).apply {
            textSize = 13f
            setTextColor(text)
            setLineSpacing(0f, 1.15f)
        }
        resultCard.addView(estimateText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        resultCard.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(14) })
        progressText = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(accent)
            gravity = Gravity.END
            setPadding(0, dp(5), 0, 0)
        }
        resultCard.addView(progressText)
        statusText = TextView(this).apply {
            text = "대기 중"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(5), 0, 0)
            maxLines = 3
        }
        resultCard.addView(statusText)
        root.addView(resultCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        startButton = makeButton("압축 시작", accent, Color.rgb(10, 16, 24)) { startCompression() }
        startButton.isEnabled = false
        cancelButton = makeButton("작업 취소", card2, text) {
            workId?.let { WorkManager.getInstance(this@MainActivity).cancelWorkById(it) }
        }
        cancelButton.isEnabled = false
        shareButton = makeButton("완료 파일 공유", card2, text) { shareResults() }
        shareButton.isEnabled = false
        actionRow.addView(startButton, LinearLayout.LayoutParams(-1, dp(52)))
        actionRow.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        actionRow.addView(shareButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(actionRow)

        scroll.addView(root)
        setContentView(scroll)
        updateQualityText(50)
    }

    private fun makeSpinner(items: Array<String>, onSelected: () -> Unit): Spinner = Spinner(this).apply {
        background = cardBackground(card2)
        val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = TextView(this@MainActivity).apply {
                text = getItem(position)
                textSize = 14f
                setTextColor(text)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        this.adapter = adapter
        setOnItemSelectedListener(SimpleItemSelected { onSelected() })
    }

    private fun makeButton(value: String, backgroundColor: Int, foregroundColor: Int, action: () -> Unit): Button = Button(this).apply {
        text = value
        textSize = 14f
        setTextColor(foregroundColor)
        background = cardBackground(backgroundColor)
        isAllCaps = false
        setOnClickListener { action() }
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
            q <= 20 -> "약 0.35~1.5 Mbps"
            q <= 50 -> "약 0.8~4 Mbps"
            q <= 80 -> "약 2~8 Mbps"
            else -> "약 4~16 Mbps"
        }
        val quality = when {
            q <= 20 -> "화질 손실 큼 · 용량 최소화"
            q <= 50 -> "화질/용량 균형"
            q <= 80 -> "화질 우선"
            else -> "화질 손실 최소화"
        }
        detailText.text = "목표 용량 약 ${ratio.toInt()}%  ·  $quality\n해상도: $resolution  ·  비트레이트: $bitrate\n오디오는 기본 AAC 계열 처리 · 실제 결과는 영상에 따라 달라짐"
    }

    private fun updateEstimate() {
        if (!::estimateText.isInitialized || selectedUris.isEmpty()) return
        val original = selectedUris.sumOf { querySize(it) }
        val q = qualityBar.progress + 1
        val ratio = (15.0 + (q - 1) * 75.0 / 99.0) / 100.0
        val estimated = (original * ratio).toLong()
        val reduction = max(0.0, (1.0 - ratio) * 100.0)
        estimateText.text = "예상 결과  ${formatBytes(estimated)}  ·  약 ${reduction.toInt()}% 감소\n1-pass 인코딩 기준 예상치이며 실제 크기는 영상 내용에 따라 달라집니다."
    }

    private fun startCompression() {
        if (selectedUris.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<CompressionWorker>()
            .setInputData(workDataOf(
                CompressionWorker.KEY_URIS to selectedUris.map(Uri::toString).toTypedArray(),
                CompressionWorker.KEY_QUALITY to (qualityBar.progress + 1),
                CompressionWorker.KEY_RESOLUTION to resolutionSpinner.selectedItemPosition,
                CompressionWorker.KEY_CODEC to codecSpinner.selectedItemPosition
            ))
            .build()
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
                    if (before > 0 && after > 0) "완료  ·  ${formatBytes(before)} → ${formatBytes(after)}  ·  약 ${(100.0 - after * 100.0 / before).toInt()}% 감소"
                    else "완료  ·  Movies/Compresser에 저장됨"
                }
                WorkInfo.State.CANCELLED -> "취소됨"
                WorkInfo.State.FAILED -> "실패  ·  ${info.outputData.getString(CompressionWorker.KEY_ERROR) ?: "알 수 없는 오류"}"
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
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, "압축 파일 공유"))
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "video"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        do {
            value /= 1024.0
            i++
        } while (value >= 1024 && i < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    private class SimpleItemSelected(private val action: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
