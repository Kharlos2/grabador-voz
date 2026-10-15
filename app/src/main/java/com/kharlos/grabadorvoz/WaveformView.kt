package com.kharlos.grabadorvoz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.log10

/**
 * Dibuja ondas de audio en barras.
 * - Modo en vivo: las barras entran por la derecha mientras se graba.
 * - Modo estático: ondas fijas del audio con una bolita que avanza y se puede arrastrar.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val barW = 3f * dp
    private val gap = 2f * dp
    private val minBarH = 3f * dp
    private val knobR = 8f * dp

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.primary) }
    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 150, 150, 150) }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.record_red) }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.primary) }
    private val rect = RectF()

    private var live = false
    private val liveLevels = ArrayList<Float>()
    private var levels: FloatArray? = null
    private var bars: FloatArray? = null
    private var prog = 0f
    private var dragging = false

    var showKnob = false

    var progress: Float
        get() = prog
        set(value) {
            prog = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Devuelve el progreso actual (0..1) en cada fotograma mientras [animating] es true. */
    var positionProvider: (() -> Float)? = null

    var animating = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation() else invalidate()
        }

    /** (progreso, terminó de arrastrar) */
    var onSeek: ((Float, Boolean) -> Unit)? = null

    fun startLive() {
        live = true
        showKnob = false
        liveLevels.clear()
        invalidate()
    }

    fun addLiveLevel(level: Float) {
        liveLevels.add(level)
        if (liveLevels.size > 2000) liveLevels.subList(0, 1000).clear()
        invalidate()
    }

    fun setLiveActive(active: Boolean) {
        livePaint.color = if (active) ContextCompat.getColor(context, R.color.record_red) else Color.argb(170, 150, 150, 150)
        invalidate()
    }

    /** Picos del audio (0..1). null = barras planas mientras carga. */
    fun setPeaks(peaks: FloatArray?) {
        live = false
        levels = peaks?.let { p -> FloatArray(p.size) { level(p[it]) } }
        bars = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bars = null
    }

    private fun contentLeft() = paddingLeft.toFloat()
    private fun contentWidth() = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
    private fun barCount() = ((contentWidth() + gap) / (barW + gap)).toInt().coerceAtLeast(1)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = barCount()
        val left = contentLeft()
        val usableH = (height - paddingTop - paddingBottom).toFloat()
        val cy = paddingTop + usableH / 2f

        if (live) {
            val start = (liveLevels.size - n).coerceAtLeast(0)
            for (j in 0 until n) {
                val idx = start + j
                val has = idx < liveLevels.size
                drawBar(canvas, left + j * (barW + gap), cy, if (has) liveLevels[idx] else 0f, usableH, if (has) livePaint else restPaint)
            }
            return
        }

        val src = levels
        val b = bars ?: (if (src != null && src.isNotEmpty()) resample(src, n) else FloatArray(n)).also { bars = it }

        if (animating && !dragging) positionProvider?.let { prog = it().coerceIn(0f, 1f) }
        val knobX = left + prog * contentWidth()

        for (i in 0 until n) {
            val x = left + i * (barW + gap)
            val paint = if (x + barW / 2f <= knobX) playedPaint else restPaint
            drawBar(canvas, x, cy, b[i], usableH - knobR, paint)
        }
        if (showKnob) canvas.drawCircle(knobX, cy, knobR, knobPaint)

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawBar(c: Canvas, x: Float, cy: Float, level: Float, maxH: Float, paint: Paint) {
        val h = minBarH + level * (maxH - minBarH).coerceAtLeast(0f)
        rect.set(x, cy - h / 2f, x + barW, cy + h / 2f)
        c.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (live || onSeek == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                seekToX(event.x, false)
            }
            MotionEvent.ACTION_MOVE -> seekToX(event.x, false)
            MotionEvent.ACTION_UP -> {
                seekToX(event.x, true)
                dragging = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSeek?.invoke(prog, true)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun seekToX(x: Float, finished: Boolean) {
        prog = ((x - contentLeft()) / contentWidth()).coerceIn(0f, 1f)
        onSeek?.invoke(prog, finished)
        invalidate()
    }

    companion object {
        /** Convierte amplitud (0..1) a altura de barra (0..1) en escala de decibeles, para que la voz se vea bien. */
        fun level(amp: Float): Float {
            if (amp <= 0.0001f) return 0f
            val db = 20f * log10(amp)
            return ((db + 45f) / 45f).coerceIn(0f, 1f)
        }

        fun resample(src: FloatArray, n: Int): FloatArray = FloatArray(n) { i ->
            val s = (i.toLong() * src.size / n).toInt()
            val e = ((i + 1).toLong() * src.size / n).toInt().coerceAtLeast(s + 1).coerceAtMost(src.size)
            var m = 0f
            for (k in s until e) if (src[k] > m) m = src[k]
            m
        }
    }
}
