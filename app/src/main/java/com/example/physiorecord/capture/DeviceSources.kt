package com.example.physiorecord.capture

import com.example.physiorecord.data.ChannelKind
import com.example.physiorecord.data.Device
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.ui.Prefs

/**
 * 按设备登记表自动造数据源：加一台设备只需要在 DeviceRegistry 加一行，
 * 手动命令与周期采集都从这里拿到它对应的源。
 */
object DeviceSources {

    fun forDevice(device: Device, prefs: Prefs): CaptureSource? =
        when (device.channels.firstOrNull()?.kind) {
            ChannelKind.WIFI_TCP -> TcpSensorSource(prefs, device.id, device.alias)
            ChannelKind.CLASSIC_BLUETOOTH -> SppSensorSource(device.id, device.alias)
            ChannelKind.BLE -> BleSensorSource(device.id, device.alias)
            ChannelKind.USB_SERIAL -> UsbSensorSource(device.id, device.alias)
            else -> null
        }

    /** 把登记表里所有“有通道可读”的设备都注册进 DeviceHub（内置源由调用方自行注册）。 */
    fun registerAll(prefs: Prefs) {
        for (device in DeviceRegistry.all()) {
            forDevice(device, prefs)?.let { DeviceHub.register(it) }
        }
    }
}
