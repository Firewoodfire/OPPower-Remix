package com.oppower.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalDiagnosticsParsingTest {
    @Test
    fun parsesTaggedKeyValueLines() {
        val raw = """
            [prop] persist.sys.horae.enable=1
            [proc] horae=alive
            [uid] shell=2000
            [node] ufcs_active=<不可读>
        """.trimIndent()

        val rows = parseThermalDiagnostics(raw)

        assertEquals(4, rows.size)
        assertEquals(ThermalDiagnosticRow("属性 persist.sys.horae.enable", "1", readable = true), rows[0])
        assertEquals(ThermalDiagnosticRow("进程 horae", "alive", readable = true), rows[1])
        assertEquals(ThermalDiagnosticRow("身份 shell", "2000", readable = true), rows[2])
        // 尖括号占位表示读不到，不算正常值。
        assertEquals(ThermalDiagnosticRow("节点 ufcs_active", "<不可读>", readable = false), rows[3])
    }

    @Test
    fun emptyInputYieldsNoRows() {
        assertTrue(parseThermalDiagnostics("").isEmpty())
        assertTrue(parseThermalDiagnostics("   ").isEmpty())
    }

    @Test
    fun skipsLinesThatAreNotTaggedPairs() {
        val raw = """
            noise line
            [prop] no equals sign
            [node] pps_active=1
        """.trimIndent()

        val rows = parseThermalDiagnostics(raw)

        assertEquals(1, rows.size)
        assertEquals("节点 pps_active", rows[0].label)
        assertEquals("1", rows[0].value)
    }

    @Test
    fun moduleParametersAreMarkedSeparately() {
        val raw = """
            [params] total=2
            [param] max_voltage=9000
            [param] temp_target=42
        """.trimIndent()

        val rows = parseThermalDiagnostics(raw)

        assertEquals(3, rows.size)
        assertEquals("参数总数", rows[0].label)
        assertEquals("2", rows[0].value)
        assertFalse(rows[0].isParameter)
        assertEquals("max_voltage", rows[1].label)
        assertEquals("9000", rows[1].value)
        assertTrue(rows[1].isParameter)
        assertEquals("temp_target", rows[2].label)
        assertTrue(rows[2].isParameter)
    }

    /** 属性值里带 `=` 时只按第一个 `=` 切分，别把后半截吃掉。 */
    @Test
    fun keepsEqualsSignsInsideTheValue() {
        val rows = parseThermalDiagnostics("[prop] a=b=c")

        assertEquals(1, rows.size)
        assertEquals("b=c", rows[0].value)
    }
}
