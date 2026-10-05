package com.github.tvbox.osc.player.effect.anime4k

internal data class Anime4kPass(
    val name: String,
    val binds: List<String>,
    val save: String,
    val widthExpr: List<String>,
    val heightExpr: List<String>,
    val components: Int,
    val whenExpr: List<String>,
    val fragmentShader: String,
)

internal data class Anime4kBinding(val name: String, val width: Int, val height: Int)

internal data class Anime4kPlannedPass(
    val pass: Anime4kPass,
    val width: Int,
    val height: Int,
    val writesScreen: Boolean,
    val bindings: List<Anime4kBinding>,
)

internal data class Anime4kBindingSource(val name: String, val target: Int, val width: Int, val height: Int)

internal data class Anime4kPassIo(
    val planned: Anime4kPlannedPass,
    val bindings: List<Anime4kBindingSource>,
    val outputTarget: Int,
    val createdTarget: Boolean,
)

internal object Anime4kShader {

    const val MAIN = "MAIN"

    /** mpv 的 `HOOKED`(当前图像,每次 pass 后指向它的输出、链首等于输入纹理);`NATIVE`(原始视频)天然落到输入纹理 */
    const val HOOKED = "HOOKED"

    /** 绑定来源 = media3 传进来的输入纹理(名字还没有被任何 pass 写过) */
    const val INPUT_BINDING = -1

    /** pass 输出 = 直接画进 media3 的输出 FBO */
    const val SCREEN_TARGET = -1

    fun assignTargets(planned: List<Anime4kPlannedPass>): List<Anime4kPassIo> {
        val latest = HashMap<String, Int>()
        val sizes = HashMap<String, IntArray>()
        val result = ArrayList<Anime4kPassIo>()
        var nextTarget = 0
        planned.forEach { item ->
            val bindings = item.bindings.map { binding ->
                Anime4kBindingSource(
                    name = binding.name,
                    target = latest[binding.name] ?: INPUT_BINDING,
                    width = binding.width,
                    height = binding.height,
                )
            }
            var outputTarget = SCREEN_TARGET
            var created = false
            if (!item.writesScreen) {
                val save = item.pass.save
                val existing = latest[save]
                // 绑 HOOKED 的 pass(Clamp 应用 / Deblur)读的是"当前图像",多半就是要复用的那张纹理
                // ⇒ 一律不复用,避免"自己采样自己"(mpv 那边同名读写分属不同纹理)
                val readsSave = item.bindings.any { it.name == save || it.name == HOOKED }
                val reusable = existing != null && !readsSave &&
                    sizes[save]?.let { it[0] == item.width && it[1] == item.height } == true
                outputTarget = if (reusable) {
                    existing!!
                } else {
                    created = true
                    nextTarget++
                    nextTarget - 1
                }
                latest[save] = outputTarget
                sizes[save] = intArrayOf(item.width, item.height)
                // 「当前图像」只在写回 MAIN 时前进:SAVE LINELUMA / MMKERNEL / conv2d_tf 是辅助纹理,图像不变。
                // 漏改会把辅助纹理当图像读(Deblur 的 Apply 曾把 MMKERNEL 当图像,屏幕显示 DoG 内核)
                if (save == MAIN) latest[HOOKED] = outputTarget
            }
            result.add(Anime4kPassIo(item, bindings, outputTarget, created))
        }
        return result
    }

    private const val DIRECTIVE = "//!"

    private const val PRECISION = "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
        "precision highp float;\n" +
        "#else\n" +
        "precision mediump float;\n" +
        "#endif\n"

    private const val ENTRY = "void main() {\n  gl_FragColor = hook();\n}\n"

    /** 收尾 pass：链内结果比「送屏画布」大时用 2×2 box（缩小时抗锯齿更好） */
    private const val PRESENT_BOX_BODY = "vec4 hook() {\n" +
        "    return (MAIN_tex(MAIN_pos + vec2(-0.5, -0.5) * MAIN_pt)\n" +
        "        + MAIN_tex(MAIN_pos + vec2(0.5, -0.5) * MAIN_pt)\n" +
        "        + MAIN_tex(MAIN_pos + vec2(-0.5, 0.5) * MAIN_pt)\n" +
        "        + MAIN_tex(MAIN_pos + vec2(0.5, 0.5) * MAIN_pt)) * 0.25;\n" +
        "}\n"

