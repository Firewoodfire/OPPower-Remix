package com.oppower.ui

import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.battery.ChargeInsight
import com.oppower.history.ChartHistoryStore
import com.oppower.history.CurveAxis
import com.oppower.history.CurveProcessing
import com.oppower.history.CurveSegment
import com.oppower.history.CurveSnapshot
import com.oppower.model.BatterySnapshot
import com.oppower.history.buildChargeRecord
import com.oppower.model.ChartPoint
import com.oppower.model.ChargeRecord
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.RuntimeSettings
import com.oppower.model.friendlyLabel
import com.oppower.model.powerTierLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Scaffold
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.LargeTopAppBar
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** 记录弹窗里最多列多少条历史。弹窗本身有高度上限，列太多只会拖慢首帧。 */
private const val HISTORY_IN_DIALOG = 20

/** 主页面记录弹窗的两种来源。 */
private enum class RecordKind(val title: String) {
    CHARGE("充电记录"),
    DISCHARGE("放电记录"),
}

/** 底栏的三个一级页面。图标不能挂在枚举上：COUI 的图标是 @Composable 属性。 */
private enum class MainTab(val barLabel: String, val heading: String) {
    HOME("主页", "OPPower"),
    CHARGE("快充", "快充控制"),
    DETECT("检测", "充电检测"),
}

/**
 * 主页面。
 *
 * 版式严格按 COUI 的「分组卡片 + 行」重做，不再有仪表盘式的九宫格小卡：
 * 每一屏只有「组标题（卡片外）」→「24dp 超椭圆卡片」→「48dp 行」这一种结构，
 * 行的左端是 16sp 标题、下面可以带 13sp 摘要，右端是值 / 开关 / 箭头。
 *
 * 之前那版把颜色和圆角换成了 COUI token，但骨架还是"一堆带阴影的玻璃小卡"，
 * 字号收到 9–10sp、每张卡各写一套 padding——那是刷漆，不是重做。
 */
