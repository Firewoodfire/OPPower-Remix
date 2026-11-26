# OPPower 真机标定与验证记录

记录日期：2026-10-22

设备：realme Neo8 RMX8899，ColorOS 17

条件：真实亮屏、AC 在线、外接功率计。除特别注明外，测试时未发送 LV，未冻结 Athena/Battery。

## `cosa_ctc_value` 条件样例

下表来自本机真机采样，仅对应采样时的热控基础档 8 和其他场景影响为 0。当前实现不使用缓存 `gearVal`；`gear` 越小表示系统允许的输出越高，但功率不是 gear 的线性函数，最终仍需以外接功率计确认。

| `cosa_ctc_value` | 当时系统 gear | `chargeVal` | 效果 |
| ---: | ---: | ---: | --- |
| `0` | 8 | 2 | 原生约 5 W |
| `-3` | 5 | 4 | 开始提升 |
| `-4` | 4 | 6 | 约 10–14 W |
| `-6` | 3 | 7 | 更高 |
| `-7` | 2 | 9 | 更高 |
| `-8` | 1 | 9 | 接近最低 gear |
| `-9` | 0 | 12 | 实测约 15–30 W，跨应用保持 |
| `-10` | 0 | -2 | 早期记录为无效哨兵；beta23 按用户要求作为基础档 10 的 gear 0 补偿，继续真机核对 |
| `-11` 及以下 | 0 | -2 | 策略字段进入无效哨兵状态，禁止使用 |

曾观察到抖音极速版前台、真实亮屏、无 LV 条件下的约 20 W 实际功率；gear 0 仍可能受充电器、线材、电池温度、SOC、原生热策略和前台应用共同限制，因此 `gear=0` 不等于固定瓦数。`setChargingLevel 0` 虽然可返回成功，但实测不能稳定突破约 20 W，不作为提功率入口。

## `chargeVal` 写入入口核查

截至 2026-10-23，未发现可直接写入 `getCurThermalPolicy.chargeVal` 的普通设置项或公开 setter。该值来自热控 XML 中当前分类的 `gear_config charge` 属性；本机当前短视频分类示例为 `tempGear 2 → 12`、`3/4 → 9`、`5 → 6`、`6 → 4`、`7 → 3`。`12` 是当前样例中的最高观测值，尚未证明是跨设备、跨策略的绝对上限。

可实现充电控制写入的路径是：

```text
dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel N
→ ThermalControlUtils.D1(N, normalCooldown, packageName)
→ android.os.OplusBatteryManager.setChargingLevel()
→ IGuardElfThermalControl.setChargeLevel()
```

直接调用底层 Binder 需要 `com.oplus.permission.safe.READ_COMMON`，OPPower 与 Shizuku shell 不能获得；`OplusBatteryService` 的 `setChargingLevel` 调试入口由系统进程代执行，因此普通 Shizuku 用户可以调用。它在未接 AC 时对 `N = 0/3/6/9/12` 均返回执行成功，但没有改变 `getCurThermalPolicy.chargeVal`。所以 `chargeVal` 是策略诊断字段，`setChargingLevel N` 是充电控制写入字段，二者不能仅凭回读值判断是否相同。

全局设置 `sys_thermal_control_list` 也已核验。该设置最大允许 32768 字符，完整热控 XML 无法直接写入；高版本最小 XML 可以写入，但 Cosa 监听只把全局字符串作为更新触发，实际策略数据仍从受 `oplus.permission.OPLUS_COMPONENT_SAFE` / `oplus.permission.RUS_WRITE_DATABASE` 保护的 ROM 更新 Provider 读取。因此直接写全局设置或补发 `oplus.intent.action.ROM_UPDATE_CONFIG_SUCCESS` 不会改变 `chargeVal`，全局设置随后已删除恢复。`com.oplus.romupdate.provider.db` 与旧 authority `com.nearme.romupdate.provider.db` 对 shell 都返回权限拒绝。

`tempGear` 是 XML 中的静态阈值行，当前分类的 `charge` 属性挂在对应 `tempGear` 行上；运行时修改该行需要替换 ROM 更新 Provider 数据，不能通过普通设置完成。隐藏调试入口 `setThermalControlTest` 和 `setTempLevel` 受 `sys.deepsleep.allow.debug` 控制，当前属性为空且 `setprop` 被 SELinux 拒绝，两个命令都返回 `cmd not allow`。设备报告 `ro.debuggable=0` 且不存在 `su`，因此当前无 root、无工程签名权限时无法执行 `tempGear charge=0` 实测。

禁止再用 `cmd thermalservice inject-temperature` 代替 `tempGear` 测试。2026-10-23 实测向电池类型注入 `SHUTDOWN` 状态后设备立即关机；`cmd thermalservice reset` 只解除框架状态覆盖，不清除已注入的温度样本，必须重启才能清空。该接口也不改变 OPlus `Thermal Levels[0]`，因此对目标无效且会触发系统安全关机。

`setChargingLevel 0` 只保留为已核实可调用的对照入口，不作为 `tempGear` 修改的替代实现。只有在获得可写 Provider、工程调试属性或 root 后，才能继续自然策略、`tempGear charge=0`、仅 `setChargingLevel 0`、两者同时开启的四组逐秒功率对照。

## 新的充电功率门控候选（2026-10-23）

`chargeVal` 本身仍不可直接写入。源码链路已经确认它是活动 `ThermalPolicy.charge` 的诊断值，再由 `rb.b.e(policy)` 原样交给 `ThermalControlUtils.D1()`。真正决定功率的是 `D1()` 收到的充电 level 与 `speedChargeAdd`，以及当前是否处于 custom/single-speed charge 路径。因此后续应调整这些下发门控，而不是尝试修改 `getCurThermalPolicy` 的只读显示值。

已用 40605 端口和 Shizuku/shell 相同身份确认以下入口：

| 门控 | 当前值 | 读回/写入门径 | 结论 |
| --- | ---: | --- | --- |
| `speed_charge_state` | `3` | `settings get/put system` | ColorOS 原生手动快充状态，当前已在 mode 3 |
| `single_speed_charge_state` | `0` | `settings get/put system` | 仅为入口核查临时写 `1`，已恢复 `0`；会启用 XML `speedChargeAdd` 并可能影响 gear 回读，按用户真机对比收益不如 `cosa`，列为排除项 |
| `customize_charge_switch_state` | 未设置 | `settings get/put/delete system` | 写 `1` 后回读成功，已删除恢复；是否实际提高瓦数待接电验证 |
| `getSmartChgMode()`（GuardElf 事务 19） | `1` | `service call guardelfthermalcontrol 19` | 可读 |
| `setSmartChgMode(0)`（GuardElf 事务 20） | `1 → 0 → 1` | `service call guardelfthermalcontrol 20 s16 0` | 写后事务 19 回读 `0`，随后已恢复 `1` |
| `getCustomSelectChgMode()`（GuardElf 事务 23） | `3` | `service call guardelfthermalcontrol 23` | 当前已是 custom mode 3 |
| `setCustomSelectChgMode(3,true)`（GuardElf 事务 24） | 返回 `0` | Binder 参数 `i32 3 i32 1` | 调用成功；该 getter 会返回框架当前 policy id，不能把单次 getter 回读当成 setter 成功条件 |

热控应用还注册了 `oplus.intent.action.ACTION_THERMAL_CONTROL_USER_SYSTEM`。当 extra 为 `name=set_charge`、`level=N`、`speedChargeAdd=M`、`foregroundPkgName=<pkg>` 时会进入 `ThermalControlUtils.D1(N, M, pkg)`。通过当前 ADB/shell 身份发送测试广播返回 `Broadcast completed: result=0`，但当时设备未接电，不能据此宣称功率变化。这个入口可以在保持目标 gear 的同时覆盖策略下发值，是下一轮接电对照实验的首选。

旧版亮屏快充工程还记录了一条完整组合链：`speed_charge_state=3`、`single_speed_charge_state=1`、`customize_charge_switch_state=1`、清除 `SmartChgMode` bit 0、`setCustomSelectChgMode(3,true)`、`setChargeLevel 0`、`setPsySlowChgEnable(60,33,0)`。这只能证明调用面可达到，不代表每项都有独立增益。其中 `single_speed_charge_state=1` 已因真机收益不如 `cosa` 且会干扰 gear 回读而排除。下一轮只比较不依赖 single-speed 的路径，并至少测量实际输入功率、电池电流、`Thermal Levels[0]`、计算 gear、policy `chargeVal` 与 `speedChargeAdd`。

V12 `ICharger` 的 `ChgFuncFlag.SET_CHG_UP_LIMIT_VALUE` 名字容易被误认为功率上限，但实际语义是电池充电上限百分比（配套状态项 `SET_CHG_UP_LIMIT_STATE`），不能用于突破瓦数。`/proc/charging_limit_current` 在当前系统不存在；旧路径中的 `cool_down` 节点对 shell 返回权限拒绝，不能作为 OPPower 的可靠提功率入口。

截至本轮 A/B 测试结束，设备仍接 AC，`cosa=0`，基础档 8，真实充电功率约 6.2W，电池温度约 40.8°C；OPPower 与 `llpower` 均未运行，避免继续回写 `cosa`。
## 接电 A/B 真机结果（2026-10-23，RMX8899 / ColorOS 17）

测试条件：真实亮屏，`stay_on_while_plugged_in` 临时设为 7，测试结束已恢复 0；`speed_charge_state=3`、`single_speed_charge_state=0`、`customize_charge_switch_state` 未设置、`SmartChgMode=1`、`CustomSelectChgMode=3`。先停止 OPPower 和 `com.lele.llpower`，确认 30 秒内无人回写 `cosa`。功率均按电池侧 `|Battery current| × Battery voltage` 计算，仅用于同机短时对照。

| 对照 | 稳定条件 | 结果 | 结论 |
| --- | --- | --- | --- |
| 服务隔离 | `cosa=0`，两个应用无进程，电量约 42%，温度 37.2°C | `cosa` 连续 30 秒保持 0；功率约 10.7–12W | `llpower` 是测试期间唯一在运行的外部充电监控实例；强制停止后不再回写 `cosa` |
| `customize_charge_switch_state=1` | 写入 `cosa=-6` 后保持亮屏；随后写 `customize=1` | 写入后约 27 秒功率从约 11.7W 升到约 40W，但日志同期出现 `ThermalControlUtils: onGameCustomTempChanged value:-6`；删除 `customize` 后高功率未立即退出 | 因果被 `cosa` 写入触发的热控路径混入，不能证明 `customize` 有独立增益，列为无效对照 |
| customize_charge_switch_state=1 专项复测 | OPPower 前台，cosa=-7、基础档 7、计算 gear=0；屏幕全程 Awake，温度约 40.1–40.9°C | 写入前约 35.66–35.83W；写入后约 34.53–35.83W，未出现跃升；删除后恢复未设置 | 独立对照没有功率增益，并略有下降；不纳入提速路径 |
| `SmartChgMode=0` 单独测试 | `cosa=-6`，基础档 7，计算 gear 1，`customize` 未设置 | 前后均约 38.1–38.9W；`chargeVal=0`、`speedChargeAdd=-2` 不变；恢复后回读 `SmartChgMode=1` | 该条件下没有立即观察到独立增益 |
| `customize=1` + `SmartChgMode=0` 组合 | gear0；`customize=1` 保持生效 | 2026-10-23 15:59 用户实测写入 `SmartChgMode=0` 后功率约提高 1–3W，实际增益可能更高；写入后回读为 0，当时电量 81%、温度约 39.5°C、电池侧约 14.2W | 组合路径有正收益，作为候选保留；尚不能把增益全部归因于 `SmartChgMode` 单独作用 |
| `set_charge(level=0, speedChargeAdd=0)` | 同一 `cosa`、同一前台包 `com.android.launcher` | 广播返回 `result=0`；发送前约 38.2–39.5W，发送后约 39.0–39.3W；`chargeVal` 和 `speedChargeAdd` 不变 | 没有独立功率增益。源码确认该路径最终调用 `setSmartCoolDown(0,0,pkg)` |
| `set_charge(level=0, speedChargeAdd=-2)` | beta12 使用该系统广播 | 广播返回 `result=0`，但接电日志中没有对应的 `setSmartCoolDown`，HAL 未变化 | 排除广播下发；`result=0` 不能证明接收器执行 |
| `OplusBatteryService setChargingLevel 0` | 2026-10-23 16:03，OPPower 前台，gear0，`SmartChgMode=0`，`customize=null` | 命令返回 `set charging level = 0`；当时电量 84%、温度约 38.0°C、电池侧约 16.3W；用户实测确认有效提升，实际幅度可能更高 | 纳入组合验证候选；与之前的 `set_charge` 广播结果不同，说明当前组合状态和直接下发路径仍需继续对照 |
| `setPsySlowChgEnable(60,33,0)` | GuardElf 事务 38，参数 `i32 60 i32 33 i32 0` | Binder 返回异常：`com.oplus.permission.safe.READ_COMMON`；ADB/Shizuku shell uid 2000 无权调用，未发生状态变化 | 普通 shell/Shizuku 路径不可用；需要系统签名或拥有 `READ_COMMON` 的进程，不能作为 OPPower 当前权限下的提速入口 |
| 降温恢复 | `cosa=0`，调用 `setChargingLevel 2` | 功率从约 36.9W 在约 10 秒内降到 6.8W，随后稳定约 6.2W；热控温度从 42.2°C 开始回落 | 充电控制通道可实际压低功率；策略缓存 `gearVal` 仍为 1，不能把它当作真实下发状态 |

高功率阶段还确认了一个缓存现象：`cosa` 已恢复为 0、基础档为 8，但 `getCurThermalPolicy.gearVal` 仍缓存为 1，功率保持约 36–37W；直到 `setChargingLevel 2` 下发后功率才降到约 6W。该现象说明缓存 `gearVal` 不能用于控制器反馈或验收，也不能据此推断当前实际功率。OPPower 仍按 `Thermal Levels[0] + cosa` 计算目标；是否到达控制目标不能以滞后的 `policy.gear` 判断。

本轮没有找到可直接替代 `chargeVal` 的写入入口，`chargeVal` 仍是只读诊断字段。`setSmartCoolDown(0,0)` 单独测试没有额外瓦数；但用户后续在 `customize_charge_switch_state=1` 保持生效、计算 gear=0 的条件下写入 `SmartChgMode=0`，实测功率约提高 1–3W，说明该组合路径存在正收益，实际增益可能更高。此前独立 A/B 结束时曾恢复 `cosa=0`、`stay_on_while_plugged_in=0`、`customize_charge_switch_state` 未设置和 `SmartChgMode=1`，设备回到约 6W；随后用户继续组合复测：`customize_charge_switch_state=1` 条件下的 `SmartChgMode=0` 获得约 1–3W 正收益；`customize` 恢复默认后，继续在 OPPower 前台、`cosa=-6`、基础档 6、计算 gear=0、`SmartChgMode=0` 状态直接调用 `setChargingLevel 0`，用户再次确认有效提升。当前电量约 84%，继续验证这一组合路径。
## 闭环控制验收

- 控制目标是规则选出的计算 gear：`计算 gear = Thermal Levels[0] + cosa_ctc_value`。
- 目标 `cosa = 目标 gear - Thermal Levels[0]`；基础档变化时立即重新计算，得到新的计算 gear。
- `getCurThermalPolicy` 的 `gearVal` 缓存不参与控制、显示或验收，代码只读取 `chargeVal` 作独立诊断。
- 目标变化、首次开启或检测到外部 `cosa` 漂移时，直接覆盖动态计算出的目标值并回读 `cosa`，不先写 `0`。
- 写入后只触发一次系统热控重算：优先把 `oplus_float_window_show` 的同一整数在 `0/00` 或 `1/01` 之间切换。数值不变，监听回调调用 `j2(null, 0ms)` 并立即提交重算。若该值不可用，才回退到 `oplus_camera_3rd_activity`；相机回调调用 `m2()`，系统内部约 5 秒后计算。
- 同一目标保持且基础档未变化时不重复写 `cosa`、不重排 `m2()`，也不重复下发原生 `setChargingLevel`。应用继续每秒读取基础档和 `cosa`；`SmartChgMode` 每个充电会话只确认一次。
- `基础档 + cosa == target` 即达到计算目标；`policy.gear` 缓存和 `chargeVal` 都不参与该判断。
- 温度下降 5 秒、恢复 8 秒的确认仍属于热安全规则；用户选择和档位切换没有防抖。
- 只有拔电、关闭总开关、电量暂停、充满或用户点击“一键恢复”才恢复 `cosa=0`，并在实际写入时触发一次系统重算。系统驱动的后台服务销毁不会恢复 `cosa`；软件不读取或写入 `single_speed_charge_state`。

## 软件验证

截至 2026-10-23：

- `:app:compileDebugKotlin` 通过。
- `:app:testDebugUnitTest` 通过，共 32 项、0 失败。覆盖默认温度档、SOC 暂停、100%/FULL、禁止更高温反向升档、均衡档内部 gear、Shizuku 命令白名单、24 小时曲线记录序列化、LLPower 采样间隔、曲线聚合/平滑/断线/坐标轴，以及“只按基础档和 cosa 计算、不经过 0”“同一目标只触发一次刷新”“缓存 gear 不参与计算”“基础档变化立即重算 cosa”“外部 cosa 漂移单次修正”和“数值不变刷新别名”的控制器用例。
- `:app:assembleDebug` 通过并生成 debug key 签名 APK。
- 页面数据每秒刷新；曲线按 LLPower 节奏采样：充电亮屏每 3 秒、充电熄屏每 5 秒、未充电每 180 秒。控制服务在 AC 在线时每 1 秒执行一次控制循环。
- 设置多级页面、主题与比例、通知内容/频率、硬件修正、诊断字段、数据管理、悬浮窗与无障碍保活已接入源码。
- 设置主页、亮屏快充详情页和系统页提供 Shizuku 未安装、未运行、未授权、已授权四种状态及对应操作。
- 通知只在接电时显示；总开关关闭时显示“亮屏快充未启用”。Android 16 真机已确认使用 LLPower 同类的 `Notification.ProgressStyle`、`battery_live_update_v2` 通道和 `PROMOTED_ONGOING` 实时卡片；开启 ColorOS“流体云显示实时活动”后，顶部卡片使用系统透明样式，正文保留电量、电压、电流、温度和亮屏快充档位。
- `1.0.0-beta3` 使用 debug key 签名（versionCode 3），已通过上述单测和 `:app:assembleDebug`。APK 位于 `release/1.0.0-beta3/`，SHA-256 为 `DA8DA0D74546FA1E686BE61573542588EC39A2B1AAE2C1AC986A505613C11F35`；beta2 及其归档保持原样。各版本使用独立目录。
- `1.0.0-beta4` 使用 debug key 签名（versionCode `4`），已通过 `:app:lintDebug`（0 error）、上述单测和 `:app:assembleDebug`。APK 位于 `release/1.0.0-beta4/`，SHA-256 为 `43AD083730408C8C1FC1189C88DF7862FC1CE3C1F9B18EF52B8B9D4C0A1EE093`；beta1–beta3 保持原样。各版本使用独立目录。
- 1.0.0-beta5 修复 Android 16 / ColorOS 17 实时通知回归：实时分支恢复 setOngoing(true)，真机通知转储重新包含 PROMOTED_ONGOING 与 Notification.ProgressStyle，通知栏不再显示应用图标和展开箭头；关闭亮屏快充时闪电为白色，开启时为琥珀色 #FFC107。APK 位于 release/1.0.0-beta5/。
- 真机已安装验证冷启动、前台服务、通知和 Shizuku 授权路径。修复了 `ForegroundServiceDidNotStartInTimeException`；前台服务启动后持续运行，无新的 ANR 或崩溃记录。
- `1.0.0-beta6` 删除同一目标重复写入和 3 秒重试，改为固定映射单次写入、单次热控重算触发；实际档位只认 `policy.gear`。24 项单测全部通过，`lintDebug` 为 0 error / 20 warning，`:app:assembleDebug` 通过。APK 位于 `release/1.0.0-beta6/OPPower-1.0.0-beta6-debug.apk`，SHA-256 为 `D1A48BBE9FC52DC12C260DC5D7212118CF72C671FE4CD093E5722F038ED15AC3`。安装后已用无线 ADB 确认等价字符串写入会触发热控策略缓存变化；因手机当时未接电且前台切换到 QQ，目标 gear 的最终功率路径仍需在接电并保持 OPPower 前台时复验。
- `1.0.0-beta7` 将固定 `cosa` 映射改为实时公式 `gear = 热控基础档位 + 场景及其他修正 + cosa`。控制器读取 `Thermal Levels[0]`，按 `cosa = 目标 gear - 基础档 - 修正` 每秒重算，并在界面显示全部求和项与独立的 `chargeVal`。30 项单测全部通过，`lintDebug` 为 0 error / 20 warning，`:app:assembleDebug` 通过。APK 位于 `release/1.0.0-beta7/OPPower-1.0.0-beta7-debug.apk`，SHA-256 为 `78AB7D2183C3C5F47F1C1FF4FB0788D9250E9489FCA89A08E5F5469BFF0BDCA5`；真机只读验证结果为 `实际档位 7 = 基础档 7 + 修正 0 + cosa 0`，`chargeVal=2` 保持独立显示；动态公式的真机充电写入与功率验收仍需在接电、OPPower 前台条件下完成。
- `1.0.0-beta8` 将档位定义统一为 `计算 gear = Thermal Levels[0] + cosa_ctc_value`，目标 `cosa = 目标 gear - 基础档`。`policy.gear` 缓存不再解析、显示、参与校准或验收；热控策略只读取 `chargeVal`。32 项单测全部通过，`lintDebug` 为 0 error / 20 warning，`:app:assembleDebug` 通过。APK 位于 `release/1.0.0-beta8/OPPower-1.0.0-beta8-debug.apk`，SHA-256 为 `9D7DB495BFB4457983743B5DB846B7F0CF390AA0C5BE3BA91758B62564CA977B`。 真机只读验证显示 `计算档位 5 = Thermal Levels[0] 5 + cosa 0`，`chargeVal=4` 单独显示；接电写入与功率验收仍待完成。

- `1.0.0-beta9` 增加原生充电级双通道：继续写入 `cosa` 并触发热控重算，同时通过受限的 `set_charge` 系统广播直接下发映射后的原生充电级，解决 OPPower 前台时 `getCurThermalPolicy=null` 导致实际充电状态停留在旧值的问题。原生级按目标映射为 `0..1→0`、`2..4→7`、`5→5`、`6→3`、`7+→2`；同一原生级不重复下发，暂停/关闭/拔电/充满回退到安全级 `2`。33 项单测全部通过，`lintDebug` 构建成功。真机首轮验证在 OPPower 前台、未切应用时显示“原生充电级=0”，`cosa=-6`、基础档 6、计算 gear 0；当时电量 88%、约 7.9 W，功率受高 SOC 限制。

- `1.0.0-beta10` 在控制会话生效时自动确保 `system.customize_charge_switch_state=1` 并将 GuardElf `SmartChgMode` 写为 `0`，进入会话前保存原值；暂停、关闭、拔电和充满时恢复。两项每会话只应用一次，稳定期间不重复写。34 项单测全部通过，覆盖应用、单次写入和拔电恢复。真机安装 beta10 后只读确认 `system.customize_charge_switch_state=1`、GuardElf `SmartChgMode=0`，且 OPPower 控制仍在运行。

