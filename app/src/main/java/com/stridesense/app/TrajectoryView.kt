package com.stridesense.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TrajectoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22313B.toInt()
        strokeWidth = dp(1f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF344B59.toInt()
        strokeWidth = dp(1f)
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF54E2BD.toInt()
        strokeWidth = dp(3.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF83A9FF.toInt() }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8FA2AE.toInt()
        textSize = dp(10f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private var route: List<TrackPoint> = emptyList()

    init {
        setBackgroundColor(0xFF0F171F.toInt())
        contentDescription = "Top-down two-dimensional movement trajectory"
    }

    fun setRoute(points: List<TrackPoint>) {
        route = points.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val bounds = RectF(left, top, right, bottom)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return

        val halfSpan = route.fold(1.8f) { acc, point -> max(acc, max(abs(point.xMeters), abs(point.yMeters)) * 1.18f) }
        val pixelsPerMeter = min(bounds.width(), bounds.height()) * 0.44f / halfSpan
        val originX = bounds.centerX()
        val originY = bounds.centerY()

        for (meter in -5..5) {
            val offset = meter * pixelsPerMeter
            val x = originX + offset
            val y = originY - offset
            if (x in bounds.left..bounds.right) {
                canvas.drawLine(x, bounds.top, x, bounds.bottom, if (meter == 0) axisPaint else gridPaint)
            }
            if (y in bounds.top..bounds.bottom) {
                canvas.drawLine(bounds.left, y, bounds.right, y, if (meter == 0) axisPaint else gridPaint)
            }
        }

        canvas.drawText("N", originX - dp(4f), bounds.top + dp(16f), labelPaint)
        canvas.drawText("E", bounds.right - dp(15f), originY - dp(7f), labelPaint)
        canvas.drawText("TOP VIEW  ·  METRES", bounds.left + dp(12f), bounds.top + dp(17f), labelPaint)

        if (route.isEmpty()) {
            canvas.drawCircle(originX, originY, dp(4f), startPaint)
            return
        }

        val path = Path()
        route.forEachIndexed { index, point ->
            val px = originX + point.xMeters * pixelsPerMeter
            val py = originY - point.yMeters * pixelsPerMeter
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, routePaint)
        val first = route.first()
        val last = route.last()
        canvas.drawCircle(originX + first.xMeters * pixelsPerMeter, originY - first.yMeters * pixelsPerMeter, dp(5f), startPaint)
        val lastX = originX + last.xMeters * pixelsPerMeter
        val lastY = originY - last.yMeters * pixelsPerMeter
        canvas.drawCircle(lastX, lastY, dp(8f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4454E2BD })
        canvas.drawCircle(lastX, lastY, dp(4.5f), endPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
