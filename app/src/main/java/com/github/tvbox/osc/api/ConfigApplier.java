package com.github.tvbox.osc.api;

import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 把配置 JSON 的规则段物化到全局表:嗅探规则/广告拦截/DNS/解析器列表 */
final class ConfigApplier {

    private ConfigApplier() {
    }

    /** 嗅探规则(rules):host 规则/过滤、广告正则、click 脚本与 exclude 排除 */
    static void applyHostRules(JsonObject infoJson) {
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                //嗅探过滤规则
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                //广告过滤规则
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    ArrayList<String> ads = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        String regex = one.getAsString();
                        if (M3u8.isAd(regex)) ads.add(regex);
                        else rule.add(regex);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                        VideoParseRuler.addHostRegex(host, ads);
                    }
                }
                //嗅探脚本规则 如 click
                if (obj.has("hosts") && obj.has("script")) {
                    ArrayList<String> scripts = new ArrayList<>();
                    JsonArray scriptArray = obj.getAsJsonArray("script");
                    for (JsonElement one : scriptArray) {
                        String script = one.getAsString();
                        scripts.add(script);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostScript(host, scripts);
                    }
                }
                //排除不嗅探的 URL 条件(fongmi 规则的 exclude):命中即否决,优先于内置嗅探正则
                //字段类型写错时忽略该条,不能让整份配置解析失败(同 doh 的兜底态度)
                if (obj.has("hosts") && obj.has("exclude")
                        && obj.get("hosts").isJsonArray() && obj.get("exclude").isJsonArray()) {
                    ArrayList<String> excludes = new ArrayList<>();
                    for (JsonElement one : obj.getAsJsonArray("exclude")) {
                        excludes.add(one.getAsString());
                    }
                    if (!excludes.isEmpty()) {
                        for (JsonElement one : obj.getAsJsonArray("hosts")) {
                            VideoParseRuler.addHostExclude(one.getAsString(), excludes);
                        }
                    }
                }
            }
        }
    }

    /** DNS over HTTPS(doh):接口把它写成非数组或格式异常时视为未提供,退回内置列表 */
    static void applyDoh(JsonObject infoJson) {
        String dohJson = "";
        if (infoJson.has("doh")) {
            // 接口可能把 doh 写成非数组(或格式异常):此时视为未提供,退回内置列表,不让整个配置加载挂掉
            try {
                dohJson = infoJson.getAsJsonArray("doh").toString();
            } catch (Exception e) {
                LOG.e("ApiConfig", e);
            }
        }
        OkGoHelper.applyDohConfig(dohJson);
    }

    /** 追加的广告拦截(ads) */
    static void applyAds(JsonObject infoJson) {
        if(infoJson.has("ads")){
            for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                if(!AdBlocker.hasHost(host.getAsString())){
                    AdBlocker.addAdHost(host.getAsString());
                }
            }
        }
    }

    /** 解析地址(parses):只做构造,超级解析与默认解析的选择由调用方负责 */
    static List<ParseBean> parseParseBeans(JsonObject infoJson) {
        List<ParseBean> parseBeans = new ArrayList<>();
        if (infoJson.has("parses")) {
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = obj.has("ext") ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeans.add(pb);
            }
        }
        return parseBeans;
    }

}
