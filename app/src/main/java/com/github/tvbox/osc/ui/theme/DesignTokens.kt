package com.github.tvbox.osc.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌(Design Tokens)——「流光 Lumen」视觉规范的唯一来源。
 *
 * <p>为什么要有这个文件:此前圆角、间距、阴影散落在各组件里,同一屏能同时出现
 * 16/20/24dp 三种圆角和四种阴影强度,海报被压成背景。这里把规范收敛成常量,
 * 组件只允许引用它们,不允许再写字面量。
 *
 * <p>规范见 `deliverables/MediaBox_UI_Design_System.html`。
 *
 * <p>⚠️ 主色必须与 [MediaBoxSeedArgb] 同源。MaterialKolor 从种子色生成的
 * ColorScheme 才是运行时真正生效的那套;这里的常量用于**不走 MaterialTheme 的
 * 场景**(玻璃层绘制、海报角标、播放器控件),两处色值漂移的表现是
 * "同一屏出现两种青绿",极难察觉但会显得脏。
 */
object MediaBoxColors {

    /** 品牌色深阶:按下态、深色模式主色、播放器强调 */
    val BrandDeep = Color(0xFF0B5A54)

    /** 品牌色:与 [MediaBoxSeedArgb] 完全一致 */
    val Brand = Color(0xFF0F766E)

    /** 品牌色亮阶:深色底上的强调色、进度条、播放器高亮 */
    val BrandBright = Color(0xFF7DE3D4)

    /** 品牌色浅底:选中态容器、标签底 */
    val BrandSoft = Color(0xFFE3F2F0)

    /** 强调色:仅用于「评分 / 新 / 独播」这类需要跳出的信息,面积不得超过一屏的 5% */
    val Accent = Color(0xFFF59E0B)

    /** 危险色:失败态、删除 */
    val Danger = Color(0xFFDC2626)

    /** 成功色:源已返回、连接正常 */
    val Ok = Color(0xFF16A34A)

    /** 页面底色(浅色模式),与 `colors.xml` 的 `window_background` 必须一致 */
    val Bg = Color(0xFFF6F8F9)

    /** 一级容器 */
    val Surface = Color(0xFFFFFFFF)

    /** 二级容器:输入框、chip、骨架屏底 */
    val Surface2 = Color(0xFFEEF3F3)

    /** 描边 */
    val Line = Color(0xFFDCE5E4)

    /** 正文主色 */
    val Ink = Color(0xFF12211F)

    /** 正文次级色 */
    val Ink2 = Color(0xFF41524F)

    /** 辅助说明 */
    val Ink3 = Color(0xFF71837F)

    /** 占位符、禁用态 —— **不得**用于正文(对比度不足) */
    val Ink4 = Color(0xFF9DADA9)
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

/**
 * 间距一律取 4 的倍数。
 *
 * <p>页面左右安全边距固定 [ScreenEdge];区块之间 [Section];同区块内元素 [Item]。
 */
object MediaBoxSpace {

    val Hair = 4.dp

    /** 同区块内元素间距(角标内边距、图标与文字) */
    val Item = 8.dp

    /** 网格行列间距、列表条目间距 */
    val Grid = 12.dp

    /** 页面左右安全边距 —— 全站统一,不允许某个页面单独用 20dp */
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
 *
 * <p>Compose 侧对应 `Modifier.shadow(elevation, shape)`,这里只给数值。
 */
object MediaBoxElevation {

    /** 卡片静止态 */
    val Card = 1.dp

    /** 卡片按下 / 悬浮 */
    val Raised = 4.dp

    /** 弹层、悬浮导航 */
    val Overlay = 12.dp
}
