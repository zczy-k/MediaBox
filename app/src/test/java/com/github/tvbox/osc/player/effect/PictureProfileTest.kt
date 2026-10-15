package com.github.tvbox.osc.player.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureProfileTest {

    @Test
    fun originalAndCustom_areIdentity() {
        assertTrue(PictureProfile.of(PicturePreset.Original).isNoOp)
        assertTrue(PictureProfile.of(PicturePreset.Custom).isNoOp)
        assertEquals(PictureProfile.OFF, PictureProfile.of(PicturePreset.Original))
    }

    @Test
    fun everyNamedPreset_changesThePicture() {
        PicturePreset.entries
            .filter { it != PicturePreset.Original && it != PicturePreset.Custom }
            .forEach { preset ->
                assertFalse("$preset 应是有效果的预设", PictureProfile.of(preset).isNoOp)
            }
    }

    @Test
    fun presetTable_keepsSourceValues() {
        val vivid = PictureProfile.of(PicturePreset.Vivid)
        assertEquals(1.22f, vivid.saturation, 0.0001f)
        assertEquals(1.12f, vivid.contrast, 0.0001f)
        assertEquals(0.015f, vivid.brightness, 0.0001f)
        assertEquals(0.26f, vivid.sharpness, 0.0001f)
        assertEquals(0.028f, vivid.threshold, 0.0001f)
        assertEquals(0.03f, vivid.shadowLift, 0.0001f)
        assertEquals(1f, vivid.gamma, 0.0001f)
        assertEquals(0f, vivid.hue, 0.0001f)
        assertEquals(0f, vivid.temperature, 0.0001f)

        val cinema = PictureProfile.of(PicturePreset.Cinema)
        assertEquals(0.97f, cinema.gamma, 0.0001f)
        assertEquals(26f, cinema.temperature, 0.0001f)
        assertEquals(PictureProfile.DEFAULT_THRESHOLD, cinema.threshold, 0.0001f)

        val clear = PictureProfile.of(PicturePreset.Clear)
        assertEquals(0.025f, clear.threshold, 0.0001f)
    }

    @Test
    fun onlyCustomPreset_expandsSliders() {
        PicturePreset.entries.forEach { preset ->
            assertEquals(preset == PicturePreset.Custom, preset.adjustable)
        }
    }

    @Test
    fun clamped_bringsOutOfRangeBackIntoSliderRange() {
        val wild = PictureProfile(
            saturation = 9f,
            contrast = 0f,
            brightness = 1f,
            gamma = 0f,
            hue = 999f,
            temperature = -999f,
            sharpness = 5f,
            shadowLift = -3f,
        )
        val fixed = wild.clamped()
        assertEquals(PictureProfile.MAX_SATURATION, fixed.saturation, 0.0001f)
        assertEquals(PictureProfile.MIN_CONTRAST, fixed.contrast, 0.0001f)
        assertEquals(PictureProfile.MAX_BRIGHTNESS, fixed.brightness, 0.0001f)
        assertEquals(PictureProfile.MIN_GAMMA, fixed.gamma, 0.0001f)
        assertEquals(PictureProfile.MAX_HUE, fixed.hue, 0.0001f)
        assertEquals(PictureProfile.MIN_TEMPERATURE, fixed.temperature, 0.0001f)
        assertEquals(PictureProfile.MAX_SHARPNESS, fixed.sharpness, 0.0001f)
        assertEquals(PictureProfile.MIN_SHADOW_LIFT, fixed.shadowLift, 0.0001f)
    }

    @Test
    fun clamped_replacesNaNWithDefault() {
        val fixed = PictureProfile(hue = Float.NaN, saturation = Float.NaN).clamped()
        assertEquals(0f, fixed.hue, 0f)
        assertEquals(1f, fixed.saturation, 0f)
        assertTrue(fixed.clamped().isNoOp)
    }

    @Test
    fun temperatureGains_areNeutralOnlyAtZero() {
        assertTrue(PictureProfile.OFF.isColorNoOp)
        assertEquals(1f, PictureProfile.OFF.redGain, 0f)
        assertEquals(1f, PictureProfile.OFF.blueGain, 0f)
        assertTrue(PictureProfile(temperature = 42f).redGain > 1f)
        assertTrue(PictureProfile(temperature = 42f).blueGain < 1f)
        assertTrue(PictureProfile(temperature = -42f).redGain < 1f)
        assertTrue(PictureProfile(temperature = -42f).blueGain > 1f)
    }

    @Test
    fun noOpFlags_splitByPass() {
        assertTrue(PictureProfile(gamma = 1.2f).isToneNoOp.not())
        assertTrue(PictureProfile(gamma = 1.2f).isColorNoOp)
        assertTrue(PictureProfile(sharpness = 0.2f).isDetailNoOp.not())
        assertTrue(PictureProfile(hue = 30f).isColorNoOp)
    }

    // ==================== 去灰(levels 拉伸) ====================

    @Test
    fun levelsOf_zeroIsExactIdentity() {
        val levels = PictureProfile.levelsOf(0f)
        assertEquals(0f, levels[0], 0f)
        assertEquals(1f, levels[1], 0f)
        // 恒等判定必须把去灰算进去:漏了"只拖去灰滑条"会被当成没调,链根本不挂
        assertTrue(PictureProfile(dehaze = 0.4f).isNoOp.not())
        assertTrue(PictureProfile(dehaze = 0.4f).isColorNoOp.not())
    }

    @Test
    fun levelsOf_mapsEndsAndSurvivesGarbage() {
        val full = PictureProfile.levelsOf(1f)
        assertEquals(PictureProfile.DEHAZE_MAX_BLACK, full[0], 0.0001f)
        assertEquals(1f - PictureProfile.DEHAZE_MAX_WHITE_PUSH, full[1], 0.0001f)
        val half = PictureProfile.levelsOf(0.5f)
        assertEquals(PictureProfile.DEHAZE_MAX_BLACK / 2f, half[0], 0.0001f)
        // NaN / 越界:着色器拿这两个数做除数,绝不能出现 hi <= lo
        assertEquals(0f, PictureProfile.levelsOf(Float.NaN)[0], 0f)
        assertEquals(1f, PictureProfile.levelsOf(Float.NaN)[1], 0f)
        assertEquals(0f, PictureProfile.levelsOf(-3f)[0], 0f)
        val over = PictureProfile.levelsOf(9f)
        assertTrue(over[1] > over[0])
    }

    @Test
    fun clearPreset_carriesTheDehazeItAte() {
        val preset = PictureProfile.of(PicturePreset.Clear)
        // 「通透」并进了「清晰」:去灰必须还在,否则合并等于把这个能力删了
        assertTrue(preset.dehaze > 0.3f)
        // 去灰与"暗部提升"方向相反(一个压黑底、一个抬黑底),同时开等于互相抵消
        assertTrue(preset.shadowLift < 0.05f)
    }

    @Test
    fun resolve_mapsMergedAwayPresets_insteadOfDroppingToOriginal() {
        // 预设名是字符串存盘的:枚举少一个,老 KV 就查不到 ⇒ 必须有映射,不能默默掉回「原始」
        assertEquals(PicturePreset.Vivid, PicturePreset.resolve("Anime"))
        assertEquals(PicturePreset.Vivid, PicturePreset.resolve("Sport"))
        assertEquals(PicturePreset.Vivid, PicturePreset.resolve("Game"))
        assertEquals(PicturePreset.Clear, PicturePreset.resolve("Dehaze"))
        assertEquals(PicturePreset.Soft, PicturePreset.resolve("Comfort"))
        assertEquals(PicturePreset.Original, PicturePreset.resolve("Natural"))
        assertEquals(PicturePreset.Original, PicturePreset.resolve("Bright"))
        // 现名照旧直读;认不出来的串才落 fallback
        assertEquals(PicturePreset.Cinema, PicturePreset.resolve("Cinema"))
        assertEquals(PicturePreset.Custom, PicturePreset.resolve("hacked-value", PicturePreset.Custom))
        assertEquals(PicturePreset.Original, PicturePreset.resolve(null))
    }
}
