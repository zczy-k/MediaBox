package com.github.tvbox.osc.util

import java.io.File

/**
 * 字幕落盘目录的**数量上限 + LRU 冲刷**。
 *
 * <p>为什么需要它:字幕文件名直接取服务端的 `content-disposition` 或 URL 路径
 * (见 [com.github.tvbox.osc.subtitle.SubtitleLoader]),**没有做归一化**,于是
 * "每次点播带远程字幕的剧集 = 内部 cacheDir 下多一个文件",而且名字各不一样、
 * 不会被覆盖。代码里原本**没有任何**删除/计数逻辑 —— 这正是"看过的片越多、App 占用越大"
 * 的直接来源(Exo 视频缓存有 512MB LRU 天花板,但字幕目录连天花板都没有)。
 *
 * <p>为什么删除是安全的:[com.github.tvbox.osc.ui.player.PlayContainer.cachedPlayPath]
 * 读回 Room 里记的路径后会 `File.exists()` 校验,文件不在就返回空串回退到本次的新地址,
 * 不会出现"指向空文件 → 静默无字幕"。所以这里可以放心按 LRU 删。
 *
 * <p>策略:**按最后修改时间**保留最近 [MAX_FILES] 个,超出的从最旧开始删。
 * 不设"过期时间"是因为字幕缓存的真正价值就是"下次直接用",按时间淘汰收益低、误伤高;
 * 数量上限已经能封住占用上界(单文件通常几十 KB ~ 数 MB,[MAX_FILES] 条最坏也在几十 MB 量级)。
 *
 * <p>纯逻辑部分([pickEviction])可 JVM 单测;IO 部分须在后台线程调用。
 */
object SubtitleCacheJanitor {

    /**
     * 保留条数上限。取 200 的理由:一部 40 集的剧配 2 条字幕(国语+外语)也就 80 个文件,
     * 200 够放下最近四五部片;而单文件即便 5MB,200 条也只有 1GB,再叠加上 Exo 的 512MB
     * 才需要用户手动清 —— 而实际上字幕文件多在几十 KB,量级差着几个数量级。
     */
    const val MAX_FILES = 200

    private const val DIR_NAME = "zimu"
    private const val COPY_PREFIX = "subtitle_"

    /**
     * 扫一遍字幕落盘目录并按 LRU 裁剪。**幂等**,可重复调用。
     *
     * @param cacheDir 内部缓存目录(不能用外部缓存:外部缓存会被系统的"清除缓存"直接清掉)
     * @return 被删文件数;目录不存在返回 0
     */
    @JvmStatic
    fun trim(cacheDir: File?): Int {
        if (cacheDir == null) return 0
        var removed = 0
        try {
            removed += trimOne(cacheDir.resolve(DIR_NAME))
            removed += trimCopies(cacheDir)
        } catch (t: Throwable) {
            LOG.d("SubtitleCacheJanitor", "trim failed: " + t.message)
        }
        return removed
    }

    /** /zimu/ 目录:按时间保留最近 [MAX_FILES] 个文件 */
    private fun trimOne(dir: File): Int {
        val files = dir.listFiles { f -> f.isFile } ?: return 0
        if (files.size <= MAX_FILES) return 0
        val ordered = files.sortedBy { it.lastModified() }
        val victims = pickEviction(ordered.map { it.lastModified() }, MAX_FILES)
        var removed = 0
        victims.forEach { idx ->
            if (ordered[idx].delete()) removed++
        }
        if (removed > 0) {
            LOG.i("SubtitleCacheJanitor", "trim zimu: removed " + removed + " of " + files.size)
        }
        return removed
    }

    /**
     * 本地导入字幕的副本(`subtitle_<时间戳>_<名>`,见 PlayContainer 导入流程):
     * 同样按 LRU 保留 [MAX_FILES] 个。文件名带时间戳所以天然按时间有序,
     * 但仍统一走 [pickEviction],免得两条路径的口径分叉。
     */
    private fun trimCopies(cacheDir: File): Int {
        val files = cacheDir.listFiles { f ->
            f.isFile && f.name.startsWith(COPY_PREFIX)
        } ?: return 0
        if (files.size <= MAX_FILES) return 0
        val ordered = files.sortedBy { it.lastModified() }
        val victims = pickEviction(ordered.map { it.lastModified() }, MAX_FILES)
        var removed = 0
        victims.forEach { idx ->
            if (ordered[idx].delete()) removed++
        }
        if (removed > 0) {
            LOG.i("SubtitleCacheJanitor", "trim subtitle copies: removed " + removed + " of " + files.size)
        }
        return removed
    }

    /**
     * 纯逻辑:给一份按升序排好的修改时间,返回**要删掉的下标**(最旧的先删)。
     *
     * @param sortedLastModified 升序排列的最后修改时间
     * @param keep               要保留的条数
     * @return 待删下标(升序);总量不超 [keep] 时返回空表
     *
     * <p>输入已排序,所以答案就是前 `size - keep` 个。单独抽出来是为了能单测 ——
     * IO 那部分在 JVM 上测不了(需要真实 [File]),而"删哪些"才是容易写错的地方。
     */
    @JvmStatic
    fun pickEviction(sortedLastModified: List<Long>, keep: Int): List<Int> {
        val overflow = (sortedLastModified.size - keep).coerceAtLeast(0)
        if (overflow == 0) return emptyList()
        return (0 until overflow).toList()
    }
}