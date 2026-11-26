package com.oppower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.itos.coui.slider.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppower.controller.ChargeCurrentLimit
import com.oppower.model.BaseTier
import com.oppower.model.ControllerStatus
import com.oppower.model.GearReadResult
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import com.oppower.model.SocTierRule
import com.oppower.model.TierRule
import com.oppower.model.conservatismRank
import com.oppower.model.friendlyLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group

/**
 * 强制亮屏快充设置页。
 *
 * 版式完全按 COUI 的 `Group / Item` 语义重排：每个区块是一张 24dp 超椭圆卡片，
 * 标题在卡片外、内缩 32dp，说明在卡片下方；卡片之间 16dp，带标题的区块上方再多 12dp。
 * 只有"基础功率档位"与"规则编辑"这类需要并排比较的内容才用自定义块，其余全部走
 * [CouiRow] / [CouiSwitchRow]，以保证行高、图标位、按压反馈与设置主页一致。
 */
@Composable
internal fun ChargeSettingsPage(
    settings: State<RuntimeSettings>,
    acOnline: State<Boolean>,
    controllerStatus: State<ControllerStatus>,
    gearReadResult: State<GearReadResult?>,
    shizuku: State<ShizukuAccess>,
    onBack: () -> Unit,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onRestore: () -> Unit,
    onRequestShizuku: () -> Unit,
    onReadGear: () -> Unit,
) {
    // 这一层只订「设置」：电池每秒刷新不会让整页重排，只有总开关、档位、规则真的
    // 变了才重排。`acOnline` / 控制器文案 / 档位读取结果都只影响个别分组，交给
    // 那几个分组自己去读，别在这里展开。
    val current by settings
    // 例外：规则区的「当前状态」标签要跟着接电状态变，但它只是一行文字，
    // 读一次窄状态比让整页重组便宜得多。
    val acOnlineNow by acOnline
    val temperatureInputs = remember {
        mutableStateListOf(*current.customRules.temperatureRules.map { it.threshold.oneDecimal() }.toTypedArray())
    }
    val socInputs = remember {
        mutableStateListOf(*current.customRules.socRules.map { it.thresholdPercent.toString() }.toTypedArray())
    }
    val colors = CouiPageDefaults.themeColors()

    CouiPage(
        title = "强制亮屏快充",
        subtitle = "接电后校准 · 拔电自动恢复",
        onBack = onBack,
    ) {
            MasterGroup(
                settingsState = settings,
                controllerStatus = controllerStatus,
                gearReadResult = gearReadResult,
                onSettingsChange = onSettingsChange,
                onReadGear = onReadGear,
            )

            ShizukuGroup(shizuku = shizuku, onRequestShizuku = onRequestShizuku)

            Group(
                title = "基础功率档位",
                footer = "规则只会在所选档位的基础上继续降档，不会越过它往上加。",
            ) {
                // 档位选择用 COUI 的分段控件，而不是三张自绘卡片：
                // 三档互斥、数量固定，正是 SegmentedControl 的语义。
                CouiChoiceRow(
                    title = "选择档位",
                    labels = enumValues<BaseTier>().map { it.label },
                    selectedIndex = enumValues<BaseTier>().indexOf(current.baseTier),
                    summary = current.baseTier.friendlyLabel,
                    onSelected = { index ->
                        onSettingsChange { it.copy(baseTier = enumValues<BaseTier>()[index]) }
                    },
                )
            }

            ChargeCurrentLimitGroup(
                settingsFlow = settings,
                controllerStatus = controllerStatus,
                permissionGranted = shizuku.value.permission,
                acOnlineNow = acOnline.value,
                onSettingsChange = onSettingsChange,
            )

            if (!current.customRulesEnabled) {
                DefaultRuleGroup(current)
            }

            Group(
                title = "自定义规则",
                footer = if (current.customRulesEnabled) {
                    "温度与电量各自判档、取更保守者；进入功率大档后再只按温度自动分档。"
                } else {
                    "关闭时按默认策略执行：温度升高降档，电量达上限后暂停。"
                },
            ) {
                CouiSwitchRow(
                    title = "启用自定义规则",
                    summary = if (current.customRulesEnabled) "温度和电量分别设置条件，再选择目标档位" else "当前使用默认策略",
                    checked = current.customRulesEnabled,
                    onCheckedChange = { enabled -> onSettingsChange { it.copy(customRulesEnabled = enabled) } },
                )

                AnimatedVisibility(
                    visible = current.customRulesEnabled,
                    enter = expandVertically(animationSpec = spring(dampingRatio = 0.82f)) + fadeIn(tween(180)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
                ) {
                    Column {
                        Divider()
                        RuleSectionLabel(
                            title = "当前状态",
                            trailing = if (acOnlineNow) "已接电 · 自动执行" else "未接电 · 接电后执行",
                            trailingColor = if (acOnlineNow) colors.accent else colors.secondary,
                        )

                        Divider()
                        RuleSectionLabel("温度规则", "填写温度，再选择达到后使用的档位")
                        current.customRules.temperatureRules.forEachIndexed { index, rule ->
                            val input = temperatureInputs.getOrElse(index) { rule.threshold.oneDecimal() }
                            val numeric = input.toFloatOrNull()
                            val previous = current.customRules.temperatureRules.getOrNull(index - 1)?.threshold
                            val next = current.customRules.temperatureRules.getOrNull(index + 1)?.threshold
                            val minimum = previous?.plus(0.5f) ?: 30f
                            val maximum = next?.minus(0.5f) ?: 46f
                            val valid = numeric != null && numeric in minimum..maximum
                            RuleEditorRow(
                                title = "温度条件 ${index + 1}",
                                input = input,
                                suffix = "°C",
                                valid = input.isEmpty() || valid,
                                keyboardType = KeyboardType.Decimal,
                                onInput = { value ->
                                    if (value.isEmpty() || value.matches(Regex("\\d{0,2}(\\.\\d?)?"))) {
                                        temperatureInputs[index] = value
                                        value.toFloatOrNull()?.takeIf { it in minimum..maximum }?.let { parsed ->
                                            onSettingsChange { settings ->
                                                settings.copy(
                                                    customRules = settings.customRules.copy(
                                                        temperatureRules = settings.customRules.temperatureRules.mapIndexed { itemIndex, old ->
                                                            if (itemIndex == index) old.copy(threshold = parsed) else old
                                                        },
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                },
                                error = if (input.isNotEmpty() && !valid) "请输入 ${minimum.oneDecimal()}–${maximum.oneDecimal()}°C" else null,
                                selected = rule.target,
                                maximumPower = current.customRules.temperatureRules
                                    .getOrNull(index - 1)?.target ?: settingsBaseTier(current),
                                onSelected = { target ->
                                    onSettingsChange { settings ->
                                        settings.copy(
                                            customRules = settings.customRules.copy(
                                                temperatureRules = settings.customRules.temperatureRules.mapIndexed { itemIndex, old ->
                                                    if (itemIndex == index) TierRule(old.threshold, target) else old
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                        }

                        Divider()
                        RuleSectionLabel("电量规则", "电量只切换大档，不参与档内温度分档")
                        current.customRules.socRules.forEachIndexed { index, rule ->
                            val input = socInputs.getOrElse(index) { rule.thresholdPercent.toString() }
                            val numeric = input.toIntOrNull()
                            val previous = current.customRules.socRules.getOrNull(index - 1)?.thresholdPercent
                            val next = current.customRules.socRules.getOrNull(index + 1)?.thresholdPercent
                            val minimum = previous?.plus(1) ?: 1
                            val maximum = next?.minus(1) ?: 99
                            val valid = numeric != null && numeric in minimum..maximum
                            RuleEditorRow(
                                title = "电量条件 ${index + 1}",
                                input = input,
                                suffix = "%",
                                valid = input.isEmpty() || valid,
                                keyboardType = KeyboardType.Number,
                                onInput = { value ->
                                    if (value.length <= 3 && value.all(Char::isDigit)) {
                                        socInputs[index] = value
                                        value.toIntOrNull()?.takeIf { it in minimum..maximum }?.let { parsed ->
                                            onSettingsChange { settings ->
                                                settings.copy(
                                                    customRules = settings.customRules.copy(
                                                        socRules = settings.customRules.socRules.mapIndexed { itemIndex, old ->
                                                            if (itemIndex == index) old.copy(thresholdPercent = parsed) else old
                                                        },
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                },
                                error = if (input.isNotEmpty() && !valid) "请输入 $minimum–$maximum%" else null,
                                selected = rule.target,
                                maximumPower = current.customRules.socRules
                                    .getOrNull(index - 1)?.target ?: settingsBaseTier(current),
                                onSelected = { target ->
                                    onSettingsChange { settings ->
                                        settings.copy(
                                            customRules = settings.customRules.copy(
                                                socRules = settings.customRules.socRules.mapIndexed { itemIndex, old ->
                                                    if (itemIndex == index) SocTierRule(old.thresholdPercent, target) else old
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                        }

                        Divider()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("100% / 充满", style = CouiType.denseStrong, color = colors.secondary, modifier = Modifier.weight(1f))
                            Text("固定暂停快充增强", style = CouiType.dense, color = colors.title)
                        }
                    }
                }
            }

            if (current.customRulesEnabled) {
                CustomRulePreview(current)
            }

            Group(title = "自动调节说明") {
                Column(Modifier.padding(horizontal = CouiPageDefaults.rowPadding, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExplainLine("温度升高时降功率，跨多档也一次到位——保护温度优先。")
                    ExplainLine("温度跨过 0.5°C 分段并持续 5 秒才降档；回落后逐档回升，每档稳定 8 秒。")
                    ExplainLine("逐档回升是为了避免功率一步跳回最高、几十秒后又冲回阈值来回打摆。")
                    ExplainLine("温和策略只随温度调整，电量达到上限后暂停。")
                }
            }

            Box(
                Modifier
                    .padding(horizontal = CouiPageDefaults.horizontalMargin)
                    .padding(top = CouiPageDefaults.groupSpacing)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(capsuleShape(48.dp))
                    .background(colors.card)
                    .border(0.5.dp, colors.danger.copy(alpha = 0.42f), capsuleShape(48.dp))
                    .clickable(onClick = onRestore),
                contentAlignment = Alignment.Center,
            ) {
                Text("一键恢复系统默认", style = CouiType.itemTitle(), color = colors.danger)
            }
            CouiFooter("恢复后快充增强关闭，充电行为回到系统原生策略。")
    }
}

/** 总开关组：开关、息屏策略、档位读取合并在一张卡片里，靠分隔线切段。 */
@Composable
private fun MasterGroup(
    settingsState: State<RuntimeSettings>,
    controllerStatus: State<ControllerStatus>,
    gearReadResult: State<GearReadResult?>,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
    onReadGear: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    // `locales[0]` 在语言列表为空时会抛 IndexOutOfBoundsException（精简 ROM / 早期开机）。
    // 计算放进 remember：组合体里直接读 `Locale.getDefault()` 会被 lint 判为不可观察读取。
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
    }
    val settings by settingsState
    val status by controllerStatus
    val gear by gearReadResult
    Group(
        title = "快充增强",
        footer = "关闭总开关或一键恢复时，快充增强会恢复系统默认。",
    ) {
        CouiSwitchRow(
            title = "启用快充增强",
            summary = status.message,
            checked = settings.masterEnabled,
            onCheckedChange = { enabled -> onSettingsChange { it.copy(masterEnabled = enabled) } },
        )
        Divider()

        CouiRow(
            title = "当前充电档位",
            summary = gear?.let { result ->
                result.error?.let { "读取失败 · " + it }
                    ?: "基础档=" + (result.baseGear?.toString() ?: "--") +
                    " · cosa=" + (result.cosa?.toString() ?: "--") +
                    " · 计算=" + (result.calculatedGear?.toString() ?: "--") +
                    " · chargeVal=" + (result.chargeVal?.toString() ?: "--")
            } ?: "只读读取基础档与 cosa 计算档位",
            value = gear?.let { result ->
                result.error ?: result.gear?.toString() ?: "—"
            },
            valueColor = when {
                gear?.error != null -> colors.danger
                gear?.gear != null -> colors.accent
                else -> colors.summary
            },
            trailing = {
                Box(
                    Modifier
                        .padding(start = 12.dp)
                        .height(32.dp)
                        .clip(capsuleShape(32.dp))
                        .background(colors.accent.copy(alpha = 0.14f))
                        .clickable(onClick = onReadGear)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("读取", style = CouiType.denseStrong, color = colors.accent)
                }
            },
        )
        gear?.takeUnless { it.error != null }?.let { result ->
            Text(
                "公式：基础档 + cosa = 计算档位 · 来源：" +
                    (result.source ?: "系统 Thermal Levels + cosa_ctc_value") +
                    " · " + SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag(locale.toLanguageTag())).format(Date(result.readAtMillis)),
                style = CouiType.dense,
                color = colors.summary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CouiPageDefaults.rowPadding, end = CouiPageDefaults.rowPadding, bottom = 12.dp),
            )
        }

        Divider()
        CouiSwitchRow(
            title = "息屏也生效",
            summary = "关闭时，屏幕熄灭后暂停充电增强",
            checked = settings.screenOffBoostEnabled,
            onCheckedChange = { enabled ->
                onSettingsChange { it.copy(screenOffBoostEnabled = enabled) }
            },
        )
    }
}

/**
 * 二级限流：在离散档位之外，直接用毫安把充电电流压到目标值。
 *
 * UI 只负责给一个"目标上限"，实际写入值由 `ChargeCurrentLimit` 再按温度与电量收紧，
 * 因此这里把"你设的值"和"系统实际认的值"分开显示，避免看起来设了却没生效。
 */
@Composable
private fun ChargeCurrentLimitGroup(
    settingsFlow: State<RuntimeSettings>,
    controllerStatus: State<ControllerStatus>,
    permissionGranted: Boolean,
    acOnlineNow: Boolean,
    onSettingsChange: ((RuntimeSettings) -> RuntimeSettings) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val settings by settingsFlow
    val status by controllerStatus
    val enabled = settings.chargeCurrentLimitEnabled
    val manualMa = settings.chargeCurrentLimitMa
    val applied = status.appliedCurrentLimitMa
    val observed = status.observedCurrentLimitMa

    Group(
        title = "电流上限（二级限流）",
        footer = "档位是离散的，两档之间没有中间态；电流上限是连续量，用它把功率压到目标值。" +
            "写入只经 Shizuku 的受限通道，节点路径写死在特权端，上层只能传毫安值。",
    ) {
        CouiSwitchRow(
            title = "启用电流上限",
            summary = "在系统档位之外，额外按毫安限制实际充电电流",
            checked = enabled,
            onCheckedChange = { value ->
                onSettingsChange { it.copy(chargeCurrentLimitEnabled = value) }
            },
        )

        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(animationSpec = spring(dampingRatio = 0.82f)) + fadeIn(tween(180)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
        ) {
            Column {
                Divider()
                Column(
                    Modifier.padding(
                        horizontal = CouiPageDefaults.rowPadding,
                        vertical = CouiPageDefaults.contentPadding,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "目标上限",
                            style = CouiType.itemTitle(),
                            color = colors.title,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (manualMa > 0) "$manualMa mA" else "跟随档位",
                            style = CouiType.itemValue(),
                            color = colors.accent,
                        )
                    }
                    Text(
                        if (manualMa > 0) {
                            "手动值优先于档位折算：设得比档位基准高，等于放开该档位的电流约束；" +
                                "高温与高电量时仍会在此基础上收紧"
                        } else {
                            "按当前档位自动折算，档位越保守上限越低"
                        },
                        style = CouiType.itemSummary(),
                        color = colors.summary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Slider(
                        value = manualMa.toFloat(),
                        onValueChange = { value ->
                            // 0 是"自动"档，其余按 500mA 对齐，避免出现 2 437mA 这种怪值。
                            val snapped = (value / ChargeCurrentLimit.MANUAL_STEP_MA).toInt() *
                                ChargeCurrentLimit.MANUAL_STEP_MA
                            // 0 是"自动"，1..999 没有意义（低于节点下限），一并归到最小可写值。
                            val normalized = if (
                                snapped in 1 until ChargeCurrentLimit.MANUAL_MIN_MA
                            ) {
                                ChargeCurrentLimit.MANUAL_MIN_MA
                            } else {
                                snapped
                            }
                            onSettingsChange { it.copy(chargeCurrentLimitMa = normalized) }
                        },
                        valueRange = 0f..ChargeCurrentLimit.MANUAL_MAX_MA.toFloat(),
                        steps = ChargeCurrentLimit.MANUAL_MAX_MA / ChargeCurrentLimit.MANUAL_STEP_MA - 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("自动", style = CouiType.itemSummary(), color = colors.secondary)
                        Text(
                            "${ChargeCurrentLimit.MANUAL_MAX_MA} mA",
                            style = CouiType.itemSummary(),
                            color = colors.secondary,
                        )
                    }
                }

                Divider()
                CouiStatusLine(
                    title = "本机支持",
                    value = when (status.chargeLimitSupported) {
                        true -> "可写"
                        false -> "无节点"
                        null -> if (permissionGranted) "待探测" else "待授权"
                    },
                    tone = when (status.chargeLimitSupported) {
                        true -> StatusTone.OK
                        false -> StatusTone.BAD
                        null -> StatusTone.NEUTRAL
                    },
                )
                Divider()
                CouiStatusLine(
                    title = "已写入上限",
                    value = when {
                        applied > 0 -> "$applied mA"
                        !acOnlineNow -> "接电后生效"
                        status.chargeLimitSupported == false -> "本机不支持"
                        else -> "尚未介入"
                    },
                    tone = if (applied > 0) StatusTone.OK else StatusTone.NEUTRAL,
                )
                Divider()
                CouiStatusLine(
                    title = "系统回读",
                    value = if (observed > 0) "$observed mA" else "—",
                    tone = when {
                        observed <= 0 -> StatusTone.NEUTRAL
                        applied > 0 && observed == applied -> StatusTone.OK
                        else -> StatusTone.WARN
                    },
                )
            }
        }
    }
}

private enum class StatusTone { OK, WARN, BAD, NEUTRAL }

@Composable
private fun CouiStatusLine(title: String, value: String, tone: StatusTone) {
    val colors = CouiPageDefaults.themeColors()
    val toneColor = when (tone) {
        StatusTone.OK -> colors.accent
        StatusTone.WARN -> colors.secondary
        StatusTone.BAD -> colors.danger
        StatusTone.NEUTRAL -> colors.summary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = CouiPageDefaults.rowMinHeight)
            .padding(horizontal = CouiPageDefaults.rowPadding, vertical = CouiPageDefaults.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = CouiType.itemTitle(), color = colors.title, modifier = Modifier.weight(1f))
        Text(value, style = CouiType.itemValue(), color = toneColor)
    }
}

@Composable
private fun ShizukuGroup(shizuku: State<ShizukuAccess>, onRequestShizuku: () -> Unit) {
    val access = shizuku.value
    val ready = access.ready
    val colors = CouiPageDefaults.themeColors()
    val status = when {
        !access.installed -> "未安装 · 点击下载"
        !access.available -> "未运行 · 点击启动"
        !access.permission -> "未授权 · 点击授权给 OPPower"
        else -> "已授权 OPPower，可自动控制充电"
    }
    Group(title = "系统控制权限") {
        CouiRow(
            title = "Shizuku",
            summary = status,
            icon = { CouiStatusDotInline(ready) },
            valueColor = if (ready) colors.accent else colors.secondary,
            onClick = onRequestShizuku,
            chevron = true,
        )
    }
}

/** 组内状态点：复用设置主页的配色约定（就绪=强调色、待处理=次强调色）。 */
@Composable
private fun CouiStatusDotInline(ok: Boolean) {
    val colors = CouiPageDefaults.themeColors()
    Box(
        Modifier
            .size(10.dp)
            .clip(capsuleShape(10.dp))
            .background(if (ok) colors.accent else colors.secondary),
    )
}

/** 组内的次级小节标题：比组标题重、比行标题轻，用来切分"温度规则 / 电量规则"。 */
@Composable
private fun RuleSectionLabel(title: String, trailing: String, trailingColor: Color? = null) {
    val colors = CouiPageDefaults.themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = CouiPageDefaults.rowPadding, end = CouiPageDefaults.rowPadding, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = CouiType.denseStrong, color = colors.summary, modifier = Modifier.weight(1f))
        Text(trailing, style = CouiType.dense, color = trailingColor ?: colors.summary)
    }
}

@Composable
private fun ExplainLine(text: String) {
    Text(
        text = text,
        style = CouiType.caption,
        color = CouiPageDefaults.themeColors().summary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 规则编辑行：左侧数值输入 + 右侧档位下拉。
 *
 * 这一块不适合套 `CouiRow`——它有两个交互控件且需要横向对齐，属于 COUI 里
 * "自定义内容行"的场景，所以只用组内边距与语义字号，不再叠第二层卡片。
 */
@Composable
private fun RuleEditorRow(
    title: String,
    input: String,
    suffix: String,
    valid: Boolean,
    keyboardType: KeyboardType,
    onInput: (String) -> Unit,
    error: String?,
    selected: ControlTier,
    maximumPower: ControlTier,
    onSelected: (ControlTier) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = CouiPageDefaults.themeColors()
    val accent = if (valid) colors.accent else colors.danger
    // Compose 1.13 起 BasicTextField 的 value/onValueChange 形式已废弃。这里改用
    // TextFieldState：输入走 snapshotFlow 回传，外部改值（比如恢复默认）走 edit 同步。
    val fieldState = rememberTextFieldState(initialText = input)
    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() }.collect { onInput(it) }
    }
    LaunchedEffect(input) {
        if (input != fieldState.text.toString()) {
            fieldState.edit {
                replace(0, length, input)
                placeCursorBeforeCharAt(input.length)
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = CouiPageDefaults.rowPadding, end = CouiPageDefaults.rowPadding, top = 4.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = CouiType.denseStrong, color = colors.title, modifier = Modifier.weight(1f))
            if (error != null) {
                Text(error, style = CouiType.dense, color = colors.danger)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .width(76.dp)
                    .height(38.dp)
                    .clip(capsuleShape(38.dp))
                    .background(if (valid) colors.background else colors.danger.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.5f), capsuleShape(38.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    state = fieldState,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    textStyle = TextStyle(color = colors.title, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.weight(1f),
                )
                Text(suffix, style = CouiType.dense, color = colors.accent)
            }
            Text("→", style = CouiType.itemSummary(), color = colors.summary, modifier = Modifier.padding(horizontal = 8.dp))
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(capsuleShape(38.dp))
                        .background(colors.accentSurface)
                        .border(1.dp, colors.accent.copy(alpha = 0.35f), capsuleShape(38.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selected.label,
                        style = CouiType.denseStrong,
                        color = when (selected) {
                            ControlTier.PAUSED -> colors.danger
                            ControlTier.GENTLE -> colors.secondary
                            else -> colors.accent
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text("⌄", style = CouiType.itemSummary(), color = colors.accent)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ControlTier.entries.forEach { tier ->
                        val enabled = tier.conservatismRank() <= maximumPower.conservatismRank()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    tier.label,
                                    style = CouiType.dense,
                                    color = when {
                                        tier == selected -> colors.accent
                                        enabled -> colors.title
                                        else -> colors.disabled
                                    },
                                    fontWeight = if (tier == selected) FontWeight.Medium else FontWeight.Normal,
                                )
                            },
                            enabled = enabled,
                            onClick = {
                                onSelected(tier)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultRuleGroup(settings: RuntimeSettings) {
    val colors = CouiPageDefaults.themeColors()
    val rows = if (settings.baseTier == BaseTier.MAX_OUTPUT) {
        listOf(
            "默认" to "持续保持 gear 0",
            "温度" to "由系统原生热控自行调节",
            "电量 ≥ 80%" to "本次插电暂停快充增强",
            "电量充满" to "恢复系统默认",
        )
    } else {
        listOf(
            "低于 43.0°C" to "${settings.baseTier.friendlyLabel} · 随温度调整",
            "达到 43.0°C" to "暂停快充增强，恢复系统默认",
            "电量 ≥ 80%" to "本次插电暂停快充增强",
            "电量充满" to "恢复系统默认",
        )
    }
    Group(
        title = "默认策略 · ${settings.baseTier.friendlyLabel}",
        footer = "想自己定阈值时，打开上面的「自定义规则」。",
    ) {
        rows.forEachIndexed { index, (range, target) ->
            if (index > 0) Divider()
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp).padding(horizontal = CouiPageDefaults.rowPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(range, style = CouiType.caption, color = colors.secondary, modifier = Modifier.weight(1f))
                Text(target, style = CouiType.caption, color = colors.title)
            }
        }
    }
}

/**
 * 规则预览：把"用户填了什么"翻译成"到时会怎么走"。
 *
 * 这里刻意保留较密的字号（11–12sp）与紧凑行高，因为一屏要同时看到温度、电量、
 * 分段与区间表四组信息；换成 16sp 行标题会让预览从一屏变成三屏，失去"一眼看全"的意义。
 */
@Composable
private fun CustomRulePreview(settings: RuntimeSettings) {
    val colors = CouiPageDefaults.themeColors()
    val tempRules = settings.customRules.temperatureRules.sortedBy { it.threshold }
    val socRules = settings.customRules.socRules.sortedBy { it.thresholdPercent }
    val t1 = tempRules.getOrNull(0)?.threshold ?: 42f
    val t2 = tempRules.getOrNull(1)?.threshold ?: 43f
    val tempTarget1 = tempRules.getOrNull(0)?.target ?: ControlTier.HIGH
    val tempTarget2 = tempRules.getOrNull(1)?.target ?: ControlTier.PAUSED
    val soc1 = socRules.getOrNull(0)?.thresholdPercent ?: 60
    val soc2 = socRules.getOrNull(1)?.thresholdPercent ?: 85
    val socTarget1 = socRules.getOrNull(0)?.target ?: ControlTier.HIGH
    val socTarget2 = socRules.getOrNull(1)?.target ?: ControlTier.PAUSED
    val stepCount = (((t2 - t1) / 0.5f).toInt()).coerceAtLeast(1)

    Group(
        title = "动态控制示意",
        footer = "温度和电量先各自选择大档，取更保守者；进入功率大档后再只按温度自动分档。",
    ) {
        Column(Modifier.padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DynamicSegmentBar(
                labels = listOf(
                    "< " + t1.oneDecimal() + "°C " + settings.baseTier.label,
                    t1.oneDecimal() + "–" + t2.oneDecimal() + "°C " + tempTarget1.label,
                    "≥ " + t2.oneDecimal() + "°C " + tempTarget2.label,
                ),
                weights = listOf(2.8f, 1.4f, 1.2f),
                colors = listOf(
                    colors.accent.copy(alpha = 0.30f),
                    colors.secondary.copy(alpha = 0.28f),
                    colors.danger.copy(alpha = 0.24f),
                ),
            )
            DynamicSegmentBar(
                labels = listOf(
                    "< " + soc1 + "% " + settings.baseTier.label,
                    "$soc1–$soc2% " + socTarget1.label,
                    "≥ " + soc2 + "% " + socTarget2.label,
                ),
                weights = listOf(2.5f, 1.2f, 1.2f),
                colors = listOf(
                    colors.accent.copy(alpha = 0.28f),
                    colors.secondary.copy(alpha = 0.24f),
                    colors.danger.copy(alpha = 0.22f),
                ),
            )

            Divider(startIndent = 0.dp, endIndent = 0.dp, color = colors.divider)
            PreviewLabel("功率档内部温度分段", "至少 0.5°C / 段")
            Text(
                t1.oneDecimal() + "–" + t2.oneDecimal() + "°C · " + stepCount + " 个温度段",
                style = CouiType.dense,
                color = colors.summary,
            )
            val gearSet = when (tempTarget1) {
                ControlTier.HIGH -> BaseTier.HIGH.gearSet
                ControlTier.GENTLE -> BaseTier.GENTLE.gearSet
                else -> emptyList()
            }
            if (gearSet.isNotEmpty()) {
                repeat(stepCount) { index ->
                    val lower = t1 + index * 0.5f
                    val upper = lower + 0.5f
                    val label = when {
                        stepCount == 1 || index >= (stepCount * 2) / 3 -> "低功率端"
                        index >= stepCount / 3 -> "中功率端"
                        else -> "高功率端"
                    }
                    Surface(
                        color = colors.background,
                        shape = capsuleShape(28.dp),
                        border = BorderStroke(0.6.dp, colors.divider),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                lower.oneDecimal() + "°C ≤ 温度 < " + upper.oneDecimal() + "°C",
                                style = CouiType.dense,
                                color = colors.summary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(label + " · " + tempTarget1.label, style = CouiType.denseStrong, color = colors.accent)
                        }
                    }
                }
                Text(
                    tempTarget1.label + " 在实测功率范围内自动选择高、中、低功率端。",
                    style = CouiType.dense,
                    color = colors.summary,
                )
            } else {
                Text("温度条件 1 不是功率档，仅执行所选大档。", style = CouiType.dense, color = colors.summary)
            }

            Divider(startIndent = 0.dp, endIndent = 0.dp, color = colors.divider)
            PreviewLabel("规则区间", "严格按边界判断")
            RuleRangeRow("温度  < " + t1.oneDecimal() + "°C", settings.baseTier.label)
            RuleRangeRow(t1.oneDecimal() + "°C ≤ 温度 < " + t2.oneDecimal() + "°C", tempTarget1.label)
            RuleRangeRow("温度  ≥ " + t2.oneDecimal() + "°C", tempTarget2.label)
            RuleRangeRow("电量  < " + soc1 + "%", settings.baseTier.label)
            RuleRangeRow("$soc1% ≤ 电量 < $soc2%", socTarget1.label)
            RuleRangeRow("$soc2% ≤ 电量 < 100%", socTarget2.label)
            RuleRangeRow("电量  ≥ 100% 或充满", "暂停快充 · 关闭快充保持", warning = true)
        }
    }
}

@Composable
private fun PreviewLabel(title: String, trailing: String) {
    val colors = CouiPageDefaults.themeColors()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CouiType.denseStrong, color = colors.title, modifier = Modifier.weight(1f))
        Text(trailing, style = CouiType.dense, color = colors.summary)
    }
}

@Composable
private fun DynamicSegmentBar(labels: List<String>, weights: List<Float>, colors: List<Color>) {
    val theme = CouiPageDefaults.themeColors()
    Row(Modifier.fillMaxWidth().height(26.dp).clip(capsuleShape(26.dp))) {
        labels.forEachIndexed { index, label ->
            Box(
                Modifier
                    .weight(weights.getOrElse(index) { 1f })
                    .fillMaxSize()
                    .background(colors.getOrElse(index) { theme.background })
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = CouiType.dense, color = theme.title, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RuleRangeRow(condition: String, target: String, warning: Boolean = false) {
    val colors = CouiPageDefaults.themeColors()
    Surface(
        color = if (warning) colors.secondarySurface else colors.background,
        shape = capsuleShape(26.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                condition,
                style = CouiType.dense,
                color = if (warning) colors.secondary else colors.summary,
                modifier = Modifier.weight(1f),
            )
            Text(
                target,
                style = CouiType.denseStrong,
                color = if (warning) colors.secondary else colors.title,
            )
        }
    }
}
/** 基础档位对应的最高可控档位，规则不能越过的天花板。 */
private fun settingsBaseTier(settings: RuntimeSettings): ControlTier = when (settings.baseTier) {
    BaseTier.MAX_OUTPUT -> ControlTier.MAX_OUTPUT
    BaseTier.HIGH -> ControlTier.HIGH
    BaseTier.GENTLE -> ControlTier.GENTLE
}
