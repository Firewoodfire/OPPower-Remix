package com.oppower.battery

/**
 * `power_supply` 节点的只读快照。字段名与内核 sysfs 一致，便于对照
 * `/sys/class/power_supply/<name>/<field>` 手工校验。
 */
data class PowerSupplyNode(
    val name: String,
    val values: Map<String, String>,
) {
    val type: String get() = values["type"].orEmpty().lowercase()

    fun text(key: String): String? = values[key]?.takeIf { it.isNotBlank() }

    fun long(key: String): Long? = values[key]?.trim()?.toLongOrNull()

    fun int(key: String): Int? = long(key)?.toInt()

    /** sysfs 的 `online` 只表示电气连接，不代表正在进电。 */
    val online: Boolean get() = int("online") == 1
}

/**
 * 解析特权进程返回的多节点文本。格式刻意保持简单，任何一行损坏都只丢弃该行，
 * 不会让整次检测失败：
 *
 * ```
 * [dir] usb
 * online=1
 * voltage_max=11000000
 * ```
 */
object PowerSupplyDump {
    private const val DIR_PREFIX = "[dir] "

    fun parse(raw: String): List<PowerSupplyNode> {
        val nodes = mutableListOf<PowerSupplyNode>()
        var currentName: String? = null
        var currentValues = LinkedHashMap<String, String>()

        fun flush() {
            val name = currentName ?: return
            if (currentValues.isNotEmpty()) nodes += PowerSupplyNode(name, currentValues)
            currentValues = LinkedHashMap()
        }

        raw.lineSequence().forEach { line ->
            when {
                line.startsWith(DIR_PREFIX) -> {
                    flush()
                    currentName = line.removePrefix(DIR_PREFIX).trim().takeIf { it.isNotEmpty() }
                }
                else -> {
                    val index = line.indexOf('=')
                    if (index <= 0) return@forEach
                    val key = line.substring(0, index).trim()
                    val value = line.substring(index + 1).trim()
                    if (key.isNotEmpty() && value.length <= 64) currentValues[key] = value
                }
            }
        }
        flush()
        return nodes
    }
}

/** 充电器对外宣称的能力，来自 `voltage_max`/`current_max`。 */
data class ChargerCapability(
    val supplyName: String,
    val supplyType: String,
    val protocolLabel: String,
    val maxVoltageMv: Int,
    val maxCurrentMa: Int,
    val maxPowerW: Float,
) {
    val isFastCharge: Boolean get() = maxPowerW >= 15f

    val label: String
        get() = when {
            maxPowerW >= 1f -> "${protocolLabel} · ${maxVoltageMv / 1000f}V/${maxCurrentMa / 1000f}A"
            else -> protocolLabel
        }
}

/** 电池设计容量与当前满充容量，用来估算健康度。 */
data class BatteryHealth(
    val designMah: Int,
    val fullMah: Int,
    val cycleCount: Int?,
    val temperatureC: Float?,
) {
    val percent: Int?
        get() = if (designMah in 1..30_000 && fullMah in 1..30_000) {
            (fullMah * 100L / designMah).toInt().coerceIn(0, 130)
        } else {
            null
        }
}

enum class ChargeStage(val label: String) {
    DISCONNECTED("未接电"),
    HANDSHAKE("协商中"),
    CONSTANT_CURRENT("恒流快充"),
    CONSTANT_VOLTAGE("恒压收尾"),
    TRICKLE("涓流补电"),
    FULL("已充满"),
    DISCHARGING("放电中"),
}

/**
 * 一次充电检测的完整结论。所有字段都由纯函数从 [PowerSupplyNode] 与采样点算出，
 * 便于在没有真机的情况下做单元测试。
 */
data class ChargeInsight(
    val capability: ChargerCapability? = null,
    val health: BatteryHealth? = null,
    val stage: ChargeStage = ChargeStage.DISCONNECTED,
    val stageDetail: String = "",
    val inputPowerW: Float = 0f,
    val utilizationPercent: Int? = null,
    val minutesToFull: Int? = null,
    val stabilityPercent: Int = 100,
    val anomalies: List<String> = emptyList(),
    val chargeMahThisSession: Int? = null,
    val efficiencyPercent: Int? = null,
    val hasData: Boolean = false,
) {
    val isHealthy: Boolean get() = anomalies.isEmpty()
}

