package com.gshotwell.hr4x4

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val BG = 0xFF0D1117.toInt()
    private val PANEL = 0xFF161B22.toInt()
    private val LINE = 0xFF30363D.toInt()
    private val TEXT = 0xFFE6EDF3.toInt()
    private val MUTED = 0xFF8B949E.toInt()
    private val GREEN_BTN = 0xFF238636.toInt()
    private val RED_BTN = 0xFF6E2A2A.toInt()
    private val C_LOW = 0xFF3B82F6.toInt()
    private val C_IN = 0xFF22C55E.toInt()
    private val C_HIGH = 0xFFEF4444.toInt()

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var setupView: LinearLayout
    private lateinit var workoutView: LinearLayout
    private lateinit var summaryView: LinearLayout

    private lateinit var connText: TextView
    private lateinit var connectBtn: Button
    private lateinit var startBtn: Button
    private lateinit var zoneBpmText: TextView
    private val numInputs = mutableMapOf<String, EditText>()
    private val boolInputs = mutableMapOf<String, Switch>()

    private lateinit var phaseText: TextView
    private lateinit var clockText: TextView
    private lateinit var nextText: TextView
    private lateinit var hrText: TextView
    private lateinit var targetText: TextView
    private lateinit var statusText: TextView
    private lateinit var pauseBtn: Button
    private lateinit var simRow: LinearLayout
    private lateinit var progressFill: View
    private lateinit var progressTrack: LinearLayout

    private lateinit var summaryBody: LinearLayout
    private lateinit var summaryMeta: TextView
    private var summaryShownFor = 0L
    private var savedCsv: File? = null

    private lateinit var sounds: Sounds
    private val listener: () -> Unit = { render() }

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sounds = Sounds(this)
        buildUi()
        requestPermsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        Engine.addListener(listener)
        autoConnect()
        render()
    }

    override fun onPause() {
        Engine.removeListener(listener)
        super.onPause()
    }

    // ---------------------------------------------------------------- permissions
    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBtPerms() = btPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermsIfNeeded() {
        val want = btPerms().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBtPerms()) autoConnect()
    }

    private fun prefs() = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)

    private fun autoConnect() {
        val addr = prefs().getString("strapAddr", null) ?: return
        if (!hasBtPerms() || Engine.conn != Conn.NONE) return
        WorkoutService.send(this, WorkoutService.ACTION_CONNECT, addr, prefs().getString("strapName", null))
    }

    // ---------------------------------------------------------------- scanning
    @SuppressLint("MissingPermission")
    private fun scanForStrap() {
        if (!hasBtPerms()) { requestPermsIfNeeded(); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { toast("No Bluetooth on this device"); return }
        if (!adapter.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        val scanner = adapter.bluetoothLeScanner ?: run { toast("Bluetooth scanner unavailable"); return }

        val found = LinkedHashMap<String, String>()
        val labels = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val hrUuid = ParcelUuid(HrBle.HR_SERVICE)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name
                val isHr = r.scanRecord?.serviceUuids?.contains(hrUuid) == true ||
                    name?.contains("Polar", ignoreCase = true) == true
                if (!isHr) return
                val addr = r.device.address
                if (addr !in found) {
                    found[addr] = name ?: addr
                    labels.add("${name ?: "Unknown strap"}   ($addr)")
                }
            }
        }
        var stopped = false
        fun stop() { if (!stopped) { stopped = true; try { scanner.stopScan(cb) } catch (_: Exception) {} } }

        AlertDialog.Builder(this)
            .setTitle("Looking for straps… (wear it; wet the electrodes)")
            .setAdapter(labels) { _, which ->
                stop()
                val addr = found.keys.elementAt(which)
                val name = found[addr]
                prefs().edit().putString("strapAddr", addr).putString("strapName", name).apply()
                WorkoutService.send(this, WorkoutService.ACTION_CONNECT, addr, name)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { stop() }
            .show()
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }

    // ---------------------------------------------------------------- UI building
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun tv(text: String = "", size: Float = 16f, bold: Boolean = false, color: Int = TEXT, center: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (center) gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun rounded(color: Int, stroke: Int? = LINE, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun btn(text: String, color: Int = PANEL, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 16f; setTextColor(TEXT)
        background = rounded(color)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(PANEL)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun LinearLayout.addSpaced(v: View, top: Int = 12) {
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) })
    }

    private fun numRow(spec: NumSpec, value: Double): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        l.addView(tv(spec.label, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or (if (spec.decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            setText(if (spec.decimal) trimNum(value) else value.toInt().toString())
            setTextColor(TEXT); textSize = 16f; gravity = Gravity.END
            background = rounded(BG, LINE, 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { updateZoneBpm() }
            })
        }
        numInputs[spec.key] = e
        l.addView(e, LinearLayout.LayoutParams(dp(88), WRAP_CONTENT))
        return l
    }

    private fun boolRow(spec: BoolSpec, value: Boolean): View {
        val sw = Switch(this).apply {
            text = spec.label; textSize = 15f; setTextColor(TEXT); isChecked = value
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, _ -> currentSettings().save(this@MainActivity); render() }
        }
        boolInputs[spec.key] = sw
        return sw
    }

    private fun trimNum(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private fun buildUi() {
        val st = Settings.load(this)
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        // header
        connText = tv("No strap", 14f, color = MUTED).apply { gravity = Gravity.END }
        val title = tv("4x4 Coach", 20f, bold = true)
        root.addView(row(title, connText))

        // ---- setup
        setupView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        connectBtn = btn("Connect Polar H10", GREEN_BTN) { scanForStrap() }
        setupView.addSpaced(connectBtn)

        val zones = card()
        zones.addView(numRow(Settings.MAX_SPEC, st.n("maxHr")))
        Settings.ZONE_SPECS.forEach { zones.addView(numRow(it, st.n(it.key))) }
        zoneBpmText = tv("", 13f, color = MUTED).apply { setPadding(0, dp(6), 0, 0) }
        zones.addView(zoneBpmText)
        setupView.addSpaced(zones)

        val times = card()
        Settings.TIME_SPECS.forEach { times.addView(numRow(it, st.n(it.key))) }
        setupView.addSpaced(times)

        val alerts = card()
        alerts.addView(tv("Alerts", 16f, bold = true))
        Settings.ALERT_SPECS.forEach { alerts.addView(numRow(it, st.n(it.key))) }
        alerts.addView(tv("Ramp grace holds off “too low” at the start of work and warm-up, and “too high” at the start of recovery, while your HR catches up.", 13f, color = MUTED))
        Settings.BOOL_SPECS.forEach { alerts.addView(boolRow(it, st.b(it.key))) }
        alerts.addSpaced(row(
            btn("Test high") { sounds.play(Snd.HIGH, true) },
            btn("Test low") { sounds.play(Snd.LOW, true) },
            btn("Test in") { sounds.play(Snd.IN, true) },
        ), 8)
        setupView.addSpaced(alerts)

        startBtn = btn("Start workout", GREEN_BTN) { startWorkout() }
        setupView.addSpaced(startBtn, 16)
        setupView.addSpaced(btn("Disconnect strap") {
            WorkoutService.send(this, WorkoutService.ACTION_STOP)
        }, 8)
        root.addSpaced(setupView, 4)
        updateZoneBpm()

        // ---- workout
        workoutView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val clockCard = card()
        phaseText = tv("—", 22f, bold = true, center = true)
        clockText = tv("0:00", 72f, bold = true, center = true).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        progressTrack = LinearLayout(this).apply { background = rounded(LINE, null, 4) }
        progressFill = View(this).apply { background = rounded(TEXT, null, 4) }
        progressTrack.addView(progressFill, LinearLayout.LayoutParams(0, dp(8)))
        nextText = tv("", 14f, color = MUTED, center = true)
        clockCard.addView(phaseText)
        clockCard.addView(clockText)
        clockCard.addSpaced(progressTrack, 6)
        clockCard.addSpaced(nextText, 6)
        workoutView.addSpaced(clockCard)

        val hrCard = card()
        hrText = tv("--", 110f, bold = true, center = true)
        targetText = tv("", 20f, color = MUTED, center = true)
        statusText = tv("", 26f, bold = true, center = true)
        simRow = row(
            btn("−10") { SimControl.bias -= 10 }, btn("−3") { SimControl.bias -= 3 },
            btn("+3") { SimControl.bias += 3 }, btn("+10") { SimControl.bias += 10 },
        )
        hrCard.addView(hrText)
        hrCard.addView(targetText)
        hrCard.addSpaced(statusText, 4)
        hrCard.addSpaced(simRow, 8)
        workoutView.addSpaced(hrCard)

        pauseBtn = btn("Pause") { Engine.togglePause() }
        workoutView.addSpaced(row(
            pauseBtn,
            btn("Skip phase") { Engine.skip() },
            btn("End", RED_BTN) { confirmEnd() },
        ))
        workoutView.addSpaced(tv("You can lock the phone — alerts and the clock keep running.", 13f, color = MUTED, center = true))
        root.addSpaced(workoutView, 4)

        // ---- summary
        summaryView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sumCard = card()
        sumCard.addView(tv("Session summary", 22f, bold = true, center = true))
        summaryMeta = tv("", 13f, color = MUTED, center = true)
        sumCard.addView(summaryMeta)
        summaryBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sumCard.addSpaced(summaryBody, 8)
        sumCard.addSpaced(tv("In zone = share of the interval at or above the work-zone floor. 60 s drop = fall in HR over the first minute of the next recovery.", 13f, color = MUTED), 8)
        summaryView.addSpaced(sumCard)
        summaryView.addSpaced(row(
            btn("Share CSV") { shareCsv() },
            btn("Done", GREEN_BTN) { Engine.dismissSummary() },
        ))
        root.addSpaced(summaryView, 4)
    }

    private fun updateZoneBpm() {
        val s = currentSettings()
        fun z(lo: String, hi: String) = "${s.bpm(s.n(lo))}–${s.bpm(s.n(hi))}"
        zoneBpmText.text = "Work ${z("workLo", "workHi")} · Recovery ${z("recLo", "recHi")} · Warm-up ${z("wuLo", "wuHi")} bpm"
    }

    private fun currentSettings(): Settings {
        val saved = Settings.load(this)
        val num = Settings.NUM_SPECS.associate { spec ->
            spec.key to (numInputs[spec.key]?.text?.toString()?.toDoubleOrNull() ?: saved.n(spec.key))
        }
        val bool = Settings.BOOL_SPECS.associate { spec -> spec.key to (boolInputs[spec.key]?.isChecked ?: saved.b(spec.key)) }
        return Settings(num, bool)
    }

    // ---------------------------------------------------------------- actions
    private fun startWorkout() {
        val s = currentSettings()
        s.save(this)
        if (!s.sim && Engine.conn != Conn.CONNECTED) { toast("Connect the strap first (or turn on the simulator)"); return }
        SimControl.bias = 0.0
        WorkoutService.send(this, WorkoutService.ACTION_START)
        scroll.scrollTo(0, 0)
    }

    private fun confirmEnd() {
        AlertDialog.Builder(this)
            .setMessage("End the workout now?")
            .setPositiveButton("End") { _, _ -> Engine.finish() }
            .setNegativeButton("Keep going", null)
            .show()
    }

    private fun shareCsv() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "4x4 session ${stamp()}")
            putExtra(Intent.EXTRA_TEXT, Engine.csv())
        }
        startActivity(Intent.createChooser(i, "Share session CSV"))
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(Engine.sessionStartWall))

    private fun saveCsvFile() {
        try {
            val dir = getExternalFilesDir("sessions") ?: filesDir
            val f = File(dir, "4x4_${stamp()}.csv")
            f.writeText(Engine.csv())
            savedCsv = f
        } catch (_: Exception) { savedCsv = null }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- render
    private fun fmt(sec: Double): String {
        val s = ceil(sec.coerceAtLeast(0.0)).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun blend(c: Int, amt: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * amt).roundToInt()
        return Color.rgb(ch(Color.red(BG), Color.red(c)), ch(Color.green(BG), Color.green(c)), ch(Color.blue(BG), Color.blue(c)))
    }

    private fun render() {
        // connection header
        connText.text = when (Engine.conn) {
            Conn.CONNECTED -> "● ${Engine.connName}" + (if (Engine.hrFresh()) " · ${Engine.lastHr}" else "")
            Conn.CONNECTING -> "Connecting…"
            Conn.LOST -> "Strap lost – retrying"
            Conn.NONE -> "No strap"
        }
        connText.setTextColor(when (Engine.conn) { Conn.CONNECTED -> C_IN; Conn.LOST -> C_HIGH; else -> MUTED })
        val savedName = prefs().getString("strapName", null)
        connectBtn.text = if (Engine.conn == Conn.CONNECTED) "Pick a different strap"
            else if (savedName != null) "Find strap (last: $savedName)" else "Connect Polar H10"

        val mode = when { Engine.running -> 1; Engine.finished -> 2; else -> 0 }
        setupView.visibility = if (mode == 0) View.VISIBLE else View.GONE
        workoutView.visibility = if (mode == 1) View.VISIBLE else View.GONE
        summaryView.visibility = if (mode == 2) View.VISIBLE else View.GONE

        val keepScreen = mode == 1 && (boolInputs["keepScreen"]?.isChecked ?: true)
        if (keepScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        when (mode) {
            0 -> scroll.setBackgroundColor(BG)
            1 -> renderWorkout()
            2 -> renderSummary()
        }
    }

    private fun renderWorkout() {
        val ph = Engine.currentPhase ?: return
        val el = Engine.phaseElapsed()
        val paused = Engine.pausedAt != null
        phaseText.text = ph.name + if (paused) " (paused)" else ""
        clockText.text = fmt(ph.durSec - el)
        val frac = (el / ph.durSec).coerceIn(0.0, 1.0)
        progressTrack.post {
            val w = (progressTrack.width * frac).roundToInt()
            val lp = progressFill.layoutParams
            if (lp.width != w) { lp.width = w; progressFill.layoutParams = lp }
        }
        val nx = Engine.plan.getOrNull(Engine.phaseIdx + 1)
        nextText.text = if (nx != null) "Next: ${nx.name} · ${fmt(nx.durSec)}" else "Last phase"
        pauseBtn.text = if (paused) "Resume" else "Pause"
        simRow.visibility = if (Engine.s?.sim == true) View.VISIBLE else View.GONE

        val fresh = Engine.hrFresh()
        hrText.text = if (fresh) Engine.lastHr.toString() else "--"
        targetText.text = ph.zone?.let { "Target ${it.lo}–${it.hi} bpm" } ?: "No target"
        val z = if (fresh) Engine.zone else ZS.NONE
        var label = when {
            !fresh -> "No signal"
            z == ZS.HIGH -> "TOO HIGH"
            z == ZS.LOW -> "TOO LOW"
            z == ZS.IN -> "IN ZONE"
            else -> ""
        }
        if (fresh && (z == ZS.HIGH || z == ZS.LOW) && Engine.suppressed(z)) {
            label += "  · ramping ${fmt((Engine.s?.grace ?: 0.0) - el)}"
        }
        statusText.text = label
        val color = when (z) { ZS.HIGH -> C_HIGH; ZS.LOW -> C_LOW; ZS.IN -> C_IN; ZS.NONE -> MUTED }
        statusText.setTextColor(color)
        scroll.setBackgroundColor(when (z) { ZS.HIGH -> blend(C_HIGH, .25f); ZS.LOW -> blend(C_LOW, .22f); ZS.IN -> blend(C_IN, .18f); ZS.NONE -> BG })
    }

    private fun renderSummary() {
        scroll.setBackgroundColor(BG)
        if (summaryShownFor == Engine.sessionStartWall) return
        summaryShownFor = Engine.sessionStartWall
        saveCsvFile()

        val s = Engine.s ?: return
        val plan = Engine.plan
        val samples = Engine.samples
        val floor = s.bpm(s.n("workLo"))
        summaryBody.removeAllViews()
        fun line(cols: List<String>, header: Boolean = false) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(5)) }
            cols.forEachIndexed { i, c ->
                r.addView(tv(c, 15f, bold = header, color = if (header) MUTED else TEXT).apply {
                    gravity = if (i == 0) Gravity.START else Gravity.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, if (i == 0) 1.3f else 1f))
            }
            summaryBody.addView(r)
        }
        line(listOf("Interval", "Avg", "Peak", "In zone", "60 s drop"), true)
        plan.forEachIndexed { i, ph ->
            if (ph.kind != Kind.WORK) return@forEachIndexed
            val xs = samples.filter { it.phase == i }.map { it.hr }
            if (xs.isEmpty()) { line(listOf("${ph.idx}", "–", "–", "–", "–")); return@forEachIndexed }
            val avg = xs.average().roundToInt()
            val peak = xs.max()
            val inz = (xs.count { it >= floor } * 100.0 / xs.size).roundToInt()
            var drop = "–"
            if (plan.getOrNull(i + 1)?.kind == Kind.REC) {
                val rec = samples.filter { it.phase == i + 1 }
                if (rec.isNotEmpty()) {
                    val t0 = rec[0].t
                    val w = rec.filter { it.t - t0 <= 60 }
                    if (w.isNotEmpty() && w.last().t - t0 >= 55) drop = (xs.last() - w.last().hr).toString()
                }
            }
            line(listOf("${ph.idx}", "$avg", "$peak", "$inz%", drop))
        }
        val mins = if (samples.isNotEmpty()) (samples.last().t / 60).roundToInt() else 0
        val date = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(Engine.sessionStartWall))
        summaryMeta.text = "$date · $mins min · max HR ${s.maxHr} (work floor $floor)" +
            (savedCsv?.let { "\nSaved: ${it.absolutePath}" } ?: "")
    }
}
