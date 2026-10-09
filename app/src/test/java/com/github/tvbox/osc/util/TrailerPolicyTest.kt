package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.Movie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预告片不是正片」判据的边界(产品口径 2026-10-09 拍板:只找到预告片 = 视同没有这部片)。
 *
 * <p>两条最容易写反的地方:
 * ① 只看"只有一条"就判预告片 ⇒ **全部电影都是单集**,会把整库误判掉;
 *    必须是"唯一一条 **且** 集名命中词表"。
 * ② 词表收得太宽 ⇒ 把正片判成预告片 ⇒ 好片被当"没资源"从首页永久藏掉。
 *    所以认不出的集名(如 `01`、`HD`、`正片`)一律当正片(fail-open)。
 */
class TrailerPolicyTest {

    private fun video(vararg episodes: String): Movie.Video {
        val v = Movie.Video()
        val urlBean = Movie.Video.UrlBean()
        val info = Movie.Video.UrlBean.UrlInfo()
        info.flag = "线路一"
        info.urls = episodes.joinToString("#")
        info.beanList = ArrayList(episodes.map { Movie.Video.UrlBean.UrlInfo.InfoBean(it, "http://x/$it") })
        urlBean.infoList = ArrayList(listOf(info))
        v.urlBean = urlBean
        return v
    }

    // ---------- 集名判定 ----------

    @Test
    fun trailerNames_areRecognized() {
        assertTrue(TrailerPolicy.isTrailerName("预告片"))
        assertTrue(TrailerPolicy.isTrailerName("预告"))
        // 括注会被归一化删掉,所以带年份/序号的预告也命中
        assertTrue(TrailerPolicy.isTrailerName("预告片(2026)"))
        assertTrue(TrailerPolicy.isTrailerName("【先导预告】"))
        assertTrue(TrailerPolicy.isTrailerName("Trailer"))
        assertTrue(TrailerPolicy.isTrailerName("正式预告 Trailer"))
        // pv 只认开头:中文媒体常把预告影像叫 PV1/PV2
        assertTrue(TrailerPolicy.isTrailerName("PV"))
        assertTrue(TrailerPolicy.isTrailerName("PV2"))
        assertTrue(TrailerPolicy.isTrailerName("片花"))
        assertTrue(TrailerPolicy.isTrailerName("特报"))
    }

    @Test
    fun realEpisodeNames_areNeverTrailers() {
        assertFalse(TrailerPolicy.isTrailerName("第01集"))
        assertFalse(TrailerPolicy.isTrailerName("01"))
        assertFalse(TrailerPolicy.isTrailerName("正片"))
        assertFalse(TrailerPolicy.isTrailerName("HD"))
        assertFalse(TrailerPolicy.isTrailerName("HD国语版"))
        // fail-open:认不出就当正片 —— 宁可漏判,不可把真电影判成"没资源"
        assertFalse(TrailerPolicy.isTrailerName(null))
        assertFalse(TrailerPolicy.isTrailerName(""))
    }

    @Test
    fun pvOnlyMatchesAtStart() {
        // "我的PV人生"是正片标题:pv 不在开头 ⇒ 不是预告
        assertFalse(TrailerPolicy.isTrailerName("我的PV人生"))
    }

    // ---------- 剔除动作 ----------

    @Test
    fun trailerOnlyVideo_isStrippedToNothing() {
        val v = video("预告片")
        assertFalse(TrailerPolicy.stripTrailers(v))
        // 剔空后 beanList 必须是空列表而不是 null:VodInfo.setVideo 靠 size()>0 跳过
        assertEquals(0, v.urlBean.infoList[0].beanList.size)
    }

    @Test
    fun mixedList_keepsRealEpisodesAndDropsTrailers() {
        val v = video("预告片", "第01集", "第02集")
        assertTrue(TrailerPolicy.stripTrailers(v))
        val names = v.urlBean.infoList[0].beanList.map { it.name }
        assertEquals(listOf("第01集", "第02集"), names)
    }

    @Test
    fun allRealList_isUntouched() {
        val v = video("第01集", "第02集")
        assertTrue(TrailerPolicy.stripTrailers(v))
        assertEquals(2, v.urlBean.infoList[0].beanList.size)
    }

    @Test
    fun trailerLineIsEmptiedButRealLineSurvives() {
        // 两条线路:一条全是预告,一条有正片 ⇒ 整体仍有正片,预告线路被剔空
        val v = Movie.Video()
        val urlBean = Movie.Video.UrlBean()
        val trailerLine = Movie.Video.UrlBean.UrlInfo().apply {
            flag = "预告线"
            beanList = ArrayList(listOf(Movie.Video.UrlBean.UrlInfo.InfoBean("预告片", "http://x/t")))
        }
        val realLine = Movie.Video.UrlBean.UrlInfo().apply {
            flag = "正片线"
            beanList = ArrayList(listOf(Movie.Video.UrlBean.UrlInfo.InfoBean("正片", "http://x/1")))
        }
        urlBean.infoList = ArrayList(listOf(trailerLine, realLine))
        v.urlBean = urlBean
        assertTrue(TrailerPolicy.stripTrailers(v))
        assertEquals(0, v.urlBean.infoList[0].beanList.size)
        assertEquals(1, v.urlBean.infoList[1].beanList.size)
    }

    @Test
    fun degenerateInputsAreSafe() {
        val noUrl = Movie.Video()
        assertFalse(TrailerPolicy.stripTrailers(noUrl))
        val emptyUrlBean = Movie.Video().apply { urlBean = Movie.Video.UrlBean() }
        assertFalse(TrailerPolicy.stripTrailers(emptyUrlBean))
        assertFalse(TrailerPolicy.stripTrailers(null))
    }
}
