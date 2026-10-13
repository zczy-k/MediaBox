package com.github.tvbox.osc.util

import com.github.tvbox.osc.player.LineHealthPolicy

/**
 * 误拉黑防护与**四层恢复**的纯判据(2026-10-13,P0-3)—— 《源与线路健康度及智能调度系统》
 * §3「误拉黑防护与恢复」的落地。
 *
 * <p>## 为什么必须做恢复
 * 项目现有封禁是"**单向门**":要么到点自然过期,要么用户手动解除。中间的三种"其实已经好了"
 * 的恢复全部缺失,最危险的是**被封的源无法自证**——
 * 被封后 [SourceHealthFilter] 会把它移出搜索池,于是它**再也没机会答话**,只能等满整段封禁。
 * 一次弱网抖动(雪崩保险丝触发前的少量源)就可能让一批好源白白停用 30 分钟。
 *
 * <p>## 四层恢复(本项目落地的分层)
 * <ol>
 *   <li>**L1 网络平反**(本判据 [isNetworkOnlyBan] + [SourceHealthMemory.onNetworkRestored]):
 *       **纯网络类证据**支撑的封禁,在网络恢复时立即平反。最高价值 —— 直接命中"弱网抖动误封"。</li>
 *   <li>**L2 探活使能**(本判据 [probeCandidates]):挑出"值得主动探活"的封禁源,
 *       由调用方经既有迷你探测通道验证;成功即平反。**本判据只选谁,不发起网络**。</li>
 *   <li>**L3 手动重置**([SourceHealthMemory.unblockAll],已实现);</li>
 *   <li>**L4 自然过期**([SourceHealthPolicy.normalize],已实现)。</li>
 * </ol>
 *
 * <p>## 铁律
 * **只对"纯网络类证据"平反**:一条封禁若还有任何**内容相关**(非超时)的证据支撑,
 * 就不是"网络恢复"能解释的 —— 强行平反等于把真实坏源放回来。宁可漏平反(等 L4 自然过期),
 * 不可误平反(把坏源放回搜索池,用户再踩一次坑)。
 *
 * <p>纯逻辑,无 IO / Android 依赖 —— 由 `RecoveryPolicyTest` 锁行为。
 */
object RecoveryPolicy {

    /**
     * 这条封禁是否**仅由网络类证据支撑**(⇒ 网络恢复即可安全平反)。
     *
     * <p>"网络类证据" = 搜索超时([SourceFailKind.SEARCH_TIMEOUT])。
     * 理由:超时是**内容无关**的最弱证据(见 [SourceHealthPolicy] 类注释),
     * 一次弱网就能造成一串;而 [SourceFailKind.SEARCH_FAILED](HTTP/解析异常)虽然也可能因网络,
     * 但**无法区分**"网络坏"与"源坏"⇒ 归为**不可平反**那一侧(宁漏不误杀)。
     *
     * <p>判定条件(全部满足):
     * <ol>
     *   <li>当前**确实处于封禁态**(`bannedUntil > now`);</li>
     *   <li>**非永久封禁**(`manualLocked` 为真时只能 L3 手动解除,**任何自动层都不动它**);</li>
     *   <li>证据里**没有任何非超时事件**(有则说明源自身也有问题,不平反)。</li>
     * </ol>
     *
     * <p>⚠️ 注意不必要求 `timeoutStreak > 0`:封禁落地时 streak 已被清零(见 `ban`/规则 2),
     * 真正的证据在 `fails` 里。只看 `fails` 才是可靠的。
     */
    fun isNetworkOnlyBan(state: SourceHealthState, now: Long): Boolean {
        if (state.manualLocked) return false          // 永久封禁:只有用户能解
        if (state.bannedUntil <= now) return false     // 当前未封:无需平反
        // 证据全为超时 ⇒ 纯网络类。空证据也视为可平反(例如规则 2 封禁时证据已被清)
        return state.fails.all { it.kind == SourceFailKind.SEARCH_TIMEOUT }
    }

    /**
     * L1 平反后的新状态:清封禁态与证据,但**保留 [SourceHealthState.banCount]**。
     *
     * <p>为什么保留 banCount:与 [SourceHealthPolicy.unblock] 同款 —— 平反的是"这一次误封",
     * 不是"抹掉这个源的历史"。若平反也清 banCount,"误封—平反"循环会让一个反复抖动的源
     * 永远停在首封档(6h),而不是升到 24h;虽然平反场景下它未必是真坏源,但**不改升级语义**
     * 是更安全的默认(与既有 `unblock` 一致,避免两处语义打架)。
     *
     * <p>与 [SourceHealthPolicy.unblock] 的差异:后者清 `manualLocked`,本函数**只用于非永久封禁**
     * (调用方已按 [isNetworkOnlyBan] 过滤),故不改 `manualLocked`(它本就为 false)。
     */
    fun liftNetworkBan(state: SourceHealthState): SourceHealthState =
        state.copy(fails = emptyList(), bannedUntil = 0L, timeoutStreak = 0)

