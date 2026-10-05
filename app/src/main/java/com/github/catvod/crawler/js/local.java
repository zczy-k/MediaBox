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
            KV.put("jsRuntime_" + str + "_" + str2, str3);
        } catch (Exception e) {
            LOG.e("local", e);
        }
    }
}