package com.example.physiorecord.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.physiorecord.data.PhysioRepository
import com.example.physiorecord.ui.Prefs

/**
 * 前台服务：在后台运行、没有界面的组件。
 *
 * 它做四件事：
 *   1. 进入服务后先发布常驻通知（前台服务的硬性要求，也让采集状态可见）；
 *   2. 按固定间隔重复同一动作：从数据源读一次，经仓库写入数据库；
 *   3. 被叫停时，把定时任务摘掉；
 *   4. 全程不碰 SQL——它只认识仓库的 save(...)。
 *
 * 每一轮的「读一次、写一笔」都交给后台执行线程，
 * 但"约下一轮"仍留在主线程——这样间隔不会被写库耗时拖长。
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "Capture"
        private const val CHANNEL_ID = "capture_channel"
        private const val CHANNEL_NAME = "持续采集"
        private const val NOTIFICATION_ID = 1

        /** 采集间隔（毫秒）：10000 = 10 秒 */
        const val INTERVAL_MS = 10000L
    }

    /**
     * 已登记的数据源：在 onCreate 里建——字段初始化阶段还没有 Context，
     * 在那里调用 Prefs(this) 会因拿不到 Context 而崩溃。
     */
    private lateinit var sources: List<CaptureSource>

    private lateinit var repository: PhysioRepository

    /** 周期调度：到点重新触发一次采集 */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 每次到点做的事：把"读一次"作为一条命令交给设备队列，
     * 结果回来后把值交给写库线；"约下一次"仍在主线程，计时从本次 tick 起算。
     */
    private val tick = object : Runnable {
        override fun run() {
            // 每台设备各自提交一条；慢设备只占它自己的队列，不挡别人
            for (source in sources) {
                val key = source.key
                DeviceHub.submit(key, DeviceCommand.READ_ONCE) { result ->
                    if (result is CommandResult.Ok) {
                        if (result.metrics.isEmpty()) return@submit
                        repository.saveAsync(key, result.metrics, System.currentTimeMillis())
                    } else if (result is CommandResult.Failed) {
                        Log.d(TAG, "读一次失败（$key）：${result.reason}")
                    }
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()

        repository = PhysioRepository(this)
        val prefs = Prefs(this)
        // 内置源 + 登记表里所有带可读通道的设备：BLE/SPP/USB 也会进周期采集（没连上时每轮记失败）
        sources = buildList {
            add(PhoneSensorSource())
            add(DemoBandSource())
            for (device in com.example.physiorecord.data.DeviceRegistry.all()) {
                DeviceSources.forDevice(device, prefs)?.let { add(it) }
            }
        }
        sources.forEach { DeviceHub.register(it) }

        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())

        handler.postDelayed(tick, INTERVAL_MS)
        Log.d(TAG, "采集服务已启动：每 $INTERVAL_MS 毫秒写一笔（现在把应用切到后台看看）")
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)   // 只停未来的 tick；在飞的一条会跑完
        Log.d(TAG, "采集服务已停止：不再有新的一笔")
        super.onDestroy()
    }

    /** 服务不提供绑定 */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 建"通知抽屉"：Android 8.0 起，发通知前必须先建好渠道，否则通知不显示 */
    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /** 构造前台服务的常驻通知 */
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("正在采集")
            .setContentText("应用退到后台也会继续记录")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}
