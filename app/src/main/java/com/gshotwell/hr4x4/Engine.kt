package com.gshotwell.hr4x4

import android.os.SystemClock
import kotlin.math.ceil

enum class Kind { WARMUP, WORK, REC, COOL }
data class Zone(val lo: Int, val hi: Int)
data class Phase(val kind: Kind, val idx: Int, val name: String, val durSec: Double, val zone: Zone?)
data class Sample(val t: Double, val hr: Int, val phase: Int, val kind: Kind)

enum class ZS { NONE, LOW, IN, HIGH }
enum class Snd { HIGH, LOW, IN, PHASE, TICK, LOST }
enum class Conn { NONE, CONNECTING, CONNECTED, LOST }

/**
 * Workout state machine. Lives in the app process; the foreground service drives
 * tick() and feeds heart rate, the activity only reads state and sends commands.
 * All calls happen on the main thread.
 */
object Engine {
    interface Sink {
        fun sound(s: Snd)
        fun say(text: String)
        fun mediaNext()
    }

    var sink: Sink? = null
    var s: Settings? = null
        private set

    var plan: List<Phase> = emptyList(); private set
    var phaseIdx = 0; private set
    private var phaseStart = 0L
    var pausedAt: Long? = null; private set
    var running = false; private set
    var finished = false; private set

    var zone = ZS.NONE; private set
    private var pending: ZS? = null
    private var pendingSince = 0L
    private var lastAlertAt = 0L
    private var lastAnnounced = ZS.NONE
    private var lastCountdown = -1

    val samples = mutableListOf<Sample>()
    /** sessionStartWall of the last session already written to disk / Health Connect. */
    var exportedFor = 0L
    var exportStatus = ""
    var sessionStartWall = 0L; private set
    private var sessionStartMono = 0L

    var lastHr: Int? = null; private set
    private var lastHrAt = 0L
    var conn = Conn.NONE; private set
    var connName = ""; private set

    // ---------- connection log (shown on screen for debugging) ----------
    val logLines = ArrayDeque<String>()
    var logVersion = 0; private set
    private val logFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    fun log(msg: String) {
        android.util.Log.i("hr4x4", msg)
        logLines.addLast("${logFmt.format(java.util.Date())}  $msg")
        while (logLines.size > 80) logLines.removeFirst()
        logVersion++
        notifyUi()
    }

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyUi() = listeners.toList().forEach { it() }

    fun now() = SystemClock.elapsedRealtime()
    fun phaseElapsed(): Double = ((pausedAt ?: now()) - phaseStart) / 1000.0
    fun hrFresh() = lastHr != null && now() - lastHrAt < 5000
    val currentPhase get() = plan.getOrNull(phaseIdx)

    private fun sound(x: Snd) = sink?.sound(x)
    private fun say(t: String) { if (s?.voice == true) sink?.say(t) }

    fun buildPlan(st: Settings): List<Phase> {
        val p = mutableListOf<Phase>()
        fun z(lo: String, hi: String) = Zone(st.bpm(st.n(lo)), st.bpm(st.n(hi)))
        val reps = st.n("reps").toInt().coerceIn(1, 20)
        if (st.n("wuMin") > 0) p += Phase(Kind.WARMUP, 0, "Warm-up", st.n("wuMin") * 60, z("wuLo", "wuHi"))
        for (i in 1..reps) {
            p += Phase(Kind.WORK, i, "Interval $i of $reps", st.n("workMin") * 60, z("workLo", "workHi"))
            if (i < reps) p += Phase(Kind.REC, i, "Recovery $i", st.n("recMin") * 60, z("recLo", "recHi"))
        }
        if (st.n("cdMin") > 0) p += Phase(Kind.COOL, 0, "Cool-down", st.n("cdMin") * 60, null)
        return p
    }

    // ---------- commands ----------
    fun start(st: Settings) {
        s = st
        plan = buildPlan(st)
        samples.clear()
        sessionStartWall = System.currentTimeMillis()
        sessionStartMono = now()
        running = true; finished = false; pausedAt = null
        enterPhase(0)
    }

    private fun enterPhase(i: Int) {
        phaseIdx = i; phaseStart = now(); lastCountdown = -1
        zone = ZS.NONE; pending = null; lastAlertAt = 0; lastAnnounced = ZS.NONE
        if (i >= plan.size) { finish(); return }
        val ph = plan[i]
        sound(Snd.PHASE)
        val st = s
        if (st != null && ((ph.kind == Kind.WORK && st.b("skipWork")) || (ph.kind == Kind.REC && st.b("skipRec")))) {
            sink?.mediaNext()
        }
        val zt = ph.zone?.let { " Target ${it.lo} to ${it.hi}." } ?: ""
        when (ph.kind) {
            Kind.WORK -> say("Interval ${ph.idx}. Go.$zt")
            Kind.REC -> say("Recover.$zt")
            Kind.WARMUP -> say("Warm up.$zt")
            Kind.COOL -> say("Cool down. Nice work.")
        }
        notifyUi()
    }

