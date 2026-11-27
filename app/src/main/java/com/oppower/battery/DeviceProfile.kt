package com.oppower.battery

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import java.io.File

/** 电池的电芯结构。双芯并联时系统上报的电流只是单芯值，功率需要乘二。 */
enum class CellStructure { SINGLE, DUAL, UNKNOWN }

/** 电芯结构的判定来源，只在界面上展示，用来解释“这个结论是怎么来的”。 */
enum class CellDetection(val label: String) {
    DEVICE_TREE("设备树"),
    POWER_SUPPLY("电源节点"),
    UNKNOWN("未识别"),
}

/**
 * 一台设备的最小画像。
 *
 * 存在的理由：旧版把机型和电池结构写死在代码里，换一台机器就只对一半。
 * 这里改成运行时探测——硬件属性不会变，所以探测一次就够。
 */
data class DeviceProfile(
    val cellStructure: CellStructure = CellStructure.UNKNOWN,
    /** 探测到的电芯数；0 表示未知。 */
    val cellCount: Int = 0,
    val detection: CellDetection = CellDetection.UNKNOWN,
    val model: String = "",
    val osLabel: String = "",
) {
    val isDualCell: Boolean get() = cellStructure == CellStructure.DUAL

    /** 单/双芯的中文说法，未知时给出“未识别”。 */
    val cellLabel: String
        get() = when (cellStructure) {
            CellStructure.DUAL -> "双电芯"
            CellStructure.SINGLE -> "单电芯"
            CellStructure.UNKNOWN -> "未识别"
        }

    /** 机型展示串，例如 `PKX110 · ColorOS 16`。 */
    val displayName: String
        get() = listOf(
            model.ifBlank { "未知机型" },
            osLabel.ifBlank { "ColorOS" },
        ).joinToString(" · ")
}

/**
 * 从 `oplus,batt_num` 属性的原始字节里取电芯数。
 *
 * 设备树属性是 4 字节大端整数，值小的时候高字节全是 0，所以取最后一个非零字节。
 * 也有固件只写 1 字节，同样适用。
 */
internal fun parseCellCount(raw: ByteArray?): Int? {
    if (raw == null || raw.isEmpty()) return null
    val value = raw.lastOrNull { it.toInt() != 0 }?.toInt()?.and(0xFF) ?: return null
    return value.takeIf { it in 1..4 }
}

/**
 * 没有特权时的兜底：只看 `power_supply` 下暴露了几个电池节点。
 *
 * - `battery2` 存在：两颗电池各自成节点，双芯。
 * - `main` 且有 `capacity`：部分双芯平台把主芯挂在 `main` 上。
 * - 只有 `battery`：单芯。
 *
 * 判不出来时返回 [CellStructure.UNKNOWN]，宁可让用户手动开，也不要把单芯机
 * 误判成双芯——那会让功率显示翻倍，比少算一半更容易误导。
 */
internal fun inferCellStructure(
    supplyNames: Set<String>,
    mainHasCapacity: Boolean,
): CellStructure = when {
    "battery2" in supplyNames -> CellStructure.DUAL
    "main" in supplyNames && mainHasCapacity -> CellStructure.DUAL
    "battery" in supplyNames -> CellStructure.SINGLE
    else -> CellStructure.UNKNOWN
}

/**
 * ROM 大版本标签。`Build.DISPLAY` 在 ColorOS 上形如 `PKX110_16.0.2.400(CN01)`，
 * 从中取大版本；取不到时退回 Android 版本号——ColorOS 自 11 起与 Android
 * 大版本对齐，这个近似在 ColorOS 机器上成立。
 */
internal fun formatOsLabel(display: String, androidRelease: String): String {
    val romMajor = listOf(
        Regex("""_(\d{2})\."""),
        Regex("""[Vv](\d{2})\."""),
        Regex("""^(\d{2})\."""),
    ).firstNotNullOfOrNull { pattern ->
        pattern.find(display)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 11..99 }
    }
    val major = romMajor ?: androidRelease.substringBefore('.').toIntOrNull()
    return if (major != null) "ColorOS $major" else "ColorOS"
}

private const val DEVICE_TREE_BATT_NUM = "/sys/firmware/devicetree/base/oplus,batt_num"
private const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"

/**
 * 应用域直接读设备树。多数 ColorOS 把 `/sys/firmware/devicetree/base` 设成
 * 仅 root 可进，读不到就返回 null，由上层退回 [inferCellStructure]。
 */
internal fun readCellCountFromDeviceTree(): Int? = runCatching {
    val file = File(DEVICE_TREE_BATT_NUM)
    if (!file.isFile || !file.canRead()) return@runCatching null
    parseCellCount(file.readBytes())
}.getOrNull()

internal fun inferCellStructureFromPowerSupply(): CellStructure = runCatching {
    val root = File(POWER_SUPPLY_ROOT)
    inferCellStructure(
        supplyNames = root.list()?.toSet().orEmpty(),
        mainHasCapacity = File(root, "main/capacity").canRead(),
    )
}.getOrDefault(CellStructure.UNKNOWN)

/**
 * 采样一次设备能力。
 *
 * [privilegedCellCount] 由 Shizuku 通道读到时传入，它的优先级最高：shell 域能读
 * 设备树，而应用域在多数机型上读不到。
 */
