package com.example.physiorecord.capture

/**
 * 第二台演示设备：读一次要 2 秒，用来验证"慢设备只堵自己的队列"。
 *
 * 真实设备里对应的是蓝牙握手、串口等应答——慢是常态。
 */
class DemoBandSource : CaptureSource {

    override val key: String = "demo_band"

    override val name: String = "演示手环"

    override fun readOnce(): Int {
        Thread.sleep(2000)
        return (40..120).random()
    }

    /** 这台演示设备一次给三个指标，用来验证“一条读数带多个指标” */
    override fun readMetrics(): Map<String, Int> {
        Thread.sleep(2000)
        return linkedMapOf(
            "heart_rate" to (55..110).random(),
            "spo2" to (94..99).random(),
            "cadence" to (0..40).random()
        )
    }
}
