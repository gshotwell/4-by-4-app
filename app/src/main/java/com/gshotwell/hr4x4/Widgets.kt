package com.gshotwell.hr4x4

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/** Design tokens shared by the activity and the custom views. */
object Ui {
    const val BG = 0xFF0B0E14.toInt()
    const val SURFACE = 0xFF141923.toInt()
    const val SURFACE2 = 0xFF1C2330.toInt()
    const val LINE = 0xFF262E3B.toInt()
    const val TEXT = 0xFFF2F5F9.toInt()
    const val MUTED = 0xFF8A94A6.toInt()
    const val FAINT = 0xFF4A5468.toInt()

    const val LOW = 0xFF60A5FA.toInt()
    const val IN = 0xFF34D399.toInt()
    const val HIGH = 0xFFF87171.toInt()

    const val WORK = 0xFFFB923C.toInt()
    const val REC = 0xFF60A5FA.toInt()
    const val WARM = 0xFFA78BFA.toInt()
    const val COOL = 0xFF94A3B8.toInt()

    const val PRIMARY = 0xFF34D399.toInt()
    const val ON_PRIMARY = 0xFF06281C.toInt()
    const val DANGER = 0xFF3A1D22.toInt()

    fun kindColor(k: Kind) = when (k) { Kind.WORK -> WORK; Kind.REC -> REC; Kind.WARMUP -> WARM; Kind.COOL -> COOL }
    fun zoneColor(z: ZS) = when (z) { ZS.HIGH -> HIGH; ZS.LOW -> LOW; ZS.IN -> IN; ZS.NONE -> MUTED }
    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
}

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Circular phase progress; children (the clock) sit in the middle. */
class RingView(ctx: Context) : FrameLayout(ctx) {
    var progress = 0f; set(v) { if (field != v) { field = v; invalidate() } }
    var color = Ui.WORK; set(v) { if (field != v) { field = v; invalidate() } }
    private val stroke = ctx.dpf(14f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Ui.SURFACE2 }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        val size = min(width, height) - stroke * 1.2f
        val cx = width / 2f; val cy = height / 2f
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        c.drawArc(rect, 0f, 360f, false, track)
        arc.color = color
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep > 0.5f) c.drawArc(rect, -90f, sweep, false, arc)
    }
}

/** Whole-session strip: one segment per phase, width proportional to duration. */
class TimelineView(ctx: Context) : View(ctx) {
    var plan: List<Phase> = emptyList(); set(v) { field = v; invalidate() }
    var current = -1; set(v) { if (field != v) { field = v; invalidate() } }
    var frac = 0f; set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val gap = ctx.dpf(3f)

    override fun onDraw(c: Canvas) {
        if (plan.isEmpty()) return
        val total = plan.sumOf { it.durSec }.toFloat()
        val usable = width - gap * (plan.size - 1)
        val h = height.toFloat(); val rad = h / 2
        var x = 0f
        plan.forEachIndexed { i, ph ->
            val w = max(usable * (ph.durSec.toFloat() / total), h)
            val col = Ui.kindColor(ph.kind)
            r.set(x, 0f, x + w, h)
            p.color = when {
                current < 0 -> Ui.alpha(col, 0.85f)
                i < current -> Ui.alpha(col, 0.9f)
                else -> Ui.alpha(col, 0.22f)
            }
            c.drawRoundRect(r, rad, rad, p)
            if (i == current) {
                p.color = col
                r.set(x, 0f, x + max(w * frac, h), h)
                c.drawRoundRect(r, rad, rad, p)
            }
            x += w + gap
        }
    }
}

