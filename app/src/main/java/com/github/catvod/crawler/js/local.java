package com.github.catvod.crawler.js;

import com.github.tvbox.osc.util.LOG;
import androidx.annotation.Keep;
import com.github.tvbox.osc.util.KV;
import com.whl.quickjs.wrapper.Function;

public class local {@Keep@Function
    public void delete(String str, String str2) {
        try {
            KV.delete("jsRuntime_" + str + "_" + str2);
        } catch (Exception e) {
            LOG.e("local", e);
        }
    }@Keep@Function
    public String get(String str, String str2) {
        try {
            return KV.get("jsRuntime_" + str + "_" + str2, "");
        } catch (Exception e) {
            // BugReview P3:原实现删的是 str,坏数据残留;应删完整 key
            KV.delete("jsRuntime_" + str + "_" + str2);
            return str2;
        }
    }@Keep@Function
    public void set(String str, String str2, String str3) {
        try {
            // JS 运行时缓存此前**无上限、无过期**,随订阅源数量单调增长(2026-10-14 收口)。
            // 超限时整批丢:这些是**可再生的编译产物**,下次加载该源会重新写回 —— 与 spider 自写的
            // cache_*(可能存登录态/token,绝不在清理范围内)性质不同。
            if (++sWritesSinceCheck >= RUNTIME_CACHE_CHECK_EVERY) {
                sWritesSinceCheck = 0;
                java.util.List<String> keys = KV.keys("jsRuntime_");
                if (keys.size() > RUNTIME_CACHE_MAX_KEYS) {
                    for (String key : keys) KV.delete(key);
                    LOG.i("jsRuntime cache trimmed keys=" + keys.size());
                }
            }
            KV.put("jsRuntime_" + str + "_" + str2, str3);
        } catch (Exception e) {
            LOG.e("local", e);
        }
    }

    /** 检查按写次数节流:MMKV 的 allKeys() 每次都取整张键表,放在每条写入上太贵 */
    private static final int RUNTIME_CACHE_CHECK_EVERY = 64;
    private static final int RUNTIME_CACHE_MAX_KEYS = 800;
    private static int sWritesSinceCheck = 0;
}