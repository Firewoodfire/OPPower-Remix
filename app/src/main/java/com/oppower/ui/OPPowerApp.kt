package com.oppower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import com.oppower.CrashRecorder
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.model.RuntimeSettings
import com.oppower.model.UiDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group

private enum class Page {
    MAIN,
    CHARGE_SETTINGS,
    SETTINGS,
}

/** 顶层页面的层级，只用于决定转场方向：更深从右推入，更浅从左侧回位。 */
private val Page.depth: Int
    get() = when (this) {
        Page.MAIN -> 0
        Page.CHARGE_SETTINGS, Page.SETTINGS -> 1
    }

/**
 * 从整个 [UiState] 里切出一片，作为独立的 `State<T>` 交给页面。
 *
 * `UiState` 每秒都被整体替换一次（电池遥测），若把 `state.value` 直接传进二级页，
 * 那个页面的**整棵组合树**每秒都要重新走一遍参数比对：设置主页 1377 行、快充设置
 * 950 行，低端 SoC 上这一趟就是肉眼可见的卡顿。
 *
 * 切成窄状态后，只有 `selector` 结果真正变了才会通知订阅者：电池刷新只影响读
 * `battery` 的那几个叶子，设置页不动。
 *
 * `remember` 只 key 在 `state` 上是有意的——`selector` 都是无捕获的纯函数，
 * 内容恒定，没必要每次重组换实例。
 */
@Composable
private fun <T> slice(state: State<UiState>, selector: (UiState) -> T): State<T> =
    remember(state) { derivedStateOf { selector(state.value) } }

@Composable
fun OPPowerApp(
    state: State<UiState>,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRequestShizuku: () -> Unit,
    onReadGear: () -> Unit,
    onRestore: () -> Unit,
    onDismissIntro: () -> Unit,
    onClearHistory: () -> Unit,
    onExportHistory: suspend () -> java.io.File?,
    onResetSettings: () -> Unit,
    onRunThermalDiagnostics: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var settingsScreen by rememberSaveable { mutableStateOf(SettingsScreen.HOME) }
    val context = LocalContext.current

    // 页面位置同步给崩溃记录。必须在**组合期**直接写：设置页的崩溃就发生在组合期，
    // 放 LaunchedEffect / SideEffect 里要等提交，那时位置还停在上一页（上次日志
    // 显示「主页」就是这个原因）。这里只写一个 @Volatile 字段，不持有组合状态。
    CrashRecorder.markLocation(
        when (page) {
            Page.MAIN -> "主页"
            Page.CHARGE_SETTINGS -> "快充设置"
            Page.SETTINGS -> "设置 / ${settingsScreen.name}"
        },
    )

    // 下面这些窄状态在 OPPowerApp 这一层取，页面拿到的就是「只在必要时才变」的值。
    // 放在这里而不是页面里，是因为外层 `when` 一旦读 `state.value`，整块转场容器
    // 就会跟着每秒电池刷新重组，页面再怎么拆也白搭。
    val settingsSlice = slice(state) { it.settings }
    val acOnlineSlice = slice(state) { it.battery.acOnline }
    val controllerStatusSlice = slice(state) { it.controllerStatus }
    val gearSlice = slice(state) { it.gearReadResult }
    val shizukuSlice = slice(state) {
        ShizukuAccess(
            installed = it.shizukuInstalled,
            available = it.shizukuAvailable,
            permission = it.shizukuPermission,
            connected = it.shizukuServiceConnected,
        )
    }
    val systemAccessSlice = slice(state) { it.systemAccess }
    val insightSlice = slice(state) { it.insight }
    val batterySlice = slice(state) { it.battery }
    val historyCountsSlice = slice(state) {
        HistoryCounts(
            points = it.points.size,
            chargeRecords = it.chargeRecords.size,
            dischargeRecords = it.dischargeRecords.size,
        )
    }
    val thermalSlice = slice(state) { it.thermalDiagnostics }
    val thermalLoadingSlice = slice(state) { it.thermalDiagnosticsLoading }

    BackHandler(enabled = page != Page.MAIN) {
        when (page) {
            Page.MAIN -> Unit
            Page.CHARGE_SETTINGS -> page = Page.MAIN
            Page.SETTINGS -> if (settingsScreen == SettingsScreen.HOME) {
                page = Page.MAIN
            } else {
                settingsScreen = if (settingsScreen.isAboutChild()) SettingsScreen.ABOUT else SettingsScreen.HOME
            }
        }
    }

    // COUI 的弹窗（Dialog / 菜单）在 Dialog 窗口里要取窗口级背景源做玻璃底：
    //   val windowBackdrop = backdrop ?: LocalCouiMenuBackdrop.current
    //   if (material != null) checkNotNull(windowBackdrop)
    // 不提供就会在弹窗创建那一刻抛 IllegalArgumentException。界面本身不会崩，
    // 但"点充电记录 / 放电记录"这类只打开弹窗的操作会直接闪退。
    // 这里在应用根部提供一次，弹窗统一拿到同一份源，弹窗也随内容一起进采样。
    //
    // 页面之间走 PageTransition：前进时新页从右侧推入，返回时从左侧回位。
    // 早先这里用直接 when 切换，是为了避开 crossfade 叠两页的绘制开销；现在的做法把
    // 退出压到 120ms、位移只有 1/8 屏宽，叠层窗口很短，同时每页的状态用
    // SaveableStateHolder 保住，返回主页时列表不会跳回顶部。
    ProvideCouiMenuBackdrop { contentModifier ->
        PageTransition(
            target = page,
            depthOf = { it.depth },
            label = "RootPage",
            modifier = contentModifier.fillMaxSize(),
        ) { current ->
            when (current) {
                Page.MAIN -> MainPage(
                    state = state,
                    onOpenChargeSettings = { page = Page.CHARGE_SETTINGS },
                    onOpenSettings = {
                        settingsScreen = SettingsScreen.HOME
                        page = Page.SETTINGS
                    },
                    onOpenDetectSettings = {
                        settingsScreen = SettingsScreen.CHARGE_DETECT
                        page = Page.SETTINGS
                    },
                    onRequestShizuku = onRequestShizuku,
                    onSettingsChange = onSettingsChange,
                    onJoinQq = { joinQqGroup(context) },
                )

                Page.CHARGE_SETTINGS -> ChargeSettingsPage(
                    settings = settingsSlice,
                    acOnline = acOnlineSlice,
                    controllerStatus = controllerStatusSlice,
                    gearReadResult = gearSlice,
                    shizuku = shizukuSlice,
                    onBack = { page = Page.MAIN },
                    onSettingsChange = onSettingsChange,
                    onRestore = onRestore,
                    onRequestShizuku = onRequestShizuku,
                    onReadGear = onReadGear,
                )

                Page.SETTINGS -> SettingsRouter(
                    screen = settingsScreen,
                    settings = settingsSlice,
                    shizuku = shizukuSlice,
                    systemAccess = systemAccessSlice,
                    insight = insightSlice,
                    controllerStatus = controllerStatusSlice,
                    battery = batterySlice,
                    historyCounts = historyCountsSlice,
                    thermalDiagnostics = thermalSlice,
                    thermalDiagnosticsLoading = thermalLoadingSlice,
                    onNavigate = { settingsScreen = it },
                    onBack = {
                        if (settingsScreen == SettingsScreen.HOME) {
                            page = Page.MAIN
                        } else {
                            settingsScreen = if (settingsScreen.isAboutChild()) SettingsScreen.ABOUT else SettingsScreen.HOME
                        }
                    },
                    onSettingsChange = onSettingsChange,
                    onJoinQq = { joinQqGroup(context) },
                    onClearHistory = onClearHistory,
                    onRequestShizuku = onRequestShizuku,
                    onExportHistory = {
                        scope.launch {
                            val file = onExportHistory() ?: return@launch
                            shareCsv(context, file)
                        }
                    },
                    onResetSettings = onResetSettings,
                    onRunThermalDiagnostics = onRunThermalDiagnostics,
                )
            }
        }

        // 崩溃记录只在「设置 → 数据管理」里看：闪退已修好，不再在启动时弹提示条。
        // CrashRecorder 仍在后台写盘，需要时去那一页复制即可。

        // 首启弹窗必须和页面在同一份菜单/对话框背景源作用域里：
        // COUI 的 Dialog 在 material != null 时会 checkNotNull(LocalCouiMenuBackdrop.current)，
        // 放在 ProvideCouiMenuBackdrop 外面就是一次 IllegalArgumentException 闪退。
        FirstRunHost(
            state = state,
            onDismiss = onDismissIntro,
            onJoinQq = { joinQqGroup(context) },
        )
    }
}

