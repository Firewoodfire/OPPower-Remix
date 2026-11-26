package com.oppower.controller

import com.oppower.model.BatterySnapshot
import com.oppower.model.BaseTier
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二级限流是纯函数，边界全部靠单测锁死：
 * 关闭 / 拔电 / 满电 / 暂停 必须回落到 `null`（= 恢复系统默认），
 * 其余情况必须落在 sysfs 可接受的毫安区间内。
 */
class ChargeCurrentLimitTest {

    private fun settings(
        enabled: Boolean = true,
        manualMa: Int = 0,
        baseTier: BaseTier = BaseTier.MAX_OUTPUT,
    ) = RuntimeSettings(
        masterEnabled = true,
        baseTier = baseTier,
        chargeCurrentLimitEnabled = enabled,
        chargeCurrentLimitMa = manualMa,
    )

    private fun battery(
        temperatureC: Float = 35f,
        levelPercent: Int = 40,
        acOnline: Boolean = true,
    ) = BatterySnapshot(
        temperatureC = temperatureC,
        levelPercent = levelPercent,
        status = if (acOnline) 2 else 3,
        acOnline = acOnline,
    )

    // ---- targetMa 的“不该限流”分支 ----

    @Test
    fun disabledReturnsNull() {
        assertNull(ChargeCurrentLimit.targetMa(settings(enabled = false), battery(), gear = 4))
    }

    @Test
    fun unpluggedReturnsNull() {
        assertNull(
            ChargeCurrentLimit.targetMa(settings(), battery(acOnline = false), gear = 4),
        )
    }

    @Test
    fun fullBatteryReturnsNull() {
        assertNull(ChargeCurrentLimit.targetMa(settings(), battery(levelPercent = 100), gear = 4))
    }

    @Test
    fun pausedTierReturnsNull() {
        assertNull(
            ChargeCurrentLimit.targetMa(
                settings(),
                battery(),
                gear = 4,
                tier = ControlTier.PAUSED,
            ),
        )
    }

    // ---- 自动档：跟随档位 ----

    @Test
    fun autoModeIsMonotonicAcrossGears() {
        val s = settings()
        val limits = (0..9).map { ChargeCurrentLimit.targetMa(s, battery(), gear = it)!! }
        assertEquals("档位越高电流必须越小", limits.sortedDescending(), limits)
        assertTrue("最激进档不能低于最保守档", limits.first() > limits.last())
    }

    @Test
    fun autoModeFallsBackToMostAggressiveGearWhenUnknown() {
        val unknown = ChargeCurrentLimit.targetMa(settings(), battery(), gear = null)!!
        val gearZero = ChargeCurrentLimit.targetMa(settings(), battery(), gear = 0)!!
        assertEquals(gearZero, unknown)
        assertEquals(6_900, unknown) // 6000 基准 × 1.15（35°C 放宽）× 1.0（40% SOC）
    }

    @Test
    fun hotTemperatureTightensLimit() {
        val cool = ChargeCurrentLimit.targetMa(settings(), battery(temperatureC = 32f), gear = 4)!!
        val hot = ChargeCurrentLimit.targetMa(settings(), battery(temperatureC = 44f), gear = 4)!!
        assertTrue("44°C 必须比 32°C 更保守", hot < cool)
    }

    @Test
    fun highSocTightensLimit() {
        val low = ChargeCurrentLimit.targetMa(settings(), battery(levelPercent = 50), gear = 4)!!
        val mid = ChargeCurrentLimit.targetMa(settings(), battery(levelPercent = 80), gear = 4)!!
        val high = ChargeCurrentLimit.targetMa(settings(), battery(levelPercent = 95), gear = 4)!!
        assertTrue(mid < low)
        assertTrue(high < mid)
    }

    @Test
    fun extremeInputClampsIntoNodeRange() {
        val cold = ChargeCurrentLimit.targetMa(
            settings(),
            battery(temperatureC = 5f, levelPercent = 10),
            gear = 0,
        )!!
        assertTrue(cold <= ChargeCurrentLimit.MAX_MA)

        val hot = ChargeCurrentLimit.targetMa(
            settings(),
            battery(temperatureC = 60f, levelPercent = 99),
            gear = 9,
        )!!
        assertTrue(hot >= ChargeCurrentLimit.MIN_MA)
    }

    // ---- 手动档 ----

    @Test
    fun manualValueWinsOverGear() {
        val limit = ChargeCurrentLimit.targetMa(
            settings(manualMa = 3_000),
            battery(temperatureC = 35f, levelPercent = 40),
            gear = 0,
        )!!
        assertEquals(3_450, limit) // 3000 × 1.15 × 1.0
    }

    @Test
    fun manualValueIsClampedToUiRange() {
        val tooHigh = ChargeCurrentLimit.targetMa(settings(manualMa = 99_000), battery(), gear = 4)!!
        assertTrue(tooHigh <= ChargeCurrentLimit.MAX_MA)

        val tooLow = ChargeCurrentLimit.targetMa(settings(manualMa = 1), battery(), gear = 4)!!
        assertTrue(tooLow >= 800) // 1000 基准 × 1.15 放宽 × 温度电量系数
    }

    // ---- stepToward ----

    @Test
    fun firstWriteJumpsStraightToTarget() {
        assertEquals(3_000, ChargeCurrentLimit.stepToward(currentMa = 0, targetMa = 3_000))
    }

    @Test
    fun hysteresisSkipsTinyDeltas() {
        val current = 3_000
        val target = current + ChargeCurrentLimit.HYSTERESIS_MA - 1
        assertEquals(current, ChargeCurrentLimit.stepToward(current, target))
    }

    @Test
    fun deltaBeyondHysteresisStepsByMaxStep() {
        val current = 3_000
        val target = current + 1_000
        val next = ChargeCurrentLimit.stepToward(current, target)
        assertEquals(current + ChargeCurrentLimit.MAX_STEP_MA, next)
    }

    @Test
    fun stepIsSymmetricForDownwardTargets() {
        val current = 3_000
        val next = ChargeCurrentLimit.stepToward(current, current - 1_000)
        assertEquals(current - ChargeCurrentLimit.MAX_STEP_MA, next)
    }

    @Test
    fun cooldownBlocksStepButKeepsValue() {
        val current = 3_000
        val next = ChargeCurrentLimit.stepToward(
            currentMa = current,
            targetMa = 5_000,
            minStepCooldownElapsed = false,
        )
        assertEquals(current, next)
    }

    @Test
    fun stepNeverLeavesNodeRange() {
        val up = ChargeCurrentLimit.stepToward(
            currentMa = ChargeCurrentLimit.MAX_MA - 100,
            targetMa = ChargeCurrentLimit.MAX_MA,
        )
        assertTrue(up in ChargeCurrentLimit.MIN_MA..ChargeCurrentLimit.MAX_MA)

        val down = ChargeCurrentLimit.stepToward(
            currentMa = ChargeCurrentLimit.MIN_MA + 100,
            targetMa = ChargeCurrentLimit.MIN_MA,
        )
        assertTrue(down in ChargeCurrentLimit.MIN_MA..ChargeCurrentLimit.MAX_MA)
    }
}
