package com.example.physiorecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.PhysioRepository

/**
 * 设备列表子页：一屏看清登记了几台、各自什么型号/通道、有多少笔；
 * 点一台就把它设为当前设备并回设备页（不在这里做“连接”，连接仍在设备页）。
 */
class DeviceListFragment : Fragment() {

    private lateinit var repository: PhysioRepository
    private lateinit var prefs: Prefs
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = PhysioRepository(requireContext())
        prefs = Prefs(requireContext())
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
            text = "← 返回设备"
            setOnClickListener { (activity as? com.example.physiorecord.MainActivity)?.closePage() }
        })
        box.addView(UiKit.title(context, "设备列表", 24f))
        box.addView(UiKit.hint(context, "登记表里的设备；点一台设为当前设备。数据按设备分表，切设备不会串数据。"))
        box.addView(android.widget.Button(context).apply {
            text = "＋ 添加设备"
            setOnClickListener {
                (activity as? com.example.physiorecord.MainActivity)?.openPage(AddDeviceFragment())
            }
        })

        rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(rows)
        return scroll
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val devices = DeviceRegistry.all()
        val current = prefs.currentDevice
        Bg.io.execute {
            val counts = devices.associate { it.id to repository.countAll(it.id) }
            Bg.onMain {
                rows.removeAllViews()
                for (device in devices) {
                    val prefix = if (device.id == current) "✓ " else ""
                    val text = prefix + device.alias + " · " + device.model + "\n" +
                        "通道 " + device.channelSummary() + " · 共 " + (counts[device.id] ?: 0) + " 笔"
                    rows.addView(android.widget.Button(requireContext()).apply {
                        this.text = text
                        setOnClickListener {
                            prefs.currentDevice = device.id
                            (activity as? com.example.physiorecord.MainActivity)?.closePage()
                        }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = UiKit.dp(requireContext(), 6f) })
                }
            }
        }
    }
}
