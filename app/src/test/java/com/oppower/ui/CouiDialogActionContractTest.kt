package com.oppower.ui

import androidx.compose.ui.graphics.Color
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 这条约束曾经让「充电记录 / 放电记录」一点就闪退：
 * `CouiDialogAction` 的 init 要求 Primary 必须自带配色，漏传会在组合期抛
 * `IllegalArgumentException`，而弹窗只在真正显示时才组合，所以平时看不出来。
 *
 * 这里把库的行为钉住：升级 COUI 时如果这条约束变了，测试会先响。
 * 生产代码统一走 `couiPrimaryAction(...)`，调用方拿不到漏传的机会。
 */
class CouiDialogActionContractTest {
    @Test
    fun primaryStyleWithoutHostColorsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CouiDialogAction(
                text = "关闭",
                onClick = {},
                style = CouiDialogActionStyle.Primary,
            )
        }
    }

    @Test
    fun primaryStyleWithHostColorsIsAccepted() {
        val action = CouiDialogAction(
            text = "关闭",
            onClick = {},
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(Color(0xFF1F7A1F)),
        )

        assertNotNull(action.colors)
    }

    /** Secondary 是默认样式，不强制配色——项目里多数弹窗走的都是这条。 */
    @Test
    fun secondaryStyleNeedsNoColors() {
        val action = CouiDialogAction(text = "取消", onClick = {})

        assertNotNull(action)
    }
}