    /**
     * L2 探活使能:挑出"值得主动探活"的封禁源,交由调用方经既有探测通道验证。
     *
     * <p>与 L1 的分工:
     * <ul>
     *   <li>L1 处理"**纯网络类**封禁"—— 网络一恢复就平反,不用探;</li>
     *   <li>L2 处理"**含源相关证据**的封禁"—— 不能凭"网络恢复了"就放行,得**实测**它到底活没活。
     *       探活成功才平反,这是"给被封的源一次自证机会"的落地(呼应文档"拉黑后源无法自证"的痛点)。</li>
     * </ul>
     *
     * <p>⚠️ 本判据**只选谁、不发起网络**;真正的探测由调用方注入(复用
     * `MiniProbeGate`/`MiniProbeBudget` 的预算闸,避免又开出第二条探测路径)。
     *
     * <p>选取口径:
     * <ol>
     *   <li>已封禁且**非永久**、且**含源类证据**([isNetworkOnlyBan] 为假的那批 ——
     *       纯网络类的由 L1 直接平反,不必探);</li>
     *   <li>**剩余封禁时长足够短**([remainingMs] ≤ [maxRemainingMs],默认
     *       [DEFAULT_MAX_REMAINING_MS])—— 探活是**重代价**(网络请求),只为"快到期、且可能误封"
     *       的源做;离到期还很远的源,探活了收益也远,不如等 L4 自然过期;</li>
     *   <li>按 `banCount` 升序(轻封优先:6h 档比永久档更可能是误封)、再按剩余时长升序
     *       (快到期优先),最多 [limit] 个。</li>
     * </ol>
     *
     * @param states          (源 key → 状态)快照
     * @param now             当前时刻(ms)
     * @param maxRemainingMs  剩余封禁 ≤ 此值才探活(默认 [DEFAULT_MAX_REMAINING_MS])
     * @param limit           本次最多探活几个(默认 [DEFAULT_PROBE_LIMIT])
     */
    fun probeCandidates(
        states: Map<String, SourceHealthState>,
        now: Long,
        maxRemainingMs: Long = DEFAULT_MAX_REMAINING_MS,
        limit: Int = DEFAULT_PROBE_LIMIT,
    ): List<String> {
        if (states.isEmpty() || limit <= 0) return emptyList()
        return states.asSequence()
            .filter { (_, st) ->
                st.bannedUntil > now &&
                    // 永久封禁只能 L3 手动解,不探
                    !st.manualLocked &&
                    // 纯网络类的由 L1 平反,不必探
                    !isNetworkOnlyBan(st, now) &&
                    // 快到期才值得探(重代价动作)
                    (st.bannedUntil - now) <= maxRemainingMs
            }
            .sortedWith(
                compareBy<Map.Entry<String, SourceHealthState>> { it.value.banCount }
                    .thenBy { it.value.bannedUntil },
            )
            .map { it.key }
            .take(limit)
            .toList()
    }

    /**
     * L2 探活的最大剩余封禁窗口:剩余 ≤ 它才探。
     *
     * <p>取 20 分钟:与规则 2 的封禁时长(30 分钟)同量级 —— 规则 2 的源在"还剩不到 2/3
     * 时长"时进入探活候选,探活了就能提前放回搜索池;而规则 1(6h/24h)的源离到期太远,
     * 探活收益低,更多交给 L4 自然过期 + 用户 L3 手动解除。
     */
    const val DEFAULT_MAX_REMAINING_MS = 20L * 60 * 1000

    /** 单次探活上限:探活要走网络,一批太多会拖慢;3 个与既有补探测预算同量级 */
    const val DEFAULT_PROBE_LIMIT = 3

    /**
     * 线路级是否值得 L1 平反(网络恢复)。
     *
     * <p>与源级 [isNetworkOnlyBan] 同口径,但线路级"可疑"是 TTL 制([LineHealthPolicy.isSuspect])、
     * 无封禁态,所以判据更简单:可疑**仅由弱网期的失败**造成 ⇒ 网络恢复即视为可疑解除。
     *
     * <p>⚠️ 线路级本就"只降权不屏蔽",恢复的紧迫性远低于源级;这里提供判据主要供
     * 调用方在"网络恢复"时**顺手清掉弱网期可疑**,避免恢复后一批好线路仍从队尾爬起。
     */
    fun shouldLiftLineSuspect(state: LineHealthPolicy.State, now: Long, weakNetworkDuringFail: Boolean): Boolean =
        weakNetworkDuringFail && LineHealthPolicy.isSuspect(state, now)
}