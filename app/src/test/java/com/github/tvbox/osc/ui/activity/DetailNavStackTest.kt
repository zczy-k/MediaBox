package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailNavStackTest {

    private fun target(id: String, key: String = "src", title: String = "片$id") =
        DetailNavStack.Target(vodId = id, sourceKey = key, title = title, picture = "", fromCollect = false)

    /** 只保留当前一部:返回键一次即退出页面,返回链不随打开次数增长 */
    @Test
    fun pushKeepsOnlyCurrentTarget() {
        val stack = DetailNavStack()
        assertTrue(stack.push(target("a")))
        assertTrue(stack.push(target("b")))
        assertNull(stack.pop())
    }

    /** 重复点当前这一部不该重载(同源同 id 同标题视为同一目标;换源后的同 id 要能入栈) */
    @Test
    fun pushIgnoresSameTargetOnTop() {
        val stack = DetailNavStack()
        assertTrue(stack.push(target("a")))
        assertFalse(stack.push(target("a")))
        assertTrue(stack.push(target("a", key = "src2")))
        assertTrue(stack.push(target("a", key = "src2", title = "同片换名")))
    }

    @Test
    fun popNeverReturnsPrevious() {
        val stack = DetailNavStack()
        assertNull(stack.pop())
        stack.push(target("a"))
        assertNull(stack.pop())
    }

    /** 空 id 卡片靠标题区分:标题不同 = 新片(判成同一部会让卡片点了没反应) */
    @Test
    fun emptyIdCardsUseTitle() {
        val stack = DetailNavStack()
        assertTrue(stack.push(target("", title = "片甲")))
        assertFalse(stack.push(target("", title = "片甲")))
        assertTrue(stack.push(target("", title = "片乙")))
    }
}
