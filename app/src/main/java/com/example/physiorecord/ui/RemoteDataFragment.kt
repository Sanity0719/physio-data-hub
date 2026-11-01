package com.example.physiorecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.PhysioRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一台远程设备的数据列表：按时间倒序显示最近 20 条，点一条可删这一条。
 */
class RemoteDataFragment : Fragment() {

    private lateinit var repository: PhysioRepository
    private lateinit var box: LinearLayout
    private lateinit var key: String
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

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
            text = "← 返回远程设备"
            setOnClickListener { (activity as? com.example.physiorecord.MainActivity)?.closePage() }
        })
        val device = DeviceRegistry.byKey(key)
        box.addView(UiKit.title(context, device?.alias ?: key, 24f))
        box.addView(UiKit.hint(context, "点一条记录可以删这一条（同一时刻的全部指标一起删）。"))

        return scroll
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val context = requireContext()
        Bg.io.execute {
            val rows = repository.recent(key, 20)
            Bg.onMain {
                while (box.childCount > 3) box.removeViewAt(3)
                if (rows.isEmpty()) {
                    box.addView(UiKit.hint(context, "这台设备还没有数据"))
                    return@onMain
                }
                for (row in rows) {
                    box.addView(android.widget.Button(context).apply {
                        text = "${fmt.format(Date(row.at))} · ${row.value}"
                        isAllCaps = false
                        setOnClickListener { confirmDelete(row.at, row.value) }
                    })
                }
            }
        }
    }

    private fun confirmDelete(at: Long, value: Int) {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle("删这一条？")
            .setMessage("${fmt.format(Date(at))} 这次读数（值 $value，含同一时刻的全部指标），删了不能恢复。")
            .setPositiveButton("删") { _, _ ->
                Bg.io.execute {
                    repository.deleteAt(key, at)
                    Bg.onMain { render() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val ARG_KEY = "device_key"

        fun newInstance(deviceKey: String): RemoteDataFragment = RemoteDataFragment().apply {
            arguments = Bundle().apply { putString(ARG_KEY, deviceKey) }
        }
    }
}
