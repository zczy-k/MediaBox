package com.github.tvbox.osc.ui.activity

/**
 * 详情加载看门狗的时限口径:**纯逻辑**,不碰 Handler/网络,可直接 JVM 单测。
 *
 * <p>为什么必须有这道表:正常详情路径此前**完全没有超时** —— 源不响应、或请求排在被占满的
 * 线程池后面,页面就永远停在 Loading 转圈。这里给一个"兜底上限",不是把正常请求掐短:
 * 取数自身已有站点级限时({@code SourceBean.getPlayTimeoutSeconds()},5~60s,缺省 8s),
 * 看门狗只在"连站点自己的限时都等完了还没回"时收口。
 *
 * <p>取值:站点限时 + 余量(余量覆盖 extend 拉取 + 请求两段串行),再夹在 [MIN_TIMEOUT_MS] 与
 * [MAX_TIMEOUT_MS] 之间。下界保证快源不会被误杀,上界保证声明了 60s 的慢源也不会把用户
 * 按在转圈页上一分多钟。
 */
internal object DetailLoadWatchdog {

    /** 下界:覆盖"缺省 8s 源"的 extend 8s + 请求 10s(OkGo 缺省)两段串行 */
    const val MIN_TIMEOUT_MS = 20_000L

    /** 上界:站点声明 60s 时也不让用户等超过这个数 */
    const val MAX_TIMEOUT_MS = 45_000L

    /** 站点限时之外再给一段余量,吸收排队与网络抖动 */
    private const val HEADROOM_MS = 12_000L

    /** 站点未声明 timeout 时的缺省(与 SourceBean 同口径) */
    private const val DEFAULT_SOURCE_TIMEOUT_SECONDS = 8

    /** @param sourcePlayTimeoutSeconds 站点声明的取数限时(秒);非正数按缺省处理 */
    fun timeoutMs(sourcePlayTimeoutSeconds: Int): Long {
        val seconds = if (sourcePlayTimeoutSeconds > 0) {
            sourcePlayTimeoutSeconds
        } else {
            DEFAULT_SOURCE_TIMEOUT_SECONDS
        }
        return (seconds * 1000L + HEADROOM_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
