package com.github.tvbox.osc.util

import java.util.Locale

/**
 * 拼音匹配的**纯逻辑**(无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 为什么拼音必须配"本地索引"才有用
 * 关键约束:源站的搜索接口收的是**字面关键词**。用户敲 `qjyf` 时,把它原样发给源站
 * 一定搜不到 —— 源站库里没有哪个片名是 `qjyf`。所以拼音搜索**不能**只是"多一种匹配规则",
 * 它必须先把拼音**解析成中文片名**,再拿那个中文片名去搜(见 [TitleIndexMemory])。
 *
 * <p>本对象只负责"给定一个片名的拼音、和一个拼音输入,是否匹配"这一层判定;
 * 汉字→拼音的转换在 [PinyinTransliterator](依赖平台 ICU),两者刻意分开,
 * 让这一层能被单测完全覆盖。
 *
 * <p>## 匹配口径(与中文匹配同构,便于复用既有排序权重)
 * 全拼优先于首字母、前缀优先于包含:
 * <ol>
 *   <li>全拼全等(5)/ 前缀(4)/ 包含(3)—— 与中文的 `全等/前缀/包含` 同档;</li>
 *   <li>首字母全等(2)/ 前缀(2)/ 包含(1)—— 首字母信息量远低于全拼
 *       (`hy` 能命中几十个片名),所以整体压到中文"包含"之下。</li>
 * </ol>
 *
 * <p>## 为什么首字母全等只给 2 分
 * 首字母的碰撞率高得惊人:一部剧叫《汉月》,拼音 `hanyue`、首字母 `hy`;
 * 另一部《红颜》也是 `hy`。若给首字母全等 5 分,输入 `hy` 会让《红颜》排到
 * 《汉月》前面,而用户想要哪一个完全无法从输入判断 —— 此时**应该并列**,
 * 让后续的中文相关度与到达序去决定。压低到 2 分正是为了让它们同分。
 */
object PinyinQuery {

    /** 拼音音节里允许出现的字符(去声调后的 ASCII 字母) */
    private val NON_ALNUM = Regex("[^a-z0-9]")

