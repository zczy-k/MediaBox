package com.github.tvbox.osc.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源链入口闸门的真值表。
 *
 * <p>锁两条互补的硬约束:
 * <ol>
 *   <li>**钉住的播放源仍然拦得住**(`changeable=0` 且不是索引型)—— 这是闸门存在的理由,
 *       绝不能被这次修复顺手拆掉;</li>
 *   <li>**索引型源不再被误拦** —— 它自己没有线路,拦下等于让用户白等(真机 55 秒事故)。</li>
 * </ol>
 */
class FallbackEntryPolicyTest {

    @Test
    fun normalSourceAlwaysAllowed() {
        // changeable=1 的普通源:照旧放行,与修复前行为一致
        assertFalse(FallbackEntryPolicy.blocksFallbackEntry(changeable = true, indexLike = false))
        assertFalse(FallbackEntryPolicy.blocksFallbackEntry(changeable = true, indexLike = true))
    }

    @Test
    fun pinnedPlaybackSourceIsStillBlocked() {
        // 闸门存在的理由:钉住的播放源(如「我的云盘」csp_MyDriveGuard,indexs=0)
        // 取不到内容就该如实报错,绝不能被静默换成某个随机聚合站去播。
        assertTrue(FallbackEntryPolicy.blocksFallbackEntry(changeable = false, indexLike = false))
    }

    @Test
    fun indexLikeSourceWithChangeableZeroIsAllowed() {
        // 本次修复的核心格:js_douban 真机配置 "searchable":0,"quickSearch":0,"changeable":0。
        // 它没有任何线路,内容只能靠聚合搜索从别站取 —— changeable=0 对它只意味着
        // "站内没有可换线路",不是"不许去别处取内容"。
        assertFalse(FallbackEntryPolicy.blocksFallbackEntry(changeable = false, indexLike = true))
    }

    @Test
    fun onChainAlwaysAllowed() {
        // 候选站自己可能也是 changeable=0(如 csp_MyDriveGuard 的 searchable=1,会被聚合搜索收进候选)。
        // 若在链上还按源配置裁决,就会在某个候选站上硬生生断链 —— 既不换源也不报错推进。
        assertFalse(FallbackEntryPolicy.blocksFallbackEntry(changeable = false, indexLike = false, onChain = true))
        assertFalse(FallbackEntryPolicy.blocksFallbackEntry(changeable = true, indexLike = true, onChain = true))
    }

    @Test
    fun onChainOverridesEvenThePinnedBlock() {
        // 已上链 ⇒ 放手:入口那一刻已经受过闸门保护,"去别处取内容"这个决定早已作出。
        assertFalse(FallbackEntryPolicy.blocksFallbackEntry(changeable = false, indexLike = false, onChain = true))
    }

    @Test
    fun onlyTheNarrowCombinationIsBlocked() {
        // 全表遍历:8 格里**只有**一格(非链上 + changeable=0 + 非索引型)该被拦。
        // 这条断言的价值在于:将来有人加参数/改默认值时,只要闸门范围偷偷变大变小,这里立刻红。
        var blocked = 0
        for (changeable in booleanArrayOf(true, false)) {
            for (indexLike in booleanArrayOf(true, false)) {
                for (onChain in booleanArrayOf(true, false)) {
                    if (FallbackEntryPolicy.blocksFallbackEntry(changeable, indexLike, onChain)) blocked++
                }
            }
        }
        assertTrue("恰好一格该被拦,实际 $blocked 格", blocked == 1)
    }
}
