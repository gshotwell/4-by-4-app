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
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // ---------------------------------------------------------------- views
    private lateinit var scroll: ScrollView
    private lateinit var setupView: LinearLayout
    private lateinit var summaryView: LinearLayout
    private lateinit var workoutView: LinearLayout
    private lateinit var bottomBar: LinearLayout

    // setup
    private lateinit var strapDot: View
    private lateinit var strapName: TextView
    private lateinit var strapStatus: TextView
    private lateinit var strapHr: TextView
    private lateinit var findBtn: Button
    private lateinit var scanPanel: LinearLayout
    private lateinit var scanStatus: TextView
    private lateinit var planTimeline: TimelineView
    private lateinit var planTotal: TextView
    private val zoneBpm = mutableMapOf<String, TextView>()
    private val steppers = mutableMapOf<String, Stepper>()
    private val switches = mutableMapOf<String, Switch>()
    private lateinit var voiceBtn: Button
    private lateinit var logText: TextView
    private var shownLogVersion = -1

    // workout
    private lateinit var wPhaseKicker: TextView
    private lateinit var wPhaseTitle: TextView
    private lateinit var wStrap: TextView
    private lateinit var wTimeline: TimelineView
    private lateinit var wRing: RingView
    private lateinit var wClock: TextView
    private lateinit var wNext: TextView
    private lateinit var wHr: TextView
    private lateinit var wBpm: TextView
    private lateinit var wPill: TextView
    private lateinit var wGauge: ZoneGaugeView
    private lateinit var wPause: Button
    private lateinit var wSim: LinearLayout

    // summary
    private lateinit var sMeta: TextView
    private lateinit var sTiles: LinearLayout
    private lateinit var sMaxCard: LinearLayout
    private lateinit var maxSeenRow: LinearLayout
    private lateinit var maxSeenText: TextView
    private lateinit var maxSeenBtn: Button
    private lateinit var sChart: HrChartView
    private lateinit var sIntervals: LinearLayout
    private lateinit var sFoot: TextView
    private var summaryShownFor = 0L

    private lateinit var sounds: Sounds
    private var previewTts: TextToSpeech? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener: () -> Unit = { render() }
    private var lastMode = -1

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sounds = Sounds(this)
        buildUi()
        requestPermsIfNeeded()
        handleIntent(intent)
        if (Engine.logLines.isEmpty()) logPermState()
        if (!Engine.running && !Engine.finished && SessionStore.hasActive(this)) {
            WorkoutService.send(this, WorkoutService.ACTION_RESUME)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Engine.addListener(listener)
        autoConnect()
        lastMode = -1
        render()
    }

    override fun onPause() {
        stopScan()
        if (::scanPanel.isInitialized) scanPanel.visibility = View.GONE
        Engine.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        previewTts?.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        val a = i?.action ?: return
        if (a == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" || a == "android.intent.action.VIEW_PERMISSION_USAGE") {
            AlertDialog.Builder(this)
                .setTitle("Health Connect")
                .setMessage("4x4 Coach only writes data: each finished workout is saved as a HIIT exercise session with its heart-rate samples. It never reads your health data, and nothing leaves your phone.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---------------------------------------------------------------- Health Connect
    private fun requestHealth() {
        if (!HealthSync.available(this)) {
            toast("Health Connect isn't available. Install or update it from the Play Store, then try again.")
            switches["health"]?.isChecked = false
            return
        }
        scope.launch {
            if (!HealthSync.hasPermissions(this@MainActivity)) {
                try { startActivityForResult(HealthSync.permissionIntent(this@MainActivity), 2) }
                catch (e: Exception) { toast("Couldn't open Health Connect: ${e.message}") }
            }
        }
    }

    @Deprecated("Activity result API on plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            if (!HealthSync.parsePermissionResult(resultCode, data)) {
                toast("Health Connect permission not granted")
                switches["health"]?.isChecked = false
            } else toast("Health Connect connected")
        }
    }

    // ---------------------------------------------------------------- permissions
    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBtPerms() = btPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun logPermState() {
        val parts = (btPerms().toList() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            .map { it.substringAfterLast('.') + "=" + (if (checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) "yes" else "NO") }
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        Engine.log("Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}; Bluetooth ${if (bt?.isEnabled == true) "on" else "OFF"}; " + parts.joinToString(", "))
        if (Build.VERSION.SDK_INT < 31) {
            val lm = getSystemService(android.location.LocationManager::class.java)
            if (lm?.isLocationEnabled == false) Engine.log("Location is OFF – Android 11 and older can't scan for Bluetooth without it")
        }
    }

    private fun requestPermsIfNeeded() {
        val want = btPerms().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        logPermState()
        if (hasBtPerms()) autoConnect()
    }

    private fun prefs() = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)

    private fun autoConnect() {
        val addr = prefs().getString("strapAddr", null) ?: return
        if (!hasBtPerms()) { Engine.log("Can't auto-connect: Bluetooth permission missing"); return }
        if (Engine.conn != Conn.NONE) return
        WorkoutService.send(this, WorkoutService.ACTION_CONNECT, addr, prefs().getString("strapName", null))
    }

    // ---------------------------------------------------------------- scanning
    private var scanCb: ScanCallback? = null
    private val scanHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    private fun scanForStrap() {
        if (!hasBtPerms()) { requestPermsIfNeeded(); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { toast("No Bluetooth on this device"); return }
        if (!adapter.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        val scanner = adapter.bluetoothLeScanner ?: run { toast("Bluetooth scanner unavailable"); return }
        stopScan()

        val found = LinkedHashMap<String, String>()
        val others = HashSet<String>()
        Engine.log("Scanning for straps…")
        scanPanel.removeAllViews()
        scanStatus.text = "Searching… wear the strap with damp electrodes."
        scanPanel.addView(scanStatus)
        scanPanel.visibility = View.VISIBLE
        val hrUuid = ParcelUuid(HrBle.HR_SERVICE)

        val cb = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Engine.log("Scan failed: error $errorCode")
                scanStatus.text = "Scan failed (error $errorCode). Toggle Bluetooth and try again."
            }
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name
                val isHr = r.scanRecord?.serviceUuids?.contains(hrUuid) == true ||
                    name?.contains("Polar", ignoreCase = true) == true
                if (!isHr) { others.add(r.device.address); return }
                val addr = r.device.address
                if (addr in found) return
                val label = name ?: "Unknown strap"
                found[addr] = label
                Engine.log("Scan saw: $label ($addr) RSSI ${r.rssi}")
                scanStatus.text = "Tap your strap:"
                scanPanel.addSpaced(primaryBtn(label) { pickStrap(addr, label, "tapped") }, 8)
                if (found.size == 1) {
                    scanHandler.postDelayed({
                        if (scanCb != null && found.size == 1) pickStrap(addr, label, "only strap found")
                    }, 3000)
                }
            }
        }
        scanCb = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scanHandler.postDelayed({
            if (scanCb === cb) {
                stopScan()
                Engine.log("Scan timed out: ${found.size} heart-rate device(s), ${others.size} other device(s) seen")
                if (found.isEmpty()) scanStatus.text = "No strap found. Wear it with damp electrodes, close other apps using it, then try again."
            }
        }, 20_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        scanHandler.removeCallbacksAndMessages(null)
        try { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun pickStrap(addr: String, name: String, why: String) {
        stopScan()
        Engine.log("Picked $name ($why)")
        prefs().edit().putString("strapAddr", addr).putString("strapName", name).apply()
        scanPanel.removeAllViews()
        scanPanel.visibility = View.GONE
        WorkoutService.send(this, WorkoutService.ACTION_CONNECT, addr, name)
    }

    // ---------------------------------------------------------------- voice
    private fun voiceLabel(): String {
        val n = prefs().getString("voiceName", null) ?: return "Voice · phone default"
        return "Voice · " + prefs().getString("voiceLabel", n)
    }

    private fun describe(v: Voice): String {
        val loc = v.locale.getDisplayName(Locale.getDefault())
        val id = v.name.split("-").let { parts ->
            val x = parts.indexOf("x"); if (x >= 0 && x + 1 < parts.size) parts[x + 1] else v.name
        }
        val net = if (v.isNetworkConnectionRequired) " · needs internet" else ""
        return "$loc · $id$net"
    }

    private fun pickVoice() {
        voiceBtn.text = "Loading voices…"
        previewTts?.shutdown()
        lateinit var t: TextToSpeech
        t = TextToSpeech(this) { status ->
            runOnUiThread {
                voiceBtn.text = voiceLabel()
                if (status != TextToSpeech.SUCCESS) { toast("Text-to-speech isn't available"); return@runOnUiThread }
                val lang = Locale.getDefault().language
                val voices = (t.voices ?: emptySet())
                    .filter { it.locale.language == lang && !it.features.contains("notInstalled") }
                    .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.toString() }, { it.name }))
                if (voices.isEmpty()) { toast("No voices found for your language"); return@runOnUiThread }
                val labels = arrayOf("Phone default") + voices.map { describe(it) }.toTypedArray()
                val current = prefs().getString("voiceName", null)
                val checked = voices.indexOfFirst { it.name == current } + 1
                var choice = checked
                t.setSpeechRate(currentSettings().n("speechRate").toFloat())
                AlertDialog.Builder(this)
                    .setTitle("Pick a voice (tap to hear it)")
                    .setSingleChoiceItems(labels, checked) { _, which ->
                        choice = which
                        if (which == 0) t.language = Locale.getDefault() else t.voice = voices[which - 1]
                        t.speak("Interval two. Go. Target 157 to 176. Too high.", TextToSpeech.QUEUE_FLUSH, null, "preview")
                    }
                    .setPositiveButton("Use this voice") { _, _ ->
                        val e = prefs().edit()
                        if (choice <= 0) e.remove("voiceName").remove("voiceLabel")
                        else e.putString("voiceName", voices[choice - 1].name).putString("voiceLabel", labels[choice])
                        e.apply()
                        voiceBtn.text = voiceLabel()
                        Engine.log("Voice set to ${if (choice <= 0) "phone default" else labels[choice]}")
                    }
                    .setNegativeButton("Cancel", null)
                    .setOnDismissListener { t.stop(); t.shutdown(); if (previewTts === t) previewTts = null }
                    .show()
            }
        }
        previewTts = t
    }

    // ---------------------------------------------------------------- UI kit
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun tv(text: String = "", size: Float = 15f, color: Int = Ui.TEXT, face: Typeface = Ui.regular, center: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); typeface = face
            includeFontPadding = false
            if (center) gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun kicker(text: String, color: Int = Ui.MUTED) = tv(text.uppercase(), 12f, color, Ui.medium).apply { letterSpacing = 0.12f }

    private fun shape(color: Int, radius: Float = 16f, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun ripple(bg: GradientDrawable) =
        RippleDrawable(ColorStateList.valueOf(Ui.alpha(0xFFFFFFFF.toInt(), 0.14f)), bg, null)

    private fun baseBtn(text: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 16f; setTextColor(fg); typeface = Ui.medium
        background = ripple(shape(bg, 16f)); stateListAnimator = null
        minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onClick() }
    }
    private fun primaryBtn(text: String, onClick: () -> Unit) = baseBtn(text, Ui.PRIMARY, Ui.ON_PRIMARY, onClick)
    private fun secondaryBtn(text: String, onClick: () -> Unit) = baseBtn(text, Ui.SURFACE2, Ui.TEXT, onClick)
    private fun dangerBtn(text: String, onClick: () -> Unit) = baseBtn(text, Ui.DANGER, Ui.HIGH, onClick)

    private fun dot(color: Int, size: Int = 10) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun hrow(vararg views: View, weights: Boolean = true, gap: Int = 10) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val lp = if (weights) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            if (i > 0) lp.marginStart = dp(gap)
            addView(v, lp)
        }
    }

    private fun vcol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card(title: String? = null, trailing: View? = null): LinearLayout = vcol().apply {
        background = shape(Ui.SURFACE, 20f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        if (title != null) {
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(title, 17f, Ui.TEXT, Ui.medium), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            trailing?.let { head.addView(it) }
            addView(head)
        }
    }

    /** Card whose body folds away behind its header. */
    private fun foldCard(title: String, subtitle: String, open: Boolean = false, fill: (LinearLayout) -> Unit): LinearLayout {
        val chevron = tv(if (open) "▴" else "▾", 18f, Ui.MUTED)
        val c = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = vcol().apply {
            addView(tv(title, 17f, Ui.TEXT, Ui.medium))
            addView(tv(subtitle, 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
        head.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(chevron)
        val body = vcol().apply { visibility = if (open) View.VISIBLE else View.GONE }
        fill(body)
        head.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            chevron.text = if (show) "▴" else "▾"
        }
        c.addView(head)
        c.addView(body)
        return c
    }

    private fun LinearLayout.addSpaced(v: View, top: Int = 12, height: Int = WRAP_CONTENT) {
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, height).apply { topMargin = dp(top) })
    }

    private fun divider() = View(this).apply { setBackgroundColor(Ui.LINE) }

    // ---------------------------------------------------------------- stepper
    private data class Range(val step: Double, val min: Double, val max: Double)
    private val ranges = mapOf(
        "maxHr" to Range(1.0, 120.0, 230.0),
        "workLo" to Range(1.0, 50.0, 100.0), "workHi" to Range(1.0, 50.0, 105.0),
        "recLo" to Range(1.0, 40.0, 95.0), "recHi" to Range(1.0, 40.0, 100.0),
        "wuLo" to Range(1.0, 40.0, 95.0), "wuHi" to Range(1.0, 40.0, 100.0),
        "wuMin" to Range(0.5, 0.0, 30.0), "reps" to Range(1.0, 1.0, 10.0),
        "workMin" to Range(0.5, 0.5, 10.0), "recMin" to Range(0.5, 0.5, 10.0), "cdMin" to Range(0.5, 0.0, 30.0),
        "grace" to Range(5.0, 0.0, 180.0), "confirm" to Range(1.0, 0.0, 10.0), "hyst" to Range(1.0, 0.0, 8.0),
        "remind" to Range(5.0, 0.0, 120.0), "speechRate" to Range(0.1, 0.5, 2.5),
    )

    private inner class Stepper(val key: String, initial: Double, compact: Boolean) {
        var value = initial
        val r = ranges[key] ?: Range(1.0, 0.0, 999.0)
        val label = tv("", if (compact) 16f else 18f, Ui.TEXT, Ui.medium).apply {
            gravity = Gravity.CENTER; fontFeatureSettings = "tnum"
        }
        val view: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = shape(Ui.SURFACE2, 14f)
            val bw = if (compact) dp(34) else dp(42)
            val bh = if (compact) dp(36) else dp(42)
            addView(stepBtn("−", -1), LinearLayout.LayoutParams(bw, bh))
            addView(label, LinearLayout.LayoutParams(if (compact) dp(38) else dp(56), WRAP_CONTENT))
            addView(stepBtn("+", +1), LinearLayout.LayoutParams(bw, bh))
        }

        init { show() }

        fun set(v: Double) { value = v.coerceIn(r.min, r.max); show() }

        private fun show() {
            label.text = if (r.step < 1.0 && value % 1.0 != 0.0) "%.1f".format(value).trimEnd('0').trimEnd('.') else value.toInt().toString()
        }

        fun bump(dir: Int) {
            val nv = ((value + dir * r.step).coerceIn(r.min, r.max) * 10).roundToInt() / 10.0
            if (nv == value) return
            value = nv; show(); onSettingsChanged()
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun stepBtn(sym: String, dir: Int) = TextView(this@MainActivity).apply {
            text = sym; textSize = 22f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER; typeface = Ui.light
            background = ripple(shape(0x00000000, 14f))
            isClickable = true
            // tap = one step; hold = repeat
            val h = Handler(Looper.getMainLooper())
            val repeat = object : Runnable { override fun run() { bump(dir); h.postDelayed(this, 70) } }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; bump(dir); h.postDelayed(repeat, 450) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; h.removeCallbacks(repeat) }
                }
                true
            }
        }
    }

    private fun stepper(key: String, compact: Boolean = false): View {
        val s = Stepper(key, Settings.load(this).n(key), compact)
        steppers[key] = s
        return s.view
    }

    private fun settingRow(label: String, sub: String? = null, control: View): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = vcol()
        texts.addView(tv(label, 15f))
        if (sub != null) texts.addView(tv(sub, 12f, Ui.MUTED).apply { setPadding(0, dp(3), dp(8), 0) })
        l.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        l.addView(control)
        return l
    }

    private fun switchRow(key: String, label: String, sub: String? = null): View {
        val sw = Switch(this).apply {
            isChecked = Settings.load(this@MainActivity).b(key)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.PRIMARY, 0xFFB8C0CC.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.alpha(Ui.PRIMARY, 0.45f), Ui.FAINT))
            setOnCheckedChangeListener { _, checked ->
                onSettingsChanged()
                if (key == "health" && checked) requestHealth()
            }
        }
        switches[key] = sw
        val row = settingRow(label, sub, sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    // ---------------------------------------------------------------- build
    private fun buildUi() {
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val frame = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }

        val main = vcol()
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val content = vcol().apply { setPadding(dp(16), dp(20), dp(16), dp(24)) }
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setBackgroundColor(Ui.BG)
        }
        main.addView(bottomBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        frame.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setupView = vcol(); buildSetup(setupView); content.addView(setupView)
        summaryView = vcol(); buildSummary(summaryView); content.addView(summaryView)

        workoutView = vcol().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        buildWorkout(workoutView)
        frame.addView(workoutView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(frame)
        refreshDerived()
    }

    private fun buildSetup(v: LinearLayout) {
        // header
        v.addView(tv("4x4 Coach", 30f, Ui.TEXT, Ui.medium))
        v.addView(tv("Norwegian interval trainer", 15f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })

        // strap
        val strap = card()
        strapDot = dot(Ui.FAINT, 12)
        strapName = tv("No strap", 17f, Ui.TEXT, Ui.medium)
        strapStatus = tv("Tap Find strap to pair your Polar H10", 13f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }
        strapHr = tv("", 34f, Ui.TEXT, Ui.light).apply { fontFeatureSettings = "tnum" }
        val names = vcol().apply { addView(strapName); addView(strapStatus) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(strapDot)
        top.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        top.addView(strapHr)
        strap.addView(top)
        findBtn = secondaryBtn("Find strap") { scanForStrap() }
        strap.addSpaced(findBtn, 14)
        scanStatus = tv("", 13f, Ui.MUTED)
        scanPanel = vcol().apply { visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        strap.addView(scanPanel)
        v.addSpaced(strap, 20)

        // session plan
        planTotal = tv("", 15f, Ui.MUTED)
        val plan = card("Session", planTotal)
        planTimeline = TimelineView(this)
        plan.addSpaced(planTimeline, 14, dp(10))
        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("Warm-up" to Ui.WARM, "Work" to Ui.WORK, "Recovery" to Ui.REC, "Cool-down" to Ui.COOL).forEachIndexed { i, (n, c) ->
            legend.addView(dot(c, 8).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = if (i == 0) 0 else dp(14) })
            legend.addView(tv(n, 12f, Ui.MUTED).apply { setPadding(dp(6), 0, 0, 0) })
        }
        plan.addSpaced(legend, 10)
        plan.addSpaced(divider(), 14, dp(1))
        plan.addView(settingRow("Warm-up", "minutes", stepper("wuMin")))
        plan.addView(settingRow("Intervals", null, stepper("reps")))
        plan.addView(settingRow("Work", "minutes each", stepper("workMin")))
        plan.addView(settingRow("Recovery", "minutes between", stepper("recMin")))
        plan.addView(settingRow("Cool-down", "minutes", stepper("cdMin")))
        v.addSpaced(plan)

        // zones
        val zones = card("Heart-rate zones")
        zones.addView(settingRow("Max heart rate", "every zone is a % of this", stepper("maxHr")))
        maxSeenText = tv("", 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }
        maxSeenBtn = secondaryBtn("Use") {
            val v = prefs().getInt("maxSeen", 0)
            if (v > 0) setMaxHr(v)
        }.apply { minHeight = dp(40); minimumHeight = dp(40); textSize = 14f }
        maxSeenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(maxSeenText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(maxSeenBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        zones.addView(maxSeenRow)
        zones.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        fun zoneRow(name: String, color: Int, lo: String, hi: String, key: String) {
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
            l.addView(dot(color, 10))
            val texts = vcol()
            texts.addView(tv(name, 15f))
            val bpm = tv("", 12f, Ui.MUTED).apply { setPadding(0, dp(3), 0, 0); fontFeatureSettings = "tnum" }
            zoneBpm[key] = bpm
            texts.addView(bpm)
            l.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            l.addView(stepper(lo, compact = true))
            l.addView(tv("–", 15f, Ui.MUTED).apply { setPadding(dp(6), 0, dp(6), 0) })
            l.addView(stepper(hi, compact = true))
            zones.addView(l)
        }
        zoneRow("Work", Ui.WORK, "workLo", "workHi", "work")
        zoneRow("Recovery", Ui.REC, "recLo", "recHi", "rec")
        zoneRow("Warm-up", Ui.WARM, "wuLo", "wuHi", "wu")
        zones.addSpaced(tv("Values are % of max heart rate.", 12f, Ui.MUTED), 10)
        v.addSpaced(zones)

        // music + health
        val extras = card("Music & health")
        extras.addView(switchRow("skipWork", "Next song when an interval starts", "Works with Tidal, Spotify and other players"))
        extras.addView(switchRow("skipRec", "Next song when a recovery starts"))
        extras.addView(switchRow("health", "Save to Health Connect", "Exercise session + heart rate"))
        v.addSpaced(extras)

        // voice & alerts
        v.addSpaced(foldCard("Voice & alerts", "What you hear, and when") { b ->
            voiceBtn = secondaryBtn(voiceLabel()) { pickVoice() }
            b.addSpaced(voiceBtn, 14)
            b.addView(settingRow("Speech rate", "1 = normal", stepper("speechRate")))
            b.addView(switchRow("voice", "Spoken announcements"))
            b.addView(switchRow("vibrate", "Vibrate"))
            b.addView(switchRow("countdown", "Countdown beeps", "Last 3 seconds of each phase"))
            b.addView(divider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
            b.addView(settingRow("Ramp grace", "Seconds of silence while HR climbs into work or drops into recovery", stepper("grace")))
            b.addView(settingRow("Hold time", "Seconds a change must last before alerting", stepper("confirm")))
            b.addView(settingRow("Hysteresis", "bpm back inside a limit to count as in zone", stepper("hyst")))
            b.addView(settingRow("Repeat", "Seconds between reminders while out of zone (0 = off)", stepper("remind")))
            b.addSpaced(hrow(
                secondaryBtn("Test high") { sounds.play(Snd.HIGH, true) },
                secondaryBtn("Test low") { sounds.play(Snd.LOW, true) },
                secondaryBtn("Test in") { sounds.play(Snd.IN, true) },
                gap = 8
            ), 10)
        })

        // advanced
        v.addSpaced(foldCard("Advanced", "Screen, simulator, connection log") { b ->
            b.addView(switchRow("keepScreen", "Keep screen on during workouts", "Alerts work with the screen off either way"))
            b.addView(switchRow("sim", "Simulated heart rate", "Test the app without the strap"))
            b.addSpaced(kicker("Connection log"), 12)
            logText = tv("", 11f, Ui.MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setLineSpacing(0f, 1.2f) }
            val logBox = vcol().apply { background = shape(Ui.BG, 12f); setPadding(dp(12), dp(10), dp(12), dp(10)); addView(logText) }
            b.addSpaced(logBox, 8)
            b.addSpaced(hrow(
                secondaryBtn("Copy log") {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("4x4 log", Engine.logLines.joinToString("\n")))
                    toast("Log copied")
                },
                dangerBtn("Disconnect strap") { WorkoutService.send(this, WorkoutService.ACTION_STOP) },
                gap = 8
            ), 10)
        })
        v.addSpaced(tv("v${packageManager.getPackageInfo(packageName, 0).versionName}", 12f, Ui.FAINT, center = true), 18)
    }

    private fun buildWorkout(v: LinearLayout) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = vcol()
        wPhaseKicker = kicker("Interval")
        wPhaseTitle = tv("—", 26f, Ui.TEXT, Ui.medium).apply { setPadding(0, dp(6), 0, 0) }
        titles.addView(wPhaseKicker); titles.addView(wPhaseTitle)
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        wStrap = tv("", 13f, Ui.MUTED)
        top.addView(wStrap)
        v.addView(top)

        wTimeline = TimelineView(this)
        v.addSpaced(wTimeline, 16, dp(8))

        wRing = RingView(this)
        val inner = vcol().apply { gravity = Gravity.CENTER }
        wClock = tv("0:00", 76f, Ui.TEXT, Ui.light, center = true).apply { fontFeatureSettings = "tnum"; letterSpacing = -0.02f }
        wNext = tv("", 14f, Ui.MUTED, center = true).apply { setPadding(0, dp(10), 0, 0) }
        inner.addView(wClock)
        inner.addView(wNext)
        wRing.addView(inner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        v.addView(wRing, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        val hrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
        wHr = tv("--", 92f, Ui.TEXT, Ui.light).apply { fontFeatureSettings = "tnum"; letterSpacing = -0.03f }
        wBpm = tv("bpm", 18f, Ui.MUTED).apply { setPadding(dp(8), 0, 0, dp(14)) }
        hrRow.addView(wHr); hrRow.addView(wBpm)
        v.addSpaced(hrRow, 4)

        wPill = tv("", 14f, Ui.TEXT, Ui.medium).apply {
            letterSpacing = 0.1f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val pillWrap = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(wPill) }
        v.addSpaced(pillWrap, 6)

        wGauge = ZoneGaugeView(this)
        v.addSpaced(wGauge, 14)

        wSim = hrow(
            secondaryBtn("−10") { SimControl.bias -= 10 }, secondaryBtn("−3") { SimControl.bias -= 3 },
            secondaryBtn("+3") { SimControl.bias += 3 }, secondaryBtn("+10") { SimControl.bias += 10 }, gap = 8
        )
        v.addSpaced(wSim, 8)

        wPause = secondaryBtn("Pause") { Engine.togglePause() }
        v.addSpaced(hrow(
            wPause,
            secondaryBtn("Skip") { Engine.skip() },
            dangerBtn("End") { confirmEnd() },
            gap = 10
        ), 16)
    }

    private fun buildSummary(v: LinearLayout) {
        v.addView(kicker("Workout complete", Ui.IN))
        v.addView(tv("Session summary", 30f, Ui.TEXT, Ui.medium).apply { setPadding(0, dp(8), 0, 0) })
        sMeta = tv("", 14f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) }
        v.addView(sMeta)

        sMaxCard = card().apply { visibility = View.GONE }
        v.addSpaced(sMaxCard, 18)
        sTiles = vcol()
        v.addSpaced(sTiles, 12)

        val chart = card("Heart rate")
        sChart = HrChartView(this)
        chart.addSpaced(sChart, 12, dp(200))
        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        legend.addView(dot(Ui.alpha(Ui.WORK, 0.5f), 8))
        legend.addView(tv("Work interval", 12f, Ui.MUTED).apply { setPadding(dp(6), 0, dp(14), 0) })
        legend.addView(dot(Ui.alpha(Ui.IN, 0.7f), 8))
        legend.addView(tv("Target zone", 12f, Ui.MUTED).apply { setPadding(dp(6), 0, 0, 0) })
        chart.addSpaced(legend, 10)
        v.addSpaced(chart, 12)

        val iv = card("Intervals")
        sIntervals = vcol()
        iv.addView(sIntervals)
        iv.addSpaced(tv("In zone = share of the interval at or above the work-zone floor. Drop = how far HR fell in the first minute of the next recovery; bigger is fitter.", 12f, Ui.MUTED), 12)
        v.addSpaced(iv, 12)

        sFoot = tv("", 12f, Ui.MUTED, center = true).apply { setLineSpacing(0f, 1.3f) }
        v.addSpaced(sFoot, 14)
    }

    // ---------------------------------------------------------------- settings plumbing
    private fun currentSettings(): Settings {
        val saved = Settings.load(this)
        val num = Settings.NUM_SPECS.associate { it.key to (steppers[it.key]?.value ?: saved.n(it.key)) }
        val bool = Settings.BOOL_SPECS.associate { it.key to (switches[it.key]?.isChecked ?: saved.b(it.key)) }
        return Settings(num, bool)
    }

    private fun onSettingsChanged() {
        currentSettings().save(this)
        refreshDerived()
    }

    private fun refreshDerived() {
        if (!::planTimeline.isInitialized) return
        val s = currentSettings()
        val plan = Engine.buildPlan(s)
        planTimeline.plan = plan
        planTimeline.current = -1
        planTotal.text = "${(plan.sumOf { it.durSec } / 60).roundToInt()} min"
        fun z(lo: String, hi: String) = "${s.bpm(s.n(lo))}–${s.bpm(s.n(hi))} bpm"
        zoneBpm["work"]?.text = z("workLo", "workHi")
        zoneBpm["rec"]?.text = z("recLo", "recHi")
        zoneBpm["wu"]?.text = z("wuLo", "wuHi")
        val seen = prefs().getInt("maxSeen", 0)
        if (seen <= 0) {
            maxSeenText.text = "Highest seen: none yet. A hard session will record your peak, and the app will offer to update this when you beat it."
            maxSeenBtn.visibility = View.GONE
        } else {
            val d = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(prefs().getLong("maxSeenWall", 0)))
            maxSeenText.text = "Highest seen: $seen bpm ($d)" +
                if (seen < s.maxHr) "\nBelow your setting. That's normal: 4x4 sessions rarely reach true max." else ""
            maxSeenBtn.visibility = if (seen > s.maxHr) View.VISIBLE else View.GONE
            maxSeenBtn.text = "Use $seen"
        }
    }

    // ---------------------------------------------------------------- actions
    private fun startWorkout() {
        val s = currentSettings()
        s.save(this)
        if (!s.sim && Engine.conn != Conn.CONNECTED) { toast("Connect the strap first (or turn on the simulator under Advanced)"); return }
        SimControl.bias = 0.0
        WorkoutService.send(this, WorkoutService.ACTION_START)
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

    private fun setMaxHr(v: Int) {
        steppers["maxHr"]?.set(v.toDouble())
        onSettingsChanged()
        toast("Max heart rate set to $v. Zones updated.")
    }

    private fun footText() = listOfNotNull(Engine.exportStatus.ifBlank { null }, Engine.savedCsvPath?.let { "CSV saved to $it" }).joinToString("\n")

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- render
    private fun fmt(sec: Double): String {
        val s = ceil(sec.coerceAtLeast(0.0)).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun setBottomBar(mode: Int) {
        bottomBar.removeAllViews()
        when (mode) {
            0 -> bottomBar.addView(primaryBtn("Start workout") { startWorkout() }.apply { textSize = 17f; minHeight = dp(58) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            2 -> {
                bottomBar.addView(secondaryBtn("Share CSV") { shareCsv() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                bottomBar.addView(primaryBtn("Done") { Engine.dismissSummary() },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            }
        }
    }

    private fun render() {
        val mode = when { Engine.running -> 1; Engine.finished -> 2; else -> 0 }
        if (mode != lastMode) {
            lastMode = mode
            setupView.visibility = if (mode == 0) View.VISIBLE else View.GONE
            summaryView.visibility = if (mode == 2) View.VISIBLE else View.GONE
            workoutView.visibility = if (mode == 1) View.VISIBLE else View.GONE
            scroll.visibility = if (mode == 1) View.GONE else View.VISIBLE
            bottomBar.visibility = if (mode == 1) View.GONE else View.VISIBLE
            setBottomBar(mode)
            scroll.scrollTo(0, 0)
            if (mode == 0) { refreshDerived(); window.decorView.setBackgroundColor(Ui.BG); workoutView.setBackgroundColor(Ui.BG) }
        }
        val keepScreen = mode == 1 && (switches["keepScreen"]?.isChecked ?: true)
        if (keepScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        when (mode) {
            0 -> renderSetup()
            1 -> renderWorkout()
            2 -> renderSummary()
        }
    }

    private fun renderSetup() {
        val fresh = Engine.hrFresh()
        val saved = prefs().getString("strapName", null)
        val (dotColor, name, status) = when (Engine.conn) {
            Conn.CONNECTED -> Triple(Ui.IN, Engine.connName.ifBlank { "Strap" }, if (fresh) "Connected · receiving heart rate" else "Connected · waiting for heart rate")
            Conn.CONNECTING -> Triple(0xFFFBBF24.toInt(), Engine.connName.ifBlank { saved ?: "Strap" }, "Connecting…")
            Conn.LOST -> Triple(Ui.HIGH, Engine.connName.ifBlank { saved ?: "Strap" }, "Signal lost · reconnecting")
            Conn.NONE -> Triple(Ui.FAINT, saved ?: "No strap", if (saved != null) "Not connected" else "Tap Find strap to pair your Polar H10")
        }
        (strapDot.background as GradientDrawable).setColor(dotColor)
        strapName.text = name
        strapStatus.text = status
        strapHr.text = if (fresh) "${Engine.lastHr}" else ""
        findBtn.text = if (Engine.conn == Conn.CONNECTED) "Pair a different strap" else "Find strap"

        if (shownLogVersion != Engine.logVersion) {
            shownLogVersion = Engine.logVersion
            logText.text = if (Engine.logLines.isEmpty()) "(nothing yet)" else Engine.logLines.toList().takeLast(30).joinToString("\n")
        }
    }

    private fun renderWorkout() {
        val ph = Engine.currentPhase ?: return
        val el = Engine.phaseElapsed()
        val paused = Engine.pausedAt != null
        val kindCol = Ui.kindColor(ph.kind)

        wPhaseKicker.text = when (ph.kind) {
            Kind.WORK -> "WORK · ${ph.idx} OF ${Engine.plan.count { it.kind == Kind.WORK }}"
            Kind.REC -> "RECOVERY"
            Kind.WARMUP -> "WARM-UP"
            Kind.COOL -> "COOL-DOWN"
        } + if (paused) " · PAUSED" else ""
        wPhaseKicker.setTextColor(kindCol)
        wPhaseTitle.text = when (ph.kind) {
            Kind.WORK -> "Interval ${ph.idx}"
            Kind.REC -> "Recover"
            Kind.WARMUP -> "Warm up"
            Kind.COOL -> "Cool down"
        }
        wStrap.text = when (Engine.conn) {
            Conn.CONNECTED -> "● strap"
            Conn.LOST -> "● reconnecting"
            Conn.CONNECTING -> "● connecting"
            Conn.NONE -> if (Engine.s?.sim == true) "simulator" else "no strap"
        }
        wStrap.setTextColor(when (Engine.conn) { Conn.CONNECTED -> Ui.IN; Conn.LOST -> Ui.HIGH; else -> Ui.MUTED })

        val frac = (el / ph.durSec).toFloat().coerceIn(0f, 1f)
        wTimeline.plan = Engine.plan
        wTimeline.current = Engine.phaseIdx
        wTimeline.frac = frac
        wRing.color = if (paused) Ui.FAINT else kindCol
        wRing.progress = frac
        wClock.text = fmt(ph.durSec - el)
        wClock.setTextColor(if (paused) Ui.MUTED else Ui.TEXT)
        val nx = Engine.plan.getOrNull(Engine.phaseIdx + 1)
        wNext.text = if (nx != null) "then ${nx.name.lowercase()} · ${fmt(nx.durSec)}" else "last phase"
        wPause.text = if (paused) "Resume" else "Pause"
        wSim.visibility = if (Engine.s?.sim == true) View.VISIBLE else View.GONE

        val fresh = Engine.hrFresh()
        val z = if (fresh) Engine.zone else ZS.NONE
        val zc = Ui.zoneColor(z)
        wHr.text = if (fresh) Engine.lastHr.toString() else "--"
        wHr.setTextColor(if (z == ZS.NONE) Ui.TEXT else zc)

        var label = when {
            !fresh -> "NO SIGNAL"
            ph.zone == null -> "EASY"
            z == ZS.HIGH -> "TOO HIGH"
            z == ZS.LOW -> "TOO LOW"
            z == ZS.IN -> "IN ZONE"
            else -> "…"
        }
        if (fresh && (z == ZS.HIGH || z == ZS.LOW) && Engine.suppressed(z)) {
            label = if (z == ZS.LOW) "RAMPING UP · ${fmt((Engine.s?.grace ?: 0.0) - el)}" else "SETTLING · ${fmt((Engine.s?.grace ?: 0.0) - el)}"
        }
        val pillCol = if (label.startsWith("RAMP") || label.startsWith("SETTL")) Ui.MUTED else if (z == ZS.NONE) Ui.MUTED else zc
        wPill.text = label
        wPill.setTextColor(pillCol)
        wPill.background = shape(Ui.alpha(pillCol, 0.16f), 100f)

        val zone = ph.zone
        if (zone != null) {
            wGauge.visibility = View.VISIBLE
            wGauge.set(zone.lo, zone.hi, if (fresh) Engine.lastHr else null, if (z == ZS.NONE) Ui.TEXT else zc)
        } else wGauge.visibility = View.INVISIBLE

        val bg = if (z == ZS.NONE || label.startsWith("RAMP") || label.startsWith("SETTL")) Ui.BG else Ui.blend(Ui.BG, zc, if (z == ZS.IN) 0.08f else 0.16f)
        workoutView.setBackgroundColor(bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
    }

    private fun tile(value: String, label: String, color: Int = Ui.TEXT) = vcol().apply {
        background = shape(Ui.SURFACE, 18f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(tv(value, 30f, color, Ui.light).apply { fontFeatureSettings = "tnum" })
        addView(tv(label, 12f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun renderSummary() {
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        sFoot.text = footText()
        if (summaryShownFor == Engine.sessionStartWall) return
        summaryShownFor = Engine.sessionStartWall

        if (Engine.exportedFor != Engine.sessionStartWall) {
            Engine.exportedFor = Engine.sessionStartWall
            Engine.exportStatus = ""
            if (switches["health"]?.isChecked == true) {
                Engine.exportStatus = "Saving to Health Connect…"
                val start = Engine.sessionStartWall
                val samples = Engine.samples.toList()
                val reps = Engine.s?.n("reps")?.toInt() ?: 4
                HealthSync.scope.launch {
                    Engine.exportStatus = HealthSync.write(applicationContext, start, samples, reps)
                    sFoot.text = footText()
                }
            }
        }

        val s = Engine.s ?: return
        val plan = Engine.plan
        val samples = Engine.samples
        val floor = s.bpm(s.n("workLo"))

        data class Row(val idx: Int, val avg: Int, val peak: Int, val inz: Int, val drop: Int?)
        val rows = mutableListOf<Row>()
        plan.forEachIndexed { i, ph ->
            if (ph.kind != Kind.WORK) return@forEachIndexed
            val xs = samples.filter { it.phase == i }.map { it.hr }
            if (xs.isEmpty()) return@forEachIndexed
            var drop: Int? = null
            if (plan.getOrNull(i + 1)?.kind == Kind.REC) {
                val rec = samples.filter { it.phase == i + 1 }
                if (rec.isNotEmpty()) {
                    val t0 = rec[0].t
                    val w = rec.filter { it.t - t0 <= 60 }
                    if (w.isNotEmpty() && w.last().t - t0 >= 55) drop = xs.last() - w.last().hr
                }
            }
            rows += Row(ph.idx, xs.average().roundToInt(), xs.max(), (xs.count { it >= floor } * 100.0 / xs.size).roundToInt(), drop)
        }

        val mins = if (samples.isNotEmpty()) (samples.last().t / 60).roundToInt() else 0
        val date = SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.getDefault()).format(Date(Engine.sessionStartWall))
        sMeta.text = "$date · $mins min"

        // tiles
        val workSamples = samples.filter { it.kind == Kind.WORK }.map { it.hr }
        val avgWork = if (workSamples.isNotEmpty()) workSamples.average().roundToInt().toString() else "–"
        val peak = if (samples.isNotEmpty()) samples.maxOf { it.hr }.toString() else "–"
        val inZone = if (workSamples.isNotEmpty()) "${(workSamples.count { it >= floor } * 100.0 / workSamples.size).roundToInt()}%" else "–"
        val drops = rows.mapNotNull { it.drop }
        val avgDrop = if (drops.isNotEmpty()) "${drops.average().roundToInt()}" else "–"
        sMaxCard.removeAllViews()
        sMaxCard.visibility = View.GONE
        val peakHr = Engine.sessionPeak
        if (peakHr > s.maxHr) {
            sMaxCard.visibility = View.VISIBLE
            sMaxCard.addView(kicker("New max heart rate", Ui.WORK))
            sMaxCard.addSpaced(tv("You hit $peakHr bpm, above your max setting of ${s.maxHr}. Your zones are percentages of max, so they're currently set too low.", 14f, Ui.TEXT).apply { setLineSpacing(0f, 1.25f) }, 8)
            sMaxCard.addSpaced(primaryBtn("Set max heart rate to $peakHr") { setMaxHr(peakHr); sMaxCard.visibility = View.GONE }, 12)
        }
        sTiles.removeAllViews()
        sTiles.addView(hrow(tile(avgWork, "Avg work HR"), tile(peak, "Peak HR"), gap = 10))
        sTiles.addSpaced(hrow(tile(inZone, "Work time ≥ $floor bpm", Ui.IN), tile(avgDrop, "Avg 1-min drop"), gap = 10), 10)

        sChart.set(samples.toList(), plan)

        sIntervals.removeAllViews()
        if (rows.isEmpty()) sIntervals.addSpaced(tv("No interval data recorded.", 14f, Ui.MUTED), 10)
        rows.forEach { r ->
            val block = vcol().apply { setPadding(0, dp(14), 0, 0) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv("Interval ${r.idx}", 15f, Ui.TEXT, Ui.medium), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(tv("avg ${r.avg} · peak ${r.peak}" + (r.drop?.let { " · drop $it" } ?: ""), 13f, Ui.MUTED).apply { fontFeatureSettings = "tnum" })
            block.addView(head)
            val bar = BarView(this).apply { frac = r.inz / 100f; color = if (r.inz >= 70) Ui.IN else if (r.inz >= 40) 0xFFFBBF24.toInt() else Ui.HIGH }
            val barRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            barRow.addView(bar, LinearLayout.LayoutParams(0, dp(8), 1f))
            barRow.addView(tv("${r.inz}% in zone", 12f, Ui.MUTED).apply { setPadding(dp(10), 0, 0, 0); fontFeatureSettings = "tnum" })
            block.addSpaced(barRow, 8)
            sIntervals.addView(block)
        }
    }
}
