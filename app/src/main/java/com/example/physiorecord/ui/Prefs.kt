package com.example.physiorecord.ui

import android.content.Context
import com.example.physiorecord.data.DeviceRegistry

/**
 * 界面之间共享的少量状态（放本机偏好里，进程被杀也在）。
 * 只存"当前看哪台设备、折线取几个点、对外发到哪"，不存业务数据。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("ui_state", Context.MODE_PRIVATE)

    /** 当前操作的设备编号；默认取登记表里的第一台 */
    var currentDevice: String
        get() = sp.getString("device", DeviceRegistry.default().id) ?: DeviceRegistry.default().id
        set(value) = sp.edit().putString("device", value).apply()

    /** 折线取最近多少个点 */
    var chartPoints: Int
        get() = sp.getInt("points", 60)
        set(value) = sp.edit().putInt("points", value).apply()

    /** 主动外发的落点：模拟器先用 adb reverse 再填 127.0.0.1；真机填 PC 的局域网 IP */
    var sendHost: String
        get() = sp.getString("host", "127.0.0.1") ?: "127.0.0.1"
        set(value) = sp.edit().putString("host", value).apply()

    /** 经典蓝牙（SPP）设备的 MAC */
    var sppMac: String
        get() = sp.getString("spp_mac", "00:00:00:00:00:00") ?: "00:00:00:00:00:00"
        set(value) = sp.edit().putString("spp_mac", value).apply()

    /** 低功耗蓝牙（BLE）设备的 MAC */
    var bleMac: String
        get() = sp.getString("ble_mac", "00:00:00:00:00:00") ?: "00:00:00:00:00:00"
        set(value) = sp.edit().putString("ble_mac", value).apply()

    var sendPort: Int
        get() = sp.getInt("port", 8899)
        set(value) = sp.edit().putInt("port", value).apply()

    /** 每台设备当前选中的图表定义 id；0 = 还没选 */
    fun defId(device: String): Long = sp.getLong("def_$device", 0L)
    fun setDefId(device: String, id: Long) = sp.edit().putLong("def_$device", id).apply()
}
