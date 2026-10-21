package com.github.tvbox.osc.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * 设计令牌(Design Tokens)——「珊瑚橙」视觉规范的唯一来源。
 *
 * <p>⚠️ 主色必须与 [MediaBoxSeedArgb] 同源。MaterialKolor 从种子色生成的
 * ColorScheme 才是运行时真正生效的那套;这里的常量用于**不走 MaterialTheme 的
 * 场景**(玻璃层绘制、播放器控件、海报角标),两处色值漂移的表现是
 * "同一屏出现两种橙",极难察觉但会显得脏。
 */
object MediaBoxColors {

    /** 品牌色深阶:按下态、深色模式主色、白字按钮底 */
    val BrandDeep = Color(0xFFD6431F)

    /** 品牌色:与 [MediaBoxSeedArgb] 完全一致 */
    val Brand = Color(0xFFFF6B4A)

    /** 品牌色亮阶:深色底上的强调、深色模式主色 */
    val BrandBright = Color(0xFFFFC0AE)

    /** 品牌色浅底:选中态容器、标签底 */
    val BrandSoft = Color(0xFFFFEDE8)

    /**
     * 强调色:评分、角标这类"要跳出来"的信息。
     *
     * <p>为什么是蓝不是橙:主色已经是橙,再用橙做强调会和主色糊成一片。
     * 冷色点缀在暖色主色上才有对比。全案唯一保留蓝的地方。
     */
    val Accent = Color(0xFF2F6BFF)

    /** 危险色:失败态、删除 */
    val Danger = Color(0xFFDC2626)

    /** 成功色:源已返回、连接正常 */
    val Ok = Color(0xFF16A34A)

    /** 页面底色(浅色模式),与 `colors.xml` 的 `window_background` 必须一致 */
    val Bg = Color(0xFFFBF8F7)

    /** 一级容器 */
    val Surface = Color(0xFFFFFFFF)

    /** 二级容器:输入框、chip、骨架屏底 */
    val Surface2 = Color(0xFFF4EFED)

    /** 描边 */
    val Line = Color(0xFFE8DED9)

    /** 正文主色(带一点暖调,纯黑在暖底上会发青) */
    val Ink = Color(0xFF1A1412)

    /** 正文次级色 */
    val Ink2 = Color(0xFF4A403C)

    /** 辅助说明 */
    val Ink3 = Color(0xFF7A6E6A)

    /** 占位符、禁用态 —— **不得**用于正文(对比度不足) */
    val Ink4 = Color(0xFFA3968F)
}

/**
 * 全流体尺寸系统。
 *
 * <p>设计原则:**尺寸是算出来的,不是查表查出来的**。断点只决定"结构"
 * (导航放哪、几栏),所有数值都是可用宽度 w 的连续函数 —— 这样 673dp 的折叠屏、
 * 500dp 的分屏、800dp 的小平板都各自落在自己的最优值上,不会卡在两档之间。
 *
 * <p>入参 w / h 必须来自 `BoxWithConstraints` 的 **maxWidth / maxHeight**(当前窗口),
 * 不能用屏幕宽度:分屏、自由窗口、折叠态下屏幕宽度会骗人。
 *
 * <p>规范见 `deliverables/MediaBox_UI_Final.html`。
 */
object Fluid {

    /** 页面左右安全边距:16 是手机最小呼吸感,40 是"再宽就该加列"的临界 */
    fun padding(w: Dp): Dp = (w.value * 0.044f).coerceIn(16f, 40f).dp

    /** 间距:低于 8 卡片粘连,高于 16 网格松散 */
    fun gap(w: Dp): Dp = (w.value * 0.008f).coerceIn(8f, 16f).dp

    /** 卡片目标宽:只用来反推列数,不直接决定卡片宽 */
    fun targetCard(w: Dp): Dp = (w.value * 0.19f).coerceIn(100f, 150f).dp

    /**
     * 网格列数。`minColumns` 只做兜底(手机 3 / 平板 4 / 大屏 5 / TV 6),
     * 防止极窄窗口算出 1 列。
     */
    fun columns(w: Dp, minColumns: Int): Int {
        val avail = w.value - 2 * padding(w).value
        if (avail <= 0f) return minColumns
        val step = targetCard(w).value + gap(w).value
        return max(((avail + gap(w).value) / step).toInt(), minColumns)
    }

    /**
     * 卡片实际宽 —— **由列数反推**。
     *
     * <p>这是"不卡死"的核心:任何 w 都能解出一个精确值,网格永远铺满、不留尾巴。
     * 不要改用固定卡宽 + 居中:那会让栅格与不在栅格里的兄弟元素(页签行、顶栏)错位。
     */
    fun cardWidth(w: Dp, columns: Int): Dp {
        val avail = w.value - 2 * padding(w).value
        if (columns <= 0) return 0.dp
        return ((avail - (columns - 1) * gap(w).value) / columns).dp
    }

