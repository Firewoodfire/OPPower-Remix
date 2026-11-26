package com.oppower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageColors
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.layout.Scaffold
import com.itos.coui.scroll.couiVerticalScroll
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.shape.CouiShape
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiColor
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiCollapsingAppBarDefaults
import com.itos.coui.topappbar.TopAppBarButton

/**
 * COUI 组件在本项目里的**语义别名**。
 *
 * 这里没有任何自绘实现：`Group` / `Item` / `Divider` / `Footer` / `Switch` /
 * `SegmentedControl` / `Slider` / `Dialog` / `TopAppBar` 全部来自
 * `io.github.itoseo:coui` 的公开 API，本项目只填参数。
 *
 * 之所以还要有这一层，是因为 OPPower 的调用点有 200 多个，逐处展开会把
 * "标题 + 摘要 + 右值 + 箭头" 这种固定组合抄 200 遍；COUI 官方 demo
 * （`DemoPreferenceRows.kt`）用的也是同一套做法。
 */

/** 页面语义色；`danger` 是 COUI 的 RED，`CouiPageColors` 本身不含这一角色。 */
internal val CouiPageColors.danger: Color
    @Composable get() = if (CouiTheme.isDark) CouiColor.Night.RED else CouiColor.Day.RED

/** 次要强调色。 */
internal val CouiPageColors.secondary: Color
    @Composable get() = CouiTheme.colorScheme.onSurfaceVariant

/** 强调色的低饱和底，用于图标底、徽标。 */
internal val CouiPageColors.accentSurface: Color
    @Composable get() = accent.copy(alpha = if (CouiTheme.isDark) 0.16f else 0.10f)

/** 中性底，用于进度槽。 */
internal val CouiPageColors.secondarySurface: Color
    @Composable get() = summary.copy(alpha = if (CouiTheme.isDark) 0.14f else 0.08f)

/** 当前是否深色。 */
internal val CouiPageColors.isDark: Boolean
    @Composable get() = CouiTheme.isDark

/** COUI 排版角色。直接取 `rememberCouiTextStyle`，字号/字重/行距全部由库决定。 */
internal object CouiType {
    @Composable
    fun itemTitle(): TextStyle = rememberCouiTextStyle(CouiTextKind.ItemTitle)
    @Composable
    fun itemSummary(): TextStyle = rememberCouiTextStyle(CouiTextKind.ItemSummary)
    @Composable
    fun itemValue(): TextStyle = rememberCouiTextStyle(CouiTextKind.ItemValue)
    @Composable
    fun groupTitle(): TextStyle = rememberCouiTextStyle(CouiTextKind.GroupTitle)
    @Composable
    fun groupFooter(): TextStyle = rememberCouiTextStyle(CouiTextKind.GroupFooter)
    @Composable
    fun topBar(): TextStyle = rememberCouiTextStyle(CouiTextKind.TopBar)
    @Composable
    fun sheetTitle(): TextStyle = rememberCouiTextStyle(CouiTextKind.SheetTitle)

    /**
     * 下面几档 COUI 没有对应角色：它们服务于"一屏要塞下 8 行数据"的密集区
     * （曲线轴标签、规则区间表、图标角标），套 13sp 会把两屏内容挤成三屏。
     */
    val pageTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    val metric = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val metricHuge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
    val captionStrong = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
    val dense = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 15.sp)
    val denseStrong = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
}

/** `CouiPageDefaults` 的短名，避免每个调用点都写全限定名。 */
internal object Coui {
    val pageMargin: Dp = CouiPageDefaults.horizontalMargin
    val rowPadding: Dp = CouiPageDefaults.rowPadding
    val rowMinHeight: Dp = CouiPageDefaults.rowMinHeight
    val iconSize: Dp = CouiPageDefaults.iconSize
    val iconSpacing: Dp = CouiPageDefaults.iconSpacing
    val contentPadding: Dp = CouiPageDefaults.contentPadding
    val summarySpacing: Dp = CouiPageDefaults.summarySpacing
    val groupSpacing: Dp = CouiPageDefaults.groupSpacing
    val categoryTopSpacing: Dp = CouiPageDefaults.categoryTopSpacing
    val titleInset: Dp = 32.dp
    val cardRadius: Dp = 24.dp
    val toolbarHeight: Dp = CouiPageDefaults.toolbarHeight
    val dividerHeight: Dp = CouiPageDefaults.dividerHeight
}

