package com.oppower.service

import com.oppower.battery.ChargeInsight
import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

data class SharedBatterySample(
    val snapshot: BatterySnapshot,
    val capturedAtElapsedMillis: Long,
)

object ChargeControlBus {
    val status = MutableStateFlow(ControllerStatus())
    val battery = MutableStateFlow<SharedBatterySample?>(null)

    /** 充电检测结论；页面可见时由 ViewModel 产出，息屏后由服务接管。 */
    val insight = MutableStateFlow(ChargeInsight())

    /**
     * 页面是否在前台。前台时由 ViewModel 负责读取 power_supply，服务不再重复查询，
     * 避免同一次检测被两处各做一遍。
     */
    @Volatile
    var pageVisible: Boolean = false

    val wakeRequests = Channel<Unit>(Channel.CONFLATED)

    fun requestUpdate() {
        wakeRequests.trySend(Unit)
    }
}