@Composable
internal fun MainPage(
    state: State<UiState>,
    onOpenChargeSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetectSettings: () -> Unit = onOpenSettings,
    onRequestShizuku: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onJoinQq: () -> Unit,
) {
    // 滚动容器与每秒一次的遥测解耦：每个可见行只订阅自己真正渲染的那一小片。
    val batteryState = remember(state) { derivedStateOf { state.value.battery } }
    val settingsState = remember(state) { derivedStateOf { state.value.settings } }
    val controllerStatusState = remember(state) { derivedStateOf { state.value.controllerStatus } }
    val pointsState = remember(state) { derivedStateOf { state.value.points } }
    val chargeRecordsState = remember(state) { derivedStateOf { state.value.chargeRecords } }
    val dischargeRecordsState = remember(state) { derivedStateOf { state.value.dischargeRecords } }
    val insightState = remember(state) { derivedStateOf { state.value.insight } }
    val shizukuAccessState = remember(state) {
        derivedStateOf { state.value.shizukuAvailable to state.value.shizukuPermission }
    }
    val notificationAccessState = remember(state) {
        derivedStateOf {
            state.value.systemAccess.notificationsReady(usesLiveNotificationChannel())
        }
    }
    var recordKind by remember { mutableStateOf<RecordKind?>(null) }

    // 底栏把原来堆在一页里的三块信息拆开：主页看状态与曲线，快充控制、充电检测各自成页。
    // 顶栏标题跟着页走，但主页仍用应用名——ColorOS 首页的大标题就是应用名，不是"主页"。
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }

    // 每条滚动轴单独持有：切回来时位置还在；数量固定、顺序也固定，所以逐个 remember 是安全的。
    val homeScroll = rememberLazyListState()
    val chargeScroll = rememberLazyListState()
    val detectScroll = rememberLazyListState()

    // 每页一个折叠行为。COUI 的折叠状态 remember 在各自调用点上，三份互不干扰；
    // 切页时换实例，新页的标题自然从展开态开始，不会继承上一页的收起偏移。
    val homeBehavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        canScroll = { homeScroll.canScrollForward || homeScroll.canScrollBackward },
    )
    val chargeBehavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        canScroll = { chargeScroll.canScrollForward || chargeScroll.canScrollBackward },
    )
    val detectBehavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        canScroll = { detectScroll.canScrollForward || detectScroll.canScrollBackward },
    )
    val behavior = when (tab) {
        MainTab.HOME -> homeBehavior
        MainTab.CHARGE -> chargeBehavior
        MainTab.DETECT -> detectBehavior
    }

    // 停在快充 / 检测页时，返回键先回主页，而不是直接退出应用。
    BackHandler(enabled = tab != MainTab.HOME) { tab = MainTab.HOME }

    val density = LocalDensity.current
    // 这一层每帧都可能重组（顶栏折叠时 topInset 在变），把语义色算一次。
    val pageColors = CouiPageDefaults.themeColors()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection),
        containerColor = pageColors.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    // 样式由顶栏按展开/收起两档自己套，这里只给内容和溢出策略。
                    androidx.compose.material3.Text(
                        text = tab.heading,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 折叠到底时标题跟着淡出。放在 graphicsLayer 里读折叠进度，
                        // 只触发重绘，不会让顶栏每帧重组。
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - behavior.state.collapsedFraction
                        },
                    )
                },
                actions = {},
                scrollBehavior = behavior,
                // 折叠后**不留**那条 52dp 的紧凑栏：收到近乎 0，标题同步淡出，
                // 正文直接滚到状态栏底下，跟 ColorOS 主页的滚法一致。
                // 库里要求 collapsedHeight > 0 且小于 expandedHeight，所以给 0.1dp。
                collapsedHeight = 0.1.dp,
                // 只调顶栏标题：库默认 40sp，这里提到 48sp；字体族与字重由
                // `withTitleOverrides` 保留，不会被这个局部样式冲掉。
                expandedTitleStyle = TextStyle(fontSize = 48.sp),
                dark = CouiTheme.isDark,
                colors = CouiTopAppBarDefaults.colors(
                    titleColor = pageColors.title,
                    dark = CouiTheme.isDark,
                ),
            )
        },
        bottomBar = {
            // 底栏直接占 Scaffold 的栏位：NavigationBar 要拿 Scaffold 提供的 backdrop
            // 做玻璃采样，脱离栏位会 checkNotNull 失败。
            //
            // 左右两枚附件球是 NavigationBar 自己的槽位（56dp 圆形、独立玻璃材质、
            // 与中间胶囊有融合动画），不是自己拼的 Box：QQ 群走左侧普通材质，
            // 设置走右侧强调色材质，正好是 ColorOS 底栏两端各一颗的手感。
            NavigationBar(
                items = listOf(
                    CouiNavigationItem(MainTab.HOME.barLabel, CouiIcons.Filled.Grid),
                    CouiNavigationItem(MainTab.CHARGE.barLabel, CouiIcons.Filled.ShieldFlash),
                    CouiNavigationItem(MainTab.DETECT.barLabel, CouiIcons.Filled.Radar),
                ),
                selectedIndex = tab.ordinal,
                leftIcon = CouiIcons.Filled.Users,
                leftDescription = "加入 QQ 群",
                leftVisible = true,
                onLeftClick = onJoinQq,
                rightIcon = CouiIcons.Filled.SettingDetail,
                rightDescription = "设置",
                rightVisible = true,
                onRightClick = onOpenSettings,
                onSelection = { index -> tab = MainTab.entries[index] },
            )
        },
    ) { innerPadding ->
        // 顶距必须走 behavior.state.contentPadding：折叠时顶栏在 110dp → 52dp 之间连续变化。
        // 底栏高度也从这里返回，但只作为列表的内容内边距——正文要延伸到底栏后面，
        // 悬浮胶囊才能透出滚过的卡片，而不是被一条空白带隔开。
        val contentPadding = behavior.state.contentPadding(innerPadding, density)
        val topInset = contentPadding.calculateTopPadding()
        val bottomInset = contentPadding.calculateBottomPadding()
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // 切页不做横向位移：三页共用同一个顶栏和底栏，横推会让人以为是进了二级页。
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            label = "MainTab",
        ) { current ->
            val scrollState = when (current) {
                MainTab.HOME -> homeScroll
                MainTab.CHARGE -> chargeScroll
                MainTab.DETECT -> detectScroll
            }
            LazyColumn(
                state = scrollState,
                // 顶距走 contentPadding 而不是 Modifier.padding：
                // 折叠时 topInset 在 110dp→52dp 之间**每帧**都在变，挂在 Modifier 上
                // 会改 LazyColumn 自己的测量约束，等于每帧把整个列表重新量一遍；
                // 放进 contentPadding 只影响内容起始偏移，可见项不用重新测量。
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topInset + 8.dp, bottom = bottomInset + 24.dp),
            ) {
                when (current) {
                    MainTab.HOME -> {
                        item(key = "status") {
                            StatusGroup(batteryState, settingsState, onSettingsChange)
                        }
                        item(key = "records") {
                            RecordsGroup(
                                pointsState = pointsState,
                                chargeRecordsState = chargeRecordsState,
                                dischargeRecordsState = dischargeRecordsState,
                                notificationAccessState = notificationAccessState,
                                settingsState = settingsState,
                                onOpen = { recordKind = it },
                            )
                        }
                        item(key = "charts") {
                            ChartsGroup(pointsState = pointsState, batteryState = batteryState)
                        }
                        item(key = "footer") {
                            GroupFooterNote(
                                "记录最近 8 小时，每次显示 2 小时。曲线用于观察趋势，" +
                                    "实际输入功率以外部功率计为准。",
                            )
                        }
                    }

                    MainTab.CHARGE -> {
                        item(key = "control") {
                            ControlGroup(
                                batteryState = batteryState,
                                settingsState = settingsState,
                                controllerStatusState = controllerStatusState,
                                shizukuAccessState = shizukuAccessState,
                                chargeRecordsState = chargeRecordsState,
                                insightState = insightState,
                                onOpenChargeSettings = onOpenChargeSettings,
                                onRequestShizuku = onRequestShizuku,
                                onSettingsChange = onSettingsChange,
                            )
                        }
                        item(key = "charge-note") {
                            GroupFooterNote("降档只在所选最高档位之下进行，不会越过它往上顶。")
                        }
                    }

                    MainTab.DETECT -> {
                        item(key = "detect") {
                            DetectGroup(
                                settingsState = settingsState,
                                insightState = insightState,
                                onOpenDetectSettings = onOpenDetectSettings,
                            )
                        }
                        item(key = "detect-note") {
                            GroupFooterNote("检测与快充控制互不影响，关掉检测不会动正在生效的档位。")
                        }
                    }
                }
            }
        }
    }

    recordKind?.let { kind ->
        val points by pointsState
        val chargeRecords by chargeRecordsState
        val dischargeRecords by dischargeRecordsState
        RecordDialog(
            kind = kind,
            points = points,
            records = if (kind == RecordKind.CHARGE) chargeRecords else dischargeRecords,
            onDismiss = { recordKind = null },
        )
    }
}

/**
 * 组一：当前状态。
 *
 * COUI 的设置页没有"九宫格指标"，只有行。功率这一行是唯一允许放大字号的例外，
 * 因为它是这个应用的主角；其余全部回到 16sp/13sp 的行节奏。
 */
