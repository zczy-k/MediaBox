package com.github.tvbox.osc.util.kvcodec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

/**
 * KV 值的纯 JVM 编解码核心(见 skill/avbox-kv-mmkv-spec.md §4.3):不依赖 Android,可单测 ——
 * 集合语义对齐是本次迁移最大的风险点(spec §6.1),必须有可重复执行的验证手段。
 *
 * <p>String 原样落盘;集合 / Map / JsonArray / 任意对象 Gson 文本化(带类型前缀),读侧按
 * 「键 → 类型」注册表恢复元素类型。**绝不使用匿名 TypeToken 捕获类型变量** —— 该写法在 gson 2.13+
 * 直接抛 IllegalArgumentException,正是 Hawk 集合数据读不出的根因。
 *
 * <p>读侧类型优先级:
 * ① 调用侧默认值携带的具体类型(`KV.get("k", new ArrayList&lt;String&gt;())` 走这条);
 * ② 登记表登记的显式 Type(spec §7-Q3),用于默认值为 null / Object 这类判不出类型的场景;
 * ③ 两者皆无但原文是复杂值时,退化为 JSON 节点树还原(只保证不丢值),并打日志。
 */
public class KVDecoder {

    private static final Gson GSON = new Gson();
    /** 复杂值的文本前缀:让"坏数据"与"本来就存这个字符串"可区分 */
    private static final String JSON_PREFIX = "\u0001json:";

    /** 键 → 显式类型登记表(实现:`util/kv/KVKeySpec`) */
    public interface TypeRegistry {
        @Nullable
        Type typeOf(@NonNull String key);
    }

    private volatile TypeRegistry registry;
    /** 静默模式:见 {@link #quiet()} */
    private final boolean quiet;
    /** 静默副本(延迟创建并缓存 —— {@code KV.get(key)} 是热路径,不能每次读都 new 一个) */
    private volatile KVDecoder quietInstance;

    public KVDecoder() {
        this(false);
    }

