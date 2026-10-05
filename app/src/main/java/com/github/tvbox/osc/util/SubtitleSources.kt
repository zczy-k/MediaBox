package com.github.tvbox.osc.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 字幕源注册表:内置源的启用开关 + 用户自定义源,两者都落在 KV。
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

    data class BuiltIn(val key: String, val name: String)

    data class Custom(val name: String, val urlTemplate: String)

    /** 内置源;顺序即搜索发起顺序 */
    val builtIns: List<BuiltIn> = listOf(
        BuiltIn(KEY_ASSRT, "Assrt"),
        BuiltIn(KEY_SUBTITLECAT, "SubtitleCat"),
        BuiltIn(KEY_XUNLEI, "迅雷字幕"),
    )

    /** 未登记过时视为启用,避免老用户升级后所有源都被判成关闭 */
    fun isEnabled(key: String): Boolean {
        val raw = KV.get(HawkConfig.SUBTITLE_SOURCES, "")
        if (raw.isNullOrBlank()) return true
        return try {
            JSONObject(raw).optBoolean(key, true)
        } catch (e: Exception) {
            true
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        val obj = readObject()
        obj.put(key, enabled)
        KV.put(HawkConfig.SUBTITLE_SOURCES, obj.toString())
    }

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

    private fun readObject(): JSONObject {
        val raw = KV.get(HawkConfig.SUBTITLE_SOURCES, "")
        return try {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }
}
