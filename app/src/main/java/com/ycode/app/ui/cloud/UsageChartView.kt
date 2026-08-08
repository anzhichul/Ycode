package com.ycode.app.ui.cloud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class UsageChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Style { LINE, BARS }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var labels = emptyList<String>()
    private var values = emptyList<Double>()
    private var style = Style.BARS

    fun setData(labels: List<String>, values: List<Double>, style: Style) {
        this.labels = labels
        this.values = values
        this.style = style
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(5f)
        val right = width - paddingRight - dp(5f)
        val top = paddingTop + dp(8f)
        val bottom = height - paddingBottom - dp(25f)
        if (values.isEmpty() || right <= left || bottom <= top) return drawEmpty(canvas)
        val peak = max(values.maxOrNull() ?: 0.0, 1.0)
        paint.strokeWidth = dp(1f)
        paint.color = Color.rgb(224, 231, 241)
        repeat(3) { index ->
            val y = top + (bottom - top) * index / 2f
            canvas.drawLine(left, y, right, y, paint)
        }
        if (style == Style.LINE) drawLine(canvas, left, right, top, bottom, peak) else drawBars(canvas, left, right, top, bottom, peak)
        drawLabels(canvas, left, right, bottom)
    }

    private fun drawLine(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, peak: Double) {
        val step = if (values.size <= 1) 0f else (right - left) / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + step * index
            val y = bottom - ((value / peak) * (bottom - top)).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = Color.rgb(20, 108, 255)
        canvas.drawPath(path, paint)
        values.forEachIndexed { index, value ->
            paint.style = Paint.Style.FILL
            canvas.drawCircle(left + step * index, bottom - ((value / peak) * (bottom - top)).toFloat(), dp(3f), paint)
        }
    }

    private fun drawBars(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, peak: Double) {
        val slot = (right - left) / values.size
        val barWidth = slot * .58f
        values.forEachIndexed { index, value ->
            val x = left + slot * index + (slot - barWidth) / 2f
            val y = bottom - ((value / peak) * (bottom - top)).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = COLORS[index % COLORS.size]
            canvas.drawRoundRect(x, y, x + barWidth, bottom, dp(5f), dp(5f), paint)
        }
    }

    private fun drawLabels(canvas: Canvas, left: Float, right: Float, bottom: Float) {
        paint.color = Color.rgb(112, 126, 147)
        paint.textSize = dp(9f)
        paint.textAlign = Paint.Align.CENTER
        val slot = (right - left) / labels.size.coerceAtLeast(1)
        labels.forEachIndexed { index, label ->
            val x = if (style == Style.LINE && labels.size > 1) left + (right - left) * index / (labels.size - 1) else left + slot * index + slot / 2f
            canvas.drawText(label.take(10), x, bottom + dp(17f), paint)
        }
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.color = Color.rgb(125, 137, 155)
        paint.textSize = dp(11f)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("暂无统计数据", width / 2f, height / 2f, paint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    companion object {
        private val COLORS = intArrayOf(Color.rgb(20, 108, 255), Color.rgb(22, 134, 95), Color.rgb(112, 88, 214), Color.rgb(236, 143, 48), Color.rgb(195, 69, 85))
    }
}
