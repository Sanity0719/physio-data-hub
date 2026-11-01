package com.example.physiorecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.physiorecord.capture.CommandResult
import com.example.physiorecord.capture.DeviceCommand
import com.example.physiorecord.capture.DeviceHub
import com.example.physiorecord.capture.LinkState
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.PhysioRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程设备：列出所有走网络（TCP）的设备，显示它最近一次在线/有数据的时刻，
 * 并提供“查看数据”与“连接/断开”。这是对远程主机设备数据的管理入口。
 */
class RemoteDevicesFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var repository: PhysioRepository
    private lateinit var box: LinearLayout
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(requireContext())
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
            text = "← 返回数据管理"
            setOnClickListener { (activity as? com.example.physiorecord.MainActivity)?.closePage() }
        })
        box.addView(UiKit.title(context, "远程设备", 24f))
        box.addView(UiKit.hint(context, "这里只列网络（TCP）设备：最近一次在线、最近一条数据、数据条数，并可直接进它的数据列表按条删除。"))

        return scroll
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val context = requireContext()
        Bg.io.execute {
            val items = DeviceRegistry.all()
                .filter { d -> d.channels.any { it.kind == com.example.physiorecord.data.ChannelKind.WIFI_TCP } }
                .map { d ->
                    val last = repository.recent(d.id, 1).firstOrNull()
                    val total = repository.countAll(d.id)
                    Triple(d, last, total)
                }
            Bg.onMain {
                // 保留返回按钮与标题，清掉旧卡片
                while (box.childCount > 3) box.removeViewAt(3)
                for ((device, last, total) in items) {
                    val status = DeviceHub.linkStatus(device.id)
                    val stateText = when (status.state) {
                        LinkState.CONNECTED -> "已连接"
                        LinkState.CONNECTING -> "连接中…"
                        LinkState.RETRYING -> "重连中（第${status.attempt}次）"
                        LinkState.FAILED -> "连接失败"
                        else -> "未连接"
                    }
                    val online = if (last == null) "还没有数据" else "最近数据：${fmt.format(Date(last.at))}"
                    val card = UiKit.card(context)
                    val body = UiKit.body(context)
                    body.addView(UiKit.title(context, device.alias, 18f))
                    body.addView(UiKit.hint(context, device.model))
                    body.addView(UiKit.hint(context, "通道：${device.channelSummary()}"))
                    body.addView(UiKit.text(context, "连接：$stateText · 共 $total 笔\n$online", 12f, UiKit.DIM))
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(UiKit.tile(context, "查看数据", "按条删改", {
                        (activity as? com.example.physiorecord.MainActivity)
                            ?.openPage(RemoteDataFragment.newInstance(device.id))
                    }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .also { it.rightMargin = UiKit.dp(context, 8f) })
                    val connected = status.state == LinkState.CONNECTED || status.state == LinkState.RETRYING
                    row.addView(UiKit.tile(
                        context,
                        if (connected) "断开" else "连接",
                        if (connected) "关掉这条连接" else "连远程设备",
                        { toggle(device.id) }
                    ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    body.addView(row)
                    card.addView(body)
                    box.addView(card)
                }
            }
        }
    }

    private fun toggle(key: String) {
        val connected = DeviceHub.linkStatus(key).state.let {
            it == LinkState.CONNECTED || it == LinkState.RETRYING
        }
        DeviceHub.submit(key, if (connected) DeviceCommand.DISCONNECT else DeviceCommand.CONNECT) {
            Bg.onMain { render() }
        }
    }
}
