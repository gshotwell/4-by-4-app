package com.gshotwell.hr4x4

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Synthesised alert tones + vibration. Ducks music while playing, like a nav app. */
class Sounds(private val ctx: Context) {
    private enum class Wave { SINE, SQUARE, TRIANGLE, SAW }
    private data class Note(val f: Double, val start: Double, val dur: Double, val vol: Double, val wave: Wave = Wave.SINE)

    private val rate = 44100
    private val main = Handler(Looper.getMainLooper())
    val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private var focusHolders = 0

    fun acquireFocus() { if (focusHolders++ == 0) am?.requestAudioFocus(focus) }
    fun releaseFocus() { if (focusHolders > 0 && --focusHolders == 0) am?.abandonAudioFocusRequest(focus) }

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    fun play(s: Snd, vibrate: Boolean) {
        when (s) {
            Snd.HIGH -> { notes(Note(1046.0, 0.0, .18, .45, Wave.SQUARE), Note(784.0, .22, .18, .45, Wave.SQUARE), Note(523.0, .44, .3, .45, Wave.SQUARE)); if (vibrate) vib(0, 300, 100, 300) }
            Snd.LOW -> { notes(Note(523.0, 0.0, .18, .5, Wave.TRIANGLE), Note(784.0, .22, .18, .5, Wave.TRIANGLE), Note(1046.0, .44, .3, .5, Wave.TRIANGLE)); if (vibrate) vib(0, 120, 80, 120, 80, 120) }
            Snd.IN -> { notes(Note(1318.0, 0.0, .5, .35)); if (vibrate) vib(0, 80) }
            Snd.PHASE -> { notes(Note(880.0, 0.0, .12, .4), Note(880.0, .18, .12, .4), Note(1318.0, .36, .45, .4)); if (vibrate) vib(0, 200, 100, 200) }
            Snd.TICK -> notes(Note(660.0, 0.0, .08, .3))
            Snd.LOST -> { notes(Note(330.0, 0.0, .4, .4, Wave.SAW), Note(247.0, .45, .5, .4, Wave.SAW)); if (vibrate) vib(0, 500) }
        }
    }

    private fun vib(vararg pattern: Long) {
        try { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) } catch (_: Exception) {}
    }

    private fun notes(vararg ns: Note) {
        val total = ns.maxOf { it.start + it.dur } + 0.05
        val n = (total * rate).toInt()
        val buf = DoubleArray(n)
        for (note in ns) {
            val s0 = (note.start * rate).toInt()
            val len = (note.dur * rate).toInt()
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx >= n) break
                val t = i.toDouble() / rate
                val attack = (t / 0.015).coerceAtMost(1.0)
                val env = attack * exp(-4.0 * t / note.dur)
                val ph = (note.f * t) % 1.0
                val w = when (note.wave) {
                    Wave.SINE -> sin(2 * PI * ph)
                    Wave.SQUARE -> if (ph < 0.5) 0.6 else -0.6
                    Wave.TRIANGLE -> 4 * abs(ph - 0.5) - 1
                    Wave.SAW -> (2 * ph - 1) * 0.6
                }
                buf[idx] += w * env * note.vol
            }
        }
        val pcm = ShortArray(n) { (buf[it].coerceIn(-1.0, 1.0) * 32000).toInt().toShort() }
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            acquireFocus()
            track.play()
            main.postDelayed({
                try { track.stop() } catch (_: Exception) {}
                track.release()
                releaseFocus()
            }, (total * 1000).toLong() + 150)
        } catch (_: Exception) {}
    }
}
