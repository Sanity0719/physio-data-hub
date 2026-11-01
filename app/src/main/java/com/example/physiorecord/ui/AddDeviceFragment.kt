package com.example.physiorecord.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.physiorecord.capture.DeviceHub
import com.example.physiorecord.capture.DeviceSources
import com.example.physiorecord.data.Channel
import com.example.physiorecord.data.ChannelAddress
import com.example.physiorecord.data.ChannelKind
import com.example.physiorecord.data.Device
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.PhysioRepository

/**
 * 界面添加设备：填名称/型号/通道类型/地址 → 落库 → 立刻能选、能连、能读数。
 * 不需要改代码；重启后启动时从库里读回来。
 */
class AddDeviceFragment : Fragment() {

    private lateinit var repository: PhysioRepository
    private lateinit var prefs: Prefs
    private lateinit var aliasInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var metricInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var macInput: EditText
    private lateinit var kindGroup: RadioGroup
    private lateinit var status: TextView

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
            text = "← 返回设备列表"
            setOnClickListener { (activity as? com.example.physiorecord.MainActivity)?.closePage() }
        })
        box.addView(UiKit.title(context, "添加设备", 24f))
        box.addView(UiKit.hint(context, "填完保存即可出现在设备列表里；以后不用改代码。"))

        box.addView(UiKit.label(context, "名称"))
        aliasInput = edit(context, "如：卧室血氧仪", InputType.TYPE_CLASS_TEXT)
        box.addView(aliasInput)

        box.addView(UiKit.label(context, "型号（可留空）"))
        modelInput = edit(context, "如：CMS50D", InputType.TYPE_CLASS_TEXT)
        box.addView(modelInput)

        box.addView(UiKit.label(context, "指标名（默认 value）"))
        metricInput = edit(context, "value", InputType.TYPE_CLASS_TEXT)
        box.addView(metricInput)

        box.addView(UiKit.label(context, "通道类型"))
        kindGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        for ((i, text) in listOf("网络 TCP", "蓝牙低功耗", "蓝牙串口", "USB 串口").withIndex()) {
            kindGroup.addView(RadioButton(context).apply { id = i + 1; this.text = text })
        }
        (kindGroup.getChildAt(0) as RadioButton).isChecked = true
        box.addView(kindGroup)

        box.addView(UiKit.label(context, "TCP 地址 / 端口（选 TCP 时填）"))
        hostInput = edit(context, "127.0.0.1", InputType.TYPE_CLASS_TEXT)
        box.addView(hostInput)
        portInput = edit(context, "8899", InputType.TYPE_CLASS_NUMBER)
        box.addView(portInput)

        box.addView(UiKit.label(context, "蓝牙 MAC（选两种蓝牙时填）"))
        macInput = edit(context, "00:00:00:00:00:00", InputType.TYPE_CLASS_TEXT)
        box.addView(macInput)

        box.addView(android.widget.Button(context).apply {
            text = "保存设备"
            setOnClickListener { save() }
        })
        status = UiKit.text(context, "", 12f, UiKit.DIM)
        box.addView(status)

        return scroll
    }

    private fun edit(context: android.content.Context, hint: String, type: Int): EditText =
        EditText(context).apply {
            this.hint = hint
            inputType = type
            setTextColor(UiKit.TEXT)
            setHintTextColor(UiKit.DIM)
        }

    private fun save() {
        val alias = aliasInput.text.toString().trim()
        if (alias.isEmpty()) {
            status.text = "名称不能为空"
            return
        }
        val metric = metricInput.text.toString().trim().ifBlank { "value" }
        val kind = when (kindGroup.checkedRadioButtonId) {
            2 -> ChannelKind.BLE
            3 -> ChannelKind.CLASSIC_BLUETOOTH
            4 -> ChannelKind.USB_SERIAL
            else -> ChannelKind.WIFI_TCP
        }
        val address = when (kind) {
            ChannelKind.WIFI_TCP -> {
                val host = hostInput.text.toString().trim().ifBlank { "127.0.0.1" }
                val port = portInput.text.toString().trim().toIntOrNull() ?: 8899
                if (port !in 1..65535) {
                    status.text = "端口要 1–65535"
                    return
                }
                ChannelAddress.Network(host, port)
            }
            ChannelKind.CLASSIC_BLUETOOTH, ChannelKind.BLE -> {
                val mac = macInput.text.toString().trim().uppercase()
                if (!Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac)) {
                    status.text = "MAC 格式要像 11:22:33:44:55:66"
                    return
                }
                ChannelAddress.Bluetooth(mac)
            }
            else -> ChannelAddress.Usb(0, 0)
        }
        val key = "custom_" + System.currentTimeMillis()
        val device = Device(
            id = key,
            alias = alias,
            model = modelInput.text.toString().trim().ifBlank { "自建设备" },
            primaryMetric = metric,
            metrics = listOf(metric),
            channels = listOf(Channel(id = "$key-ch", kind = kind, address = address, note = "界面自建"))
        )
        status.text = "保存中…"
        Bg.io.execute {
            val ok = repository.insertCustomDevice(device)
            Bg.onMain {
                if (!ok) {
                    status.text = "保存失败（名称或编号重复）"
                    return@onMain
                }
                DeviceRegistry.addCustom(device)
                DeviceSources.forDevice(device, prefs)?.let { DeviceHub.register(it) }
                // 切到新设备，方便立刻读一笔
                prefs.currentDevice = device.id
                status.text = "已添加：$alias；已切到这台设备"
                (activity as? com.example.physiorecord.MainActivity)?.closePage()
            }
        }
    }
}