    /**
     * 收尾 pass：**轻微缩小**（≤[BOX_DOWNSCALE_RATIO]）或**放大**时用双线性 + **只对亮度**做 4 邻域锐化。
     * - 只动亮度：彩噪/彩边不会被放大（CAS 逐通道 RGB 的已知副作用）；
     * - **钳在「自身 + 4 邻域」的亮度范围内**：结构上不产生新的过亮/过暗极值 ⇒ 不出白边（AMD CAS 的思路）；
     * - 强度走 uniform `uSharpen`（面板滑条，每帧现读，改完即时生效、不必重播）。
     */
    private const val PRESENT_SHARP_BODY = "uniform float uSharpen;\n" +
        "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n" +
        "vec4 hook() {\n" +
        "    vec4 c = MAIN_tex(MAIN_pos);\n" +
        "    float lc = dot(c.rgb, LUMA);\n" +
        "    float ln = dot(MAIN_tex(MAIN_pos + vec2(0.0, -1.0) * MAIN_pt).rgb, LUMA);\n" +
        "    float ls = dot(MAIN_tex(MAIN_pos + vec2(0.0, 1.0) * MAIN_pt).rgb, LUMA);\n" +
        "    float lw = dot(MAIN_tex(MAIN_pos + vec2(-1.0, 0.0) * MAIN_pt).rgb, LUMA);\n" +
        "    float le = dot(MAIN_tex(MAIN_pos + vec2(1.0, 0.0) * MAIN_pt).rgb, LUMA);\n" +
        "    float delta = (lc - (ln + ls + lw + le) * 0.25) * uSharpen;\n" +
        "    float up = max(0.0, max(max(ln, ls), max(lw, le)) - lc);\n" +
        "    float down = max(0.0, lc - min(min(ln, ls), min(lw, le)));\n" +
        "    return vec4(clamp(c.rgb + clamp(delta, -down, up), 0.0, 1.0), c.a);\n" +
        "}\n"

    private val IVEC_DECL = Regex("""(?m)^(\s*)ivec2\s+(\w+)\s*=\s*ivec2\((\w+)\s*\*\s*vec2\(2\.0\)\)\s*;""")

    private val DYNAMIC_INDEX =
        Regex("""(?m)^(\s*)(?:\w+\s+)?(\w+)\s*=\s*([^;]*?)\[(\w+)\.y\s*\*\s*2\s*\+\s*\4\.x\]\s*;""")

    fun parse(text: String): List<Anime4kPass> = splitBlocks(text).map(::buildPass)

    /** 源/画布比值低于此 ⇒ **不做 2x**：2x 的细节大半会被收尾缩放丢掉，白做还慢（1080p 源 + 2240 画布就是这种情形） */
    private const val UPSCALE_MIN_RATIO = 1.35f

    /** 高于此 ⇒ 一段 2x 仍填不满画布，再加一段（共 4x）—— 只有 480p 这类低清源会走到 */
    private const val DOUBLE_UPSCALE_MIN_RATIO = 2.5f

    /** [compose] 的结果：pass 列表 + 实际用了几段放大（供日志判读） */
    internal data class Composed(val passes: List<Anime4kPass>, val upscaleCount: Int)

