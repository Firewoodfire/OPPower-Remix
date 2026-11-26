package com.oppower.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedCommandsTest {
    @Test
    fun acceptsOnlyExpectedSettingsCommands() {
        assertTrue(AllowedCommands.matches("settings get global cosa_ctc_value"))
        assertTrue(AllowedCommands.matches("settings put global cosa_ctc_value 0"))
        assertTrue(AllowedCommands.matches("settings put global cosa_ctc_value -9"))
        assertTrue(AllowedCommands.matches("settings put global cosa_ctc_value -10"))
        assertTrue(AllowedCommands.matches("settings get system oplus_camera_3rd_activity"))
        assertTrue(AllowedCommands.matches("settings get system oplus_float_window_show"))
        assertTrue(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 00"))
        assertTrue(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 01"))
        assertTrue(AllowedCommands.matches("settings put system oplus_float_window_show 00"))
        assertTrue(AllowedCommands.matches("settings put system oplus_float_window_show 01"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 0"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 2"))
        assertTrue(AllowedCommands.matches("getprop persist.sys.horae.enable"))
        assertTrue(AllowedCommands.matches("getprop init.svc.horae"))
    }

    /**
     * 白名单里的两条正则必须两端锚定。曾经它们没有 `^` / `$`，
     * 于是 `settings put global cosa_ctc_value 0; reboot` 也能匹配 ——
     * 而 execute() 是把这个字符串整串交给 `sh -c` 的。
     */
    @Test
    fun regexEntriesAreAnchoredAtBothEnds() {
        listOf(
            "settings put global cosa_ctc_value 0; reboot",
            "settings put global cosa_ctc_value 0 && reboot",
            "settings put global cosa_ctc_value 0 | reboot",
            "settings put global cosa_ctc_value 0 \$(reboot)",
            "settings put global cosa_ctc_value 0\nreboot",
            " prefix settings put global cosa_ctc_value 0",
            "settings put global cosa_ctc_value 0 suffix",
            "settings put system oplus_float_window_show 00; reboot",
            "settings put system oplus_camera_3rd_activity 01 && reboot",
            "settings put system oplus_float_window_show 00 trailing",
        ).forEach { command ->
            assertFalse("不能放行拼接命令：$command", AllowedCommands.matches(command))
        }
    }

    @Test
    fun getpropAllowlistRejectsArbitraryPropertyNames() {
        listOf(
            "getprop ro.build.fingerprint",
            "getprop",
            "getprop persist.sys.horae.enable; reboot",
            "setprop persist.sys.horae.enable 0",
        ).forEach { command ->
            assertFalse("不属于白名单：$command", AllowedCommands.matches(command))
        }
    }

    @Test
    fun privilegedBatteryDumpUsesCurrentAndVoltageFromOneSample() {
        val dump = """
            Current OPLUS Battery Service state:
              Charger voltage : 19
              Battery current : 493
            Current Battery Service state:
              AC powered: true
              voltage: 3978
              temperature: 405
        """.trimIndent()

        assertEquals(
            com.oppower.battery.PrivilegedBatteryReading(currentMa = 493, voltageMv = 3_978),
            parsePrivilegedBatteryDump(dump),
        )
    }

    @Test
    fun privilegedBatteryDumpRequiresAnActualCurrentField() {
        assertNull(parsePrivilegedBatteryDump("voltage: 3978"))
        assertEquals(
            com.oppower.battery.PrivilegedBatteryReading(currentMa = 0, voltageMv = 0),
            parsePrivilegedBatteryDump("Battery current : 0"),
        )
    }

    @Test
    fun thermalRefreshAliasKeepsTheSameIntegerValue() {
        mapOf("0" to "00", "00" to "0", "1" to "01", "01" to "1").forEach { (source, alias) ->
            val write = thermalRefreshWrite(source)
            assertEquals(source.toInt(), alias.toInt())
            assertEquals(ThermalRefreshWrite(alias, source.toInt()), write)
        }
        assertEquals(ThermalRefreshWrite("0", 0), thermalRefreshWrite("null"))
        assertNull(thermalRefreshWrite("2"))
        assertNull(thermalRefreshWrite(""))
    }

    @Test
    fun rejectsShellInjectionAndUnknownSettings() {
        assertFalse(AllowedCommands.matches("am broadcast -a oplus.intent.action.ACTION_THERMAL_CONTROL_USER_SYSTEM -p com.oplus.battery --es name set_charge --ei level 0 --ei speedChargeAdd -2 --es foregroundPkgName com.oppower"))
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value -11"))
        // 上界与 GearCalculator.COSA_MAX 对齐：-10…9。10 越界。
        assertTrue(AllowedCommands.matches("settings put global cosa_ctc_value 9"))
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value 10"))
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value 0; reboot"))
        assertFalse(AllowedCommands.matches("settings put global another_value 1"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 2"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 1"))
        assertFalse(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 2"))
        assertFalse(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 00; reboot"))
        assertFalse(AllowedCommands.matches("settings put system oplus_float_window_show 2"))
        assertFalse(AllowedCommands.matches("settings put system oplus_float_window_show 00; reboot"))
        assertFalse(AllowedCommands.matches("settings get system smart_charge_switch_state"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 0"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 1"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 2"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 0; reboot"))
        assertFalse(AllowedCommands.matches("settings put system customize_charge_switch_state 1"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 19"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 20 s16 0"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 20 s16 0; reboot"))
        assertFalse(
            AllowedCommands.matches(
                "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 1",
            ),
        )
        assertFalse(
            AllowedCommands.matches(
                "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 0; reboot",
            ),
        )
        assertFalse(AllowedCommands.matches("rm -rf /data/local/tmp/oppower"))
    }

    /**
     * 限流走的是新增的 AIDL 方法，节点路径写死在特权端；shell 通道必须一如既往地
     * 拒绝一切 sysfs 读写，否则等于把 root 写节点能力开放给上层。
     */
    @Test
    fun sysfsChargeLimitNodesStayOutOfTheShellChannel() {
        listOf(
            "cat /sys/class/power_supply/battery/constant_charge_current_max",
            "echo 3000000 > /sys/class/power_supply/battery/constant_charge_current_max",
            "echo 3000000 > /sys/class/power_supply/main/constant_charge_current_max",
            "echo 1000000 > /sys/class/power_supply/battery/input_current_limit",
            "sh -c 'echo 0 > /sys/class/power_supply/battery/constant_charge_current_max'",
            "settings put global cosa_ctc_value 0 && echo 1 > /sys/class/power_supply/battery/input_current_limit",
        ).forEach { command ->
            assertFalse("shell 通道不应允许：$command", AllowedCommands.matches(command))
        }
    }

    @Test
    fun chargeLimitNodePathsAreFixedNotCallerSupplied() {
        ChargeLimitNodes.LIMIT_FIELDS.forEach { field ->
            val paths = ChargeLimitNodes.pathsFor(field)
            assertTrue("每个字段至少一个候选节点", paths.isNotEmpty())
            paths.forEach { path ->
                assertTrue("必须是固定前缀", path.startsWith("/sys/class/power_supply/"))
                assertEquals("路径末尾必须就是该字段名", field, path.substringAfterLast('/'))
                assertFalse("不允许路径穿越", path.contains(".."))
            }
        }
    }
}
