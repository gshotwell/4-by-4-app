package com.gshotwell.hr4x4

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale
import kotlin.math.ceil

/**
 * Foreground service that owns the strap connection, the workout clock and the alerts,
 * so everything keeps running with the screen off or the app in the background.
 */
class WorkoutService : Service(), Engine.Sink {
    companion object {
        const val ACTION_CONNECT = "connect"
        const val ACTION_START = "start"
        const val ACTION_END = "end"
        const val ACTION_STOP = "stop"
        const val ACTION_RESUME = "resume"
        const val EXTRA_ADDR = "addr"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "workout"
        private const val NOTIF_ID = 44

        fun send(ctx: Context, action: String, addr: String? = null, name: String? = null) {
            val i = Intent(ctx, WorkoutService::class.java).setAction(action)
            addr?.let { i.putExtra(EXTRA_ADDR, it) }
            name?.let { i.putExtra(EXTRA_NAME, it) }
            ctx.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ble: HrBle
    private lateinit var sounds: Sounds
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickCount = 0
    private var simHr = 95.0
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ble = HrBle(this)
        sounds = Sounds(this)
        Engine.sink = this
        Engine.store = SessionStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(sounds.attrs)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { sounds.releaseFocus() } }
                    override fun onStop(id: String?, interrupted: Boolean) { main.post { sounds.releaseFocus() } }
                    @Deprecated("old API")
                    override fun onError(id: String?) { main.post { sounds.releaseFocus() } }
                })
            }
        }
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
            started = true
        }
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_ADDR)?.let { ble.connect(it, intent.getStringExtra(EXTRA_NAME)) }
            ACTION_START -> startWorkout()
            ACTION_END -> Engine.finish()
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_RESUME, null -> tryResume()   // null = system restarted us after a kill
        }
        // Ask Android to restart the service if it's killed mid-workout.
        return if (Engine.running) START_STICKY else START_NOT_STICKY
    }

    private fun startWorkout() {
        val st = Settings.load(this)
        simHr = 95.0
        acquireWakeLock()
        Engine.start(st)
    }

    private fun tryResume() {
        if (Engine.running) return
        val store = Engine.store ?: return
        val saved = store.load() ?: return
        if (saved.resumable) {
            Engine.log("Resuming workout after the app was closed")
            acquireWakeLock()
            Engine.restore(saved)
            if (Engine.running) say("Workout resumed")
        } else {
            Engine.log("Recovered an unfinished workout from ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.startWall))}")
            Engine.recoverFinished(saved)
        }
        val p = getSharedPreferences(Settings.PREFS, MODE_PRIVATE)
        p.getString("strapAddr", null)?.let { ble.connect(it, p.getString("strapName", null)) }
    }

    override fun finished() {
        val peak = Engine.sessionPeak
        val p = getSharedPreferences(Settings.PREFS, MODE_PRIVATE)
        Engine.maxTestResult()?.let { r ->
            p.edit().putInt("maxTestResult", r.estimate).putLong("maxTestWall", Engine.sessionStartWall)
                .putBoolean("maxTestPlateau", r.plateau == true).apply()
            Engine.log("Max test: peak ${r.testPeak}" + (r.verifyPeak?.let { ", verification $it" } ?: "") + " → estimate ${r.estimate}")
        }
        if (peak > p.getInt("maxSeen", 0)) {
            p.edit().putInt("maxSeen", peak).putLong("maxSeenWall", Engine.sessionStartWall).apply()
            Engine.log("New highest heart rate recorded: $peak bpm")
        }
    }

    private fun stopEverything() {
        Engine.finish()
        ble.disconnect()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickCount++
            val st = Engine.s
            if (Engine.running && Engine.pausedAt == null && st?.sim == true && tickCount % 4 == 0) simulate()
            Engine.tick()
            if (!Engine.running) releaseWakeLock()
            if (tickCount % 4 == 0) updateNotification()
            main.postDelayed(this, 250)
        }
    }

    private fun simulate() {
        val st = Engine.s ?: return
        val ph = Engine.currentPhase ?: return
        val target = when (ph.kind) {
            Kind.WORK -> st.bpm((st.n("workLo") + st.n("workHi")) / 2)
            Kind.COOL -> 100
            Kind.WARMUP -> st.bpm((st.n("wuLo") + st.n("wuHi")) / 2)
            Kind.REC -> st.bpm((st.n("recLo") + st.n("recHi")) / 2)
        } + SimControl.bias
        simHr += (target - simHr) * 0.06 + (Math.random() - 0.5) * 2
        SimControl.bias *= 0.99
        Engine.onHr(simHr.toInt())
    }

    // ---------- Sink ----------
    override fun sound(s: Snd) = sounds.play(s, Engine.s?.vibrate ?: true)

    private var appliedVoice: String? = null

    /** Picks up voice / rate changes made in the app without restarting the service. */
    private fun applyVoicePrefs(t: TextToSpeech) {
        val p = getSharedPreferences(Settings.PREFS, MODE_PRIVATE)
        val want = p.getString("voiceName", null)
        if (want != appliedVoice) {
            appliedVoice = want
            val v = want?.let { n -> try { t.voices?.firstOrNull { it.name == n } } catch (_: Exception) { null } }
            if (v != null) t.voice = v else t.language = Locale.getDefault()
        }
        t.setSpeechRate(p.getFloat("speechRate", 1f).coerceIn(0.5f, 2.5f))
    }

    override fun say(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        applyVoicePrefs(t)
        sounds.acquireFocus()
        // slight delay so speech doesn't talk over the tone that usually precedes it
        main.postDelayed({
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
        }, 650)
    }

    /** Same signal as a headset "next" button: works with Tidal, Spotify, etc. */
    override fun mediaNext() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val t = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT, 0))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT, 0))
    }

    // ---------- notification ----------
    private fun fmt(sec: Double): String {
        val s = ceil(sec.coerceAtLeast(0.0)).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val hr = if (Engine.hrFresh()) "${Engine.lastHr} bpm" else "-- bpm"
        val ph = Engine.currentPhase
        if (Engine.running && ph != null) {
            val zs = when (Engine.zone) { ZS.HIGH -> " · TOO HIGH"; ZS.LOW -> " · TOO LOW"; ZS.IN -> " · in zone"; ZS.NONE -> "" }
            b.setContentTitle("${ph.name} · ${fmt(ph.durSec - Engine.phaseElapsed())}${if (Engine.pausedAt != null) " (paused)" else ""}")
            b.setContentText("$hr$zs" + (ph.zone?.let { " · target ${it.lo}–${it.hi}" } ?: ""))
            b.addAction(0, "End workout", servicePI(ACTION_END, 1))
        } else {
            val c = when (Engine.conn) {
                Conn.CONNECTED -> "Strap connected"
                Conn.CONNECTING -> "Connecting to strap…"
                Conn.LOST -> "Strap lost – retrying"
                Conn.NONE -> "No strap"
            }
            b.setContentTitle("4x4 Coach")
            b.setContentText("$c · $hr")
        }
        b.addAction(0, "Disconnect", servicePI(ACTION_STOP, 2))
        return b.build()
    }

    private fun servicePI(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, WorkoutService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun updateNotification() {
        if (!started) return
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }

    // ---------- wake lock ----------
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hr4x4:workout")
            .apply { acquire(3 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (Engine.sink === this) Engine.sink = null
        ble.disconnect()
        releaseWakeLock()
        tts?.shutdown()
        super.onDestroy()
    }
}

/** Nudges for the simulated heart rate (testing only). */
object SimControl { var bias = 0.0 }