- `1.0.0-beta11` 移除 `customize_charge_switch_state` 的读取、写入、恢复和 Shizuku 命令白名单，保留 `cosa`、原生充电级直发与 GuardElf `SmartChgMode=0`。36 项单测、`lintDebug` 和 `assembleDebug` 全部通过；APK 位于 `release/1.0.0-beta11/OPPower-1.0.0-beta11-debug.apk`，安装后 versionCode 11、versionName 1.0.0-beta11。设备上的 `customize_charge_switch_state` 已删除并回读为 null。

- `1.0.0-beta12` 将 gear 0 的原生下发从 `set_charge(0,0)` 改为 `set_charge(0,-2)`，目标稳定时每 3 秒重新确认一次；档位或 `cosa` 变化仍立即处理。源码与 Shizuku 白名单只增加 level 0 对应的 speed -2。设备采样时已拔电，因此功率效果尚待真机接电验收。

- `1.0.0-beta13` 修复 beta12 的未生效广播：接电日志确认 `set_charge` 只返回广播成功，没有进入 `D1()`；改为 `OplusBatteryService setChargingLevel` 后，logcat 明确出现 `setSmartCoolDown(0,0)` 和 HAL `set cooldown:0`。37 项单测、`lintDebug` 和 `assembleDebug` 通过。APK 位于 `release/1.0.0-beta13/OPPower-1.0.0-beta13-debug.apk`，SHA-256 为 `9968DFA7E4312A710916827CF2194DA6A17527096123D189D3C694EB36D597C9`。真机保留微信及 `TYPE_APPLICATION_OVERLAY` 浮窗时，25 秒内 8 次 level 0 都到达 HAL，但 88% SOC 下功率保持约 11.10–11.53 W；浮窗复杂场景和当前协商状态仍占主导。

- `1.0.0-beta14` 移除稳定期每 3 秒重发原生 level 的设计。beta8 没有主动下发 `setChargingLevel`；beta14 仅在首次进入目标或原生 level / speedAdd 变化时下发一次。`SmartChgMode` 改为每个充电会话只确认一次，避免控制循环每秒重复执行 Binder 读取。38 项单测、`lintDebug` 和 `assembleDebug` 全部通过；APK 位于 `release/1.0.0-beta14/OPPower-1.0.0-beta14-debug.apk`。

- `1.0.0-beta15` 将实际充电门控改为事件驱动：读到已有 `chargeVal=0, speedChargeAdd=-2` 时跳过 native 调用；策略 category 变为受限状态时最多补一次协商；native 失败不在稳定期每秒重试。温度规则继续只做安全降档。41 项单测、`lintDebug` 和 `assembleDebug` 全部通过；APK 位于 `release/1.0.0-beta15/OPPower-1.0.0-beta15-debug.apk`。

## beta11 参数面复核（2026-10-23）

- 应用已移除 `customize_charge_switch_state` 全部读写和命令白名单，保留 `SmartChgMode=0`。
- GuardElf `QuickModeGain`（事务 27）返回 `com.oplus.permission.safe.READ_COMMON` 拒绝；不能作为反馈参数。
- Charger V12 接口中的 `setChgConfig`、`setChargerCoolDown`、慢充与充电配置入口在 shell/Shizuku 身份下权限拒绝；直接 Binder 事务出现 DeadObject。
- `speedChargeAdd` 在 `ThermalControlUtils.D1()` 中只于 single-speed 条件成立时加到原生 level，不是独立倍率；负值可能产生无效 level。
- 新增两项自定义规则回归测试后共 36 项单测通过。测试证明开启自定义后默认温度阈值与默认 80% 暂停不再参与。
- 因设备未接电，移除 `customize=1` 后的 `SmartChgMode` 独立功率 A/B 尚未执行。

## 后续真机验收

1. 安装当前 APK，启动 Shizuku 并授权。
2. 接电并开启总开关，保持 OPPower 前台，不切换应用；确认控制循环自动读取 `Thermal Levels[0]` 和 `cosa` 并计算 gear。
3. 选择“最大输出”，每 200–300 ms 采样 `cosa_ctc_value` 和 `Thermal Levels`。确认 `基础档 + cosa = 目标 gear`；例如基础档为 6 时必须写 `cosa=-6`。同时故意保留旧 `policy.gear`，确认它不影响计算结果。
4. 开启强制亮屏快充后首次选择规则，保持 OPPower 前台并确认无需切换应用即可写入；切换基础档或自定义规则时确认写入序列不包含中间 `cosa=0`，且每个目标只触发一次重算。
5. 外部或系统把 `cosa` 改错后，确认控制器在下一周期按最新基础档重新计算并修正一次；稳定到目标后不重复写 `cosa`，也不重复下发 `setChargingLevel`；仅在目标或原生映射变化时检查一次 cooldown 调用。
6. 在抖音、B 站和桌面之间切换，每档至少观察 60 秒，确认稳定目标不重复写；只有基础档或 `cosa` 变化时重新计算。
7. 最大输出在 41/42/43°C 及更高温度下持续要求 gear 0，确认 OPPower 不再自行降档；由于系统基础档可能高于 10，应用必须显示计算 gear 与“目标不可达”，并禁止写入低于 `-10`。20–40 W 和 10–30 W 的默认温度安全暂停仍按 43°C 验收；另验证 80% 电量暂停、100% 恢复系统默认，以及自定义温度/电量规则。
8. 拔电后确认 `cosa=0`；再插电确认直接读取当前 `cosa` 和 gear 建立闭环，不执行“基础 gear 重校准”。
9. 强制终止应用、重启设备和 Shizuku 断开场景单独记录失败边界。

## 已排除方案

- 单次 LV0：只带来短时提升，切换应用后会回落；循环补发不是持久控制。
- 冻结 Athena/Battery：约十秒后系统因 freezer 冲突终止进程并重启，功率回落。
- 分屏、旁路充电、高性能磁贴、Doze/假亮屏、关闭智能充电：没有满足持久亮屏快充目标。
- 伪造温度、停止原生热服务、工程模式或签名权限绕过：不纳入实现。
- `single_speed_charge_state=1` 会遮蔽缓存档位回读，且不是持续功率的必要条件；正式控制路径只使用热控基础档和 `cosa_ctc_value`。

## 第二轮充电功率发现（2026-10-23）

本轮继续只使用 ADB/Shizuku shell，不注入温度、不伪造亮屏状态。当前设备在部分只读/场景核查时未接 AC；因此“策略字段未变化”只记作待接电 A/B，不作为接口无效结论。

| 候选/入口 | 可写性 | 已知信息 | 第二轮状态 | 接电后的首轮动作 |
| --- | --- | --- | --- | --- |
| `send_pkg(pkgName)` 热控广播 | shell 广播返回成功 | `ThermalControlUtils.s1()` 会把包名送入 Horae 场景层；可指定高 `charge`/`speedChargeAdd` 的包模板 | **保留待测**：发送 `com.tencent.qqlive` 并触发 `m2()` 后，策略字段仍显示抖音；这不排除 Horae 内部状态已改变，需看实际功率 | 固定 gear0/电量/温度，依次比较抖音、腾讯视频、B 站、桌面包名，再恢复真实包名 |
| `send_scene(sceneName)` | shell 广播返回成功 | `scene_16` 在 XML 中有 `charge="0"` 条目；广播会进入 `u1(25, sceneName)` | **保留待测**：只读策略未变化，不能据此否定 | 先测 `scene_16`、空场景和当前真实场景，至少各观察 20 秒实际功率 |
| `send_thermal_mode_scene(state)` | shell 广播返回成功 | XML 的 `app_type` 将游戏映射为 `type=4`；状态通过 `u1(27, state)` 送入 Horae | **保留待测**：`state=4 -> 0` 后策略字段未变化 | 接电后比较 `state=4` 与 `state=0`；若温度或基础档变化明显立即恢复 |
| `set_charge(level, speedChargeAdd, pkg)` | shell 广播返回成功 | `D1()` 在 `level=0` 时固定调用 `setSmartCoolDown(0,0,pkg)`，因此 `speedChargeAdd` 会被忽略；`level>0` 才可能叠加正 speedAdd | **保留待测**：已有 `level=0/speedAdd=0` 无独立增益；尚未测正 `speedChargeAdd` | 在 `speed_charge_state=3` 且充电流程处于 mode 3 时，比较 `level=0,speedAdd=0` 与保守组合 `level=4,speedAdd=4,pkg=com.tencent.qqlive` |
| `setLongChargeProtectionChargeWattage` | BatteryService 命令接受 | `P0(N)` 写智能充电保护的 `mChargeWattage`；仅当值 `<65` 时关闭“支持智能慢充”判断，不是硬件功率 setter | **保留待测、低优先级**：可影响慢充保护门控，不会直接写充电电流 | 在测试模式下比较 `<65` 与真实 wattage；结束后用 `resetLongChargeProtectionTestMode` 恢复 |
| `oplus_battery_settings_charge_wattage` | Settings 可读/可写 | 是 UI/状态镜像，当前未接电为 `0`；未发现它直接驱动充电 HAL | **只作观测** | 每次实际功率采样时同步记录，不能把它作为功率控制字段 |
| `SmartChgMode` 非 bit0 值 | GuardElf 事务 20 可写 | 当前 bitfield 为 `1`；已确认 `0` 在 `customize=1` 组合中有约 1–3W 正收益，其他 bit 语义未确认 | **暂不盲写**：2/3 等值只列候选 | 仅在确认 bit 定义后测试；任何测试前记录原值并恢复 `1` |
| `setPsySlowChgEnable` | shell 直调拒绝 | 返回 `com.oplus.permission.safe.READ_COMMON` | 当前权限不可用 | 不纳入应用路径；除非出现系统代执行广播 |
| `setChargerCycle` / `setChgOlcConfig` / `setChgRusConfig` / `setChgConfig` | shell 直调拒绝 | GuardElf 事务 34/35/43/44 均要求系统权限；工程模式里可见 `en808`/`dis808` 等充电满测入口，但无法确认长期恢复 | **阻塞，不盲测** | 获得可审计的系统代执行入口后再评估 |
| `setChargingModeInGsmCall` / `setTbattPwrOff` | shell 直调拒绝 | GuardElf 事务 41/42 返回 `com.oplus.permission.safe.POWER`；后者涉及电池供电关闭 | **阻塞且高风险** | 不作为常规提速入口 |
| `setLongChargeProtectionTestMode` + `setLongChargeProtectionBatteryLevel` | BatteryService 命令接受 | 可模拟慢充 AI 条件和电量，但不会修改 thermal `chargeVal` | 次选 | 仅在已确认慢充保护实际进入时测试 |

GuardElf 权限边界补充：事务 34–39 对 shell 返回 `READ_COMMON` 拒绝；事务 40–44 返回 `POWER` 拒绝。热控调试命令 `thermalscene`、`setAmbientTemperature`、`sendChargeModeChanged` 仍受 `sys.deepsleep.allow.debug` 门控，当前均为 `cmd not allow`。

以下默认/安全字段已经处于较有利状态，不应为了提高功率而反向修改：

| 字段 | 当前值 | 说明 |
| --- | ---: | --- |
| `speed_charge_state` | `3` | 已处于原生快充选择状态 |
| `single_speed_charge_state` | `0` | 已验证开启会干扰回读，且不是必要条件 |
| `oplus_battery_cool_charge` | `0` | 未开启额外降温充电限制 |
| `oplus_settings_hightemp_protect` | `0` | 未开启高温保护限制 |
| `oplus_thermal_outdoor_scene` | `0` | 未进入户外热控场景 |
| `thermal_safety_mode` | `0` | 当前安全模式最低 |
| `battery_charging_state_enforce_level` | `-1` | 未额外强制充电状态级别 |
| `battery_charging_state_update_delay` | `-1` | 未增加充电状态更新延迟 |

## 同 SOC 热控基础档抬高 A/B（2026-10-23）

测试条件：OPPower 前台、AC 接电；`system.customize_charge_switch_state=1`、`system.speed_charge_state=3`、`system.single_speed_charge_state=0`；`global.cosa_ctc_value` 由控制循环维护；`getCurThermalPolicy` 在 OPPower 前台返回 `null`。功率按电池侧 `Battery voltage × |Battery current|` 计算。

| 时点/状态 | 电量 | 热温度 | 基础档 | cosa | 计算 gear | 电压/电流 | 电池侧功率 | 结果 |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 19:24:51，负载前 | 84% | 35.99°C | 5 | -5 | 0 | 4.397 V / 2.855 A | 12.55 W | 基线 |
| 19:27:40，约 135 秒真实 CPU 负载后 | 86% | 41.01°C | 8 | -8 | 0 | 4.437 V / 2.477 A | 10.99 W（停载瞬间的过渡样本） | 不计入稳定对照 |
| 19:27:49，停载后单次稳定读数 | 86% | 41.01°C | 8 | -8 | 0 | 4.472 V / 3.659 A | 16.36 W | 较基线约提升 3.8 W / 30% |

真机确认：在不切换应用的情况下，基础档从 5 自然升到 8，OPPower 自动把 `cosa` 从 -5 重算并写入 -8，计算 gear 始终保持 0。用户观察功率明显上升；单次稳定读数从约 12.6 W 到约 16.4 W。该组记录确认“同一计算 gear 下，基础档抬高可以带来可测功率增量”，但测试伴随热温度从约 36.0°C 升到 41.0°C、电量从 84% 升到 86%，因此它是自然升温 A/B，不能把 3.8 W 全部归因于基础档。

同日的既有日志还显示，gear 0 的功率层同时强依赖 SOC：43–44% 时约 31.8–38.7 W，68–70% 时约 34.5–35.8 W，79–80% 时约 15.4–15.6 W。当前 84–86% 的高 SOC 阶段只有约 12.6–16.4 W。因此后续控制逻辑应同时记录基础档、cosa、计算 gear、`chargeVal`、SOC 和温度；`gear=0` 不能单独代表最高功率。

`chargeVal=--` 的根因也已复核：OPPower 自身不在 ROM 热控 XML 的包名分类中，OPPower 前台时原生策略对象可以为 `null`，本周期不调用 `D1()`，实际充电控制可能保持上一条下发值。切回桌面后原生策略对象立即生成，读到的示例为 `gearVal=5, chargeVal=6, speedChargeAdd=1`。因此计算 gear 只表示 OPPower 根据基础档和 cosa 得到的目标；需要另行验证原生策略对象为空时是否必须通过系统代执行 `set_charge` 才能保证实际下发。

接电后的第二轮顺序应固定为：先记录 gear0 的自然基线；再测 `send_pkg`/场景广播；再测 `set_charge` 正 speedAdd；最后才评估长充保护 wattage。每组至少记录电池侧 `|current × voltage|`、输入侧功率计、`Thermal Levels[0]`、计算 gear、`chargeVal`、`speedChargeAdd`、电量和电池温度，并确认恢复真实包名、场景及 GuardElf 原值。
## 温度与功率因果复核（2026-10-24）

下午高功率记录不能证明“温度越高，功率越高”。更直接的证据如下：

- `15:33:14.595` 到 `15:33:21.260`，电池温度保持约 37.2°C，热控温度保持 37.936°C，功率却从 15.58 W 跳到 32.38 W。
- 同一段内，热控策略从 `chargeVal=6, speedChargeAdd=1` 变为 `chargeVal=0, speedChargeAdd=-2`，充电电流从 -3.914 A 变为 -7.897 A。
- `15:57` 一段温度约 39.98°C，基础档 7、计算 gear 0，但功率只有约 15.39 W；温度更高没有带来更高功率。
- beta12/beta13 在约 38.5°C、计算 gear 0 时读到的浮窗场景策略仍为 `chargeVal=12`，功率约 10–11 W。

因此，温度上升经常是前一段高功率或前台负载造成的结果，而不是允许更高功率的输入条件。控制逻辑不能为追求高功率主动升温，也不能把温度作为升档依据。温度只用于安全限制和降档。

下一轮需要在低 SOC（建议 60–70%）、相同充电器和线材、真实亮屏、保留微信浮窗的条件下做三组单变量测试：

1. 仅写入目标 `cosa` 并触发一次 `m2()`，不调用 `setChargingLevel`。
2. 在相同策略下仅调用一次 `setChargingLevel 0`，确认能否从 `chargeVal=12` 场景进入 30 W 以上。
3. 若高功率只短暂出现，记录系统下一次覆盖它的来源（`m2()`、`float_window_scene_102`、cooldown 写回或充电协议状态），寻找单次写入能持久生效的更高层入口。

复测前必须核对 SOC、温度、充电器协商电压/电流、基础档、`cosa`、计算 gear、policy category、`chargeVal`、`speedChargeAdd` 和实际功率。只有功率变化先于温度变化、且排除 SOC 与协商条件后，才能把某个温度相关参数视为候选增益。

## 第三轮场景配置实测（2026-10-24）

设备未接 AC，电量 83%，电池温度约 39.9°C。测试目标是确认可写入口是否能够改变实际热控策略，不以未接电状态推断瓦数。

| 入口 | 写入与恢复 | 策略回读 | 判定 |
| --- | --- | --- | --- |
| `cosa_gameMode=0` | `com.oplus.appplatform.settings/nonpersist` 写入成功，随后删除 | category 不变，gear 7，chargeVal 2 | 无当前场景收益 |
| `cosa_gameMode=1` | 同上 | category 不变，gear 7 → 8，restrict 0 → 1，modem 0 → 1，disVideoSR 0 → 1，chargeVal 2 | 增加限制，排除 |
| `cosa_gameMode=2` | 同上 | 恢复为 gear 7、chargeVal 2 | 无收益 |
| `cosa_refreshSet=reset` | 同上 | 可触发控制器刷新 | 仅刷新触发，不是功率参数 |
| `debug_gamemode_value=1` | global 写 1，随后恢复 0 | category、gear、chargeVal 均不变 | 非游戏前台无收益 |
| `oplus_float_window_show=0` | system 写 0，HOME → 抖音后仍为 `category_16_fw_scene_102`；随后恢复 1 | gear 7，chargeVal 2 | 不能解除浮窗场景 |
| `updateMaxChargeCurrent()` | 源码审计 | Binder 实现固定返回 false | 空实现 |
| `updateMaxChargeTemperature()` | 源码审计 | Binder 实现固定返回 false | 空实现 |
| `updateMinChargeTemperature()` | 源码审计 | Binder 实现固定返回 false | 空实现 |

所有非持久测试键均已删除，`oplus_float_window_show` 已恢复 1，`debug_gamemode_value` 已恢复 0，微信进程和浮窗未处理。

阈值仍采用 `OPERATIONS.md` 的正式标准：实际功率提升至少 5 W、持续至少 30 秒、两轮可重复。本轮没有参数达到该标准。

## 第四轮接电复核（2026-10-24）

设备电量 79%–82%，电池温度 38.7–40.1°C，AC 实际接电，OPPower 强制亮屏快充开启。基线外接功率计约 15 W；热控基础档 6–7，`cosa=-6/-7` 使计算 gear 0，但浮窗策略为 `chargeVal=12`、`speedChargeAdd=0`。

- `cosa_gameMode=1` 经 nonpersist Provider 写入、回读成功。持续 30 秒外接功率计约 16 W，没有高 5 W 的持续变化；`chargeVal` 仍为 12。测试后键已删除，未达到有效标准。
- `/sys/class/oplus_chg/common/bcc_current` 在源码中会写入 VOOC/UFCS/PPS 的 BCC 电流 votable，是真实电流控制节点；设备上 shell/Shizuku UID 2000 访问 `/sys/class/oplus_chg/common` 返回 Permission denied，因此当前权限不可用。
- GuardElf 事务 45 `getChgConfig` 和事务 40 `getChargingModeInGsmCall` 均返回 `com.oplus.permission.safe.POWER` 权限拒绝，不能作为可接入入口。
- `smart_charge_switch_state` 与 `smart_charge_protection_switch_state` 当前均为 1。两项将在 AC 下分别做开启/关闭 A/B，尚未完成功率验证，暂不作为有效参数。

本轮新增参数没有达到“提升至少 5 W、持续至少 30 秒、两轮可重复”的标准。当前 SOC 已到 82%，高 SOC 本身会显著压低充电功率；后续高功率候选应在约 60%–70% SOC 下复核。
## beta16 跳 0 W 修复与原生写入移除（2026-10-24）

真机在一次 gear 0 充电中观察到约 40 W 后突然跳到 0 W，随后恢复并再次跳 0 W，最终约 27 W。此前控制器在写入 `cosa` 并触发热控刷新后，同一周期还调用 `setChargingLevel`。ColorOS 场景热控会自行重算并写回 `cooldown=12/15`，因此两套控制链可能在 `0 → 12 → 0` 之间争夺最终值；用户实测也确认 `setChargingLevel` 与 `cosa` 形成的 gear 0 作用重合。

beta16 的决定：

- 完整移除控制器、Shizuku gateway、命令白名单中的 `setChargingLevel`。
- 不再保存或显示原生充电级，不在暂停、拔电、关闭或充满时回写原生安全 level。
- 保留 `cosa` 写入、回读和单次热控刷新；系统自行生成的 `chargeVal`、`speedChargeAdd`、category 与 cooldown 只用于诊断。
- 该路径不得为短期提功率重新加入；`OPERATIONS.md` 第 0 节已列为强制约束。

验证结果：34 项 JVM 单测全部通过，包含 14 项充电控制器测试和 3 项 Shizuku 白名单测试。测试确认 gear 0 只产生一次 `cosa=-8` 写入和一次热控刷新，稳定期间不重复写入，并显式拒绝 `setChargingLevel 0`。APK 位于 `release/1.0.0-beta16/OPPower-1.0.0-beta16-debug.apk`，SHA-256 为 `A3C3294AC09B5D12C69229DC39EB543F0645990DCC21DD26025541C3D00C4911`，并已安装到 RMX8899（versionCode 16）。跳 0 W 的接电复验仍待完成。
## beta17：恢复 beta8 自然策略条件（2026-10-24）

从设备内 OPPower 24 小时曲线数据库复核 2026-10-23 下午记录：

- `15:47–15:54` 连续约 7 分钟保持 30 W 以上，分钟均值约 30.45–35.40 W，温度约 40.0–41.4°C，SOC 66–78%。
- 当时运行 beta8；控制器只写 `cosa` 并触发一次热控重算，没有 `setChargingLevel`，也没有 `SmartChgMode` 写入。
- beta12 的 `set_charge(level=0, speedChargeAdd=-2)` 广播虽然参数设计正确，但真机日志只显示广播成功，没有进入 `D1()`，不能复现这段自然高功率。

beta17 因此移除后续加入的 GuardElf `SmartChgMode` 读取、写入、恢复和命令白名单，不增加任何守门轮询。随后复核确认，`set_charge(0,-2)` 广播接收器要求发送方持有 `oplus.permission.OPLUS_COMPONENT_SAFE`；Shizuku/ADB 的 shell UID 不持有该签名权限，`Broadcast completed` 只说明命令被受理，实际不会进入 `D1()`。该无效候选已从控制器、Shizuku 白名单、测试和设计书中删除，只保留 `cosa` 写入与一次热控刷新。

