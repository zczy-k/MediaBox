package com.github.tvbox.osc.player

/**
 * 线路有效性判定的**纯函数规则**(2026-10-12;无 Android 依赖,可 JVM 单测)。
 *
 * <p>## 补的是哪个空白
 * 既有机制里,**线路级健康没有任何跨会话记忆**:
 * `PlaybackAttemptState.triedLineFlags` 是单次播放会话内的 HashSet,
 * 于是"第 3 集在「线路四」卡死"这个经验在换集/重开/下次播放时**全部作废** ——
 * 用户每次都在同一条坏线路上重来一遍。
 *
 * <p>## 四条硬约束(每条都有真机教训,写死不要改)
 * <ol>
 *   <li>**只降权,永不屏蔽**:线路列表由源给出,一部片可能只有 3 条线。
 *       屏蔽掉 1 条而剩下 2 条都坏 = 用户"无路可走"。所以最坏只排到队尾。</li>
 *   <li>**绑定片 id**:线路地址是**源-specific** 的(`flag` 在不同源里指向不同 CDN),
 *       跨片/跨源复用线路经验是错的(`按片绑定`由用户 2026-10-12 确认)。</li>
 *   <li>**网络抖动期暂停累计**(调用方负责判定):一次弱网会把几百条好线路同时
 *       打成"可疑",而没有这道保险丝的话弱网恢复后所有线路都要从队尾爬起。</li>
 *   <li>**播放证据永不参与源级封禁**:见 [com.github.tvbox.osc.util.SourceFailKind.PLAY_FAILED]
 *       的 KDoc —— "单条线路坏地址误封整个健康源 6 小时"是真机实测的坑。</li>
 * </ol>
 *
 * <p>## 设计哲学(与 SourceCompletenessPolicy / SearchDedupPolicy 一致)
 * **宁漏不误杀、拿不准不降权**:语义不足一律返回中性,判定只在"证据确凿"时发生。
 */
object LineHealthPolicy {

    /**
     * 连续失败多少次算"可疑"。
     *
     * <p>取 2 而不是 1:单次失败可能是账号限流、一次性 403、偶发超时 ——
     * 那些都不是"这条线坏了"的证据。连续 2 次同一部片的同一条线失败,
     * 才值得把它排到后面。
     */
    const val SUSPECT_STREAK = 2

    /**
     * 可疑态的**有效期**。
     *
     * <p>取 15 分钟:与既有 `probeNegativeAt` 的 10 分钟冷却同量级 ——
     * 目标是"这一部片、这十来分钟内的后续集"不受坏线拖累,而不是跨天惩罚。
     * 太短(=没记忆)和太长(坏线已恢复还压着)都不好。
     */
    const val SUSPECT_TTL_MS = 15L * 60 * 1000

    /**
     * "慢线路"阈值(毫秒)。
     *
     * <p>与源侧搜索的 `SourceHealthPolicy.SLOW_ANSWER_MS`(5s)同口径:
     * >5s 才答话的线路在换线时会明显拖慢起播,值得排后;但**仍不屏蔽**。
     */
    const val SLOW_LINE_MS = 5_000L

    // —— "上次播通"的加分档位（2026-10-15）——
    // 为什么要有：换线时最硬的证据就是"这条线刚刚在这部片上真播出过画面"。
    // 为什么分三档、且 30 天后归零：越近越可信；隔了一个月的"能播"参考价值已经不大
    // （线路可能早换了一批），不能让它永久压住新线路。
    /** 24 小时内播通过 */
    const val PLAYED_FRESH_MS = 24L * 60 * 60 * 1000
    /** 7 天内播通过 */
    const val PLAYED_WEEK_MS = 7L * 24 * 60 * 60 * 1000
    /** 超过 30 天不再加分 */
    const val PLAYED_STALE_MS = 30L * 24 * 60 * 60 * 1000

    const val SCORE_PLAYED_FRESH = 60
    const val SCORE_PLAYED_WEEK = 40
    const val SCORE_PLAYED_STALE = 20

    // —— 首帧耗时与"该给这条线多少等待"（2026-10-15 自适应）——
    // 真机分段实测把主耗时定位在 **地址交给内核之后**（player 段 4.1~10.4 秒），
    // 而不是取流那段（spider 段均值 1.5 秒，全部落在 6~9 秒窗口内）。
    // 所以等待预算按**这条线路自己的历史首帧**给，而不是按线路标签猜谁"看起来是高分辨率"。
    // 样本不足就用现在的固定值 ⇒ 新线路永远不会比改动前更宽容，也就不会因为"没数据"而多等。
    /** 没有足够历史时的默认预算(ms) —— 就是改动前的 7s + 2×5s,行为不变 */
    const val DEFAULT_START_BUDGET_MS = 17_000L

    /** 预算下界:再快也不低于原来那把 7 秒秒表 */
    const val MIN_START_BUDGET_MS = 7_000L

