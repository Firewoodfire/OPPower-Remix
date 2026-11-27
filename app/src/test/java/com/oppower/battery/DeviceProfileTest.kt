package com.oppower.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun cellCountIsReadFromBigEndianDeviceTreeBytes() {
        // `oplus,batt_num = <2>` 在 dtb 里是 4 字节大端。
        assertEquals(2, parseCellCount(byteArrayOf(0, 0, 0, 2)))
        assertEquals(1, parseCellCount(byteArrayOf(0, 0, 0, 1)))
        // 部分固件只写 1 字节。
        assertEquals(1, parseCellCount(byteArrayOf(1)))
        assertEquals(2, parseCellCount(byteArrayOf(2)))
    }

    @Test
    fun cellCountIgnoresTrailingPaddingAndRejectsGarbage() {
        assertEquals(2, parseCellCount(byteArrayOf(0, 2, 0, 0)))
        // 全零表示属性存在但没填值。
        assertNull(parseCellCount(byteArrayOf(0, 0, 0, 0)))
        // 超出 1…4 的字节多半是错位读到的别的属性，不能当结论。
        assertNull(parseCellCount(byteArrayOf(0, 0, 0, 9)))
        assertNull(parseCellCount(byteArrayOf(0x40)))
        assertNull(parseCellCount(ByteArray(0)))
        assertNull(parseCellCount(null))
    }

    @Test
    fun powerSupplyLayoutSeparatesSingleAndDualCell() {
        assertEquals(
            CellStructure.DUAL,
            inferCellStructure(setOf("battery", "usb", "battery2"), mainHasCapacity = false),
        )
        assertEquals(
            CellStructure.DUAL,
            inferCellStructure(setOf("battery", "usb", "main"), mainHasCapacity = true),
        )
        assertEquals(
            CellStructure.SINGLE,
            inferCellStructure(setOf("battery", "usb", "main"), mainHasCapacity = false),
        )
        assertEquals(
            CellStructure.SINGLE,
            inferCellStructure(setOf("battery", "usb", "wired"), mainHasCapacity = false),
        )
        assertEquals(
            CellStructure.UNKNOWN,
            inferCellStructure(emptySet(), mainHasCapacity = false),
        )
    }

    @Test
    fun osLabelPrefersRomVersionAndFallsBackToAndroid() {
        assertEquals("ColorOS 16", formatOsLabel("PKX110_16.0.2.400(CN01)", "16"))
        assertEquals("ColorOS 15", formatOsLabel("V15.0.1", "15"))
        assertEquals("ColorOS 14", formatOsLabel("14.0.0", "14"))
        // ROM 串里没有版本号时用 Android 版本兜底。
        assertEquals("ColorOS 13", formatOsLabel("unknown", "13"))
        assertEquals("ColorOS", formatOsLabel("", ""))
        // 一位数的 Android 版本也照常兜底，不做额外假设。
        assertEquals("ColorOS 9", formatOsLabel("", "9"))
    }

    @Test
    fun displayNameFallsBackWhenModelIsMissing() {
        val known = DeviceProfile(model = "PKX110", osLabel = "ColorOS 16")
        assertEquals("PKX110 · ColorOS 16", known.displayName)

        val unknown = DeviceProfile()
        assertEquals("未知机型 · ColorOS", unknown.displayName)
    }

    @Test
    fun cellLabelAndDualFlagFollowStructure() {
        val dual = DeviceProfile(cellStructure = CellStructure.DUAL, cellCount = 2)
        assertTrue(dual.isDualCell)
        assertEquals("双电芯", dual.cellLabel)

        val single = DeviceProfile(cellStructure = CellStructure.SINGLE, cellCount = 1)
        assertFalse(single.isDualCell)
        assertEquals("单电芯", single.cellLabel)

        assertEquals("未识别", DeviceProfile().cellLabel)
    }
}
