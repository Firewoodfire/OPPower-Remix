package com.oppower.controller

import com.oppower.model.BaseTier
import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerPhase
import com.oppower.model.RuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingControllerTest {
    @Test
    fun initialApplyCalculatesTargetGearImmediately() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)

        val first = controller.evaluate(settings, battery())
        val second = controller.evaluate(settings, battery())

        assertEquals(ControllerPhase.ACTIVE, first.phase)
        assertEquals(0, first.desiredGear)
        assertEquals(0, first.observedGear)
        assertEquals(0, first.calculatedGear)
        assertTrue(first.verified)
        assertEquals(listOf(-8), gateway.writes)




        assertEquals(1, gateway.refreshRequests)
        assertEquals(0, second.observedGear)
        assertEquals(listOf(-8), gateway.writes)


        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun baseGearSixTargetZeroWritesMinusSix() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 6, baseGear = 6)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(6, status.baseGear)
        assertEquals(-6, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertEquals(listOf(-6), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun cachedPolicyGearDoesNotAffectCalculatedGear() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 9, baseGear = 8)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(8, status.baseGear)
        assertEquals(-8, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertTrue(status.message.contains("计算档位=0"))
    }

    @Test
    fun baseGearChangeRecalculatesCosaEvenWithStaleCachedGear() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 7, baseGear = 7)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(settings, battery())
        gateway.writes.clear()
        val refreshBefore = gateway.refreshRequests
        gateway.baseGear = 6

        val status = controller.evaluate(settings, battery())

        assertEquals(6, status.baseGear)
        assertEquals(-6, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertEquals(listOf(-6), gateway.writes)
        assertEquals(refreshBefore + 1, gateway.refreshRequests)
        assertTrue(status.verified)
    }

    @Test
    fun baseTierChangeRecalculatesCosaWithoutIntermediaryZero() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        val controller = ChargingController(gateway)

        controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())
        gateway.writes.clear()
        val refreshBefore = gateway.refreshRequests

        val status = controller.evaluate(settings(BaseTier.GENTLE), battery())

        assertEquals(2, status.desiredGear)
        assertEquals(-6, status.appliedCosa)
        assertEquals(2, status.calculatedGear)
        assertEquals(listOf(-6), gateway.writes)
        assertFalse(gateway.writes.contains(0))


        assertEquals(refreshBefore + 1, gateway.refreshRequests)
    }


    @Test
    fun maxToGentleAndBackToMaxRestoresGearImmediately() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8)
        val controller = ChargingController(gateway)

        controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())
        gateway.writes.clear()
        gateway.refreshRequests = 0

        val gentle = controller.evaluate(settings(BaseTier.GENTLE), battery())
        gateway.writes.clear()
        gateway.refreshRequests = 0
        val restored = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(2, gentle.desiredGear)
        assertEquals(0, restored.desiredGear)
        assertEquals(0, restored.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertTrue(restored.verified)
    }

    @Test
    fun externalCosaDriftIsCorrectedFromBaseGear() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)
        assertTrue(controller.evaluate(settings, battery()).verified)

        gateway.writes.clear()
        gateway.refreshRequests = 0
        gateway.cosa = -6

        val corrected = controller.evaluate(settings, battery())
        controller.evaluate(settings, battery())

        assertEquals(-8, corrected.appliedCosa)
        assertEquals(0, corrected.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun reenablingAfterOffWritesTargetCosaWithoutZero() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        val controller = ChargingController(gateway)
        val enabled = settings(BaseTier.MAX_OUTPUT)
        controller.evaluate(enabled, battery())
        controller.evaluate(enabled.copy(masterEnabled = false), battery())
        gateway.writes.clear()
        gateway.refreshRequests = 0

        val status = controller.evaluate(enabled, battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(0, status.desiredGear)
        assertEquals(listOf(-8), gateway.writes)
        assertFalse(gateway.writes.contains(0))
        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun disablingMasterSwitchRestoresCosaZero() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        val controller = ChargingController(gateway)
        controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())
        gateway.writes.clear()

        val status = controller.evaluate(
            settings(BaseTier.MAX_OUTPUT).copy(masterEnabled = false),
            battery(),
        )

        assertEquals(ControllerPhase.DISABLED, status.phase)
        assertEquals(0, gateway.cosa)
        assertEquals(listOf(0), gateway.writes)


    }

    @Test
    fun refreshFailureDoesNotRewriteSameCalculatedTarget() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8, refreshSucceeds = false)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)

        val first = controller.evaluate(settings, battery())
        val second = controller.evaluate(settings, battery())

        assertTrue(first.message.contains("系统重算触发失败"))
        assertEquals(0, first.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertEquals(0, second.calculatedGear)
    }

    @Test
    fun readCurrentGearUsesOnlyBaseAndCosa() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 8, baseGear = 8)
        val controller = ChargingController(gateway)

        assertEquals(0, controller.readCurrentGear())
        val resolved = controller.readResolvedGear()
        assertEquals(0, resolved.gear)
        assertEquals(8, resolved.baseGear)
        assertEquals(-8, resolved.cosa)
        assertEquals("系统 Thermal Levels + cosa_ctc_value（忽略 policy.gear 缓存）", resolved.source)
    }

    @Test
    fun unreachableTargetClampsToCosaBoundaryWithoutError() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 20, baseGear = 20)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(listOf(-10), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertEquals(-10, status.appliedCosa)
        assertEquals(10, status.calculatedGear)
        assertFalse(status.verified)
        assertTrue(status.message.contains("目标 gear 0 不可达"))
    }

    @Test
    fun baseGearTenWritesMinusTenAndReachesGearZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 10, baseGear = 10)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(listOf(-10), gateway.writes)
        assertEquals(-10, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertTrue(status.verified)
        assertTrue(status.message.contains("计算档位已达到 0"))
    }

    @Test
    fun disconnectRestoresCosaOnly() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8)
        val controller = ChargingController(gateway)
        val enabled = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(enabled, battery())
        val status = controller.evaluate(
            enabled,
            battery().copy(acOnline = false, status = 3),
        )

        assertEquals(ControllerPhase.WAITING_FOR_POWER, status.phase)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun gearZeroWritesCosaOnceWithoutUnverifiedBroadcast() {
        val gateway = FakeChargingGateway(
            cosa = 0,
            gear = 8,
            policyCategory = "common_config",
            policyChargeVal = 0,
            policySpeedAdd = -2,
        )
        val controller = ChargingController(gateway)
        val enabled = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(enabled, battery())
        controller.evaluate(enabled, battery())

        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
    }




    @Test
    fun levelZeroStartsImmediatelyAndPollsOnlyEveryThreeSeconds() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings()

        val first = controller.evaluate(settings, battery())
        assertEquals(ControllerPhase.ACTIVE, first.phase)
        assertTrue(first.levelZeroMode)
        assertEquals(0, first.desiredGear)
        assertEquals(listOf(0), gateway.writes)
        assertEquals(listOf(0), gateway.nativeLevelRequests)
        assertEquals(0, gateway.policyReads)
        assertEquals(0, gateway.statusReads)

        now = 1_999L
        controller.evaluate(settings, battery())
        now = 3_999L
        controller.evaluate(settings, battery())
        assertEquals(listOf(0), gateway.nativeLevelRequests)

        now = 4_000L
        controller.evaluate(settings, battery())
        assertEquals(listOf(0, 0), gateway.nativeLevelRequests)
        assertEquals(listOf(0), gateway.writes)
    }

    @Test
    fun levelZeroFailureIsReportedAndRetriedAfterThreeSeconds() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0).apply {
            nativeLevelRequestSucceeds = false
        }
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings()

        val first = controller.evaluate(settings, battery())
        now = 2_000L
        val secondAtTwoSeconds = controller.evaluate(settings, battery())
        now = 4_000L
        val retry = controller.evaluate(settings, battery())

        assertEquals(ControllerPhase.ERROR, first.phase)
        assertEquals(ControllerPhase.ERROR, secondAtTwoSeconds.phase)
        assertEquals(listOf(0), gateway.nativeLevelRequests.take(1))
        assertEquals(listOf(0, 0), gateway.nativeLevelRequests)
        assertTrue(first.message.contains("3 秒后重试"))
        assertTrue(retry.message.contains("3 秒后重试"))
    }

    @Test
    fun levelZeroStartsWithMasterSwitchOff() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings().copy(masterEnabled = false)

        val status = controller.evaluate(settings, battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0), gateway.nativeLevelRequests)
        assertEquals(0, gateway.policyReads)
        assertEquals(0, gateway.statusReads)
    }

    @Test
    fun turningMasterOffDoesNotStopLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings()

        assertEquals(ControllerPhase.ACTIVE, controller.evaluate(settings, battery()).phase)
        now = 4_000L
        val status = controller.evaluate(settings.copy(masterEnabled = false), battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0, 0), gateway.nativeLevelRequests)
    }

    @Test
    fun turningOffLevelZeroWhileMasterIsOffRestoresSafeLevel() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings().copy(masterEnabled = false)

        controller.evaluate(settings, battery())
        now = 2_000L
        val status = controller.evaluate(settings.copy(nativeLevelZeroEnabled = false), battery())

        assertEquals(ControllerPhase.DISABLED, status.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun socPauseRestoresNativeLevelTwoFromLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery())
        val status = controller.evaluate(settings, battery().copy(levelPercent = 80))

        assertEquals(ControllerPhase.SOC_PAUSED, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun disconnectRestoresNativeLevelTwoFromLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery())
        val status = controller.evaluate(settings, battery().copy(acOnline = false, status = 3))

        assertEquals(ControllerPhase.WAITING_FOR_POWER, status.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun fullChargeRestoresNativeLevelTwoFromLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery())
        val status = controller.evaluate(settings, battery().copy(levelPercent = 100, status = 5))

        assertEquals(ControllerPhase.FULL_PAUSED, status.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun screenOffPausesLevelZeroWhenScreenOffBoostIsDisabled() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery(), screenOn = true)
        val paused = controller.evaluate(settings, battery(), screenOn = false)
        controller.evaluate(settings, battery(), screenOn = false)

        assertEquals(ControllerPhase.SCREEN_OFF_PAUSED, paused.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun screenOffKeepsLevelZeroWhenScreenOffBoostIsEnabled() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings().copy(screenOffBoostEnabled = true)

        val status = controller.evaluate(settings, battery(), screenOn = false)

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0), gateway.nativeLevelRequests)
    }

    @Test
    fun failedExplicitRestoreDoesNotReportSuccess() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        controller.evaluate(levelZeroSettings(), battery())
        gateway.nativeLevelRequestSucceeds = false

        val status = controller.restoreAll(forceNativeRestore = true)

        assertEquals(ControllerPhase.ERROR, status.phase)
        assertTrue(status.levelZeroMode)
        assertTrue(status.message.contains("恢复系统默认失败"))
    }

    @Test
    fun failedFreshSafetyRestoreStaysPendingForNextControlCycle() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0).apply {
            nativeLevelRequestSucceeds = false
        }
        val controller = ChargingController(gateway)

        val failed = controller.restoreAll(forceNativeRestore = true)
        gateway.nativeLevelRequestSucceeds = true
        val retried = controller.evaluate(RuntimeSettings(), battery())

        assertEquals(ControllerPhase.ERROR, failed.phase)
        assertTrue(failed.levelZeroMode)
        assertEquals(ControllerPhase.DISABLED, retried.phase)
        assertEquals(listOf(2, 2), gateway.nativeLevelRequests)
    }

    @Test
    fun explicitRestoreAlsoWritesSafeNativeLevel() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)

        val status = controller.restoreAll(forceNativeRestore = true)

        assertEquals(ControllerPhase.DISABLED, status.phase)
        assertEquals(listOf(2), gateway.nativeLevelRequests)
    }

    @Test
    fun diagnosticPolicyAndThermalStatusAreCached() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(settings, battery())
        gateway.policyReads = 0
        gateway.statusReads = 0

        now = 2_000L
        controller.evaluate(settings, battery())
        now = 3_000L
        controller.evaluate(settings, battery())

        assertEquals(1, gateway.policyReads)
        // 上一轮写过 cosa → 触发了热控重算 → 缓存被主动失效，所以 2 秒时会真读一次；
        // 3 秒时落在 2 秒缓存窗口内，直接复用。两次 evaluate 只花了一次 Binder 往返。
        assertEquals(1, gateway.statusReads)

        now = 5_001L
        controller.evaluate(settings, battery())

        assertEquals(2, gateway.policyReads)
        assertEquals(2, gateway.statusReads)
    }

    // ---- 二级限流：写入 / 恢复 / 漂移重写 ----

    private fun limitedSettings(manualMa: Int = 3_000) = settings(BaseTier.MAX_OUTPUT)
        .copy(chargeCurrentLimitEnabled = true, chargeCurrentLimitMa = manualMa)

    @Test
    fun chargeLimitIsWrittenOnceAndThenRestoredOnDisable() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val on = limitedSettings()

        val status = controller.evaluate(on, battery())
        val applied = status.appliedCurrentLimitMa
        assertTrue("开启后必须真的写了节点", applied > 0)
        assertEquals(listOf(applied), gateway.limitWrites)
        assertEquals(applied, gateway.limitNodeMa)
        assertEquals(true, status.chargeLimitSupported)

        now = 2_000L
        val off = controller.evaluate(on.copy(chargeCurrentLimitEnabled = false), battery())
        assertEquals(0, off.appliedCurrentLimitMa)
        assertEquals("必须写回系统原来的 3000mA", 3_000, gateway.limitNodeMa)
        assertEquals("恢复只应发生一次", 1, gateway.limitWrites.size)
    }

    @Test
    fun unsupportedNodeIsRememberedAndDoesNotBreakGearControl() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8).apply {
            limitNodePresent = false
        }
        val controller = ChargingController(gateway, nowMillis = { 1_000L })

        val status = controller.evaluate(limitedSettings(), battery())

        assertEquals(false, status.chargeLimitSupported)
        assertTrue("没有节点时不该产生写入", gateway.limitWrites.isEmpty())
        // 档位控制不受影响：cosa 仍然照常下发。
        assertTrue("cosa 必须继续工作", gateway.writes.isNotEmpty())
    }

    @Test
    fun driftingNodeIsRewrittenInsteadOfSilentlyLost() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val on = limitedSettings()

        val applied = controller.evaluate(on, battery()).appliedCurrentLimitMa
        assertTrue(applied > 0)

        // 系统热控把节点刷回默认，控制器自己记的数还停在 applied。
        gateway.limitNodeMa = 3_000
        now = 12_000L
        val status = controller.evaluate(on, battery())

        assertEquals(applied, status.appliedCurrentLimitMa)
        assertEquals("节点必须被重写回目标值", applied, gateway.limitNodeMa)
        assertEquals(listOf(applied, applied), gateway.limitWrites)
    }

    @Test
    fun steadyNodeIsNotRewrittenEverySecond() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val on = limitedSettings()

        controller.evaluate(on, battery())
        repeat(5) {
            now += 1_000L
            controller.evaluate(on, battery())
        }

        assertEquals("稳态不应该反复写节点", 1, gateway.limitWrites.size)
    }

    @Test
    fun unpluggingRestoresTheNode() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val on = limitedSettings()

        assertTrue(controller.evaluate(on, battery()).appliedCurrentLimitMa > 0)

        now = 2_000L
        controller.evaluate(on, battery().copy(acOnline = false, status = 3))

        assertEquals(3_000, gateway.limitNodeMa)
    }

    @Test
    fun snappedNodeDoesNotCauseEndlessRewrites() {
        // 平台把 3000 吸附成 2900：回读永远不等于目标，但也不该被当成"漂移"。
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8).apply {
            limitSnapTo = { ma -> ma / 100 * 100 - 100 }
        }
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val on = limitedSettings()

        val first = controller.evaluate(on, battery())
        val writesAfterFirst = gateway.limitWrites.size
        assertEquals("回读值必须如实反映被吸附后的值", gateway.limitNodeMa, first.observedCurrentLimitMa)

        repeat(6) {
            now += 10_000L
            controller.evaluate(on, battery())
        }

        assertEquals("被吸附的节点不应该被反复重写", writesAfterFirst, gateway.limitWrites.size)
    }

    @Test
    fun zeroedNodeIsRescuedToASafeValue() {
        // 平台把写入落成 0（= 禁止充电）：必须尽力写回一个能充电的值，而不是直接放弃。
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8).apply {
            limitSnapTo = { 0 }
        }
        val controller = ChargingController(gateway, nowMillis = { 1_000L })

        val status = controller.evaluate(limitedSettings(), battery())

        assertEquals("归零后必须被识别为不可用", false, status.chargeLimitSupported)
        assertTrue("必须尝试写回安全值", gateway.limitWrites.size >= 2)
        assertTrue(
            "最后一次写入不能是 0",
            (gateway.limitWrites.lastOrNull() ?: 0) >= 1_000,
        )
    }

    private fun settings(baseTier: BaseTier) = RuntimeSettings(
        masterEnabled = true,
        baseTier = baseTier,
    )

    // ---- 温度驱动的升功率限幅 ----

    @Test
    fun temperatureRecoveryRaisesPowerOneGearAtATime() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = settings(BaseTier.GENTLE)

        // 42.5°C：规则表给出最保守的 gear 5，首次应用不受限幅。
        val hot = controller.evaluate(settings, battery(temperatureC = 42.5f))
        assertEquals(5, hot.desiredGear)
        assertEquals(-3, gateway.writes.last()) // baseGear 8 + cosa -3 = gear 5

        // 温度回落到 40°C：规则表直接给 gear 2，但一次只能升一档，先停在 4。
        val cooling = controller.evaluate(settings, battery(temperatureC = 40f))
        assertTrue("跨多档升功率时必须说明是逐档回升", cooling.message.contains("逐档回升"))
        assertEquals("确认期间不能提前写更高功率", -3, gateway.writes.last())

        // 过了升档确认时间，才真正走到 gear 4。
        now += settings.temperatureUpshiftSeconds * 1_000L + 1
        val step = controller.evaluate(settings, battery(temperatureC = 40f))
        assertEquals(4, step.desiredGear)
        assertEquals(-4, gateway.writes.last())
    }

    @Test
    fun temperatureRiseLowersPowerInOneStep() {
        // 降功率必须一步到位：多等一档就是多烧几十秒。
        val gateway = FakeChargingGateway(cosa = 0, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = settings(BaseTier.GENTLE)

        assertEquals(2, controller.evaluate(settings, battery(temperatureC = 40f)).desiredGear)
        assertEquals(-6, gateway.writes.last())

        controller.evaluate(settings, battery(temperatureC = 42.5f))
        now += settings.temperatureDownshiftSeconds * 1_000L + 1
        val downshifted = controller.evaluate(settings, battery(temperatureC = 42.5f))

        assertEquals("升到 gear 5 必须一次完成，不能被限幅拆成 3→4→5", 5, downshifted.desiredGear)
        assertEquals(-3, gateway.writes.last())
    }

    private fun levelZeroSettings(baseTier: BaseTier = BaseTier.MAX_OUTPUT) =
        settings(baseTier).copy(nativeLevelZeroEnabled = true)

    private fun battery(temperatureC: Float = 30f, levelPercent: Int = 30) = BatterySnapshot(
        temperatureC = temperatureC,
        levelPercent = levelPercent,
        status = 2,
        acOnline = true,
    )

    private class FakeChargingGateway(
        var cosa: Int,
        var gear: Int,
        var baseGear: Int = 8,
        var refreshSucceeds: Boolean = true,


        var policyCategory: String = "test_scene",
        var policyChargeVal: Int? = 12,
        var policySpeedAdd: Int? = 1,
    ) : ChargingGateway {
        override val isAvailable = true
        override val hasPermission = true
        val writes = mutableListOf<Int>()

        var refreshRequests = 0
        var policyReads = 0
        var statusReads = 0
        var nativeLevelRequestSucceeds = true
        val nativeLevelRequests = mutableListOf<Int>()

        /** 模拟 sysfs 上“当前生效的电流上限”，默认是系统自己的 3000mA。 */
        var limitNodeMa = 3_000
        /** 模拟平台把写入值吸附到自己的档位表（null = 原样接受）。 */
        var limitSnapTo: ((Int) -> Int)? = null
        var limitNodePresent = true
        var limitWriteFails = false
        val limitWrites = mutableListOf<Int>()
        var limitReads = 0

        private fun limitSnapshot() = if (!limitNodePresent) {
            com.oppower.shizuku.ChargeLimitSnapshot(constantCurrentMa = -1, constantRaw = -1)
        } else {
            com.oppower.shizuku.ChargeLimitSnapshot(
                constantCurrentMa = limitNodeMa,
                constantRaw = limitNodeMa * 1_000,
            )
        }

        override fun readChargeLimit(): com.oppower.shizuku.ChargeLimitSnapshot {
            limitReads += 1
            return limitSnapshot()
        }

        override fun writeChargeLimit(ma: Int): com.oppower.shizuku.ChargeLimitSnapshot? {
            limitWrites += ma
            if (limitWriteFails || !limitNodePresent) return null
            limitNodeMa = limitSnapTo?.invoke(ma) ?: ma
            return limitSnapshot()
        }

        override fun restoreChargeLimit(
            original: com.oppower.shizuku.ChargeLimitSnapshot,
        ): com.oppower.shizuku.ChargeLimitSnapshot? {
            if (!limitNodePresent) return null
            val value = original.effectiveLimitMa
            if (value > 0) limitNodeMa = value
            return limitSnapshot()
        }

        override fun readGlobal(name: String): String {
            assertEquals("cosa_ctc_value", name)
            return cosa.toString()
        }

        override fun putGlobal(name: String, value: Int) {
            assertEquals("cosa_ctc_value", name)
            writes += value
            cosa = value
        }

        override fun readThermalPolicy(): String {
            policyReads += 1
            return "categoryName: $policyCategory\ngearVal: $gear\nchargeVal: ${policyChargeVal ?: "--"}\nspeedChargeAdd: ${policySpeedAdd ?: "--"}"
        }

        override fun readThermalStatus(): String {
            statusReads += 1
            return "Thermal status = $baseGear\nThermal Temperature = 35.0\nThermal Levels = [$baseGear]"
        }

        override fun requestThermalPolicyRefresh(): Boolean {
            refreshRequests += 1
            return refreshSucceeds
        }

        override fun requestNativeChargeLevel(level: Int): Boolean {
            nativeLevelRequests += level
            return nativeLevelRequestSucceeds
        }
    }

}
