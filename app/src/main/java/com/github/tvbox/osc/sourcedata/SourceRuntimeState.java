package com.github.tvbox.osc.sourcedata;

import com.github.tvbox.osc.bean.AbsSortXml;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点取数的运行期状态持有者:homeContent 缓存、extend 解析缓存。
 *
 * <p>这些状态必须活过页面级 VM 的换实例(换源清理要有唯一出口),原先是门面 {@link SourceViewModel}
 * 的 static 字段 —— 页面 VM 因此"名不副实"地带着 static 可变状态。搬到此处后门面只管通道与入口,
 * 清理入口的唯一性不变(所有读写仍只有这一个归属)。
 */
public final class SourceRuntimeState {

    private SourceRuntimeState() {
    }

    // homeContent 缓存,最多 5 个 sourceKey 的 AbsSortXml
    // access-order 的 LinkedHashMap:连 get 都会改结构,而读写它的是多个池线程 ⇒ 所有访问都在
    // 这把锁(监视器就是 map 本身)下,持锁期间不做 IO,否则链表会在并发下损坏
    static final Map<String, AbsSortXml> sortCache = new LinkedHashMap<String, AbsSortXml>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, AbsSortXml> eldest) {
            return size() > 5;
        }
    };

    /** extend(站点扩展参数)解析结果缓存,键是原始 extend 的 MD5 */
    static final ConcurrentHashMap<String, String> extendCache = new ConcurrentHashMap<>();

    /** 换源/换配置后清掉运行期缓存(分类结构与 extend 都与源绑定) */
    public static void clearRuntimeCache() {
        synchronized (sortCache) {
            sortCache.clear();
        }
        extendCache.clear();
    }

    /**
     * extend 是否**已是热的**(不需要再为它花一次网络往返)。
     *
     * <p>给"迷你只读通道"的准入过滤用(见 `player/MiniProbeGate`):type 4 的 extend 若是冷缓存,
     * 光把它解析出来就要一次请求 + 可能阻塞在 EXTEND_POOL 上 —— 这种源直接不探更划算。
     *
     * <p>口径与 {@code SourceHelper.getFixUrl} 完全对齐:空 / 非 http 的 extend 会被原样返回、
     * 根本不联网,因此都算"热"。
     */
    public static boolean isExtendWarm(String extend) {
        if (extend == null || extend.isEmpty()) return true;
        if (!extend.startsWith("http")) return true;
        return extendCache.containsKey(com.github.tvbox.osc.util.MD5.string2MD5(extend));
    }
}
