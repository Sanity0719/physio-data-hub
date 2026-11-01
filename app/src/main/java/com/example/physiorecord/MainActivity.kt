package com.example.physiorecord

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.physiorecord.capture.DemoBandSource
import com.example.physiorecord.capture.DeviceHub
import com.example.physiorecord.capture.PhoneSensorSource
import com.example.physiorecord.ui.Module
import com.example.physiorecord.ui.Modules
import com.example.physiorecord.ui.UiKit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 唯一的 Activity：上面标题、下面底部导航、中间换页面。
 *
 * 导航项不是写死的，而是遍历 Modules.all 生成：
 * 加模块只改登记表（Modules.kt 一行），这里不用动。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 数据源只在入口登记一次（设备队列按编号找它们）
        DeviceHub.init(this)
        DeviceHub.register(PhoneSensorSource())
        DeviceHub.register(DemoBandSource())
        val prefs = com.example.physiorecord.ui.Prefs(this)
        // 界面自建的设备先从库里读回来，再统一注册数据源
        com.example.physiorecord.data.DeviceRegistry.loadCustom(
            com.example.physiorecord.data.PhysioRepository(this).customDevices()
        )
        android.util.Log.d("Storage", "登记设备共 ${com.example.physiorecord.data.DeviceRegistry.all().size} 台（含界面自建）")
        // 登记表里所有带可读通道的设备都在这里自动注册源；加设备只改 DeviceRegistry 一行
        com.example.physiorecord.capture.DeviceSources.registerAll(prefs)
        DeviceHub.configureTcp("net_gateway", prefs.sendHost, prefs.sendPort)
        DeviceHub.configureSpp("spp_sensor", prefs.sppMac)
        DeviceHub.configureBle("ble_sensor", prefs.bleMac)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF05070E.toInt())
        }

        toolbar = MaterialToolbar(this).apply {
            title = Modules.all.first().title
            setTitleTextColor(UiKit.TEXT)
            setBackgroundColor(0xFF05070E.toInt())
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 56f))
        )

        container = FrameLayout(this).apply { id = CONTAINER_ID }
        root.addView(
            container,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val nav = BottomNavigationView(this).apply {
            setBackgroundColor(0xFF0A1220.toInt())
            itemTextColor = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(UiKit.CYAN, UiKit.DIM)
            )
            Modules.all.forEachIndexed { index, module ->
                menu.add(0, index + 1, index, module.title)
            }
        }
        nav.setOnItemSelectedListener { item ->
            if (!restoring) {
                Modules.all.getOrNull(item.itemId - 1)?.let { show(it) }
            }
            true
        }
        root.addView(nav)

        setContentView(root)
        val savedModule = savedInstanceState?.getInt("module", 0) ?: 0
        if (savedInstanceState == null) {
            nav.selectedItemId = 1
        } else {
            // 重建（旋转）：让 FragmentManager 恢复原页面，不要用 replace 把它顶掉
            restoring = true
            nav.selectedItemId = savedModule + 1
            restoring = false
            currentModule = savedModule
            toolbar.title = Modules.all[savedModule].title
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pageBefore != null) {
                    closePage()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun show(module: Module) {
        pageBefore = null
        currentModule = Modules.all.indexOf(module)
        toolbar.title = module.title
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, module.factory())
            .commit()
    }

    private var pageBefore: androidx.fragment.app.Fragment? = null
    private var currentModule = 0
    private var restoring = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("module", currentModule)
    }

    /** 打开一个子页面；返回时用 [closePage] 原样回到进入前的页面 */
    fun openPage(page: androidx.fragment.app.Fragment) {
        pageBefore = supportFragmentManager.findFragmentById(CONTAINER_ID)
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, page)
            .commit()
    }

    /** 从子页面回到进入前的那一页（重新 onResume，展示页会刷新） */
    fun closePage() {
        val back = pageBefore
        pageBefore = null
        if (back == null) {
            // 返回目标没保住（例如旋转后重建）：回到当前模块，至少导航与内容一致
            show(Modules.all.getOrElse(currentModule) { Modules.all.first() })
            return
        }
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, back)
            .commit()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (pageBefore != null) closePage() else super.onBackPressed()
    }
    companion object {
        const val CONTAINER_ID = 1001
    }
}
