package com.github.tvbox.osc.player.usecase;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;

import org.json.JSONArray;
import org.json.JSONException;

/**
 * 播放器切换 / URL 工具 / 旁路资源停止 用例
 * （从 VodController:1681-1908 剥离，Compose 化改造 阶段 0）。
 */
public final class PlayerSwitchUseCase {

    private PlayerSwitchUseCase() {
    }

    /**
     * 自动重试的"换内核"阶梯:内核只剩 EXO,没有可切的目标 —— 恒返回 true(跳过),
     * 让上层阶梯继续走换线路。
     */
    public static boolean switchPlayer() {
        return true;
    }

    public static String encodeUrl(String url) {
        try {
            return java.net.URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    public static String firstUrlByArray(String url) {
        try {
            JSONArray urlArray = new JSONArray(url);
            for (int i = 0; i < urlArray.length(); i++) {
                String item = urlArray.getString(i);
                if (item.contains("http")) {
                    url = item;
                    break; // 找到第一个立即终止循环
                }
            }
        } catch (JSONException e) {
            LOG.d("PlayerSwitchUseCase", "url is not a json array, keep raw");
        }
        return url;
    }

    public static void stopOther() {
        Thunder.stop(false);//停止磁力下载
        Jianpian.finish();//停止p2p下载
        App.getInstance().setDashData(null);
    }
}
