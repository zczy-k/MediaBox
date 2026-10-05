package com.github.tvbox.osc.ui.theme

/**
 * 玻璃效果参数:固定值,不再开放给用户调。
 *
 * 原版把模糊 / 扭曲 / 通透度 / 色散 四项做成滑块并持久化到 KV,每拖一次都要重建整条玻璃
 * 渲染链(backdrop + RenderEffect)并触发大范围重组。这里收敛成编译期常量:渲染参数在首次
 * 组合时即固定,之后不再有状态读取与重组。
 *
 * 关于流畅度:玻璃确实有代价,成本集中在"色散"(chromaticAberration)。它走
 * `RoundedRectRefractionWithDispersion` 着色器 —— 每个像素对内容层采样 7 次(红/橙/黄/绿/
 * 青/蓝/紫各一次)再加权合成;关掉则退回 `RoundedRectRefraction`,只采样 1 次。
 * 也就是说色散一项就是 7 倍的片元着色开销,在玻璃带区域(顶栏 + 底部导航)每帧都要跑。
 * 因此这里**默认关闭色散**(dispersion = false),保留 blur + lens 的玻璃观感,砍掉最贵的那层。
 *
 * 想彻底关掉玻璃效果(最省电、最跟手):把两个 Enabled 改成 false 即可 —— 调用侧已按
 * `navbarEnabled && SDK >= S` 判断,关掉后自动退回纯色顶栏/导航栏。
 */
object LiquidGlassState {
    val config: LiquidGlassConfig = LiquidGlassConfig(
        navbarEnabled = true,
        controlsEnabled = true,
        blurDp = 20f,
        distortionDp = 30f,
        translucency = 0.5f,
        dispersion = false,
    )
}
