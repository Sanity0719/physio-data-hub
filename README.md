# 生理数据集散中心 · Android 客户端

> 《智能终端系统及应用开发》课程设计。把手机做成一台**生理数据集散中心**：多种设备/通道的数据统一收进来，按设备分开长期存住，再按用户定义画出去、导出去、发回去。

- 语言：Kotlin（单 Activity + Fragment）
- 构建：Gradle Kotlin DSL，`minSdk 24` / `compileSdk 37` / `targetSdk 37`
- 图表：MPAndroidChart v3.1.0
- 存储：SQLite（原生 `SQLiteOpenHelper`，不引 ORM）

---

## 一、功能总览

| 模块 | 能力 |
| --- | --- |
| 设备接入 | 六种通道：本机模拟、WiFi/TCP、WiFi/UDP、经典蓝牙（SPP）、低功耗蓝牙（GATT）、USB 串口；设备可改别名；可配多台设备；掉线自动重连（最多 3 次、间隔 1.5 s） |
| 采集 | 手动"读一次"；前台服务按 10 s 周期自动采集并写库，锁屏/切后台照常 |
| 存储 | 设备登记表 + 每台设备一张采集表；同一时刻重复写入幂等跳过；旧单表数据一次性迁移；数据库版本 12 |
| 数据管理 | 补一笔、按条查看/改值/删除、按范围删除、清空当前设备（二次确认）、CSV 导出/导入（坏行计数、同刻跳过） |
| 可视化 | 折线 / 柱状 / 饼图三种图型；图表定义（名称、指标集合、图型、公式）落库，可多套切换；公式支持四则运算与括号，饼图支持 `sum()` / `avg()` 聚合成块 |
| 命令下发 | 文本命令下行，三态结果：`APPLIED`（收到回执）/ `REJECTED`（连接或发送失败）/ `NO_RECEIPT`（发出但未等到回话）；每次下发留一行历史 |
| 局域网互动 | 手机监听 8888 端口，收到一行回一行；也可反过来主动连到 PC 发一句读一句 |

## 二、架构与数据流

```
界面（Fragment）
   │  提交命令 / 读数据
   ▼
DeviceHub ──► 每设备一条串行队列（DeviceQueue）──► 通道会话（LinkSession）
   │                                              ├─ TCP：长连接 + 读线程
   │                                              ├─ BLE：GATT 发现服务 → 可写可通知特征 → 开通知
   │                                              ├─ SPP：RFCOMM，连接限时兜底
   │                                              └─ USB：Host 找设备 → 授权 → claimInterface → 批量端点
   ▼
CaptureSource（每设备一个数据源）──► 读数（一条读数可带多项指标）
   ▼
PhysioRepository（唯一读写入口，写库单线程）──► SQLite：devices / device_data_<key> / chart_defs / commands
   ▼
界面：状态卡、数据列表、图表、命令历史
```

三种触发都汇到同一条路上：**手动读一次**、**后台服务周期采集**、**命令下发**，都由 `DeviceHub` 按设备排队执行，结果从同一条回调线回到界面。

## 三、设备与通道

通道只描述"怎么连"，不描述"连的是谁"——一台设备可以挂多条通道，加一种连接方式只需要加一个 `ChannelKind` 和一个地址类型，`DeviceRegistry` 里的设备定义不动。

| 通道 | 地址形式 | 实现要点 |
| --- | --- | --- |
| 本机模拟 `BUILTIN` | 无 | 手机传感器读数，用于没有外设时跑通链路 |
| WiFi/TCP | host + port | 长连接 + 独立读线程；读超时 5000 ms、单行上限 1024 B；对端关闭即判失败 |
| WiFi/UDP | host + port | 无连接收发，按包解析 |
| 经典蓝牙 `CLASSIC_BLUETOOTH` | MAC | RFCOMM；`connect()` 没有超时参数，用限时等待 + 超时关闭兜住 |
| 低功耗蓝牙 `BLE` | MAC | GATT：连接 → 发现服务 → 选可写且可通知的特征 → 打开通知 → 收包入队 |
| USB 串口 `USB_SERIAL` | vendorId + productId | USB Host，不引三方库；权限用一次性广播，收到授权再打开 |

## 四、数据模型（`physio.db`，版本 12）

| 表 | 字段 | 说明 |
| --- | --- | --- |
| `devices` | `device_key`, `display_name`, `transport`, `table_name` | 设备登记表。`device_key` 一旦写入不再变，表名只由它派生 |
| `device_data_<key>` | `seq`（自增）, `at`（毫秒）, `metric`（指标名）, `value`（原始值） | 每台设备一张。`seq` 只用于稳定排序；`at` 上有唯一约束 |
| `chart_defs` | `id`, `name`, `device_key`, `metric_keys`, `chart_type`, `formula`, `created_at` | 一条可视化定义；`formula` 可多行，每行定义一个量 |
| `commands` | `id`, `device_key`, `payload`, `status`, `reason`, `receipt`, `created_at` | 下发历史；`status` 走三态 |

按设备分表的原因：共用一张表时，任何一台设备的量级变化都会拖累其它设备的查询；分表后查询天然带"设备"这一维，索引也短。打开数据库时按登记表补建缺的表，所以**加设备不需要升版本号**。

## 五、界面

