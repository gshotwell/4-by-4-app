package com.gshotwell.hr4x4

import android.os.SystemClock
import kotlin.math.ceil

enum class Kind { WARMUP, WORK, REC, COOL }
data class Zone(val lo: Int, val hi: Int)
data class Phase(
    val kind: Kind, val idx: Int, val name: String, val durSec: Double, val zone: Zone?,
    /** All-out max-test effort: zone alerts off, effort cues on. */
    val test: Boolean = false,
    /** Short all-out sprint after the test, to check the peak doesn't go higher. */
    val verify: Boolean = false,
)
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
        /** Called once when a session ends (normally or recovered). */
        fun finished()
    }

    var store: SessionStore? = null
    /** Highest 5-sample median HR this session: robust to single-beat strap glitches. */
    var sessionPeak = 0; private set
    private val recentHr = ArrayDeque<Int>()
    private var lastSaveAt = 0L
    var savedCsvPath: String? = null; private set

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
    private val firedCues = mutableSetOf<String>()
    private fun fmtMin(sec: Double): String {
        val m = sec / 60
        return if (m % 1.0 == 0.0) "${m.toInt()} minutes" else "${(sec).toInt()} seconds"
    }

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
        val maxTest = st.b("maxTest")
        for (i in 1..reps) {
            if (maxTest && i == reps) {
                p += Phase(Kind.WORK, i, "Interval $i · max test", st.n("workMin") * 60, null, test = true)
            } else {
                p += Phase(Kind.WORK, i, "Interval $i of $reps", st.n("workMin") * 60, z("workLo", "workHi"))
            }
            if (i < reps) p += Phase(Kind.REC, i, "Recovery $i", st.n("recMin") * 60, z("recLo", "recHi"))
        }
        if (maxTest && st.b("maxVerify")) {
            p += Phase(Kind.REC, reps, "Recovery before check", st.n("recMin") * 60, z("recLo", "recHi"))
            p += Phase(Kind.WORK, 0, "Verification sprint", 60.0, null, test = true, verify = true)
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
        sessionPeak = 0; recentHr.clear(); savedCsvPath = null
        store?.begin()
        enterPhase(0)
    }

    /** Pick up a session the app was killed in the middle of. */
    fun restore(sv: SessionStore.Saved) {
        s = sv.settings
        plan = buildPlan(sv.settings)
        samples.clear(); samples.addAll(sv.samples)
        sessionStartWall = sv.startWall; sessionStartMono = sv.startMono
        phaseIdx = sv.phaseIdx; phaseStart = sv.phaseStartMono; pausedAt = sv.pausedAtMono
        sessionPeak = sv.peak; recentHr.clear(); savedCsvPath = null
        zone = ZS.NONE; pending = null; lastAlertAt = 0; lastAnnounced = ZS.NONE; lastCountdown = -1
        running = true; finished = false
        store?.reopen()
        // Fast-forward through any phases that ended while the app was gone.
        if (pausedAt == null) {
            while (phaseIdx < plan.size && phaseElapsed() >= plan[phaseIdx].durSec) {
                phaseStart += (plan[phaseIdx].durSec * 1000).toLong()
                phaseIdx++
            }
        }
        if (phaseIdx >= plan.size) { finish(announce = false); return }
        saveState()
        notifyUi()
    }

    /** Too old to resume: keep its data and show it as a finished session. */
    fun recoverFinished(sv: SessionStore.Saved) {
        s = sv.settings
        plan = buildPlan(sv.settings)
        samples.clear(); samples.addAll(sv.samples)
        sessionStartWall = sv.startWall; sessionStartMono = sv.startMono
        sessionPeak = sv.peak; savedCsvPath = null
        phaseIdx = sv.phaseIdx.coerceAtMost(plan.size - 1); pausedAt = null
        running = true
        finish(announce = false)
    }

    private fun saveState() {
        val st = s ?: return
        lastSaveAt = now()
        store?.saveState(st, phaseIdx, phaseStart, pausedAt, sessionStartWall, sessionStartMono, sessionPeak)
    }

    private fun enterPhase(i: Int) {
        phaseIdx = i; phaseStart = now(); lastCountdown = -1; firedCues.clear()
        zone = ZS.NONE; pending = null; lastAlertAt = 0; lastAnnounced = ZS.NONE
        if (i >= plan.size) { finish(); return }
        saveState()
        val ph = plan[i]
        sound(Snd.PHASE)
        val st = s
        if (st != null && ((ph.kind == Kind.WORK && st.b("skipWork")) || (ph.kind == Kind.REC && st.b("skipRec")))) {
            sink?.mediaNext()
        }
        val zt = ph.zone?.let { " Target ${it.lo} to ${it.hi}." } ?: ""
        when {
            ph.verify -> say("Verification. All out for one minute. Go.")
            ph.test -> say("Max test. Hold the hardest pace you can sustain for ${fmtMin(ph.durSec)}, and empty the tank in the last minute. Go.")
            else -> when (ph.kind) {
            Kind.WORK -> say("Interval ${ph.idx}. Go.$zt")
            Kind.REC -> say("Recover.$zt")
            Kind.WARMUP -> say("Warm up.$zt")
            Kind.COOL -> say("Cool down. Nice work.")
            }
        }
        notifyUi()
    }

    fun togglePause() {
        if (!running) return
        val p = pausedAt
        if (p == null) { pausedAt = now(); say("Paused") }
        else { phaseStart += now() - p; pausedAt = null; say("Resumed") }
        saveState()
        notifyUi()
    }

    fun skip() { if (running) enterPhase(phaseIdx + 1) }

    fun finish(announce: Boolean = true) {
        if (!running) return
        running = false; finished = true; pausedAt = null; zone = ZS.NONE
        savedCsvPath = store?.finish(csv(), sessionStartWall)
        sink?.finished()
        if (announce) say("Workout complete")
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
        if (ph.test) {
            fun cue(key: String, at: Double, text: String) {
                if (remain <= at && key !in firedCues) { firedCues += key; sound(Snd.PHASE); say(text) }
            }
            if (!ph.verify) {
                if (ph.durSec > 150) cue("half", ph.durSec / 2, "Halfway. Hold it.")
                cue("last", 60.0, "Last minute. All out.")
            }
            cue("thirty", 30.0, "Thirty seconds. Everything you've got.")
        }
        if (now() - lastSaveAt >= 1000) saveState()
        notifyUi()
    }

    fun onHr(hr: Int) {
        lastHr = hr; lastHrAt = now()
        if (running && pausedAt == null) {
            val ph = plan[phaseIdx]
            val smp = Sample((now() - sessionStartMono) / 1000.0, hr, phaseIdx, ph.kind)
            samples += smp
            store?.append(smp)
            recentHr.addLast(hr)
            while (recentHr.size > 5) recentHr.removeFirst()
            if (recentHr.size == 5) {
                val med = recentHr.sorted()[2]
                if (med > sessionPeak) sessionPeak = med
            }
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
        if (z == null || currentPhase?.test == true) { zone = ZS.NONE; return }
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

    /** Highest 5-sample median HR over the phases matching [which]. */
    fun medianPeak(which: (Phase) -> Boolean): Int {
        val idx = plan.indices.filter { which(plan[it]) }.toSet()
        val hrs = samples.filter { it.phase in idx }.map { it.hr }
        if (hrs.size < 5) return hrs.maxOrNull() ?: 0
        return hrs.windowed(5).maxOf { it.sorted()[2] }
    }

    data class MaxTest(val testPeak: Int, val verifyPeak: Int?, val estimate: Int, val plateau: Boolean?)

    /** Result of the max-test interval (and verification sprint), if the session had one. */
    fun maxTestResult(): MaxTest? {
        if (plan.none { it.test && !it.verify }) return null
        val t = medianPeak { it.test && !it.verify }
        if (t <= 0) return null
        val hasVerify = plan.any { it.verify }
        val v = if (hasVerify) medianPeak { it.verify }.takeIf { it > 0 } else null
        val plateau = v?.let { it <= t + 2 }
        return MaxTest(t, v, maxOf(t, v ?: 0), plateau)
    }

    fun csv(): String = buildString {
        append("seconds,hr,phase_index,phase\n")
        samples.forEach { append("%.1f,%d,%d,%s\n".format(it.t, it.hr, it.phase, it.kind.name.lowercase())) }
    }
}
