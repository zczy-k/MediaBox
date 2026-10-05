package com.github.tvbox.osc.util.kv;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.kvcodec.KVDecoder;
import com.github.tvbox.osc.util.kvcodec.KVLog;
import com.tencent.mmkv.MMKV;

/**
 * 业务侧 KV 门面内部使用的 Android 胶水:MMKV 取原始值 + `echo-kv` 日志,类型恢复委托给
 * {@link KVDecoder}(纯 JVM 核心,单测覆盖集合/嵌套泛型语义);复杂键的元素类型由
 * {@link KVKeySpec} 集中登记。
 *
 * <p>键不存在属正常路径不打日志;键存在却解不出目标类型则打 `echo-kv` 日志并返回默认值 ——
 * 这是对旧 Hawk 实现"失败静默"的直接修正(spec §4.6)。
 */
public final class KVCodec {

    /** 编解码核心单例,类型登记表在类初始化时装上 */
    private static final KVDecoder DECODER = new KVDecoder();

    static {
        KVLog.setSink(LOG::e);
        DECODER.setRegistry(new KVKeySpec());
        // 事件级自检:确认 MMKV 起来后登记表装载成功(排查"KV 全读不出"时看这一行在不在)
        LOG.i("echo-kv type-registry keys=" + KVKeySpec.registeredCount());
    }

    private KVCodec() {
    }

    @NonNull
    public static String encode(@NonNull Object value) {
        return DECODER.encode(value);
    }

    /**
     * 读取并恢复类型。
     *
     * <p>键不存在 → 返回 defaultValue(正常路径,不打日志);键存在但解不出目标类型 → 返回 defaultValue,
     * 由 {@link KVDecoder} 统一留痕。{@code KV.get(key)} 这类"没有默认值"的用法走静默副本:
     * 它本来就以 null 表示"读不到",不该刷错误日志。
     *
     * <p>⚠️ 存在性判定必须用 {@code containsKey}:**MMKV 的 `getValueSize` 对不存在的键返回 0 而非 -1**
     * (底层是 `size_t`,见 `Core/MMKV.cpp` 的 `getValueSize`),拿它写 `&lt; 0` 会变成"永远不成立" ——
     * 后果是每个不存在的键都带着 `raw=null` 走进解码器刷一条 type-mismatch 错误
     * (2026-09-13 实测:一轮启动刷出 2.9 万行,把有效日志全淹了)。
     */
    @Nullable
    public static <T> T decode(@NonNull String key, @NonNull MMKV store, @Nullable T defaultValue) {
        if (!store.containsKey(key)) return defaultValue; // 键不存在属正常路径,不打日志
        String raw = store.decodeString(key);
        if (raw == null) return defaultValue; // 存在但取不到字符串值:按"读不到"处理,同样不刷日志
        KVDecoder decoder = defaultValue == null ? DECODER.quiet() : DECODER;
        T parsed = decoder.decode(key, raw, defaultValue);
        return parsed == null ? defaultValue : parsed;
    }
}