    /** 预算上界:再慢也不许无限等(总要有换线的时候) */
    const val MAX_START_BUDGET_MS = 25_000L

    /** 平滑样本数封顶:再老的线路也只记最近这么几次,换 CDN 后能转回来 */
    private const val EMA_SAMPLE_CAP = 8

    /** 低于这个样本数就按"没历史"处理:一两次观测说明不了这条线的常态 */
    const val MIN_BUDGET_SAMPLES = 2

    /** 超过这个值判为异常样本(净化卡住、系统抖动),不污染平滑值 */
    const val MAX_FIRST_FRAME_MS = 60_000L

    /** 这条记忆多久没更新就不作数(ms):半年前能播多快,代表不了今天 */
    const val BUDGET_STALE_MS = 30L * 24 * 60 * 60 * 1000

    /** 单条线路的健康状态(可序列化) */
    data class State(
        /** 连续失败次数;成功起播即归零 */
        val failStreak: Int = 0,
        /** 最近一次失败时刻(ms);0 = 没失败过 */
        val failAt: Long = 0L,
        /** 最近一次取流耗时(ms);0 = 未知 */
        val resolveMs: Long = 0L,
        /** 最近一次成功起播时刻(ms);0 = 没成功过 */
        val playedAt: Long = 0L,
        /** 首帧耗时的平滑值(ms);0 = 没有样本 */
        val firstFrameEmaMs: Long = 0L,
        /** 参与平滑的样本数(封顶,保证它对最近几次仍然敏感) */
        val firstFrameSamples: Int = 0,
        /** 最后一次首帧样本的时刻(ms);用于判"这本历史还作不作数" */
        val firstFrameAt: Long = 0L,
    )

    /**
     * 记一次失败。
     *
     * @param suppressCount 弱网抖动期时为 true:证据仍记录(`failAt` 仍更新,
     *                      供"最近失败过"参与排序),但**不累计 streak**
     *                      —— 否则一次弱网会把几百条好线路同时推过 [SUSPECT_STREAK]。
     */
    fun recordFail(state: State, now: Long, suppressCount: Boolean = false): State {
        val streak = if (suppressCount) state.failStreak else state.failStreak + 1
        return state.copy(failStreak = streak, failAt = now)
    }

    /** 记一次成功起播:连续失败归零(这是唯一的重置入口,与源侧 `recordAnswered` 同构) */
    fun recordPlayed(state: State, now: Long): State =
        if (state.failStreak == 0 && state.playedAt == now) state
        else state.copy(failStreak = 0, playedAt = now)

    /** 记一次取流耗时;<=0 视为未知,不覆盖已有值 */
    fun recordResolveMs(state: State, resolveMs: Long): State =
        if (resolveMs <= 0) state else state.copy(resolveMs = resolveMs)

    /**
     * 记一次首帧耗时(地址交给内核 → 画面真的出来)。
     *
     * <p>异常样本直接丢:净化卡住之类的偶发慢会把平滑值抬到天花板,反而让以后每一次起播都多等,
     * 那是"为一次事故给全部播放加罚"。平滑取 3/4 旧 + 1/4 新,样本数封顶 [EMA_SAMPLE_CAP],
     * 保证它对最近几次仍然敏感(不然一条线换了 CDN 以后要几十次才转得过来)。
     */
    fun recordFirstFrame(state: State, ms: Long, now: Long): State {
        if (ms <= 0L || ms > MAX_FIRST_FRAME_MS) return state
        val blended = if (state.firstFrameSamples == 0 || state.firstFrameEmaMs <= 0L)
            ms
        else
            (state.firstFrameEmaMs * 3L + ms) / 4L
        return state.copy(
            firstFrameEmaMs = blended,
            firstFrameSamples = (state.firstFrameSamples + 1).coerceAtMost(EMA_SAMPLE_CAP),
            firstFrameAt = now,
        )
    }

    /**
     * 这条线路的**等起播预算**(首查 7 秒之外,还允许再宽限多久)。
     *
     * <p>规则按"没有数据时不许比改动前更宽容"来定:样本不足、或者历史已经过期 ⇒
     * 返回 [DEFAULT_START_BUDGET_MS](等于改动前的 7s + 2×5s),既不新增等待也不减少;
     * 有历史 ⇒ 取 1.5 倍平滑值再加 2 秒余量,并夹在 [[MIN_START_BUDGET_MS], [MAX_START_BUDGET_MS]] 里
     * —— 下界保证快线仍快,上界保证"再慢也要有个头",不做无限等待。
     *
     * @return 一次起播尝试的总预算(ms),从把地址交给播放器那一刻算起
     */
    fun startBudgetMs(state: State, now: Long): Long {
        if (state.firstFrameSamples < MIN_BUDGET_SAMPLES) return DEFAULT_START_BUDGET_MS
        if (state.firstFrameEmaMs <= 0L) return DEFAULT_START_BUDGET_MS
        if (now - state.firstFrameAt > BUDGET_STALE_MS) return DEFAULT_START_BUDGET_MS
        val wanted = state.firstFrameEmaMs + state.firstFrameEmaMs / 2L + 2_000L
        return wanted.coerceIn(MIN_START_BUDGET_MS, MAX_START_BUDGET_MS)
    }

