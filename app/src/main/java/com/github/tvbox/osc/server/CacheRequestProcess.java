package com.github.tvbox.osc.server;

import android.text.TextUtils;

import com.github.tvbox.osc.util.KV;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class CacheRequestProcess implements RequestProcess {

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return fileName != null && fileName.startsWith("/cache");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        if (params == null) params = session.getParms();
        if (files != null && !files.isEmpty()) params.putAll(files);
        String action = params.get("do");
        String key = params.get("key");
        if (TextUtils.isEmpty(key)) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "");
        String cacheKey = getKey(params.get("rule"), key);
        if ("get".equals(action)) return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, KV.get(cacheKey, ""));
        if ("set".equals(action)) {
            String value = params.get("value");
            KV.put(cacheKey, value == null ? "" : value);
        }
        if ("del".equals(action)) KV.delete(cacheKey);
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "OK");
    }

    private String getKey(String rule, String key) {
        return "cache_" + (TextUtils.isEmpty(rule) ? "" : rule + "_") + key;
    }
}
