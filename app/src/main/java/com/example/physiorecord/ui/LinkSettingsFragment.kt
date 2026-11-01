package com.example.physiorecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.physiorecord.capture.CommandResult
import com.example.physiorecord.capture.DeviceCommand
import com.example.physiorecord.capture.DeviceHub
import com.example.physiorecord.data.ChannelKind
import com.example.physiorecord.data.DeviceRegistry

/**
 * 连接设置子页：按当前设备的通道，改它自己的连接参数。
 *
 * - WiFi/TCP 设备：地址 + 端口；
 * - 经典蓝牙（SPP）设备：蓝牙 MAC。
 *
 * 保存后重建会话，旧连接被关掉；下一次连接用新参数。
 */
class LinkSettingsFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var result: TextView
    private var hostInput: EditText? = null
    private var portInput: EditText? = null
    private var macInput: EditText? = null

    private val key: String get() = prefs.currentDevice
    private val device get() = DeviceRegistry.byKey(key)
    private val isUsb: Boolean
        get() = device?.channels?.firstOrNull()?.kind == ChannelKind.USB_SERIAL

    private val isBt: Boolean
        get() = device?.channels?.firstOrNull()?.kind.let {
            it == ChannelKind.CLASSIC_BLUETOOTH || it == ChannelKind.BLE
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        box.addView(UiKit.title(context, "连接设置", 24f))
        box.addView(UiKit.hint(context, "只影响当前设备（" + (device?.alias ?: key) + "）的通道参数。"))

        if (isBt) {
            box.addView(UiKit.label(context, "蓝牙 MAC"))
            macInput = EditText(context).apply {
                setText(if (device?.channels?.firstOrNull()?.kind == ChannelKind.BLE) prefs.bleMac else prefs.sppMac)
                hint = "如 11:22:33:44:55:66（先配对）"
                setTextColor(UiKit.TEXT)
                setHintTextColor(UiKit.DIM)
            }
            box.addView(macInput)
        } else if (isUsb) {
            box.addView(UiKit.hint(context, "USB 串口设备不需要地址/端口。OTG 接上后回设备页点“连接”，系统会弹授权框。"))
        } else {
            box.addView(UiKit.label(context, "地址"))
            hostInput = EditText(context).apply {
                setText(prefs.sendHost)
                setTextColor(UiKit.TEXT)
                setHintTextColor(UiKit.DIM)
            }
            box.addView(hostInput)

            box.addView(UiKit.label(context, "端口"))
            portInput = EditText(context).apply {
                setText(prefs.sendPort.toString())
                setTextColor(UiKit.TEXT)
                setHintTextColor(UiKit.DIM)
            }
            box.addView(portInput)
        }

        if (isUsb) {
            box.addView(android.widget.Button(context).apply {
                text = "连接（USB）"
                setOnClickListener { connectUsb() }
            })
        } else {
            box.addView(android.widget.Button(context).apply {
                text = "保存"
                setOnClickListener { saveSettings(false) }
            })
            box.addView(android.widget.Button(context).apply {
                text = "保存并连接"
                setOnClickListener { saveSettings(true) }
            })
            box.addView(android.widget.Button(context).apply {
                text = "测试连接（不保存）"
                setOnClickListener { testConnection() }
            })
        }

        result = UiKit.text(context, "尚未测试", 12f, UiKit.DIM)
        box.addView(result, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        return scroll
    }

    private fun validMac(mac: String): Boolean =
        Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac)

    /** 读输入并写入偏好；返回给人看的一句参数，失败返回 null */
    private fun connectUsb() {
        result.text = "正在连接 USB…"
        DeviceHub.submit(key, DeviceCommand.CONNECT) { r ->
            Bg.onMain {
                result.text = when (r) {
                    is CommandResult.Ok -> "USB 已连接"
                    is CommandResult.Failed -> "USB 连接失败：${r.reason}"
                    is CommandResult.NoReceipt -> "USB 没有回话：${r.reason}"
                }
            }
        }
    }

    private fun readAndSave(): String? {
        if (isBt) {
            val mac = macInput?.text?.toString()?.trim().orEmpty().uppercase()
            if (mac.isEmpty()) {
                result.text = "MAC 不能为空"
                return null
            }
            if (!validMac(mac)) {
                result.text = "MAC 格式要像 11:22:33:44:55:66"
                return null
            }
            if (device?.channels?.firstOrNull()?.kind == ChannelKind.BLE) {
                prefs.bleMac = mac
                DeviceHub.configureBle(key, mac)
            } else {
                prefs.sppMac = mac
                DeviceHub.configureSpp(key, mac)
            }
            DeviceHub.resetSession(key)
            return mac
        } else {
            val host = hostInput?.text?.toString()?.trim().orEmpty()
            val port = portInput?.text?.toString()?.trim()?.toIntOrNull()
            if (host.isEmpty()) {
                result.text = "地址不能为空"
                return null
            }
            if (port == null || port !in 1..65535) {
                result.text = "端口要是 1–65535 的整数"
                return null
            }
            prefs.sendHost = host
            prefs.sendPort = port
            DeviceHub.configureTcp(key, host, port)
            DeviceHub.resetSession(key)
            return "$host:$port"
        }
    }

    private fun saveSettings(andConnect: Boolean) {
        val shown = readAndSave() ?: return
        result.text = "已保存：$shown；旧连接已关闭"
        if (andConnect) {
            result.text = "已保存，正在连接 $shown…"
            DeviceHub.submit(key, DeviceCommand.CONNECT) { r ->
                Bg.onMain {
                    result.text = when (r) {
                        is CommandResult.Ok -> "已保存并连接：$shown"
                        is CommandResult.Failed -> "已保存，但连接失败：${r.reason}"
                        is CommandResult.NoReceipt -> "已保存，但没有回话：${r.reason}"
                    }
                }
            }
        }
    }

    private fun testConnection() {
        val shown = readAndSave() ?: return
        result.text = "正在测试 $shown…"
        DeviceHub.submit(key, DeviceCommand.CONNECT) { r ->
            DeviceHub.submit(key, DeviceCommand.DISCONNECT) {
                Bg.onMain {
                    result.text = when (r) {
                        is CommandResult.Ok -> "测试通过：$shown（已断开）"
                        is CommandResult.Failed -> "测试失败：${r.reason}"
                        is CommandResult.NoReceipt -> "测试没有回话：${r.reason}"
                    }
                }
            }
        }
    }
}