/** 卡片外形：COUI 超椭圆，`cornerWeight = 3f`。 */
internal val CouiCardShape = CouiShape(24.dp, 3f)

/**
 * 列表行的图标。
 *
 * 度量沿用 COUI 的 `Item.icon` 槽：24dp 见方、与标题间距 16dp。槽位只有 24dp，
 * 所以用「单色 COUI 图标 + 16% 主色底 + 超椭圆圆角」来表达分组的颜色，
 * 底块 26dp 略溢出槽位 1dp —— `Item` 内是居中的 Box，不会裁切。
 *
 * 图标全部来自 `CouiIcons`，没有一个自绘：颜色只改 tint，形状仍是库里的。
 */
@Composable
internal fun CouiTintedIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CouiShape(8.dp, 2.5f))
            .background(tint.copy(alpha = if (CouiTheme.isDark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 设置分组的图标色板。
 *
 * 对应 ColorOS 设置里「每类一个颜色」的观感：紫、橙、青、红、绿、蓝。
 * 深色主题下整体提亮，否则 24dp 的小图标在暗底上会糊成一团。
 */
internal object CouiIconTint {
    private val day = listOf(
        Color(0xFF7B5CD6),
        Color(0xFFE08B2D),
        Color(0xFF2E9E9B),
        Color(0xFFD4514F),
        Color(0xFF3F9E5A),
        Color(0xFF4A7FD4),
        Color(0xFFB05E9E),
    )
    private val night = listOf(
        Color(0xFFA98DF2),
        Color(0xFFF0A85C),
        Color(0xFF5FC7C3),
        Color(0xFFE6797A),
        Color(0xFF6CC183),
        Color(0xFF7BA6E8),
        Color(0xFFD187C2),
    )

    /** 按分组序号取色，越界时回绕。 */
    @Composable
    fun at(index: Int): Color {
        val palette = if (CouiTheme.isDark) night else day
        return palette[((index % palette.size) + palette.size) % palette.size]
    }
}

/** 胶囊外形。 */
internal fun capsuleShape(radius: Dp) = CouiShape(radius, 2.5f)

/**
 * 通用设置行：左标题（可带摘要）、右侧值 / 开关 / 箭头。
 *
 * 底层是 COUI 的 [Item]：行高、内缩、按压蒙层、语义合并都由库处理。
 */
@Composable
internal fun CouiRow(
    title: String,
    summary: String? = null,
    icon: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    valueColor: Color = Color.Unspecified,
    chevron: Boolean = false,
    enabled: Boolean = true,
    role: Role? = null,
    minHeight: Dp = CouiPageDefaults.rowMinHeight,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CouiPageDefaults.themeColors()
    val dark = CouiTheme.isDark
    Item(
        modifier = Modifier.heightIn(min = minHeight),
        icon = icon ?: leading,
        onClick = onClick,
        enabled = enabled,
        title = {
            Text(
                text = title,
                kind = CouiTextKind.ItemTitle,
                color = if (enabled) colors.title else colors.disabled,
                textAlign = TextAlign.Start,
            )
        },
        summary = summary?.takeIf { it.isNotEmpty() }?.let { text ->
            @Composable {
                Text(
                    text = text,
                    kind = CouiTextKind.ItemSummary,
                    color = if (enabled) colors.summary else colors.disabled,
                    textAlign = TextAlign.Start,
                )
            }
        },
        trailing = {
            if (!value.isNullOrEmpty()) {
                Text(
                    modifier = Modifier.widthIn(max = 168.dp).padding(start = 12.dp),
                    text = value,
                    kind = CouiTextKind.ItemValue,
                    color = if (valueColor == Color.Unspecified) colors.summary else valueColor,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chevron) {
                androidx.compose.material3.Icon(
                    imageVector = CouiIcons.Filled.Next,
                    contentDescription = null,
                    tint = if (dark) Color(0x4DFFFFFF) else Color(0x42000000),
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
            trailing?.invoke()
        },
    )
}

/** 开关行；整行可点，右侧是 COUI 的 [Switch]。 */
@Composable
internal fun CouiSwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val dark = CouiTheme.isDark
    Item(
        title = {
            Text(
                text = title,
                kind = CouiTextKind.ItemTitle,
                color = if (enabled) colors.title else colors.disabled,
                textAlign = TextAlign.Start,
            )
        },
        summary = summary?.takeIf { it.isNotEmpty() }?.let { text ->
            @Composable {
                Text(
                    text = text,
                    kind = CouiTextKind.ItemSummary,
                    color = if (enabled) colors.summary else colors.disabled,
                    textAlign = TextAlign.Start,
                )
            }
        },
        onClick = { if (enabled) onCheckedChange(!checked) },
        enabled = enabled,
        trailing = {
            Switch(
                modifier = Modifier.padding(start = 16.dp),
                checked = checked,
                onCheckedChange = onCheckedChange,
                dark = dark,
                colors = CouiSwitchDefaults.colors(colors.accent, dark),
                enabled = enabled,
            )
        },
    )
}

/** 带分段控件的行：标题与说明在上、分段在下。分段本身是 COUI 的 [SegmentedControl]。 */
@Composable
internal fun CouiChoiceRow(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    summary: String? = null,
    onSelected: (Int) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CouiPageDefaults.rowPadding, vertical = CouiPageDefaults.contentPadding),
    ) {
        Text(text = title, kind = CouiTextKind.ItemTitle, color = colors.title, textAlign = TextAlign.Start)
        if (!summary.isNullOrEmpty()) {
            Text(
                text = summary,
                kind = CouiTextKind.ItemSummary,
                color = colors.summary,
                modifier = Modifier.padding(top = CouiPageDefaults.summarySpacing),
                textAlign = TextAlign.Start,
            )
        }
        SegmentedControl(
            modifier = Modifier.padding(top = 10.dp),
            labels = labels,
            selectedIndex = selectedIndex.coerceIn(0, labels.lastIndex.coerceAtLeast(0)),
            onSelected = onSelected,
        )
    }
}

/** 组脚注；转发到 COUI 的 [Footer]。 */
@Composable
internal fun CouiFooter(text: String, modifier: Modifier = Modifier) {
    Footer(modifier = modifier, text = text)
}

/** 无标题卡片。 */
@Composable
internal fun CouiCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    Column(
        modifier
            .fillMaxWidth()
            .clip(CouiCardShape)
            .background(colors.card),
        content = content,
    )
}

/**
 * 弹窗主操作按钮。
 *
 * COUI 在这里埋了两道校验：`CouiDialogAction.init` 里的
 * `require(style != Primary || colors != null)`，以及按钮绘制时的
 * `error("主操作必须由宿主显式提供配色")`。两条都在组合期抛，
 * 漏传配色就是直接闪退，而且只在弹窗真正显示时才触发——很容易漏测。
 *
 * 这个封装把配色带死，调用方没有漏的机会。
 */
@Composable
internal fun couiPrimaryAction(text: String, onClick: () -> Unit): com.itos.coui.dialog.CouiDialogAction {
    val accent = CouiPageDefaults.themeColors().accent
    return com.itos.coui.dialog.CouiDialogAction(
        text = text,
        onClick = onClick,
        style = com.itos.coui.dialog.CouiDialogActionStyle.Primary,
        colors = com.itos.coui.dialog.CouiDialogDefaults.primaryActionColors(accent),
    )
}

/** 状态点：COUI 没有对应组件，两个圆点自绘（不参与任何布局度量）。 */
@Composable
internal fun CouiStatusDot(ok: Boolean, warn: Boolean = false) {
    val colors = CouiPageDefaults.themeColors()
    val color = when {
        warn -> CouiColor.Day.ORANGE
        ok -> colors.accent
        else -> colors.disabled
    }
    Box(Modifier.size(8.dp).clip(CouiShape(4.dp, 2f)).background(color))
}

/**
 * 二级页外壳。
 *
 * `Scaffold` 负责安全区、正文底色，并把自己录制的 backdrop 提供给顶栏；
 * `TopAppBar` 是 COUI 的固定高度顶栏，返回键用 COUI 的 `TopAppBarButton`。
 * 正文用 COUI 的 `couiVerticalScroll`，滚动到边缘时的拉伸也走库实现。
 *
 * [search] 只在 [largeTitle] 为 true 时生效：它被交给 COUI 顶栏的搜索槽，
 * 由库负责滚动时的收起与模糊背景延伸，不用调用方自己算。
 */
@Composable
internal fun CouiPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    largeTitle: Boolean = false,
    search: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (largeTitle) {
        CouiLargePage(title, onBack, modifier, subtitle, search, actions, content)
        return
    }
    val colors = CouiPageDefaults.themeColors()
    val dark = CouiTheme.isDark
    val scrollState = rememberScrollState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            com.itos.coui.topappbar.TopAppBar(
                title = {
                    Text(
                        text = title,
                        kind = CouiTextKind.TopBar,
                        color = colors.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )
                },
                navigationIcon = {
                    com.itos.coui.topappbar.TopAppBarButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            imageVector = CouiIcons.Filled.Back,
                            contentDescription = "返回",
                            tint = colors.title,
                        )
                    }
                },
                actions = actions,
                dark = dark,
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .couiVerticalScroll(scrollState)
                .padding(innerPadding)
                .padding(bottom = CouiPageDefaults.groupSpacing),
        ) {
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    kind = CouiTextKind.GroupFooter,
                    color = colors.summary,
                    modifier = Modifier.padding(horizontal = Coui.titleInset, vertical = 6.dp),
                    textAlign = TextAlign.Start,
                )
            }
            content()
        }
    }
}

