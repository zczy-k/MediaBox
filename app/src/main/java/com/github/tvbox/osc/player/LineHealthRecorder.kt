package com.github.tvbox.osc.player

import com.github.tvbox.osc.util.LOG

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
 *       只是不累计连续次数 —— 由入参 `noNetwork` 控制;</li>
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
     * @param noNetwork 是否弱网抖动期(由调用方判定,见 [SourceHealthMemory] 的雪崩检测);
     *                  为 true 时**只记证据不累计 streak**。
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
        // 闸 1 + 落库
        LineHealthMemory.recordFail(sourceKey, vodId, flag, System.currentTimeMillis(), noNetwork)
        LOG.i(
            "echo-line-health fail key=" + key +
                " noNetwork=" + noNetwork +
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
