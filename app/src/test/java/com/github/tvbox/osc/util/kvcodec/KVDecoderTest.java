package com.github.tvbox.osc.util.kvcodec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KV 编解码语义单测(avbox-kv-mmkv-spec §5-P0 / §6.1 高风险项):集合、JsonArray、嵌套 Map
 * 与 Hawk 旧行为一致,且失败不再静默。
 *
 * <p>纯 JVM 运行(不依赖 Android / MMKV):{@link KVDecoder} 在本包内不含任何平台依赖。
 */
public class KVDecoderTest {

    private KVDecoder decoder;

    /** 与 App 侧 KVKeySpec 等价的测试注册表(键名用字面量,避免测试依赖 Android 侧常量类) */
    private static final Map<String, Type> TYPES = new HashMap<>();

    static {
        TYPES.put("search_history", new TypeToken<ArrayList<String>>() {
        }.getType());
        TYPES.put("subscribe_list", new TypeToken<ArrayList<String>>() {
        }.getType());
        TYPES.put("live_group_list", TypeToken.get(JsonArray.class).getType());
        TYPES.put("source_card_policy", new TypeToken<HashMap<String, String>>() {
        }.getType());
        TYPES.put("checked_sources_for_search", new TypeToken<HashMap<String, HashMap<String, String>>>() {
        }.getType());
        TYPES.put("api_url", TypeToken.get(String.class).getType());
        TYPES.put("play_type", TypeToken.get(int.class).getType());
        TYPES.put("live_group_index", TypeToken.get(int.class).getType());
    }

    @Before
    public void setUp() {
        decoder = new KVDecoder();
        decoder.setRegistry(key -> {
            Type direct = TYPES.get(key);
            if (direct != null) return direct;
            if (key.startsWith("live_group_index")) return TYPES.get("live_group_index");
            if (key.startsWith("jsRuntime_")) return TYPES.get("api_url");
            return null;
        });
    }

    @Test
    public void stringRoundTrip_keepsLiteralText() {
        String encoded = decoder.encode("hello");
        assertEquals("hello", encoded);
        assertEquals("hello", decoder.decode("api_url", encoded, ""));
    }

    @Test
    public void stringThatLooksLikeJson_isNotMistakenForComplexValue() {
        String tricky = "{\"a\":1}";
        String encoded = decoder.encode(tricky);
        assertEquals(tricky, decoder.decode("api_url", encoded, ""));
    }

    @Test
    public void primitiveRoundTrip() {
        assertEquals(Integer.valueOf(2), decoder.decode("play_type", 2, 0));
        assertEquals(Boolean.TRUE, decoder.decode("play_type", Boolean.TRUE, Boolean.FALSE));
        assertEquals(Float.valueOf(1.5f), decoder.decode("play_type", 1.5f, 0f));
        assertEquals(Long.valueOf(9L), decoder.decode("play_type", 9L, 0L));
    }

    @Test
    public void arrayList_restoresStringElementType() {
        List<String> value = new ArrayList<>();
        value.add("源A\thttp://a");
        value.add("源B\thttp://b");

        String encoded = decoder.encode(value);
        ArrayList<String> restored = decoder.decode("subscribe_list", encoded, new ArrayList<>());

        assertNotNull(restored);
        assertEquals(value, restored);
        // 元素必须是 String 而非 LinkedTreeMap(Hawk 在 gson 2.13+ 的失败表现正是拿不到元素类型)
        assertTrue(restored.get(0) instanceof String);
    }

    @Test
    public void jsonArray_restoresNodeTreeNotList() {
        JsonArray value = JsonParser.parseString("[{\"group\":\"央视\"},{\"group\":\"卫视\"}]").getAsJsonArray();

        String encoded = decoder.encode(value);
        JsonArray restored = decoder.decode("live_group_list", encoded, new JsonArray());

        assertNotNull(restored);
        assertEquals(2, restored.size());
        assertEquals("央视", restored.get(0).getAsJsonObject().get("group").getAsString());
    }

