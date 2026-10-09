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
        return true
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
