package com.github.tvbox.osc.util;

import android.content.Context;

/**
 * 应用上下文的最小入口。
 *
 * <p>动机:util / data / server / catvod 拿 Context 时若直接引用 `base.App`,就让下层反向依赖 Application 类,
 * 包级双向依赖(`base ⇄ util`、`base ⇄ data`、`base ⇄ server`、`catvod.crawler ⇄ base`)由此产生。
 * 这里只暴露 application context,由 `App.onCreate` 注入一次。
 *
 * <p>与 `App.getInstance()` 同语义:未就绪(单测 / 极早调用)时为 null,调用方需自行判空。
 */
public final class AppContextHolder {
    private static volatile Context appContext;

    private AppContextHolder() {
    }

    /** 由 {@code App} 在 onCreate 最早处调用(必须先于任何读 Context 的 util 调用) */
    public static void install(Context context) {
        appContext = context.getApplicationContext();
    }

    public static Context context() {
        return appContext;
    }
}
