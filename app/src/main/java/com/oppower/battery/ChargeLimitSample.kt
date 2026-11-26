package com.oppower.battery

/**
 * 充电限流会用到的 sysfs 节点结果。
 *
 * 值与节点按位对应，节点集合固定（见 `ChargeLimitNodes.DIRECT`），这样特权端只需要
 * 回传整数数组：长度不足时调用方按“没有该字段”处理，不会因为多一个 `null` 崩溃。
 *
 * 字段顺序即协议，新增字段只能追加到末尾。
 */
object ChargeLimitSample {
    /** `constant_charge_current_max`：反算出的毫安值，未知为 -1。 */
    const val INDEX_CONSTANT_CURRENT_MA = 0

    /** `constant_charge_current_max` 的原始写入串，未知为空。 */
    const val INDEX_CONSTANT_CURRENT_RAW = 1

    /** `input_current_limit`：反算出的毫安值，未知为 -1。 */
    const val INDEX_INPUT_CURRENT_MA = 2

    /** `input_current_limit` 的原始写入串，未知为空。 */
    const val INDEX_INPUT_CURRENT_RAW = 3

    /** 电池侧 `current_now` 反算出的毫安值，未知为 -1。 */
    const val INDEX_CURRENT_NOW_MA = 4

    /** 至少具备这个长度，才算一次有效读取。 */
    const val MIN_LENGTH = 5

    /** 只具备原始串、没有毫安值的最小长度。 */
    const val MIN_RAW_LENGTH = 4
}

/**
 * 一次限流写入/恢复的结果。`raw` 是服务端回读到的原始值，用于在 UI 上给出
 * “系统实际认了多少毫安”，而不是只回显我们写下去的数字。
 */
data class ChargeLimitReading(
    val appliedMa: Int,
    val raw: String,
    val readBackMa: Int = -1,
    val path: String = "",
) {
    val confirmed: Boolean get() = readBackMa > 0 && appliedMa > 0 && readBackMa == appliedMa

    /**
     * 这条记录是否值得写回去。`appliedMa > 0` 表示是 OPPower 自己写的值，
     * 其余情况（-1 / 0）说明是系统原值，不能拿去覆盖。
     */
    val restorable: Boolean get() = appliedMa > 0 && raw.isNotBlank()
}

/**
 * 把 sysfs 的电流值归一成毫安。
 *
 * 不同平台的单位不一致：QCOM 常见 µA（`3000000`），MTK 与部分 OPPO 平台直接写 mA
 * （`3000`）。这里不做“按固定单位换算”，而是按量级判断，并以 [plausibleMa] 兜底，
 * 避免把 `3000` 当成 3 A 之外的荒谬值写回系统。
 */
object ChargeLimitScale {
    /** 允许写入的最小/最大毫安。低于 500mA 会让部分机型判定为“充电异常”。 */
    const val MIN_MA = 500
    const val MAX_MA = 12_000

    fun microBased(raw: Long): Boolean = raw > 20_000L

    fun toMilliamp(raw: Long): Int {
        if (raw <= 0L) return -1
        val ma = if (microBased(raw)) raw / 1_000L else raw
        return ma.takeIf { it in MIN_MA.toLong()..MAX_MA.toLong() }?.toInt() ?: -1
    }

    /**
     * 反算写入串：读到的原始值决定了这个节点期望的单位。
     * 读不到（`raw <= 0`）时保守地按 µA 写，因为那是内核文档约定的单位。
     */
    fun toRaw(ma: Int, observedRaw: Long): String {
        val value = ma.coerceIn(MIN_MA, MAX_MA)
        return if (observedRaw > 0L && !microBased(observedRaw)) {
            value.toString()
        } else {
            (value.toLong() * 1_000L).toString()
        }
    }

    /**
     * 需要"恢复"的值是否可信。用于 [ChargeLimitReading.restorable]：原始值 0
     * 说明写的时候节点等于 0（异常态），拿它去恢复只会把系统固定在 0。
     */
    fun usableRaw(raw: Int): Boolean = raw > 0

    fun plausibleMa(ma: Int): Boolean = ma in MIN_MA..MAX_MA
}
