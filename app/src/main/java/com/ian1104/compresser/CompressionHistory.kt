package com.ian1104.compresser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val timestamp: Long,
    val originalBytes: Long,
    val outputBytes: Long,
    val count: Int,
    val outputUris: List<String>
)

object CompressionHistory {
    private const val PREFS = "compression_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 50

    fun add(context: Context, entry: HistoryEntry) {
        val all = get(context).toMutableList()
        all.add(0, entry)
        val array = JSONArray()
        all.take(MAX_ENTRIES).forEach { e ->
            array.put(JSONObject().apply {
                put("timestamp", e.timestamp)
                put("original", e.originalBytes)
                put("output", e.outputBytes)
                put("count", e.count)
                put("uris", JSONArray(e.outputUris))
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun get(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val uris = o.optJSONArray("uris") ?: JSONArray()
                    add(HistoryEntry(
                        o.optLong("timestamp"),
                        o.optLong("original"),
                        o.optLong("output"),
                        o.optInt("count", uris.length()),
                        List(uris.length()) { n -> uris.optString(n) }
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