    /**
     * 拼装一条链的 pass 顺序：
     * `Clamp 统计 → Restore → Deblur(可选) → Clamp 应用 → Upscale ×N`
     *
     * - **Clamp 应用放在第一个放大之前**：对应 mpv 的 `PREKERNEL` 钩子（官方说明：在高光钳制上"所有 shader 执行完后"生效）；
     * - **放大段数由「送屏画布 / 源」的比值决定**：源已接近画布 ⇒ 不做 2x，改由收尾（只亮度锐化）直接放大到画布；
     *   源远小于画布 ⇒ 两段（共 4x）。缺画布（管线还没推尺寸）时按一段处理；
     * - 纯放大档被跳过时整条链会没有 pass ⇒ 退回一段放大（宁可白做，也别退化成纯拷贝）。
     */
    fun compose(
        restore: List<Anime4kPass>,
        upscale: List<Anime4kPass>,
        deblur: List<Anime4kPass>,
        clamp: List<Anime4kPass>,
        inputWidth: Int,
        canvas: IntArray?,
        deblurEnabled: Boolean,
    ): Composed {
        val ratio = canvas?.let { it[0].toFloat() / inputWidth } ?: 1f
        var count = when {
            upscale.isEmpty() -> 0
            canvas == null -> 1
            ratio < UPSCALE_MIN_RATIO -> 0
            ratio >= DOUBLE_UPSCALE_MIN_RATIO -> 2
            else -> 1
        }
        if (count == 0 && restore.isEmpty() && !deblurEnabled && upscale.isNotEmpty()) count = 1
        val passes = ArrayList<Anime4kPass>()
        passes.addAll(clamp.dropLast(1))
        passes.addAll(restore)
        if (deblurEnabled) passes.addAll(deblur)
        passes.addAll(clamp.takeLast(1))
        repeat(count) { passes.addAll(upscale) }
        return Composed(passes, count)
    }

    fun plan(
        texts: List<String>,
        inputWidth: Int,
        inputHeight: Int,
        canvas: IntArray? = null,
    ): List<Anime4kPlannedPass> = planPasses(texts.flatMap(::parse), inputWidth, inputHeight, canvas)

    /**
     * 规划整条链。`canvas` = 显示侧推给管线的输出画布尺寸（[com.github.tvbox.osc.player.effect.PictureEffects.outputCanvas]）。
     * 链末**出到画布尺寸**（= 视频在屏幕上的实际像素数）：否则链的结果会被管线的缩放器再缩放一遍，成果到不了屏幕
     * 画布不存在 / 比源小 / 与源不同比例时回落源尺寸（交给管线等比适应）。
     */
    fun planPasses(
        passes: List<Anime4kPass>,
        inputWidth: Int,
        inputHeight: Int,
        canvas: IntArray? = null,
    ): List<Anime4kPlannedPass> {
        val sizes = HashMap<String, IntArray>()
        sizes[MAIN] = intArrayOf(inputWidth, inputHeight)
        sizes[HOOKED] = intArrayOf(inputWidth, inputHeight)
        val planned = ArrayList<Anime4kPlannedPass>()
        passes.forEach { pass ->
            // 没写 //!WIDTH/HEIGHT 的 pass 继承「当前图像」尺寸(mpv 语义),而不是回落到源尺寸
            val current = sizes[HOOKED] ?: intArrayOf(inputWidth, inputHeight)
            val width = resolve(pass.widthExpr, sizes, current[0])
            val height = resolve(pass.heightExpr, sizes, current[1])
            sizes[pass.save] = intArrayOf(width, height)
            // HOOKED = 当前图像:只有写回 MAIN 的 pass 才让它前进(辅助纹理的 SAVE 不动图像)
            if (pass.save == MAIN) sizes[HOOKED] = intArrayOf(width, height)
            val bindings = pass.binds.map { name ->
                val bound = sizes[name] ?: intArrayOf(width, height)
                Anime4kBinding(name, bound[0], bound[1])
            }
            planned.add(Anime4kPlannedPass(pass, width, height, false, bindings))
        }
        val result = ArrayList(planned)
        val last = result.lastOrNull()
        val target = presentedSize(inputWidth, inputHeight, last, canvas)
        if (last != null && (last.width != target[0] || last.height != target[1])) {
            result.add(
                Anime4kPlannedPass(
                    pass = presentPass(
                        target[0],
                        target[1],
                        box = last.width.toFloat() > target[0] * BOX_DOWNSCALE_RATIO,
                    ),
                    width = target[0],
                    height = target[1],
                    writesScreen = true,
                    bindings = listOf(Anime4kBinding(MAIN, last.width, last.height)),
                ),
            )
        }
        return result.mapIndexed { index, item -> item.copy(writesScreen = index == result.lastIndex) }
    }

