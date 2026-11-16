package com.gshotwell.hr4x4

import android.content.Context

/** Numeric setting: key, label, default, whether it accepts decimals. */
data class NumSpec(val key: String, val label: String, val def: Double, val decimal: Boolean = false)
data class BoolSpec(val key: String, val label: String, val def: Boolean)

class Settings(private val num: Map<String, Double>, private val bool: Map<String, Boolean>) {
    fun n(key: String): Double = num[key] ?: NUM_SPECS.first { it.key == key }.def
    fun b(key: String): Boolean = bool[key] ?: BOOL_SPECS.first { it.key == key }.def

    val maxHr get() = n("maxHr").toInt()
    fun bpm(pct: Double): Int = Math.round(maxHr * pct / 100.0).toInt()

    val grace get() = n("grace")
    val confirm get() = n("confirm")
    val hyst get() = n("hyst").toInt()
    val remind get() = n("remind")
    val voice get() = b("voice")
    val vibrate get() = b("vibrate")
    val countdown get() = b("countdown")
    val sim get() = b("sim")

    fun save(ctx: Context) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        NUM_SPECS.forEach { e.putFloat(it.key, n(it.key).toFloat()) }
        BOOL_SPECS.forEach { e.putBoolean(it.key, b(it.key)) }
        e.apply()
    }

    companion object {
        const val PREFS = "hr4x4"

        val ZONE_SPECS = listOf(
            NumSpec("workLo", "Work zone low (% max)", 85.0),
            NumSpec("workHi", "Work zone high (% max)", 95.0),
            NumSpec("recLo", "Recovery zone low (% max)", 60.0),
            NumSpec("recHi", "Recovery zone high (% max)", 75.0),
            NumSpec("wuLo", "Warm-up zone low (% max)", 60.0),
            NumSpec("wuHi", "Warm-up zone high (% max)", 75.0),
        )
        val TIME_SPECS = listOf(
            NumSpec("wuMin", "Warm-up (min)", 10.0, true),
            NumSpec("reps", "Intervals", 4.0),
            NumSpec("workMin", "Work (min)", 4.0, true),
            NumSpec("recMin", "Recovery (min)", 3.0, true),
            NumSpec("cdMin", "Cool-down (min)", 5.0, true),
        )
        val ALERT_SPECS = listOf(
            NumSpec("grace", "Ramp grace at phase start (s)", 60.0),
            NumSpec("confirm", "Hold a change for (s)", 2.0),
            NumSpec("hyst", "Hysteresis (bpm)", 2.0),
            NumSpec("remind", "Repeat while out of zone (s, 0 = off)", 20.0),
            NumSpec("speechRate", "Speech rate (1 = normal)", 1.0, true),
        )
        val MAX_SPEC = NumSpec("maxHr", "Max heart rate", 185.0)
        val NUM_SPECS = listOf(MAX_SPEC) + ZONE_SPECS + TIME_SPECS + ALERT_SPECS
        val BOOL_SPECS = listOf(
            BoolSpec("voice", "Voice announcements", true),
            BoolSpec("vibrate", "Vibrate", true),
            BoolSpec("countdown", "Countdown beeps (last 3 s)", true),
            BoolSpec("keepScreen", "Keep screen on during workout", true),
            BoolSpec("skipWork", "Next song when each interval starts", true),
            BoolSpec("skipRec", "Next song when each recovery starts", true),
            BoolSpec("health", "Save sessions to Health Connect", false),
            BoolSpec("sim", "Simulated heart rate (testing, no strap)", false),
        )

        fun load(ctx: Context): Settings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val num = NUM_SPECS.associate { it.key to p.getFloat(it.key, it.def.toFloat()).toDouble() }
            val bool = BOOL_SPECS.associate { it.key to p.getBoolean(it.key, it.def) }
            return Settings(num, bool)
        }
    }
}
