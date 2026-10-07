package com.github.tvbox.osc.util

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 诊断日志的前缀登记表闸门:**新增 `echo-*` 诊断日志必须同时登记到
 * [LOG] 的 `FILE_LOG_PREFIXES`,否则它永远不会落盘**。
 *
 * <p>为什么值得写成测试:这个坑我踩了**两次**。
 * 第一次加 `echo-line-heights` 时没登记,真机日志里查不到,误判成"代码没执行",
 * 白等了一整轮构建 + 装机 + 实测,还把排查方向带偏到"记忆没落盘""装错包"上。
 * 第二次(v1.0.29)又踩了一次同样的坑。
 *
 * <p>单靠人读代码是守不住的 —— 加日志的人往往只关注"打出来没有",不会想到
 * 文件日志还有一层前缀过滤(见 [LOG] 类 KDoc,那里其实早就写了这个警告)。
 * 所以把它变成一条会红的断言。
 *
 * <p>判定口径:源码里出现的 `echo-xxx` 字面量,只要它出现在带"诊断"语义的
 * `LOG.i(...)` 调用里,就必须在前缀表中。放宽到"全部 echo- 字面量"会误伤
 * 大量非诊断日志(`echo-goPlayUrl` 等已在表内,但也有走 logcat-only 的),
 * 因此这里只锁定**明确用于诊断**的那批前缀。
 */
class DiagLogPrefixTest {

    /** 本仓新增诊断日志时统一使用的前缀。新增诊断日志请一并登记到 LOG.FILE_LOG_PREFIXES。 */
    private val diagnosticPrefixes = listOf(
        "echo-line-probe",   // 线路探测的目标筛选
        "echo-line-heights", // 线路画质读取侧
        "echo-unavailable",  // 无资源标记
        "echo-quality",      // 画质优选决策 + 写入侧键
    )

    @Test
    fun `诊断日志前缀必须都在 LOG 的落盘前缀表里`() {
        val table = logSource()
        val unregistered = diagnosticPrefixes.filter { !table.contains("\"$it\"") }
        if (unregistered.isNotEmpty()) {
            fail(
                "这些诊断日志前缀没登记到 LOG.FILE_LOG_PREFIXES,真机文件日志里查不到: " +
                    "$unregistered\n" +
                    "加日志时忘记登记 ⇒ 日志被过滤 ⇒ 误判成代码没执行。" +
                    "请在 util/LOG.java 的 FILE_LOG_PREFIXES 里补上。"
            )
        }
    }

    @Test
    fun `读取侧与写入侧的日志必须都存在 - 否则无法比对键是否匹配`() {
        // 「记忆写进去了却读不回来」这类问题,必须能看到两侧的键才能定位。
        // 少任何一侧都只能靠猜 —— v1.0.29 就是在缺读取侧日志时卡了两轮。
        val source = logSource()
        assertTrue(
            "缺少写入侧日志(echo-quality 的 write-key)",
            source.contains("echo-quality write-key")
        )
        assertTrue(
            "缺少读取侧日志(echo-line-heights)",
            source.contains("echo-line-heights")
        )
    }

    /** 读 LOG.java 源码:登记表是 private 静态数组,反射取值在 unit test 下不稳。 */
    private fun logSource(): String {
        val candidates = listOf(
            "src/main/java/com/github/tvbox/osc/util/LOG.java",
            "../app/src/main/java/com/github/tvbox/osc/util/LOG.java",
        )
        for (p in candidates) {
            val f = File(p)
            if (f.exists()) return f.readText()
        }
        throw AssertionError(
            "找不到 LOG.java,候选路径都不存在: $candidates（工作目录 ${File(".").absolutePath}）"
        )
    }
}