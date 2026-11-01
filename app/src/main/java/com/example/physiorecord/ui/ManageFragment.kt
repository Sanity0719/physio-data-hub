package com.example.physiorecord.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.physiorecord.data.DeviceRegistry
import com.example.physiorecord.data.PhysioRepository
import com.example.physiorecord.export.DeviceCsv
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.io.File

/**
 * 模块三：数据管理。
 *
 * 结构同设备连接：状态卡 + 2 列功能宫格。
 * 清空单独一块（红字 + 二次确认），不跟"导出/导入"混在一起。
 */
class ManageFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var repository: PhysioRepository
    private lateinit var deviceName: TextView
    private lateinit var deviceMeta: TextView
    private lateinit var result: TextView
    private lateinit var root: ScrollView
    private lateinit var metricInput: android.widget.EditText
    private lateinit var minutesInput: android.widget.EditText
    private lateinit var queryResult: TextView
    private lateinit var recordRows: LinearLayout
    private var recentList: List<PhysioRepository.Reading> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        prefs = Prefs(context)
        repository = PhysioRepository(context)

        root = ScrollView(context)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16f), UiKit.dp(context, 12f),
                UiKit.dp(context, 16f), UiKit.dp(context, 16f))
        }
        root.addView(box)

        // ===== 状态卡 =====
        val status = UiKit.card(context)
        val statusBody = UiKit.body(context)
        statusBody.addView(UiKit.label(context, "当前设备"))
        deviceName = UiKit.title(context, "—", 24f)
        statusBody.addView(deviceName)
        deviceMeta = UiKit.hint(context, "读取中…")
        statusBody.addView(deviceMeta)
        status.addView(statusBody)
        box.addView(status)

        // ===== 功能宫格 =====
        box.addView(UiKit.label(context, "数据交换"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 12f) })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            UiKit.tile(context, "导出 CSV", "把当前设备写成文件", ::exportCsv),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also { it.rightMargin = UiKit.dp(context, 8f) }
        )
        row.addView(
            UiKit.tile(context, "导入 CSV", "从文件补数据，同刻跳过", ::importCsv),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        box.addView(UiKit.label(context, "条件查询"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 14f) })

        val queryRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        metricInput = android.widget.EditText(context).apply {
            hint = "指标（可空=全部）"
            setHintTextColor(UiKit.DIM)
            setTextColor(UiKit.TEXT)
        }
        queryRow.addView(metricInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        minutesInput = android.widget.EditText(context).apply {
            hint = "最近几分钟（可空=全部）"
            setHintTextColor(UiKit.DIM)
            setTextColor(UiKit.TEXT)
        }
        queryRow.addView(minutesInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        queryRow.addView(android.widget.Button(context).apply {
            text = "查"
            setOnClickListener { runQuery() }
        })
        box.addView(queryRow)
        queryResult = UiKit.text(context, "查询结果：—", 12f, UiKit.DIM)
        box.addView(queryResult)

        box.addView(UiKit.label(context, "数据列表（看全部、按序号改删）"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 14f) })
        recordRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(recordRows)

        box.addView(UiKit.label(context, "记录操作"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 14f) })

        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(
            UiKit.tile(context, "手动补一条", "记到主指标，时刻取现在", ::addManual),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also { it.rightMargin = UiKit.dp(context, 8f) }
        )
        row2.addView(
            UiKit.tile(context, "改最近一条", "只改最近一次的主指标值", ::editLatest),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        box.addView(row2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        val row3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(
            UiKit.tile(context, "删最近一条", "删掉最近一次读数", ::deleteLatest),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also { it.rightMargin = UiKit.dp(context, 8f) }
        )
        row3.addView(
            UiKit.tile(context, "按时间范围删", "起止时间自己填，确认后删", ::deleteRange),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        box.addView(row3, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        box.addView(
            UiKit.tile(context, "配置下发", "发一条命令并记历史", ::openCommands),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = UiKit.dp(context, 8f) }
        )
        box.addView(
            UiKit.tile(context, "远程设备", "远端列表、最近在线、按条删", ::openRemote),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = UiKit.dp(context, 8f) }
        )

        box.addView(UiKit.label(context, "危险操作"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 14f) })

        val danger = UiKit.tile(context, "清空当前设备", "只删当前设备；会二次确认", ::confirmClear)
        (danger.getChildAt(0) as LinearLayout).getChildAt(0).let {
            (it as TextView).setTextColor(0xFFFF6B6B.toInt())
        }
        box.addView(danger, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 8f) })

        result = UiKit.text(context, "尚未操作", 12f, UiKit.DIM)
        box.addView(result, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = UiKit.dp(context, 10f) })

        return root
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
    }

    private fun openCommands() {
        (activity as? com.example.physiorecord.MainActivity)?.openPage(CommandCenterFragment())
    }

    private fun openRemote() {
        (activity as? com.example.physiorecord.MainActivity)?.openPage(RemoteDevicesFragment())
    }

    private fun csvFile(): File = File(requireContext().getExternalFilesDir(null), "physio.csv")

    private fun runQuery() {
        val key = prefs.currentDevice
        val metric = metricInput.text.toString().trim().ifBlank { null }
        val minutes = minutesInput.text.toString().trim().toIntOrNull()
        val to = System.currentTimeMillis()
        val from = if (minutes == null) 0L else to - minutes * 60_000L
        Bg.io.execute {
            val list = repository.query(key, metric, from, to, 20)
            Bg.onMain {
                if (list.isEmpty()) {
                    queryResult.text = "查询结果：没有符合条件的读数"
                } else {
                    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    val lines = list.take(8).map { r ->
                        fmt.format(java.util.Date(r.at)) + " · " + r.values.entries.joinToString(" / ") { "${it.key} ${it.value}" }
                    }
                    queryResult.text = "查询结果：${list.size} 条（最多显示 8 条）\n" + lines.joinToString("\n")
                }
            }
        }
    }

    private fun askNumber(title: String, hint: String, initial: String, onOk: (Int) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            this.hint = hint
            setText(initial)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null) result.text = "要填一个整数" else onOk(v)
            }
            .show()
    }

    private fun addManual() = askNumber("手动补一条", "值（整数）", "") { v ->
        val key = prefs.currentDevice
        Bg.io.execute {
            val ok = repository.insertManual(key, v)
            val n = repository.countAll(key)
            Bg.onMain {
                result.text = if (ok) "已补一条：$v（共 $n 笔）" else "没补上：同一时刻已有记录"
                refreshInfo()
            }
        }
    }

    private fun editLatest() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val latest = repository.latestReading(key)
            val primary = DeviceRegistry.byKey(key)?.primaryMetric ?: "value"
            val current = latest?.values?.get(primary)
            Bg.onMain {
                if (latest == null || current == null) {
                    result.text = "没有记录可改，先补一条"
                    return@onMain
                }
                askNumber("改最近一条", "原来 $current", current.toString()) { v ->
                    Bg.io.execute {
                        val rows = repository.updateLatest(key, v)
                        Bg.onMain {
                            result.text = "已改最近一条：$current → $v（$rows 行）"
                            refreshInfo()
                        }
                    }
                }
            }
        }
    }

    private fun deleteLatest() {
        val key = prefs.currentDevice
        AlertDialog.Builder(requireContext()).setTitle("删最近一条")
            .setMessage("确定删掉当前设备最近一次读数？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删") { _, _ ->
                Bg.io.execute {
                    val rows = repository.deleteLatest(key)
                    Bg.onMain {
                        result.text = "已删最近一条（$rows 行）"
                        refreshInfo()
                    }
                }
            }.show()
    }

    private fun deleteRange() {
        val ctx = requireContext()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        val now = System.currentTimeMillis()
        val fromInput = android.widget.EditText(ctx).apply { setText(fmt.format(java.util.Date(now - 10 * 60_000L))) }
        val toInput = android.widget.EditText(ctx).apply { setText(fmt.format(java.util.Date(now))) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(ctx, 16f), UiKit.dp(ctx, 8f), UiKit.dp(ctx, 16f), 0)
            addView(UiKit.label(ctx, "起始时间（yyyy-MM-dd HH:mm）"))
            addView(fromInput)
            addView(UiKit.label(ctx, "结束时间（yyyy-MM-dd HH:mm）"))
            addView(toInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle("按时间范围删")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("删") { _, _ ->
                val from = parseTime(fmt, fromInput.text.toString())
                val to = parseTime(fmt, toInput.text.toString())
                if (from == null || to == null || from > to) {
                    result.text = "时间格式要像 2026-10-22 21:30，且起始不晚于结束"
                    return@setPositiveButton
                }
                Bg.io.execute {
                    val rows = repository.deleteBetween(keyOf(), from, to)
                    Bg.onMain {
                        result.text = "已删范围 ${fmt.format(java.util.Date(from))} – ${fmt.format(java.util.Date(to))} 内 $rows 行"
                        refreshInfo()
                    }
                }
            }
            .show()
    }

    private fun parseTime(fmt: java.text.SimpleDateFormat, text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return runCatching { fmt.isLenient = false; fmt.parse(t)?.time }.getOrNull()
    }

    private fun keyOf(): String = prefs.currentDevice

    private fun refreshInfo() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val n = repository.countAll(key)
            val recent = repository.query(key, null, 0, System.currentTimeMillis(), 10)
            val device = DeviceRegistry.byKey(key)
            val name = device?.alias ?: key
            val channel = device?.channelSummary() ?: ""
            Bg.onMain {
                recentList = recent
                renderRecords(key)
                deviceName.text = name
                deviceMeta.text = "$channel · 共 $n 笔\n文件：${csvFile().absolutePath}"
            }
        }
    }

    private fun renderRecords(key: String) {
        val ctx = requireContext()
        recordRows.removeAllViews()
        recordRows.addView(android.widget.Button(ctx).apply {
            text = "打开数据列表（全部数据，按序号改/删）"
            setOnClickListener {
                (activity as? com.example.physiorecord.MainActivity)
                    ?.openPage(DataListFragment.newInstance(key))
            }
        })
    }

    private fun showRecord(key: String, at: Long) {
        val ctx = requireContext()
        val reading = recentList.firstOrNull { it.at == at } ?: return
        val primary = DeviceRegistry.byKey(key)?.primaryMetric ?: "value"
        val current = reading.values[primary]
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        AlertDialog.Builder(ctx)
            .setTitle(fmt.format(java.util.Date(at)))
            .setMessage(reading.values.entries.joinToString("\n") { "${it.key}：${it.value}" })
            .setPositiveButton("改值") { _, _ ->
                if (current == null) {
                    result.text = "这条没有主指标，改不了"
                } else {
                    askNumber("改这一条", "$primary 原值 $current", current.toString()) { v ->
                        Bg.io.execute {
                            val rows = repository.updateAt(key, at, primary, v)
                            Bg.onMain {
                                result.text = "已改 $current → $v（$rows 行）"
                                refreshInfo()
                            }
                        }
                    }
                }
            }
            .setNeutralButton("删这条") { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle("删除这一条")
                    .setMessage("${fmt.format(java.util.Date(at))}\n" + reading.values.entries.joinToString(" / ") { "${it.key} ${it.value}" })
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删") { _, _ ->
                        Bg.io.execute {
                            val rows = repository.deleteAt(key, at)
                            Bg.onMain {
                                result.text = "已删一条（$rows 行）"
                                refreshInfo()
                            }
                        }
                    }.show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportCsv() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val file = csvFile()
            val n = DeviceCsv.export(repository, key, file)
            Bg.onMain {
                result.text = "已导出 $n 条 → ${file.absolutePath}"
                Snackbar.make(root, "已导出 $n 条", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun importCsv() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val file = csvFile()
            if (!file.exists()) {
                Bg.onMain { result.text = "没有找到 CSV，先导出一次或把文件放过去" }
                return@execute
            }
            val report = DeviceCsv.import(repository, key, file)
            Bg.onMain {
                result.text = "导入 ${report.imported} 条，跳过 ${report.skipped}，坏行 ${report.bad}"
                Snackbar.make(root, "导入 ${report.imported} 条", Snackbar.LENGTH_SHORT).show()
                refreshInfo()
            }
        }
    }

    private fun confirmClear() {
        val name = DeviceRegistry.byKey(prefs.currentDevice)?.alias ?: prefs.currentDevice
        AlertDialog.Builder(requireContext())
            .setTitle("清空当前设备")
            .setMessage("将删除「$name」的全部记录，不能撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> clearDevice() }
            .show()
    }

    private fun clearDevice() {
        val key = prefs.currentDevice
        Bg.io.execute {
            val n = repository.deleteByDevice(key)
            Bg.onMain {
                result.text = "已清空当前设备：$n 条"
                refreshInfo()
            }
        }
    }
}
