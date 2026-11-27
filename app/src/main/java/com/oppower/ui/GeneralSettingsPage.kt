package com.oppower.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.itos.coui.slider.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.itos.coui.icons.CouiIcons
import com.oppower.BuildConfig
import com.oppower.CrashRecorder
import com.oppower.battery.ChargeStage
import com.oppower.battery.CellStructure
import com.oppower.battery.DeviceProfiles
import com.oppower.data.PreferencesStore
import com.oppower.model.NotificationStyle
import com.oppower.battery.ChargeInsight
import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerStatus
import com.oppower.model.RuntimeSettings
import com.oppower.model.SystemAccessState
import com.oppower.model.ThemeMode
import com.oppower.model.ThemePalette
import com.oppower.model.UiDensity
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group

internal enum class SettingsScreen {
    HOME,
    APPEARANCE,
    NOTIFICATIONS,
    CHARGE_DETECT,
    STABLE_OPERATION,
    ADVANCED_KEEP_ALIVE,
    HARDWARE,
    SYSTEM,
    DATA,
    FAQ,
    ABOUT,
    IMPORTANT_NOTICE,
    DEVELOPER_NOTICE,
    LICENSES,
}

/** 设置页内部的层级：主页 0、二级页 1、关于下的说明页 2。只参与转场方向判定。 */
private val SettingsScreen.depth: Int
    get() = when (this) {
        SettingsScreen.HOME -> 0
        SettingsScreen.IMPORTANT_NOTICE,
        SettingsScreen.DEVELOPER_NOTICE,
        SettingsScreen.LICENSES,
        -> 2
        else -> 1
    }

@Composable
internal fun SettingsRouter(
    screen: SettingsScreen,
    settings: State<RuntimeSettings>,
    shizuku: State<ShizukuAccess>,
    systemAccess: State<SystemAccessState>,
    insight: State<ChargeInsight>,
    controllerStatus: State<ControllerStatus>,
    battery: State<BatterySnapshot>,
    historyCounts: State<HistoryCounts>,
    thermalDiagnostics: State<String?>,
    thermalDiagnosticsLoading: State<Boolean>,
    onNavigate: (SettingsScreen) -> Unit,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onJoinQq: () -> Unit,
    onClearHistory: () -> Unit,
    onRequestShizuku: () -> Unit,
    onExportHistory: () -> Unit,
    onResetSettings: () -> Unit,
    onRunThermalDiagnostics: () -> Unit,
) {
    // 设置内部的层级切换单独走一次转场：进入二级页从右推入，返回主页从左侧回位。
    // 与顶层 PageTransition 是嵌套的两层，各自只在自己那一层变化时动画。
    PageTransition(
        target = screen,
        depthOf = { it.depth },
        label = "SettingsScreen",
        modifier = Modifier.fillMaxSize(),
    ) { current ->
        when (current) {
        SettingsScreen.HOME -> SettingsHomePage(
            settings = settings,
            shizuku = shizuku,
            systemAccess = systemAccess,
            insight = insight,
            onBack = onBack,
            onNavigate = onNavigate,
            onJoinQq = onJoinQq,
            onRequestShizuku = onRequestShizuku,
        )
        SettingsScreen.APPEARANCE -> AppearanceSettingsPage(settings, onBack, onSettingsChange)
        SettingsScreen.NOTIFICATIONS -> NotificationSettingsPage(settings, systemAccess, onBack, onSettingsChange)
        SettingsScreen.CHARGE_DETECT -> ChargeDetectSettingsPage(settings, insight, onBack, onSettingsChange)
        SettingsScreen.STABLE_OPERATION -> StableOperationPage(systemAccess, controllerStatus, onBack, onNavigate)
        SettingsScreen.ADVANCED_KEEP_ALIVE -> AdvancedKeepAlivePage(systemAccess, settings, onBack, onSettingsChange)
        SettingsScreen.HARDWARE -> HardwareSettingsPage(
            settingsState = settings,
            shizukuState = shizuku,
            batteryState = battery,
            thermalDiagnostics = thermalDiagnostics,
            thermalDiagnosticsLoading = thermalDiagnosticsLoading,
            onBack = onBack,
            onSettingsChange = onSettingsChange,
            onRunThermalDiagnostics = onRunThermalDiagnostics,
        )
        SettingsScreen.SYSTEM -> SystemSettingsPage(
            settingsState = settings,
            shizukuState = shizuku,
            systemAccessState = systemAccess,
            controllerStatus = controllerStatus,
            batteryState = battery,
            insightState = insight,
            onBack = onBack,
            onSettingsChange = onSettingsChange,
            onRequestShizuku = onRequestShizuku,
        )
        SettingsScreen.DATA -> DataManagementPage(
            settingsState = settings,
            historyCounts = historyCounts,
            onBack = onBack,
            onSettingsChange = onSettingsChange,
            onClearHistory = onClearHistory,
            onExportHistory = onExportHistory,
            onResetSettings = onResetSettings,
        )
        SettingsScreen.FAQ -> FaqPage(onBack)
        SettingsScreen.ABOUT -> AboutPage(onBack, onNavigate, onJoinQq)
        SettingsScreen.IMPORTANT_NOTICE -> NoticePage(
            title = "重要说明",
            onBack = onBack,
            paragraphs = listOf(
                "OPPower 的亮屏快充增强依赖系统控制权限。权限不可用时，应用会停止写入并恢复默认偏移。",
                "应用不会修改温度数据，也不会关闭系统原生温控。温度、电量和充满保护仍由本地规则执行。",
                "实际输入功率受充电器、线材、亮屏耗电、系统热策略和电池状态影响，界面功率来自系统电池数据。",
            ),
        )
        SettingsScreen.DEVELOPER_NOTICE -> NoticePage(
            title = "开发者说明",
            onBack = onBack,
            paragraphs = listOf(
                "OPPower 基于 LLPower 二改得来，并新增 ColorOS 亮屏快充控制。界面、数据模型和系统控制逻辑已独立实现。",
                "本项目仅适用于已经验证过的 ColorOS 策略。不同机型、固件和系统权限状态可能无法写入或回读。",
                "反馈问题时请提供机型、系统版本、当前策略状态和可复现步骤。不要在群内公开设备私密信息。",
            ),
        )
        SettingsScreen.LICENSES -> LicensesPage(onBack)
        }
    }
}

private data class SettingsEntry(
    val title: String,
    val summary: String,
    val screen: SettingsScreen,
    val keywords: String = "",
    /** 行首图标，全部取自 `CouiIcons`；null 时不占图标槽。 */
    val icon: ImageVector? = null,
    /** 图标色序号，对应 `CouiIconTint` 的六色板。 */
    val tint: Int = 0,
)

