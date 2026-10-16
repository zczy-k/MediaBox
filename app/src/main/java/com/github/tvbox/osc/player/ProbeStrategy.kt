package com.github.tvbox.osc.player

/**
 * 一次探测尝试的**结果分类**(2026-10-15 智能探测)。
 *
 * <p>为什么要分类:不同失败该给完全不同的反应,而原来只有一行 `probe-failed http=xxx` 加 null。
 * 真机一天里就混着四种完全不同的病 —— 403(缺鉴权头)、502(网关抖动)、404(直链过期)、
 * 200 但内容里没有分辨率(HLS 子列表)。**用同一种"放弃"回四种,既测不出东西又白烧流量**。
 */
enum class ProbeOutcome {
    /** 拿到分辨率(或拿到可用的 master) */
    OK,

    /** 401/403:鉴权类 —— 只有这一类值得换身份重试 */
    FORBIDDEN,

    /** 404/410:这条直链已经失效。换头无用,重试也无用 */
    NOT_FOUND,

    /** 5xx:源站/网关抖动。换头无用,短时间后再看别的路,别当这站不能测 */
    SERVER,

    /** 连接失败/超时/IO 异常:网络侧问题,同样不该给站点贴标签 */
    NETWORK,

    /** 200 但内容不可用(HTML 页、空响应、加密壳):再试一样的请求还是废的 */
    CONTENT_UNUSABLE,

    /** 200 且是 m3u8,但 master 与分片内探都没给出分辨率 */
    NO_SIZE,
}

/**
 * 探测**用什么身份去请求**。
 *
 * <p>⚠️ 默认档是"与播放同源的 header"([ProbeTactic.SOURCE_HEADERS]),这条不能破:
 * 真机踩过探测用 `UA.random()` 而播放用源站 UA ⇒ **同一个 m3u8 播放顺畅、探测 404**,
 * 于是所有基于探测的画质决策都建在一个播放时根本不会出现的假象上。
 * 所以补头只允许发生在"已经被 403 拒绝之后",而不是无条件加。
 */
enum class ProbeTactic {
    SOURCE_HEADERS,

    /** 只补 Referer(聚合 CDN 的防盗链多数看这一项) */
    REFERER,

    /** Referer + UA 一起补(单补 Referer 仍被拒时的最后一档) */
    REFERER_PLUS_UA,
}

/**
 * 阶梯与冷却规则(纯函数,好测)。
 *
 * <p>设计取向:
 * <ul>
 *   <li>**只在有希望的方向上重试**。403 才换身份(先 Referer 后加 UA);5xx、超时、404 换头必然无用,
 *       一次都不多试 —— 探测是和播放抢带宽的,乱重试会把起播拖慢。</li>
 *   <li>**冷却按失败性质分档**:鉴权类(这个站确实挑头)冷却久一点,
 *       网关/网络类冷却短(可能只是抖动,过几分钟还要能测);
 *       404/内容不可用**不冷却站点** —— 那是这一条地址的问题,同一站的别的片子照样该试。</li>
 *   <li>**记住这个站上次是靠什么成功的**,下次直接从那一档开始,省掉前面几次注定失败的往返。</li>
 * </ul>
 */
object ProbeStrategy {

    /** 一条地址最多试几档(含默认档):再多就是在烧用户的带宽 */
    const val MAX_ATTEMPTS = 3

    /**
     * 鉴权类(403 把身份档位用尽仍失败)的站点冷却。
     *
     * <p>⚠️ 第一版这里是 30 分钟,真机立刻误伤:1080zyk 那条线因为"我们只试了 UA 那一档"
     * 就被整个排除在探测之外 30 分钟(`probe-skip reason=host-cooldown`)。
     * 冷却判的是"这个站拒绝被探测",而我们**试错方向**的时候不该判它 ——
     * 所以顺序修好(Referer 优先)之后,时长也压到 10 分钟:一波观看内不再纠缠,
     * 换一部片又不给机会就太难看。
     */
    const val COOL_DOWN_FORBIDDEN_MS = 10L * 60 * 1000

    /** 网关/网络类冷却:短到不影响下一次看片,又能挡住一波抖动里的连环重试 */
    const val COOL_DOWN_TRANSIENT_MS = 3L * 60 * 1000

