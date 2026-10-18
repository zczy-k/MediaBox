package com.github.tvbox.osc.player;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * 播放侧需要的"页面能力"(播放服务化 Spec §2.1,`skill/avbox-playback-service-spec.md`)。
 *
 * <p>动机:改造前 `PlayContainer` 直接持 `Activity` 并用 `instanceof DetailActivity` 回调页面
 * (本地字幕选择器 / 线路耗尽后的换源兜底)。播放层搬到服务后不再允许依赖具体 Activity,
 * 因此把这些能力抽成接口,由页面实现(`DetailActivity implements PageHost`),服务侧只认它。
 *
 * <p>P0 阶段仅收口已有耦合点(行为等价);P2 起 `PageHost` 注册改为弱引用,页面销毁即失效。
 */
public interface PageHost {

    /** 页面上下文(仅用于 Popup/Toast/选择器等需要 Activity 的场合;禁止长期持有) */
    @NonNull
    Context context();

    /** 页面是否仍存活(未 finish/未销毁)—— 对应改造前的 `mActivity != null && !isFinishing()` */
    boolean isPageAlive();

    /** 主线程执行(已销毁时静默丢弃) */
    void runOnUi(@NonNull Runnable action);

    /** 轻提示 */
    void toast(@NonNull CharSequence text);

    /** 打开系统文件选择器挑本地字幕(SAF,结果回调到播放侧的 onLocalSubtitlePicked) */
    void launchLocalSubtitlePicker();

    /** 申请通知权限(媒体通知兜底,已授权时调用无副作用) */
    void requestNotificationPermission();

    /**
     * 打开页面的选集面板(播放器底栏"选集"入口)。
     *
     * <p>选集内容与切集逻辑属于详情页(线路切换、分组、倒序都在那边),播放层不自建一套;
     * 页面无详情数据(直播等)时静默忽略。
     */
    void showEpisodeSheet();

    /**
     * 线路耗尽后的"换源兜底"入口(旧 `DetailActivity.startDetailFallbackAfterLinesExhausted`)。
     *
     * @return 是否已接管后续换源(交给页面详情引擎)
     */
    boolean onPlaybackLinesExhausted();

    /**
     * **页面侧此刻是否还在为这部片找别的源**(2026-10-18)。
     *
     * <p>用途:内核报错时决定给用户看哪句话。真机《怦然心动》的毛病是 ——
     * 换源链还在正常跑(9 个候选、剩余 211 个源还没问),播放器却已经把
     * 「视频播放出错」糊到屏幕上了。用户看到的是一个裸错误,而系统其实还在干活。
     *
     * <p>返回 true ⇒ 播放层说「正在查找其他片源…」;
     * 返回 false ⇒ 页面已确认无源可用,播放层说「该影片暂无有效片源」。
     *
     * <p>默认实现返回 false(直播等无详情引擎的页面没有"换源"这个概念)。
     */
    default boolean isSearchingOtherSources() {
        return false;
    }
}
