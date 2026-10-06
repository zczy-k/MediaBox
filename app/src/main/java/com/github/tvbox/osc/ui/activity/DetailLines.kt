package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.theme.filterChipColors

/**
 * 线路 chip 的显示名:「线路N」,实测到画质时再补「 · 1080P」。
 *
 * <p>画质取的是**实测值**([DetailViewModel.lineQualityHeights]),不是站点自报的 flag ——
 * flag 常写成假的清晰度,有的还直接带站名/域名。没测到就只显示序号,不编。
 *
 * <p>抽成顶层函数是因为**三个入口**(详情页正文、竖屏选集面板、横屏全屏侧滑面板)
 * 必须给出完全一致的标签,否则用户在两处看到同一个线路却是两个名字。
 */
@Composable
internal fun lineLabel(index: Int, flagName: String?, heights: Map<String, Int>): String {
    val base = stringResource(R.string.detail_line_index, index + 1)
    val suffix = LineLabelPolicy.qualitySuffix(flagName?.let { heights[it] } ?: 0)
    return if (suffix.isEmpty()) base else "$base · $suffix"
}

/**
 * 详情页正文的「线路」区:与「画质」区同构(同一个 [ChipRow] 容器),插在画质与选集之间。
 *
 * <p>为什么恢复这个区(它曾被主动下线):下线时的理由是"暴露 flag 名会让用户以为手动选过,
 * 实际又会被自动策略改掉"。现在这条理由**已经不成立**了 ——
 * ①标签换成「线路N + 实测画质」,不再输出站点自报的名字,也没有泄露源身份;
 * ②标签与实测画质都取自真实数据,用户能据此判断"这条大概多少画质";
 * ③更关键的是,手动选线是**一等意图**:一旦用户主动选过,播放侧会记 `userPickedLine`
 * 而**不再自动换线换源**。既然这个意图要生效,UI 上就必须给入口,否则这条代码路径
 * 只能靠内部逻辑触发,用户永远摸不到。
 *
 * <p>单线路不显示:没有可切换对象,一行只有一个 chip 的"线路"区是噪声。
 */
@Composable
internal fun DetailLineSection(vm: DetailViewModel) {
    val info = vm.vodInfo ?: return
    val flags = info.seriesFlags.orEmpty()
    if (flags.size <= 1) return
    val currentFlag = info.playFlag
    // 与面板同一口径:打开这一瞬刷新一次实测画质(起播后由播放层回写,到这时才可能变)
    val lineHeights by vm.lineQualityHeights.collectAsStateWithLifecycle()

    ChipRow(
        title = stringResource(R.string.detail_line),
        leading = { SectionTitleIcon(painterResource(R.drawable.ic_detail_line)) },
    ) {
        itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { index, flag ->
            FilterChip(
                selected = flag.name == currentFlag,
                onClick = { vm.onFlagClick(flag.name ?: "") },
                label = { Text(lineLabel(index, flag.name, lineHeights)) },
                shape = RoundedCornerShape(20.dp),
                colors = MaterialTheme.colorScheme.filterChipColors(),
            )
        }
    }
}
