package com.github.tvbox.osc.util

/**
 * 搜索结果"同名聚合"的**纯判定规则**(P2,2026-10-09;无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 为什么聚合必须有身份校验
 * 归并键是**归一化标题**——但同名不等于同片:同名翻拍(1986/2010 两版《西游记》)、
 * 同名剧场版与剧集、国内外同名作品,标题归一化后完全一致。若只按键归并,
 * 用户点开的可能是完全不同的片子,这是比"多显示一行重复"严重得多的**误并事故**。
 *
 * <p>因此本策略要求键相同**且身份相容**才算同片:
 * 年份/地区/类型三个字段,任一"双方都确知且互相矛盾"(如 2004 vs 2017)即拆为两组。
 * 刻意与可用性粗筛同一设计哲学:**宁漏并不误并** —— 拿不准就分开,分开的代价只是
 * 列表多一行,误并的代价是"点开是另一部片"。
 *
 * <p>## Diff key 稳定性(调用方契约)
 * 返回的 [Group.titleKey]+[Group.subIndex] 与**列表位置无关**:同一标题的新结果并入
 * 同组时组键不变(子组只有在发生身份冲突时才新增)。调用方据此构造 LazyColumn 的
 * 稳定 key,新结果到达只触发组内容更新,不触发行身份变化 —— 滚动位置与动画都稳定。
 *
 * <p>## 输出顺序
 * 组的顺序 = 各组**首个成员**在输入中的出现顺序(桶创建序);组内成员保持输入序。
 * 无有效标题(归一化为空)的条目永不归并,各自独立成组(键由调用方回退到行键)。
 */
object SearchDedupPolicy {

    /** 参与聚合判定的身份字段(调用方从 Movie.Video 提取;纯值便于 JVM 单测) */
    data class FilmIdentity(
        /** 归一化标题(归并键);空串=无有效标题,**永不与他人归并** */
        val titleKey: String,
        /** 年份;0=未知。双方都确知且不同 ⇒ 不同片 */
        val year: Int,
        /** 地区;""=未知。双方都确知且不同(忽略大小写)⇒ 不同片 */
        val area: String,
        /** 类型;""=未知。双方都确知且不同(忽略大小写)⇒ 不同片 */
        val type: String,
        /**
         * 内容角标(note)归一化后的**版本关键词**;""=无版本标注。
         *
         * <p>## 为什么 note 必须参与身份判定(2026-10-11 真机事故)
         * 番号类影片的"有码 / 无码 / 中文字幕"标注**只存在 note 里**,而标题(番号本身)
         * 在两个源里**完全一样**。此前身份只比 年份/地区/类型 —— 这三项对番号片通常全空或相同
         * ⇒ 判定"相容" ⇒ **两个内容不同的版本被并成一张卡**:用户看到一张缩略图 + 角标写
         * "2 个源",而聚合组只渲染一个 representative ⇒ 另一个版本(例如无码)在列表上**消失了**。
         *
         * <p>这与本类 KDoc 的"宁漏并不误并"一致:有码/无码是**不同的观看内容**,
         * 误并的代价不是"多一行冗余",而是"用户要找的那个版本根本看不到"。
         *
         * <p>取值须由 [normalizeNote] 产出(抽关键词而非整串),否则源站写法差异
         * ("无码"/"無碼"/"无码高清")会把同一版本拆成多组。
         */
        val noteKey: String = "",
    )

    /**
     * 一个聚合组。[titleKey]+[subIndex] 构成稳定组键(同一桶内身份冲突时才产生新子组,
     * 子组序按桶内创建序编号);[members] 保持输入序。
     */
    data class Group<T>(val titleKey: String, val subIndex: Int, val members: List<T>)

    /** 身份相容:不存在任何"双方都确知且互相矛盾"的字段 */
    fun compatible(a: FilmIdentity, b: FilmIdentity): Boolean {
        if (a.year > 0 && b.year > 0 && a.year != b.year) return false
        if (a.area.isNotEmpty() && b.area.isNotEmpty() && !a.area.equals(b.area, ignoreCase = true)) return false
        if (a.type.isNotEmpty() && b.type.isNotEmpty() && !a.type.equals(b.type, ignoreCase = true)) return false
        // 版本角标:只有**双方都明确标了、且标的不一样**才算冲突。
        // 一侧无角标 ⇒ 不判死(该源只是没标,不代表它是另一个版本);否则"有角标的源"
        // 会被永远隔离在自己那一组里,反而把本该聚合的常规片名拆散。
        if (a.noteKey.isNotEmpty() && b.noteKey.isNotEmpty() && a.noteKey != b.noteKey) return false
        return true
    }

