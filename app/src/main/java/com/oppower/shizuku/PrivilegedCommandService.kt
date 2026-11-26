package com.oppower.shizuku

import android.content.Context
import android.os.Process
import android.os.RemoteException
import androidx.annotation.Keep
import com.oppower.battery.ChargeLimitSample
import com.oppower.battery.ChargeLimitScale
import com.oppower.battery.PrivilegedBatteryReading
import com.oppower.battery.PrivilegedBatterySource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class PrivilegedCommandService @Keep constructor() : IPrivilegedCommandService.Stub() {
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    @Throws(RemoteException::class)
    override fun readBatteryFallback(): IntArray {
        return runCatching {
            val reading = readDirectBatteryFallback()
                ?: readForcedBatteryCurrent()
                ?: run {
                    val dump = runShell(BATTERY_DUMP_COMMAND, BATTERY_DUMP_TIMEOUT_MILLIS)
                    parsePrivilegedBatteryDump(dump)?.copy(source = PrivilegedBatterySource.DUMPSYS)
                }
                ?: error("Battery current is unavailable")
            intArrayOf(reading.currentMa, reading.voltageMv, reading.source.protocolCode)
        }.getOrElse { error ->
            throw RemoteException("OPPower battery fallback failed: ${error.message.orEmpty()}")
        }
    }

    /**
     * Some ColorOS builds expose power-supply sysfs values, while newer builds
     * hide them from the shell domain. Try sysfs first, then use BatteryService's
     * force-refresh command before falling back to the cached `dumpsys` dump.
     */
    private fun readDirectBatteryFallback(): PrivilegedBatteryReading? {
        val supplies = listOf("battery", "main")
        for (supply in supplies) {
            val base = "/sys/class/power_supply/$supply"
            val rawCurrent = readFirstLong(
                "$base/current_now",
                "$base/batt_current",
                "$base/current_avg",
            ) ?: continue
            if (rawCurrent == 0L || rawCurrent in INVALID_LONG_MARKERS) continue

            val currentMa = normalizePrivilegedCurrentToMa(rawCurrent)
            if (kotlin.math.abs(currentMa) > MAX_PRIVILEGED_CURRENT_MA) continue

            val rawVoltage = readFirstLong("$base/voltage_now", "$base/batt_vol")
                ?: readFirstLong(BATTERY_VOLTAGE_PATH)
            return PrivilegedBatteryReading(
                currentMa = currentMa.roundToInt(),
                voltageMv = normalizePrivilegedVoltageToMv(rawVoltage),
                source = if (supply == "main") {
                    PrivilegedBatterySource.MAIN_SYSFS
                } else {
                    PrivilegedBatterySource.BATTERY_SYSFS
                },
            )
        }
        return null
    }

    private fun readForcedBatteryCurrent(): PrivilegedBatteryReading? {
        val output = runCatching {
            runShell(FORCED_CURRENT_COMMAND, BATTERY_DUMP_TIMEOUT_MILLIS)
        }.getOrNull() ?: return null
        val currentMa = parseForcedBatteryCurrent(output) ?: return null
        if (kotlin.math.abs(currentMa) > MAX_PRIVILEGED_CURRENT_MA) return null
        return PrivilegedBatteryReading(
            currentMa = currentMa.roundToInt(),
            voltageMv = 0,
            source = PrivilegedBatterySource.BATTERY_SERVICE_FORCED,
        )
    }

    private fun readFirstLong(vararg paths: String): Long? {
        for (path in paths) {
            val value = runCatching {
                File(path).takeIf { it.isFile && it.canRead() }?.readText()?.trim()?.toLongOrNull()
            }.getOrNull()
            if (value != null && value !in INVALID_LONG_MARKERS) return value
        }
        return null
    }

    /**
     * 充电检测所需的多节点快照。只读 `power_supply` 下已确认存在的节点，
     * 单文件读取失败只跳过该字段，不新增任何 shell 命令面。
     */
    @Throws(RemoteException::class)
    override fun readPowerSupplyDump(): String = runCatching {
        buildString {
            POWER_SUPPLY_DIRS.forEach { name ->
                val dir = File(POWER_SUPPLY_ROOT, name)
                if (!dir.isDirectory) return@forEach
                val values = buildMap {
                    POWER_SUPPLY_FIELDS.forEach { field ->
                        runCatching {
                            File(dir, field)
                                .takeIf { it.isFile && it.canRead() }
                                ?.readText()
                                ?.trim()
                        }.getOrNull()
                            ?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH }
                            ?.let { put(field, it) }
                    }
                }
                if (values.isEmpty()) return@forEach
                append("[dir] ").append(name).append('\n')
                values.forEach { (field, value) -> append(field).append('=').append(value).append('\n') }
            }
        }
    }.getOrElse { error -> throw RemoteException("OPPower power supply dump failed: ${error.message.orEmpty()}") }

    /**
     * 充电限流的只读快照：`[constant_charge_current_max mA, constant raw,
     * input_current_limit mA, input raw, current_now mA]`。
     *
     * 节点集合固定；读不到时对应位置为 -1，由调用方决定要不要显示
     * “本机不支持”。每个节点的判定顺序是 `battery/…` → `main/…`，
     * 这样两种平台布局都能拿到值。
     */
    @Throws(RemoteException::class)
    override fun readChargeLimitNodes(): IntArray = runCatching {
        val result = IntArray(ChargeLimitSample.MIN_LENGTH) { -1 }
        ChargeLimitNodes.LIMIT_FIELDS.forEachIndexed { index, field ->
            val observed = readFirstLong(*ChargeLimitNodes.pathsFor(field).toTypedArray()) ?: return@forEachIndexed
            if (observed < 0L || observed > MAX_CHARGE_LIMIT_RAW) return@forEachIndexed
            result[index * 2] = ChargeLimitScale.toMilliamp(observed)
            result[index * 2 + 1] = observed.toInt()
        }
        result[ChargeLimitSample.INDEX_CURRENT_NOW_MA] = readFirstLong(
            BATTERY_CURRENT_PATH,
            MAIN_CURRENT_PATH,
        )?.let { ChargeLimitScale.toMilliamp(normalizeChargeCurrentForLimit(it)) } ?: -1
        result
    }.getOrElse { error ->
        throw RemoteException("OPPower charge limit read failed: ${error.message.orEmpty()}")
    }

    /**
     * 写入充电电流上限。只接受“毫安值”，节点与单位由服务端自己决定：
     * 调用方拿不到任意路径，也就无法把这条通道当成通用 shell 用。
     *
     * 返回值与 [readChargeLimitNodes] 同格式。写完立即回读，让上层用系统
     * 实际值判断成败，而不是只回显写下去的数字。
     */
    @Throws(RemoteException::class)
    override fun writeChargeLimitNodes(currentMa: Int): IntArray = runCatching {
        require(ChargeLimitScale.plausibleMa(currentMa)) { "Current limit out of range" }
        ChargeLimitNodes.LIMIT_FIELDS.forEach { field ->
            for (path in ChargeLimitNodes.pathsFor(field)) {
                val file = File(path)
                if (!file.isFile || !file.canWrite()) continue
                // 读一次当前原始值，用它的量级判断这个节点要 µA 还是 mA。
                val observed = runCatching { file.readText().trim().toLong() }.getOrDefault(-1L)
                val payload = chargeLimitPayload(currentMa, observed)
                val written = runCatching { file.writeText(payload) }.isSuccess
                if (written) break
            }
        }
        readChargeLimitNodes()
    }.getOrElse { error ->
        throw RemoteException("OPPower charge limit write failed: ${error.message.orEmpty()}")
    }

    /**
     * 恢复系统默认限流：把写入前的原始值写回去。
     *
     * 原始值由上层在首次写入前通过 [readChargeLimitNodes] 保存并透传回来。
     * 特权进程本身不保存状态——Shizuku 用户服务可能被系统回收重启，
     * 状态放在这里会丢，而丢状态就意味着限流永远回不到系统默认。
     */
    @Throws(RemoteException::class)
    override fun restoreChargeLimitNodes(originalRaw: IntArray): IntArray = runCatching {
        ChargeLimitNodes.LIMIT_FIELDS.forEachIndexed { index, field ->
            val raw = originalRaw.getOrNull(index * 2 + 1) ?: -1
            // 0 与负数都不可信：写入时节点就是 0 说明系统当时处于异常态，
            // 拿它恢复只会把上限永久固定在 0，充不进电比限流本身更糟。
            if (!ChargeLimitScale.usableRaw(raw)) return@forEachIndexed
            for (path in ChargeLimitNodes.pathsFor(field)) {
                val file = File(path)
                if (!file.isFile || !file.canWrite()) continue
                if (runCatching { file.writeText(raw.toString()) }.isSuccess) break
            }
        }
        readChargeLimitNodes()
    }.getOrElse { error ->
        throw RemoteException("OPPower charge limit restore failed: ${error.message.orEmpty()}")
    }

    /**
     * 热控诊断快照。**全程只读**：属性走白名单里的固定 `getprop`，
     * 进程状态读 `/proc`，节点与模块参数直接读文件，没有任何写入路径。
     *
     * 为什么是这几项：
     * 1. `persist.sys.horae.enable` —— 第三方模块靠改写它再 `stop/start horae`
     *    强制重算热控。属性不存在，这条路在本机就是空的。
     * 2. `init.svc.horae` —— init 眼里这个服务是 running / stopped / 未定义。
     *    比扫 `/proc` 可靠：进程没了可能只是没启动，也可能是名字不叫 horae。
     * 3. 进程名扫描只比对 argv[0] 的 basename。整条 cmdline 匹配会把
     *    `sh -c getprop persist.sys.horae.enable` 自己也数进去，制造假阳性。
     * 4. `oplus_chg_v2` 模块参数 —— 如果 `max_voltage` / `pps_max_voltage` /
     *    `temp_target` 这类字段真的存在，改它们比重启整个热控服务直接得多，
     *    而且不需要服务在跑。先把清单和当前值摊出来，再谈要不要动。
     */
    @Throws(RemoteException::class)
    override fun readThermalDiagnostics(): String = runCatching {
        buildString {
            appendProperty(PROP_HORAE_ENABLE)
            appendProperty(PROP_INIT_SVC_HORAE)
            append("[proc] horae=")
                .append(processNamesMatching("horae").joinToString(",").ifEmpty { "absent" })
                .append('\n')
            append("[bin] /system/bin/horae=")
                .append(if (File(HORAE_BINARY).exists()) "present" else "missing").append('\n')
            append("[uid] shell=").append(Process.myUid()).append('\n')
            THERMAL_DIAGNOSTIC_NODES.forEach { (label, path) ->
                append("[node] ").append(label).append('=')
                    .append(readNodeText(path) ?: "<不可读>").append('\n')
            }
            val parameters = chargerModuleParameters()
            append("[params] total=").append(parameters.size).append('\n')
            parameters.take(MAX_DIAGNOSTIC_PARAMETERS).forEach { parameter ->
                append("[param] ").append(parameter).append('\n')
            }
        }
    }.getOrElse { error ->
        throw RemoteException("OPPower diagnostics failed: ${error.message.orEmpty()}")
    }

    private fun StringBuilder.appendProperty(name: String) {
        append("[prop] ").append(name).append('=')
            .append(readProperty(name) ?: "<不存在>").append('\n')
    }

    /**
     * 找出 argv[0] 的 basename 里含 [keyword] 的进程，返回去重后的名字。
     *
     * 只取 argv[0] 是刻意的：`/proc/<pid>/cmdline` 里参数也用 `\0` 分隔，
     * 整串匹配会让 `sh -c getprop …horae…` 这条自己执行的探测命令命中，
     * 于是永远报"存在",诊断就失去意义了。
     */
    private fun processNamesMatching(keyword: String): List<String> = runCatching {
        File("/proc").listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
            ?.mapNotNull { pid ->
                runCatching {
                    File(pid, "cmdline").readText()
                        .substringBefore('\u0000')
                        .substringAfterLast('/')
                        .trim()
                }.getOrNull()
            }
            ?.filter { it.contains(keyword, ignoreCase = true) }
            ?.distinct()
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())

    /** `oplus_chg_v2` 的参数清单，格式 `name=value`；目录不存在返回空。 */
    private fun chargerModuleParameters(): List<String> = runCatching {
        val dir = File(OPLUS_CHG_PARAMETER_DIR)
        if (!dir.isDirectory) return@runCatching emptyList<String>()
        dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.map { file ->
                val value = runCatching {
                    file.readText().trim().take(MAX_PARAMETER_VALUE_LENGTH)
                }.getOrDefault("?")
                "${file.name}=$value"
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 读一个 `persist.*` / `init.svc.*` 运行时属性。
     *
     * `/system/build.prop` 里只有编译期属性，这些属性由 `property_service`
     * 维护在内存里，所以只能走 `getprop`。属性名来自本文件写死的常量，
     * 对应命令都在 [AllowedCommands] 的精确白名单里。
     */
    private fun readProperty(name: String): String? = runCatching {
        execute("getprop $name")
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH }
    }.getOrNull()

    private fun readNodeText(path: String): String? = runCatching {
        File(path)
            .takeIf { it.isFile && it.canRead() }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH }
    }.getOrNull()

    @Throws(RemoteException::class)
    override fun execute(command: String): String {
        if (!AllowedCommands.matches(command)) {
            throw SecurityException("OPPower command is not allowlisted")
        }
        return runCatching { runShell(command) }
            .getOrElse { error ->
                // AIDL exceptions must be a small, serializable message. The cause stays
                // inside the user-service process.
                throw RemoteException("OPPower command failed: ${error.message.orEmpty()}")
            }
    }

    private fun runShell(
        command: String,
        timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS,
    ): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Command timed out")
        }
        val output = process.inputStream.use { input ->
            ByteArrayOutputStream().also { input.copyTo(it) }.toString(Charsets.UTF_8.name())
        }.trim()
        if (process.exitValue() != 0) error(output.ifBlank { "Command failed" })
        return output
    }
    private companion object {
        const val POWER_SUPPLY_ROOT = "/sys/class/power_supply"
        const val MAX_FIELD_LENGTH = 64
        /** 上限字段的原始值最大 12_000_000 µA，留一点余量给异常的 10 倍上报。 */
        const val MAX_CHARGE_LIMIT_RAW = 120_000_000L
        const val BATTERY_CURRENT_PATH = "/sys/class/power_supply/battery/current_now"
        const val MAIN_CURRENT_PATH = "/sys/class/power_supply/main/current_now"
        val POWER_SUPPLY_DIRS = listOf(
            "battery",
            "usb",
            "main",
            "charger",
            "pc_port",
            "wired",
            "wireless",
            "otg",
        )
        val POWER_SUPPLY_FIELDS = listOf(
            "type",
            "online",
            "status",
            "health",
            "voltage_now",
            "voltage_max",
            "current_now",
            "current_max",
            "input_current_limit",
            "constant_charge_current_max",
            "charge_full",
            "charge_full_design",
            "capacity",
            "temp",
            "cycle_count",
            "usb_type",
            "real_type",
            "scope",
        )
        const val BATTERY_DUMP_COMMAND = "dumpsys battery"
        const val FORCED_CURRENT_COMMAND = "cmd battery get -f current_now"
        const val BATTERY_DUMP_TIMEOUT_MILLIS = 1_500L
        const val COMMAND_TIMEOUT_MILLIS = 8_000L
        const val BATTERY_VOLTAGE_PATH = "/sys/class/power_supply/battery/voltage_now"
        const val MAX_PRIVILEGED_CURRENT_MA = 20_000f
        const val PROP_HORAE_ENABLE = "persist.sys.horae.enable"
        const val PROP_INIT_SVC_HORAE = "init.svc.horae"

        /** init 眼的服务状态属性，`running` / `stopped` / 空（未定义）。 */
        const val HORAE_BINARY = "/system/bin/horae"
        const val OPLUS_CHG_PARAMETER_DIR = "/sys/module/oplus_chg_v2/parameters"
        const val MAX_PARAMETER_VALUE_LENGTH = 32

        /** 参数清单上限：一加 13 上约 30~60 个，给足余量但不至于把 Binder 撑爆。 */
        const val MAX_DIAGNOSTIC_PARAMETERS = 64

        /**
         * 诊断用的只读节点。`oplus_dbg_tbat` 是厂商充电驱动的电池温度入参，
         * `ufcs_*` / `pps_*` 只有在内核真的编进了对应协议时才存在——
         * 不存在本身就是一条有用的信息。
         */
        val THERMAL_DIAGNOSTIC_NODES = listOf(
            "oplus_dbg_tbat" to "/sys/module/oplus_chg_v2/parameters/oplus_dbg_tbat",
            "ufcs_active" to "/sys/class/power_supply/battery/ufcs_active",
            "pps_active" to "/sys/class/power_supply/battery/pps_active",
            "svooc_active" to "/sys/class/power_supply/battery/svooc_active",
        )
    }
}