@Composable
private fun StatusGroup(
    batteryState: State<BatterySnapshot>,
    settingsState: State<RuntimeSettings>,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val battery by batteryState
    val settings by settingsState
    val currentText = if (!battery.sampleValid) {
        "—"
    } else if (settings.showCurrentInAmpere) {
        "${(abs(battery.currentMa) / 1000f).oneDecimal()} A"
    } else {
        "${battery.currentMa} mA"
    }
    val charging = battery.isCharging

    Group(
        title = "当前状态",
        footer = "数据来自系统电池服务，每秒刷新；功率是趋势值，准值以外部功率计为准。",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 13.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text("实时功率", style = CouiType.itemTitle(), color = colors.title)
                Text(
                    when {
                        charging -> "正在充电"
                        battery.acOnline -> "已接电，未在充电"
                        else -> "未接电"
                    },
                    style = CouiType.itemSummary(),
                    color = if (charging) colors.accent else colors.summary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                if (battery.sampleValid) battery.powerW.oneDecimal() else "—",
                style = CouiType.metricHuge,
                color = colors.title,
            )
            Text(
                "W",
                style = CouiType.itemSummary(),
                color = colors.summary,
                modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
            )
        }

        Divider()
        // 点这行切换 A / mA：一个长期存在但无处安放的开关，收进行里比单独占一个卡片干净。
        CouiRow(
            title = "电池电流",
            summary = "点按切换 A / mA 显示",
            value = currentText,
            valueColor = colors.title,
            onClick = { onSettingsChange { it.copy(showCurrentInAmpere = !it.showCurrentInAmpere) } },
        )
        Divider()
        CouiRow(
            title = "电池电压",
            value = if (battery.sampleValid) "${(battery.voltageMv / 1000f).oneDecimal()} V" else "—",
            valueColor = colors.title,
        )
        Divider()
        CouiRow(
            title = "电池温度",
            value = "${battery.temperatureC.oneDecimal()} °C",
            valueColor = if (battery.temperatureC >= 42f) colors.danger else colors.title,
        )
        Divider()
        CouiRow(
            title = "系统电量",
            value = "${battery.levelPercent}%",
            valueColor = colors.title,
        )
        Divider()
        CouiRow(
            title = "充电状态",
            value = battery.statusLabel,
            valueColor = if (charging) colors.accent else colors.summary,
        )
        Divider()
        CouiRow(
            title = "供电方式",
            value = if (battery.acOnline) "电源适配器" else "未接电",
            valueColor = colors.title,
        )
    }
}

/**
 * 组一：当前充电。
 *
 * 功率仍是全页唯一放大字号的指标；下面接本次充电的累计值，让"开了快充到底有没有用"
 * 这件事在同一个屏幕上能自证——不用再去主页翻记录。
 *
 * 统计口径说明：时长与充入量来自历史采样，采样点之间的变化看不到，所以拔电后数值
 * 会停在上一个采样点；`ChargeRecord` 的区间也正好覆盖"最近一次插电"，不会串上一次。
 */
@Composable
private fun CurrentChargeGroup(
    batteryState: State<BatterySnapshot>,
    recordsState: State<List<ChargeRecord>>,
    insightState: State<ChargeInsight>,
) {
    val colors = CouiPageDefaults.themeColors()
    val battery by batteryState
    val records by recordsState
    val insight by insightState
    val charging = battery.isCharging
    // 拔电后最后一条记录仍是"上一次充电"，所以只在接电时当作本次数据用。
    val session = records.lastOrNull()?.takeIf { battery.acOnline }
    val charger = insight.capability?.takeIf { battery.acOnline }

    Group(
        title = "当前充电",
        footer = "功率每秒刷新；时长与电量按本机采样累计，拔电后归零，历史见主页记录。",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 13.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text("实时功率", style = CouiType.itemTitle(), color = colors.title)
                Text(
                    when {
                        charging -> "正在充电"
                        battery.acOnline -> "已接电，未在充电"
                        else -> "未接电"
                    },
                    style = CouiType.itemSummary(),
                    color = if (charging) colors.accent else colors.summary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                if (battery.sampleValid) battery.powerW.oneDecimal() else "—",
                style = CouiType.metricHuge,
                color = colors.title,
            )
            Text(
                "W",
                style = CouiType.itemSummary(),
                color = colors.summary,
                modifier = Modifier.padding(start = 3.dp, bottom = 5.dp),
            )
        }

        charger?.let { cap ->
            Divider()
            val utilization = insight.utilizationPercent?.takeIf { it > 0 }
            CouiRow(
                title = "充电器",
                summary = buildString {
                    append(cap.protocolLabel)
                    // 利用率把"充电器有 65W，实际只用了 12W"这件事直接摆出来，
                    // 不用用户自己去检测页读异常列表。
                    utilization?.let { append(" · 当前用到 ").append(it).append('%') }
                },
                value = if (cap.maxPowerW >= 1f) "${cap.maxPowerW.toInt()} W" else "—",
                valueColor = colors.title,
            )
        }

        if (session != null) {
            Divider()
            CouiRow(
                title = "本次充入",
                summary = "${session.startPercent}% → ${session.endPercent}%",
                value = session.chargeDeltaMah?.let { "$it mAh" } ?: "—",
                valueColor = colors.title,
            )
            Divider()
            CouiRow(
                title = "本次时长",
                value = (session.endedAtMillis - session.startedAtMillis).durationText(),
                valueColor = colors.title,
            )
            Divider()
            CouiRow(
                title = "平均 / 峰值功率",
                value = "${session.averagePowerW.oneDecimal()} / ${session.peakPowerW.oneDecimal()} W",
                valueColor = colors.title,
            )
        } else if (!battery.acOnline) {
            Divider()
            CouiRow(
                title = "本次充电",
                summary = "接入电源后开始统计时长、充入电量与平均功率",
                value = "—",
                valueColor = colors.secondary,
            )
        }
    }
}

