package com.github.tvbox.osc.util

/** 订阅地址查询(KV:订阅列表每项 `名字\t地址`,配置历史每项纯地址):判断"记录所属的订阅还能不能切回去" */
object SubscribeList {

    private const val Split = "\t"

    /** 点播侧全部可切回的地址:订阅列表 + 配置历史(手输过没存进列表的源也在历史里) */
    fun vodUrls(): Set<String> = parseNamed(HawkConfig.SUBSCRIBE_LIST) + raw(HawkConfig.API_HISTORY)

    private fun parseNamed(key: String): Set<String> =
        KV.get(key, ArrayList<String>())
            .map { value ->
                val index = value.indexOf(Split)
                if (index < 0) value else value.substring(index + Split.length)
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun raw(key: String): Set<String> =
        KV.get(key, ArrayList<String>())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