    /** CDN 自述拒绝原因时最常见的词(中英都收;只为把原话定位到,不做翻译不做判断) */
    private val DENY_REASON = Regex(
        "denied|forbidden|unauthoriz|not allowed|not found|not exist|no such|blocked?|banned|expire|expired|invalid token|token|referer|referrer" +
            "|禁止|拒绝|无权|过期|到期|频繁|限制|防盗",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 这一档失败后,下一档用什么身份重试;返回 null = 别再试了。
     *
     * <p>顺序是 **Referer 先、UA 后**。第一版按"先补 UA"做,v1.1.36 日志里
     * `probe-retry … to=BROWSER_UA` 之后仍然 403;同一地址在 PC 上不带任何头反倒 200。
     * ⚠️ 这条对照**证不了**"卡的不是 UA":PC 那次同时换了 UA(curl 与 okhttp 的默认值不同)、
     * TLS 指纹和出口 IP,三个变量一起动,归因分不出来。改成 Referer 优先只是
     * "两个假设都没被证伪时,先试防盗链更常见的那一项",真正的判据是这一版加的
     * 按档结果(`tactic=`)与拒绝原因(`server=`/`deny=`) —— 别再拿状态码猜。
     *
     * <p>`alreadyHasUa` / `alreadyHasReferer` 用来跳过"换了等于没换"的那一档 ——
     * 源站本来就配了 Referer 时再"补 Referer"就是拿同一个请求打第二遍,白烧一次带宽。
     * 阶梯**单调**,补过的头不会被拆掉。
     */
    @JvmStatic
    fun nextTactic(
        current: ProbeTactic,
        outcome: ProbeOutcome,
        alreadyHasUa: Boolean,
        alreadyHasReferer: Boolean,
    ): ProbeTactic? {
        if (outcome != ProbeOutcome.FORBIDDEN) return null
        return when (current) {
            // 先补 Referer(聚合 CDN 的防盗链看这一项);源站已配 Referer 就跳过它,只剩 UA 可补
            ProbeTactic.SOURCE_HEADERS -> when {
                !alreadyHasReferer -> ProbeTactic.REFERER
                !alreadyHasUa -> ProbeTactic.REFERER_PLUS_UA
                else -> null
            }
            // 只补 Referer 仍被拒 ⇒ 再带上 UA(有些站两个都校验)
            ProbeTactic.REFERER -> if (alreadyHasUa) null else ProbeTactic.REFERER_PLUS_UA
            ProbeTactic.REFERER_PLUS_UA -> null
        }
    }

    /** 这个站上次是靠哪档成功的 ⇒ 下次从哪档开始(没记过就按默认档) */
    @JvmStatic
    fun firstTactic(lastSuccess: ProbeTactic?): ProbeTactic = lastSuccess ?: ProbeTactic.SOURCE_HEADERS

    /**
     * 失败之后要不要给这个站点上冷却,冷却多久(0 = 不冷却)。
     *
     * <p>NOT_FOUND / CONTENT_UNUSABLE / NO_SIZE 都不冷却站点:它们说明的是"这一条地址",
     * 不是"这个站不能测"。贴错标签的后果是整个站的画质优选长期失效。
     */
    @JvmStatic
    fun coolDownMs(outcome: ProbeOutcome, outOfTactics: Boolean): Long = when {
        outcome == ProbeOutcome.FORBIDDEN && outOfTactics -> COOL_DOWN_FORBIDDEN_MS
        outcome == ProbeOutcome.SERVER || outcome == ProbeOutcome.NETWORK -> COOL_DOWN_TRANSIENT_MS
        else -> 0L
    }

    /** 分类:HTTP 状态码 → 结果(内容层面判不出分辨率的由调用方给 NO_SIZE / CONTENT_UNUSABLE) */
    @JvmStatic
    fun classifyHttp(code: Int): ProbeOutcome = when {
        code == 401 || code == 403 || code == 407 -> ProbeOutcome.FORBIDDEN
        code == 404 || code == 410 || code == 415 -> ProbeOutcome.NOT_FOUND
        code in 500..599 -> ProbeOutcome.SERVER
        else -> ProbeOutcome.CONTENT_UNUSABLE
    }

    /**
     * 从 CDN 的拒绝响应里**抠出能读的原因**(2026-10-15 真机教训)。
     *
     * <p>第一版只取错误体第一行,真机打回来的是 `deny=<!DOCTYPE html>` —— 因为这类站
     * 回的是 HTML 拒绝页,第一行永远是 doctype,等于什么也没说。
     * 这里先去掉标签与脚本/样式块、折叠空白,再**优先取带原因关键词的那一句**;
     * 一句都没有就退回整段摘要,段落也为空时才用头里的提示(如 X-Tengine-Error)。
     *
     * <p>关键词表按"防盗链最常见的自述"收:referer / token / expire / ban / deny /
     * forbidden / block / unauthorized,加上中文侧的 禁止 / 拒绝 / 过期 / 无权 / 频繁 / 限制。
     * 不做翻译、不做解读 —— 这条日志的唯一职责是把 CDN 自己的话带到现场。
     */
    @JvmStatic
    fun denySummary(body: String?, headerHints: String? = null): String {
        val raw = body.orEmpty().trim()
        val stripped = raw
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]*>"), " ")
            .replace(Regex("&[a-z]+;|&#\\d+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (stripped.isEmpty()) return headerHints.orEmpty().ifBlank { "-" }.take(90)
        val hit = DENY_REASON.find(stripped)?.value
        if (hit != null) {
            // 取关键词所在的那一小段(前后各留一点),而不是从页头截起 —— 前面往往是导航文案
            val at = stripped.indexOf(hit).coerceAtLeast(0)
            return stripped.substring(maxOf(0, at - 30), minOf(stripped.length, at + hit.length + 55))
                .replace('\n', ' ').replace('"', '\'')
        }
        // 没命中原因词时:HTML 页(原始体以 `<` 开头)的前 60 字必然是导航或样式残渣 ——
        // 真机 v1.1.44 打回的就是 `404 body { margin: 0; padding: 0; background-color: #111`,
        // 那不是原因,是噪声。宁可老实说"它没说原因",也别拿 CSS 冒充诊断信息。
        if (raw.startsWith("<")) return "<none>"
        return stripped.replace('\n', ' ').replace('"', '\'').take(60)
    }
}
