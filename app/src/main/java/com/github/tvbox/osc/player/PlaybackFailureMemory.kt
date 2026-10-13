package com.github.tvbox.osc.player

/**
 * 播放失败的**会话级短期记忆**(2026-10-13 真机修复,问题 17a/17c)。
 *
 * <p>判据见 [PlaybackFailurePolicy];本类只管**存取**,不做判断。
 *
 * <p>## 记什么、按什么维度
 * 键 = `内容标题键|源key`,值 = 最近一次失败时刻。按**内容**隔离是硬要求:
 * "千与千寻在源A放不出来"**不能**牵连"另一部片在源A" —— 失败往往是**单条影片在单源**的资源问题
 * (该源没这部片、或该片源的文件坏了),不是源整体坏。这与"线路级失败"口径一致
 * (见《源与线路的权重模型》§3.3)。
 *
 * <p>## 为什么**不能**并按
 * 不落盘、不进健康台账、不影响搜索排序 —— 它只在**当前进程、当前这轮换源**内生效。
 * 落盘会让"上次的瞬时抖动"变成"这次的先验",与项目"证据强度不对称 / 拿不准不猜"的原则冲突。
 *
 * <p>## 为什么用一个单调递增的时刻而不是布尔
 * 布尔无法表达 TTL;[PlaybackFailurePolicy.isFailed] 按"失败时刻 + TTL"实时判定,
 * 过期即自动遗忘,无需后台清理任务。
 */
object PlaybackFailureMemory {

    /** 键 = 内容标题键|源key → 最近失败时刻(ms) */
    private val failedAt = HashMap<String, Long>()

    /** 内容标题键(与 DetailViewModel.searchTitle 同口径):换片时换键,旧记录自然不再命中 */
    @Volatile
    private var contentKey: String = ""

    /**
     * 切换内容键。**仅在内容键真的改变时才清空记忆**。
     *
     * <p>⚠️ 这是本类最容易踩的坑:调用方([DetailViewModel.resetEngineState])在**换片与换源**
     * 两条路径上都会走到 —— 换源时片名**不变**,若这里无条件清空,"跳过本会话失败过的源"
     * 就永远失效(刚记下失败,一换源就被清)。故只有当 key 与当前不同(换片)时才清。
     */
    @JvmStatic
    fun beginContent(key: String?) {
        val k = key?.trim().orEmpty()
        if (k == contentKey) return
        contentKey = k
        failedAt.clear()
    }

    /**
     * 记一次"这个源在这部片上放不出来"。
     *
     * @param sourceKey 源 key
     * @param now       当前时刻(ms);传 0 则用 {@link System#currentTimeMillis()}
     */
    @JvmStatic
    @JvmOverloads
    fun recordFailure(sourceKey: String?, now: Long = 0L) {
        val sk = sourceKey?.trim().orEmpty()
        if (sk.isEmpty() || contentKey.isEmpty()) return
        failedAt[key(sk)] = if (now > 0) now else System.currentTimeMillis()
    }

    /** 该源在这部片上是否仍处于失败态(TTL 内)。 */
    @JvmStatic
    fun hasFailed(sourceKey: String?): Boolean {
        val sk = sourceKey?.trim().orEmpty()
        if (sk.isEmpty() || contentKey.isEmpty()) return false
        val at = failedAt[key(sk)] ?: return false
        return PlaybackFailurePolicy.isFailed(at, System.currentTimeMillis())
    }

    /** 取某源失败时刻(0 = 无/已过期);供 [PlaybackFailurePolicy.allCandidatesFailed] 用。 */
    @JvmStatic
    fun failedAtOf(sourceKey: String): Long {
        if (contentKey.isEmpty()) return 0L
        return failedAt[key(sourceKey)] ?: 0L
    }

    /**
     * 候选源是否**全部**处于本会话失败态(⇒ 换源链无路可走,该进失败终态)。
     *
     * @param candidateKeys 候选源 key 列表
     */
    @JvmStatic
    fun allFailed(candidateKeys: List<String>): Boolean =
        PlaybackFailurePolicy.allCandidatesFailed(candidateKeys, ::failedAtOf, System.currentTimeMillis())

    /** 当前内容键(诊断用)。 */
    @JvmStatic
    fun currentContentKey(): String = contentKey

    private fun key(sourceKey: String) = contentKey + "|" + sourceKey
}