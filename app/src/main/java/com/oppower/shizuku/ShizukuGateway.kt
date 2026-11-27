package com.oppower.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.IBinder
import com.oppower.BuildConfig
import com.oppower.battery.ChargeLimitSample
import com.oppower.battery.PrivilegedBatteryReading
import com.oppower.battery.PrivilegedBatterySource
import com.oppower.controller.ChargingGateway
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 充电限流通道的快照。原始值用于恢复，毫安值用于展示与校验。 */
data class ChargeLimitSnapshot(
    val constantCurrentMa: Int = -1,
    val constantRaw: Int = -1,
    val inputCurrentMa: Int = -1,
    val inputRaw: Int = -1,
    val currentNowMa: Int = -1,
) {
    /** 可写入的节点是否存在：两个上限都读不到时，本机不支持这条通道。 */
    val supported: Boolean get() = constantCurrentMa > 0 || inputCurrentMa > 0

    /** 恢复时回传给特权进程的原始值数组，顺序与 AIDL 一致。 */
    fun toRawArray(): IntArray = intArrayOf(
        constantCurrentMa,
        constantRaw,
        inputCurrentMa,
        inputRaw,
        currentNowMa,
    )

    /** 系统当前实际生效的字面上限，优先电池侧。 */
    val effectiveLimitMa: Int get() = constantCurrentMa.takeIf { it > 0 } ?: inputCurrentMa

    companion object {
        fun from(values: IntArray?): ChargeLimitSnapshot? {
            if (values == null || values.size < ChargeLimitSample.MIN_RAW_LENGTH) return null
            return ChargeLimitSnapshot(
                constantCurrentMa = values[ChargeLimitSample.INDEX_CONSTANT_CURRENT_MA],
                constantRaw = values[ChargeLimitSample.INDEX_CONSTANT_CURRENT_RAW],
                inputCurrentMa = values[ChargeLimitSample.INDEX_INPUT_CURRENT_MA],
                inputRaw = values[ChargeLimitSample.INDEX_INPUT_CURRENT_RAW],
                currentNowMa = values.getOrElse(ChargeLimitSample.INDEX_CURRENT_NOW_MA) { -1 },
            )
        }
    }
}

