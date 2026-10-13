package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SourceHealthMemory

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
    ): Boolean {
        if (!LineHealthMemory.isEnabled()) return false       // 总开关关闭:整条线路健康机制等同不存在
        if (userPickedLine) return false                    // 闸 2:用户选的,不产生证据
        val key = LineHealthMemory.keyOf(sourceKey, vodId, flag)
        if (key.isEmpty()) return false
        if (!recordedForSession.add(key)) return false       // 闸 3:本会话已记过
        // 闸 1:两个弱网信号取或(见 KDoc)。断网判定取不到上下文时按"有网"处理
        // (见 SourceHealthMemory.isNetworkAvailable 的容错口径),不会因一次异常而永久抑制累计。
        val weakNetwork = noNetwork || !SourceHealthMemory.isNetworkAvailable()
        // 闸 1 + 落库
        LineHealthMemory.recordFail(sourceKey, vodId, flag, System.currentTimeMillis(), weakNetwork)
        LOG.i(
            "echo-line-health fail key=" + key +
                " noNetwork=" + noNetwork +
                " weakNet=" + weakNetwork +
                " streak=" + LineHealthMemory.lookup(sourceKey, vodId, flag).failStreak,
        )
        return true
    }

    /** 上报一次取流耗时(不设闸:耗时是无害的观测值) */
    @JvmStatic
    fun recordResolveMs(sourceKey: String?, vodId: String?, flag: String?, resolveMs: Long) {
        if (!LineHealthMemory.isEnabled()) return
        if (resolveMs <= 0) return
        LineHealthMemory.recordResolveMs(sourceKey, vodId, flag, resolveMs)
    }

    /** 上报一次成功起播:连续失败归零 */
    @JvmStatic
    fun recordPlayed(sourceKey: String?, vodId: String?, flag: String?) {
        if (!LineHealthMemory.isEnabled()) return
        LineHealthMemory.recordPlayed(sourceKey, vodId, flag, System.currentTimeMillis())
    }
}