@Composable
private fun SettingsHomePage(
    settings: State<RuntimeSettings>,
    shizuku: State<ShizukuAccess>,
    systemAccess: State<SystemAccessState>,
    insight: State<ChargeInsight>,
    onBack: () -> Unit,
    onNavigate: (SettingsScreen) -> Unit,
    onJoinQq: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val couiColors = CouiPageDefaults.themeColors()
    var query by rememberSaveable { mutableStateOf("") }
    val currentSettings = settings.value
    val access = systemAccess.value
    val currentInsight = insight.value
    val stableReady = access.notificationsReady(usesLiveNotificationChannel()) &&
        access.batteryUnrestricted

    val entries = listOf(
        SettingsEntry("Shizuku 授权", shizukuStatus(shizuku.value), SettingsScreen.SYSTEM, "shizuku 权限 授权",
            CouiIcons.Filled.ShieldCheck, 0),
        SettingsEntry(
            "外观设置",
            "${currentSettings.themeMode.label} · ${currentSettings.themePalette.label} · ${currentSettings.uiDensity.label}",
            SettingsScreen.APPEARANCE,
            "主题 配色 字体 圆角 密度 外观",
            icon = CouiIcons.Filled.Sun,
            tint = 1,
        ),
        SettingsEntry(
            "充电检测",
            when {
                !currentSettings.chargeDetectEnabled -> "已关闭"
                currentInsight.capability != null ->
                    "${currentInsight.stage.label} · ${currentInsight.capability.maxPowerW.toInt()} W 充电器"
                else -> "识别充电器、效率与异常"
            },
            SettingsScreen.CHARGE_DETECT,
            "充电 检测 协议 效率 健康 异常",
            icon = CouiIcons.Filled.Health,
            tint = 2,
        ),
        SettingsEntry(
            "通知设置",
            when {
                !access.notificationsReady(usesLiveNotificationChannel()) -> "系统通知未允许"
                currentSettings.liveNotificationEnabled ->
                    "状态通知已开启 · ${currentSettings.notificationRefreshSeconds} 秒刷新"
                else -> "状态通知已关闭"
            },
            SettingsScreen.NOTIFICATIONS,
            "通知 刷新 实时活动 精简",
            icon = CouiIcons.Filled.Bell,
            tint = 3,
        ),
        SettingsEntry(
            "稳定运行",
            if (stableReady) "后台运行权限已就绪" else "建议完成运行向导",
            SettingsScreen.STABLE_OPERATION,
            "后台 保活 电池优化 权限",
            icon = CouiIcons.Filled.Fan,
            tint = 4,
        ),
        SettingsEntry("硬件修正", hardwareSummary(currentSettings), SettingsScreen.HARDWARE, "电流 电压 双芯 修正",
            CouiIcons.Filled.GridSliders, 5),
        SettingsEntry(
            "系统与诊断",
            if (currentSettings.debugMode) "诊断模式已开启" else "后台权限、应用详情与调试",
            SettingsScreen.SYSTEM,
            "诊断 调试 系统 应用详情",
            icon = CouiIcons.Filled.SettingDetail,
            tint = 6,
        ),
        SettingsEntry("数据管理", "24 小时原始记录 · 8 小时曲线", SettingsScreen.DATA, "数据 导出 清除 存储",
            CouiIcons.Filled.CloudData, 0),
        SettingsEntry("常见问题", "功率、后台运行与读数说明", SettingsScreen.FAQ, "faq 帮助 问题",
            CouiIcons.Filled.BookOpen, 2),
        SettingsEntry("关于 OPPower", "版本 ${BuildConfig.VERSION_NAME} · 更新与许可", SettingsScreen.ABOUT, "关于 版本 许可",
            CouiIcons.Filled.Info, 1),
    )
    val normalized = query.trim()
    val filtered = if (normalized.isEmpty()) {
        entries
    } else {
        entries.filter { entry ->
            entry.title.contains(normalized, ignoreCase = true) ||
                entry.summary.contains(normalized, ignoreCase = true) ||
                entry.keywords.contains(normalized, ignoreCase = true)
        }
    }

    CouiPage(
        title = "设置",
        subtitle = "OPPower · 外观、检测、通知与数据",
        onBack = onBack,
        largeTitle = true,
        // 搜索框**不再**塞进 `CollapsingTopAppBar` 的 search 槽。
        //
        // 那条分支是唯一一条没被长期验证过的路径：内部 `TopAppBarSearch` 有
        // `checkNotNull(state.renderer)`、`checkNotNull(expandedCenter)`、
        // `checkNotNull(LocalCouiToolbarOverlay.current)` 等若干处依赖测量时序的断言，
        // 命中任意一个就是组合期 `IllegalArgumentException` —— 表现为「点进设置直接
        // 闪退」，且只在特定机型 / 特定帧率下偶发。大标题本身（无搜索槽分支）在
        // 关于页已经跑了十几个版本，是稳的。
        //
        // 搜索框放到正文顶部：行为完全一致，视觉上也就是比原来低了一个标题高度。
    ) {
        CouiSearchField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (normalized.isNotEmpty() && filtered.isEmpty()) {
            Group(title = "搜索结果", footer = "换个关键词试试，例如“通知”“检测”“权限”。") {
                CouiRow(title = "没有匹配的设置", summary = normalized)
            }
            return@CouiPage
        }
        // OPPO 设置的家常做法：不加组标题，用不带标题的连续卡片+留白分组，行首带彩色图标。
        val grouped = if (normalized.isEmpty()) {
            filtered.chunked(4)
        } else {
            listOf(filtered)
        }
        grouped.forEach { chunk ->
            Group {
                chunk.forEachIndexed { rowIndex, entry ->
                    if (rowIndex > 0) Divider()
                    CouiRow(
                        title = entry.title,
                        summary = entry.summary,
                        icon = entry.icon?.let { vector ->
                            { CouiTintedIcon(vector, CouiIconTint.at(entry.tint)) }
                        },
                        chevron = true,
                        onClick = { onNavigate(entry.screen) },
                    )
                }
            }
        }

        Group(title = "用户群") {
            CouiRow(
                title = "加入 OPPower 用户群",
                summary = "群号 $QQ_GROUP_NUMBER · 点击复制并打开 QQ",
                value = "›",
                valueColor = couiColors.accent,
                onClick = onJoinQq,
            )
        }
        Text(
            "所有可见设置入口均已接入功能；未实现的项目不会显示为空入口。",
            style = CouiType.groupFooter(),
            color = couiColors.summary.copy(alpha = 0.86f),
            modifier = Modifier.padding(start = 32.dp, top = 10.dp),
        )
    }
}

