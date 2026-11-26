package com.oppower.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大标题页的返回键浮层与搜索框度量。
 *
 * `CollapsingTopAppBar` 的标题槽只收 `title: String`，没有导航图标位，返回键只能
 * 做成浮层压在标题行左侧；它得跟着标题一起淡出，否则滚到底后屏幕左上角会孤零零
 * 剩一个返回箭头。这套数值是量出来的，写进测试免得下次改回来。
 */
class LargeTitleHeaderTest {

    /** 还没滚：返回键完全可见。 */
    @Test
    fun backButtonIsOpaqueAtTop() {
        assertEquals(1f, backButtonAlpha(scrollValue = 0, density = 1f), 0.001f)
    }

    /** 滚过顶栏高度（110dp）的一半：淡到一半。 */
    @Test
    fun backButtonFadesHalfwayThroughTheHeader() {
        assertEquals(0.5f, backButtonAlpha(scrollValue = 55, density = 1f), 0.001f)
    }

    /** 顶栏退干净后返回键也消失，不能留在原地。 */
    @Test
    fun backButtonIsGoneOnceHeaderLeft() {
        assertEquals(0f, backButtonAlpha(scrollValue = 110, density = 1f), 0.001f)
    }

    /** 继续往下滚不能出负透明度。 */
    @Test
    fun backButtonClampsAtZero() {
        assertEquals(0f, backButtonAlpha(scrollValue = 10_000, density = 3f), 0.001f)
    }

    /** 边缘弹簧会给负滚动量，此时按完全可见处理。 */
    @Test
    fun backButtonStaysOpaqueOnOverscroll() {
        assertEquals(1f, backButtonAlpha(scrollValue = -60, density = 3f), 0.001f)
    }

    /** 密度 3 时 165px 才等于 55dp。 */
    @Test
    fun backButtonConvertsPixelsToDp() {
        assertEquals(0.5f, backButtonAlpha(scrollValue = 165, density = 3f), 0.001f)
    }

    /** ColorOS 实测 47dp，比 COUI 默认的 40dp 高；写死在这里防止被顺手改回 40。 */
    @Test
    fun searchFieldMatchesColorOsHeight() {
        assertEquals(47f, SearchFieldHeight.value, 0.01f)
    }

    /** 24dp 圆角，不是全胶囊：全胶囊会取到高度的一半，ColorOS 明显没有这么圆。 */
    @Test
    fun searchFieldUsesRoundedRectNotPill() {
        assertEquals(24f, SearchFieldCorner.value, 0.01f)
        assertTrue(SearchFieldCorner < SearchFieldHeight)
    }
}
