package com.github.tvbox.osc.ui.activity

/**
 * 一场观看内「换源列表」的**身份台账**：谁进过表、它的匿名号是几号、它现在是什么状态（2026-10-15/16）。
 *
 * <p>## 号为什么绑身份而不绑位置
 * 那一行 chip 的「源1／源2／源3」是用户唯一的抓手 —— 他要说"切回刚才那个源"，靠的就是这个号。
 * 而列表每来一次回包都按四键重排（可看性→相关度→权重→到达序），号若按渲染位置现算，
 * **同一个源每轮都可能换个数字**。所以号在第一次进表时定下、之后不变、也不回收
 * （隐藏会留下跳号，这是刻意接受的代价：跳号只是看着别扭，找不到源是致命的）。
 *
 * <p>## 状态：为什么"隐藏"要两条不同来源的证据
 * 用户 2026-10-16 定的形态：自动换源链替他试过、放不出来 ⇒ 标 🟡；**他本人又手动切过去、再失败** ⇒ 隐藏。
 * 刻意用两个独立布尔而不是"失败计数"：同一次故障可能被多条路径各报一次
 * （线路耗尽 + 详情超时 + 质量看门狗），计数会把一次抖动凑成"两次证据"而误隐藏一个源。
 *
 * <p>## 为什么这里自己记状态，不复用短期失败记忆
 * [com.github.tvbox.osc.player.PlaybackFailureMemory] 的 TTL 是 5 分钟，而且那 5 分钟是刻意的
 * （源站抖动不该被永久拉黑，过后给重试机会）。那个语义是给"自动链别再原地打转"用的。
 * 给人眼看的状态跟着它一起过期，就会出现"🟡 自己变回无色、隐藏的源自己冒回来"——
 * 所以自动链继续用它那 5 分钟账，**这块面板的状态只活到这部片结束**（换片即清，换源**不**清 ——
 * 他还在同一部片里，🟡 与隐藏的源不该因为切了一刀就自己跑回来）。
 *
 * <p>纯逻辑，无 IO / Android 依赖 —— 由 `SourceBoardLedgerTest` 锁行为。
 */
class SourceBoardLedger {

    /** 面板上这一行的状态 */
    enum class Mark {
        /** 正常：没试过，或试过且播通过 */
        NONE,

        /** 🟡：自动链或用户其中一路试过、放不出来（还留着给他切） */
        SUSPECT,

        /** 两条不同来源都失败过 ⇒ 这一场不再显示（表里仍在，随时可回） */
        HIDDEN,
    }

    private val ordinals = LinkedHashMap<String, Int>()
    private val autoFailed = HashSet<String>()
    private val userFailed = HashSet<String>()

    /** 这个源的号（从 1 起）；没进过表返回 0 —— 调用方据此不渲染这一行，而不是显示"源0" */
    fun ordinalOf(sourceKey: String?): Int =
        if (sourceKey.isNullOrEmpty()) 0 else ordinals[sourceKey] ?: 0

    /**
     * 登记这个源并返回它的号。**同一个源重复登记拿回同一个号** —— 这是"号不漂"的全部机制。
     *
     * <p>空 key 不占号（返回 0）：候选里偶有 `sourceKey` 缺失的脏条目，给它编号只会让用户
     * 对着一个永远点不动的 chip 发懵。
     */
    fun assign(sourceKey: String?): Int {
        if (sourceKey.isNullOrEmpty()) return 0
        ordinals[sourceKey]?.let { return it }
        val next = ordinals.size + 1
        ordinals[sourceKey] = next
        return next
    }

    /**
     * 记一次"这个源放不出来"。`fromAutoChain` 区分证据来自哪条路：
     * 自动换源链替他试的 / 他亲自点过去试的 —— **两条都到齐才隐藏**。
     *
     * @return 状态是否发生变化（没变就不用重刷列表）
     */
    fun recordFailure(sourceKey: String?, fromAutoChain: Boolean): Boolean {
        if (sourceKey.isNullOrEmpty()) return false
        val before = markOf(sourceKey)
        if (fromAutoChain) autoFailed.add(sourceKey) else userFailed.add(sourceKey)
        return markOf(sourceKey) != before
    }

    /** 这个源播通了 ⇒ 状态清零（🟡 必须可逆：抖动过一次不代表这一场都不行） */
    fun recordSuccess(sourceKey: String?): Boolean {
        if (sourceKey.isNullOrEmpty()) return false
        val before = markOf(sourceKey)
        autoFailed.remove(sourceKey)
        userFailed.remove(sourceKey)
        return markOf(sourceKey) != before
    }

    fun markOf(sourceKey: String?): Mark {
        if (sourceKey.isNullOrEmpty()) return Mark.NONE
        val a = autoFailed.contains(sourceKey)
        val u = userFailed.contains(sourceKey)
        return when {
            a && u -> Mark.HIDDEN
            a || u -> Mark.SUSPECT
            else -> Mark.NONE
        }
    }

    /** 是否参与渲染。**只影响视图**：条目仍留在候选表里（数据层从不因状态而删条目）。 */
    fun isVisible(sourceKey: String?): Boolean = markOf(sourceKey) != Mark.HIDDEN

    /** 当前处于隐藏态的源（列表渲染入口要整体排除它们） */
    fun hiddenKeys(): Set<String> = if (autoFailed.isEmpty() || userFailed.isEmpty()) emptySet()
    else autoFailed.intersect(userFailed)

    /** 已登记的源数（诊断用：与候选表条数同量级，不等就说明有源没被登记） */
    fun size(): Int = ordinals.size

    /** 换片：整场作废，号从 1 重新开始、状态清零。换源**不**清 —— 他还在看同一部片，号必须延续 */
    fun clear() {
        ordinals.clear()
        autoFailed.clear()
        userFailed.clear()
    }
}