/**
 * 充电检测。参考 Scene 充电统计的信息分层：先看“充得多快”，再看“为什么慢”，
 * 最后给电池健康与稳定性。判定阈值全部集中在本文件，便于按机型调整。
 */
object ChargeInspector {
    /** 单芯恒压阈值；双芯机型由调用方把阈值翻倍。 */
    const val CV_THRESHOLD_MV = 4_360
    const val TRICKLE_CURRENT_MA = 150
    const val HANDSHAKE_WINDOW_MS = 20_000L
    const val MIN_STABLE_SAMPLES = 6
    const val HIGH_TEMPERATURE_C = 42f
    const val MAX_PLAUSIBLE_CURRENT_MA = 15_000
    const val MIN_UTILIZATION_PERCENT = 35

    fun analyze(
        dump: String,
        acOnline: Boolean,
        levelPercent: Int,
        currentMa: Int,
        voltageMv: Int,
        powerW: Float,
        batteryTempC: Float,
        batteryStatus: Int,
        pluggedForMillis: Long?,
        recentPower: List<Float> = emptyList(),
        recentLevels: List<Pair<Long, Int>> = emptyList(),
        doubleCell: Boolean = false,
        sessionChargeMah: Int? = null,
        sessionAveragePowerW: Float? = null,
        sessionMinutes: Float? = null,
    ): ChargeInsight {
        val nodes = PowerSupplyDump.parse(dump)
        val capability = detectCharger(nodes)
        val health = detectHealth(nodes)
        val stage = detectStage(
            acOnline = acOnline,
            levelPercent = levelPercent,
            currentMa = currentMa,
            voltageMv = voltageMv,
            batteryStatus = batteryStatus,
            pluggedForMillis = pluggedForMillis,
            doubleCell = doubleCell,
        )
        val utilization = capability?.takeIf { it.maxPowerW >= 1f }?.let { cap ->
            if (!acOnline) null else ((powerW.coerceAtLeast(0f) / cap.maxPowerW) * 100f).toInt().coerceIn(0, 200)
        }
        val minutes = estimateMinutesToFull(levelPercent, recentLevels)
        val stability = stabilityPercent(recentPower)
        val anomalies = summarizeAnomalies(
            acOnline = acOnline,
            stage = stage,
            levelPercent = levelPercent,
            currentMa = currentMa,
            powerW = powerW,
            capability = capability,
            utilizationPercent = utilization,
            batteryTempC = batteryTempC,
            stability = stability,
            recentLevels = recentLevels,
        )
        val efficiency = chargeEfficiency(
            chargeMah = sessionChargeMah,
            averagePowerW = sessionAveragePowerW,
            minutes = sessionMinutes,
            cellVoltageV = 3.85f,
        )
        return ChargeInsight(
            capability = capability,
            health = health,
            stage = stage,
            stageDetail = stageDetail(stage, capability, levelPercent, currentMa),
            inputPowerW = if (acOnline) powerW.coerceAtLeast(0f) else 0f,
            utilizationPercent = utilization,
            minutesToFull = minutes,
            stabilityPercent = stability,
            anomalies = anomalies,
            chargeMahThisSession = sessionChargeMah,
            efficiencyPercent = efficiency,
            hasData = nodes.isNotEmpty() || acOnline,
        )
    }

    /** 只保留电气上真正供电的节点，避免把 `battery` 自身当成充电器。 */
    fun detectCharger(nodes: List<PowerSupplyNode>): ChargerCapability? {
        val charger = nodes
            .filter { it.type !in BATTERY_TYPES && it.type.isNotEmpty() }
            .filter { it.online || it.long("voltage_max") != null }
            .maxByOrNull { capabilityPowerW(it) }
            ?: return null
        val voltageMv = normalizeMicro(charger.long("voltage_max") ?: charger.long("voltage_now") ?: 0L)
        val currentMa = normalizeMicro(charger.long("current_max") ?: charger.long("input_current_limit") ?: 0L)
        val maxPowerW = if (voltageMv > 0 && currentMa > 0) {
            voltageMv / 1000f * currentMa / 1000f
        } else {
            0f
        }
        return ChargerCapability(
            supplyName = charger.name,
            supplyType = charger.type,
            protocolLabel = protocolLabel(charger, voltageMv),
            maxVoltageMv = voltageMv,
            maxCurrentMa = currentMa,
            maxPowerW = maxPowerW,
        )
    }