@Composable
private fun AppearanceSettingsPage(
    settingsState: State<RuntimeSettings>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val couiColors = CouiPageDefaults.themeColors()
    val settings by settingsState
    // 扫字体目录是 IO，放在 remember 里做一次，别跟着每次重组重扫。
    val fontFaces = remember { OppoFont.describe() }
    CouiPage(title = "外观设置", subtitle = "主题、密度与界面显示", onBack = onBack) {
        Group(title = "主题模式") {
            CouiChoiceRow(
                title = "深浅模式",
                labels = enumValues<ThemeMode>().map { it.label },
                selectedIndex = settings.themeMode.ordinal,
                onSelected = { index ->
                    onSettingsChange { it.copy(themeMode = enumValues<ThemeMode>()[index]) }
                },
            )
        }
        Group(title = "主题配色") {
            CouiChoiceRow(
                title = "强调色",
                labels = enumValues<ThemePalette>().take(3).map { it.label },
                selectedIndex = settings.themePalette.ordinal.coerceIn(0, 2),
                onSelected = { index ->
                    onSettingsChange { it.copy(themePalette = enumValues<ThemePalette>()[index]) }
                },
            )
            Divider()
            CouiChoiceRow(
                title = "更多强调色",
                labels = enumValues<ThemePalette>().drop(3).map { it.label },
                selectedIndex = (settings.themePalette.ordinal - 3).coerceIn(0, 2),
                onSelected = { index ->
                    onSettingsChange { it.copy(themePalette = enumValues<ThemePalette>()[index + 3]) }
                },
            )
        }
        Group(title = "界面比例") {
            CouiChoiceRow(
                title = "字体大小",
                labels = listOf("较小", "标准", "较大"),
                selectedIndex = listOf(0.9f, 1f, 1.1f).indexOfFirst { it == settings.fontScale }
                    .coerceAtLeast(1),
                onSelected = { index ->
                    onSettingsChange { it.copy(fontScale = listOf(0.9f, 1f, 1.1f)[index]) }
                },
            )
            Divider()
            CouiChoiceRow(
                title = "界面比例",
                summary = "COUI 组件行高固定 48dp，这里整体缩放界面与字号",
                labels = listOf("紧凑", "标准", "宽松"),
                selectedIndex = listOf(0.9f, 1f, 1.1f).indexOfFirst { it == settings.uiScale }
                    .coerceAtLeast(1),
                onSelected = { index ->
                    onSettingsChange { it.copy(uiScale = listOf(0.9f, 1f, 1.1f)[index]) }
                },
            )
        }
        Group(title = "卡片与后台") {
            Column(Modifier.padding(horizontal = CouiPageDefaults.rowPadding, vertical = CouiPageDefaults.contentPadding)) {
                Text("卡片不透明度", style = CouiType.itemTitle(), color = couiColors.title)
                Text(
                    "${(settings.cardOpacity * 100).roundToInt()}%",
                    style = CouiType.itemValue(),
                    color = couiColors.accent,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Slider(
                    value = settings.cardOpacity,
                    onValueChange = { value -> onSettingsChange { it.copy(cardOpacity = value) } },
                    valueRange = 0.65f..1f,
                    steps = 6,
                )
            }
            Divider()
            CouiSwitchRow(
                title = "隐藏后台任务",
                summary = "从最近任务和预览中隐藏，不影响常驻快充服务",
                checked = settings.hideFromRecents,
                onCheckedChange = { value -> onSettingsChange { it.copy(hideFromRecents = value) } },
            )
        }
        Group(
            title = "界面字体",
            footer = "OPPO Sans 直接从 /system/fonts 读取，不打包进 APK——" +
                "一个字重的中文字形约 9 MB，打包会让安装包大十倍。" +
                "读不到时自动回落到系统默认字体，不影响任何功能。\n当前字重：$fontFaces",
        ) {
            CouiRow(
                title = "正文字体",
                summary = "与 ColorOS 设置页同一份字形",
                value = if (OppoFont.available) "OPPO Sans" else "系统默认",
                valueColor = if (OppoFont.available) couiColors.accent else couiColors.summary,
            )
        }
    }
}

@Composable
private fun ChargeDetectSettingsPage(
    settingsState: State<RuntimeSettings>,
    insightState: State<ChargeInsight>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val couiColors = CouiPageDefaults.themeColors()
    val settings by settingsState
    val insightValue = insightState.value
    CouiPage(title = "充电检测", subtitle = "识别充电器能力、充电阶段与异常", onBack = onBack) {
        Group(
            title = "检测",
            footer = "检测只读取 /sys/class/power_supply 快照，不写入任何设置，也不影响快充控制。",
        ) {
            CouiSwitchRow(
                title = "充电检测卡片",
                summary = "在主页面显示充电器协议、效率与稳定性",
                checked = settings.chargeDetectEnabled,
                onCheckedChange = { value -> onSettingsChange { it.copy(chargeDetectEnabled = value) } },
            )
            if (settings.chargeDetectEnabled) {
                Divider()
                CouiChoiceRow(
                    title = "检测间隔",
                    summary = "间隔越短越灵敏，Shizuku 读取次数也越多",
                    labels = listOf("3 秒", "5 秒", "10 秒", "20 秒", "30 秒"),
                    selectedIndex = listOf(3, 5, 10, 20, 30)
                        .indexOfFirst { it == settings.detectionIntervalSeconds }
                        .coerceAtLeast(2),
                    onSelected = { index ->
                        onSettingsChange {
                            it.copy(detectionIntervalSeconds = listOf(3, 5, 10, 20, 30)[index])
                        }
                    },
                )
            }
        }
        Group(title = "提醒") {
            CouiSwitchRow(
                title = "异常提醒",
                summary = "电流为零、功率波动或温度偏高时发一条通知",
                checked = settings.anomalyAlertEnabled,
                onCheckedChange = { value -> onSettingsChange { it.copy(anomalyAlertEnabled = value) } },
            )
            Divider()
            CouiSwitchRow(
                title = "充满提醒",
                summary = "电量达到 100% 时提醒拔电，便于保护电池",
                checked = settings.fullChargeAlertEnabled,
                onCheckedChange = { value -> onSettingsChange { it.copy(fullChargeAlertEnabled = value) } },
            )
        }
        Group(title = "显示") {
            CouiSwitchRow(
                title = "显示电池健康",
                summary = "由满充容量与设计容量估算，仅供参考",
                checked = settings.showBatteryHealth,
                onCheckedChange = { value -> onSettingsChange { it.copy(showBatteryHealth = value) } },
            )
        }
        Group(title = "本次检测") {
            CouiRow(
                title = "充电阶段",
                summary = insightValue.stageDetail.ifBlank { "接电后自动判断" },
                value = insightValue.stage.label,
                valueColor = couiColors.accent,
            )
            Divider()
            CouiRow(
                title = "充电器",
                summary = insightValue.capability?.let { "${it.supplyName} · ${it.supplyType}" } ?: "未识别到供电节点",
                value = insightValue.capability?.let { "${it.maxPowerW.toInt()} W" } ?: "—",
            )
            Divider()
            CouiRow(
                title = "利用率",
                summary = "实际输入功率 / 充电器声称能力",
                value = insightValue.utilizationPercent?.let { "$it%" } ?: "—",
            )
            Divider()
            CouiRow(
                title = "稳定性",
                summary = stabilityLabel(insightValue.stabilityPercent),
                value = "${insightValue.stabilityPercent}%",
            )
            if (insightValue.health != null) {
                Divider()
                CouiRow(
                    title = "电池健康",
                    summary = insightValue.health.let { "${it.fullMah} / ${it.designMah} mAh" },
                    value = insightValue.health.percent?.let { "$it%" } ?: "—",
                )
                insightValue.health.cycleCount?.let { cycles ->
                    Divider()
                    CouiRow(title = "循环次数", value = "$cycles 次")
                }
            }
        }
        if (insightValue.anomalies.isNotEmpty()) {
            Group(title = "当前异常", footer = "异常只用于提示，不会自动改变充电策略。") {
                insightValue.anomalies.forEachIndexed { index, text ->
                    if (index > 0) Divider()
                    CouiRow(title = text, value = "!", valueColor = couiColors.secondary)
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsPage(
    settingsState: State<RuntimeSettings>,
    systemAccessState: State<SystemAccessState>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val couiColors = CouiPageDefaults.themeColors()
    val context = LocalContext.current
    val settings by settingsState
    val access = systemAccessState.value
    CouiPage(title = "通知设置", subtitle = "常驻状态通知与刷新频率", onBack = onBack) {
        Group(title = "通知") {
            CouiSwitchRow(
                title = "状态通知",
                summary = "显示快充状态、功率和电池数据；关闭后保留精简后台通知",
                checked = settings.liveNotificationEnabled,
                onCheckedChange = { value -> onSettingsChange { it.copy(liveNotificationEnabled = value) } },
            )
            Divider()
            CouiRow(
                title = "系统通知权限",
                value = if (access.notificationsAllowed) "已允许" else "未允许",
                valueColor = if (access.notificationsAllowed) couiColors.accent else couiColors.secondary,
                chevron = true,
                onClick = { openNotificationSettings(context) },
            )
            Divider()
            CouiRow(
                title = if (usesLiveNotificationChannel()) "实时活动通知渠道" else "状态通知渠道",
                value = when {
                    usesLiveNotificationChannel() && !access.liveNotificationChannelEnabled -> "已关闭"
                    !usesLiveNotificationChannel() && !access.statusNotificationChannelEnabled -> "已关闭"
                    else -> "已开启"
                },
                valueColor = couiColors.accent,
                chevron = true,
                onClick = { openNotificationSettings(context) },
            )
            if (Build.VERSION.SDK_INT >= 36) {
                Divider()
                CouiRow(
                    title = "流体云实时活动",
                    summary = "需要在系统通知页开启显示权限",
                    chevron = true,
                    onClick = { openNotificationSettings(context) },
                )
            }
        }
        Group(title = "通知样式", footer = "精简只显示功率与电量；详细会带充电阶段与充电器信息。") {
            CouiChoiceRow(
                title = "内容层级",
                labels = enumValues<NotificationStyle>().map { it.label },
                selectedIndex = settings.notificationStyle.ordinal,
                onSelected = { index ->
                    onSettingsChange { it.copy(notificationStyle = enumValues<NotificationStyle>()[index]) }
                },
            )
        }
        Group(title = "通知内容") {
            CouiSwitchRow("显示电量", "百分比", settings.notificationShowBatteryPercent) { value ->
                onSettingsChange { it.copy(notificationShowBatteryPercent = value) }
            }
            Divider()
            CouiSwitchRow("显示电流", "单位 mA", settings.notificationShowCurrent) { value ->
                onSettingsChange { it.copy(notificationShowCurrent = value) }
            }
            Divider()
            CouiSwitchRow("显示电压", "单位 V", settings.notificationShowVoltage) { value ->
                onSettingsChange { it.copy(notificationShowVoltage = value) }
            }
            Divider()
            CouiSwitchRow("显示温度", "", settings.notificationShowTemperature) { value ->
                onSettingsChange { it.copy(notificationShowTemperature = value) }
            }
            Divider()
            CouiSwitchRow("功率只显示整数", "小于 1 W 时仍保留小数", settings.notificationIntegerPower) { value ->
                onSettingsChange { it.copy(notificationIntegerPower = value) }
            }
        }
        Group(title = "刷新与自动精简") {
            CouiChoiceRow(
                title = "刷新间隔",
                labels = listOf("2 秒", "5 秒", "10 秒", "30 秒"),
                selectedIndex = listOf(2, 5, 10, 30)
                    .indexOfFirst { it == settings.notificationRefreshSeconds }
                    .coerceAtLeast(1),
                onSelected = { index ->
                    onSettingsChange { it.copy(notificationRefreshSeconds = listOf(2, 5, 10, 30)[index]) }
                },
            )
            Divider()
            CouiChoiceRow(
                title = "熄屏刷新间隔",
                labels = listOf("3 秒", "30 秒", "1 分钟", "5 分钟"),
                selectedIndex = listOf(3, 30, 60, 300)
                    .indexOfFirst { it == settings.notificationScreenOffRefreshSeconds }
                    .coerceAtLeast(1),
                onSelected = { index ->
                    onSettingsChange {
                        it.copy(notificationScreenOffRefreshSeconds = listOf(3, 30, 60, 300)[index])
                    }
                },
            )
            Divider()
            CouiSwitchRow(
                title = "充满后精简状态通知",
                summary = "电量 100% 时恢复系统充电策略，并保留一条精简后台通知",
                checked = settings.notificationAutoCloseOnFull,
                onCheckedChange = { value -> onSettingsChange { it.copy(notificationAutoCloseOnFull = value) } },
            )
            Divider()
            CouiSwitchRow(
                title = "熄屏后精简状态通知",
                summary = "熄屏时只保留必要后台通知，亮屏后恢复详细数据",
                checked = settings.notificationAutoCloseOnScreenOff,
                onCheckedChange = { value -> onSettingsChange { it.copy(notificationAutoCloseOnScreenOff = value) } },
            )
        }
    }
}

@Composable
private fun StableOperationPage(
    systemAccessState: State<SystemAccessState>,
    controllerStatus: State<ControllerStatus>,
    onBack: () -> Unit,
    onNavigate: (SettingsScreen) -> Unit,
) {
    val context = LocalContext.current
    val access = systemAccessState.value
    val status = controllerStatus.value
    val notificationOk = access.notificationsReady(usesLiveNotificationChannel())
    val batteryOk = access.batteryUnrestricted
    val overlayOk = access.overlayAllowed
    val accessibilityOk = access.accessibilityEnabled
    CouiPage(title = "稳定运行", subtitle = "保证亮屏快充与记录服务持续工作", onBack = onBack) {
        Group(title = "运行检查") {
            CouiStatusRow("通知权限", if (notificationOk) "已就绪" else "需要授权", notificationOk)
            Divider()
            CouiStatusRow("电池优化", if (batteryOk) "不受限制" else "可能限制后台", batteryOk)
            Divider()
            CouiStatusRow("悬浮窗保活", if (overlayOk) "权限已授予" else "未授权", overlayOk, warn = true)
            Divider()
            CouiStatusRow("无障碍保活", if (accessibilityOk) "已启用" else "未启用", accessibilityOk, warn = true)
        }
        Group(title = "权限入口") {
            CouiRow(
                title = "通知设置",
                value = if (notificationOk) "已允许" else "前往设置",
                chevron = true,
                onClick = { openNotificationSettings(context) },
            )
            Divider()
            CouiRow(
                title = "电池优化",
                value = if (batteryOk) "不受限制" else "前往设置",
                chevron = true,
                onClick = { openBatteryOptimizationSettings(context) },
            )
            Divider()
            CouiRow(
                title = "应用详情",
                value = "耗电与自启动",
                chevron = true,
                onClick = { openAppDetails(context) },
            )
            Divider()
            CouiRow(
                title = "高级保活",
                value = "悬浮窗与无障碍",
                chevron = true,
                onClick = { onNavigate(SettingsScreen.ADVANCED_KEEP_ALIVE) },
            )
        }
        Group(
            title = "重要说明",
            footer = "ColorOS 可能在温度较高或长时间亮屏时限制后台服务。建议将 OPPower 加入电池优化白名单，" +
                "并在多任务界面锁定。特殊情况再启用高级保活，日常无需全开。",
        ) {
            CouiRow(
                title = "查看完整说明",
                value = "重要说明",
                chevron = true,
                onClick = { onNavigate(SettingsScreen.IMPORTANT_NOTICE) },
            )
        }
        Group(title = "当前控制服务") {
            CouiStatusRow(
                "控制状态",
                status.phase.label,
                status.phase == com.oppower.model.ControllerPhase.ACTIVE,
            )
            Divider()
            CouiRow(title = "系统返回", summary = status.message)
        }
    }
}

@Composable
private fun AdvancedKeepAlivePage(
    systemAccessState: State<SystemAccessState>,
    settingsState: State<RuntimeSettings>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val context = LocalContext.current
    val access = systemAccessState.value
    val settings = settingsState.value
    val overlayGranted = access.overlayAllowed
    val accessibilityEnabled = access.accessibilityEnabled
    CouiPage(title = "高级保活", subtitle = "非特殊情况无需开启", onBack = onBack) {
        Group(
            title = "悬浮窗保活",
            footer = "透明悬浮窗尺寸仅 1×1 像素，不显示内容，也不会拦截触摸。",
        ) {
            CouiSwitchRow(
                title = "挂载 1×1 透明悬浮窗",
                summary = "开启后持续帮助控制服务恢复；需要悬浮窗权限",
                checked = settings.overlayKeepAliveEnabled && overlayGranted,
                onCheckedChange = { enabled ->
                    if (enabled && !overlayGranted) {
                        onSettingsChange { it.copy(overlayKeepAliveEnabled = true) }
                        openOverlayPermission(context)
                    } else {
                        onSettingsChange { it.copy(overlayKeepAliveEnabled = enabled) }
                    }
                },
            )
            Divider()
            CouiRow(
                "悬浮窗权限",
                value = if (overlayGranted) "已授权" else "前往授权",
                chevron = true,
                onClick = { openOverlayPermission(context) },
            )
        }
        Group(
            title = "无障碍保活",
            footer = "无障碍服务只用于维持后台监控，不读取屏幕内容、不执行自动点击，也不保存任何辅助功能数据。",
        ) {
            CouiStatusRow("服务状态", if (accessibilityEnabled) "已启用" else "未启用", accessibilityEnabled)
            Divider()
            CouiRow(
                title = "打开无障碍设置",
                value = "OPPower 保活服务",
                chevron = true,
                onClick = { openAccessibilitySettings(context) },
            )
        }
    }
}

@Composable
private fun HardwareSettingsPage(
    settingsState: State<RuntimeSettings>,
    shizukuState: State<ShizukuAccess>,
    batteryState: State<BatterySnapshot>,
    thermalDiagnostics: State<String?>,
    thermalDiagnosticsLoading: State<Boolean>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRunThermalDiagnostics: () -> Unit,
) {
    val settings by settingsState
    val access = shizukuState.value
    val battery = batteryState.value
    val diagnostics = thermalDiagnostics.value
    val diagnosticsLoading = thermalDiagnosticsLoading.value
    val couiColors = CouiPageDefaults.themeColors()
    val context = LocalContext.current
    // 探测结果有进程内缓存，命中后只是一次 volatile 读；不 remember 是为了
    // 让 Shizuku 精修之后（`DeviceProfiles.refine`）下次进页面能看到新结论。
    val profile = DeviceProfiles.get(context)
    val preferences = remember(context) { PreferencesStore(context) }
    CouiPage(title = "硬件修正", subtitle = "兼容不同设备的电池读数", onBack = onBack) {
        Group(title = "电流与功率") {
            CouiSwitchRow(
                title = "反转电流正负",
                summary = "如果充电时电流显示为负，请开启此项",
                checked = settings.invertCurrentSign,
                onCheckedChange = { value -> onSettingsChange { it.copy(invertCurrentSign = value) } },
            )
            Divider()
            CouiSwitchRow(
                title = "电流和功率显示绝对值",
                summary = "只改变界面显示，不影响曲线原始方向",
                checked = settings.absoluteCurrentPowerDisplay,
                onCheckedChange = { value -> onSettingsChange { it.copy(absoluteCurrentPowerDisplay = value) } },
            )
        }
        Group(title = "电池结构") {
            CouiSwitchRow(
                title = "双芯电池修正",
                summary = "双电芯机型开启后，电流与功率按两芯合并显示",
                checked = settings.doubleCellCorrection,
                onCheckedChange = { value ->
                    // 记下“这是用户自己定的”，启动后的特权探测不再覆盖。
                    preferences.markDoubleCellUserSet()
                    onSettingsChange { it.copy(doubleCellCorrection = value) }
                },
            )
            Divider()
            CouiSwitchRow(
                title = "虚拟电压",
                summary = "设备无法读取电压时，使用 3.85 V 估算值",
                checked = settings.virtualVoltage,
                onCheckedChange = { value -> onSettingsChange { it.copy(virtualVoltage = value) } },
            )
        }
        Group(
            title = "当前读数",
            footer = when {
                profile.isDualCell && settings.doubleCellCorrection ->
                    "本机探测为双电芯（依据：${profile.detection.label}），电流与功率已按两芯合并显示。"
                profile.isDualCell ->
                    "本机探测为双电芯（依据：${profile.detection.label}），建议开启双芯修正，否则功率只显示一半。"
                profile.cellStructure == CellStructure.SINGLE ->
                    "本机探测为单电芯（依据：${profile.detection.label}），无需开启双芯修正。若读数方向不正确，先尝试“反转电流正负”。"
                else ->
                    "未识别到电池结构。双电芯机型请手动开启双芯修正；单电芯请保持关闭，否则功率会翻倍。"
            },
        ) {
            CouiRow(
                "电流",
                value = if (battery.sampleValid) "${battery.currentMa} mA" else "—",
            )
            Divider()
            CouiRow(
                "电压",
                value = if (battery.sampleValid) "${(battery.voltageMv / 1000f).oneDecimal()} V" else "—",
            )
            Divider()
            CouiRow(
                "电池功率",
                value = if (battery.sampleValid) "${battery.powerW.oneDecimal()} W" else "—",
            )
        }
        ThermalDiagnosticsGroup(
            raw = diagnostics,
            loading = diagnosticsLoading,
            canRun = access.permission && access.available,
            accentColor = couiColors.accent,
            summaryColor = couiColors.summary,
            onRun = onRunThermalDiagnostics,
        )
    }
}

/**
 * 热控诊断。
 *
 * 这一组存在的理由很具体：想知道“能不能不 root 就让系统重算一次热控策略”，
 * 得先确认厂商的 `persist.sys.horae.enable` 开关在本机确实存在、horae 服务
 * 也确实活着。这两条都不成立时，任何“重启热控”的按钮都是装饰品。
 * 所以这里只负责把原始结果如实摊开，不替用户下结论。
 */
@Composable
private fun ThermalDiagnosticsGroup(
    raw: String?,
    loading: Boolean,
    canRun: Boolean,
    accentColor: Color,
    summaryColor: Color,
    onRun: () -> Unit,
) {
    val rows = remember(raw) { parseThermalDiagnostics(raw.orEmpty()) }
    val summary = remember(rows) { rows.filter { !it.isParameter } }
    val parameters = remember(rows) { rows.filter { it.isParameter } }
    Group(
        title = "热控诊断",
        footer = "只读取属性、进程状态和内核参数，不写入任何值。" +
            "身份显示 0 表示特权进程是 root（Shizuku 以 root 启动），2000 才是普通 shell。",
    ) {
        CouiRow(
            title = "读取诊断快照",
            summary = when {
                loading -> "正在读取…"
                !canRun -> "需要 Shizuku 授权后可用"
                raw == null -> "点击读取当前热控状态"
                else -> "再次读取会覆盖本次结果"
            },
            value = if (loading) "读取中" else "读取",
            valueColor = if (canRun) accentColor else summaryColor,
            enabled = canRun && !loading,
            chevron = canRun && !loading,
            onClick = if (canRun && !loading) onRun else null,
        )
        summary.forEach { row ->
            Divider()
            CouiRow(
                title = row.label,
                value = row.value,
                valueColor = if (row.readable) accentColor else summaryColor,
            )
        }
    }
    if (parameters.isNotEmpty()) {
        Group(
            title = "充电模块参数",
            footer = "来自 /sys/module/oplus_chg_v2/parameters。参数存在不等于可写——" +
                "SELinux 仍会单独判定，这里只反映文件本身读到了什么。",
        ) {
            parameters.forEachIndexed { index, row ->
                if (index > 0) Divider()
                CouiRow(
                    title = row.label,
                    value = row.value,
                    valueColor = if (row.readable) accentColor else summaryColor,
                )
            }
        }
    }
}

/**
 * 把服务端的多行 `[tag] key=value` 解析成展示行。
 * 值里出现尖括号占位（`<不存在>` / `<不可读>`）时标为“非正常”，
 * 由调用方决定用次要色还是强调色显示。
 */
/** 一行诊断结果。[readable] 为 false 表示读不到（占位符或空值），用次要色呈现。 */
internal data class ThermalDiagnosticRow(
    val label: String,
    val value: String,
    val readable: Boolean,
    /** 模块参数清单很长，UI 会把它们单独成组，不和状态摘要混在一起。 */
    val isParameter: Boolean = false,
)

internal fun parseThermalDiagnostics(raw: String): List<ThermalDiagnosticRow> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("[") && '=' in it }
        .mapNotNull { line ->
            val tag = line.substringAfter('[').substringBefore(']').trim()
            val body = line.substringAfter("] ").trim()
            val key = body.substringBefore('=').trim()
            val value = body.substringAfter('=', "").trim()
            if (key.isEmpty()) return@mapNotNull null
            val label = when (tag) {
                "prop" -> "属性 $key"
                "proc" -> "进程 $key"
                "node" -> "节点 $key"
                "bin" -> "文件 $key"
                "uid" -> "身份 $key"
                "params" -> "参数总数"
                "param" -> key
                else -> "$tag $key"
            }
            val abnormal = value.isEmpty() || (value.startsWith('<') && value.endsWith('>'))
            ThermalDiagnosticRow(
                label = label,
                value = value.ifEmpty { "—" },
                readable = !abnormal,
                isParameter = tag == "param",
            )
        }
        .toList()
}

@Composable
private fun SystemSettingsPage(
    settingsState: State<RuntimeSettings>,
    shizukuState: State<ShizukuAccess>,
    systemAccessState: State<SystemAccessState>,
    controllerStatus: State<ControllerStatus>,
    batteryState: State<BatterySnapshot>,
    insightState: State<ChargeInsight>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val couiColors = CouiPageDefaults.themeColors()
    val context = LocalContext.current
    val settings by settingsState
    val access = systemAccessState.value
    val status = controllerStatus.value
    val battery = batteryState.value
    val insightValue = insightState.value
    val batteryOk = access.batteryUnrestricted
    val notificationOk = access.notificationsReady(usesLiveNotificationChannel())
    val profile = DeviceProfiles.get(context)
    CouiPage(title = "系统与诊断", subtitle = "后台权限与数据来源", onBack = onBack) {
        Group(
            title = "后台运行",
            footer = "OPPower 只通过 Shizuku 读取和调整已确认的 ColorOS 充电项目；授权失败时会停止写入并恢复系统默认。",
        ) {
            CouiRow(
                title = "系统通知权限",
                value = if (notificationOk) "已允许" else "未允许",
                valueColor = if (notificationOk) couiColors.accent else couiColors.secondary,
                chevron = true,
                onClick = { openNotificationSettings(context) },
            )
            Divider()
            CouiRow(
                title = "电池优化",
                value = if (batteryOk) "不受限制" else "前往设置",
                chevron = true,
                onClick = { openBatteryOptimizationSettings(context) },
            )
            Divider()
            CouiRow("应用详情", value = "耗电管理与自启动", chevron = true) { openAppDetails(context) }
            Divider()
            CouiRow("Shizuku 授权", value = shizukuStatus(shizukuState.value), chevron = true, onClick = onRequestShizuku)
        }
        Group(title = "诊断") {
            CouiSwitchRow(
                title = "调试模式",
                summary = "显示运行状态、充电阶段与权限状态",
                checked = settings.debugMode,
                onCheckedChange = { value -> onSettingsChange { it.copy(debugMode = value) } },
            )
            if (settings.debugMode) {
                Divider()
                CouiRow("当前状态", value = status.phase.label)
                Divider()
                CouiRow(
                    "功率档",
                    value = status.desiredGear?.let(::friendlyPowerGear) ?: "等待启用",
                )
                Divider()
                CouiRow("充电状态", value = if (battery.isCharging) "正在充电" else "未在充电")
                Divider()
                CouiRow("电源接入", value = if (battery.acOnline) "是" else "否")
                Divider()
                CouiRow("外部控制权限", value = if (shizukuState.value.permission) "已授权" else "未授权")
                Divider()
                CouiRow("充电阶段", value = insightValue.stage.label)
                Divider()
                CouiRow(
                    "充电器协议",
                    value = insightValue.capability?.protocolLabel ?: "未识别",
                )
            }
        }
        Group(title = "应用") {
            CouiRow("版本", value = BuildConfig.VERSION_NAME)
            Divider()
            CouiRow("设备", value = profile.displayName)
            Divider()
            CouiRow("电池结构", value = profile.cellLabel)
            Divider()
            CouiRow("网络权限", value = "未申请，数据不上传")
        }
    }
}

@Composable
private fun DataManagementPage(
    settingsState: State<RuntimeSettings>,
    historyCounts: State<HistoryCounts>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onClearHistory: () -> Unit,
    onExportHistory: () -> Unit,
    onResetSettings: () -> Unit,
) {
    val settings by settingsState
    val couiColors = CouiPageDefaults.themeColors()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // 上一次未捕获异常。只在真崩过之后才有内容，所以平时这一组不出现。
    var crashRecord by remember { mutableStateOf(CrashRecorder.read(context)) }
    val counts = historyCounts.value
    // 存储占用要遍历 filesDir 和 24h 数据库，不能放在组合阶段：采样点每秒都在变，
    // `remember(counts.points)` 会退化成每秒一次主线程目录遍历，低端机上直接卡住首帧。
    val storageBytes by produceState(initialValue = 0L, counts) {
        value = withContext(Dispatchers.IO) { measureLocalStorage(context) }
    }
    CouiPage(title = "数据管理", subtitle = "曲线、充放电记录与本地存储", onBack = onBack) {
        Group(
            title = "存储占用",
            footer = "原始曲线记录最多保存 24 小时。充电亮屏每 3 秒、充电熄屏每 5 秒、未充电每 180 秒采样，" +
                "图表显示最近 8 小时。所有数据只保存在本机。",
        ) {
            CouiRow("本地数据", value = formatBytes(storageBytes))
            Divider()
            CouiRow("曲线采样点", value = "${counts.points} 个")
            Divider()
            CouiRow("充电记录", value = "${counts.chargeRecords} 条")
            Divider()
            CouiRow("放电记录", value = "${counts.dischargeRecords} 条")
        }
        Group(title = "历史曲线") {
            CouiSwitchRow(
                title = "记录实时曲线",
                summary = "关闭后停止新增采样，并移除现有曲线数据",
                checked = settings.historyEnabled,
                onCheckedChange = { value -> onSettingsChange { it.copy(historyEnabled = value) } },
            )
            Divider()
            CouiRow(
                title = "导出为 CSV",
                summary = "导出最近 24 小时原始采样，可发给其他应用分析",
                value = "导出",
                valueColor = if (counts.isEmpty) couiColors.summary else couiColors.accent,
                enabled = !counts.isEmpty,
                chevron = !counts.isEmpty,
                onClick = if (counts.isEmpty) null else onExportHistory,
            )
            Divider()
            CouiRow(
                title = "清除全部本地记录",
                value = if (counts.isEmpty && counts.chargeRecords == 0) "暂无记录" else "清除",
                valueColor = if (counts.isEmpty) couiColors.summary else couiColors.danger,
                enabled = !counts.isEmpty || counts.chargeRecords > 0,
                onClick = { confirmClear = true },
            )
        }
        Group(
            title = "恢复默认",
            footer = "只重置本应用的设置与显示偏好，不会改动系统充电项目。控制开关会一并关闭并恢复系统默认。",
        ) {
            CouiRow(
                title = "重置全部设置",
                value = "重置",
                valueColor = couiColors.secondary,
                onClick = { confirmReset = true },
            )
        }
        crashRecord?.let { record ->
            Group(
                title = "最近一次异常",
                footer = "崩溃栈只存在本机，不会上传。点第一行复制全文，反馈问题时贴给开发者即可定位。",
            ) {
                CouiRow(
                    title = "复制崩溃信息",
                    summary = record.lineSequence().firstOrNull().orEmpty(),
                    value = "复制",
                    valueColor = couiColors.accent,
                    chevron = true,
                    onClick = { copyCrashRecord(context, record) },
                )
                Divider()
                CouiRow(
                    title = "清除这条记录",
                    value = "清除",
                    valueColor = couiColors.secondary,
                    onClick = {
                        CrashRecorder.clear(context)
                        crashRecord = null
                    },
                )
            }
        }
    }
    if (confirmClear) {
        com.itos.coui.dialog.Dialog(
            onDismissRequest = { confirmClear = false },
            title = "清除全部记录？",
            message = "24 小时原始功率与温度记录、充放电摘要会被删除，此操作不可撤销。",
            // 主操作必须由宿主给配色（COUI 在组合期 require），统一走 couiPrimaryAction。
            confirmAction = couiPrimaryAction("删除") {
                onClearHistory()
                confirmClear = false
            },
            dismissAction = com.itos.coui.dialog.CouiDialogAction(
                text = "取消",
                onClick = { confirmClear = false },
            ),
        )
    }
    if (confirmReset) {
        com.itos.coui.dialog.Dialog(
            onDismissRequest = { confirmReset = false },
            title = "重置全部设置？",
            message = "外观、通知、检测与硬件修正都会回到默认值，快充控制会关闭并恢复系统默认。本地记录不会被删除。",
            confirmAction = couiPrimaryAction("重置") {
                onResetSettings()
                confirmReset = false
            },
            dismissAction = com.itos.coui.dialog.CouiDialogAction(
                text = "取消",
                onClick = { confirmReset = false },
            ),
        )
    }
}

private data class FaqEntry(val question: String, val answer: String)

@Composable
private fun FaqPage(onBack: () -> Unit) {
    val couiColors = CouiPageDefaults.themeColors()
    var expanded by rememberSaveable { mutableIntStateOf(0) }
    val entries = listOf(
        FaqEntry("为什么手机显示功率和功率计不一致？", "界面显示的是系统提供的电池侧数据，功率计显示的是充电器输入端功率。链路损耗和手机自身耗电都会造成差异。"),
        FaqEntry("为什么亮屏快充有时只维持十几瓦？", "系统会结合温度、电量、前台应用和充电策略动态限流。OPPower 能调整已验证的策略档位，但不会关闭原生温控。"),
        FaqEntry("拔掉充电器后会恢复默认吗？", "会。控制服务检测到拔电后会恢复偏移。重新接电时再按当前规则开始。"),
        FaqEntry("为什么设置已经开启却没有生效？", "请先确认系统控制权限、电池优化白名单和常驻服务状态。控制策略无法回读时，应用会主动恢复默认。"),
        FaqEntry("充满后为什么会暂停增强？", "100% 后继续提高充电功率没有意义。应用会恢复偏移并暂停增强，重新拔插后才开始新会话。"),
        FaqEntry("充电检测里的“效率”是什么？", "它把充入电量按 3.85 V 折算成能量，再与输入电能比较，包含线材与升压损耗，只适合横向比较，不是电芯库仑效率。"),
        FaqEntry("为什么识别不到充电器功率？", "部分 ColorOS 版本不向 shell 暴露 voltage_max/current_max。此时只显示实际输入与阶段判断，不影响快充控制。"),
        FaqEntry("曲线为什么每 15 分钟才标一个时间节点？", "图表默认显示 2 小时窗口，所以横轴按 15 分钟显示时间标签。实际采样与 LLPower 一致：充电亮屏 3 秒、充电熄屏 5 秒、未充电 180 秒。"),
    )
    CouiPage(title = "常见问题", subtitle = "功率、读数与后台运行", onBack = onBack) {
        Group(title = "问答") {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Divider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (expanded == index) -1 else index }
                        .padding(horizontal = CouiPageDefaults.rowPadding, vertical = CouiPageDefaults.contentPadding),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.question,
                            style = CouiType.itemTitle(),
                            color = if (expanded == index) couiColors.accent else couiColors.title,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (expanded == index) "−" else "+",
                            color = couiColors.summary,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded == index,
                        enter = expandVertically(spring(stiffness = 420f)) + fadeIn(),
                        exit = shrinkVertically(spring(stiffness = 420f)) + fadeOut(),
                    ) {
                        Text(
                            entry.answer,
                            style = CouiType.itemSummary(),
                            color = couiColors.summary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutPage(
    onBack: () -> Unit,
    onNavigate: (SettingsScreen) -> Unit,
    onJoinQq: () -> Unit,
) {
    val context = LocalContext.current
    val profile = DeviceProfiles.get(context)
    // 这一页按 ColorOS 17「关于」的范式排：大标题顶栏 + 封面 + 无标题分组。
    CouiPage(title = "关于 OPPower", onBack = onBack, largeTitle = true) {
        AboutHero()
        Group {
            CouiRow("版本", value = BuildConfig.VERSION_NAME)
            Divider()
            CouiRow("当前设备", value = profile.displayName)
            Divider()
            CouiRow("电池结构", value = profile.cellLabel)
            Divider()
            CouiRow("适配方式", value = "按设备能力探测，不绑定机型")
            Divider()
            CouiRow("网络与上传", value = "无网络权限，仅存本机")
        }
        Group(title = "说明与许可") {
            CouiRow(
                title = "重要说明",
                value = "使用边界与风险",
                chevron = true,
                onClick = { onNavigate(SettingsScreen.IMPORTANT_NOTICE) },
            )
            Divider()
            CouiRow(
                title = "开发者说明",
                value = "来源与反馈",
                chevron = true,
                onClick = { onNavigate(SettingsScreen.DEVELOPER_NOTICE) },
            )
            Divider()
            CouiRow(
                title = "开源许可",
                value = "第三方组件",
                chevron = true,
                onClick = { onNavigate(SettingsScreen.LICENSES) },
            )
        }
        Group(title = "反馈", footer = "点击后会复制群号并尝试打开 QQ 群邀请链接。") {
            CouiRow("OPPower 用户群", value = QQ_GROUP_NUMBER, chevron = true, onClick = onJoinQq)
        }
    }
}

@Composable
private fun NoticePage(title: String, onBack: () -> Unit, paragraphs: List<String>) {
    val couiColors = CouiPageDefaults.themeColors()
    CouiPage(title = title, subtitle = "OPPower", onBack = onBack) {
        Group(title = "说明") {
            paragraphs.forEachIndexed { index, paragraph ->
                Text(
                    paragraph,
                    style = CouiType.itemSummary(),
                    color = couiColors.summary,
                    modifier = Modifier.padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
                )
                if (index != paragraphs.lastIndex) Divider()
            }
        }
    }
}

@Composable
private fun LicensesPage(onBack: () -> Unit) {
    val licenses = listOf(
        "AndroidX / Jetpack Compose" to "Apache License 2.0",
        "Kotlin Coroutines" to "Apache License 2.0",
        "Shizuku API" to "Apache License 2.0",
        "Material Components" to "Apache License 2.0",
    )
    CouiPage(title = "开源许可", subtitle = "本项目使用的第三方组件", onBack = onBack) {
        Group(
            title = "第三方组件",
            footer = "各组件版权与完整许可证归原作者所有。源码仓库会保留对应依赖声明和许可信息。",
        ) {
            licenses.forEachIndexed { index, (name, license) ->
                CouiRow(name, value = license)
                if (index != licenses.lastIndex) Divider()
            }
        }
        Group(
            title = "界面规范",
            footer = "界面度量参考 COUI-Kit（MPL 2.0）公开的 ColorOS 17 布局规范独立实现，" +
                "未引入该库的二进制依赖，也不代表与原厂存在隶属关系。",
        ) {
            CouiRow("设计参考", value = "COUI-Kit · MPL 2.0")
        }
    }
}

@Composable
private fun CouiStatusRow(title: String, value: String, ok: Boolean, warn: Boolean = false) {
    val couiColors = CouiPageDefaults.themeColors()
    CouiRow(
        title = title,
        value = value,
        valueColor = if (ok) couiColors.accent else if (warn) couiColors.secondary else couiColors.danger,
        leading = {
            CouiStatusDot(ok = ok, warn = warn)
            Spacer(Modifier.padding(end = 10.dp))
        },
    )
}

private fun usesLiveNotificationChannel(): Boolean = Build.VERSION.SDK_INT >= 36

/**
 * 本地占用 = `filesDir` 全部文件 + 24 小时曲线数据库（含 wal/shm）。
 * 这段要遍历目录，只在 IO 线程调用。
 */
private fun measureLocalStorage(context: Context): Long {
    val files = context.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    // The 24 h sample database lives in databases/, not files/.
    val databases = context.getDatabasePath("chart-history-24h.db")
    return files + listOf(databases, java.io.File("${databases.path}-wal"), java.io.File("${databases.path}-shm"))
        .sumOf { if (it.isFile) it.length() else 0L }
}

/**
 * 系统设置的子页不是每个 ROM 都实现：ColorOS 上无障碍、电池优化、悬浮窗、应用详情
 * 都可能被裁掉（尤其是精简固件）。`ACTION_*` 找不到 Activity 时 `startActivity`
 * 抛 `ActivityNotFoundException`，而这个调用发生在点击回调里，没人接就直接闪退。
 *
 * 所有跳系统设置的入口统一走这里，失败只提示、不抛。
 */
private fun openSystemPage(context: Context, intent: Intent, failureHint: String) {
    val opened = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, failureHint, Toast.LENGTH_SHORT).show()
    }
}

private fun openAccessibilitySettings(context: Context) {
    openSystemPage(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        "系统没有提供无障碍设置入口",
    )
}

/** 把崩溃记录整段复制走：设置页里只显示标题行，全文靠剪贴板带出去。 */
/** 把崩溃记录整段复制走：设置页里只显示标题行，全文靠剪贴板带出去。 */
private fun copyCrashRecord(context: Context, record: String) {
    runCatching {
        context.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("OPPower 崩溃记录", record))
        Toast.makeText(context, "崩溃信息已复制", Toast.LENGTH_SHORT).show()
    }
}

private fun openNotificationSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) openAppDetails(context)
}

private fun openBatteryOptimizationSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) openAppDetails(context)
}

