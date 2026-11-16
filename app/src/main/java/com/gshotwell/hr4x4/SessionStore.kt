package com.gshotwell.hr4x4

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Writes the in-progress workout to disk as it happens, so a killed or crashed app can
 * pick the session back up (or at least keep its data).
 *
 *   files/active/state.json   – settings snapshot + clock state, rewritten every second
 *   files/active/samples.csv  – heart-rate samples, appended
 */
class SessionStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "active")
    private val stateFile = File(dir, "state.json")
    private val samplesFile = File(dir, "samples.csv")
    private var writer: BufferedWriter? = null
    private var unflushed = 0

    companion object {
        /** A session last saved longer ago than this is finalised rather than resumed. */
        const val RESUME_WINDOW_MS = 15 * 60 * 1000L
        fun bootWall() = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        fun hasActive(ctx: Context) = File(File(ctx.filesDir, "active"), "state.json").exists()
    }

    fun begin() {
        dir.mkdirs()
        samplesFile.delete()
        writer = BufferedWriter(FileWriter(samplesFile, true))
        unflushed = 0
    }

    fun append(s: Sample) {
        val w = writer ?: return
        try {
            w.write("%.2f,%d,%d,%s\n".format(Locale.US, s.t, s.hr, s.phase, s.kind.name))
            if (++unflushed >= 5) { w.flush(); unflushed = 0 }
        } catch (_: Exception) {}
    }

    fun saveState(st: Settings, phaseIdx: Int, phaseStartMono: Long, pausedAtMono: Long?, startWall: Long, startMono: Long, peak: Int) {
        try {
            val num = JSONObject(); st.num.forEach { (k, v) -> num.put(k, v) }
            val bool = JSONObject(); st.bool.forEach { (k, v) -> bool.put(k, v) }
            val o = JSONObject()
                .put("num", num).put("bool", bool)
                .put("phaseIdx", phaseIdx)
                .put("phaseStartMono", phaseStartMono)
                .put("pausedAtMono", pausedAtMono ?: -1L)
                .put("startWall", startWall)
                .put("startMono", startMono)
                .put("bootWall", bootWall())
                .put("savedWall", System.currentTimeMillis())
                .put("peak", peak)
            val tmp = File(dir, "state.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(stateFile)
        } catch (_: Exception) {}
    }

    /** Ends the active record and keeps a dated CSV. Returns the CSV's path, if written. */
    fun finish(csv: String, startWall: Long): String? {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        var path: String? = null
        if (csv.count { it == '\n' } > 1) {
            try {
                val out = ctx.getExternalFilesDir("sessions") ?: File(ctx.filesDir, "sessions").apply { mkdirs() }
                val f = File(out, "4x4_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startWall)) + ".csv")
                f.writeText(csv)
                path = f.absolutePath
            } catch (_: Exception) {}
        }
        clear()
        return path
    }

    fun clear() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        stateFile.delete(); samplesFile.delete(); File(dir, "state.tmp").delete()
    }

    data class Saved(
        val settings: Settings, val phaseIdx: Int, val phaseStartMono: Long, val pausedAtMono: Long?,
        val startWall: Long, val startMono: Long, val samples: List<Sample>, val peak: Int,
        /** Same boot and saved recently enough that the monotonic clock values still mean something. */
        val resumable: Boolean,
    )

    fun load(): Saved? {
        if (!stateFile.exists()) return null
        return try {
            val o = JSONObject(stateFile.readText())
            val numO = o.getJSONObject("num"); val boolO = o.getJSONObject("bool")
            val num = numO.keys().asSequence().associateWith { numO.getDouble(it) }
            val bool = boolO.keys().asSequence().associateWith { boolO.getBoolean(it) }
            val samples = if (samplesFile.exists()) samplesFile.readLines().mapNotNull { line ->
                val p = line.split(",")
                if (p.size < 4) null else try {
                    Sample(p[0].toDouble(), p[1].toInt(), p[2].toInt(), Kind.valueOf(p[3]))
                } catch (_: Exception) { null }
            } else emptyList()
            val sameBoot = abs(o.getLong("bootWall") - bootWall()) < 60_000
            val recent = System.currentTimeMillis() - o.getLong("savedWall") < RESUME_WINDOW_MS
            val paused = o.getLong("pausedAtMono").let { if (it < 0) null else it }
            Saved(
                Settings(num, bool), o.getInt("phaseIdx"), o.getLong("phaseStartMono"), paused,
                o.getLong("startWall"), o.getLong("startMono"), samples, o.optInt("peak", 0),
                sameBoot && recent,
            )
        } catch (_: Exception) { null }
    }

    /** Re-open the samples file for appending after a resume. */
    fun reopen() {
        dir.mkdirs()
        writer = BufferedWriter(FileWriter(samplesFile, true))
        unflushed = 0
    }
}
