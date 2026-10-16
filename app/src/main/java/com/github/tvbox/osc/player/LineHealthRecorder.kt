package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SourceHealthMemory
import com.github.tvbox.osc.util.SourceWeightMemory
import com.github.tvbox.osc.util.SourceWeightPolicy

/**
 * 线路健康的上报闸门(2026-10-12):把"这次播放失败该不该记到线路上"的判断收在一处。
 *
 * <p>## 为什么需要它(而不是在 4 个失败分支各写一遍)
 * 失败路径有 4 个入口(取流超时 / 取流失败 / 起播后错误 / 线路耗尽换源),
 * 每个都记一次会重复计数;而各自的守卫条件(userPickedLine、是否已起播)又不同。
 * 分散写必然漂移 —— 本仓已经因为"散落的 if"付出过代价(见 `probeNegativeAt`
 * 与 `probeDirectNegativeAt` 为什么要分表的注释)。
 *
 * <p>## 三道闸(缺一道就会误伤)
 * <ol>
 *   <li><b>弱网抖动期不累计</b>:一次弱网会把几百条好线路同时判失败,
 *       没这道闸的话恢复后所有线路都要从队尾爬起。证据仍记录(失败时刻),
 *       只是不累计连续次数 —— 判据见 [recordFailure] 的 `noNetwork` 参数;</li>
 *   <li><b>用户手动选的线路不记</b>(`userPickedLine`):用户明确选了这条,
 *       它的失败是"用户选择的结果",不是"线路本身无效"的证据;</li>
 *   <li><b>同一部片同一会话内不重复记</b>:换线流程里一条线路可能被多个回调
 *       先后报告失败,不去重会让 streak 一次涨好几。</li>
 * </ol>
 *
 * <p>所有闸都是**保守**的:宁可不记,不可错记。代价只是这条线路的降权晚一轮生效,
 * 而错记会让好线路被无故排后。
 */
object LineHealthRecorder {

    /** 本会话已上报过的 `源|片|flag`,防止一条线路被多个回调重复记 */
    private val recordedForSession = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 新一次播放会话(换片/换线/重开)时清空去重集合 */
    fun onNewSession() {
        recordedForSession.clear()
    }

    /**
     * 上报一次线路失败。
     *
     * <p>## 闸 1 的弱网判据(2026-10-13 统一)
     * 此前只看调用方传来的"最后一次播放错误是不是网络类"(`noNetwork`),
     * 而它**取不到"设备此刻整体有没有网"** —— 断网时 EXO 的错误恰好被归到别的类
     * (解码/容器类),这条闸就形同不存在,一次断网照样能把几百条好线路打成可疑。
     *
     * <p>现在两个信号**取或**,任一说"像弱网"就不累计 streak:
     * <ul>
     *   <li>`noNetwork`:调用方判定(最后一次播放错误是网络类);</li>
     *   <li>`SourceHealthMemory.isNetworkAvailable()`:设备此刻确实没有可用网络。</li>
     * </ul>
     * 取或是**保守**方向 —— 宁可少累计 streak(少降权几条好线路),
     * 也不可在弱网期把好线路成批打成可疑。这与源侧"断网时不记"([SourceHealthMemory] 的全局护栏)
     * 是同一个口径,只是源侧更彻底(整条不记),线路侧仍保留失败时刻供"最近失败过"参与排序。
     *
     * @param noNetwork 调用方判定的"这次失败像弱网"(最后一次播放错误为网络类)
     * @param userPickedLine 用户是否手动选了这条线路(手动选择不产生"线路无效"证据)
     * @param lineFaultUnproven **是我们没等到,不是它死了**:换线由"取流超时"或"等起播超时"触发,
     *        而到点时地址根本还没交给内核(慢在站点不答话、本地链路、或我们自己的 m3u8 净化),
     *        又或者缓冲百分比确实在长(宽限预算用尽)。
     *        两种都只有"我没等到"的证据、没有"它坏了"的证据 —— 记成失败会把一条好好的线越压越后,
     *        尤其首帧本来就慢的高码率线路和冷启动要 import 的 py/js 源。
     * @return 是否真的记了(供日志/测试判断闸门是否按预期放行)
     */
    @JvmStatic
    @JvmOverloads
    fun recordFailure(
        sourceKey: String?,
        vodId: String?,
        flag: String?,
        noNetwork: Boolean = false,
        userPickedLine: Boolean = false,
        lineFaultUnproven: Boolean = false,
    ): Boolean {
        if (userPickedLine) return false                    // 闸 2:用户选的,不产生证据
        val key = LineHealthMemory.keyOf(sourceKey, vodId, flag)
        if (key.isEmpty()) return false
        if (!recordedForSession.add(key)) return false       // 闸 3:本会话已记过
        // 闸 1:两个弱网信号取或(见 KDoc)。断网判定取不到上下文时按"有网"处理
        // (见 SourceHealthMemory.isNetworkAvailable 的容错口径),不会因一次异常而永久抑制累计。
        val weakNetwork = noNetwork || !SourceHealthMemory.isNetworkAvailable()
        // 闸 1 + 落库。suppressCount 有两个来源:弱网(一次抖动不该把一批好线打成可疑)、
        // 以及"还在推进时我们主动放弃"(只有"没等到"的证据,没有"它死了"的证据)。
        val suppressCount = weakNetwork || lineFaultUnproven
        LineHealthMemory.recordFail(sourceKey, vodId, flag, System.currentTimeMillis(), suppressCount)
        LOG.i(
            "echo-line-health fail key=" + key +
                " noNetwork=" + noNetwork +
                " weakNet=" + weakNetwork +
                " unproven=" + lineFaultUnproven +
                " suppressed=" + suppressCount +
                " streak=" + LineHealthMemory.lookup(sourceKey, vodId, flag).failStreak,
        )
        return true
    }