    /**
     * 链末输出尺寸：画布不小于源、且与源同比例时**一律出到画布**（1x 档也出：源已接近画布时
     * 不再做 2x，改由收尾的"只亮度锐化"直接放大），否则回落源尺寸。
     */
    private fun presentedSize(
        inputWidth: Int,
        inputHeight: Int,
        last: Anime4kPlannedPass?,
        canvas: IntArray?,
    ): IntArray {
        val source = intArrayOf(inputWidth, inputHeight)
        if (last == null || canvas == null) return source
        val width = canvas[0]
        val height = canvas[1]
        if (width < inputWidth || height < inputHeight) return source
        if (!sameAspect(width, height, inputWidth, inputHeight)) return source
        return intArrayOf(width, height)
    }

    /** 宽高比是否一致（交叉相乘，1% 容差，避免浮点抖动） */
    private fun sameAspect(width: Int, height: Int, otherWidth: Int, otherHeight: Int): Boolean {
        val left = width.toLong() * otherHeight
        val right = otherWidth.toLong() * height
        return kotlin.math.abs(left - right) * 100 <= right
    }

    fun resolveSize(expr: List<String>, widthOf: (String) -> Int, heightOf: (String) -> Int): Int {
        val stack = ArrayList<Int>()
        expr.forEach { token ->
            when {
                token == "*" -> {
                    if (stack.size < 2) return@forEach
                    val b = stack.removeAt(stack.lastIndex)
                    val a = stack.removeAt(stack.lastIndex)
                    stack.add(a * b)
                }

                token.endsWith(".w") -> stack.add(widthOf(token.dropLast(2)))
                token.endsWith(".h") -> stack.add(heightOf(token.dropLast(2)))
                else -> token.toIntOrNull()?.let { stack.add(it) }
            }
        }
        return stack.lastOrNull() ?: 0
    }

    fun adaptToEs2(body: String): String {
        var adapted = IVEC_DECL.replace(body) { match ->
            val indent = match.groupValues[1]
            val index = match.groupValues[2]
            val fraction = match.groupValues[3]
            "${indent}float ${index}x = step(0.5, ${fraction}.x);\n" +
                "${indent}float ${index}y = step(0.5, ${fraction}.y);"
        }
        adapted = DYNAMIC_INDEX.replace(adapted) { match ->
            val indent = match.groupValues[1]
            val target = match.groupValues[2]
            val sample = match.groupValues[3]
            val index = match.groupValues[4]
            "${indent}vec4 ${index}v = ${sample};\n" +
                "${indent}float ${target} = mix(mix(${index}v.x, ${index}v.y, ${index}x)," +
                " mix(${index}v.z, ${index}v.w, ${index}x), ${index}y);"
        }
        return adapted
    }

    /** 收尾用 2×2 box 的最小缩放比：低于它说明只是"轻微缩小"，box 的 2 像素脚印远宽于理想脚印（1.14 倍缩小时宽 1.75 倍）⇒ 白送一次模糊 */
    private const val BOX_DOWNSCALE_RATIO = 1.25f

    private fun presentPass(width: Int, height: Int, box: Boolean): Anime4kPass = Anime4kPass(
        name = "present",
        binds = listOf(MAIN),
        save = MAIN,
        widthExpr = listOf(width.toString()),
        heightExpr = listOf(height.toString()),
        components = 4,
        whenExpr = emptyList(),
        fragmentShader = fragmentShader(
            if (box) PRESENT_BOX_BODY else PRESENT_SHARP_BODY,
            listOf(MAIN),
        ),
    )

    private fun resolve(expr: List<String>, sizes: Map<String, IntArray>, fallback: Int): Int {
        val value = resolveSize(
            expr,
            { sizes[it]?.get(0) ?: fallback },
            { sizes[it]?.get(1) ?: fallback },
        )
        return if (value > 0) value else fallback
    }

    private class Block(val header: List<String>, val body: String)

