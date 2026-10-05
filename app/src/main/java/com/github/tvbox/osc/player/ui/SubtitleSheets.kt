package com.github.tvbox.osc.player.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Subtitle
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState
import com.github.tvbox.osc.player.state.SubtitleSheetState
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.sourcedata.SubtitleViewModel
import kotlin.math.roundToInt
import java.util.Locale

private const val MAX_SUBTITLE_DELAY_SECONDS = 600.0

private fun formatSubtitleDelay(milliseconds: Int): String {
    val value = String.format(Locale.US, "%.3f", milliseconds / 1000.0).trimEnd('0').trimEnd('.')
    return if (value.isEmpty() || value == "-0") "0" else value
}

@Composable
fun SubtitleSheet(sheet: SubtitleSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exo = sheet.exoInternal
    var sizeText by remember {
        mutableStateOf(if (exo) SubtitleHelper.getExoSubtitleScale().toString() + "%"
        else SubtitleHelper.getTextSize(context.findActivityOrNull()).toString())
    }
    var posText by remember {
        mutableStateOf(if (exo) {
            val p = SubtitleHelper.getExoSubtitlePosition()
            if (p == 0.0f) "0" else "$p%"
        } else "")
    }
    var delayText by remember {
        mutableStateOf(formatSubtitleDelay(SubtitleHelper.getTimeDelay()))
    }
    var subtitleOn by remember { mutableStateOf(sheet.enabled) }

    val applyDelay: () -> Unit = {
        val seconds = delayText.trim().replace(',', '.').toDoubleOrNull()
        if (seconds == null || !seconds.isFinite()) {
            Toast.makeText(context, context.getString(R.string.subtitle_delay_invalid), Toast.LENGTH_SHORT).show()
        } else {
            val safeSeconds = seconds.coerceIn(-MAX_SUBTITLE_DELAY_SECONDS, MAX_SUBTITLE_DELAY_SECONDS)
            val milliseconds = (safeSeconds * 1000.0).roundToInt()
            delayText = formatSubtitleDelay(milliseconds)
            sheet.onDelayChange(milliseconds)
        }
    }
    val adjustDelay: (Double) -> Unit = { deltaSeconds ->
        val current = delayText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        val milliseconds = ((current + deltaSeconds).coerceIn(
            -MAX_SUBTITLE_DELAY_SECONDS,
            MAX_SUBTITLE_DELAY_SECONDS,
        ) * 1000.0).roundToInt()
        delayText = formatSubtitleDelay(milliseconds)
        sheet.onDelayChange(milliseconds)
    }

    // 面板高度必须封顶:不封顶时内容比屏幕高,verticalScroll 不生效,底部控件会被窗口裁掉
    val maxPanelHeight = LocalConfiguration.current.screenHeightDp.dp * 0.92f
    PlayerDialog(onDismiss = onDismiss) {
        val dismiss = LocalPlayerSheetDismiss.current
        val dismissThen = LocalPlayerSheetDismissThen.current
        SheetPanel(
            width = playerDim(R.dimen.vs_640),
            modifier = Modifier.heightIn(max = maxPanelHeight),
        ) {
                Column(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(playerDim(R.dimen.vs_480))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = playerDim(R.dimen.vs_30)),
                ) {
                    // 字幕显示总开关(默认关):打开立即按记忆重新加载,关闭立即隐藏
                    SheetButton(
                        text = stringResource(
                            if (subtitleOn) R.string.subtitle_switch_on else R.string.subtitle_switch_off,
                        ),
                        selected = subtitleOn,
                        onClick = {
                            subtitleOn = !subtitleOn
                            sheet.onToggleEnabled(subtitleOn)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                    )
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    if (sheet.hasInternal) {
                        SheetButton(stringResource(R.string.subtitle_builtin), onClick = {
                            dismissThen { sheet.onSelectInternal() }
                        }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    }
                    SheetButton(stringResource(R.string.subtitle_local), onClick = {
                        dismissThen { sheet.onSelectLocal() }
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    SheetButton(stringResource(R.string.subtitle_online), onClick = {
                        dismissThen { sheet.onSelectRemote() }
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    // 字号行:exo 模式百分比 50~200 步 5;外挂字号 12~60 步 2
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(stringResource(R.string.subtitle_size_minus), onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.getExoSubtitleScale() - 5).coerceAtLeast(50)
                                sizeText = "$scale%"
                                SubtitleHelper.setExoSubtitleScale(scale)
                            } else {
                                val cur = (sizeText.toIntOrNull() ?: 16) - 2
                                val next = cur.coerceAtLeast(12)
                                sizeText = next.toString()
                                SubtitleHelper.setTextSize(next)
                                sheet.onTextSizeChange()
                            }
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                        Text(
                            text = sizeText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = playerTextSize(R.dimen.ts_26),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton(stringResource(R.string.subtitle_size_plus), onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.getExoSubtitleScale() + 5).coerceAtMost(200)
                                sizeText = "$scale%"
                                SubtitleHelper.setExoSubtitleScale(scale)
                            } else {
                                val cur = (sizeText.toIntOrNull() ?: 16) + 2
                                val next = cur.coerceAtMost(60)
                                sizeText = next.toString()
                                SubtitleHelper.setTextSize(next)
                                sheet.onTextSizeChange()
                            }
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Row(
                        Modifier.fillMaxWidth().height(playerDim(R.dimen.vs_60)),
                        horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_8)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(
                            stringResource(R.string.subtitle_style_white_outline),
                            onClick = {
                                sheet.onSelectStyle(0)
                                dismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SheetButton(
                            stringResource(R.string.subtitle_style_pink_outline),
                            onClick = {
                                sheet.onSelectStyle(1)
                                dismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SheetButton(
                            stringResource(R.string.subtitle_style_no_outline),
                            onClick = {
                                sheet.onSelectStyle(2)
                                dismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (exo) {
                        Spacer(Modifier.height(playerDim(R.dimen.vs_8)))
                        Row(
                            Modifier.fillMaxWidth().height(playerDim(R.dimen.vs_60)),
                            horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SheetButton(
                                stringResource(R.string.subtitle_move_up),
                                onClick = {
                                    val position = (SubtitleHelper.getExoSubtitlePosition() + 0.5f).coerceAtMost(80.0f)
                                    SubtitleHelper.setExoSubtitlePosition(position)
                                    posText = if (position == 0.0f) "0" else "$position%"
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SheetButton(
                                stringResource(R.string.subtitle_move_down),
                                onClick = {
                                    val position = (SubtitleHelper.getExoSubtitlePosition() - 0.5f).coerceAtLeast(-80.0f)
                                    SubtitleHelper.setExoSubtitlePosition(position)
                                    posText = if (position == 0.0f) "0" else "$position%"
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_8)))
                    // 原来是 SheetTextAction(高 vs_40),文字被压掉半截;换成常规按钮,高度与其余行一致
                    SheetButton(
                        text = stringResource(R.string.theme_reset),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_50)),
                        onClick = {
                            sheet.onReset()
                            sizeText = if (exo) SubtitleHelper.getExoSubtitleScale().toString() + "%"
                            else SubtitleHelper.getTextSize(context.findActivityOrNull()).toString()
                            val position = SubtitleHelper.getExoSubtitlePosition()
                            posText = if (position == 0.0f) "0" else "$position%"
                            delayText = SubtitleHelper.getTimeDelay().let { formatSubtitleDelay(it) }
                        },
                    )
                    if (exo) {
                        Text(
                            text = stringResource(R.string.subtitle_position_value, posText),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = playerDim(R.dimen.vs_5)),
                        )
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Text(
                        text = stringResource(if (exo) R.string.subtitle_delay_exo_hint else R.string.subtitle_delay_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = playerTextSize(R.dimen.ts_20),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    // 快捷微调仍保留 ±0.5 秒;中间输入框可直接填写正/负秒数(最多 ±10 分钟)。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_8)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(
                            stringResource(R.string.subtitle_advance),
                            onClick = { adjustDelay(-0.5) },
                            modifier = Modifier.weight(1f),
                        )
                        SheetInput(
                            value = delayText,
                            onValueChange = { value ->
                                if (value.matches(Regex("^-?\\d{0,4}([.,]\\d{0,3})?$"))) delayText = value
                            },
                            hint = stringResource(R.string.subtitle_delay_input_hint),
                            // Text keyboard allows the leading minus sign (negative = advance/earlier).
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                            onSubmit = applyDelay,
                            modifier = Modifier.weight(0.9f),
                        )
                        SheetButton(
                            stringResource(R.string.subtitle_delay),
                            onClick = { adjustDelay(0.5) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SheetButton(
                        text = stringResource(R.string.subtitle_delay_apply),
                        onClick = applyDelay,
                        modifier = Modifier.fillMaxWidth().height(playerDim(R.dimen.vs_50)),
                    )
                }
            }
    }
}

// ---------------------------------------------------------------------------
// 字幕搜索(数据链沿用 SubtitleViewModel:assrt 搜索 / zip 展开 / 分页)
// ---------------------------------------------------------------------------

@Composable
fun SubtitleSearchSheet(sheet: SubtitleSearchSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val viewModel: SubtitleViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var word by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<Subtitle>()) }
    var loading by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("search") } // search | zipfiles
    var page by remember { mutableIntStateOf(1) }
    var canLoadMore by remember { mutableStateOf(false) }
    val zipCache = remember { mutableListOf<Subtitle>() }
    // 当前展开的发布页(zipfiles 模式下列表是它内部的文件):随选择回传,供播放层记住来源
    var release by remember { mutableStateOf<Subtitle?>(null) }
    // Each page merges several title/code variants; allow more pages and stop when all return empty.
    val maxPage = 20
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val search: (String) -> Unit = { raw ->
        val w = raw.trim()
        if (w.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_input_empty), Toast.LENGTH_SHORT).show()
        } else {
            mode = "search"
            release = null
            items = emptyList()
            loading = true
            word = w
            page = 1
            viewModel.searchResult(w, 1)
        }
    }

    // 观察 SubtitleViewModel.searchResult
    DisposableEffect(viewModel) {
        val observer = androidx.lifecycle.Observer<com.github.tvbox.osc.bean.SubtitleData> { data ->
            mainHandler.post {
                loading = false
                val list = data.subtitleList
                if (list == null) {
                    Toast.makeText(context, context.getString(R.string.toast_subtitle_not_found), Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (list.isNotEmpty()) {
                    if (data.isZip) {
                        if (data.isNew) {
                            items = list
                            zipCache.clear()
                            zipCache.addAll(list)
                        } else {
                            items = items + list
                            zipCache.addAll(list)
                        }
                        page++
                        if (page > maxPage) {
                            canLoadMore = false
                        } else {
                            canLoadMore = true
                        }
                    } else {
                        items = list
                        canLoadMore = false
                    }
                } else {
                    canLoadMore = false
                }
            }
        }
        viewModel.searchResult.observe(lifecycleOwner, observer)
        onDispose { viewModel.searchResult.removeObserver(observer) }
    }

    // 进入即清洗片名并自动搜索(旧 setSearchWord 的清洗链)
    LaunchedEffect(Unit) {
        var wd = sheet.searchWord
        wd = wd.replace(Regex("(?:（|\\(|\\[|【|\\.mp4|\\.mkv|\\.avi|\\.MP4|\\.MKV|\\.AVI)"), "")
        wd = wd.replace(Regex("(?:：|\\:|）|\\)|\\]|】|\\.)"), " ")
        // Keep the full filename/title; SubtitleSearchQuery generates bounded title/code variants itself.
        wd = wd.trim()
        word = wd
        if (wd.isNotEmpty()) search(wd)
    }

    // zip 展开态按返回回搜索列表(旧 onBackPressed)
    BackHandler(enabled = mode == "zipfiles") {
        mode = "search"
        release = null
        items = zipCache.toList()
        canLoadMore = page < maxPage
        loading = false
    }

    // 触底加载更多(zip 搜索态)
    val listState = rememberLazyListState()
    LaunchedEffect(listState, canLoadMore, mode, items) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (canLoadMore && mode == "search" && items.isNotEmpty() &&
                    items.firstOrNull()?.isZip == true && last != null && last >= items.size - 3
                ) {
                    canLoadMore = false
                    viewModel.searchResult(word, page)
                }
            }
    }

    PlayerDialog(onDismiss = onDismiss) {
        val dismiss = LocalPlayerSheetDismiss.current
        SheetPanel(
                width = playerDim(R.dimen.vs_960),
                modifier = Modifier.height(playerDim(R.dimen.vs_480)),
            ) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
                Row(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .height(playerDim(R.dimen.vs_50)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetInput(
                        value = word,
                        onValueChange = { word = it },
                        hint = stringResource(R.string.subtitle_search_hint),
                        modifier = Modifier.weight(1f),
                        onSubmit = { search(word) },
                    )
                    Spacer(Modifier.width(playerDim(R.dimen.vs_5)))
                    SheetButton(text = stringResource(R.string.common_search), onClick = { search(word) })
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = playerDim(R.dimen.vs_30)),
                ) {
                    if (loading) {
                        SheetLoading(size = playerDim(R.dimen.vs_50))
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5)),
                        ) {
                            itemsIndexed(items) { _, item ->
                                SheetButton(
                                    text = "${item.name}(${if (item.isZip) "压缩包" else "文件"})",
                                    onClick = {
                                        if (item.isZip) {
                                            mode = "zipfiles"
                                            release = item
                                            loading = true
                                            viewModel.getSearchResultSubtitleUrls(item)
                                        } else {
                                            // 旧行为:发起直链解析后立即收起,回调在容器侧落地。
                                            // 发布页取不到就传空串:宁可不记,也不要把文件直链当发布页存进记忆
                                            val releaseUrl = release?.url.orEmpty()
                                            viewModel.getSubtitleUrl(item) { subtitle ->
                                                mainHandler.post {
                                                    if (subtitle.url != null) sheet.onLoadSubtitle(subtitle, releaseUrl)
                                                }
                                            }
                                            dismiss()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            }
    }
}
