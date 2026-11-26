package com.oppower.controller

import com.oppower.model.BatterySnapshot
import com.oppower.model.BaseTier
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.ControlRules
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import com.oppower.model.RuleEngine
import com.oppower.model.ThermalPolicy
import com.oppower.model.ThermalStatus
import com.oppower.shizuku.ChargeLimitSnapshot

interface ChargingGateway {
    val isAvailable: Boolean
    val hasPermission: Boolean

    fun readGlobal(name: String): String
    fun putGlobal(name: String, value: Int)
    fun readThermalPolicy(): String
    fun readThermalStatus(): String
    fun requestThermalPolicyRefresh(): Boolean
    fun requestNativeChargeLevel(level: Int): Boolean = false

    /**
     * 二级控制通道：直接写 sysfs 的充电电流上限。默认实现返回 `null`，
     * 表示当前 Gateway 不支持这条通道；控制层据此静默退回到只要 cosa 的
     * 单级控制，不会因为宿主没实现而报错。
     */
    fun readChargeLimit(): ChargeLimitSnapshot? = null
    fun writeChargeLimit(ma: Int): ChargeLimitSnapshot? = null
    fun restoreChargeLimit(original: ChargeLimitSnapshot): ChargeLimitSnapshot? = null
}

class ChargingController(
    private val shizuku: ChargingGateway,
    private val rules: RuleEngine = RuleEngine(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var sessionSocLocked = false
    private var lastAcForSession: Boolean? = null
    private var lastFull = false
    private var currentCosa = 0
    private var cosaInitialized = false
    private var lastAppliedGear: Int? = null
    private var lastObservedGear: Int? = null
    private var appliedTier: ControlTier? = null
    private var temperatureCandidateGear: Int? = null
    private var temperatureCandidateSince = 0L
    private var controlSettingsSnapshot: ControlSettings? = null
    private var pausedFromGear: Int? = null
    private var cachedDiagnosticPolicy: ThermalPolicy? = null
    private var cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
    // 热控状态短缓存：1 秒循环不必每秒都打一次 dumpsys。
    private var cachedThermalStatus: ThermalStatus? = null
    private var cachedThermalStatusAtMillis = Long.MIN_VALUE
    private var levelZeroModeEntered = false
    private var nativeLevelZeroApplied = false
    private var nativeLevelRestorePending = false
    private var lastNativeLevelZeroAttemptAt: Long? = null

    // ---- 二级控制：充电电流上限 ----
    /** 首次写入前读到的系统原始值，恢复时原样写回。 */
    private var chargeLimitOriginal: ChargeLimitSnapshot? = null
    /** 当前由 OPPower 写入的电流上限（mA），0 表示还没有写过。 */
    private var appliedChargeLimitMa = 0
    /** 系统回读到的实际上限，用于 UI 与“系统是否认了”的判断。 */
    private var observedChargeLimitMa = -1
    /**
     * 系统当前实际被执行到的上限（不是我们想要的）。
     *
     * 有些平台会把写入值吸附到自己的档位表（比如 3000 变 2900），此时回读永远
     * 不等于目标，如果拿目标值做漂移基准就会每 10 秒重写一次，毫无意义还费电。
     * 所以漂移判断用这个值，它只在写入后真正回读成功时才更新。
     */
    private var actualChargeLimitMa = -1
    private var lastChargeLimitStepAt = Long.MIN_VALUE
    /** 上次核对节点是否仍等于我们写入值的时刻（0 = 从未核对）。 */
    private var lastChargeLimitVerifyAt = 0L
    /** Shizuku 上是否真的有可写的限流节点，`null` 表示还没探测过。 */
    private var chargeLimitSupported: Boolean? = null

    private val levelZeroSafetyActive: Boolean
        get() = levelZeroModeEntered || nativeLevelZeroApplied || nativeLevelRestorePending

    @Synchronized
    fun evaluate(
        settings: RuntimeSettings,
        battery: BatterySnapshot,
        screenOn: Boolean = true,
    ): ControllerStatus {
        if (!settings.masterEnabled && !settings.nativeLevelZeroEnabled) {
            lastAcForSession = battery.acOnline
            val restoreFailure = runCatching { restoreEverything() }.exceptionOrNull()
            return if (restoreFailure == null) {
                ControllerStatus(ControllerPhase.DISABLED, "充电增强未开启")
            } else {
                ControllerStatus(
                    ControllerPhase.ERROR,
                    "关闭时恢复系统默认失败：${restoreFailure.message ?: restoreFailure.javaClass.simpleName}",
                    desiredGear = if (levelZeroSafetyActive) 0 else null,
                    levelZeroMode = levelZeroSafetyActive,
                )
            }
        }

        if (!shizuku.isAvailable) {
            return ControllerStatus(
                ControllerPhase.ERROR,
                "Shizuku 未运行，未写入新的充电设置",
                desiredGear = if (levelZeroSafetyActive) 0 else null,
                levelZeroMode = levelZeroSafetyActive,
            )
        }
        if (!shizuku.hasPermission) {
            return ControllerStatus(
                ControllerPhase.ERROR,
                "OPPower 尚未获得 Shizuku 授权",
                desiredGear = if (levelZeroSafetyActive) 0 else null,
                levelZeroMode = levelZeroSafetyActive,
            )
        }

        return runCatching {
            initializeFromDevice()
            if (lastAcForSession != null && lastAcForSession != battery.acOnline) {
                resetSession()
            }
            lastAcForSession = battery.acOnline

            if (!battery.acOnline) {
                restoreEverything()
                return ControllerStatus(ControllerPhase.WAITING_FOR_POWER, "等待接入电源")
            }

            val full = battery.levelPercent >= 100 || battery.status == 5
            if (full) {
                restoreEverything()
                lastFull = true
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "电量已充满：已恢复系统默认")
            }
            if (lastFull) {
                restoreEverything()
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "本次插电已充满，拔电后重新开始")
            }

            val pauseSoc = rules.socPauseThreshold(settings)
            if (battery.levelPercent >= pauseSoc) {
                sessionSocLocked = true
            }
            if (sessionSocLocked) {
                restoreEverything()
                return ControllerStatus(
                    ControllerPhase.SOC_PAUSED,
                    "电量达到 $pauseSoc%：本次插电已暂停充电增强",
                    sessionSocLocked = true,
                    levelZeroMode = settings.nativeLevelZeroEnabled,
                )
            }

            if (!screenOn && !settings.screenOffBoostEnabled) {
                restoreEverything()
                return ControllerStatus(
                    ControllerPhase.SCREEN_OFF_PAUSED,
                    "息屏暂停 · 开启“息屏也生效”后继续",
                    desiredGear = if (settings.nativeLevelZeroEnabled) 0 else lastAppliedGear,
                    appliedCosa = currentCosa,
                    levelZeroMode = settings.nativeLevelZeroEnabled,
                )
            }

            if (settings.nativeLevelZeroEnabled) {
                enterLevelZeroMode()
                return evaluateLevelZero()
            }
            exitLevelZeroMode()

            val decision = rules.decide(
                settings = settings,
                temperatureC = battery.temperatureC,
                levelPercent = battery.levelPercent,
                batteryFull = full,
                sessionSocLocked = sessionSocLocked,
            )
            val requested = decision.targetGear

            if (requested == null) {
                if (appliedTier == ControlTier.PAUSED && pausedFromGear != null) {
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                val current = lastAppliedGear ?: pausedFromGear
                if (lastAppliedGear == null && pausedFromGear != null) {
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                if (current == null) {
                    restoreCosa()
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                val now = nowMillis()
                val holdSeconds = settings.temperatureDownshiftSeconds.coerceAtLeast(1)
                if (temperatureCandidateGear != TEMPERATURE_PAUSE_CANDIDATE) {
                    temperatureCandidateGear = TEMPERATURE_PAUSE_CANDIDATE
                    temperatureCandidateSince = now
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "温度达到暂停阈值 · 持续 $holdSeconds 秒后暂停",
                        desiredGear = current,
                        appliedCosa = currentCosa,
                        observedGear = lastObservedGear,
                    )
                }
                if (now - temperatureCandidateSince < holdSeconds * 1_000L) {
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "温度达到暂停阈值 · 等待确认（$holdSeconds 秒）",
                        desiredGear = current,
                        appliedCosa = currentCosa,
                        observedGear = lastObservedGear,
                    )
                }
                restoreCosa()
                pausedFromGear = current
                appliedTier = ControlTier.PAUSED
                return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
            }

            val target = confirmGear(requested, settings, decision.tier)
            if (target == null) {
                val currentGear = lastAppliedGear
                val waitSeconds = if (requested > (currentGear ?: requested)) {
                    settings.temperatureDownshiftSeconds
                } else {
                    settings.temperatureUpshiftSeconds
                }
                // 升功率一次跨多档时是逐档爬的，说明一下免得用户以为卡住了。
                val stepping = currentGear != null && requested < currentGear - 1
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    buildString {
                        append("等待 $waitSeconds 秒确认")
                        if (stepping) append(" · 逐档回升")
                        append(" · ")
                        append(decision.reason)
                    },
                    desiredGear = requested,
                    appliedCosa = currentCosa,
                    observedGear = lastObservedGear,
                )
            }

            shizuku.readGlobal("cosa_ctc_value").toIntOrNull()?.let { currentCosa = it }
            val thermalStatus = readThermalStatusCached(nowMillis())
            val baseGear = thermalStatus.baseGear
                ?: throw IllegalStateException("系统热控基础档位暂未返回")
            val policy = runCatching { readPolicyForDiagnostics(nowMillis()) }.getOrDefault(ThermalPolicy())
            val chargeVal = policy.chargeVal
            val resolvedBeforeWrite = GearCalculator.resolve(
                policy = policy,
                thermalStatus = thermalStatus,
                cosa = currentCosa,
            )

            // The target is calculated only from the live thermal base and cosa.
            // policy.gear is deliberately ignored, including stale cached values.
            val requiredCosa = target - baseGear
            val targetReachable = requiredCosa in GearCalculator.COSA_MIN..GearCalculator.COSA_MAX
            val desiredCosa = GearCalculator.clampCosaForTarget(target, baseGear)

            val targetChanged = lastAppliedGear != target
            val needsApply = currentCosa != desiredCosa || targetChanged
            val wrote = if (needsApply && currentCosa != desiredCosa) writeCosa(desiredCosa) else false
            val refreshRequested = if (needsApply) requestThermalRefresh() else false
            val calculatedGear = baseGear + currentCosa
            val verified = targetReachable && calculatedGear == target

            lastAppliedGear = target
            lastObservedGear = calculatedGear

            // 二级控制与 cosa 并列执行：cosa 决定功率族，电流上限决定族内的具体功率。
            // 限流失败不影响 cosa 主路径——它只是“更细的旋钮”，不是控制生效的必要条件。
            val limitOutcome = applyChargeCurrentLimit(settings, battery, calculatedGear, decision.tier)

            return ControllerStatus(
                ControllerPhase.ACTIVE,
                buildString {
                    append(
                        when {
                            !targetReachable ->
                                "目标 gear $target 不可达：所需 cosa=$requiredCosa，已使用安全下界" +
                                    " ${GearCalculator.COSA_MIN}，当前计算档位=$calculatedGear"
                            wrote && refreshRequested ->
                                "目标 cosa 已写入，计算档位已达到 $target，已触发一次系统重算"
                            wrote ->
                                "目标 cosa 已写入，计算档位=$target，但系统重算触发失败"
                            refreshRequested -> "基础档变化，已按公式重算 cosa 并触发系统重算"
                            verified -> "计算档位已达到目标 $target"
                            else -> "已按基础档和 cosa 更新计算档位"
                        },
                    )
                    append(" · 计算档位=")
                    append(calculatedGear)
                    append(" · 基础档=")
                    append(baseGear)
                    append(" · cosa=")
                    append(currentCosa)
                    append(" · 目标=")
                    append(target)

                    chargeVal?.let { append(" · chargeVal=$it") }
                    limitOutcome?.let { append(" · 电流上限=").append(it) }
                },
                desiredGear = target,
                appliedCosa = currentCosa,
                observedGear = calculatedGear,
                baseGear = baseGear,
                calculatedGear = calculatedGear,
                chargeVal = chargeVal,
                gearSource = resolvedBeforeWrite.source,
                verified = verified,
                appliedCurrentLimitMa = appliedChargeLimitMa,
                observedCurrentLimitMa = observedChargeLimitMa,
                chargeLimitSupported = chargeLimitSupported,
            )
        }.getOrElse { error ->
            ControllerStatus(
                ControllerPhase.ERROR,
                "控制暂时中断，已保持当前设置：${error.message ?: error.javaClass.simpleName}",
                desiredGear = if (levelZeroSafetyActive) 0 else lastAppliedGear,
                levelZeroMode = levelZeroSafetyActive,
                appliedCosa = currentCosa,
                observedGear = lastObservedGear,
            )
        }
    }

    @Synchronized
    fun readCurrentGear(): Int? {
        if (!shizuku.isAvailable || !shizuku.hasPermission) {
            throw IllegalStateException("请先完成 Shizuku 授权")
        }
        val cosa = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        return GearCalculator.resolve(
            policy = ThermalPolicy(),
            thermalStatus = readThermalStatus(),
            cosa = cosa,
        ).gear
    }

    @Synchronized
    fun readResolvedGear(): ResolvedGear {
        if (!shizuku.isAvailable || !shizuku.hasPermission) {
            throw IllegalStateException("请先完成 Shizuku 授权")
        }
        val cosa = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        val chargeVal = runCatching { readPolicyFresh().chargeVal }.getOrNull()
        return GearCalculator.resolve(
            policy = ThermalPolicy(chargeVal = chargeVal),
            thermalStatus = readThermalStatus(),
            cosa = cosa,
        )
    }

    @Synchronized
    fun restoreAll(forceNativeRestore: Boolean = false): ControllerStatus {
        val failure = runCatching {
            var restoreFailure: Throwable? = null
            try {
                restoreNativeLevel(force = forceNativeRestore || levelZeroSafetyActive)
            } catch (error: Throwable) {
                restoreFailure = error
            }
            restoreCosa()
            restoreChargeLimit()
            if (restoreFailure != null) throw restoreFailure
            levelZeroModeEntered = false
            nativeLevelZeroApplied = false
            nativeLevelRestorePending = false
            lastNativeLevelZeroAttemptAt = null
            resetSession()
        }.exceptionOrNull()
        return if (failure == null) {
            ControllerStatus(ControllerPhase.DISABLED, "已恢复系统默认")
        } else {
            ControllerStatus(
                ControllerPhase.ERROR,
                "恢复系统默认失败：${failure.message ?: failure.javaClass.simpleName}",
                levelZeroMode = levelZeroSafetyActive,
                appliedCosa = currentCosa,
                observedCurrentLimitMa = observedChargeLimitMa,
                chargeLimitSupported = chargeLimitSupported,
            )
        }
    }

    @Synchronized
    fun resetSession() {
        sessionSocLocked = false
        lastFull = false
        lastAppliedGear = null
        lastObservedGear = null

        appliedTier = null

        temperatureCandidateGear = null
        temperatureCandidateSince = 0L
        controlSettingsSnapshot = null
        pausedFromGear = null
        cachedDiagnosticPolicy = null
        cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
        lastChargeLimitVerifyAt = 0L
        cachedThermalStatus = null
        cachedThermalStatusAtMillis = Long.MIN_VALUE
        lastNativeLevelZeroAttemptAt = null
    }

    private fun enterLevelZeroMode() {
        if (levelZeroModeEntered) return
        // Level 0 is an independent opt-in path. Clear any normal-mode cosa
        // offset first so the native charge-level write is the only active
        // charging control. This path is strict: a failed reset must surface
        // instead of silently combining both control mechanisms.
        // 电流上限同样要清掉：它属于普通控制模式，留着会与 Level 0 的
        // “高功率保活”语义直接冲突（保活却限着流）。
        restoreOffset()
        restoreChargeLimit()
        levelZeroModeEntered = true
        nativeLevelZeroApplied = false
        lastNativeLevelZeroAttemptAt = null
    }

    private fun exitLevelZeroMode() {
        if (!levelZeroSafetyActive) return
        restoreNativeLevel(force = true)
        levelZeroModeEntered = false
    }

    private fun evaluateLevelZero(): ControllerStatus {
        val now = nowMillis()
        val lastAttempt = lastNativeLevelZeroAttemptAt
        val attemptDue = lastAttempt == null || now - lastAttempt >= LEVEL_ZERO_POLL_MILLIS
        if (!attemptDue) {
            val remainingSeconds = ((LEVEL_ZERO_POLL_MILLIS - (now - lastAttempt) + 999L) / 1_000L)
                .coerceAtLeast(1L)
            return ControllerStatus(
                phase = if (nativeLevelZeroApplied) ControllerPhase.ACTIVE else ControllerPhase.ERROR,
                message = if (nativeLevelZeroApplied) {
                    "高功率保活中 · 温度过高仍会自动保护"
                } else {
                    "高功率保活暂未生效 · $remainingSeconds 秒后重试"
                },
                desiredGear = 0,
                levelZeroMode = true,
                appliedCosa = currentCosa,
                verified = nativeLevelZeroApplied,
            )
        }

        lastNativeLevelZeroAttemptAt = now
        val confirmed = runCatching { shizuku.requestNativeChargeLevel(NATIVE_LEVEL_ZERO) }
            .getOrDefault(false)
        nativeLevelZeroApplied = confirmed
        nativeLevelRestorePending = !confirmed
        return if (confirmed) {
            ControllerStatus(
                ControllerPhase.ACTIVE,
                "高功率保活中 · 温度过高仍会自动保护",
                desiredGear = 0,
                levelZeroMode = true,
                appliedCosa = currentCosa,
                verified = true,
            )
        } else {
            ControllerStatus(
                ControllerPhase.ERROR,
                "高功率保活暂未生效 · 3 秒后重试",
                desiredGear = 0,
                levelZeroMode = true,
                appliedCosa = currentCosa,
            )
        }
    }

    private fun restoreEverything() {
        var failure: Throwable? = null
        try {
            exitLevelZeroMode()
        } catch (error: Throwable) {
            failure = error
        }
        restoreCosa()
        // 电流上限必须和 cosa 一起恢复：只清 cosa 会让系统停在“档位放开了但电流
        // 还压着”的中间态，用户看到的功率会莫名其妙地起不来。
        restoreChargeLimit()
        if (failure != null) throw failure
    }

    /**
     * 二级控制的调度点。返回给状态栏用的短描述，`null` 表示这次没有动限流。
     *
     * 设计要点：
     * 1. 不开启时立刻恢复系统默认，切换开关不会把设备留在半限流状态；
     * 2. 本机没有可写节点时把 `chargeLimitSupported` 记成 false，之后不再反复探测；
     * 3. 每次写值先读回、再校验，写失败只影响限流，不影响 cosa。
     */
    private fun applyChargeCurrentLimit(
        settings: RuntimeSettings,
        battery: BatterySnapshot,
        gear: Int?,
        tier: ControlTier,
    ): String? {
        if (chargeLimitSupported == false) return null
        val target = ChargeCurrentLimit.targetMa(settings, battery, gear, tier)
        if (target == null) {
            // 不需要限流：只有在确实写过的时候才恢复一次，避免每秒都去打特权调用。
            if (appliedChargeLimitMa != 0) {
                restoreChargeLimit()
                return "已恢复系统默认"
            }
            return null
        }

        val original = chargeLimitOriginal ?: runCatching { shizuku.readChargeLimit() }.getOrNull()
        if (original == null || !original.supported) {
            chargeLimitSupported = false
            return null
        }
        chargeLimitOriginal = original
        chargeLimitSupported = true

        val now = nowMillis()
        val cooldownElapsed = now - lastChargeLimitStepAt >= CHARGE_LIMIT_STEP_COOLDOWN_MILLIS
        // 首次介入（还没写过）时不受冷却限制，否则用户打开开关后要空等 2.5 秒。
        val initialWrite = appliedChargeLimitMa == 0
        val next = ChargeCurrentLimit.stepToward(
            currentMa = appliedChargeLimitMa,
            targetMa = target,
            minStepCooldownElapsed = initialWrite || cooldownElapsed,
        )
        if (next == appliedChargeLimitMa) {
            // 稳态不等于"系统一定还限着"：热控重算、充电器重协商、甚至系统省电
            // 都可能把节点刷回默认。只信自己记的数会一直以为限流还在，所以每 10 秒
            // 核对一次节点；发现漂移就直接重写目标值，而不是慢慢爬回去。
            if (now - lastChargeLimitVerifyAt >= CHARGE_LIMIT_VERIFY_MILLIS) {
                lastChargeLimitVerifyAt = now
                val drift = runCatching { shizuku.readChargeLimit() }.getOrNull()
                val actual = drift?.effectiveLimitMa ?: -1
                if (actual > 0) {
                    observedChargeLimitMa = actual
                    // 基准取"上一次系统真正接受的值"：平台吸附过的目标值再写一遍
                    // 也还是被吸附，用目标当基准会陷入无意义的周期性重写。
                    val baseline = actualChargeLimitMa.takeIf { it > 0 } ?: appliedChargeLimitMa
                    if (kotlin.math.abs(actual - baseline) > CHARGE_LIMIT_DRIFT_TOLERANCE_MA) {
                        val rewritten = runCatching {
                            shizuku.writeChargeLimit(appliedChargeLimitMa)
                        }.getOrNull()
                        val restored = rewritten?.effectiveLimitMa ?: -1
                        if (restored > 0) {
                            observedChargeLimitMa = restored
                            actualChargeLimitMa = restored
                            lastChargeLimitStepAt = now
                            return "已重写 $restored mA"
                        }
                    }
                }
            }
            return if (observedChargeLimitMa > 0) "保持 ${observedChargeLimitMa} mA" else null
        }

        val result = runCatching { shizuku.writeChargeLimit(next) }.getOrNull() ?: return null
        val effective = result.effectiveLimitMa
        if (effective <= 0) {
            // 节点存在但写不进去：记成不支持，避免 1 秒循环反复失败。
            // 但先确认它是不是被"归零"了——上限被写成 0 在很多平台等于禁止充电，
            // 这比限流失效严重得多，必须尽力写回归档值。
            rescueZeroChargeLimit()
            chargeLimitSupported = false
            return "写入失败"
        }
        appliedChargeLimitMa = next
        observedChargeLimitMa = effective
        actualChargeLimitMa = effective
        lastChargeLimitStepAt = now
        return "$next mA"
    }

    /**
     * 节点上限被写成 0 时的补救。
     *
     * 部分平台的 `constant_charge_current_max = 0` 语义是"禁止充电"，而不是"不限流"。
     * 如果上一次写入因为平台差异把值落成 0，设备可能直接停止充电，用户只会看到
     * "插着电不掉电也不涨"。这条路径不依赖任何缓存状态，尽力把已知的安全值写回去。
     */
    private fun rescueZeroChargeLimit() {
        val fallback = listOf(
            chargeLimitOriginal?.effectiveLimitMa ?: -1,
            actualChargeLimitMa,
            CHARGE_LIMIT_RESCUE_MA,
        ).firstOrNull { it in ChargeCurrentLimit.MIN_MA..ChargeCurrentLimit.MAX_MA } ?: return
        val rescued = runCatching { shizuku.writeChargeLimit(fallback) }.getOrNull()
        if ((rescued?.effectiveLimitMa ?: -1) > 0) {
            observedChargeLimitMa = rescued!!.effectiveLimitMa
        }
    }

    /**
     * 把限流恢复到首次写入前的系统值。
     *
     * 幂等：没有写过时是空操作，因此可以放心挂在拔电、满电、SOC 锁、关闭总开关、
     * 一键恢复、以及切换「高功率保活」等每一条退出路径上。
     */
    private fun restoreChargeLimit() {
        val original = chargeLimitOriginal
        if (appliedChargeLimitMa == 0 || original == null) {
            appliedChargeLimitMa = 0
            actualChargeLimitMa = -1
            observedChargeLimitMa = original?.effectiveLimitMa ?: -1
            return
        }
        val restored = runCatching { shizuku.restoreChargeLimit(original) }.getOrNull()
        if (restored != null) {
            observedChargeLimitMa = restored.effectiveLimitMa
            appliedChargeLimitMa = 0
            actualChargeLimitMa = -1
            lastChargeLimitVerifyAt = 0L
        }
        // 恢复失败时保留 appliedChargeLimitMa，让下一轮继续尝试。
        // 系统重启或拔电会自然把 sysfs 复位，因此这不是致命状态，
        // 不需要把它升级成 ERROR 去打断 cosa 的安全恢复。
    }

    private fun restoreNativeLevel(force: Boolean = false) {
        if (!force && !nativeLevelZeroApplied && !nativeLevelRestorePending) return
        val confirmed = try {
            shizuku.requestNativeChargeLevel(NATIVE_LEVEL_RESTORE)
        } catch (error: Throwable) {
            nativeLevelRestorePending = true
            throw error
        }
        if (!confirmed) {
            nativeLevelRestorePending = true
            throw IllegalStateException("系统未确认恢复普通充电")
        }
        nativeLevelZeroApplied = false
        nativeLevelRestorePending = false
        lastNativeLevelZeroAttemptAt = null
    }

    private fun confirmGear(
        requested: Int,
        settings: RuntimeSettings,
        tier: ControlTier,
    ): Int? {
        val current = lastAppliedGear ?: pausedFromGear
        val now = nowMillis()
        val controlSettings = ControlSettings(
            baseTier = settings.baseTier,
            customRulesEnabled = settings.customRulesEnabled,
            customRules = settings.customRules,
        )
        val controlSettingsChanged = controlSettingsSnapshot?.let { it != controlSettings } == true
        controlSettingsSnapshot = controlSettings

        if (current == null) {
            appliedTier = tier
            return requested
        }

        // User rule and base-tier changes are applied on this cycle. The closed
        // loop writes the dynamically calculated cosa exactly once and never
        // clears to zero while moving between targets.
        if (controlSettingsChanged) {
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            appliedTier = tier
            pausedFromGear = null
            return requested
        }

        if (lastAppliedGear != null && requested == current) {
            appliedTier = tier
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            return requested
        }

        val proposed = limitPowerIncrease(requested = requested, current = current)

        // Temperature movement, including a transition between large tiers and
        // recovery from a temperature pause, changes the concrete gear only after
        // it remains stable for the configured confirmation time.
        if (temperatureCandidateGear != proposed) {
            temperatureCandidateGear = proposed
            temperatureCandidateSince = now
            return null
        }
        val confirmSeconds = if (proposed > current) {
            settings.temperatureDownshiftSeconds
        } else {
            settings.temperatureUpshiftSeconds
        }
        return if (now - temperatureCandidateSince >= confirmSeconds * 1_000L) {
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            appliedTier = tier
            pausedFromGear = null
            proposed
        } else null
    }

    /**
     * 升功率时的限幅。gear 数字越小功率越高，所以「升功率」是 requested < current。
     *
     * 温度从阈值上方回落时，规则表会直接给出该温度段的档位，可能一次跨好几档。
     * 不限幅的后果是功率突然翻倍，几十秒后电池又冲回阈值、再次降档，形成几十秒
     * 一轮的锯齿，用户体感就是"功率在跳"。这里把升功率拆成每轮最多一档，每一步
     * 都走一次确认时间，温度有充分机会在半路上把它拦下来。
     *
     * 降功率（requested > current）完全不限幅：保护温度优先，越快越好。
     * 用户改基础档位走的是 controlSettingsChanged 分支，不经过这里，仍然立即生效。
     */
    private fun limitPowerIncrease(requested: Int, current: Int): Int =
        if (requested < current) maxOf(requested, current - 1) else requested

    private fun initializeFromDevice() {
        if (cosaInitialized) return
        currentCosa = shizuku.readGlobal("cosa_ctc_value").toIntOrNull() ?: 0
        cosaInitialized = true
    }

    private fun restoreCosa() {
        runCatching { restoreOffset() }
    }

    private fun restoreOffset() {
        if (!cosaInitialized) initializeFromDevice()
        val wrote = if (currentCosa != 0) writeCosa(0) else false
        if (wrote) requestThermalRefresh()
        lastAppliedGear = null
        lastObservedGear = null
        appliedTier = null

        temperatureCandidateGear = null
        temperatureCandidateSince = 0L
        pausedFromGear = null
        controlSettingsSnapshot = null
        cachedDiagnosticPolicy = null
        cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
    }

    private fun writeCosa(value: Int): Boolean {
        if (currentCosa == value) return false
        shizuku.putGlobal("cosa_ctc_value", value)
        val readBack = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        if (readBack != value) throw IllegalStateException("cosa 回读不一致")
        currentCosa = value
        return true
    }

    /**
     * 带短缓存的 thermal status 读取。
     *
     * 每秒一次的 `evaluate()` 原本要做 3 次 Binder 往返（cosa、thermalStatus、
     * 条件性 policy）。`Thermal Levels` 实际以系统热控的回调节奏变化，远慢于 1 秒，
     * 因此 2 秒内的重复读取可以直接复用；[requestThermalRefresh] 成功后会主动
     * 失效缓存，保证「刚触发重算就立刻重新读」这条路径不被缓存吃掉。
     */
    private fun readThermalStatusCached(now: Long): ThermalStatus {
        val cached = cachedThermalStatus
        val age = now - cachedThermalStatusAtMillis
        if (cached != null && age in 0L..THERMAL_STATUS_CACHE_MILLIS) return cached
        return readThermalStatus().also {
            cachedThermalStatus = it
            cachedThermalStatusAtMillis = now
        }
    }

    private fun readThermalStatus(): ThermalStatus =
        GearCalculator.parseThermalStatus(shizuku.readThermalStatus())

    private fun requestThermalRefresh(): Boolean {
        val requested = runCatching { shizuku.requestThermalPolicyRefresh() }.getOrDefault(false)
        if (requested) {
            cachedDiagnosticPolicy = null
            cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
            // 系统刚被触发重算，热控状态可能已经变了，缓存必须立刻失效。
            cachedThermalStatus = null
            cachedThermalStatusAtMillis = Long.MIN_VALUE
        }
        return requested
    }

    /**
     * chargeVal and speedChargeAdd describe the native policy but do not drive
     * OPPower's gear calculation. Cache only this diagnostic query so the
     * one-second thermal-status and cosa checks remain untouched.
     */
    private fun readPolicyForDiagnostics(now: Long): ThermalPolicy {
        val cached = cachedDiagnosticPolicy
        val age = now - cachedDiagnosticPolicyAtMillis
        if (cached != null && age in 0L..POLICY_DIAGNOSTIC_CACHE_MILLIS) return cached
        return readPolicyFresh().also {
            cachedDiagnosticPolicy = it
            cachedDiagnosticPolicyAtMillis = now
        }
    }

    private fun readPolicyFresh(): ThermalPolicy {
        val raw = shizuku.readThermalPolicy()
        return ThermalPolicy(
            raw = raw,
            category = CATEGORY_PATTERN.find(raw)?.groupValues?.getOrNull(1).orEmpty(),
            chargeVal = CHARGE_VAL_PATTERN.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull(),
            speedChargeAdd = SPEED_CHARGE_PATTERN.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull(),
        )
    }

    private companion object {
        val CHARGE_VAL_PATTERN = Regex("chargeVal:\\s*(-?\\d+)")
        val SPEED_CHARGE_PATTERN = Regex("speedChargeAdd:\\s*(-?\\d+)")
        val CATEGORY_PATTERN = Regex("categoryName:\\s*([^\\s]+)")
        const val TEMPERATURE_PAUSE_CANDIDATE = -1
        const val POLICY_DIAGNOSTIC_CACHE_MILLIS = 3_000L
        /** 热控状态缓存 2 秒：系统热控回调远慢于 1 秒控制节奏，重复读纯属浪费往返。 */
        const val THERMAL_STATUS_CACHE_MILLIS = 2_000L
        const val LEVEL_ZERO_POLL_MILLIS = 3_000L
        /** 电流上限的步进冷却：一次 400mA，最快每 2.5 秒走一步，避免充电器频繁重协商。 */
        const val CHARGE_LIMIT_STEP_COOLDOWN_MILLIS = 2_500L
        /** 稳态下每 10 秒核对一次节点，确认限流没有被系统悄悄刷回默认。 */
        const val CHARGE_LIMIT_VERIFY_MILLIS = 10_000L
        /** 回读与目标差在这个范围内视为没漂移（sysfs 常有几十毫安的量化误差）。 */
        const val CHARGE_LIMIT_DRIFT_TOLERANCE_MA = 100
        /** 没有任何可信存档时，退回这个"一定能充上电"的值做应急写入。 */
        const val CHARGE_LIMIT_RESCUE_MA = 2_000
        const val NATIVE_LEVEL_ZERO = 0
        const val NATIVE_LEVEL_RESTORE = 2
    }

    private data class ControlSettings(
        val baseTier: BaseTier,
        val customRulesEnabled: Boolean,
        val customRules: ControlRules,
    )
}