/** Horizontal gauge: target band highlighted, marker at the current heart rate. */
class ZoneGaugeView(ctx: Context) : View(ctx) {
    var lo = 0; var hi = 0
    var hr: Int? = null
    var markerColor = Ui.TEXT
    fun set(lo: Int, hi: Int, hr: Int?, color: Int) {
        if (this.lo == lo && this.hi == hi && this.hr == hr && markerColor == color) return
        this.lo = lo; this.hi = hi; this.hr = hr; markerColor = color; invalidate()
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dpf(12f); color = Ui.MUTED; typeface = Ui.regular; textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val barH = ctx.dpf(10f)
    private val markR = ctx.dpf(9f)

    override fun onDraw(c: Canvas) {
        if (hi <= lo) return
        val span = hi - lo
        val min = lo - max(20, span)
        val max = hi + max(12, span / 2)
        val padX = markR + ctx().dpf(2f)
        val w = width - padX * 2
        fun x(v: Int) = padX + w * ((v - min).toFloat() / (max - min)).coerceIn(0f, 1f)
        val cy = markR + ctx().dpf(2f)
        // track
        p.color = Ui.SURFACE2
        r.set(padX, cy - barH / 2, padX + w, cy + barH / 2)
        c.drawRoundRect(r, barH / 2, barH / 2, p)
        // low and high flanks, faintly tinted
        p.color = Ui.alpha(Ui.LOW, 0.25f)
        r.set(padX, cy - barH / 2, x(lo), cy + barH / 2); c.drawRoundRect(r, barH / 2, barH / 2, p)
        p.color = Ui.alpha(Ui.HIGH, 0.25f)
        r.set(x(hi), cy - barH / 2, padX + w, cy + barH / 2); c.drawRoundRect(r, barH / 2, barH / 2, p)
        // target band
        p.color = Ui.IN
        r.set(x(lo), cy - barH / 2, x(hi), cy + barH / 2)
        c.drawRoundRect(r, barH / 2, barH / 2, p)
        // labels
        val ty = cy + barH / 2 + txt.textSize + ctx().dpf(6f)
        c.drawText("$lo", x(lo), ty, txt)
        c.drawText("$hi", x(hi), ty, txt)
        // marker
        hr?.let { v ->
            val mx = x(v)
            p.color = Ui.BG; c.drawCircle(mx, cy, markR + ctx().dpf(3f), p)
            p.color = markerColor; c.drawCircle(mx, cy, markR, p)
            p.color = Ui.BG; c.drawCircle(mx, cy, markR * 0.38f, p)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (markR * 2 + ctx().dpf(4f) + txt.textSize + ctx().dpf(10f)).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun ctx() = context
}

/** Session heart-rate trace with work intervals and their target band shaded. */
class HrChartView(ctx: Context) : View(ctx) {
    private var samples: List<Sample> = emptyList()
    private var plan: List<Phase> = emptyList()
    fun set(samples: List<Sample>, plan: List<Phase>) { this.samples = samples; this.plan = plan; invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2f); color = Ui.TEXT; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.LINE; strokeWidth = ctx.dpf(1f) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dpf(11f); color = Ui.MUTED; typeface = Ui.regular }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        if (samples.size < 2) {
            txt.textAlign = Paint.Align.CENTER
            c.drawText("Not enough heart-rate data", width / 2f, height / 2f, txt)
            return
        }
        val left = context.dpf(30f); val bottom = height - context.dpf(18f); val top = context.dpf(6f)
        val w = width - left - context.dpf(4f)
        val tMax = samples.last().t.coerceAtLeast(1.0)
        val zoneVals = plan.mapNotNull { it.zone }.flatMap { listOf(it.lo, it.hi) }
        var yMin = min(samples.minOf { it.hr }, zoneVals.minOrNull() ?: 200) - 8
        var yMax = max(samples.maxOf { it.hr }, zoneVals.maxOrNull() ?: 0) + 6
        yMin = (yMin / 10) * 10; yMax = ((yMax + 9) / 10) * 10
        fun x(t: Double) = left + w * (t / tMax).toFloat()
        fun y(v: Int) = bottom - (bottom - top) * ((v - yMin).toFloat() / (yMax - yMin))

        // phase backgrounds from the samples' own timestamps
        plan.forEachIndexed { i, ph ->
            if (ph.kind != Kind.WORK) return@forEachIndexed
            val xs = samples.filter { it.phase == i }
            if (xs.isEmpty()) return@forEachIndexed
            val x0 = x(xs.first().t); val x1 = x(xs.last().t)
            fill.color = Ui.alpha(Ui.WORK, 0.10f)
            c.drawRect(x0, top, x1, bottom, fill)
            ph.zone?.let { z ->
                fill.color = Ui.alpha(Ui.IN, 0.22f)
                c.drawRect(x0, y(z.hi), x1, y(z.lo), fill)
            }
        }
        // grid
        txt.textAlign = Paint.Align.RIGHT
        val step = if (yMax - yMin > 80) 20 else 10
        var g = yMin
        while (g <= yMax) {
            c.drawLine(left, y(g), left + w, y(g), grid)
            c.drawText("$g", left - context.dpf(5f), y(g) + txt.textSize / 3, txt)
            g += step
        }
        txt.textAlign = Paint.Align.CENTER
        val minutes = (tMax / 60).toInt()
        val every = if (minutes > 40) 10 else 5
        var m = 0
        while (m <= minutes) {
            c.drawText("${m}′", x(m * 60.0), height.toFloat() - context.dpf(3f), txt)
            m += every
        }
        // trace
        path.reset()
        samples.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.t), y(s.hr)) else path.lineTo(x(s.t), y(s.hr)) }
        c.drawPath(path, line)
    }
}

/** Thin horizontal bar, 0..1. */
class BarView(ctx: Context) : View(ctx) {
    var frac = 0f; set(v) { field = v; invalidate() }
    var color = Ui.IN
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = Ui.SURFACE2; r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, p)
        if (frac > 0) { p.color = color; r.set(0f, 0f, max(h, width * frac.coerceIn(0f, 1f)), h); c.drawRoundRect(r, h / 2, h / 2, p) }
    }
}
