package com.oppower.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * 页面之间的层级转场。
 *
 * 方向由调用方给的 `depthOf` 决定：目标层级更深就新页从右侧推入（前进），
 * 更浅就新页从左侧回位（返回）。同一层级按前进处理。
 *
 * 曲线与时长沿用 COUI 的窗口动效：`CouiDialogDefaults` 里进入用
 * `CubicBezierEasing(.3f, 0f, .1f, 1f)`、退出用 `CubicBezierEasing(.3f, 0f, 1f, 1f)`，
 * 那对曲线是「先快后缓」，也是 ColorOS 大圆角窗口的观感来源，页面转场直接对齐它，
 * 不另造一套节奏。
 */
internal object PageMotion {
    /** 与 `CouiDialogDefaults.enterEasing` 同一条曲线。 */
    val enterEasing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

    /** 与 `CouiDialogDefaults.exitEasing` 同一条曲线。 */
    val exitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** 进入时长。COUI 对话框是 250ms，页面内容更多，向后退一档。 */
    const val EnterMillis = 240

    /**
     * 淡入时长，故意比位移短。
     *
     * 退出只有 120ms，如果新页也花 240ms 才不透明，旧页消失的那一刻新页还是半透明，
     * 底下会露出页面底色闪一下。把 alpha 压在 130ms 内走完，两者重叠后新页已经实心，
     * 剩下的位移就只是几何变换，不再有可见的“空一拍”。
     */
    const val FadeInMillis = 130

    /**
     * 退出时长。这里故意压得远短于进入：
     *
     * 主页是 `LazyColumn`、设置页是整棵树都组合的 `Column`，两页同时存在时绘制成本翻倍。
     * 早先版本试过等长的 crossfade，真机上能看出一顿。把退出收到 120ms（约 7 帧）后，
     * 叠层只发生在进入动画的前半段，其余时间只有一页在画。
     */
    const val ExitMillis = 120

    /** 进入位移占屏宽的比例：1/8 在 412dp 屏上约 51dp，够看出层级又不拖影。 */
    const val TravelFraction = 1f / 8f
}

/** `SaveableStateProvider` 的 key 必须能进 Bundle，枚举统一转成名字。 */
private fun pageKey(page: Any): String =
    if (page is Enum<*>) page.name else page.toString()

/**
 * @param target 当前页面标识，变化即触发转场。
 * @param depthOf 页面层级，仅用于判断推入方向，数值本身不参与布局。
 */
@Composable
internal fun <T : Any> PageTransition(
    target: T,
    depthOf: (T) -> Int,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    // 每页的 rememberSaveable 状态（列表滚动位置、展开项）在切走时存起来、切回时还原，
    // 否则每次返回主页列表都会跳回顶部。
    val saveableStates = rememberSaveableStateHolder()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    AnimatedContent(
        targetState = target,
        modifier = modifier,
        label = label,
        transitionSpec = {
            val forward = depthOf(targetState) >= depthOf(initialState)
            val sign = if (forward != rtl) 1 else -1
            val enter: EnterTransition =
                fadeIn(tween(PageMotion.FadeInMillis, easing = PageMotion.enterEasing)) +
                    slideInHorizontally(
                        animationSpec = tween(PageMotion.EnterMillis, easing = PageMotion.enterEasing),
                        initialOffsetX = { width -> (width * PageMotion.TravelFraction * sign).toInt() },
                    )
            enter togetherWith fadeOut(tween(PageMotion.ExitMillis, easing = PageMotion.exitEasing))
        },
    ) { page ->
        saveableStates.SaveableStateProvider(pageKey(page)) {
            content(page)
        }
    }
}
