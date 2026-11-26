package com.oppower.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri

const val QQ_GROUP_NUMBER = "1126197031"
const val QQ_GROUP_INVITE = "https://qm.qq.com/q/eXdtZrzjKo"

/**
 * Shizuku 的四项状态打成一个值。
 *
 * 被打包的原因不是「少写几个参数」，而是**重组判定**：把这四项拆成四个
 * `State<Boolean>` 传进页面，页面里读任意一个都会让整页订阅它；打成一个
 * `State<ShizukuAccess>` 后，页面只在四项里有任何一项真正翻转时才重组——
 * 而这四项本来就一起变。
 */
data class ShizukuAccess(
    val installed: Boolean = false,
    val available: Boolean = false,
    val permission: Boolean = false,
    val connected: Boolean = false,
) {
    val ready: Boolean get() = available && permission
}

/**
 * 数据管理页要的三项计数。
 *
 * 原来直接传 `UiState`，等于把整个采样列表（最多 8 小时 × 上千个点）交给页面比较；
 * 页面真正用到的只有长度和「是否为空」。收成三个 Int 后，新增采样点不会让页面重组，
 * 只有数量真的跨过阈值时才更新。
 */
data class HistoryCounts(
    val points: Int = 0,
    val chargeRecords: Int = 0,
    val dischargeRecords: Int = 0,
) {
    val isEmpty: Boolean get() = points == 0
}

fun joinQqGroup(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("OPPower QQ 群号", QQ_GROUP_NUMBER))
    Toast.makeText(context, "群号 $QQ_GROUP_NUMBER 已复制", Toast.LENGTH_SHORT).show()
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, QQ_GROUP_INVITE.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure {
        Toast.makeText(context, "无法打开邀请链接，群号已复制", Toast.LENGTH_LONG).show()
    }
}
