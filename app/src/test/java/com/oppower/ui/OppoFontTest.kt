package com.oppower.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPPO Sans 的文件名选择逻辑。
 *
 * 真机上字体文件名随 ColorOS 版本换过几轮，这里把判断规则钉住：
 * 换机型后如果选错了字重，测试会先报，不用等真机截图。
 */
class OppoFontTest {

    @Test
    fun picksAllWeightsFromColorOs17Names() {
        val faces = OppoFont.selectFaces(
            listOf(
                "OPlusSans3-Regular.ttf",
                "OPlusSans3-Medium.ttf",
                "OPlusSans3-Bold.ttf",
            ),
        )

        assertEquals(listOf(400 to "OPlusSans3-Regular.ttf", 500 to "OPlusSans3-Medium.ttf", 700 to "OPlusSans3-Bold.ttf"), faces)
    }

    @Test
    fun prefersSimplifiedChineseOverTraditional() {
        val faces = OppoFont.selectFaces(
            listOf(
                "OPlusSans-Hant-Regular.ttf",
                "OPlusSans-Hans-Regular.ttf",
            ),
        )

        assertEquals(1, faces.size)
        assertEquals("OPlusSans-Hans-Regular.ttf", faces.single().second)
    }

    @Test
    fun ignoresFontsFromOtherFamilies() {
        val faces = OppoFont.selectFaces(
            listOf(
                "Roboto-Regular.ttf",
                "NotoSansCJK-Regular.ttc",
                "DroidSansMono.ttf",
                "OPPOSans-Regular.ttf",
            ),
        )

        assertEquals(listOf(400 to "OPPOSans-Regular.ttf"), faces)
    }

    /** `SemiBold` 里含 `bold`，规则若按短关键字优先会误判成 700。 */
    @Test
    fun readsSemiBoldAsSixHundredNotSevenHundred() {
        val faces = OppoFont.selectFaces(listOf("OPlusSans3-SemiBold.ttf"))

        assertEquals(listOf(600 to "OPlusSans3-SemiBold.ttf"), faces)
    }

    /** 同理，`ExtraLight` 不能被 `light` 抢先命中成 300。 */
    @Test
    fun readsExtraLightAsTwoHundredNotThreeHundred() {
        val faces = OppoFont.selectFaces(listOf("OPlusSans3-ExtraLight.ttf"))

        assertEquals(listOf(200 to "OPlusSans3-ExtraLight.ttf"), faces)
    }

    /** `OPlus Sans` 的小写是 `oplussans`，两个 s 相连，容易被写成 `oplusans`。 */
    @Test
    fun matchesColorOsFamilyNameWithDoubledS() {
        val faces = OppoFont.selectFaces(listOf("OPlusSans-Regular.ttf"))

        assertEquals(listOf(400 to "OPlusSans-Regular.ttf"), faces)
    }

    @Test
    fun isCaseInsensitive() {
        val faces = OppoFont.selectFaces(listOf("OPLUSSANS3-MEDIUM.TTF"))

        assertEquals(listOf(500 to "OPLUSSANS3-MEDIUM.TTF"), faces)
    }

    @Test
    fun skipsFilesWithoutARecognisedWeight() {
        // 有些 ROM 会塞 OPlusSans-Italic.ttf 这类文件，字重语义不明时宁可不选。
        val faces = OppoFont.selectFaces(listOf("OPlusSans-Italic.ttf"))

        assertTrue(faces.isEmpty())
    }

    @Test
    fun returnsEmptyForEmptyInput() {
        assertTrue(OppoFont.selectFaces(emptyList()).isEmpty())
    }

    /** 同一字重出现多个候选时只保留一个，避免 FontFamily 里两个 400 互相覆盖。 */
    @Test
    fun deduplicatesByWeight() {
        val faces = OppoFont.selectFaces(
            listOf(
                "OPlusSans3-Regular.ttf",
                "OPPOSans-Regular.ttf",
            ),
        )

        assertEquals(1, faces.size)
        assertEquals(400, faces.single().first)
    }

    /** 顶栏大标题要 700；机器上只有 600 时退一档，不能直接放弃。 */
    @Test
    fun closestWeightFallsBackToNearestAvailable() {
        val faces = listOf(400 to "R", 500 to "M", 600 to "SB")

        assertEquals("SB", OppoFont.pickClosest(700, faces))
    }

    /** 距离相同时取偏粗的：细一档在大字号上会发灰。 */
    @Test
    fun closestWeightPrefersHeavierOnTie() {
        val faces = listOf(400 to "R", 600 to "SB")

        assertEquals("SB", OppoFont.pickClosest(500, faces))
    }

    /** 只有一款 Regular 时也返回它，FatFamily 单字体即全部字重。 */
    @Test
    fun closestWeightReturnsSoleFace() {
        val faces = listOf(400 to "R")

        assertEquals("R", OppoFont.pickClosest(700, faces))
    }

    @Test
    fun closestWeightReturnsNullForEmptyInput() {
        assertEquals(null, OppoFont.pickClosest(700, emptyList()))
    }
}
