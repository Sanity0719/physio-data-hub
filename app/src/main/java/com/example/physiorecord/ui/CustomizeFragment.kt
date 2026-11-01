package com.example.physiorecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.Formula
import com.example.physiorecord.data.PhysioRepository

/**
 * 可视化定制子页。
 *
 * 只做一件事：编辑“一条图表定义”（名称、指标集合、图型、公式试算）。
 * 展示页（[VisualizeFragment]）不放任何编辑控件；保存后回展示页立刻按新定义画。
 */
class CustomizeFragment : Fragment() {

    companion object {
        private const val ARG_ID = "defId"
        fun newInstance(defId: Long = 0L): CustomizeFragment = CustomizeFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, defId) }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var repository: PhysioRepository
    private lateinit var nameInput: EditText
    private lateinit var metricsBox: LinearLayout
    private lateinit var typeGroup: RadioGroup
    private lateinit var formulaInput: EditText
    private lateinit var formulaResult: TextView
    private lateinit var device: com.example.physiorecord.data.Device
    private var editingId = 0L
    private var latest: Map<String, Int> = emptyMap()
    private var restoreState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(requireContext())
        repository = PhysioRepository(requireContext())
        device = DeviceRegistry.byKey(prefs.currentDevice) ?: DeviceRegistry.default()
        editingId = arguments?.getLong(ARG_ID) ?: 0L
        restoreState = savedInstanceState
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 12f),
                UiKit.dp(context, 16f), UiKit.dp(context, 16f))
        }
        scroll.addView(box)

        box.addView(android.widget.Button(context).apply {
            text = "← 返回可视化"
            setOnClickListener { close() }
        })
        box.addView(UiKit.title(context, "可视化定制", 24f))
        box.addView(UiKit.hint(context,
            "改的是“一条图表定义”：名称、看哪些指标、画成什么图。保存后展示页立刻按它画。"))

        box.addView(UiKit.label(context, "名称"))
        nameInput = EditText(context).apply {
            hint = "定义名称"
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.DIM)
        }
        box.addView(nameInput)

        box.addView(UiKit.label(context, "指标（至少勾一个）"))
        metricsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val metrics = device.metrics.ifEmpty { listOf(device.primaryMetric) }
        for (m in metrics) {
            metricsBox.addView(CheckBox(context).apply {
                text = m
                tag = m
                setTextColor(UiKit.TEXT)
            })
        }
        box.addView(metricsBox)

        box.addView(UiKit.label(context, "图型"))
        typeGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("折线" to "line", "柱状" to "bar", "饼图" to "pie").forEachIndexed { idx, pair ->
            typeGroup.addView(RadioButton(context).apply {
                id = 10 + idx
                text = pair.first
                tag = pair.second
                setTextColor(UiKit.TEXT)
            })
        }
        box.addView(typeGroup)

        box.addView(android.widget.Button(context).apply {
            text = "保存"
            setOnClickListener { save() }
        })

        box.addView(UiKit.label(context, "公式（每行一条，每条只定义一个量；折线/柱状=逐条求值成一条序列；饼图=sum(…)或avg(…)聚合一块）"))
        formulaInput = EditText(context).apply {
            hint = "示例：\nheart_rate-spo2\nsum(spo2)\navg(heart_rate)"
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.DIM)
            setSingleLine(false)
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        box.addView(formulaInput)
        box.addView(android.widget.Button(context).apply {
            text = "试算"
            setOnClickListener { runFormula() }
        })
        formulaResult = UiKit.hint(context, "公式结果：—")
        box.addView(formulaResult)

        box.addView(android.widget.Button(context).apply {
            text = "删除这条定义"
            setTextColor(0xFFFF6B6B.toInt())
            visibility = if (editingId > 0) View.VISIBLE else View.GONE
            setOnClickListener { confirmDelete() }
        })

        load()
        return scroll
    }

    private fun load() {
        Bg.io.execute {
            val def = if (editingId > 0) repository.chartDefById(editingId) else null
            val count = repository.chartDefs(device.id).size
            val recent = repository.recentReadings(device.id, 1).firstOrNull()?.values ?: emptyMap()
            Bg.onMain {
                latest = recent
                if (def != null) {
                    nameInput.setText(def.name)
                    formulaInput.setText(def.formulas.joinToString("\n"))
                    for (i in 0 until metricsBox.childCount) {
                        val cb = metricsBox.getChildAt(i) as CheckBox
                        cb.isChecked = def.metricKeys.contains(cb.tag as String)
                    }
                    val idx = when (def.chartType) {
                        "bar" -> 1
                        "pie" -> 2
                        else -> 0
                    }
                    (typeGroup.getChildAt(idx) as RadioButton).isChecked = true
                } else {
                    val maxIndex = count.let { n ->
                        // 默认名按“图表 N”里的最大 N 递增，避免删掉中间一条后重名
                        repository.chartDefs(device.id).mapNotNull {
                            Regex("^图表 (\\d+)$").find(it.name)?.groupValues?.get(1)?.toIntOrNull()
                        }.maxOrNull() ?: n
                    }
                    nameInput.setText("图表 ${maxIndex + 1}")
                    (typeGroup.getChildAt(0) as RadioButton).isChecked = true
                    // 新建时默认勾上这台设备现有的全部指标；用户可以取消
                    for (i in 0 until metricsBox.childCount) {
                        (metricsBox.getChildAt(i) as CheckBox).isChecked = true
                    }
                }
                restoreState?.let {
                    applySavedState(it)
                    restoreState = null
                }
            }
        }
    }

    /** 旋转/重建后把没保存的输入还回去 */
    private fun applySavedState(state: Bundle) {
        state.getString("name")?.let { nameInput.setText(it) }
        state.getString("formula")?.let { formulaInput.setText(it) }
        state.getStringArrayList("metrics")?.let { keys ->
            for (i in 0 until metricsBox.childCount) {
                val cb = metricsBox.getChildAt(i) as CheckBox
                cb.isChecked = keys.contains(cb.tag as String)
            }
        }
        val typeId = state.getInt("typeId", 0)
        if (typeId != 0) {
            (typeGroup.findViewById<RadioButton>(typeId))?.isChecked = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!this::nameInput.isInitialized) return
        outState.putString("name", nameInput.text.toString())
        outState.putString("formula", formulaInput.text.toString())
        val checked = ArrayList<String>()
        for (i in 0 until metricsBox.childCount) {
            val cb = metricsBox.getChildAt(i) as CheckBox
            if (cb.isChecked) checked.add(cb.tag as String)
        }
        outState.putStringArrayList("metrics", checked)
        outState.putInt("typeId", typeGroup.checkedRadioButtonId)
    }

    private fun selectedChartType(): String {
        return when (typeGroup.checkedRadioButtonId) {
            11 -> "bar"
            12 -> "pie"
            else -> "line"
        }
    }

    private fun readFormulas(): List<String> =
        formulaInput.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun runFormula() {
        val lines = readFormulas()
        if (lines.isEmpty()) {
            formulaResult.text = "还没有公式"
            return
        }
        val first = lines.first()
        val type = selectedChartType()
        if (type == "pie") {
            formulaResult.text = "按窗口聚合试算中…"
            Bg.io.execute {
                val readings = repository.recentReadings(device.id, 30).map { it.values }
                val r = com.example.physiorecord.data.Formula.aggregate(first, readings)
                Bg.onMain {
                    formulaResult.text = if (r == null) {
                        "饼图公式要写成 sum(…) 或 avg(…)"
                    } else {
                        "窗口(最近30条)试算：" + java.lang.String.format(java.util.Locale.CHINA, "%.1f", r)
                    }
                }
            }
            return
        }
        val r = Formula.eval(first, latest)
        formulaResult.text = if (r == null) {
            "公式算不了（检查指标名或括号）"
        } else {
            "最近一条试算：" + java.lang.String.format(java.util.Locale.CHINA, "%.1f", r)
        }
    }

    private fun save() {
        val name = nameInput.text.toString().trim().ifBlank { "图表" }
        val keys = ArrayList<String>()
        for (i in 0 until metricsBox.childCount) {
            val cb = metricsBox.getChildAt(i) as CheckBox
            if (cb.isChecked) keys.add(cb.tag as String)
        }
        if (keys.isEmpty()) {
            formulaResult.text = "至少勾一个指标"
            return
        }
        val type = selectedChartType()
        val formulas = readFormulas()
        if (type == "pie") {
            val bad = formulas.firstOrNull {
                !Regex("^(sum|avg)\\s*\\(.*\\)$", RegexOption.IGNORE_CASE).matches(it)
            }
            if (bad != null) {
                formulaResult.text = "饼图公式要写成 sum(…) 或 avg(…)：$bad"
                return
            }
        }
        formulaResult.text = "保存中…"
        Bg.io.execute {
            try {
                val id = if (editingId > 0) {
                    repository.updateChartDef(editingId, name, keys, type, formulas)
                    editingId
                } else {
                    repository.addChartDef(device.id, name, keys, type, formulas)
                }
                prefs.setDefId(device.id, id)
                Bg.onMain { close() }
            } catch (e: Exception) {
                Bg.onMain { formulaResult.text = "保存失败：" + (e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun close() {
        (activity as? com.example.physiorecord.MainActivity)?.closePage()
            ?: parentFragmentManager.popBackStack()
    }

    private fun confirmDelete() {
        if (editingId <= 0) return
        AlertDialog.Builder(requireContext())
            .setTitle("删除这条定义")
            .setMessage("只删定义，不动采集到的记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删") { _, _ ->
                Bg.io.execute {
                    repository.deleteChartDef(editingId)
                    if (prefs.defId(device.id) == editingId) prefs.setDefId(device.id, 0L)
                    Bg.onMain { close() }
                }
            }
            .show()
    }
}
