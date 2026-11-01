package com.example.physiorecord.ui

import androidx.fragment.app.Fragment

/**
 * 模块登记表：底部导航就是照着这张表生成的。
 *
 * 新增模块只需在此登记一行 Module(...)，
 * 不用改 MainActivity 的布局和切换逻辑——这就是"给未来留位置"的做法。
 */
data class Module(
    val id: String,
    val title: String,
    val factory: () -> Fragment
)

object Modules {

    /** 当前只登记"后端已经存在"的三个模块 */
    val all: List<Module> = listOf(
        Module("visual", "数据可视化") { VisualizeFragment() },
        Module("devices", "设备连接") { DevicesFragment() },
        Module("manage", "数据管理") { ManageFragment() }
    )
}