    private KVDecoder(boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * 静默副本:用于 {@code KV.get(key)} 这类**没有默认值**的读取 —— 它拿不到期望类型属于正常用法,
     * 且本身就以 null 表示"读不到",不该刷错误日志。返回的副本与原实例共享登记表。
     */
    @NonNull
    public KVDecoder quiet() {
        KVDecoder copy = quietInstance;
        if (copy == null) {
            copy = new KVDecoder(true);
            copy.registry = registry;
            quietInstance = copy;
        }
        return copy;
    }

    public void setRegistry(@Nullable TypeRegistry typeRegistry) {
        registry = typeRegistry;
        KVDecoder copy = quietInstance;
        if (copy != null) copy.registry = typeRegistry;
    }

    /** 查登记表(内部使用;外部只需要 {@link #resolveType(String, Class)}) */
    @Nullable
    private Type registeredType(@NonNull String key) {
        TypeRegistry current = registry;
        return current == null ? null : current.typeOf(key);
    }

    /** String / 基本类型之外的复杂值统一走这里(调用方保证 value 非 null) */
    @NonNull
    public String encode(@NonNull Object value) {
        return value instanceof String ? (String) value : JSON_PREFIX + GSON.toJson(value);
    }

    /**
     * 用 MMKV 取回的原始对象恢复出目标类型;失败返回 null,由调用方决定日志与默认值。
     *
     * @param raw          落盘原文(String,或基本类型经 MMKV 原生解码后的对象)
     * @param defaultValue 调用侧默认值(携带期望类型,可为 null)
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T decode(@NonNull String key, @Nullable Object raw, @Nullable T defaultValue) {
        // 默认值为 Object 时带不来任何类型信息,与"没有默认值"等价 —— 必须走登记表/节点树兜底,
        // 否则 `Object.class.isInstance(raw)` 恒为 true,会把落盘的复杂 JSON 原文当结果返回
        Class<?> wanted = (defaultValue == null || defaultValue.getClass() == Object.class)
                ? null : defaultValue.getClass();
        // String 与基本类型:MMKV 原生解码结果就是目标类型,直接返回
        if (wanted != null && wanted.isInstance(raw)) return (T) raw;

        Type type = resolveType(key, wanted);
        Object parsed;
        if (type == null) {
            // 判不出目标类型:原文是复杂值时至少还原成 JSON 节点树,不丢值;否则留痕回落默认值
            String json = jsonTextOf(raw);
            parsed = json == null ? null : GSON.fromJson(json, JsonElement.class);
            if (parsed == null) {
                report("echo-kv type-unresolved key=" + key + " raw=" + typeName(raw)
                        + " default=" + (wanted == null ? "null" : wanted.getName()));
                return null;
            }
            return (T) parsed;
        }
        try {
            parsed = parse(raw, type);
        } catch (Throwable e) {
            report("echo-kv decode-failed key=" + key + " type=" + type + " raw=" + describe(raw) + " " + e);
            return null;
        }
        if (type instanceof Class) {
            parsed = coerceNumber((Class<?>) type, parsed); // JSON 数字统一是 Double,按目标类型收敛
        }
        if (assignable(type, parsed)) return (T) parsed;

        report("echo-kv type-mismatch key=" + key + " expected=" + type + " actual=" + typeName(parsed)
                + " raw=" + describe(raw));
        return null;
    }

    /**
     * 把 Gson 解析出的数字收敛到目标数值类型。
     *
     * <p>必要性:Gson 反序列化"光秃秃的数字 token"时一律给 Double(`parse("[32]")` → `Double`),于是
     * `KV.get(SEARCH_THREADS, 32)` 这种 int 读取会因 `int.class.isInstance(Double)` 为 false 而判失败、
     * 回落默认值 —— 极隐蔽(值看着对、类型悄悄换掉)。这里显式按目标类型转换,并对超范围值降级为 null
     * (回落调用方默认值),避免 `Integer.MAX_VALUE + 1` 这类静默截断。
     */
    @Nullable
    static Object coerceNumber(@NonNull Class<?> wanted, @Nullable Object value) {
        if (!(value instanceof Number)) return value;
        Number number = (Number) value;
        long longValue = number.longValue();
        double doubleValue = number.doubleValue();
        if (wanted == int.class || wanted == Integer.class) {
            return (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) ? null : (int) longValue;
        }
        if (wanted == long.class || wanted == Long.class) {
            // 浮点值超出 long 精度时 longValue() 会饱和到 MAX/MIN,用 double 边界拦掉
            return (doubleValue < Long.MIN_VALUE || doubleValue > Long.MAX_VALUE) ? null : longValue;
        }
        if (wanted == short.class || wanted == Short.class) {
            return (longValue < Short.MIN_VALUE || longValue > Short.MAX_VALUE) ? null : (short) longValue;
        }
        if (wanted == byte.class || wanted == Byte.class) {
            return (longValue < Byte.MIN_VALUE || longValue > Byte.MAX_VALUE) ? null : (byte) longValue;
        }
        if (wanted == float.class || wanted == Float.class) return number.floatValue();
        if (wanted == double.class || wanted == Double.class) return doubleValue;
        return value;
    }

    /** 目标类型:调用侧默认值判得出具体类型就用它,否则查登记表(见类注释优先级) */
    @Nullable
    public Type resolveType(@NonNull String key, @Nullable Class<?> wanted) {
        if (wanted != null && wanted != Object.class) return TypeToken.get(wanted).getType();
        return registeredType(key);
    }

    /** 已按显式类型落盘的复杂值直接按该类型反序列化;否则按目标类型从原始值重编码(类型转换) */
    @Nullable
    Object parse(@Nullable Object raw, @NonNull Type type) {
        String json = jsonTextOf(raw);
        if (json != null) return GSON.fromJson(json, type);
        if (raw == null) return null;
        // MMKV 原生解码出的值(int/long/bool/String)已经是目标类型或只差数值收窄,直接交给 coerceNumber 判定;
        // 走 Gson 的 toJson→fromJson 只会把标量写成非法 JSON(如 long 2 → "2" 被当 JSON 数字解析后仍是 Double)
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof String) return raw;
        return GSON.fromJson(GSON.toJson(raw), type);
    }

    /** @return 复杂值原文;raw 不是带前缀的文本时返回 null */
    @Nullable
    private static String jsonTextOf(@Nullable Object raw) {
        if (!(raw instanceof String)) return null;
        String text = (String) raw;
        return text.startsWith(JSON_PREFIX) ? text.substring(JSON_PREFIX.length()) : null;
    }

    static boolean assignable(@NonNull Type type, @Nullable Object value) {
        if (value == null) return false;
        Class<?> raw = TypeToken.get(type).getRawType();
        if (raw.isInstance(value)) return true;
        // 泛型集合/映射无法用 isInstance 校验元素类型:只校验到"是集合/映射"这一级,元素类型由注册表保证
        if (Collection.class.isAssignableFrom(raw)) return value instanceof Collection;
        if (Map.class.isAssignableFrom(raw)) return value instanceof Map;
        if (JsonElement.class.isAssignableFrom(raw)) return value instanceof JsonElement;
        return false;
    }

    private void report(@NonNull String message) {
        if (!quiet) KVLog.e(message);
    }

    @NonNull
    private static String typeName(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    /** 诊断用:类名 + 内容摘要(坏值时能一眼看出"存进去的到底是什么") */
    @NonNull
    private static String describe(@Nullable Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value);
        if (text.length() > 60) text = text.substring(0, 60) + "…";
        return value.getClass().getSimpleName() + "(" + text + ")";
    }
}
