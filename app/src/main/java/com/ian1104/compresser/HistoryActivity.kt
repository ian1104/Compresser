package com.ian1104.compresser

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class HistoryActivity : AppCompatActivity() {
    private val bg = Color.rgb(13, 15, 18)
    private val card = Color.rgb(23, 26, 31)
    private val card2 = Color.rgb(29, 33, 39)
    private val text = Color.rgb(242, 244, 247)
    private val muted = Color.rgb(157, 165, 176)
    private val accent = Color.rgb(105, 170, 255)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) renderList()
    }

    private lateinit var list: LinearLayout

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "압축 기록"
            textSize = 26f
            setTextColor(text)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "전체 삭제"
            isAllCaps = false
            textSize = 12f
            setTextColor(muted)
            setOnClickListener { CompressionHistory.clear(this@HistoryActivity); renderList() }
        }, LinearLayout.LayoutParams(dp(88), dp(44)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "최근 압축 결과를 최대 50개까지 저장합니다."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(14))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderList()
    }

    private fun renderList() {
        list.removeAllViews()
        val entries = CompressionHistory.get(this)
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "아직 압축 기록이 없습니다."
                textSize = 14f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, dp(60))
            })
            return
        }
        entries.forEach { entry ->
            val reduction = if (entry.originalBytes > 0) {
                ((1.0 - entry.outputBytes.toDouble() / entry.originalBytes) * 100).coerceIn(0.0, 100.0)
            } else 0.0
            val cardView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(card)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            cardView.addView(TextView(this).apply {
                text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(entry.timestamp))
                textSize = 12f
                setTextColor(muted)
            })
            cardView.addView(TextView(this).apply {
                text = "${entry.count}개 영상 · ${formatBytes(entry.originalBytes)} → ${formatBytes(entry.outputBytes)}"
                textSize = 15f
                setTextColor(text)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(7), 0, dp(4))
            })
            cardView.addView(TextView(this).apply {
                text = "${reduction.roundToInt()}% 용량 절감"
                textSize = 13f
                setTextColor(accent)
            })
            if (entry.outputUris.isNotEmpty()) {
                cardView.addView(Button(this).apply {
                    text = "첫 결과 열기"
                    isAllCaps = false
                    textSize = 12f
                    setTextColor(text)
                    setBackgroundColor(card2)
                    setOnClickListener { openUri(entry.outputUris.first()) }
                }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) })
            }
            list.addView(cardView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun openUri(value: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(value), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024 && index < units.lastIndex) { value /= 1024.0; index++ }
        return String.format(Locale.KOREA, "%.1f%s", value, units[index])
    }
}