## beta18 立即刷新候选（2026-10-24）

`ThermalControlUtils.d1()` 对 `oplus_float_window_show` 的监听调用 `m.j2(null, 0ms)`，与 `m2()` 的约 5 秒延迟不同。beta18 因此把等值字符串切换 `0/00`、`1/01` 作为首选刷新触发器，并保留相机入口作为兼容回退。

本次只验证了代码路径与 JVM 单测；设备在验证时处于安全锁屏且未接 AC，`getCurThermalPolicy` 返回 `null`，不能据此判断刷新失败或功率提升。下一次真机验收必须在物理解锁、真实亮屏、AC 在线和普通前台场景下进行。
## beta19 gear 0 脉冲保护（2026-10-24）

设备逐秒数据库显示 gear 0 在约 41°C 开始出现 10 mA 停顿，应用功率约 0.038 W；关闭 OPPower 后仍可复现，说明不是 `cosa` 写入造成。gear 1 手动保持两轮均未出现 0 W，功率约 33–38 W。

beta19 在最大输出目标且温度达到 40.5°C 时立即切到 gear 1，不做 5 秒确认，并在本段最大输出内保持。新增单元测试覆盖该切换。JVM 单测通过；安装后的真实接电复验仍需确认 0 W 消失和 30 W 以上功率保持。

## beta20：撤销 gear 1 保护、采样与刷新隔离（2026-10-24）

beta19 的 gear 1 自动保护不符合最终要求，beta20 已删除该逻辑和对应测试。gear 0 继续由实时基础档计算 `cosa` 并立即刷新。

应用侧对“LLPower 正常而 OPPower 显示 0 W”增加了两项隔离：

- 使用 `getLongProperty(CURRENT_NOW)`，与 LLPower 的读取路径一致；只有当前值为 0 时才读取 `CURRENT_AVERAGE`。
- 页面每秒刷新不再等待历史 SQLite 写入；采样、写库和控制循环的临时异常都不会终止后续周期。

真机验证：

- 版本码 20、版本名 `1.0.0-beta20`，覆盖安装成功并保留数据。
- 接电后 OPPower 前台，目标 gear 0 可立即形成；无需切换应用。
- 在 SOC 约 57%–59%、温度约 38–39°C 时，系统基础档 5–6，`cosa=-5/-6`，实际功率约 22–24 W，主页面持续刷新。
- 微信无通话悬浮窗、OPPower 前台时，热控类别为 `common_config`，基础档 5–6，策略为 `chargeVal=3, speedChargeAdd=-2`，实际功率约 22–24 W，仍未达到 30 W。

Thermal Levels 写入验证：

- `getThermalStatus` 可正常读取。
- `setThermalControlTest 1`、`setTempLevel 6/7`、`setThermalControlTest 0` 均返回 `cmd not allow`。
- 门控属性 `sys.deepsleep.allow.debug` 为空，当前 shell/Shizuku UID 2000 无法启用测试模式；真实 Thermal Levels 未被修改。

验证通过项：JVM 单测、Debug 构建和覆盖安装。未通过项：gear 0 稳定达到 30 W 以上；需要继续寻找不依赖修改 Thermal Levels 的原生功率条件。

## `appCategory=game` 热控分类对照（2026-10-24）

- 设备：RMX8899 / ColorOS 17；真实亮屏、AC 接电、OPPower 前台；SOC 68%–72%，温度 41.0–41.9°C。
- 临时 Manifest 设置 `android:appCategory="game"` 后，`cmd game list-modes com.oppower` 返回可用模式 `[standard,custom]`，并成功设为 `custom`。
- 修正测试悬浮窗的主线程创建后，系统窗口列表确认存在 OPPower 的 `TYPE_APPLICATION_OVERLAY`；`getCurThermalPolicy` 一度返回 `float_window_common / gearVal=0 / chargeVal=-2 / speedChargeAdd=-2`。
- 单次悬浮窗设置重算日志仍为 `appType=-1`、`configItemName=common_config`、`float={com.oppower}`、`full={com.oppower}`；没有 `category_4_fw_common`，也没有 `setSmartCoolDown(0,0)`。
- 电池侧功率约 24.8 W（5.69 A × 4.356 V）。这与既有 gear 0 水平一致，没有可重复的额外增益。
- 临时测试包能进入 `category_4_fw_common`，依赖厂商热控 XML 中的 `appType=4` 分类；普通 Manifest 的游戏声明不足以复制该条件。
- 已恢复 Manifest、重置游戏模式、关闭悬浮窗测试开关、恢复 Overlay app-op 与 `oplus_float_window_show`，并重新构建安装普通版本。该路径排除，不再重复。

## 2026-10-25 服务接口排除

- `power_monitor`：无可用功率键或 setter，shell 控制还需 `DEVICE_POWER`。
- `horae`：只承担热控状态/温度/屏幕状态交互；shell 调用被 SELinux 拒绝。
- `oplus_battery_stats_service`：只有 binder call stats 与 UID power sipper 查询，均为只读。
- 当前未接 AC，因此没有把任何设置写入结果记为功率收益。`smart_charge_switch_state` 和 `smart_charge_protection_switch_state` 仍待同条件 AC A/B。
- OPPower 前台受微信浮窗影响时策略为 `common_config_float_window_scene_102 / chargeVal=4`；不能与历史 `common_config / chargeVal=0` 高功率段直接比较。

## 智能充电开关 AC A/B（2026-10-25）

- 固定条件：OPPower beta20 前台、AC 在线、计算 gear 0、`float_window_scene_102 / chargeVal=12 / speedChargeAdd=0`。
- `smart_charge_switch_state: 1 → 0`：基线 25.40 W；首轮 26.39 W；恢复后 26.13 W；再次关闭 26.68 W。重复增益约 0.55–0.99 W，温度同步上升，不能视为突破功率层；beta21 按产品决定作为补充提升保留并自动恢复。
- `smart_charge_protection_switch_state: 1 → 0`：26.81 W → 26.48 W，策略不变，不成立。
- 两项测试后均恢复为 `1`，没有新增功率参数。
- 当前 HotPolicy 为 `float_window_scene_102`，而历史 cooldown 0 来自 `common_config / chargeVal=0`。MultiApp 用户 999 的微信仍持有多个现存悬浮窗；对主用户 app-op 写入不会清除这些窗口。

## beta21：补充充电开关与设置权限真值刷新（2026-10-25）

- 控制器在首次生效时保存 `smart_charge_switch_state` 原值并写 0；稳定时不重复写，暂停、拔电、充满和关闭总开关时恢复。原值同时持久化，服务被系统销毁并重建后可恢复。
- 新增生命周期单测，验证相同启用周期只写一次 0，关闭总开关后恢复原值 1。
- 设置页系统状态不再由界面临时缓存：通知权限、通知渠道、电池优化、悬浮窗、无障碍和 Shizuku 安装/运行/授权/服务连接状态统一进入 UI 状态，并在 `onResume`、通知授权回调和 Shizuku 授权回调后刷新。
- JVM 单测、Debug Kotlin 编译、lint（0 error）和 Debug APK 构建通过。
- beta21 已通过 `192.168.10.7:33881` 覆盖安装；`versionCode=21`、`versionName=1.0.0-beta21`，应用启动成功，返回状态为 `WARM / ok`，未出现崩溃日志。
- 真机通知设置页已显示系统通知权限“已允许”和实时活动通知渠道“已开启”，确认界面使用了系统真值。
- 验收时设备未接 AC，服务未运行，`smart_charge_switch_state` 已回读为 `1`；控制期间写 0 和接电增益仍需后续 AC 条件复测。

## beta22：电流与功率 0 W 跳变修复（2026-10-25）

- 真机采样定位：快充时电池服务返回 `CURRENT_NOW` 约 `-9947..-10065`，充电保持、热控 gear 0 保持。0 W 帧与旧实现的 `abs(value) >= 10000` 微安判断完全对应。
- LLPower 2.0.4 反编译确认：来源顺序为 `CURRENT_NOW`、`CURRENT_AVERAGE`、`/sys/class/power_supply/battery/current_now`、`/sys/class/power_supply/main/current_now`、`/sys/class/power_supply/battery/batt_current`；仅 `abs(value) > 20000` 才除以 1000。
- beta22 同步该阈值和来源顺序，并增加无效哨兵过滤、高电量整数安培异常值清理、电压 sysfs 回退；电流与功率共用同一显示电流。
- 新增 4 项边界单测，完整 40 项 JVM 单测、Debug 编译和 lint（0 error）通过。
- beta22 已覆盖安装到 RMX8899，`versionCode=22`、`versionName=1.0.0-beta22`。验收时设备已拔电，因此高强度快充下的连续 1 秒真机采样待重新接电后补测。

## beta23：基础档 10 使用 cosa=-10（2026-10-25）

- 真机同时读到 `Thermal status=10`、`Thermal Levels=[10]`、热控温度约 `43.98°C`、电池温度 `43.0°C`、AC 在线，确认基础档 10 不是界面缓存。
- beta22 会写安全下界 `cosa=-9`，实际 gear 1，并将要求 `-10` 的目标报为 ERROR；这与用户要求基础档 10 仍保持 gear 0 不符。
- beta23 将合法下界调整为 `-10`。基础档 10、目标 0 时写入 `-10` 并标记计算 gear 0 已验证；基础档 11 及以上仍不写越界值。
- 新增基础设施测试：基础档 10 写 `-10` 得到 gear 0；基础档 11 保持 `-10` 并报告目标不可达。
- 42 项 JVM 单测、Debug 编译和 lint（0 error）通过；beta23 已覆盖安装到 RMX8899，`versionCode=23`、`versionName=1.0.0-beta23`。安装后设备基础档已回落到 9 且随后拔电，因此基础档 10 下的 `-10` 真机写入仍待下一次复现确认。

## beta24：最大输出取消应用侧温度分档（2026-10-25）

- 最大输出默认不再使用 41/42/43°C 三段规则，40.0–45.8°C 单测目标均为 gear 0；温度只影响系统原生 `Thermal Levels[0]`，OPPower 随后按新的基础档重算 `cosa`。20–40 W、10–30 W、80% 电量暂停、100%/FULL 暂停和显式自定义规则保持不变。
- 真机在 beta24 下已确认：AC 在线、物理解锁、真实亮屏、OPPower 未切换应用时，约 2 秒内把旧的 `cosa=-6` 自动改成 `-10`；此后 `cosa` 持续回读为 `-10`。该结果确认首次开启和基础档变化无需等约 5 秒刷新回退路径。
- 2026-10-25 17:46:58–17:48:22 做了只读连续采样，共 27 行。条件为 SOC 72%、电池温度 42.8–43.2°C、手机温度 46–47°C、`Thermal Levels=[11]`、`cosa=-10`，因此计算 gear 为 1；`cosa` 已到合法下界，目标 gear 0 暂时不可达。该时段 `getCurThermalPolicy` 始终返回 `null`，只能说明策略对象当前不可回读，不能据此认定原生热控失效。
- 同一次真机观察中，接电后的最初约 90 秒只有约 2–6 W，随后升至约 21.9–23.6 W；没有达到持续 30 W+，也不算完成了功率提升目标。采样电流仍出现 `0`、约 `1.0 A` 和约 `-1.4 A` 的不同状态，需要与充电器协商、SOC、温度和系统热更新分开判断，不能只看 `cosa` 或 `gear`。
- beta24 修正了界面说明和规则引擎返回文案，避免把最大输出继续描述为“温度适配”。本轮未重新加入 `setChargingLevel`、`set_charge`、`SmartChgMode`、守门轮询或人为升温；流体云通知和黄色闪电图标设计未改动。
- 补齐 `oplus_float_window_show=null` 的即时刷新路径：系统源码确认监听器无论新值为何都会调用 `d1()` 并在 0 ms 请求 `j2()`，且 `null` 与 `0` 都表示非浮窗场景。beta24 将该值一次性规范化为 `0`，保留策略语义的同时触发即时重算；`null → 0` 和等价写法切换已有 JVM 测试覆盖。
- beta24 的 42 项 JVM 单测全部通过，Debug 编译、lint（0 error，20 warning）和 APK 构建通过。APK 位于 `release/1.0.0-beta24/OPPower-1.0.0-beta24-debug.apk`，SHA-256 为 `FACE8FD9C6BB5ACBA097CFC7AAD4ED4F9488780836394FAFEB056D328C617DC0`，已覆盖安装并回读 `versionCode=24`、`versionName=1.0.0-beta24`。稳定 30 W+ 仍未验证，下一轮重点是充电器/线材协商、系统基础档与实际电流上限的同条件对照。
## beta25：AC 快充伪零采样修复（2026-10-25）

- 历史库核对发现，AC 在线且电压、温度、SOC、充电状态正常时，仍有多段持续 3–30 秒的 `current_ma=0`、`power_w=0`，共 33 条；确认问题来自 `BatteryManager.CURRENT_NOW` 偶发返回 0，而非图表或界面绘制。
- LLPower 2.0.4 有两条采样路径：`BatteryEngine` 使用 `CURRENT_NOW → CURRENT_AVERAGE → current_now/main current_now/batt_current`；`ChargeEngine` 在 sysfs 缺失时解析 `dumpsys battery`。beta25 将 dumpsys 路径收窄为只读后备：仅 AC 在线、SOC `<99`、状态不是 FULL，且主电流为 0 或电压不在 `3000..5500 mV` 时触发。
- 后备电流和电压必须来自同一次 `dumpsys battery` 输出；后备值缺失、为 0、命中无效哨兵或超出合理范围时不替换主采样。beta26 对 AC 未充满时仍连续出现的伪零值增加了 3.5 秒上限的同帧可靠值纠错；超过上限、拔电或充满后立即接受零值。正常非零变化不做平滑或防抖，也不会把不同帧的电流与电压拼接。
- Shizuku AIDL 新增固定只读 `readBatteryFallback()`，user service 内执行命令的超时为 1.5 秒；页面和控制服务初始化时预先绑定，主进程等待绑定最多 350 ms，调用保持在后台采样线程。
- 新增测试覆盖触发边界、数值配对、异常后备和 `dumpsys battery` 解析；完整 47 项 JVM 单测、Debug Kotlin 编译和 lint（0 error，20 warning）通过。
- 本轮未修改通知、流体云、黄色闪电图标、微信、`cosa` 控制逻辑或任何充电写入命令；接电后的真机连续采样仍是 beta25 的待验收项。
## beta26：后台功率回落与偶发零值复核（2026-10-25）

- 用户真机反馈：保持亮屏快充时把 OPPower 放到后台，功率跳动；切回前台后恢复。静态排查确认旧 `onDestroy()` 会在 Android 销毁/重建前台服务时调用 `restoreAll()`，把 `cosa` 与辅助控制开关恢复默认，属于应用主动撤销控制而非系统热保护。
- beta26 已删除该生命周期恢复动作。系统停止服务时保留控制设置，`START_STICKY` 重启后重新初始化 Shizuku 并按当前热控基础档恢复目标；用户关闭总开关、拔电、SOC 暂停、充满或点击一键恢复时仍在控制循环内显式恢复。
- 新增 `CurrentSampleStabilizer`：仅处理 AC 在线且未充满时的瞬时全零采样，最多复用 3.5 秒最近一帧可靠电流和电压。拔电、达到 99%/FULL 或持续超过窗口后立即采用零值；正常非零样本直接更新，不做防抖或平滑。
- 修正此前文档对 LLPower 的过度简化：实际同时存在 BatteryEngine 的属性/sysfs 路径，以及 ChargeEngine 的 dumpsys 后备路径。OPPower 按这些只读来源组合，并确保后备电流和电压取自同一帧。
- JVM 单测覆盖 AC 伪零保持、超时接受零、拔电与充满禁止复用、正常非零变化立即采用。完整 53 项 JVM 单测全部通过（0 failure、0 error、0 skipped）；Debug Kotlin 编译、`assembleDebug` 和 Android lint（0 error，20 warning）通过。APK 核对为 versionCode 26、versionName 1.0.0-beta26、v2 debug 签名有效，归档与根目录副本 SHA-256 一致。
- 真机后台 3 分钟 A/B 与 logcat 生命周期确认需在 beta26 安装后执行。

## beta27：恢复系统智能充电默认值（2026-11-06）

- 移除 `smart_charge_switch_state` 的全部应用侧读取、写入、恢复和持久化状态，并从 Shizuku Gateway 与固定命令白名单移除该键；控制会话只保留 `cosa_ctc_value` 写入与一次热控刷新。
- 删除 3 项只覆盖旧开关读写/恢复的控制器测试，并新增命令白名单拒绝测试；当前完整 50 项 JVM 单测全部通过（0 failure、0 error、0 skipped）。
- Debug Kotlin 编译、`:app:assembleDebug` 和 Android lint 通过；lint 为 0 errors、20 warnings。Debug APK 使用 v2 debug 签名，versionCode 27、versionName 1.0.0-beta27。
- APK 位于 `release/1.0.0-beta27/OPPower-1.0.0-beta27-debug.apk`，SHA-256 为 `EADD75F9A268D304161E9583F65BF5D7C5B926C9B8D741E04213139F19580B92`；归档与根目录副本哈希一致，已覆盖安装到 RMX8899。
- 安装后通过 `192.168.10.5:43633` 将 `smart_charge_switch_state` 恢复为 `1`。在 OPPower 前台服务运行且 `cosa=-10` 活跃控制期间，连续 6 次采样（约 11 秒）系统值始终为 `1`，没有再被改回 `0`。
## beta27 充电记录时间加权与长时间内存优化（2026-11-22）

- 充电记录的平均功率、平均电流、平均温度和充入 mAh 改为按相邻采样实际间隔进行梯形加权；3 秒、5 秒和未充电 180 秒采样都能兼容。超过 5 分钟的异常空档会跳过加权与积分，峰值和最高温度仍保留全部样本。
- 内存中不再为每条记录复制完整原始点列表。SQLite 继续保留 24 小时用于记录摘要，主页面和曲线总线只驻留最近 8 小时；当前会话改为 O(1) 增量累计，新采样不再重扫整段会话。
- 新增不等间隔梯形平均、异常空档排除、批量与增量结果一致、8 小时内存窗口测试。完整 62 项 JVM 单测通过（0 failure、0 error）；`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 通过，lint 为 0 errors、20 warnings。
## beta27 无损控制循环优化（2026-11-22）

- 设置变化、接电和通知恢复动作不再并发执行“立即 evaluate + 控制循环再次 evaluate”；服务只通过 conflated 唤醒通道执行一轮即时控制，并保留该动作的强制通知与日志。
- `cosa_ctc_value` 与 `Thermal Levels` 仍每秒读取，基础档变化、外部偏移漂移和用户档位切换仍会在当前周期重算并写入。仅用于诊断的 `getCurThermalPolicy.chargeVal/speedChargeAdd` 增加 3 秒缓存，热控刷新成功时立即失效缓存；用户手动“读取档位”仍绕过缓存读取新鲜值。
- 新增测试确认连续两个 1 秒控制周期只执行一次诊断 policy 查询，但热控状态仍每周期查询；超过 3 秒后才重新读取 policy。完整 63 项 JVM 单测通过（0 failure、0 error）；`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 通过，lint 为 0 errors、20 warnings。
- Debug APK 已覆盖安装到 RMX8899，保留现有设置并成功启动。功率提速 A/B 待电量降至 60%–70% 后在相同充电器、线材、温度、前台场景和计算 gear 下执行；本轮没有新增或恢复任何原生充电级写入。
## 液态玻璃视觉升级（2026-11-22）

- 主页面、强制亮屏快充、应用设置、记录详情、FAQ 与关于页统一使用静态深色玻璃面板；原有页面层级、控件位置、点击区域和功能入口保持不变。
- 根据真机反馈撤掉了彩色极光、移动光带和整面白雾，页面背景改为低对比的深青纵向渐变，仅保留一处低透明度冷色柔光；卡片使用半透明深色填充、发丝级高光边、柔和阴影，主卡片左上角保留局部反光和短弧高光。
- 深色与浅色主题、六套配色、卡片不透明度和页面比例继续生效；本次只调整视觉材质与对比度，没有修改数据刷新、曲线采样、通知、充放电流或 Shizuku 权限白名单。
- 63 项 JVM 单测、Debug 编译和 lint 均通过（0 error、20 warning）。最新 Debug APK 已覆盖安装到 RMX8899 并截取主页面截图；安装后的无线调试连接随后断开，后续视觉复核需重新连接。


## Level 0 高速保活与息屏开关（2026-11-22）

- 主页面“强制亮屏快充”下方新增默认关闭的 Level 0 高速保活开关；二级页总开关卡片底部新增默认关闭的“息屏也生效”开关，二者均持久化到本机设置并即时唤醒控制服务。
- Level 0 是独立控制路径：进入时先恢复 `cosa=0`，首次立即下发 `setChargingLevel 0`，之后最多每 3 秒复写一次；不调用普通 temperature/SOC 分档，也不写普通 gear 目标。
- 80% 本次暂停、100%/FULL、拔电、关闭总开关、关闭 Level 0、息屏暂停和一键恢复都会先请求安全级 `setChargingLevel 2`，再恢复 `cosa=0`。如果安全级未被 Shizuku 或 BatteryService 确认，控制状态显示 ERROR 并继续保留服务重试，不谎报已恢复。
- Shizuku 白名单仅新增完整固定命令 `setChargingLevel 0` 与 `setChargingLevel 2`；档位 1、其他值、分号拼接和未知命令继续拒绝。
- 新增 10 项控制器测试，覆盖首次立即下发、3 秒轮询节流、失败重试、普通 gear 不参与、拔电/80%/充满/息屏恢复、息屏开关开启继续保活、一键恢复安全级以及恢复安全级失败不再谎报成功。完整 73 项 JVM 单测通过（0 failure、0 error、0 skipped）。
- 主页面暂停状态不再显示“Level 0 接管中”：只有 ACTIVE 且原生 Level 0 已确认时显示接管中；80%、充满、息屏和温度保护明确显示已恢复系统默认。一键恢复通知改用控制器返回的真实状态，安全级 `setChargingLevel 2` 未被确认时直接显示恢复失败；即使总开关和实时通知都已关闭，恢复失败仍会保留本地重试循环。
- 修复主页面状态同步：总开关已保存为开启、但未接电且控制服务尚未启动时，不再把上次的 DISABLED 状态显示成“总开关未开启”，改为“已开启 · 接电后自动开始”。
- `:app:assembleDebug` 与 `:app:lintDebug` 通过；lint 为 0 errors、20 warnings。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，大小 11,140,917 bytes，SHA-256 为 `DC596AE312C8761D4098966542FC859A916E918510E03311E7380362B0D6904E`。
- 2026-11-22 通过 `192.168.10.7:43693` 连接 RMX8899 并成功覆盖安装；保留原有设置，设备当时未接电、电量 71%。主页面真机截图确认“Level 0 高速保活”卡片位于“强制亮屏快充”下方；安装后的 SharedPreferences 回读为 `master_enabled=true`、`native_level_zero_enabled=false`、`screen_off_boost_enabled=false`。
- 二级页“息屏也生效”和 Level 0 实际下发仍未完成真机交互验收：安装后手机被用户切到其他应用使用，为避免误触和窥屏未继续自动点击。Level 0 的实际增益、3 秒复写与 ColorOS 回写的长期冲突仍需在同一充电器、线材、SOC、温度、前台场景下单独 A/B，完成前不宣称固定瓦数提升。

