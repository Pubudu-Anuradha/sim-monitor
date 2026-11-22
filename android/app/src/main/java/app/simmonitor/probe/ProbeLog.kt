package app.simmonitor.probe

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Every request, reply, failure and incoming SMS, kept in memory for the UI and appended to
 * `files/probe-log.jsonl`. The exported file becomes parser test fixtures on the desktop.
 */
object ProbeLog {
    const val FILE_NAME = "probe-log.jsonl"

    data class Entry(val ts: Instant, val kind: String, val fields: Map<String, Any?>) {
        val summary: String
            get() = fields.entries.filter { it.value != null }
                .joinToString("  ") { (k, v) -> "$k=$v" }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var loaded = false

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        val parsed = f.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val fields = o.keys().asSequence()
                    .filter { it != "ts" && it != "kind" }
                    .associateWith { o.opt(it)?.takeIf { v -> v != JSONObject.NULL } }
                Entry(Instant.parse(o.getString("ts")), o.getString("kind"), fields)
            }.getOrNull()
        }
        _entries.value = parsed.reversed()
    }

    @Synchronized
    fun add(context: Context, kind: String, vararg fields: Pair<String, Any?>) {
        load(context)
        val entry = Entry(Instant.now(), kind, linkedMapOf(*fields))
        val json = JSONObject().apply {
            put("ts", entry.ts.toString())
            put("kind", kind)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("sdk", Build.VERSION.SDK_INT)
            fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
        }
        file(context).appendText(json.toString() + "\n")
        _entries.update { listOf(entry) + it }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        _entries.value = emptyList()
    }
}
