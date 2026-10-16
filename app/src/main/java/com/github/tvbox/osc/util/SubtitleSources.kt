package com.github.tvbox.osc.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 字幕源注册表:内置源恒启用（不再摆在设置里）+ 用户自定义源（落在 KV）。
 *
 * <p>自定义源的约定:URL 模板里用 `{kw}` 占位(搜索时会替换为 URL 编码后的关键词),
 * 接口返回 JSON——可以是数组,也可以是 `{"list":[...]}`;每个元素至少要有
 * 字幕文件直链 `url`,可选 `name` 作为显示名。这样任何返回标准 JSON 的字幕站
 * 都能被用户自行接入,不必等内置源支持。
 */
object SubtitleSources {

    const val KEY_ASSRT = "assrt"

    const val KEY_SUBTITLECAT = "subtitlecat"

    const val KEY_XUNLEI = "xunlei"

    data class Custom(val name: String, val urlTemplate: String)

    /**
     * 内置源**恒启用**（2026-10-16：设置页不再列内置源）。
     *
     * <p>为什么撤掉开关：这三个源能不能用是"今天这个站连不连得上"的问题，用户既没有信息判断，
     * 也没有必要判断；摆出来只会变成"看着能选、选了也不知道会怎样"的一行。
     *
     * <p>⚠️ 撤 UI 必须同时把**旧的关闭值作废**：老用户 KV 里可能存着 `{"assrt":false}`，
     * 若继续读它，就会出现"入口没了、源却被永久锁死、还没法恢复"——这正是"删行前先确认它是唯一入口"
     * 那条口径要防的事。所以现在这里不读 KV，`SUBTITLE_SOURCES` 这个键连同注册一起撤掉
     * （残留在 MMKV 里的老值没人读，无害）。
     */
    fun isEnabled(key: String): Boolean = true

    fun custom(): List<Custom> {
        val raw = KV.get(HawkConfig.SUBTITLE_CUSTOM_SOURCES, "")
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (url.isEmpty()) null else Custom(name.ifEmpty { url }, url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 同名视为覆盖:用户重复添加同一名字时不产生重复条目 */
    fun addCustom(name: String, urlTemplate: String) {
        val url = urlTemplate.trim()
        if (url.isEmpty()) return
        val label = name.trim().ifEmpty { url }
        val merged = LinkedHashMap<String, Custom>()
        custom().forEach { merged[it.name] = it }
        merged[label] = Custom(label, url)
        writeCustom(merged.values.toList())
    }

    fun removeCustom(name: String) {
        writeCustom(custom().filterNot { it.name == name })
    }

    private fun writeCustom(items: List<Custom>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("name", item.name).put("url", item.urlTemplate))
        }
        KV.put(HawkConfig.SUBTITLE_CUSTOM_SOURCES, array.toString())
    }
}