    /** 海报标题字号:跟着卡片走,保证标题放得下 */
    fun titleSize(cardWidth: Dp): TextUnit =
        (cardWidth.value * 0.101f).coerceIn(10.5f, 15f).sp

    /** 正文字号:跟着阅读宽度走;TV 另按 10 英尺单独定,不走这里 */
    fun bodySize(w: Dp): TextUnit = (w.value * 0.0375f).coerceIn(13.5f, 16f).sp

    /**
     * 头图高度:以宽度为主,高度只做防失控上限。
     *
     * <p>2026-10-21 修正:原来叠的是 `h × 0.28`,在横屏平板上(内容 760×500)算出 140,
     * 被 coerceIn 抬到 150 ⇒ 头图变成 760×150 的 **5:1 扁条**。源图是竖版海报(2:3),
     * Crop 到 5:1 只剩中间 13%,还要横向放大 5 倍 ⇒ 既"显示不全"又"糊"。
     *
     * <p>现在:主项 `w × 0.44`(手机 158dp,与原来 166 基本一致),上限放宽到 `h × 0.50`
     * 只用于矮屏/电视兜底(电视 540 高 → 270,占屏高一半)。
     * 平板 760×500 → 250dp,**宽高比从 5:1 回到 3:1**。
     */
    fun heroHeight(w: Dp, h: Dp): Dp =
        min(w.value * 0.44f, h.value * 0.50f).coerceIn(150f, 280f).dp

    /** 卡片圆角:与尺寸成比例,小卡用大圆角会"发软" */
    fun cardRadius(cardWidth: Dp): Dp = (cardWidth.value * 0.095f).coerceIn(10f, 16f).dp

    /** 头图圆角 */
    fun heroRadius(w: Dp): Dp = (w.value * 0.06f).coerceIn(18f, 28f).dp

    /** 底部导航高度 */
    fun navHeight(w: Dp): Dp = (w.value * 0.15f).coerceIn(52f, 64f).dp

    /** 侧栏宽度 */
    fun railWidth(w: Dp): Dp = (w.value * 0.09f).coerceIn(72f, 96f).dp

    /** 详情页操作条的圆钮直径 */
    fun actionButtonSize(w: Dp): Dp = (w.value * 0.055f).coerceIn(40f, 48f).dp

    /** 详情页两栏时,左侧海报栏宽度 */
    fun detailPosterWidth(w: Dp): Dp = (w.value * 0.30f).coerceIn(220f, 320f).dp

    /**
     * 详情页操作条能放几个次级入口(含溢出槽)。
     *
     * <p>主按钮保底 140dp(要放得下「从第 5 集播放」);放不下的入口收进「⋯」。
     * 封顶 4:四个入口全放下时不需要溢出槽。
     */
    fun detailActionSlots(avail: Dp, w: Dp): Int {
        val step = actionButtonSize(w).value + 8f
        val raw = ((avail.value - 140f - 8f) / step).toInt()
        return raw.coerceIn(1, 4)
    }

    /** 结构断点:只有"导航位置 / 详情栏数 / 搜索列数"这类结构判断用它 */
    const val WIDE_DP = 600f

    fun isWide(w: Dp): Boolean = w.value >= WIDE_DP
}

/**
 * 圆角只有三档 + 胶囊,禁止再出现其它值。
 *
 * <p>层级规则:越"大"的容器圆角越大,让嵌套关系靠曲率就能读出来 ——
 * 卡片里的缩略图(10) < 卡片(16) < 浮层(28)。
 */
object MediaBoxRadius {

    /** 小件:缩略图、集数格、输入框内元素 */
    val Small = 10.dp

    /** 海报卡、普通卡片 */
    val Card = 16.dp

    /** Hero 轮播、大容器 */
    val Container = 22.dp

    /** 底部弹层 */
    val Sheet = 28.dp

    /** 胶囊:按钮、搜索框、导航条 */
    val Pill = 999.dp
}

/** 间距一律取 4 的倍数。 */
object MediaBoxSpace {

    val Hair = 4.dp

    /** 同区块内元素间距 */
    val Item = 8.dp

    /** 网格行列间距、列表条目间距 */
    val Grid = 12.dp

    /** 页面左右安全边距(固定档;需要自适应时用 [Fluid.padding]) */
    val ScreenEdge = 16.dp

    /** 区块之间 */
    val Section = 24.dp

    /** 大区块 / 顶部留白 */
    val Block = 32.dp
}

/**
 * 阴影三级,**只有浮层允许用阴影**。
 *
 * <p>列表内的卡片一律用 1px 描边代替阴影 —— 滚动时多层半透明阴影叠加会出脏边,
 * 而且在低端机上是实打实的过度绘制。
 */
object MediaBoxElevation {

    /** 卡片静止态 */
    val Card = 1.dp

    /** 卡片按下 / 悬浮 */
    val Raised = 4.dp

    /** 弹层、悬浮导航 */
    val Overlay = 12.dp
}
