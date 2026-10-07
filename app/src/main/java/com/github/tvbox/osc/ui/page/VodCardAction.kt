package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.util.LOG

sealed interface VodCardTarget {
    data class Action(val video: Movie.Video) : VodCardTarget

    data class Folder(val video: Movie.Video) : VodCardTarget

    data class Search(val title: String) : VodCardTarget

    data class Detail(val video: Movie.Video) : VodCardTarget
}

internal fun Movie.Video.isFolderCard(): Boolean = tag == "folder"

/**
 * 索引型源的卡片 id 形如 `msearch:###<片名>###<海报>@Referer=…`。
 *
 * <p>它**不是**可打开的详情 id，只是"片名 + 海报"的打包占位 ——
 * 真机实测（v1.0.42 的 card-route 日志）:
 * ```
 * id=msearch:###起义###https://img3.doubanio.com/…poster.jpg@Referer=https://api.douban.com/@User-Agent=…
 * ```
 * 也就是说这类卡片**手里只有片名**，站点根本没给详情 id。
 */
internal fun Movie.Video.isMsearchCard(): Boolean = id.orEmpty().startsWith("msearch:")

internal fun Movie.Video.hasOpenableDetailId(): Boolean {
    val value = id.orEmpty()
    return value.isNotEmpty() && !value.startsWith("msearch:")
}

/**
 * 这张卡**能不能进详情页**。
 *
 * <p>⚠️ v1.0.43 起 `msearch:` 占位卡**也算能进** —— 见下面对"为什么"的说明。
 *
 * <p>判据:站点身份在 + id 非空。注意**不再要求 id 是"真"详情 id**。
 */
internal fun canOpenOwnDetail(video: Movie.Video): Boolean =
    !video.sourceKey.isNullOrEmpty() && video.id.orEmpty().isNotEmpty()

/**
 * 卡片点击的路由决策。
 *
 * <p>## 为什么 `msearch:` 占位卡也路由到 Detail（v1.0.43）
 *
 * <p>用户诉求:"不同类型的源,效果要统一 —— 点首页卡片就进详情页,只有点搜索才进搜索页。"
 *
 * <p>原来索引型源的卡片一律改道搜索页,理由写在 [canOpenOwnDetail] 的旧注释里
 * ("卡片只是关键词入口,没有可播详情")。但真机实测拿到 card-route 日志后发现:
 * 这类卡片的 id 是 `msearch:###<片名>###<海报>@Referer=…`,**手里只有片名**。
 *
 * <p>关键判断:**"这个源给不出详情"这件事,详情页自己就能兜住** ——
 * `DetailResponseGuard.isUnloadableTarget` 对 `msearch:` 直接判为不可加载,
 * 于是 `loadDetail` 不做网络请求就转 `onDetailUnavailable()`,
 * 后者启动聚合搜索 + 自动换源,拿**片名**在别的源找到同一部片并加载。
 * 也就是说:把 msearch 卡路由到 Detail,最终呈现的正是用户要的那部片的详情页 ——
 * 不需要在点击当下替用户改道,更不需要新造一套"搜索后开第一条"的机制。
 *
 * <p>于是判据收敛成一条:**站点身份在 + id 非空 → 进详情**。
 * 只有 id 真的为空(没有任何线索)时才退回搜索页。
 *
 * <p>⚠️ 这里带一条诊断日志（`echo-detail card-route=`），**别删**。
 * 前缀用 `echo-detail`（已登记进 FILE_LOG_PREFIXES）。
 */
fun resolveVodCardTarget(video: Movie.Video): VodCardTarget {
    val target = when {
        !video.action.isNullOrEmpty() -> VodCardTarget.Action(video)
        video.isFolderCard() -> VodCardTarget.Folder(video)
        canOpenOwnDetail(video) -> VodCardTarget.Detail(video)
        else -> VodCardTarget.Search(video.name.orEmpty())
    }
    LOG.i(
        "echo-detail card-route=" + target.javaClass.simpleName +
            " src=" + video.sourceKey + " id=" + video.id +
            " tag=" + video.tag + " action=" + video.action +
            " msearch=" + video.isMsearchCard() +
            " canOpen=" + canOpenOwnDetail(video)
    )
    return target
}

fun Context.dispatchVodCardClick(video: Movie.Video, onAction: (Movie.Video) -> Unit = {}) {
    when (val target = resolveVodCardTarget(video)) {
        is VodCardTarget.Action -> onAction(video)
        is VodCardTarget.Folder -> openVodFolder(target.video)
        is VodCardTarget.Search -> jumpToSearch(target.title)
        is VodCardTarget.Detail -> jumpToDetail(
            target.video.id,
            target.video.sourceKey,
            target.video.name,
            target.video.pic,
        )
    }
}

fun Context.openVodCardOrDetail(video: Movie.Video) {
    if (video.isFolderCard()) {
        openVodFolder(video)
        return
    }
    jumpToDetail(video.id, video.sourceKey, video.name, video.pic)
}

private fun Context.openVodFolder(video: Movie.Video) {
    val folderId = video.id.orEmpty()
    if (folderId.isEmpty()) {
        Toast.makeText(this, getString(R.string.toast_folder_data_missing), Toast.LENGTH_SHORT).show()
        return
    }
    PartitionListActivity.startForFolder(this, folderId, video.name.orEmpty())
}
