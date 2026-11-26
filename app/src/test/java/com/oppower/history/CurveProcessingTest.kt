package com.oppower.history

import com.oppower.model.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveProcessingTest {
    private val baseTime = 1_700_000_000_000L

    @Test
    fun llpowerSamplingIntervalsAreUsed() {
        assertEquals(3_000L, ChartHistoryStore.sampleIntervalMillis(isCharging = true, screenOn = true))
        assertEquals(5_000L, ChartHistoryStore.sampleIntervalMillis(isCharging = true, screenOn = false))
        assertEquals(180_000L, ChartHistoryStore.sampleIntervalMillis(isCharging = false, screenOn = true))
        assertEquals(180_000L, ChartHistoryStore.sampleIntervalMillis(isCharging = false, screenOn = false))
    }

    @Test
    fun emptyAndSinglePointInputsRemainSafe() {
        val empty = CurveProcessing.processPower(emptyList(), baseTime, baseTime + 1_000L)
        assertTrue(empty.points.isEmpty())
        assertTrue(empty.segments.isEmpty())

        val single = CurveProcessing.processPower(
            listOf(point(baseTime, power = 23.4f)),
            baseTime,
            baseTime + 1_000L,
        )
        assertEquals(1, single.points.size)
        assertEquals(1, single.segments.size)
    }

    @Test
    fun duplicateTimestampsAreAveraged() {
        val snapshot = CurveProcessing.processPower(
            listOf(
                point(baseTime + 10_000L, power = 10f),
                point(baseTime + 10_000L, power = 30f),
            ),
            baseTime,
            baseTime + 60_000L,
        )

        assertEquals(20f, snapshot.points.single().value, 0.001f)
    }

    @Test
    fun eightHoursAtThreeSecondCadenceStaysWithinRenderBudget() {
        val end = baseTime + CurveProcessing.HISTORY_WINDOW_MS
        val points = (0..9_600).map { index ->
            point(baseTime + index * 3_000L, power = 20f)
        }

        val snapshot = CurveProcessing.processPower(points, baseTime, end)

        assertTrue(snapshot.points.size <= CurveProcessing.MAX_RENDER_POINTS)
        assertTrue(snapshot.points.zipWithNext().all { (left, right) -> left.epochMillis < right.epochMillis })
    }

    @Test
    fun upwardPowerPeakIsRetainedInsideARobustBucket() {
        val snapshot = CurveProcessing.processPower(
            listOf(
                point(baseTime + 1_000L, power = 10f),
                point(baseTime + 2_000L, power = 10f),
                point(baseTime + 3_000L, power = 10f),
                point(baseTime + 4_000L, power = 100f),
            ),
            baseTime,
            baseTime + 30_000L,
        )

        assertTrue(snapshot.points.any { it.value > 10f })
    }

    @Test
    fun chargingZeroDropoutsAreNotRenderedAsRealDips() {
        val snapshot = CurveProcessing.processPower(
            listOf(
                point(baseTime + 1_000L, power = 20f),
                point(baseTime + 2_000L, power = 0f),
                point(baseTime + 3_000L, power = 21f),
            ),
            baseTime,
            baseTime + 10_000L,
        )

        assertTrue(snapshot.points.isNotEmpty())
        assertTrue(snapshot.points.all { it.value >= 15f })
    }

    @Test
    fun temperatureUsesMinuteBucketsAndSevenMinuteGapsSplitSegments() {
        val alignedMinute = baseTime - (baseTime % CurveProcessing.TEMPERATURE_BUCKET_MS)
        val firstBucket = CurveProcessing.processTemperature(
            listOf(
                point(alignedMinute + 1_000L, temperature = 30f),
                point(alignedMinute + 59_000L, temperature = 32f),
            ),
            alignedMinute,
            alignedMinute + 10L * 60_000L,
        )
        assertEquals(alignedMinute + 30_000L, firstBucket.points.single().epochMillis)
        assertEquals(31f, firstBucket.points.single().value, 0.001f)

        val split = CurveProcessing.processPower(
            listOf(
                point(baseTime, power = 20f),
                point(baseTime + CurveProcessing.CURVE_GAP_MS + 1_000L, power = 21f),
            ),
            baseTime,
            baseTime + CurveProcessing.CURVE_GAP_MS + 2_000L,
        )
        assertEquals(2, split.segments.size)
    }

    @Test
    fun residentHistoryKeepsOnlyTheEightHourChartWindow() {
        val end = baseTime + CurveProcessing.HISTORY_WINDOW_MS + 60_000L
        val points = listOf(
            point(baseTime, power = 10f),
            point(end - CurveProcessing.HISTORY_WINDOW_MS - 1_000L, power = 20f),
            point(end - CurveProcessing.HISTORY_WINDOW_MS, power = 30f),
            point(end, power = 40f),
        )

        val retained = CurveProcessing.historyWithinWindow(points, end)

        assertEquals(2, retained.size)
        assertEquals(end - CurveProcessing.HISTORY_WINDOW_MS, retained.first().epochMillis)
        assertEquals(end, retained.last().epochMillis)
    }

    @Test
    fun axesMatchLlpowerGridRules() {
        val power = CurveProcessing.resolvePowerAxis(
            listOf(CurvePoint(baseTime, 14.5f), CurvePoint(baseTime + 1L, 34.2f)),
        )
        assertEquals(10f, power.minimum, 0.001f)
        assertEquals(40f, power.maximum, 0.001f)
        assertEquals(10f, power.tickStep, 0.001f)

        val temperature = CurveProcessing.resolveTemperatureAxis(
            listOf(CurvePoint(baseTime, 30f), CurvePoint(baseTime + 1L, 31f)),
        )
        assertTrue(temperature.maximum - temperature.minimum >= 5f)
    }

    /**
     * 温度轴的刻度数由画面高度决定，不由数据跨度决定。
     *
     * beta50 之前是「1°C 一格」，17°C 的跨度会排出 18 行刻度，7sp 的字在 190dp
     * 的曲线框里叠成一团（真机截图里底部几行直接糊在一起）。这里把「格数不超过 7」
     * 钉死，避免以后又按度数推导步长。
     */
    @Test
    fun temperatureAxisKeepsTickLabelsReadable() {
        val axis = CurveProcessing.resolveTemperatureAxis(
            listOf(CurvePoint(baseTime, 24f), CurvePoint(baseTime + 1L, 41f)),
        )
        assertEquals(5f, axis.tickStep, 0.001f)
        assertEquals(20f, axis.minimum, 0.001f)
        assertEquals(45f, axis.maximum, 0.001f)
        assertTrue("刻度数 ${axis.tickCount} 太密", axis.tickCount in 3..7)
    }

    @Test
    fun temperatureAxisStaysReadableForNarrowAndWideSpans() {
        val narrow = CurveProcessing.resolveTemperatureAxis(
            listOf(CurvePoint(baseTime, 24f), CurvePoint(baseTime + 1L, 25f)),
        )
        assertTrue("窄跨度刻度数 ${narrow.tickCount} 太密", narrow.tickCount in 2..7)

        val wide = CurveProcessing.resolveTemperatureAxis(
            listOf(CurvePoint(baseTime, -20f), CurvePoint(baseTime + 1L, 120f)),
        )
        assertTrue("宽跨度刻度数 ${wide.tickCount} 太密", wide.tickCount in 2..7)
    }

    private fun point(
        timestamp: Long,
        power: Float = 20f,
        temperature: Float = 35f,
    ) = ChartPoint(
        epochMillis = timestamp,
        powerW = power,
        temperatureC = temperature,
        levelPercent = 50,
        acOnline = true,
    )
}