    /**
     * note(内容角标)归一化:**只抽"版本判别"关键词**,其余噪声(集数/评分/画质套话)一律丢弃。
     *
     * <p>## 为什么抽关键词而不是整串比较
     * 源站的 note 是自由文本:`"无码"` / `"無碼"` / `"无码高清"` / `"HD中字"` /
     * `"无码 中文字幕"`。整串比较会把同一版本拆成多组;整串丢弃又回到误并。
     * 抽成**有序关键词集**后,与出现顺序、附加修饰词都无关。
     *
     * <p>## 为什么刻意**不含**集数描述
     * 常规剧集的 note 常是 `"共36集"` / `"更新至12集"`(见 AbsJson 的 note 回退链),
     * 也可能落成评分 `"4.4"`。这些是**同一部片的不同进度**,拿它当身份会让
     * 集数一集一涨、组键就变,列表跟着跳动。所以只认版本词,其余一律返回空串。
     */
    fun normalizeNote(note: String?): String {
        if (note.isNullOrEmpty()) return ""
        val lower = note.lowercase(java.util.Locale.ROOT)
        val hits = ArrayList<String>(2)
        // 有码 / 无码。
        //
        // ⚠️ **顺序有语义,不能调换**:`"uncensored"` 里**包含** `"censored"` 子串,
        // 若先判有码,`uncensored` 会被判成"有码"—— 正好判反(这是最坏的错法:
        // 两个版本仍然分开了,但标签互换,提示与实情相反)。
        // 同理中文侧先判"无码"再判"有码"。
        //
        // 关键词覆盖三种语言口径:
        //   中文 无码 / 繁体 無碼 / 英文 uncensored;
        //   中文 有码 / 繁体 有碼 / 英文 censored / 日文 モザイク(马赛克=有码)。
        if (lower.contains("无码") || lower.contains("無碼") || lower.contains("无修正")
            || lower.contains("無修正") || lower.contains("uncensored")
        ) {
            hits.add("无码")
        } else if (lower.contains("有码") || lower.contains("有碼")
            || lower.contains("修正") || lower.contains("censored") || lower.contains("モザイク")
        ) {
            // ⚠️ 日文侧:番号片的**主流写法**是 `無修正`(无码)与 `修正`(有码),
            // 而不是中文的"无码/有码"。此前只认中文与英文 ⇒ 日文源返回空串,
            // 而空串在 compatible() 里等于"该源没标" ⇒ **不与"无码"冲突 ⇒ 误并复现**。
            // 这是 2026-10-11 复查时发现的中文路径已修、日文路径漏修的缺口。
            hits.add("有码")
        }
        // 字幕:先判**否定式**(无字幕),再判肯定式。
        //
        // ⚠️ 与 uncensored/censored 同类的**判反陷阱**:`"无字幕"` 里**包含** `"字幕"`,
        // 若不分先后,"无字幕"(明确表示**没有**字幕)会被判成"中字" —— 结论正好相反。
        // 这比漏判更糟:漏判只是少一个版本标记,判反是给出错误结论。
        val noSubs = lower.contains("无字幕") || lower.contains("無字幕")
            || lower.contains("no sub") || lower.contains("unsubbed")
        // 中文字幕。日文源常写 `字幕`(汉字同形);英文侧常见标注是 `chinese` / `subbed`。
        // ⚠️ 刻意**不收**裸 `sub`:它是 `subtitle`/`subgroup` 等词的子串,会大面积误命中。
        val hasSubs = lower.contains("中字") || lower.contains("中文字幕") || lower.contains("字幕")
            || lower.contains("chinese") || lower.contains("subbed")
        if (noSubs) {
            // 单独一个标记:"无字"与"中字"是**不同版本**,必须能区分开
            hits.add("无字")
        } else if (hasSubs) {
            hits.add("中字")
        }
        // 破解 / 流出(英文 leak 指未授权流出)
        if (lower.contains("破解") || lower.contains("流出") || lower.contains("leak")) {
            hits.add("破解")
        }
        return hits.joinToString("+")
    }

    /**
     * 贪心分组:按 titleKey 分桶;桶内逐个尝试加入"与该子组**全部**成员相容"的子组
     * (与任一成员冲突就换下一子组,都不行才开新子组)。输入顺序即遍历顺序,
     * 输出顺序 = 各组首个成员的出现顺序。
     */
    fun <T> group(items: List<T>, identity: (T) -> FilmIdentity): List<Group<T>> {
        class Sub(val titleKey: String, val subIndex: Int, val firstIndex: Int, val members: ArrayList<T>)
        val buckets = HashMap<String, ArrayList<Sub>>()
        val created = ArrayList<Sub>()
        items.forEachIndexed { index, item ->
            val id = identity(item)
            if (id.titleKey.isEmpty()) {
                // 无有效标题:不参与聚合,独立成组(键由调用方回退到行键,保证唯一)
                created.add(Sub("", 0, index, arrayListOf(item)))
                return@forEachIndexed
            }
            val subs = buckets.getOrPut(id.titleKey) { ArrayList() }
            var target: Sub? = null
            for (sub in subs) {
                if (sub.members.all { compatible(id, identity(it)) }) {
                    target = sub
                    break
                }
            }
            if (target == null) {
                target = Sub(id.titleKey, subs.size, index, ArrayList())
                subs.add(target)
                created.add(target)
            }
            target.members.add(item)
        }
        return created.map { Group(it.titleKey, it.subIndex, it.members.toList()) }
    }
}