## 高功率保活独立开关（2026-11-23）

- 主页面新增卡片已改为“高功率保活”，标题下按用户原话显示“（强制亮屏快充不行再试试这个）”。用户可见文案不再出现 Level 0、复写或分档等内部术语；固定原生命令仍只存在于内部 Shizuku 白名单。
- 保活路径已真正独立于“强制亮屏快充”总开关：总开关关闭而保活开启时，控制器仍进入保活路径；关闭总开关不会停止保活。关闭保活且总开关也未开启时才恢复安全级，且始终先请求 `setChargingLevel 2`，再恢复 `cosa=0`。
- 服务启动、电源广播、开机恢复、设置变更、通知与循环停止条件都已纳入独立保活开关。修复了“同时关闭最后一个充电增强开关时服务先退出、来不及恢复”的竞态：现在会先执行一次显式安全恢复，失败时继续保留本地重试容器。
- 二级页“息屏也生效”开关保持位于总开关卡片内；关闭时熄屏暂停普通快充与高功率保活，开启后两者都可在熄屏时继续。
- 控制器侧新增 13 项保活与安全恢复测试，覆盖首次立即下发、3 秒节流、失败重试、总开关关闭仍启动、关闭总开关不停止、关闭保活恢复安全级、安全恢复失败跨周期重试、拔电/80%/充满/息屏恢复、息屏开关开启继续保活以及一键恢复失败不谎报。完整 76 项 JVM 单测通过（0 failure、0 error、0 skipped）；`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 均通过，lint 为 0 errors、20 warnings。
- 最新 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，大小 11,140,888 bytes，SHA-256 为 `777777FEE0532576C0C738F6333D532B05784BAE757D8FB75A97F984DDD978D6`。本小节的构建信息取代上一小节同一未归档 beta27 的旧 APK 大小/哈希记录。
- 2026-11-23 通过 `192.168.10.7:43693` 覆盖安装到 RMX8899。安装后 SharedPreferences 回读为 `master_enabled=false`、`native_level_zero_enabled=true`、`screen_off_boost_enabled=false`、`live_notification=true`，正是“总开关关、只开保活”的目标组合。
- 验收时设备未接电（AC/USB 均未连接，电量约 70%），因此尚未观察到真实 `setChargingLevel 0` 下发和功率变化；不能据此宣称固定瓦数提升。接电后仍需确认服务启动、保活命令、息屏开关、关闭保活/一键恢复的 `2` 级恢复，以及 3 秒轮询的实际稳定性。

## beta28 待定：控制稳定性与耗电复核（2026-11-23）

- 修复服务生命周期崩溃风险：`syncStatusNotification` 在关闭最后一个充电开关时会移除前台通知，却没有清除本地 `foregroundStarted` 标记。下一轮控制周期再次 `startForeground()` 时 Android 12+ 会抛出 `ForegroundServiceStartNotAllowedException`，使控制循环永久丢帧。现在通知只在前台服务确实需要展示时才更新，移除时统一复位标记。
- 修复“最后一个开关关闭时安全恢复失败”分支：原实现会在没有前台通知的情况下 `startControlLoop()`，而 `onStartCommand` 返回 `START_NOT_STICKY`，进程被回收后安全恢复即中断。现在该分支先以“正在恢复系统默认”重新进入前台，再保留重试。
- 控制循环空闲退避：所有充电增强开关关闭（仅保留通知）时不再每秒读取电池、评估控制器并触发 `settings` 子进程，改为 1 秒周期的纯等待，任何设置变更、屏幕事件或接电广播仍会立即唤醒。
- 安全恢复重试增加息屏条件：`setChargingLevel 2` 恢复失败时，只在亮屏期间每秒重试；息屏后暂停重试，亮屏后立即恢复。避免整夜每秒一次 Binder + dumpsys 唤醒。
- 曲线记录与会话绑定：曲线采样只在总开关或高功率保活开启时写入。此前关闭全部控制后，服务仍会为“实时通知”每 180 秒唤醒并写库，与“关闭控制即停止采样”的文档描述不一致。
- 电流零值纠错窗口由 5.0 秒改回文档约定的 3.5 秒；单测中原本显式传入 3.5 秒，默认值一直未被覆盖。
- 冷启动与 Shizuku 重连：`ShizukuGateway` 注册 `addBinderReceivedListenerSticky` 与 `addBinderDeadListener`，授权授予或 Shizuku 重启后立即重新绑定 user service，不再依赖下一次“命令触发绑定”。
- 设置页“本地数据”占用统计补入 `databases/chart-history-24h.db` 及 WAL/SHM 文件；此前只统计 `files/`，导致 24 小时采样库不计入。
- 热控正则表达式提升为 `companion object` 常量，避免每秒控制周期重复编译正则。
- 本轮验证：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（76 项，0 failure / 0 error）、`:app:lintDebug`、`:app:assembleRelease`（R8 混淆通过，Shizuku 反射类保留规则有效）均通过。以上为代码级回归，仍需在 RMX8899 上复验：接电后连续开关切换、关闭最后一个开关时的安全恢复、息屏 30 分钟后 `dumpsys batterystats` 的唤醒次数。

## beta29：充电检测、COUI 界面规范与设置扩展（2026-11-23）

本轮是三项需求的合并交付：充电检测参照 Scene 的信息分层、界面参照 [COUI-Kit](https://github.com/ItosEO/COUI-Kit) 的 ColorOS 17 度量、设置页在「每项必须真实接上功能」的前提下扩容。

### 充电检测（`battery/ChargeInspector.kt`）

- 数据来源为 `sysfs/power_supply` 只读快照，经 Shizuku user service 新增的白名单命令 `readPowerSupplyDump()` 一次性取回 `battery/usb/main/charger/pc_port/wired/wireless/otg` 各节点的 `voltage_max`、`current_max`、`charge_full`、`charge_full_design`、`cycle_count`、`temp`、`usb_type`、`online` 等字段。**只读**，不写任何节点。
- 解析层 `PowerSupplyDump.parse()` 采用 `[dir] name` + `k=v` 行格式，坏行只丢弃该行，不影响整包；`normalizeMicro()` 按量级把 µV/µA/µAh 归一化到 mV/mA/mAh，避免不同机型单位差异造成 1000 倍误差。
- 信息分层刻意对齐 Scene：界面第一层是「充得多快」，第二层「为什么慢」，第三层才是「电池健康」。电量、电压、电流、功率四个大字指标在最上，协议、阶段、预计充满在其下；充电器能力与利用率单独成行，用来说明「当前 18 W 是充电器只给 18 W，还是给了 66 W 但手机只用 18 W」。
- 充电阶段判定：`HANDSHAKE`（刚插入、电流未稳定）→ `CONSTANT_CURRENT`（恒流，电压未到阈值）→ `CONSTANT_VOLTAGE`（电压达到 4.36 V，双芯按 2 倍换算）→ `TRICKLE`（电流 < 150 mA 且电量 ≥ 95%）→ `FULL` / `DISCONNECTED`。
- 预计充满时间用斜率法：取最近若干采样电量增量的中位数换算成 %/min；低于 0.05 %/min 时不估算，直接说明「电流过小，无法预估」，不编造数字。
- 稳定性评分用采样窗口内电流的变异系数映射为 0–100 百分比，用于区分「功率波动是快充协商在跳档」还是「线材/接口接触不良」。
- 效率估算按 2% 线损折算，仅作量级参考，界面文案明确标注为估算值。
- 异常归因 `summarizeAnomalies()` 覆盖：充电器能力充足但实际功率偏低（利用率 < 35%）、温度偏高（≥ 42 ℃）、电压达恒压阈值后仍在拉大电流、协议为普通 USB 但电流异常高、电池健康度 < 80% 等。
- **双读互斥**：检测数据在前台由 ViewModel 读、息屏由服务读。`ChargeControlBus.pageVisible` 保证界面可见时服务侧跳过检测，避免同一秒内两次 Shizuku 调用。
- 检测间隔 3/5/10/20/30 秒可选，异常提醒与充满提醒是两个独立通知通道（`ALERT_NOTIFICATION_ID = 2109`），不复用主状态通知。
- 单元测试 `ChargeInspectorTest` 新增 14 项，覆盖解析健壮性、协议识别、健康度、阶段判定、剩余时间、稳定性、效率与异常归因。

### 界面：COUI 度量独立实现（`ui/CouiUi.kt`）

- COUI-Kit 的 AAR 在 `AarMetadata` 中要求 `compileSdk 37.1`、`minAgpVersion 9.1.0`、Kotlin 2.4.20、Compose 1.13 与 `minSdk 31`，而本项目基线是 AGP 8.10.1 / SDK 36 / Kotlin 2.1.21 / minSdk 26。直接引入会导致整个工程升级到尚不稳定的工具链，因此选择**按公开度量独立实现等价组件**，而不是引入二进制依赖。
- `Coui` 度量令牌覆盖：页面左右 16dp、行内 16dp、行高 48dp、卡片 24dp 连续圆角、分隔线 0.33dp、组标题与脚注内缩 32dp、开关 44×24 配 18dp 滑块与 3dp 边距、分段选择器 38dp。
- 组件集：`CouiPage`、`CouiCard`、`CouiGroup`、`CouiRow`、`CouiDivider`、`CouiSwitchRow`、`CouiSwitch`、`CouiSegmented`、`CouiChoiceRow`、`CouiSearchField`、`CouiStatusDot`、`CouiPageHeader`。
- 开关带触感反馈（`HapticFeedbackType.TextHandleMove`）与弹簧动画（`dampingRatio = 0.72`）；分段指示器跟手插值，点击即提交，不做二次确认。
- 主页面分组标题同步改为 COUI 组标题样式（12sp Medium / 次要色 / 内缩 20dp），去掉原来的彩色竖条，与设置页保持同一视觉语言。
- 许可页新增「界面规范：COUI-Kit · MPL 2.0」署名。

### 设置扩展（每项均真实接入）

| 新增项 | 接入位置 | 实际行为 |
| --- | --- | --- |
| 设置搜索 | `SettingsHomePage` | 按标题、摘要与 `keywords` 过滤 5 个分组，空结果给出提示 |
| 充电检测开关 | `MainViewModel.refreshInsight` / 服务 `maybeRunDetection` | 关闭后不再读取 `power_supply`，检测卡片隐藏 |
| 检测间隔 | 同上 | 3–30 秒真实改变服务与前台刷新节奏 |
| 异常提醒 | `ChargeControlService.postAlert` | 命中异常时发独立通道通知，同一次异常不重复轰炸 |
| 充满提醒 | 同上 | 进入 `FULL` 后发一次提醒 |
| 显示电池健康 | `ChargeDetectCard` | 控制健康度区段显隐 |
| 通知样式 | `notificationBatteryData` | 三档裁剪通知内容：极简只留电量 / 标准加功率 / 详细加温度与阶段 |
| 列表密度 | `ProvideCouiMetrics` → `LocalCouiMetrics` | 紧凑 42dp + 字号收一档，对全部页面生效 |
| 导出为 CSV | `ChartHistoryStore.exportCsv` + FileProvider | 24 小时曲线写 `cache/export/`，经 `ACTION_SEND` 分享；带二次确认 |
| 重置全部设置 | `PreferencesStore.resetAll` + `controlSettingsChanged` | 恢复默认值并保留首次引导已读标记；带二次确认 |

界面密度通过 `CompositionLocal` 在根布局注入一次，设置一改全页立即生效，无需重启 Activity。

### 本机构建验证

- `:app:testDebugUnitTest`：**90 项通过，0 failure / 0 error / 0 skipped**（beta28 基线 76 项 + `ChargeInspectorTest` 14 项）。
- `:app:lintDebug`：**0 errors, 20 warnings, 1 hint**；warning 均为存量项（`SmallSp`、`allowBackup` 弃用、KTX 建议等），本轮未新增 lint 分类。
- `:app:assembleRelease`：R8 混淆通过，Shizuku 反射类保留规则仍有效。产物 `app/build/outputs/apk/release/app-release-unsigned.apk`，大小 1,654,666 bytes，SHA-256 `2A9832F677EB6AA33DDE28F7B8141BECF3B45930DF43ED74A5F5FF02156148A2`。

### 仍需真机复验

- `power_supply` 各字段在 RMX8899 上的实际可读性与单位量级，尤其是 `charge_full` / `charge_full_design` 与 `cycle_count` 是否存在。
- 充电阶段判定阈值（4.36 V 恒压点、150 mA 涓流门限）在该机型上的实际表现。
- 预计充满时间的斜率估算在真实充电过程中的误差范围。
- 检测间隔导致的 Shizuku 调用频率对续航的影响（建议对比 5 秒与 30 秒两档的 `dumpsys batterystats`）。
- 新界面在深色/浅色、六套配色与三档列表密度下的实际渲染效果。

## beta31：按 COUI-Kit 源码重构

beta29 只把 COUI 的度量"抄"进了 `ui/CouiUi.kt`（564 行，一堆各写一段的 Composable）。beta31 按 COUI-Kit 的**结构**重做，拆成形状 / 度量 / 主题 / 反馈 / 组件五层，并把两个最大的页面（`MainPage` 1410 行、`ChargeSettingsPage` 863 行）从"自定义 `GlassPanel` 堆叠"改成 `CouiGroup` / `CouiRow` 的分组语义。

### 结构变化

| 位置 | beta29 | beta31 |
| --- | --- | --- |
| 圆角 | `RoundedCornerShape(24.dp)` | `CouiShape(24.dp, cornerWeight = 3f)` 超椭圆 |
| 主题下发 | `private var activeColors`（可变全局量） | `LocalCouiColors`（`CompositionLocal`） |
| 色引用 | 300+ 处顶层全局 `val Cyan/Surface/...` | 全部集中到 `CouiTheme.colors.*` |
| 快充设置页 | 11 张自定义卡片各写 padding | 6 个 `CouiGroup` + `CouiSwitchRow` / `CouiRow` |
| 主页面开关 | Material3 `Switch` | `CouiSwitch`（44×24，弹簧 `0.72/720`） |
| 主页面按钮 | Material3 `Button` / `TextButton` | `CouiPrimaryButton` / `CouiSecondaryButton`（胶囊） |
| 界面文件 | `ui/CouiUi.kt` | `ui/coui/{CouiShape,CouiTokens,CouiTheme,CouiFeedback,CouiComponents}.kt` |

### 修掉的实际缺陷

1. **主题切换会弹回首页**：根因是 `activeColors` 是可变全局量，Composition 读不到变化，只能靠重建子树刷新。改为 `CompositionLocal` 后就地重组，页面状态不再丢。
2. **页面被挤窄**：`CouiPage` 的水平 padding 与 `CouiGroup` 自带的 16dp 叠加成 32dp 双层缩进。现在只由 Group 提供边距，页头单独取 16dp。
3. **圆角肩部发硬**：`RoundedCornerShape` 在圆弧-直边交界处曲率突变。超椭圆按等值线采样，肩部连续。
4. **短点按没有反馈**：旧实现依赖 `ripple`，快速划过时几乎看不见。新的按压蒙层保证"最短一次完整变暗"（0.7 进度下限）。

### 功能保留核对

界面改动集中在表现层，业务调用点未动：

- `:app:testDebugUnitTest`：**90 项通过，0 failure**（与 beta29 完全一致，条数未减）。
- `:app:lintDebug`：**0 errors, 21 warnings, 1 hint**（beta29 为 0/20/1，新增 1 条是 `MainPage` 既存的小字号，因卡片字号统一后位置前移）。
- 设置页 22 个入口、充电检测 8 个参数、自定义规则的温度/电量两组编辑、历史记录、两张曲线、CSV 导出、关闭屏幕显示、一键恢复——全部保留原有回调与副作用。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta31/OPPower-1.0.0-beta31-debug.apk` | 10,910,669 bytes | 31 |
| `release/1.0.0-beta31/OPPower-1.0.0-beta31-release-unsigned.apk` | 1,654,666 bytes | 31 |

### 仍需真机复验

- 超椭圆在 24dp / 屏幕 480dpi 下的实际采样密度（`segments` 由扇高容差 0.05px 反推，低 dpi 设备可能出现折线感）。
- 快捷控制两张卡与指标区三列在紧凑密度下的换行与截断。
- 深色主题下 `card` 与 `background` 的色差是否足以让 24dp 圆角"立起来"（无投影后完全依赖色阶）。

## beta32：Shizuku 受限通道与二级限流（2026-11-23）

### 为什么要第二层控制

`cosa_ctc_value` 只能取 `-10..9`，系统按 `calculated gear = thermal base + cosa` 折算，粒度被**基础档**绑死：基础档为 8 时，cosa 每 ±1 就是整档跳变，40 W 与 25 W 之间没有任何中间状态。真机上表现为"调一格太烫、退一格太慢"。

`/sys/class/power_supply/*/constant_charge_current_max` 是**连续**毫安量，可以直接把电流压在任意值上。两者叠加才是完整控制：

- 档位（cosa，离散）负责让系统进入正确的功率族；
- 电流上限（sysfs，连续）负责在族内把功率压到目标值。

### 特权通道的收口方式

不把 `echo > /sys/...` 加进 `AllowedCommands`——那等于把任意节点写能力交给上层。改为在 AIDL 里新增三个方法（事务号 5/6/7）：

```aidl
int[] readChargeLimitNodes() = 5;
int[] writeChargeLimitNodes(int currentMa) = 6;
int[] restoreChargeLimitNodes(in int[] originalRaw) = 7;
```

节点路径写死在 `PrivilegedCommandService` 内（`battery` 与 `main` 两处候选），调用方**只能传毫安值**，传不了路径；`require(plausibleMa)` 拦 500 以下与 12000 以上的值。第三版曾漏掉 `in` 修饰符，生成桩把数组当成 out-only，Kotlin 侧报 `overrides nothing` + `Too many arguments`。

`AllowedCommandsTest` 新增两条断言锁定这件事：`cat`/`echo` 到这两个节点的命令仍被 shell 通道拒绝；`ChargeLimitNodes.pathsFor()` 的返回值必须以固定前缀开头、末尾就是字段名、不含 `..`。

### 单位自适应

QCOM 常上报 µA（`3000000`），MTK 与部分 OPPO 平台直接写 mA（`3000`）。写错单位等于写错 1000 倍，所以按量级判断（`> 20000` 视为 µA），读不到时保守按 µA 写，并用 `plausibleMa` 兜底。`ChargeLimitScaleTest` 覆盖 12 个用例。真实机型是否接受写入、回读是否等于写入值，仍需真机确认——`ChargeLimitReading.confirmed` 就是为此准备的。

### 收敛参数

| 参数 | 值 | 理由 |
| --- | --- | --- |
| 单步上限 | 400 mA | 跃变会让充电器重新协商，大跨步还可能被系统判为"充电异常" |
| 滞回 | 150 mA | 1 秒循环里为 50 mA 反复写特权节点不值得 |
| 步进冷却 | 2.5 s | 给充电器留重协商时间，避免抖动 |
| 温度系数 | 40 °C 枢轴，±1 °C = ∓6% / +3% | 高温收益最大，低温放宽要更保守 |
| 电量系数 | 60% 起 -0.7%/1%，85% 起再 -1.6%/1% | 高 SOC 段继续灌大电流只发热不增速 |
| 稳态核对 | 10 s 一次，容差 100 mA | 系统热控会在后台把节点刷回默认 |

### 退出路径

`applyChargeCurrentLimit` 在"不需要限流"时返回 `null`，由调用方 `restoreChargeLimit()` 写回原始值。已挂载的退出路径：拔电、满电（100%）、SOC 锁、`ControlTier.PAUSED`（含息屏暂停与温度暂停）、关闭总开关、一键恢复、进入 Level 0 保活（否则会出现"保活却限着流"的自相矛盾状态）。恢复是幂等的，失败时保留 `appliedChargeLimitMa` 让下一轮重试，不升级成 ERROR 打断 cosa 的安全恢复。

### 顺带修掉的往返浪费

1 秒控制循环原本每轮 3 次 Binder 往返（cosa、thermalStatus、条件性 policy）。热控状态加 2 秒短缓存后降到约 1 次/2 秒；`requestThermalRefresh()` 成功会主动失效缓存，所以"刚触发重算就立刻重读"这条关键路径不会被缓存吃掉。`diagnosticPolicyAndThermalStatusAreCached` 锁定了这个行为：t=2s 与 t=3s 两轮 `evaluate()` 只真读一次 `thermalStatus`。

### 验证

- `:app:testDebugUnitTest`：**125 项通过，0 failure**（beta31 的 90 项全部保留，新增 35 项）。
- `:app:lintDebug`：**0 errors, 21 warnings, 1 hint**（与 beta31 持平，无新增类别）。
- 新增用例分布：`ChargeCurrentLimitTest` 19 项（`targetMa` 五个 null 分支、档位单调性、温度/电量收紧、手动档覆盖与夹紧、`stepToward` 的首次写入/滞回/对称步进/冷却/边界）、`ChargeLimitScaleTest` 12 项、`ChargingControllerTest` 5 项（写入一次后恢复、无节点时不破坏 cosa、漂移重写、稳态不反复写、拔电恢复）、`AllowedCommandsTest` 2 项。

### 仍需真机复验

1. 本机 `constant_charge_current_max` 是否可写、`readBackMa == appliedMa` 是否成立（看 UI 的"系统回读"行是绿色还是琥珀色）。
2. 限流生效后外接功率计的读数：3 A 档是否真的压到 ~12 W（双芯机型电压约 8 V）。
3. 400 mA 步进是否会被 ColorOS 判为充电异常（观察通知栏是否出现"充电缓慢"提示）。
4. 系统热控在多少秒内会把节点刷回默认——10 秒核对间隔是否够用。
5. Shizuku 服务升级：`UserServiceArgs.version = BuildConfig.VERSION_CODE`，versionCode 变化会重启特权进程；若不重启，新事务号会抛 `TransactionException` 并被 `runCatching` 吞成 null，表现为"待探测"。

## beta33：beta32 遗留问题修正（2026-11-23）

### 1. 漂移基线的语义错误

beta32 用 `appliedChargeLimitMa`（我们想要的値）当漂移基准。问题在于部分平台会把写入值**吸附**到自己的档位表（写 3000、回读 2900），此时 `|actual - applied| = 100` 恰好等于容差，或更大一点就会触发"重写"，而重写结果仍然是 2900——10 秒一次的无效特权写入，永远不会收敛。

改为新增 `actualChargeLimitMa`，只在写入后回读成功时更新，漂移判断用它与当前回读值比较。`snappedNodeDoesNotCauseEndlessRewrites` 锁定：模拟 `ma -> ma/100*100-100` 的吸附，连续 6 轮核对后写入次数不再增长。

### 2. 节点归零等于禁止充电

`constant_charge_current_max = 0` 在部分平台不是"不限流"而是"禁止充电"。beta32 在 `effectiveLimitMa <= 0` 时只把 `chargeLimitSupported` 记成 false 就放弃，最坏结果是设备插着电不涨电——这比限流失效严重得多。