fun probeDeviceProfile(privilegedCellCount: Int? = null): DeviceProfile {
    val deviceTreeCount = privilegedCellCount ?: readCellCountFromDeviceTree()
    val structure: CellStructure
    val detection: CellDetection
    when {
        deviceTreeCount != null -> {
            structure = if (deviceTreeCount >= 2) CellStructure.DUAL else CellStructure.SINGLE
            detection = CellDetection.DEVICE_TREE
        }
        else -> {
            structure = inferCellStructureFromPowerSupply()
            detection = if (structure == CellStructure.UNKNOWN) {
                CellDetection.UNKNOWN
            } else {
                CellDetection.POWER_SUPPLY
            }
        }
    }
    val inferredCount = when (structure) {
        CellStructure.DUAL -> 2
        CellStructure.SINGLE -> 1
        CellStructure.UNKNOWN -> 0
    }
    return DeviceProfile(
        cellStructure = structure,
        cellCount = deviceTreeCount ?: inferredCount,
        detection = detection,
        model = Build.MODEL.orEmpty().trim(),
        osLabel = formatOsLabel(Build.DISPLAY.orEmpty(), Build.VERSION.RELEASE.orEmpty()),
    )
}

/**
 * 设备画像的进程内 + 持久缓存。
 *
 * 缓存的作用是让 [com.oppower.data.PreferencesStore] 在 `load()` 里同步拿到双芯
 * 判定：`load()` 跑在主线程，不能为了一个布尔值去等 Shizuku。
 *
 * [CellStructure.UNKNOWN] 不写入缓存——这种结果通常是权限还没到位，下次启动
 * 应该重新试一次，而不是把“不知道”永久钉死。
 */
object DeviceProfiles {
    private const val PREFS = "oppower_device"
    private const val KEY_CELL_COUNT = "cell_count"
    private const val KEY_CELL_STRUCTURE = "cell_structure"
    private const val KEY_DETECTION = "detection"

    /** UNKNOWN 的重试间隔：权限可能在运行期才到位，但不能每次重组都去读节点。 */
    private const val UNKNOWN_RETRY_MILLIS = 5_000L

    @Volatile
    private var memo: DeviceProfile? = null

    @Volatile
    private var lastProbeAtMillis = 0L

    fun get(context: Context): DeviceProfile {
        val cached = memo
        if (cached != null && isSettled(cached)) return cached
        return synchronized(this) {
            val current = memo
            if (current != null && isSettled(current)) {
                current
            } else {
                val resolved = readCache(context) ?: probeDeviceProfile()
                lastProbeAtMillis = SystemClock.elapsedRealtime()
                if (resolved.cellStructure != CellStructure.UNKNOWN) writeCache(context, resolved)
                memo = resolved
                resolved
            }
        }
    }

    /**
     * 已经有明确结论就不用再读节点；只有 [CellStructure.UNKNOWN] 才值得隔几秒重试，
     * 因为 Shizuku 权限可能在运行期才给上。
     */
    private fun isSettled(profile: DeviceProfile): Boolean =
        profile.cellStructure != CellStructure.UNKNOWN ||
            SystemClock.elapsedRealtime() - lastProbeAtMillis < UNKNOWN_RETRY_MILLIS

    /**
     * 用特权通道读到的电芯数纠正缓存。用户手动改过双芯开关时，调用方不会再拿
     * 这个结果去改设置，但界面展示仍然以探测值为准。
     */
    fun refine(context: Context, privilegedCellCount: Int?): DeviceProfile {
        val current = get(context)
        if (privilegedCellCount == null) return current
        if (current.detection == CellDetection.DEVICE_TREE &&
            current.cellCount == privilegedCellCount
        ) {
            return current
        }
        val refined = probeDeviceProfile(privilegedCellCount)
        writeCache(context, refined)
        memo = refined
        return refined
    }

    private fun readCache(context: Context): DeviceProfile? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val structure = runCatching {
            CellStructure.valueOf(prefs.getString(KEY_CELL_STRUCTURE, null).orEmpty())
        }.getOrNull() ?: return null
        if (structure == CellStructure.UNKNOWN) return null
        val detection = runCatching {
            CellDetection.valueOf(prefs.getString(KEY_DETECTION, null).orEmpty())
        }.getOrNull() ?: CellDetection.UNKNOWN
        return DeviceProfile(
            cellStructure = structure,
            cellCount = prefs.getInt(KEY_CELL_COUNT, 0),
            detection = detection,
            // 机型与 ROM 版本每次都从 Build 现读：系统升级后缓存不能是旧的。
            model = Build.MODEL.orEmpty().trim(),
            osLabel = formatOsLabel(Build.DISPLAY.orEmpty(), Build.VERSION.RELEASE.orEmpty()),
        )
    }

    private fun writeCache(context: Context, profile: DeviceProfile) {
        if (profile.cellStructure == CellStructure.UNKNOWN) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_CELL_STRUCTURE, profile.cellStructure.name)
            putInt(KEY_CELL_COUNT, profile.cellCount)
            putString(KEY_DETECTION, profile.detection.name)
        }
    }
}
