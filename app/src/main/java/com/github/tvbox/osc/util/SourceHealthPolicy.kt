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

    /**
     * 选中该源起播失败(所有线路试完仍放不出来,且**不是**纯网络原因)。
     *
     * <p>⚠️ 2026-10-10 起**停止记录**(记录侧已摘除调用)且**不参与** [SourceHealthPolicy.shouldBan]:
     * 真机实证它会因单条线路的坏地址(如 Ksvideo 伪协议)误封整个健康源 6 小时。
     * 枚举保留仅为兼容旧台账 JSON 的反序列化。
     */
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
 * @param timeoutStreak **连续超时**次数(规则 2 的证据)。只有"源给出答案"([recordAnswered])才归零;
 *                      其它类型的失败既不加也不清 —— 详见 [recordFail] 的口径说明。
 *                      不变量:`timeoutStreak > 0` ⟹ `fails` 里至少有一条超时事件
 *                      (由 [normalize] 保证:证据出窗口时计数一并归零)。
 */
data class SourceHealthState(
    val fails: List<SourceFailEvent> = emptyList(),
    val banCount: Int = 0,
    val bannedUntil: Long = 0L,
    val manualLocked: Boolean = false,
    val timeoutStreak: Int = 0,
)

/**
 * 防滥用封禁的**唯一判据**(纯函数,无 IO/Android 依赖 —— 由 SourceHealthPolicyTest 锁行为)。
 *
 * <p>规则(产品口径),两条**并行**:
 * <ol>
 *   <li>**升级档位**(内容相关证据):24 小时窗口内失败 ≥ [MIN_FAILS] 次,且涉及
 *       ≥ [MIN_DISTINCT_CONTENTS] 部不同影片 → 封禁,时长按 [SourceHealthState.banCount] 升级
 *       (首次 [FIRST_BAN_MS](6h)→ 再次 [SECOND_BAN_MS](24h)→ 第三次及以后**永久**);</li>
 *   <li>**连续超时**(内容无关证据):连续 [TIMEOUT_STREAK_BAN] 次搜索超时 → 固定封禁
 *       [TIMEOUT_BAN_MS](1h),**不消耗 [SourceHealthState.banCount]**(不参与永久升级)。</li>
 * </ol>
 *
 * <p>## 为什么规则 2 要单独存在
 * 超时是**内容无关**的证据:"这个源连响应都没有"和"你搜的是哪部片"没有关系。
 * 而规则 1 的门槛里有"≥2 部**不同**影片" —— 这是为了挡住"同一部冷门片连点三次就封源"
 * (那可能只是那部片在源站被下架)。两者混在一个门槛里,后果是**用户要连搜三部不同的片子
 * 才会开始变快**,而真机实测一轮 328 个源里有 289 个超时(88%)—— 前两轮白等。
 * 拆开之后:超时走轻量快判(2 次即封 1 小时),内容相关失败仍走严格门槛。各按各的证据强度。
 *
 * <p>## 规则 2 为什么**不升级档位**
 * 超时是最弱的证据,一次网络抖动就能造成一串超时。若让它也累计 [SourceHealthState.banCount],
 * 第三次就会进入**永久封禁**、必须用户手动解除 —— 用最弱的证据给出最重的惩罚,不可接受。
 * 所以超时封禁固定 1 小时、到点自动解封,永不升级。
 *
 * <p>两处容易写错、必须守住的地方:
 * <ul>
 *   <li>**封禁生效时必须把证据清空**:否则 6 小时解封后旧的三条还在窗口里,下一次失败立刻又触发封禁,
 *       "会自动解封"就成了一句空话。规则 2 同款:封禁时 [SourceHealthState.timeoutStreak] 一并归零,
 *       解封后要**重新**连续超时才会再触发;</li>
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

    /** 规则 2 门槛:连续超时多少次即封 */
    const val TIMEOUT_STREAK_BAN = 2

    /** 规则 2 封禁时长(固定,**不升级**) */
    const val TIMEOUT_BAN_MS = 1L * 60 * 60 * 1000

    /**
     * 「降权」的有效期:最近一次搜索超时距今在这个窗口内,该源就被排到队尾。
     *
     * <p>为什么不是"永久降权":超时可能只是一次网络抖动。降权不屏蔽,所以误伤代价小,
     * 但也不能让一个源因为昨天下午的一次超时,今天一整天都排最后。
     * 30 分钟覆盖"同一次使用会话",跨会话自然失效。
     */
    const val DEFER_MS = 30L * 60 * 1000

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

    /**
     * 是否满足封禁门槛。
     *
     * <p>⚠️ 2026-10-10:**[SourceFailKind.PLAY_FAILED] 不再参与判据**。真机实测它误伤面太大:
     * "利未记"在健康源「天堂」上撞到一条 Ksvideo 伪地址线路被记 PLAY_FAILED,凑上另外两部片的
     * 播放失败就把**整个源**封了 6 小时 —— 而源本身完全正常(同日还在正常出片)。单条线路的
     * 坏地址是线路级问题,由线路优选/负冷却处置;源级封禁只认"源在搜索环节就没法用"的证据
     * (SEARCH_TIMEOUT / SEARCH_FAILED)。旧台账里残留的 PLAY_FAILED 证据因此一并失效 ——
     * 这正是目的:它们被证明是误伤。 */
    fun shouldBan(fails: List<SourceFailEvent>, now: Long): Boolean {
        val counted = prune(fails, now).filter { it.kind != SourceFailKind.PLAY_FAILED }
        return counted.size >= MIN_FAILS && distinctContents(counted) >= MIN_DISTINCT_CONTENTS
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
        // 连续超时计数必须与保留下来的证据保持一致:窗口内已无任何超时事件(被剪掉了),
        // 计数就得归零 —— 否则会留下一个"上个纪元"的计数,让之后**单次**超时直接触发封禁。
        // 解封时同样归零(封禁清空证据,见 [ban])。
        val streak = when {
            expired -> 0
            window.any { it.kind == SourceFailKind.SEARCH_TIMEOUT } -> state.timeoutStreak
            else -> 0
        }
        if (!expired && window.size == state.fails.size && streak == state.timeoutStreak) return state
        return state.copy(
            fails = if (expired) emptyList() else window,
            bannedUntil = if (expired) 0L else state.bannedUntil,
            timeoutStreak = streak,
        )
    }

    /**
     * 记一次失败。命中任一条规则则立即封禁。
     *
     * <p>## 连续超时(规则 2)的加减口径
     * <ul>
     *   <li>[SourceFailKind.SEARCH_TIMEOUT] ⇒ 计数 **+1**;</li>
     *   <li>[SourceFailKind.SEARCH_FAILED] / [SourceFailKind.PLAY_FAILED] ⇒ 计数**不变**:
     *       它们既不是"源答话了"(没资格把连续超时洗白),也不是超时(不凑这个数)。
     *       这类"源在但不可用"的证据由规则 1 负责;</li>
     *   <li>**源给出答案**(Done / Empty)⇒ 计数归零,走 [recordAnswered]。这是唯一的重置入口。</li>
     * </ul>
     *
     * <p>## [suppressTimeoutBan](2026-10-10 雪崩保险丝)
     * 置真时(判定为网络抖动期,见 [SourceHealthMemory]):超时事件**只追加进 [SourceHealthState.fails]**
     * (供 30 分钟降权排序用),**不加** [SourceHealthState.timeoutStreak]、**不触发**规则 2 封禁 ——
     * 一次弱网能让上百个源同时"连续超时 2 次",不抑制就会成批封掉 1 小时,弱网恢复后反而无源可搜。
     * 规则 1 语义不受影响(其判据已不含 PLAY_FAILED,而 SEARCH_FAILED 本就不加 streak)。
     *
     * @param contentKey 归一化片名,见 [contentKey];为空表示"这次失败取不到片名",
     *                   它仍计入次数,但不计入"不同影片数"
     */
    fun recordFail(
        state: SourceHealthState,
        kind: SourceFailKind,
        contentKey: String,
        now: Long,
        suppressTimeoutBan: Boolean = false,
    ): SourceHealthState {
        if (state.manualLocked) return state
        val base = normalize(state, now)
        val window = base.fails + SourceFailEvent(kind, now, contentKey)
        // 规则 1 先判:它的封禁时长(≥6h)严格长于规则 2(1h),先判后判最终态一样;
        // 但只有先判,才不会被"规则 2 已封"短路掉升级档位。
        if (shouldBan(window, now)) return ban(base, now)
        val streak = if (kind == SourceFailKind.SEARCH_TIMEOUT && !suppressTimeoutBan) {
            base.timeoutStreak + 1
        } else {
            base.timeoutStreak
        }
        if (streak >= TIMEOUT_STREAK_BAN) {
            // 规则 2:固定 1 小时,**不动 banCount**(见类注释:最弱的证据不给最重的惩罚)。
            // fails 保留 —— 那是规则 1 的证据,不归这条规则处置。
            // ⚠️ 取 max:绝不能用 1 小时去**缩短**一个更强的在效封禁(例如规则 1 刚判的 6 小时)。
            // 正常路径下已被封的源会被过滤掉、不会再产生超时,但这条不该依赖那个前提。
            return base.copy(
                fails = window,
                timeoutStreak = 0,
                bannedUntil = maxOf(base.bannedUntil, now + TIMEOUT_BAN_MS),
            )
        }
        return base.copy(fails = window, timeoutStreak = streak)
    }

    /**
     * 源**给出了答案**(搜索命中或无命中都算)⇒ 连续超时计数归零。
     *
     * <p>为什么这必须是独立入口:重置的触发点是"成功",而成功不会走 [recordFail]。
     * 把它塞进 [recordFail] 只会让口径变浑 —— "累计失败"和"清零"是两个方向的事。
     *
     * @return 无变化时返回原对象,调用方可据此跳过落盘
     */
    fun recordAnswered(state: SourceHealthState): SourceHealthState =
        if (state.timeoutStreak == 0) state else state.copy(timeoutStreak = 0)

    /**
     * 本轮的**降权**判据(规则 A):最近 [DEFER_MS] 内有搜索超时 ⇒ 排序时降到队尾。
     *
     * <p>与"屏蔽"是两件事,别混:
     * <ul>
     *   <li>屏蔽 = 根本不搜(规则 1/2 的产物);</li>
     *   <li>降权 = **照搜,只是不占首轮的并发额度**。所以没有误伤风险,也不需要 fail-open。</li>
     * </ul>
     * 已屏蔽的源返回 false —— 它走过滤那条路,不该同时被当成"降权"(否则日志与 UI 口径会打架)。
     */
    fun shouldDefer(state: SourceHealthState, now: Long): Boolean {
        if (isBlocked(state, now)) return false
        val lastTimeout = state.fails.asSequence()
            .filter { it.kind == SourceFailKind.SEARCH_TIMEOUT }
            .maxOfOrNull { it.at } ?: return false
        return now - lastTimeout < DEFER_MS
    }

    /** 按升级档位封禁:[SourceHealthState.banCount] 先自增,第 3 次起转永久 */
    fun ban(state: SourceHealthState, now: Long): SourceHealthState {
        val nextCount = state.banCount + 1
        // 封禁即清空连续超时计数:解封后必须**重新**连续超时才会再触发(否则"自动解封"形同虚设)
        return when (nextCount) {
            1 -> state.copy(banCount = 1, fails = emptyList(), timeoutStreak = 0, bannedUntil = now + FIRST_BAN_MS, manualLocked = false)
            2 -> state.copy(banCount = 2, fails = emptyList(), timeoutStreak = 0, bannedUntil = now + SECOND_BAN_MS, manualLocked = false)
            else -> state.copy(banCount = nextCount, fails = emptyList(), timeoutStreak = 0, bannedUntil = 0L, manualLocked = true)
        }
    }

    /**
     * 手动解除(设置页「全部解除」)。
     *
     * <p>清证据、清封禁态、清连续超时计数,**保留 [SourceHealthState.banCount]** —— 已经封过三次的源再犯仍应进永久档,
     * 否则"手动解除"就成了绕过升级逻辑的后门。
     */
    fun unblock(state: SourceHealthState): SourceHealthState =
        state.copy(fails = emptyList(), bannedUntil = 0L, manualLocked = false, timeoutStreak = 0)
}