| 页面 | 职责 |
| --- | --- |
| 设备（`DevicesFragment`） | 状态卡：当前设备、共几笔、最近一笔；功能宫格 |
| 设备列表 / 添加设备 | 多设备的增删与切换、别名编辑 |
| 连接设置（`LinkSettingsFragment`） | 按当前设备的通道类型显示参数（TCP 地址端口 / 蓝牙 MAC / USB 厂商与产品号） |
| 远程设备（`RemoteDevicesFragment`） | 局域网里发现的设备 |
| 数据管理（`ManageFragment`） | 补一笔、改/删最近一条、按范围删、按条查看、导出/导入 CSV、清空 |
| 数据列表（`DataListFragment`） | 分页查看全部读数，按序号改值、删除 |
| 可视化（`VisualizeFragment`） | 按当前图表定义画图，附一行口径摘要 |
| 定制图表（`CustomizeFragment`） | 编辑图表定义：名称、指标集合、图型、公式，可存多套 |
| 命令中心（`CommandCenterFragment`） | 发一条文本命令，看回执与历史 |

## 六、构建与运行

```bash
# 1) 环境：JDK 17+，Android SDK（platform 37 / 对应 Build-Tools）
# 2) 构建
./gradlew assembleDebug
# 产物： app/build/outputs/apk/debug/app-debug.apk
```

- 命令行构建若卡在依赖下载，检查 `gradle.properties` 里的代理参数（`http.proxyHost` 等）是否与当前网络匹配。
- Windows 下双击 `启动模拟器.bat` 可开一个名为 `Medium_Phone` 的 AVD（脚本里的 SDK 路径按本机位置改）。
- 与 PC 联调（TCP 通道）：手机与 PC 同一网段；`adb forward tcp:8888 tcp:8888` 后 PC 侧连 `127.0.0.1:8888`，发一行、回一行。默认端口 8888。
- USB 串口通道需要 OTG 转接与真实串口设备；两条蓝牙通道需要对应外设配合。

## 七、目录结构

```
app/src/main/java/com/example/physiorecord/
├── MainActivity.kt          单 Activity 容器，页面进出都走它
├── capture/                 采集与通道
│   ├── CaptureSource.kt         数据源接口（读一次 / 一次多项指标 / 回包解析）
│   ├── PhoneSensorSource.kt     本机模拟源
│   ├── DemoBandSource.kt        演示设备（读一次慢 2 s，用来验证队列隔离）
│   ├── TcpSensorSource.kt       TCP 源
│   ├── BleSensorSource.kt       BLE 源
│   ├── SppSensorSource.kt       经典蓝牙源
│   ├── UsbSensorSource.kt       USB 串口源
│   ├── LinkSession.kt           会话契约（连接 / 断开 / 发一行读一行）
│   ├── TcpLinkSession.kt / BleLinkSession.kt / SppLinkSession.kt / UsbLinkSession.kt
│   ├── DeviceHub.kt             设备命令的唯一入口
│   ├── DeviceQueue.kt           每设备一条串行队列
│   ├── DeviceSources.kt         按登记表造数据源（加设备只改登记表）
│   ├── DeviceCommand.kt         命令与结果模型
│   └── CaptureService.kt        前台服务：10 s 周期采集 + 常驻通知
├── data/                    存储与模型
│   ├── DeviceModel.kt           通道种类、地址、设备定义
│   ├── DeviceRegistry.kt        设备清单（身份 + 通道）
│   ├── PhysioDatabase.kt        建表 / 迁移 / 按设备建表
│   ├── PhysioRepository.kt      唯一读写入口，写库单线程
│   └── Formula.kt               公式求值（递归下降）与饼图聚合
├── export/DeviceCsv.kt      CSV 导出与导入（表头 AT,VALUE）
├── link/                    局域网两个方向
│   ├── LanListener.kt           手机监听，收一行回一行
│   └── OutboundClient.kt        手机主动连出去，发一句读一句
└── ui/                      页面与公共控件
    ├── Modules.kt / UiKit.kt / Bg.kt / Prefs.kt
    └── 各 Fragment（见上表）
```

## 八、关键取舍

1. **接口先定、实现后补**：`CaptureSource` 只留三样成员（编号、显示名、读一次），后来加四种物理通道，采集流程与界面一行没改。
2. **一次读数可带多项指标**：第一版只带一个值，加设备时不得不改接口与表结构；现在 `readMetrics()` 一次返回多项，采集表按"每指标一行"存。
3. **幂等优先**：迁移可重复执行、同一时刻重复写入被跳过、CSV 导入同刻跳过并统计坏行——长期记录最怕"多跑一次就多一份重复数据"。
4. **线程边界**：读设备（慢）与写库在后台线程，约下一轮与更新界面在主线程；前台服务只负责按周期触发。
5. **三态命令**：把"发出去了但没回话"单列成 `NO_RECEIPT`，而不是并进成功或失败——设备没回话时结果就是未知，不能替它猜。
6. **图形口径写进摘要**：折线看趋势、柱状按读数下标对齐、饼图是窗口内各指标占比（跨量纲时在摘要里标明），避免图好看但读数错。

## 九、环境要求

- JDK 17 及以上；Android SDK Platform 37 与对应 Build-Tools
- 手机端需 Android 7.0（API 24）及以上
- 权限：前台服务与通知（持续采集）、网络（TCP/UDP 与局域网）、蓝牙（连接）、USB（设备访问，运行时授权）