    /**
     * 这条线路当前是否"可疑"(近期连续失败、且没成功过)。
     *
     * <p>两道时间闸都要过:
     * <ul>
     *   <li>失败在 [SUSPECT_TTL_MS] 之外 ⇒ 已过期,恢复中性;</li>
     *   <li>[State.playedAt] 晚于 [State.failAt] ⇒ 失败后又成功过,说明是偶发,不复疑。</li>
     * </ul>
     */
    fun isSuspect(state: State, now: Long): Boolean {
        if (state.failStreak < SUSPECT_STREAK) return false
        if (state.failAt <= 0) return false
        if (now - state.failAt > SUSPECT_TTL_MS) return false
        if (state.playedAt > state.failAt) return false
        return true
    }

    /** 这条线路是否"慢"(取流耗时超过 [SLOW_LINE_MS]) */
    fun isSlow(state: State): Boolean = state.resolveMs >= SLOW_LINE_MS

    /**
     * 换线时的排序分(**越大越先试**)。
     *
     * <p>两半：**坏证据扣分**（[isSuspect]/[isSlow]）+ **上次真播通的加分**（[playedBonus]）。
     * 后者是 2026-10-15 补的 —— `playedAt` 一直在记（每次成功起播都写），但打分时
     * `else -> 0` 完全不用它，于是"上周就是这条线播通的"这份确凿证据换不来一个身位，
     * 重看同一部片时上次那条能播的线路和从没试过的线路排在一起。
     *
     * <p>⚠️ 不是屏蔽,是**排后**:`PlaybackRetryDelegate.tryNextLine` 仍会把
     * 所有线路(含被压分的)逐个试完才会 exhausted。
     */
    fun orderScore(state: State, now: Long): Int =
        when {
            isSuspect(state, now) && isSlow(state) -> -200
            isSuspect(state, now) -> -100
            isSlow(state) -> -50
            else -> 0
        } + playedBonus(state, now)

    /**
     * "上次真正播通过"的加分：越近越可信，超过 [PLAYED_STALE_MS] 就不算了。
     *
     * <p>刚失败过（可疑）的线路**一分不给**：坏证据优先于好证据 —— 上周能播、
     * 刚才连挂两次，该排后面，而不是靠旧功抵。
     */
    fun playedBonus(state: State, now: Long): Int {
        if (state.playedAt <= 0L || isSuspect(state, now)) return 0
        val age = now - state.playedAt
        if (age < 0L || age > PLAYED_STALE_MS) return 0
        return when {
            age <= PLAYED_FRESH_MS -> SCORE_PLAYED_FRESH
            age <= PLAYED_WEEK_MS -> SCORE_PLAYED_WEEK
            else -> SCORE_PLAYED_STALE
        }
    }

    /**
     * 按健康分重排候选:**稳定排序 + 永不剔除**(2026-10-12 的硬约束落实到实现)。
     *
     * <p>两点都必须成立,少一个就违反设计:
     * <ul>
     *   <li>**稳定**:同分保持原序。候选列表本身已按站点展示顺序排好,
     *       没有确凿证据就不该改动它 —— 否则"明明没坏却换了顺序";</li>
     *   <li>**永不剔除**:返回列表与 [items] 等长。坏线路只是排到队尾,
     *       <b>仍然会被试到</b>(`tryNextLine` 本来就要把全部线路试完才 exhausted)。
     *       屏蔽掉一条可能让"只剩两条且都坏"的场景变成无路可走。</li>
     * </ul>
     *
     * @param keyAt 按**下标**取该条候选的健康键。刻意用下标而不是 `(T) -> String`:
     *              调用方持有的是"候选列表 + 等长键列表"两个平行列表,而 **flag 可能重复**
     *              (同一源站两次上报同名线路),用 `indexOf(item)` 反查会永远命中第一条,
     *              把第二条的分数错记到第一条上。下标不会串位。
     * @param scoreOf 取某个健康键的分数;缺失按 0(中性)处理
     */
    fun <T> reorderByScore(items: List<T>, keyAt: (Int) -> String, scoreOf: (String) -> Int): List<T> {
        if (items.size < 2) return items
        // 全零 ⇒ 一条可用的健康证据都没有,原样返回(省一次排序)
        if (items.indices.none { scoreOf(keyAt(it)) != 0 }) return items
        return items.indices
            .sortedWith(
                // 主键:分数降序(健康分高的先试)
                compareByDescending<Int> { scoreOf(keyAt(it)) }
                    // 次级键:原下标 —— 保证同分稳定(稳定排序)
                    .thenBy { it },
            )
            .map { items[it] }
    }
}
