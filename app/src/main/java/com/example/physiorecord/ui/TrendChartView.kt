package com.example.physiorecord.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 简易折线图：横轴是"第几个点（从旧到新）"，纵轴是原始值。
 *
 * 数据由外面喂进来（"取哪一段、取多少条"是外面的事），本控件只负责画：
 *   1. 先算值的上下界（全等值时人为撑开一点，避免除零）；
 *   2. 把每个点映射到画布坐标（X 按时间比例，Y 按值比例）；
 *   3. 画轴线、画折线、点少的时候画圆点；
 *   4. 没有数据时画一句"还没有记录"。
 *
 * 只在界面线上操作；数据变化时调 setSeries 触发重画。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Point(val x: Float, val y: Float)

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E88E5")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 30f
    }

    private var points: List<Point> = emptyList()
    private var hasData = false

    /** 喂数据：一串（时刻毫秒, 原始值），按时间从旧到新传进来 */
    fun setSeries(series: List<Pair<Long, Int>>) {
        if (series.size < 2) {
            points = emptyList()
            hasData = series.isNotEmpty()
            invalidate()
            return
        }
        val vMin = series.minOf { it.second }.toFloat()
        val vMax = series.maxOf { it.second }.toFloat()
        var vLow = vMin
        var vHigh = vMax
        if (vHigh - vLow < 0.5f) {
            // 全等值或只差一点：撑开一个固定窗口，线才会落在中间而不是贴边
            vLow -= 1f
            vHigh += 1f
        }
        val vSpan = vHigh - vLow
        // 横轴按"第几个点"均匀铺开，而不是按时间比例：
        // 连续读取时几笔之间只差几秒，按时间算会挤成一根竖线，看不出走势。
        // 时间信息已经由下方列表逐行给出。
        val lastIndex = (series.size - 1).toFloat().takeIf { it > 0f } ?: 1f
        points = series.mapIndexed { i, (_, v) ->
            Point(i / lastIndex, (v - vLow) / vSpan)
        }
        hasData = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 16f
        val top = paddingTop + 12f
        val right = width - paddingRight - 16f
        val bottom = height - paddingBottom - 24f
        if (right <= left || bottom <= top) return

        // 轴线：左竖线与下横线
        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        if (!hasData || points.isEmpty()) {
            val msg = if (hasData) "数据点不够，先多采几次" else "还没有记录"
            canvas.drawText(msg, left + 8f, (top + bottom) / 2f, textPaint)
            return
        }

        // 折线：横轴按点序均匀铺满宽度，纵轴按值比例铺满高度（Y 轴翻转）
        val path = Path()
        points.forEachIndexed { i, p ->
            val px = left + p.x * (right - left)
            val py = bottom - p.y * (bottom - top)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, linePaint)

        // 点少时补圆点，便于看出每一笔
        if (points.size <= 80) {
            points.forEach { p ->
                val px = left + p.x * (right - left)
                val py = bottom - p.y * (bottom - top)
                canvas.drawCircle(px, py, 4f, dotPaint)
            }
        }
    }
}
