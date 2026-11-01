package com.example.physiorecord.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.physiorecord.capture.CaptureService
import com.example.physiorecord.capture.CommandResult
import com.example.physiorecord.capture.DeviceCommand
import com.example.physiorecord.capture.DeviceHub
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.PhysioRepository
import com.example.physiorecord.link.LanListener
import com.example.physiorecord.link.OutboundClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块二：设备连接。
 *
 * 结构（不是列表）：
 *   顶部一条状态卡 —— 当前设备是谁、共几笔、最近一笔是什么；
 *   下面 2 列功能宫格 —— 每个格子一件事，未来加功能只加格子；
 *   最下面一行小字 —— 最近一次操作的结果。
 */
class DevicesFragment : Fragment() {

    private lateinit var repository: PhysioRepository
    private lateinit var prefs: Prefs
    private lateinit var heroName: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroLatest: TextView
    private lateinit var linkStatus: TextView
    private lateinit var linkButton: android.widget.Button
    private lateinit var actionStatus: TextView
    private lateinit var root: ScrollView

    private var listener: LanListener? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            updateLinkUi()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        repository = PhysioRepository(context)
        prefs = Prefs(context)

        root = ScrollView(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 12f),
                UiKit.dp(context, 16f), UiKit.dp(context, 16f))
        }
        root.addView(box)

        // ===== 状态卡 =====
        val hero = UiKit.card(context)
        val heroBody = UiKit.body(context)
        heroBody.addView(UiKit.label(context, "当前设备"))
        heroName = UiKit.title(context, "—", 24f)
        heroBody.addView(heroName)
        heroMeta = UiKit.hint(context, "")
        heroBody.addView(heroMeta)
        heroLatest = UiKit.text(context, "", 12f, UiKit.DIM)
        heroBody.addView(heroLatest)
        linkStatus = UiKit.text(context, "○ 未连接", 13f, UiKit.CYAN)
        heroBody.addView(linkStatus)
        hero.addView(heroBody)
        box.addView(hero)

        // 连接 / 断开：文案随状态变；状态只由会话对象给
        linkButton = android.widget.Button(context).apply {
            text = "连接"
            setOnClickListener { connectOrDisconnect() }
        }
        box.addView(linkButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        box.addView(UiKit.tile(context, "连接设置", "地址 / 端口，测试连接", ::openLinkSettings),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = UiKit.dp(context, 8f) })

        // ===== 功能宫格（2 列）=====
        box.addView(UiKit.label(context, "操作"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 12f) })

        addRow(
            box,
            UiKit.tile(context, "切换设备", "从登记表里选一台", ::showDevicePicker),
            UiKit.tile(context, "再读一次", "读一笔并入库", ::readOnce)
        )
        addRow(
            box,
            UiKit.tile(context, "开始持续采集", "前台服务按间隔写库", ::startCapture),
            UiKit.tile(context, "停止采集", "停止定时写库", ::stopCapture)
        )
        addRow(
            box,
            UiKit.tile(context, "开始监听", "守端口，等 PC 来问", ::startListening),
            UiKit.tile(context, "停止监听", "关连接、停线程", ::stopListening)
        )
        box.addView(
            UiKit.tile(context, "主动发一次 LATEST", "手机连出去，要一条最新数据", ::sendOnce),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = UiKit.dp(context, 8f) }
        )

        actionStatus = UiKit.text(context, "", 12f, UiKit.DIM)
        box.addView(actionStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 10f) })

        return root
    }

    /** 一行两格：等宽，间距一致 */
    private fun addRow(box: LinearLayout, left: View, right: View) {
        val context = requireContext()
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            .also { it.rightMargin = UiKit.dp(context, 8f) })
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        uiHandler.post(statusTick)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onDestroyView() {
        stopListening()
        super.onDestroyView()
    }

    private fun refresh() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val total = repository.countAll(key)
            val latest = repository.recent(key, 1).firstOrNull()
            val device = DeviceRegistry.byKey(key)
            val name = device?.alias ?: key
            val meta = device?.let { "${it.model} · 通道 ${it.channelSummary()}" } ?: ""
            Bg.onMain {
                heroName.text = name
                heroMeta.text = "$meta · 共 $total 笔"
                heroLatest.text = if (latest == null) "还没有记录" else "最近：${fmt.format(Date(latest.at))} · ${latest.value}"
                updateLinkUi()
            }
        }
    }

    /** 状态行与主按钮只读会话状态；每秒刷一次，后台读失败也能及时反映 */
    private fun updateLinkUi() {
        if (!this::linkStatus.isInitialized || !this::linkButton.isInitialized) return
        val status = DeviceHub.linkStatus(prefs.currentDevice)
        linkStatus.text = status.line()
        when (status.state) {
            com.example.physiorecord.capture.LinkState.CONNECTED -> {
                linkButton.text = "断开"
                linkButton.isEnabled = true
            }
            com.example.physiorecord.capture.LinkState.RETRYING -> {
                linkButton.text = "停止重试"
                linkButton.isEnabled = true
            }
            com.example.physiorecord.capture.LinkState.CONNECTING -> {
                linkButton.text = "连接中…"
                linkButton.isEnabled = false
            }
            else -> {
                linkButton.text = "连接"
                linkButton.isEnabled = true
            }
        }
    }

    private fun showDevicePicker() {
        (activity as? com.example.physiorecord.MainActivity)?.openPage(DeviceListFragment())
    }

    private fun openLinkSettings() {
        (activity as? com.example.physiorecord.MainActivity)?.openPage(LinkSettingsFragment())
    }

    private fun isSppDevice(key: String): Boolean {
        val kind = com.example.physiorecord.data.DeviceRegistry.byKey(key)
            ?.channels?.firstOrNull()?.kind
        return kind == com.example.physiorecord.data.ChannelKind.CLASSIC_BLUETOOTH ||
            kind == com.example.physiorecord.data.ChannelKind.BLE
    }

    private fun connectOrDisconnect() {
        val key = prefs.currentDevice
        if (isSppDevice(key) && Build.VERSION.SDK_INT >= 31 &&
            requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2002)
            actionStatus.text = "等蓝牙权限后再点连接"
            return
        }
        val status = DeviceHub.linkStatus(key)
        val connected = status.state == com.example.physiorecord.capture.LinkState.CONNECTED ||
            status.state == com.example.physiorecord.capture.LinkState.RETRYING
        val command = if (connected) DeviceCommand.DISCONNECT else DeviceCommand.CONNECT
        actionStatus.text = if (connected) "断开中…" else "连接中…"
        DeviceHub.submit(key, command) { result ->
            when (result) {
                is CommandResult.Ok -> Bg.onMain {
                    actionStatus.text = if (connected) "已断开" else "已连接"
                    refresh()
                }
                is CommandResult.Failed -> Bg.onMain {
                    actionStatus.text = (if (connected) "断开失败：" else "连接失败：") + result.reason
                    refresh()
                }
                is CommandResult.NoReceipt -> Bg.onMain {
                    actionStatus.text = "没有回话：${result.reason}"
                    refresh()
                }
            }
        }
    }

    private fun readOnce() {
        val key = prefs.currentDevice
        actionStatus.text = "读一次…"
        DeviceHub.submit(key, DeviceCommand.READ_ONCE) { result ->
            when (result) {
                is CommandResult.Ok -> {
                    val metrics = result.metrics
                    repository.saveAsync(key, metrics, System.currentTimeMillis())
                    val shown = metrics.entries.joinToString(" / ") { "${it.key} ${it.value}" }
                    Bg.onMain {
                        actionStatus.text = "读到 ${metrics.size} 个指标：$shown"
                        refresh()
                    }
                }
                is CommandResult.Failed -> Bg.onMain {
                    actionStatus.text = "读取失败：${result.reason}"
                    refresh()
                }
                is CommandResult.NoReceipt -> Bg.onMain {
                    actionStatus.text = "读取没有回话：${result.reason}"
                    refresh()
                }
            }
        }
    }

    private fun startCapture() {
        val context = requireContext()
        val intent = Intent(context, CaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        actionStatus.text = "已请求启动采集服务"
    }

    private fun stopCapture() {
        val context = requireContext()
        context.stopService(Intent(context, CaptureService::class.java))
        actionStatus.text = "已请求停止采集服务"
    }

    // ===== 局域网通路（已有后端） =====

    private fun startListening() {
        if (listener != null) return
        if (Build.VERSION.SDK_INT >= 37 &&
            requireContext().checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 2001)
            actionStatus.text = "未监听（等本地网络权限）"
            return
        }
        val l = LanListener { replyLatest() }
        listener = l
        l.start(object : LanListener.Listener {
            override fun onState(text: String) { Bg.onMain { actionStatus.text = text } }
            override fun onRequest(request: String, response: String) {
                Bg.onMain { actionStatus.text = "最近：$request → $response" }
            }
            override fun onError(message: String) { Bg.onMain { actionStatus.text = "问题：$message" } }
        })
        actionStatus.text = "监听中 · 端口 ${LanListener.PORT}"
    }

    private fun stopListening() {
        listener?.stop()
        listener = null
        if (this::actionStatus.isInitialized) actionStatus.text = "未监听"
    }

    private fun sendOnce() {
        val host = prefs.sendHost
        val port = prefs.sendPort
        actionStatus.text = "外发中 → $host:$port"
        Thread {
            val result = try {
                OutboundClient.send(host, port, "LATEST")
            } catch (e: Exception) {
                "失败：${e.message}"
            }
            Bg.onMain { actionStatus.text = "外发 → 回 $result" }
        }.start()
    }

    /** 回包格式沿用旧版：设备编号|序号|时刻|值|显示名 */
    private fun replyLatest(): String {
        val latest = repository.recent(prefs.currentDevice, 1).firstOrNull() ?: return "EMPTY"
        return "${latest.deviceKey}|${latest.seq}|${fmt.format(Date(latest.at))}|${latest.value}|${latest.displayName}"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else actionStatus.text = "没有本地网络权限，无法监听"
        }
        if (requestCode == 2002) {
            actionStatus.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                "已授权，再点连接"
            } else {
                "没有蓝牙权限，无法连接"
            }
        }
    }
}
