# OPPower 优化报告（beta29）

仓库：`https://github.com/aaaaaaaax1/OPPower`
基线：`1.0.0-beta27`（仓库当前 HEAD）
本轮目标版本：`1.0.0-beta29`
补丁：`OPPower-beta29.patch`（4617 行，含 beta28 + beta29 全部改动）
验证：90 项 JVM 单测通过 · lint 0 error / 20 warning · Release R8 混淆通过

---

## 一、总览

两轮改动合计涉及 **24 个文件**，新增 **5 个文件**、**+1449 / -770 行**（不含新建文件）。按优先级：

| 级别 | 数量 | 内容 |
| --- | --- | --- |
| P0 崩溃/失效 | 2 | 前台服务重复 `startForeground` 崩溃、安全恢复中断即丢失 |
| P1 体验/正确性 | 8 | 空闲耗电、零值纠错窗口、Shizuku 重连、存储统计、正则常量、文档漂移等 |
| P2 功能新增 | 3 大块 | 充电检测、COUI 界面、设置扩展（10 项，全部真实接入） |

---

## 二、P0：两个会让功能「静默失效」的缺陷

### 1. 前台服务生命周期崩溃（`ChargeControlService.kt`）

关闭最后一个充电增强开关时，`syncStatusNotification` 会 `stopForeground()` 移除通知，但**没有清除本地 `foregroundStarted` 标记**。下一轮控制周期再次调用 `startForeground()` 时，Android 12+ 会抛出 `ForegroundServiceStartNotAllowedException`，控制循环永久丢帧——用户看到的是「开关开着但完全没效果」。

**修复**：通知只在前台服务确实需要展示时更新，用 `hasActiveControlTarget(settings)` 统一判断；移除通知时复位 `foregroundStarted`。

### 2. 安全恢复在进程回收后中断（`ChargeControlService.kt`）

「关闭最后一个开关」时若 `setChargingLevel 2` 恢复失败，原实现直接 `startControlLoop()`，而 `onStartCommand` 返回 `START_NOT_STICKY`。进程被系统回收后安全恢复就断了，设备可能停留在未恢复的充电档位。

**修复**：失败分支先以「正在恢复系统默认」重新进入前台，再保留重试。任何设置变更、屏幕事件、接电广播仍能立即唤醒。

---

## 三、P1：一致性与耗电

| 问题 | 位置 | 处理 |
| --- | --- | --- |
| 所有开关关闭后仍每秒读电池 + 起 `settings` 子进程 | `ChargeControlService` | 加入 `idleBackoff`，纯等待 1 秒；事件驱动立即唤醒 |
| 安全恢复失败后息屏仍每秒重试 Binder + dumpsys | `ChargeControlService` | 增加 `retryingSafetyRestore && screenOn` 条件，息屏暂停、亮屏续上 |
| 实时通知让「关闭控制」仍每 180 秒写曲线库 | 同上 | 抽出 `shouldRecordHistory()`，只在总开关或保活开启时写库 |
| 电流零值纠错窗口 5.0 s，与文档约定的 3.5 s 不符 | `BatteryReader` | `CurrentSampleStabilizer(holdMillis = 3_500L)` |
| Shizuku 重启后不自动重连，要等下一次命令触发 | `ShizukuGateway` | 注册 `addBinderReceivedListenerSticky` / `addBinderDeadListener` |
| 「本地数据」占用不计 24h 曲线库 | 设置页 | 补入 `databases/chart-history-24h.db` 及 WAL/SHM |
| 每秒控制周期重复编译正则 | `ChargingController` / `GearCalculator` | 提升为 `companion object` 常量；顺带去掉 `!!` |
| `docs/OPERATIONS.md:549` 写白名单允许 `0/2/3/5/7`，实际只有 `0/2` | 文档 | **未改**，需你确认以哪边为准 |

---

## 四、充电检测（参考 Scene）

### 设计取舍

Scene 的充电页之所以好用，是因为它**按用户的追问顺序排信息**，而不是按数据源排：

