package com.github.tvbox.osc.util;

import android.content.res.AssetManager;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置 EPG 频道名映射(assets/epg_data.json → 频道名 → {logo, epgid})。
 *
 * <p><b>为什么改成后台懒加载</b>:这个 JSON 有 108KB,原来在 {@code App.onCreate} 里同步
 * 读流 + Gson 解析,完全跑在主线程上 —— 冷启动时白白占掉几十毫秒,而这份数据只有直播页
 * 用到。现在改成:① onCreate 只触发一次后台线程加载;② 加载完成前 {@link #getEpgInfo}
 * 返回 null。
 *
 * <p><b>返回 null 是安全的</b>:调用方 {@code LiveEpgController.getEpg} 的写法是
 * {@code if (epgInfo != null && epgInfo[1].isNotEmpty())},拿不到就退回用频道名本身查询,
 * 直播页不会因为 EPG 未就绪而报错或空白。
 */
public class EpgUtil {

    private static volatile JsonObject epgDoc = null;

    private static volatile HashMap<String, JsonObject> epgHashMap = new HashMap<>();

    /** 保证只启动一次后台加载(线程切换后也可能被多个调用点同时触发) */
    private static final AtomicBoolean loading = new AtomicBoolean(false);

    /**
     * 触发加载。**不阻塞**:立即返回,解析在后台线程完成。
     * 由 {@code App.onCreate} 调用一次。
     */
    public static void init() {
        if (epgDoc != null || !loading.compareAndSet(false, true)) {
            return;
        }
        new Thread(EpgUtil::loadBlocking, "epg-load").start();
    }

    /** 实际解析,只在后台线程跑 */
    private static void loadBlocking() {
        try {
            AssetManager assetManager = AppContextHolder.context().getAssets();
            InputStreamReader inputStreamReader =
                    new InputStreamReader(assetManager.open("epg_data.json"), "UTF-8");
            BufferedReader br = new BufferedReader(inputStreamReader);
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                builder.append(line);
            }
            br.close();
            inputStreamReader.close();
            if (builder.length() == 0) {
                return;
            }
            JsonObject doc = new Gson().fromJson(builder.toString(), (Type) JsonObject.class);
            HashMap<String, JsonObject> map = new HashMap<>();
            JsonElement epgs = doc.get("epgs");
            if (epgs != null) {
                for (JsonElement opt : epgs.getAsJsonArray()) {
                    JsonObject obj = (JsonObject) opt;
                    String name = obj.get("name").getAsString().trim();
                    for (String string : name.split(",")) {
                        map.put(string, obj);
                    }
                }
            }
            // 先发 map 再发 doc:getEpgInfo 只读 map,doc 仅作为"已就绪"标志
            epgHashMap = map;
            epgDoc = doc;
        } catch (IOException e) {
            LOG.e("EpgUtil", e);
        }
    }

    public static String[] getEpgInfo(String channelName) {
        try {
            JsonObject obj = epgHashMap.get(channelName);
            if (obj != null) {
                return new String[] {
                        obj.get("logo").getAsString(),
                        obj.get("epgid").getAsString()
                };
            }
        } catch (Exception ex) {
            LOG.e("EpgUtil", ex);
        }
        return null;
    }
}
