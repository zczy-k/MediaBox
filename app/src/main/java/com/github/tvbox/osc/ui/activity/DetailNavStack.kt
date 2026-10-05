package com.github.tvbox.osc.ui.activity

/**
 * 详情页的内容栈:栈顶 = 当前展示的影片。
 *
 * 只存导航参数(影片数据由加载链路重建),栈深不带来视图/播放资源 —— 详情页因此不必靠叠加实例承载内容切换。
 */
internal class DetailNavStack {

    internal data class Target(
        val vodId: String,
        val sourceKey: String,
        val title: String,
        val picture: String,
        val fromCollect: Boolean,
    )

    private val stack = ArrayList<Target>()

    /** 入栈;与栈顶同片(源+id+标题,空 id 卡片靠标题区分)返回 false,调用方不再重载 */
    fun push(target: Target): Boolean {
        val top = stack.lastOrNull()
        if (top != null &&
            top.vodId == target.vodId &&
            top.sourceKey == target.sourceKey &&
            top.title == target.title
        ) {
            return false
        }
        stack.add(target)
        while (stack.size > MAX_DEPTH) {
            stack.removeAt(0)
        }
        return true
    }

    /** 弹出一层;栈底不可弹,返回 null 表示没有上一部(调用方走退出页面) */
    fun pop(): Target? {
        if (stack.size <= 1) return null
        stack.removeAt(stack.lastIndex)
        return stack.last()
    }

    companion object {
        /** 只留当前一部:返回键一次即退出页面,不留"按打开次数增长"的返回链 */
        private const val MAX_DEPTH = 1
    }
}