1. **充得多快** —— 实时功率、协议、电压电流、充电阶段、预计充满
2. **为什么慢** —— 充电器能给多少 vs 手机实际用多少、利用率、异常归因
3. **电池健康** —— 满充容量、设计容量、健康度、循环次数

OPPower 的检测卡严格按这个顺序组织。最上层是电量/电压/电流/功率四个大字指标，协议与阶段作为标签紧跟其下。

### 实现要点

`battery/ChargeInspector.kt`（424 行，纯 Kotlin，无 Android 依赖，可完整单测）：

- **数据来源**：`sysfs/power_supply` 只读快照。通过 Shizuku user service 新增白名单命令 `readPowerSupplyDump()` 一次取回 `battery/usb/main/charger/pc_port/wired/wireless/otg` 各节点的 `voltage_max`、`current_max`、`charge_full`、`charge_full_design`、`cycle_count`、`temp`、`usb_type`、`online` 等字段。**只读，不写任何节点**。
- **解析健壮性**：`PowerSupplyDump.parse()` 处理 `[dir] name` + `k=v` 格式，坏行只丢该行不影响整包；`normalizeMicro()` 按量级把 µV/µA/µAh 归一化到 mV/mA/mAh —— 不同机型单位差 1000 倍，这个坑绕不过去。
- **充电阶段判定**：`HANDSHAKE` → `CONSTANT_CURRENT` → `CONSTANT_VOLTAGE`（4.36 V 阈值，双芯翻倍）→ `TRICKLE`（< 150 mA 且 ≥ 95%）→ `FULL` / `DISCONNECTED`。
- **剩余时间**：斜率法，取相邻电量增量的中位数换算 %/min；低于 0.05 %/min 直接说明「电流过小，无法预估」，**不编数字**。
- **稳定性**：采样窗口内电流变异系数映射为 0–100，用来区分「快充协商跳档」和「线材接触不良」。
- **异常归因**：利用率 < 35%、温度 ≥ 42 ℃、恒压后仍在拉大电流、普通 USB 协议却电流异常高、健康度 < 80%。
- **双读互斥**：前台由 ViewModel 读、息屏由服务读。`ChargeControlBus.pageVisible` 保证界面可见时服务侧跳过，避免同秒两次 Shizuku 调用。

14 项单测覆盖解析、协议识别、健康度、阶段、剩余时间、稳定性、效率、异常归因。

---

## 五、界面：COUI-Kit 规范

### 为什么没有直接依赖

`COUI-Kit` 的 `AarMetadata` 硬性要求：

```
compileSdk 37.1 · minAgpVersion 9.1.0 · Kotlin 2.4.20 · Compose 1.13 · minSdk 31
```

本项目基线是 AGP 8.10.1 / SDK 36 / Kotlin 2.1.21 / minSdk 26。直接引入意味着**整个工程升级到尚不稳定的工具链**，风险与收益完全不成比例。

**选择**：按该库公开的度量规范，在 `ui/CouiUi.kt`（564 行）独立实现等价组件，并在许可页署名（MPL 2.0）。

### 度量令牌

| 项 | 值 | 项 | 值 |
| --- | --- | --- | --- |
| 页面左右 | 16dp | 卡片圆角 | 24dp 连续 |
| 行内左右 | 16dp | 分隔线 | 0.33dp |
| 行高 | 48dp（紧凑 42dp） | 组标题内缩 | 32dp |
| 开关 | 44×24，滑块 18dp，边距 3dp | 分段选择器 | 38dp |
| ItemTitle | 16sp / Medium | ItemSummary | 13sp |
| ItemValue | 14sp | GroupTitle / Footer | 12sp |

### 组件与交互

`CouiPage`、`CouiCard`、`CouiGroup`、`CouiRow`、`CouiDivider`、`CouiSwitchRow`、`CouiSwitch`、`CouiSegmented`、`CouiChoiceRow`、`CouiSearchField`、`CouiStatusDot`、`CouiPageHeader`。

