package com.example.physiorecord.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.card.MaterialCardView

/**
 * 界面小工具：统一的卡片、统计块、折线图样式。
 *
 * 这些控件都是 Material 组件（卡片、按钮、Chip、滑杆都从这里取），
 * 保证所有页面的圆角、描边、间距一致。
 */
object UiKit {

    const val CYAN = 0xFF00E5FF.toInt()
    const val BLUE = 0xFF2D7DFF.toInt()
    const val TEXT = 0xFFDCE7F5.toInt()
    const val DIM = 0xFF7E90B0.toInt()
    const val STROKE = 0xFF1E3A5F.toInt()

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    /** 统一卡片：半透明深底 + 冷色描边 */
    fun card(context: Context): MaterialCardView = MaterialCardView(context).apply {
        cardElevation = 0f
        strokeWidth = dp(context, 1f)
        strokeColor = STROKE
        setCardBackgroundColor(0xE60B1526.toInt())
        radius = dp(context, 14f).toFloat()
    }

    /** 卡片里的内容容器 */
    fun body(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 14f), dp(context, 12f), dp(context, 14f), dp(context, 12f))
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(CYAN)
        textSize = 11f
        letterSpacing = 0.16f
    }

    fun hint(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(DIM)
        textSize = 12f
        setPadding(0, dp(context, 4f), 0, 0)
    }

    fun title(context: Context, text: String, size: Float = 22f): TextView = TextView(context).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = size
    }

    fun text(context: Context, text: String, size: Float = 14f, color: Int = TEXT): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        setPadding(0, dp(context, 4f), 0, 0)
    }

    fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height)

    /** 一行统计块：大数字 + 小标签 */
    fun stat(context: Context, value: String, label: String): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
        }
        box.addView(TextView(context).apply {
            text = value
            setTextColor(CYAN)
            textSize = 24f
        })
        box.addView(TextView(context).apply {
            text = label
            setTextColor(DIM)
            textSize = 11f
        })
        return box
    }

    /**
     * 功能块（宫格里的一个格子）：一块可点的卡片，上面功能名、下面一句状态/说明。
     * 新增功能只需增加一块宫格，页面布局不需要整体改动。
     */
    fun tile(context: Context, title: String, sub: String, onClick: () -> Unit): MaterialCardView {
        val card = card(context)
        val body = body(context)
        body.addView(TextView(context).apply {
            text = title
            setTextColor(TEXT)
            textSize = 16f
        })
        body.addView(TextView(context).apply {
            text = sub
            setTextColor(DIM)
            textSize = 11f
            setPadding(0, dp(context, 3f), 0, 0)
        })
        card.addView(body)
        card.isClickable = true
        card.setOnClickListener { onClick() }
        return card
    }

    /** 统一的折线图风格：暗底、青色线、淡填充、无网格噪声 */
    fun lineChart(context: Context, heightDp: Float = 220f): LineChart {
        val chart = LineChart(context)
        chart.layoutParams = lp(height = dp(context, heightDp))
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textColor = DIM
        chart.legend.textSize = 10f
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        chart.axisRight.isEnabled = false
        chart.axisLeft.textColor = DIM
        chart.axisLeft.gridColor = 0x1A2D7DFF
        chart.axisLeft.axisLineColor = STROKE
        chart.xAxis.textColor = DIM
        chart.xAxis.gridColor = 0x142D7DFF
        chart.xAxis.axisLineColor = STROKE
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawGridLines(false)
        chart.setNoDataText("还没有记录")
        chart.setNoDataTextColor(DIM)
        return chart
    }

    /** 把「时刻-数值」喂给折线：横轴按点序均匀铺开 */
    fun applySeries(chart: LineChart, series: List<Pair<Long, Int>>) {
        if (series.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }
        val entries = series.mapIndexed { i, (_, v) -> Entry(i.toFloat(), v.toFloat()) }
        val set = LineDataSet(entries, "读数").apply {
            color = CYAN
            lineWidth = 2.4f
            setCircleColor(CYAN)
            circleRadius = 3f
            setDrawCircleHole(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = CYAN
            fillAlpha = 40
            highLightColor = CYAN
            setDrawHorizontalHighlightIndicator(false)
        }
        chart.data = LineData(set)
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = value.toInt().toString()
        }
        chart.invalidate()
    }

    /** 多序列：x 就是读数下标，各指标按同一读数下标对齐；缺的指标跳过，不用别的读数补 */
    fun applyMultiSeries(chart: LineChart, seriesByMetric: Map<String, List<Pair<Int, Float>>>) {
        val usable = seriesByMetric.filterValues { it.isNotEmpty() }
        if (usable.isEmpty()) {
            chart.clear()
            chart.invalidate()
            return
        }
        val palette = intArrayOf(CYAN, 0xFFFFC857.toInt(), 0xFFFF6B9A.toInt(), 0xFF7BE0C4.toInt())
        val sets = usable.entries.mapIndexed { idx, (metric, series) ->
            val entries = series.map { (i, v) -> Entry(i.toFloat(), v) }
            LineDataSet(entries, metric).apply {
                color = palette[idx % palette.size]
                lineWidth = 2.2f
                setCircleColor(color)
                circleRadius = 2.2f
                setDrawCircleHole(false)
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
                highLightColor = color
                setDrawHorizontalHighlightIndicator(false)
            }
        }
        chart.data = LineData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.ILineDataSet })
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = value.toInt().toString()
        }
        chart.invalidate()
    }

    /** 柱状图：横向是定义里的指标名，纵向是最近一次读数 */
    fun barChart(context: Context, heightDp: Float): BarChart = BarChart(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp))
        description.isEnabled = false
        legend.isEnabled = false
        setDrawGridBackground(false)
        setScaleEnabled(false)
        xAxis.textColor = DIM
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        axisLeft.textColor = DIM
        axisRight.isEnabled = false
        setNoDataText("还没有记录")
        setNoDataTextColor(DIM)
    }

    fun applyBar(
        chart: BarChart,
        seriesByMetric: Map<String, List<Pair<Int, Float>>>,
        labels: Map<Int, String>
    ) {
        val usable = seriesByMetric.filterValues { it.isNotEmpty() }
        if (usable.isEmpty()) { chart.clear(); chart.invalidate(); return }
        // 横轴=读数下标；同一读数内各指标左右错开一点，不用 groupBars（单指标时它会抛异常）
        val names = usable.keys.toList()
        val palette = intArrayOf(CYAN, 0xFFFFC857.toInt(), 0xFFFF6B9A.toInt(), 0xFF7BE0C4.toInt())
        // 轴上限按“最大读线下标”算：勾选的指标可能从第 2 条读数才开始，不能用系列长度
        val maxIndex = usable.values.maxOf { s -> s.maxOf { it.first } }
        val count = maxIndex + 1
        val step = 0.72f / names.size.coerceAtLeast(1)
        val sets = names.mapIndexed { idx, name ->
            val offset = (idx - (names.size - 1) / 2f) * step
            val entries = usable[name]!!.map { (i, v) -> BarEntry(i + offset, v) }
            BarDataSet(entries, name).apply {
                color = palette[idx % palette.size]
                setDrawValues(false)
            }
        }
        chart.data = BarData(sets.map { it as com.github.mikephil.charting.interfaces.datasets.IBarDataSet }).apply {
            barWidth = step * 0.8f
        }
        // y 轴必须从 0（或更低的负值）起：否则等于数据最小值的柱子高度会是 0，看起来像缺数据
        val yMin = usable.values.minOfOrNull { s -> s.minOfOrNull { it.second } ?: 0f } ?: 0f
        chart.axisLeft.axisMinimum = minOf(0f, yMin)
        chart.axisLeft.resetAxisMaximum()
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String =
                labels[Math.round(value).toInt()] ?: ""
        }
        chart.xAxis.axisMinimum = -0.6f
        chart.xAxis.axisMaximum = maxIndex + 0.6f
        chart.xAxis.labelCount = minOf(count, 6)
        chart.xAxis.granularity = 1f
        chart.xAxis.labelRotationAngle = 45f
        chart.xAxis.setDrawGridLines(false)
        chart.setVisibleXRangeMaximum(count.toFloat())
        chart.legend.isEnabled = true
        chart.legend.textColor = DIM
        chart.legend.textSize = 10f
        chart.invalidate()
    }

    /** 饼图：每一块是定义里的一个指标的最近一次读数 */
    fun pieChart(context: Context, heightDp: Float): PieChart = PieChart(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp))
        description.isEnabled = false
        legend.textColor = TEXT
        setEntryLabelColor(TEXT)
        setEntryLabelTextSize(10f)
        setUsePercentValues(false)
        setNoDataText("还没有记录")
        setNoDataTextColor(DIM)
    }

    fun applyPie(chart: PieChart, slices: List<Pair<String, Float>>) {
        val usable = slices.filter { it.second > 0f }
        if (usable.isEmpty()) { chart.clear(); chart.invalidate(); return }
        val entries = usable.map { PieEntry(it.second, it.first) }
        val set = PieDataSet(entries, "").apply {
            colors = listOf(CYAN, 0xFFFFC857.toInt(), 0xFFFF6B9A.toInt(), 0xFF7BE0C4.toInt())
            setDrawValues(true)
            valueTextColor = TEXT
            valueTextSize = 10f
        }
        chart.data = PieData(set).apply {
            setValueTextSize(10f)
            setValueFormatter(object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    String.format(java.util.Locale.CHINA, "%.0f%%", value)
            })
        }
        chart.setUsePercentValues(true)
        chart.setDrawEntryLabels(true)
        chart.invalidate()
    }
}
