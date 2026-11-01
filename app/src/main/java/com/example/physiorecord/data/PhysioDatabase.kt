package com.example.physiorecord.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 数据库：一张"设备登记表" + 每台设备一张"设备数据采集表"。
 *
 * 采集表只存三样：自增序号、时刻、原始值。
 *   seq   自增序号，只用于稳定排序（判断"有没有新的一笔"要用时刻，不能用它）
 *   at    时刻（毫秒时间戳），同一台设备内唯一
 *   value 设备给的原始值，不做任何加工
 *
 * 版本 2 的变化：从"一张 records 装所有设备"改成"每设备一张表"。
 * 旧表 records 不删，只搬一次，留作回退；迁移时同一时刻只保留一条。
 */
class PhysioDatabase(context: Context) :
    SQLiteOpenHelper(context, "physio.db", null, DB_VERSION) {

    companion object {
        private const val TAG = "Storage"

        /** 数据库结构版本；1=旧单表，2=每设备一表，3=补建新设备表，4=多指标，5=图表定义，6=补建后来登记的设备（网络设备） */
        const val DB_VERSION = 12

        /** 旧版单表：保留不删，迁移完仍可回退 */
        const val LEGACY_TABLE = "records"

        const val TABLE_DEVICES = "devices"
        const val COL_DEVICE_KEY = "device_key"
        const val COL_DISPLAY_NAME = "display_name"
        const val COL_TRANSPORT = "transport"
        const val COL_TABLE_NAME = "table_name"

        const val COL_SEQ = "seq"
        const val COL_AT = "at"
        const val COL_METRIC = "metric"
        const val COL_VALUE = "value"

        /** 图表定义表：定义是独立对象，删它不动原始记录 */
        const val TABLE_CHART_DEFS = "chart_defs"
        const val COL_DEF_FORMULA = "formula"
        const val COL_DEF_ID = "id"
        const val COL_DEF_NAME = "name"
        const val COL_DEF_DEVICE = "device_key"
        const val COL_DEF_METRICS = "metric_keys"
        const val COL_DEF_TYPE = "chart_type"
        const val COL_DEF_CREATED = "created_at"

        /** 下发命令历史：一次下发就是一行，状态从 PENDING 走到 APPLIED/REJECTED/NO_RECEIPT */
        const val TABLE_COMMANDS = "commands"
        const val COL_CMD_ID = "id"
        const val COL_CMD_DEVICE = "device_key"
        const val COL_CMD_PAYLOAD = "payload"
        const val COL_CMD_STATUS = "status"
        const val COL_CMD_REASON = "reason"
        const val COL_CMD_RECEIPT = "receipt"
        const val COL_CMD_CREATED = "created_at"
        const val COL_CMD_UPDATED = "updated_at"

        /** 读数序号：同一时刻的多指标行共用同一个 reading_id，界面按它定位“一条” */
        const val COL_READING_ID = "reading_id"

        /** 界面自建的设备：登记一次，重启仍在 */
        const val TABLE_CUSTOM_DEVICES = "custom_devices"
        const val COL_CUSTOM_KEY = "device_key"
        const val COL_CUSTOM_ALIAS = "alias"
        const val COL_CUSTOM_MODEL = "model"
        const val COL_CUSTOM_KIND = "channel_kind"
        const val COL_CUSTOM_HOST = "host"
        const val COL_CUSTOM_PORT = "port"
        const val COL_CUSTOM_MAC = "mac"
        const val COL_CUSTOM_METRIC = "metric"
        const val COL_CUSTOM_CREATED = "created_at"

        /** 一台设备一张采集表：一个时刻的一个指标一行；同刻同指标只留一条 */
        fun createDeviceTableSql(table: String): String = """
            CREATE TABLE IF NOT EXISTS $table (
                $COL_SEQ    INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_AT     INTEGER NOT NULL,
                $COL_METRIC TEXT    NOT NULL,
                $COL_VALUE  INTEGER NOT NULL,
                $COL_READING_ID INTEGER,
                UNIQUE($COL_AT, $COL_METRIC)
            )
        """.trimIndent()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createDevicesTable(db)
        createChartDefsTable(db)
        createCommandsTable(db)
        createCustomDevicesTable(db)
        for (device in DeviceRegistry.all()) {
            createDeviceTable(db, device)
            seedDevice(db, device)
        }
    }

    /**
     * 每次打开库时补建登记表里新设备的采集表（幂等）。
     * 这样“加一台设备”不再必须升 DB_VERSION：登记表加一行，打开即建表。
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        for (device in DeviceRegistry.all()) {
            createDeviceTable(db, device)
            seedDevice(db, device)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            Log.d(TAG, "升级 1→2：建设备登记表、建采集表、搬旧数据（旧表保留）")
            createDevicesTable(db)
            for (device in DeviceRegistry.all()) {
                createDeviceTable(db, device)
                seedDevice(db, device)
            }
            migrateLegacyRecords(db, DeviceRegistry.default())
        }
        if (oldVersion < 3) {
            // 登记表里新增了设备：把缺的表补上，已有数据不动
            Log.d(TAG, "升级 2→3：补建新登记设备的采集表")
            for (device in DeviceRegistry.all()) {
                createDeviceTable(db, device)
                seedDevice(db, device)
            }
        }
        if (oldVersion < 4) {
            Log.d(TAG, "升级 3→4：采集表加指标列，旧行按主指标回填")
            for (device in DeviceRegistry.all()) upgradeToMetricTable(db, device)
        }
        if (oldVersion < 5) {
            Log.d(TAG, "升级 4→5：建图表定义表")
            createChartDefsTable(db)
        }
        if (oldVersion < 6) {
            Log.d(TAG, "升级 5→6：补建后来登记设备的采集表（网络设备）")
            for (device in DeviceRegistry.all()) {
                createDeviceTable(db, device)
                seedDevice(db, device)
            }
        }
        if (oldVersion < 7) {
            Log.d(TAG, "升级 6→7：补建后来登记设备的采集表（蓝牙串口设备）")
            for (device in DeviceRegistry.all()) {
                createDeviceTable(db, device)
                seedDevice(db, device)
            }
        }
        if (oldVersion < 8) {
            Log.d(TAG, "升级 7→8：补建后来登记设备的采集表（低功耗蓝牙设备）")
            for (device in DeviceRegistry.all()) {
                createDeviceTable(db, device)
                seedDevice(db, device)
            }
        }
        if (oldVersion < 9) {
            Log.d(TAG, "升级 8→9：补建后来登记设备的采集表（USB 串口设备）")
            for (device in DeviceRegistry.all()) {
                createDeviceTable(db, device)
                seedDevice(db, device)
            }
        }
        if (oldVersion < 10) {
            Log.d(TAG, "升级 9→10：图表定义补 formula 列")
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_CHART_DEFS ADD COLUMN $COL_DEF_FORMULA TEXT")
            }.onFailure { Log.w(TAG, "补 formula 列失败：${it.message}") }
        }
        if (oldVersion < 11) {
            Log.d(TAG, "升级 10→11：建下发命令历史表")
            createCommandsTable(db)
        }
        if (oldVersion < 12) {
            Log.d(TAG, "升级 11→12：自建设备表 + 每条读数补自增序号")
            createCustomDevicesTable(db)
            for (device in DeviceRegistry.all()) upgradeAddReadingId(db, device)
        }
    }

    private fun createCustomDevicesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CUSTOM_DEVICES (
                $COL_CUSTOM_KEY     TEXT PRIMARY KEY,
                $COL_CUSTOM_ALIAS   TEXT NOT NULL,
                $COL_CUSTOM_MODEL   TEXT NOT NULL,
                $COL_CUSTOM_KIND    TEXT NOT NULL,
                $COL_CUSTOM_HOST    TEXT,
                $COL_CUSTOM_PORT    INTEGER,
                $COL_CUSTOM_MAC     TEXT,
                $COL_CUSTOM_METRIC  TEXT NOT NULL,
                $COL_CUSTOM_CREATED INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** 旧表补 reading_id：按时刻从旧到新编 1..N（同一时刻的多指标行共用一个序号） */
    private fun upgradeAddReadingId(db: SQLiteDatabase, device: Device) {
        val table = device.tableName
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $COL_READING_ID INTEGER") }
        val ats = ArrayList<Long>()
        runCatching {
            db.rawQuery("SELECT DISTINCT $COL_AT FROM $table ORDER BY $COL_AT ASC", null).use { c ->
                while (c.moveToNext()) ats.add(c.getLong(0))
            }
        }
        for ((i, at) in ats.withIndex()) {
            db.execSQL(
                "UPDATE $table SET $COL_READING_ID=? WHERE $COL_AT=? AND $COL_READING_ID IS NULL",
                arrayOf<Any>(i + 1, at)
            )
        }
    }

    /** 图表定义：给人起名、指向一台设备和它的一组指标，图型单独存；删定义不动原始记录 */
    private fun createChartDefsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CHART_DEFS (
                $COL_DEF_ID       INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DEF_NAME     TEXT NOT NULL,
                $COL_DEF_DEVICE   TEXT NOT NULL,
                $COL_DEF_METRICS  TEXT NOT NULL,
                $COL_DEF_TYPE     TEXT NOT NULL,
                $COL_DEF_FORMULA  TEXT,
                $COL_DEF_CREATED  INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** 3→4：重建表（旧 UNIQUE(at) 不能就地改），旧行按该设备的主指标回填，序号保留 */
    private fun upgradeToMetricTable(db: SQLiteDatabase, device: Device) {
        val t = device.tableName
        if (!tableExists(db, t)) {
            createDeviceTable(db, device)
            return
        }
        val tmp = "${t}_v4"
        db.execSQL(createDeviceTableSql(tmp))
        db.execSQL(
            "INSERT INTO $tmp ($COL_SEQ, $COL_AT, $COL_METRIC, $COL_VALUE) " +
                "SELECT $COL_SEQ, $COL_AT, '${device.primaryMetric}', $COL_VALUE FROM $t"
        )
        val n = rawCount(db, tmp)
        db.execSQL("DROP TABLE $t")
        db.execSQL("ALTER TABLE $tmp RENAME TO $t")
        Log.d(TAG, "升级 3→4：设备 ${device.id} 回填 $n 行（指标=${device.primaryMetric}）")
    }

    private fun createDevicesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DEVICES (
                $COL_DEVICE_KEY  TEXT PRIMARY KEY,
                $COL_DISPLAY_NAME TEXT NOT NULL,
                $COL_TRANSPORT   TEXT NOT NULL,
                $COL_TABLE_NAME  TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
    }

    private fun createCommandsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_COMMANDS (
                $COL_CMD_ID      INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CMD_DEVICE  TEXT NOT NULL,
                $COL_CMD_PAYLOAD TEXT NOT NULL,
                $COL_CMD_STATUS  TEXT NOT NULL,
                $COL_CMD_REASON  TEXT,
                $COL_CMD_RECEIPT TEXT,
                $COL_CMD_CREATED INTEGER NOT NULL,
                $COL_CMD_UPDATED INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createDeviceTable(db: SQLiteDatabase, device: Device) {
        db.execSQL(createDeviceTableSql(device.tableName))
    }

    private fun seedDevice(db: SQLiteDatabase, device: Device) {
        val values = ContentValues().apply {
            put(COL_DEVICE_KEY, device.id)
            put(COL_DISPLAY_NAME, device.alias)
            put(COL_TRANSPORT, device.channelSummary())
            put(COL_TABLE_NAME, device.tableName)
        }
        db.insertWithOnConflict(TABLE_DEVICES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * 把旧单表的数据搬进默认设备的采集表。
     * - 旧表不存在就跳过（全新安装的升级路径不会走到这里，但兜一下底）
     * - 按 seq 升序搬；同一时刻重复的，INSERT OR IGNORE 只留第一条
     * - 旧表不删，留作回退
     */
    private fun migrateLegacyRecords(db: SQLiteDatabase, device: Device) {
        if (!tableExists(db, LEGACY_TABLE)) {
            Log.d(TAG, "旧表不存在，跳过迁移")
            return
        }
        db.execSQL(
            """
            INSERT OR IGNORE INTO ${device.tableName} ($COL_AT, $COL_VALUE)
            SELECT $COL_AT, $COL_VALUE FROM $LEGACY_TABLE ORDER BY $COL_SEQ ASC
            """.trimIndent()
        )
        val moved = rawCount(db, device.tableName)
        val legacy = rawCount(db, LEGACY_TABLE)
        Log.d(TAG, "迁移完成：旧表 $legacy 条，新表 $moved 条（同刻重复已合并）")
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun rawCount(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}
