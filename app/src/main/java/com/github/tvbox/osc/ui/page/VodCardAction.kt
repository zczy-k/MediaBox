package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.PartitionListActivity

sealed interface VodCardTarget {
    data class Action(val video: Movie.Video) : VodCardTarget

    data class Folder(val video: Movie.Video) : VodCardTarget

    data class Search(val title: String) : VodCardTarget

    data class Detail(val video: Movie.Video) : VodCardTarget
}

internal fun Movie.Video.isFolderCard(): Boolean = tag == "folder"

internal fun Movie.Video.hasOpenableDetailId(): Boolean {
    val value = id.orEmpty()
    return value.isNotEmpty() && !value.startsWith("msearch:")
}

/**
 * 这张卡**在它自己那个来源里**能不能直接打开详情。
 *
 * <p>⚠️ v1.0.40 起**不再看站点是不是索引型**(`indexs=1`)。
 *
 * <p>原来索引型源的卡片一律改道搜索(见 [resolveVodCardTarget]),理由是
 * "卡片只是关键词/分类入口,点进去没有可播详情"。真机用下来这个口径有两个问题:
 *
 * <ol>
 *   <li><b>同类卡片在不同源里表现不一致</b>:用户换一个源,同样的点击一个进详情、
 *       一个进搜索。首页卡片长得一模一样(海报 + 片名),行为却不同,看起来就是坏了。</li>
 *   <li><b>"这个源给不出详情"已经不是致命问题了</b>:详情取不到时,详情页本来就有
 *       聚合搜索 + 自动换源的兜底链(见 DetailViewModel 的 fallback 逻辑)——
 *       它会拿片名去别的源找到同一部片。也就是说"当前源没详情"完全有人接住,
 *       没必要在点击当下就替用户改道。</li>
 * </ol>
 *
 * <p>所以判据收敛成一条:**只要 id 可用就进详情**。id 不可用(空 / `msearch:` 占位)
 * 或站点身份缺失时仍然只能走搜索 —— 那种情况进详情连请求都发不出去。
 */
internal fun canOpenOwnDetail(video: Movie.Video): Boolean =
    !video.sourceKey.isNullOrEmpty() && video.hasOpenableDetailId()

fun resolveVodCardTarget(video: Movie.Video): VodCardTarget = when {
    !video.action.isNullOrEmpty() -> VodCardTarget.Action(video)
    video.isFolderCard() -> VodCardTarget.Folder(video)
    canOpenOwnDetail(video) -> VodCardTarget.Detail(video)
    else -> VodCardTarget.Search(video.name.orEmpty())
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
