package com.example.physiorecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.PhysioRepository
import com.github.mikephil.charting.charts.LineChart

/**
 * 可视化展示页（只读）。
 *
 * 页面上只放：当前设备名、两个入口（切换定义 / 定制）、一张图、一行摘要。
 * 定义的新建、编辑、删除、公式试算都在 [CustomizeFragment] 子页里做。
 */
class VisualizeFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var repository: PhysioRepository
    private lateinit var deviceName: TextView
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var chart: LineChart
    private lateinit var barChart: com.github.mikephil.charting.charts.BarChart
    private lateinit var pieChart: com.github.mikephil.charting.charts.PieChart

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(requireContext())
        repository = PhysioRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val root = ScrollView(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 12f),
                UiKit.dp(context, 16f), UiKit.dp(context, 16f))
        }
        root.addView(box)

        // 顶部：设备名 + 两个入口（展示页不放任何编辑控件）
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        deviceName = UiKit.title(context, "—", 22f)
        header.addView(deviceName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(android.widget.Button(context).apply {
            text = "切换定义"
            setOnClickListener { switchDefinition() }
        })
        header.addView(android.widget.Button(context).apply {
            text = "定制"
            setOnClickListener { openCustomize(0L) }
        })
        box.addView(header)

        // 图区：三张图叠放，按当前定义只显示一张
        chart = UiKit.lineChart(context, 260f)
        barChart = UiKit.barChart(context, 260f)
        pieChart = UiKit.pieChart(context, 260f)
        barChart.visibility = View.GONE
        pieChart.visibility = View.GONE
        val chartBox = android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 260f))
            addView(chart)
            addView(barChart)
            addView(pieChart)
        }
        box.addView(chartBox)

        empty = UiKit.hint(context, "还没有图表定义")
        empty.visibility = View.GONE
        box.addView(empty)

        summary = UiKit.text(context, "—", 12f, UiKit.DIM)
        box.addView(summary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val key = prefs.currentDevice
        val defId = prefs.defId(key)
        Bg.io.execute {
            val device = DeviceRegistry.byKey(key)
            val name = device?.alias ?: key
            val def = if (defId > 0) repository.chartDefById(defId) else null
            val defs = repository.chartDefs(key)
            val total = repository.countAll(key)
            val points = prefs.chartPoints
            val recent = repository.recentReadings(key, points)
            // 每条读数一个下标；各指标按同一读数下标对齐，缺的指标跳过（不用别的读数补）
            val readings = recent.asReversed()
            val series = LinkedHashMap<String, MutableList<Pair<Int, Float>>>()
            val pieSlices = LinkedHashMap<String, Float>()
            if (def != null) {
                readings.forEachIndexed { i, r ->
                    for (m in def.metricKeys) {
                        r.values[m]?.let { series.getOrPut(m) { ArrayList() }.add(i to it.toFloat()) }
                    }
                }
                if (def.formulas.isNotEmpty()) {
                    if (def.chartType == "pie") {
                        // 饼图：每条聚合公式（sum/avg）只算一块
                        for (f in def.formulas) {
                            com.example.physiorecord.data.Formula
                                .aggregate(f, readings.map { it.values })
                                ?.let { pieSlices[f] = it.toFloat() }
                        }
                    } else {
                        // 折线/柱状：每条公式逐条读数求值，各成一条序列
                        for (f in def.formulas) {
                            readings.forEachIndexed { i, r ->
                                com.example.physiorecord.data.Formula.eval(f, r.values)?.let {
                                    series.getOrPut("公式 $f") { ArrayList() }.add(i to it.toFloat())
                                }
                            }
                        }
                    }
                }
            }
            val xLabels = readings.mapIndexed { i, r ->
                i to java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(r.at))
            }.toMap()
            val totals = series.filterKeys { !it.startsWith("公式 ") }
                .mapValues { e -> e.value.sumOf { it.second.toDouble() }.toFloat() }
            android.util.Log.d(
                "Chart",
                "type=${def?.chartType} points=${readings.size} formulas=${def?.formulas?.size} " +
                    "series=${series.mapValues { it.value.size }} pieSlices=${pieSlices.size} totals=${totals.size}"
            )
            Bg.onMain {
                deviceName.text = name
                if (def == null) {
                    chart.visibility = View.GONE
                    barChart.visibility = View.GONE
                    pieChart.visibility = View.GONE
                    empty.visibility = View.VISIBLE
                    empty.text = if (defs.isEmpty()) {
                        "还没有图表定义。点右上“定制”新建一条。"
                    } else {
                        "还没选定义。点右上“切换定义”选一条，或“定制”新建。"
                    }
                    summary.text = "$name · 共 $total 笔 · " + if (defs.isEmpty()) "没有定义" else "未选定义"
                } else {
                    empty.visibility = View.GONE
                    when (def.chartType) {
                        "bar" -> {
                            chart.visibility = View.GONE
                            pieChart.visibility = View.GONE
                            barChart.visibility = View.VISIBLE
                            UiKit.applyBar(barChart, series, xLabels)
                        }
                        "pie" -> {
                            chart.visibility = View.GONE
                            barChart.visibility = View.GONE
                            pieChart.visibility = View.VISIBLE
                            val slices = if (pieSlices.isNotEmpty()) pieSlices else totals
                            UiKit.applyPie(pieChart, slices.entries.map { it.key to it.value })
                        }
                        else -> {
                            barChart.visibility = View.GONE
                            pieChart.visibility = View.GONE
                            chart.visibility = View.VISIBLE
                            if (series.isEmpty()) {
                                chart.clear()
                                chart.invalidate()
                            } else {
                                UiKit.applyMultiSeries(chart, series)
                            }
                        }
                    }
                    val metricPart = def.metricKeys.joinToString(" / ")
                    val shape = when (def.chartType) {
                        "bar" -> "窗口内 ${readings.size} 点的柱状（按读数排列，有值的指标画出柱）"
                        "pie" -> "窗口内 ${readings.size} 点的占比（跨量纲，仅看比例）"
                        else -> "窗口内 ${readings.size} 点的折线"
                    }
                    val n = def.formulas.size
                    val formulaPart = when {
                        n == 0 -> ""
                        def.chartType == "pie" -> " · 饼图公式 $n 条（sum/avg 聚合，每条一块）"
                        else -> " · $n 条公式（每条逐点求值成一条序列）"
                    }
                    summary.text = "${def.name} · $metricPart · $shape · 设备共 $total 笔$formulaPart"
                }
            }
        }
    }

    private fun switchDefinition() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val defs = repository.chartDefs(key)
            val current = prefs.defId(key)
            Bg.onMain {
                if (defs.isEmpty()) {
                    openCustomize(0L)
                    return@onMain
                }
                val labels = defs.map { d ->
                    val base = (if (d.id == current) "✓ " else "") + d.name + " · " + d.chartType +
                        " · " + d.metricKeys.joinToString("/")
                    if (d.formulas.isNotEmpty()) "$base · 公式×${d.formulas.size}" else base
                }.toTypedArray()
                val builder = AlertDialog.Builder(requireContext())
                    .setTitle("切换图表定义")
                    .setItems(labels) { _, i ->
                        prefs.setDefId(key, defs[i].id)
                        refresh()
                    }
                    .setNegativeButton("关闭", null)
                if (current > 0) {
                    builder.setPositiveButton("编辑当前") { _, _ -> openCustomize(current) }
                } else {
                    builder.setPositiveButton("新建") { _, _ -> openCustomize(0L) }
                }
                builder.show()
            }
        }
    }

    private fun openCustomize(defId: Long) {
        (activity as? com.example.physiorecord.MainActivity)?.openPage(CustomizeFragment.newInstance(defId))
    }
}