    /**
     * 输入是否"看起来是拼音":全是 ASCII 字母,且**不含任何汉字与数字**。
     *
     * <p>用于决定"要不要**优先**尝试**拼音解析"。⚠️ 注意语义(2026-10-12 修正):
     * 它**只**决定"要不要去本地索引里试一次",**不**决定"要不要搜源站" ——
     * 拼音解析失败必须**退回普通关键词搜索**,绝不能拒绝服务(见 [SearchViewModel.search])。
     *
     * <p>## 为什么排除数字(这是上一版的真实设计错误)
     * 初版允许数字,于是 `MIAA-195`、`SSIS-001`、`FC2-PPV` 这类**番号全被判成拼音**:
     * 字母有、数字允许、连字符允许 ⇒ 判定为拼音 ⇒ 本地索引必然查不到
     * ⇒ 走进"解析失败"分支 ⇒ **一个请求都不发**,用户看到
     * "暂无法识别,先搜一次中文片名即可建立"。
     *
     * <p>而番号搜索是这个 App 的**主要用例之一**(`SearchSettings.parseIntent` 专门识别
     * `INTENT_CODE_TOKEN` 番号并做冲突保护)。等于新功能把一条主路径弄坏了。
     *
     * <p>判据收紧的依据:拼音输入**几乎不会出现"字母紧接数字"** ——
     * 用户敲拼音就是敲字母(可带空格/连字符/撇号作音节分隔),而番号的特征正是
     * "字母块 + 数字块"。所以**含数字即不判为拼音**,交给普通搜索(源站本就支持番号搜索)。
     *
     * <p>代价:极少见的"片名里带数字、用户按拼音敲"(如《三体3》敲 `santi3`)
     * 不再走拼音解析,会按字面去搜 —— 那只是搜不到,不会拒绝服务,可接受。
     *
     * <p>## 为什么还要限长(2026-10-12 收敛为只认首字母;2026-10-18 上限从 6 提到 12)
     * 既然只匹配**首字母**,那么合法输入就是"2~N 个字母"(对应 2~N 字片名)。
     * 超过 [MAX_INITIALS_LEN] 的字母串不再是首字母输入 —— 它更可能是:
     * 用户真在敲英文片名(如 `thematrix`)、或误粘了一串东西。
     * 那种情况**不该**去拼音索引里试(试了也几乎必然落空),直接按普通搜索走更省事。
     *
     * <p>⚠️ 上限从 6 提到 12 的理由见 [MAX_INITIALS_LEN]:原值把 7 字以上片名的
     * 首字母输入整类挡在门外(《寂静之地入侵日》=`jjzdrqr`),而中文片名超 6 字很常见。
     *
     * <p>⚠️ 这仍然只是"要不要去索引试一次"的判据 —— 命中与否都不会拒绝服务。
     */
    fun looksLikePinyin(query: String?): Boolean {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return false
        var letterCount = 0
        for (c in q) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> letterCount++
                // ⚠️ 数字一律否决:见 KDoc —— 含数字的字母串几乎都是番号,不是拼音
                c in '0'..'9' -> return false
                c == ' ' || c == '-' || c == '_' || c == '\'' -> {}
                else -> return false   // 含 CJK / 标点 / 其它 ⇒ 不是拼音输入
            }
        }
        // 首字母输入的长度上限:见 KDoc
        return letterCount in 1..MAX_INITIALS_LEN
    }

    /**
     * 首字母查询的字母数上限(不含分隔符)。
     *
     * <p>取 12(2026-10-18 从 6 上调):原值 6 会**硬性挡掉 7 字以上片名的首字母输入** ——
     * 如《寂静之地入侵日》= `jjzdrqr`(7 字母)会被判成"不是首字母输入",
     * 直接按字面去问源站,而源站不认这种输入 ⇒ **搜不到**。
     * 中文片名超过 6 字很常见(《长安十二时辰》恰好 6 字,正在旧阈值的边界上),
     * 12 已覆盖绝大多数。
     *
     * <p>上调的**代价评估**(为什么不必担心误判):
     * 超过上限本来就会回落普通搜索,而上调后即使真把一串长英文(`thematrixreloaded`)
     * 当成拼音去索引试一次,结果也只是"索引里没有 ⇒ 落空 ⇒ 回落普通搜索" ——
     * **与现在完全同一条路,只是多一次无效查表**。这也正是原 KDoc 自己写的
     * "试了也几乎必然落空",所以"落空"是既有行为,上调不引入新失败模式。
     */
    const val MAX_INITIALS_LEN = 12

    /** 归一化查询:小写、去所有非字母数字(空格/连字符/撇号都不参与比较) */
    fun normalize(raw: String?): String =
        NON_ALNUM.replace(raw?.lowercase(Locale.ROOT).orEmpty(), "")

    /**
     * 取**首字母串**:`"han yue"` → `"hy"`。
     *
     * <p>按音节边界取首字母,而不是取整串的首字母 —— 后者对多字片名没有意义。
     * 音节边界用空白、连字符、下划线**或撇号**;若输入已压紧(无分隔),则退化为
     * "整串首字母",此时调用方本就不该指望首字母匹配。
     *
     * <p>⚠️ 撇号必须算分隔符:ICU 给 `xi'an`(西安)这类音节加隔音撇号,
     * 而 [PinyinTransliterator.normalizeSyllables] 会把撇号折成空格 —— 两处口径必须一致,
     * 否则"转换侧产出 `xi an`、匹配侧直接收到 `xi'an`"两种输入会得出不同首字母
     * (前者 `xa`、后者 `x`),同一部片在两条路径上匹配结果不同。
     * 这是 CI 抓到的:单测期望 `xa` 实得 `x`。
     */
    fun initials(pinyin: String?): String {
        val raw = pinyin?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val parts = raw.split(' ', '-', '_', '\'').filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            // 无音节分隔:无法可靠切分,返回整串首字母(调用方应避免依赖)
            val only = NON_ALNUM.replace(raw, "")
            return if (only.isEmpty()) "" else only.first().toString()
        }
        val sb = StringBuilder(parts.size)
        for (p in parts) {
            val cleaned = NON_ALNUM.replace(p, "")
            if (cleaned.isNotEmpty()) sb.append(cleaned[0])
        }
        return sb.toString()
    }

    /**
     * 拼音匹配打分:越高越贴合。0 = 不匹配。
     *
     * <p>## ⚠️ 只认**首字母**(2026-10-12 收敛,用户决定)
     * 全拼档(`qianjunyifa`)已**刻意移除**。理由:
     * <ul>
     *   <li><b>TV 端是主场景</b>:遥控器上一个字一个字地按 10+ 个字母,成本高到用户
     *       根本不会这么做;而首字母只需 2~4 键(`qjyf`),这才是 TV 端真实可用的输入方式;</li>
     *   <li><b>全拼没有独立价值</b>:能敲出全拼的场景(手机/键盘),用户直接打中文更快 ——
     *       全拼只是在"不想切输入法"时的一个折中,而首字母覆盖了同样的需求且更省力;</li>
     *   <li><b>避免误命中</b>:全拼的"包含"档(`full.contains(q)`)会让任意短串命中大量片名
     *       (如 `an` 命中所有含 an 音节的片名),信噪比差。首字母的碰撞面小得多。</li>
     * </ul>
     *
     * <p>档位(与中文匹配 [SearchSettings.relevanceScore] 同构,便于同表比较):
     * 首字母全等/前缀 = 2,包含 = 1。
     * 全等与前缀**同档**:首字母信息量相同(`qj` 与 `qjyf` 都只是"开头几个字母"),
     * 强行区分只会让排序对用户的按键次数敏感,而那不是内容相关度。
     */
    fun score(pinyin: String?, query: String?): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 0
        val ini = initials(pinyin)
        if (ini.isEmpty()) return 0
        if (ini == q) return 2
        if (ini.startsWith(q)) return 2
        if (ini.contains(q)) return 1
        return 0
    }

    fun matches(pinyin: String?, query: String?): Boolean = score(pinyin, query) > 0
}