    fun togglePause() {
        if (!running) return
        val p = pausedAt
        if (p == null) { pausedAt = now(); say("Paused") }
        else { phaseStart += now() - p; pausedAt = null; say("Resumed") }
        notifyUi()
    }

    fun skip() { if (running) enterPhase(phaseIdx + 1) }

    fun finish() {
        if (!running) return
        running = false; finished = true; pausedAt = null; zone = ZS.NONE
        say("Workout complete")
        notifyUi()
    }

    fun dismissSummary() { finished = false; notifyUi() }

    // ---------- inputs ----------
    fun tick() {
        if (!running) { notifyUi(); return }
        if (pausedAt != null) { notifyUi(); return }
        val ph = plan[phaseIdx]
        val remain = ph.durSec - phaseElapsed()
        if (s?.countdown == true) {
            val sec = ceil(remain).toInt()
            if (sec in 1..3 && sec != lastCountdown) { lastCountdown = sec; sound(Snd.TICK) }
        }
        if (remain <= 0) { enterPhase(phaseIdx + 1); return }
        notifyUi()
    }

    fun onHr(hr: Int) {
        lastHr = hr; lastHrAt = now()
        if (running && pausedAt == null) {
            val ph = plan[phaseIdx]
            samples += Sample((now() - sessionStartMono) / 1000.0, hr, phaseIdx, ph.kind)
            evaluate(hr)
        }
    }

    fun setConn(c: Conn, name: String = connName) {
        val prev = conn
        conn = c; connName = name
        if (running && c == Conn.LOST && prev == Conn.CONNECTED) {
            sound(Snd.LOST); say("Heart rate strap disconnected")
        }
        if (running && c == Conn.CONNECTED && prev == Conn.LOST) {
            sound(Snd.IN); say("Strap reconnected")
        }
        notifyUi()
    }

    // ---------- zone logic ----------
    private fun rawState(hr: Int, z: Zone, h: Int): ZS = when (zone) {
        ZS.HIGH -> if (hr > z.hi - h) ZS.HIGH else if (hr < z.lo) ZS.LOW else ZS.IN
        ZS.LOW -> if (hr < z.lo + h) ZS.LOW else if (hr > z.hi) ZS.HIGH else ZS.IN
        else -> if (hr > z.hi) ZS.HIGH else if (hr < z.lo) ZS.LOW else ZS.IN
    }

    /** True while the ramp grace period silences an expected out-of-zone state. */
    fun suppressed(st: ZS): Boolean {
        val ph = currentPhase ?: return false
        val grace = s?.grace ?: 0.0
        if (phaseElapsed() >= grace) return false
        return (ph.kind == Kind.WORK && st == ZS.LOW) ||
            (ph.kind == Kind.REC && st == ZS.HIGH) ||
            (ph.kind == Kind.WARMUP && st == ZS.LOW)
    }

    private fun evaluate(hr: Int) {
        val st = s ?: return
        val z = currentPhase?.zone
        if (z == null) { zone = ZS.NONE; return }
        val next = rawState(hr, z, st.hyst)
        if (next == zone) { pending = null; maybeRemind(); return }
        if (pending != next) { pending = next; pendingSince = now() }
        if ((now() - pendingSince) / 1000.0 < st.confirm) return
        val prev = zone
        zone = next; pending = null
        if (next == ZS.IN) {
            // ding on returning to zone, but not on the first lock-on of a phase
            if (prev != ZS.NONE) alert(ZS.IN)
        } else if (!suppressed(next)) {
            alert(next)
        }
    }

    private fun maybeRemind() {
        val st = s ?: return
        if (zone == ZS.IN || zone == ZS.NONE || suppressed(zone)) return
        // First announcement once a grace period ends, then repeats if enabled.
        if (lastAnnounced != zone) { alert(zone); return }
        if (st.remind > 0 && (now() - lastAlertAt) / 1000.0 >= st.remind) alert(zone)
    }

    private fun alert(z: ZS) {
        lastAlertAt = now(); lastAnnounced = z
        when (z) {
            ZS.HIGH -> { sound(Snd.HIGH); say("Too high") }
            ZS.LOW -> { sound(Snd.LOW); say("Too low") }
            ZS.IN -> sound(Snd.IN)
            ZS.NONE -> {}
        }
    }

    fun csv(): String = buildString {
        append("seconds,hr,phase_index,phase\n")
        samples.forEach { append("%.1f,%d,%d,%s\n".format(it.t, it.hr, it.phase, it.kind.name.lowercase())) }
    }
}
