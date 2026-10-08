package com.github.tvbox.osc.util

import java.util.Locale

/**
 * 一次「源不可用」的证据。三类各计 **1 次**失败,门槛只看次数与影片数,不再加权 ——
 * 计分口径越复杂越难解释,而用户要的是"反复失败就别再选了"。
 *
 * <p>为什么要有 [contentKey]:门槛之一是"涉及至少 2 部**不同**影片"。只看次数会被
 * "同一部烂片连点三次"顶满(那可能只是那部片在源站被下架,不是源坏了)。
 */
enum class SourceFailKind {
    /** 搜索请求超时(产品口径:一律计一次失败,见需求规则 3) */
    SEARCH_TIMEOUT,

    /** 搜索请求失败(HTTP 异常/解析异常)。与超时同类:源没给出有效应答 */
    SEARCH_FAILED,

    /** 选中该源起播失败(所有线路试完仍放不出来,且**不是**纯网络原因) */
    PLAY_FAILED,
}

/** 单条失败证据。[at] 为写入时刻(ms)。 */
data class SourceFailEvent(val kind: SourceFailKind, val at: Long, val contentKey: String)

/**
 * 单个源的健康状态(可序列化到 KV)。
 *
 * @param fails        24 小时窗口内的失败证据(超出窗口在读/写时裁掉)
 * @param banCount     已封禁**次数**:1 = 曾封 6 小时;2 = 曾封 24 小时;≥3 = 已进永久封禁
 * @param bannedUntil  本次封禁的解除时刻;0 = 当前未封禁。[manualLocked] 为真时无意义
 * @param manualLocked 永久封禁:到点也不自动解封,只能由用户手动解除
 */
data class SourceHealthState(
    val fails: List<SourceFailEvent> = emptyList(),
    val banCount: Int = 0,
    val bannedUntil: Long = 0L,
    val manualLocked: Boolean = false,
)

/**
 * 防滥用封禁的**唯一判据**(纯函数,无 IO/Android 依赖 —— 由 SourceHealthPolicyTest 锁行为)。
 *
 * <p>规则(产品口径):
 * <ol>
 *   <li>触发:24 小时窗口内失败 ≥ [MIN_FAILS] 次,且涉及 ≥ [MIN_DISTINCT_CONTENTS] 部不同影片;</li>
 *   <li>时长升级:首次 [FIRST_BAN_MS](6h)→ 再次 [SECOND_BAN_MS](24h)→ 第三次及以后**永久**([manualLocked]);</li>
 *   <li>搜索超时一律计一次失败。</li>
 * </ol>
 *
 * <p>两处容易写错、必须守住的地方:
 * <ul>
 *   <li>**封禁生效时必须把证据清空**:否则 6 小时解封后旧的三条还在窗口里,下一次失败立刻又触发封禁,
 *       "会自动解封"就成了一句空话;</li>
 *   <li>解封只清证据、**不清 [SourceHealthState.banCount]**:升级要靠它累计,清了就永远只封 6 小时。</li>
 * </ul>
 */
object SourceHealthPolicy {

    /** 失败窗口:24 小时 */
    const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** 触发门槛:窗口内失败次数 */
    const val MIN_FAILS = 3

    /** 触发门槛:窗口内涉及的不同影片数 */
    const val MIN_DISTINCT_CONTENTS = 2

    /** 首次封禁时长 */
    const val FIRST_BAN_MS = 6L * 60 * 60 * 1000

    /** 第二次封禁时长 */
    const val SECOND_BAN_MS = 24L * 60 * 60 * 1000

    /**
     * 内容键:用于"不同影片"计数。
     *
     * <p>用**归一化后的片名**而不是 `源|片id`:同一部片在不同源里的 id 不同,按 id 计会把
     * "同一部片在两个源都放不出来"算成 2 部,门槛就被稀释了;而片名归一化后天然跨源可比。
     * 归一化只做空白/大小写/全角空格处理,不做别名归并(那是搜索匹配的事,口径不同)。
     */
    fun contentKey(title: String?): String {
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return raw.lowercase(Locale.ROOT).replace(WHITESPACE, "")
    }

    private val WHITESPACE = Regex("\\s+")

    /** 裁掉窗口外的证据。未来时间戳(时钟回拨/手动改时间)保留,免得因为时间跳变丢掉近期的真实证据 */
    fun prune(fails: List<SourceFailEvent>, now: Long): List<SourceFailEvent> =
        fails.filter { it.at > now - WINDOW_MS }

    /** 窗口内涉及的不同影片数(内容键为空的证据不计,避免"取不到片名"把门槛凑够) */
    fun distinctContents(fails: List<SourceFailEvent>): Int =
        fails.asSequence().map { it.contentKey }.filter { it.isNotEmpty() }.toSet().size

    /** 是否满足封禁门槛 */
    fun shouldBan(fails: List<SourceFailEvent>, now: Long): Boolean {
        val window = prune(fails, now)
        return window.size >= MIN_FAILS && distinctContents(window) >= MIN_DISTINCT_CONTENTS
    }

    /** 当前是否处于封禁态 */
    fun isBlocked(state: SourceHealthState, now: Long): Boolean =
        state.manualLocked || state.bannedUntil > now

    /**
     * 到点自动解封(并清空证据)。永久封禁不动 —— 只有 [unblock] 能解。
     *
     * @return 与入参等价的新状态;无变化时返回原对象(调用方可据此跳过落盘)
     */
    fun normalize(state: SourceHealthState, now: Long): SourceHealthState {
        if (state.manualLocked) return state
        val window = prune(state.fails, now)
        val expired = state.bannedUntil in 1..now
        if (!expired && window.size == state.fails.size) return state
        return state.copy(
            fails = if (expired) emptyList() else window,
            bannedUntil = if (expired) 0L else state.bannedUntil,
        )
    }

    /**
     * 记一次失败。命中门槛则立即封禁,并按**已封次数**定档(fails 一并清空,见类注释)。
     *
     * @param contentKey 归一化片名,见 [contentKey];为空表示"这次失败取不到片名",
     *                   它仍计入次数,但不计入"不同影片数"
     */
    fun recordFail(
        state: SourceHealthState,
        kind: SourceFailKind,
        contentKey: String,
        now: Long,
    ): SourceHealthState {
        if (state.manualLocked) return state
        val base = normalize(state, now)
        val window = base.fails + SourceFailEvent(kind, now, contentKey)
        if (!shouldBan(window, now)) return base.copy(fails = window)
        return ban(base, now)
    }

    /** 按升级档位封禁:[SourceHealthState.banCount] 先自增,第 3 次起转永久 */
    fun ban(state: SourceHealthState, now: Long): SourceHealthState {
        val nextCount = state.banCount + 1
        return when (nextCount) {
            1 -> state.copy(banCount = 1, fails = emptyList(), bannedUntil = now + FIRST_BAN_MS, manualLocked = false)
            2 -> state.copy(banCount = 2, fails = emptyList(), bannedUntil = now + SECOND_BAN_MS, manualLocked = false)
            else -> state.copy(banCount = nextCount, fails = emptyList(), bannedUntil = 0L, manualLocked = true)
        }
    }

    /**
     * 手动解除(设置页「全部解除」)。
     *
     * <p>清证据、清封禁态,**保留 [SourceHealthState.banCount]** —— 已经封过三次的源再犯仍应进永久档,
     * 否则"手动解除"就成了绕过升级逻辑的后门。
     */
    fun unblock(state: SourceHealthState): SourceHealthState =
        state.copy(fails = emptyList(), bannedUntil = 0L, manualLocked = false)
}