/**
 * 大标题二级页：和主页用同一个 `LargeTopAppBar`，滚到底整条退出。
 *
 * 之前用 `CollapsingTopAppBar`：它按 `hasSearchBar` 分两支，无搜索槽时标题会**钉
 * 在 52dp 紧凑栏里一直不走**（beta54 之前设置页大标题不消失的原因）；有搜索槽
 * 时又得把搜索框绑进顶栏。现在改成参数化控制：
 *
 * | 手段 | 作用 |
 * | --- | --- |
 * | `collapsedHeight = 0.1.dp` | 库要求 `> 0.dp` 且 `< expandedHeight`，给接近 0 的值让顶栏整条退出 |
 * | `graphicsLayer { alpha = 1f - collapsedFraction }` | 标题随折叠进度淡出，只读 snapshot、不触发重组 |
 *
 * 返回键放在顶栏自带的 `navigationIcon` 槽里。早先因为 `CollapsingTopAppBar` 没有
 * 这个槽，把返回键做成顶栏外的浮层，导致 `TopAppBarButton` 取不到 internal 的
 * `LocalCouiToolbarBackdrop`，进设置必崩（beta53 的崩溃栈定位到）。放到槽里之后
 * 「标题不消失」和「闪退」一起解决。
 */
@Composable
private fun CouiLargePage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    search: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val dark = CouiTheme.isDark
    val scrollState = rememberScrollState()
    val behavior = com.itos.coui.topappbar.CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward },
    )
    val density = LocalDensity.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            com.itos.coui.topappbar.LargeTopAppBar(
                title = {
                    androidx.compose.material3.Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 折叠进度驱动标题淡出。在 graphicsLayer 里读 snapshot，
                        // 只触发重绘，不会让顶栏每帧重组。
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - behavior.state.collapsedFraction
                        },
                    )
                },
                // 返回键放在顶栏自带的 navigationIcon 槽里，作用域由 TopAppBarSurface
                // 提供，TopAppBarButton 能正常取到 LocalCouiToolbarBackdrop。
                // beta53 之前把它做成顶栏外的浮层，那才是设置页闪退的真正原因。
                navigationIcon = {
                    TopAppBarButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            imageVector = CouiIcons.Filled.Back,
                            contentDescription = "返回",
                            tint = colors.title,
                        )
                    }
                },
                actions = actions,
                scrollBehavior = behavior,
                // 库要求 collapsedHeight > 0.dp；给一个视觉上为 0 的值，
                // 折叠到底后整条顶栏退出，不留 52dp 的空栏。
                collapsedHeight = 0.1.dp,
                expandedTitleStyle = TextStyle(fontSize = 40.sp),
                dark = dark,
                colors = com.itos.coui.topappbar.CouiTopAppBarDefaults.colors(
                    titleColor = colors.title,
                    dark = dark,
                ),
            )
        },
    ) { innerPadding ->
        val contentPadding = behavior.state.contentPadding(innerPadding, density)
        Column(
            Modifier
                .fillMaxSize()
                .couiVerticalScroll(scrollState)
                .padding(contentPadding)
                .consumeWindowInsets(innerPadding)
                .padding(bottom = CouiPageDefaults.groupSpacing),
        ) {
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    kind = CouiTextKind.GroupFooter,
                    color = colors.summary,
                    modifier = Modifier.padding(horizontal = Coui.titleInset, vertical = 6.dp),
                    textAlign = TextAlign.Start,
                )
            }
            // LargeTopAppBar 没有搜索槽；调用方若传了，渲染在正文顶部。
            // 当前设置主页 / 关于页都不传，搜索框由调用方直接放进 content。
            search?.invoke()
            content()
        }
    }
}


