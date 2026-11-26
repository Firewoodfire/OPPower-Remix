# OPPower（二改版 / Remix）

> **关于本项目**
>
> 本仓库是 **基于 [aaaaaaaax1/OPPower](https://github.com/aaaaaaaax1/OPPower) 二次修改** 而来，由 `Firewoodfire` 维护，与原仓库作者无隶属关系。
>
> 相对原版的改动集中在 **beta44–beta54**：设置页稳定性（修复大标题页返回键导致的闪退）、顶栏与曲线 UI 调整（大标题随滚动淡出、温度轴刻度重排、底栏两端悬浮球、标题字号）、性能切片优化（按字段订阅状态，减少每秒重组）、崩溃记录落盘与若干边界崩溃修复。详细逐版记录见 [`docs/VALIDATION.md`](docs/VALIDATION.md)。
>
> **机型适配**：上游原版仅针对 realme Neo8（RMX8899）/ ColorOS 17 单机型验证适配。本二改版已扩展到多个机型（含一加 13 / PJZ110），并把机型相关参数抽成独立模块，目标是覆盖 ColorOS 全系——不再把阈值、路径、功率族写死在单一机型上。参数模块的具体内容以仓库内实际代码为准。
>
> 功能与授权说明沿用上游项目，著作权归原作者所有；本仓库仅保留二改增量。

OPPower 是面向 realme Neo8（RMX8899）和 ColorOS 17 的亮屏快充控制器。它通过 Shizuku 执行受限的 `settings` 命令，维持 `cosa_ctc_value` 热控偏移，并提供功率、温度、电量监测和本地充放电趋势。

> 当前版本为 `1.0.0-beta54`。仓库包含设计书、可编译源码和本地生成的测试 APK；APK 构建产物不提交到 Git。

## 功能

- 最大输出、20–40 W、10–30 W 三个基础功率档。
- 最大输出默认持续保持 gear 0，由系统原生热控调节基础档；20–40 W 和 10–30 W 档保留温度适配，可切换为两条自定义温度条件和两条自定义电量条件。
- 温度回落时升功率**逐档进行**：每档单独走一次确认时间（默认 8 秒），避免功率一步跳回最高、几十秒后又冲回热阈值，形成几十秒一轮的功率锯齿。升温降档不限幅，跨多档也一次到位——保护温度优先。改基础档位仍立即生效，不受限幅影响。
- 插电自动校准、拔电恢复、达到 80% 电量暂停快充增强、充满后恢复系统默认。
- 前台服务保持控制；划掉界面或进入后台后仍继续执行控制。关闭总开关、拔电、电量暂停或充满时恢复系统默认；系统销毁并重建服务时保留当前控制意图，重启后自动继续。
- 电流、电压、温度、电量，以及按 LLPower 节奏采样、保留 24 小时原始记录、可左右拖动的真实时间功率/温度曲线；曲线可导出为 CSV（经系统分享面板发送到任意应用）。
- 充电检测：参考 Scene 的信息分层，先回答“充得多快”（实时功率、协商协议、电压电流、充电阶段、预计充满），再回答“为什么慢”（充电器能力对比、利用率、异常归因），最后才是“电池健康”（满充容量、设计容量、健康度、循环次数）。数据来自 `sysfs/power_supply` 只读快照，检测间隔 3–30 秒可调，异常与充满可分别提醒。
- AC 快充时若主电流或电压无效，先按 LLPower 的顺序尝试 `CURRENT_NOW`、`CURRENT_AVERAGE`、sysfs 和一次只读 `dumpsys battery`；仍为偶发零值时，最多沿用 3.5 秒同一帧的最近可靠电流与电压，之后接受真实零值。正常非零功率变化不做平滑或防抖，按 1 秒立即更新。
- 页面指标每秒刷新；曲线在充电亮屏时每 3 秒、充电熄屏时每 5 秒、未充电时每 180 秒采样，图表显示最近 8 小时并默认显示 2 小时窗口。
- 设置包含外观、通知、充电检测、稳定运行、高级保活、硬件修正、系统与诊断、数据管理、常见问题、关于与许可；设置主页支持关键字搜索，直接命中条目。
- 设置主页和系统页提供 Shizuku 状态与授权入口；未安装时打开下载页，未运行时打开 Shizuku，已运行时直接向 OPPower 申请授权。
- 外观支持主题模式、六种配色、字体和界面比例、卡片不透明度及隐藏最近任务。COUI 的行高固定为 48dp，因此不再提供"列表密度"开关，「界面比例」改为整体缩放界面与字号。
- 通知支持内容选择、刷新与熄屏刷新间隔、充满/熄屏自动精简；接电即显示，总开关关闭时也会显示未启用状态。Android 16 与 ColorOS 17 使用 LLPower 同类的实时活动卡片，需在系统通知设置开启“流体云显示实时活动”；启用后不显示应用图标、收放箭头、额外“打开 OPPower”行或“本次关闭”按钮，并保留亮屏快充档位。
- 大标题页（设置各二级页）滚动时整块顶栏连同标题一起移出屏幕，滚回顶部再滑下来，与 ColorOS 设置页一致；不再把标题收成 52dp 的紧凑顶栏钉在顶部。
- 界面字体使用 ColorOS 原厂 OPPO Sans：运行时从 `/system/fonts` 读 `OPPO Sans` / `OPlus Sans` 各字重，不打包字体文件（一个字重约 9 MB），读不到自动回落到系统默认；设置 → 外观设置 → 界面字体 可查看实际生效的文件名。
- 硬件修正支持电流方向、双芯电流、虚拟电压和绝对值显示；系统页显示真实诊断字段并打开系统权限入口。
- 硬件修正页提供只读的「热控诊断」：读取 `persist.sys.horae.enable`、`init.svc.horae`、horae 进程名、`/system/bin/horae` 是否存在、`oplus_dbg_tbat` 与三个协议节点，并单独列出 `/sys/module/oplus_chg_v2/parameters` 的全部参数与当前值。用来判断“重启 horae 强制重算热控”这条路在本机到底存不存在，以及能不能绕过服务直接改模块参数。全程不写任何值。
- 高级保活提供可选的 1×1 透明悬浮窗与不读取屏幕内容、不自动点击的无障碍保活服务。
- 二级限流：在离散档位之外，用 Shizuku 的受限通道直接写 `constant_charge_current_max` / `input_current_limit`，把充电电流连续压到目标毫安。档位负责进对功率族，电流上限负责族内精调，二者叠加解决了"两档之间没有中间态"的问题。写入值按 400mA 步进、150mA 滞回、2.5 秒冷却收敛，随温度（40°C 枢轴）与电量（60%/85% 双拐点）自动收紧；拔电、满电、SOC 锁、息屏暂停、关闭总开关、一键恢复、进入高功率保活都会写回首次介入前的原始值，稳态每 10 秒回读核对，被系统刷回默认会立刻重写；节点被写成 0（部分平台的"禁止充电"语义）时会按「原始值 → 上次实际值 → 2000 mA」应急写回。
- Shizuku 命令白名单和写入回读校验。限流节点路径写死在特权端，`AllowedCommands` 未放宽，`echo > /sys/...` 依旧被 shell 通道拒绝。白名单里的正则全部两端锚定（`^…$`），`settings put global cosa_ctc_value 0; reboot` 这类拼接串不再能通过；`cosa_ctc_value` 的可写范围与 `GearCalculator.COSA_MAX` 对齐为 -10…9。
- 快充页把「当前充电」放在最上面：实时功率、充电器协议与能力、当前利用率、本次充入电量、时长、平均/峰值功率。不需要再切到检测页或主页记录才能判断"开了快充到底有没有用"。「当前策略」行同时给出档位诊断（基础档 / cosa / 计算档 / 目标），排查"功率没上来"不必再进设置页点「读取档位」。
- 首次启动说明，以及 QQ 群号复制和邀请链接。

## 界面规范

界面**直接依赖** [COUI-Kit](https://github.com/ItosEO/COUI-Kit) 的发布产物，不再自行实现度量：

```kotlin
implementation("io.github.itoseo:coui:0.0.1-alpha16")
implementation("io.github.itoseo:coui-icons:0.0.1-alpha16")
```

用到的组件全部来自库本身：`layout.Group` / `layout.Item` / `layout.Divider` / `layout.Footer` / `layout.Scaffold`、`text.Text`（`CouiTextKind` 语义排版）、`switch.Switch`、`slider.Slider`、`segmentbutton.SegmentedControl`、`dialog.Dialog`、`topappbar.TopAppBar` + `TopAppBarButton`、`shape.CouiShape` / `CouiCapsuleShape`、`effect.couiButtonFeedback`、`scroll.couiVerticalScroll`、`theme.CouiTheme`。配色由 `CouiTheme(colorScheme = CouiColorScheme(...))` 下发，六套主题只决定 `accent` 与背景色阶。

`ui/CouiKit.kt` 只是一层**参数预设**（官方 demo 的 `DemoPreferenceRows.kt` 也是这个做法）：把「标题 + 摘要 + 右值 + 箭头」这类固定组合包成 `CouiRow`，内部调用的仍然是 `Item` / `Text` / `Switch`，没有任何自绘。

以下内容 COUI 没有提供对应组件，保持项目自绘：实时功率与温度的 Canvas 曲线、规则编辑器里的数字输入行与区间预览条、8dp 状态点。

主页面（`ui/MainPage.kt`）、充电设置（`ui/ChargeSettingsPage.kt`）、通用设置（`ui/GeneralSettingsPage.kt`）统一是 **`Scaffold` + 顶栏 + 组 + 行** 结构，没有一层自绘的卡片容器：

- 顶栏用 `topappbar.TopAppBar`，右侧动作是库的 `TopAppBarButton`（设置齿轮、QQ 群文字按钮）。`Scaffold` 内部会把正文的 backdrop 通过 `ProvideCouiBackdrop` 下发，顶栏据此对滚过来的内容做渐进模糊——这是库自带的效果，不是项目模拟的。
- 每个分组是 `layout.Group(title = ...)` + 若干 `layout.Item` + `layout.Divider`，脚注是 `layout.Footer`。
- 行内的开关是 `switch.Switch(colors = CouiSwitchDefaults.colors(colors.accent, dark))`，档位选择是 `segmentbutton.SegmentedControl`，连续值是 `slider.Slider`，弹窗是 `dialog.Dialog` + `CouiDialogAction`。
- 所有文字走 `text.Text` 与 `rememberCouiTextStyle(CouiTextKind.xxx)`，不再有硬编码的字号字重。

`ui/CouiKit.kt` 存在的唯一理由是省掉重复参数：`CouiRow` = `Item` + 两个 `Text` + `CouiIcons.Filled.Next`，`CouiSwitchRow` = `Item` + `Switch`，`CouiPage` = `Scaffold` + `TopAppBar` + `couiVerticalScroll`。官方 demo 的 `DemoPreferenceRows.kt` 也是这么包的。任何视觉参数都来自 `CouiPageDefaults`，项目里没有第二套度量。

**代价**：COUI 的 `TopAppBarButton` 与 `SegmentedControl` 用到 API 34，所以本项目的 `minSdk` 从 26 抬到 **34**；AAR 会带完整渲染器与字重，debug 包从 10.8 MB 涨到 37.8 MB（release 经 R8 + 资源压缩后 2.81 MB）。

**升级路径**：库版本写在 `app/build.gradle.kts` 的 `couiVersion`，与 `compileSdk 37.2` / AGP 9.1+ 绑定。降级 COUI 前先确认组件签名没变。

### 页面之间的转场

顶层三页（主页 / 充电设置 / 设置）和设置内部的十余个二级页各有一层转场，实现集中在 `ui/PageTransition.kt`，用 `AnimatedContent` 按页面层级判断方向：更深的页从右侧推入，更浅的页从左侧回位。

动效参数不是另起一套，直接对齐 COUI 窗口的曲线：进入 `CubicBezierEasing(.3f, 0f, .1f, 1f)`、退出 `CubicBezierEasing(.3f, 0f, 1f, 1f)`，与 `CouiDialogDefaults` 里的 `enterEasing` / `exitEasing` 是同一条。时长上只做了一处偏离：进入 240ms、淡入 130ms、退出 **120ms**。

退出压短是刻意的。主页是 `LazyColumn`，设置页是整棵子树都组合的 `Column`，两页同时存在时绘制成本翻倍——早先的等长 crossfade 在真机上能看出一顿。把退出收到 120ms（约 7 帧）后，叠层只发生在进入动画的前半段；同时淡入比位移短，避免旧页消失那一刻新页还半透明、露出页面底色。

每页的 `rememberSaveable` 状态（列表滚动位置、展开项）由 `SaveableStateHolder` 按页 key 存取，返回主页时列表不会跳回顶部。

### 大标题页

`CouiPage` 有一个 `largeTitle` 开关，打开后换成 COUI 的 `LargeTopAppBar` + `CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior`：展开时标题按 `CouiTextKind.HomeTitle`（40sp semibold）排在正文上方，向上滚动收成 18sp 小标题，顶栏高度连续变化。

正文的顶内边距不是固定的 `innerPadding`，而是 `behavior.state.contentPadding(innerPadding, density)` 换算出来的动态值——这样滚动过程中正文既不会被顶栏盖住，也不会跳变。`canScroll` 传的是"正文是否超过一屏"，内容不满一屏时顶栏不折叠。

主页与「关于 OPPower」都用这个模式：主页的大标题是应用名（`OPPower`），关于页是 ColorOS 17 关于页的范式——大标题顶栏 → 封面卡 → 无标题分组 → 分组。封面是 `ui/AboutHero.kt`，`TextureView` + `MediaPlayer` 播 `res/raw/about_hero.mp4`（4 秒无声渐变动画），首帧 webp 占位到视频真正出画才隐藏，暂停和播完切末帧。

### 底栏

主页用 COUI 的 `navigation.NavigationBar` 把一级信息拆成三页：主页（状态 / 记录 / 曲线）、快充控制、充电检测。`NavigationBar` 必须放在 `Scaffold` 的 `bottomBar` 栏位里——它要通过栏位拿到 `Scaffold` 提供的 `CouiBackdrop` 做玻璃采样，脱离栏位会 `checkNotNull` 失败。底栏是悬浮胶囊，自带安全区与 16dp 外边距。

三个页签各有独立的 `LazyListState` 和独立的 `exitUntilCollapsedScrollBehavior`：切回来时滚动位置还在，新页的大标题也不会继承上一页的收起偏移。切页用 180/120ms 的交叉淡化，不做横向位移——三页共用同一个顶栏和底栏，横推会让人以为是进了二级页。

底栏高度从 `Scaffold` 的 `innerPadding` 里返回，但只作为 `LazyColumn` 的**内容**内边距，不缩小容器：正文要延伸到底栏后面，悬浮胶囊才能透出滚过的卡片。停在快充 / 检测页时按返回键先回主页。

### 行的图标

设置主页每行都带图标，走的是 `Item` 自带的 `icon` 槽（24dp 见方，与标题间距 16dp）。图标全部来自 `CouiIcons`，用 `CouiTintedIcon` 包一层「主色 14%（深色 22%）底 + 8dp 超椭圆圆角」，表达 ColorOS 设置里"每类一个颜色"的观感。色板在 `CouiIconTint`，分昼夜两套。

设置主页的分组是不带标题的连续卡片（每 4 行一张），靠组间距表达分组，不再用"常用 / 设备与数据"这类文字标签。

## 使用条件

- 设备：realme Neo8 RMX8899，ColorOS 17。其他机型或固件未验证。
- 必须安装并启动 Shizuku，授权本应用访问 Shizuku。
- 需要真实亮屏，保持原生热保护运行。
- 系统电池功率是趋势数据；最终功率以外部功率计为准。

## 构建

项目使用 JDK 17、Android SDK 37.2、Gradle 9.6.0 和 AGP 9.4.1。请把构建工具安装在仓库之外，不提交 SDK、JDK、Gradle 发行包、`.gradle`、`build` 或 `local.properties`。

> `gradle/wrapper/gradle-wrapper.properties` 当前指向腾讯云镜像（`mirrors.cloud.tencent.com/gradle`），因为官方 `services.gradle.org` 在部分网络下不可达。能直连官方时改回 `https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip` 即可。

```powershell
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest
```

第一版 beta 使用 Android debug key 签名，适合直接安装测试。生成命令：

```powershell
./gradlew.bat :app:assembleDebug
```

构建结果位于 `app/build/outputs/apk/debug/app-debug.apk`。每次可安装版本必须同时归档到 `release/<versionName>/OPPower-<versionName>-debug.apk`，并在版本目录写入 `BUILD.txt`；不同版本使用不同目录，禁止覆盖旧版本。项目根目录保留当前版本 APK 作为快捷入口。正式发布前应创建独立的 release keystore，并执行 `:app:assembleRelease`。

## 文档

- [设计与运行规范](docs/DESIGN.md)
- [真机操作书](docs/OPERATIONS.md)
- [真机标定与验证记录](docs/VALIDATION.md)

## 隐私

应用没有网络权限，不上传电池、充电或使用记录。数据保存在本机，用于界面状态、规则执行和本次会话趋势。

## 许可与来源

本项目在交互与功能方向上参考 LLPower，独立实现 Android 工程。首次启动界面会说明这一来源。应用内“开源许可”页列出现有第三方依赖；发布前请补充与最终代码、资源和第三方依赖相符的完整许可证文件。
