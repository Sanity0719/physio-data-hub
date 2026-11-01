package com.example.physiorecord.capture

import com.example.physiorecord.data.DeviceRegistry

/**
 * 实现一：手机自带的传感器。
 *
 * 本类只负责"给一次读数"；读数怎么用、写到哪，由调用方决定。
 * 真要接真实传感器时，替换本类的实现即可，接口不变。
 */
class PhoneSensorSource : CaptureSource {

    override val key: String = DeviceRegistry.DEFAULT_KEY

    override val name: String = "手机传感器"

    /** 返回 0..100 的随机数，用于无外设时跑通采集链路 */
    override fun readOnce(): Int = (0..100).random()
}
