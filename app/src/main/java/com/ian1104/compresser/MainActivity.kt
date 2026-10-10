package com.ian1104.compresser

import android.Manifest
import android.app.Activity
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

class MainActivity : AppCompatActivity() {
    private val selectedUris = mutableListOf<Uri>()
    private lateinit var selectedText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var presetSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private var workId: java.util.UUID? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        selectedUris.clear()
        selectedUris.addAll(uris)
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        selectedText.text = "선택한 영상 ${uris.size}개\n" + uris.take(3).joinToString("\n") { displayName(it) } + if (uris.size > 3) "\n…" else ""
        startButton.isEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 42, 32, 28)
        }

        val title = TextView(this).apply {
            text = "Compresser"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "빠른 H.264 하드웨어 영상 압축"
            textSize = 14f
            gravity = Gravity.CENTER
        }

        val pickButton = Button(this).apply {
            text = "영상 선택"
            setOnClickListener { picker.launch(arrayOf("video/*")) }
        }

        presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "빠른 압축 · 720p · 약 35%",
                    "균형 · 1080p · 약 50%",
                    "고화질 · 1080p · 약 65%"
                )
            )
        }

        selectedText = TextView(this).apply {
            text = "선택한 영상이 없습니다."
            textSize = 15f
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        progressText = TextView(this).apply { text = "0%"; textSize = 14f }
        statusText = TextView(this).apply { text = "대기 중"; textSize = 15f }

        startButton = Button(this).apply {
            text = "압축 시작"
            isEnabled = false
            setOnClickListener { startCompression() }
        }
        cancelButton = Button(this).apply {
            text = "작업 취소"
            isEnabled = false
            setOnClickListener { workId?.let { WorkManager.getInstance(this@MainActivity).cancelWorkById(it) } }
        }

        root.addView(title, LinearLayout.LayoutParams(-1, 60))
        root.addView(subtitle, LinearLayout.LayoutParams(-1, 48))
        root.addView(pickButton, LinearLayout.LayoutParams(-1, 58))
        root.addView(selectedText, LinearLayout.LayoutParams(-1, 100))
        root.addView(presetSpinner, LinearLayout.LayoutParams(-1, 62))
        root.addView(startButton, LinearLayout.LayoutParams(-1, 58))
        root.addView(cancelButton, LinearLayout.LayoutParams(-1, 58))
        root.addView(progress, LinearLayout.LayoutParams(-1, 34))
        root.addView(progressText, LinearLayout.LayoutParams(-1, 34))
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun startCompression() {
        if (selectedUris.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<CompressionWorker>()
            .setInputData(
                workDataOf(
                    CompressionWorker.KEY_URIS to selectedUris.map(Uri::toString).toTypedArray(),
                    CompressionWorker.KEY_PRESET to presetSpinner.selectedItemPosition
                )
            )
            .build()
        workId = request.id
        WorkManager.getInstance(this).enqueue(request)
        startButton.isEnabled = false
        cancelButton.isEnabled = true
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
                WorkInfo.State.SUCCEEDED -> "완료 · Movies/Compresser에 저장됨"
                WorkInfo.State.CANCELLED -> "취소됨"
                WorkInfo.State.FAILED -> "실패 · ${info.outputData.getString("error") ?: "알 수 없는 오류"}"
                else -> "처리 중…"
            }
            if (info.state.isFinished) {
                cancelButton.isEnabled = false
                startButton.isEnabled = selectedUris.isNotEmpty()
                if (info.state == WorkInfo.State.SUCCEEDED) {
                    Toast.makeText(this, "압축이 완료되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "video"
    }
}
