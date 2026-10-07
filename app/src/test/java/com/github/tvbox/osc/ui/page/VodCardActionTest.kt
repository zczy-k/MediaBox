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
        // ⚠️ 注意语义:v1.0.43 起 hasOpenableDetailId 的含义收窄成
        // "这张卡**在本源**有没有真详情 id",**不再等于"能不能进详情页"**。
        // msearch 占位卡照样会进详情页 —— 由详情页的兜底链接住(见下一条测试)。
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

    /**
     * v1.0.43 的新口径:**msearch 占位卡也路由到详情页**。
     *
     * <p>为什么敢这么做(真机 card-route 日志给的依据):这类卡的 id 是
     * `msearch:###<片名>###<海报>@Referer=…`,**手里只有片名**。
     * 而详情页对 `msearch:` 目标由 `DetailResponseGuard.isUnloadableTarget` 直接判为
     * 不可加载 ⇒ 不发请求就转 `onDetailUnavailable()` ⇒ 聚合搜索 + 自动换源
     * 拿片名在别的源找到同一部片。所以"进详情"最终真能落到那部片的详情页,
     * 不存在"白等"。
     *
     * <p>用户诉求即"不同类型的源效果统一:点卡片进详情,只有点搜索才进搜索页"。
     */
    @Test
    fun msearchPlaceholderStillRoutesToDetail() {
        val card = videoWithId("msearch:###起义###https://img3.doubanio.com/x.jpg@Referer=https://api.douban.com/")
        card.sourceKey = "点我切源"
        assertTrue("msearch 占位卡也应能进详情(靠详情页兜底链)", canOpenOwnDetail(card))
        assertTrue(card.isMsearchCard())
    }

    @Test
    fun missingSourceKeyStillCannotOpenDetail() {
        val card = videoWithId("msearch:###起义###")
        card.sourceKey = null
        assertFalse("站点身份缺失时进详情连请求都发不出去", canOpenOwnDetail(card))
    }

    @Test
    fun emptyIdStillCannotOpenDetail() {
        val card = videoWithId("")
        card.sourceKey = "点我切源"
        assertFalse("没有任何线索(连片名 id 都没有)时只能走搜索", canOpenOwnDetail(card))
    }
}
