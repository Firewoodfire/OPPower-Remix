package com.oppower.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.itos.coui.theme.CouiColorScheme
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.CouiTypography
import com.oppower.model.RuntimeSettings
import com.oppower.model.ThemeMode
import com.oppower.model.ThemePalette

/**
 * 本项目的调色板。只保留"应用自己的颜色决定"，语义角色由
 * [CouiColorScheme] 承担——不再有第二套 `CouiColors` 中间层。
 */
internal data class Palette(
    val background: Color,
    val card: Color,
    val accent: Color,
    val accentSurface: Color,
    val title: Color,
    val summary: Color,
    val outline: Color,
)

private fun paletteColors(palette: ThemePalette, dark: Boolean): Palette {
    val background = if (dark) Color(0xFF0A0F12) else Color(0xFFF0F2F4)
    val card = if (dark) Color(0xFF1C2227) else Color(0xFFFFFFFF)
    val title = if (dark) Color(0xFFEDF3F5) else Color(0xFF14181B)
    val summary = if (dark) Color(0xFF9BA9B0) else Color(0xFF5C676D)
    val outline = if (dark) Color(0x33FFFFFF) else Color(0x1F000000)
    val accent = when (palette) {
        ThemePalette.TEAL -> if (dark) Color(0xFF4FD1C5) else Color(0xFF0D8074)
        ThemePalette.OCEAN -> if (dark) Color(0xFF5AB8F0) else Color(0xFF1273A8)
        ThemePalette.FOREST -> if (dark) Color(0xFF8ED489) else Color(0xFF357B31)
        ThemePalette.AMBER -> if (dark) Color(0xFFE7BE72) else Color(0xFF8B6320)
        ThemePalette.ROSE -> if (dark) Color(0xFFF0A0AE) else Color(0xFF9A4453)
        ThemePalette.VIOLET -> if (dark) Color(0xFFC4B0F5) else Color(0xFF5F4590)
    }
    return Palette(background, card, accent, accent.copy(alpha = if (dark) 0.16f else 0.10f), title, summary, outline)
}

/**
 * 主题入口。
 *
 * 直接把配色喂给 COUI 的 [CouiTheme]：`Group` / `Item` / `Switch` / `Slider` /
 * `Dialog` / `TopAppBar` 全部从这个作用域读色，本项目不再维护自己的色板副本。
 *
 * Density 只在 uiScale / fontScale 变化时换实例，其余时候沿用系统值，
 * 避免每次重组都新建 Density 造成全树失效。
 */
@Composable
fun OPPowerTheme(settings: RuntimeSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val density = LocalDensity.current
    val palette = paletteColors(settings.themePalette, dark)
    val cardAlpha = settings.cardOpacity.coerceIn(0.65f, 1f)

    val scheme = CouiColorScheme(
        accent = palette.accent,
        onAccent = if (dark) Color(0xFF06241D) else Color.White,
        surface = if (dark) Color(0xFF1C2227) else Color.White,
        container = if (dark) Color(0xFF262D33) else Color(0xFFF7F8F9),
        onSurface = palette.title,
        onSurfaceVariant = palette.summary,
        outlineVariant = palette.outline,
        accentOnSurface = palette.accent,
        menuSurface = if (dark) Color(0xFF262D33) else Color.White,
        checkboxAccent = palette.accent,
        menuCheckboxAccent = palette.accent,
        background = palette.background,
        card = if (cardAlpha >= 0.999f) palette.card else palette.card.copy(alpha = cardAlpha),
    )

    val materialScheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.accent,
            background = palette.background,
            surface = scheme.surface,
            onPrimary = Color(0xFF06241D),
            onBackground = palette.title,
            onSurface = palette.title,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.accent,
            background = palette.background,
            surface = scheme.surface,
            onPrimary = Color.White,
            onBackground = palette.title,
            onSurface = palette.title,
        )
    }

    // Theme changes must update the existing composition in place. Re-keying this
    // subtree discarded the current settings page and sent the user back home.
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density * settings.uiScale.coerceIn(0.85f, 1.15f),
            fontScale = density.fontScale * settings.fontScale.coerceIn(0.85f, 1.2f),
        ),
    ) {
        val fontFamily = OppoFont.family
        val couiTypography = remember(fontFamily) { couiTypographyOf(fontFamily) }
        val materialTypography = remember(fontFamily) { materialTypographyOf(fontFamily) }
        CouiTheme(darkTheme = dark, colorScheme = scheme, typography = couiTypography) {
            MaterialTheme(
                colorScheme = materialScheme,
                typography = materialTypography,
                content = content,
            )
        }
    }
}

