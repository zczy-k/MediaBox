package com.github.tvbox.osc.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画质/无资源记忆的**空表契约**。
 *
 * <p>这个测试守护的是 v1.0.31 真机抓到的最终根因:
 * ```
 * echo-quality record FAILED key=热播影视|76062|线路四
 *   err=JSONException:End of input at character 0
 * ```
 * `JSONObject("")` 在 org.json 里**抛异常**,而"键不存在"时 [com.github.tvbox.osc.util.KV.get]
 * 返回的恰好是默认值 `""`。于是首次使用(表还是空的)时每次读写都抛,
 * 异常被 catch 吞掉,表现是「探测有日志、记忆永远读不回来、标签永不出现」。
 *
 * <p>为排查这个问题花了整整四轮构建 —— 所以用测试把"空串必须先挡掉"锁住。
 *
 * <p>注意:本测试只验证 org.json 的**行为契约**(空串会抛)与修复后的兜底语义,
 * 不验证 `VideoQualityMemory` 本身 —— 后者要读 MMKV(单测环境没有实现,测了等于没测)。
 * 「空串会抛」这半条契约的可测性依赖 `testImplementation("org.json:json")`:
 * 没有它时,unit test 下 org.json 被 `isReturnDefaultValues=true` 换成"只返默认值"的 stub,
 * 空串根本不抛(2026-10-10 补依赖后,本文件从"恒挂 2 条"变为真实契约验证)。
 */
class QualityMemoryJsonContractTest {

    @Test
    fun `orgjson 对空串会抛异常 - 这就是必须先挡掉的原因`() {
        // 这条断言是本测试存在的意义:如果哪天 org.json 改了行为允许空串,
        // 说明 loadAll 的兜底可以简化,这条会提醒你重新评估。
        var thrown = false
        try {
            JSONObject("")
        } catch (e: Throwable) {
            thrown = true
        }
        assertTrue("org.json 的 JSONObject(\"\") 应当抛异常", thrown)
    }

    @Test
    fun `修复后的兜底 - 空串与空白都返回空表而不是抛异常`() {
        // 对应 loadAll / loadAvailabilityAll 里的 raw.isNullOrBlank() 分支
        listOf("", "   ", "\n").forEach { raw ->
            val result = safeParse(raw)
            assertNotNull("「${raw.replace("\n", "\\n")}」应返回空表而非抛异常", result)
            assertEquals("空表应当没有条目", 0, result.length())
        }
    }

    @Test
    fun `修复后的兜底 - 脏数据不毁掉整表，退回空表即可`() {
        // 对应 loadAll 的 catch 分支:宁可从空表重建，也不能让所有记忆永久读不回来
        val result = safeParse("{ 这不是合法 JSON")
        assertNotNull(result)
        assertEquals(0, result.length())
    }

    @Test
    fun `合法 JSON 正常解析`() {
        val result = safeParse("""{"热播影视|76062|线路四":"1280,536,0,2,1759836800000"}""")
        assertEquals(1, result.length())
        assertEquals("1280,536,0,2,1759836800000", result.getString("热播影视|76062|线路四"))
    }

    /** 与 loadAll 修复后的逻辑保持一致（复制而非反射调用，避免 MMKV 依赖）。 */
    private fun safeParse(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject()
        }
    }
}