class ShizukuGateway(context: Context) : ChargingGateway {
    private val appContext = context.applicationContext
    private val bindLock = Any()
    private var commandService: IPrivilegedCommandService? = null
    private var binding = false
    private var connectedLatch: CountDownLatch? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, PrivilegedCommandService::class.java),
    )
        .daemon(false)
        .processNameSuffix("oppower")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(bindLock) {
                commandService = binder?.let(IPrivilegedCommandService.Stub::asInterface)
                binding = false
                connectedLatch?.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindLock) {
                commandService = null
                binding = false
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(bindLock) {
                commandService = null
                binding = false
            }
        }
    }

    override val isAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val isInstalled: Boolean
        get() = runCatching {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

    override val hasPermission: Boolean
        get() = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    val isServiceConnected: Boolean
        get() = synchronized(bindLock) { commandService != null }

    fun requestPermission() {
        if (!isAvailable || hasPermission) return
        runCatching { Shizuku.requestPermission(1001) }
    }

    /** Opens the manager so the user can start its service before granting OPPower access. */
    fun openManager(): Boolean = runCatching {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return@runCatching false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        true
    }.getOrDefault(false)

    /** The app never requests network access; this only hands installation to a browser/store. */
    fun openInstallPage() {
        runCatching {
            appContext.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://github.com/RikkaApps/Shizuku/releases/latest".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun bind() {
        if (!isAvailable || !hasPermission) return
        synchronized(bindLock) {
            if (commandService != null || binding) return
            connectedLatch = CountDownLatch(1)
            binding = true
            runCatching {
                Shizuku.bindUserService(userServiceArgs, connection)
            }.onFailure {
                binding = false
                connectedLatch?.countDown()
            }
        }
    }

    fun unbind() {
        synchronized(bindLock) {
            commandService = null
            binding = false
            connectedLatch = null
        }
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
    }

    override fun readGlobal(name: String): String = execute("settings get global ${name.requireAllowedName()}")

    override fun putGlobal(name: String, value: Int) {
        execute("settings put global ${name.requireAllowedName()} $value")
    }

    override fun readThermalPolicy(): String =
        execute("dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy")

    override fun readThermalStatus(): String =
        execute("dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus")

    /**
     * Opt-in native charge-level control used only by the explicit Level 0
     * hold mode. The BatteryService debug entry executes D1() inside the
     * system process and reaches the charger HAL; the shell identity alone
     * cannot call the protected Binder directly.
     */
    override fun requestNativeChargeLevel(level: Int): Boolean {
        if (level !in NATIVE_CHARGE_LEVELS) return false
        val output = execute(
            "dumpsys activity service com.oplus.battery/.OplusBatteryService " +
                "setChargingLevel $level",
        )
        return output.contains("set charging level = $level")
    }

    /**
     * Reads current and voltage from one privileged `dumpsys battery` sample. This
     * path is used only when BatteryManager reports an AC-online zero, matching
     * LLPower's shell-level fallback without adding any new shell-command surface.
     */
    fun readBatteryFallback(): PrivilegedBatteryReading? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        val values = awaitService(BATTERY_SAMPLE_TIMEOUT_MILLIS).readBatteryFallback()
        if (values.size !in 2..3) return@runCatching null
        PrivilegedBatteryReading(
            currentMa = values[0],
            voltageMv = values[1],
            source = if (values.size >= 3) {
                PrivilegedBatterySource.fromProtocolCode(values[2])
            } else {
                PrivilegedBatterySource.UNKNOWN
            },
        )
    }.getOrNull()

    /**
     * 读取充电限流节点。只在确实需要时才调用：这是一次 Binder 往返 + 两次文件读，
     * 放进 1 秒控制循环前请先确认没有别的办法拿到同一个值。
     */
    override fun readChargeLimit(): ChargeLimitSnapshot? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        ChargeLimitSnapshot.from(awaitService().readChargeLimitNodes())
    }.getOrNull()

    /**
     * 写入充电电流上限（毫安）。返回值为写完后的回读快照，调用方用
     * `effectiveLimitMa` 判断系统是否真的认了这次写入。
     */
    override fun writeChargeLimit(ma: Int): ChargeLimitSnapshot? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        ChargeLimitSnapshot.from(awaitService().writeChargeLimitNodes(ma))
    }.getOrNull()

    /** 用首次写入前保存的原始值恢复系统默认限流。 */
    override fun restoreChargeLimit(original: ChargeLimitSnapshot): ChargeLimitSnapshot? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        if (!original.supported) return@runCatching null
        ChargeLimitSnapshot.from(
            awaitService().restoreChargeLimitNodes(original.toRawArray()),
        )
    }.getOrNull()

    /**
     * 充电检测用的只读快照。调用方应把它当作“每隔几秒一次的额外查询”，不要放进
     * 1 秒控制循环，避免为展示信息增加 Shizuku 往返。
     */
    fun readPowerSupplyDump(): String? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        awaitService(COMMAND_TIMEOUT_MILLIS).readPowerSupplyDump().takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 设备树里的电芯数。只在判断电池结构时调用一次，不要放进 1 秒控制循环。
     * 返回 null 表示没权限或本机读不到，调用方应退回应用域探测。
     */
    fun readDeviceCellCount(): Int? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        awaitService(COMMAND_TIMEOUT_MILLIS).readDeviceCellCount().takeIf { it in 1..4 }
    }.getOrNull()

    /**
     * 热控诊断快照（只读）。返回原始的多行文本，由 UI 侧解析成键值对——
     * 这样服务端将来多吐几行不需要改 AIDL 签名。
     */
    fun readThermalDiagnostics(): String? = runCatching {
        if (!isAvailable || !hasPermission) return@runCatching null
        awaitService(COMMAND_TIMEOUT_MILLIS).readThermalDiagnostics()
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * ThermalControlUtils observes both settings. A float-window change calls
     * j2(null, 0ms), which requests an immediate policy recomputation. The
     * camera callback calls m2(), whose internal delay is about five seconds,
     * so it remains only as a compatibility fallback.
     *
     * Rewriting the same integer with an equivalent textual representation
     * (0/00 or 1/01) preserves the system value while triggering one observer.
     */
    override fun requestThermalPolicyRefresh(): Boolean {
        if (runCatching { refreshViaAlias("oplus_float_window_show") }.getOrDefault(false)) {
            return true
        }
        return runCatching { refreshViaAlias("oplus_camera_3rd_activity") }.getOrDefault(false)
    }

    private fun refreshViaAlias(name: String): Boolean {
        val current = execute("settings get system $name")
        val write = thermalRefreshWrite(current) ?: return false
        execute("settings put system $name ${write.value}")
        return execute("settings get system $name").trim().toIntOrNull() == write.expectedInt
    }
    private fun execute(command: String): String = awaitService().execute(command)

    private fun awaitService(
        timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS,
    ): IPrivilegedCommandService {
        bind()
        val latch: CountDownLatch
        synchronized(bindLock) {
            commandService?.let { return it }
            latch = connectedLatch ?: CountDownLatch(0)
        }
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return synchronized(bindLock) {
            commandService ?: throw IllegalStateException("Shizuku 特权服务尚未连接")
        }
    }

    private fun String.requireAllowedName(): String {
        require(this == "cosa_ctc_value")
        return this
    }

    /**
     * Binds as soon as the service becomes usable. Binding only on the first
     * Shizuku command meant that a granted permission or a restarted Shizuku
     * binder left the gateway without a connection until the next cycle's
     * probe; this listener simply re-arms it, including after a debugger
     * restart. The service is bounded to this package, so no exported
     * component is exposed.
     */
    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        bind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(bindLock) {
            commandService = null
            binding = false
            connectedLatch = null
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val COMMAND_TIMEOUT_MILLIS = 4_000L
        private const val BATTERY_SAMPLE_TIMEOUT_MILLIS = 350L
        private val NATIVE_CHARGE_LEVELS = setOf(0, 2)
    }
}

internal data class ThermalRefreshWrite(
    val value: String,
    val expectedInt: Int,
)

/**
 * ThermalControlUtils treats only integer 1 as an active float-window scene. If the
 * provider has no row, it falls back to the same inactive state as 0. Normalizing
 * that one absent row to 0 therefore preserves policy semantics while notifying the
 * observer, which immediately runs j2(null, 0 ms).
 */
internal fun thermalRefreshWrite(current: String): ThermalRefreshWrite? =
    when (current.trim().lowercase()) {
        "0" -> ThermalRefreshWrite("00", 0)
        "00" -> ThermalRefreshWrite("0", 0)
        "1" -> ThermalRefreshWrite("01", 1)
        "01" -> ThermalRefreshWrite("1", 1)
        "null" -> ThermalRefreshWrite("0", 0)
        else -> null
    }
