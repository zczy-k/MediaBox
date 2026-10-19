package com.github.tvbox.osc.util

import java.util.Locale

/**
 * 「番号(作品编号)」的**唯一识别口径**(纯函数,无 IO/Android 依赖 —— 由 TitleCodePolicyTest 锁行为)。
 *
 * <p>## 解决什么
 *
 * 真机实测(2026-10-19,源 `py_02e87301` / AV01 站):搜 `MIDA-791` 时,
 * **列表卡片**显示 `MIDA-791 AI无法创造的…`,点进**详情页**只剩 `AI无法创造的…`。
 *
 * <p>根因在源侧 —— 同一个 spider 的两个函数各写各的:
 * <ul>
 *   <li>搜索 `_item()`:`vod_name = "%s %s" % (dvd_id, title)` ⇒ **番号拼在名字最前**;</li>
 *   <li>详情 `detailContent()`:`name = _clean(title)` ⇒ **番号被丢掉**,只塞进 `vod_remarks`
 *       (而详情页不渲染 remarks)。</li>
 * </ul>
 *
 * <p>这不只是"少显示几个字":详情标题是 `searchTitle` 的来源,丢了番号之后
 * 换源链拿"一整串中文长名"去搜别的站,命中率远低于拿番号搜。
 *
 * <p>## 为什么在 App 侧兜底
 *
 * 源是远端订阅下发(`sub.lytvs.top/pydl/xxx.py`),改本地缓存会被刷新覆盖;
 * 而"列表拼番号、详情不拼"是**一类**源的常见病,不止 AV01 一家。
 * 在 App 侧做一次通用回填,对所有这类源一次性生效。
 *
 * <p>## 三条必须守住的边界
 *
 * <ul>
 *   <li>**只在详情名里一个番号都没有时才补**:详情已经自带番号(如
 *       `MIDA-791-RM [Reducing Mosaic]…`)一律不动 —— 否则会出现
 *       `MIDA-791 MIDA-791-RM …` 这种双份,或者把两个不同番号硬拼在一起。</li>
 *   <li>**只认开头附近的番号**:片名后半段的年份/集数(`… 第2季 2026`)不是番号,
 *       误抽出来会把详情标题改成 `2026 …`。窗口取 [LEADING_WINDOW] 个字符,
 *       与 [PlayTitleMatch] 的保守口径一致。</li>
 *   <li>**抽不出番号就原样返回**(fail-open):中文片名、纯英文片名、纯数字片名
 *       一律不加工 —— 宁可少补一次,不可把标题改坏。</li>
 * </ul>
 */
internal object TitleCodePolicy {

    /**
     * 番号形状:2~14 个字母 + 分隔符 + 2~8 位数字。
     *
     * <p>`FC2-PPV-1234567` 单独一条:按通用规则会被切成 `FC2`,那是错的 ——
     * 这一族作品的番号整体才是检索词(与 [SubtitleSearchQuery] 同口径)。
     *
     * <p>前后都加了"不能紧邻字母数字"的断言:避免 `XADN-811` 被当成 `ADN-811`、
     * 也避免 `ADN-8112` 被截成 `ADN-811`。
     */
    private val CODE = Regex(
        "(?i)(?<![a-z0-9])(?:fc2[\\s._-]*ppv[\\s._-]*[0-9]{5,8}|[a-z]{2,14}[\\s._-]*[0-9]{2,8})(?![a-z0-9])"
    )

    /** 番号必须出现在标题**前**这么多字符内,否则视为"片名中段的数字",不当番号 */
    private const val LEADING_WINDOW = 8

    private val SEPARATORS = Regex("[\\s._]+")
    private val DASHES = Regex("-+")

    /**
     * 取出标题**开头**的番号,规范成 `MIDA-791` 形态;没有则返回空串。
     *
     * <p>规范化只做三件事:去首尾空白、分隔符统一成 `-`、字母转大写。
     * **不动数字**:`SSIS-001` 的前导零是番号的一部分,剥掉就变成另一个号了。
     */
    fun leadingCode(title: String?): String {
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val match = CODE.find(raw) ?: return ""
        if (match.range.first > LEADING_WINDOW) return ""
        return normalizeCode(match.value)
    }

    /** 标题里**任意位置**是否已经有番号 —— 用于判断"详情名自带番号,不需要补" */
    fun hasAnyCode(title: String?): Boolean {
        val raw = title?.trim().orEmpty()
        return raw.isNotEmpty() && CODE.containsMatchIn(raw)
    }

    /**
     * 用 [reference](用户点进来的那条卡片的标题)里的番号,补回 [detail] 的开头。
     *
     * @return 补好的标题;不需要补 / 抽不出番号时**原样返回** [detail]。
     */
    fun restoreCode(reference: String?, detail: String?): String {
        val target = detail?.trim().orEmpty()
        if (target.isEmpty()) return target
        val code = leadingCode(reference)
        if (code.isEmpty()) return target
        // 详情名自带番号(可能和 reference 不同版本号)⇒ 一律不动,避免双份/错拼
        if (hasAnyCode(target)) return target
        return "$code $target"
    }

    /**
     * 在线字幕搜索框的预填词:标题里有番号就**只填番号**,没有才填整条标题。
     *
     * <p>为什么:字幕站按番号索引的命中率远高于按"番号+一长串译名"搜 ——
     * 后者常常一条都搜不到。而中文片名/普通剧集没有番号,整条标题就是最优检索词,
     * 此时保持原样(不改动用户看到的输入框内容)。
     */
    fun subtitleSeed(title: String?): String {
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val code = leadingCode(raw)
        return code.ifEmpty { raw }
    }

    private fun normalizeCode(raw: String): String =
        raw.trim()
            .replace(SEPARATORS, "-")
            .replace(DASHES, "-")
            .trim('-')
            .uppercase(Locale.ROOT)
}
