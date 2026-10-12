package com.github.tvbox.osc.util

import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **本地标题拼音索引**:把"用户敲的拼音"解析成"真实的中文片名"。
 *
 * <p>## 为什么必须有它(而不是只加一条匹配规则)
 * 源站搜索接口收的是**字面关键词**。用户敲 `qjyf` 时,若把它原样发给源站,
 * 结果是必然的 0 命中 —— 源站库里没有任何片名是 `qjyf`。
 * 所以拼音搜索的完整链路是:
 * ```
 *   用户输入 qjyf
 *     → 本地索引里找出拼音匹配的片名《千钧一发》   ← 本类负责这一步
 *     → 拿《千钧一发》去搜源站(走既有全站搜索)     ← SearchViewModel 负责
 * ```
 * 少了第一步,拼音就只是个永远搜不到东西的输入框。
 *
 * <p>## ⚠️ 写入必须异步(2026-10-11 自查抓到的性能缺陷)
 * [record] 的调用点全在**主线程**:`SearchViewModel.onSearchResultEvent` 是
 * `ThreadMode.MAIN`,而且它**每个源回包调用一次** —— 一次全站搜索约 300 次。
 * 而"加载整表(最多 4000 条 JSON)+ ICU 转拼音 + 落盘"是实打实的重活,
 * 若同步执行就是主线程 300 次全表解析,搜索过程必然卡顿。
 *
 * 因此 [record] 只做**入队**(无锁、无 IO、无 ICU),真正的转换与落盘由单个后台线程
 * 批量合并完成。合并还有额外好处:同一部片被多个源返回时只转一次拼音。
 *
 * <p>## 索引从哪来(冷启动问题)
 * 索引**不预置任何数据**(那要带几万字的片名表,与体积目标冲突),而是在使用中积累:
 * <ul>
 *   <li>搜索有命中的结果(标题已经过用户意图验证,质量最高);</li>
 *   <li>观看历史与收藏(用户真实关心过的片名)。</li>
 * </ul>
 * 因此**首次安装后第一次敲拼音可能无结果** —— 这是本方案的固有代价,不是 bug。
 * 但用户在搜过/看过几部片之后,索引就会覆盖他的兴趣面,收益随使用增长。
 *
 * <p>## 容量与淘汰
 * 上限 [MAX_ENTRIES] 条,超出按时间淘汰最旧。每条只存"归一化标题 → 拼音 + 原片名 + 时间戳"。
 *
 * <p>按订阅地址分桶(与 [SearchResultCache] / [SourceHealthMemory] 同款原则):
 * 换仓后旧索引自动作废,避免"搜出来的片名属于另一个源库"。
 */
object TitleIndexMemory {

    /** 最多保留多少条标题 */
    private const val MAX_ENTRIES = 4000

    /** 一次解析最多返回多少个候选片名(每个候选都会变成一次真实搜索,不能放开) */
    const val MAX_RESOLVED = 3

    private const val KEY_PREFIX = "title_index_"

    /** 待写入队列:只放片名,入队是纯内存操作 */
    private val pending = ConcurrentLinkedQueue<String>()

    /** 是否有正在排队的落盘任务(避免重复提交) */
    private val flushScheduled = AtomicBoolean(false)