/** 组二：快充控制。开关、档位、当前策略都在这一组里，不再拆成三张卡。 */
@Composable
private fun ControlGroup(
    batteryState: State<BatterySnapshot>,
    settingsState: State<RuntimeSettings>,
    controllerStatusState: State<ControllerStatus>,
    shizukuAccessState: State<Pair<Boolean, Boolean>>,
    chargeRecordsState: State<List<ChargeRecord>>,
    insightState: State<ChargeInsight>,
    onOpenChargeSettings: () -> Unit,
    onRequestShizuku: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val settings by settingsState
    val status by controllerStatusState
    val shizukuAccess by shizukuAccessState
    val controlActive = status.phase == ControllerPhase.ACTIVE

    // 拆成独立页后，主页那颗大功率数字看不到了。调档位时最想知道的就是"现在多少瓦"，
    // 所以把它连同本次充电的累计值一起放在最上面，不改任何控制逻辑。
    CurrentChargeGroup(
        batteryState = batteryState,
        recordsState = chargeRecordsState,
        insightState = insightState,
    )

    Group(title = "快充控制", footer = defaultSummary(settings)) {
        CouiSwitchRow(
            title = "亮屏快充",
            summary = "接电后按所选档位维持高功率，拔电自动恢复系统默认",
            checked = settings.masterEnabled,
            onCheckedChange = { enabled -> onSettingsChange { it.copy(masterEnabled = enabled) } },
        )
        Divider()
        CouiRow(
            title = "基础档位",
            summary = "规则只会在所选档位基础上继续降档",
            value = settings.baseTier.friendlyLabel,
            valueColor = colors.title,
            chevron = true,
            onClick = onOpenChargeSettings,
        )
        Divider()
        CouiSwitchRow(
            title = "高功率保活",
            summary = "强制亮屏快充无效时再试；开启后由系统保活通道接替档位控制，80% 或充满后暂停",
            checked = settings.nativeLevelZeroEnabled,
            onCheckedChange = { enabled ->
                onSettingsChange { it.copy(nativeLevelZeroEnabled = enabled) }
            },
        )

        if (settings.masterEnabled || settings.nativeLevelZeroEnabled) {
            Divider()
            CouiRow(
                title = "当前策略",
                // 把诊断拆进摘要：目标档、基础档、实际计算档三者的关系是排查"功率没上来"的
                // 唯一依据，藏在设置页的"读取档位"里没人会点。
                summary = gearDiagnostic(status),
                value = if (settings.nativeLevelZeroEnabled && !settings.masterEnabled) {
                    levelZeroHeadline(status)
                } else {
                    powerTierLabel(status.desiredGear)
                },
                valueColor = if (controlActive) colors.accent else colors.secondary,
            )
            Divider()
            CouiRow(
                title = "控制状态",
                summary = status.message,
                value = status.phase.label,
                valueColor = if (controlActive) colors.accent else colors.summary,
            )
            if (status.appliedCurrentLimitMa > 0) {
                Divider()
                val observed = status.observedCurrentLimitMa
                CouiRow(
                    title = "电流上限",
                    summary = when {
                        observed == status.appliedCurrentLimitMa -> "系统已回读确认"
                        observed > 0 -> "系统回读 $observed mA"
                        else -> "等待回读"
                    },
                    value = "${status.appliedCurrentLimitMa} mA",
                    valueColor = if (observed == status.appliedCurrentLimitMa) {
                        colors.accent
                    } else {
                        colors.secondary
                    },
                )
            }
        }

        if (!shizukuAccess.first || !shizukuAccess.second) {
            Divider()
            CouiRow(
                title = "系统控制权限",
                summary = if (!shizukuAccess.first) "需要先启动 Shizuku 服务" else "授权后才能写入系统设置",
                value = "去授权",
                valueColor = colors.danger,
                chevron = true,
                onClick = onRequestShizuku,
            )
        }
        if (status.phase == ControllerPhase.ERROR) {
            Divider()
            CouiRow(
                title = "控制暂时中断",
                summary = status.message,
                value = "检查",
                valueColor = colors.danger,
                chevron = true,
                onClick = onOpenChargeSettings,
            )
        }
    }
}

/**
 * 档位诊断摘要。
 *
 * 系统实际档位 = 热控基础档 + cosa 偏移，OPPower 只能改 cosa。三个数任何一个对不上，
 * 用户看到的功率就不会变，所以直接把三者摆在同一行，排查时不用再进设置页点"读取档位"。
 */
private fun gearDiagnostic(status: ControllerStatus): String? {
    if (status.levelZeroMode) return null
    val base = status.baseGear ?: return null
    val calculated = status.calculatedGear ?: return null
    val cosa = status.appliedCosa
    val head = "基础档 $base · cosa ${if (cosa >= 0) "+" else ""}$cosa · 计算档 $calculated"
    val target = status.desiredGear
    return if (target != null && target != calculated) "$head · 目标 $target" else head
}