/**
 * 返回键的透明度。
 *
 * `CollapsingTopAppBar` 的折叠状态 `offsetPx` / `rangePx` 都是 `internal`，应用侧
 * 读不到，所以只能拿正文自己的滚动量估：滚过顶栏高度（110dp）就完全退干净。
 * 返回键是浮层，与标题各走各的动画，差个一两帧也看不出来。
 */
internal fun backButtonAlpha(scrollValue: Int, density: Float): Float =
    1f - (scrollValue / (CouiCollapsingAppBarDefaults.titleOnlyHeight.value * density)).coerceIn(0f, 1f)

/**
 * 标题行字号：ColorOS 设置页大标题实测 40sp / Bold。
 *
 * `CollapsingTopAppBar` 内部用 `sans-serif-semibold` 这个系统内置族名取字，
 * 拿不到 OPPO Sans，所以标题样式得由调用方给——但那一版 API 不收
 * `expandedTitleStyle`，只能整块自己画。这里的常量留给文档与测试对齐用。
 */
internal val LargeTitleSize: androidx.compose.ui.unit.TextUnit = 40.sp


/**
 * 设置搜索框。
 *
 * 尺寸取自 ColorOS 设置页实测（录屏 640px 宽按 412dp 折算）：
 * 高 47dp、左右各 16dp、24dp 圆角、底色比页面亮一档。不是全胶囊——
 * 全胶囊在 47dp 高度下会显得太圆，和 ColorOS 的观感对不上。
 *
 * 搜索框默认由调用方放在**正文顶部**，不提供 `CollapsingTopAppBar` 的搜索槽版本：
 * 那条分支里的 `checkNotNull` 依赖测量时序，偶发在组合期抛异常直接闪退。
 * 外距由调用方给，这里只负责框本身。
 */
