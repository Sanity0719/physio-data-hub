package com.example.physiorecord.capture

/**
 * 数据源：应用从"哪儿"拿数据。
 *
 * 契约三样：
 *   1. key   —— 设备编号（不会变，数据按它落到对应表）；
 *   2. name  —— 显示名（给人看，可改，不影响数据归属）；
 *   3. readOnce() —— 读一次，返回一个原始读数。
 *
 * 接入蓝牙、串口、网络时，只需再写一个实现类：
 * 采集流程和界面一行都不用改。
 */
interface CaptureSource {

    /** 设备编号：登记表里的 key，也是采集表归属的依据 */
    val key: String

    /** 给人看的设备名 */
    val name: String

    /** 读一次，返回一个原始读数（单指标设备的默认实现走这里） */
    fun readOnce(): Int

    /**
     * 读一次，返回这次读数里的多个指标。
     * 默认实现：把 readOnce() 的结果记到该设备的主指标上。
     */
    fun readMetrics(): Map<String, Int> {
        val primary = com.example.physiorecord.data.DeviceRegistry.byKey(key)?.primaryMetric ?: "value"
        return mapOf(primary to readOnce())
    }

    /**
     * 有长连接的通道（如 TCP）收到一行回话时，用它解析成指标；
     * 返回 null 表示这条回话看不懂，调用方应当报错而不是编数据。
     */
    fun parseReply(line: String): Map<String, Int>? = null
}