/** 组三：充电检测。原来那张自绘卡片拆成行，一条信息一行。 */
@Composable
private fun DetectGroup(
    settingsState: State<RuntimeSettings>,
    insightState: State<ChargeInsight>,
    onOpenDetectSettings: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val settings by settingsState
    val insight by insightState

    // 检测关掉时这一页不能是空白：给一条说明行 + 直达开关，否则用户点进来会以为坏了。
    if (!settings.chargeDetectEnabled) {
        Group(
            title = "充电检测",
            footer = "只读取 /sys/class/power_supply 快照，不写入任何设置，也不参与快充控制。",
        ) {
            CouiRow(
                title = "检测已关闭",
                summary = "开启后才会识别充电阶段、充电器能力与功率稳定性",
                value = "去开启",
                valueColor = colors.accent,
                chevron = true,
                onClick = onOpenDetectSettings,
            )
        }
        return
    }

    Group(
        title = "充电检测",
        footer = "只读取 /sys/class/power_supply 快照，不写入任何设置，也不参与快充控制。",
    ) {
        CouiRow(
            title = "充电阶段",
            summary = insight.stageDetail.takeIf { it.isNotBlank() },
            value = insight.stage.label,
            valueColor = colors.accent,
        )
        Divider()
        CouiRow(
            title = "充电器能力",
            summary = insight.capability?.protocolLabel ?: "尚未识别协议",
            value = insight.capability?.let { "${it.maxPowerW.toInt()} W" } ?: "—",
            valueColor = colors.title,
        )
        Divider()
        CouiRow(
            title = "预计充满",
            summary = if (insight.minutesToFull == null) "样本不足，按当前斜率估算" else "按当前充电斜率推算",
            value = insight.minutesToFull?.let(::formatMinutes) ?: "—",
            valueColor = colors.title,
        )
        Divider()
        CouiRow(
            title = "功率利用率",
            summary = "实际输入与该充电器峰值能力的比值",
            value = insight.utilizationPercent?.let { "$it%" } ?: "—",
            valueColor = colors.title,
        )
        Divider()
        CouiRow(
            title = "稳定性",
            summary = stabilityLabel(insight.stabilityPercent),
            value = "${insight.stabilityPercent}%",
            valueColor = if (insight.stabilityPercent >= 85) colors.title else colors.secondary,
        )
        insight.anomalies.take(3).forEach { text ->
            Divider()
            CouiRow(title = "异常", summary = text, value = "注意", valueColor = colors.danger)
        }
        Divider()
        CouiRow(
            title = "检测设置",
            summary = "检测间隔、异常提醒、满充提醒",
            chevron = true,
            onClick = onOpenDetectSettings,
        )
    }
}

/** 组四：充放电记录与通知。 */
@Composable
private fun RecordsGroup(
    pointsState: State<List<ChartPoint>>,
    chargeRecordsState: State<List<ChargeRecord>>,
    dischargeRecordsState: State<List<ChargeRecord>>,
    notificationAccessState: State<Boolean>,
    settingsState: State<RuntimeSettings>,
    onOpen: (RecordKind) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val points by pointsState
    val chargeRecords by chargeRecordsState
    val dischargeRecords by dischargeRecordsState
    val notificationsReady by notificationAccessState
    val settings by settingsState

    val chargeSummary = remember(points, chargeRecords) {
        chargeRecords.lastOrNull()?.let { record ->
            "${record.startPercent}% → ${record.endPercent}% · 峰值 ${record.peakPowerW.oneDecimal()} W"
        } ?: points.filter { it.acOnline }.takeIf { it.isNotEmpty() }?.let { list ->
            "${list.first().levelPercent}% → ${list.last().levelPercent}% · 本次会话"
        } ?: "等待接电"
    }
    val dischargeSummary = remember(points, dischargeRecords) {
        dischargeRecords.lastOrNull()?.let { record ->
            "${record.startPercent}% → ${record.endPercent}% · ${(record.endedAtMillis - record.startedAtMillis).durationText()}"
        } ?: points.filter { !it.acOnline }.takeIf { it.isNotEmpty() }?.let { list ->
            "${list.first().levelPercent}% → ${list.last().levelPercent}% · 本次会话"
        } ?: "等待数据"
    }
    val notificationValue = when {
        !notificationsReady -> "需授权"
        settings.liveNotificationEnabled -> "已开启"
        else -> "已关闭"
    }

    Group(title = "记录与通知", footer = "记录只保存在本机，导出通过系统分享面板发送。") {
        CouiRow(
            title = "充电记录",
            summary = chargeSummary,
            chevron = true,
            onClick = { onOpen(RecordKind.CHARGE) },
        )
        Divider()
        CouiRow(
            title = "放电记录",
            summary = dischargeSummary,
            chevron = true,
            onClick = { onOpen(RecordKind.DISCHARGE) },
        )
        Divider()
        CouiRow(
            title = "实时通知",
            summary = if (notificationsReady) "通知权限已就绪" else "需要在系统设置里允许通知",
            value = notificationValue,
            valueColor = if (notificationsReady) colors.title else colors.danger,
        )
    }
}

/** 组五：两张曲线。容器换成 CouiCard，让整页只剩一种卡片语言。 */
@Composable
private fun ChartsGroup(
    pointsState: State<List<ChartPoint>>,
    batteryState: State<BatterySnapshot>,
) {
    val points by pointsState
    val recordInterval = remember(batteryState) {
        derivedStateOf {
            ChartHistoryStore.sampleIntervalMillis(
                isCharging = batteryState.value.isCharging,
                screenOn = true,
            )
        }
    }
    Group(title = "实时曲线", footer = "两张图可独立左右拖动，时间节点 15 分钟。") {
        Column(Modifier.padding(vertical = 2.dp)) {
            PowerRealtimeChart(points = points, recordIntervalMs = recordInterval)
            Divider(startIndent = 0.dp, endIndent = 0.dp)
            TemperatureRealtimeChart(points = points)
        }
    }
}

/** 页尾说明：卡片外、与组说明同一种字号，不再单独做一张卡。 */
@Composable
private fun GroupFooterNote(text: String) {
    Text(
        text = text,
        style = CouiType.groupFooter(),
        color = CouiPageDefaults.themeColors().summary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 14.dp),
    )
}

private fun usesLiveNotificationChannel(): Boolean = android.os.Build.VERSION.SDK_INT >= 36

private fun levelZeroHeadline(status: ControllerStatus): String = when {
    status.phase == ControllerPhase.ACTIVE && status.levelZeroMode -> "高功率保活中"
    status.phase == ControllerPhase.ERROR -> "高功率保活需要处理"
    else -> "高功率保活已暂停"
}

private fun defaultSummary(settings: RuntimeSettings): String {
    return if (settings.customRulesEnabled) {
        "自定义：${settings.customRules.temperatureRules.size} 个温度条件 · " +
            "${settings.customRules.socRules.size} 个电量条件"
    } else when (settings.baseTier) {
        com.oppower.model.BaseTier.MAX_OUTPUT ->
            "持续输出 · 80% 暂停 · 充满暂停 · 温度过高自动保护"
        else ->
            "低于 43°C 随温度调整 · 43°C/80% 暂停 · 充满暂停 · 升温立即降档，回温逐档回升"
    }
}

