package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.theme.filterChipColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 分区标题前的裸图标(22dp、onSurface 着色):画稿图标与内置图标共用 */
@Composable
internal fun SectionTitleIcon(painter: Painter) {
    Icon(
        painter = painter,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun SectionTitleIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * 详情页的换源分区。
 *
 * <p>⚠️ **这里绝不显示任何真实站名**。产品要求:任何情况下用户都不能看到 App 有哪些源。
 * 以前这里直接把 `SourceChip.name`(= `ApiConfig.getSource(key).name`)渲染成 FilterChip 标签,
 * 于是空态"暂无片源"下方会冒出一排真实站名 —— 这就是被用户截图拍到的泄露。
 * 现在标签一律由 [DetailViewModel.sourceLabelFor] 按**下标**生成(`源 1`/`源 2`…),
 * 与站名无关;`currentSourceName` 参数已彻底删除(唯一调用方本来就传 null,留着就是定时炸弹)。
 *
 * <p>候选 chip 仍然可点,点走的是 [DetailViewModel.switchSource] —— 手动换源的能力保留,
 * 只是不再告诉用户"你正在换到哪个站"。换源全自动化下这才是正确形态:
 * 提示既刷屏又泄露身份(与 [DetailViewModel.stopPlaybackForSwitch] 静默换源同一口径)。
 */
@Composable
internal fun SourceSection(vm: DetailViewModel, revision: Int) {
    @Suppress("UNUSED_EXPRESSION") revision
    val sourceChips by vm.sourceChips.collectAsStateWithLifecycle()
    val sourcesSearching by vm.sourcesSearching.collectAsStateWithLifecycle()
    val searchProgress by vm.searchProgress.collectAsStateWithLifecycle()
    val crossHint by vm.crossSourceHint.collectAsStateWithLifecycle()
    val chipPage by vm.chipPage.collectAsStateWithLifecycle()
    // [临时诊断 2026-10-14 #1] 观察 UI 实际收到的 sourcesSearching / searchProgress ——
    // 仅在值变化时打点(Compose 会重组很多次,LaunchedEffect 的 key 变了才重跑),
    // 用来判定"搜索在途时 UI 到底拿到的是 true 还是 false"。定位后移除。
    LaunchedEffect(sourcesSearching, searchProgress) {
        com.github.tvbox.osc.util.LOG.i(
            "echo-source-diag ui searching=$sourcesSearching" +
                " progress=${searchProgress?.done}/${searchProgress?.total}" +
                " chips=${sourceChips.size}"
        )
    }
    // 2026-10-14(详情页「换源」分页设计):**不再"空列表就整块不渲染"** ——
    // 那样用户在"没有候选/搜索没跑"时**没有任何入口发起换源**(真机反馈的核心问题)。
    // 现在无条件渲染:「换源」按钮始终可见,候选列表有内容时才在下方展开。
    val totalCandidates = vm.totalCandidateCount()
    val listState = rememberLazyListState()
    LaunchedEffect(chipPage, sourceChips.size) {
        if (sourceChips.isNotEmpty()) listState.scrollToItem(0)
    }
    Column(
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(20.dp))
            .padding(vertical = 12.dp)
    ) {
        // ① 「换源」按钮行(无条件可见,放最前面方便用户发起)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_switch_source))
            // 2026-10-14 用户反馈 #2 修正:按钮**不再因"自动搜索在跑"而置灰** ——
            // 那会让用户一进详情页(自动搜索即启动)就点不了换源,逻辑不通。
            // 现在只在"用户主动点了换源、该轮搜索还在跑"这一小段防连点(manualSwitching)。
            TextButton(
                onClick = { vm.onSwitchSourceClick() },
                enabled = !vm.manualSwitching.collectAsStateWithLifecycle().value,
            ) {
                Text(stringResource(R.string.detail_source_switch_action))
            }
            Text(
                // 2026-10-14 #1 修正:进度显示**以 searchProgress 为准**(与 sourcesSearching 解耦)。
                // 原来用 `if (sourcesSearching)` 判断,一旦 resetEngineState 把 progress 清 null
                // 而 searching 仍为 true,就永远落到提示分支(真机 bug)。现在只要有进度有值就显示进度。
                text = run {
                    val p = searchProgress
                    if (p != null && p.total > 0) {
                        stringResource(R.string.detail_source_switching, p.done, p.total)
                    } else {
                        stringResource(R.string.detail_source_switch_hint)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }
        crossHint?.let { hint ->
            // 只说"对端实测画质"这一事实,不带站名;序号按**那个源的身份**取(与 chips 同一口径),
            // 而 hintIndex 只用来判断"它此刻在不在可见列表里"—— 两者不能再混着当一件事。
            val hintIndex = sourceChips.indexOfFirst { it.key == hint.sourceKey }
            if (hintIndex >= 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.detail_cross_hint,
                            vm.sourceLabelFor(hint.sourceKey),
                            hint.label,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 去看看 = 与点 chip 同一条换源链;关掉 = 本片本会话不再提示
                    TextButton(onClick = {
                        // 先闩掉本片(键=当前源|片),再换源:换源后新源已在播,不应再对同片弹提示
                        vm.dismissCrossSourceHint()
                        vm.candidateForKey(hint.sourceKey)?.let { vm.switchSource(it) }
                    }) {
                        Text(stringResource(R.string.detail_cross_hint_go))
                    }
                    TextButton(onClick = { vm.dismissCrossSourceHint() }) {
                        Text(stringResource(R.string.detail_cross_hint_dismiss))
                    }
                }
            }
        }
        // ② 候选源分页(有候选才展开)。每页 5 个,双向翻页;评分随 chip 显示。
        if (sourceChips.isNotEmpty()) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 上一页(仅非首页显示)
                if (CandidatePager.hasPrev(chipPage)) {
                    item(key = "prev") {
                        TextButton(onClick = { vm.prevChipPage() }) {
                            Text(stringResource(R.string.detail_source_prev_page))
                        }
                    }
                }
                // 标签由**这个源自己的匿名号**生成(源 1 / 源 2 …),不含真实站名。
                // 2026-10-15:号不再按"页号*每页数+页内下标"现算 —— 那样同一个源每次回包重排都会换号,
                // 而"切回刚才那个源"全靠这个数字认人。号改由台账按 key 绑定(见 SourceBoardLedger)。
                itemsIndexed(sourceChips, key = { _, c -> c.key }) { _, chip ->
                    val label = vm.sourceLabelFor(chip.key)
                    if (label.isEmpty()) return@itemsIndexed
                    val scoreText = chip.score?.let {
                        stringResource(R.string.detail_source_score, Math.round(it).toInt())
                    } ?: stringResource(R.string.detail_source_no_score)
                    FilterChip(
                        selected = false,
                        onClick = { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } },
                        // 🟡 = 这一场已经试过它、放不出来（自动链替他试的，或他手动切过去试的）。
                        // 刻意只给一个符号不给文字：状态要看得懂，又不想把那一行变成说明书。
                        label = { Text(if (chip.suspect) "\uD83D\uDFE1 $label · $scoreText" else "$label · $scoreText") },
                        shape = RoundedCornerShape(20.dp),
                        colors = MaterialTheme.colorScheme.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                }
                // 下一页(仅在还有下一页时显示)
                if (CandidatePager.hasNext(totalCandidates, chipPage)) {
                    item(key = "next") {
                        TextButton(onClick = { vm.nextChipPage() }) {
                            Text(stringResource(R.string.detail_source_next_page))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RelatedSection(
    activity: DetailActivity,
    vm: DetailViewModel,
    onCardLongClick: (Movie.Video) -> Unit = {},
) {
    val relatedVideos by vm.relatedVideos.collectAsStateWithLifecycle()
    if (relatedVideos.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_recommend))
            Text(
                text = stringResource(R.string.detail_recommend),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                relatedVideos,
                key = { _, v -> (v.sourceKey ?: "") + "|" + (v.id ?: "") },
            ) { _, video ->
                VodCard(
                    video = video,
                    onClick = { activity.openVodCardOrDetail(video) },
                    onLongClick = { onCardLongClick(video) },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

private val CR_LINK_REGEX = Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]")
private val WHITESPACE_REGEX = Regex("\\s")

/**
 * 站点在简介开头堆的推广块。真机样本(js_douban,`挽救计划`):
 * ```
 * 【&#128293;官方交流群:https://t.me/tvshare23】━━━━━━━━━━━━━━━━━━挽救计划：太阳正在被…
 * ```
 * 形态很稳定:**方括号包住一段推广信息,后面跟一长串分隔线**,再接正文。
 * 所以按「方括号 + 分隔线」整体切掉,而不是逐个词抠 —— 抠词会漏掉没见过的变体。
 */
private val PROMO_BLOCK_REGEX = Regex(
    "[【\\[（(][^】\\]）)]{0,120}?(?:群|QQ|微信|公众号|客服|频道|群组|TG|Telegram)[^】\\]）)]{0,120}?[】\\]）)][\\s]*[\\-–—_=~＝━─—*#·\\.]{4,}"
)

/** 描述里独立的裸链接/裸域名。切完推广块后仍可能有残留(有些站点不写推广块,直接甩链接)。 */
private val BARE_LINK_REGEX = Regex(
    "(?:https?://|www\\.)[\\w\\-./?%&=:#@+~]{4,200}",
    RegexOption.IGNORE_CASE
)

/** 推广关键词句:「欢迎订阅」「请支持正版」这类纯广告句。 */
private val PROMO_SENTENCE_REGEX = Regex(
    "[^。！？!?\\n]{0,60}?(?:欢迎订阅|请支持正版|请勿传播|禁止转载|仅供学习|转载请注明|商务合作|广告合作|扫码关注|点击下方链接|加入我们|招募代理)[^。！？!?\\n]{0,60}[。！？!?]?"
)

/** 站点拿分隔线当视觉隔断:`━━━━━`、`————`、`=====` 等,连续 4 个以上。 */
private val SEPARATOR_RUN_REGEX = Regex("[\\-–—_=~＝━─—*#·\\.]{4,}")

/** 解码失败/残留的 HTML 实体(如 `&lt;br&gt;`、`&#128293;`)。 */
private val HTML_ENTITY_REGEX = Regex("&(?:[a-zA-Z]{2,8}|#\\d{1,7}|#[xX][0-9a-fA-F]{1,6});")

/**
 * 清洗详情页简介:**只删推广,保留正常信息**。
 *
 * <p>产品口径(用户明确指定):保留「主演 / 导演 / 类型 / 年代 / 地区」等正常信息,
 * 只删纯广告 —— 链接、群号、推广块、分隔线、广告句。
 *
 * <p>⚠️ **处理顺序不能换**:先解实体再切推广块。反过来会在
 * `【&#128293;官方交流群:…】` 上漏掉数字实体,导致推广块正则匹配不到
 * (真机样本里 `&#128293;` 就卡在方括号内,先切块会把推广留在界面上)。
 *
 * <p>为什么不用 `SourceIdentityMask.mask`:那个抹的是 URL/域名这类**技术标识**,
 * 这里要解决的是**推广文案块** —— 站点把群号塞在【】里,正则不认,得整块切。
 */
internal fun cleanVodDescription(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    // ① 先解实体:让后面的正则看到完整文本(否则 &lt;br&gt; / &#128293; 会挡住匹配)
    var text = raw.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    text = HtmlEntityDecode.decode(text)
    // ② 切广告:推广块 → 广告句 → 裸链接 → 分隔线。顺序有讲究:
    //    推广块连着分隔线,先切块再清分隔线才不会留下"━━━"这种孤儿装饰。
    text = PROMO_BLOCK_REGEX.replace(text, " ")
    text = PROMO_SENTENCE_REGEX.replace(text, " ")
    text = BARE_LINK_REGEX.replace(text, " ")
    text = SEPARATOR_RUN_REGEX.replace(text, " ")
    // ③ 收尾:去残留实体与全部空白
    text = HTML_ENTITY_REGEX.replace(text, " ")
    return text.replace(WHITESPACE_REGEX, "").trim()
}

/**
 * 只去 HTML 标签的旧实现,**保留**以兼容其它调用方。
 *
 * <p>⚠️ 它**不做广告过滤** —— 站点堆在简介开头的推广群/链接会原样留在界面上。
 * 详情页已改用 [cleanVodDescription];这里只作为纯"去标签"能力保留
 * (若将来有只需去标签、不需要过滤的场景再用)。
 */
internal fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(WHITESPACE_REGEX, "")
}