- 开关带触感反馈与弹簧动画（`dampingRatio = 0.72`）
- 分段指示器跟手插值，点击即提交
- 主页面分组标题同步改为 COUI 组标题样式，去掉原来的彩色竖条
- 所有二级页统一 `CouiPage` 外壳：返回圆钮 + 26sp 大标题 + 统一安全区

---

## 六、设置扩展：10 项，每一项都真实接上功能

用户明确要求「不要空开关」。逐项核对如下（新增项没有一项是只存不用）：

| 新增项 | 接入位置 | 实际行为 |
| --- | --- | --- |
| 设置搜索 | `SettingsHomePage` | 按标题、摘要、`keywords` 过滤 5 个分组，空结果给提示 |
| 充电检测开关 | `MainViewModel.refreshInsight` / 服务 `maybeRunDetection` | 关闭后不再读 `power_supply`，检测卡隐藏 |
| 检测间隔 3–30 s | 同上 | 真实改变服务与前台刷新节奏 |
| 异常提醒 | `ChargeControlService.postAlert` | 命中异常发独立通道通知，同一异常不重复轰炸 |
| 充满提醒 | 同上 | 进入 `FULL` 后发一次 |
| 显示电池健康 | `ChargeDetectCard` | 控制健康度区段显隐 |
| 通知样式（极简/标准/详细） | `notificationBatteryData` | 三档裁剪：只留电量 / 加功率 / 加温度与阶段 |
| 列表密度 | `ProvideCouiMetrics` → `LocalCouiMetrics` | 紧凑 42dp + 字号收一档，全页面生效 |
| 导出为 CSV | `ChartHistoryStore.exportCsv` + FileProvider | 24h 曲线写 `cache/export/`，`ACTION_SEND` 分享，带二次确认 |
| 重置全部设置 | `PreferencesStore.resetAll` | 恢复默认并保留首次引导标记，带二次确认 |

界面密度通过 `CompositionLocal` 在根布局注入一次，改设置即全页生效，无需重启 Activity。

---

## 七、验证结果

```
:app:testDebugUnitTest   90 tests, 0 failures, 0 errors, 0 skipped
:app:lintDebug           0 errors, 20 warnings, 1 hint（均为存量项）
:app:assembleRelease     BUILD SUCCESSFUL，R8 混淆通过
```

Release 产物：`app/build/outputs/apk/release/app-release-unsigned.apk`
大小 1,654,666 bytes
SHA-256 `2A9832F677EB6AA33DDE28F7B8141BECF3B45930DF43ED74A5F5FF02156148A2`

### 仍需你在真机上确认

这些是代码级无法替代的验证：

1. `power_supply` 各字段在 RMX8899 上是否真的可读，尤其 `charge_full` / `charge_full_design` / `cycle_count` 是否存在
2. 充电阶段阈值（4.36 V、150 mA）在该机型的实际表现
3. 预计充满时间在真实充电中的误差范围
4. 检测间隔对续航的影响（建议对比 5 秒与 30 秒两档的 `dumpsys batterystats`）
5. 新界面在深色/浅色、六套配色、三档密度下的渲染效果

---

## 八、遗留与建议

- `docs/OPERATIONS.md:549` 的白名单描述（`0/2/3/5/7`）与实际代码（`0/2`）不符，属于第一轮就发现的文档漂移，我没有擅自判定哪边正确 —— 如果当初收缩白名单是有意为之，改文档；反之需要补回命令。
- 检测用的 `readPowerSupplyDump()` 走一次 Shizuku Binder 往返读 8 个目录，比读单个 `BatteryManager` 重。若真机上 3 秒间隔被发现耗电偏高，建议把默认间隔改为 5 秒，并把目录裁剪到 `battery/usb/main` 三个。
- `ChargeInspector` 目前是纯函数式分析，没有做跨采样历史平滑。若真机上阶段判定抖动明显，可以在 ViewModel 侧加 2–3 帧滞回，而不必改分析核心。