/**
 * 记录详情弹窗。内部一律用 `CouiCard` + 行，不再嵌套玻璃面板：
 * 弹窗本身已经是浮层，再叠阴影会脏。
 */
@Composable
private fun RecordDialog(
    kind: RecordKind,
    points: List<ChartPoint>,
    records: List<ChargeRecord>,
    onDismiss: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val relevantPoints = points.filter { if (kind == RecordKind.CHARGE) it.acOnline else !it.acOnline }
    val fallback = remember(records, relevantPoints) {
        records.lastOrNull() ?: relevantPoints.takeIf { it.size >= 2 }?.let { group ->
            buildChargeRecord(
                points = group,
                endReason = if (kind == RecordKind.CHARGE) "当前充电中" else "当前放电中",
            )
        }
    }
    var selected by remember(records, kind) { mutableStateOf(fallback) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // COUI 的 Dialog：底部卡片、动作按钮、进出场全部走库实现。
    com.itos.coui.dialog.Dialog(
        onDismissRequest = onDismiss,
        title = kind.title,
        confirmAction = couiPrimaryAction("关闭", onDismiss),
    ) {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (selected == null) {
                    Text(
                        "暂无记录。完成一次充电或放电后，这里会显示完整统计、结束原因和历史详情。",
                        style = CouiType.itemSummary(),
                        color = colors.summary,
                    )
                }
                selected?.let { record ->
                    CouiCard {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
                        ) {
                            Text(
                                if (kind == RecordKind.CHARGE) "最近一次充电" else "最近一次放电",
                                style = CouiType.groupTitle(),
                                color = colors.accent,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(
                                    "${record.startPercent}% → ${record.endPercent}%",
                                    style = CouiType.metric,
                                    color = colors.title,
                                    modifier = Modifier.weight(1f),
                                )
                                val delta = record.endPercent - record.startPercent
                                Text(
                                    "${if (delta >= 0) "+" else ""}$delta%",
                                    style = CouiType.itemValue(),
                                    color = if (delta >= 0) colors.accent else colors.secondary,
                                )
                            }
                            RecordDetailRows(record)
                        }
                    }
                }

                if (records.isNotEmpty()) {
                    Text(
                        if (records.size > HISTORY_IN_DIALOG) {
                            "历史记录 · 最近的在前，最多显示 $HISTORY_IN_DIALOG 条"
                        } else {
                            "历史记录"
                        },
                        style = CouiType.groupTitle(),
                        color = colors.summary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                records.asReversed().take(HISTORY_IN_DIALOG).forEach { record ->
                    Box(Modifier.padding(top = 8.dp)) {
                        CouiCard(Modifier.clickable { selected = record }) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 10.dp),
                            ) {
                                Text(
                                    "${timeFormat.format(Date(record.startedAtMillis))} · " +
                                        (record.endedAtMillis - record.startedAtMillis).durationText(),
                                    style = CouiType.dense,
                                    color = colors.summary,
                                )
                                Text(
                                    "${record.startPercent}% → ${record.endPercent}%",
                                    style = CouiType.itemTitle(),
                                    color = if (selected == record) colors.accent else colors.title,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Text(
                                    "${record.peakPowerW.oneDecimal()} W 峰值 · " +
                                        "${record.maxTemperatureC.oneDecimal()} °C 最高 · ${record.endReason}",
                                    style = CouiType.dense,
                                    color = colors.summary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
    }
}

/** 详情字段：一行一个，左标签右值，和设置页的行完全同构。 */
@Composable
private fun RecordDetailRows(record: ChargeRecord) {
    val colors = CouiPageDefaults.themeColors()
    // `locales[0]` 在语言列表为空时会抛 IndexOutOfBoundsException（精简 ROM / 早期开机）。
    // 计算放进 remember：组合体里直接读 `Locale.getDefault()` 会被 lint 判为不可观察读取。
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
    }
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val durationMinutes = ((record.endedAtMillis - record.startedAtMillis).coerceAtLeast(0L) / 60_000.0)
    val deltaPercent = record.endPercent - record.startPercent
    val ratePerHour = if (durationMinutes > 0) deltaPercent / (durationMinutes / 60.0) else 0.0
    val rows = listOf(
        "充电时长" to (record.endedAtMillis - record.startedAtMillis).durationText(),
        "平均每小时充电" to "${String.format(Locale.forLanguageTag(locale.toLanguageTag()), "%.1f", ratePerHour)}%/h",
        "开始时间" to formatter.format(Date(record.startedAtMillis)),
        "结束时间" to formatter.format(Date(record.endedAtMillis)),
        "峰值功率" to "${record.peakPowerW.oneDecimal()} W",
        "平均功率" to "${record.averagePowerW.oneDecimal()} W",
        "峰值电流" to "${record.peakCurrentMa} mA",
        "平均电流" to "${record.averageCurrentMa} mA",
        "最高温度" to "${record.maxTemperatureC.oneDecimal()} °C",
        "平均温度" to "${record.averageTemperatureC.oneDecimal()} °C",
        "电荷量变化" to (record.chargeDeltaMah?.let { "+$it mAh" } ?: "--"),
        "结束原因" to record.endReason,
    )
    Column(Modifier.padding(top = 10.dp)) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) Divider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = CouiType.itemSummary(),
                    color = colors.summary,
                    modifier = Modifier.weight(1f),
                )
                Text(value, style = CouiType.itemValue(), color = colors.title)
            }
        }
    }
}

private data class RealtimeChartData(
    val power: CurveSnapshot,
    val temperature: CurveSnapshot,
)