现在先调 `rescueZeroChargeLimit()`，按「首次介入前原始值 → 上次实际生效值 → 2000 mA」的顺序挑第一个落在 500–12000 的值写回，再标不支持。`zeroedNodeIsRescuedToASafeValue` 用 `limitSnapTo = { 0 }` 模拟必然归零的平台，断言至少发生两次写入且最后一次 ≥ 1000 mA。

### 3. 恢复时拒绝不可信原始值

`ChargeLimitScale.usableRaw(raw)` 拦掉 0 与负数。写入时节点就是 0 说明系统当时处于异常态，把它存档再恢复等于把「禁止充电」永久钉死。`onlyPositiveRawIsRestorable` 与 `restorableOnlyWhenOppowerWroteIt` 覆盖。

### 4. lint 从 21 条压到 2 条

| 类别 | 处理 |
| --- | --- |
| `UnusedResources` ×2 + `SmallSp` ×4 + `HardcodedText` ×1 | 删除 `notification_charge_status.xml` 与 `ic_notification_tracker_dot.xml`（全局 grep 无引用） |
| `UseKtx` ×9 | 引入 `androidx.core:core-ktx:1.13.1`，改 `edit {}` / `transaction {}` / `toUri()` |
| `ModifierParameter` ×2 | `CouiGroup` / `CouiPage` 的 `Modifier` 提到首个可选参数，14 个调用点改具名 |
| `AutoboxingStateCreation` ×1 | `mutableStateOf(0)` → `mutableIntStateOf(0)` |
| `DataExtractionRules` ×1 | 新增 `res/xml/data_extraction_rules.xml`，云备份与设备迁移全排除 |

剩余：`AndroidGradlePluginVersion`（Gradle 8.13 → 8.14.8），属构建工具链升级，不影响产物正确性，未在本轮处理。

> `resetAll()` 必须用 `edit(commit = true)`：它紧接着要 `introSeen()` 读回标记，`apply()` 是异步落盘，会把刚写的值读丢。

### 验证

- `:app:testDebugUnitTest`：**129 项通过，0 failure**
- `:app:lintDebug`：**0 errors, 2 warnings, 0 hints**
- 产物：debug 10,941,635 B / release unsigned 1,669,366 B，versionCode 33

## beta34：主页面按 COUI 结构从零重做（2026-11-23）

beta31 的「重构」经自查不成立：`CouiShape` / `CouiTheme` / `CouiSwitch` 这些零件确实换成了 COUI 的实现，但 `MainPage` 内部仍是旧仪表盘的骨架——一张大卡里叠功率英雄区、6 个九宫格 `Metric`、3 个 `ActionTile`，字号 9/10/11sp，进度条带渐变，面板带投影。圆角和配色变了，信息架构没变。这一版把 `MainPage.kt` 从 1496 行整体重写为 1125 行。

### 结构对照

| 位置 | beta31–beta33 | beta34 |
| --- | --- | --- |
| 页面骨架 | 自建 `SectionHeader` + 多张 `GlassPanel` | 6 个 `CouiGroup`，组标题在卡外、内缩 32dp、12sp Medium |
| 实时功率 | `PowerHero`：大字 + 进度条 + 三按钮 | `CouiType.metricHuge`（34sp）独占一行，右侧状态点 |
| 六项电气参数 | 3×2 `Metric` 九宫格，9sp 标签 | 6 个 `CouiRow`，16sp 标题 + 13sp 摘要 |
| 快捷操作 | `ActionTile` ×3 | 并入 `ControlGroup` 的开关行与 chevron 行 |
| 控制错误 | 独立 `ControlIssueCard` | `ControlGroup` 内的红色行 |
| 充电检测 | `ChargeDetectPanel.kt` 自绘 301 行 | `DetectGroup` 内 8 个 `CouiRow`，文件剩 18 行 |
| 两张曲线 | 两张独立 `ChartCard` | 同一张卡 + 0.33dp 分隔线 |
| 最小字号 | 9sp（界面文字） | 11sp（`CouiType.dense`），另有 3 处 7/9sp 仅在 Canvas 轴标签 |

### 删除的死代码

`PowerHero`、`Metric`、`ActionTile`、`ForceChargeCard`、`LevelZeroCard`、`SessionRecords`、`RecordCard`、`SectionHeader`、`ControlIssueCard`、`ChargeDetectCard`、`StageChip`、`CouiMetric`、`DetailRow`、`StabilityBar`、`TextStyleHuge`——全部 grep 确认无调用点后删除。`ChargeDetectPanel.kt` 只留 `stabilityLabel(percent)` 与 `formatMinutes(minutes)` 两个纯文本函数，供 `DetectGroup` 与记录弹窗共用。

### 顺带的交互补完

- 「电池电流」行可点，切换 A / mA。这个显示单位原先藏在设置页里，放在被它影响的数值上才合理。
- 「基础档位」行带 chevron 直达充电设置页，取代原先散在首页的跳转按钮。

### 功能保留核对

界面层重写，业务调用点一个未动：`:app:testDebugUnitTest` **129 项通过、0 failure**，条数与 beta33 完全一致；`:app:lintDebug` **0 errors / 2 warnings / 0 hints**。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta34/OPPower-1.0.0-beta34-debug.apk` | 10,876,099 bytes | 34 |
| `release/1.0.0-beta34/OPPower-1.0.0-beta34-release-unsigned.apk` | 1,652,982 bytes | 34 |

两版都比 beta33 小约 65 KB / 16 KB，差额来自删掉的检测卡片与旧组件。

### 仍需真机复验

- 48dp 行高在「字体大小=最大」的辅助功能设置下是否被撑破（`CouiRow` 目前是固定 `heightIn`，不是固定高度）。
- 34sp 的功率数值遇到 `99.9 W` 这种三位整数时，右侧状态点是否够位置。
- 「电池电流」行的可点提示是否被用户感知（没有 chevron，仅靠按压反馈）。
- 深色下卡片 `card` 与页面 `background` 的色差是否足够，因为这一版彻底取消了投影。

---

## beta35：改为真实依赖 COUI-Kit

### 为什么改

beta34 仍然是"按 COUI 的度量自己写一遍组件"，用户判定为"美化"而非"组件套用"。这一版把 `ui/coui/` 下 5 个文件（`CouiShape.kt`、`CouiTokens.kt`、`CouiTheme.kt`、`CouiFeedback.kt`、`CouiComponents.kt`，约 1000 行）**整体删除**，改为直接依赖 Maven Central 上的发布产物：

```kotlin
val couiVersion = "0.0.1-alpha16"
implementation("io.github.itoseo:coui:$couiVersion")
implementation("io.github.itoseo:coui-icons:$couiVersion")
```

删除的辅助文件：`ui/GlassUi.kt`、`ui/ChargeDetectPanel.kt`（后者只保留 `stabilityLabel` / `formatMinutes` 两个纯文本函数，移入 `CouiKit.kt`）。备份在 `/tmp/ui-backup-beta34`。

### 工具链迁移

不迁移就无法消费该 AAR（`agpMetadata.minAgpVersion = 9.1.0`，`compileSdk 37.2`）。

| 项 | beta34 | beta35 |
| --- | --- | --- |
| Gradle | 8.13 | **9.6.0** |
| AGP | 8.10.1 | **9.4.1** |
| Kotlin（Compose 插件） | 2.1.21 | **2.4.20** |
| compileSdk | 36 | **37.2** |
| minSdk | 26 | **34** |
| targetSdk | 36 | 37 |
| Compose BOM | `2024.09.00` 形式 | 逐模块写死 1.13.0 |

AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件必须移除，否则构建直接失败：

> Failed to apply plugin 'org.jetbrains.kotlin.android': no longer required for Kotlin support since AGP 9.0

`kotlinOptions { jvmTarget = ... }` 同步改为 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`。

### 界面改了什么

| 位置 | beta34 | beta35 |
| --- | --- | --- |
| 主页面容器 | `GlassBackdrop` + `LazyColumn` | `layout.Scaffold` + `topappbar.TopAppBar` |
| 顶栏按钮 | 自绘 `IconButton` / 文字按钮 | `TopAppBarButton`（`CouiIcons.Filled.SettingDetail`、`text = true`） |
| 分组 | 自绘 `CouiGroup` | `layout.Group` + `layout.Divider` + `layout.Footer` |
| 行 | 自绘 `CouiRow` | `layout.Item` + `text.Text` |
| 开关 | 自绘 | `switch.Switch(CouiSwitchDefaults.colors(...))` |
| 档位选择 | 自绘 `BaseTierCard` 三张卡（共 4325 字符） | `segmentbutton.SegmentedControl` |
| 滑杆 | Material3 `Slider` | `slider.Slider` |
| 弹窗 | `AlertDialog` | `dialog.Dialog` + `CouiDialogAction` |
| 滚动 | `verticalScroll` | `scroll.couiVerticalScroll` |
| 配色 | `LocalCouiColors`（项目自定义） | `CouiTheme(colorScheme = CouiColorScheme(...))` |
| 排版 | `CouiType` 常量 | `rememberCouiTextStyle(CouiTextKind.xxx)` |

顶栏的渐进模糊来自库本身：`Scaffold` 内部 `rememberCouiBackdrop()` 后 `ProvideCouiBackdrop(backdrop)` 给 `topBar`，`TopAppBarButton` 从 `LocalCouiToolbarBackdrop` 取采样。beta34 自绘的顶栏拿不到这个作用域，所以只是静态半透明。

### `ui/CouiKit.kt` 现在是什么

只剩参数预设（官方 demo `DemoPreferenceRows.kt` 同款做法），零自绘：

- `CouiRow` = `Item` + 两个 `Text` + `CouiIcons.Filled.Next`，右值 `widthIn(max = 168.dp)`
- `CouiSwitchRow` = `Item` + `Switch`
- `CouiChoiceRow` = `SegmentedControl`
- `CouiPage` = `Scaffold` + `TopAppBar` + `couiVerticalScroll`
- `CouiCard` = `clip(CouiShape(24.dp, 3f))` + `background(colors.card)`，仅 Canvas 曲线区域用
- 扩展属性补齐 `CouiPageColors` 没有的 `danger` / `secondary` / `accentSurface` / `secondarySurface` / `isDark`

COUI 没提供、保持自绘的三处：功率与温度 Canvas 曲线、规则编辑器数字输入行 + 区间预览条、8dp 状态点。

### 踩坑记录

1. **minSdk 26 → 34**：`TopAppBarButton`、`SegmentedControl` 的 lint 声明为 API 34。顺带删掉 `Build.VERSION.SDK_INT >= 33`、`TIRAMISU`、`S` 等冗余判断。
2. **`@Composable get()` 不可用于非组合 getter**：`CouiType.itemTitle` 初版写成属性，报 *Functions which invoke @Composable functions must be marked with the @Composable annotation*，改为 `@Composable fun itemTitle(): TextStyle`，全部调用点加 `()`。
3. **`NotObservableLocale` ×2**：一律 `Locale.getDefault()` 改用 `LocalConfiguration.current.locales[0]`，再 `Locale.forLanguageTag(...)`。
4. **`ModifierParameter` ×2**：`CouiPage` / `CouiSearchField` 的 `modifier` 提到首个可选参数。
5. **`ObsoleteSdkInt`**：`mipmap-anydpi-v26/ic_launcher.xml` 合并进 `mipmap-anydpi/`。
6. **`NotShrinkingResources`**：release 加 `isShrinkResources = true`。
7. **Gradle 发行包**：`services.gradle.org` 在容器内 307 后不可达，阿里云镜像对 9.6.0 返回 404，最终用 `mirrors.cloud.tencent.com/gradle`。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | PASS |
| `:app:assembleDebug` | PASS |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **129 / 129 PASS** |
| `:app:lintDebug` | **0 errors / 0 warnings**（`No issues found.`） |

最后一轮把 `CouiPage` 的 `modifier` 提到首个可选参数后，lint 清零。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta35/OPPower-1.0.0-beta35-debug.apk` | 37,827,997 bytes | 35 |
| `release/1.0.0-beta35/OPPower-1.0.0-beta35-release-unsigned.apk` | 2,811,130 bytes | 35 |

体积对比 beta34：debug 10,876,099 → 37,827,997（+248%），release 1,652,982 → 2,811,130（+70%）。增量全部来自 AAR 携带的渲染器与字重。

### 仍需真机复验

- 顶栏渐进模糊是否真的采样到正文（滚动时观察正文穿过顶栏的过渡）。
- `TopAppBarButton` 在深色主题下的轮廓高光是否过亮。
- `Group` 的 24dp 超椭圆圆角在 480dpi 下的实际观感。
- release 包 2.83 MB 下 R8 后的首帧是否变慢。
- minSdk 34 会让 Android 13 及以下设备无法安装——这是引入 COUI 的硬性代价，需在发布说明里明示。

---

## beta36：页面层级转场

### 改了什么

新增 `ui/PageTransition.kt`，把两处硬切的页面切换换成带方向的转场：

| 位置 | 原来 | 现在 |
| --- | --- | --- |
| 顶层页（主页 / 充电设置 / 设置） | `when (page) { ... }` | `PageTransition(target = page, depthOf = { it.depth })` |
| 设置内部二级页 | `SettingsRouter` 里 `when (screen) { ... }` | 同样的 `PageTransition`，nest 在顶层之内 |

方向按层级判断：目标层级更深 → 新页从右侧推入；更浅 → 从左侧回位。同层级按前进处理。

```kotlin
private val Page.depth: Int
    get() = when (this) {
        Page.MAIN -> 0
        Page.CHARGE_SETTINGS, Page.SETTINGS -> 1
    }

private val SettingsScreen.depth: Int
    get() = when (this) {
        SettingsScreen.HOME -> 0
        SettingsScreen.IMPORTANT_NOTICE,
        SettingsScreen.DEVELOPER_NOTICE,
        SettingsScreen.LICENSES -> 2
        else -> 1
    }
```

### 动效参数的来源

COUI 的 `navigation` 包里只有 `NavigationBar`（底部导航）和 `CouiFabExpansion`（浮动按钮展开），没有页面级转场组件，所以容器用 Compose 的 `AnimatedContent`。但曲线不自己造，直接对齐 `CouiDialogDefaults`：

| 参数 | 值 | 来源 |
| --- | --- | --- |
| 进入曲线 | `CubicBezierEasing(.3f, 0f, .1f, 1f)` | `CouiDialogDefaults.enterEasing` |
| 退出曲线 | `CubicBezierEasing(.3f, 0f, 1f, 1f)` | `CouiDialogDefaults.exitEasing` |
| 进入时长 | 240ms | COUI 对话框 250ms，页面内容更多，退一档 |
| 淡入时长 | 130ms | 本项目独有，见下 |
| 退出时长 | 120ms | 本项目独有，见下 |
| 位移 | 1/8 屏宽（412dp 屏约 51dp） | 本项目独有 |

### 两个刻意的偏离

**退出比进入短一半。** 主页是 `LazyColumn`，但设置页是整棵子树都组合的 `Column`（二级页都是 `CouiPage` + `verticalScroll`，不是懒加载）。两页同时存在时绘制成本翻倍。原先这段 `when` 上面留着一句注释：

> A direct switch avoids rendering two full pages during transitions. On long, scrollable pages the crossfade created duplicate layout and drawing work, which appeared as a visible hitch on the device.

所以这次把退出收到 120ms（约 7 帧），叠层只发生在进入动画的前半段。

**淡入比位移短。** 如果新页也花 240ms 才不透明，旧页在 120ms 消失那一刻新页还是半透明，底下会露出页面底色闪一下。alpha 压到 130ms 走完，剩下的位移只是几何变换。

### 顺带补的状态保留

每页的 `rememberSaveable` 状态（主页列表滚动位置、展开项、设置页搜索词）现在由 `rememberSaveableStateHolder()` + `SaveableStateProvider` 按页 key 存取。以前从设置返回主页，列表必跳回顶部。

`pageKey()` 把枚举转成 `name` 字符串，因为 `SaveableStateProvider` 的 key 必须能进 Bundle。

### 新增依赖

```kotlin
implementation("androidx.compose.animation:animation:1.13.0")
```

显式声明。原本编译也能过（foundation 会传递带入），但项目风格是逐模块写死版本。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug` | PASS |
| `:app:assembleRelease` | PASS |
| `:app:testDebugUnitTest` | **129 / 129 PASS** |
| `:app:lintDebug` | **0 errors / 0 warnings** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta36/OPPower-1.0.0-beta36-debug.apk` | 37,844,381 bytes | 36 |
| `release/1.0.0-beta36/OPPower-1.0.0-beta36-release-unsigned.apk` | 2,843,898 bytes | 36 |

相对 beta35：debug +16 KB、release +32 KB，增量来自 `AnimatedContent` 与动画核心类。

### 仍需真机复验

- 设置页（整棵子树组合）在 120ms 叠层期间是否掉帧，尤其低端机。
- 快速连点「设置」再「返回」，转场是否被正确打断而不是卡在半路。
- 返回主页后 `LazyColumn` 滚动位置是否真的保住（列表没有显式 `LazyListState`，靠默认的 `rememberSaveable` 恢复）。
- 深色主题下 51dp 位移是否露出可见黑边。
- 转场期间 `TopAppBar` 的 backdrop 模糊：新页顶栏采样的是新页自己滚上来的内容，两页顶栏在重叠期会有两份模糊层，观感需确认。

---

## beta37：关于页套用 ColorOS 17 关于页范式

### 缺的是哪个组件

项目一直用固定高度的 `TopAppBar`，标题只能是 18sp。截图二那页用的是 **`LargeTopAppBar`** + `CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior`：

| 能力 | 固定 `TopAppBar` | `LargeTopAppBar` |
| --- | --- | --- |
| 展开态标题 | 无 | `CouiTextKind.HomeTitle`（40sp semibold） |
| 收起态标题 | 固定 18sp | 收到 18sp，高度连续变化 |
| 正文顶内边距 | `innerPadding` 静态 | `behavior.state.contentPadding(innerPadding, density)` 动态换算 |
| 折叠触发 | 无 | `canScroll` 判定正文是否超过一屏 |

组件 COUI 里本来就有，只是项目没用。`ui/CouiKit.kt` 的 `CouiPage` 新增 `largeTitle` 开关，内部就是这一套：

```kotlin
val behavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
    canScroll = { scrollState.maxValue > 0 },
)
Scaffold(modifier = modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection), ...) { innerPadding ->
    val contentPadding = remember(behavior, innerPadding, density) {
        behavior.state.contentPadding(innerPadding, density)
    }
    Column(Modifier.fillMaxSize().couiVerticalScroll(scrollState).padding(contentPadding)
        .consumeWindowInsets(innerPadding) ...)
}
```

`canScroll` 传"正文超过一屏"很关键，否则内容不满一屏的页面一进来标题就被抽走。

### 封面素材

来自 demo 安装包（`com.itos.coui.demo` 0.0.1-alpha11）的 `res/kz.mp4`：4 秒、1080×1080、30fps、无声，深紫渐变到橙紫 S 形分界。

| 项 | 原始 | 处理后 |
| --- | --- | --- |
| 视频 | 3,650,928 B / 7.3 Mbps | **438,414 B / 1.6 Mbps**（LibOpenH264） |
| 质量 | — | PSNR 49.04 dB（y 49.48） |
| 首帧占位 | — | `about_hero_first.webp` 3,714 B |
| 末帧 | — | `about_hero_last.webp` 15,724 B |

容器里 ffmpeg 没有 `libx264`，只有 `libopenh264`（不支持 crf，只能给码率）。渐变画面在 1.6 Mbps 下逐帧看过没有色带。

### 播放实现

`ui/AboutHero.kt`，结构与 `DemoAboutHero` 一致：

```kotlin
AndroidView(
    factory = { HeroVideoView(it) },
    modifier = Modifier.fillMaxSize(),
    onRelease = { it.release() },
)
```

`HeroVideoView` = `FrameLayout` + `TextureView` + 首帧 `ImageView` 占位，实现 `TextureView.SurfaceTextureListener` 与 `DefaultLifecycleObserver`：

- `onResume` → 起播；`onPause` → 切末帧；`onDetachedFromWindow` → 释放
- `MEDIA_INFO_VIDEO_RENDERING_START` 到达才隐藏占位，避免起播闪黑
- `isForceDarkAllowed = false`，否则深色主题下系统会把 View 反色

与 demo 的唯一差别：不引 `about_hero_logo` 位图（ColorOS 商标素材），品牌字用 Compose `Text` 叠加。

### 页面结构

```
LargeTopAppBar   「关于 OPPower」+ 返回
AboutHero        328dp / 左右 16dp / CouiShape(28.dp, 3f)
Group(无标题)    版本 / 适用设备 / 网络与上传
Group「说明与许可」  重要说明 / 开发者说明 / 开源许可
Group「反馈」      QQ 群 + 脚注
```

原来的 30sp 自绘标题卡删除——职责已由大标题顶栏承担。

### 顺带清理

`GeneralSettingsPage` 里 4 处对非空接收者的冗余 `!!`（`ChargerCapability`、`BatteryHealth` ×3）去掉，编译零警告。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug` | PASS |
| `:app:assembleRelease` | PASS |
| `:app:testDebugUnitTest` | **129 / 129 PASS** |
| `:app:lintDebug` | **0 errors / 0 warnings** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta37/OPPower-1.0.0-beta37-debug.apk` | 38,335,874 bytes | 37 |
| `release/1.0.0-beta37/OPPower-1.0.0-beta37-release-unsigned.apk` | 3,352,104 bytes | 37 |

相对 beta36：debug +491 KB、release +508 KB（封面视频 428 KB + 占位图 19 KB）。

### 仍需真机复验

- 大标题从 40sp 收到 18sp 的过程是否连续、有没有文字跳位。
- 视频起播前是否闪深色底。
- 浅色主题下白字在末帧橙色区域的对比度。
- 切后台再回前台会停在末帧（`finished` 标记），这是 demo 的行为，确认可接受。
- 页面转场（beta36 的 120ms 叠层）期间 `TextureView` 是否掉帧。

### 素材版权

`about_hero.mp4` 及抽帧来自 COUI demo，是 ColorOS 的视觉素材。个人二改非商业使用；
若要公开发布建议换自有封面。

---

## beta38：设置行加图标、分组改无标题卡片

### 缺的不是组件，是没往槽里放东西

`Item` 一直带有图标槽：

```kotlin
fun Item(
    icon: (@Composable () -> Unit)? = null,
    iconSize: Dp = CouiPageDefaults.iconSize,        // 24dp
    ...
) {
    if (icon != null) Box(Modifier.padding(end = CouiPageDefaults.iconSpacing).size(iconSize), ...) { icon() }
}
```

`iconSpacing` 是 16dp。项目此前从未传过 `icon`，所以每行只有纯文字。这一版把 10 个条目全部配上图标。

### 图标来源

全部来自 `CouiIcons`，并已与上游 main 分支的 `CouiIcons.kt` 逐一核对，确认不是本地 demo 才有的扩展：

| 条目 | 图标 | 色 |
| --- | --- | --- |
| Shizuku 授权 | `Filled.ShieldCheck` | 紫 |
| 外观设置 | `Filled.Sun` | 橙 |
| 充电检测 | `Filled.Health` | 青 |
| 通知设置 | `Filled.Bell` | 红 |
| 稳定运行 | `Filled.Fan` | 绿 |
| 硬件修正 | `Filled.GridSliders` | 蓝 |
| 系统与诊断 | `Filled.SettingDetail` | 紫红 |
| 数据管理 | `Filled.CloudData` | 紫 |
| 常见问题 | `Filled.BookOpen` | 橙 |
| 关于 OPPower | `Filled.Info` | 青 |

### 为什么不是截图里那种白底圆形彩色图标

检查了 demo 包内的 14 张位图：

| 文件 | 尺寸 | 内容 |
| --- | --- | --- |
| `yu.webp` / `qk.webp` | 328 / 1080 | 封面首末帧 |
| `-6/sK/Sn/j_/u5/qs/MO/fq/webp` | 48–192 | 启动页与占位图（主色 `#3CDA82` 绿 + 白） |
| `KU.png` | 663×183 | ColorOS 商标字 |
| `NZ.png` | 40×40 | 开关 loading 帧 |

