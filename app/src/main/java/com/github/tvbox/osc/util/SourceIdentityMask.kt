package com.github.tvbox.osc.util

/**
 * 用户可见文案里的**源身份脱敏**:把 URL、域名抹成 `***`。
 *
 * <p>为什么必须有:换源/换线全自动化之后,用户看到的任何提示都不该泄露"现在用的是哪个站"。
 * 而失败提示是最后一道泄露口 —— 源站返回的 err 往往原样带着接口地址或站名
 * (`PlaybackRetryDelegate.handleResolvePlayUrlFailed` 曾把它直接 `showErrorTip(err)` 透传出去),
 * `player_parse_failed_prefix` = "解析失败 >>> %1$s" 同理。
 *
 * <p>只抹可定位的技术标识,**保留错误语义**(如"超时""解析失败"),用户仍能判断该重试还是该换源。
 *
 * <p>纯逻辑、可单测。
 */
object SourceIdentityMask {

    private val URL = Regex("""(?i)\b(?:https?|ftp|rtsp|mms)://[^\s'"]+""")
    private val DOMAIN = Regex(
        """(?i)\b[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|net|org|cn|top|xyz|cc|me|tv|vip|io|pro|app|info|biz)\b"""
    )
    private const val MASK = "***"

    /**
     * @return 脱敏后的文本;入参为空则返回空串
     *
     * <p>⚠️ `@JvmStatic` 不能省:`PlayContainer.errorWithRetry`(Java)要调它,
     * 少了这个注解编译期报 "non-static method mask(String) cannot be referenced from a static context"。
     */
    @JvmStatic
    fun mask(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var out = URL.replace(text, MASK)
        out = DOMAIN.replace(out, MASK)
        return out.trim()
    }

    /**
     * 候选源的**匿名展示名**:只由"第几个"决定,与真实站名无关。
     *
     * <p>为什么不能就地 mask 真实站名:`mask` 抹的是 URL/域名这种**技术标识**,
     * 而站名(如"某某影视""某某资源")是纯中文/英文词组,正则抹不掉;硬塞进 mask
     * 只会连带把正常文案也搅坏。这里的口径不同 —— 不是"打码",是**根本不查**:
     * 调用方压根不去取 `ApiConfig.getSource(key).name`,所以无从泄露。
     *
     * <p>下标从 0 开始(调用方通常已按展示顺序排好序),内部 +1 变回人看的 1 基序号。
     * 同一屏内序号稳定即可,**跨屏不需要唯一** —— 目的只是让用户能口头说"第 2 个那个",
     * 而不是让他知道那是哪个站。
     *
     * @param index 候选在展示列表中的下标(0 基)
     * @return 形如 `源 2` 的匿名标签;index 为负时返回空串(调用方应跳过该行)
     */
    @JvmStatic
    fun anonymousLabel(index: Int, prefix: String): String {
        if (index < 0) return ""
        val p = prefix.trim()
        return if (p.isEmpty()) (index + 1).toString() else "$p ${index + 1}"
    }
}