@Composable
private fun PowerRealtimeChart(
    points: List<ChartPoint>,
    recordIntervalMs: State<Long>,
) {
    val couiColors = CouiPageDefaults.themeColors()
    val interval by recordIntervalMs
    val snapshot by produceState<CurveSnapshot?>(
        initialValue = null,
        points,
        interval,
    ) {
        value = withContext(Dispatchers.Default) {
            val end = points.lastOrNull()?.epochMillis ?: System.currentTimeMillis()
            CurveProcessing.processPower(
                points = points,
                windowStart = end - CurveProcessing.HISTORY_WINDOW_MS,
                windowEnd = end,
                recordIntervalMs = interval,
            )
        }
    }

    val data = snapshot
    if (data == null) {
        ChartLoadingCard("实时功率")
        return
    }

    var endFraction by remember { mutableFloatStateOf(1f) }
    val windowMillis = 2L * 60L * 60L * 1_000L
    val timelineNow = remember(points) { System.currentTimeMillis() }
    val historyEnd = points.lastOrNull()?.epochMillis ?: timelineNow
    val timelineEnd = maxOf(timelineNow, historyEnd)
    val availableStart = maxOf(
        historyEnd - CurveProcessing.HISTORY_WINDOW_MS,
        timelineEnd - CurveProcessing.HISTORY_WINDOW_MS,
    )
    val availableRange = (timelineEnd - availableStart - windowMillis).coerceAtLeast(0L)
    val windowStart = availableStart + (availableRange * endFraction.coerceIn(0f, 1f)).toLong()
    val windowEnd = windowStart + windowMillis
    val segments = remember(data, windowStart, windowEnd) {
        CurveProcessing.clip(data, windowStart, windowEnd)
    }

    ChartCard(
        title = "实时功率",
        unit = "W",
        color = couiColors.accent,
        segments = segments,
        windowStart = windowStart,
        windowMillis = windowMillis,
        axis = data.axis,
        onDrag = { dragAmount -> endFraction = (endFraction - dragAmount).coerceIn(0f, 1f) },
    )
}

@Composable
private fun TemperatureRealtimeChart(points: List<ChartPoint>) {
    val couiColors = CouiPageDefaults.themeColors()
    val snapshot by produceState<CurveSnapshot?>(
        initialValue = null,
        points,
    ) {
        value = withContext(Dispatchers.Default) {
            val end = points.lastOrNull()?.epochMillis ?: System.currentTimeMillis()
            CurveProcessing.processTemperature(
                points = points,
                windowStart = end - CurveProcessing.HISTORY_WINDOW_MS,
                windowEnd = end,
            )
        }
    }

    val data = snapshot
    if (data == null) {
        ChartLoadingCard("电池温度")
        return
    }

    var endFraction by remember { mutableFloatStateOf(1f) }
    val windowMillis = 2L * 60L * 60L * 1_000L
    val timelineNow = remember(points) { System.currentTimeMillis() }
    val historyEnd = points.lastOrNull()?.epochMillis ?: timelineNow
    val timelineEnd = maxOf(timelineNow, historyEnd)
    val availableStart = maxOf(
        historyEnd - CurveProcessing.HISTORY_WINDOW_MS,
        timelineEnd - CurveProcessing.HISTORY_WINDOW_MS,
    )
    val availableRange = (timelineEnd - availableStart - windowMillis).coerceAtLeast(0L)
    val windowStart = availableStart + (availableRange * endFraction.coerceIn(0f, 1f)).toLong()
    val windowEnd = windowStart + windowMillis
    val segments = remember(data, windowStart, windowEnd) {
        CurveProcessing.clip(data, windowStart, windowEnd)
    }

    ChartCard(
        title = "电池温度",
        unit = "°C",
        color = couiColors.secondary,
        segments = segments,
        windowStart = windowStart,
        windowMillis = windowMillis,
        axis = data.axis,
        onDrag = { dragAmount -> endFraction = (endFraction - dragAmount).coerceIn(0f, 1f) },
    )
}