internal fun parseForcedBatteryCurrent(output: String): Float? =
    output.lineSequence()
        .map(String::trim)
        .lastOrNull { it.matches(Regex("-?\\d+")) }
        ?.toLongOrNull()
        ?.let(::normalizePrivilegedCurrentToMa)

internal fun normalizePrivilegedCurrentToMa(raw: Long): Float =
    if (kotlin.math.abs(raw) > 20_000L) raw / 1_000f else raw.toFloat()

/**
 * 充电限流写入前的日志辅助：把“毫安 → 原始串”的换算集中在一处，
 * 免得将来有人在这里直接 `toString()` 写出单位错误的数。
 */
internal fun chargeLimitPayload(ma: Int, observedRaw: Long): String =
    ChargeLimitScale.toRaw(ma, observedRaw)

internal fun normalizeChargeCurrentForLimit(raw: Long): Long = kotlin.math.abs(raw)

/**
 * 允许写入的限流节点。
 *
 * 字段名与搜索目录都写死在这里，调用方只能传毫安值：传不了路径，就没办法把这条
 * 通道当成通用 `echo > 任意文件` 用。顺序与 [ChargeLimitSample] 的字段索引一致，
 * 新增字段只能追加到末尾。
 */
internal object ChargeLimitNodes {
    const val CONSTANT_CHARGE_CURRENT_MAX = "constant_charge_current_max"
    const val INPUT_CURRENT_LIMIT = "input_current_limit"