    /** 上报一次取流耗时(不设闸:耗时是无害的观测值) */
    @JvmStatic
    fun recordResolveMs(sourceKey: String?, vodId: String?, flag: String?, resolveMs: Long) {
        if (resolveMs <= 0) return
        LineHealthMemory.recordResolveMs(sourceKey, vodId, flag, resolveMs)
    }

    /**
     * 上报一次首帧耗时(同样是纯观测值)。
     *
     * <p>它是等起播预算从"固定 17 秒"改成"按这条线的历史算"的唯一依据;
     * 直播不记 —— 直播没有"这条线路起播要多久"的概念,频道地址也不是同一回事。
     */
    @JvmStatic
    fun recordFirstFrame(sourceKey: String?, vodId: String?, flag: String?, firstFrameMs: Long) {
        if (firstFrameMs <= 0L) return
        if (KV.get(HawkConfig.PLAYER_IS_LIVE, false)) return
        LineHealthMemory.recordFirstFrame(sourceKey, vodId, flag, firstFrameMs)
    }

    /**
     * 上报一次线路起播：该线路连续失败归零。
     *
     * <p>2026-10-16 拆账：**源级 `PLAY_SUCCESS` 不再在这里记**（改由
     * [recordSourcePlaySuccess] 在"画面确实连续推进够 60 秒"后记，见
     * [PlaybackQualityWatchdog.Host.onPlaybackConfirmedRealPlay]）。
     * 线路级留在起播即时是安全的 —— 它表达的是"这条地址至少能起来"，而且失败侧本来就有
     * 一堆抑制闸；源级不同：它**只加不减**，记早了洗不干净。
     * 真机 v1.1.49 的那次《希望一次过》就是"进播放态即算成功"直接给一个放错片的源加了分。
     */
    @JvmStatic
    fun recordPlayed(sourceKey: String?, vodId: String?, flag: String?) {
        // 两本账都不再读那把屏蔽开关(2026-10-15):它们只改位次与耐心,
        // 关掉"自动屏蔽坏源"不该连带冻结越用越准的台账
        LineHealthMemory.recordPlayed(sourceKey, vodId, flag, System.currentTimeMillis())
    }

    /**
     * 源级确凿证据：这一部片在**这个源**上真播起来了(画面连续推进 ≥ 60 秒)。
     *
     * <p>`PLAY_SUCCESS` 这个证据类型一直存在却长期没有任何调用点,于是源权重实际只是
     * "答不答话"的应答率 —— 一个源每次秒回、给出来的线路却全都播不通,分数照样顶格
     * (2026-10-15 记下的缺口)。现在它只在真播确认后加。
     *
     * <p>⚠️ **只加不减**：播放失败不扣源的分（网络抖动、解码器不支持、单条地址过期都会造成失败，
     * 归因太脏 —— 当年「天堂」被一条坏地址整源封 6 小时就是这么来的），失败仍只记线路级。
     */
    @JvmStatic
    fun recordSourcePlaySuccess(sourceKey: String?) {
        val key = sourceKey?.trim().orEmpty()
        if (key.isEmpty()) return
        // 直播不参与源权重:频道地址与"这个源好不好用"没有因果关系
        if (KV.get(HawkConfig.PLAYER_IS_LIVE, false)) return
        SourceWeightMemory.record(key, SourceWeightPolicy.EvidenceKind.PLAY_SUCCESS)
        LOG.i("echo-play-verify layer=source-weight PLAY_SUCCESS source=" + key)
    }
}
