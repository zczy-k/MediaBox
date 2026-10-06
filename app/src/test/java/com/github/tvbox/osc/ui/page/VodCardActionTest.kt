package com.github.tvbox.osc.ui.page

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isFolderCard] 单测:判错的后果是"点目录条目进详情页却永远空"(列目录要 `t=<id>&filter=true`,
 * 详情页发 `ids=<id>`),而搜索链路三个入口全靠它决定是否下钻。
 */
class VodCardActionTest {

    private fun video(tag: String?): Movie.Video {
        val item = Movie.Video()
        item.tag = tag
        return item
    }

    @Test
    fun folderTagIsFolderCard() {
        assertTrue(video("folder").isFolderCard())
    }

    @Test
    fun missingOrOtherTagIsNotFolderCard() {
        assertFalse(video(null).isFolderCard())
        assertFalse(video("").isFolderCard())
        assertFalse(video("电影").isFolderCard())
        assertFalse(video("Folder").isFolderCard())
    }

    private fun videoWithId(id: String?): Movie.Video {
        val item = Movie.Video()
        item.id = id
        return item
    }

    @Test
    fun blankOrPlaceholderIdIsNotOpenable() {
        // 空 id 与 msearch 占位都取不到详情:进详情只会白等,调用方必须改道
        assertFalse(videoWithId(null).hasOpenableDetailId())
        assertFalse(videoWithId("").hasOpenableDetailId())
        assertFalse(videoWithId("msearch:功夫").hasOpenableDetailId())
    }

    @Test
    fun normalIdIsOpenableAndPrefixMustMatchExactly() {
        assertTrue(videoWithId("12345").hasOpenableDetailId())
        // 前缀相近但不是占位,不能误判成不可打开
        assertTrue(videoWithId("msearch123").hasOpenableDetailId())
        assertTrue(videoWithId("msearch").hasOpenableDetailId())
    }
}