    /** 索引顺序必须与 `ChargeLimitSample` 一致。 */
    val LIMIT_FIELDS = listOf(CONSTANT_CHARGE_CURRENT_MAX, INPUT_CURRENT_LIMIT)

    /** 搜索目录顺序：`battery` 是主电池，`main` 是部分平台的双芯主路。 */
    val SEARCH_DIRS = listOf("battery", "main")

    fun pathsFor(field: String): List<String> =
        SEARCH_DIRS.map { "/sys/class/power_supply/$it/$field" }
}

internal fun normalizePrivilegedVoltageToMv(raw: Long?): Int {
    if (raw == null || raw <= 0L) return 0
    return when {
        raw > 100_000L -> (raw / 1_000L).toInt()
        raw > 1_000L -> raw.toInt()
        else -> (raw * 1_000L).toInt()
    }.takeIf { it in 3_000..5_500 } ?: 0
}

private val INVALID_LONG_MARKERS = setOf(
    Int.MIN_VALUE.toLong(),
    Int.MAX_VALUE.toLong(),
    Long.MIN_VALUE,
    Long.MAX_VALUE,
)
internal fun parsePrivilegedBatteryDump(output: String): PrivilegedBatteryReading? {
    val currentMa = BATTERY_CURRENT_PATTERN.find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: return null
    val voltageMv = BATTERY_VOLTAGE_PATTERN.find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0
    return PrivilegedBatteryReading(currentMa, voltageMv)
}