/**
 * 把 OPPO Sans 灌进 COUI 的排版表。
 *
 * COUI 的 [CouiTypography] 只有 `body` / `label` / `menuItem` 三个基础项自带
 * 字体族，其余六项对话框族默认 `null`。`null` 在库里的语义是"沿用调用方
 * 传入的样式"，弹窗标题因此可能落到 Material 的默认字体上——所以这里把
 * 六个可空项也显式填上，保证弹窗和页面是同一套字形。
 *
 * 字号、行高、字距全部沿用库的预设，只换 `fontFamily`。
 */
private fun couiTypographyOf(family: FontFamily): CouiTypography {
    val base = CouiTypography()
    return CouiTypography(
        body = base.body.copy(fontFamily = family),
        label = base.label.copy(fontFamily = family),
        menuItem = base.menuItem.copy(fontFamily = family),
        chipLabelFontFamily = family,
        dialogTitleFontFamily = family,
        dialogMessageFontFamily = family,
        dialogButtonFontFamily = family,
        dialogInputFontFamily = family,
        dialogCounterFontFamily = family,
    )
}

/**
 * 大标题专用字族：OPPO Sans Bold 单文件。
 *
 * ColorOS 的设置页大标题（「设置」「组件」）用的是 Bold 而不是 SemiBold，
 * 而且字重是实打实的字体文件，不是 Compose 的合成加粗。合成加粗只是在
 * 原字形外描一圈边，中文大字号会明显糊掉——这是之前"字体不对"的直接原因。
 *
 * `LargeTopAppBar` 内部用 `Typeface.create("sans-serif-semibold", 700)` 取字族，
 * 系统内置名拿不到 OPPO Sans，只能靠传 [androidx.compose.ui.text.TextStyle] 覆盖。
 */
@Composable
internal fun largeTitleFontFamily(): FontFamily =
    remember { OppoFont.weightFamily(700) ?: OppoFont.family }

/**
 * Material 侧同步换族。
 *
 * 项目里还有少量 `androidx.compose.material3.Text`（主要在 `CouiRow` 之外的
 * 临时布局），它们只读 `MaterialTheme.typography`。不同步的话会出现同一页
 * 一半 OPPO Sans、一半 Roboto。
 */
private fun materialTypographyOf(family: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.withFamily() = copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.withFamily(),
        displayMedium = base.displayMedium.withFamily(),
        displaySmall = base.displaySmall.withFamily(),
        headlineLarge = base.headlineLarge.withFamily(),
        headlineMedium = base.headlineMedium.withFamily(),
        headlineSmall = base.headlineSmall.withFamily(),
        titleLarge = base.titleLarge.withFamily(),
        titleMedium = base.titleMedium.withFamily(),
        titleSmall = base.titleSmall.withFamily(),
        bodyLarge = base.bodyLarge.withFamily(),
        bodyMedium = base.bodyMedium.withFamily(),
        bodySmall = base.bodySmall.withFamily(),
        labelLarge = base.labelLarge.withFamily(),
        labelMedium = base.labelMedium.withFamily(),
        labelSmall = base.labelSmall.withFamily(),
    )
}
