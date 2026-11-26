package com.oppower.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.BatteryManager
import androidx.annotation.RequiresApi
import com.oppower.MainActivity
import com.oppower.R
import com.oppower.battery.BatteryReader
import com.oppower.battery.ChargeInsight
import com.oppower.battery.ChargeInspector
import com.oppower.controller.ChargingController
import com.oppower.data.PreferencesStore
import com.oppower.history.ChartHistoryBus
import com.oppower.history.ChartHistoryStore
import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.RuntimeSettings
import com.oppower.model.powerTierLabel
import com.oppower.shizuku.ShizukuGateway
import com.oppower.ui.oneDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal object StatusNotificationChannels {
    const val STATUS = "charge_control"
    const val LIVE = "battery_live_update_v2"
    const val ALERT = "charge_alert"
}

class ChargeControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var shizuku: ShizukuGateway
    private lateinit var controller: ChargingController
    private lateinit var batteryReader: BatteryReader
    private lateinit var prefs: PreferencesStore
    private lateinit var prefsFile: SharedPreferences
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener
    @Volatile private var cachedSettings = RuntimeSettings()
    private lateinit var historyStore: ChartHistoryStore
    private lateinit var overlay: KeepAliveOverlay
    private lateinit var overlayScope: CoroutineScope
    private var foregroundStarted = false
    @Volatile private var screenOn = true
    private var lastNotificationText = ""
    private var lastNotificationTitle = ""
    private var lastNotificationAt = 0L
    private var lastOverlayEnabled: Boolean? = null
    private var insight: ChargeInsight = ChargeInsight()
    private var lastDetectionAt = 0L
    private var supplyDump: String? = null
    private var pluggedAtElapsed: Long? = null
    private var lastAnomalySignature = ""
    private var fullAlerted = false
    private val detectPowerWindow = ArrayDeque<Float>()
    private val detectLevelWindow = ArrayDeque<Pair<Long, Int>>()
    @Volatile private var controlLoopStarted = false
    @Volatile private var pendingImmediateAction: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
            ChargeControlBus.requestUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("OPPower", "ChargeControlService created")
        shizuku = ShizukuGateway(this)
        shizuku.bind()
        prefs = PreferencesStore(this)
        cachedSettings = prefs.load()
        prefsFile = getSharedPreferences("oppower", MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            cachedSettings = prefs.load()
            ChargeControlBus.requestUpdate()
        }
        prefsFile.registerOnSharedPreferenceChangeListener(prefsListener)
        controller = ChargingController(shizuku = shizuku)
        batteryReader = BatteryReader(this, shizuku::readBatteryFallback)
        historyStore = ChartHistoryStore(this)
        overlay = KeepAliveOverlay(this)
        overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        createChannel()
        notificationDismissed = false
    }

    private fun startControlLoop() {
        if (controlLoopStarted) return
        controlLoopStarted = true
        scope.launch {
            try {
                while (true) {
                    val cycleStartedAt = SystemClock.elapsedRealtime()
                    val immediateAction = pendingImmediateAction
                    if (immediateAction != null) pendingImmediateAction = null
                    var keepRunning = true
                    var idleBackoff = false
                    try {
                        val settings = cachedSettings
                        val battery = batteryReader.snapshot(settings)
                        ChargeControlBus.battery.value = SharedBatterySample(
                            snapshot = battery,
                            capturedAtElapsedMillis = SystemClock.elapsedRealtime(),
                        )
                        // evaluate() also performs the safe cosa=0 restoration when
                        // the master switch is off. Keeping that path in the normal
                        // cycle prevents a disabled switch from leaving an old
                        // offset active after a settings change.
                        val status = controller.evaluate(settings, battery, screenOn)
                        ChargeControlBus.status.value = status
                        if (lastOverlayEnabled != settings.overlayKeepAliveEnabled) {
                            lastOverlayEnabled = settings.overlayKeepAliveEnabled
                            overlayScope.launch { overlay.sync(settings.overlayKeepAliveEnabled) }
                        }
                        syncStatusNotification(status, battery, settings, force = immediateAction != null)
                        if (shouldRecordHistory(settings)) {
                            runCatching {
                                historyStore.record(battery, screenOn = screenOn)
                                    ?.let(ChartHistoryBus::append)
                            }.onFailure {
                                android.util.Log.w("OPPower", "History sample failed", it)
                            }
                        }
                        maybeRunDetection(settings, battery)
                        if (immediateAction != null) {
                            android.util.Log.i(
                                "OPPower",
                                "Immediate control action=$immediateAction " +
                                    "phase=${status.phase} targetGear=${status.desiredGear} " +
                                    "cosa=${status.appliedCosa}",
                            )
                        }
                        // Keep a failed native safety restore alive only while the
                        // display is interactive. Every Shizuku command is a Binder
                        // round trip plus a dumpsys call; retrying a missing charging
                        // service behind a dark screen would wake the SoC once per
                        // second all night. The loop re-checks after screen-on and
                        // notices still request an immediate update.
                        val retryingSafetyRestore =
                            status.phase == ControllerPhase.ERROR &&
                                status.levelZeroMode &&
                                screenOn
                        if (!battery.acOnline && !retryingSafetyRestore) {
                            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            keepRunning = false
                        } else if (
                            !hasActiveControlTarget(settings) &&
                            !retryingSafetyRestore
                        ) {
                            idleBackoff = true
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // Keep the control loop alive after a transient Shizuku,
                        // battery or notification failure. The next cycle retries.
                        android.util.Log.w("OPPower", "Charge control cycle failed", error)
                    }
                    if (!keepRunning) break
                    // The notification obeys its configured cadence. Chart history is
                    // throttled by ChartHistoryStore to LLPower's sampling intervals.
                    // When no control feature is enabled the one-second cadence only
                    // burns wakeups, so the loop idles until a settings change arrives.
                    val remaining = if (idleBackoff) {
                        CONTROL_CYCLE_MILLIS
                    } else {
                        (CONTROL_CYCLE_MILLIS -
                            (SystemClock.elapsedRealtime() - cycleStartedAt)).coerceAtLeast(0L)
                    }
                    if (remaining > 0L) {
                        withTimeoutOrNull(remaining) { ChargeControlBus.wakeRequests.receive() }
                    }
                }
            } finally {
                controlLoopStarted = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        android.util.Log.i(
            "OPPower",
            "ChargeControlService started action=${action ?: "sticky-restart"}",
        )
        // Preferences are applied synchronously to the in-process instance, but
        // the service may have been created before the toggle was saved. Reload
        // on every command so the immediate pass always sees the new target.
        cachedSettings = prefs.load()
        val settings = cachedSettings
        val enabled = settings.masterEnabled ||
            settings.nativeLevelZeroEnabled ||
            settings.liveNotificationEnabled
        if (action == ACTION_RESUME_NOTIFICATION) {
            if (!enabled) return START_NOT_STICKY
            notificationDismissed = false
        }
        if (action in IMMEDIATE_CONTROL_ACTIONS && !enabled) {
            // Turning off the last control feature must take one explicit safe
            // restore pass before the service exits. Relying on the prior loop
            // is racy: stopSelf() can cancel the only retry path first.
            restoreAfterControlsDisabled()
            return START_NOT_STICKY
        }

        val loopWasRunning = controlLoopStarted
        if (action in IMMEDIATE_CONTROL_ACTIONS) {
            // A new loop performs its first cycle immediately. An existing loop
            // receives the conflated wake and runs the action without spawning a
            // second evaluator that would duplicate Shizuku queries and writes.
            pendingImmediateAction = action
        }
        if (enabled) {
            // Android requires a foreground notification promptly. Start it with
            // an inexpensive placeholder, then fill real battery data on IO.
            if (!foregroundStarted) {
                startForeground(
                    NOTIFICATION_ID,
                    notification("0.0", "等待充电状态", "", BatterySnapshot(), settings),
                )
                foregroundStarted = true
            }
            startControlLoop()
            if (loopWasRunning && action in IMMEDIATE_CONTROL_ACTIONS) {
                ChargeControlBus.requestUpdate()
            }
        }
        if (action == ACTION_RESTORE_ALL) {
            scope.launch {
                val status = controller.restoreAll(forceNativeRestore = true)
                ChargeControlBus.status.value = status
                val settings = cachedSettings
                val battery = batteryReader.snapshot(settings)
                if (status.phase == ControllerPhase.ERROR && status.levelZeroMode) {
                    // A failed native safety restore must keep a local retry path
                    // even when both the master switch and live notification are off.
                    // The retry loop owns the notification from here on.
                    startControlLoop()
                    ChargeControlBus.requestUpdate()
                    return@launch
                }
                syncStatusNotification(
                    status,
                    battery,
                    settings,
                    force = true,
                )
            }
        }
        if (action == ACTION_HISTORY_SETTING_CHANGED) {
            scope.launch {
                if (!cachedSettings.historyEnabled) {
                    historyStore.clear()
                    ChartHistoryBus.reset()
                }
            }
        }

        return if (enabled) START_STICKY else START_NOT_STICKY
    }

    private fun restoreAfterControlsDisabled() {
        scope.launch {
            val status = controller.restoreAll(forceNativeRestore = true)
            ChargeControlBus.status.value = status
            android.util.Log.i(
                "OPPower",
                "All controls disabled; safety restore phase=${status.phase}",
            )
            if (status.phase == ControllerPhase.ERROR && status.levelZeroMode) {
                // This path is reached with the service still running but no
                // foreground notification: keep serving the retry until the safe
                // level is confirmed, otherwise stopSelf() would hand control to
                // a START_NOT_STICKY restart that exits immediately.
                if (!foregroundStarted) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification("—", "正在恢复系统默认", status.message, batteryReader.snapshot(cachedSettings), cachedSettings),
                    )
                    foregroundStarted = true
                }
                startControlLoop()
                ChargeControlBus.requestUpdate()
                return@launch
            }
            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (::prefsFile.isInitialized && ::prefsListener.isInitialized) {
            prefsFile.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        if (::overlay.isInitialized) overlay.remove()
        scope.cancel()
        if (::overlayScope.isInitialized) overlayScope.cancel()
        // Unplugging and disabling the final active control already restore the
        // system settings synchronously in the control loop. A system-driven service
        // destroy must not undo them: doing so caused background power drops and
        // made reopening the app silently reinstate the target gear.
        android.util.Log.i(
            "OPPower",
            "ChargeControlService destroyed; persisted control intent retained",
        )
        ChargeControlBus.status.value = ControllerStatus(
            phase = ControllerPhase.DISABLED,
            message = "控制服务已停止",
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 息屏后的充电检测。页面在前台时由 ViewModel 负责，这里直接跳过，避免同一份
     * `power_supply` 被读两遍。检测结论只用于展示与提醒，不参与控制决策。
     */
    private fun maybeRunDetection(settings: RuntimeSettings, battery: BatterySnapshot) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!battery.acOnline) {
            pluggedAtElapsed = null
            detectPowerWindow.clear()
            detectLevelWindow.clear()
            lastAnomalySignature = ""
            fullAlerted = false
            lastDetectionAt = 0L
            return
        }
        if (pluggedAtElapsed == null) pluggedAtElapsed = nowElapsed
        if (!settings.chargeDetectEnabled || ChargeControlBus.pageVisible) return
        val interval = settings.detectionIntervalSeconds.coerceIn(3, 60) * 1_000L
        if (lastDetectionAt != 0L && nowElapsed - lastDetectionAt < interval) return
        lastDetectionAt = nowElapsed

        detectPowerWindow.addLast(battery.powerW)
        while (detectPowerWindow.size > 30) detectPowerWindow.removeFirst()
        val nowWall = System.currentTimeMillis()
        detectLevelWindow.addLast(nowWall to battery.levelPercent)
        while (detectLevelWindow.size > 40) detectLevelWindow.removeFirst()
        shizuku.readPowerSupplyDump()?.let { supplyDump = it }

        val session = ChargeControlBus.insight.value
        val computed = ChargeInspector.analyze(
            dump = supplyDump.orEmpty(),
            acOnline = true,
            levelPercent = battery.levelPercent,
            currentMa = battery.currentMa,
            voltageMv = battery.voltageMv,
            powerW = battery.powerW,
            batteryTempC = battery.temperatureC,
            batteryStatus = battery.status,
            pluggedForMillis = pluggedAtElapsed?.let { nowElapsed - it },
            recentPower = detectPowerWindow.toList(),
            recentLevels = detectLevelWindow.toList(),
            doubleCell = settings.doubleCellCorrection,
            sessionChargeMah = session.chargeMahThisSession,
            sessionAveragePowerW = session.inputPowerW.takeIf { it > 1f },
        )
        insight = computed
        ChargeControlBus.insight.value = computed

        val signature = computed.anomalies.joinToString("|")
        if (settings.anomalyAlertEnabled && signature.isNotEmpty() && signature != lastAnomalySignature) {
            postAlert("充电状态异常", computed.anomalies.first(), settings)
        }
        lastAnomalySignature = signature

        if (settings.fullChargeAlertEnabled && battery.levelPercent >= 100 && !fullAlerted) {
            fullAlerted = true
            postAlert("电池已充满", "建议拔掉充电器，减少满电静置时间", settings)
        }
    }

    /** 提醒通知独立通道与独立 ID，滑动即可清除，不会覆盖常驻状态通知。 */
    private fun postAlert(title: String, body: String, settings: RuntimeSettings) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                this,
                10,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, StatusNotificationChannels.ALERT)
                .setSmallIcon(R.drawable.ic_notification_power)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        }.onFailure { android.util.Log.w("OPPower", "Alert notification failed", it) }
    }

    private fun syncStatusNotification(
        status: ControllerStatus,
        battery: BatterySnapshot,
        settings: RuntimeSettings,
        force: Boolean = false,
    ) {
        if (!force && notificationDismissed) return
        // Only a foreground service may post this notification. When neither
        // charging control is enabled there is nothing to show; noticing that
        // must not tear down the notification without clearing the foreground
        // state, or the next startForeground() call would crash.
        if (!hasActiveControlTarget(settings)) {
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            lastNotificationText = ""
            lastNotificationTitle = ""
            lastNotificationAt = 0L
            return
        }
        val full = battery.levelPercent >= 100 || battery.status == 5 || status.phase == ControllerPhase.FULL_PAUSED
        // A foreground service must keep a visible notification. The two auto-close
        // settings therefore switch to a compact notification instead of removing it.
        val detailAllowed = settings.liveNotificationEnabled &&
            !(settings.notificationAutoCloseOnFull && full) &&
            !(settings.notificationAutoCloseOnScreenOff && !screenOn)
        val power = notificationPower(battery, settings)
        val data = if (detailAllowed) {
            notificationBatteryData(battery, settings)
        } else {
            if (full) {
                "已充满 · 充电增强已交还系统"
            } else {
                "充电保护正在运行"
            }
        }
        val tier = if (detailAllowed) {
            val base = when {
                status.phase == ControllerPhase.ACTIVE && status.levelZeroMode -> "高功率保活"
                status.phase != ControllerPhase.ACTIVE && status.levelZeroMode -> phaseLabel(status.phase)
                else -> powerTierLabel(status.desiredGear)
            }
            // 二级限流生效时补一句限到多少，否则通知里完全看不出档位之外还压了一层。
            if (status.appliedCurrentLimitMa > 0) {
                "$base · 限流 ${status.appliedCurrentLimitMa} mA"
            } else {
                base
            }
        } else {
            ""
        }
        val title = power
        val text = "$data\n$tier"
        val now = System.currentTimeMillis()
        val interval = if (screenOn) {
            settings.notificationRefreshSeconds.coerceIn(2, 30)
        } else {
            settings.notificationScreenOffRefreshSeconds.coerceIn(3, 300)
        }
        val refreshMillis = interval * 1_000L
        if (!force && foregroundStarted && text == lastNotificationText && title == lastNotificationTitle) return
        if (!force && foregroundStarted && title == lastNotificationTitle && now - lastNotificationAt < refreshMillis) return
        val notification = notification(power, data, tier, battery, settings)
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        lastNotificationText = text
        lastNotificationTitle = title
        lastNotificationAt = now
    }

    private fun notificationBatteryData(
        battery: BatterySnapshot,
        settings: RuntimeSettings,
    ): String {
        if (settings.notificationStyle == com.oppower.model.NotificationStyle.COMPACT) {
            return "${battery.levelPercent}%"
        }
        val values = buildList {
            if (settings.notificationShowBatteryPercent) add("${battery.levelPercent}%")
            if (settings.notificationShowVoltage && battery.sampleValid) {
                add("${(battery.voltageMv / 1000f).oneDecimal()} V")
            }
            if (settings.notificationShowCurrent && battery.sampleValid) {
                add("${battery.currentMa} mA")
            }
            if (settings.notificationShowTemperature) add("${battery.temperatureC.oneDecimal()}°C")
        }
        val text = values.joinToString("  ·  ")
        return if (settings.notificationStyle == com.oppower.model.NotificationStyle.DETAIL) {
            listOf(text, insight.stage.label, insight.capability?.protocolLabel)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
        } else {
            text
        }
    }

    private fun notification(
        power: String,
        data: String,
        tier: String,
        battery: BatterySnapshot,
        settings: RuntimeSettings,
    ): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, NotificationDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val level = battery.levelPercent.coerceIn(0, 100)
        val body = listOf(data, tier).filter { it.isNotBlank() }.joinToString("  ·  ")
        val usesLiveStyle = Build.VERSION.SDK_INT >= 36
        val title = if (battery.sampleValid) "$power W" else "功率读取中"
        val builder = Notification.Builder(this, if (usesLiveStyle) StatusNotificationChannels.LIVE else StatusNotificationChannels.STATUS)
            // Android requires a small icon. ColorOS can replace ordinary
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setDeleteIntent(dismissIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(
                if (usesLiveStyle) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC,
            )
            .setOngoing(usesLiveStyle)
            .setOnlyAlertOnce(true)
            .setShowWhen(usesLiveStyle)
            .setWhen(if (usesLiveStyle) System.currentTimeMillis() else 0L)
        if (usesLiveStyle) {
            builder.setStyle(liveProgressStyle(level, settings.masterEnabled || settings.nativeLevelZeroEnabled))
        } else {
            builder.setProgress(100, level, false)
        }
        builder.addExtras(
            Bundle().apply {
                if (usesLiveStyle) {
                    putBoolean("android.requestPromotedOngoing", true)
                } else {
                    putBoolean("android.app.preferSmallIcon", true)
                }
            },
        )
        builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    @RequiresApi(36)
    private fun liveProgressStyle(level: Int, controllerEnabled: Boolean): Notification.ProgressStyle =
        Notification.ProgressStyle()
            .setProgressTrackerIcon(
                Icon.createWithResource(this, R.drawable.ic_notification_bolt)
                    .setTint(if (controllerEnabled) AMBER else Color.WHITE),
            )
            .addProgressSegment(
                Notification.ProgressStyle.Segment(100)
                    .setColor(BATTERY_GREEN)
                    .setId(0),
            )
            .setProgress(level)

    private fun notificationPower(battery: BatterySnapshot, settings: RuntimeSettings): String = when {
        !battery.sampleValid -> "—"
        settings.notificationIntegerPower && kotlin.math.abs(battery.powerW) >= 1f ->
            "${battery.powerW.roundToInt()}"
        else -> battery.powerW.oneDecimal()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(StatusNotificationChannels.STATUS, "充电状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示亮屏快充状态、电池功率和充电保护"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                StatusNotificationChannels.ALERT,
                "充电提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "充电异常与充满提醒，可单独关闭"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
            },
        )
        if (Build.VERSION.SDK_INT >= 36) {
            manager.createNotificationChannel(
                NotificationChannel(
                    StatusNotificationChannels.LIVE,
                    "实时充电状态",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "显示功率、电量、温度与亮屏快充档位"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    private fun phaseLabel(phase: ControllerPhase): String = when (phase) {
        ControllerPhase.DISABLED -> "已关闭"
        ControllerPhase.WAITING_FOR_POWER -> "等待接电"
        ControllerPhase.ACTIVE -> "快速充电中"
        ControllerPhase.TEMPERATURE_PAUSED -> "温度保护"
        ControllerPhase.SOC_PAUSED -> "电量保护"
        ControllerPhase.FULL_PAUSED -> "已充满"
        ControllerPhase.SCREEN_OFF_PAUSED -> "息屏暂停"
        ControllerPhase.ERROR -> "需要检查"
    }

    companion object {
        const val NOTIFICATION_ID = 2108
        const val ALERT_NOTIFICATION_ID = 2109
        const val ACTION_RESTORE_ALL = "com.oppower.action.RESTORE_ALL"
        const val ACTION_HISTORY_SETTING_CHANGED = "com.oppower.action.HISTORY_SETTING_CHANGED"
        const val ACTION_RESUME_NOTIFICATION = "com.oppower.action.RESUME_NOTIFICATION"
        const val ACTION_CONTROL_SETTINGS_CHANGED = "com.oppower.action.CONTROL_SETTINGS_CHANGED"
        const val ACTION_POWER_CONNECTED = "com.oppower.action.POWER_CONNECTED"
        private val BATTERY_GREEN = android.graphics.Color.rgb(76, 175, 80)
        private val AMBER = android.graphics.Color.rgb(255, 193, 7)
        private const val CONTROL_CYCLE_MILLIS = 1_000L
        private val IMMEDIATE_CONTROL_ACTIONS = setOf(
            ACTION_RESUME_NOTIFICATION,
            ACTION_CONTROL_SETTINGS_CHANGED,
            ACTION_POWER_CONNECTED,
        )

        @Volatile
        private var notificationDismissed = false

        internal fun markNotificationDismissed() {
            notificationDismissed = true
        }

        fun start(
            context: Context,
            action: String = ACTION_CONTROL_SETTINGS_CHANGED,
        ): Boolean {
            val settings = PreferencesStore(context).load()
            if (!settings.masterEnabled &&
                !settings.nativeLevelZeroEnabled &&
                !settings.liveNotificationEnabled
            ) return false
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (!plugged) return false
            val intent = Intent(context, ChargeControlService::class.java)
                .setAction(action)
            return runCatching {
                context.startForegroundService(intent)
                true
            }.getOrElse { error ->
                android.util.Log.e("OPPower", "Unable to start charge control service", error)
                false
            }
        }

        fun startWhenAvailable(
            context: Context,
            attempts: Int = 6,
            retryDelayMillis: Long = 300L,
            action: String = ACTION_CONTROL_SETTINGS_CHANGED,
            onFinished: (() -> Unit)? = null,
        ) {
            require(attempts > 0)
            val appContext = context.applicationContext
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var completedAttempts = 0
            val attempt = object : Runnable {
                override fun run() {
                    completedAttempts += 1
                    val settings = PreferencesStore(appContext).load()
                    val enabled = settings.masterEnabled ||
                        settings.nativeLevelZeroEnabled ||
                        settings.liveNotificationEnabled
                    if (start(appContext, action) || !enabled || completedAttempts >= attempts) {
                        onFinished?.invoke()
                        return
                    }
                    handler.postDelayed(this, retryDelayMillis)
                }
            }
            handler.post(attempt)
        }

        fun restore(context: Context) {
            val intent = Intent(context, ChargeControlService::class.java).setAction(ACTION_RESTORE_ALL)
            runCatching { context.startService(intent) }
        }

        fun historySettingsChanged(context: Context) {
            val intent = Intent(context, ChargeControlService::class.java)
                .setAction(ACTION_HISTORY_SETTING_CHANGED)
            runCatching { context.startService(intent) }
        }

        fun controlSettingsChanged(context: Context) {
            val appContext = context.applicationContext
            val settings = PreferencesStore(appContext).load()
            if (settings.masterEnabled ||
                settings.nativeLevelZeroEnabled ||
                settings.liveNotificationEnabled
            ) {
                startWhenAvailable(appContext, action = ACTION_CONTROL_SETTINGS_CHANGED)
                return
            }
            // This transition may be turning the last enabled feature off. The
            // existing service still needs one command so it can restore cosa=0.
            val intent = Intent(appContext, ChargeControlService::class.java)
                .setAction(ACTION_CONTROL_SETTINGS_CHANGED)
            runCatching { appContext.startService(intent) }
        }

        fun resumeNotification(context: Context) {
            start(context, ACTION_RESUME_NOTIFICATION)
        }
    }
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        ChargeControlService.markNotificationDismissed()
    }
}

/**
 * While charging control is enabled the notification reflects the live control
 * state, so a foreground service may always show it. The optional notification
 * is only offered when no charging control is enabled.
 */
internal fun hasActiveControlTarget(settings: RuntimeSettings): Boolean =
    settings.masterEnabled ||
        settings.nativeLevelZeroEnabled ||
        settings.liveNotificationEnabled

/** The persisted history switch only counts while a charging session is active. */
internal fun shouldRecordHistory(settings: RuntimeSettings): Boolean =
    settings.historyEnabled && hasActiveControlTarget(settings)