    /** 单线程执行器:转换 + 落盘都在这里,与主线程解耦 */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "title-index-writer").apply { isDaemon = true }
    }

    private fun bucketKey(): String? {
        val api = try {
            KV.get(HawkConfig.API_URL, "")
        } catch (th: Throwable) {
            ""
        }
        return if (api.isNullOrEmpty()) null else KEY_PREFIX + MD5.string2MD5(api)
    }

    /** 一条索引项:归一化标题(键)、拼音、原片名、写入时刻 */
    private data class Entry(val pinyin: String, val title: String, val at: Long)

    private fun load(bucket: String): MutableMap<String, Entry> {
        val out = HashMap<String, Entry>()
        val raw = try {
            KV.get(bucket, "")
        } catch (th: Throwable) {
            ""
        }
        if (raw.isNullOrEmpty()) return out
        return try {
            val root = JSONObject(raw)
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                val title = o.optString("t")
                val pinyin = o.optString("p")
                if (title.isEmpty() || pinyin.isEmpty()) continue
                out[k] = Entry(pinyin, title, o.optLong("at"))
            }
            out
        } catch (th: Throwable) {
            LOG.i("echo-pinyin index-load-failed: " + th.message)
            HashMap()
        }
    }

    /**
     * 把一批片名**排进**索引(搜索命中 / 历史 / 收藏的调用入口)。
     *
     * <p>本方法必须**极廉价**:调用点在主线程(见类 KDoc),所以它只入队就返回,
     * 转换与落盘由后台线程批量完成。
     *
     * <p>只对**含汉字**的标题建索引(在后台线程判定):纯英文片名不需要拼音,
     * 用户直接敲英文即可命中。
     */
    /**
     * 把一批片名**排进**索引(搜索命中 / 历史 / 收藏的调用入口)。
     *
     * <p>本方法必须**极廉价**:调用点在主线程(见类 KDoc),所以它只入队就返回,
     * 转换与落盘由后台线程批量完成。
     *
     * <p>⚠️ 参数类型刻意是 `Collection<String?>` 而不是 `Collection<String>`:
     * 调用方之一是 **Java**(`RoomDataManger`),它传的是 `Collections.singletonList(vodInfo.name)`,
     * 而 `vodInfo.name` 完全可能是 null。Kotlin 的 `Collection<String>` 在字节码里没有运行时非空检查,
     * 声明成非空只是**骗自己** —— 真收到 null 时会在 `trim()` 处抛 NPE,
     * 把写历史/写收藏的调用方一起带崩。用可空类型如实表达契约。
     *
     * <p>(`List<String>` 因 `Collection` 的协变声明仍是它的子类型,所以 Kotlin 侧的
     * `mapNotNull { it.name }` 调用点无需改动。)
     *
     * <p>⚠️ 必须 `@JvmStatic`:`RoomDataManger`(Java)以 `TitleIndexMemory.record(...)` 形式调用,
     * 而 Kotlin `object` 的普通方法在字节码里是**实例方法**(需 `INSTANCE.record(...)`)。
     * 本仓既有惯例就是给 Java 侧要用的方法加 `@JvmStatic`(见 DetailNoListMemory)。
     */
    @JvmStatic
    fun record(titles: Collection<String?>) {
        if (titles.isEmpty()) return
        var queued = 0
        for (t in titles) {
            val title = t?.trim().orEmpty()
            if (title.isEmpty()) continue
            pending.add(title)
            queued++
        }
        if (queued == 0) return
        scheduleFlush()
    }

    /** 提交一次后台落盘;已有任务在排队时直接返回(自然形成批合并) */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        try {
            writer.execute {
                try {
                    flushPending()
                } catch (th: Throwable) {
                    LOG.i("echo-pinyin index-flush-failed: " + th.message)
                } finally {
                    flushScheduled.set(false)
                    // 落盘期间可能又有新条目入队 ⇒ 补一次,否则它们要等下次搜索才被处理
                    if (!pending.isEmpty()) scheduleFlush()
                }
            }
        } catch (th: Throwable) {
            // 执行器已关闭(进程收尾):放弃,下次入队会重建失败但无害
            flushScheduled.set(false)
        }
    }

    /** 排空队列并合并落盘(后台线程) */
    private fun flushPending() {
        val bucket = bucketKey() ?: run { pending.clear(); return }
        // 一次取空队列:同一批里重复的片名由下面的 map 去重(只转一次拼音)
        val batch = LinkedHashSet<String>()
        while (true) {
            val t = pending.poll() ?: break
            batch.add(t)
        }
        if (batch.isEmpty()) return
        val map = load(bucket)
        val now = System.currentTimeMillis()
        var added = 0
        for (title in batch) {
            val key = SearchSettings.normalizedTitle(title)
            if (key.isEmpty()) continue
            val old = map[key]
            if (old != null && old.title == title) {
                // 已有同名片:只刷新时间戳(不重跑 ICU)
                map[key] = old.copy(at = now)
                continue
            }
            val pinyin = PinyinTransliterator.toPinyin(title)
            if (pinyin.isEmpty()) continue   // 纯英文/ICU 不可用 ⇒ 不建条目
            map[key] = Entry(pinyin, title, now)
            added++
        }
        if (added == 0) return
        // 容量上限:按时间淘汰最旧
        if (map.size > MAX_ENTRIES) {
            val keep = map.entries.sortedByDescending { it.value.at }.take(MAX_ENTRIES)
            map.clear()
            for (e in keep) map[e.key] = e.value
        }
        persist(bucket, map)
        LOG.i("echo-pinyin index-record added=" + added + " total=" + map.size)
    }

    /**
     * 把拼音查询解析成候选片名,按匹配分降序。
     *
     * <p>⚠️ 这是**读**路径,会在主线程被调用(用户按下搜索)。它只做一次 KV 读 + JSON 解析,
     * 不做 ICU 转换(拼音已在写入时算好),开销与一次 `VideoQualityMemory.lookupAll` 同量级。
     *
     * @return 最多 [MAX_RESOLVED] 个**原片名**(可直接拿去搜源站);无匹配返回空表
     */
    fun resolve(query: String?): List<String> {
        if (!PinyinQuery.looksLikePinyin(query)) return emptyList()
        val bucket = bucketKey() ?: return emptyList()
        return try {
            val map = load(bucket)
            if (map.isEmpty()) return emptyList()
            map.values.asSequence()
                .map { it to PinyinQuery.score(it.pinyin, query) }
                .filter { it.second > 0 }
                // 同分按时间新的优先(近期看过的更可能是想要的),再按标题稳定排序
                .sortedWith(
                    compareByDescending<Pair<Entry, Int>> { it.second }
                        .thenByDescending { it.first.at }
                        .thenBy { it.first.title },
                )
                .map { it.first.title }
                .distinct()
                .take(MAX_RESOLVED)
                .toList()
        } catch (th: Throwable) {
            LOG.i("echo-pinyin index-resolve-failed: " + th.message)
            emptyList()
        }
    }

    private fun persist(bucket: String, map: Map<String, Entry>) {
        val root = JSONObject()
        for ((k, e) in map) {
            root.put(k, JSONObject().put("t", e.title).put("p", e.pinyin).put("at", e.at))
        }
        KV.put(bucket, root.toString())
    }

    /** 清空当前订阅的索引(设置页"清缓存"用) */
    fun clear() {
        pending.clear()
        val bucket = bucketKey() ?: return
        try {
            KV.put(bucket, "")
        } catch (th: Throwable) {
            LOG.i("echo-pinyin index-clear-failed: " + th.message)
        }
    }
}
