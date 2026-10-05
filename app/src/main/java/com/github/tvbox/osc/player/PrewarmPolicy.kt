package com.github.tvbox.osc.player

/** 内核预热的空闲释放决策:独立为纯函数供 JVM 单测锁真值表 */
object PrewarmPolicy {
    const val NO_IDLE_RELEASE = -1L

    /** 空闲释放延迟:预热开启 = 常驻不释放;关闭 = 回落原有上界 */
    @JvmStatic
    fun idleReleaseDelayMs(prewarmEnabled: Boolean, baseDelayMs: Long): Long =
        if (prewarmEnabled) NO_IDLE_RELEASE else baseDelayMs

    /** 关闭开关时是否补排一次空闲释放:有页面/直播在用的,由 detach 链路自然排期 */
    @JvmStatic
    fun shouldScheduleOnDisable(engineInUse: Boolean): Boolean = !engineInUse
}