    @Test
    public void flatMap_restoresStringValues() {
        HashMap<String, String> value = new HashMap<>();
        value.put("sourceA", "detail");
        String encoded = decoder.encode(value);

        HashMap<String, String> restored =
                decoder.decode("source_card_policy", encoded, new HashMap<>());

        assertNotNull(restored);
        assertEquals("detail", restored.get("sourceA"));
        assertTrue(restored.get("sourceA") instanceof String);
    }

    @Test
    public void nestedMap_registryTypeRestoresStringValues() {
        HashMap<String, HashMap<String, String>> value = new HashMap<>();
        HashMap<String, String> inner = new HashMap<>();
        inner.put("sourceA", "1");
        value.put("http://api", inner);
        String encoded = decoder.encode(value);

        // 模拟迁移路径:原调用侧默认值 `new HashMap<>()` 的泛型已被擦除,只能靠注册表的显式 Type 恢复。
        // 这里直接把键登记为嵌套 Map 类型,断言内层值不是 LinkedTreeMap 而是 String
        KVDecoder forced = new KVDecoder();
        forced.setRegistry(key -> TYPES.get("checked_sources_for_search"));
        Object restored = forced.decode("checked_sources_for_search", encoded, null);

        assertTrue(restored instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> asMap = (Map<String, Map<String, String>>) restored;
        assertEquals("1", asMap.get("http://api").get("sourceA"));
        assertTrue(asMap.get("http://api").get("sourceA") instanceof String);
    }

    @Test
    public void nestedMap_withoutRegistry_collapsesInnerElementsToNodeTree() {
        HashMap<String, HashMap<String, String>> value = new HashMap<>();
        HashMap<String, String> inner = new HashMap<>();
        inner.put("sourceA", "1");
        value.put("http://api", inner);
        String encoded = decoder.encode(value);

        // 无注册表兜底时,裸 HashMap 泛型已被擦除 ⇒ 内层退化为节点树。
        // 记录该行为:这正是「类型必须集中登记」的原因(spec §7-Q3),而非可接受的长期状态
        KVDecoder bare = new KVDecoder();
        Object restored = bare.decode("checked_sources_for_search", encoded, new HashMap<>());

        assertTrue(restored instanceof Map);
        Object innerValue = ((Map<?, ?>) restored).get("http://api");
        assertTrue(innerValue instanceof Map);
        assertEquals("1", ((Map<?, ?>) innerValue).get("sourceA"));
    }

    @Test
    public void callerConcreteTypeWins_forDynamicKeysOutsideRegistry() {
        // jsRuntime_* 动态键:注册表给 String,调用侧默认值也是 String ⇒ 正常往返
        String encoded = decoder.encode("payload");
        assertEquals("payload", decoder.decode("jsRuntime_abc_def", encoded, ""));
    }

    @Test
    public void searchThreadsCrashRegression_zeroUnwrapsToIntNotDefault() {
        // 崩溃现场的回归:KV 里若真存了 0,解码侧必须老实读出 0(不猜测"0 是否等于缺省"),
        // 因为 0 在这里是业务不变量(搜索线程数下限 16),靠"读默认值"掩盖坏数据只会把问题推迟到调用方
        assertEquals(Integer.valueOf(0), decoder.decode("search_threads", 0, 32));
        // 键不存在时不会走到解码:KVCodec 用 containsKey 提前返回调用方默认值
        assertNull(decoder.decode("search_threads", null, null));
    }

    @Test
    public void unregisteredKeyWithoutDefault_fallsBackToJsonNodeTree() {
        JsonArray value = JsonParser.parseString("[{\"group\":\"央视\"}]").getAsJsonArray();

        // 键未登记、调用侧也没给默认值(RemoteTVBox 那类写法):至少还原成 JSON 节点树,不丢值
        Object restored = decoder.decode("unknown_key", decoder.encode(value), null);
        assertTrue(restored instanceof JsonArray);
        assertEquals("央视", ((JsonArray) restored).get(0).getAsJsonObject().get("group").getAsString());
    }

    @Test
    public void objectDefault_isTreatedAsNoTypeHint_notAsPassthrough() {
        // 回归:`defaultValue` 是 Object 时 `Object.class.isInstance(raw)` 恒为 true,
        // 若照直返回就会把落盘的复杂 JSON 原文(String)当成结果交出去。
        // 正确行为 = 与"没有默认值"等价 ⇒ 走登记表/节点树兜底。
        JsonArray value = JsonParser.parseString("[1,2]").getAsJsonArray();
        Object restored = decoder.decode("unknown_key", decoder.encode(value), new Object());
        assertTrue("复杂值应按节点树还原,而不是返回原始字符串", restored instanceof JsonArray);
    }

    @Test
    public void unregisteredPlainTextKeyWithoutDefault_isReportedNotSilentlyDefaulted() {
        // 键未登记、也没有默认值可判类型、原文还不是复杂值 ⇒ 返回 null,由 KVCodec 打 echo-kv 日志
        assertNull(decoder.decode("unknown_key", "plain", null));
    }

    @Test
    public void quietCopy_reportsNothing_butKeepsSameResult() {
        // KV.get(key)(无默认值)走静默副本:这类用法拿不到期望类型属正常,不该刷错误日志
        KVDecoder quiet = decoder.quiet();
        assertNull(quiet.decode("unknown_key", "plain", null));
        assertEquals("payload", quiet.decode("jsRuntime_abc_def", decoder.encode("payload"), ""));
    }

    @Test
    public void decodeFailure_returnsNullInsteadOfThrowing() {
        // 坏数据:前缀标明是复杂值,内容却不是合法 JSON
        Object restored = decoder.decode("checked_sources_for_search", "\u0001json:{not-json", new HashMap<>());
        assertNull(restored);
    }

    @Test
    public void legacyPlainTextValue_isConvertedByTargetType() {
        // 迁移前的原始文本(无前缀)按目标类型转:线路列表元素存的是纯文本时也要能读出 String
        assertEquals("硬解码", decoder.decode("api_url", "硬解码", ""));
    }

    // ---- 2026-09-13 崩溃回归:JSON 数字被 Gson 解析成 Double,按 int 读会判失败并回落默认值 ----

    @Test
    public void jsonNumberParsedAsDouble_isCoercedToIntTarget() {
        // 实测形态:KV 里存的是 json:0(迁移写入的坏值),读侧要 int ⇒ 必须收敛为 Integer 而不是回落默认值
        assertEquals(Integer.valueOf(0), decoder.decode("play_type", 0, 32));
        assertEquals(Integer.valueOf(3), decoder.decode("play_type", 3, 32));
    }

    @Test
    public void jsonNumberCoercion_coversAllNumericTargets() {
        assertEquals(Integer.valueOf(7), decoder.decode("k", 7, 0));
        assertEquals(Long.valueOf(7L), decoder.decode("k", 7, 0L));
        assertEquals(Float.valueOf(2.5f), decoder.decode("k", 2.5, 0f));
        assertEquals(Double.valueOf(2.5d), decoder.decode("k", 2.5, 0d));
    }

    @Test
    public void intTarget_outOfRangeFallsBackToDefaultInsteadOfTruncating() {
        // 越界值不静默截断:回落调用方默认值(由 KVCodec 返回 default)
        assertNull(decoder.coerceNumber(int.class, ((long) Integer.MAX_VALUE) + 1));
        assertNull(decoder.coerceNumber(Integer.class, Double.MAX_VALUE));
    }

    @Test
    public void stringValueOnNumericTarget_isNotCoerced() {
        // 字符串 "32" 不是数字 ⇒ 不参与数值收敛,交给类型判定(不静默当成 32)
        assertEquals("32", decoder.coerceNumber(int.class, "32"));
    }
}
