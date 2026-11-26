package com.oppower.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单位自适应是限流能不能生效的关键：写错单位等于写错 1000 倍。
 * 这里锁死 µA / mA 两种平台的换算，以及“读不到就按 µA 保守写”的兜底。
 */
class ChargeLimitScaleTest {

    @Test
    fun microAmpValuesAreDetected() {
        assertTrue(ChargeLimitScale.microBased(3_000_000L))
        assertTrue(ChargeLimitScale.microBased(20_001L))
    }

    @Test
    fun milliAmpValuesAreNotMistakenForMicro() {
        assertFalse(ChargeLimitScale.microBased(3_000L))
        assertFalse(ChargeLimitScale.microBased(20_000L))
        assertFalse(ChargeLimitScale.microBased(0L))
    }

    @Test
    fun toMilliampConvertsMicroAmp() {
        assertEquals(3_000, ChargeLimitScale.toMilliamp(3_000_000L))
    }

    @Test
    fun toMilliampKeepsMilliAmpAsIs() {
        assertEquals(3_000, ChargeLimitScale.toMilliamp(3_000L))
    }

    @Test
    fun toMilliampRejectsNonPositive() {
        assertEquals(-1, ChargeLimitScale.toMilliamp(0L))
        assertEquals(-1, ChargeLimitScale.toMilliamp(-1L))
    }

    @Test
    fun toMilliampRejectsAbsurdMagnitude() {
        // 20 mA、500 A 都不该被当成合法上限
        assertEquals(-1, ChargeLimitScale.toMilliamp(20L))
        assertEquals(-1, ChargeLimitScale.toMilliamp(500_000_000L))
    }

    @Test
    fun toRawFollowsObservedUnit() {
        assertEquals("3000000", ChargeLimitScale.toRaw(3_000, observedRaw = 3_000_000L))
        assertEquals("3000", ChargeLimitScale.toRaw(3_000, observedRaw = 3_000L))
    }

    @Test
    fun toRawDefaultsToMicroWhenNothingObserved() {
        assertEquals("3000000", ChargeLimitScale.toRaw(3_000, observedRaw = 0L))
        assertEquals("3000000", ChargeLimitScale.toRaw(3_000, observedRaw = -1L))
    }

    @Test
    fun toRawClampsAndScalesTogether() {
        assertEquals("500000", ChargeLimitScale.toRaw(1, observedRaw = 0L))
        assertEquals("12000000", ChargeLimitScale.toRaw(99_999, observedRaw = 0L))
    }

    @Test
    fun plausibleMaMatchesNodeRange() {
        assertTrue(ChargeLimitScale.plausibleMa(500))
        assertTrue(ChargeLimitScale.plausibleMa(12_000))
        assertFalse(ChargeLimitScale.plausibleMa(499))
        assertFalse(ChargeLimitScale.plausibleMa(12_001))
        assertFalse(ChargeLimitScale.plausibleMa(0))
    }

    @Test
    fun onlyPositiveRawIsRestorable() {
        assertTrue(ChargeLimitScale.usableRaw(3_000_000))
        assertFalse("0 是异常态，写回去会把系统固定在 0", ChargeLimitScale.usableRaw(0))
        assertFalse(ChargeLimitScale.usableRaw(-1))
    }

    @Test
    fun readingIsConfirmedOnlyOnExactReadBack() {
        val ok = ChargeLimitReading(appliedMa = 3_000, raw = "3000000", readBackMa = 3_000)
        assertTrue(ok.confirmed)

        val drifted = ChargeLimitReading(appliedMa = 3_000, raw = "2900000", readBackMa = 2_900)
        assertFalse(drifted.confirmed)

        val missing = ChargeLimitReading(appliedMa = 3_000, raw = "")
        assertFalse(missing.confirmed)
    }

    @Test
    fun restorableOnlyWhenOppowerWroteIt() {
        assertTrue(ChargeLimitReading(appliedMa = 3_000, raw = "3000000").restorable)
        assertFalse("没写过就不该有值可恢复", ChargeLimitReading(appliedMa = 0, raw = "3000000").restorable)
        assertFalse(ChargeLimitReading(appliedMa = -1, raw = "3000000").restorable)
        assertFalse(ChargeLimitReading(appliedMa = 3_000, raw = "").restorable)
    }
}
