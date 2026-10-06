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

    /** @return 脱敏后的文本;入参为空则返回空串 */
    fun mask(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var out = URL.replace(text, MASK)
        out = DOMAIN.replace(out, MASK)
        return out.trim()
    }
}
