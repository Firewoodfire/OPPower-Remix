package com.oppower.controller

import com.oppower.model.BatterySnapshot
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import kotlin.math.roundToInt

/**
 * 一级档位（cosa）与二级电流上限的分工。
 *
 * `cosa_ctc_value` 只能做 `-10..9` 的**离散**偏移，粒度完全被系统基础档位绑死：
 * 基础档为 8 时，cosa 每变 1 就是跳一整档，40W 与 25W 之间没有中间状态。
 * 而 sysfs 的 `constant_charge_current_max` 是**连续**的毫安值，可以直接把
 * 电流压在任意值上。两者叠加就是“粗调档位 + 细调电流”：
 *
 * - 档位（cosa）负责让系统进入正确的功率族；
 * - 电流上限负责在族内把功率压到目标值，避免档位跨度造成的过冲。
 *
 * 本对象只做纯计算，方便在没有真机的情况下用单测覆盖全部边界。
 */
object ChargeCurrentLimit {

    /** 系统节点允许的最窄/最宽区间，与 `ChargeLimitScale` 保持一致。 */
    const val MIN_MA = 500
    const val MAX_MA = 12_000

    /** 每一档对应的目标电流基准（mA），按机型的四档功率族折算。 */
    private val GEAR_BASE_MA = mapOf(
        0 to 6_000,
        1 to 5_000,
        2 to 4_200,
        3 to 3_500,
        4 to 3_000,
        5 to 2_200,
        6 to 1_800,
        7 to 1_500,
        8 to 1_200,
        9 to 1_000,
    )

    /** 温度每比 40°C 高 1°C，上限收缩 6%；低 1°C 放宽 3%。 */
    private const val TEMPERATURE_TIGHTEN_PER_C = 0.06f
    private const val TEMPERATURE_RELAX_PER_C = 0.03f
    private const val TEMPERATURE_PIVOT_C = 40f

    /** 电量超过 60% 后，每 1% 收缩 0.7%；超过 85% 后每 1% 再收缩 1.6%。 */
    private const val SOC_TIGHTEN_START = 60
    private const val SOC_TIGHTEN_HARD_START = 85

    /** 手动档的最大/最小值，用于 UI 与校验。 */
    const val MANUAL_MIN_MA = 1_000
    const val MANUAL_MAX_MA = 8_000
    const val MANUAL_STEP_MA = 500

    /**
     * 单次调度的最大变化量。电流上限的跃变会让充电器重新协商，
     * 大跨步还可能触发系统的“充电异常”判定，所以按 400mA 一步收敛。
     */
    const val MAX_STEP_MA = 400

    /**
     * 滞回：目标与当前值差异小于 150mA 时不写节点。
     * sysfs 写入是特权调用，1 秒循环里为了 50mA 反复写不值得。
     */
    const val HYSTERESIS_MA = 150

    /**
     * 计算目标电流上限。返回 `null` 表示“不需要限流”，调用方应恢复系统默认。
     *
     * @param gear 当前计算档位（0 最激进）。`null` 表示还没有档位信息，此时
     *   只按温度与电量限流，不额外收紧。
     */
    fun targetMa(
        settings: RuntimeSettings,
        battery: BatterySnapshot,
        gear: Int?,
        tier: ControlTier? = null,
    ): Int? {
        if (!settings.chargeCurrentLimitEnabled) return null
        if (!battery.acOnline) return null
        if (battery.levelPercent >= 100) return null
        if (tier == ControlTier.PAUSED) return null

        val manual = settings.chargeCurrentLimitMa
        val base = if (manual > 0) {
            manual.coerceIn(MANUAL_MIN_MA, MANUAL_MAX_MA)
        } else {
            gearBase(battery, gear)
        }
        val temperatureFactor = temperatureFactor(battery.temperatureC)
        val socFactor = socFactor(battery.levelPercent)
        val limit = (base * temperatureFactor * socFactor).roundToInt()
        return limit.coerceIn(MIN_MA, MAX_MA)
    }

    /**
     * 把计算结果收敛成下一次实际要写入的值。
     *
     * - 当前没有写过（`currentMa <= 0`）：直接写目标值，避免首次介入时多花几秒；
     * - 差异在 [HYSTERESIS_MA] 内：返回当前值，跳过这次写入；
     * - 其余情况按 [MAX_STEP_MA] 步进，冷却期内不重复写。
     */
    fun stepToward(currentMa: Int, targetMa: Int, minStepCooldownElapsed: Boolean = true): Int {
        if (currentMa <= 0) return targetMa
        val delta = targetMa - currentMa
        if (kotlin.math.abs(delta) <= HYSTERESIS_MA) return currentMa
        if (!minStepCooldownElapsed) return currentMa
        val step = delta.coerceIn(-MAX_STEP_MA, MAX_STEP_MA)
        return (currentMa + step).coerceIn(MIN_MA, MAX_MA)
    }

    /**
     * 无手动档时的基准电流。档位越高（数字越大 = 越保守）基准越低；
     * 电量已经偏高时再砍一刀，避免高 SOC 段继续灌大电流。
     */
    private fun gearBase(battery: BatterySnapshot, gear: Int?): Int {
        val clamped = (gear ?: 0).coerceIn(0, 9)
        val table = GEAR_BASE_MA[clamped] ?: 3_000
        return if (battery.levelPercent >= 90) table * 4 / 5 else table
    }

    private fun temperatureFactor(temperatureC: Float): Float = when {
        temperatureC <= 0f -> 1f
        temperatureC >= TEMPERATURE_PIVOT_C -> (
            1f - (temperatureC - TEMPERATURE_PIVOT_C) * TEMPERATURE_TIGHTEN_PER_C
            ).coerceAtLeast(0.45f)
        else -> (
            1f + (TEMPERATURE_PIVOT_C - temperatureC) * TEMPERATURE_RELAX_PER_C
            ).coerceAtMost(1.25f)
    }

    private fun socFactor(levelPercent: Int): Float {
        if (levelPercent <= SOC_TIGHTEN_START) return 1f
        var factor = 1f
        factor -= (levelPercent - SOC_TIGHTEN_START) * 0.007f
        if (levelPercent > SOC_TIGHTEN_HARD_START) {
            factor -= (levelPercent - SOC_TIGHTEN_HARD_START) * 0.016f
        }
        return factor.coerceIn(0.35f, 1f)
    }
}
