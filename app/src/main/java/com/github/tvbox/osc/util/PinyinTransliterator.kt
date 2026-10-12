package com.github.tvbox.osc.util

import android.icu.text.Transliterator
import java.util.Locale

/**
 * 汉字 → 拼音(依赖平台 ICU,**不引入任何第三方库**)。
 *
 * <p>## 为什么用 `android.icu` 而不是第三方拼音库
 * `android.icu.text.Transliterator` 从 **API 24** 起可用,而本仓 minSdk 正是 24 ——
 * 等于"零依赖、零体积代价"就能拿到完整的汉字转拼音能力。引第三方库(如 tiny-pinyin)
 * 要带上几千字的码表,而我们在做体积优化,不该为这个功能反向增加包体。
 *
 * <p>## 两级转换
 * 1. `Han-Latin`:汉字 → 带声调符号的拼音,音节之间以空格分隔(`汉月` → `hàn yuè`);
 * 2. `Latin-ASCII`:去掉声调符号(`hàn yuè` → `han yue`)。
 *
 * 结果保留**音节间空格**,这是 [PinyinQuery.initials] 取首字母的前提 ——
 * 压紧了就切不出音节边界,首字母匹配(`hy` 打《汉月》)会失效。
 *
 * <p>## 线程安全
 * ICU 的 `Transliterator` 实例**不是线程安全的**。搜索会在多个 IO 协程里并发调用,
 * 所以用 [ThreadLocal] 各持一份。`getInstance` 本身走 ICU 内部缓存,创建代价可控。
 *
 * <p>## 失败降级
 * 任何异常(个别 ROM 裁掉了 ICU 数据)一律返回空串,调用方按"这个片名没有拼音"处理 ——
 * 拼音搜索降级为不可用,**绝不影响中文搜索**。
 */
object PinyinTransliterator {

    /** 汉字 → 带声调拼音(音节空格分隔) */
    private const val HAN_TO_LATIN = "Han-Latin"

    /** 去声调:把 ā á ǎ à 等折回 a */
    private const val LATIN_TO_ASCII = "Latin-ASCII"

    private val hanToLatin = ThreadLocal.withInitial {
        runCatching { Transliterator.getInstance(HAN_TO_LATIN) }.getOrNull()
    }
    private val latinToAscii = ThreadLocal.withInitial {
        runCatching { Transliterator.getInstance(LATIN_TO_ASCII) }.getOrNull()
    }

    /**
     * 汉字 → 拼音(去声调、小写、音节以空格分隔)。
     *
     * @return 如 `"han yue"`;输入为空 / 无汉字 / ICU 不可用时返回 `""`
     */
    fun toPinyin(text: String?): String {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        // 全 ASCII 的输入(纯英文片名)没有汉字可转,直接返回空 —— 它的拼音就是它自己,
        // 不需要索引(用户直接敲英文即可命中,走的是常规匹配)。
        if (raw.all { it.code < 128 }) return ""
        return try {
            val withTones = hanToLatin.get()?.transliterate(raw) ?: return ""
            val plain = latinToAscii.get()?.transliterate(withTones) ?: withTones
            normalizeSyllables(plain)
        } catch (th: Throwable) {
            LOG.d("PinyinTransliterator", "toPinyin failed, pinyin disabled for this title: " + th.message)
            ""
        }
    }

    /**
     * 规范化音节串:小写、折叠空白、剔除 ICU 可能带出的杂音字符。
     *
     * <p>ICU 的 Han-Latin 对**非汉字**(数字、英文、标点)会原样保留,
     * 于是 `"3体"` 会得到 `"3 ti"`。这里保留它们 —— 用户在拼音里夹数字是常见的
     * (番号、季数),丢掉反而更差。
     */
    private fun normalizeSyllables(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastWasSpace = true
        for (c in raw.lowercase(Locale.ROOT)) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> {
                    sb.append(c)
                    lastWasSpace = false
                }
                // 撇号是 ICU 给某些音节加的隔音符号(xi'an):保留会破坏 compact 匹配,
                // 直接当作音节分隔处理(与空格等价)
                c == '\'' -> {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
                c.isWhitespace() || c == '-' -> {
                    if (!lastWasSpace && sb.isNotEmpty()) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
                // 其余(标点/声调残留/未知符号)一律丢弃
                else -> {}
            }
        }
        return sb.toString().trim()
    }
}