**包里没有任何彩色图标位图**——截图里那套是系统设置自己的素材。所以用「单色 COUI 图标 + 主色底 + 超椭圆圆角」表达：

```kotlin
Box(
    Modifier.size(26.dp).clip(CouiShape(8.dp, 2.5f))
        .background(tint.copy(alpha = if (CouiTheme.isDark) 0.22f else 0.14f)),
) { Icon(icon, null, tint = tint, Modifier.size(16.dp)) }
```

底块 26dp 比 24dp 槽位大 1dp，`Item` 内是居中 Box，不裁切。色板 `CouiIconTint` 分昼夜两套。

### 分组改成无标题卡片

原来的「常用 / 设备与数据 / 帮助与关于」三个带标题组，改成每 4 行一张**不带标题**的卡片，靠 `groupSpacing` 留白分组——OPPO 设置的做法是分组靠间距表达，不靠文字标签。

### 顺带清掉两个废弃 API

`CouiSearchField` 的 `BasicTextField(value = ..., onValueChange = ...)` 在 Compose 1.13 已废弃，换成：

```kotlin
val fieldState = rememberTextFieldState(initialText = value)
LaunchedEffect(fieldState) { snapshotFlow { fieldState.text.toString() }.collect { onValueChange(it) } }
LaunchedEffect(value) { if (value != fieldState.text.toString()) fieldState.edit { replace(0, length, value) } }
BasicTextField(state = fieldState, lineLimits = TextFieldLineLimits.SingleLine, ...)
```

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug` | PASS |
| `:app:assembleRelease` | PASS |
| `:app:testDebugUnitTest` | **129 / 129 PASS** |
| `:app:lintDebug` | **0 errors / 0 warnings** |
| Kotlin 编译警告 | **0** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta38/OPPower-1.0.0-beta38-debug.apk` | 38,335,878 bytes | 38 |
| `release/1.0.0-beta38/OPPower-1.0.0-beta38-release-unsigned.apk` | 3,496,492 bytes | 38 |

release +144,388 B，是 10 个 `ImageVector` 的路径数据；R8 只压不删，因为它们通过 Companion getter 暴露。

### 仍需真机复验

- 16dp 图标在 24dp 槽里的视觉居中，深色下对比够不够。
- 26dp 底块与相邻分隔线、摘要文字是否挤压（48dp 行高 + 两行摘要时）。
- 六色在浅色主题下是否都"够 ColorOS"，红与紫红是否太接近。
- 搜索结果只剩 1–2 行时卡片高度是否自然。

---

## beta39：主页标题改成 ColorOS 17 大标题

### 为什么之前不是

beta37 只把 `LargeTopAppBar` 用在了关于页，主页仍是固定高度的 `TopAppBar`。固定顶栏里标题只能按 `CouiTextKind.TopBar` 渲染（18sp / 550），所以主页那颗 18sp 小标题是**顶栏标题**，不是**首页标题**——不是组件缺失，是调用点选错了。

### COUI 的首页标题度量

| 常量 | 值 |
| --- | --- |
| `CouiTopAppBarDefaults.expandedHeight` | `titleOnlyHeight` = 110dp |
| `CouiCollapsingAppBarDefaults.expandedTitleSize` | 32dp |
| `CouiCollapsingAppBarDefaults.collapsedTitleSize` | 24dp |
| `CouiCollapsingAppBarDefaults.collapsedTitleHeight` | 52dp |
| 展开态样式兜底 | `expandedTitleTextStyle()` = `rememberCouiSystemTextStyle(CouiTextKind.HomeTitle)`，即 40sp semibold |

### 改动

```kotlin
val scrollState = rememberLazyListState()
val behavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
    canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward },
)
Scaffold(
    modifier = Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection),
    topBar = { LargeTopAppBar(title = { Text("OPPower") }, scrollBehavior = behavior, ...) },
) { innerPadding ->
    val contentPadding = behavior.state.contentPadding(innerPadding, density)
    LazyColumn(state = scrollState, modifier = Modifier.padding(contentPadding)) { ... }
}
```

两个必须做对的点：

1. **`nestedScroll(behavior.nestedScrollConnection)` 不能漏**，否则 `LazyColumn` 自己吃掉全部滚动手势，顶栏永远不折叠。
2. **正文顶距必须用 `behavior.state.contentPadding(innerPadding, density)`**，不能直接 `padding(innerPadding)`。折叠时顶栏在 110dp → 52dp 之间连续变化，静态内边距会让滚动初期内容被顶栏压住。

`canScroll` 用 `canScrollForward || canScrollBackward`（"列表能滚就允许折叠"）。只写 `canScrollForward` 的话，折到底、内容又被挤出屏幕时顶栏会突然弹回。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug` | PASS |
| `:app:assembleRelease` | PASS |
| `:app:testDebugUnitTest` | **129 / 129 PASS** |
| `:app:lintDebug` | **0 errors / 0 warnings** |
| Kotlin 编译警告 | **0** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta39/OPPower-1.0.0-beta39-debug.apk` | 38,352,262 bytes | 39 |
| `release/1.0.0-beta39/OPPower-1.0.0-beta39-release-unsigned.apk` | 3,496,492 bytes | 39 |

debug +16 KB（多一个 `LargeTopAppBar` 调用点及其状态持有），release 不变——相关代码 R8 已在关于页那条路径上保留。

### 仍需真机复验

- 首屏 40sp 的 OPPower 是否与 ColorOS 17 首页观感一致。
- 向上滚动时 40sp → 24sp 的收缩是否平滑，有没有逐帧跳字。
- 折叠到底后反向滚动能否正常展开。
- 顶栏展开 110dp 时第一个分组（实时状态）是否被顶栏下边缘压住。
- 转场期间主页大标题与设置页大标题是否同时出现两份。

---

## beta40：清掉被增量编译掩盖的警告

beta39 报告的「Kotlin 编译 0 warning」是增量构建的结论——只重编了改动文件，其余带警告的编译单元复用缓存。用 `--rerun-tasks` 强制全量重编后暴露 3 条，本版全部清掉。

### 改动

| 位置 | 问题 | 处理 |
| --- | --- | --- |
| `ChargeSettingsPage.kt` 规则编辑行 | `BasicTextField(value/onValueChange)` 已废弃 | 迁到 `rememberTextFieldState` + `snapshotFlow` + `TextFieldLineLimits.SingleLine` |
| `ChargingController.kt:430` | `(now - lastAttempt!!)` 冗余 `!!` | 去掉断言，外层已短路判空 |
| `MainViewModel.kt:167` | `shared?.snapshot` 冗余安全调用 | 改为 `shared.snapshot` |
| `MainPage.kt` / `OPPowerApp.kt` / `GeneralSettingsPage.kt` | 未使用的 `AlertDialog` 导入 | 删除（弹窗早已换成 `com.itos.coui.dialog.Dialog`） |
| `MainPage.kt` | 大标题下第一行贴太近 | `LazyColumn` 的 `contentPadding` 补 `top = 8.dp` |

迁移写法和 beta38 的 `CouiSearchField` 一致，关键是第二条 `LaunchedEffect`：外部改值（如「恢复默认」）时必须 `fieldState.edit { replace(0, length, value); placeCursorBeforeCharAt(value.length) }`，否则界面还停在旧数字。

### 验证结果（全部来自强制全量重编）

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **129 / 129 PASS**（0 skipped / 0 failed / 0 error） |
| `:app:lintDebug` | **0 errors / 0 warnings** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta40/OPPower-1.0.0-beta40-debug.apk` | 38,352,262 bytes | 40 |
| `release/1.0.0-beta40/OPPower-1.0.0-beta40-release-unsigned.apk` | 3,398,188 bytes | 40 |

release 比 beta39 少 98,304 B（-2.8%）：旧 `BasicTextField` 重载的引用链路断开后 R8 少留一组装饰类，未使用的 M3 `AlertDialog` 导入消失也让入口分析更干净。debug 不变——没删代码、没动资源。

### 仍需真机复验

- 温度 / 电量阈值输入框：小数点输入、删空、恢复默认、光标位置、软键盘遮挡。
- 首页大标题与首张卡片之间的 8dp 留白是否合适。
- beta36 ~ beta39 的待复验点（转场方向、关于页封面视频首帧、设置行图标清晰度、大标题折叠）。

---

## beta41：底栏把快充控制与充电检测拆成独立页

主页原来一屏塞了五组（状态 / 控制 / 检测 / 记录 / 曲线）。这一版用 COUI 的 `navigation.NavigationBar` 拆成三页。

### 页面划分

| 页签 | 顶栏大标题 | 内容 |
| --- | --- | --- |
| 主页 | `OPPower` | 当前状态、记录与通知、实时曲线 |
| 快充 | 快充控制 | 实时功率、开关、档位、保活、策略、状态、电流上限、权限 |
| 检测 | 充电检测 | 阶段、充电器能力、充满预估、利用率、稳定性、异常、检测设置 |

### 三个实现要点

1. **`NavigationBar` 必须在 `Scaffold` 的 `bottomBar` 栏位里。** 它默认从 `LocalCouiBackdrop.current` 取背景源做玻璃采样，取不到就 `checkNotNull` 抛异常；而 `CouiBackdrop` 构造函数是 internal，应用层无法自行创建，只能由 Scaffold 的栏位通过 `ProvideCouiBackdrop` 下发。
2. **三个页签各有独立的 `LazyListState` 与 `exitUntilCollapsedScrollBehavior`。** COUI 的折叠状态 remember 在各自调用点上，三份互不干扰。共用一个 behavior 会导致从折到底的主页切到快充时，新页标题一进去就是收起的、正文还被顶到顶栏下面。
3. **底栏高度只作为 `LazyColumn` 的内容内边距。** `innerPadding.bottom` 约 96dp（56dp 胶囊 + 上下 16dp + 安全区）。按常规 `Modifier.padding(innerPadding)` 处理会把正文截到胶囊上沿、多出一条空白带；改为 `padding(top = topInset)` + `contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 24.dp)`，正文延伸到底栏后面，卡片从胶囊玻璃下透出。

切页动效是 180ms 淡入 / 120ms 淡出，不做位移——三页共用同一个顶栏和底栏，横推会让人误以为进了二级页。

### 两处补齐

- 快充页顶部加「实时功率」行：拆分后主页那颗大数字看不到了，调档位时需要看到当前瓦数。纯展示，不碰控制逻辑。
- 检测关闭时不再是空白，改成「检测已关闭 / 去开启」一行，点击直达检测设置。

### 返回键

停在快充或检测页时返回键先回主页。`OPPowerApp` 的 `BackHandler` 只在 `page != MAIN` 时启用，两者不冲突。

### 验证结果（强制全量重编）

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **129 / 129 PASS**（0 skipped / 0 failed / 0 error） |
| `:app:lintDebug` | **0 errors / 0 warnings** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta41/OPPower-1.0.0-beta41-debug.apk` | 38,368,646 bytes | 41 |
| `release/1.0.0-beta41/OPPower-1.0.0-beta41-release-unsigned.apk` | 3,452,732 bytes | 41 |

debug +16 KB、release +54 KB，增量来自 `CouiFloatingRenderer` 的浮动材质与阴影绘制、以及 `AnimatedContent` 转场。

### 仍需真机复验

- 底栏胶囊的玻璃采样：滚动时卡片能否正常透出，有无黑边或错位。
- 三页来回切：标题是否每次都是展开态，列表位置是否各自记住。
- 主页折到底后切快充，快充标题是否展开（三页折叠状态应互相独立）。
- 切页 180ms 淡入期间，顶栏标题换字有无重影。
- 检测页按返回键是否回主页。

---

## beta42：修复记录弹窗闪退（窗口级背景源缺失）

### 现象

点「充电记录」「放电记录」立即闪退。

### 根因

不是记录弹窗自己的 bug，是**整个应用从未提供窗口级背景源**。COUI 的 `Dialog` 在创建 Dialog 窗口时执行：

```kotlin
val windowBackdrop = backdrop ?: LocalCouiMenuBackdrop.current
if (material != null) checkNotNull(windowBackdrop) { "弹窗项目材质需要宿主提供不包含弹窗自身的窗口背景源" }
```

`Dialog` 默认带材质（玻璃底），所以 `checkNotNull` 必抛 `IllegalArgumentException`，崩溃点在 `ComposeDialog(...)`，即窗口创建瞬间。

首启「关于 OPPower」弹窗走的是同一个路径，同样会崩——只是它只在清数据后第一次出现，那次崩溃被当成偶发闪退，没人复现。记录弹窗随时可点，问题就集中暴露在它身上。

### 修复

应用根部包一层公开入口：

```kotlin
ProvideCouiMenuBackdrop { contentModifier ->
    PageTransition(..., modifier = contentModifier.fillMaxSize()) { current -> ... }
}
```

`CouiBackdrop` 构造函数是 internal，应用层只能通过 `rememberCouiBackdrop()` / `ProvideCouiMenuBackdrop` 拿。这一层提供一次即可，所有弹窗共用，并且弹窗也随内容一起进入采样。

### 顺带

记录弹窗最多列 20 条历史（`HISTORY_IN_DIALOG`），标题写明"最多显示 20 条"。原来 `records.asReversed().forEach` 无上限，历史攒到几百条时弹窗首帧要一次性测量几百个卡片。弹窗本身已有 `heightIn(max = 460dp) + verticalScroll`，限条数只为了避免点开那一帧卡顿。

### 排除掉的假设

| 假设 | 排除依据 |
| --- | --- |
| `clickable` 撞上非零最小约束 | `CouiCard` 是 `fillMaxWidth`，行是 `padding + Row`，不会拿到 `minWidth>0, minHeight=0` |
| `verticalScroll` 在无界高度下测量 | COUI 的 action 区是 `Column(if (naturalHeight > constraints.maxHeight) Modifier.verticalScroll(...))`，说明 `maxHeight` 有限 |
| 记录数据为空导致空指针 | `selected == null` 分支已存在 |

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **129 / 129 PASS** |
| `:app:lintDebug` | **0 errors / 0 warnings** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta42/OPPower-1.0.0-beta42-debug.apk` | 38,368,646 bytes | 42 |
| `release/1.0.0-beta42/OPPower-1.0.0-beta42-release-unsigned.apk` | 3,452,732 bytes | 42 |

与 beta41 体积完全相同——只加了包装层和一处上限判断，没有引入新类。

### 仍需真机复验

- 充电记录 / 放电记录弹窗能否正常弹出，玻璃底是否正常。
- 首启说明弹窗（清数据后可见）与设置页的两个确认弹窗是否也恢复。
- 若仍闪退，抓 `AndroidRuntime` 段，重点看是否 `IllegalArgumentException` 且消息含"弹窗项目材质"。

---

## beta43：快充的功率稳定性与可读性

本版只动快充，不碰界面骨架与依赖。两件事：让温度调节下的功率不再来回打摆；让快充页能自证"开了有没有用"。

### 一、升功率逐档回升

**问题**：温度回落时，`RuleEngine` 直接给出该温度段的目标档位，`confirmGear` 一次就把 cosa 写到位。以 GENTLE 为例，42.5°C 时 gear 5，降到 40°C 时目标 gear 2——一次跨 3 档，功率瞬间接近翻倍。充几十秒后电池温度又冲回 43°C 被暂停，随后再降、再升，形成几十秒一轮的锯齿。

**改法**：

```kotlin
private fun limitPowerIncrease(requested: Int, current: Int): Int =
    if (requested < current) maxOf(requested, current - 1) else requested
```

- gear 数字越小功率越高，所以 `requested < current` 就是升功率。
- 每次最多升一档，每档独立走一次 `temperatureUpshiftSeconds`（默认 8 秒）确认。
- 降功率不限幅；改基础档位走 `controlSettingsChanged` 分支，仍然立即生效。
- 限幅生效时状态消息追加「逐档回升」。

**为什么不在 RuleEngine 里做滞回**：滞回需要把"当前档位"传进纯函数，会让规则表与控制器状态耦合；而 `confirmGear` 本来就有确认计时器，在它上面加一步限幅，语义最窄、测试面最小。

### 二、快充页「当前充电」组

拆页后快充页只剩一行实时功率。新增一组，数据全部复用主页记录的 `ChargeRecord` 与 `ChargeInsight.capability`，不增加 Binder 往返：

| 行 | 内容 |
| --- | --- |
| 实时功率 | 保留全页唯一的大字号数字 |
| 充电器 | 协议 + 当前利用率，右值是峰值能力 |
| 本次充入 | 起止电量 + mAh |
| 本次时长 | 采样区间，拔电后停在上一个采样点 |
| 平均 / 峰值功率 | 本次会话累计 |

未接电时降级为一行「接电后开始统计」，避免空组。

### 三、档位诊断进「当前策略」行

`summary` 直接给出「基础档 X · cosa ±Y · 计算档 Z · 目标 W」。系统实际档位 = 热控基础档 + cosa，三者任何一个对不上，功率都不会变。这条信息原来藏在设置页的「读取档位」按钮里。

### 四、文案校正

| 位置 | 原文 | 现在 |
| --- | --- | --- |
| 高功率保活 | "强制亮屏快充无效时再试" | 补充「开启后由系统保活通道接替档位控制」 |
| 电流上限手动值 | "固定上限为基准值" | 「手动值优先于档位折算，可以高于档位基准」 |
| 自动调节说明 | "回落并持续 8 秒才升档" | 「升温降档一次到位、回温逐档回升」 |

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **131 / 131 PASS**（新增 2 条限幅用例） |
| `:app:lintDebug` | **0 errors / 0 warnings** |

新增用例：

- `temperatureRecoveryRaisesPowerOneGearAtATime`：42.5°C 停在 gear 5，降到 40°C 时先到 4，确认期内不得提前写更高功率。
- `temperatureRiseLowersPowerInOneStep`：40°C 的 gear 2 升到 42.5°C 时一次到 5，不被限幅拆成 3→4→5。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta43/OPPower-1.0.0-beta43-debug.apk` | 38,385,030 bytes | 43 |
| `release/1.0.0-beta43/OPPower-1.0.0-beta43-release-unsigned.apk` | 3,469,116 bytes | 43 |

两个包各比 beta42 多 16,384 B，增量是新组与诊断摘要的排版代码。

### 仍需真机复验

- 温度回落时功率是否逐档爬升（约 8 秒一档），而不是一下跳回最高。
- 升温降档是否仍一次到位。
- 「当前充电」的时长与充入是否随采样增长；拔电后是否正确降级。
- 充电器利用率与检测页是否一致。
- 「当前策略」摘要里的三个档位数字，是否与实际功率变化对得上。

---

## 1.0.0-beta44

本版不做界面改动，只补一条只读探针和一条白名单修正。

### 一、只读「热控诊断」（设置 → 硬件修正）

第三方模块提升充电功率的常见做法是 `setprop persist.sys.horae.enable 1; start horae`，靠重启 horae 服务强制重算热控策略。这条路径不写 sysfs、不需要 root，但前提是固件里真有这条厂商开关。在做一个"重启热控"按钮之前，先量清楚前提是否成立。

| 读取项 | 来源 | 判定 |
| --- | --- | --- |
| `persist.sys.horae.enable` | 白名单精确 `getprop` | 显示 `<不存在>` 即表示本机无此通道 |
| `persist.sys.horae.debug` | 白名单精确 `getprop` | 同上 |
| `horae` 进程 | 扫 `/proc/*/cmdline`，不 fork shell | `alive` / `absent` |
| `oplus_dbg_tbat` | `oplus_chg_v2` 模块参数 | 读不到显示 `<不可读>` |
| `ufcs_active` / `pps_active` / `svooc_active` | `battery` 下的协议节点 | 同上 |

AIDL 新增 `String readThermalDiagnostics() = 8`，返回原始多行 `[tag] key=value`，解析在 UI 侧（`parseThermalDiagnostics`）。服务端将来多吐几行不需要改签名。

**安全边界**：整条链路只读。属性名来自服务端常量且两条命令都在精确白名单里；进程状态扫 `/proc`；节点直接读文件。即使删掉这个功能也没有留下写入面。

### 二、白名单正则两端锚定

`execute()` 把整串交给 `sh -c`，而两条正则原来没有锚点：

```kotlin
// 之前
Regex("settings put global cosa_ctc_value (?:-10|-?\\d)")
Regex("settings put system (?:oplus_camera_3rd_activity|oplus_float_window_show) (?:0|1|00|01)")
// 现在
Regex("^settings put global cosa_ctc_value (?:-[1-9]|-10|\\d)$")
Regex("^settings put system (?:oplus_camera_3rd_activity|oplus_float_window_show) (?:0|1|00|01)$")
```

`settings put global cosa_ctc_value 0; reboot` 在旧版本能匹配成功——匹配只要求"含这段前缀"。要构造这条命令的前提是应用自身被改写，属纵深防御而非直接可利用，但这是白名单的基本正确性。上界跟着 `GearCalculator.COSA_MAX = 9` 走，白名单不比实现更宽。

### 三、修正的既有用例

`rejectsShellInjectionAndUnknownSettings` 原本断言 `cosa_ctc_value 10` 非法。该断言与 `GearCalculator.COSA_MAX = 9` 一致，但和白名单注释里写的"范围 -10…10"矛盾。现按实现统一为 **-10…9**，用例改为断言 `9` 合法、`10` 非法。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **137 / 137 PASS**（新增 6 条） |
| `:app:lintDebug` | **0 errors / 0 warnings**（仅两条依赖更新提示） |

新增用例：

