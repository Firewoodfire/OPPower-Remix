package com.oppower.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeInspectorTest {
    private val dump = """
        [dir] battery
        type=Battery
        voltage_now=4385000
        charge_full=4820000
        charge_full_design=5000000
        cycle_count=128
        temp=365
        [dir] usb
        type=USB
        online=1
        voltage_max=11000000
        current_max=6000000
        usb_type=USB_PD_PPS
    """.trimIndent()

    @Test
    fun parsesEveryNodeAndIgnoresBrokenLines() {
        val nodes = PowerSupplyDump.parse(
            """
            [dir] usb
            online=1
            broken line without separator
            voltage_max=9000000
            """
                .trimIndent(),
        )
        assertEquals(1, nodes.size)
        assertEquals("usb", nodes[0].name)
        assertEquals(9_000_000L, nodes[0].long("voltage_max"))
        assertTrue(nodes[0].online)
    }

    @Test
    fun detectsPpsCapabilityFromMicroUnits() {
        val capability = ChargeInspector.detectCharger(PowerSupplyDump.parse(dump))
        assertNotNull(capability)
        assertEquals("USB PD PPS", capability!!.protocolLabel)
        assertEquals(11_000, capability.maxVoltageMv)
        assertEquals(6_000, capability.maxCurrentMa)
        assertEquals(66f, capability.maxPowerW, 0.01f)
        assertTrue(capability.isFastCharge)
    }

    @Test
    fun ignoresBatteryNodeWhenLookingForCharger() {
        val onlyBattery = PowerSupplyDump.parse(
            """
            [dir] battery
            type=Battery
            online=1
            voltage_max=4400000
            current_max=3000000
            """
                .trimIndent(),
        )
        assertNull(ChargeInspector.detectCharger(onlyBattery))
    }

    @Test
    fun computesBatteryHealthFromChargeCounters() {
        val health = ChargeInspector.detectHealth(PowerSupplyDump.parse(dump))
        assertNotNull(health)
        assertEquals(5_000, health!!.designMah)
        assertEquals(4_820, health.fullMah)
        assertEquals(96, health.percent)
        assertEquals(128, health.cycleCount)
        assertEquals(36.5f, health.temperatureC!!, 0.01f)
    }

    @Test
    fun stageFollowsVoltageAndCurrentBoundaries() {
        assertEquals(
            ChargeStage.DISCONNECTED,
            ChargeInspector.detectStage(false, 50, 3_000, 4_000, 2, null),
        )
        assertEquals(
            ChargeStage.FULL,
            ChargeInspector.detectStage(true, 100, 0, 4_400, 5, null),
        )
        assertEquals(
            ChargeStage.TRICKLE,
            ChargeInspector.detectStage(true, 97, 120, 4_380, 2, 600_000),
        )
        assertEquals(
            ChargeStage.CONSTANT_VOLTAGE,
            ChargeInspector.detectStage(true, 90, 2_000, 4_400, 2, 600_000),
        )
        assertEquals(
            ChargeStage.HANDSHAKE,
            ChargeInspector.detectStage(true, 40, 2_000, 4_000, 2, 5_000),
        )
        assertEquals(
            ChargeStage.CONSTANT_CURRENT,
            ChargeInspector.detectStage(true, 40, 6_000, 4_100, 2, 120_000),
        )
    }

    @Test
    fun doubleCellThresholdDelaysConstantVoltage() {
        assertEquals(
            ChargeStage.CONSTANT_CURRENT,
            ChargeInspector.detectStage(true, 80, 3_000, 4_500, 2, 600_000, doubleCell = true),
        )
    }

    @Test
    fun estimatesMinutesToFullFromMedianSlope() {
        val now = 1_000_000L
        val points = listOf(
            now to 40,
            now + 120_000 to 42,
            now + 240_000 to 44,
            now + 360_000 to 46,
        )
        // 1 %/min → 剩余 54 % 需要 54 分钟
        assertEquals(54, ChargeInspector.estimateMinutesToFull(46, points))
    }

    @Test
    fun refusesToEstimateWithoutUsableSlope() {
        assertNull(ChargeInspector.estimateMinutesToFull(50, emptyList()))
        assertNull(ChargeInspector.estimateMinutesToFull(100, listOf(1L to 99, 2L to 100)))
        val flat = listOf(0L to 50, 60_000L to 50, 120_000L to 50)
        assertNull(ChargeInspector.estimateMinutesToFull(50, flat))
    }

    @Test
    fun stabilityFallsWithPowerNoise() {
        assertEquals(100, ChargeInspector.stabilityPercent(emptyList()))
        val steady = List(10) { 20f }
        assertEquals(100, ChargeInspector.stabilityPercent(steady))
        val noisy = listOf(5f, 35f, 8f, 32f, 6f, 34f, 7f, 33f, 9f, 30f)
        assertTrue(ChargeInspector.stabilityPercent(noisy) < 40)
    }

    @Test
    fun efficiencyComparesChargedEnergyWithInput() {
        // 30 分钟、平均 20 W 输入约 10 Wh；充入 2000 mAh ≈ 7.7 Wh → 约 77%
        assertEquals(77, ChargeInspector.chargeEfficiency(2_000, 20f, 30f))
        assertNull(ChargeInspector.chargeEfficiency(null, 20f, 30f))
        assertNull(ChargeInspector.chargeEfficiency(0, 20f, 30f))
        assertNull(ChargeInspector.chargeEfficiency(2_000, 0f, 30f))
    }

    @Test
    fun flagsWeakLinkWhenChargerIsCapableButPowerIsLow() {
        val insight = ChargeInspector.analyze(
            dump = dump,
            acOnline = true,
            levelPercent = 55,
            currentMa = 1_200,
            voltageMv = 4_150,
            powerW = 5f,
            batteryTempC = 33f,
            batteryStatus = 2,
            pluggedForMillis = 180_000,
        )
        assertTrue(insight.anomalies.any { it.contains("充电器支持") })
        assertEquals(7, insight.utilizationPercent)
        assertEquals(ChargeStage.CONSTANT_CURRENT, insight.stage)
    }

    @Test
    fun reportsZeroCurrentAfterPlugIn() {
        val insight = ChargeInspector.analyze(
            dump = dump,
            acOnline = true,
            levelPercent = 60,
            currentMa = 0,
            voltageMv = 4_150,
            powerW = 0f,
            batteryTempC = 30f,
            batteryStatus = 2,
            pluggedForMillis = 60_000,
        )
        assertTrue(insight.anomalies.any { it.contains("电流接近 0") })
    }

    @Test
    fun idleStateProducesNoAnomalies() {
        val insight = ChargeInspector.analyze(
            dump = "",
            acOnline = false,
            levelPercent = 70,
            currentMa = -500,
            voltageMv = 4_000,
            powerW = -2f,
            batteryTempC = 30f,
            batteryStatus = 3,
            pluggedForMillis = null,
        )
        assertEquals(ChargeStage.DISCONNECTED, insight.stage)
        assertTrue(insight.anomalies.isEmpty())
        assertTrue(insight.isHealthy)
    }

    @Test
    fun highTemperatureIsSurfaced() {
        val insight = ChargeInspector.analyze(
            dump = dump,
            acOnline = true,
            levelPercent = 50,
            currentMa = 5_000,
            voltageMv = 4_200,
            powerW = 21f,
            batteryTempC = 43.5f,
            batteryStatus = 2,
            pluggedForMillis = 300_000,
        )
        assertTrue(insight.anomalies.any { it.contains("接近降档阈值") })
    }
}
