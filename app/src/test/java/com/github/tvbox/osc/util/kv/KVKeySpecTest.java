package com.github.tvbox.osc.util.kv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class KVKeySpecTest {

    private final KVKeySpec spec = new KVKeySpec();

    @Test
    public void registeredListKey_keepsElementTypeSignature() {
        Type listType = spec.typeOf("subscribe_list");
        assertNotNull("订阅列表必须登记类型", listType);
        assertEquals(ArrayList.class, TypeToken.get(listType).getRawType());
        // 关键:元素类型是 String 而不是被擦成 Object
        assertTrue("泛型签名丢失:实际 " + listType,
                listType.toString().contains("java.lang.String"));
    }

    @Test
    public void registeredNestedMapKey_keepsBothTypeArguments() {
        Type nested = spec.typeOf("checked_sources_for_search");
        assertNotNull("嵌套泛型键必须登记类型", nested);
        assertEquals(HashMap.class, TypeToken.get(nested).getRawType());
        String text = nested.toString();
        // 嵌套泛型必须两层都在,否则内层会解成 LinkedTreeMap
        assertTrue("嵌套泛型签名丢失: " + text, text.contains("HashMap<java.lang.String, java.util.HashMap<java.lang.String, java.lang.String>>"));
    }

    @Test
    public void jsonArrayKey_isRegisteredAsJsonArrayNotList() {
        Type jsonArrayType = spec.typeOf("live_group_list");
        assertNotNull("直播分组必须登记类型", jsonArrayType);
        assertEquals(JsonArray.class, TypeToken.get(jsonArrayType).getRawType());
    }

    @Test
    public void primitiveKeys_resolveToTheirBoxedTypes() {
        assertEquals(TypeToken.get(Integer.class).getType(), spec.typeOf("play_type"));
        assertEquals(TypeToken.get(Boolean.class).getType(), spec.typeOf("incognito"));
        assertEquals(TypeToken.get(Boolean.class).getType(), spec.typeOf("nav_live_hidden"));
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("api_url"));
    }

    @Test
    public void dynamicKeyFamilies_resolveByPrefix() {
        // live_group_index_<直播源地址> → int
        assertEquals(TypeToken.get(Integer.class).getType(), spec.typeOf("live_group_index_http://example.com/tv"));
        // jsRuntime_* → String
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("jsRuntime_spiderA_cookie"));
        // cache_* → String(HTTP 缓存接口,当前无调用方但登记保留)
        assertEquals(TypeToken.get(String.class).getType(), spec.typeOf("cache_rule_key"));
    }

    @Test
    public void newlyAddedSettingsKey_isRegistered() {
        // 禁用手势控制(2026-09-13 新增)必须登记,否则读取要退回"调用侧默认值兜底"
        Map<String, Type> unusedGuard = new HashMap<>();
        assertTrue(unusedGuard.isEmpty());
        assertEquals(TypeToken.get(Boolean.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.GESTURE_CONTROL_DISABLED));
    }

    @Test
    public void exoVideoDynamicScheduling_isRegistered() {
        // 未登记的键会静默回落调用侧默认值 ⇒ 隐藏开关"改了没反应",必须锁住登记
        assertEquals(TypeToken.get(Boolean.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.EXO_VIDEO_DYNAMIC_SCHEDULING));
    }

    @Test
    public void collectColumns_isRegistered() {
        // 收藏页布局(2026-09-30 新增):漏登记即"选了三列还是双列"(读取侧静默回落默认值)
        assertEquals(TypeToken.get(Integer.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.COLLECT_COLUMNS));
    }

    @Test
    public void homeColumns_isRegistered() {
        // 首页布局(2026-10-04 新增):漏登记即"选了双列还是三列"(读取侧静默回落默认值)
        assertEquals(TypeToken.get(Integer.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.HOME_COLUMNS));
    }

    @Test
    public void homePosterRatioMode_isRegisteredAsString() {
        assertEquals(TypeToken.get(String.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.HOME_POSTER_RATIO_MODE));
    }

    @Test
    public void pictureParamKeys_areRegistered() {
        // 画质参数的每一条都要登记:漏一条即"滑条拖了没反应"(读取侧静默回落默认值)
        assertEquals(TypeToken.get(String.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_PRESET));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_SATURATION));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_CONTRAST));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_BRIGHTNESS));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_GAMMA));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_HUE));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_TEMPERATURE));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_SHARPNESS));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.PICTURE_SHADOW_LIFT));
    }

    @Test
    public void anime4kKeys_areRegistered() {
        assertEquals(TypeToken.get(String.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.ANIME4K_TIER));
        assertEquals(TypeToken.get(Boolean.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.ANIME4K_ENABLED));
        assertEquals(TypeToken.get(Float.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.ANIME4K_SHARPEN));
        assertEquals(TypeToken.get(Boolean.class).getType(),
                spec.typeOf(com.github.tvbox.osc.util.HawkConfig.ANIME4K_DEBLUR));
    }

    @Test
    public void liveWebHeader_roundTripDecodesAsStringMap() {
        com.github.tvbox.osc.util.kvcodec.KVDecoder decoder =
                new com.github.tvbox.osc.util.kvcodec.KVDecoder();
        decoder.setRegistry(spec);
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", "Mozilla/5.0");
        header.put("Referer", "http://example.com/");
        String raw = decoder.encode(header);
        Object decoded = decoder.decode("live_web_header", raw, null);
        assertTrue("应解出 Map,实际 " + (decoded == null ? "null" : decoded.getClass()), decoded instanceof Map);
        assertEquals("Mozilla/5.0", ((Map<?, ?>) decoded).get("User-Agent"));
        assertEquals("http://example.com/", ((Map<?, ?>) decoded).get("Referer"));
    }

    @Test
    public void unknownKey_returnsNull() {
        org.junit.Assert.assertNull(spec.typeOf("definitely_not_a_registered_key"));
    }
}
