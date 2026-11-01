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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 某台设备的数据列表：分页看全部读数，按“序号”（reading_id）改/删特定一条。
 */
class DataListFragment : Fragment() {

    private lateinit var repository: PhysioRepository
    private lateinit var box: LinearLayout
    private lateinit var status: TextView
    private lateinit var key: String
    private var offset = 0
    private val pageSize = 50
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = requireArguments().getString(ARG_KEY) ?: DeviceRegistry.default().id
        repository = PhysioRepository(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val scroll = ScrollView(context)
        box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 12f),
                UiKit.dp(context, 16f), UiKit.dp(context, 16f))
        }
        scroll.addView(box)

        box.addView(android.widget.Button(context).apply {
            text = "← 返回"
            setOnClickListener { (activity as? com.example.physiorecord.MainActivity)?.closePage() }
        })
        box.addView(UiKit.title(context, DeviceRegistry.byKey(key)?.alias ?: key, 24f))
        box.addView(UiKit.hint(context, "按序号从新到旧；点某一条可以改值或删这条。"))
        status = UiKit.text(context, "", 12f, UiKit.DIM)
        box.addView(status)

        val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(android.widget.Button(context).apply {
            text = "上一页"
            setOnClickListener { if (offset - pageSize >= 0) { offset -= pageSize; render() } }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(android.widget.Button(context).apply {
            text = "下一页"
            setOnClickListener { offset += pageSize; render() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(nav)

        return scroll
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val context = requireContext()
        Bg.io.execute {
            val total = repository.countAll(key)
            val rows = repository.pageReadings(key, pageSize, offset)
            Bg.onMain {
                while (box.childCount > 5) box.removeViewAt(5)
                val from = if (rows.isEmpty()) 0 else offset + 1
                val to = offset + rows.size
                status.text = "第 $from–$to 条 / 共 $total 条"
                if (rows.isEmpty()) {
                    box.addView(UiKit.hint(context, "没有更多数据"))
                    return@onMain
                }
                for (r in rows) {
                    box.addView(android.widget.Button(context).apply {
                        text = "序号 ${r.readingId} · ${fmt.format(Date(r.at))} · ${r.displayName}"
                        isAllCaps = false
                        setOnClickListener { openRow(r.readingId, r.at, r.values) }
                    })
                }
            }
        }
    }

    private fun openRow(readingId: Long, at: Long, values: Map<String, Int>) {
        val context = requireContext()
        val shown = values.entries.joinToString(" / ") { "${it.key} ${it.value}" }
        AlertDialog.Builder(context)
            .setTitle("序号 $readingId · ${fmt.format(Date(at))}")
            .setMessage(shown)
            .setPositiveButton("改值") { _, _ -> askValue(readingId, values) }
            .setNeutralButton("删这条") { _, _ ->
                AlertDialog.Builder(context)
                    .setTitle("删序号 $readingId？")
                    .setMessage("这次读数（$shown）整条删除，不能恢复。")
                    .setPositiveButton("删") { _, _ ->
                        Bg.io.execute {
                            repository.deleteReading(key, readingId)
                            Bg.onMain { render() }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun askValue(readingId: Long, values: Map<String, Int>) {
        val context = requireContext()
        val metric = values.keys.firstOrNull() ?: return
        val input = android.widget.EditText(context).apply {
            setText(values[metric].toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(context)
            .setTitle("改 $metric 的值")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                Bg.io.execute {
                    repository.updateReadingValue(key, readingId, metric, v)
                    Bg.onMain { render() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val ARG_KEY = "device_key"

        fun newInstance(deviceKey: String): DataListFragment = DataListFragment().apply {
            arguments = Bundle().apply { putString(ARG_KEY, deviceKey) }
        }
    }
}