private fun shareCsv(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "OPPower 24 小时充电曲线")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "导出 OPPower 数据").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

@Composable
private fun FirstRunHost(
    state: State<UiState>,
    onDismiss: () -> Unit,
    onJoinQq: () -> Unit,
) {
    val showIntro = remember(state) { derivedStateOf { state.value.showIntro } }
    if (showIntro.value) {
        FirstRunDialog(onDismiss = onDismiss, onJoinQq = onJoinQq)
    }
}

private fun SettingsScreen.isAboutChild(): Boolean = when (this) {
    SettingsScreen.IMPORTANT_NOTICE,
    SettingsScreen.DEVELOPER_NOTICE,
    SettingsScreen.LICENSES,
    -> true
    else -> false
}

@Composable
private fun FirstRunDialog(onDismiss: () -> Unit, onJoinQq: () -> Unit) {
    val colors = CouiPageDefaults.themeColors()
    com.itos.coui.dialog.Dialog(
        onDismissRequest = {},
        title = "关于 OPPower",
        confirmAction = couiPrimaryAction("知道了", onDismiss),
    ) {
        Column {
            com.itos.coui.text.Text(
                text = "本项目基于 LLPower 二改得来，增加了 ColorOS 亮屏快充功能。",
                kind = com.itos.coui.text.CouiTextKind.ItemSummary,
                color = colors.summary,
            )
            Row(Modifier.padding(top = 14.dp)) {
                com.itos.coui.text.Text(
                    text = "大家感兴趣的可以加群：",
                    kind = com.itos.coui.text.CouiTextKind.ItemSummary,
                    color = colors.summary,
                )
                com.itos.coui.text.Text(
                    text = QQ_GROUP_NUMBER,
                    kind = com.itos.coui.text.CouiTextKind.ItemTitle,
                    color = colors.accent,
                    modifier = Modifier.clickable(onClick = onJoinQq).padding(horizontal = 2.dp),
                )
            }
            com.itos.coui.text.Text(
                text = "点击群号可复制并尝试打开 QQ 加入群聊。有任何问题可在群内反馈。",
                kind = com.itos.coui.text.CouiTextKind.GroupFooter,
                color = colors.summary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
