package com.github.tvbox.osc.sourcedata

import com.github.tvbox.osc.util.MD5
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * extend 解析(取数)的三条口径:空/非 http 原样返回、命中缓存不再取数、取不到值时回退**原 extend**。
 *
 * 第三条是回归高发点:回退若写成空串,站点会收到被清空的 extend(旧实现里超时与异常共用这条出口)。
 */
class SourceHelperExtendTest {

    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, String>()

    @Test
    fun emptyAndNonHttpExtendPassThrough() {
        assertEquals("", SourceHelper.getFixUrl(cache, gson, "", 15))
        assertEquals("""{"a":1}""", SourceHelper.getFixUrl(cache, gson, """{"a":1}""", 15))
    }

    @Test
    fun cacheHitIsKeyedByMd5OfExtend() {
        val extend = "http://ext.example/api.json"
        cache[MD5.string2MD5(extend)] = """{"minified":true}"""
        assertEquals("""{"minified":true}""", SourceHelper.getFixUrl(cache, gson, extend, 15))
    }

    @Test
    fun unresolvedExtendFallsBackToOriginal() {
        // timeoutSeconds 取负值让 Future.get 立刻抛 IllegalArgumentException,确定性命中"取不到值"的
        // 出口 —— 与真实超时(TimeoutException)、取数抛异常共用同一句 return extend。
        // 地址用 127.0.0.1/file/ 走本地读文件分支,测试不发网络请求(后台任务的失败被 Future 吞掉)。
        val extend = "http://127.0.0.1/file/missing-extend-${System.nanoTime()}.json"
        assertEquals(extend, SourceHelper.getFixUrl(cache, gson, extend, -1))
    }
}
