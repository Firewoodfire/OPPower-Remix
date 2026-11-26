package com.oppower.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * ColorOS 的原厂字体是 OPPO Sans（3.0 起文件名前缀为 `OPlusSans3`），
 * 落地在 `/system/fonts/`。普通应用对该目录有读权限，所以不需要把字体
 * 打进 APK——一个字重的中文字形就有 9 MB 上下，四个字重直接能把 release
 * 包从 3.5 MB 抬到 40 MB，代价完全不成比例。
 *
 * 直接读系统文件还有一个好处：字体与 ColorOS 设置页、通知栏用的是同一份
 * 字形，不会出现"应用内比系统细一档"的观感差。
 *
 * 风险控制：整个查找过程包在 [runCatching] 里，任何一步失败都回落到
 * [FontFamily.SansSerif]。设备上没有 OPPO 字体（非 ColorOS 机型、或目录
 * 结构改了）时应用照常启动，只是字体退化成系统默认。
 */
internal object OppoFont {

    private val FONT_DIR = File("/system/fonts")

    /**
     * 字重关键字 → 字重值。顺序有意义：命中即返回，所以长关键字必须排在
     * 它的子串前面——`extralight` 要在 `light` 前，`semibold` / `extrabold`
     * 要在 `bold` 前，否则 `OPlusSans3-SemiBold` 会被判成 700。
     */
    private val WEIGHT_KEYWORDS: List<Pair<String, Int>> = listOf(
        "extralight" to 200,
        "ultralight" to 200,
        "extrabold" to 800,
        "ultrabold" to 800,
        "semibold" to 600,
        "demibold" to 600,
        "regular" to 400,
        "normal" to 400,
        "medium" to 500,
        "light" to 300,
        "heavy" to 800,
        "black" to 900,
        "bold" to 700,
        "book" to 400,
        "thin" to 100,
    )

    /**
     * 字体族只认这两个前缀，避免把 `NotoSansCJK` 之类一起卷进来。
     *
     * 注意 `OPlus Sans` 的小写是 `oplussans`——`OPlus` 结尾的 s 和 `Sans` 开头的 s
     * 连在一起，中间没有分隔符。写成 `oplusans` 会永远匹配不上，这个坑我踩过一次。
     */
    private val FAMILY_MARKERS = listOf("opposans", "oplussans")

    private val faces: List<Pair<Int, String>> by lazy {
        runCatching { selectFaces(FONT_DIR.list()?.asList().orEmpty()) }.getOrDefault(emptyList())
    }

    /** 是否真的找到并启用了 OPPO 字体。false 表示回落到了系统默认。 */
    val available: Boolean get() = faces.isNotEmpty()

    /**
     * 应用级单例。Compose 的 `FontFamily` 是不可变对象，构造一次复用即可；
     * 每次重组重建会让整棵文本树失效。
     */
    val family: FontFamily by lazy {
        if (faces.isEmpty()) {
            FontFamily.SansSerif
        } else {
            FontFamily(
                *faces.map { (weight, name) ->
                    Font(
                        file = File(FONT_DIR, name),
                        weight = FontWeight(weight),
                        style = FontStyle.Normal,
                    )
                }.toTypedArray(),
            )
        }
    }

    /**
     * 取最接近 [weight] 的那一款字体，用于顶栏标题这种「要大字号 + 要粗」的场景。
     *
     * `android.graphics.Typeface.create` 用到的族名是 `sans-serif-semibold` 这类
     * 系统内置名，拿不到 OPPO Sans；Compose 的合成加粗又只是在原字形上描边，
     * 中文大标题会糊。ColorOS 自己是大标题直接用 Bold，所以这里也直接换文件。
     *
     * 没找到 OPPO 字体、或需要 400 以下字重（正文用不到）时返回 null，调用方
     * 继续用 [family]。返回值按字重记忆，避免每次重组都查一遍。
     */
    fun weightFamily(weight: Int): FontFamily? {
        if (faces.isEmpty()) return null
        return cache.getOrPut(weight) {
            val pick = pickClosest(weight) ?: return@getOrPut null
            FontFamily(Font(File(FONT_DIR, pick), weight = FontWeight(weight), style = FontStyle.Normal))
        }
    }

    /**
     * 在已找到的字重里挑与 [target] 最接近的一个；距离相同优先取偏粗的一侧
     * （大标题宁可粗一点，也不要细一档显得发灰）。
     */
    internal fun pickClosest(target: Int, from: List<Pair<Int, String>> = faces): String? =
        from.minWithOrNull(
            compareBy({ (w, _) -> kotlin.math.abs(w - target) }, { (w, _) -> if (w >= target) 0 else 1 }),
        )?.second

    private val cache = HashMap<Int, FontFamily?>()

    /**
     * 从目录清单里挑出 OPPO Sans 的各个字重。
     *
     * 抽成纯函数是为了能在 JVM 单测里跑——真机字体文件名随 ColorOS 版本
     * 变过好几轮（`OPPOSans-R` / `OPlusSans-Regular` / `OPlusSans3-Regular`），
     * 把选择逻辑钉在测试里，改规则时不需要真机就能验证。
     *
     * @return 按字重升序排列的 `字重值 to 文件名`，同一字重只保留一个。
     */
    internal fun selectFaces(names: List<String>): List<Pair<Int, String>> {
        val picked = LinkedHashMap<Int, Pair<Int, String>>()
        names.forEach { name ->
            if (!name.endsWith(".ttf", ignoreCase = true) && !name.endsWith(".otf", ignoreCase = true)) {
                return@forEach
            }
            val base = name.substringBeforeLast('.').lowercase()
            if (FAMILY_MARKERS.none { base.contains(it) }) return@forEach
            // 繁体字集单独成文件，优先简体或中立方集，避免混排时字形跳变。
            if (base.contains("hant")) return@forEach
            val weight = WEIGHT_KEYWORDS.firstOrNull { base.contains(it.first) }?.second ?: return@forEach
            // 同一字重可能同时存在 Hans 与中立方集，Hans 排前面。
            val rank = if (base.contains("hans")) 0 else 1
            val existing = picked[weight]
            if (existing == null || rank < existing.first) picked[weight] = rank to name
        }
        return picked.entries.map { (weight, face) -> weight to face.second }.sortedBy { it.first }
    }

    /** 当前生效的字体清单，设置页直接展示这一行。 */
    fun describe(): String = if (faces.isEmpty()) {
        "系统默认（未找到 OPPO Sans）"
    } else {
        faces.joinToString("、") { (weight, name) -> "${weightName(weight)} $name" }
    }

    private fun weightName(weight: Int): String = when (weight) {
        100 -> "Thin"
        200 -> "ExtraLight"
        300 -> "Light"
        400 -> "Regular"
        500 -> "Medium"
        600 -> "SemiBold"
        700 -> "Bold"
        800 -> "ExtraBold"
        900 -> "Black"
        else -> weight.toString()
    }
}
