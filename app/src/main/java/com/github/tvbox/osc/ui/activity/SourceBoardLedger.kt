package com.github.tvbox.osc.ui.activity

/**
 * 一场观看内「换源列表」的**身份台账**:谁进过这张表、它的匿名号是几号(2026-10-15)。
 *
 * <p>## 为什么要给号"绑身份"而不是绑位置
 * 那一行 chip 的「源1／源2／源3」是用户唯一的抓手 —— 他要说"切回刚才那个源",靠的就是这个号。
 * 而列表每次来新回包都会按四键重排(可看性→相关度→权重→到达序),号若按渲染位置现算,
 * **同一个源每轮都可能换个数字**,而且这张表现在一场观看内只增不减、活得更久,漂移会更频繁。
 * 所以号在**第一次进表时**定下,之后不变、不回收。
 *
 * <p>## 刻意保留的副作用(比"号漂"轻)
 * 隐藏或重排后会出现「源1 源2 源4」跳号、以及「源5」排在「源1」前面。
 * 号是身份、顺序是决策,两者互不表达对方的含义 —— 用户找不到"刚才那个源"才是致命问题。
 *
 * <p>## 生命周期
 * 只活到"这一场观看"结束:换片(导航目标变了)整张作废,号从 1 重新开始。
 * 它**不落盘** —— 跨会话的是搜索命中缓存与零命中台账,不是这张表(见方案定案)。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `SourceBoardLedgerTest` 锁行为。
 */
class SourceBoardLedger {

    private val ordinals = LinkedHashMap<String, Int>()

    /** 这个源的号(从 1 起);没进过表返回 0 —— 调用方据此不渲染这一行,而不是显示"源0" */
    fun ordinalOf(sourceKey: String?): Int =
        if (sourceKey.isNullOrEmpty()) 0 else ordinals[sourceKey] ?: 0

    /**
     * 登记这个源并返回它的号。**同一个源重复登记拿回同一个号** —— 这是"号不漂"的全部机制。
     *
     * <p>空 key 不占号(返回 0):候选里偶有 `sourceKey` 缺失的脏条目,给它编号会让用户对着
     * 一个永远点不动的 chip 发懵,不如不显示。
     */
    fun assign(sourceKey: String?): Int {
        if (sourceKey.isNullOrEmpty()) return 0
        ordinals[sourceKey]?.let { return it }
        val next = ordinals.size + 1
        ordinals[sourceKey] = next
        return next
    }

    /** 已登记的源数(诊断用:与候选表条数同量级,不等就说明有源没被登记) */
    fun size(): Int = ordinals.size

    /** 换片:整张作废,下一次观看从头定号 */
    fun clear() {
        ordinals.clear()
    }
}