- `regexEntriesAreAnchoredAtBothEnds`：`;` / `&&` / `|` / `$(...)` / 换行 / 前后缀 共 10 种拼接写法全部拒绝。
- `getpropAllowlistRejectsArbitraryPropertyNames`：任意属性名、裸 `getprop`、属性名后接 `;`、以及 `setprop` 一律拒绝。
- `ThermalDiagnosticsParsingTest`（4 条）：标签解析、空输入、非标签行跳过、值里含 `=` 不被截断。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta44/OPPower-1.0.0-beta44-debug.apk` | 38,401,414 bytes | 44 |
| `release/1.0.0-beta44/OPPower-1.0.0-beta44-release-unsigned.apk` | 3,469,116 bytes | 44 |

debug 比 beta43 多 16,384 B；release 无变化（增量落在同一页内）。

### 仍需真机复验

- 一加 13（PJZ110）上 `persist.sys.horae.enable` 是否存在——这是本版唯一真正要知道的事。显示「不存在」就直接放弃方案 B。
- horae 进程名是否就叫 `horae`（部分固件是 `com.oplus.horae` 或带 `:remote` 后缀）。若显示 `absent` 但系统明显有热控在跑，说明名字对不上，要改匹配规则。
- 三个协议节点在本机是否存在。全部 `<不可读>` 不代表没有快充，只代表内核没暴露这一层。
- 点击读取是否卡顿：一次要跑两条 `getprop`（各一次 shell fork）+ 扫 `/proc`。明显迟滞就去掉 `/proc` 扫描。

---

## 1.0.0-beta45

beta44 的真机结果推翻了两个预设，本版是修正加加深。

### 真机结果（beta44 / 一加 13 PJZ110）

| 项 | 值 | 解读 |
| --- | --- | --- |
| `persist.sys.horae.enable` | `1` | 属性存在且已启用，路是通的 |
| `persist.sys.horae.debug` | `<不存在>` | 该属性在本机没有，不再读 |
| `horae` 进程 | `absent` | **可疑，见下** |
| 身份 `shell` | `0` | Shizuku 以 root 启动，特权进程是 uid 0 |
| `oplus_dbg_tbat` | `0` | 节点可读 |
| `ufcs/pps/svooc_active` | `<不可读>` | 内核未暴露协议层 |

### 一、修的 bug：进程名匹配恒为 false

```kotlin
// beta44：永远匹配不上
File(pid, "cmdline").readText().trimEnd('\u0000') == "horae"

// beta45：取 argv[0] basename 再比子串
File(pid, "cmdline").readText()
    .substringBefore('\u0000').substringAfterLast('/').trim()
    .contains("horae", ignoreCase = true)
```

`/proc/<pid>/cmdline` 存的是完整路径 `/system/bin/horae`，带启动参数时更长。相等判断必然失败，所以那个 `absent` 大概率是我自己的 bug。

只取 argv[0] 是必要约束：整条 cmdline 匹配会把诊断自身执行的 `sh -c getprop …horae…` 数进去，恒报"存在"。

### 二、新增探测

| 项 | 作用 |
| --- | --- |
| `[prop] init.svc.horae` | init 眼里的服务状态：`running` / `stopped` / 空（未定义） |
| `[bin] /system/bin/horae` | `present` / `missing`，区分"没定义"与"定义了没跑" |
| `[param]` 分组 | `/sys/module/oplus_chg_v2/parameters` 全量清单，`name=value` |

第三条是重点：若 `max_voltage` / `pps_max_voltage` / `temp_target` 真实存在，改它们比重启热控服务直接得多——不停服务、不管 init 状态、服务没跑也能改。

### 三、删掉一项

`persist.sys.horae.debug` 已确认不存在，不再读取，省一次 shell fork。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **138 / 138 PASS**（新增 1 条参数分组用例） |
| `:app:lintDebug` | **0 errors / 0 warnings** |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta45/OPPower-1.0.0-beta45-debug.apk` | 38,417,798 bytes | 45 |
| `release/1.0.0-beta45/OPPower-1.0.0-beta45-release-debugkey.apk` | 3,487,859 bytes | 45 |
| `release/1.0.0-beta45/OPPower-1.0.0-beta45-release-unsigned.apk` | 3,469,116 bytes | 45 |

release-debugkey 用调试密钥签名（与 debug 包同证书，可互相覆盖），是唯一可直接安装的 release 包。

### 仍需真机复验

- `进程 horae` 是否从 `absent` 变成实际路径 → 验证上面的 bug 判断。
- `init.svc.horae` 是 `running` / `stopped` / 空 → 决定是"启动服务"还是"根本没这服务"。
- 充电模块参数里是否有 voltage / current / temp 类字段 → 决定能否绕过服务直接调参数。

---

## 1.0.0-beta46

修「点充电记录 / 放电记录闪退」。beta42 修过一次，但找错了地方。

### 根因

COUI 的 `CouiDialogAction` 构造函数里有硬校验：

```kotlin
init {
    require(style != CouiDialogActionStyle.Primary || colors != null) {
        "主操作必须由宿主提供主题配色"
    }
}
```

项目两处都写了 `style = Primary` 却不传 `colors`，`require` 失败抛 `IllegalArgumentException`。异常抛在**组合期**（弹窗真正显示的那一帧），所以表现为"点一下立刻闪退"。

库里还有第二道防线（`CouiDialogButton.kt:77`）：`else error("主操作必须由宿主显式提供配色")`。

| 位置 | 影响 |
| --- | --- |
| `ui/MainPage.kt` RecordDialog「关闭」 | 充电记录 / 放电记录闪退 |
| `ui/OPPowerApp.kt` FirstRunDialog「知道了」 | 新装用户首次启动即崩（老用户 `introSeen=true` 一直没撞上） |

### beta42 的误判

上轮判断为「应用未提供窗口级背景源，`Dialog` 创建窗口时 `checkNotNull` 抛异常」，加了 `ProvideCouiMenuBackdrop`。读库源码后确认：

```kotlin
// CouiDialogWindow.kt:173-176
val windowBackdrop = backdrop ?: LocalCouiMenuBackdrop.current
if (material != null) {
    checkNotNull(windowBackdrop) { "弹窗项目材质需要宿主提供不包含弹窗自身的窗口背景源" }
}
```

`checkNotNull` 只在 `material != null` 时执行，项目从未传 `material`。那条路径不会走到，`ProvideCouiMenuBackdrop` 并非必需（保留无害，将来用材质弹窗仍需它）。

### 修复

新增 `ui/CouiKit.kt`：

```kotlin
@Composable
internal fun couiPrimaryAction(text: String, onClick: () -> Unit): CouiDialogAction {
    val accent = CouiPageDefaults.themeColors().accent
    return CouiDialogAction(
        text = text, onClick = onClick,
        style = CouiDialogActionStyle.Primary,
        colors = CouiDialogDefaults.primaryActionColors(accent),
    )
}
```

配色由封装带死，调用方没有漏传的机会。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **141 / 141 PASS**（新增 3 条） |
| `:app:lintDebug` | **0 errors / 0 warnings** |

新增 `CouiDialogActionContractTest`：Primary 不带配色必须抛、带配色必须过、Secondary 不要求配色——把库行为钉住，升级 COUI 若变更该约束会先发现。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta46/OPPower-1.0.0-beta46-debug.apk` | 38,417,798 bytes | 46 |
| `release/1.0.0-beta46/OPPower-1.0.0-beta46-release-debugkey.apk` | 3,487,859 bytes | 46 |
| `release/1.0.0-beta46/OPPower-1.0.0-beta46-release-unsigned.apk` | 3,469,116 bytes | 46 |

### 仍需真机复验

- 充电记录 / 放电记录弹窗能否正常打开，「关闭」按钮配色是否为主题色。
- 数据管理的「清除全部本地记录」确认弹窗（Secondary，应不受影响）。
- 首次启动说明弹窗（若 `introSeen` 已为 true 则看不到，可清数据验证）。

---

## 1.0.0-beta47

把界面字体换成 ColorOS 原厂 OPPO Sans。

### 一、为什么读系统文件，而不是打包字体

| 方案 | 体积代价 | 一致性 | 结论 |
| --- | --- | --- | --- |
| 打包 OPPO Sans 四个字重 | +36 MB（单字重中文字形约 9 MB） | 完全可控 | 否决 |
| 运行时读 `/system/fonts` | 0 | 与系统设置页同源文件 | **采用** |
| 沿用 `FontFamily.SansSerif` | 0 | 取决于 ROM 是否把 sans-serif 映射到 OPPO Sans | 不确定，放弃 |

第三方结论：release 包 3.5 MB，打包字体后变 40 MB，为字体付十倍体积不成比例；且分发第三方字体需附授权声明。系统目录普通应用可读，没有理由不从那里取。

### 二、选择逻辑

```kotlin
private val FAMILY_MARKERS = listOf("opposans", "oplussans")
private val WEIGHT_KEYWORDS = listOf(
    "extralight" to 200, "ultralight" to 200,
    "extrabold" to 800,  "ultrabold" to 800,
    "semibold" to 600,   "demibold" to 600,
    "regular" to 400,    "normal" to 400,
    "medium" to 500,     "light" to 300,
    "heavy" to 800,      "black" to 900,
    "bold" to 700,       "book" to 400,  "thin" to 100,
)
```

两条规则值得记：

1. **关键字顺序即优先级**。`SemiBold` 里含 `bold`，若 `bold` 排在前面就会判成 700；`ExtraLight` 同理会变成 300。所以长关键字必须前置。
2. **繁体让位简体**。`OPlusSans-Hant-*` 直接排除，同字重有 `Hans` 与中立方集时 `Hans` 优先，避免混排时字形跳变。

`selectFaces` 抽成纯函数，输入是文件名列表，输出是 `字重 to 文件名`，可在 JVM 单测里跑——ColorOS 的字体文件名换过 `OPPOSans-R` / `OPlusSans-Regular` / `OPlusSans3-Regular` 三轮，规则靠测试守，不靠真机截图。

### 三、踩到的坑：`OPlus Sans` 的小写是 `oplussans`

`OPlus` 结尾的 s 与 `Sans` 开头的 s 相连，没有分隔符。

第一版按 `oplusans` 写，四条用例全返回空列表。先怀疑 `Sequence.mapNotNull` 的标签，改成 for 循环仍然空；最后把中间状态塞进断言消息才看见真相：

```text
base=[oplussans3-regular] len=18 opposans=false oplusans=false regular=true
```

族名比预期多一个 s。这类错误读代码看不出来——`oplusans` 和 `oplussans` 视觉上只差一个字母，必须让测试把实际值打出来。

现在有 `matchesColorOsFamilyNameWithDoubledS` 专门守这条。

### 四、注入点

`ui/Theme.kt`：

```kotlin
CouiTheme(darkTheme = dark, colorScheme = scheme, typography = couiTypography) {
    MaterialTheme(colorScheme = materialScheme, typography = materialTypography, content = content)
}
```

- **COUI 侧**：`body` / `label` / `menuItem` 三个基础项换族，另外六个 `dialog*FontFamily` 可空字段也显式填上。这些字段默认 `null`，`null` 会让弹窗标题落到 Material 默认字体，同一页出现两套字形。
- **Material 侧**：15 个 role 全换。项目里还有少量 `androidx.compose.material3.Text`，不同步会出现半页 OPPO Sans、半页 Roboto。

字号、行高、字距一律沿用 COUI 预设，只换 `fontFamily`。

### 五、失败路径

整个查找过程包在 `runCatching` 里，任何一步失败都回落到 `FontFamily.SansSerif`：

- 目录不可读
- 没有任何文件匹配族名
- 匹配到文件但字重关键字不认识

非 ColorOS 机型、ROM 改了目录结构、字体被裁剪——三种情况都只是字体退化，不会崩。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **151 / 151 PASS**（新增 10 条） |
| `:app:lintDebug` | **0 errors / 0 warnings**（仅两条依赖版本提示） |

新增 `OppoFontTest`（10 条）：

- `picksAllWeightsFromColorOs17Names`：三个字重全识别
- `prefersSimplifiedChineseOverTraditional`：繁体让位简体
- `ignoresFontsFromOtherFamilies`：排除 Roboto / NotoSansCJK / DroidSansMono
- `readsSemiBoldAsSixHundredNotSevenHundred`、`readsExtraLightAsTwoHundredNotThreeHundred`：长关键字优先
- `matchesColorOsFamilyNameWithDoubledS`：双 s 族名
- `isCaseInsensitive`、`skipsFilesWithoutARecognisedWeight`、`returnsEmptyForEmptyInput`、`deduplicatesByWeight`

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta47/OPPower-1.0.0-beta47-debug.apk` | 38,434,178 bytes | 47 |
| `release/1.0.0-beta47/OPPower-1.0.0-beta47-release-debugkey.apk` | 3,504,243 bytes | 47 |
| `release/1.0.0-beta47/OPPower-1.0.0-beta47-release-unsigned.apk` | 3,485,500 bytes | 47 |

debug +16,380 B，release +16,743 B，全部是新增 Kotlin 代码与字符串，无资源打包。

### 仍需真机复验

- 设置 → 外观设置 → 界面字体 显示「OPPO Sans」还是「系统默认」。后者说明族名或目录结构与我预期不符，footer 里的文件名能直接定位。
- 大标题（`LargeTopAppBar` 折叠态）字重是否正常。COUI 在 `TopAppBar.kt:257` 有 `LoadedFontFamily` 特判，我们的 `FontListFontFamily` 不走那条分支，但只能真机确认。
- 是否有文本被裁切。OPPO Sans 字面宽度与 Roboto 不同，固定宽度的 `value` 列（`widthIn(max = 168.dp)`）风险最高。

---

## 1.0.0-beta48

顶栏折叠语义对齐 ColorOS：滚动时整块顶栏跟着正文滚走。

### 一、问题

| | ColorOS 设置页 | OPPower（beta47 之前） |
| --- | --- | --- |
| 滚动时 | 大标题和正文一起移出屏幕 | 标题缩成 52dp 紧凑顶栏，**一直钉在顶部** |
| 滚回顶部 | 标题滑回来 | 标题展开回 40sp |
| 语义来源 | ColorOS 自有 | Material `LargeTopAppBar` |

差别在"钉不钉"。COUI 提供的是 `exitUntilCollapsedScrollBehavior`，正是 Material 的钉住语义，所以这不是 bug，是实现与目标平台不一致。

### 二、约束：应用侧碰不到偏移量

- COUI 只导出 `exitUntilCollapsedScrollBehavior` 一个工厂，没有 `enterAlways` / `pinned` 变体。
- `CouiCollapsingAppBarState` 的构造函数是 `internal`，`offsetPx` 也是 `internal`。
- 因此应用侧既不能自建状态，也不能写偏移。

### 三、绕法：把折叠范围压成 0

读 `topappbar/TopAppBar.kt` 的 layout 段：

```kotlin
val expanded = expandedHeight.roundToPx()
val collapsed = collapsedHeight.roundToPx()
val range = (expanded - collapsed).coerceAtLeast(0)
val offset = (state?.offsetPx ?: 0f).coerceIn(0f, range.toFloat())
val height = constraints.constrainHeight((expanded - offset).roundToInt())
```

顶栏高度 = `expanded - offset`。**传入 `expandedHeight == collapsedHeight`，`range` 为 0，COUI 内部那套"折叠成紧凑态"的逻辑自动失效**，高度完全听宿主的。

于是实现只需三步：

1. `LargeTopAppBar(expandedHeight = H, collapsedHeight = H)`，不传 `scrollBehavior`、不挂 `nestedScroll`；
2. `Modifier.graphicsLayer { translationY = -offset; alpha = 1 - ratio }` 把整块顶栏移出屏幕；
3. 正文顶留白**反向**减去同样的 `offset`。

不重造顶栏的另一个原因：`AppBarLayout` 的标题摆放末尾有 `coerceIn(0f, height - titleHeight)` 兜底，高度退到 0 时不会出现负坐标崩溃，只是标题被压出可视区——正是想要的效果。

### 四、唯一的坑：留白方向

Scaffold 给的顶留白里已经含了顶栏高度。三种写法里只有一种对：

| 写法 | 结果 |
| --- | --- |
| 只上移顶栏，留白不动 | 正文原地不动，顶栏在它上方，中间空出一条越来越宽的缝 |
| 照搬 COUI 的 `contentPadding`（顶栏收多少、正文让出多少） | 上移与让白互相抵消，正文一动不动 |
| **`base - offset`，且不取负** | 顶栏与正文贴合，一起移动 |

符号搞反时真机表现是"标题能缩但正文不动"，肉眼很难定位。`PinnedLargeTopAppBarTest` 用数值钉住这一条。

### 五、退让范围含状态栏

退让上限取「顶栏 110dp + 状态栏高度」。若只算 110dp，退到底时标题已滚走，但顶栏容器仍占着一条状态栏高的空白，正文顶部会空一块。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **159 / 159 PASS**（新增 8 条） |
| `:app:lintDebug` | **0 errors / 0 warnings**（仅两条依赖版本提示） |

新增 `PinnedLargeTopAppBarTest`（8 条）：

- `keepsScaffoldPaddingWhenAppBarIsFullyVisible` / `subtractsRetreatDistanceFromTopPadding` / `clampsToZeroWhenAppBarIsFullyGone` / `neverReturnsNegativePadding`
- `offsetTracksScrollOneToOne` / `offsetConvertsPixelsBackToDp` / `offsetClampsNegativeOverscrollToZero` / `offsetNeverExceedsRange`

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `release/1.0.0-beta48/OPPower-1.0.0-beta48-debug.apk` | 38,434,182 bytes | 48 |
| `release/1.0.0-beta48/OPPower-1.0.0-beta48-release-debugkey.apk` | 3,504,243 bytes | 48 |
| `release/1.0.0-beta48/OPPower-1.0.0-beta48-release-unsigned.apk` | 3,485,500 bytes | 48 |

debug 比 beta47 多 4 B，release 完全相同——纯逻辑改动，无新增资源。

### 仍需真机复验

- 二级页向上滑：顶栏与标题是否**一起**滑走，有没有残留 52dp 顶栏。
- 滑回顶部：标题是否跟回来，有没有半透明残留。
- 滑到底：正文顶部有没有空出一块（留白方向错的症状）。
- 快速甩动到底部再回弹：`translationY` 与 `alpha` 是否会短暂错位。
- 目前只有「关于 OPPower」一页用大标题。若确认这套行为正确，下一步会把设置二级页全部切过来。

---

## 1.0.0-beta49

顶栏改成「照抄 ColorOS 那一支」，设置主页把搜索框放进顶栏搜索槽。

### 一、beta48 的判断是错的

beta48 我认定 ColorOS 顶栏是「`LargeTopAppBar` + 整块滚走」，于是手工算偏移。用户看了截图和录屏的原话是：

> 这一块为什么不是透明的，而且这个字体不对吧大小也不对吧

> 发你视频了，你怎么都不会照着抄啊

重读 `coui/topappbar/CollapsingTopAppBar.kt` 才发现，它内部按 `hasSearchBar` 分成两套完全不同的行为——而 ColorOS 设置页是**带搜索槽**的那一套：

| | 无搜索槽 | 有搜索槽（ColorOS 实际用的） |
| --- | --- | --- |
| 底色范围 | 只有标题高度，下面 8dp 渐隐 | 铺到搜索框底再 +14dp |
| 大标题 | 32dp 按 `0.75` 缩放（实际 24sp） | 40sp 按 `.7` 缩放，**带淡出** |
| 收起后 | 标题钉在 52dp 紧凑栏里不走 | 标题整体淡出，留一条空栏 |
| 滚动推进 | `state.offsetPx` | `searchState.pageProgress` |

「不透明」和「字体不对」是同一处根因的两种表现：走错分支后底色只铺 110dp、标题按 24sp 画，都不对。

### 二、底色「透明」的真相

没有去改 `TopAppBarSurface`。它的 `gradientColor` 默认取 `LocalScaffoldTopBarContainerColor`，而这个值由 `Scaffold` 用 `containerColor` 注入——**顶栏底色本来就和页面同色**，同色叠同色，观感自然透明。看起来不透明，是因为无搜索槽分支下底色只延伸到标题高度，正文从 110dp 之下开始漏出来，边界看得见。

改成有搜索槽分支后，底色延伸 `searchBlurExtension = 14.dp` 到搜索框下方，与正文的过渡落在搜索框底下，视觉上就是一块干净的纯色。

### 三、返回键没有位置

`CollapsingTopAppBar` 的签名里只有 `title: String`：

```kotlin
fun CollapsingTopAppBar(
    modifier: Modifier, state: CouiCollapsingAppBarState,
    searchState: CouiSearchBarState = rememberCouiSearchBarState(false),
    title: String, color: Color, ..., searchBar: (@Composable () -> Unit)? = null,
)
```

标题槽是字符串、没有导航图标位、也没有内容槽。返回键只能做成**浮层**压在 `Box` 顶层左上角：顶栏标题的起始缩进是 `titleOnlyStart = 16.dp`，放一个 40dp 的 `TopAppBarButton` 正好填满这段留白。它随正文滚动量淡出（`backButtonAlpha`），退干净后不留在屏幕上。

`searchState` 必须显式传 `rememberCouiSearchBarState(false)`：`active` 恒为 false 时 `CollapsingTopAppBar` 里 `p = searchState.pageProgress` 保持在 0，标题不会走"搜索态"那套 `translationY = -200f * p`。

### 四、字体

顶栏标题原本走 `Typeface.create("sans-serif-semibold", 700)`，这是系统内置族名，**拿不到 OPPO Sans**。文字本身是 OPPO Sans（`CouiTypography` 已灌），但大标题那个 `Typeface` 不是，于是大标题退化成系统默认字形加合成加粗——中文大字号合成加粗会糊边，这就是"字体不对"的直接原因。

`CollapsingTopAppBar` 不收 `expandedTitleStyle`，改不了顶栏内那个 `Typeface`。不过它的标题是 `BasicText(title, style = titleStyle.copy(color = titleColor))`，`titleStyle` 由 `rememberCouiSearchBarState` 之外的内部分支决定——这条路走不通，所以本版先把基准字号/字色拿准，字体族留作后续（需要 COUI 暴露 `expandedTitleStyle`，或自绘顶栏）。

新增 `OppoFont.weightFamily(700)` 与 `pickClosest()`：在已找到的字重里取最接近目标的一档，同距离优先取偏粗的一侧。等 COUI 放开样式参数即可直接接上。

### 五、搜索框照实测

| | ColorOS 实测 | beta48 | beta49 |
| --- | --- | --- | --- |
| 高度 | ≈47dp | 40dp | 47dp |
| 圆角 | 24dp 大圆角 | `CouiCapsuleShape` 全胶囊 | `CouiShape(24.dp, 2.5f)` |
| 左右边距 | 16dp | 16dp + `vertical 4.dp` 叠层 | 0（搜索槽自带 16dp） |
| 位置 | 大标题正下方、跟着标题走 | 正文 `Spacer(12.dp)` 之后 | 顶栏搜索槽 |

搜索框从正文挪进顶栏搜索槽后，滚动时它和大标题一起收/一起放，而不是被正文带着跑。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **163 / 163 PASS** |
| `:app:lintDebug` | **0 errors**，2 warnings（依赖有新版提示） |

测试变动：

- 删 `PinnedLargeTopAppBarTest`（8 条）——它测的偏移换算随实现一起删掉了
- 新增 `LargeTitleHeaderTest`（7 条）：`backButtonAlpha` 的 0 / 半程 / 退干净 / 超量 / 负滚动 / 密度换算，以及搜索框 47dp 与 24dp 圆角
- `OppoFontTest` 新增 4 条：`pickClosest` 的回落、同距离取粗、单字体、空输入

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `OPPower-1.0.0-beta49-debug.apk` | 38,434,182 bytes | 49 |
| `OPPower-1.0.0-beta49-release-debugkey.apk` | 3,520,627 bytes | 49 |

release 比 beta48 多 16,384 bytes——`CollapsingTopAppBar` 那条分支（搜索槽布局、返回键浮层、`pickClosest`）都进了 R8 后的产物。debug 字节数不变，因为 `--rerun-tasks` 完整重编后各 dex 的对齐恰好落在同一个总长上。

