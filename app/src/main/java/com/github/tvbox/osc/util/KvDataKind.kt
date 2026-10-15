package com.github.tvbox.osc.util

/** KV 里一份数据"丢了能不能自己长回来"。清除缓存只许动 [REBUILDABLE]。 */
enum class DataKind {
    /** 可再生：下次用到自然长回来（代价只是慢一次） */
    REBUILDABLE,

    /** 资产：用户"越用越准"的全部来源，丢了长不回来 */
    ASSET,

    /** 用户数据：订阅、登录态、手动选择 */
    USER,
}

/**
 * KV 数据类别登记表 —— 「清除缓存」的唯一依据。
 *
 * <p>为什么要有这张表：清除缓存此前是**手写调用**（这里 clear() 一下、那里删几个前缀一下），
 * 于是"该不该清"只存在于注释和记忆里。源长期权重的注释甚至写着"设置页清缓存用"
 * 而实际没人调用 —— 也就是说这些资产是靠"没人顺手加一行"保住的，不是靠设计。
 *
 * <p>**没登记的一律按 [DataKind.USER] 处理**：清除缓存是白名单删除。新加一份台账忘了登记，
 * 后果是"它不会被清"，而不是"用户攒的东西被清掉" —— 两种错里只有一种是可恢复的。
 */
object KvDataKind {

    /** 前缀 → 类别（顺序即匹配优先级，先具体后宽泛） */
    private val kinds: List<Pair<String, DataKind>> = listOf(
        // —— 可再生 ——
        "search_cache_" to DataKind.REBUILDABLE,      // 搜索命中缓存：下次搜同一词自然再写
        "jsRuntime_" to DataKind.REBUILDABLE,         // JS 运行时编译产物：加载该源时重编译
        "title_index_" to DataKind.REBUILDABLE,       // 拼音索引：搜索/历史/收藏过程中重新积累
        "line_health_" to DataKind.REBUILDABLE,       // 线路健康：可疑态 15 分钟就过期，本就是短周期

        // —— 资产（清除缓存永不碰）——
        "source_weight_" to DataKind.ASSET,           // 源短期分 + 长期应答率 + 上次被搜时刻
        "source_health" to DataKind.ASSET,            // 源封禁台账（含 source_health_nolist_ 无剧集列表源）
        "video_quality_memory" to DataKind.ASSET,     // 线路实测分辨率/码率
        "video_availability_memory" to DataKind.ASSET, // "这部片这个源没资源"的标记
        "completeness_sets" to DataKind.ASSET,        // 剧集完整度
        "progress_index_" to DataKind.ASSET,          // 续播进度索引
        "track_mem_" to DataKind.ASSET,               // 音轨/视轨/字幕选择（用户逐片挑过的决定）
        "bucket_seen" to DataKind.ASSET,              // 订阅桶的"最后被碰到"时钟；丢了最坏是少回收一轮

        // —— 用户数据 ——
        "cache_" to DataKind.USER,                    // spider 业务数据：不少源在里面存登录态/token
    )

    @JvmStatic
    fun kindOf(key: String): DataKind =
        kinds.firstOrNull { key.startsWith(it.first) }?.second ?: DataKind.USER

    /** 「清除缓存」允许删的前缀（白名单）。 */
    @JvmStatic
    fun rebuildablePrefixes(): List<String> =
        kinds.filter { it.second == DataKind.REBUILDABLE }.map { it.first }
}