private fun openAppDetails(context: Context) {
    openSystemPage(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        "系统没有提供应用详情入口",
    )
}

private fun openOverlayPermission(context: Context) {
    openSystemPage(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
        "系统没有提供悬浮窗权限入口",
    )
}

private fun hardwareSummary(settings: RuntimeSettings): String = buildList {
    if (settings.invertCurrentSign) add("电流已反向")
    if (settings.doubleCellCorrection) add("双芯合并")
    if (settings.virtualVoltage) add("虚拟电压")
    if (settings.absoluteCurrentPowerDisplay) add("绝对值显示")
}.joinToString(" · ").ifEmpty { "默认读数，无需修正" }

private fun shizukuStatus(access: ShizukuAccess): String = when {
    !access.installed -> "未安装 Shizuku"
    !access.available -> "Shizuku 未运行"
    !access.permission -> "尚未授权 OPPower"
    access.connected -> "已授权并连接"
    else -> "已授权，等待服务连接"
}

private fun friendlyPowerGear(gear: Int): String = when (gear) {
    0 -> "最大输出"
    in 1..4 -> "20–40 W · 档 $gear"
    5 -> "10–30 W · 档 $gear"
    else -> "档 $gear"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576f)
    bytes >= 1L shl 10 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}

/** 供主页面复用的充电阶段着色，避免两处不一致。 */
@Composable
internal fun ChargeStage.accentColor(): androidx.compose.ui.graphics.Color {
    val couiColors = CouiPageDefaults.themeColors()
    return when (this) {
    ChargeStage.CONSTANT_CURRENT, ChargeStage.FULL -> couiColors.accent
    ChargeStage.CONSTANT_VOLTAGE, ChargeStage.TRICKLE, ChargeStage.HANDSHAKE -> couiColors.secondary
    ChargeStage.DISCONNECTED, ChargeStage.DISCHARGING -> couiColors.summary
    }
}