@Composable
private fun ChartLoadingCard(title: String) {
    val couiColors = CouiPageDefaults.themeColors()
    CouiCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = CouiType.itemTitle(), color = couiColors.title, modifier = Modifier.weight(1f))
                Text("正在准备曲线", style = CouiType.dense, color = couiColors.summary)
            }
            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                Text("正在处理最近 8 小时数据…", style = CouiType.itemSummary(), color = couiColors.summary)
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    unit: String,
    color: Color,
    segments: List<CurveSegment>,
    windowStart: Long,
    windowMillis: Long,
    axis: CurveAxis,
    onDrag: (Float) -> Unit,
) {
    val couiColors = CouiPageDefaults.themeColors()
    val textMeasurer = rememberTextMeasurer()
    val currentOnDrag by rememberUpdatedState(onDrag)
    val tickCount = axis.tickCount
    val span = (axis.maximum - axis.minimum).coerceAtLeast(0.001f)
    val hasData = segments.any { it.points.isNotEmpty() }
    val axisLabels = remember(axis, unit) {
        val count = axis.tickCount
        val range = (axis.maximum - axis.minimum).coerceAtLeast(0.001f)
        List(count) { index ->
            val fraction = 1f - index / (count - 1).coerceAtLeast(1).toFloat()
            val value = axis.minimum + range * fraction
            if (unit == "W") {
                value.roundToInt().toString()
            } else {
                String.format(Locale.getDefault(), "%.1f", value)
            }
        }
    }
    // 刻度文字在绘制阶段才用到，但它只跟轴范围和配色有关，跟画布尺寸无关。
    // 放在 drawWithCache 里量，每次采样刷新都要重新排一次版；挪到这里 remember 住，
    // TextMeasurer 自己那点 LRU 也就不必来回驱逐了。
    val axisStyle = remember(couiColors.summary) {
        TextStyle(color = couiColors.summary, fontSize = 7.sp)
    }
    val measuredLabels = remember(axisLabels, axisStyle, textMeasurer) {
        axisLabels.map { label -> textMeasurer.measure(text = label, style = axisStyle) }
    }
    CouiCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = CouiType.itemTitle(), color = couiColors.title, modifier = Modifier.weight(1f))
                Text("2 小时视图 · 可拖动", style = CouiType.dense, color = couiColors.summary)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .padding(top = 6.dp)
                    .pointerInput(windowMillis) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            currentOnDrag(dragAmount / size.width.coerceAtLeast(1))
                        }
                    },
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 35.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)
                        .drawWithCache {
                            val right = size.width
                            val bottom = size.height
                            val strokes: List<Pair<Path?, Offset?>> = segments.mapNotNull { segment ->
                                val points = segment.points
                                if (points.isEmpty()) return@mapNotNull null
                                if (points.size == 1) {
                                    val point = points.first()
                                    val x = right * ((point.epochMillis - windowStart).toFloat() / windowMillis).coerceIn(0f, 1f)
                                    val y = bottom - bottom * ((point.value - axis.minimum) / span).coerceIn(0f, 1f)
                                    return@mapNotNull Pair(null, Offset(x, y))
                                }
                                val path = Path()
                                points.forEachIndexed { index, point ->
                                    val x = right * ((point.epochMillis - windowStart).toFloat() / windowMillis).coerceIn(0f, 1f)
                                    val y = bottom - bottom * ((point.value - axis.minimum) / span).coerceIn(0f, 1f)
                                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                Pair(path, null)
                            }
                            onDrawBehind {
                                val divisor = (tickCount - 1).coerceAtLeast(1).toFloat()
                                val lineGap = bottom / divisor
                                repeat(tickCount) { index ->
                                    val y = bottom * index / divisor
                                    drawLine(couiColors.divider, Offset(0f, y), Offset(right, y), 1f)
                                }
                                // 刻度字只有 7sp，线间距小于 1.4 倍字高时必然叠字。
                                // 按像素间距隔几条画一个，首尾两个一定保留——轴的两端是读数基准，
                                // 中间少几行没人看，叠成一团就没法看了。
                                val labelHeight = measuredLabels.firstOrNull()?.size?.height?.toFloat() ?: 1f
                                val stride = if (lineGap <= 0f) {
                                    1
                                } else {
                                    kotlin.math.ceil(labelHeight * 1.4f / lineGap).toInt().coerceAtLeast(1)
                                }
                                measuredLabels.forEachIndexed { index, measured ->
                                    if (stride > 1 && index % stride != 0 && index != measuredLabels.lastIndex) {
                                        return@forEachIndexed
                                    }
                                    val center = bottom * index / divisor
                                    // 首尾刻度往框内压半个字高，别贴着上下边线糊掉。
                                    val y = (center - measured.size.height / 2f)
                                        .coerceIn(0f, (bottom - measured.size.height).coerceAtLeast(0f))
                                    drawText(measured, topLeft = Offset(-measured.size.width - 5f, y))
                                }
                                strokes.forEach { (path, point) ->
                                    if (path != null) {
                                        drawPath(path, color, style = Stroke(3f))
                                    } else if (point != null) {
                                        drawCircle(color, radius = 3f, center = point)
                                    }
                                }
                            }
                        },
                    onDraw = {},
                )
                if (!hasData) {
                    Text("等待采样…", color = couiColors.summary, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
            TimelineLabels(windowStart, windowMillis)
        }
    }
}

@Composable
private fun TimelineLabels(windowStart: Long, windowMillis: Long) {
    val couiColors = CouiPageDefaults.themeColors()
    val labels = remember(windowStart, windowMillis) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        List(9) { index ->
            formatter.format(Date(windowStart + windowMillis * index / 8L))
        }
    }
    Row(
        // 左右留白与 Canvas 的绘图区对齐（start 35dp / end 12dp）：
        // 之前 Row 铺满卡片，最后一个时间标签会顶到卡片右缘外，看上去像溢出了。
        Modifier.fillMaxWidth().padding(start = 35.dp, end = 12.dp, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEach { label ->
            Text(label, color = couiColors.summary, fontSize = 7.sp, maxLines = 1, softWrap = false)
        }
    }
}
private val com.oppower.model.BatterySnapshot.statusLabel: String
    get() = when (status) {
        2 -> "充电中"
        3 -> "放电中"
        4 -> "未充电"
        5 -> "已充满"
        else -> "未知"
    }

internal val ControllerPhase.label: String
    get() = when (this) {
        ControllerPhase.DISABLED -> "未启用"
        ControllerPhase.WAITING_FOR_POWER -> "等待接电"
        ControllerPhase.ACTIVE -> "控制生效"
        ControllerPhase.TEMPERATURE_PAUSED -> "温度暂停"
        ControllerPhase.SOC_PAUSED -> "电量暂停"
        ControllerPhase.FULL_PAUSED -> "已充满"
        ControllerPhase.SCREEN_OFF_PAUSED -> "息屏暂停"
        ControllerPhase.ERROR -> "需要处理"
    }

/** COUI 主行动按钮：胶囊、强调色实底、44dp 高，与整页的超椭圆语言一致。 */
@Composable
internal fun CouiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CouiPageDefaults.themeColors()
    Box(
        modifier
            .height(44.dp)
            .clip(capsuleShape(44.dp))
            .background(colors.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = CouiType.itemTitle(),
            color = if (colors.isDark) Color(0xFF06241D) else Color.White,
            maxLines = 1,
        )
    }
}

/** 次级按钮：卡片底 + 细边，用于"查看详情"这类不改变状态的动作。 */
@Composable
internal fun CouiSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CouiPageDefaults.themeColors()
    Box(
        modifier
            .height(44.dp)
            .clip(capsuleShape(44.dp))
            .background(colors.card)
            .border(0.5.dp, colors.divider, capsuleShape(44.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CouiType.itemTitle(), color = colors.title, maxLines = 1)
    }
}
