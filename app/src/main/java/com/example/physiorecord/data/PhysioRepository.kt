package com.example.physiorecord.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.physiorecord.data.PhysioDatabase.Companion.COL_AT
import com.example.physiorecord.data.PhysioDatabase.Companion.COL_METRIC
import com.example.physiorecord.data.PhysioDatabase.Companion.COL_SEQ
import com.example.physiorecord.data.PhysioDatabase.Companion.COL_VALUE

/**
 * 仓库（Repository）：数据的"门"。
 *
 * 采集层往这道门里写，界面从这道门里读；界面永远不碰 SQL。
 *
 * 所有读写都带一个"设备编号"：每台设备一张表，互不干扰。
 * 同一台设备、同一时刻只留一条——重复写进来会被跳过，不算失败。
 */
class PhysioRepository(context: Context) {

    companion object {
        private const val TAG = "Storage"

        /**
         * 全应用唯一的写库线：所有设备的写入都排到这一条线，
         * 避免多个写者抢锁。读不排队（SQLite 允许并发读）。
         */
        private val dbWrite = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "db-write")
        }
    }

    private val helper = PhysioDatabase(context)

    /** 异步写一次读数（可带多个指标）：排到唯一的写库线；可选回调告诉调用方是否真写进去 */
    fun saveAsync(deviceKey: String, metrics: Map<String, Int>, at: Long, onResult: ((Boolean) -> Unit)? = null) {
        dbWrite.execute {
            val ok = saveReading(deviceKey, at, metrics)
            onResult?.invoke(ok)
        }
    }

    /**
     * 写一次读数：这次读数里的每个指标各占一行，同一时刻同一指标只留一条。
     * 返回 true 表示至少写进了一个指标；false 表示全部被跳过（同刻同指标已存在）。
     */
    fun saveReading(deviceKey: String, at: Long, metrics: Map<String, Int>): Boolean {
        if (metrics.isEmpty()) return false
        val table = DeviceRegistry.tableFor(deviceKey)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // 一次读数一个自增序号：同一时刻的多指标行共用它
            val readingId = db.rawQuery(
                "SELECT COALESCE(MAX(${PhysioDatabase.COL_READING_ID}),0)+1 FROM $table", null
            ).use { if (it.moveToFirst()) it.getLong(0) else 1L }
            var inserted = 0
            for ((metric, value) in metrics) {
                val values = ContentValues().apply {
                    put(COL_AT, at)
                    put(COL_METRIC, metric)
                    put(COL_VALUE, value)
                    put(PhysioDatabase.COL_READING_ID, readingId)
                }
                val seq = db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (seq != -1L) inserted++
            }
            db.setTransactionSuccessful()
            Log.d(TAG, "写入 $inserted/${metrics.size} 个指标（设备=$deviceKey，时刻=$at）")
            return inserted > 0
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 写一笔进某台设备的采集表（单指标兼容口：记到该设备的主指标上）。
     * 返回 true 表示真写进去了；false 表示同一时刻已有一条，被跳过。
     */
    fun save(deviceKey: String, value: Int, at: Long): Boolean {
        val primary = DeviceRegistry.byKey(deviceKey)?.primaryMetric ?: "value"
        return saveReading(deviceKey, at, mapOf(primary to value))
    }

    /** 一次读数：序号 + 时刻 + 这一次读到的所有指标 */
    data class Reading(
        val at: Long,
        val values: Map<String, Int>,
        val readingId: Long = 0
    ) {
        val displayName: String get() = values.entries.joinToString(" / ") { "${it.key} ${it.value}" }
    }

    /**
     * 图表定义：独立、可命名、可删除；删定义不动原始记录。
     * formulas 每行一条、每条只定义一个量：
     *  - 折线/柱状：逐条读数求值 → 一条序列
     *  - 饼图：sum(expr)/avg(expr) 聚合 → 一块
     */
    data class ChartDef(
        val id: Long,
        val name: String,
        val deviceKey: String,
        val metricKeys: List<String>,
        val chartType: String,
        val formulas: List<String>,
        val createdAt: Long
    )

    private fun splitFormulas(raw: String?): List<String> =
        raw.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun joinFormulas(list: List<String>): String =
        list.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    fun chartDefs(deviceKey: String): List<ChartDef> {
        val sql = "SELECT ${PhysioDatabase.COL_DEF_ID}, ${PhysioDatabase.COL_DEF_NAME}, " +
            "${PhysioDatabase.COL_DEF_DEVICE}, ${PhysioDatabase.COL_DEF_METRICS}, " +
            "${PhysioDatabase.COL_DEF_TYPE}, ${PhysioDatabase.COL_DEF_FORMULA}, ${PhysioDatabase.COL_DEF_CREATED} " +
            "FROM ${PhysioDatabase.TABLE_CHART_DEFS} WHERE ${PhysioDatabase.COL_DEF_DEVICE}=? " +
            "ORDER BY ${PhysioDatabase.COL_DEF_ID} ASC"
        val out = ArrayList<ChartDef>()
        helper.readableDatabase.rawQuery(sql, arrayOf(deviceKey)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChartDef(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3).split(',').filter { it.isNotBlank() },
                        c.getString(4), splitFormulas(c.getString(5)), c.getLong(6)
                    )
                )
            }
        }
        return out
    }

    fun addChartDef(
        deviceKey: String,
        name: String,
        metricKeys: List<String>,
        chartType: String = "line",
        formulas: List<String> = emptyList()
    ): Long {
        val values = ContentValues().apply {
            put(PhysioDatabase.COL_DEF_NAME, name)
            put(PhysioDatabase.COL_DEF_DEVICE, deviceKey)
            put(PhysioDatabase.COL_DEF_METRICS, metricKeys.joinToString(","))
            put(PhysioDatabase.COL_DEF_TYPE, chartType)
            put(PhysioDatabase.COL_DEF_FORMULA, joinFormulas(formulas))
            put(PhysioDatabase.COL_DEF_CREATED, System.currentTimeMillis())
        }
        return helper.writableDatabase.insert(PhysioDatabase.TABLE_CHART_DEFS, null, values)
    }

    fun deleteChartDef(id: Long): Int =
        helper.writableDatabase.delete(PhysioDatabase.TABLE_CHART_DEFS, "${PhysioDatabase.COL_DEF_ID}=?", arrayOf(id.toString()))

    /** 按 id 取一条定义；不在就返回 null（例如定义已被删） */
    fun chartDefById(id: Long): ChartDef? = chartDefsAny()
        .firstOrNull { it.id == id }

    private fun chartDefsAny(): List<ChartDef> {
        val sql = "SELECT ${PhysioDatabase.COL_DEF_ID}, ${PhysioDatabase.COL_DEF_NAME}, " +
            "${PhysioDatabase.COL_DEF_DEVICE}, ${PhysioDatabase.COL_DEF_METRICS}, " +
            "${PhysioDatabase.COL_DEF_TYPE}, ${PhysioDatabase.COL_DEF_FORMULA}, ${PhysioDatabase.COL_DEF_CREATED} " +
            "FROM ${PhysioDatabase.TABLE_CHART_DEFS} ORDER BY ${PhysioDatabase.COL_DEF_ID} ASC"
        val out = ArrayList<ChartDef>()
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChartDef(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3).split(',').filter { it.isNotBlank() },
                        c.getString(4), splitFormulas(c.getString(5)), c.getLong(6)
                    )
                )
            }
        }
        return out
    }

    /** 一条下发记录：状态机 PENDING → APPLIED / REJECTED / NO_RECEIPT */
    data class CommandRow(
        val id: Long,
        val deviceKey: String,
        val payload: String,
        val status: String,
        val reason: String?,
        val receipt: String?,
        val createdAt: Long,
        val updatedAt: Long
    )

    fun insertCommand(deviceKey: String, payload: String): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(PhysioDatabase.COL_CMD_DEVICE, deviceKey)
            put(PhysioDatabase.COL_CMD_PAYLOAD, payload)
            put(PhysioDatabase.COL_CMD_STATUS, "PENDING")
            put(PhysioDatabase.COL_CMD_CREATED, now)
            put(PhysioDatabase.COL_CMD_UPDATED, now)
        }
        return helper.writableDatabase.insert(PhysioDatabase.TABLE_COMMANDS, null, values)
    }

    fun updateCommand(id: Long, status: String, reason: String?, receipt: String?) {
        val values = ContentValues().apply {
            put(PhysioDatabase.COL_CMD_STATUS, status)
            put(PhysioDatabase.COL_CMD_REASON, reason)
            put(PhysioDatabase.COL_CMD_RECEIPT, receipt)
            put(PhysioDatabase.COL_CMD_UPDATED, System.currentTimeMillis())
        }
        helper.writableDatabase.update(
            PhysioDatabase.TABLE_COMMANDS, values,
            "${PhysioDatabase.COL_CMD_ID}=?", arrayOf(id.toString())
        )
    }

    fun recentCommands(deviceKey: String, limit: Int = 20): List<CommandRow> {
        val sql = "SELECT ${PhysioDatabase.COL_CMD_ID}, ${PhysioDatabase.COL_CMD_DEVICE}, " +
            "${PhysioDatabase.COL_CMD_PAYLOAD}, ${PhysioDatabase.COL_CMD_STATUS}, " +
            "${PhysioDatabase.COL_CMD_REASON}, ${PhysioDatabase.COL_CMD_RECEIPT}, " +
            "${PhysioDatabase.COL_CMD_CREATED}, ${PhysioDatabase.COL_CMD_UPDATED} " +
            "FROM ${PhysioDatabase.TABLE_COMMANDS} WHERE ${PhysioDatabase.COL_CMD_DEVICE}=? " +
            "ORDER BY ${PhysioDatabase.COL_CMD_ID} DESC LIMIT ?"
        val out = ArrayList<CommandRow>()
        helper.readableDatabase.rawQuery(sql, arrayOf(deviceKey, limit.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CommandRow(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getLong(6), c.getLong(7)
                    )
                )
            }
        }
        return out
    }

    /** 改一条定义（名字/指标/图型/公式）；createdAt 不动 */
    fun updateChartDef(
        id: Long,
        name: String,
        metricKeys: List<String>,
        chartType: String,
        formulas: List<String> = emptyList()
    ): Int {
        val values = ContentValues().apply {
            put(PhysioDatabase.COL_DEF_NAME, name)
            put(PhysioDatabase.COL_DEF_METRICS, metricKeys.joinToString(","))
            put(PhysioDatabase.COL_DEF_TYPE, chartType)
            put(PhysioDatabase.COL_DEF_FORMULA, joinFormulas(formulas))
        }
        return helper.writableDatabase.update(
            PhysioDatabase.TABLE_CHART_DEFS, values, "${PhysioDatabase.COL_DEF_ID}=?", arrayOf(id.toString())
        )
    }

    /** 最近一次读数（含它带的全部指标） */
    fun latestReading(deviceKey: String): Reading? = recentReadings(deviceKey, 1).firstOrNull()

    /** 手动补一条：值记到该设备的主指标上，时刻取当前 */
    fun insertManual(deviceKey: String, value: Int, at: Long = System.currentTimeMillis()): Boolean {
        val primary = DeviceRegistry.byKey(deviceKey)?.primaryMetric ?: "value"
        return saveReading(deviceKey, at, mapOf(primary to value))
    }

    /** 改最近一次读数里主指标的值；返回改了几行（0 = 没有记录或值相同） */
    fun updateLatest(deviceKey: String, value: Int): Int {
        val latest = latestReading(deviceKey) ?: return 0
        val primary = DeviceRegistry.byKey(deviceKey)?.primaryMetric ?: "value"
        val values = ContentValues().apply { put(COL_VALUE, value) }
        return helper.writableDatabase.update(
            DeviceRegistry.tableFor(deviceKey), values,
            "$COL_AT=? AND $COL_METRIC=?", arrayOf(latest.at.toString(), primary)
        )
    }

    /** 删最近一次读数（同一时刻的多个指标一起删）；返回删了几行 */
    fun deleteLatest(deviceKey: String): Int {
        val latest = latestReading(deviceKey) ?: return 0
        return helper.writableDatabase.delete(
            DeviceRegistry.tableFor(deviceKey), "$COL_AT=?", arrayOf(latest.at.toString())
        )
    }

    /** 删某个时间范围内的读数；返回删了几行 */
    fun deleteRange(deviceKey: String, from: Long, to: Long): Int =
        helper.writableDatabase.delete(
            DeviceRegistry.tableFor(deviceKey), "$COL_AT BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString())
        )

    /** 按“设备 + 时刻”取一条读数（多指标会一起返回） */
    fun readingAt(deviceKey: String, at: Long): Reading? =
        query(deviceKey, null, at, at, 1).firstOrNull()

    /** 按“设备 + 时刻”删一条读数（同刻的多个指标一起删） */
    fun deleteAt(deviceKey: String, at: Long): Int =
        helper.writableDatabase.delete(
            DeviceRegistry.tableFor(deviceKey), "$COL_AT=?", arrayOf(at.toString())
        )

    /** 按“设备 + 时刻 + 指标”改一个值 */
    fun updateAt(deviceKey: String, at: Long, metric: String, value: Int): Int {
        val values = ContentValues().apply { put(COL_VALUE, value) }
        return helper.writableDatabase.update(
            DeviceRegistry.tableFor(deviceKey), values,
            "$COL_AT=? AND $COL_METRIC=?", arrayOf(at.toString(), metric)
        )
    }

    /**
     * 条件查询：指标可留空（=全部），时间范围必填（调用方给 from/to）。
     * 结果按时间从新到旧，最多 limit 次读数；每个读数只回填被选中的指标（指标留空则全部）。
     */
    fun query(deviceKey: String, metric: String?, from: Long, to: Long, limit: Int = 20): List<Reading> {
        val table = DeviceRegistry.tableFor(deviceKey)
        val cols = "$COL_AT, $COL_METRIC, $COL_VALUE, ${PhysioDatabase.COL_READING_ID}"
        val where = if (metric.isNullOrBlank()) {
            "SELECT $cols FROM $table WHERE $COL_AT BETWEEN ? AND ? " +
                "ORDER BY $COL_AT DESC, $COL_SEQ DESC LIMIT ?"
        } else {
            "SELECT $cols FROM $table WHERE $COL_AT BETWEEN ? AND ? AND $COL_METRIC = ? " +
                "ORDER BY $COL_AT DESC, $COL_SEQ DESC LIMIT ?"
        }
        val args = if (metric.isNullOrBlank()) {
            arrayOf(from.toString(), to.toString(), (limit * 8).toString())
        } else {
            arrayOf(from.toString(), to.toString(), metric, (limit * 8).toString())
        }
        val grouped = LinkedHashMap<Long, MutableMap<String, Int>>()
        val ids = LinkedHashMap<Long, Long>()
        helper.readableDatabase.rawQuery(where, args).use { c ->
            while (c.moveToNext() && grouped.size < limit) {
                val at = c.getLong(0)
                grouped.getOrPut(at) { LinkedHashMap() }[c.getString(1)] = c.getInt(2)
                ids[at] = c.getLong(3)
            }
        }
        return grouped.map { Reading(it.key, it.value, ids[it.key] ?: 0) }
    }

    /** 取某台设备最近 limit 次读数（每次读数含多个指标），按时间从新到旧 */
    fun recentReadings(deviceKey: String, limit: Int): List<Reading> {
        val table = DeviceRegistry.tableFor(deviceKey)
        val sql = "SELECT $COL_AT, $COL_METRIC, $COL_VALUE, ${PhysioDatabase.COL_READING_ID} FROM $table " +
            "ORDER BY $COL_AT DESC, $COL_SEQ DESC LIMIT ?"
        val grouped = LinkedHashMap<Long, MutableMap<String, Int>>()
        val ids = LinkedHashMap<Long, Long>()
        helper.readableDatabase.rawQuery(sql, arrayOf((limit * 8).toString())).use { c ->
            while (c.moveToNext() && grouped.size < limit) {
                val at = c.getLong(0)
                grouped.getOrPut(at) { LinkedHashMap() }[c.getString(1)] = c.getInt(2)
                ids[at] = c.getLong(3)
            }
        }
        return grouped.map { Reading(it.key, it.value, ids[it.key] ?: 0) }
    }

    /** 一页读数（数据列表用）：按时间从新到旧 */
    fun pageReadings(deviceKey: String, limit: Int, offset: Int): List<Reading> {
        val table = DeviceRegistry.tableFor(deviceKey)
        val sql = "SELECT $COL_AT, $COL_METRIC, $COL_VALUE, ${PhysioDatabase.COL_READING_ID} FROM $table " +
            "WHERE $COL_AT IN (SELECT $COL_AT FROM $table GROUP BY $COL_AT ORDER BY $COL_AT DESC LIMIT ? OFFSET ?) " +
            "ORDER BY $COL_AT DESC, $COL_SEQ DESC"
        val grouped = LinkedHashMap<Long, MutableMap<String, Int>>()
        val ids = LinkedHashMap<Long, Long>()
        helper.readableDatabase.rawQuery(sql, arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                val at = c.getLong(0)
                grouped.getOrPut(at) { LinkedHashMap() }[c.getString(1)] = c.getInt(2)
                ids[at] = c.getLong(3)
            }
        }
        return grouped.map { Reading(it.key, it.value, ids[it.key] ?: 0) }
    }

    /** 一条记录（给界面显示用）：读数序号、时刻、数值、设备编号 */
    data class RecordRow(
        val seq: Long,
        val at: Long,
        val value: Int,
        val deviceKey: String,
        val readingId: Long = 0
    ) {
        /** 给人看的设备名；登记表里找不到就退回编号 */
        val displayName: String get() = DeviceRegistry.byKey(deviceKey)?.alias ?: deviceKey
    }

    /**
     * 取某台设备最近 limit 条：从最新往回数，返回顺序也是"从新到旧"。
     * 同步方法——调用方负责"别在界面线上调它"。
     */
    fun recent(deviceKey: String, limit: Int): List<RecordRow> {
        val t0 = System.nanoTime()
        val table = DeviceRegistry.tableFor(deviceKey)
        val primary = DeviceRegistry.byKey(deviceKey)?.primaryMetric ?: "value"
        val sql = "SELECT $COL_SEQ, $COL_AT, $COL_VALUE, ${PhysioDatabase.COL_READING_ID} FROM $table " +
            "WHERE $COL_METRIC = ? ORDER BY $COL_AT DESC, $COL_SEQ DESC LIMIT ?"
        val out = ArrayList<RecordRow>(limit)
        helper.readableDatabase.rawQuery(sql, arrayOf(primary, limit.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(RecordRow(c.getLong(0), c.getLong(1), c.getInt(2), deviceKey, c.getLong(3)))
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.d(TAG, "取最近 ${out.size} 条（设备=$deviceKey）：耗时=${ms}ms，线程=${Thread.currentThread().name}")
        return out
    }

    /** 取某台设备在 [from, to] 时间窗内的记录，按时间从旧到新（画图用） */
    fun recentBetween(deviceKey: String, from: Long, to: Long): List<RecordRow> {
        val t0 = System.nanoTime()
        val table = DeviceRegistry.tableFor(deviceKey)
        val primary = DeviceRegistry.byKey(deviceKey)?.primaryMetric ?: "value"
        val sql = "SELECT $COL_SEQ, $COL_AT, $COL_VALUE, ${PhysioDatabase.COL_READING_ID} FROM $table " +
            "WHERE $COL_METRIC = ? AND $COL_AT BETWEEN ? AND ? ORDER BY $COL_AT ASC"
        val out = ArrayList<RecordRow>()
        helper.readableDatabase.rawQuery(sql, arrayOf(primary, from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(RecordRow(c.getLong(0), c.getLong(1), c.getInt(2), deviceKey, c.getLong(3)))
            }
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.d(TAG, "取时间窗 $from..$to（设备=$deviceKey）：${out.size} 条，耗时=${ms}ms")
        return out
    }

    /** 删除某台设备的全部记录；返回删掉几条 */
    fun deleteByDevice(deviceKey: String): Int {
        val table = DeviceRegistry.tableFor(deviceKey)
        val n = helper.writableDatabase.delete(table, null, null)
        Log.d(TAG, "清空设备=$deviceKey：删除 $n 条")
        return n
    }

    /** 数一数某台设备现在一共几条记录 */
    fun countAll(deviceKey: String): Int {
        val t0 = System.nanoTime()
        val table = DeviceRegistry.tableFor(deviceKey)
        val n = helper.readableDatabase.rawQuery("SELECT COUNT(DISTINCT $COL_AT) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.d(TAG, "统计总数=$n（设备=$deviceKey）：耗时=${ms}ms，线程=${Thread.currentThread().name}")
        return n
    }

    /** 按读数序号删一条（同一序号的多个指标行一起删）；返回删掉几行 */
    fun deleteReading(deviceKey: String, readingId: Long): Int {
        val table = DeviceRegistry.tableFor(deviceKey)
        val n = helper.writableDatabase.delete(
            table, "${PhysioDatabase.COL_READING_ID}=?", arrayOf(readingId.toString())
        )
        Log.d(TAG, "按序号删（设备=$deviceKey，序号=$readingId）：$n 行")
        return n
    }

    /** 按读数序号改某个指标的值；返回改了几行 */
    fun updateReadingValue(deviceKey: String, readingId: Long, metric: String, value: Int): Int {
        val values = ContentValues().apply { put(COL_VALUE, value) }
        return helper.writableDatabase.update(
            DeviceRegistry.tableFor(deviceKey), values,
            "${PhysioDatabase.COL_READING_ID}=? AND $COL_METRIC=?",
            arrayOf(readingId.toString(), metric)
        )
    }

    /** 按时间范围删（含两端）；返回删掉几行 */
    fun deleteBetween(deviceKey: String, from: Long, to: Long): Int {
        val table = DeviceRegistry.tableFor(deviceKey)
        val n = helper.writableDatabase.delete(table, "$COL_AT BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()))
        Log.d(TAG, "按时间范围删（设备=$deviceKey，$from..$to）：$n 行")
        return n
    }

    // ===== 界面自建设备 =====

    /** 读出自建设备并还原成运行时设备对象 */
    fun customDevices(): List<Device> {
        val sql = "SELECT ${PhysioDatabase.COL_CUSTOM_KEY}, ${PhysioDatabase.COL_CUSTOM_ALIAS}, " +
            "${PhysioDatabase.COL_CUSTOM_MODEL}, ${PhysioDatabase.COL_CUSTOM_KIND}, " +
            "${PhysioDatabase.COL_CUSTOM_HOST}, ${PhysioDatabase.COL_CUSTOM_PORT}, " +
            "${PhysioDatabase.COL_CUSTOM_MAC}, ${PhysioDatabase.COL_CUSTOM_METRIC} " +
            "FROM ${PhysioDatabase.TABLE_CUSTOM_DEVICES} ORDER BY ${PhysioDatabase.COL_CUSTOM_CREATED} ASC"
        val out = ArrayList<Device>()
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                val kind = runCatching { ChannelKind.valueOf(c.getString(3)) }.getOrDefault(ChannelKind.WIFI_TCP)
                val address = when (kind) {
                    ChannelKind.WIFI_TCP -> ChannelAddress.Network(
                        c.getString(4) ?: "127.0.0.1", c.getInt(5).takeIf { it > 0 } ?: 8899
                    )
                    ChannelKind.CLASSIC_BLUETOOTH, ChannelKind.BLE ->
                        ChannelAddress.Bluetooth(c.getString(6) ?: "00:00:00:00:00:00")
                    ChannelKind.USB_SERIAL -> ChannelAddress.Usb(0, 0)
                    else -> ChannelAddress.None
                }
                out.add(
                    Device(
                        id = key,
                        alias = c.getString(1),
                        model = c.getString(2),
                        primaryMetric = c.getString(7) ?: "value",
                        metrics = listOf(c.getString(7) ?: "value"),
                        channels = listOf(
                            Channel(id = "$key-ch", kind = kind, address = address, note = "界面自建")
                        )
                    )
                )
            }
        }
        return out
    }

    /** 界面添加一台设备：落库 + 建它的采集表；返回是否成功 */
    fun insertCustomDevice(device: Device): Boolean {
        val channel = device.channels.firstOrNull() ?: return false
        val values = ContentValues().apply {
            put(PhysioDatabase.COL_CUSTOM_KEY, device.id)
            put(PhysioDatabase.COL_CUSTOM_ALIAS, device.alias)
            put(PhysioDatabase.COL_CUSTOM_MODEL, device.model)
            put(PhysioDatabase.COL_CUSTOM_KIND, channel.kind.name)
            when (val a = channel.address) {
                is ChannelAddress.Network -> {
                    put(PhysioDatabase.COL_CUSTOM_HOST, a.host)
                    put(PhysioDatabase.COL_CUSTOM_PORT, a.port)
                }
                is ChannelAddress.Bluetooth -> put(PhysioDatabase.COL_CUSTOM_MAC, a.mac)
                else -> Unit
            }
            put(PhysioDatabase.COL_CUSTOM_METRIC, device.primaryMetric)
            put(PhysioDatabase.COL_CUSTOM_CREATED, System.currentTimeMillis())
        }
        val ok = helper.writableDatabase.insertWithOnConflict(
            PhysioDatabase.TABLE_CUSTOM_DEVICES, null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (ok) {
            helper.writableDatabase.execSQL(PhysioDatabase.createDeviceTableSql(device.tableName))
        }
        return ok
    }
}