@Composable
internal fun CouiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索设置",
) {
    val colors = CouiPageDefaults.themeColors()
    val textStyle = rememberCouiTextStyle(CouiTextKind.Search)
    // Compose 1.13 起 BasicTextField 推荐用 TextFieldState 形式；这里用 rememberTextFieldState
    // 持有文本，再用 snapshotFlow 把变化回传给调用方的 Compose 状态，行为与原来一致。
    val fieldState = rememberTextFieldState(initialText = value)
    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() }.collect { onValueChange(it) }
    }
    // 外部清空（比如点清空按钮）时把输入框也同步掉，避免状态不同步。
    //
    // 光标直接用 `TextRange` 指定：`placeCursorBeforeCharAt` 在**空文本**时越界抛
    // `IndexOutOfBoundsException`——点清空按钮走的就是这条路径，所以上一版点
    // 「清空」会直接闪退。`TextRange(0)` 对空串也合法。
    LaunchedEffect(value) {
        if (value != fieldState.text.toString()) {
            fieldState.edit {
                replace(0, length, value)
                selection = TextRange(value.length)
            }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(SearchFieldHeight)
            .clip(CouiShape(SearchFieldCorner, 2.5f))
            .background(colors.card)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            imageVector = CouiIcons.Outlined.Search,
            contentDescription = null,
            tint = colors.summary,
            modifier = Modifier.size(20.dp),
        )
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(text = placeholder, kind = CouiTextKind.Search, color = colors.disabled)
            }
            BasicTextField(
                state = fieldState,
                textStyle = textStyle.copy(color = colors.title),
                cursorBrush = SolidColor(colors.accent),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            androidx.compose.material3.IconButton(onClick = { onValueChange("") }) {
                androidx.compose.material3.Icon(
                    imageVector = CouiIcons.Filled.Close,
                    contentDescription = "清空",
                    tint = colors.summary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 搜索框高度：ColorOS 实测 47dp，比 COUI 默认的 40dp 高一档。 */
internal val SearchFieldHeight: Dp = 47.dp

/** 搜索框圆角：大圆角矩形，不是全胶囊。 */
internal val SearchFieldCorner: Dp = 24.dp

/** 检测文案：稳定性百分比 → 人话。 */
internal fun stabilityLabel(percent: Int): String = when {
    percent >= 85 -> "平稳"
    percent >= 65 -> "轻微波动"
    else -> "波动明显"
}

/** 分钟数 → 「X 小时 Y 分」。 */
internal fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}
