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
import kotlin.math.abs

/** Ondas del audio con dos manijas (inicio y fin) para recortar, y una línea de reproducción. */
class TrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val barW = 3f * dp
    private val gap = 2f * dp
    private val minBarH = 3f * dp
    private val gripR = 9f * dp
    private val handleW = 3f * dp

    private val primary = ContextCompat.getColor(context, R.color.primary)
    private val inPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 150, 150, 150) }
    private val selPaint = Paint().apply { color = Color.argb(28, Color.red(primary), Color.green(primary), Color.blue(primary)) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.record_red)
        strokeWidth = 2f * dp
    }
    private val rect = RectF()

    private var levels: FloatArray? = null
    private var bars: FloatArray? = null

    var start = 0f
        private set
    var end = 1f
        private set

    /** Posición de reproducción (0..1); negativo = oculta. */
    var cursor = -1f
        set(value) {
            field = value
            invalidate()
        }

    /** Separación mínima entre manijas (fracción del total). */
    var minGap = 0.02f

    /** (inicio, fin, terminó de arrastrar) */
    var onChange: ((Float, Float, Boolean) -> Unit)? = null

    private var dragging = 0 // 0 nada, 1 inicio, 2 fin

    fun setPeaks(peaks: FloatArray?) {
        levels = peaks?.let { p -> FloatArray(p.size) { WaveformView.level(p[it]) } }
        bars = null
        invalidate()
    }

    fun setRange(s: Float, e: Float) {
        start = s.coerceIn(0f, 1f)
        end = e.coerceIn(start, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bars = null
    }

    private fun left() = paddingLeft.toFloat()
    private fun contentW() = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
    private fun xOf(f: Float) = left() + f * contentW()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = ((contentW() + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        val src = levels
        val b = bars ?: (if (src != null && src.isNotEmpty()) WaveformView.resample(src, n) else FloatArray(n)).also { bars = it }

        val top = paddingTop + gripR * 2
        val bottom = height - paddingBottom - gripR * 2
        val cy = (top + bottom) / 2f
        val maxH = (bottom - top).coerceAtLeast(minBarH)
        val sx = xOf(start)
        val ex = xOf(end)

        // Zona seleccionada
        rect.set(sx, paddingTop + gripR, ex, height - paddingBottom - gripR)
        canvas.drawRect(rect, selPaint)

        // Barras
        for (i in 0 until n) {
            val x = left() + i * (barW + gap)
            val mid = x + barW / 2f
            val h = minBarH + b[i] * (maxH - minBarH)
            rect.set(x, cy - h / 2f, x + barW, cy + h / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, if (mid in sx..ex) inPaint else outPaint)
        }

        // Manijas
        for (x in floatArrayOf(sx, ex)) {
            rect.set(x - handleW / 2f, paddingTop + gripR, x + handleW / 2f, height - paddingBottom - gripR)
            canvas.drawRect(rect, handlePaint)
            canvas.drawCircle(x, paddingTop + gripR, gripR, handlePaint)
            canvas.drawCircle(x, height - paddingBottom - gripR, gripR, handlePaint)
        }

        // Línea de reproducción
        if (cursor >= 0f) {
            val cx = xOf(cursor)
            canvas.drawLine(cx, paddingTop + gripR * 2, cx, height - paddingBottom - gripR * 2, cursorPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val f = ((event.x - left()) / contentW()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = if (abs(event.x - xOf(start)) <= abs(event.x - xOf(end))) 1 else 2
                move(f)
            }
            MotionEvent.ACTION_MOVE -> move(f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != 0) onChange?.invoke(start, end, true)
                dragging = 0
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun move(f: Float) {
        when (dragging) {
            1 -> start = f.coerceIn(0f, (end - minGap).coerceAtLeast(0f))
            2 -> end = f.coerceIn((start + minGap).coerceAtMost(1f), 1f)
            else -> return
        }
        onChange?.invoke(start, end, false)
        invalidate()
    }
}
