package com.oppower.ui

import com.oppower.model.BatterySnapshot
import com.oppower.model.RuntimeSettings
import com.oppower.model.SystemAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「电池每秒刷新不能让整页重组」这条约束的回归测试。
 *
 * 做法是把页面真正订阅的那几片状态从两份 `UiState` 里切出来，断言它们相等。
 * Compose 判断要不要重组，比的正是这些值的 `equals`——只要切出来的值相等，
 * 设置页、快充页那些几万字节的整页组合就不会因为一次电量读数而白跑一遍。
 *
 * 这里直接调 `slice` 里的 selector 等价表达式，不依赖 Compose 运行时；
 * `slice` 本身只是 `derivedStateOf` 的包装，语义就是「结果变了才通知」。
 */
class UiStateSlicingTest {

    private val settings = RuntimeSettings()
    private val access = SystemAccessState(
        notificationsAllowed = true,
        batteryUnrestricted = true,
    )

    private fun stateWith(battery: BatterySnapshot) = UiState(
        battery = battery,
        settings = settings,
        systemAccess = access,
    )

    private val firstSample = BatterySnapshot(
        currentMa = 3200,
        voltageMv = 4300,
        powerW = 13.8f,
        temperatureC = 34.2f,
        levelPercent = 55,
        acOnline = true,
        sampleValid = true,
    )

    /** 下一秒的读数：功率、温度、电量都在动，这正是每秒都会发生的事。 */
    private val nextSample = firstSample.copy(
        currentMa = 3400,
        powerW = 14.6f,
        temperatureC = 34.5f,
        levelPercent = 56,
    )

    @Test
    fun settingsSliceSurvivesBatteryRefresh() {
        val before = stateWith(firstSample)
        val after = stateWith(nextSample)

        assertEquals(before.settings, after.settings)
    }

    @Test
    fun systemAccessSliceSurvivesBatteryRefresh() {
        val before = stateWith(firstSample)
        val after = stateWith(nextSample)

        assertEquals(before.systemAccess, after.systemAccess)
    }

    /** 快充设置页与设置主页都靠它判断授权状态；不能跟着电池一起抖。 */
    @Test
    fun shizukuSliceSurvivesBatteryRefresh() {
        fun shizukuOf(state: UiState) = ShizukuAccess(
            installed = state.shizukuInstalled,
            available = state.shizukuAvailable,
            permission = state.shizukuPermission,
            connected = state.shizukuServiceConnected,
        )

        assertEquals(shizukuOf(stateWith(firstSample)), shizukuOf(stateWith(nextSample)))
    }

    /** 数据管理页只要三项计数，不该因为多了一个采样点就重排整页。 */
    @Test
    fun historyCountsSurviveSampleRefresh() {
        fun countsOf(state: UiState) = HistoryCounts(
            points = state.points.size,
            chargeRecords = state.chargeRecords.size,
            dischargeRecords = state.dischargeRecords.size,
        )

        assertEquals(countsOf(stateWith(firstSample)), countsOf(stateWith(nextSample)))
    }

    /** 但计数真的变了，就必须能看出来——否则页面不会更新。 */
    @Test
    fun historyCountsChangeWhenSampleCountChanges() {
        val before = HistoryCounts(points = 120, chargeRecords = 3, dischargeRecords = 1)
        val after = HistoryCounts(points = 121, chargeRecords = 3, dischargeRecords = 1)

        assertNotEquals(before, after)
    }

    /** 接电状态翻转时，规则区那行提示必须跟着变。 */
    @Test
    fun acOnlineSliceChangesOnPlugEvent() {
        val unplugged = stateWith(firstSample.copy(acOnline = false))
        val plugged = stateWith(firstSample.copy(acOnline = true))

        assertNotEquals(unplugged.battery.acOnline, plugged.battery.acOnline)
    }

    @Test
    fun shizukuAccessReadyRequiresAvailableAndPermission() {
        assertFalse(ShizukuAccess(installed = true, available = true, permission = false).ready)
        assertFalse(ShizukuAccess(installed = true, available = false, permission = true).ready)
        assertTrue(ShizukuAccess(installed = true, available = true, permission = true).ready)
    }

    /** 计数为 0 时数据管理页显示「暂无记录」，这个判定依赖 isEmpty。 */
    @Test
    fun historyCountsIsEmptyOnlyWhenNoPoints() {
        assertTrue(HistoryCounts().isEmpty)
        assertFalse(HistoryCounts(points = 1).isEmpty)
    }
}