private val BATTERY_CURRENT_PATTERN = Regex(
    pattern = """(?im)^\s*Battery current\s*:\s*(-?\d+)\s*$""",
)
private val BATTERY_VOLTAGE_PATTERN = Regex(
    pattern = """(?im)^\s*voltage\s*:\s*(\d+)\s*$""",
)

internal object AllowedCommands {
    private val exact = setOf(
        "settings get global cosa_ctc_value",
        "settings get system oplus_camera_3rd_activity",
        "settings get system oplus_float_window_show",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 0",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 2",
        "getprop persist.sys.horae.enable",
        "getprop init.svc.horae",
    )

    /**
     * `cosa_ctc_value` 的写入范围与 [com.oppower.controller.GearCalculator] 一致：
     * -10…9（负值降档，0 为默认）。这里跟着实现走，而不是跟着“道理上”的范围走——
     * 控制层永远只会写这个区间，白名单没必要比它宽。
     *
     * 这条正则必须两端锚定：没有 `^` / `$` 时，
     * `settings put global cosa_ctc_value 0; rm -rf /…` 这种串能匹配成功，
     * 而 [PrivilegedCommandService.execute] 是把整串交给 `sh -c` 执行的。
     * 有了双锚点，能通过的串只有这一条完整命令。
     */
    private val putCosa = Regex("^settings put global cosa_ctc_value (?:-[1-9]|-10|\\d)$")

    /** 同上，双锚点：这两个别名只允许被改写成 0/00 或 1/01 的等价文本。 */
    private val putThermalRefreshAlias = Regex(
        "^settings put system (?:oplus_camera_3rd_activity|oplus_float_window_show) (?:0|1|00|01)$",
    )

    fun matches(command: String): Boolean =
        command in exact ||
            putCosa.matches(command) ||
            putThermalRefreshAlias.matches(command)
}