    private fun splitBlocks(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        var header: MutableList<String>? = null
        var body: StringBuilder? = null
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.startsWith(DIRECTIVE + "DESC") -> {
                    val previous = header
                    if (previous != null) blocks.add(Block(previous, body?.toString().orEmpty()))
                    header = ArrayList<String>().apply { add(line) }
                    body = StringBuilder()
                }

                line.startsWith(DIRECTIVE) -> header?.add(line)
                else -> body?.append(line)?.append('\n')
            }
        }
        val last = header
        if (last != null) blocks.add(Block(last, body?.toString().orEmpty()))
        return blocks
    }

    private fun buildPass(block: Block): Anime4kPass {
        val binds = ArrayList<String>()
        var name = ""
        var save = MAIN
        var widthExpr: List<String> = emptyList()
        var heightExpr: List<String> = emptyList()
        var components = 4
        var whenExpr: List<String> = emptyList()
        block.header.forEach { line ->
            val tokens = line.removePrefix(DIRECTIVE).trim().split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val value = tokens.drop(1)
            when (tokens[0]) {
                "DESC" -> name = value.joinToString(" ")
                "BIND" -> value.firstOrNull()?.let { binds.add(it) }
                "SAVE" -> value.firstOrNull()?.let { save = it }
                "WIDTH" -> widthExpr = value
                "HEIGHT" -> heightExpr = value
                "COMPONENTS" -> components = value.firstOrNull()?.toIntOrNull() ?: components
                "WHEN" -> whenExpr = value
            }
        }
        // 只保留函数体真用到的 BIND:编译器会把没用到的 sampler uniform 优化掉,而 media3 设不存在的
        // uniform 会抛 ⇒ 首帧崩(Clamp_Highlights 第二个统计 pass 就是 BIND HOOKED 却只用 STATSMAX)
        val usedBinds = binds.filter { name ->
            // 别名也要算:"BIND HOOKED 但函数体用 MAIN_texOff"这种写法(Clamp_Highlights 第一个 pass)要留住
            val alias = when (name) {
                HOOKED -> MAIN
                MAIN -> HOOKED
                else -> null
            }
            block.body.contains("${name}_") || (alias != null && block.body.contains("${alias}_"))
        }
        return Anime4kPass(
            name = name,
            binds = usedBinds,
            save = save,
            widthExpr = widthExpr,
            heightExpr = heightExpr,
            components = components,
            whenExpr = whenExpr,
            fragmentShader = fragmentShader(block.body, usedBinds),
        )
    }

    /**
     * 生成片元着色器。**`HOOKED` 与 `MAIN` 互为别名**：mpv 里两者都指"当前图"，官方 shader 存在
     * `BIND HOOKED` 却在函数体里用 `MAIN_texOff(...)` 的写法（`Clamp_Highlights` 第一个 pass 就是），
     * 我们这边"当前图像"只有一个名字，别名指向同一个 uniform 即可；漏了别名 = 生成的 GLSL 里宏未定义、编译必失败。
     */
    private fun fragmentShader(body: String, binds: List<String>): String = buildString {
        append(PRECISION)
        append("varying vec2 vTexCoord;\n")
        val emitted = LinkedHashSet<String>()
        binds.forEach { tex ->
            emitted.add(tex)
            append("uniform sampler2D uTex$tex;\n")
            append("uniform vec2 uPt$tex;\n")
            append("uniform vec2 uSize$tex;\n")
            appendTexMacros(tex, tex)
        }
        binds.forEach { tex ->
            val alias = when (tex) {
                HOOKED -> MAIN
                MAIN -> HOOKED
                else -> null
            }
            if (alias != null && emitted.add(alias)) appendTexMacros(alias, tex)
        }
        val adapted = adaptToEs2(body)
        append(adapted)
        if (!adapted.endsWith("\n")) append('\n')
        append(ENTRY)
    }

    /** 给 [name] 发一套 mpv 风格宏，全部指向 [uniform] 那个绑定（别名与本体共用 uniform） */
    private fun StringBuilder.appendTexMacros(name: String, uniform: String) {
        append("#define ${name}_tex(p) texture2D(uTex$uniform, (p))\n")
        append("#define ${name}_texOff(off) texture2D(uTex$uniform, vTexCoord + (off) * uPt$uniform)\n")
        append("#define ${name}_pos vTexCoord\n")
        append("#define ${name}_pt uPt$uniform\n")
        append("#define ${name}_size uSize$uniform\n")
    }
}
