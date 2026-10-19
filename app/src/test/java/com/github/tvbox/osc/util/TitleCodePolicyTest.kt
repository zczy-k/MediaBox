package com.github.tvbox.osc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「番号识别 / 回填 / 字幕预填」的**纯逻辑**口径(对应 `TitleCodePolicy`)。
 *
 * <p>锁住的是三组真机踩出来的行为:
 * <ul>
 *   <li>**详情页番号回填**:源在搜索侧拼 `dvd_id`、详情侧不拼(实测 AV01 / `py_02e87301`),
 *       App 必须按入口标题把番号补回去,否则列表有、详情没有;</li>
 *   <li>**不许补成双份**:详情名自带番号(哪怕是另一个版本号 `MIDA-791-RM`)一律不动 ——
 *       拼出 `MIDA-791 MIDA-791-RM …` 比不补更糟;</li>
 *   <li>**字幕搜索框只填番号**:有番号只填番号(字幕站按番号索引命中率最高),
 *       没番号才整条标题进框。</li>
 * </ul>
 *
 * <p>下面这些**绝不能**被判成番号:中文片名、纯年份、片名中段的编号 ——
 * 一旦误判,详情标题会被改成 `2026 …` 或被截断。
 */
class TitleCodePolicyTest {

    // ---------- leadingCode ----------

    @Test
    fun `中文长名开头的番号能抽出来`() {
        assertEquals(
            "MIDA-791",
            TitleCodePolicy.leadingCode(
                "MIDA-791 AI无法创造的生动真实美少女 AV出道 在这个充满虚伪的世界里，不对自己也不对周围人撒谎的我。 奏玉树"
            )
        )
    }

    @Test
    fun `番号带版本后缀时只取番号主体`() {
        assertEquals(
            "MIDA-791",
            TitleCodePolicy.leadingCode("mida-791-rm [Reducing Mosaic] A Raw, Authentic Beauty That AI Can't Create.")
        )
        assertEquals("MIDA-791", TitleCodePolicy.leadingCode("MIDA-791-UNCENSORED-LEAKED"))
    }

    @Test
    fun `空格与下划线分隔统一成连字符`() {
        assertEquals("MIDA-791", TitleCodePolicy.leadingCode("  mida 791 xxx"))
        assertEquals("MIDA-791", TitleCodePolicy.leadingCode("MIDA_791 xxx"))
    }

    @Test
    fun `前导零是番号的一部分,不许剥`() {
        assertEquals("SSIS-001", TitleCodePolicy.leadingCode("SSIS-001 新人"))
    }

    @Test
    fun `FC2 PPV 整段才算番号`() {
        assertEquals("FC2-PPV-1234567", TitleCodePolicy.leadingCode("FC2-PPV-1234567 素人作品"))
    }

    @Test
    fun `中文片名没有番号`() {
        assertEquals("", TitleCodePolicy.leadingCode("AI无法创造的生动真实美少女 AV出道 在这个充满虚伪的世界里"))
        assertEquals("", TitleCodePolicy.leadingCode("繁花 第一季"))
        assertEquals("", TitleCodePolicy.leadingCode("第2季 2026"))
    }

    @Test
    fun `片名中段才出现的编号不算番号`() {
        // 出现在第 18 个字符,超出 LEADING_WINDOW(8) ⇒ 不是这部片的番号
        assertEquals("", TitleCodePolicy.leadingCode("[Reducing Mosaic] ADN-811 I Want You To Love Me"))
    }

    @Test
    fun `空输入不炸`() {
        assertEquals("", TitleCodePolicy.leadingCode(null))
        assertEquals("", TitleCodePolicy.leadingCode(""))
        assertEquals("", TitleCodePolicy.leadingCode("   "))
    }

    // ---------- hasAnyCode ----------

    @Test
    fun `详情名自带番号能被识别`() {
        assertTrue(TitleCodePolicy.hasAnyCode("MIDA-791-RM [Reducing Mosaic] A Raw"))
        assertTrue(TitleCodePolicy.hasAnyCode("[Reducing Mosaic] ADN-811 I Want You To Love Me"))
        assertFalse(TitleCodePolicy.hasAnyCode("AI无法创造的生动真实美少女 AV出道"))
        assertFalse(TitleCodePolicy.hasAnyCode(null))
    }

    // ---------- restoreCode ----------

    @Test
    fun `详情丢番号时按入口标题补回`() {
        assertEquals(
            "MIDA-791 AI无法创造的生动真实美少女 AV出道",
            TitleCodePolicy.restoreCode(
                "MIDA-791 AI无法创造的生动真实美少女 AV出道 在这个充满虚伪的世界里",
                "AI无法创造的生动真实美少女 AV出道"
            )
        )
    }

    @Test
    fun `详情自带番号时一个字都不改`() {
        val detail = "MIDA-791-RM [Reducing Mosaic] A Raw, Authentic Beauty"
        assertEquals(detail, TitleCodePolicy.restoreCode("MIDA-791 A Raw", detail))
        // 同一个番号、不同分隔符也算"自带",不能补成双份
        assertEquals("MIDA 791 yyy", TitleCodePolicy.restoreCode("MIDA-791 xxx", "MIDA 791 yyy"))
    }

    @Test
    fun `入口没有番号时原样返回`() {
        assertEquals("普通剧名", TitleCodePolicy.restoreCode("普通剧名", "普通剧名"))
        assertEquals("别的名字", TitleCodePolicy.restoreCode("繁花", "别的名字"))
        assertEquals("AI无法创造的…", TitleCodePolicy.restoreCode("", "AI无法创造的…"))
        assertEquals("AI无法创造的…", TitleCodePolicy.restoreCode(null, "AI无法创造的…"))
    }

    @Test
    fun `详情名为空时返回空`() {
        assertEquals("", TitleCodePolicy.restoreCode("MIDA-791 xxx", ""))
        assertEquals("", TitleCodePolicy.restoreCode("MIDA-791 xxx", null))
    }

    // ---------- subtitleSeed ----------

    @Test
    fun `字幕搜索框有番号只填番号`() {
        assertEquals(
            "MIDA-791",
            TitleCodePolicy.subtitleSeed(
                "MIDA-791 AI无法创造的生动真实美少女 AV出道 在这个充满虚伪的世界里，不对自己也不对周围人撒谎的我。 奏玉树"
            )
        )
        assertEquals("MIDA-791", TitleCodePolicy.subtitleSeed("mida-791-rm [Reducing Mosaic] A Raw"))
    }

    @Test
    fun `字幕搜索框没番号就整条标题`() {
        assertEquals("繁花 第一季", TitleCodePolicy.subtitleSeed("繁花 第一季"))
        assertEquals("The Bear", TitleCodePolicy.subtitleSeed("The Bear"))
        // 只有年份不算番号,整条保留
        assertEquals("流浪地球 2026", TitleCodePolicy.subtitleSeed("流浪地球 2026"))
    }

    @Test
    fun `字幕预填空输入返回空`() {
        assertEquals("", TitleCodePolicy.subtitleSeed(null))
        assertEquals("", TitleCodePolicy.subtitleSeed(""))
        assertEquals("", TitleCodePolicy.subtitleSeed("   "))
    }
}