    fun detectHealth(nodes: List<PowerSupplyNode>): BatteryHealth? {
        val battery = nodes.firstOrNull { it.type in BATTERY_TYPES || it.name == "battery" } ?: return null
        val design = normalizeMicro(battery.long("charge_full_design") ?: 0L)
        val full = normalizeMicro(battery.long("charge_full") ?: 0L)
        if (design <= 0 && full <= 0) return null
        return BatteryHealth(
            designMah = design,
            fullMah = full,
            cycleCount = battery.int("cycle_count"),
            temperatureC = battery.long("temp")?.let { it / 10f },
        )
    }

    fun detectStage(
        acOnline: Boolean,
        levelPercent: Int,
        currentMa: Int,
        voltageMv: Int,
        batteryStatus: Int,
        pluggedForMillis: Long?,
        doubleCell: Boolean = false,
    ): ChargeStage {
        if (!acOnline) return ChargeStage.DISCONNECTED
        if (levelPercent >= 100 || batteryStatus == BATTERY_STATUS_FULL) return ChargeStage.FULL
        val threshold = if (doubleCell) CV_THRESHOLD_MV * 2 else CV_THRESHOLD_MV
        val current = kotlin.math.abs(currentMa)
        if (current < TRICKLE_CURRENT_MA && levelPercent >= 95) return ChargeStage.TRICKLE
        if (voltageMv >= threshold) return ChargeStage.CONSTANT_VOLTAGE
        if (pluggedForMillis != null && pluggedForMillis in 0..HANDSHAKE_WINDOW_MS) {
            return ChargeStage.HANDSHAKE
        }
        return ChargeStage.CONSTANT_CURRENT
    }

    /**
     * 用最近几分钟的电量斜率外推。斜率取相邻增量的中位数，能抵抗单帧跳变；
     * 斜率过小（<0.05 %/min）时不估算，避免给出几百分钟的假精度。
     */
    fun estimateMinutesToFull(
        levelPercent: Int,
        recentLevels: List<Pair<Long, Int>>,
    ): Int? {
        if (levelPercent >= 100) return null
        if (recentLevels.size < 3) return null
        val ordered = recentLevels.sortedBy { it.first }
        val deltas = ordered.zipWithNext().mapNotNull { (left, right) ->
            val minutes = (right.first - left.first) / 60_000f
            if (minutes <= 0f || minutes > 30f) null else (right.second - left.second) / minutes
        }.filter { it > 0f }
        if (deltas.size < 2) return null
        val slope = deltas.sorted()[deltas.size / 2]
        if (slope < 0.05f) return null
        val remaining = (100 - levelPercent) / slope
        return remaining.toInt().coerceIn(1, 600)
    }

    /** 最近功率的变异系数转成稳定性百分比；样本不足时返回满分。 */
    fun stabilityPercent(recentPower: List<Float>): Int {
        if (recentPower.size < MIN_STABLE_SAMPLES) return 100
        val values = recentPower.map { it.coerceAtLeast(0f) }
        val mean = values.average().toFloat()
        if (mean < 0.5f) return 100
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size
        val cv = kotlin.math.sqrt(variance).toFloat() / mean
        return (100f - cv * 120f).toInt().coerceIn(0, 100)
    }

    /**
     * 充入电量与输入电能的比值。输入按平均功率积分，输出按 3.85 V 标称电压折算，
     * 因此结果包含线损与升压损耗，只用于横向比较，不宣称是电芯库仑效率。
     */
    fun chargeEfficiency(
        chargeMah: Int?,
        averagePowerW: Float?,
        minutes: Float?,
        cellVoltageV: Float = 3.85f,
    ): Int? {
        if (chargeMah == null || averagePowerW == null || minutes == null) return null
        if (chargeMah <= 0 || averagePowerW <= 1f || minutes <= 1f) return null
        val outputWh = chargeMah / 1000f * cellVoltageV
        val inputWh = averagePowerW * minutes / 60f
        if (inputWh <= 0f) return null
        return ((outputWh / inputWh) * 100f).toInt().coerceIn(1, 100)
    }