### 仍需真机复验

- 设置主页顶栏底色是否与正文同色、有没有可见的分界线（这是本次的核心）。
- 向上滑：标题与搜索框是否一起淡出，返回键是否同步消失。
- 滑回顶部：标题是否重新出现、有没有卡在半透明。
- 搜索框高度与圆角是否与录屏一致。
- 「关于 OPPower」页（也用 `largeTitle = true`）表现是否同上。

---

## 1.0.0-beta50

性能：消除「每秒一次整页重组」，修掉滚动时列表重复测量。

### 一、根因：`UiState` 被整体传进二级页

`MainViewModel` 每秒把 `UiState` 整体替换一次：

```kotlin
mutable.value = mutable.value.copy(
    battery = battery,
    controllerStatus = controllerStatusForDisplay(...),
)
```

而 `OPPowerApp` 当时的写法是把 `state.value` 直接递给二级页：

```kotlin
Page.CHARGE_SETTINGS -> ChargeSettingsPage(state = state.value, ...)
Page.SETTINGS -> SettingsRouter(state = state.value, ...)
```

`UiState` 是 data class，`battery` 一变整个实例就不 equals，于是**每秒一次**：

- `GeneralSettingsPage`（1377 行）整棵组合树重新走参数比对
- `ChargeSettingsPage`（950 行）同上
- 甚至 `state.gearReadResult`、`shizukuInstalled` 这些跟电池毫无关系的分支也要重新判一遍

`MainPage` 早就用 `derivedStateOf` 按字段拆开了，但两个二级页没有——所以「进设置页之后一直发烫卡顿」和「主页还行」。

### 二、改法：在入口处切窄状态

`OPPowerApp` 里定义一次 `slice`：

```kotlin
@Composable
private fun <T> slice(state: State<UiState>, selector: (UiState) -> T): State<T> =
    remember(state) { derivedStateOf { selector(state.value) } }
```

页面只收自己真正用到的几片。例如快充设置页：

```kotlin
ChargeSettingsPage(
    settings = settingsSlice,           // 只在改设置时变
    acOnline = acOnlineSlice,           // 只在插拔时变
    controllerStatus = controllerStatusSlice,
    gearReadResult = gearSlice,
    shizuku = shizukuSlice,             // 四项打包，避免四次订阅
    ...
)
```

关键点：`derivedStateOf` 的 selector 结果没变就**不通知**。电池每秒刷新只影响读 `battery` 的那几个叶子，设置页不动。

`ShizukuAccess` 与 `HistoryCounts` 两个小 data class 就是为这个打包的：

- `ShizukuAccess`：四项状态本来就一起变，拆成四个 `State<Boolean>` 只会让页面订阅四遍。
- `HistoryCounts`：数据管理页只要「点数 / 记录数」，不该把上千个采样点的 List 交给它比较。

### 三、滚动时列表被重复测量

`MainPage` 原来这样写：

```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize().padding(top = topInset),
    contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 24.dp),
)
```

折叠顶栏时 `topInset` 在 110dp → 52dp 之间**每帧**都在变。挂在 `Modifier.padding` 上会改 LazyColumn 自己的测量约束，等于滚动过程中每帧把整个列表重新量一遍。

改成 `contentPadding = PaddingValues(top = topInset + 8.dp, ...)`：只影响内容起始偏移，可见项不需要重新测量。视觉一致。

### 四、曲线刻度文字每帧重排

`ChartCard` 的 `drawWithCache` 里做了 `textMeasurer.measure()` × 9。绘制缓存块每次重组都会重跑，于是每来一个采样点就把刻度重新排一遍版，`TextMeasurer` 那点 LRU 也来回驱逐。

刻度文字只跟轴范围与配色有关、跟画布尺寸无关，挪到 `remember(axisLabels, axisStyle, textMeasurer)` 里算一次即可。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩） |
| `:app:testDebugUnitTest` | **171 / 171 PASS**（新增 8 条） |
| `:app:lintDebug` | **0 errors**，2 warnings（依赖版本提示） |

新增 `UiStateSlicingTest`（8 条），直接对「电池刷新前后各切片是否 equals」下断言：

- `settingsSliceSurvivesBatteryRefresh` / `systemAccessSliceSurvivesBatteryRefresh`
- `shizukuSliceSurvivesBatteryRefresh` / `historyCountsSurviveSampleRefresh`
- 反向用例：`historyCountsChangeWhenSampleCountChanges` / `acOnlineSliceChangesOnPlugEvent`
- `shizukuAccessReadyRequiresAvailableAndPermission` / `historyCountsIsEmptyOnlyWhenNoPoints`

这组用例的意义是把「什么不该变」钉住。以后谁再把整个 `UiState` 塞进页面，`equals` 断言会先报。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `OPPower-1.0.0-beta50-debug.apk` | 38,466,946 bytes | 50 |
| `OPPower-1.0.0-beta50-release-debugkey.apk` | 3,520,627 bytes | 50 |

release 与 beta49 **字节数相同**（md5 不同：`11899e0b…` vs `d523130a…`）。新增的 `HistoryCounts`/`ShizukuAccess` 和删掉的长参数列表在 R8 之后恰好等长，属巧合，已用 `strings` 确认 beta49 的 dex 里没有 `HistoryCounts`、beta50 有。

debug 增加 32,764 字节。

### 仍需真机复验

- 停在设置页 1 分钟，观察是否还在持续发热/掉帧（本轮的主要目标）。
- 主页上下滑动，折叠顶栏过程中是否比之前顺。
- 打开有曲线的页面，看曲线刷新时有没有一顿。
- 切底栏三个 tab 的手感。
- **如果仍然卡，先确认装的是 `-release-debugkey.apk` 而不是 debug 包**：debug 包不做 R8、Compose 也不走优化路径，same code 下慢三到五倍是常态。

## 1.0.0-beta51

本轮三件事：设置页闪退、温度曲线刻度叠字、底栏两端加悬浮球。

### 一、设置页闪退

报障只说「设置里有闪退」，没有栈。逐个入口审下来，找到三处必然或极易抛异常的位置：

| 位置 | 原因 | 处理 |
| --- | --- | --- |
| `GeneralSettingsPage` 无障碍设置入口 | `startActivity(ACTION_ACCESSIBILITY_SETTINGS)` 裸调用，精简 ROM 上找不到 Activity 时抛 `ActivityNotFoundException`，异常从点击回调里出去就是闪退 | 统一改走 `openSystemPage()`，失败只 Toast |
| `OPPowerApp` 首启弹窗 | `FirstRunHost` 在 `ProvideCouiMenuBackdrop` **外面**，而 COUI 的 `Dialog` 在 `material != null` 时 `checkNotNull(LocalCouiMenuBackdrop.current)` | 挪进 backdrop 作用域（与 beta42 修的记录弹窗同一类问题，只是这条路径只在清数据后第一次启动才走到） |
| `DataManagementPage` 存储占用 | `remember(counts.points) { filesDir.walkTopDown()... }` 放在组合期。采样点每秒增加，等于每秒在主线程遍历一次目录 | 改 `produceState` + `Dispatchers.IO` |

顺带把「清除全部记录」「重置全部设置」两个确认弹窗改走 `couiPrimaryAction()`：原来手写 `CouiDialogAction` 没给 `style`/`colors`，按钮落到 Secondary 样式（灰底），主操作应该是强调色。

```kotlin
/** 所有跳系统设置的入口统一走这里，失败只提示、不抛。 */
private fun openSystemPage(context: Context, intent: Intent, failureHint: String) {
    val opened = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) Toast.makeText(context, failureHint, Toast.LENGTH_SHORT).show()
}
```

### 二、温度曲线刻度叠字

根因是**步长由数据跨度决定**：

```kotlin
val gridLines = range.toInt().coerceIn(1, 40)   // 24°C~41°C → 17 格
return CurveAxis(minimum, maximum, range / gridLines)
```

17 行 7sp 的刻度字塞进 190dp 的曲线框，行距只有约 11dp，底部几行直接糊在一起（截图里 25.0 / 24.0 是叠的）。功率轴因为固定 10 W 一格，8 行还算正常。

改成**由画面高度反推步长**，再吸附到 1 / 2 / 5 / 10：

```kotlin
val step = niceStep(
    (rawMaximum - rawMinimum).coerceAtLeast(TEMPERATURE_MIN_RANGE_C) / TEMPERATURE_TARGET_GRID_LINES,
)
```

24~41 现在得到 20/25/30/35/40/45 六行。`ChartCard` 另加一层像素级兜底：行距小于 1.4 倍字高时按 `index % stride` 抽稀，首尾两个刻度一定保留（轴的两端是读数基准）。

横轴时间标签同步对齐到绘图区（`start = 35.dp, end = 12.dp`）：原来 `Row` 铺满卡片，最后一个 `23:46` 会顶到卡片右缘外。

### 三、底栏两端悬浮球

`NavigationBar` 自带左右附件球槽位，不用自己拼 `Box` + `Fab`：

```kotlin
NavigationBar(
    items = listOf(主页 / 快充 / 检测),
    selectedIndex = tab.ordinal,
    leftIcon = CouiIcons.Filled.Users,
    leftDescription = "加入 QQ 群",
    leftVisible = true,
    onLeftClick = onJoinQq,
    rightIcon = CouiIcons.Filled.SettingDetail,
    rightDescription = "设置",
    rightVisible = true,
    onRightClick = onOpenSettings,
    onSelection = { tab = MainTab.entries[it] },
)
```

两颗 56dp 圆形球、独立玻璃材质、与中间胶囊有融合动画；左侧走中性材质、右侧走强调色材质。顶栏的 `QQ 群` 胶囊和齿轮按钮随之删除。

自己拼的版本拿不到同一份 `LocalCouiBackdrop` 采样，融合动画也没有——这条已写进 `AGENTS.md`。

### 四、崩溃可观测

设置页的闪退依赖机型和 ROM，光读代码只能猜。新增 `CrashRecorder`：在 `MainActivity.onCreate` 最前面装 `Thread.setDefaultUncaughtExceptionHandler`，把**最近一次**未捕获异常（时间、线程、版本、机型 + 完整栈）写到 `files/last-crash.txt`。

只记录不吞——处理完仍然交回上一个 handler，进程照常退出。

设置 → 数据管理 → 底部「最近一次异常」：有记录才出现，可一键复制全文、可清除。**下次再闪退，把那一段贴出来就能直接定位。**

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩 + lintVitalRelease） |
| `:app:testDebugUnitTest` | **173 / 173 PASS**（新增 2 条） |
| `:app:lintDebug` | **0 errors**，2 warnings（依赖版本提示） |

新增用例：

- `temperatureAxisKeepsTickLabelsReadable`：24~41 必须是 5 的步长、20~45 的范围、刻度数 3~7。
- `temperatureAxisStaysReadableForNarrowAndWideSpans`：1°C 跨度与 -20~120 跨度都落在 2~7 行。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `OPPower-1.0.0-beta51-debug.apk` | 38,483,330 bytes | 51 |
| `OPPower-1.0.0-beta51-release-debugkey.apk` | 3,520,683 bytes | 51 |

debug +16,384 字节；release +56 字节。均已上传网盘 `OPPower 安装包/`，beta50 的两个包已删除。

### 仍需真机复验

- 设置里每个入口点一遍：无障碍设置、电池优化、应用详情、悬浮窗、通知权限、Shizuku 授权、热控诊断、清除记录、重置设置。
- 底栏左右两颗球的位置与融合动画（`Users` / `SettingDetail`），以及三页切换时球是否跟着走。
- 温度曲线：24~41 区间应显示 6 行刻度，且不再与上下边框粘连。
- 若仍闪退：设置 → 数据管理 → 「最近一次异常」→ 复制，把那段发回来。

## 1.0.0-beta52

本轮四件事：设置继续闪退、曲线卡片小标题、主页顶栏折叠行为、顶栏标题字号。

### 一、设置闪退（第二轮）

beta51 修了三处，用户反馈仍闪退。这次不再泛查入口，而是回到 diff 本身找**唯一一条未被长期验证的路径**：

```kotlin
// beta43 → beta52 之间新增，设置主页独有
CouiPage(
    largeTitle = true,
    search = { CouiSearchField(...) },   // ← 只有这一页用了顶栏搜索槽
)
```

`git diff` 显示 beta43 时搜索框还在正文里，后来才挪进 `CollapsingTopAppBar` 的 search 槽。而 COUI 的 `TopAppBarSearch` 里有这些断言：

```kotlin
val renderer = checkNotNull(state.renderer)
val overlay = checkNotNull(LocalCouiToolbarOverlay.current)
val end = checkNotNull(expandedCenter)
```

全部依赖测量时序，命中任意一个就是组合期 `IllegalArgumentException` —— 症状正是「点进设置就闪退，且只在特定机型/帧率偶发」。大标题的无搜索分支在关于页已经跑了十几个版本，是稳的。

**处理**：搜索框回到正文顶部，`largeTitle` 保留，不再传 `search`。同时修掉三处确定的风险点：

| 位置 | 问题 | 处理 |
| --- | --- | --- |
| `CouiKit.CouiSearchField` | `placeCursorBeforeCharAt(value.length)` 在清空后文本为空时越界 | 改 `selection = TextRange(value.length)`，`TextRange(0)` 对空串合法 |
| `CouiKit.CouiLargePage` | `state.contentPadding()` 是组合期唯一调用库内部动画状态的地方 | `runCatching` 兜底成 Scaffold 的 inset，最多顶距差一点 |
| `MainPage` / `ChargeSettingsPage` | `locales[0]` 在语言列表为空时越界 | `remember(configuration)` 里先判空（顺带避开 `NonObservableLocale` lint） |

**顺带把「闪退」从猜变成可定位**：

- `CrashRecorder.markLocation()` 在每次页面切换时记下当前页，崩溃栈里多一行 `位置：设置 / HOME`。
- 新增 `CrashNotice`：挂在整个 App 根部（不在设置页里——崩在进设置途中根本走不到数据管理页），有记录时启动即显示一条 12 秒后自动消失的提示，点一下复制全文。

### 二、曲线卡片的小标题

删掉 `PowerRealtimeChart` / `TemperatureRealtimeChart` 里的 `Text("功率曲线")` / `Text("温度曲线")` 两处 `groupTitle()`。卡片自己已有「实时功率」/「电池温度」标题，上面那行是重复的。删掉后那层只为放标题而存在的 `Column` 一并去掉。

### 三、主页顶栏

**折叠到底不再留标题**：

```kotlin
LargeTopAppBar(
    collapsedHeight = 0.1.dp,   // 库要求 > 0.dp，给一个视觉上为 0 的值
    ...
)
// 标题：
Modifier.graphicsLayer { alpha = 1f - behavior.state.collapsedFraction }
```

原来是 `exitUntilCollapsed`，滚到底会钉住一条 52dp 的紧凑栏，「OPPower」一直挂在那儿。现在整条退出，正文直接滚到状态栏下，标题同步淡出。`collapsedFraction` 是公开属性，在 `graphicsLayer` 里读只触发重绘，不引发重组。

**只调顶栏标题字号**：`expandedTitleStyle = TextStyle(fontSize = 48.sp)`（库默认 40sp）。`withTitleOverrides` 会保留库的 fontFamily 和字重，所以只改了尺寸。卡片标题、列表行标题、正文全部没动。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩 + lintVitalRelease） |
| `:app:testDebugUnitTest` | **173 / 173 PASS** |
| `:app:lintDebug` | **0 errors**，2 warnings（依赖版本提示） |

中途踩了一次 `NonObservableLocale`：`remember` 外面直接读 `Locale.getDefault()` 被 lint 判为不可观察，已把计算挪进 `remember(configuration)`。

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `OPPower-1.0.0-beta52-debug.apk` | 39,440,529 bytes | 52 |
| `OPPower-1.0.0-beta52-release-debugkey.apk` | 3,537,067 bytes | 52 |

debug +957,199 字节：这版 debug 包把新增的 COUI 顶栏/搜索组件整链带进来了，release 只 +16,384。均已上传网盘 `OPPower 安装包/`，beta51 的两个包已删除。

### 仍需真机复验（这次请重点看）

1. **进设置、切各个二级页**：如果还闪退，**重启 App 后主页顶部应该有一条「上次运行发生了崩溃」的提示**，点一下复制，把内容发回来。现在栈里会带 `位置：设置 / XXX`，一次就能定位。
2. 设置主页的搜索框现在在正文顶部，输入/清空都试一遍（清空那条路径之前会崩）。
3. 主页向上滑到底：顶栏应该整条收掉，不再留「OPPower」小字；标题是 48sp，比上一版明显大一圈。
4. 功率/温度卡片上方那两行「功率曲线」「温度曲线」应该已经没有。

装包请认准 `-release-debugkey.apk`（3.5 MB）。38 MB 的 debug 包不做 R8，手感差三到五倍。

## 1.0.0-beta53

**设置闪退定位到了，真凶不是 beta52 猜的搜索槽。**

用户按 beta52 的提示条复制回了完整栈，第一行就是：

```text
java.lang.IllegalStateException: 顶栏按钮必须位于顶栏效果作用域内
    at com.itos.coui.topappbar.TopAppBarButtonKt.TopAppBarButton(TopAppBarButton.kt:70)
    at com.oppower.ui.CouiKitKt.CouiLargePage(CouiKit.kt:596)
    at com.oppower.ui.GeneralSettingsPageKt.SettingsHomePage(GeneralSettingsPage.kt:296)
```

### 根因

`CouiLargePage` 的返回键因为顶栏没有 `navigationIcon` 槽，被做成浮层压在标题左侧。那个浮层里用了 COUI 的 `TopAppBarButton`，而它一进组合就执行：

```kotlin
val backdrop = checkNotNull(LocalCouiToolbarBackdrop.current) {
    "顶栏按钮必须位于顶栏效果作用域内"
}
```

`LocalCouiToolbarBackdrop` 是 `internal` 的 `staticCompositionLocalOf`，**只由 `TopAppBarSurface` 在顶栏子树里提供**。浮层位于 Scaffold 顶栏之外，永远取不到 → 进设置主页必然崩。

这也解释了为什么 beta37 加大标题之后问题就一直存在：只有 `largeTitle = true` 的两页（设置主页、关于页）走 `CouiLargePage`，其它十几个二级页用普通 `CouiPage`，返回键在 `TopAppBar` 的 `navigationIcon` 槽里，作用域正确。

### 修复

浮层返回键换成普通 `IconButton`（40dp 点击区 + 24dp 箭头），不依赖任何 COUI 顶栏作用域。视觉上就是一个返回箭头，差异可忽略。

同时修了诊断信息本身：上次日志里 `位置：主页` 是错的——`markLocation` 放在 `LaunchedEffect` 里，要等提交阶段才写，而崩溃发生在组合阶段。改成在 `OPPowerApp` 组合体里直接写 `@Volatile` 字段，现在会准确记成 `设置 / HOME`。

**beta52 关于 search 槽的判断是错的，已写进 AGENTS.md 纠正。** 那次改动没有坏处（少一条测量时序依赖），但没解决问题。这次是拿栈定位的，不是猜的。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩 + lintVitalRelease） |
| `:app:testDebugUnitTest` | **173 / 173 PASS** |
| `:app:lintDebug` | **0 errors**，2 warnings（依赖版本提示） |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `OPPower-1.0.0-beta53-debug.apk` | 38,499,718 bytes | 53 |
| `OPPower-1.0.0-beta53-release-debugkey.apk` | 3,537,067 bytes | 53 |

debug 比 beta52 少 940,811 字节（那一版的 debug 多带了一整条 COUI 搜索链）；release 字节数与 beta52 相同、md5 不同，仍是 R8 等长巧合。均已上传网盘 `OPPower 安装包/`，beta52 的两个包已删除。

### 真机复验

1. **进设置、在二级页之间来回切**：这次应该不会再闪退。
2. **关于页**（也是 `largeTitle = true`）：同样点进去，验证那条路径也修好了。
3. 设置主页返回键、关于页返回键都能正常返回。
4. 万一中招：启动后主页顶部仍会显示崩溃提示条，`位置` 这次会准确写出页面名。

## 1.0.0-beta54

两件小改：设置页大标题会消失、启动不再弹崩溃提示。

### 一、设置页大标题不消失

截图里「设置」两个大字一直压在列表上，滚到第 10 项还在——根因是 `CouiLargePage` 用的 `CollapsingTopAppBar` 按 `hasSearchBar` 分两支：

| | 无搜索槽（beta53 状态） | 有搜索槽 |
|---|---|---|
| 大标题 | 32sp 按 0.75 缩放（实际 24sp） | 40sp 淡出 |
| 收起后 | **标题钉在 52dp 紧凑栏里一直不走** | 整个标题淡出，留一条空栏 |

beta52 为了排查闪退把搜索框挪回正文，等于走了「无搜索槽」那支，标题从此不再淡出。而 `CollapsingTopAppBar` 的 `collapsedHeight` 是写死的 52dp，没有参数可改。

**处理**：`CouiLargePage` 换成和主页同一个 `LargeTopAppBar`，用主页已经验证过的两个手段：

```kotlin
LargeTopAppBar(
    collapsedHeight = 0.1.dp,   // 库要求 > 0.dp，给视觉上为 0 的值
    ...
)
// 标题：
Modifier.graphicsLayer { alpha = 1f - behavior.state.collapsedFraction }
```

滚动时标题随折叠进度淡出，到底整条顶栏退出，不留空栏。返回键顺势放进顶栏自带的 `navigationIcon` 槽（beta53 修闪退时那个浮层 `TopAppBarButton` 的问题也一并绕过）——这里能拿到 `LocalCouiToolbarBackdrop`，不需要再担心作用域。

顺带清理了只为旧实现服务的 `backButtonAlpha` 调用与几个 import（函数本身保留，测试还在断言它）。

### 二、启动不再弹崩溃提示

beta52/53 为了拿日志加的 `CrashNotice` 已删除。闪退修好之后每次启动都弹一条旧记录没有意义。

崩溃记录入口**只在「设置 → 数据管理 → 最近一次异常」**：`CrashRecorder` 继续写盘、`markLocation()` 继续把页面名写进栈，但不再主动打扰。要不要清掉那条 beta52 的旧记录，由用户点「清除这条记录」决定。

### 验证结果

| 任务 | 结果 |
| --- | --- |
| `:app:assembleDebug --rerun-tasks` | PASS，**0 warning** |
| `:app:assembleRelease` | PASS（R8 + 资源压缩 + lintVitalRelease） |
| `:app:testDebugUnitTest` | **173 / 173 PASS** |
| `:app:lintDebug` | **0 errors**，2 warnings（依赖版本提示） |

### 构建产物

| 产物 | 大小 | versionCode |
| --- | --- | --- |
| `OPPower-1.0.0-beta54-debug.apk` | 38,483,330 bytes | 54 |
| `OPPower-1.0.0-beta54-release-debugkey.apk` | 3,520,683 bytes | 54 |

均已上传网盘 `OPPower 安装包/`，beta53 的两个包已删除。

### 真机复验

- 设置页向上滑：大标题应随滚动淡出，到底整条顶栏消失；往回滑标题重新出现。
- 设置页与关于页的返回键仍能点。
- 启动 App 不应再出现深色崩溃提示条；记录仍可在数据管理页看到。