    private fun summarizeAnomalies(
        acOnline: Boolean,
        stage: ChargeStage,
        levelPercent: Int,
        currentMa: Int,
        powerW: Float,
        capability: ChargerCapability?,
        utilizationPercent: Int?,
        batteryTempC: Float,
        stability: Int,
        recentLevels: List<Pair<Long, Int>>,
    ): List<String> = buildList {
        if (!acOnline) return@buildList
        if (stage != ChargeStage.FULL && kotlin.math.abs(currentMa) < 30 && levelPercent < 99) {
            add("已接电但电流接近 0，检查充电器握手或线材")
        }
        if (stability < 70) add("功率波动较大，稳定性 $stability%")
        if (capability != null && capability.isFastCharge &&
            utilizationPercent != null && utilizationPercent < MIN_UTILIZATION_PERCENT &&
            stage != ChargeStage.TRICKLE && stage != ChargeStage.FULL
        ) {
            add("充电器支持 ${capability.maxPowerW.toInt()} W，当前只有 ${powerW.toInt()} W")
        }
        if (batteryTempC >= HIGH_TEMPERATURE_C) {
            add("电池 ${batteryTempC.toInt()}°C，接近降档阈值")
        }
        if (kotlin.math.abs(currentMa) > MAX_PLAUSIBLE_CURRENT_MA) {
            add("电流读数 ${currentMa} mA 超出合理范围，检查硬件修正")
        }
        if (recentLevels.size >= 4) {
            val ordered = recentLevels.sortedBy { it.first }
            val first = ordered.first().second
            val last = ordered.last().second
            if (last < first && stage != ChargeStage.FULL) add("充电中电量下降，可能存在取电大于输入")
        }
    }

    private fun stageDetail(
        stage: ChargeStage,
        capability: ChargerCapability?,
        levelPercent: Int,
        currentMa: Int,
    ): String = when (stage) {
        ChargeStage.DISCONNECTED -> "等待接入电源"
        ChargeStage.HANDSHAKE -> "正在与充电器协商电压与电流"
        ChargeStage.CONSTANT_CURRENT ->
            "大电流恒流阶段 · ${kotlin.math.abs(currentMa)} mA"
        ChargeStage.CONSTANT_VOLTAGE -> "电池接近满压，电流开始收敛"
        ChargeStage.TRICKLE -> "涓流补电，功率会持续下降"
        ChargeStage.FULL -> "已充满 · 建议拔电或交给系统维护"
        ChargeStage.DISCHARGING -> "使用电池供电"
    }.let { detail ->
        capability?.let { "$detail · 充电器 ${it.protocolLabel}" } ?: detail
    }

    private fun protocolLabel(node: PowerSupplyNode, maxVoltageMv: Int): String {
        val usbType = node.text("usb_type")?.uppercase()
            ?: node.text("real_type")?.uppercase()
            ?: node.text("scope")?.uppercase()
        when {
            usbType == null -> Unit
            usbType.contains("PPS") -> return "USB PD PPS"
            usbType.contains("PD") -> return "USB PD"
            usbType.contains("DCP") -> return "USB DCP"
            usbType.contains("HVDCP") || usbType.contains("QC") -> return "QC 快充"
            usbType.contains("CDP") -> return "USB CDP"
            usbType.contains("SDP") -> return "USB SDP"
            usbType.contains("VOOC") || usbType.contains("SVOOC") -> return "SVOOC 超级闪充"
        }
        val name = node.name.lowercase()
        if (name.contains("wireless")) return "无线充电"
        if (node.type == "mains") return "有线充电器"
        return when {
            maxVoltageMv >= 20_000 -> "高压快充 20V"
            maxVoltageMv >= 11_000 -> "高压快充 11V"
            maxVoltageMv >= 9_000 -> "快充 9V"
            maxVoltageMv in 4_000..5_500 -> "普通 5V"
            else -> "未知协议"
        }
    }

    private fun capabilityPowerW(node: PowerSupplyNode): Float {
        val voltageMv = normalizeMicro(node.long("voltage_max") ?: node.long("voltage_now") ?: 0L)
        val currentMa = normalizeMicro(node.long("current_max") ?: node.long("input_current_limit") ?: 0L)
        return if (voltageMv > 0 && currentMa > 0) voltageMv / 1000f * currentMa / 1000f else 0f
    }

    /**
     * sysfs 里的电压/电流/容量可能以微单位上报（µV、µA、µAh），也可能已经是
     * 毫单位。只按量级判断，不使用固定单位假设。
     */
    internal fun normalizeMicro(raw: Long): Int {
        if (raw <= 0L) return 0
        return when {
            raw > 100_000_000L -> (raw / 1_000_000L).toInt()
            raw > 100_000L -> (raw / 1_000L).toInt()
            else -> raw.toInt()
        }
    }

    private val BATTERY_TYPES = setOf("battery")
    private const val BATTERY_STATUS_FULL = 5
}
